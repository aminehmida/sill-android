package me.saffer.sill.android.ui.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The picture's zoom for one stream screen (iOS's StreamZoomModel): held by the stream screen so a turn
 * keeps it, read by the touch view for every touch and written while two fingers pinch or move the
 * picture; the picture's layer reads it while drawing, so a pinch redraws the picture's transform and
 * nothing else.
 */
class StreamZoomState {
    var zoom by mutableStateOf(StreamZoom())
        private set
    private var source: Any? = null
    private var seenSource = false

    fun set(z: StreamZoom) { if (z != zoom) zoom = z }

    /** The whole picture. */
    fun reset() = set(zoom.reset())

    /** The picture's pane is another size: the zoom stays on the same point of the picture. */
    fun paneResized(w: Double, h: Double) = set(zoom.resized(w, h))

    /**
     * What streams, as each layout's picture sees it: another source, whoever picked it, is the whole
     * picture again (StreamScreen's `.onChange(of: client.active)`); the same one seen again (a turn's
     * other layout) keeps the zoom.
     */
    fun follow(streaming: Any) {
        if (seenSource && streaming != source) reset()
        source = streaming
        seenSource = true
    }
}
