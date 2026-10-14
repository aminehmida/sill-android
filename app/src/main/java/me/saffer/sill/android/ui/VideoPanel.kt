package me.saffer.sill.android.ui

import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.ui.input.DirectTouchView
import kotlin.math.roundToInt

/** The video aspect-fit into the panel, centred, in pixels. */
fun fitRect(panel: IntSize, video: Pair<Int, Int>?): RectF? {
    val (vw, vh) = video ?: return null
    if (panel.width <= 0 || panel.height <= 0 || vw <= 0 || vh <= 0) return null
    val s = minOf(panel.width / vw.toFloat(), panel.height / vh.toFloat())
    val w = vw * s; val h = vh * s
    val l = (panel.width - w) / 2f; val t = (panel.height - h) / 2f
    return RectF(l, t, l + w, t + h)
}

/**
 * The stream panel (StreamScreen's picture with HEVCDisplayView and InputOverlay): the decoder's
 * SurfaceView at the picture's aspect, the touch overlay over the whole panel, and the one pointer
 * sprite. Reports its size as the Viewport (dp, the Mac's "device points").
 */
@Composable
fun VideoPanel(client: SillClient, modifier: Modifier, reportSize: Boolean = true, touch: Boolean = true, onTouched: () -> Boolean = { false }) {
    val density = LocalDensity.current
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val rect = fitRect(panel, client.videoSize)
    Box(
        modifier.clipToBounds().onSizeChanged { size ->
            panel = size
            if (reportSize && size.width > 0) with(density) { client.setPanel(size.width.toDp().value.toDouble(), size.height.toDp().value.toDouble()) }
        },
    ) {
        if (rect != null) {
            val w = with(density) { rect.width().toDp() }
            val h = with(density) { rect.height().toDp() }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = client.decoder.setSurface(holder.surface).let { }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = client.decoder.setSurface(holder.surface).let { }
                            override fun surfaceDestroyed(holder: SurfaceHolder) = client.decoder.detachSurfaceNow()
                        })
                    }
                },
                modifier = Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }.size(w, h),
            )
        }
        if (touch) {
            AndroidView(
                factory = { ctx -> DirectTouchView(ctx, client) },
                update = { v -> v.videoRect = rect ?: RectF(); v.onTouchedPicture = onTouched },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PointerSprite(client, rect)
    }
}

/**
 * The one pointer sprite (PointerPresence): the Mac's arrow, in the Mac's cursor shape, while the
 * Mac or another device moved it last; this device's own only after the trackpad moved it.
 */
@Composable
private fun PointerSprite(client: SillClient, rect: RectF?) {
    rect ?: return
    val at: Offset = when {
        !client.pointerHere -> client.macPointer ?: return
        client.ownPointerVisible -> client.ownPointer
        else -> return
    }
    val sprite = client.cursor
    Canvas(Modifier.fillMaxSize()) {
        val x = rect.left + at.x * rect.width()
        val y = rect.top + at.y * rect.height()
        if (sprite != null && sprite.width > 0 && sprite.height > 0) {
            val w = sprite.width.dp.toPx(); val h = sprite.height.dp.toPx()
            val left = x - sprite.hotX / sprite.width * w
            val top = y - sprite.hotY / sprite.height * h
            drawImage(
                sprite.image,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            )
        } else {
            // A plain arrow, its tip at the point (about 20 dp tall).
            val s = 1.dp.toPx()
            val p = Path().apply {
                moveTo(x, y); lineTo(x, y + 18 * s); lineTo(x + 4.5f * s, y + 13.5f * s); lineTo(x + 8 * s, y + 20 * s)
                lineTo(x + 10.5f * s, y + 19 * s); lineTo(x + 7.5f * s, y + 12.5f * s); lineTo(x + 13 * s, y + 12.5f * s); close()
            }
            drawPath(p, Color.Black)
            drawPath(p, Color.White, style = Stroke(width = 1.5f * s))
        }
    }
}
