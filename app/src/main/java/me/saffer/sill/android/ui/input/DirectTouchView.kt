package me.saffer.sill.android.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
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
 * Touch on the picture itself (iOSClient/InputOverlay.swift). Fingers: a tap clicks where it lands,
 * a long press (0.45 s) right-clicks, a pan with one or two fingers scrolls (with the Mac's gesture
 * phases and a coast after), three or four fingers make a Mac gesture. A mouse, trackpad or S Pen is
 * the Mac's pointer: down and up are the buttons, moves drag, hover moves. Positions go out as
 * fractions of the video frame; a tap in the letterbox bars is dropped, moves are clamped.
 */
@SuppressLint("ViewConstructor")
class DirectTouchView(context: Context, private val client: SillClient) : View(context) {
    /** The video's rect in this view's coordinates; empty while there is no picture. */
    var videoRect = RectF()

    /** A touch landed on the picture; true when it closed a panel, and then it clicks nothing (iOS's tap catcher). */
    var onTouchedPicture: (() -> Boolean)? = null
    private var swallowing = false
    private var lastPointer = 0.5 to 0.5

    private val density = resources.displayMetrics.density
    private val slop = 10f * density
    private val handler = Handler(Looper.getMainLooper())
    private val strokes = StrokeGestures(density)
    private var velocity: VelocityTracker? = null

    private enum class State { IDLE, POSSIBLE, SCROLLING, DONE, SILENT }
    private var state = State.IDLE
    private var downX = 0f; private var downY = 0f
    private var lastCx = 0f; private var lastCy = 0f
    private var scrollAt = 0.5 to 0.5
    private var pointerButton: PointerAction? = null

    private val momentum = ScrollMomentum(density,
        onBegin = { client.scrollPhase(ScrollPhase.MOMENTUM_BEGAN, scrollAt.first, scrollAt.second) },
        onStep = { dx, dy -> sendScroll(dx, dy) },
        onEnd = { client.scrollPhase(ScrollPhase.MOMENTUM_ENDED, scrollAt.first, scrollAt.second) },
    )

    private val longPress = Runnable {
        if (state != State.POSSIBLE) return@Runnable
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

    private fun fraction(x: Float, y: Float, clamped: Boolean): Pair<Double, Double>? {
        val r = videoRect
        if (r.width() <= 0f || r.height() <= 0f) return null
        var fx = ((x - r.left) / r.width()).toDouble()
        var fy = ((y - r.top) / r.height()).toDouble()
        if (clamped) { fx = fx.coerceIn(0.0, 1.0); fy = fy.coerceIn(0.0, 1.0) }
        else if (fx !in 0.0..1.0 || fy !in 0.0..1.0) return null
        return fx to fy
    }

    private fun sendScroll(dxPx: Float, dyPx: Float) {
        val r = videoRect
        if (r.width() <= 0f) return
        client.scroll(scrollAt.first, scrollAt.second, (dxPx / r.width()).toDouble(), (dyPx / r.height()).toDouble())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) swallowing = onTouchedPicture?.invoke() == true
        if (swallowing) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
            return true
        }
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return pointerEvent(e)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        val t = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                momentum.stop()
                strokes.reset()
                strokes.down(e.getPointerId(0), e.x, e.y, t)
                state = State.POSSIBLE
                downX = e.x; downY = e.y
                lastCx = e.x; lastCy = e.y
                handler.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                strokes.down(e.getPointerId(i), e.getX(i), e.getY(i), t)
                handler.removeCallbacks(longPress)
                if (strokes.silent) {
                    if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                    state = State.SILENT
                } else {
                    val (cx, cy) = centroid(e, -1); lastCx = cx; lastCy = cy
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) strokes.move(e.getPointerId(i), e.getX(i), e.getY(i), t)
                if (state == State.SILENT || state == State.DONE || state == State.IDLE) return true
                val (cx, cy) = centroid(e, -1)
                if (state == State.POSSIBLE) {
                    if (hypot(e.x - downX, e.y - downY) < slop && e.pointerCount == 1) return true
                    if (e.pointerCount > 1 && hypot(cx - lastCx, cy - lastCy) < slop) return true
                    handler.removeCallbacks(longPress)
                    state = State.SCROLLING
                    scrollAt = fraction(cx, cy, clamped = true) ?: (0.5 to 0.5)
                    client.scrollPhase(ScrollPhase.BEGAN, scrollAt.first, scrollAt.second)
                }
                scrollAt = fraction(cx, cy, clamped = true) ?: scrollAt
                sendScroll(cx - lastCx, cy - lastCy)
                lastCx = cx; lastCy = cy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                strokes.up(e.getPointerId(i))?.let { (name, fingers) ->
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    client.gesture(name, fingers)
                }
                if (state != State.SILENT) {
                    val (cx, cy) = centroid(e, i); lastCx = cx; lastCy = cy
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                strokes.up(e.getPointerId(0))?.let { (name, fingers) -> client.gesture(name, fingers) }
                when (state) {
                    State.POSSIBLE -> tap(e.x, e.y)
                    State.SCROLLING -> {
                        client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f; val vy = velocity?.yVelocity ?: 0f
                        momentum.start(vx, vy)
                    }
                    else -> Unit
                }
                state = State.IDLE
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
                state = State.IDLE
                velocity?.recycle(); velocity = null
            }
        }
        return true
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
                // A wheel notch is about 40 dp of finger travel; natural direction, like the pan.
                val r = videoRect
                val dx = -e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 40f * density / r.width()
                val dy = e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 40f * density / r.height()
                client.scroll(p.first, p.second, dx.toDouble(), dy.toDouble())
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        momentum.stop()
        handler.removeCallbacks(longPress)
        // A rotation swaps the layout mid-stroke: close the scroll, let a mouse or pen button go.
        if (state == State.SCROLLING) client.scrollPhase(ScrollPhase.ENDED, scrollAt.first, scrollAt.second)
        state = State.IDLE
        pointerButton?.let { down ->
            pointerButton = null
            client.pointer(if (down == PointerAction.RIGHT_DOWN) PointerAction.RIGHT_UP else PointerAction.LEFT_UP, lastPointer.first, lastPointer.second)
        }
    }
}
