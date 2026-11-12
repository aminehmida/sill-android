package me.saffer.sill.android

import me.saffer.sill.android.audio.AudioPlayout
import me.saffer.sill.android.audio.AudioPlayout.Companion.FADE_FRAMES
import me.saffer.sill.android.audio.AudioPlayout.Companion.MARGIN
import me.saffer.sill.android.audio.AudioPlayout.Companion.RATE
import me.saffer.sill.android.audio.AudioPlayout.Companion.WAITING_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The playout policy (audio/AudioPlayout.kt) against iOSClient's own check, Tests/checks/audio-playout:
 * the same hand-worked numbers, and its simulation ported line for line (a Mac making packets and frames,
 * a link, the decoder's state, the player's timeline on an output clock read in IO-sized renders) with
 * the same invariants and scenarios.
 */
class AudioPlayoutTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-9) = abs(a - b) < eps
    private fun ms(s: Double) = "%.1f ms".format(s * 1000)

    // MARK: - The need, the floor, the cover, the picture's lag: by hand

    @Test fun formatsAndNumbers() {
        val m = AudioPlayout()
        assertTrue("a format: a new epoch", m.format(3, 480, 240, byteArrayOf(9)))
        assertFalse("the same epoch and cookie again (a move's connection): ignored", m.format(3, 480, 240, byteArrayOf(9)))
        assertTrue("the same epoch, another cookie: a new decoder", m.format(3, 480, 240, byteArrayOf(8)))
        assertTrue("another epoch: a new decoder", m.format(4, 512, 256, byteArrayOf(8)))
        m.latency(0.011, 0.005, 1.0 / 60, 60.0)
        assertTrue("no packet yet: no floor, the cover's start 40 ms", m.floor == null && near(m.cover(0.0), 0.040, 1e-12))
        m.away(true)
        assertTrue("away: the cover's start 150 ms", near(m.cover(0.0), 0.150, 1e-12))
        m.away(false)
        m.packet(4, 0, 99.995, true, now = 100.0)
        assertTrue("the first packet sets the floor", near(m.floor!!, 0.005))
        assertTrue("the need: 40 + 10.7 + 5 + 11 + 2 ms", near(m.need(100.0), 0.040 + 512.0 / 48_000 + 0.005 + 0.011 + 0.002, 1e-12))
        m.packet(4, 1, 100.5 - 0.0053, false, now = 100.6)
        assertTrue("100 ms over the floor in the first 2 s: the cover stays 40 ms", near(m.cover(100.6), 0.040, 1e-12))
        m.packet(4, 2, 102.3 - 0.035, false, now = 102.3)
        assertTrue("past 2 s, 30 ms over the floor: the cover 30 ms at once", near(m.cover(102.3), 0.030))
        m.packet(4, 3, 102.4 - 0.0051, false, now = 102.4)
        assertTrue("the largest of the window stays", near(m.cover(102.4), 0.030))

        val lone = AudioPlayout()
        lone.format(1, 480, 240, ByteArray(0))
        lone.packet(1, 0, 9.990, true, now = 10.0)
        lone.packet(1, 1, 12.5 - 0.011, false, now = 12.5)
        assertTrue("1 ms over: home's lower bound, 20 ms", near(lone.cover(12.5), 0.020))
        lone.packet(1, 2, 12.6 - 0.310, false, now = 12.6)
        assertTrue("300 ms over at home: held at 150 ms", near(lone.cover(12.6), 0.150))
        lone.away(true)
        assertTrue("the same away: 300 ms", near(lone.cover(12.6), 0.300))
        lone.packet(1, 3, 12.7 - 0.910, false, now = 12.7)
        assertTrue("900 ms over away: held at 600 ms", near(lone.cover(12.7), 0.600))

        // Rule 6: a packet that waited behind the engine starting gives when it came.
        val waited = AudioPlayout()
        waited.format(1, 480, 240, ByteArray(0))
        waited.packet(1, 0, 9.990, true, now = 10.0)
        waited.packet(1, 1, 12.5 - 0.011, false, now = 12.5)
        waited.packet(1, 2, 12.6 - 0.011, false, came = 12.6, now = 12.68)
        assertTrue("the cover and the floor take when it came", near(waited.cover(12.68), 0.020) && near(waited.floor!!, 0.010))

        val fl = AudioPlayout()
        fl.format(1, 480, 240, ByteArray(0))
        fl.packet(1, 0, 0.990, true, now = 1.0)
        for (k in 1..9) fl.packet(1, k.toLong(), k + 1.5 - 0.015, false, now = k + 1.5)
        assertTrue("the floor: the smallest of the last 10 s", near(fl.floor!!, 0.010))
        fl.packet(1, 10, 11.5 - 0.015, false, now = 11.5)
        assertTrue("10 s on, the smallest has left the window", near(fl.floor!!, 0.015))

        val pic = AudioPlayout()
        pic.format(1, 480, 240, ByteArray(0))
        pic.latency(0.010, 0.005, 1.0 / 60, 60.0)
        pic.packet(1, 0, 9.980, true, now = 10.0)
        for ((k, raw) in listOf(0.030, 0.010, 0.050, 0.012, 0.040).withIndex()) { val a = 10.0 + k * 0.1; pic.frame(a - raw, a) }
        val lag = pic.pictureLag(10.5)!!
        assertTrue("the picture's lag: median 30 − floor 20 + 25 + 4", near(lag, 0.030 - 0.020 + 1.5 / 60 + 0.004))
        assertTrue("the need decides while it is the larger", near(pic.delay(10.5, lag), pic.need(10.5), 1e-12))
        for (k in 0 until 5) { val a = 12.1 + k * 0.1; pic.frame(a - 0.300, a) }
        val late = pic.pictureLag(12.5)!!
        assertTrue("frames 300 ms late: the median follows them", near(late, 0.300 - 0.020 + 1.5 / 60 + 0.004))
        assertTrue("the guard decides: the lag + one frame", near(pic.delay(12.5, late), late + 1.0 / 60, 1e-12))
        assertTrue("a still window: the lag keeps its last value", near(pic.pictureLag(20.0)!!, late))
        assertNull("no frame and no floor: no lag", AudioPlayout().pictureLag(0.0))
    }

    // MARK: - Rule 10: a packet past the hand's deadline, before its timer ran

    @Test fun packetPastTheHandsDeadline() {
        val m = AudioPlayout()
        m.format(1, 480, 0, ByteArray(0))
        m.latency(0.010, 0.005, 1.0 / 60, 60.0)
        m.played(0.0, 100.0, 100.0)
        var t = 100.0
        for (k in 0 until 50) { m.packet(1, k.toLong(), t - 0.023, k == 0, now = t); t += 0.010 }
        val now = m.deadline!! + 0.004
        val out = m.packet(1, 50, t - 0.023, false, now = now).output
        assertEquals("the tail in hand goes out faded", true, out.schedules.firstOrNull { it.part == AudioPlayout.Schedule.Part.TAIL && it.seq == 49L }?.fadeOut)
        val next = out.schedules.filter { it.seq == 50L }
        assertTrue("…the packet placed by time, faded in (or late)", out.discards.isNotEmpty() || (next.isNotEmpty() && next.all { it.at != null && it.fadeIn }))
        assertTrue("…never less than the IO buffer + 2 ms before it renders", next.all { it.goesOut - now >= 0.005 + MARGIN - 1e-9 })
    }

    // MARK: - Fades and a frame gained or lost

    @Test fun fadesAndAFrameChanged() {
        val x = FloatArray(480) { 1f }
        AudioPlayout.fade(x, true)
        assertTrue("a fade in: 240 frames rising", x[0] < 0.001 && x[239] > 0.99 && x[240] == 1f && (0 until 240).all { x[it] <= x[it + 1] })
        val y = FloatArray(480) { 1f }
        AudioPlayout.fade(y, false)
        assertTrue("a fade out: the last 5 ms falling", y[479] < 0.001 && y[240] > 0.99 && y[239] == 1f)
        val short = FloatArray(100) { 1f }
        AudioPlayout.fade(short, true)
        assertTrue("shorter than a fade: over all of it", short[0] < 0.01 && short[99] > 0.99)
        val sine = FloatArray(480) { sin(2 * PI * 440 * it / 48_000).toFloat() }
        val (l, r) = AudioPlayout.change(sine.copyOf(), sine.copyOf(), 1)
        assertTrue("a frame gained: 481, both alike", l.size == 481 && r.size == 481 && l.contentEquals(r))
        val natural = (0 until 479).maxOf { abs(sine[it + 1] - sine[it]) }
        assertTrue("…no step larger than the sine's own", (0 until 480).maxOf { abs(l[it + 1] - l[it]) } <= natural * 1.01)
        val (l2, r2) = AudioPlayout.change(sine.copyOf(), sine.copyOf(), -1)
        assertTrue("a frame lost: 479", l2.size == 479 && r2.size == 479)
        assertTrue("…no step much larger", (0 until 478).maxOf { abs(l2[it + 1] - l2[it]) } <= natural * 1.6)
        val firstDiff = (0 until 480).first { sine[it] != l[it] }
        val q = (120 until 360).minByOrNull { abs(sine[it]) + abs(sine[it + 1]) }!!
        assertTrue("the change in the middle half, at its quietest point", firstDiff in 120..360 && firstDiff == q + 1)
        val (n0, _) = AudioPlayout.change(sine, sine, 0)
        assertTrue("no change for 0", n0.contentEquals(sine))
    }

    // MARK: - Resets, duplicates, mute

    @Test fun resetsDuplicatesAndMute() {
        val m = AudioPlayout()
        m.format(5, 480, 240, byteArrayOf(1))
        m.packet(5, 0, 0.99, true, now = 1.0)
        m.reset(AudioPlayout.Reset.SESSION)
        assertTrue("a session reset: no floor, no epoch", m.floor == null && m.epoch == null)
        assertTrue("…so the same epoch's format is a new decoder", m.format(5, 480, 240, byteArrayOf(1)))
        val p = m.packet(5, 0, 0.99, true, now = 1.5)
        assertTrue("…and a packet it saw before is no duplicate now", p.decode && p.id != null)

        val d = AudioPlayout()
        d.format(1, 480, 240, ByteArray(0))
        d.packet(1, 0, 0.99, true, now = 1.0)
        for (k in 1..300) d.packet(1, k.toLong(), k * 0.01 + 0.99, false, now = 1 + k * 0.01)
        assertTrue("older than the last 256: no longer a duplicate", d.packet(1, 1, 1.0, false, now = 5.0).decode)
        assertFalse("one among them is", d.packet(1, 299, 3.98, false, now = 5.0).decode)

        val wrap = AudioPlayout()
        wrap.format(65535, 480, 240, ByteArray(0))
        val a = wrap.packet(65535, 0xFFFF_FFFFL, 0.99, true, now = 1.0)
        val b = wrap.packet(65535, 0, 1.0, false, now = 1.01)
        assertTrue("the seq wraps without a reset", a.id != null && b.id != null && !b.resetFirst)
        assertFalse("another epoch than the format's is skipped", wrap.packet(3, 5, 1.0, false, now = 1.0).decode)

        val mu = AudioPlayout()
        mu.format(1, 480, 240, ByteArray(0))
        mu.mute(true)
        val q = mu.packet(1, 0, 0.99, true, now = 1.0)
        assertTrue("muted: the floor counts it, nothing decoded", !q.decode && q.id == null && mu.floor != null)
    }

    @Test fun segmentStartDropsThePrimingAndAJoinDropsAll() {
        val m = AudioPlayout()
        m.format(1, 480, 240, ByteArray(0))
        val first = m.packet(1, 0, 10.0, true, now = 10.01)
        assertTrue("a segment's first packet: reset, its 240 frames of priming dropped", first.decode && first.resetFirst && first.dropFront == 240 && first.id != null)
        val second = m.packet(1, 1, 10.01, false, now = 10.02)
        assertTrue("the next: no reset, nothing dropped", second.decode && !second.resetFirst && second.dropFront == 0)
        val gap = m.packet(1, 5, 10.05, false, now = 10.06)
        assertTrue("after a gap: reset, decoded and dropped whole", gap.decode && gap.resetFirst && gap.dropFront == 480 && gap.id == null)
        val joined = AudioPlayout()
        joined.format(1, 480, 240, ByteArray(0))
        val j = joined.packet(1, 777, 10.0, false, now = 10.01)
        assertTrue("joining mid-segment: the same", j.resetFirst && j.dropFront == 480 && j.id == null)
    }

    // MARK: - The simulation (Tests/checks/audio-playout's World and Run)

    class World {
        var seconds = 20.0
        var F = 480; var P = 240
        var outputLatency = 0.010
        var io = 0.005
        var refresh = 1.0 / 60
        var streamFPS = 60.0
        var videoFPS = 60.0
        var away = false
        var hostDrift = 0.0
        var hostSteps = listOf<Pair<Double, Double>>()
        var outDrift = 0.0
        var transit: (Double) -> Double = { 0.003 }
        var videoTransit: ((Double) -> Double)? = null
        var videoEncode = 0.008
        var pauses = listOf<Pair<Double, Double>>()
        var epochs = listOf<Double>()
        var skipped = setOf<Int>()
        /** from, to, extra. */
        var duplicate: Triple<Int, Int, Double>? = null
        class Move(val register: Double, val handOver: Double, val oldTransit: Double, val newTransit: Double)
        var move: Move? = null
        var joinAt: Int? = null
        var engineStart = 0.0
        var readingJitter = 0.0
        var mutes = listOf<Pair<Double, Boolean>>()
        var routes = listOf<Pair<Double, Double>>()
        var drifts = listOf<Pair<Double, Double>>()
        var ioAt = listOf<Pair<Double, Double>>()
        var homeAt: Double? = null
        var rtt: Double? = 0.005
        var processing = 0.00002
        var decodeFails = setOf<Int>()

        fun wall(t: Double) = WALL_BASE + t * (1 + hostDrift) + hostSteps.filter { it.first <= t }.sumOf { it.second }
    }

    class Played(val id: Int, val part: AudioPlayout.Schedule.Part, val first: Boolean, val seq: Long, val epoch: Int, val start: Double,
                 val frames: Int, val stamp: Double, val scheduledAt: Double, val arrival: Double, val fadeIn: Boolean, val fadeOut: Boolean,
                 val change: Int, val decision: AudioPlayout.Decision, val ear: Double, val contiguous: Boolean)

    class Rng(seed: Long) {
        private var state = seed * -0x61c8864680b583ebL + 1
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        fun unit(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble()
        fun range(a: Double, b: Double) = a + (b - a) * unit()
        fun chance(p: Double) = unit() < p
        fun int(a: Int, b: Int) = a + java.lang.Long.remainderUnsigned(next(), (b - a + 1).toLong()).toInt()
    }

    private class Kept(val seq: Long, val epoch: Int, val arrival: Double, val frames: Int, val good: Boolean, val fadeIn: Boolean, val firstStamp: Double)

    private sealed class Event {
        class Packet(val epoch: Int, val seq: Long, val stamp: Double, val segmentStart: Boolean, val arrival: Double) : Event()
        class Format(val epoch: Int) : Event()
        class Frame(val stamp: Double) : Event()
        class Mute(val muted: Boolean) : Event()
        class Route(val latency: Double) : Event()
        class Drift(val latency: Double) : Event()
        class Io(val io: Double) : Event()
        object Home : Event()
        object RttTick : Event()
        object Second : Event()
    }

    class Run(val w: World, seed: Long = 7) {
        val model = AudioPlayout()
        val played = ArrayList<Played>()
        val packets: List<Played> get() = played.filter { it.first }
        val tails: List<Played> get() = played.filter { it.part == AudioPlayout.Schedule.Part.TAIL }
        private val playedIDs = HashSet<Int>()
        private val partsDone = HashSet<Long>()
        private val playedKeys = HashSet<Long>()
        private val delivered = HashSet<Long>()
        val discarded = HashSet<Int>()
        private val kept = HashMap<Int, Kept>()
        val seconds = ArrayList<Pair<Double, AudioPlayout.Second>>()
        val errors = ArrayList<Pair<Double, Double>>()
        val violations = ArrayList<String>()
        var duplicatesSent = 0
        var dupChangedWindows = 0
        var formatsIgnored = 0
        val jumpsMade = ArrayList<Triple<Double, Double, AudioPlayout.JumpCause>>()
        private var t0: Double? = null
        private var playerEnd: Double? = null
        private var outputLatency = w.outputLatency
        private var io = w.io
        private var decEpoch = -1
        private var decNext = 0L
        private var decBroken = true
        private var decNoise = 0
        private val rng = Rng(seed)
        private var lastNow = 0.0
        private var staleness = 0.0
        private var lastReadingAt = 0.0

        private fun violation(s: String) { if (violations.size < 20) violations.add(s) }
        private fun T(s: Double) = t0!! + s / (RATE * (1 + w.outDrift))

        private fun reading(now: Double): Pair<Double, Double>? {
            val t0 = t0 ?: return null
            if (now < t0 - io) return null
            val block = max(1.0, AudioPlayout.rounded(io * RATE))
            val k = floor((now + io - t0) * RATE * (1 + w.outDrift) / block)
            if (k < 0) return null
            val s = k * block
            val jitter = if (w.readingJitter > 0) rng.range(-w.readingJitter, w.readingJitter) else 0.0
            return s to T(s) + jitter
        }

        private fun feedReading(now: Double) {
            val r = reading(now) ?: return
            lastReadingAt = now
            apply(model.played(r.first, r.second, now), now)
        }

        private fun apply(out: AudioPlayout.Output, now: Double) {
            model.takeJump()?.let { jumpsMade.add(Triple(now, it.first, it.second)) }
            for (d in out.discards) { if (d in discarded) violation("discarded twice: $d"); discarded.add(d) }
            for (s in out.schedules) schedule(s, now)
        }

        private fun schedule(s: AudioPlayout.Schedule, now: Double) {
            val k = kept[s.id] ?: run { violation("scheduled an id never kept: ${s.id}"); return }
            if (t0 == null) { violation("scheduled with no engine running: seq ${s.seq}"); return }
            if (s.id in discarded) violation("scheduled a discarded packet: seq ${s.seq}")
            val tail = s.part == AudioPlayout.Schedule.Part.TAIL
            val partKey = (s.id.toLong() shl 1) or (if (tail) 1L else 0L)
            if (!partsDone.add(partKey)) violation("id ${s.id}'s ${s.part} scheduled twice")
            val first = playedIDs.add(s.id)
            if (!tail && !first) violation("seq ${s.seq}'s body after its tail")
            if (tail && first && s.offset != 0) violation("seq ${s.seq}'s tail before its body")
            val tailCount = minOf(FADE_FRAMES, k.frames)
            if (if (!tail) s.offset != 0 || s.count != k.frames - tailCount else s.offset != k.frames - tailCount || s.count != tailCount) {
                violation("seq ${s.seq}'s ${s.part}: frames ${s.offset}+${s.count} of ${k.frames}")
            }
            if (first && !playedKeys.add((s.epoch.toLong() shl 32) or s.seq)) violation("seq ${s.seq} of epoch ${s.epoch} played twice")
            val start: Double
            val at = s.at
            if (at != null) {
                start = at
                playerEnd?.let { end -> if (at < end - 0.5) violation("seq ${s.seq} overlaps: at $at before the end $end") }
                if (!first) violation("seq ${s.seq}'s tail placed apart from its body")
            } else {
                val end = playerEnd ?: run { violation("seq ${s.seq} follows nothing"); return }
                start = end
                if (abs(end - s.start) > 0.5) violation("seq ${s.seq} planned at ${s.start}, follows at $end")
            }
            val seen = s.goesOut - now
            val moved = abs(w.outDrift) * (staleness + io + MARGIN) + 2 * w.readingJitter + 1e-6
            if (seen < io + MARGIN - moved) violation("seq ${s.seq}'s ${s.part} scheduled ${"%.4f".format(seen * 1000)} ms before it goes out as the model sees it")
            val ahead = T(start) - now
            val slack = 2e-5 + abs(w.outDrift) * (max(0.0, ahead) + staleness + io) + 2 * w.readingJitter
            if (ahead < io + MARGIN - slack) violation("seq ${s.seq}'s ${s.part} scheduled ${"%.4f".format(ahead * 1000)} ms before it goes out")
            if (T(start) - io < k.arrival) violation("seq ${s.seq} renders before it arrives")
            if (abs(s.change) > 1 || (tail && s.change != 0)) violation("seq ${s.seq}: ${s.change} frames changed in its ${s.part}")
            if (s.frames != s.count + s.change) violation("seq ${s.seq}: ${s.frames} frames, ${s.count} and changed ${s.change}")
            if (!k.good) violation("seq ${s.seq} played though decoded on a reset decoder mid-segment")
            if (abs(s.stamp - k.firstStamp) > 1e-7) violation("seq ${s.seq} placed by ${s.stamp}, its first kept frame's stamp is ${k.firstStamp}")
            if (first && k.fadeIn && !s.fadeIn) violation("seq ${s.seq} after a join played without a fade in")
            if (s.fadeIn && !first) violation("seq ${s.seq}: a fade in on its tail after its body")
            if (s.fadeOut && !tail) violation("seq ${s.seq}: a fade out on its body")
            if (first && s.decision.ear < s.decision.picture - 1e-9) violation("seq ${s.seq} ahead of the picture by ${s.decision.picture - s.decision.ear}")
            playerEnd = start + s.frames
            played.add(Played(s.id, s.part, first, s.seq, s.epoch, start, s.frames, s.stamp, now, k.arrival, s.fadeIn, s.fadeOut,
                s.change, s.decision, T(start) + outputLatency, at == null))
        }

        fun run(): Run {
            val events = ArrayList<Triple<Double, Int, Event>>()
            var order = 0
            fun add(t: Double, e: Event) { events.add(Triple(t, order, e)); order++ }
            val segF = w.F / RATE
            val breaks = (w.pauses.map { Triple(it.first, it.second, false) } + w.epochs.map { Triple(it, 0.0, true) }).sortedBy { it.first }
            var epoch = 1
            var seq = 0
            var segStart = 0.0
            var lastOld = Double.NEGATIVE_INFINITY
            var lastNew = Double.NEGATIVE_INFINITY
            var lastDup = Double.NEGATIVE_INFINITY
            var n = 0
            var bi = 0
            val firstSeq = w.joinAt ?: 0
            add(0.0, Event.Format(epoch))
            while (true) {
                val produce = segStart + (n + 1) * segF - w.P / RATE + 0.010
                if (produce > w.seconds) break
                if (bi < breaks.size && produce >= breaks[bi].first) {
                    val (at, pause, newEpoch) = breaks[bi]
                    bi++
                    segStart = at + pause
                    n = 0
                    if (newEpoch) { epoch++; seq = 0; add(segStart + 0.001, Event.Format(epoch)) }
                    continue
                }
                val content = segStart + (n * w.F - w.P) / RATE
                val stamp = w.wall(content)
                if (!(epoch == 1 && (seq in w.skipped || seq < firstSeq))) {
                    val e = epoch; val sq = seq.toLong(); val start = n == 0
                    fun deliver(arrival: Double) = add(arrival + w.processing, Event.Packet(e, sq, stamp, start, arrival))
                    val m = w.move
                    if (m != null) {
                        lastOld = max(lastOld, produce + m.oldTransit)
                        if (lastOld < m.handOver) deliver(lastOld)
                        if (produce >= m.register) { lastNew = maxOf(lastNew, produce + m.newTransit, m.handOver); deliver(lastNew) }
                    } else {
                        lastOld = max(lastOld, produce + w.transit(produce))
                        deliver(lastOld)
                    }
                    val d = w.duplicate
                    if (epoch == 1 && d != null && seq >= d.first && seq <= d.second) {
                        lastDup = max(lastDup, produce + w.transit(produce) + d.third)
                        deliver(lastDup)
                    }
                }
                seq++
                n++
            }
            w.move?.let { add(it.handOver + w.processing - 0.0001, Event.Format(1)) }
            if (w.videoFPS > 0) {
                var t = 0.0
                var last = Double.NEGATIVE_INFINITY
                val vt = w.videoTransit ?: w.transit
                while (t < w.seconds) {
                    val out = t + w.videoEncode
                    last = max(last, out + vt(out))
                    add(last, Event.Frame(w.wall(out)))
                    t += 1 / w.videoFPS
                }
            }
            for (m in w.mutes) add(m.first, Event.Mute(m.second))
            for (r in w.routes) add(r.first, Event.Route(r.second))
            for (d in w.drifts) add(d.first, Event.Drift(d.second))
            for (x in w.ioAt) add(x.first, Event.Io(x.second))
            w.homeAt?.let { add(it, Event.Home) }
            var s = 1.0
            while (s < w.seconds + 1) { add(s, Event.RttTick); add(s + 0.0005, Event.Second); s += 1 }
            events.sortWith(compareBy<Triple<Double, Int, Event>> { it.first }.thenBy { it.second })

            model.latency(outputLatency, io, w.refresh, w.streamFPS)
            model.away(w.away)
            model.rtt(w.rtt)
            var engineAt: Double? = null
            var muted = false
            events.removeAll { it.first > w.seconds + 1 }
            events.add(Triple(w.seconds + 1, order, Event.Second))
            var i = 0
            while (i < events.size) {
                val (t, _, e) = events[i]
                val at = engineAt
                if (at != null && t0 == null && at <= t) {
                    t0 = at
                    playerEnd = null
                    feedReading(at)
                    continue
                }
                val d = model.deadline
                if (d != null && d < t) {
                    var now = max(d, lastNow)
                    staleness = now - lastReadingAt
                    feedReading(now)
                    now = max(now, model.deadline ?: now)
                    lastNow = now
                    apply(model.tick(now), now)
                    continue
                }
                i++
                lastNow = t
                staleness = 0.0
                when (e) {
                    is Event.Format -> if (!model.format(e.epoch, w.F, w.P, byteArrayOf(1, 2, (e.epoch and 0xFF).toByte()))) formatsIgnored++
                    is Event.Frame -> model.frame(e.stamp, t)
                    is Event.Packet -> {
                        val dup = !delivered.add((e.epoch.toLong() shl 32) or e.seq)
                        if (dup) duplicatesSent++
                        val before = Triple(model.floor, model.cover(t), model.need(t))
                        if (engineAt == null && !muted) engineAt = t + w.engineStart
                        feedReading(t)
                        val plan = model.packet(e.epoch, e.seq, e.stamp, e.segmentStart, now = t)
                        if (dup && (model.floor != before.first || model.cover(t) != before.second || model.need(t) != before.third)) dupChangedWindows++
                        if (dup && plan.decode) violation("a duplicate (seq ${e.seq}) decoded")
                        decode(plan, e.epoch, e.seq, e.segmentStart, e.arrival, e.stamp)
                        apply(plan.output, t)
                    }
                    is Event.Mute -> {
                        muted = e.muted
                        model.mute(e.muted)
                        apply(model.reset(AudioPlayout.Reset.PLAYBACK), t)
                        t0 = null
                        playerEnd = null
                        engineAt = if (e.muted) null else t + w.engineStart
                    }
                    is Event.Route -> {
                        outputLatency = e.latency
                        model.latency(e.latency, io, w.refresh, w.streamFPS)
                        apply(model.reset(AudioPlayout.Reset.JUMP), t)
                    }
                    is Event.Drift -> { outputLatency = e.latency; model.latency(e.latency, io, w.refresh, w.streamFPS) }
                    is Event.Io -> { io = e.io; model.latency(outputLatency, e.io, w.refresh, w.streamFPS) }
                    Event.Home -> model.away(false)
                    Event.RttTick -> model.rtt(w.rtt)
                    Event.Second -> {
                        val sec = model.takeSecond(t)
                        seconds.add(t to sec)
                        sec.error?.let { errors.add(t to it) }
                    }
                }
            }
            while (true) {
                val d = model.deadline ?: break
                if (d > w.seconds + 1) break
                val now = max(d, lastNow)
                staleness = now - lastReadingAt
                feedReading(now)
                val at = max(now, model.deadline ?: now)
                lastNow = at
                apply(model.tick(at), at)
            }
            val tailsDone = played.filter { it.part == AudioPlayout.Schedule.Part.TAIL }.map { it.id }.toSet()
            val held = kept.keys - discarded - tailsDone
            if (held.size > 1 + WAITING_LIMIT) violation("${held.size} kept packets never played nor discarded")
            return this
        }

        /** The decoder: what the plan asks, and whether what it gives is the signal. */
        private fun decode(plan: AudioPlayout.Plan, epoch: Int, seq: Long, segmentStart: Boolean, arrival: Double, stamp: Double) {
            if (!plan.decode) {
                if (plan.id != null) violation("seq $seq kept without being decoded")
                return
            }
            var good: Boolean
            if (plan.resetFirst) {
                decEpoch = epoch
                decNext = (seq + 1) and 0xFFFF_FFFFL
                decBroken = false
                if (segmentStart) {
                    good = true
                    decNoise = 0
                    if (plan.dropFront != minOf(w.P, w.F)) violation("seq $seq: a segment's first packet drops ${plan.dropFront}, not ${w.P}")
                } else {
                    good = false
                    decNoise = 1
                }
            } else if (segmentStart) {
                decBroken = true
                good = false
            } else if (!decBroken && decEpoch == epoch && decNext == seq) {
                decNext = (seq + 1) and 0xFFFF_FFFFL
                good = true
            } else {
                decBroken = true
                good = false
            }
            val fadeRequired = decNoise == 1 && good && !plan.resetFirst
            if (good && !plan.resetFirst && decNoise > 0) decNoise = 0
            if (epoch == 1 && seq.toInt() in w.decodeFails && plan.id != null) {
                model.decodeFailed()
                good = true
                decBroken = true
            }
            val id = plan.id
            if (id != null) {
                kept[id] = Kept(seq, epoch, arrival, w.F - plan.dropFront, good, fadeRequired, stamp + plan.dropFront / RATE)
            } else if (plan.dropFront != w.F && !(segmentStart && w.P >= w.F)) {
                violation("seq $seq decoded and neither kept nor dropped whole")
            }
            if (plan.resetFirst && !segmentStart && plan.id != null) violation("seq $seq on a reset decoder mid-segment was kept")
        }

        fun lateAfter(t: Double) = seconds.filter { it.first > t }.sumOf { it.second.late }
        fun jumps(after: Double) = seconds.filter { it.first > after }.sumOf { it.second.jumps }
        fun jumps(a: Double, b: Double) = seconds.filter { it.first > a && it.first <= b + 1 }.sumOf { it.second.jumps }
        fun late(a: Double, b: Double) = seconds.filter { it.first > a && it.first <= b + 1 }.sumOf { it.second.late }
        fun playedAfter(t: Double) = packets.filter { it.scheduledAt > t }
        val worstEarOff: Double get() = packets.maxOfOrNull { abs(it.ear - it.decision.ear) } ?: 0.0
        fun underruns(after: Double) = tails.count { it.scheduledAt > after && it.scheduledAt < w.seconds - 0.5 && it.fadeOut }
        fun packet(seq: Long, epoch: Int = 1) = packets.firstOrNull { it.seq == seq && it.epoch == epoch }
        fun tail(seq: Long, epoch: Int = 1) = tails.firstOrNull { it.seq == seq && it.epoch == epoch }
        val joins: Int get() = seconds.sumOf { it.second.joins }
    }

    private fun simulate(w: World, seed: Long = 7) = Run(w, seed).run()
    private fun noViolations(r: Run, what: String) = assertTrue("$what: ${r.violations.take(3)}", r.violations.isEmpty())

    // MARK: - Scenarios

    @Test fun steadyLinks() {
        for ((ol, io) in listOf(0.010 to 0.005, 0.180 to 0.005, 2.0 to 0.005, 0.010 to 0.023, 0.180 to 0.023, 2.0 to 0.023)) {
            val w = World().apply { seconds = 30.0; outputLatency = ol; this.io = io; transit = { t -> 0.003 + 0.0005 * sin(t * 7.3) } }
            val r = simulate(w)
            val what = "steady, output ${ms(ol)}, IO ${ms(io)}"
            noViolations(r, what)
            assertEquals("$what: nothing late after 2 s", 0, r.lateAfter(2.0))
            assertTrue("$what: every packet after 3 s played", r.playedAfter(3.0).size >= 2600)
            assertTrue("$what: no jump or fade out after 3 s", r.jumps(3.0) == 0 && r.underruns(3.0) == 0)
            val settled = r.errors.filter { it.first > 20 }.maxOfOrNull { abs(it.second) } ?: 1.0
            assertTrue("$what: error under 1 ms once settled (${ms(settled)})", settled < 0.001)
            assertTrue("$what: heard where placed (${ms(r.worstEarOff)})", r.worstEarOff < 0.0005)
        }
        val r512 = simulate(World().apply { F = 512; P = 256; seconds = 25.0 })
        noViolations(r512, "steady, 512 frames a packet")
        assertTrue("steady, 512: nothing late, everything played", r512.lateAfter(2.0) == 0 && r512.playedAfter(3.0).size >= 2000)
        val r120 = simulate(World().apply { streamFPS = 120.0; videoFPS = 120.0; refresh = 1.0 / 120 })
        noViolations(r120, "steady, 120 fps")
        assertEquals(0, r120.lateAfter(2.0))
    }

    @Test fun theSoundTrailsThePicture() {
        val r = simulate(World())
        val behind = r.seconds.mapNotNull { it.second.behindMs }.takeLast(10)
        assertTrue("at home the sound trails the picture by 0–100 ms ($behind)", behind.isNotEmpty() && behind.all { it in 1..99 })
        val d = r.seconds.last().second
        assertTrue("the delay is at least the need", d.delay >= d.need - 1e-9)
        val late = simulate(World().apply { videoTransit = { 1.0 } })
        noViolations(late, "a picture a second late")
        val s = late.seconds.last().second
        assertTrue("…the delay follows the picture", s.delay > 1.0 && (s.lag ?: 0.0) > 0.9)
        assertTrue("…the sound a frame behind it", late.packets.takeLast(100).all { it.decision.ear - it.decision.picture >= 1.0 / 60 - 0.0002 })
        noViolations(simulate(World().apply { seconds = 25.0; videoTransit = { t -> 0.2 + if (t > 10) minOf(0.040, (t - 10) * 0.013) else 0.0 } }), "the picture's lag rising fast")
        val still = simulate(World().apply { videoFPS = 0.0 })
        noViolations(still, "no picture at all")
        assertEquals("…plays by the need alone", 0, still.lateAfter(2.0))
    }

    @Test fun keyframeBurstsAndLatePackets() {
        val w = World().apply {
            seconds = 40.0
            transit = { t -> val into = t % 4; 0.003 + if (into < 0.040) 0.040 - into else 0.0 }
        }
        val r = simulate(w)
        noViolations(r, "a 40 ms burst every 4 s")
        assertEquals("…nothing late after 2 s", 0, r.lateAfter(2.0))
        assertEquals("…no fade out once the cover has it", 0, r.underruns(9.0))
        assertTrue("…the cover holds the burst", r.seconds.filter { it.first > 10 }.all { it.second.cover in 0.030..0.046 })

        val one = simulate(World().apply { seconds = 12.0; transit = { t -> if (abs(t - 6) < 0.005) 0.073 else 0.003 } })
        noViolations(one, "one packet 70 ms late")
        assertEquals("…nothing dropped as late: the cover rose with it", 0, one.lateAfter(2.0))

        val first = simulate(World().apply { transit = { t -> if (t < 0.1) 0.070 else 0.003 } })
        noViolations(first, "the first packets behind a keyframe")
        assertEquals(0, first.lateAfter(2.0))

        val stall = simulate(World().apply { seconds = 30.0; transit = { t -> if (t >= 8 && t < 10) 10 - t + 0.003 else 0.003 } })
        noViolations(stall, "a 2 s stall, then a burst")
        assertTrue("…the burst's old packets dropped as late", stall.late(9.9, 10.5) > 50)
        assertEquals("…a jump once the window forgets the stall", 1, stall.jumps(18.0, 22.0))
        assertEquals("…nothing late after the burst", 0, stall.lateAfter(11.5))
    }

    @Test fun gapsJoinsPausesEpochs() {
        val gap = simulate(World().apply { skipped = setOf(1000, 1001, 1002) })
        noViolations(gap, "a seq gap")
        assertNull("…the packet after the gap is decoded and not played", gap.packet(1003))
        assertEquals("…the next fades in", true, gap.packet(1004)?.fadeIn)
        assertEquals("…the one before it fades out", true, gap.tail(999)?.fadeOut)
        assertEquals("…one join", 1, gap.joins)

        val join = simulate(World().apply { joinAt = 777 })
        noViolations(join, "joining mid-segment")
        assertTrue("…its first packet dropped, the next placed by time, faded in",
            join.packet(777) == null && join.packets.first().seq == 778L && join.packets.first().fadeIn)

        val pauses = simulate(World().apply { seconds = 20.0; this.pauses = listOf(6.0 to 0.05, 12.0 to 0.5) })
        noViolations(pauses, "the Mac pauses 50 and 500 ms")
        val starts = pauses.packets.filter { !it.contiguous && it.scheduledAt > 3 }
        assertTrue("…two segment starts placed by time, faded in", starts.size == 2 && starts.all { it.fadeIn })
        assertTrue("…each at its stamp, after the priming", starts.all { abs(it.decision.error) < 0.0002 })
        val before = pauses.tails.filter { p -> starts.any { it.seq == p.seq + 1 } }
        assertTrue("…the packet before each fades out", before.size == 2 && before.all { it.fadeOut })
        assertEquals(0, pauses.lateAfter(2.0))

        val epoch = simulate(World().apply { seconds = 20.0; epochs = listOf(10.0) })
        noViolations(epoch, "a new epoch")
        assertEquals("…its first packet plays, from seq 0", 0L, epoch.packets.firstOrNull { it.epoch == 2 }?.seq)
        assertEquals("…the old epoch's last fades out", true, epoch.tails.lastOrNull { it.epoch == 1 }?.fadeOut)

        val fail = simulate(World().apply { decodeFails = setOf(500) })
        noViolations(fail, "a decode that fails")
        assertTrue("…the next dropped, the one after faded in", fail.packet(501) == null && fail.packet(502)?.fadeIn == true)

        val stops = simulate(World().apply { seconds = 5.0; transit = { t -> if (t > 3) 100.0 else 0.003 } })
        noViolations(stops, "the Mac stops sending")
        assertEquals("…the last packet goes out at its deadline, faded", true, stops.tails.last().fadeOut)
    }

    @Test fun duplicatesAndMoves() {
        val move = simulate(World().apply { seconds = 20.0; this.move = World.Move(10.0, 10.2, 0.033, 0.003) })
        noViolations(move, "a move's hand-over")
        val dups = move.seconds.sumOf { it.second.duplicates }
        assertTrue("…every copy dropped as a duplicate", move.duplicatesSent >= 15 && dups == move.duplicatesSent)
        assertEquals("…none counted toward the floor, the cover or the need", 0, move.dupChangedWindows)
        assertEquals("…the new connection's format ignored", 1, move.formatsIgnored)
        assertTrue("…at most one jump", move.jumps(9.0, 13.0) <= 1)
        assertEquals("…nothing reset", 0, move.joins)
        assertEquals(0, move.lateAfter(2.0))
        for (extra in listOf(-0.05, 0.02, 0.3)) {
            val r = simulate(World().apply { duplicate = Triple(700, 760, extra) })
            noViolations(r, "61 packets delivered twice, the copy ${ms(extra)} off")
            assertTrue("…61 duplicates, none counted", r.duplicatesSent == 61 && r.dupChangedWindows == 0)
        }
        val home = simulate(World().apply { seconds = 30.0; away = true; homeAt = 15.0; transit = { t -> if (t < 15) 0.080 + 0.050 * sin(t * 3.1) else 0.004 } })
        noViolations(home, "away at 80 ± 50 ms, then home")
        assertEquals("…nothing late away", 0, home.lateAfter(2.0))
        assertTrue("…away's lower bound", home.seconds.filter { it.first > 5 && it.first < 15 }.all { it.second.cover >= 0.060 })
        assertTrue("…home's bounds once the window forgets", home.seconds.filter { it.first > 27 }.all { it.second.cover <= 0.150 })
    }

    @Test fun clocksDriftAndStep() {
        for ((host, out) in listOf(100e-6 to 0.0, -100e-6 to 0.0, 0.0 to 100e-6, 100e-6 to -100e-6)) {
            val r = simulate(World().apply { seconds = 3600.0; hostDrift = host; outDrift = out; videoFPS = 5.0 })
            val what = "the Mac's clock ${host * 1e6} ppm, the output's ${out * 1e6} ppm"
            noViolations(r, what)
            val worst = r.errors.filter { it.first > 60 }.maxOfOrNull { abs(it.second) } ?: 1.0
            assertTrue("$what: error within ±5 ms after a minute (${ms(worst)})", worst < 0.005)
            assertTrue("$what: no jump and nothing late after a minute", r.jumps(60.0) == 0 && r.lateAfter(60.0) == 0)
        }
        for (by in listOf(1.0, -1.0)) {
            val r = simulate(World().apply { seconds = 25.0; hostSteps = listOf(12.0 to by) })
            noViolations(r, "the Mac's clock steps $by s")
            assertTrue("…a jump or two", r.jumps(11.0, 16.0) in 1..2)
            assertTrue("…nothing more after it", r.jumps(17.0) == 0 && r.lateAfter(17.0) == 0)
            val tail = r.errors.filter { it.first > 18 }.maxOfOrNull { abs(it.second) } ?: 1.0
            assertTrue("…the error back under 5 ms", tail < 0.005)
            assertEquals("…each jump reported once", r.jumps(11.0, 16.0), r.jumpsMade.size)
            if (by > 0) assertTrue("…forward: a clock step's jump of nothing",
                r.jumpsMade.first().third == AudioPlayout.JumpCause.STEP && abs(r.jumpsMade.first().second) < 0.005)
            else assertTrue("…back: the second dropped, then the clock step",
                r.jumpsMade.first().second < -0.5 && r.jumpsMade.any { it.third == AudioPlayout.JumpCause.STEP })
        }
        val slower = simulate(World().apply { seconds = 25.0; transit = { t -> if (t < 10) 0.003 else 0.083 } })
        noViolations(slower, "the path 80 ms slower")
        assertTrue("…a jump", slower.jumps(10.0, 13.0) >= 1)
        assertTrue("…then steady", slower.lateAfter(13.0) == 0 && slower.jumps(13.0) == 0)
        val half = simulate(World().apply { seconds = 20.0; transit = { t -> if (t >= 10 && t < 10.5) 0.063 else 0.003 } })
        noViolations(half, "half a second 60 ms slower")
        assertEquals("…no step and no jump", 0, half.jumps(9.0, 20.0))
    }

    @Test fun routesMutesAndTheEngineStarting() {
        val route = simulate(World().apply { seconds = 25.0; routes = listOf(8.0 to 0.190, 16.0 to 0.010) })
        noViolations(route, "a route change to Bluetooth and back")
        assertTrue("…one jump each way", route.jumps(7.5, 9.0) == 1 && route.jumps(15.5, 17.0) == 1)
        assertEquals(0, route.lateAfter(2.0))
        assertTrue("…placed where due at once", (route.errors.filter { it.first > 10 && it.first < 16 }.maxOfOrNull { abs(it.second) } ?: 1.0) < 0.002)

        val mute = simulate(World().apply { seconds = 20.0; engineStart = 0.030; mutes = listOf(6.0 to true, 12.0 to false) })
        noViolations(mute, "mute and unmute")
        assertTrue("…nothing played while muted", mute.played.none { it.scheduledAt > 6 && it.scheduledAt < 12 })
        val back = mute.packets.firstOrNull { it.scheduledAt > 12 }
        assertNotNull(back)
        assertTrue("…sound again within 0.2 s of unmuting, faded in", back!!.decision.ear - 12 < 0.2 && back.fadeIn)

        val slow = simulate(World().apply { engineStart = 0.080 })
        noViolations(slow, "the engine takes 80 ms to start")
        assertTrue("…from a packet that waited for it", slow.packets.size > 1500 && slow.packets.first().seq < 8)
        val slower = simulate(World().apply { engineStart = 2.5 })
        noViolations(slower, "an engine that takes 2.5 s")
        assertTrue("…the oldest discarded", slower.discarded.size >= 100)

        // The output latency measured afresh with no reset (MacSound's case): rule 11 closes the error.
        for (F in listOf(480, 512)) {
            val r = simulate(World().apply { seconds = 40.0; this.F = F; P = F / 2; io = 0.035; ioAt = listOf(10.0 to 0.005) })
            noViolations(r, "the IO buffer shrinks with no reset, $F frames")
            assertTrue("…no jump, nothing late, the error closed", r.jumps(10.0) == 0 && r.lateAfter(2.0) == 0 &&
                abs(r.errors.last().second) < 0.005)
        }
        val jitter = simulate(World().apply { seconds = 30.0; readingJitter = 0.0002 })
        noViolations(jitter, "readings with ±0.2 ms of jitter")
        assertTrue("…few frames changed once settled", jitter.seconds.filter { it.first > 15 }.sumOf { it.second.framesAdded + it.second.framesDropped } < 100)
    }

    /** Random worlds (the Swift check runs 5,000; 1,500 here): every invariant holds. */
    @Test fun randomRuns() {
        val rng = Rng(20260927)
        var steady = 0
        repeat(1500) { k ->
            val w = World()
            w.seconds = rng.range(4.0, 12.0)
            if (rng.chance(0.3)) { w.F = 512; w.P = 256 }
            w.outputLatency = if (rng.chance(0.2)) rng.range(0.1, 2.0) else rng.range(0.003, 0.03)
            w.io = rng.range(0.002, 0.030)
            w.away = rng.chance(0.25)
            w.hostDrift = rng.range(-200e-6, 200e-6)
            w.outDrift = rng.range(-200e-6, 200e-6)
            w.videoFPS = if (rng.chance(0.2)) 0.0 else if (rng.chance(0.3)) 120.0 else 60.0
            w.streamFPS = if (w.videoFPS == 120.0) 120.0 else 60.0
            w.refresh = if (rng.chance(0.5)) 1.0 / 60 else 1.0 / 120
            w.readingJitter = if (rng.chance(0.3)) rng.range(0.0, 0.0005) else 0.0
            w.engineStart = if (rng.chance(0.3)) rng.range(0.0, 0.2) else 0.0
            w.rtt = if (rng.chance(0.2)) null else rng.range(0.001, 0.2)
            val base = if (w.away) rng.range(0.02, 0.2) else rng.range(0.001, 0.02)
            val jitter = rng.range(0.0, if (w.away) 0.05 else 0.01)
            val seed = rng.next()
            var events = 0
            var burstEvery = 0.0; var burstSize = 0.0; var stallAt = -1.0; var stallFor = 0.0; var stepPath = -1.0; var stepBy = 0.0
            if (rng.chance(0.3)) { burstEvery = rng.range(1.0, 5.0); burstSize = rng.range(0.01, 0.15); events++ }
            if (rng.chance(0.15)) { stallAt = rng.range(1.0, w.seconds); stallFor = rng.range(0.05, 2.5); events++ }
            if (rng.chance(0.15)) { stepPath = rng.range(1.0, w.seconds); stepBy = rng.range(-0.08, 0.12); events++ }
            val phase = rng.range(0.0, 100.0)
            w.transit = { t ->
                var d = base + jitter * (0.5 + 0.5 * sin(t * 13.7 + phase)) * abs(sin(t * 3.1 + phase * 2))
                if (burstEvery > 0) { val into = t % burstEvery; if (into < burstSize) d += burstSize - into }
                if (stallAt >= 0 && t >= stallAt && t < stallAt + stallFor) d += stallAt + stallFor - t
                if (stepPath >= 0 && t >= stepPath) d = max(0.0005, d + stepBy)
                d
            }
            if (rng.chance(0.3)) { val lag = rng.range(0.0, 1.0); w.videoTransit = { t -> base + lag + 0.01 * sin(t) } }
            if (rng.chance(0.15)) { w.hostSteps = listOf(rng.range(1.0, w.seconds) to rng.range(-1.5, 1.5)); events++ }
            if (rng.chance(0.2)) { w.pauses = listOf(rng.range(0.5, w.seconds) to rng.range(0.02, 1.0)); events++ }
            if (rng.chance(0.1)) { w.epochs = listOf(rng.range(0.5, w.seconds)); events++ }
            if (rng.chance(0.15)) { val s = rng.int(10, 500); w.skipped = (s until s + rng.int(1, 5)).toSet(); events++ }
            if (rng.chance(0.15)) { val s = rng.int(10, 400); w.duplicate = Triple(s, s + rng.int(0, 60), rng.range(-0.05, 0.1)); events++ }
            if (rng.chance(0.1)) w.joinAt = rng.int(1, 200)
            if (rng.chance(0.15)) { val a = rng.range(0.5, w.seconds); w.mutes = listOf(a to true, a + rng.range(0.05, 3.0) to false); events++ }
            if (rng.chance(0.15)) { w.routes = listOf(rng.range(0.5, w.seconds) to rng.range(0.003, 0.3)); events++ }
            if (rng.chance(0.1)) { w.drifts = listOf(rng.range(0.5, w.seconds) to max(0.003, w.outputLatency + rng.range(-0.03, 0.03))); events++ }
            if (w.away && rng.chance(0.2)) { w.homeAt = rng.range(1.0, w.seconds); events++ }
            if (rng.chance(0.05)) { w.decodeFails = setOf(rng.int(20, 300)); events++ }
            val r = simulate(w, seed)
            noViolations(r, "random $k")
            if (events == 0 && jitter < (if (w.away) 0.045 else 0.009) && w.joinAt == null && w.videoTransit == null) {
                steady++
                assertEquals("random $k: a steady link lost packets as late after 2 s", 0, r.lateAfter(2.0))
            }
        }
        assertTrue("some runs were steady ($steady)", steady > 0)
    }

    companion object {
        const val WALL_BASE = 1_790_000_000.0
    }
}
