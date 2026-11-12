package me.saffer.sill.android.protocol

// Pure rules shared with the Mac and the iPhone and iPad app, ported from Sources/StreamProtocol
// (Viewport.swift's ViewportBounds, TextScale.swift, Switcher.swift's LockedMac, ScrollDirection.swift).
// No Android types: unit tested on the JVM.

/** What a host takes from a device's viewport (kind 9): sides 1 to 16384 points, a scale 0.25 to 4. */
object ViewportBounds {
    const val MIN_SIDE = 1.0
    const val MAX_SIDE = 16384.0
    const val MIN_SCALE = 0.25
    const val MAX_SCALE = 4.0

    data class Held(val width: Double, val height: Double, val scale: Double?)

    /**
     * What a device sends: each side held to its range and a scale to its range, so no host refuses
     * it; a scale that is not a positive number becomes none. Nil while a side is not a positive
     * number (a panel not measured yet).
     */
    fun held(width: Double, height: Double, scale: Double?): Held? {
        if (!width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return null
        return Held(
            width.coerceIn(MIN_SIDE, MAX_SIDE),
            height.coerceIn(MIN_SIDE, MAX_SIDE),
            scale?.takeIf { it.isFinite() && it > 0 }?.coerceIn(MIN_SCALE, MAX_SCALE),
        )
    }
}

/**
 * Aa, the text size control (TextScale.swift): 0.5× to 1.5× in 0.25 steps, and Off (no scale: the
 * Mac leaves the window alone) one step below, offered only where no scale leaves the window alone.
 * Usable only while a window streams: the Mac ignores the scale for the Desktop.
 */
object TextScale {
    const val OFF = 0.25
    val scales = listOf(0.5, 0.75, 1.0, 1.25, 1.5)

    fun detents(offered: Boolean): List<Double> = if (offered) listOf(OFF) + scales else scales

    /** Where `scale` sits on the ruler: its own value, Off's place for nil when offered, else 1×. */
    fun position(scale: Double?, offered: Boolean): Double = scale ?: if (offered) OFF else 1.0

    /** The scale a detent stands for: nil for Off. */
    fun scaleAt(position: Double): Double? = if (position == OFF) null else position

    /** The nearest detent to `value`, clamped to the ends. */
    fun snap(value: Double, offered: Boolean): Double {
        val d = detents(offered)
        val c = value.coerceIn(d.first(), d.last())
        return d.minByOrNull { kotlin.math.abs(it - c) } ?: 1.0
    }

    /** "Off", or the scale with trailing zeros dropped and "×": 0.5×, 1×, 1.25×. */
    fun title(position: Double): String = if (position == OFF) "Off" else number(position) + "×"

    fun number(scale: Double): String {
        var t = String.format(java.util.Locale.ROOT, "%.2f", scale)
        while (t.endsWith("0")) t = t.dropLast(1)
        if (t.endsWith(".")) t = t.dropLast(1)
        return t
    }

    /**
     * Whether the ruler has Off: not for a window on the Mac's virtual display (no scale is 1× there).
     * While a window streams, where it streams decides (the running stream's `onVirtualDisplay`);
     * otherwise the Virtual Display setting as the device knows it.
     */
    fun offersOff(virtualDisplay: Boolean?, streaming: StreamSource, onVirtualDisplay: Boolean?): Boolean {
        if (streaming is StreamSource.Window && onVirtualDisplay != null) return !onVirtualDisplay
        return virtualDisplay != true
    }

    /** Aa sizes a window, never the Desktop: usable only while a window streams. */
    fun selectable(streaming: StreamSource): Boolean = streaming is StreamSource.Window

    const val UNAVAILABLE_HINT = "Available for a window."
}

/** A locked Mac on the device (Switcher.swift's LockedMac): why, the cover over the stream and its words. */
object LockedMac {
    enum class Why { SCREEN_LOCKED, OTHER_USER }

    sealed class Shade {
        data class Locked(val why: Why) : Shade()
        data object Unlocking : Shade()
    }

    /** What this device says in its hello: it shows a Mac's off-console view. */
    const val VIEW_GENERATION = 1

    /** How long "Unlocking…" waits for the picture at most, in seconds. */
    const val UNLOCKING_TIMEOUT_S = 4.0

    fun why(list: WindowList): Why? {
        if (list.locked != true) return null
        return if (list.offConsole == true) Why.OTHER_USER else Why.SCREEN_LOCKED
    }

    /** The off-console view: this session's own desktop and windows, to look at. */
    fun view(list: WindowList): Boolean = list.locked == true && list.offConsole == true && list.offConsoleView == true

    /** Whether the stream is covered: only for a Mac off console that shows nothing of itself. */
    fun covers(why: Why?, view: Boolean): Boolean = why == Why.OTHER_USER && !view

