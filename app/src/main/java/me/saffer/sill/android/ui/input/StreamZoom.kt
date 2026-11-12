package me.saffer.sill.android.ui.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Pinch to zoom on the stream picture (iOSClient/StreamZoom.swift, issue #65). Pure: no Android types,
// unit tested on the JVM. Units are the pane's pixels.

/** A point in the picture's pane. */
data class Pt(val x: Double, val y: Double)

/**
 * The picture's zoom: two fingers on the picture zoom it from 1× to 5×, centred on the pinch (the
 * midpoint between the fingers) as a photo in Photos is, and move it with that midpoint; the Mac's
 * pointer is not involved. A slight pinch out leaves the picture where it is; only a pinch all the way
 * out gives back the whole picture. The zoom is this device's alone: nothing is sent.
 *
 * The picture is drawn scaled about the pane's middle, then moved by the offset, so a point `u` of the
 * pane as it is at 1× shows at `c + scale·(u − c) + offset`, `c` the pane's middle. The offset stays
 * within `±size·(scale − 1)/2` on each axis, so the zoomed picture always covers its pane. The input
 * view takes every touch back through [unzoomed] before it makes it a fraction of the frame, and a
 * scroll's movement through [unzoomedDelta], so a tap, a click, a hover and a scroll land on the pixel
 * shown under the finger.
 */
data class StreamZoom(
    val scale: Double = MIN_SCALE,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    /** The pane the offset was made in: the last pinch's, or the last [resized]. */
    val paneW: Double = 0.0,
    val paneH: Double = 0.0,
) {
    val isZoomed: Boolean get() = scale > MIN_SCALE

    /** The whole picture (the pane is remembered). */
    fun reset(): StreamZoom = copy(scale = MIN_SCALE, offsetX = 0.0, offsetY = 0.0)

    /**
     * One step of a pinch in a pane of `w`×`h`: the scale times `factor` (kept to 1×–5×), and the point
     * of the picture that was under `from` (the fingers' last midpoint) now under `to` (their midpoint
     * now), as far as the pane's edges allow. Reaching 1× is the whole picture, centred.
     */
    fun pinch(factor: Double, from: Pt, to: Pt, w: Double, h: Double): StreamZoom {
        if (w <= 0 || h <= 0 || factor <= 0 || !factor.isFinite()) return this
        val oldScale = max(MIN_SCALE, scale)
        val next = min(MAX_SCALE, max(MIN_SCALE, oldScale * factor))
        if (next <= MIN_SCALE + 0.001) return StreamZoom(paneW = w, paneH = h)
        val cx = w / 2; val cy = h / 2
        val ratio = next / oldScale
        val rawX = (to.x - cx) - (from.x - cx - offsetX) * ratio
        val rawY = (to.y - cy) - (from.y - cy - offsetY) * ratio
        val maxX = w * (next - 1) / 2
        val maxY = h * (next - 1) / 2
        return StreamZoom(next, rawX.coerceIn(-maxX, maxX), rawY.coerceIn(-maxY, maxY), w, h)
    }

    /** The stroke that zoomed is over: a picture left within [WHOLE_BELOW] of the whole is the whole one. */
    fun settle(): StreamZoom = if (scale < WHOLE_BELOW) reset() else this

    /**
     * The pane is another size (a turn, the other layout, a fold): the zoom stays, and so does the point
     * of the picture at the pane's middle (the offset in proportion, which keeps it inside the edges).
     */
    fun resized(w: Double, h: Double): StreamZoom {
        if (w <= 0 || h <= 0) return this
        if (!isZoomed || paneW <= 0 || paneH <= 0 || (paneW == w && paneH == h)) return copy(paneW = w, paneH = h)
        val maxX = w * (scale - 1) / 2
        val maxY = h * (scale - 1) / 2
        return copy(
            offsetX = (offsetX * w / paneW).coerceIn(-maxX, maxX),
            offsetY = (offsetY * h / paneH).coerceIn(-maxY, maxY),
            paneW = w, paneH = h,
        )
    }

    /** A point of the pane on screen, back to where it is in the picture at 1×. */
    fun unzoomed(p: Pt, w: Double, h: Double): Pt {
        if (!isZoomed || w <= 0 || h <= 0) return p
        val cx = w / 2; val cy = h / 2
        val s = max(MIN_SCALE, scale)
        return Pt(cx + (p.x - cx - offsetX) / s, cy + (p.y - cy - offsetY) / s)
    }

    /** Where a point of the picture at 1× shows on screen now ([unzoomed]'s inverse). */
    fun zoomed(p: Pt, w: Double, h: Double): Pt {
        if (!isZoomed || w <= 0 || h <= 0) return p
        val cx = w / 2; val cy = h / 2
        return Pt(cx + (p.x - cx) * scale + offsetX, cy + (p.y - cy) * scale + offsetY)
    }

    /** The part of the picture at 1× that shows in a pane of `w`×`h`: left, top, width, height. */
    fun visibleRect(w: Double, h: Double): DoubleArray {
        val o = unzoomed(Pt(0.0, 0.0), w, h)
        val s = max(MIN_SCALE, scale)
        return doubleArrayOf(o.x, o.y, w / s, h / s)
    }

    /**
     * A scroll's movement on screen, in the picture's pixels at 1×: the content under the fingers moves
     * as far as they do (a finger's, a trackpad's and a wheel's alike), whatever the zoom. Reverse
     * Scrolling turns it around afterwards.
     */
    fun unzoomedDelta(dx: Double, dy: Double): Pt {
        val s = max(MIN_SCALE, scale)
        return Pt(dx / s, dy / s)
    }

    companion object {
        const val MIN_SCALE = 1.0
        const val MAX_SCALE = 5.0
        /**
         * A stroke that leaves the picture under this is the whole picture again ([settle]): the 1 % or
         * so between them shows nothing more, and two fingers on a picture that looks whole would
         * otherwise move it rather than scroll the Mac.
         */
        const val WHOLE_BELOW = 1.02
    }
}

/**
 * Who a stroke on the picture belongs to (StreamZoom.Stroke). One finger is always the Mac's (a tap, a
 * hold, a drag's scroll). Two fingers at 1× are the Mac's scroll unless they pinch first: their spacing
 * changes more than their midpoint moves ([pinchTravel] before the finger pan begins; when it begins,
 * [panBegan]), and then they are the picture's. On a zoomed picture any two fingers are the picture's, as
 * in Photos: they pinch it and move it, and one finger still scrolls the Mac. Three fingers are the Mac's
 * gesture ([silenced]), and the picture goes back to how the stroke found it. Once decided, a stroke keeps
 * its owner until its last finger lifts, so a scroll never turns into a zoom halfway, and a pinch's fingers
 * never scroll the Mac when one of them lifts.
 *
 * Fed every finger on the picture, numbered as it lands, in the pane's pixels (one touch event's moves
 * together); told when the finger pan begins and when three fingers take the stroke. While the picture
 * owns the stroke, the first two fingers down drive it: the factor is their spacing now over their spacing
 * at the last step, the midpoint theirs, and from the moment they pinch it catches up from where they
 * landed, so the point of the picture that was under them stays under them.
 *
 * `pinchTravel` and `minSpacing` are iOS's 12 and 1 points; the view passes them in pixels.
 */
class ZoomStroke(private val pinchTravel: Double = 12.0, private val minSpacing: Double = 1.0) {
    enum class Owner {
        /** No finger down. */
        NONE,
        /** Fingers down, the stroke not decided. */
        UNDECIDED,
        /** The Mac's: the finger pan scrolls it. The picture stays as it is. */
        MAC,
        /** The picture's: two fingers pinch and move it. Nothing goes to the Mac. */
        PICTURE,
        /** Three fingers: a gesture for the Mac. The picture as the stroke found it. */
        GESTURE,
    }

    var owner = Owner.NONE
        private set
    /** Every finger down, where it is now. */
    private val at = LinkedHashMap<Int, Pt>()
    /** The two fingers that drive the picture, in the order they landed. */
    private var pair = listOf<Int>()
    /** Where they were when the second landed, and at the last step taken. */
    private var pairFrom = listOf<Pt>()
    private var pairLast = listOf<Pt>()
    /** The zoom as the stroke found it, and as its last step left it. */
    private var before: StreamZoom? = null
    private var made: StreamZoom? = null

    /** The finger pan may scroll the Mac: the stroke is the Mac's, or no finger of it was seen. */
    val scrollsMac: Boolean get() = owner == Owner.MAC || owner == Owner.NONE
    /** Fingers down now. */
    val fingers: Int get() = at.size

    /**
     * A finger lands. Two fingers down make the pair; on a zoomed picture they are the picture's at
     * once, and so are two down again after one of a picture's pair lifted.
     */
    fun down(id: Int, point: Pt, zoom: StreamZoom) {
        if (owner == Owner.NONE) { owner = Owner.UNDECIDED; before = zoom }
        at[id] = point
        if (!(owner == Owner.UNDECIDED || owner == Owner.PICTURE) || pair.size >= 2 || at.size != 2) return
        formPair()
        if (owner == Owner.UNDECIDED && zoom.isZoomed) owner = Owner.PICTURE
    }

    /**
     * Fingers move, one touch event's together: while the picture owns the stroke, its pair's move is
     * one step of the pinch in a pane of `w`×`h`. Returns the zoom after (the same one when unchanged).
     */
    fun moved(moves: Map<Int, Pt>, zoom: StreamZoom, w: Double, h: Double): StreamZoom {
        var seen = false
        for ((id, p) in moves) if (at.containsKey(id)) { at[id] = p; seen = true }
        if (!seen || pair.size != 2 || pair.none { moves.containsKey(it) }) return zoom
        val a = at[pair[0]] ?: return zoom
        val b = at[pair[1]] ?: return zoom
        if (owner == Owner.UNDECIDED) {
            val (spacing, travel) = change(pairFrom, listOf(a, b))
            if (spacing < pinchTravel || spacing <= travel) return zoom
            owner = Owner.PICTURE   // a pinch: caught up from where the pair landed (pairLast == pairFrom)
        }
        if (owner != Owner.PICTURE) return zoom
        val last = pairLast
        pairLast = listOf(a, b)
        val was = distance(last[0], last[1]); val now = distance(a, b)
        if (was < minSpacing || now < minSpacing) return zoom
        val next = zoom.pinch(now / was, midpoint(last[0], last[1]), midpoint(a, b), w, h)
        made = next
        return next
    }

    /**
     * The finger pan began (its fingers' midpoint moved the slop): the Mac's scroll, unless the stroke
     * is already the picture's or its two fingers' spacing has changed more than their midpoint moved.
     * A tie is the Mac's.
     */
    fun panBegan() {
        if (owner != Owner.UNDECIDED) return
        if (pair.size == 2) {
            val a = at[pair[0]]; val b = at[pair[1]]
            if (a != null && b != null) {
                val (spacing, travel) = change(pairFrom, listOf(a, b))
                if (spacing > travel) { owner = Owner.PICTURE; return }
            }
        }
        owner = Owner.MAC
    }

    /**
     * A finger lifts, or the system cancels it. One of a picture's pair lifting leaves the picture where
     * it is (two fingers down again carry on); the last finger ends the stroke, and a picture left within
     * [StreamZoom.WHOLE_BELOW] of the whole is the whole one. Returns the zoom after.
     */
    fun up(id: Int, zoom: StreamZoom): StreamZoom {
        if (at.remove(id) == null) return zoom
        if (id in pair) {
            pair = listOf(); pairFrom = listOf(); pairLast = listOf()
            if (owner == Owner.PICTURE && at.size == 2) formPair()
        }
        if (at.isNotEmpty()) return zoom
        val ended = owner
        clear()
        return if (ended == Owner.PICTURE) zoom.settle() else zoom
    }

    /**
     * Three fingers took the stroke for a gesture: the picture goes back to how the stroke found it (only
     * a zoom this stroke made: one changed meanwhile by something else stays), and the stroke's fingers do
     * nothing more here. Returns the zoom after.
     */
    fun silenced(zoom: StreamZoom): StreamZoom {
        val was = owner
        owner = Owner.GESTURE
        pair = listOf(); pairFrom = listOf(); pairLast = listOf()
        val b = before; val m = made
        if (was != Owner.PICTURE || b == null || m == null || zoom != m || b == zoom) return zoom
        return b
    }

    private fun clear() {
        owner = Owner.NONE
        at.clear()
        pair = listOf(); pairFrom = listOf(); pairLast = listOf()
        before = null; made = null
    }

    /** The two fingers down, in the order they landed. */
    private fun formPair() {
        pair = at.keys.sorted()
        pairFrom = pair.mapNotNull { at[it] }
        pairLast = pairFrom
    }

    companion object {
        /** How much two fingers' spacing changed, and how far their midpoint moved. */
        fun change(from: List<Pt>, to: List<Pt>): Pair<Double, Double> {
            if (from.size != 2 || to.size != 2) return 0.0 to 0.0
            val spacing = abs(distance(to[0], to[1]) - distance(from[0], from[1]))
            val travel = distance(midpoint(to[0], to[1]), midpoint(from[0], from[1]))
            return spacing to travel
        }

        fun distance(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)
        fun midpoint(a: Pt, b: Pt): Pt = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
    }
}
