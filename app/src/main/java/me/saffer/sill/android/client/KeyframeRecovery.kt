package me.saffer.sill.android.client

import kotlin.math.pow

/**
 * When this device asks the Mac for a keyframe after its picture was interrupted (iOSClient's
 * KeyframeRecovery.swift, issue #101): the decoder was rebuilt or fell behind and waits for a
 * keyframe, and a still window on the Mac sends none, so the device asks (kind 44), which the Mac
 * answers by encoding its last frame again.
 *
 * - One ask per interruption while its keyframe is due; an ask unanswered within `answerWait` is
 *   owed again, as a repeat (a Mac from before kind 44 skips it).
 * - Only while the app is on screen and something streams.
 * - At least `SPACING` after the last ask; repeats double the spacing up to `LONGEST_SPACING`. A
 *   picture back for `CALM`, or a trip to the background, starts the spacing at 2 s again.
 *
 * Pure: times are seconds on a monotonic clock, fed by SillClient on the main thread.
 */
class KeyframeRecovery {
    sealed class Decision {
        data object Nothing : Decision()
        data object AskNow : Decision()
        data class AskAt(val at: Double) : Decision()
        data class KeyframeDue(val at: Double) : Decision()
    }

    var open = false; private set
    var asked = false; private set
    var repeats = 0; private set
    var lastAsk: Double? = null; private set
    private var backAt: Double? = null
    private var wentAway = false

    val spacingNow: Double get() = minOf(SPACING * 2.0.pow(repeats), LONGEST_SPACING)

    fun interrupted(now: Double) {
        if (open) return
        open = true
        asked = false
        val back = backAt
        repeats = if (!wentAway && back != null && now >= back && now - back < CALM) repeats + 1 else 0
    }

    fun pictureBack(now: Double, active: Boolean) {
        if (!open) return
        open = false
        backAt = now
        if (active) wentAway = false
    }

    fun leftForeground() {
        wentAway = true
        asked = false
        repeats = 0
    }

    fun consider(now: Double, active: Boolean, streaming: Boolean, wait: Double): Decision {
        if (!open || !active || !streaming) return Decision.Nothing
        if (asked) {
            val last = lastAsk
            if (last != null && now >= last && now < last + wait) return Decision.KeyframeDue(last + wait)
            asked = false
            repeats++
        }
        val last = lastAsk
        if (last != null && now >= last && now < last + spacingNow) return Decision.AskAt(last + spacingNow)
        asked = true
        lastAsk = now
        return Decision.AskNow
    }

    fun reset() {
        open = false; asked = false; repeats = 0; lastAsk = null; backAt = null; wentAway = false
    }

    companion object {
        const val SPACING = 2.0
        const val LONGEST_SPACING = 32.0
        const val CALM = 10.0
        const val SHORTEST_WAIT = 4.0

        /** 4 s, or four of the worst recent round trips on a slow link. */
        fun answerWait(worstRttMs: Int?): Double = maxOf(SHORTEST_WAIT, 4.0 * (worstRttMs ?: 0).coerceAtLeast(0) / 1000.0)
    }
}

/**
 * When the device holds its screen on (iOSClient's DeviceIdlePolicy.swift): while the app is on screen
 * and a source streams, until `IDLE_LIMIT_S` after the person last used the session (input sent to the
 * Mac, a control, the start of a session they started, the app coming to the front). What the device
 * does by itself (the automatic reconnect, the Desktop it asks for again) never counts.
 */
object DeviceIdlePolicy {
    const val IDLE_LIMIT_S = 600.0

    /** When the hold ends, or nil when the screen is not held on at `now`. */
    fun holdEnds(foreground: Boolean, streaming: Boolean, lastUse: Double?, now: Double, limit: Double = IDLE_LIMIT_S): Double? {
        if (!foreground || !streaming || lastUse == null) return null
        val end = lastUse + limit
        return if (end > now) end else null
    }

    /** A session's start as use: one the person started counts from now; the automatic reconnect keeps the last one's. */
    fun sessionStart(previous: Double?, reconnected: Boolean, now: Double): Double? = if (reconnected) previous else now
}
