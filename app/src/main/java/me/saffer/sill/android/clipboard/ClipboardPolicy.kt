package me.saffer.sill.android.clipboard

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

// The clipboard's wire and rules (kind 32), ported from Sources/StreamProtocol's Clipboard.swift and
// ClipboardPolicy.swift (Sill for Mac 0.6, docs/clipboard-plan.md). Pure: no Android types, unit tested
// on the JVM. The device's rules are here whole, and the host's reading of a device's message
// (`fromDevice`, `hostReceives`), which the tests hold this device's messages to. Left out, as the Mac's
// alone: its pasteboard's rules (`hostDecides`, `hostWaits`, the markers), macOS's paste privacy
// (`MacAccess` and its lines) and the host's log lines.

/**
 * Kind 32, both ways (Clipboard.swift). Text in this generation (`WindowList.clipboard` 1).
 *
 * - Device → host, only to a host whose list says `clipboard` 1 or more: any kind 32 subscribes the
 *   connection to the Mac's copies (`{}` is what a device sends while its Share Clipboard is on);
 *   `text` also becomes the Mac's clipboard; `stop: true` ends the subscription and wins over the rest.
 * - Host → device, only to a connection that subscribed: `text`, copied on the Mac or sent by another
 *   device. `{}` takes text alone: a host never sends `image` to it.
 *
 * Every field optional and left out when nil, so `{}` is a subscription; unknown keys are ignored.
 */
data class Clipboard(
    /** At most `MAX_TEXT_BYTES` of UTF-8, never empty when sent. */
    val text: String? = null,
    /** Reserved for a later generation (`clipboard` 2): base64 on the wire, as Swift's Data. Never sent here. */
    val image: ByteArray? = null,
    /** Device → host: this device's Share Clipboard went off. Never from a host. */
    val stop: Boolean? = null,
) {
    fun encode(): ByteArray {
        val o = JSONObject()
        text?.let { o.put("text", it) }
        image?.let { o.put("image", java.util.Base64.getEncoder().encodeToString(it)) }
        stop?.let { o.put("stop", it) }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Clipboard) return false
        val sameImage = if (image == null) other.image == null else other.image != null && image.contentEquals(other.image)
        return text == other.text && stop == other.stop && sameImage
    }

    override fun hashCode(): Int = 31 * (31 * (text?.hashCode() ?: 0) + (image?.contentHashCode() ?: 0)) + (stop?.hashCode() ?: 0)

    companion object {
        /** The most text either way: 256 KB of UTF-8 (262,144 bytes). */
        const val MAX_TEXT_BYTES = 256 * 1024

        /**
         * Swift's JSONDecoder of Clipboard: one JSON object in valid UTF-8; `text` a string, `image` a
         * base64 string, `stop` a boolean, each when present and not null; anything else nil.
         */
        fun decode(bytes: ByteArray): Clipboard? = runCatching {
            val s = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val tokens = JSONTokener(s)
            val o = tokens.nextValue() as? JSONObject ?: return null
            if (tokens.nextClean() != 0.toChar()) return null          // something after the object
            fun field(key: String): Any? = if (o.has(key) && !o.isNull(key)) o.get(key) else null
            val text = field("text")
            val image = field("image")
            val stop = field("stop")
            if ((text != null && text !is String) || (image != null && image !is String) || (stop != null && stop !is Boolean)) return null
            val imageBytes = image?.let { b64 ->
                // Swift's Data(base64Encoded:): padded, nothing but the alphabet.
                if (b64.length % 4 != 0) return null
                java.util.Base64.getDecoder().decode(b64)
            }
            Clipboard(text, imageBytes, stop)
        }.getOrNull()
    }
}

object ClipboardPolicy {
    /** `WindowList.clipboard` from a host that takes and sends text. */
    const val GENERATION = 1
    /** The most a kind 32's JSON may be, either way: the host's cap on a device's message (1 MiB). */
    const val MAX_PAYLOAD = 1 shl 20
    /** A device's subscriptions and texts a second, per connection, that a host takes. */
    const val PER_SECOND = 4
    /** How often the host looks at its pasteboard while a device subscribes (s). */
    const val LOOK_INTERVAL = 0.25
    /** How long the Mac's text stays on this device's clipboard (s). */
    const val DEVICE_EXPIRY = 600.0
    /** How long the chip stays at most (s), counted only while it is seen (`chipCounts`). */
    const val CHIP_LIFETIME = 20.0
    /** A session whose first list comes this soon after Sill became active still gets that look (s). */
    const val ACTIVATION_WINDOW = 15.0
    /** How long "Sent to…", "Pasted on…", "Too long…" or "Didn’t reach…" stays (s). */
    const val NOTE_LIFETIME = 3.0
    /** A Paste's receipt: the payload of the ping sent after its text, this many random bytes (a ping's own is 8). */
    const val RECEIPT_LENGTH = 16
    /** The key ⌘V presses: V's HID usage (the keyboard page's 0x19). */
    const val PASTE_KEY = 0x19
    /** How long after the receipt ⌘V goes (s): the Mac writes the text on a queue of its own. */
    const val PASTE_DELAY = 0.05

