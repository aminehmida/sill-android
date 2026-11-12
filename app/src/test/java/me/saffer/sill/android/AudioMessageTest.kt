package me.saffer.sill.android

import me.saffer.sill.android.audio.AudioFormat
import me.saffer.sill.android.audio.AudioMessage
import me.saffer.sill.android.audio.AudioPackets
import me.saffer.sill.android.audio.EldConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * Kind 43 (audio/AudioMessage.kt) against Audio.swift's checks (Tests/checks/protocol and compatibility),
 * and the cookie (audio/EldConfig.kt) against what AudioToolbox's AAC-ELD encoder really hands the Mac
 * (read on a Mac with Sources/SillHost/AudioEncoder.swift: 480 and 512 frames, 48 kHz stereo, 128 kbps).
 */
class AudioMessageTest {
    private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun packets(epoch: Int, seq: Long, start: Boolean, list: List<ByteArray>) = AudioPackets(epoch, seq, start, list).serialized()
    private fun format(json: String) = byteArrayOf(1) + json.toByteArray()

    private val eld = ByteArray(162) { (it * 7 + 1).toByte() }
    private val example = packets(3, 0, true, listOf(eld))

    @Test fun packetsAsTheMacWritesThem() {
        assertEquals("the plan's example: 02 0003 00000000 01 01 00A2", "0200030000000001010" + "0a2", hex(example.copyOf(11)))
        assertEquals(173, example.size)
        assertArrayEquals(eld, example.copyOfRange(11, 173))
        assertEquals(AudioMessage.Packets(AudioPackets(3, 0, true, listOf(eld))), AudioMessage.parse(example))
        val many = (0 until 255).map { i -> ByteArray(i % 40) { i.toByte() } }
        assertEquals("255 packets (some empty), the top epoch and a large seq",
            AudioMessage.Packets(AudioPackets(65_535, 4_000_000_000L, false, many)), AudioMessage.parse(packets(65_535, 4_000_000_000L, false, many)))
        assertEquals("the epoch modulo 65,536", AudioMessage.Packets(AudioPackets(4464, 1, false, listOf(eld))), AudioMessage.parse(packets(70_000, 1, false, listOf(eld))))
        assertEquals("seq and epoch big endian", "020102030405060001", hex(packets(0x0102, 0x03040506, false, listOf(byteArrayOf(9))).copyOf(9)))
        val flagged = example.copyOf(); flagged[7] = 0x03
        assertEquals("bit 0 is the segment's start; bits 1–7 ignored", AudioMessage.Packets(AudioPackets(3, 0, true, listOf(eld))), AudioMessage.parse(flagged))
        flagged[7] = 0xFE.toByte()
        assertEquals("0xFE: no segment start", AudioMessage.Packets(AudioPackets(3, 0, false, listOf(eld))), AudioMessage.parse(flagged))
        val big = ByteArray(16_384 - 9 - 2) { 1 }
        assertNotNull("16,384 bytes are taken", AudioMessage.parse(packets(1, 1, false, listOf(big))))
        assertNull("over 16 KB", AudioMessage.parse(packets(1, 1, false, listOf(big + byteArrayOf(1)))))
        val capped = AudioMessage.parse(packets(1, 1, false, List(300) { byteArrayOf(1) })) as AudioMessage.Packets
        assertEquals("at most 255 packets are written", 255, capped.packets.packets.size)
    }

