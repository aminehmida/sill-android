package me.saffer.sill.android.ui.input

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * A scroll's coast after the fingers lift, made on the device (InputOverlay's ScrollMomentum): it
 * starts only above 150 dp/s, its first step v/60 goes out at once, then each frame v *= 0.96^(dt·60)
 * and the step is v·dt, until a step would be under 0.3 dp. The Mac sees momentumBegan, the steps,
 * then momentumEnded, so apps rubber-band and fling as with a real trackpad.
 */
class ScrollMomentum(
    private val density: Float,
    private val onBegin: () -> Unit,
    private val onStep: (dxPx: Float, dyPx: Float) -> Unit,
    private val onEnd: () -> Unit,
) : Choreographer.FrameCallback {
    private var vx = 0f
    private var vy = 0f
    private var lastNanos = 0L
    var running = false
        private set

    /** Velocity in px/s. Returns whether a coast started. */
    fun start(velocityX: Float, velocityY: Float): Boolean {
        stop()
        if (hypot(velocityX, velocityY) / density < 150f) return false
        vx = velocityX; vy = velocityY
        running = true
        onBegin()
        onStep(vx / 60f, vy / 60f)
        lastNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
        return true
    }

    /** Always ends a running coast with momentumEnded. */
    fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        onEnd()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = System.nanoTime()
        val dt = min((now - lastNanos) / 1e9f, 0.1f)
        lastNanos = now
        val decay = 0.96f.pow(dt * 60f)
        vx *= decay; vy *= decay
        if (maxOf(abs(vx), abs(vy)) / 60f / density < 0.3f) { stop(); return }
        onStep(vx * dt, vy * dt)
        Choreographer.getInstance().postFrameCallback(this)
    }
}

/**
 * Three- and four-finger strokes (iOSClient/TrackpadGestures.swift), decided as one of the three
 * lifts: a stroke goes silent once three fingers are down before any finger moved 24 dp (so no tap,
 * click or scroll comes of it); it arms when the last three landed within 0.15 s of each other; a
 * swipe is 40 dp of the centroid (or 20 dp at 500 dp/s over the last 50 ms) with one axis 1.3× the
 * other and every finger moving along; a pinch or spread is the spread changing by a quarter.
 */
class StrokeGestures(private val density: Float) {
    private class Finger(val id: Int, val downX: Float, val downY: Float, val downAt: Long) {
        var x = downX; var y = downY
        val trail = ArrayDeque<Triple<Long, Float, Float>>()
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var anyMoved = false
    var silent = false
        private set
    private var armedIds: List<Int>? = null
    private var decided = false
    private var extraMoved = 0

    fun reset() {
        fingers.clear(); anyMoved = false; silent = false; armedIds = null; decided = false; extraMoved = 0
    }

    fun down(id: Int, x: Float, y: Float, t: Long) {
        fingers[id] = Finger(id, x, y, t)
        if (fingers.size >= 3 && !anyMoved && !silent) {
            silent = true
            val last3 = fingers.values.sortedBy { it.downAt }.takeLast(3)
            if (fingers.size <= 4 && last3.last().downAt - last3.first().downAt <= 150) armedIds = last3.map { it.id }
        }
        if (fingers.size > 4) armedIds = null
    }

    fun move(id: Int, x: Float, y: Float, t: Long) {
        val f = fingers[id] ?: return
        f.x = x; f.y = y
        f.trail.addLast(Triple(t, x, y))
        while (f.trail.size > 1 && t - f.trail.first().first > 60) f.trail.removeFirst()
        if (hypot(x - f.downX, y - f.downY) / density > 24f) anyMoved = true
    }

    /** One finger lifts; returns (gesture name, fingers) when this lift decides one. */
    fun up(id: Int): Pair<String, Int>? {
        val armed = armedIds
        val result = if (!decided && armed != null && id in armed && armed.all { it in fingers }) {
            decided = true
            decide(armed.map { fingers[it]!! })
        } else null
        fingers.remove(id)
        return result
    }

    private fun decide(three: List<Finger>): Pair<String, Int>? {
        val cdx = three.map { it.x - it.downX }.average().toFloat()
        val cdy = three.map { it.y - it.downY }.average().toFloat()
        val travel = hypot(cdx, cdy) / density
        val others = fingers.values.count { it.id !in three.map { f -> f.id } && hypot(it.x - it.downX, it.y - it.downY) / density > 10f }
        val count = minOf(4, 3 + others)
        // Speed of the centroid over the last 50 ms.
        val speed = three.map { f ->
            val first = f.trail.firstOrNull() ?: return@map 0f
            val last = f.trail.lastOrNull() ?: return@map 0f
            val dt = (last.first - first.first).coerceAtLeast(1)
            hypot(last.second - first.second, last.third - first.third) / density / (dt / 1000f)
        }.average().toFloat()
        val swipeTravel = travel >= 40f || (travel >= 20f && speed >= 500f)
        val horizontal = kotlin.math.abs(cdx) >= 1.3f * kotlin.math.abs(cdy)
        val vertical = kotlin.math.abs(cdy) >= 1.3f * kotlin.math.abs(cdx)
        val together = three.all { f ->
            val dx = f.x - f.downX; val dy = f.y - f.downY
            val along = (dx * cdx + dy * cdy) / maxOf(1e-3f, hypot(cdx, cdy))
            along >= 0.5f * hypot(cdx, cdy)
        }
        if (swipeTravel && together && (horizontal || vertical)) {
            val name = if (horizontal) (if (cdx < 0) "swipeLeft" else "swipeRight") else (if (cdy < 0) "swipeUp" else "swipeDown")
            return name to count
        }
        fun spread(points: List<Pair<Float, Float>>): Float {
            val cx = points.map { it.first }.average().toFloat(); val cy = points.map { it.second }.average().toFloat()
            return points.map { hypot(it.first - cx, it.second - cy) }.average().toFloat()
        }
        val before = spread(three.map { it.downX to it.downY })
        val after = spread(three.map { it.x to it.y })
        if (before > 1f) {
            val ratio = after / before
            if (ratio <= 0.75f) return "pinch" to count
            if (ratio >= 1.25f) return "spread" to count
        }
        return null
    }

    val count get() = fingers.size
}
