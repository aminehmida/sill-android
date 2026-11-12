package me.saffer.sill.android.audio

/**
 * The player's timeline (what AVAudioPlayerNode is to iOSClient's AudioOutput): buffers scheduled at a
 * sample of the timeline or right after the one before, played in order, silence between them. The
 * output's writer pulls it a chunk at a time and hands every chunk to the AudioTrack, sound or silence,
 * so the track never runs dry and its frame count IS the timeline: sample s is the track's frame s.
 *
 * Pure (no Android types) and thread-safe: the audio thread schedules, the writer thread reads.
 */
class PlayerTimeline(private val channels: Int = 2) {
    private class Item(val start: Long, val pcm: FloatArray, val frames: Int) {
        val end: Long get() = start + frames
    }

    private val items = ArrayDeque<Item>()
    /** The next frame the writer reads: every frame before it has gone to the output. */
    private var position = 0L
    /** Where the last buffer scheduled ends. */
    private var end = 0L
    /** A fade to silence asked (the engine stopping): its length, how far it has gone. */
    private var fadeFrames = 0
    private var fadeDone = 0

    @Synchronized fun position(): Long = position

    /**
     * One buffer of interleaved frames, from sample `at` (rounded) or, with nil, right after the buffer
     * before it. A buffer never starts before the one scheduled before it ends, nor before what the writer
     * has already read (it would be cut: AVAudioPlayerNode plays a late buffer late too). Returns its start.
     */
    @Synchronized fun schedule(pcm: FloatArray, at: Double?): Long {
        val frames = pcm.size / channels
        val wanted = if (at != null && at.isFinite()) AudioPlayout.rounded(at).toLong() else end
        val start = maxOf(wanted, end, position)
        if (frames > 0) {
            items.addLast(Item(start, pcm, frames))
            end = start + frames
        }
        return start
    }

    /**
     * The next `frames` frames into `out` (interleaved): what is scheduled there, silence elsewhere, and
     * the fade to silence once asked. Returns the timeline sample of the first.
     */
    @Synchronized fun read(out: FloatArray, frames: Int): Long {
        val from = position
        val to = from + frames
        java.util.Arrays.fill(out, 0, frames * channels, 0f)
        while (items.isNotEmpty() && items.first().end <= from) items.removeFirst()
        for (item in items) {
            if (item.start >= to) break
            val s = maxOf(item.start, from)
            val e = minOf(item.end, to)
            if (s < e) System.arraycopy(item.pcm, ((s - item.start) * channels).toInt(), out, ((s - from) * channels).toInt(), ((e - s) * channels).toInt())
        }
        while (items.isNotEmpty() && items.first().end <= to) items.removeFirst()
        position = to
        if (fadeFrames > 0) {
            for (i in 0 until frames) {
                val g = if (fadeDone >= fadeFrames) 0f else 1f - (fadeDone + 1).toFloat() / fadeFrames
                if (fadeDone < fadeFrames) fadeDone++
                for (c in 0 until channels) out[i * channels + c] *= g
            }
        }
        return from
    }

    /** Down to silence over `frames` (a linear ramp, as the iOS mixer's volume ramp), and silent after. */
    @Synchronized fun fadeOut(frames: Int) {
        if (fadeFrames > 0) return
        fadeFrames = maxOf(1, frames)
        fadeDone = 0
    }

    /** Whether anything scheduled is still to be read (for the checks). */
    @Synchronized fun pending(): Int = items.sumOf { (it.end - maxOf(it.start, position)).coerceAtLeast(0).toInt() }
}

/**
 * The output's clock: which device time each sample of the timeline is heard at, from the track's
 * presentation time stamps (AudioTimestamp: a frame and the monotonic time it reached the output), and
 * how long before that the writer takes a sample (the output latency the playout counts: the track's
 * buffer, the mixer and the device, and the writer's wait to get into the buffer).
 *
 * The playout's timeline reading is (sample, its ear time − that latency), so its ear is the stamp's.
 * Pure and thread-safe: the writer feeds it, the audio thread reads it.
 */
class OutputClock(private val rate: Double = AudioPlayout.RATE, private val window: Double = 2.0) {
    private var tsFrame = 0L
    private var tsTime = 0.0
    private var hasStamp = false
    private var stampIsGuess = false
    /** Sliding maximum of (ear − when the writer took it) over `window` s: (taken at, value), values falling. */
    private val ahead = ArrayDeque<Pair<Double, Double>>()

    /** A presentation time stamp: `frame` reached the output at device time `time` (s). */
    @Synchronized fun stamp(frame: Long, time: Double) {
        if (!time.isFinite()) return
        tsFrame = frame; tsTime = time; hasStamp = true; stampIsGuess = false
    }

    /** A stand-in for a device that gives no time stamp (yet): only while no real one came. */
    @Synchronized fun guess(frame: Long, time: Double) {
        if (hasStamp && !stampIsGuess || !time.isFinite()) return
        tsFrame = frame; tsTime = time; hasStamp = true; stampIsGuess = true
    }

    @Synchronized fun hasStamp(): Boolean = hasStamp
    @Synchronized fun hasRealStamp(): Boolean = hasStamp && !stampIsGuess

    /** When sample `sample` is heard, from the last stamp; nil before any. */
    @Synchronized fun ear(sample: Long): Double? = if (!hasStamp) null else tsTime + (sample - tsFrame) / rate

    /** The writer took the chunk starting at `sample` at device time `at`. */
    @Synchronized fun wrote(sample: Long, at: Double) {
        val e = ear(sample) ?: return
        val v = e - at
        if (!v.isFinite()) return
        while (ahead.isNotEmpty() && ahead.last().second <= v) ahead.removeLast()
        ahead.addLast(at to v)
        while (ahead.isNotEmpty() && ahead.first().first < at - window) ahead.removeFirst()
    }

    /** The largest (ear − taken) of the last `window` s: how long before it is heard a sample is taken. */
    @Synchronized fun latency(): Double? = ahead.firstOrNull()?.second

    /** A new route: what the window held was the old one's. */
    @Synchronized fun forgetLatency() { ahead.clear() }

    /** The last stamp: a sample and when it is heard. */
    @Synchronized fun reading(): Pair<Long, Double>? = if (!hasStamp) null else tsFrame to tsTime
}

/**
 * What MacSound feeds the playout from the output's clock, at each packet and at the tail's deadline (iOS:
 * the player's lastRenderTime): the reading (the last stamp's sample, and when it goes out = when it is
 * heard − the output latency), and that latency when it moved. The latency goes up to the next half
 * millisecond and only a move of more than 1 ms is passed on, so the need stays put. Pure.
 */
class ClockFeed(private val model: AudioPlayout) {
    /** The output latency the model has; negative: none yet. */
    var given = -1.0
        private set

    /** The next reading passes the latency again (a new engine or route, another stream rate). */
    fun forget() { given = -1.0 }

    fun feed(clock: OutputClock, io: Double, refresh: Double, streamFPS: Double, now: Double): AudioPlayout.Output? {
        val (sample, ear) = clock.reading() ?: return null
        val measured = clock.latency() ?: return null
        val latency = Math.ceil(measured * 2000) / 2000
        if (given < 0 || Math.abs(latency - given) > 0.001) {
            given = latency
            model.latency(latency, io, refresh, streamFPS)
        }
        return model.played(sample.toDouble(), ear - given, now)
    }
}
