package me.saffer.sill.android.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The device's playout of the Mac's sound (iOSClient's AudioPlayout.swift, docs/audio-plan.md §7.2): which
 * packets are decoded, which are played, and where each goes on the player's timeline, so that the sound
 * is never ahead of the picture and trails it by as little as its own path allows. Pure (no Android
 * types): the unit tests run it on a simulated clock, and MacSound on its audio thread, doing what it says.
 *
 * Clocks. Device times are seconds of the monotonic clock (System.nanoTime, the clock AudioTimestamp's
 * nanoTime counts). Stamps are the Mac's wall clock (seconds since 1970, the header's). The offset between
 * the two is never needed: every comparison goes through the floor. Samples are the player's timeline at
 * 48 kHz; `played` says which device time a sample of it goes out at (when the output takes it), and it is
 * heard the output latency later.
 *
 * The rules, the plan's numbers:
 * 1. The floor is the smallest (arrival − stamp) of the packets of the last 10 s, kept in 1 s buckets, a
 *    packet's arrival being when the playout takes it (`packet`), or when it came for one that waited
 *    behind Sill's own engine starting or stopping (rule 6). With no packet for 10 s it keeps its last value.
 * 2. The need is what a packet needs between arriving and being heard: the jitter cover (the largest
 *    arrival − stamp − floor of the last 10 s; 40 ms at home and 150 ms away until the session's first 2 s
 *    of packets have come, which the window leaves out; home 20–150 ms, away 60–600 ms), one packet (the
 *    hand's wait, rule 10), the IO buffer, the output latency and 2 ms.
 * 3. The picture's lag is the median (arrival − stamp − floor) of the frames of the last 2 s, plus 1.5
 *    refreshes and 4 ms. No frames: its last value. It can be negative; it is never clamped.
 * 4. The delay is the need, or the picture's lag and one frame of the stream when that is more. A packet
 *    stamped T is due at the ear at T + floor + delay, and goes onto the timeline the output latency before.
 * 5. The first packet of a segment, and the first after a gap, a late packet, a fade out or a jump, is
 *    placed by time (a segment's first packet drops its P frames of priming, so from T + P ÷ rate). The
 *    rest follow the packet before them.
 * 6. Late: a packet placed by time less than the IO buffer + 2 ms ahead of its render is decoded (for the
 *    decoder's state) and not played.
 * 7. Duplicates: a packet whose (epoch, seq) is among the last 256 is dropped before it counts anywhere; a
 *    format for the epoch playing with the same cookie is ignored.
 * 8. Missing: a seq that skips resets the decoder, and the packet after the gap is a late join.
 * 9. A clean start only where the Mac's encoder started clean (the segment flag): the decoder resets, the
 *    first P decoded frames are dropped and the next 5 ms fade in. Any other first packet on a reset
 *    decoder is decoded and dropped whole, and the next one fades in.
 * 10. The newest packet's last 5 ms (its tail) stays in hand until the next packet arrives or the tail's
 *    deadline passes (its render time − the IO buffer − 2 ms), and goes out then, faded if nothing follows.
 * 11. Drift: a packet that follows another may gain a frame (ahead) or lose one (behind), one at most.
 * 12. Jumps: an error over 40 ms, or a sound that would lead the picture, is closed at once.
 * 13. A step: a second of packets all more than max(50 ms, 2 × the rtt median) above the floor, or a
 *    packet 20 ms under it, restarts the floor (and the picture's window), closed by a jump.
 * The engine's idle (rule 14) is MacSound's.
 */
class AudioPlayout {
    companion object {
        /** The player's timeline: 48 kHz. */
        const val RATE = 48_000.0
        const val FLOOR_SECONDS = 10
        const val COVER_START_HOME = 0.040
        const val COVER_START_AWAY = 0.150
        const val COVER_GRACE = 2.0
        val COVER_HOME = 0.020..0.150
        val COVER_AWAY = 0.060..0.600
        /** Rule 2's last term, and rule 6's margin beyond the IO buffer. */
        const val MARGIN = 0.002
        const val PICTURE_SECONDS = 2.0
        const val PICTURE_REFRESHES = 1.5
        const val PICTURE_EXTRA = 0.004
        const val CORRECT_FROM = 0.001
        const val CORRECT_TO = 0.00025
        const val JUMP_OVER = 0.040
        const val STEP_ABOVE = 0.050
        const val STEP_BELOW = 0.020
        const val STEP_PACKETS = 50
        const val FADE_SECONDS = 0.005
        const val DEDUPE_COUNT = 256
        /** Packets kept while the engine starts, before its first reading gives a timeline (a second's). */
        const val WAITING_LIMIT = 100
        /** A fade's frames at the timeline's rate: 5 ms. */
        val FADE_FRAMES: Int = rounded(FADE_SECONDS * RATE).toInt()

        /** Swift's `rounded()`: to nearest, halves away from zero. */
        fun rounded(x: Double): Double = if (x >= 0) Math.floor(x + 0.5) else -Math.floor(-x + 0.5)

        fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return 0.0
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }

        private fun insertionIndex(sorted: List<Double>, value: Double): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid] < value) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** A raised-cosine fade over the first (`fadeIn`) or last `frames` frames of `x`, in place. */
        fun fade(x: FloatArray, fadeIn: Boolean, frames: Int = FADE_FRAMES) {
            val n = minOf(frames, x.size)
            if (n <= 0) return
            for (i in 0 until n) {
                val g = (0.5 - 0.5 * cos(PI * (i + 1) / (n + 1))).toFloat()
                if (fadeIn) x[i] *= g else x[x.size - 1 - i] *= g
            }
        }

        /**
         * One frame added (+1) or dropped (−1), both channels alike, at the packet's quietest point in its
         * middle half, blended into its neighbours. The same arrays for 0, or for a packet under 4 frames.
         */
        fun change(left: FloatArray, right: FloatArray, by: Int): Pair<FloatArray, FloatArray> {
            val n = minOf(left.size, right.size)
            if (by == 0 || n < 4) return left to right
            val from = n / 4
            val to = maxOf(from + 1, (3 * n) / 4)
            var q = from
            var level = Float.POSITIVE_INFINITY
            for (i in from until minOf(to, n - 1)) {
                val l = abs(left[i]) + abs(right[i]) + abs(left[i + 1]) + abs(right[i + 1])
                if (l < level) { level = l; q = i }
            }
            return if (by > 0) {
                fun add(x: FloatArray): FloatArray {
                    val y = FloatArray(n + 1)
                    System.arraycopy(x, 0, y, 0, q + 1)
                    y[q + 1] = (x[q] + x[q + 1]) / 2
                    System.arraycopy(x, q + 1, y, q + 2, n - q - 1)
                    return y
                }
                add(left) to add(right)
            } else {
                fun drop(x: FloatArray): FloatArray {
                    val y = FloatArray(n - 1)
                    System.arraycopy(x, 0, y, 0, q)
                    y[q] = (x[q] + x[q + 1]) / 2
                    System.arraycopy(x, q + 2, y, q + 1, n - q - 2)
                    return y
                }
                drop(left) to drop(right)
            }
        }

        private fun nextSeq(seq: Long): Long = (seq + 1) and 0xFFFF_FFFFL
    }

    // MARK: - What it says

    /** Where one packet goes, as the model saw it when it placed it. */
    data class Decision(
        /** When the packet's first frame is due at the ear (stamp + floor + delay). */
        val due: Double,
        /** When it will be heard where it is placed (its render time + the output latency). */
        val ear: Double,
        /** When the picture of the same stamp shows (stamp + floor + the picture's lag); −∞ before any frame. */
        val picture: Double,
    ) {
        /** due − ear: positive, the sound is ahead of where it is due; negative, behind. */
        val error: Double get() = due - ear
    }

    /** A part of a packet to hand to the player now: its body at once, its last 5 ms once the next says. */
    data class Schedule(
        val id: Int,
        val part: Part,
        /** Which of the packet's kept frames: from `offset`, `count` of them (before `change`). */
        val offset: Int,
        val count: Int,
        /** Its first frame's place on the player's timeline, or nil: right after the buffer before it. */
        val at: Double?,
        /** Where it starts either way (planned). */
        val start: Double,
        /** Its frames once `change` is made. */
        val frames: Int,
        /** The body's: +1, a frame added; −1, one dropped (rule 11); 0. */
        val change: Int,
        val fadeIn: Boolean,
        val fadeOut: Boolean,
        /** The stamp of the packet's first kept frame, its seq and epoch. */
        val stamp: Double,
        val seq: Long,
        val epoch: Int,
        val decision: Decision,
        /** When its first frame goes out, as the model reads the timeline now. */
        val goesOut: Double,
    ) {
        enum class Part { BODY, TAIL }
        val end: Double get() = start + frames
    }

    /** What to do with the player and the kept packets. */
    class Output(
        val schedules: MutableList<Schedule> = ArrayList(2),
        /** Kept packets never to be played (late, dropped by a jump, left by a reset). */
        val discards: MutableList<Int> = ArrayList(1),
    ) {
        fun add(other: Output) { schedules += other.schedules; discards += other.discards }
        override fun equals(other: Any?) = other is Output && schedules == other.schedules && discards == other.discards
        override fun hashCode() = schedules.hashCode() * 31 + discards.hashCode()
        override fun toString() = "Output(schedules=$schedules, discards=$discards)"
    }

    /** What to do with one packet. */
    class Plan(
        /** Decode it: every packet of the epoch playing that is not a duplicate and not muted. */
        var decode: Boolean = false,
        /** Reset the decoder before it. */
        var resetFirst: Boolean = false,
        /** Frames dropped from its start once decoded: the codec's priming, or all of them. */
        var dropFront: Int = 0,
        /** The id its kept frames go by until a Schedule or a discard names it; nil when none are kept. */
        var id: Int? = null,
        /** The hand, scheduled or discarded now, and this packet, discarded when late. */
        val output: Output = Output(),
    )

    /** Why a jump was owed: the console's words. */
    enum class JumpCause(val words: String) {
        STEP("clock step"), ERROR("off by over 40 ms"), AHEAD("ahead of the picture"), ROUTE("route")
    }

    /**
     * What a reset throws away. SESSION: everything. PLAYBACK: the timeline (the engine restarted or
     * stopped, unmuted): the kept packets, the placement and the decoder's continuity, never the floor, the
     * cover or the dedupe. JUMP: the next packet is placed by time (a route's new latency).
     */
    enum class Reset { SESSION, PLAYBACK, JUMP }

    /** One second of what happened, for the console and the stats sent to the Mac. */
    data class Second(
        /** How far the sound trailed the picture, ms, over the packets placed; nil for a second with none. */
        var behindMs: Int? = null,
        var placed: Int = 0,
        var late: Int = 0,
        var duplicates: Int = 0,
        var framesAdded: Int = 0,
        var framesDropped: Int = 0,
        var jumps: Int = 0,
        var joins: Int = 0,
        var jumpDrops: Int = 0,
        var need: Double = 0.0,
        var cover: Double = 0.0,
        var delay: Double = 0.0,
        var lag: Double? = null,
        var error: Double? = null,
    )

    // MARK: - State

    var epoch: Int? = null; private set
    var framesPerPacket = 480; private set
    var primingFrames = 240; private set
    private var cookie = ByteArray(0)

    var outputLatency = 0.0; private set
    var ioBuffer = 0.005; private set
    var refresh = 1.0 / 60; private set
    var streamFPS = 60.0; private set
    var rttMedian: Double? = null; private set
    var away = false; private set
    var muted = false; private set

    private class Bucket(val second: Int, var min: Double, var max: Double?, var count: Int)
    private val buckets = ArrayList<Bucket>()
    private var floorKept: Double? = null
    private var sessionStart: Double? = null
    private class Run(val since: Double, var count: Int, var min: Double, var max: Double)
    private var above: Run? = null

    private val frameArrivals = ArrayList<Double>()
    private val frameRaws = ArrayList<Double>()
    private var frameHead = 0
    private val sortedRaws = ArrayList<Double>()
    private var lastPictureRaw: Double? = null

    private val recentKeys = ArrayList<Long>()
    private val recentSet = HashSet<Long>()
    private var recentNext = 0

    private var expectedSeq: Long? = null
    private var primingLeft = 0
    private var fadeNext = true

    private class Admitted(val id: Int, val epoch: Int, val seq: Long, val stamp: Double, val frames: Int,
                           val fadeIn: Boolean, val segmentStart: Boolean)

    private class Planned(val packet: Admitted, val start: Double, val contiguous: Boolean, val change: Int,
                          val fadeIn: Boolean, val decision: Decision) {
        val tailCount: Int get() = minOf(FADE_FRAMES, packet.frames)
        val bodyCount: Int get() = packet.frames - tailCount
        val bodyFrames: Int get() = bodyCount + change
        val tailStart: Double get() = start + bodyFrames
        val end: Double get() = tailStart + tailCount

        fun takes(next: Admitted): Boolean =
            next.epoch == packet.epoch && next.seq == nextSeq(packet.seq) && !next.segmentStart && !next.fadeIn

        fun body(goesOut: Double): Schedule? {
            if (bodyCount <= 0) return null
            return Schedule(packet.id, Schedule.Part.BODY, 0, bodyCount, if (contiguous) null else start, start, bodyFrames,
                change, fadeIn, false, packet.stamp, packet.seq, packet.epoch, decision, goesOut)
        }

        fun tail(fadeOut: Boolean, goesOut: Double): Schedule {
            val first = bodyCount == 0
            return Schedule(packet.id, Schedule.Part.TAIL, bodyCount, tailCount, if (first && !contiguous) start else null,
                tailStart, tailCount, 0, first && fadeIn, fadeOut, packet.stamp, packet.seq, packet.epoch, decision, goesOut)
        }
    }

    /** The player's timeline: a sample and the device time it goes out at. Nil until the first reading. */
    private var mappingSample = 0.0
    private var mappingTime = 0.0
    private var mapped = false
    private var hand: Planned? = null
    private var chainEnd: Double? = null
    private val waiting = ArrayList<Admitted>()
    private var nextID = 1
    private var jumpOwed = false
    private var owedCause: JumpCause? = null
    private var madeJump: Pair<Double, JumpCause>? = null
    private var correcting = 0
    var lastError: Double? = null; private set

    private var second = Second()
    private val behind = ArrayList<Double>()
    var placedThisSession = false; private set

    // MARK: - Inputs

    /** A format message. True for a new epoch (a new decoder); false for the epoch playing with the same cookie. */
    fun format(epoch: Int, framesPerPacket: Int, primingFrames: Int, cookie: ByteArray): Boolean {
        if (this.epoch == epoch && this.cookie.contentEquals(cookie) && this.framesPerPacket == framesPerPacket &&
            this.primingFrames == primingFrames) return false
        this.epoch = epoch
        this.framesPerPacket = maxOf(1, framesPerPacket)
        this.primingFrames = maxOf(0, primingFrames)
        this.cookie = cookie.copyOf()
        expectedSeq = null
        primingLeft = 0
        fadeNext = true
        return true
    }

    /** A video frame, stamped at arrival: for the picture's lag (rule 3). */
    fun frame(stamp: Double, arrival: Double) {
        val raw = arrival - stamp
        if (!raw.isFinite()) return
        frameArrivals.add(arrival)
        frameRaws.add(raw)
        sortedRaws.add(insertionIndex(sortedRaws, raw), raw)
        expireFrames(arrival)
    }

    /** The output's latencies as read back once it runs, the panel's refresh, and the stream's rate. */
    fun latency(output: Double, io: Double, refresh: Double, streamFPS: Double) {
        outputLatency = maxOf(0.0, if (output.isFinite()) output else 0.0)
        ioBuffer = maxOf(0.0, if (io.isFinite()) io else 0.0)
        this.refresh = maxOf(0.0, if (refresh.isFinite()) refresh else 0.0)
        this.streamFPS = if (streamFPS > 0 && streamFPS.isFinite()) streamFPS else 60.0
    }

    /** The pings' median round trip of the last second, for the step test (rule 13). */
    fun rtt(median: Double?) { rttMedian = median?.takeIf { it.isFinite() && it >= 0 } }

    /** Whether this session is away from home: the cover's start and bounds (rule 2). */
    fun away(away: Boolean) { this.away = away }

    /** Muted (or in the background): each packet still counts for the floor and the cover, none is decoded. */
    fun mute(muted: Boolean) { this.muted = muted }

    /** The player's timeline: `sample` goes out at device time `time`. The first reading places what waited. */
    fun played(sample: Double, time: Double, now: Double): Output {
        if (!sample.isFinite() || !time.isFinite()) return Output()
        val first = !mapped
        mappingSample = sample; mappingTime = time; mapped = true
        if (!first || waiting.isEmpty()) return Output()
        val out = Output()
        val list = ArrayList(waiting)
        waiting.clear()
        for (a in list) out.add(place(a, now))
        return out
    }

    /**
     * One packet, with its own stamp (the message's + its place × F ÷ rate), taken `now`. A packet that
     * waited behind Sill's own engine starting or stopping gives `came`, when it came: the floor and the
     * cover take that, the late test and the placement `now` (rule 6).
     */
    fun packet(epoch: Int, seq: Long, stamp: Double, segmentStart: Boolean, came: Double? = null, now: Double): Plan {
        val arrival = came ?: now
        val playing = this.epoch
        if (playing == null || epoch != playing || !stamp.isFinite() || !arrival.isFinite()) return Plan()
        // Rule 7: before anything counts.
        val key = ((epoch and 0xFFFF).toLong() shl 32) or (seq and 0xFFFF_FFFFL)
        if (key in recentSet) {
            second.duplicates += 1
            return Plan()
        }
        remember(key)
        record(arrival - stamp, arrival)
        if (muted) {
            expectedSeq = null
            return Plan()
        }
        val plan = Plan(decode = true)
        val continuous = !segmentStart && expectedSeq == seq
        expectedSeq = nextSeq(seq)
        if (segmentStart) {
            // Rule 9: a clean start.
            plan.resetFirst = true
            primingLeft = primingFrames
            fadeNext = true
        } else if (!continuous) {
            // Rules 8 and 9: decoded for the decoder's state and dropped whole; the next fades in.
            plan.resetFirst = true
            plan.dropFront = framesPerPacket
            primingLeft = 0
            fadeNext = true
            second.joins += 1
            plan.output.add(handOver(fadeOut = true))
            return plan
        }
        val drop = minOf(primingLeft, framesPerPacket)
        primingLeft -= drop
        plan.dropFront = drop
        val kept = framesPerPacket - drop
        if (kept <= 0) return plan
        val a = Admitted(nextID, epoch, seq, stamp + drop / RATE, kept, fadeNext, segmentStart)
        nextID += 1
        fadeNext = false
        plan.id = a.id
        if (!mapped) {
            waiting.add(a)
            if (waiting.size > WAITING_LIMIT) {
                plan.output.discards.add(waiting.removeAt(0).id)
                // What follows the first kept one no longer follows anything: placed by time.
                val f = waiting[0]
                waiting[0] = Admitted(f.id, f.epoch, f.seq, f.stamp, f.frames, true, f.segmentStart)
            }
            return plan
        }
        plan.output.add(place(a, now))
        return plan
    }

    /** The decoder could not decode a packet the plan kept: silence in its place, and afresh at the next. */
    fun decodeFailed() {
        expectedSeq = null
        primingLeft = 0
        fadeNext = true
    }

    /** Time passing: the tail in hand goes out, faded, once its deadline has passed (rule 10). */
    fun tick(now: Double): Output {
        val d = deadline
        if (hand == null || d == null || now < d) return Output()
        return handOver(fadeOut = true)
    }

    /** When `tick` next has something to do, or nil. */
    val deadline: Double?
        get() {
            val h = hand ?: return null
            return time(h.tailStart) - ioBuffer - MARGIN
        }

    fun reset(kind: Reset): Output {
        if (kind == Reset.JUMP) {
            owe(JumpCause.ROUTE)
            return Output()
        }
        val out = Output()
        hand?.let { out.discards.add(it.packet.id) }
        out.discards += waiting.map { it.id }
        hand = null
        waiting.clear()
        chainEnd = null
        mapped = false
        jumpOwed = false
        owedCause = null
        madeJump = null
        correcting = 0
        expectedSeq = null
        primingLeft = 0
        fadeNext = true
        if (kind == Reset.SESSION) {
            epoch = null
            cookie = ByteArray(0)
            buckets.clear()
            floorKept = null
            sessionStart = null
            above = null
            frameArrivals.clear(); frameRaws.clear(); frameHead = 0; sortedRaws.clear()
            lastPictureRaw = null
            recentKeys.clear(); recentSet.clear(); recentNext = 0
            lastError = null
            placedThisSession = false
            second = Second()
            behind.clear()
        }
        return out
    }

    /** The second that ends now, and the next one starts. */
    fun takeSecond(now: Double): Second {
        expireFrames(now)
        val s = second
        s.behindMs = if (behind.isEmpty()) null else rounded(median(behind.sorted()) * 1000).toInt()
        s.need = need(now)
        s.cover = cover(now)
        val lag = pictureLag(now)
        s.lag = lag
        s.delay = delay(now, lag)
        s.error = lastError
        second = Second()
        behind.clear()
        return s
    }

    // MARK: - The numbers

    /** Rule 1. */
    val floor: Double? get() = if (buckets.isEmpty()) floorKept else buckets.minOf { it.min }

    /** Rule 2's cover. */
    fun cover(now: Double): Double {
        val start = if (away) COVER_START_AWAY else COVER_START_HOME
        val begun = sessionStart ?: return start
        val floor = this.floor ?: return start
        if (now - begun < COVER_GRACE) return start
        val top = buckets.mapNotNull { it.max }.maxOrNull() ?: return start
        val bounds = if (away) COVER_AWAY else COVER_HOME
        return minOf(maxOf(top - floor, bounds.start), bounds.endInclusive)
    }

    /** Rule 2. */
    fun need(now: Double): Double = cover(now) + framesPerPacket / RATE + ioBuffer + outputLatency + MARGIN

    /** Rule 3: nil before any frame came (or before the floor). */
    fun pictureLag(now: Double): Double? {
        val floor = this.floor ?: return null
        val raw = if (sortedRaws.isNotEmpty() && liveFramesAfterExpiry(now) > 0) median(sortedRaws) else lastPictureRaw
        return raw?.let { it - floor + PICTURE_REFRESHES * refresh + PICTURE_EXTRA }
    }

    /** Rule 4. */
    fun delay(now: Double, lag: Double?): Double {
        val guardDelay = lag?.let { it + 1 / streamFPS } ?: Double.NEGATIVE_INFINITY
        return maxOf(need(now), guardDelay)
    }

    val holding: Boolean get() = hand != null

    // MARK: - Placement

    private fun place(a: Admitted, now: Double): Output {
        val out = Output()
        // Rule 10: a tail whose deadline has passed goes out faded before anything follows it.
        val d0 = deadline
        if (hand != null && d0 != null && now >= d0) out.add(handOver(fadeOut = true))
        val floor = this.floor ?: return out
        expireFrames(now)
        val lag = pictureLag(now)
        val delay = delay(now, lag)
        val due = a.stamp + floor + delay
        val picture = lag?.let { a.stamp + floor + it } ?: Double.NEGATIVE_INFINITY
        var placed: Planned? = null
        val h = hand
        if (h != null && !jumpOwed && h.takes(a)) {
            // Rule 5: it follows the packet in hand.
            val start = h.end
            val ear = time(start) + outputLatency
            val decision = Decision(due, ear, picture)
            lastError = decision.error
            if (ear >= picture && abs(decision.error) <= JUMP_OVER) {
                val change = if (a.frames - minOf(FADE_FRAMES, a.frames) >= 4) correction(decision.error) else 0
                placed = Planned(a, start, true, change, false, decision)
            } else {
                // Rule 12: too far off, or ahead of the picture: closed at once.
                owe(if (ear < picture) JumpCause.AHEAD else JumpCause.ERROR)
            }
        }
        if (placed == null) {
            // Rule 5: by time.
            var start = sample(due - outputLatency)
            val chain0 = hand?.end ?: chainEnd
            val cause = owedCause
            if (jumpOwed && cause != null && chain0 != null) {
                madeJump = (start - chain0) / RATE to cause
                owedCause = null
            }
            val chain = hand?.end ?: chainEnd
            if (chain != null && start < chain) {
                if (jumpOwed && chain - start > framesPerPacket / 2.0) {
                    // Behind: dropped, until a packet is due within half a packet of what is scheduled.
                    second.jumpDrops += 1
                    out.add(handOver(fadeOut = true))
                    out.discards.add(a.id)
                    return out
                }
                // A new segment a hair early, or a jump's last few ms: right after what is scheduled.
                start = chain
            }
            if (time(start) - now < ioBuffer + MARGIN) {
                // Rule 6.
                second.late += 1
                out.add(handOver(fadeOut = true))
                out.discards.add(a.id)
                return out
            }
            jumpOwed = false
            correcting = 0
            val ear = time(start) + outputLatency
            placed = Planned(a, start, false, 0, true, Decision(due, ear, picture))
        }
        val p = placed
        hand?.let { out.schedules.add(it.tail(fadeOut = !p.contiguous, goesOut = time(it.tailStart))) }
        p.body(goesOut = time(p.start))?.let { out.schedules.add(it) }
        hand = p
        chainEnd = null
        second.placed += 1
        placedThisSession = true
        if (p.change > 0) second.framesAdded += 1 else if (p.change < 0) second.framesDropped += 1
        if (p.decision.picture > Double.NEGATIVE_INFINITY) behind.add(p.decision.ear - p.decision.picture)
        return out
    }

    /** The tail in hand goes to the player now. */
    private fun handOver(fadeOut: Boolean): Output {
        val h = hand ?: return Output()
        hand = null
        chainEnd = h.end
        return Output(schedules = mutableListOf(h.tail(fadeOut, time(h.tailStart))))
    }

    /** A jump is owed (rules 12 and 13, a route), counted once, and only where something plays. */
    private fun owe(cause: JumpCause) {
        if (jumpOwed) return
        jumpOwed = true
        if (hand != null || chainEnd != null) {
            second.jumps += 1
            owedCause = cause
        }
    }

    /** The jump the last placement by time made, once: how far (s; positive: later) and why. */
    fun takeJump(): Pair<Double, JumpCause>? {
        val j = madeJump
        madeJump = null
        return j
    }

    /** Rule 11: the direction for this packet, with a band so a steady stream does not flutter. */
    private fun correction(error: Double): Int {
        if (correcting != 0 && abs(error) < CORRECT_TO) {
            correcting = 0
        } else if (error > CORRECT_FROM) {
            correcting = 1
        } else if (error < -CORRECT_FROM) {
            correcting = -1
        } else if (correcting != 0 && (error > 0) != (correcting > 0)) {
            correcting = 0
        }
        return correcting
    }

    private fun time(sample: Double): Double =
        if (!mapped) Double.NEGATIVE_INFINITY else mappingTime + (sample - mappingSample) / RATE

    private fun sample(time: Double): Double =
        if (!mapped) 0.0 else rounded(mappingSample + (time - mappingTime) * RATE)

    // MARK: - The windows

    /** Rules 1, 2 and 13: one packet's (arrival − stamp). */
    private fun record(raw: Double, arrival: Double) {
        if (sessionStart == null) sessionStart = arrival
        val now = Math.floor(arrival).toInt()
        buckets.removeAll { it.second <= now - FLOOR_SECONDS }
        val floor = this.floor
        if (floor != null) {
            // Rule 13, below: a packet 20 ms under the floor.
            if (raw < floor - STEP_BELOW) {
                restart(Bucket(now, raw, coverValue(raw, arrival), 1))
                return
            }
            // Rule 13, above: a second of packets, every one of them above the floor by the threshold.
            if (raw - floor > maxOf(STEP_ABOVE, 2 * (rttMedian ?: 0.0))) {
                val run = above ?: Run(arrival, 0, raw, raw)
                run.count += 1
                run.min = minOf(run.min, raw)
                run.max = maxOf(run.max, raw)
                above = run
                if (arrival - run.since >= 1 && run.count >= STEP_PACKETS) {
                    restart(Bucket(now, run.min, coverValue(run.max, arrival), run.count))
                    return
                }
            } else {
                above = null
            }
        }
        if (buckets.lastOrNull()?.second != now) buckets.add(Bucket(now, raw, null, 0))
        val b = buckets.last()
        b.min = minOf(b.min, raw)
        b.count += 1
        coverValue(raw, arrival)?.let { m -> b.max = maxOf(b.max ?: m, m) }
        floorKept = this.floor
    }

    /** A packet's value for the cover's window: none in the session's first 2 s. */
    private fun coverValue(raw: Double, arrival: Double): Double? {
        val begun = sessionStart ?: return null
        return if (arrival - begun >= COVER_GRACE) raw else null
    }

    /** Rule 13: the windows start again from `bucket` (the picture's too), and a jump closes the difference. */
    private fun restart(bucket: Bucket) {
        buckets.clear()
        buckets.add(bucket)
        floorKept = bucket.min
        above = null
        frameArrivals.clear(); frameRaws.clear(); frameHead = 0; sortedRaws.clear()
        lastPictureRaw = null
        owe(JumpCause.STEP)
    }

    private fun remember(key: Long) {
        if (recentKeys.size < DEDUPE_COUNT) {
            recentKeys.add(key)
        } else {
            recentSet.remove(recentKeys[recentNext])
            recentKeys[recentNext] = key
            recentNext = (recentNext + 1) % DEDUPE_COUNT
        }
        recentSet.add(key)
    }

    private fun expireFrames(now: Double) {
        val from = now - PICTURE_SECONDS
        while (frameHead < frameArrivals.size && frameArrivals[frameHead] < from) {
            val raw = frameRaws[frameHead]
            if (sortedRaws.size == 1) lastPictureRaw = sortedRaws[0]
            val i = insertionIndex(sortedRaws, raw)
            if (i < sortedRaws.size && sortedRaws[i] == raw) sortedRaws.removeAt(i)
            else if (i > 0 && sortedRaws[i - 1] == raw) sortedRaws.removeAt(i - 1)
            frameHead += 1
        }
        if (frameHead > 512) {
            frameArrivals.subList(0, frameHead).clear()
            frameRaws.subList(0, frameHead).clear()
            frameHead = 0
        }
        if (sortedRaws.isNotEmpty()) lastPictureRaw = median(sortedRaws)
    }

    /** The frames of the window that are still in it at `now` (without changing it). */
    private fun liveFramesAfterExpiry(now: Double): Int {
        val from = now - PICTURE_SECONDS
        var n = 0
        var i = frameArrivals.size - 1
        while (i >= frameHead && frameArrivals[i] >= from) { n += 1; i -= 1 }
        return n
    }
}
