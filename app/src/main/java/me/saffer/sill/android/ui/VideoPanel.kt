package me.saffer.sill.android.ui

import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.ui.input.DirectTouchView
import me.saffer.sill.android.ui.input.Pt
import me.saffer.sill.android.ui.input.StreamZoomState
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
 * SurfaceView at the picture's aspect, scaled and moved by the picture's zoom (ZoomedStreamView) inside
 * the panel's clip, the touch overlay over the whole panel (unzoomed: it maps every touch back through the
 * zoom), the one pointer sprite at its own size where the zoom shows its point, and what an input method is
 * composing, at the top middle of the picture. Reports its size as the Viewport (dp, the Mac's "device
 * points").
 *
 * `zoom`: the picture's zoom; the stream screen passes one it holds so a turn's other layout keeps it.
 * `composing`: KeyboardSink's `composingText`.
 */
@Composable
fun VideoPanel(
    client: SillClient,
    modifier: Modifier,
    reportSize: Boolean = true,
    touch: Boolean = true,
    onTouched: () -> Boolean = { false },
    zoom: StreamZoomState = remember { StreamZoomState() },
    composing: String? = null,
) {
    val density = LocalDensity.current
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val rect = fitRect(panel, client.videoSize)
    // Another source, whoever picked it: the whole picture again.
    val streaming = client.active
    LaunchedEffect(streaming) { zoom.follow(streaming) }
    Box(
        modifier.clipToBounds().onSizeChanged { size ->
            panel = size
            zoom.paneResized(size.width.toDouble(), size.height.toDouble())
            if (reportSize && size.width > 0) with(density) { client.setPanel(size.width.toDp().value.toDouble(), size.height.toDp().value.toDouble()) }
        },
    ) {
        if (rect != null) {
            val w = with(density) { rect.width().toDp() }
            val h = with(density) { rect.height().toDp() }
            // Scaled about the pane's middle, then moved: the inverse of StreamZoom's mapping. Read while
            // drawing, so a pinch changes the layer and nothing else.
            Box(Modifier.fillMaxSize().graphicsLayer {
                val z = zoom.zoom
                scaleX = z.scale.toFloat(); scaleY = z.scale.toFloat()
                translationX = z.offsetX.toFloat(); translationY = z.offsetY.toFloat()
                transformOrigin = TransformOrigin.Center
            }) {
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
        }
        if (touch) {
            AndroidView(
                factory = { ctx -> DirectTouchView(ctx, client) },
                update = { v -> v.videoRect = rect ?: RectF(); v.onTouchedPicture = onTouched; v.zoomState = zoom },
                modifier = Modifier.fillMaxSize(),
            )
        }
        PointerSprite(client, rect, zoom)
        if (rect != null && composing != null) {
            // What the input method composes: the Mac has none of it until it is chosen.
            val top = with(density) { rect.top.toDp() } + 16.dp
            Text(
                composing, fontSize = 20.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top, start = 16.dp, end = 16.dp).widthIn(max = 600.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color(0xEB2A2C31)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The one pointer sprite (PointerPresence): the Mac's arrow, in the Mac's cursor shape, while the
 * Mac or another device moved it last; this device's own only after the trackpad moved it. Where the
 * picture's zoom shows its point, at its own size.
 */
@Composable
private fun PointerSprite(client: SillClient, rect: RectF?, zoom: StreamZoomState) {
    rect ?: return
    val at: Offset = when {
        !client.pointerHere -> client.macPointer ?: return
        client.ownPointerVisible -> client.ownPointer
        else -> return
    }
    val sprite = client.cursor
    Canvas(Modifier.fillMaxSize()) {
        val shown = zoom.zoom.zoomed(
            Pt((rect.left + at.x * rect.width()).toDouble(), (rect.top + at.y * rect.height()).toDouble()),
            size.width.toDouble(), size.height.toDouble(),
        )
        val x = shown.x.toFloat()
        val y = shown.y.toFloat()
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
