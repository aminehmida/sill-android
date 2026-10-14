package me.saffer.sill.android.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.protocol.PointerAction
import me.saffer.sill.android.protocol.ScrollPhase
import kotlin.math.hypot

/**
 * The relative trackpad of the upright layout (iOSClient/TrackpadView.swift): one finger moves the
 * Mac's pointer (a full swipe across the pad is 1.25 of the frame, no acceleration), a tap clicks, a
 * two-finger tap right-clicks, a hold (0.45 s) then drag drags, two fingers scroll with a coast, three
 * or four make a Mac gesture. The pad keeps its own cursor in frame fractions and sends absolute moves.
 * The latched modifiers of the key row wrap a click or a drag.
 */
@SuppressLint("ViewConstructor")
class TrackpadView(context: Context, private val client: SillClient) : View(context) {
    /** The vertical span a full swipe covers: a phone's pad is taller than the picture it drives. */
    var verticalSpan: Float? = null
    /** The key row's latched modifiers, spent by one click or drag. */
    var latched: () -> Long = { 0L }
    var spendLatch: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val slop = 2f * density
    private val tapSlop = 10f * density
    private val handler = Handler(Looper.getMainLooper())
    private val strokes = StrokeGestures(density)
    private var velocity: VelocityTracker? = null

    private var cx = 0.5; private var cy = 0.5
    private var seenReseeds = -1
    private var downAt = 0L
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var dragging = false
    private var dragMods = 0L
    private var maxFingers = 0
    private var twoFingerScroll = false
    private var silent = false
    private var trackingId = -1

    /** Where a coast scrolls: the cursor where the scroll ended, whatever the pointer does after. */
    private var coastX = 0.5
    private var coastY = 0.5

    private val momentum = ScrollMomentum(density,
        onBegin = { client.scrollPhase(ScrollPhase.MOMENTUM_BEGAN, coastX, coastY) },
        onStep = { dx, dy -> client.scroll(coastX, coastY, (dx / width).toDouble(), (dy / ySpan()).toDouble()) },
        onEnd = { client.scrollPhase(ScrollPhase.MOMENTUM_ENDED, coastX, coastY) },
    )

    /** A drag that ends without a lift (a cancel, the view going away): the button and the latched modifiers come up. */
    private fun endDrag() {
        if (!dragging) return
        dragging = false
        client.pointer(PointerAction.LEFT_UP, cx, cy, trackpad = true)
        if (dragMods != 0L) { client.modifiersUp(dragMods); spendLatch() }
        dragMods = 0L
    }

    private val hold = Runnable {
        if (moved || maxFingers != 1 || silent) return@Runnable
        catchUp()
        dragging = true
        dragMods = latched()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (dragMods != 0L) client.modifiersDown(dragMods)
        client.pointer(PointerAction.LEFT_DOWN, cx, cy, trackpad = true)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setBackgroundColor(0x00000000)
    }

    private fun ySpan(): Float = verticalSpan ?: height.toFloat()