    /**
     * The cover after a window list: another user's while it says so (none in the off-console view,
     * whose picture is live); a locked screen shows no cover (the Mac streams its lock screen); after a
     * cover, "Unlocking…" until the next keyframe.
     */
    fun shade(was: Shade?, why: Why?, view: Boolean): Shade? {
        if (view && why == Why.OTHER_USER) return null
        if (why == Why.OTHER_USER) return Shade.Locked(Why.OTHER_USER)
        return if (was == null) null else Shade.Unlocking
    }

    /** A keyframe ends "Unlocking…"; nothing else does. */
    fun shadeAfterFrame(was: Shade?, keyframe: Boolean): Shade? = if (was == Shade.Unlocking && keyframe) null else was

    const val VIEW_LINE = "Your account’s own desktop, not this Mac’s screen. Taps and keys reach it once your account is back on the screen."
    const val VIEW_PANEL_LINE = "Another user is on this Mac’s screen. You’re seeing your account’s own desktop and windows, not the screen. Taps, keys and opening apps wait until your account is back on it."
    const val VIEW_TEXT_SCALE_HINT = "Available once your account is back on this Mac’s screen."

    /** The Apps drawer's and the Settings panel's line, where the Mac's windows would be. */
    fun line(mac: String, why: Why): String = when (why) {
        Why.SCREEN_LOCKED -> "${name(mac)} is locked. Unlock it to see its windows."
        Why.OTHER_USER -> "Another user started controlling this Mac. Log in to your account to reactivate screen sharing with Sill."
    }

    fun title(mac: String, shade: Shade): String = when (shade) {
        Shade.Locked(Why.SCREEN_LOCKED) -> "${name(mac)} is locked"
        Shade.Locked(Why.OTHER_USER) -> "Another user started controlling this Mac."
        Shade.Unlocking -> "Unlocking…"
        else -> ""
    }

    /** `paired`: this session's key is the Mac's (kind 16's sessionPaired): only then do keys reach a locked screen. */
    fun detail(shade: Shade, paired: Boolean?): String? = when (shade) {
        Shade.Locked(Why.SCREEN_LOCKED) ->
            if (paired == true) "To unlock it from here, type its password and press Return. Its windows come back once it is unlocked."
            else "Its windows come back once it is unlocked."
        Shade.Locked(Why.OTHER_USER) -> "Log in to your account to reactivate screen sharing with Sill."
        else -> null
    }

    private fun name(mac: String) = mac.ifEmpty { "The Mac" }
}

/**
 * Reverse Scrolling (ScrollDirection.swift): the device's own switch, never sent. It turns around a
 * scroll made on the glass (fingers over the picture, two fingers on the on-screen trackpad) and its
 * coast; a connected trackpad or mouse already follows the system's own scrolling setting.
 */
object ScrollDirection {
    enum class Source { FINGER, PAD, TRACKPAD, WHEEL }

    fun reversible(source: Source): Boolean = source == Source.FINGER || source == Source.PAD

    /** What a scroll's deltas, and its coast's velocity, are multiplied by; read once when a scroll begins. */
    fun factor(source: Source, reversed: Boolean): Double = if (reversed && reversible(source)) -1.0 else 1.0

    /** The SharedPreferences ("sill") key of the switch. */
    const val PREF = "reverseScroll"
}

/** The link while it cannot keep up (iOSClient's AwayCopy.swift): the Settings panel's callout and the stream's line. */
object AwayCopy {
    data class Callout(val text: String, val button: String?, val change: SettingsChange?)

    /**
     * First match wins: away while a device at home is connected (the Mac keeps the home quality: nothing
     * here would change it); a suggestion; nothing lower. Nil while the link keeps up.
     */
    fun callout(link: LinkReport?, away: AwayQuality?, mac: String): Callout? {
        if (link == null || !link.isBehind) return null
        val quality = QualityPreset.name(link.bitrate)
        if (away != null && away.thisConnectionAway && !away.awayRunning) {
            return Callout("The link can’t carry $quality, which $mac keeps while a device at home is connected.", null, null)
        }
        val bitrate = link.suggestedBitrate
        if (bitrate != null) {
            val scale = link.suggestedCaptureScale
            val title = if (scale != null) QualityPreset.shortTitle(bitrate, scale) else QualityPreset.name(bitrate)
            return Callout("The link can’t carry $quality. $title is recommended.", "Use $title", SettingsChange(bitrate = bitrate, captureScale = scale))
        }
        return Callout("The link to $mac is too slow for a steady picture, even at Low.", null, null)
    }

    /** The stream screen's line while the link is behind. */
    fun streamLine(link: LinkReport?, away: AwayQuality?, mac: String): String? {
        val c = callout(link, away, mac) ?: return null
        return if (c.button != null) "The link can’t keep up with ${QualityPreset.name(link!!.bitrate)}. Lower it in Settings."
        else "The link to $mac can’t keep up."
    }

    /** How long the stream's line stays after the report clears, so a quick flip back does not flicker (ms). */
    const val LINGER_MS = 2_000L
}
