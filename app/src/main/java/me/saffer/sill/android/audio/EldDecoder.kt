package me.saffer.sill.android.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

/**
 * The Mac's sound, decoded (iOSClient's AudioDecoder): AAC-ELD packets in, 48 kHz stereo out as a float
 * buffer per channel, one packet at a time, with MediaCodec in its synchronous mode on the caller's thread.
 *
 * The platform's AAC decoder (Codec2's c2.android.aac.decoder, Fraunhofer's FDK, on every Android 10+)
 * decodes AOT 39 from the AudioSpecificConfig given as `csd-0` (EldConfig unwraps it from the Mac's
 * cookie); the frame length (480 or 512) comes from that config. KEY_AAC_PROFILE is for encoders only and
 * is not set. FDK adds no output delay of its own for ELD without SBR (its limiter is off for low-delay
 * object types), so packet n of a segment decodes, as on the Mac's AudioToolbox, to frames n·F to
 * (n+1)·F − 1 with the segment's first `primingFrames` the codec's own.
 *
 * Reset. MediaCodec.flush() keeps FDK's filter bank history (Codec2's flush clears only its bitstream
 * buffer), so a decoder that must start clean (a new segment, the packet after a gap) is swapped for a
 * fresh one, made beforehand on `maker` so the swap costs nothing.
 *
 * Threads: every call on one thread (MacSound's audio thread); the spare is made on `maker`.
 */
