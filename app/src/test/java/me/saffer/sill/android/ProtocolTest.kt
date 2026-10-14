package me.saffer.sill.android

import me.saffer.sill.android.client.MenuState
import me.saffer.sill.android.client.SettingsLedger
import me.saffer.sill.android.crypto.Base64Url
import me.saffer.sill.android.crypto.MacId
import me.saffer.sill.android.crypto.PairLink
import me.saffer.sill.android.crypto.PairingCode
import me.saffer.sill.android.crypto.PairingProof
import me.saffer.sill.android.crypto.SafeText
import me.saffer.sill.android.crypto.hex
import me.saffer.sill.android.crypto.sha256
import me.saffer.sill.android.net.DialOrder
import me.saffer.sill.android.net.Endpoint
import me.saffer.sill.android.net.SavedMac
import me.saffer.sill.android.protocol.HevcSps
import me.saffer.sill.android.protocol.HostSettingsState
import me.saffer.sill.android.protocol.Input
import me.saffer.sill.android.protocol.KeyMods
import me.saffer.sill.android.protocol.Kind
import me.saffer.sill.android.protocol.MacAddress
import me.saffer.sill.android.protocol.MacMenu
import me.saffer.sill.android.protocol.MessageReader
import me.saffer.sill.android.protocol.ParameterSets
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.PointerAction
import me.saffer.sill.android.protocol.ScrollPhase
import me.saffer.sill.android.protocol.SettingsChange
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.protocol.Wire
import me.saffer.sill.android.protocol.WindowList
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The Android side against the vectors Sources/StreamProtocol is checked with
 * (Tests/checks/protocol/main.swift, docs/remote-access-plan.md §3.6, computed in Python), and
 * against the JSON the Mac's Codable types write and read.
 */