    /** Carry on from where the Mac or another device left the pointer (PadCursor.catchUp). */
    private fun catchUp() {
        if (client.pointerReseeds != seenReseeds) {
            seenReseeds = client.pointerReseeds
            val a = client.pointerAnchor()
            cx = a.x.toDouble(); cy = a.y.toDouble()
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        catchUp()
        cx = (cx + dx / width * 1.25).coerceIn(0.0, 1.0)
        cy = (cy + dy / ySpan() * 1.25).coerceIn(0.0, 1.0)
        client.pointer(PointerAction.MOVE, cx, cy, trackpad = true)
        seenReseeds = client.pointerReseeds
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        val t = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                momentum.stop()
                strokes.reset()
                strokes.down(e.getPointerId(0), e.x, e.y, t)
                downAt = t; downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; dragging = false; maxFingers = 1; twoFingerScroll = false; silent = false
                trackingId = e.getPointerId(0)
                catchUp()
                handler.postDelayed(hold, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                strokes.down(e.getPointerId(i), e.getX(i), e.getY(i), t)
                maxFingers = maxOf(maxFingers, e.pointerCount)
                handler.removeCallbacks(hold)
                // Two fingers landing stop a coast (TrackpadView: a new scroll never overlaps the last one's).
                if (e.pointerCount >= 2) momentum.stop()
                if (strokes.silent) {
                    if (twoFingerScroll) { client.scrollPhase(ScrollPhase.ENDED, cx, cy); twoFingerScroll = false }
                    silent = true
                }
                val (mx, my) = centroid(e, -1); lastX = mx; lastY = my
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) strokes.move(e.getPointerId(i), e.getX(i), e.getY(i), t)
                if (silent) return true
                if (e.pointerCount >= 2) {
                    val (mx, my) = centroid(e, -1)
                    if (!twoFingerScroll) {
                        if (hypot(mx - lastX, my - lastY) < tapSlop && !moved) return true
                        moved = true
                        catchUp()
                        twoFingerScroll = true
                        client.scrollPhase(ScrollPhase.BEGAN, cx, cy)
                    }
                    client.scroll(cx, cy, ((mx - lastX) / width).toDouble(), ((my - lastY) / ySpan()).toDouble())
                    lastX = mx; lastY = my
                } else {
                    val idx = e.findPointerIndex(trackingId).takeIf { it >= 0 } ?: 0
                    val x = e.getX(idx); val y = e.getY(idx)
                    if (!moved) {
                        if (hypot(x - downX, y - downY) < slop) return true
                        if (hypot(x - downX, y - downY) >= tapSlop) { moved = true; if (!dragging) handler.removeCallbacks(hold) }
                    }
                    moveBy(x - lastX, y - lastY)
                    lastX = x; lastY = y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                strokes.up(e.getPointerId(i))?.let { (name, fingers) ->
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    client.gesture(name, fingers)
                }
                if (twoFingerScroll && e.pointerCount - 1 < 2) endScroll(coast = true)
                val remaining = (0 until e.pointerCount).firstOrNull { it != i }
                if (remaining != null) {
                    trackingId = e.getPointerId(remaining)
                    if (e.pointerCount - 1 >= 2) { val (mx, my) = centroid(e, i); lastX = mx; lastY = my }
                    else { lastX = e.getX(remaining); lastY = e.getY(remaining) }
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                strokes.up(e.getPointerId(0))?.let { (name, fingers) -> client.gesture(name, fingers) }
                val quick = t - downAt < 450
                when {
                    dragging -> endDrag()
                    twoFingerScroll -> endScroll(coast = true)
                    silent -> Unit
                    !moved && quick && maxFingers == 1 -> click(PointerAction.LEFT_DOWN, PointerAction.LEFT_UP)
                    !moved && maxFingers == 2 -> click(PointerAction.RIGHT_DOWN, PointerAction.RIGHT_UP)
                }
                dragging = false; twoFingerScroll = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                endDrag()
                if (twoFingerScroll) endScroll(coast = false)
                dragging = false; twoFingerScroll = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun click(down: PointerAction, up: PointerAction) {
        catchUp()
        val mods = latched()
        if (mods != 0L) client.modifiersDown(mods)
        client.pointer(down, cx, cy, trackpad = true)
        client.pointer(up, cx, cy, trackpad = true)
        if (mods != 0L) { client.modifiersUp(mods); spendLatch() }
    }

    private fun endScroll(coast: Boolean) {
        twoFingerScroll = false
        client.scrollPhase(ScrollPhase.ENDED, cx, cy)
        if (coast) {
            coastX = cx; coastY = cy
            velocity?.computeCurrentVelocity(1000)
            momentum.start(velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f)
        }
    }

    private fun centroid(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) e.x to e.y else sx / n to sy / n
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A quiet hint at the pad's centre, as the iOS pad's.
        paint.color = 0x14FFFFFF
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f * density, 18f * density, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        momentum.stop()
        handler.removeCallbacks(hold)
        // A rotation swaps the layout mid-stroke: nothing stays held on the Mac.
        endDrag()
        if (twoFingerScroll) endScroll(coast = false)
    }
}
