package me.saffer.sill.android.client

import me.saffer.sill.android.protocol.HostSettingsState
import me.saffer.sill.android.protocol.SettingsChange
import me.saffer.sill.android.protocol.StreamSettings

/**
 * The Mac's settings as this device shows them (iOSClient/HostSettingsLedger.swift): the last kind
 * 16 on this connection with this device's unanswered picks laid over it. A pick sends only what
 * changes what is shown; only the answer carrying a pick's token clears it; a value the answer does
 * not hold was refused; a pick unanswered after max(4 s, 4 × the round trip) goes back. Nothing is
 * sent before the connection's first kind 16. Pure: no clock or socket of its own.
 */
class SettingsLedger {
    enum class Field { MAX_FPS, BITRATE, CAPTURE_SCALE, PRIORITIZE_SPEED, VIRTUAL_DISPLAY, DIRECT_WIRELESS }

    private data class Pick(val token: Int, val value: Any, val at: Long)

    var host: HostSettingsState? = null
        private set
    private val picks = HashMap<Field, Pick>()

    /** Tokens increase for the life of the process, never reset (HostSettingsLedger). */
    companion object { private var nextToken = 1 }

    val shown: StreamSettings?
        get() {
            var s = host?.settings ?: return null
            for ((f, p) in picks) s = with(f, p.value, s)
            return s
        }

    fun isPending(field: Field) = picks.containsKey(field)
    val anyPending get() = picks.isNotEmpty()

    /** The kind 17 to send for this pick, with its token, or nil when it changes nothing shown. */
    fun pick(change: SettingsChange, now: Long): Pair<Int, SettingsChange>? {
        val s = shown ?: return null
        val wanted = change.applied(s)
        var out = SettingsChange()
        val fields = ArrayList<Pair<Field, Any>>()
        if (wanted.maxFPS != s.maxFPS) { out = out.copy(maxFPS = wanted.maxFPS); fields += Field.MAX_FPS to wanted.maxFPS }
        if (wanted.bitrate != s.bitrate) { out = out.copy(bitrate = wanted.bitrate); fields += Field.BITRATE to wanted.bitrate }
        if (wanted.captureScale != s.captureScale) { out = out.copy(captureScale = wanted.captureScale); fields += Field.CAPTURE_SCALE to wanted.captureScale }
        if (wanted.prioritizeSpeed != s.prioritizeSpeed) { out = out.copy(prioritizeSpeed = wanted.prioritizeSpeed); fields += Field.PRIORITIZE_SPEED to wanted.prioritizeSpeed }
        if (wanted.virtualDisplay != s.virtualDisplay) { out = out.copy(virtualDisplay = wanted.virtualDisplay); fields += Field.VIRTUAL_DISPLAY to wanted.virtualDisplay }
        // A field the Mac reported as nil (an older Mac) is never sent.
        if (s.directWireless != null && wanted.directWireless != s.directWireless) {
            out = out.copy(directWireless = wanted.directWireless); fields += Field.DIRECT_WIRELESS to wanted.directWireless!!
        }
        if (out.isEmpty) return null
        val token = nextToken++
        for ((f, v) in fields) picks[f] = Pick(token, v, now)
        return token to out
    }

    /** A kind 16. Returns the fields the Mac refused (an answer that holds another value). */
    fun receive(state: HostSettingsState): List<Field> {
        host = state
        val answering = state.answering ?: return emptyList()
        val refused = ArrayList<Field>()
        val it = picks.entries.iterator()
        while (it.hasNext()) {
            val (f, p) = it.next()
            if (p.token != answering) continue
            if (valueOf(f, state.settings) != p.value) refused += f
            it.remove()
        }
        return refused
    }

    /** Picks unanswered for longer than `timeoutMs`: they go back. */
    fun expire(now: Long, timeoutMs: Long): Boolean {
        val before = picks.size
        picks.entries.removeAll { now - it.value.at > timeoutMs }
        return picks.size != before
    }

    val oldestPickAt: Long? get() = picks.values.minOfOrNull { it.at }

    fun reset() { host = null; picks.clear() }

    private fun valueOf(f: Field, s: StreamSettings): Any? = when (f) {
        Field.MAX_FPS -> s.maxFPS
        Field.BITRATE -> s.bitrate
        Field.CAPTURE_SCALE -> s.captureScale
        Field.PRIORITIZE_SPEED -> s.prioritizeSpeed
        Field.VIRTUAL_DISPLAY -> s.virtualDisplay
        Field.DIRECT_WIRELESS -> s.directWireless
    }

    private fun with(f: Field, v: Any, s: StreamSettings): StreamSettings = when (f) {
        Field.MAX_FPS -> s.copy(maxFPS = v as Int)
        Field.BITRATE -> s.copy(bitrate = v as Int)
        Field.CAPTURE_SCALE -> s.copy(captureScale = v as Double)
        Field.PRIORITIZE_SPEED -> s.copy(prioritizeSpeed = v as Boolean)
        Field.VIRTUAL_DISPLAY -> s.copy(virtualDisplay = v as Boolean)
        Field.DIRECT_WIRELESS -> s.copy(directWireless = v as Boolean)
    }
}
