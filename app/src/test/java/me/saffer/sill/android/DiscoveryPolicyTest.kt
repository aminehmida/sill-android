package me.saffer.sill.android

import me.saffer.sill.android.net.DiscoveryPolicy
import me.saffer.sill.android.net.ReconnectClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/** iOSClient/DiscoveryPolicy.swift's issue #69 rules, against Tests/checks/policy/sleep.swift's cases. */
class DiscoveryPolicyTest {
    private val P = DiscoveryPolicy
    private val quiet = "Mac mini isn’t answering. It may be asleep. Sill keeps trying, or tap it to try now."
    private val stopped = "Stopped trying to reach Mac mini. Tap it to try again."
    private val reconnecting = "Reconnecting to Mac mini…"

    @Test fun browserRetryBacksOff() {
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0, 16.0, 30.0, 30.0), (1..7).map { P.browserRetryDelay(it) })
        assertEquals(1.0, P.browserRetryDelay(0), 0.0); assertEquals(1.0, P.browserRetryDelay(-3), 0.0)
        assertTrue((1..200).all { P.browserRetryDelay(it) <= 30 })
        assertEquals(1, P.browserFailures(0, 10.0, 10.5))
        assertEquals(2, P.browserFailures(1, 10.0, 10.5)); assertEquals(6, P.browserFailures(5, 10.0, 39.9))
        assertEquals(1, P.browserFailures(5, 10.0, 40.0))
        assertEquals(1, P.browserFailures(-2, 10.0, 11.0))
    }

    @Test fun rebuildBrowser() {
        fun r(failedAt: Double?, failures: Int, now: Double, fg: Boolean = true, back: Boolean = false) =
            P.rebuildBrowser(failedAt, failures, back, fg, now).let { it.now to it.recheckAt }
        assertEquals(true to null, r(null, 0, 5.0, back = true)); assertEquals(true to null, r(10.0, 3, 10.0, back = true))
        assertEquals(false to null, r(null, 0, 5.0)); assertEquals(false to null, r(null, 4, 500.0))
        assertEquals(false to 11.0, r(10.0, 1, 10.0)); assertEquals(false to 11.0, r(10.0, 1, 10.999)); assertEquals(true to null, r(10.0, 1, 11.0))
        assertEquals(false to 14.0, r(10.0, 3, 13.9)); assertEquals(false to 40.0, r(10.0, 6, 39.0)); assertEquals(false to 40.0, r(10.0, 9, 39.0))
        assertEquals(false to null, r(10.0, 1, 11.0, fg = false)); assertEquals(false to null, r(10.0, 1, 1e6, fg = false))
    }

    @Test fun browserResultsSettle() {
        val kept = listOf("A" to "old", "B" to "old")
        fun res(k: List<Pair<String, String>>, fresh: List<Pair<String, String>>, ends: Double?, now: Double) =
            P.browserResults(k, fresh, { it.first }, ends, now).map { "${it.first}@${it.second}" }
        assertEquals(listOf("A@new"), res(emptyList(), listOf("A" to "new"), null, 5.0))
        assertEquals(listOf("A@new", "B@old"), res(kept, listOf("A" to "new"), 103.0, 100.0))
        assertEquals(listOf("A@old", "B@old"), res(kept, emptyList(), 103.0, 100.0))
        assertEquals(listOf("B@new", "A@old"), res(kept, listOf("B" to "new"), 103.0, 102.9))
        assertEquals(listOf("A@new"), res(kept, listOf("A" to "new"), 103.0, 103.0))
        assertEquals(listOf("C@new", "A@old", "B@old"), res(kept, listOf("C" to "new"), Double.POSITIVE_INFINITY, 1e9))
        assertEquals(3.0, P.REBUILD_SETTLE, 0.0)
    }

    @Test fun redialRowAndItsLines() {
        fun d(failed: Int, last: Double?, now: Double, fg: Boolean = true) = P.redialRow(failed, last, fg, now).let { it.now to it.recheckAt }
        assertEquals(true to null, d(0, null, 0.0)); assertEquals(true to null, d(0, 50.0, 51.0))
        assertEquals(false to 15.0, d(1, 10.0, 12.0)); assertEquals(false to 15.0, d(1, 10.0, 14.999)); assertEquals(true to null, d(1, 10.0, 15.0))
        assertEquals(false to 25.0, d(2, 15.0, 24.0)); assertEquals(true to null, d(2, 15.0, 25.0))
        assertEquals(false to 45.0, d(3, 25.0, 44.0)); assertEquals(true to null, d(3, 25.0, 45.0))
        assertEquals(false to 75.0, d(4, 45.0, 74.0))
        assertEquals(false to 130.0, d(7, 100.0, 129.0)); assertEquals(true to null, d(11, 100.0, 130.0))
        assertEquals(false to null, d(12, 100.0, 1000.0)); assertEquals(false to null, d(30, 100.0, 1000.0))
        assertEquals(false to null, d(0, null, 0.0, fg = false)); assertEquals(false to null, d(5, 0.0, 1000.0, fg = false))
        assertEquals(listOf(reconnecting, reconnecting, reconnecting), (0..2).map { P.reconnectLine("Mac mini", it) })
        assertTrue((3..11).all { P.reconnectLine("Mac mini", it) == quiet })
        assertEquals(stopped, P.reconnectLine("Mac mini", 12))
        assertEquals(listOf(2.0, 4.0, 8.0, 10.0, 10.0), (1..5).map { P.remoteRetryDelay(it) })
    }

    // MARK: the reconnect clock, as SillClient runs it

    private class Run {
        val dials = mutableListOf<Double>()
        var connectedAt: Double? = null
        val lines = mutableListOf<Pair<Double, String>>()
    }

    /**
     * The glue as a model (sleep.swift's): a look every 50 ms (the rule is the same however often it is
     * asked), a dial the Mac answers connects at once, one it does not fails `dialFor` s later.
     * `listing(t)`: the row's listing (HomeMac.since), null while not listed.
     */
    private fun reconnect(
        until: Double, answersFrom: Double = Double.POSITIVE_INFINITY, listing: (Double) -> Double? = { 0.0 },
        background: ClosedFloatingPointRange<Double>? = null, dialFor: Double = 5.0, onStream: Boolean = true,
        quietAt: MutableList<Double>? = null,
    ): Run {
        val run = Run()
        val clock = ReconnectClock("M1", "Mac mini", 0.0, onStream)
        var inFlightUntil: Double? = null
        var wasBackground = false
        var t = 0.0
        while (t <= until && run.connectedAt == null) {
            val fg = background?.contains(t) != true
            if (!fg) wasBackground = true
            if (fg && wasBackground) { wasBackground = false; clock.cameForward(t) }
            inFlightUntil?.let { if (t >= it) { inFlightUntil = null; clock.dialFailed(t) } }
            if (inFlightUntil == null) {
                val line = when (val step = clock.next(listing(t), fg, t)) {
                    is ReconnectClock.Step.Dial -> {
                        run.dials += t
                        if (t >= answersFrom) run.connectedAt = t else inFlightUntil = t + dialFor
                        step.line
                    }
                    is ReconnectClock.Step.Wait -> step.line
                }
                if (run.lines.lastOrNull()?.second != line) run.lines += t to line
                if (clock.quiet && quietAt != null && quietAt.isEmpty()) quietAt += t
            }
            t = (t * 20 + 1).roundToLong() / 20.0
        }
        return run
    }

    @Test fun aHostStoppedForTwentySecondsIsReachedAtTheThirdDial() {
        val stop = reconnect(600.0, answersFrom = 14.0)
        assertEquals(listOf(0.0, 5.0, 15.0), stop.dials)
        assertEquals(15.0, stop.connectedAt!!, 0.0)
        assertEquals(listOf(reconnecting), stop.lines.map { it.second })
    }

    @Test fun aMacASleepProxyAnswersForIsDialedTwelveTimesThenLeft() {
        val quietAt = mutableListOf<Double>()
        val asleep = reconnect(3600.0, quietAt = quietAt)
        assertEquals(listOf(0.0, 5.0, 15.0, 35.0, 65.0, 95.0, 125.0, 155.0, 185.0, 215.0, 245.0, 275.0), asleep.dials)
        assertEquals(listOf(0.0, 20.0, 280.0), asleep.lines.map { it.first })
        assertEquals(listOf(reconnecting, quiet, stopped), asleep.lines.map { it.second })
        assertTrue(asleep.dials.zipWithNext().all { (a, b) -> b - a >= 5 })
        assertEquals("the connect screen from the quiet line on", 20.0, quietAt.single(), 0.0)
        assertNull(asleep.connectedAt)
    }

    @Test fun aMacThatWakesOrIsListedAfreshIsConnectedTo() {
        assertEquals(125.0, reconnect(600.0, answersFrom = 100.0).connectedAt!!, 0.0)
        val relisted = reconnect(600.0, answersFrom = 50.0, listing = { if (it < 49) 0.0 else if (it < 50) null else 50.0 })
        assertEquals(50.0, relisted.dials.last(), 0.0)
        assertEquals(50.0, relisted.connectedAt!!, 0.0)
        // Past its twelve dials, the Mac listed afresh (it woke and registered again) is dialed at once.
        val late = reconnect(3600.0, answersFrom = 1000.0, listing = { if (it < 1000) 0.0 else 1000.0 })
        assertEquals(1000.0, late.connectedAt!!, 0.0)
    }

    @Test fun noDialInTheBackgroundAndTheReturnStartsAgain() {
        val away = reconnect(3600.0, background = 40.0..300.0)
        assertFalse(away.dials.any { it in 40.0..300.0 })
        val back = away.dials.filter { it > 300 }
        assertEquals(12, back.size)
        assertEquals(4 + 12, away.dials.size)
        assertTrue(back.take(3).zip(listOf(300.05, 305.05, 315.05)).all { (a, b) -> abs(a - b) < 0.06 })
    }

    @Test fun notListedTheRemoteDialsBackOffForTwoMinutesThenWaitForTheRow() {
        val remote = reconnect(600.0, listing = { null }, dialFor = 1.0)
        assertEquals(listOf(0.0, 3.0, 8.0, 17.0, 28.0, 39.0, 50.0, 61.0, 72.0, 83.0, 94.0, 105.0, 116.0), remote.dials)
        assertEquals(listOf(0.0 to reconnecting, 120.0 to stopped), remote.lines)
        // The Mac listed at 300 s (it woke at home): dialed at once, and connected.
        val woke = reconnect(600.0, answersFrom = 300.0, listing = { if (it < 300) null else 300.0 }, dialFor = 1.0)
        assertEquals(300.0, woke.connectedAt!!, 0.0)
    }

    @Test fun theStreamStaysUpOnlyWhileTheReconnectIsABlip() {
        val clock = ReconnectClock("M1", "Mac mini", 0.0, onStream = true)
        assertTrue(clock.showsStream)
        repeat(3) { i ->
            assertTrue(clock.next(0.0, true, i * 100.0) is ReconnectClock.Step.Dial)
            assertTrue("the dial under way is not counted", clock.showsStream)
            clock.dialFailed(i * 100.0 + 5)
        }
        assertTrue(clock.quiet); assertFalse(clock.showsStream)
        assertFalse(ReconnectClock("M1", "Mac mini", 0.0, onStream = false).showsStream)
        val refused = ReconnectClock("M1", "Mac mini", 0.0, onStream = true).apply { answeredForGood() }
        assertTrue(refused.quiet)
        assertEquals(ReconnectClock.Step.Wait(null, stopped), refused.next(null, true, 1.0))
        refused.cameForward(500.0)
        assertEquals(ReconnectClock.Step.Dial(reconnecting), refused.next(null, true, 500.0))
    }
}