class ProtocolTest {
    private fun hexOf(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

    // MARK: pairing

    @Test fun pairingProofVectors() {
        val fpMac = sha256("mac".toByteArray())
        val fpDev = sha256("device".toByteArray())
        assertEquals("348a629f5ceed032c3e8706ec47d9bfafb00fb4250b018dd965435ca50cb836e", hexOf(fpMac))
        assertEquals("263a4dbe41488fb87214b0032339dbb9f0c8da14c16dfcf13084bf3c2552eca5", hexOf(fpDev))
        val k = PairingProof.codeKey("482913557208", fpMac)
        assertEquals("e9baf879a1cc4ac8b21554df480b6c1621671319107c91e7cb05d154f649d31d", hexOf(k))
        assertEquals("dk7PZj4vHsAxj--1u9Vg7ehSn8a1tWc0RCG46YpFVO8", Base64Url.encode(PairingProof.deviceProof(k, fpDev, fpMac)))
        assertTrue(PairingProof.isValidMacProof(Base64Url.decode("6BmGWC9XT8J8OY8phhq9bASgoSnoWpRxBeqZtvRrWmk")!!, k, fpMac, fpDev))
        val qr = ByteArray(16) { 0x22 }
        assertEquals("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo", Base64Url.encode(PairingProof.deviceProof(qr, fpDev, fpMac)))
        assertTrue(PairingProof.isValidMacProof(Base64Url.decode("XAX-uZPsq_Ax8fvI_sV7Ex0a-ROi19rI-FW-ykPh9TE")!!, qr, fpMac, fpDev))
        // Swapped fingerprints never check.
        assertFalse(PairingProof.isValidMacProof(Base64Url.decode("XAX-uZPsq_Ax8fvI_sV7Ex0a-ROi19rI-FW-ykPh9TE")!!, qr, fpDev, fpMac))
    }

    @Test fun macIdVectors() {
        assertEquals("A3C5HR4RBV67YR21", MacId.make(hex("50d858e0985ecc7f6041") + ByteArray(22)))
        assertEquals("A3C5HR4RBV67YR21", MacId.make(sha256("example".toByteArray())))
        assertEquals("0000000000000000", MacId.make(ByteArray(32)))
        assertEquals("ZZZZZZZZZZZZZZZZ", MacId.make(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test fun recognitionTagVector() {
        val key = ByteArray(32) { 0x11 }
        assertTrue(me.saffer.sill.android.crypto.RecognitionTag.matches("oaKjpKWmL4V47Ctm", key))
        assertFalse(me.saffer.sill.android.crypto.RecognitionTag.matches("oaKjpKWmL4V47Ctn", key))
        assertFalse(me.saffer.sill.android.crypto.RecognitionTag.matches("oaKjpKWmL4V47Ctm", ByteArray(32) { 0x12 }))
        assertFalse(me.saffer.sill.android.crypto.RecognitionTag.matches("oaKjpKWmL4V47Ct", key))
        assertFalse(me.saffer.sill.android.crypto.RecognitionTag.matches("oaKjpKWmL4V47Ctm=", key))
    }

    @Test fun pairingCodeCheck() {
        assertEquals(PairingCode.Check.Ok("482913557208"), PairingCode.check("4829 1355 7208"))
        assertEquals(PairingCode.Check.Ok("482913557208"), PairingCode.check("4829-1355-7208"))
        assertEquals(PairingCode.Check.Typo, PairingCode.check("4829 1355 7209"))
        assertEquals(PairingCode.Check.Typo, PairingCode.check("4829 1355 7280"))
        assertEquals(PairingCode.Check.Length, PairingCode.check("48291355720"))
        assertEquals(PairingCode.Check.Length, PairingCode.check("٤٨٢٩١٣٥٥٧٢٠٨"))
        assertEquals("4829 1355 7208", PairingCode.grouped("482913557208"))
    }

    @Test fun base64UrlIsCanonical() {
        assertNotNull(Base64Url.decode("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo"))
        assertNull(Base64Url.decode("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR_8JSrCV_gNOo="))   // padding
        assertNull(Base64Url.decode("AyZAJqNTU5nSKEaHx6RZOf0K5dOeygR/8JSrCV+gNOo"))    // the standard alphabet
        assertNull(Base64Url.decode("A"))
    }

    @Test fun pairLinkFromAHost() {
        // A synthetic host's link (its key's Mac ID is m).
        val text = "sill://pair?v=1&m=9MAGJQ55FET6EQ97&k=TRUJXKV7tGddJ4fdpWDw5VxaoJJ6CCrGSdJ58SYXv4M&s=XAs4dXaCYc9mS0w-czOAmQ" +
            "&n=Tom%E2%80%99s%20Mac%20mini%20%282%29&p=47455&a=127.0.0.1&a=100.101.102.103&a=%5Bfd7a:115c:a1e0::1234%5D&a=192.168.1.20"
        val r = PairLink.parse(text) as PairLink.Companion.Result.Ok
        assertEquals("9MAGJQ55FET6EQ97", r.link.macID)
        assertEquals("Tom’s Mac mini (2)", r.link.name)
        assertEquals(47455, r.link.port)
        assertEquals(listOf("127.0.0.1", "100.101.102.103", "[fd7a:115c:a1e0::1234]", "192.168.1.20"), r.link.addresses)
        assertEquals(16, r.link.secret.size)
        // m must be k's Mac ID.
        assertTrue(PairLink.parse(text.replace("m=9MAGJQ55FET6EQ97", "m=9MAGJQ55FET6EQ98")) is PairLink.Companion.Result.Failed)
        assertTrue(PairLink.parse(text.replace("v=1", "v=2")) is PairLink.Companion.Result.Failed)
        assertTrue(PairLink.parse("https://example.com/?v=1") is PairLink.Companion.Result.Failed)
        assertEquals(Endpoint("fd7a:115c:a1e0::1234", 47455), Endpoint.parse(r.link.addresses[2], r.link.port))
    }

    @Test fun safeText() {
        assertEquals("Mac mini", SafeText.label("Mac\u0000‮ mini", 64))
        assertEquals("Mac mini", SafeText.label("  Mac\n\t  mini  ", 64))
        assertEquals("iPad", SafeText.label("‮i​Pad", 64))
        assertEquals("abc", SafeText.label("abcdef", 3))
        assertEquals("ab", SafeText.label("ab cd", 3))
    }

    // MARK: addresses and dial order

    @Test fun endpoints() {
        assertEquals(Endpoint("mac.tail1234.ts.net", 7455), Endpoint.parse("mac.tail1234.ts.net", 7455))
        assertEquals(Endpoint("100.101.102.103", 9000), Endpoint.parse("100.101.102.103:9000", 7455))
        assertEquals(Endpoint("::1", 47455), Endpoint.parse("[::1]:47455", 7455))
        assertEquals(Endpoint("fd7a:115c:a1e0::1", 7455), Endpoint.parse("fd7a:115c:a1e0::1", 7455))
        assertNull(Endpoint.parse("sill://pair?v=1", 7455))
        assertNull(Endpoint.parse("a b", 7455))
        assertTrue(Endpoint.isTailscale("100.64.0.1") && Endpoint.isTailscale("100.127.255.255") && !Endpoint.isTailscale("100.128.0.1"))
        assertTrue(Endpoint.isTailscale("mac.tail1234.ts.net") && Endpoint.isTailscale("fd7a:115c:a1e0::1234"))
    }

    @Test fun dialOrderPutsTailscaleFirstAndLastWorkedFirstOfAll() {
        val mac = SavedMac(
            macID = MacId.make(ByteArray(32)), fingerprint = ByteArray(32), name = "Mac", recognitionKey = null, remotePort = 7455,
            addresses = listOf(
                MacAddress("192.168.1.5", null, "lan", "Wi‑Fi"),
                MacAddress("100.101.102.103", null, "vpn", "Tailscale"),
                MacAddress("mac.tail1234.ts.net", null, "vpn", "Tailscale"),
            ),
            typedAddresses = listOf("100.101.102.103"), infoIssuedAt = 0.0, lastWorked = null, pairedAt = 0.0, lastConnectedAt = null,
        )
        assertEquals(listOf("mac.tail1234.ts.net", "100.101.102.103", "192.168.1.5"), DialOrder.endpoints(mac).map { it.host })
        val worked = mac.copy(lastWorked = "192.168.1.5|7455")
        assertEquals("192.168.1.5", DialOrder.endpoints(worked).first().host)
        // A saved record round-trips, and is refused when its Mac ID is not its key's.
        assertEquals(mac, SavedMac.fromJson(mac.toJson()))
        assertNull(SavedMac.fromJson(mac.toJson().put("macID", "A3C5HR4RBV67YR21")))
    }

    // MARK: video

    @Test fun spsOfAVideoToolboxStream() {
        // VideoToolbox's SPS for a 1512×948 stream (captured from a synthetic host).
        val sps = hex("420101216000000300b0000003000003007ba002f4803b9f79cf924489bf97136008")
        assertEquals(1512 to 948, HevcSps.size(sps))
        val ps = ParameterSets.parse(byteArrayOf(4, 3) + len(hex("40010c01ffff216000000300b0000003000003007b3c09")) + len(sps) + len(hex("4401c0252f053240")))!!
        assertEquals(1512 to 948, ps.pictureSize())
        assertEquals(4, ps.nalLength)
        assertTrue(ps.annexB().copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1)))
    }

    private fun len(b: ByteArray) = byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b

    @Test fun annexB() {
        val frame = byteArrayOf(0, 0, 0, 2, 0x26, 0x01, 0, 0, 0, 1, 0x7F)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0, 0, 0, 1, 0x7F), ParameterSets.toAnnexB(frame.copyOf(), 4))
        assertNull(ParameterSets.toAnnexB(byteArrayOf(0, 0, 0, 9, 1), 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 5, 6), ParameterSets.toAnnexB(byteArrayOf(0, 2, 5, 6), 2))
    }

