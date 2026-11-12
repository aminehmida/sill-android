package me.saffer.sill.android.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.protocol.PointerAction
import me.saffer.sill.android.protocol.ScrollDirection
import me.saffer.sill.android.protocol.ScrollPhase
import kotlin.math.hypot

/**
 * Touch on the picture itself (iOSClient/InputOverlay.swift). Fingers: a tap clicks where it lands,
 * a long press (0.45 s) right-clicks, a pan with one or two fingers scrolls (with the Mac's gesture
 * phases and a coast after), three or four fingers make a Mac gesture. Two fingers that pinch, and any
 * two on a zoomed picture, are the picture's: they zoom it up to 5× and move it ([StreamZoom],
 * [ZoomStroke]), and send nothing. A mouse, a trackpad or the S Pen is the Mac's pointer: down and up are
 * the buttons (the secondary one a right click), moves drag, hover moves, a wheel or a trackpad's two
 * fingers scroll the window under the pointer. Positions go out as fractions of the video frame, taken back
 * through the zoom, so everything lands on what is shown under the finger; a tap in the letterbox bars is
 * dropped, moves are clamped. Reverse Scrolling turns a finger's scroll and its coast around, never a
 * wheel's or a trackpad's (ScrollDirection).
 */
@SuppressLint("ViewConstructor")
class DirectTouchView(context: Context, private val client: SillClient) : View(context) {
    /** The video's rect in this view's coordinates, at 1×; empty while there is no picture. */
    var videoRect = RectF()

    /** The picture's zoom, the stream screen's; null keeps one here. */
    var zoomState: StreamZoomState? = null
    private var ownZoom = StreamZoom()
    private var zoom: StreamZoom
        get() = zoomState?.zoom ?: ownZoom
        set(value) { zoomState?.set(value) ?: run { ownZoom = value } }

    /** A touch landed on the picture; true when it closed a panel, and then it clicks nothing (iOS's tap catcher). */
    var onTouchedPicture: (() -> Boolean)? = null
    private var swallowing = false
    private var lastPointer = 0.5 to 0.5

    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private val handler = Handler(Looper.getMainLooper())
    private val strokes = StrokeGestures(density)
    private val zoomStroke = ZoomStroke(pinchTravel = 12.0 * density, minSpacing = 1.0 * density)
    private var velocity: VelocityTracker? = null

    /** What a stroke is, decided as its first touch lands. */
    private enum class Kind { FINGER, PAD_SCROLL, POINTER, IGNORED }
    private var kind = Kind.FINGER

    private enum class State { IDLE, POSSIBLE, SCROLLING, DONE, SILENT, PICTURE }
    private var state = State.IDLE
    /** A second finger landed: no tap comes of the stroke (iOS's tap takes one touch). */
    private var tapAllowed = true
    private var downX = 0f; private var downY = 0f
    private var lastCx = 0f; private var lastCy = 0f
    private var scrollAt = 0.5 to 0.5
    /** -1 while Reverse Scrolling turns the scroll in progress around, else 1: read as each scroll begins. */
    private var scrollFactor = 1.0
    /** An old touchpad's fingers (before Android 14's gestures): scrolled as a finger's, never reversed or zoomed. */
    private var fingersFromTouchpad = false
    private var pointerButton: PointerAction? = null
    /** Each finger of the stroke, by its pointer id, to its number as it landed (the zoom's rule counts by those). */
    private val strokeIds = HashMap<Int, Int>()
    private var nextStrokeId = 0

    private val momentum = ScrollMomentum(density,
        onBegin = { client.scrollPhase(ScrollPhase.MOMENTUM_BEGAN, scrollAt.first, scrollAt.second) },
        onStep = { dx, dy -> sendScroll(dx, dy, 1.0) },
        onEnd = { client.scrollPhase(ScrollPhase.MOMENTUM_ENDED, scrollAt.first, scrollAt.second) },
    )

    private val longPress = Runnable {
        if (state != State.POSSIBLE || !tapAllowed) return@Runnable
        val p = fraction(downX, downY, clamped = false) ?: return@Runnable
        state = State.DONE
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        client.pointer(PointerAction.MOVE, p.first, p.second)
        client.pointer(PointerAction.RIGHT_DOWN, p.first, p.second)
        client.pointer(PointerAction.RIGHT_UP, p.first, p.second)
    }

    init {
        isFocusable = false
    }

    private val paneW get() = width.toDouble()
    private val paneH get() = height.toDouble()

