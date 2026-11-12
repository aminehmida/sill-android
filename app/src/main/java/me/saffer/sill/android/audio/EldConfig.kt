package me.saffer.sill.android.audio

/**
 * The format's cookie, as Android's AAC decoder takes it. AudioToolbox's kAudioConverterCompressionMagicCookie
 * for AAC-ELD, which the Mac sends as is, is an MPEG-4 ES_Descriptor (an `esds` box's body, 41 bytes):
 *
 *     03 len  ES_ID(2) flags(1)  04 len  objectType(1) streamType(1) bufferSize(3) maxBitrate(4) avgBitrate(4)
 *                                        05 len  AudioSpecificConfig   06 len  SLConfig
 *
 * MediaCodec's `csd-0` for audio/mp4a-latm is the bare AudioSpecificConfig (4 bytes for ELD: AOT 39,
 * 48 kHz, stereo, its frame length, no SBR), as MediaExtractor gives it. A cookie that is already a bare
 * AudioSpecificConfig is taken as it is. Pure; never throws.
 */
object EldConfig {
    /** AAC-ELD's audio object type (ER AAC ELD). */
    const val AOT_ELD = 39
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** What an AudioSpecificConfig says. `frameLength` is 480 or 512 for ELD (its frameLengthFlag), else 1024. */
    data class Info(val objectType: Int, val sampleRate: Int, val channels: Int, val frameLength: Int, val sbr: Boolean)

    /** The AudioSpecificConfig in a cookie: the DecoderSpecificInfo of an ES_Descriptor, or the cookie itself. */
    fun audioSpecificConfig(cookie: ByteArray): ByteArray? {
        if (cookie.isEmpty()) return null
        // An `esds` box's body may keep its version and flags (4 zero bytes) before the descriptor.
        val from = if (cookie.size > 5 && cookie[0] == 0.toByte() && cookie[1] == 0.toByte() && cookie[2] == 0.toByte() &&
            cookie[3] == 0.toByte() && cookie[4] in DESCRIPTORS) 4 else 0
        return if (cookie[from] in DESCRIPTORS) find(cookie, from, cookie.size, 0) else cookie.copyOf()
    }

    /** The AudioSpecificConfig's fields, or nil for one cut short or of a form this does not read. */
    fun parse(asc: ByteArray): Info? {
        val r = Bits(asc)
        var aot = r.read(5) ?: return null
        if (aot == 31) aot = 32 + (r.read(6) ?: return null)
        val index = r.read(4) ?: return null
        val rate = if (index == 0xF) r.read(24) ?: return null else RATES.getOrNull(index) ?: return null
        val channels = r.read(4) ?: return null
        if (aot != AOT_ELD) return Info(aot, rate, channels, 1024, aot == 5 || aot == 29)
        // ELDSpecificConfig: frameLengthFlag, three resilience flags, ldSbrPresentFlag.
        val frameLengthFlag = r.read(1) ?: return null
        r.read(3) ?: return null
        val sbr = r.read(1) ?: return null
        return Info(aot, rate, channels, if (frameLengthFlag == 1) 480 else 512, sbr == 1)
    }

    private val DESCRIPTORS = byteArrayOf(0x03, 0x04, 0x05)

    /** The DecoderSpecificInfo (tag 5) among the descriptors in [from, end). */
    private fun find(d: ByteArray, from: Int, end: Int, depth: Int): ByteArray? {
        if (depth > 4) return null
        var i = from
        while (i < end) {
            val tag = d[i].toInt() and 0xFF
            i++
            // The size: up to four bytes of seven bits, the top bit saying another follows.
            var length = 0
            var n = 0
            while (true) {
                if (i >= end || n == 4) return null
                val b = d[i].toInt() and 0xFF
                i++; n++
                length = length shl 7 or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            if (length < 0 || length > end - i) return null
            val body = i
            val bodyEnd = i + length
            when (tag) {
                0x05 -> return d.copyOfRange(body, bodyEnd)
                0x03 -> {
                    // ES_Descriptor: ES_ID, then flags, and what they say follows, then its descriptors.
                    if (length < 3) return null
                    var j = body + 2
                    val flags = d[j].toInt() and 0xFF
                    j++
                    if (flags and 0x80 != 0) j += 2
                    if (flags and 0x40 != 0) { if (j >= bodyEnd) return null; j += 1 + (d[j].toInt() and 0xFF) }
                    if (flags and 0x20 != 0) j += 2
                    if (j > bodyEnd) return null
                    find(d, j, bodyEnd, depth + 1)?.let { return it }
                }
                // DecoderConfigDescriptor: 13 bytes of its own, then its descriptors.
                0x04 -> if (length >= 13) find(d, body + 13, bodyEnd, depth + 1)?.let { return it }
            }
            i = bodyEnd
        }
        return null
    }

    private class Bits(private val d: ByteArray) {
        private var bit = 0
        fun read(n: Int): Int? {
            if (bit + n > d.size * 8) return null
            var v = 0
            repeat(n) {
                val b = (d[bit ushr 3].toInt() ushr (7 - (bit and 7))) and 1
                v = v shl 1 or b
                bit++
            }
            return v
        }
    }
}
