package me.saffer.sill.android.protocol

import java.nio.ByteBuffer

/** ImageBlob and CursorShapeBlob (Switcher.swift, Viewport.swift): a key, then the image bytes. */
object Blobs {
    class Thumbnail(val windowID: Long, val jpeg: ByteArray)
    class Icon(val bundleID: String, val png: ByteArray)
    /** hotspot and size in Mac points; the PNG is usually 2×. */
    class CursorShape(val hotX: Float, val hotY: Float, val width: Float, val height: Float, val png: ByteArray)

    fun thumbnail(d: ByteArray): Thumbnail? {
        if (d.size <= 4) return null
        val id = ByteBuffer.wrap(d, 0, 4).int.toLong() and 0xFFFFFFFFL
        return Thumbnail(id, d.copyOfRange(4, d.size))
    }

    fun icon(d: ByteArray): Icon? {
        if (d.size <= 2) return null
        val len = ByteBuffer.wrap(d, 0, 2).short.toInt() and 0xFFFF
        if (d.size <= 2 + len) return null
        return Icon(String(d, 2, len, Charsets.UTF_8), d.copyOfRange(2 + len, d.size))
    }

    fun cursorShape(d: ByteArray): CursorShape? {
        if (d.size <= 16) return null
        val b = ByteBuffer.wrap(d)
        return CursorShape(b.float, b.float, b.float, b.float, d.copyOfRange(16, d.size))
    }
}

/**
 * HEVC VPS/SPS/PPS as VideoToolbox gives them (ParameterSets in StreamMessage.swift):
 * nalLen(1) count(1), then per set length(2) and its bytes. Frames carry NAL units each prefixed by
 * a `nalUnitHeaderLength`-byte big-endian length (hvcC style); MediaCodec wants Annex B start codes.
 */
class ParameterSets(val nalLength: Int, val sets: List<ByteArray>) {
    /** VPS, SPS and PPS behind start codes: MediaCodec's csd-0. */
    fun annexB(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in sets) { out.write(START); out.write(s) }
        return out.toByteArray()
    }

    /** The coded picture's size from the SPS (NAL type 33), cropped by its conformance window. */
    fun pictureSize(): Pair<Int, Int>? = sets.firstOrNull { it.size > 2 && ((it[0].toInt() shr 1) and 0x3F) == 33 }?.let { HevcSps.size(it) }

    fun sameAs(other: ParameterSets?): Boolean =
        other != null && other.nalLength == nalLength && other.sets.size == sets.size && sets.indices.all { sets[it].contentEquals(other.sets[it]) }

    companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        fun parse(d: ByteArray): ParameterSets? {
            if (d.size < 2) return null
            val nal = d[0].toInt() and 0xFF
            val count = d[1].toInt() and 0xFF
            var off = 2
            val sets = ArrayList<ByteArray>(count)
            repeat(count) {
                if (d.size < off + 2) return null
                val len = ((d[off].toInt() and 0xFF) shl 8) or (d[off + 1].toInt() and 0xFF)
                off += 2
                if (d.size < off + len) return null
                sets.add(d.copyOfRange(off, off + len))
                off += len
            }
            return ParameterSets(nal, sets)
        }

        /** One access unit from length-prefixed NAL units to Annex B, in place when the prefix is 4 bytes. */
        fun toAnnexB(frame: ByteArray, nalLength: Int): ByteArray? {
            if (nalLength == 4) {
                var off = 0
                while (off + 4 <= frame.size) {
                    val len = ByteBuffer.wrap(frame, off, 4).int
                    if (len < 0 || off + 4 + len > frame.size) return null
                    frame[off] = 0; frame[off + 1] = 0; frame[off + 2] = 0; frame[off + 3] = 1
                    off += 4 + len
                }
                return if (off == frame.size) frame else null
            }
            val out = java.io.ByteArrayOutputStream(frame.size + 64)
            var off = 0
            while (off + nalLength <= frame.size) {
                var len = 0
                for (i in 0 until nalLength) len = (len shl 8) or (frame[off + i].toInt() and 0xFF)
                off += nalLength
                if (len < 0 || off + len > frame.size) return null
                out.write(START)
                out.write(frame, off, len)
                off += len
            }
            return out.toByteArray()
        }
    }
}

/** Just enough of an HEVC SPS (ITU-T H.265 §7.3.2.2) to read the picture's size. */
object HevcSps {
    fun size(nal: ByteArray): Pair<Int, Int>? = runCatching {
        // RBSP: drop the 2-byte NAL header and every emulation prevention byte (00 00 03).
        val rbsp = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (i in 2 until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            rbsp.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        val r = Bits(rbsp.toByteArray())
        r.skip(4)                                   // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.u(3)
        r.skip(1)                                   // sps_temporal_id_nesting_flag
        // profile_tier_level(1, maxSubLayersMinus1)
        r.skip(2 + 1 + 5 + 32 + 4 + 43 + 1 + 8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) { profilePresent[i] = r.u(1) == 1; levelPresent[i] = r.u(1) == 1 }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) r.skip(88)
            if (levelPresent[i]) r.skip(8)
        }
        r.ue()                                      // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var w = r.ue()
        var h = r.ue()
        if (r.u(1) == 1) {
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val left = r.ue(); val right = r.ue(); val top = r.ue(); val bottom = r.ue()
            w -= subW * (left + right)
            h -= subH * (top + bottom)
        }
        if (w <= 0 || h <= 0) null else w to h
    }.getOrNull()

    private class Bits(val d: ByteArray) {
        var pos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = d[pos ushr 3].toInt() and 0xFF
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }
        fun skip(n: Int) { pos += n }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) { zeros++; if (zeros > 31) error("bad ue") }
            return ((1 shl zeros) - 1) + u(zeros)
        }
    }
}