    /** A point of this view as a fraction of the video frame, through the zoom. */
    private fun fraction(x: Float, y: Float, clamped: Boolean): Pair<Double, Double>? {
        val r = videoRect
        if (r.width() <= 0f || r.height() <= 0f) return null
        val u = zoom.unzoomed(Pt(x.toDouble(), y.toDouble()), paneW, paneH)
        var fx = (u.x - r.left) / r.width()
        var fy = (u.y - r.top) / r.height()
        if (clamped) { fx = fx.coerceIn(0.0, 1.0); fy = fy.coerceIn(0.0, 1.0) }
        else if (fx !in 0.0..1.0 || fy !in 0.0..1.0) return null
        return fx to fy
    }

    /** A scroll's movement on screen (px), at `scrollAt`: back through the zoom, as frame fractions, times `factor`. */
    private fun sendScroll(dxPx: Float, dyPx: Float, factor: Double) {
        val r = videoRect
        if (r.width() <= 0f || r.height() <= 0f) return
        val d = zoom.unzoomedDelta(dxPx.toDouble(), dyPx.toDouble())
        val dx = d.x / r.width() * factor
        val dy = d.y / r.height() * factor
        if (dx == 0.0 && dy == 0.0) return
        client.scroll(scrollAt.first, scrollAt.second, dx, dy)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = onTouchedPicture?.invoke() == true
            kind = kindOf(e)
        }
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        return when (kind) {
            Kind.FINGER -> fingerEvent(e)
            Kind.PAD_SCROLL -> padScrollEvent(e)
            Kind.POINTER -> pointerEvent(e)
            Kind.IGNORED -> true
        }
    }

    /**
     * What a stroke is, by its first touch: a finger on the glass; a mouse, the S Pen, or a touchpad's
     * click and drag (the pointer); a touchpad's two-finger scroll (Android 14's gesture, which arrives as
     * a finger dragged from the pointer); or a touchpad's three-finger swipe or pinch, which is the
     * system's (nothing here: iOS takes no trackpad pinch either).
     */
    private fun kindOf(e: MotionEvent): Kind {
        fingersFromTouchpad = false
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_FINGER) return Kind.POINTER
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) return Kind.FINGER
        if (Build.VERSION.SDK_INT >= 34) {
            return when (e.classification) {
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> Kind.PAD_SCROLL
                CLASSIFICATION_MULTI_FINGER_SWIPE, MotionEvent.CLASSIFICATION_PINCH -> Kind.IGNORED
                else -> Kind.POINTER
            }
        }
        // An older touchpad's gestures: as before, a finger's taps and scrolls, at the pointer.
        if (e.buttonState != 0) return Kind.POINTER
        fingersFromTouchpad = true
        return Kind.FINGER
    }

    // MARK: fingers

    private fun strokeId(pointerId: Int): Int = strokeIds.getOrPut(pointerId) { nextStrokeId++ }

    private fun pt(e: MotionEvent, i: Int) = Pt(e.getX(i).toDouble(), e.getY(i).toDouble())

    /** Every finger of the stroke for the zoom's rule; nothing zooms before there is a picture. */
    private fun zoomMoved(e: MotionEvent) {
        if (fingersFromTouchpad) return
        val moves = HashMap<Int, Pt>()
        for (i in 0 until e.pointerCount) strokeIds[e.getPointerId(i)]?.let { moves[it] = pt(e, i) }
        val z = zoom
        val next = zoomStroke.moved(moves, z, paneW, paneH)
        if (next != z && !videoRect.isEmpty) zoom = next
    }

    private fun zoomUp(pointerId: Int) {
        val id = strokeIds.remove(pointerId) ?: return
        if (fingersFromTouchpad) return
        val z = zoom
        val next = zoomStroke.up(id, z)
        if (next != z) zoom = next
    }

    private fun fingerEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        val t = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Any new touch stops a coasting flick, the way touching a moving list does.
                momentum.stop()
                strokes.reset()
                // A stroke whose end never came (no up, no cancel): its fingers lift now.
                for (pid in strokeIds.keys.toList()) zoomUp(pid)
                strokeIds.clear()
                strokes.down(e.getPointerId(0), e.x, e.y, t)
                if (!fingersFromTouchpad) zoomStroke.down(strokeId(e.getPointerId(0)), pt(e, 0), zoom)
                state = State.POSSIBLE
                tapAllowed = true
                downX = e.x; downY = e.y
                lastCx = e.x; lastCy = e.y
                handler.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                tapAllowed = false
                strokes.down(e.getPointerId(i), e.getX(i), e.getY(i), t)
                handler.removeCallbacks(longPress)
                if (strokes.silent && state != State.SILENT) silence()
                if (!fingersFromTouchpad) zoomStroke.down(strokeId(e.getPointerId(i)), pt(e, i), zoom)
                if (state == State.POSSIBLE && zoomStroke.owner == ZoomStroke.Owner.PICTURE) state = State.PICTURE
                if (state != State.SILENT) { val (cx, cy) = centroid(e, -1); lastCx = cx; lastCy = cy }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) strokes.move(e.getPointerId(i), e.getX(i), e.getY(i), t)
                zoomMoved(e)
                if (state == State.SILENT || state == State.DONE || state == State.IDLE || state == State.PICTURE) return true
                if (state == State.POSSIBLE && zoomStroke.owner == ZoomStroke.Owner.PICTURE) {
                    handler.removeCallbacks(longPress)
                    state = State.PICTURE
                    return true
                }
                val (cx, cy) = centroid(e, -1)
                if (state == State.POSSIBLE) {
                    if (e.pointerCount == 1 && hypot(e.x - downX, e.y - downY) < slop) return true
                    if (e.pointerCount > 1 && hypot(cx - lastCx, cy - lastCy) < slop) return true
                    handler.removeCallbacks(longPress)
                    // The finger pan begins: the Mac's scroll, unless the stroke is the picture's.
                    if (!fingersFromTouchpad) zoomStroke.panBegan()
                    if (!fingersFromTouchpad && !zoomStroke.scrollsMac) { state = State.PICTURE; return true }
                    state = State.SCROLLING
                    scrollFactor = ScrollDirection.factor(
                        if (fingersFromTouchpad) ScrollDirection.Source.TRACKPAD else ScrollDirection.Source.FINGER, client.reverseScroll)
                    scrollAt = fraction(cx, cy, clamped = true) ?: (0.5 to 0.5)
                    client.scrollPhase(ScrollPhase.BEGAN, scrollAt.first, scrollAt.second)
                }
                scrollAt = fraction(cx, cy, clamped = true) ?: scrollAt
                sendScroll(cx - lastCx, cy - lastCy, scrollFactor)
                lastCx = cx; lastCy = cy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                strokes.up(e.getPointerId(i))?.let { (name, fingers) ->
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    client.gesture(name, fingers)
                }
                zoomUp(e.getPointerId(i))
                if (state != State.SILENT) { val (cx, cy) = centroid(e, i); lastCx = cx; lastCy = cy }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                strokes.up(e.getPointerId(0))?.let { (name, fingers) -> client.gesture(name, fingers) }
                zoomUp(e.getPointerId(0))
                when (state) {
                    State.POSSIBLE -> if (tapAllowed) tap(e.x, e.y)
                    State.SCROLLING -> {
                        client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f; val vy = velocity?.yVelocity ?: 0f
                        // The coast goes the way the scroll went.
                        momentum.start((vx * scrollFactor).toFloat(), (vy * scrollFactor).toFloat())
                    }
                    else -> Unit
                }
                endStroke()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                // The system took the touch: close the scroll, no coast; the zoom's fingers are gone.
                if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                for (pid in strokeIds.keys.toList()) zoomUp(pid)
                endStroke()
            }
        }
        return true
    }

    private fun endStroke() {
        state = State.IDLE
        strokeIds.clear()
        velocity?.recycle(); velocity = null
    }

    /**
     * Three fingers down before any moved (StrokeGestures): a scroll the stroke had begun ends here, with
     * no coast; nothing else of it goes out but its gesture, and a picture its first two fingers had
     * pinched goes back to how the stroke found it.
     */
    private fun silence() {
        if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
        state = State.SILENT
        if (fingersFromTouchpad) return
        val z = zoom
        val back = zoomStroke.silenced(z)
        if (back != z) zoom = back
    }

    private fun tap(x: Float, y: Float) {
        val p = fraction(x, y, clamped = false) ?: return
        client.pointer(PointerAction.MOVE, p.first, p.second)
        client.pointer(PointerAction.LEFT_DOWN, p.first, p.second)
        client.pointer(PointerAction.LEFT_UP, p.first, p.second)
    }

    /** The centroid of the fingers still down, leaving out `skip` (a finger lifting). */
    private fun centroid(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sx += e.getX(i); sy += e.getY(i); n++ }
        return if (n == 0) e.x to e.y else sx / n to sy / n
    }

    // MARK: a touchpad's two-finger scroll (Android 14 and later)

    /**
     * A touchpad's two fingers (or a mouse with a scroll gesture) over the picture: Android hands it over
     * as one finger dragged from the pointer, already turned the way the touchpad's own scrolling setting
     * says. The whole scroll goes where the pointer was as it began (PointerScroll), with the Mac's phases
     * and a coast after the lift, never reversed by Reverse Scrolling.
     */
    private fun padScrollEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                momentum.stop()
                scrollFactor = ScrollDirection.factor(ScrollDirection.Source.TRACKPAD, client.reverseScroll)
                scrollAt = fraction(e.x, e.y, clamped = true) ?: (0.5 to 0.5)
                lastCx = e.x; lastCy = e.y
                state = State.SCROLLING
                client.scrollPhase(ScrollPhase.BEGAN, scrollAt.first, scrollAt.second)
            }
            MotionEvent.ACTION_MOVE -> if (state == State.SCROLLING) {
                sendScroll(e.x - lastCx, e.y - lastCy, scrollFactor)
                lastCx = e.x; lastCy = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (state == State.SCROLLING) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) sendScroll(e.x - lastCx, e.y - lastCy, scrollFactor)
                    client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        velocity?.computeCurrentVelocity(1000)
                        momentum.start(((velocity?.xVelocity ?: 0f) * scrollFactor).toFloat(), ((velocity?.yVelocity ?: 0f) * scrollFactor).toFloat())
                    }
                }
                endStroke()
            }
        }
        return true
    }

    // MARK: the pointer

    /** A mouse, a trackpad's pointer or the S Pen: the Mac's pointer, buttons and all. */
    private fun pointerEvent(e: MotionEvent): Boolean {
        val p = fraction(e.x, e.y, clamped = true) ?: return true
        lastPointer = p
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                momentum.stop()
                val secondary = e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                if (fraction(e.x, e.y, clamped = false) == null) return true
                val down = if (secondary) PointerAction.RIGHT_DOWN else PointerAction.LEFT_DOWN
                pointerButton = down
                client.pointer(PointerAction.MOVE, p.first, p.second)
                client.pointer(down, p.first, p.second)
            }
            MotionEvent.ACTION_MOVE -> client.pointer(PointerAction.MOVE, p.first, p.second)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val down = pointerButton ?: return true
                pointerButton = null
                client.pointer(if (down == PointerAction.RIGHT_DOWN) PointerAction.RIGHT_UP else PointerAction.LEFT_UP, p.first, p.second)
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val p = fraction(e.x, e.y, clamped = true) ?: return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> { client.pointer(PointerAction.MOVE, p.first, p.second); return true }
            MotionEvent.ACTION_BUTTON_PRESS -> if (e.actionButton == MotionEvent.BUTTON_SECONDARY && pointerButton == null) {
                pointerButton = PointerAction.RIGHT_DOWN
                client.pointer(PointerAction.MOVE, p.first, p.second)
                client.pointer(PointerAction.RIGHT_DOWN, p.first, p.second)
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> if (e.actionButton == MotionEvent.BUTTON_SECONDARY && pointerButton == PointerAction.RIGHT_DOWN) {
                pointerButton = null
                client.pointer(PointerAction.RIGHT_UP, p.first, p.second)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                // A wheel (or an older touchpad's scroll): plain deltas at the pointer, no phases and no
                // coast, never reversed (Android has turned them the way its own setting says). One scroll at
                // a time: none while a finger's or a touchpad's gesture is open. A notch is about 40 dp of
                // finger travel, natural direction like the pan, back through the zoom.
                if (state == State.SCROLLING) return true
                momentum.stop()
                scrollAt = p
                val factor = ScrollDirection.factor(ScrollDirection.Source.WHEEL, client.reverseScroll)
                sendScroll(
                    -e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 40f * density,
                    e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 40f * density,
                    factor,
                )
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private companion object {
        /** A touchpad's three- or four-finger swipe (MotionEvent's, hidden from the SDK: 4 since Android 14). */
        const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        momentum.stop()
        handler.removeCallbacks(longPress)
        // A rotation swaps the layout mid-stroke: close the scroll, let a mouse or pen button go, and the
        // zoom's fingers are gone (the zoom itself is the stream screen's and stays).
        if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
        for (pid in strokeIds.keys.toList()) zoomUp(pid)
        endStroke()
        pointerButton?.let { down ->
            pointerButton = null
            client.pointer(if (down == PointerAction.RIGHT_DOWN) PointerAction.RIGHT_UP else PointerAction.LEFT_UP, lastPointer.first, lastPointer.second)
        }
    }
}
