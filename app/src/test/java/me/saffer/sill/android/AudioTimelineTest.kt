package me.saffer.sill.android

import me.saffer.sill.android.audio.AudioPlayout
import me.saffer.sill.android.audio.ClockFeed
import me.saffer.sill.android.audio.OutputClock
import me.saffer.sill.android.audio.PlayerTimeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The output side's pure parts (audio/PlayerTimeline.kt): the timeline the AudioTrack's writer pulls, the
 * clock its time stamps make, the feed that hands the clock to the playout, and the three with the
 * playout on a simulated track, checked for where each sample is heard.
 */
class AudioTimelineTest {
    private fun read(t: PlayerTimeline, frames: Int): FloatArray = FloatArray(frames * 2).also { t.read(it, frames) }

    @Test fun timeline() {
        val t = PlayerTimeline(2)
        assertEquals("a buffer at its sample", 4L, t.schedule(floatArrayOf(1f, 1f, 2f, 2f), 4.0))
        assertEquals("the next right after it", 6L, t.schedule(floatArrayOf(3f, 3f), null))
        assertEquals("one asked to start before the last ends starts at its end", 7L, t.schedule(floatArrayOf(4f, 4f), 5.0))
        assertEquals("rounded as Swift rounds (halves away from zero)", 9L, t.schedule(floatArrayOf(5f, 5f), 8.5))
        assertEquals(5, t.pending())
        assertArrayEquals("silence, then the buffers in order", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 0f, 0f, 5f, 5f), read(t, 10), 0f)
        assertEquals(10L, t.position())
        assertEquals("nothing left", 0, t.pending())
        assertEquals("a buffer whose sample has been read starts where the writer is", 10L, t.schedule(floatArrayOf(6f, 6f), 2.0))
        assertArrayEquals(floatArrayOf(6f, 6f, 0f, 0f), read(t, 2), 0f)
        assertEquals("a contiguous one after the last has played starts where the writer is", 12L, t.schedule(floatArrayOf(7f, 7f), null))
        assertArrayEquals(floatArrayOf(7f, 7f, 0f, 0f), read(t, 2), 0f)