internal class EldDecoder private constructor(
    private val codecName: String,
    private val asc: ByteArray,
    val framesPerPacket: Int,
    private val maker: ExecutorService,
) {
    companion object {
        private const val TAG = "Sill"
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC   // audio/mp4a-latm
        private const val INPUT_WAIT_US = 5_000L
        private const val OUTPUT_WAIT_NS = 20_000_000L
        /** This many failures in a row: the decoder does not work here, and the epoch plays nothing. */
        private const val FAILURES_TO_GIVE_UP = 20
        /** Fraunhofer's FDK, which decodes ELD whatever its profile list says. */
        private val FDK = setOf("c2.android.aac.decoder", "OMX.google.aac.decoder")

        /**
         * The decoder this device plays AAC-ELD with, or nil: the platform's FDK decoder if present,
         * else the first AAC decoder that lists the ELD profile.
         */
        fun findDecoder(): String? = try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && !info.isAlias && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }
            fun listsEld(info: MediaCodecInfo) = runCatching {
                info.getCapabilitiesForType(MIME).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AACObjectELD }
            }.getOrDefault(false)
            (infos.firstOrNull { it.name in FDK } ?: infos.firstOrNull { listsEld(it) })?.name
        } catch (e: Exception) {
            Log.w(TAG, "audio: no codec list: $e")
            null
        }

        /** A started decoder for this config, or nil when the device refuses it. Blocking (a few ms). */
        fun create(codecName: String, asc: ByteArray, framesPerPacket: Int, maker: ExecutorService): EldDecoder? {
            val d = EldDecoder(codecName, asc, framesPerPacket, maker)
            d.codec = d.make() ?: return null
            d.requestSpare()
            return d
        }
    }

    private var codec: MediaCodec? = null
    /** The codec has been fed nothing since it started: a reset is free. */
    private var fresh = true
    private val spare = AtomicReference<MediaCodec?>()
    @Volatile private var released = false
    /** A spare is being made, or waits in `spare`. */
    private val spareComing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var outChannels = 2
    private var outFloat = false
    private var failures = 0

    /** It failed too often to be any use (the epoch plays nothing). */
    val broken: Boolean get() = failures >= FAILURES_TO_GIVE_UP || codec == null

    private fun make(): MediaCodec? {
        var c: MediaCodec? = null
        return try {
            c = MediaCodec.createByCodecName(codecName)
            val format = MediaFormat.createAudioFormat(MIME, AudioPlayout.RATE.toInt(), 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            c.configure(format, null, null, 0)
            c.start()
            c
        } catch (e: Exception) {
            Log.w(TAG, "audio: $codecName refused AAC-ELD: $e")
            runCatching { c?.release() }
            null
        }
    }

    private fun requestSpare() {
        if (released || !spareComing.compareAndSet(false, true)) return
        maker.execute {
            val c = if (released) null else make()
            when {
                c == null -> spareComing.set(false)
                released || !spare.compareAndSet(null, c) -> runCatching { c.release() }
            }
        }
    }

    /**
     * One packet's `framesPerPacket` frames, a buffer per channel. Nil when the decoder made nothing in time,
     * failed, or made another number of frames (the caller plays silence in its place).
     */
    fun decode(packet: ByteArray): Pair<FloatArray, FloatArray>? {
        val c = codec ?: return null
        if (packet.isEmpty() || broken) return null
        val got = try { decodeOn(c, packet) } catch (e: Exception) {
            Log.w(TAG, "audio: decode failed: $e")
            replace()
            null
        }
        if (got != null && got.first.size != framesPerPacket && !toldSize) {
            toldSize = true
            Log.w(TAG, "audio: the decoder made ${got.first.size} frames of a packet, not $framesPerPacket")
        }
        val ok = got != null && got.first.size == framesPerPacket
        if (ok) failures = 0 else failures++
        return if (ok) got else null
    }
    private var toldSize = false

    private fun decodeOn(c: MediaCodec, packet: ByteArray): Pair<FloatArray, FloatArray>? {
        val inIndex = c.dequeueInputBuffer(INPUT_WAIT_US)
        if (inIndex < 0) return null
        val buf = c.getInputBuffer(inIndex) ?: return null
        buf.clear()
        val mine = pts
        pts += 10_000
        if (buf.capacity() < packet.size) {
            c.queueInputBuffer(inIndex, 0, 0, mine, 0)
            return null
        }
        buf.put(packet)
        c.queueInputBuffer(inIndex, 0, packet.size, mine, 0)
        fresh = false
        val deadline = System.nanoTime() + OUTPUT_WAIT_NS
        while (true) {
            val wait = (deadline - System.nanoTime()) / 1000
            if (wait <= 0) return null
            val outIndex = c.dequeueOutputBuffer(info, wait)
            when {
                outIndex >= 0 -> {
                    // An older packet's output that came after its wait: not this one's.
                    val stale = info.presentationTimeUs < mine - 5_000
                    val out = if (!stale && info.size > 0) c.getOutputBuffer(outIndex)?.let { pcm(it) } else null
                    c.releaseOutputBuffer(outIndex, false)
                    if (!stale) return out
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(c.outputFormat)
                else -> Unit   // INFO_TRY_AGAIN_LATER: the loop's deadline decides
            }
        }
    }

    private fun readFormat(f: MediaFormat) {
        outChannels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        outFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
        if (rate != AudioPlayout.RATE.toInt()) Log.w(TAG, "audio: the decoder gives $rate Hz")
    }

    private fun pcm(out: ByteBuffer): Pair<FloatArray, FloatArray>? {
        val ch = outChannels.coerceAtLeast(1)
        out.limit(info.offset + info.size)
        out.position(info.offset)
        val b = out.slice().order(ByteOrder.nativeOrder())
        val frames = info.size / (ch * if (outFloat) 4 else 2)
        if (frames <= 0) return null
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        if (outFloat) {
            val f = b.asFloatBuffer()
            for (i in 0 until frames) { left[i] = f.get(i * ch); right[i] = f.get(i * ch + if (ch > 1) 1 else 0) }
        } else {
            val s = b.asShortBuffer()
            for (i in 0 until frames) {
                left[i] = s.get(i * ch) / 32768f
                right[i] = s.get(i * ch + if (ch > 1) 1 else 0) / 32768f
            }
        }
        return left to right
    }

    /** The next packet decodes as on a fresh decoder (a new segment, the packet after a gap). */
    fun reset() {
        if (fresh || released) return
        replace()
    }

    /** A fresh codec in place of this one: the spare if it is made, else one made now. */
    private fun replace() {
        val old = codec
        val next = spare.getAndSet(null)
        if (next != null) spareComing.set(false)
        codec = next ?: make()   // none made yet (two resets close together): made now
        fresh = true
        pts = 0
        outChannels = 2; outFloat = false
        failures = if (codec == null) FAILURES_TO_GIVE_UP else failures
        if (old != null) maker.execute { runCatching { old.stop() }; runCatching { old.release() } }
        requestSpare()
    }

    fun release() {
        released = true
        codec?.let { c -> maker.execute { runCatching { c.stop() }; runCatching { c.release() } } }
        codec = null
        spare.getAndSet(null)?.let { c -> maker.execute { runCatching { c.release() } } }
    }
}
