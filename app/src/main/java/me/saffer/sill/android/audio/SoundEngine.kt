package me.saffer.sill.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Process
import android.util.Log
import android.media.AudioFormat as PcmFormat

/**
 * One AudioTrack and the thread that feeds it (what SoundEngine, AVAudioEngine and its player node, are
 * to iOSClient's AudioOutput): 48 kHz stereo float, low-latency, usage media, content movie (the sound of
 * what the picture shows, as a video app's). The writer hands the track one chunk (a burst of the device's
 * mixer) at a time from the timeline, sound or silence, blocking until there is room, so the track never
 * runs dry and its frames are the timeline's samples. Every few writes it reads the track's presentation
 * time stamp into the clock. An underrun (the writer late) grows the track's buffer by a burst.
 *
 * No audio focus is asked for: iOS's session is .playback with .mixWithOthers, which never stops another
 * app's sound, and the equivalent here is to play without focus.
 */
internal class SoundEngine private constructor(
    private val track: AudioTrack,
    /** The frames the writer takes at a time. */
    val chunkFrames: Int,
    private val burst: Int,
    private val onDead: () -> Unit,
) {
    companion object {
        private const val TAG = "Sill"
        private const val STAMP_EVERY_NS = 100_000_000L
        /** No time stamp this long after the start: the head position and a guessed device latency stand in. */
        private const val GUESS_AFTER_NS = 500_000_000L
        private const val GUESSED_DEVICE_LATENCY = 0.020
        /** The track's buffer to start with: about 15 ms, at least two bursts. */
        private const val START_BUFFER_FRAMES = 720
        /** The ramp to silence before a stop (the iOS mixer's volume ramp: 25 ms). */
        const val FADE_FRAMES = 1200

        /**
         * A running engine, or nil with the reason logged. `onDead`: the track died (the audio server
         * restarted), from the writer thread. `onRouting`: its output changed, on `handler`.
         */
        fun start(context: Context, handler: Handler, onDead: () -> Unit, onRouting: (SoundEngine) -> Unit): SoundEngine? {
            val am = context.getSystemService(AudioManager::class.java)
            val burst = am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: 240
            val chunk = burst.coerceIn(96, 480)
            val rate = AudioPlayout.RATE.toInt()
            val minBytes = AudioTrack.getMinBufferSize(rate, PcmFormat.CHANNEL_OUT_STEREO, PcmFormat.ENCODING_PCM_FLOAT)
            val wanted = maxOf(2 * chunk, (START_BUFFER_FRAMES + chunk - 1) / chunk * chunk)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                    .setAudioFormat(PcmFormat.Builder()
                        .setEncoding(PcmFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(PcmFormat.CHANNEL_OUT_STEREO)
                        .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    // Room to grow after underruns: four times the start.
                    .setBufferSizeInBytes(maxOf(minBytes, wanted * 4 * 2 * 4))
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "audio: no AudioTrack: $e")
                return null
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "audio: the AudioTrack did not initialize")
                track.release()
                return null
            }
            return try {
                track.setBufferSizeInFrames(wanted)
                val engine = SoundEngine(track, chunk, burst, onDead)
                track.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { onRouting(engine) }, handler)
                track.play()
                engine.writer.start()
                engine
            } catch (e: Exception) {
                Log.w(TAG, "audio: the AudioTrack did not start: $e")
                runCatching { track.release() }
                null
            }
        }
    }

    val timeline = PlayerTimeline(2)
    val clock = OutputClock()
    @Volatile private var running = true

    /**
     * The model's IO buffer: the writer takes a chunk at once (a sample is taken up to a chunk before the
     * time the clock gives it), and the tail's deadline timer may run up to 2 ms late.
     */
    val ioBuffer: Double get() = chunkFrames / AudioPlayout.RATE + 0.002

    private val writer = Thread({ write() }, "sill.audio.out")

    private fun write() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(chunkFrames * 2)
        val stamp = AudioTimestamp()
        val startedAt = System.nanoTime()
        var stampAt = 0L
        var underruns = runCatching { track.underrunCount }.getOrDefault(0)
        var checkAt = startedAt + 1_000_000_000L
        try {
            while (running) {
                val taken = System.nanoTime()
                val from = timeline.read(buf, chunkFrames)
                clock.wrote(from, taken / 1e9)
                var off = 0
                while (off < buf.size && running) {
                    val n = track.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) {
                        if (running) { Log.w(TAG, "audio: the AudioTrack failed ($n)"); onDead() }
                        return
                    }
                    if (n == 0) Thread.sleep(2)   // a track that takes nothing (paused): no busy loop
                    off += n
                }
                val now = System.nanoTime()
                if (now - stampAt >= STAMP_EVERY_NS) {
                    stampAt = now
                    if (track.getTimestamp(stamp)) {
                        clock.stamp(stamp.framePosition, stamp.nanoTime / 1e9)
                    } else if (now - startedAt > GUESS_AFTER_NS && !clock.hasRealStamp()) {
                        val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        clock.guess(head, now / 1e9 + GUESSED_DEVICE_LATENCY)
                    }
                }
                if (now >= checkAt) {
                    checkAt = now + 1_000_000_000L
                    val count = track.underrunCount
                    if (count > underruns) {
                        underruns = count
                        val size = track.bufferSizeInFrames
                        if (size + burst <= track.bufferCapacityInFrames) {
                            val got = track.setBufferSizeInFrames(size + burst)
                            Log.i(TAG, "audio: underrun; the track's buffer $size → $got frames")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) { Log.w(TAG, "audio: the writer stopped: $e"); onDead() }
        }
    }

    /** One part of a packet at `at` on the timeline, or right after the one before (nil). */
    fun schedule(left: FloatArray, right: FloatArray, at: Double?) {
        val n = minOf(left.size, right.size)
        val pcm = FloatArray(n * 2)
        for (i in 0 until n) { pcm[2 * i] = left[i]; pcm[2 * i + 1] = right[i] }
        timeline.schedule(pcm, at)
    }

    /** Down to silence over 25 ms, before a stop. */
    fun turnDown() = timeline.fadeOut(FADE_FRAMES)

    /** The output, for the console. */
    val routeName: String get() = runCatching { track.routedDevice?.let { "${it.productName} (type ${it.type})" } }.getOrNull() ?: "no output"
    val routeId: Int get() = runCatching { track.routedDevice?.id }.getOrNull() ?: -1

    /** Stops the writer and lets the track go. On the audio thread. */
    fun stop() {
        if (!running) return
        running = false
        runCatching { track.pause() }   // wakes a writer blocked in write()
        runCatching { track.flush() }
        runCatching { writer.join(200) }
        runCatching { track.release() }
    }
}