        val f = PlayerTimeline(2)
        f.schedule(FloatArray(2000) { 1f }, 0.0)
        f.fadeOut(4)
        val out = read(f, 6)
        assertArrayEquals("the fade to silence: a linear ramp, then silence", floatArrayOf(0.75f, 0.75f, 0.5f, 0.5f, 0.25f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0f), out, 1e-6f)
        assertTrue("…and silence after", read(f, 10).all { it == 0f })
    }

    @Test fun clock() {
        val c = OutputClock(48_000.0, window = 2.0)
        assertNull("no stamp: no ear", c.ear(0))
        c.wrote(0, 1.0)
        assertNull("no stamp: no latency", c.latency())
        c.guess(0, 1.02)
        assertTrue(c.hasStamp() && !c.hasRealStamp())
        c.stamp(4800, 1.2)
        assertEquals("a real stamp replaces the guess", 1.2 + 0.1, c.ear(9600)!!, 1e-12)
        c.guess(0, 5.0)
        assertEquals("…and a guess never replaces a real stamp", 1.2, c.reading()!!.second, 1e-12)
        c.wrote(9600, 1.27)     // heard 30 ms after it was taken
        c.wrote(9840, 1.28)     // 25 ms
        assertEquals("the latency: the largest of the window", 0.030, c.latency()!!, 1e-9)
        c.wrote(48_000 + 4800, 2.08)    // 120 ms
        assertEquals(0.12, c.latency()!!, 1e-9)
        c.wrote(4800 + 48_000 * 3, 4.19)  // 10 ms, more than 2 s after the 120
        assertEquals("the window forgets after 2 s", 0.01, c.latency()!!, 1e-9)
        c.forgetLatency()
        assertNull("a new route: nothing measured yet", c.latency())

        val m = AudioPlayout()
        val feed = ClockFeed(m)
        val k = OutputClock()
        k.stamp(0, 16.0)
        k.wrote(0, 16.0 - 0.03125)                  // heard 31.25 ms after it was taken
        feed.feed(k, 0.006, 1.0 / 60, 60.0, 16.0)
        assertEquals("the latency to the model, up to the next half ms", 0.0315, m.outputLatency, 1e-12)
        k.wrote(0, 16.0 - 0.03173828125)            // 31.7 ms: 32 ms
        feed.feed(k, 0.006, 1.0 / 60, 60.0, 16.0)
        assertEquals("…a move of 1 ms or less is not passed on", 0.0315, m.outputLatency, 1e-12)
        k.wrote(0, 16.0 - 0.033203125)              // 33.2 ms: 33.5 ms
        feed.feed(k, 0.006, 1.0 / 60, 60.0, 16.0)
        assertEquals("…more is", 0.0335, m.outputLatency, 1e-12)
        feed.forget()
        k.forgetLatency()
        k.wrote(0, 16.0 - 0.0078125)
        feed.feed(k, 0.006, 1.0 / 60, 60.0, 16.0)
        assertEquals("…and a forgotten one is passed on whatever it is", 0.008, m.outputLatency, 1e-12)
    }

    /**
     * The playout, the timeline, the clock and the feed together on a simulated track: a 15 ms buffer the
     * writer fills a 5 ms chunk at a time as the device takes it, heard 10 ms after the device takes it,
     * stamped every 100 ms. Every sample of the Mac's sound must be heard at its stamp + the floor + the
     * delay the model says, nothing late, nothing moved from where the model put it.
     */
    @Test fun theChainPlaysEachSampleWhereItIsDue() {
        val rate = AudioPlayout.RATE
        val chunk = 240
        val buffer = 720
        val device = 0.010
        val t0 = 100.0
        val packetFrames = 480
        val priming = 240
        val wall = 1_790_000_000.0
        val model = AudioPlayout()
        val timeline = PlayerTimeline(2)
        val clock = OutputClock()
        val feed = ClockFeed(model)
        val io = chunk / rate + 0.002   // SoundEngine.ioBuffer
        model.format(1, packetFrames, priming, ByteArray(0))
        model.latency(0.0, io, 1.0 / 60, 60.0)
        // The device takes frame s at t0 + s ÷ rate, the writer `buffer` frames ahead of it; heard `device` later.
        fun hear(s: Long) = t0 + s / rate + device
        fun readAt(k: Long) = t0 + max(0.0, (k * chunk - buffer) / rate)
        val kept = HashMap<Int, FloatArray>()
        val heard = ArrayList<Pair<Long, Float>>()
        val decisions = ArrayList<Pair<Long, AudioPlayout.Decision>>()
        var moved = 0
        var nextChunk = 0L
        var lastStamp = -1.0
        val out = FloatArray(chunk * 2)

        fun schedule(s: AudioPlayout.Schedule) {
            val pcm = kept[s.id] ?: return
            val end = minOf(s.offset + s.count, pcm.size)
            var l = pcm.copyOfRange(s.offset, end)
            if (s.change != 0) l = AudioPlayout.change(l, l.copyOf(), s.change).first
            val inter = FloatArray(l.size * 2) { if (it % 2 == 0) l[it / 2] else 1f }
            val start = timeline.schedule(inter, s.at)
            if (abs(start - s.start) > 0.5) moved++
            if (s.at != null) decisions.add(start to s.decision)
            if (s.part == AudioPlayout.Schedule.Part.TAIL) kept.remove(s.id)
        }
        fun apply(o: AudioPlayout.Output) { o.discards.forEach { kept.remove(it) }; o.schedules.forEach { schedule(it) } }
        fun writerUntil(t: Double) {
            while (readAt(nextChunk) <= t) {
                val taken = readAt(nextChunk)
                val first = nextChunk * chunk
                if (taken - lastStamp >= 0.1) { clock.stamp(first, hear(first)); lastStamp = taken }
                val from = timeline.read(out, chunk)
                clock.wrote(from, taken)
                for (i in 0 until chunk) if (out[2 * i + 1] == 1f) heard.add(from + i to out[2 * i])
                nextChunk++
            }
        }

        val seconds = 20.0
        var n = 0
        while (true) {
            val produce = t0 + (n + 1) * packetFrames / rate - priming / rate + 0.010
            if (produce > t0 + seconds) break
            val stamp = wall + (n * packetFrames - priming) / rate
            val arrival = produce + 0.003
            while (true) {
                val d = model.deadline ?: break
                if (d >= arrival) break
                writerUntil(d)
                feed.feed(clock, io, 1.0 / 60, 60.0, d)?.let { apply(it) }
                apply(model.tick(max(d, model.deadline ?: d)))
            }
            writerUntil(arrival)
            feed.feed(clock, io, 1.0 / 60, 60.0, arrival)?.let { apply(it) }
            val plan = model.packet(1, n.toLong(), stamp, n == 0, now = arrival)
            // Each kept frame carries its place in the Mac's stream: frame g is the Mac's (g − P) ÷ rate.
            plan.id?.let { id -> kept[id] = FloatArray(packetFrames - plan.dropFront) { (n * packetFrames + plan.dropFront + it).toFloat() } }
            apply(plan.output)
            if (n % 100 == 99) model.takeSecond(arrival).also { if (arrival > t0 + 2.5) assertEquals("nothing late", 0, it.late) }
            n++
        }
        writerUntil(t0 + seconds + 1)

        assertEquals("every part played where the model put it", 0, moved)
        assertTrue("decisions were made", decisions.isNotEmpty())
        for ((start, d) in decisions) assertEquals("heard where the model says", d.ear, hear(start), 0.0002)
        // The floor is (arrival − stamp), the delay the need: cover 20 ms + a packet + IO + latency + 2 ms.
        val floor = t0 + packetFrames / rate + 0.013 - wall
        assertEquals("the output latency measured: the buffer and the device", buffer / rate + device, model.outputLatency, 0.0006)
        val need = 0.020 + packetFrames / rate + io + model.outputLatency + 0.002
        val late = heard.filter { hear(it.first) > t0 + 16 && it.second == Math.floor(it.second.toDouble()).toFloat() }
        assertTrue(late.size > 48_000)
        for ((s, g) in late) {
            val heardAt = hear(s)
            val stampOf = wall + (g - priming) / rate
            assertEquals("sample $s heard at its stamp + the floor + the need", floor + need, heardAt - stampOf, 0.001)
        }
        val order = heard.filter { it.second == Math.floor(it.second.toDouble()).toFloat() }.map { it.second }
        assertTrue("the Mac's frames in order", order.zipWithNext().all { (a, b) -> b > a })
    }
}