    @Test fun whatAReaderRefuses() {
        fun refused(what: String, d: ByteArray) = assertNull(what, AudioMessage.parse(d))
        refused("an empty payload", ByteArray(0))
        refused("an unknown type (3)", byteArrayOf(3) + example.copyOfRange(1, example.size))
        refused("type 0", byteArrayOf(0) + example.copyOfRange(1, example.size))
        refused("type 1 with no JSON", byteArrayOf(1))
        refused("type 1 that is not JSON", format("aac"))
        refused("type 1 that is a JSON array", format("[]"))
        refused("type 1 over 4 KB", format("{\"app\":\"" + "a".repeat(4100) + "\"}"))
        assertNotNull("4,096 bytes are taken", AudioMessage.parse(format("{\"app\":\"" + "a".repeat(4085) + "\"}")))
        refused("type 2 with only its type", byteArrayOf(2))
        refused("type 2 cut short in its header", example.copyOf(8))
        refused("a count of 0", byteArrayOf(2, 0, 3, 0, 0, 0, 0, 1, 0))
        refused("a count of 0 and bytes after it", byteArrayOf(2, 0, 3, 0, 0, 0, 0, 1, 0, 0, 1, 7))
        refused("a length past the end", example.copyOf(example.size - 1))
        refused("bytes left over", example + byteArrayOf(0))
        refused("missing its second packet", packets(3, 0, false, listOf(eld, eld)).copyOf(9 + 2 + 162 + 1))
        val countTooHigh = packets(3, 0, false, listOf(eld)); countTooHigh[8] = 2
        refused("counting a packet it does not carry", countTooHigh)
    }

    @Test fun neverThrows() {
        val r = Random(20260927)
        var parsed = 0
        repeat(4000) { i ->
            var d: ByteArray
            if (i < 2000) {
                d = ByteArray(r.nextInt(0, 41)) { r.nextInt(256).toByte() }
                if (i % 3 == 0 && d.isNotEmpty()) d[0] = 2
                if (i % 3 == 1 && d.isNotEmpty()) d[0] = 1
            } else {
                val list = List(r.nextInt(1, 5)) { ByteArray(r.nextInt(0, 21)) { r.nextInt(256).toByte() } }
                d = packets(r.nextInt(0, 65_536), r.nextLong(0, 0x1_0000_0000L), r.nextBoolean(), list)
                when (r.nextInt(4)) {
                    0 -> d = d.copyOf(r.nextInt(0, d.size + 1))
                    1 -> { val k = r.nextInt(d.size); d[k] = (d[k].toInt() xor r.nextInt(1, 256)).toByte() }
                    2 -> d += r.nextInt(256).toByte()
                }
            }
            val m = AudioMessage.parse(d) ?: return@repeat
            parsed++
            if (m is AudioMessage.Packets) {
                val again = m.packets.serialized()
                again[7] = d[7]   // the flags' ignored bits are not kept
                assertArrayEquals("type 2 parsed writes the same bytes back", d, again)
            }
        }
        assertTrue(parsed > 0)
    }

    @Test fun theFormat() {
        val f = AudioMessage.parse(format("""{"app":"Safari","bitrate":128000,"channels":2,"codec":"aac-eld","cookie":"+PAhLAA=","epoch":3,"framesPerPacket":480,"primingFrames":240,"sampleRate":48000,"source":"app"}"""))
        val want = AudioFormat("aac-eld", 48000, 2, 480, 240, 128_000, 3, byteArrayOf(0xF8.toByte(), 0xF0.toByte(), 0x21, 0x2C, 0x00), AudioFormat.SOURCE_APP, "Safari")
        assertEquals("every field, the cookie in base64", AudioMessage.Format(want), f)
        assertEquals("{} is a format with nothing in it", AudioMessage.Format(AudioFormat()), AudioMessage.parse(format("{}")))
        assertEquals("a later host's: unknown keys skipped, an unknown codec kept", AudioMessage.Format(AudioFormat(codec = "opus", channels = 6)),
            AudioMessage.parse(format("""{"codec":"opus","v":2,"channels":6}""")))
        assertEquals("null as absent", AudioMessage.Format(AudioFormat()), AudioMessage.parse(format("""{"epoch":null,"cookie":null,"app":null}""")))
        // As Swift's JSONDecoder: whole numbers only (1.0 and 1e3 are), the right types, strict base64.
        assertEquals(AudioMessage.Format(AudioFormat(epoch = 1)), AudioMessage.parse(format("""{"epoch":1.0}""")))
        assertEquals(AudioMessage.Format(AudioFormat(epoch = 1000)), AudioMessage.parse(format("""{"epoch":1e3}""")))
        assertEquals(AudioMessage.Format(AudioFormat(bitrate = Long.MAX_VALUE)), AudioMessage.parse(format("""{"bitrate":9223372036854775807}""")))
        for (bad in listOf("""{"epoch":1.5}""", """{"epoch":"3"}""", """{"epoch":true}""", """{"bitrate":9223372036854775808}""",
            """{"codec":1}""", """{"cookie":"not base64!"}""", """{"cookie":"YQ"}""", """{"cookie":"Y Q=="}""", """{"cookie":"-_=="}""", """{"cookie":3}""")) {
            assertNull("refused: $bad", AudioMessage.parse(format(bad)))
        }
        assertArrayEquals("the cookie, padded", byteArrayOf(97), (AudioMessage.parse(format("""{"cookie":"YQ=="}""")) as AudioMessage.Format).format.cookie)
    }