    // MARK: The wire

    /**
     * A kind 32 that sets the other side's clipboard to `text`: its JSON, or nil when the text is empty,
     * longer than `MAX_TEXT_BYTES` of UTF-8, or its JSON longer than `MAX_PAYLOAD` (a control character
     * is six bytes of JSON).
     */
    fun payload(text: String): ByteArray? {
        if (text.isEmpty() || !withinCap(text)) return null
        val data = Clipboard(text = text).encode()
        if (data.isEmpty() || data.size > MAX_PAYLOAD) return null
        return data
    }

    /** A device's subscription: `{}`. */
    val subscription: ByteArray get() = Clipboard().encode()
    /** A device's switch going off: `{"stop":true}`. */
    val stopping: ByteArray get() = Clipboard(stop = true).encode()

    /** Whether `text` is at most `MAX_TEXT_BYTES` of UTF-8, counted without encoding it (a copy can be large). */
    fun withinCap(text: String): Boolean {
        // Every UTF-16 unit is at least one byte of UTF-8.
        if (text.length > Clipboard.MAX_TEXT_BYTES) return false
        return utf8Length(text) <= Clipboard.MAX_TEXT_BYTES
    }

    /** UTF-8 bytes of `s` as Java encodes it (a lone surrogate becomes one "?"). */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                Character.isSurrogate(c) -> 1
                else -> 3
            }
            i++
        }
        return n
    }

    // MARK: The host's reading of a device's message (what this device's messages ask of it)

    enum class Refusal(val wire: String) { UNDECODABLE("undecodable"), TOO_LONG("tooLong"), RATE("rate") }

    sealed class FromDevice {
        /** Send this connection the Mac's copies from now on. */
        data object Subscribe : FromDevice()
        /** The same, and this text becomes the Mac's clipboard. */
        data class Set(val text: String) : FromDevice()
        /** Send this connection nothing more. */
        data object Stop : FromDevice()
        data class Refuse(val why: Refusal) : FromDevice()
    }

    /** `stop` wins over anything with it; text that is not empty sets; anything else subscribes. */
    fun fromDevice(payload: ByteArray): FromDevice {
        val clip = Clipboard.decode(payload) ?: return FromDevice.Refuse(Refusal.UNDECODABLE)
        if (clip.stop == true) return FromDevice.Stop
        val text = clip.text
        if (text.isNullOrEmpty()) return FromDevice.Subscribe
        if (!withinCap(text)) return FromDevice.Refuse(Refusal.TOO_LONG)
        return FromDevice.Set(text)
    }

    /** Who a device's text goes to: every subscriber but its sender, in the order given. */
    fun <T> forward(to: List<T>, from: T): List<T> = to.filter { it != from }

    /**
     * A device's kind 32 at `now`, as the host takes it: what it asks, and the arrivals to keep. A
     * subscription or text at most `PER_SECOND` in any second; a stop always, counting nothing.
     */
    fun hostReceives(payload: ByteArray, arrivals: List<Double>, now: Double): Pair<FromDevice, List<Double>> {
        val ask = fromDevice(payload)
        if (!countsAgainstRate(ask)) return ask to arrivals
        val (taken, kept) = admits(arrivals, now)
        return (if (taken) ask else FromDevice.Refuse(Refusal.RATE)) to kept
    }

    fun countsAgainstRate(ask: FromDevice): Boolean = ask is FromDevice.Subscribe || ask is FromDevice.Set

    /** Whether one more is taken at `now`, at most `PER_SECOND` in any second, and the arrivals to keep. */
    fun admits(arrivals: List<Double>, now: Double): Pair<Boolean, List<Double>> {
        val recent = arrivals.filter { now - it < 1 }
        if (recent.size >= PER_SECOND) return false to recent
        return true to (recent + now)
    }

    // MARK: The device

    /** Whether the Mac's list says it takes and sends the clipboard. */
    fun hostTakes(hostClipboard: Int?): Boolean = (hostClipboard ?: 0) >= GENERATION

    /**
     * A list's word on the clipboard as this device takes it: a locked Mac's list carries no `clipboard`
     * (LockPolicy.list), and should one carry it anyway, a locked Mac shares nothing.
     */
    fun hostClipboard(clipboard: Int?, locked: Boolean?): Int? = if (locked == true) null else clipboard

    enum class Subscription { SUBSCRIBE, STOP, NONE }

    /**
     * Subscribe while the Mac takes the clipboard, the switch is on and this connection has not; stop
     * when it has and the switch went off. A Mac that stopped saying it forgot every subscription, so
     * the connection has none. What to send, and whether the connection is subscribed after it.
     */
    fun deviceSubscription(hostClipboard: Int?, switchOn: Boolean, subscribed: Boolean): Pair<Subscription, Boolean> {
        if (!hostTakes(hostClipboard)) return Subscription.NONE to false
        if (switchOn && !subscribed) return Subscription.SUBSCRIBE to true
        if (!switchOn && subscribed) return Subscription.STOP to false
        return Subscription.NONE to subscribed
    }

    /**
     * What this device sent last, and its clipboard's change right after. On Android the change is the
     * clip's time (ClipDescription.getTimestamp, ms), the nearest thing to iOS's changeCount.
     */
    data class Sent(val text: String, val changeCount: Long)

    /**
     * A kind 32 from the Mac: the text the device writes to its clipboard, or nil. Only with the switch
     * on, from a Mac that takes the clipboard, text not empty and within the cap, and never the text this
     * device sent while its clipboard still holds it (the Mac's echo).
     */
    fun deviceWrites(payload: ByteArray, switchOn: Boolean, hostClipboard: Int?, lastSent: Sent?, changeCount: Long): String? {
        if (!switchOn || !hostTakes(hostClipboard)) return null
        val text = Clipboard.decode(payload)?.text ?: return null
        if (text.isEmpty() || !withinCap(text)) return null
        if (lastSent != null && lastSent.text == text && lastSent.changeCount == changeCount) return null
        return text
    }

    /**
     * The chip, "Paste on ‹Mac›": connected to a Mac that takes the clipboard, the switch on, the
     * device's clipboard has text, and its change is not the one Sill last handled (sent, written from
     * the Mac, or the chip dismissed or timed out for it).
     */
    fun offersChip(connected: Boolean, hostClipboard: Int?, switchOn: Boolean, hasStrings: Boolean, changeCount: Long, handled: Long?): Boolean =
        connected && hostTakes(hostClipboard) && switchOn && hasStrings && changeCount != handled

    /** The chip's `CHIP_LIFETIME` runs only while it is on screen, Sill is in front and TalkBack is off. */
    fun chipCounts(onScreen: Boolean, active: Boolean, voiceOver: Boolean): Boolean = onScreen && active && !voiceOver

    /** A session's first list that says the Mac takes the clipboard: whether Sill's activation owes it the chip's look. */
    fun looksAtSessionStart(activatedAt: Double?, now: Double): Boolean {
        if (activatedAt == null) return false
        return now >= activatedAt && now - activatedAt <= ACTIVATION_WINDOW
    }

    sealed class DeviceSend {
        data class Send(val payload: ByteArray) : DeviceSend() {
            override fun equals(other: Any?) = other is Send && other.payload.contentEquals(payload)
            override fun hashCode() = payload.contentHashCode()
        }
        /** Longer than the cap: nothing sent, the person told. */
        data object TooLong : DeviceSend()
        /** Nothing to send (no text, not connected, the Mac does not take it, the switch off). */
        data object Nothing : DeviceSend()
    }

    /** What a Paste read (one string per item): the first that is not empty goes, to a connected Mac that takes it, with the switch on. */
    fun deviceSends(strings: List<String>, connected: Boolean, hostClipboard: Int?, switchOn: Boolean): DeviceSend {
        if (!connected || !hostTakes(hostClipboard) || !switchOn) return DeviceSend.Nothing
        val text = strings.firstOrNull { it.isNotEmpty() } ?: return DeviceSend.Nothing
        val data = payload(text) ?: return DeviceSend.TooLong
        return DeviceSend.Send(data)
    }

    /** How long a Paste waits for its receipt (s): four times the worst recent round trip, at least 4 s. */
    fun receiptWait(worstRttMs: Int?): Double = maxOf(4.0, 4.0 * (worstRttMs ?: 0) / 1000.0)

    /**
     * Paste right away: a receipt also presses ⌘V on the Mac only with the switch on, for a receipt in
     * time, while something streams, while the Mac's last list still shares its clipboard, never while a
     * tour keeps input from the Mac.
     */
    fun pastesOnReceipt(rightAway: Boolean, late: Boolean, streaming: Boolean, hostClipboard: Int?, tourShows: Boolean): Boolean =
        rightAway && !late && streaming && hostTakes(hostClipboard) && !tourShows

    /** The note a receipt brings, written and spoken. */
    fun receiptNote(pasted: Boolean, mac: String): Pair<String, String> =
        if (pasted) Copy.pasted(mac) to Copy.pasted(mac) else Copy.sent(mac) to Copy.sentSpoken(mac)

    enum class Corner { TOP_TRAILING, BOTTOM_TRAILING }

    /** Where the chip goes on the stream panel: under the bar sideways (its bottom while the link's line holds the top); upright, the picture's bottom. */
    fun chipCorner(landscape: Boolean, linkLineShown: Boolean): Corner =
        if (landscape && !linkLineShown) Corner.TOP_TRAILING else Corner.BOTTOM_TRAILING

    /**
     * Android's stand-in for iOS's 10-minute expiration: the Mac's text, written at `writtenAtMs` (wall
     * clock), is cleared from this device's clipboard at a look `DEVICE_EXPIRY` later, if still there.
     */
    fun ownTextExpired(writtenAtMs: Long, nowMs: Long): Boolean = nowMs - writtenAtMs >= (DEVICE_EXPIRY * 1000).toLong()

    // MARK: Words

    object Copy {
        /** "256 KB". */
        val cap: String get() = "${Clipboard.MAX_TEXT_BYTES / 1024} KB"

        // The chip.
        fun chipTitle(mac: String) = "Paste on $mac"
        const val CHIP_SUBTITLE = "Then press ⌘V there."
        fun chipSubtitle(rightAway: Boolean) = if (rightAway) "Where the cursor is." else CHIP_SUBTITLE
        fun chipSpoken(mac: String, device: String) =
            "Paste on $mac. Sends the text you copied on this $device to $mac’s clipboard. Then press Command-V there."
        fun chipSpoken(mac: String, device: String, rightAway: Boolean) =
            if (rightAway) "Paste on $mac. Pastes the text you copied on this $device where $mac’s cursor is." else chipSpoken(mac, device)
        const val DISMISS = "Dismiss"
        /** Told to TalkBack when the chip first shows for a copy. */
        fun chipAnnouncement(mac: String) = "Paste on $mac is available, near the bar."
        /** The Paste button's hint, on the chip and in Settings. */
        fun pasteHint(mac: String, device: String) = "Sends the text you copied on this $device to $mac’s clipboard."
        fun pasteHint(mac: String, device: String, rightAway: Boolean) =
            if (rightAway) "Pastes the text you copied on this $device where $mac’s cursor is." else pasteHint(mac, device)
        /** The Paste button's own word (iOS's system control says "Paste"). */
        const val PASTE = "Paste"

        // The notes.
        fun sent(mac: String) = "Sent to $mac’s clipboard. Press ⌘V there."
        fun sentSpoken(mac: String) = "Sent to $mac’s clipboard. Press Command-V there."
        fun tooLong(mac: String) = "Too long to send: $mac takes up to $cap of text."
        fun notSent(mac: String) = "Didn’t reach $mac. Try Paste again."
        fun pasted(mac: String) = "Pasted on $mac."

        // The Settings panel's This phone / This tablet group.
        const val SWITCH_TITLE = "Share Clipboard"
        fun rowTitle(mac: String) = "Paste on $mac"
        /** The switch under Share Clipboard, shown while it is on. */
        fun rightAwayTitle(mac: String) = "Paste on $mac Right Away"
        fun footnote(on: Boolean, rightAway: Boolean, mac: String, device: String): String {
            if (!on || !rightAway) return footnote(on, mac, device)
            return "Text you copy on $mac is ready to paste on this $device. To paste on $mac, copy text here and tap Paste: it goes where $mac’s cursor is. With Paste on $mac Right Away off, Paste only puts it on $mac’s clipboard."
        }
        fun footnote(on: Boolean, mac: String, device: String) =
            if (on) "Text you copy on $mac is ready to paste on this $device. To paste on $mac, copy text here, tap Paste, then press ⌘V there."
            else "Nothing you copy goes between this $device and $mac while this is off."
    }
}
