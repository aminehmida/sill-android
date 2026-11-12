package me.saffer.sill.android.audio

import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64

// The Mac's sound on the device (kind 43; StreamProtocol's Audio.swift, docs/audio-plan.md §3). A kind 43
// payload's first byte says what follows: type 1, how to decode what follows (JSON `AudioFormat`); type 2,
// codec packets of one epoch (binary `AudioPackets`). A reader skips a type it does not know.
//
// Pure: no Android types, so it is unit tested on the JVM against Audio.swift's own checks.

/** The codecs by name, as the hello and the format carry them (Audio.swift's AudioCodec). */
object AudioCodec {
    /** AAC-ELD: 48 kHz stereo, 480 or 512 frames a packet (10 or 10.7 ms), 128 kbps. */
    const val AAC_ELD = "aac-eld"
}

/**
 * Host → device (kind 43, type 1): how to decode what follows. Sent before a device's first packet of an
 * epoch, and again for every new epoch. Every field optional, so `{}` decodes. The numbers are Swift's
 * `Int` (64 bits), kept as Long so a value Swift takes is taken here too.
 */
class AudioFormat(
    /** "aac-eld" in this step. A device that does not play it ignores the sound. */
    val codec: String? = null,
    /** 48000. */
    val sampleRate: Long? = null,
    /** 2. */
    val channels: Long? = null,
    /** 480 or 512: the frames each packet decodes to. */
    val framesPerPacket: Long? = null,
    /** 240 or 256: the decoded frames a new segment starts with that are the codec's own (dropped). */
    val primingFrames: Long? = null,
    /** The encoder's target in bits per second (display only). */
    val bitrate: Long? = null,
    /** The epoch of the packets this format belongs to (`AudioPackets.epoch`, 0 to 65535). */
    val epoch: Long? = null,
    /**
     * The codec's magic cookie, base64 in the JSON. Audio.swift calls it AAC-ELD's AudioSpecificConfig;
     * what AudioToolbox hands the Mac is an MPEG-4 ES_Descriptor wrapping it (EldConfig unwraps it).
     */
    val cookie: ByteArray? = null,
    /** What the sound is of, display only: "app" (then `app` names it), "desktop" or "test". */
    val source: String? = null,
    /** "Safari": the app whose sound this is. */
    val app: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is AudioFormat && codec == other.codec &&
        sampleRate == other.sampleRate && channels == other.channels && framesPerPacket == other.framesPerPacket &&
        primingFrames == other.primingFrames && bitrate == other.bitrate && epoch == other.epoch &&
        (cookie == null && other.cookie == null || cookie != null && other.cookie != null && cookie.contentEquals(other.cookie)) &&
        source == other.source && app == other.app

    override fun hashCode(): Int = listOf(codec, sampleRate, channels, framesPerPacket, primingFrames, bitrate, epoch,
        cookie?.contentHashCode(), source, app).hashCode()

    override fun toString(): String = "AudioFormat(codec=$codec, sampleRate=$sampleRate, channels=$channels, " +
        "framesPerPacket=$framesPerPacket, primingFrames=$primingFrames, bitrate=$bitrate, epoch=$epoch, " +
        "cookie=${cookie?.size} bytes, source=$source, app=$app)"

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_DESKTOP = "desktop"
        const val SOURCE_TEST = "test"

        /**
         * The JSON as Swift's JSONDecoder reads it: unknown keys skipped, null as absent, a number that is
         * not a whole 64-bit integer or a value of the wrong type fails the whole format (nil), and the
         * cookie strict base64 (padded, nothing outside the alphabet). Never throws.
         */
        fun parseJson(bytes: ByteArray): AudioFormat? = try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            AudioFormat(
                codec = string(o, "codec"), sampleRate = long(o, "sampleRate"), channels = long(o, "channels"),
                framesPerPacket = long(o, "framesPerPacket"), primingFrames = long(o, "primingFrames"),
                bitrate = long(o, "bitrate"), epoch = long(o, "epoch"), cookie = data(o, "cookie"),
                source = string(o, "source"), app = string(o, "app"),
            )
        } catch (_: Exception) {
            null
        }

        private class Mismatch : Exception()

        private fun long(o: JSONObject, key: String): Long? {
            if (o.isNull(key)) return null
            return when (val v = o.get(key)) {
                is Int -> v.toLong()
                is Long -> v
                is Short -> v.toLong()
                is Byte -> v.toLong()
                is BigInteger -> if (v.bitLength() < 64) v.toLong() else throw Mismatch()
                is BigDecimal -> try { v.longValueExact() } catch (_: ArithmeticException) { throw Mismatch() }
                is Number -> {
                    val d = v.toDouble()
                    // 1.0 and 1e3 are whole; 1.5 is not. Past ±2^63 Swift refuses it too.
                    if (!d.isFinite() || d != Math.floor(d) || d >= 9.223372036854775807E18 || d < -9.223372036854775808E18) throw Mismatch()
                    d.toLong()
                }
                else -> throw Mismatch()   // a string, a bool, an object, an array
            }
        }

        private fun string(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            return o.get(key) as? String ?: throw Mismatch()
        }

        private fun data(o: JSONObject, key: String): ByteArray? {
            val s = string(o, key) ?: return null
            // Foundation's Data(base64Encoded:): padding required, no whitespace, standard alphabet.
            if (s.length % 4 != 0) throw Mismatch()
            return try { Base64.getDecoder().decode(s) } catch (_: IllegalArgumentException) { throw Mismatch() }
        }
    }
}