    // MARK: - The cookie

    /** AudioToolbox's kAudioConverterCompressionMagicCookie for AAC-ELD, 480 and 512 frames (an ES_Descriptor). */
    private val cookie480 = "0380808024000000048080801640140018000001f4000001f4000580808004f8e65000068080800102"
    private val cookie512 = "0380808024000000048080801640140018000001f4000001f4000580808004f8e64000068080800102"

    @Test fun theMacsCookieUnwrapped() {
        assertEquals("A4CAgCQAAAAEgICAFkAUABgAAAH0AAAB9AAFgICABPjmUAAGgICAAQI=", Base64.getEncoder().encodeToString(unhex(cookie480)))
        val asc480 = EldConfig.audioSpecificConfig(unhex(cookie480))!!
        assertEquals("the DecoderSpecificInfo of the ES_Descriptor", "f8e65000", hex(asc480))
        assertEquals(EldConfig.Info(39, 48000, 2, 480, false), EldConfig.parse(asc480))
        val asc512 = EldConfig.audioSpecificConfig(unhex(cookie512))!!
        assertEquals("f8e64000", hex(asc512))
        assertEquals(EldConfig.Info(39, 48000, 2, 512, false), EldConfig.parse(asc512))
        assertEquals("an esds box's body with its version and flags", "f8e65000", hex(EldConfig.audioSpecificConfig(unhex("00000000$cookie480"))!!))
        assertEquals("a bare AudioSpecificConfig is taken as it is", "f8e65000", hex(EldConfig.audioSpecificConfig(unhex("f8e65000"))!!))
        assertEquals("Audio.swift's test cookie: ELD at 16 kHz mono, 512, LD-SBR", EldConfig.Info(39, 16000, 1, 512, true), EldConfig.parse(unhex("f8f0212c00")))
        assertEquals("AAC-LC 44.1 kHz stereo", EldConfig.Info(2, 44100, 2, 1024, false), EldConfig.parse(unhex("1210")))
        assertNull("an empty cookie", EldConfig.audioSpecificConfig(ByteArray(0)))
        assertNull("a descriptor cut short", EldConfig.audioSpecificConfig(unhex(cookie480).copyOf(30)))
        assertNull("a descriptor with no DecoderSpecificInfo", EldConfig.audioSpecificConfig(unhex("0380808005000000060102")))
        assertNull("a config cut short", EldConfig.parse(unhex("f8")))
        val r = Random(7)
        repeat(3000) {
            val d = if (it % 2 == 0) ByteArray(r.nextInt(0, 50)) { r.nextInt(256).toByte() }
            else unhex(cookie480).also { c -> c[r.nextInt(c.size)] = r.nextInt(256).toByte() }.copyOf(r.nextInt(0, 42))
            EldConfig.audioSpecificConfig(d)?.let { asc -> EldConfig.parse(asc) }
        }
    }
}
