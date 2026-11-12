package me.saffer.sill.android

import me.saffer.sill.android.clipboard.Clipboard
import me.saffer.sill.android.clipboard.ClipboardPolicy
import me.saffer.sill.android.clipboard.ClipboardPolicy.DeviceSend
import me.saffer.sill.android.clipboard.ClipboardPolicy.FromDevice
import me.saffer.sill.android.clipboard.ClipboardPolicy.Refusal
import me.saffer.sill.android.clipboard.ClipboardPolicy.Subscription
import me.saffer.sill.android.protocol.WindowList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The clipboard's wire and rules (Tests/checks/clipboard of Sill v0.6.1), against ClipboardPolicy.kt. */
class ClipboardPolicyTest {
    private val P = ClipboardPolicy
    private val C = ClipboardPolicy.Copy
    private fun data(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun text(of: ByteArray?) = of?.let { Clipboard.decode(it)?.text }
    private val atCap = "a".repeat(Clipboard.MAX_TEXT_BYTES)

    // MARK: the window list and Clipboard's JSON

    @Test fun windowListClipboard() {
        assertEquals(1, WindowList.parse(data("""{"macName":"M","windows":[],"active":{"desktop":{}},"clipboard":1}"""))?.clipboard)
        assertNull(WindowList.parse(data("""{"macName":"M","windows":[],"active":{"desktop":{}},"gestures":1}"""))?.clipboard)
        assertEquals(2, WindowList.parse(data("""{"macName":"M","windows":[],"active":{"none":{}},"clipboard":2}"""))?.clipboard)
        assertEquals(1, P.GENERATION)
        // A locked Mac's list carries no clipboard; one that did would still share nothing.
        assertNull(P.hostClipboard(1, locked = true))
        assertEquals(1, P.hostClipboard(1, locked = false))
        assertEquals(1, P.hostClipboard(1, locked = null))
        assertNull(P.hostClipboard(null, locked = null))
    }

    @Test fun clipboardJson() {
        assertEquals("{}", String(Clipboard().encode()))
        assertEquals("{}", String(P.subscription))
        assertEquals("""{"stop":true}""", String(P.stopping))
        assertEquals("""{"text":"hi"}""", String(Clipboard(text = "hi").encode()))
        val withImage = JSONObject(String(Clipboard(text = "a", image = byteArrayOf(1, 2, 3)).encode()))
        assertEquals("AQID", withImage.getString("image"))
        assertEquals("a", withImage.getString("text"))
        assertEquals(Clipboard(text = "a", image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)),
            Clipboard.decode(data("""{"text":"a","image":"iVBORw==","later":[1]}""")))
        assertEquals(Clipboard(), Clipboard.decode(data("{}")))
        assertEquals(Clipboard(), Clipboard.decode(data("""{"text":null,"stop":null}""")))
        assertNull("a number as the text", Clipboard.decode(data("""{"text":5}""")))
        assertNull("a string as stop", Clipboard.decode(data("""{"stop":"yes"}""")))
        assertNull("an image not base64", Clipboard.decode(data("""{"image":"iVBORw"}""")))
        assertNull("not an object", Clipboard.decode(data("[]")))
        assertNull("nothing", Clipboard.decode(ByteArray(0)))
        assertNull("not JSON", Clipboard.decode(data("not json")))
        assertNull("something after the object", Clipboard.decode(data("""{"text":"a"} x""")))
        assertNull("not UTF-8", Clipboard.decode(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xFF.toByte(), '"'.code.toByte(), '}'.code.toByte())))
        assertEquals(262_144, Clipboard.MAX_TEXT_BYTES)
        assertEquals(1_048_576, P.MAX_PAYLOAD)
        assertEquals(me.saffer.sill.android.protocol.Wire.MAX_CLIENT_PAYLOAD, P.MAX_PAYLOAD)
        assertTrue(P.MAX_PAYLOAD <= me.saffer.sill.android.protocol.Wire.MAX_OTHER_HOST_PAYLOAD)
    }

    // MARK: payload(text:)

    @Test fun payload() {
        assertNull("empty text: nothing to send", P.payload(""))
        assertEquals("Hello, Mac", text(P.payload("Hello, Mac")))
        val odd = "line 1\nline\t2 ‮ “é” 😀 </script> \\ \""
        assertEquals("newlines, tabs, bidi, emoji, slashes and quotes kept", odd, text(P.payload(odd)))
        assertEquals("exactly 256 KB goes (11 bytes of JSON around it)", Clipboard.MAX_TEXT_BYTES + 11, P.payload(atCap)?.size)
        assertNull("one byte more does not", P.payload(atCap + "a"))
        val euros = "€".repeat(Clipboard.MAX_TEXT_BYTES / 3)
        assertNotNull(P.payload(euros)); assertNull("counted in UTF-8 bytes (€ is 3)", P.payload(euros + "€"))
        val emoji = "😀".repeat(Clipboard.MAX_TEXT_BYTES / 4)
        assertNotNull(P.payload(emoji)); assertNull("😀 is 4 bytes", P.payload(emoji + "😀"))
        // A control character is six bytes of JSON: within 256 KB of text, over 1 MiB of JSON.
        val controls = "\u0001".repeat(200_000)
        assertTrue(Clipboard(text = controls).encode().size > P.MAX_PAYLOAD)
        assertNull("text within the cap whose JSON is over 1 MiB is not sent", P.payload(controls))
        val fits = "\u0001".repeat((P.MAX_PAYLOAD - 11) / 6)
        assertEquals("its largest that fits goes", fits, text(P.payload(fits)))
        val oneOver = "\u0001".repeat((P.MAX_PAYLOAD - 11) / 6 + 1)
        assertTrue(P.withinCap(oneOver)); assertNull("one control character more does not", P.payload(oneOver))
    }

    @Test fun utf8Length() {
        for (s in listOf("", "a", "é", "€", "😀", "a€😀é", "\uD83D", "x\uDC00y", atCap.take(1000) + "😀€")) {
            assertEquals(s, s.toByteArray(Charsets.UTF_8).size, P.utf8Length(s))
        }
        assertFalse("more UTF-16 units than the cap: over it at once", P.withinCap("a".repeat(Clipboard.MAX_TEXT_BYTES + 1)))
    }

    // MARK: the host's reading of a device's message

    @Test fun fromDevice() {
        assertEquals(FromDevice.Subscribe, P.fromDevice(data("{}")))
        assertEquals(FromDevice.Set("abc"), P.fromDevice(data("""{"text":"abc"}""")))
        assertEquals("empty text: subscribes", FromDevice.Subscribe, P.fromDevice(data("""{"text":""}""")))
        assertEquals(FromDevice.Stop, P.fromDevice(data("""{"stop":true}""")))
        assertEquals("stop wins over text", FromDevice.Stop, P.fromDevice(data("""{"stop":true,"text":"abc"}""")))
        assertEquals("stop false is no stop", FromDevice.Subscribe, P.fromDevice(data("""{"stop":false}""")))
        assertEquals("an image alone subscribes", FromDevice.Subscribe, P.fromDevice(data("""{"image":"AQID"}""")))
        assertEquals(FromDevice.Set("t"), P.fromDevice(data("""{"text":"t","image":"AQID"}""")))
        assertEquals(FromDevice.Set(atCap), P.fromDevice(Clipboard(text = atCap).encode()))
        assertEquals(FromDevice.Refuse(Refusal.TOO_LONG), P.fromDevice(Clipboard(text = atCap + "a").encode()))
        for (junk in listOf(data("not json"), data("""{"text":7}"""), ByteArray(0))) {
            assertEquals(FromDevice.Refuse(Refusal.UNDECODABLE), P.fromDevice(junk))
        }
        assertEquals("a later field is skipped", FromDevice.Set("x"), P.fromDevice(data("""{"text":"x","later":{"a":1}}""")))
        // What this device sends reads as meant.
        assertEquals(FromDevice.Subscribe, P.fromDevice(P.subscription))
        assertEquals(FromDevice.Stop, P.fromDevice(P.stopping))
        assertEquals(FromDevice.Set("pasted"), P.fromDevice(P.payload("pasted")!!))
        assertEquals(listOf("rate", "tooLong", "undecodable"), listOf(Refusal.RATE, Refusal.TOO_LONG, Refusal.UNDECODABLE).map { it.wire })
    }

    @Test fun forwardAndRate() {
        assertEquals(listOf(1, 3), P.forward(listOf(1, 2, 3), 2))
        assertEquals(listOf(1, 2, 3), P.forward(listOf(1, 2, 3), 4))
        assertTrue(P.forward(listOf<Int>(), 1).isEmpty() && P.forward(listOf(1), 1).isEmpty())
        assertEquals(listOf(3, 2), P.forward(listOf(3, 1, 2), 1))

        var arrivals = listOf<Double>()
        val taken = mutableListOf<Boolean>()
        for (t in listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.99, 1.05, 1.11)) {
            val (ok, kept) = P.admits(arrivals, t); taken.add(ok); arrivals = kept
        }
        assertEquals(listOf(true, true, true, true, false, false, true, true), taken)
        assertEquals(true to listOf(0.5, 0.9, 0.95, 1.0), P.admits(listOf(0.0, 0.5, 0.9, 0.95), 1.0))
        assertEquals(false to listOf(0.2, 0.5, 0.9, 0.95), P.admits(listOf(0.2, 0.5, 0.9, 0.95), 1.0))
        assertEquals(4, P.PER_SECOND)

        fun receives(messages: List<Pair<Double, String>>): List<FromDevice> {
            var kept = listOf<Double>()
            return messages.map { (t, json) -> val (ask, k) = P.hostReceives(data(json), kept, t); kept = k; ask }
        }
        val stop = """{"stop":true}"""
        assertEquals("every stop taken", listOf(FromDevice.Subscribe, FromDevice.Stop, FromDevice.Subscribe, FromDevice.Stop, FromDevice.Subscribe, FromDevice.Stop),
            receives(listOf(0.0 to "{}", 2.0 to stop, 2.1 to "{}", 2.2 to stop, 2.3 to "{}", 2.4 to stop)))
        assertEquals(FromDevice.Stop, receives(listOf(3.0 to "{}", 3.1 to "{}", 3.2 to "{}", 3.3 to "{}", 3.4 to stop)).last())
        assertEquals(FromDevice.Refuse(Refusal.RATE), receives(listOf(3.0 to "{}", 3.1 to "{}", 3.2 to "{}", 3.3 to "{}", 3.4 to "{}")).last())
        assertEquals(listOf(FromDevice.Set("a"), FromDevice.Set("b"), FromDevice.Set("c"), FromDevice.Set("d"), FromDevice.Refuse(Refusal.RATE)),
            receives(listOf("a", "b", "c", "d", "e").mapIndexed { i, s -> i * 0.1 to """{"text":"$s"}""" }))
        assertEquals("stops count nothing", List(4) { FromDevice.Subscribe },
            receives(List(5) { it * 0.1 to stop } + List(4) { 0.5 + it * 0.1 to "{}" }).takeLast(4))
        assertEquals("refusals count nothing", FromDevice.Subscribe, receives(List(4) { it * 0.1 to "junk" } + (0.4 to "{}")).last())
        assertEquals(List(2) { FromDevice.Refuse(Refusal.RATE) }, receives(List(6) { it * 0.1 to "{}" }).takeLast(2))
        assertEquals(FromDevice.Subscribe, receives(List(4) { it * 0.1 to "{}" } + (1.05 to "{}")).last())
    }

    // MARK: the device

    @Test fun subscription() {
        assertTrue(!P.hostTakes(null) && !P.hostTakes(0) && P.hostTakes(1) && P.hostTakes(2))
        for (host in listOf(null, 0, 1, 2)) for (on in listOf(false, true)) for (subscribed in listOf(false, true)) {
            val got = P.deviceSubscription(host, on, subscribed)
            val takes = (host ?: 0) >= 1
            val want = when {
                !takes -> Subscription.NONE to false
                on && !subscribed -> Subscription.SUBSCRIBE to true
                !on && subscribed -> Subscription.STOP to false
                else -> Subscription.NONE to subscribed
            }
            assertEquals("host $host, switch $on, subscribed $subscribed", want, got)
        }
    }

    @Test fun macText() {
        val fromMac = Clipboard(text = "from the Mac").encode()
        assertEquals("from the Mac", P.deviceWrites(fromMac, true, 1, null, 3))
        assertNull("the switch off", P.deviceWrites(fromMac, false, 1, null, 3))
        assertNull("a Mac that does not say it", P.deviceWrites(fromMac, true, null, null, 3))
        assertNull("empty", P.deviceWrites(data("""{"text":""}"""), true, 1, null, 3))
        assertNull("no text", P.deviceWrites(data("""{"stop":true}"""), true, 1, null, 3))
        assertNull("undecodable", P.deviceWrites(data("garbage"), true, 1, null, 3))
        assertNull("over the cap", P.deviceWrites(Clipboard(text = atCap + "a").encode(), true, 1, null, 3))
        assertEquals(atCap, P.deviceWrites(Clipboard(text = atCap).encode(), true, 1, null, 3))
        val sent = ClipboardPolicy.Sent("mine", 41)
        val echo = Clipboard(text = "mine").encode()
        assertNull("the Mac's echo of what the clipboard holds", P.deviceWrites(echo, true, 1, sent, 41))
        assertEquals("the same text after the clipboard changed", "mine", P.deviceWrites(echo, true, 1, sent, 42))
        assertEquals("the clipboard unreadable (-1): written", "mine", P.deviceWrites(echo, true, 1, sent, -1))
        assertEquals("other text", "from the Mac", P.deviceWrites(fromMac, true, 1, sent, 41))
    }

    @Test fun chip() {
        for (connected in listOf(false, true)) for (host in listOf(null, 1)) for (on in listOf(false, true))
            for (has in listOf(false, true)) for (handled in listOf(null, 5L, 6L)) {
                val got = P.offersChip(connected, host, on, has, 6L, handled)
                assertEquals("connected $connected, host $host, switch $on, text $has, handled $handled",
                    connected && host != null && on && has && handled != 6L, got)
            }
        for (onScreen in listOf(false, true)) for (active in listOf(false, true)) for (talkBack in listOf(false, true)) {
            assertEquals(onScreen && active && !talkBack, P.chipCounts(onScreen, active, talkBack))
        }
        assertFalse(P.looksAtSessionStart(null, 100.0))
        assertTrue(P.looksAtSessionStart(100.0, 100.0) && P.looksAtSessionStart(100.0, 115.0))
        assertFalse(P.looksAtSessionStart(100.0, 115.01))
        assertFalse("a clock that went back", P.looksAtSessionStart(100.0, 99.9))
        assertTrue(P.ACTIVATION_WINDOW == 15.0 && P.CHIP_LIFETIME == 20.0 && P.NOTE_LIFETIME == 3.0 && P.DEVICE_EXPIRY == 600.0 && P.LOOK_INTERVAL == 0.25)
        assertEquals("Paste on Mac mini is available, near the bar.", C.chipAnnouncement("Mac mini"))
        // Android's stand-in for the 10-minute expiration.
        assertFalse(P.ownTextExpired(1_000_000, 1_000_000 + 599_999))
        assertTrue(P.ownTextExpired(1_000_000, 1_000_000 + 600_000))
    }

    @Test fun paste() {
        assertEquals(DeviceSend.Send(P.payload("abc")!!), P.deviceSends(listOf("abc"), true, 1, true))
        assertEquals("the first that is not empty", DeviceSend.Send(P.payload("b")!!), P.deviceSends(listOf("", "b", "c"), true, 1, true))
        assertEquals(DeviceSend.Nothing, P.deviceSends(listOf(), true, 1, true))
        assertEquals(DeviceSend.Nothing, P.deviceSends(listOf(""), true, 1, true))
        assertEquals(DeviceSend.TooLong, P.deviceSends(listOf(atCap + "a"), true, 1, true))
        assertEquals("JSON over 1 MiB", DeviceSend.TooLong, P.deviceSends(listOf("\u0001".repeat(200_000)), true, 1, true))
        assertEquals(DeviceSend.Nothing, P.deviceSends(listOf("abc"), false, 1, true))
        assertEquals(DeviceSend.Nothing, P.deviceSends(listOf("abc"), true, null, true))
        assertEquals(DeviceSend.Nothing, P.deviceSends(listOf("abc"), true, 1, false))

        assertTrue(P.RECEIPT_LENGTH == 16 && P.RECEIPT_LENGTH != 8)
        assertTrue(P.receiptWait(null) == 4.0 && P.receiptWait(0) == 4.0 && P.receiptWait(1000) == 4.0 && P.receiptWait(-1) == 4.0)
        assertTrue(P.receiptWait(2500) == 10.0 && P.receiptWait(1001) > 4.0)
        assertEquals("Didn’t reach Mac mini. Try Paste again.", C.notSent("Mac mini"))

        for (rightAway in listOf(false, true)) for (late in listOf(false, true)) for (streaming in listOf(false, true))
            for (host in listOf(null, 0, 1, 2)) for (tour in listOf(false, true)) {
                assertEquals("rightAway $rightAway, late $late, streaming $streaming, host $host, tour $tour",
                    rightAway && !late && streaming && (host ?: 0) >= 1 && !tour, P.pastesOnReceipt(rightAway, late, streaming, host, tour))
            }
        assertEquals(0x19, P.PASTE_KEY)
        assertTrue(P.PASTE_DELAY > 0 && P.PASTE_DELAY <= 0.1)
        assertEquals("Pasted on Mac mini." to "Pasted on Mac mini.", P.receiptNote(true, "Mac mini"))
        assertEquals(C.sent("Mac mini") to C.sentSpoken("Mac mini"), P.receiptNote(false, "Mac mini"))

        assertEquals(ClipboardPolicy.Corner.TOP_TRAILING, P.chipCorner(landscape = true, linkLineShown = false))
        assertEquals(ClipboardPolicy.Corner.BOTTOM_TRAILING, P.chipCorner(landscape = true, linkLineShown = true))
        assertEquals(ClipboardPolicy.Corner.BOTTOM_TRAILING, P.chipCorner(landscape = false, linkLineShown = false))
    }

    // MARK: words

    @Test fun words() {
        assertEquals("256 KB", C.cap)
        assertEquals("Paste on Mac mini", C.chipTitle("Mac mini"))
        assertEquals("Then press ⌘V there.", C.CHIP_SUBTITLE)
        assertEquals("Paste on Mac mini. Sends the text you copied on this tablet to Mac mini’s clipboard. Then press Command-V there.", C.chipSpoken("Mac mini", "tablet"))
        assertEquals("Sends the text you copied on this phone to Mac mini’s clipboard.", C.pasteHint("Mac mini", "phone"))
        assertEquals("Sent to Mac mini’s clipboard. Press ⌘V there.", C.sent("Mac mini"))
        assertEquals("Sent to Mac mini’s clipboard. Press Command-V there.", C.sentSpoken("Mac mini"))
        assertEquals("Too long to send: Mac mini takes up to 256 KB of text.", C.tooLong("Mac mini"))
        assertTrue(C.SWITCH_TITLE == "Share Clipboard" && C.rowTitle("Mac mini") == "Paste on Mac mini" && C.DISMISS == "Dismiss" && C.PASTE == "Paste")
        assertEquals("Text you copy on Mac mini is ready to paste on this tablet. To paste on Mac mini, copy text here, tap Paste, then press ⌘V there.",
            C.footnote(on = true, mac = "Mac mini", device = "tablet"))
        assertEquals("Nothing you copy goes between this phone and Mac mini while this is off.", C.footnote(on = false, mac = "Mac mini", device = "phone"))
        assertEquals("Where the cursor is.", C.chipSubtitle(true))
        assertTrue(C.chipSubtitle(true).length <= C.CHIP_SUBTITLE.length)
        assertEquals(C.CHIP_SUBTITLE, C.chipSubtitle(false))
        assertEquals("Paste on Mac mini. Pastes the text you copied on this tablet where Mac mini’s cursor is.", C.chipSpoken("Mac mini", "tablet", true))
        assertEquals(C.chipSpoken("Mac mini", "tablet"), C.chipSpoken("Mac mini", "tablet", false))
        assertEquals("Pastes the text you copied on this phone where Mac mini’s cursor is.", C.pasteHint("Mac mini", "phone", true))
        assertEquals(C.pasteHint("Mac mini", "phone"), C.pasteHint("Mac mini", "phone", false))
        assertEquals("Pasted on Mac mini.", C.pasted("Mac mini"))
        assertEquals("Paste on Mac mini Right Away", C.rightAwayTitle("Mac mini"))
        assertEquals("Text you copy on Mac mini is ready to paste on this tablet. To paste on Mac mini, copy text here and tap Paste: it goes where Mac mini’s cursor is. With Paste on Mac mini Right Away off, Paste only puts it on Mac mini’s clipboard.",
            C.footnote(on = true, rightAway = true, mac = "Mac mini", device = "tablet"))
        assertEquals(C.footnote(true, "Mac mini", "tablet"), C.footnote(true, false, "Mac mini", "tablet"))
        assertEquals(C.footnote(false, "Mac mini", "tablet"), C.footnote(false, true, "Mac mini", "tablet"))
        assertEquals(C.footnote(false, "Mac mini", "tablet"), C.footnote(false, false, "Mac mini", "tablet"))
        val all = listOf(
            C.chipTitle("M"), C.CHIP_SUBTITLE, C.chipSpoken("M", "phone"), C.pasteHint("M", "phone"), C.sent("M"), C.sentSpoken("M"),
            C.tooLong("M"), C.SWITCH_TITLE, C.rowTitle("M"), C.footnote(true, "M", "phone"), C.footnote(false, "M", "phone"),
            C.notSent("M"), C.chipAnnouncement("M"), C.chipSubtitle(true), C.chipSpoken("M", "phone", true), C.pasteHint("M", "phone", true),
            C.pasted("M"), C.rightAwayTitle("M"), C.footnote(true, true, "M", "phone"), C.DISMISS, C.PASTE,
        )
        assertTrue("no dashes", all.none { it.contains("—") || it.contains("–") })
        assertTrue("typographic quotes only", all.none { it.contains("'") || it.contains("\"") })
    }

    // MARK: models

    /**
     * Model 1: the Mac's setting and the device's switch flipped at random; the device follows
     * deviceSubscription, the host takes each message by fromDevice and forgets every subscription when
     * its setting goes off. After every step the two agree, the device is subscribed exactly while both
     * are on, and nothing went to a host that did not take it.
     */
    @Test fun subscriptionModel() {
        val rng = Random(32)
        var failures = 0
        repeat(5_000) {
            var hostOn = rng.nextBoolean(); var switchOn = rng.nextBoolean()
            var deviceSubscribed = false; var hostSubscribed = false
            for (step in 0 until rng.nextInt(1, 31)) {
                if (rng.nextBoolean()) { hostOn = !hostOn; if (!hostOn) hostSubscribed = false } else switchOn = !switchOn
                val (send, after) = P.deviceSubscription(if (hostOn) P.GENERATION else null, switchOn, deviceSubscribed)
                if (send != Subscription.NONE) {
                    if (!hostOn) { failures++; return@repeat }
                    when (P.fromDevice(if (send == Subscription.SUBSCRIBE) P.subscription else P.stopping)) {
                        FromDevice.Subscribe, is FromDevice.Set -> hostSubscribed = true
                        FromDevice.Stop -> hostSubscribed = false
                        is FromDevice.Refuse -> failures++
                    }
                }
                deviceSubscribed = after
                if (deviceSubscribed != hostSubscribed || deviceSubscribed != (hostOn && switchOn)) { failures++; return@repeat }
            }
        }
        assertEquals("the device's subscription and the host's agree", 0, failures)
    }

    /**
     * Model 1b: the same, quickly, with a Paste now and then, each message taken by the host's rate. The
     * host never has the device subscribed while its switch is off: a stop is never lost to the rate.
     */
    @Test fun quickSubscriptionModel() {
        val rng = Random(64)
        var failures = 0
        var refused = 0
        repeat(5_000) {
            var hostOn = true; var switchOn = rng.nextBoolean()
            var deviceSubscribed = false; var hostSubscribed = false
            var arrivals = listOf<Double>(); var now = 0.0
            for (step in 0 until rng.nextInt(1, 41)) {
                now += rng.nextDouble(0.0, 0.4)
                var payload: ByteArray? = null
                when (rng.nextInt(8)) {
                    0 -> { hostOn = !hostOn; if (!hostOn) { hostSubscribed = false; arrivals = listOf() } }
                    1 -> (P.deviceSends(listOf("pasted"), true, if (hostOn) P.GENERATION else null, switchOn) as? DeviceSend.Send)?.let { payload = it.payload }
                    else -> switchOn = !switchOn
                }
                val (send, after) = P.deviceSubscription(if (hostOn) P.GENERATION else null, switchOn, deviceSubscribed)
                deviceSubscribed = after
                if (send != Subscription.NONE) payload = if (send == Subscription.SUBSCRIBE) P.subscription else P.stopping
                payload?.let { p ->
                    if (!hostOn) { failures++; return@repeat }
                    val (ask, kept) = P.hostReceives(p, arrivals, now)
                    arrivals = kept
                    when (ask) {
                        FromDevice.Subscribe, is FromDevice.Set -> hostSubscribed = true
                        FromDevice.Stop -> hostSubscribed = false
                        is FromDevice.Refuse -> if (ask.why == Refusal.RATE) { if (send == Subscription.SUBSCRIBE) refused++ } else failures++
                    }
                }
                if (hostSubscribed && !(hostOn && switchOn)) { failures++; return@repeat }
            }
        }
        assertEquals("never subscribed at the host while the switch is off", 0, failures)
        assertTrue("the model reached the rate", refused > 0)
    }

    /** The nonce of a receipt never passes for an ordinary ping's payload (8 bytes, the time it went). */
    @Test fun receiptNonce() {
        val ping = me.saffer.sill.android.protocol.Payloads.ping(123.456)
        assertEquals(8, ping.size)
        assertFalse(ping.size == P.RECEIPT_LENGTH)
    }
}