/**
 * Host → device (kind 43, type 2): one or more codec packets of one epoch, back to back. The message
 * header's time stamp is the host's wall clock of the first decoded sample of its first packet; the
 * packets after it follow `framesPerPacket / sampleRate` apart. Big endian:
 *
 *     type 2 · epoch UInt16 · seq UInt32 · flags UInt8 · count UInt8 · count × (length UInt16 · bytes)
 *
 * flags bit 0: the first packet is the first of a segment (reset the decoder, drop the format's
 * `primingFrames`); bits 1 to 7 are ignored.
 */
class AudioPackets(
    /** 0 to 65535 (UInt16 on the wire, wrapping). */
    val epoch: Int,
    /** The first packet's number in its epoch, from 0: an unsigned 32-bit value. */
    val seq: Long,
    /** Flags bit 0. */
    val segmentStart: Boolean,
    /** 1 to 255 packets of at most 65,535 bytes each. */
    val packets: List<ByteArray>,
) {
    /** The payload, its type byte included; the epoch modulo 65,536, at most 255 packets of 65,535 bytes. */
    fun serialized(): ByteArray {
        val kept = packets.take(MAX_COUNT)
        val out = java.io.ByteArrayOutputStream(HEADER_BYTES + kept.sumOf { 2 + minOf(it.size, MAX_PACKET_BYTES) })
        out.write(AudioMessage.TYPE_PACKETS.toInt())
        out.write(epoch ushr 8 and 0xFF); out.write(epoch and 0xFF)
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((seq ushr shift).toInt() and 0xFF)
        out.write(if (segmentStart) 1 else 0)
        out.write(kept.size)
        for (p in kept) {
            val n = minOf(p.size, MAX_PACKET_BYTES)
            out.write(n ushr 8); out.write(n and 0xFF)
            out.write(p, 0, n)
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is AudioPackets && epoch == other.epoch && seq == other.seq &&
        segmentStart == other.segmentStart && packets.size == other.packets.size &&
        packets.indices.all { packets[it].contentEquals(other.packets[it]) }

    override fun hashCode(): Int = listOf(epoch, seq, segmentStart, packets.map { it.contentHashCode() }).hashCode()

    override fun toString(): String = "AudioPackets(epoch=$epoch, seq=$seq, segmentStart=$segmentStart, ${packets.map { it.size }})"

    companion object {
        const val MAX_COUNT = 255
        const val MAX_PACKET_BYTES = 65_535
        /** Before the packets: the type, epoch, seq, flags and count. */
        const val HEADER_BYTES = 9
    }
}

/** A kind 43 payload, either type. */
sealed class AudioMessage {
    data class Format(val format: AudioFormat) : AudioMessage()
    data class Packets(val packets: AudioPackets) : AudioMessage()

    companion object {
        const val TYPE_FORMAT: Byte = 1
        const val TYPE_PACKETS: Byte = 2
        /** The largest payload of each type a reader takes; a device drops a message past them whole. */
        const val MAX_FORMAT_PAYLOAD = 4096
        const val MAX_PACKETS_PAYLOAD = 16384

        /**
         * Nil for an empty payload, a type this build does not know, a format over 4 KB or one that does
         * not decode, and for packets over 16 KB, a header cut short, a count of 0, a length past the end
         * or bytes left over. Never throws, whatever the input.
         */
        fun parse(payload: ByteArray): AudioMessage? {
            if (payload.isEmpty()) return null
            return when (payload[0]) {
                TYPE_FORMAT -> {
                    if (payload.size > MAX_FORMAT_PAYLOAD) return null
                    AudioFormat.parseJson(payload.copyOfRange(1, payload.size))?.let { Format(it) }
                }
                TYPE_PACKETS -> {
                    if (payload.size > MAX_PACKETS_PAYLOAD || payload.size < AudioPackets.HEADER_BYTES) return null
                    fun u8(i: Int) = payload[i].toInt() and 0xFF
                    val epoch = u8(1) shl 8 or u8(2)
                    val seq = (u8(3).toLong() shl 24) or (u8(4).toLong() shl 16) or (u8(5).toLong() shl 8) or u8(6).toLong()
                    val flags = u8(7)
                    val count = u8(8)
                    if (count < 1) return null
                    val packets = ArrayList<ByteArray>(count)
                    var offset = AudioPackets.HEADER_BYTES
                    repeat(count) {
                        if (offset + 2 > payload.size) return null
                        val length = u8(offset) shl 8 or u8(offset + 1)
                        offset += 2
                        if (offset + length > payload.size) return null
                        packets.add(payload.copyOfRange(offset, offset + length))
                        offset += length
                    }
                    if (offset != payload.size) return null
                    Packets(AudioPackets(epoch, seq, flags and 1 == 1, packets))
                }
                else -> null
            }
        }
    }
}