    // MARK: JSON as the Mac's Codable types have it

    @Test fun inputEvents() {
        assertEquals("""{"pointer":{"_0":"leftDown","x":0.5,"y":0.25}}""", Input.pointer(PointerAction.LEFT_DOWN, 0.5, 0.25).sorted())
        assertEquals("""{"scroll":{"dx":0.01,"dy":-0.02,"x":0.5,"y":0.5}}""", Input.scroll(0.5, 0.5, 0.01, -0.02).sorted())
        assertEquals("""{"scrollGesture":{"_0":"momentumBegan","x":0.1,"y":0.2}}""", Input.scrollGesture(ScrollPhase.MOMENTUM_BEGAN, 0.1, 0.2).sorted())
        assertEquals("hi\n\u0008", JSONObject(Input.text("hi\n\u0008")).getJSONObject("text").getString("_0"))
        assertEquals("""{"key":{"down":true,"hidUsage":44,"modifiers":1048576}}""", Input.key(44, true, KeyMods.COMMAND).sorted())
    }

    @Test fun sourcesAndOthers() {
        assertEquals("""{"desktop":{}}""", Payloads.select(StreamSource.Desktop))
        assertEquals("""{"none":{}}""", Payloads.select(StreamSource.None))
        assertEquals("""{"window":{"_0":42}}""", Payloads.select(StreamSource.Window(42)))
        assertEquals(StreamSource.Window(42), StreamSource.parse(JSONObject("""{"window":{"_0":42}}""")))
        assertEquals("""{"bitrate":15000000,"token":7}""", Payloads.changeSettings(7, SettingsChange(bitrate = 15_000_000)).sorted())
        assertEquals("""{"fingers":3,"gesture":"swipeUp"}""", Payloads.gesture("swipeUp", 3).sorted())
        val hello = JSONObject(Payloads.hello("0.5.1", "1", "Galaxy A15 (SM-A155F)"))
        assertEquals(1, hello.getInt("protocol")); assertEquals("0.5.1", hello.getString("appVersion"))
    }

    @Test fun windowListAndSettingsFromTheMac() {
        val list = WindowList.parse("""{"macName":"Mac","windows":[{"id":7,"title":"T","appName":"A","bundleID":"b","width":800,"height":600}],
            "active":{"window":{"_0":7}},"launchID":"x","hostVersion":"0.5.1","protocol":1,"gestures":1}""".toByteArray())!!
        assertEquals(StreamSource.Window(7), list.active); assertEquals(1, list.gestures); assertEquals(800, list.windows[0].width)
        val s = HostSettingsState.parse("""{"settings":{"maxFPS":60,"bitrate":4000000,"captureScale":1,"prioritizeSpeed":false,"virtualDisplay":false},
            "persistent":true,"virtualDisplayAvailable":true,"softwareEncoder":false,"answering":3,
            "away":{"homeBitrate":15000000,"homeCaptureScale":2,"awayBitrate":4000000,"awayCaptureScale":1,"thisConnectionAway":true,"awayRunning":true}}""".toByteArray())!!
        assertEquals(1.0, s.settings.captureScale, 0.0); assertNull(s.settings.directWireless); assertEquals(3, s.answering)
        assertTrue(s.away!!.awayRunning)
    }

    // MARK: framing

    @Test fun framing() {
        val m = Wire.encode(Kind.PONG, byteArrayOf(1, 2, 3), keyframe = true, timestamp = 12.5)
        assertEquals(17, m.size)
        val read = MessageReader(ByteArrayInputStream(m + Wire.encode(Kind.TICK))) {}.let { r -> listOf(r.next(), r.next()) }
        assertEquals(Kind.PONG, read[0].kind); assertTrue(read[0].isKeyframe); assertEquals(12.5, read[0].timestamp, 0.0)
        assertArrayEquals(byteArrayOf(1, 2, 3), read[0].payload); assertEquals(Kind.TICK, read[1].kind)
        // A header announcing more than any host sends ends the session (an SSH banner reads as 1.7 GB).
        val huge = byteArrayOf(200.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x60, 0, 0, 0)
        assertTrue(runCatching { MessageReader(ByteArrayInputStream(huge)) {}.next() }.isFailure)
    }

    // MARK: the settings ledger and the menus

    private fun state(bitrate: Int, answering: Int? = null) = HostSettingsState.parse(
        """{"settings":{"maxFPS":60,"bitrate":$bitrate,"captureScale":2,"prioritizeSpeed":false,"virtualDisplay":false,"directWireless":false},
        "persistent":true,"virtualDisplayAvailable":false,"softwareEncoder":false${answering?.let { ",\"answering\":$it" } ?: ""}}""".toByteArray())!!

    @Test fun ledger() {
        val l = SettingsLedger()
        assertNull("nothing before the first kind 16", l.pick(SettingsChange(bitrate = 8_000_000), 0))
        l.receive(state(4_000_000))
        assertNull("a pick that changes nothing sends nothing", l.pick(SettingsChange(bitrate = 4_000_000), 0))
        val (token, sent) = l.pick(SettingsChange(bitrate = 15_000_000, maxFPS = 60), 0)!!
        assertEquals(SettingsChange(bitrate = 15_000_000), sent)
        assertEquals(15_000_000, l.shown!!.bitrate)
        assertTrue("an answer to another token keeps the pick", l.receive(state(4_000_000, token + 100)).isEmpty() && l.isPending(SettingsLedger.Field.BITRATE))
        assertEquals(listOf(SettingsLedger.Field.BITRATE), l.receive(state(4_000_000, token)))   // refused
        assertEquals(4_000_000, l.shown!!.bitrate)
        l.pick(SettingsChange(bitrate = 8_000_000), 1_000)
        assertTrue(l.expire(6_000, 4_000)); assertEquals(4_000_000, l.shown!!.bitrate)
    }

    @Test fun menus() {
        val m = MenuState()
        val sub = m.subscribe()
        m.receive(MacMenu.parse("""{"version":3,"app":"Probe","answering":${sub.token},"menus":[{"id":"1","title":"File","submenu":true}]}""".toByteArray())!!)
        assertEquals(3, m.version); assertEquals("File", m.top[0].title)
        val req = m.open(m.top[0], 0)!!
        assertEquals("1", req.id); assertEquals(3, req.version); assertEquals("File", req.title)
        m.receive(MacMenu.parse("""{"version":3,"answering":${req.token},"menu":"1","items":[{"id":"1.0","title":"New","key":"⌘N"},{"separator":true}]}""".toByteArray())!!)
        val items = (m.menus["1"] as MenuState.Menu.Items).items
        assertEquals("⌘N", items[0].key); assertTrue(items[1].separator)
        val press = m.choose(items[0], 0)!!
        assertEquals(25, press.kind)
        assertEquals(MenuState.Outcome.Pressed(false, "gone"), m.receive(MacMenu.parse("""{"version":3,"answering":${press.token},"pressed":false,"note":"gone"}""".toByteArray())!!))
        // A new version settles a menu still loading.
        m.open(m.top[0], 0)
        m.receive(MacMenu.parse("""{"version":4,"menus":[{"id":"1","title":"File"}]}""".toByteArray())!!)
        assertTrue(m.menus["1"] is MenuState.Menu.Failed)
    }

    private fun String.sorted(): String = sortJson(JSONObject(this)).toString()
    private fun sortJson(o: JSONObject): JSONObject {
        val out = JSONObject(java.util.TreeMap<String, Any>())
        val ordered = java.util.TreeMap<String, Any>()
        for (k in o.keys()) ordered[k] = o.get(k).let { if (it is JSONObject) sortJson(it) else it }
        // org.json keeps insertion order only in some versions; build the string by hand.
        return object : JSONObject() {
            override fun toString(): String = "{" + ordered.entries.joinToString(",") { (k, v) -> JSONObject.quote(k) + ":" + (if (v is JSONObject) v.toString() else JSONObject.wrap(v).let { w -> if (w is String) JSONObject.quote(w) else w.toString() }) } + "}"
        }.also { out.length() }
    }
}
