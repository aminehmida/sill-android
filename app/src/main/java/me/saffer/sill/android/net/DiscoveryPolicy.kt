package me.saffer.sill.android.net

/**
 * After the background, and a Mac whose row stays listed (iOSClient/DiscoveryPolicy.swift, issue #69).
 * Pure: no Android, checked by DiscoveryPolicyTest. Times are seconds on one monotonic clock.
 *
 * A browser can stop delivering (iOS makes a suspended app's mDNS connection defunct; Android's NSD
 * can fail to start, or stop on its own when the network changes), and nothing made it again but a
 * relaunch. A Bonjour Sleep Proxy (an Apple TV, a HomePod) answers for a sleeping Mac, so its row
 * stays listed while nothing answers a dial, and no browse event comes when the Mac wakes.
 */
object DiscoveryPolicy {
    /** A Mac on the LAN answers mDNS well within this. */
    const val NETWORK_FIRST = 3.0

    /** The waits before a browser that failed is made again, after 1, 2, 3… failures in a row. */
    val browserRetry = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 30.0)

    /** A browser that ran this long before it failed counts as the first failure in a row. */
    const val BROWSER_STABLE = 30.0

    /**
     * How long a browser made again shows the results of the browser it replaced beside its own: a
     * Mac that stays listed never leaves the rows (and keeps the time it was first listed); a Mac that
     * went leaves this long after the rebuild.
     */
    const val REBUILD_SETTLE = NETWORK_FIRST

    /** The wait before a browser is made again after `failures` failures in a row: 1, 2, 4, 8, 16, then 30 s. */
    fun browserRetryDelay(failures: Int): Double = browserRetry[failures.coerceIn(1, browserRetry.size) - 1]

    /** Failures in a row once a browser started at `startedAt` fails at `now`, `previous` before it. */
    fun browserFailures(previous: Int, startedAt: Double, now: Double): Int =
        if (now - startedAt >= BROWSER_STABLE) 1 else maxOf(previous, 0) + 1

    data class Due(val now: Boolean, val recheckAt: Double?)

    /**
     * Whether to make a browser again now, or when to look again. At once on the app's return to the
     * screen (`cameForward`), failed or not. One that failed (`failedAt`, its `failures` in a row) once
     * `browserRetryDelay` has passed since, and only in the foreground. One that runs is left alone.
     */
    fun rebuildBrowser(failedAt: Double?, failures: Int, cameForward: Boolean, foreground: Boolean, now: Double): Due {
        if (cameForward) return Due(true, null)
        if (!foreground || failedAt == null) return Due(false, null)
        val due = failedAt + browserRetryDelay(failures)
        return if (now >= due) Due(true, null) else Due(false, due)
    }

    /**
     * What the rows are read from: while a browser made again settles (before `settleEnds`), its own
     * results (`fresh`), then those of the browser it replaced (`kept`) whose names it does not list
     * yet; after that, its own alone. A name in both is the new browser's.
     */
    fun <R> browserResults(kept: List<R>, fresh: List<R>, name: (R) -> String, settleEnds: Double?, now: Double): List<R> {
        if (settleEnds == null || now >= settleEnds) return fresh
        val listed = fresh.map(name).toSet()
        return fresh + kept.filter { name(it) !in listed }
    }

    // MARK: The automatic reconnect

    /**
     * The waits between the automatic reconnect's dials of one listing of its Mac's row that do not
     * connect, counted from the last dial's start: 5, 10, 20, then 30 s.
     */
    val reconnectRetry = listOf(5.0, 10.0, 20.0, 30.0)

    /** After this many dials of a listing that did not connect, the status line says the Mac may be asleep. */
    const val RECONNECT_QUIET_AFTER = 3

    /**
     * After this many the dials of that listing stop (about 4.6 minutes). The row listed afresh, the
     * app's return to the screen, or a tap starts again.
     */
    const val RECONNECT_DIALS = 12

    /**
     * Whether the automatic reconnect dials its Mac's listed row now, or when to look again, after
     * `failed` dials of this listing that did not connect, the last of them started at `lastDialAt`:
     * the first at once; each next `reconnectRetry` after the last started; none after
     * `RECONNECT_DIALS`; none in the background, whose end looks again.
     */
    fun redialRow(failed: Int, lastDialAt: Double?, foreground: Boolean, now: Double): Due {
        if (!foreground || failed >= RECONNECT_DIALS) return Due(false, null)
        if (failed <= 0 || lastDialAt == null) return Due(true, null)
        val due = lastDialAt + reconnectRetry[minOf(failed, reconnectRetry.size) - 1]
        return if (now >= due) Due(true, null) else Due(false, due)
    }

    /**
     * The status line while the automatic reconnect dials its Mac's listed row (`failed`: the dials of
     * this listing that did not connect, a dial under way not counted).
     */
    fun reconnectLine(mac: String, failed: Int): String = when {
        failed >= RECONNECT_DIALS -> stoppedLine(mac)
        failed >= RECONNECT_QUIET_AFTER -> "$mac isn’t answering. It may be asleep. Sill keeps trying, or tap it to try now."
        else -> "Reconnecting to $mac…"
    }

    fun stoppedLine(mac: String) = "Stopped trying to reach $mac. Tap it to try again."

    /** After a session ends by itself, automatic remote dials stop this long after the loss. */
    const val REDIAL_WINDOW = 120.0

    /**
     * Nor within this long of the network last listing that Mac: a Mac the network listed moments ago
     * (its registration renewed, a lost announcement) is taken to be coming back there.
     */
    const val NETWORK_GRACE = 10.0

    /** A failed automatic remote dial is tried again after 2, 4, 8, then every 10 s. */
    val remoteRetry = listOf(2.0, 4.0, 8.0, 10.0)

    fun remoteRetryDelay(failures: Int): Double = remoteRetry[failures.coerceIn(1, remoteRetry.size) - 1]

    /** Another key answered the automatic reconnect of a Mac this device was connected to without pairing. */
    fun otherKeyLine(mac: String, device: String) = "$mac answered with another key, so this $device didn’t reconnect. Tap it to connect."
}

/**
 * The automatic reconnect of one Mac (StreamClient's `Reconnect` and `reconnectIfListed`, issue #69):
 * when its row is listed at home, its dials of that listing (`DiscoveryPolicy.redialRow`); when not,
 * its remote dials (2, 4, 8, then every 10 s, for `REDIAL_WINDOW` after the loss). It lives until
 * a session connects, a tap or Disconnect, or an answer that ends it; past its dials it waits for the
 * row to be listed afresh or the app's return, so a Mac that wakes later is still connected to. Pure:
 * the client asks it at every loss, failed dial, discovery change, return to the screen and the time
 * it said to look again, and does what it says. Not thread-safe: the main thread's.
 *
 * `onStream`: the stream screen stays up while the reconnect is still a blip (a session that was
 * streaming); once the Mac looks asleep (`quiet`), or for a session let go in the background, the
 * connect screen shows the line and the reconnect goes on under it.
 */
class ReconnectClock(val macID: String, val name: String, lostAt: Double, private val onStream: Boolean) {
    var lostAt = lostAt; private set
    /** When the row being dialed was first listed (HomeMac.since): a new value is a listing seen afresh. */
    var rowListing: Double? = null; private set
    var rowDials = 0; private set
    var lastRowDialAt: Double? = null; private set
    var remoteFailures = 0; private set
    var nextRemoteAt = lostAt; private set
    var remoteAllowed = true; private set
    /** When the Mac's row last left the network's list (no remote dial within NETWORK_GRACE of it). */
    var rowLeftAt: Double? = null; private set
    private var lastWasRow = false

    sealed class Step {
        /** Dial now, with this status line. */
        data class Dial(val line: String) : Step()
        /** Nothing now: show the line, and look again at `at` (null: at the next event only). */
        data class Wait(val at: Double?, val line: String) : Step()
    }

    /** A dial `next` asked for is under way (until `dialFailed`, or the session that connects). */
    private var dialing = false

    /**
     * The Mac looks asleep or out of reach (the dials of its row that did not connect, one under way
     * not counted, reached RECONNECT_QUIET_AFTER; or the remote dials stopped): the connect screen, with
     * the line.
     */
    val quiet: Boolean
        get() = rowDials - (if (dialing && lastWasRow) 1 else 0) >= DiscoveryPolicy.RECONNECT_QUIET_AFTER || !remoteAllowed

    /** Whether the stream screen stays up meanwhile (`Phase.Connecting(reconnecting = true)`). */
    val showsStream: Boolean get() = onStream && !quiet

    /** `listedSince`: when the Mac's home row now listed was first listed; null when no row lists it. */
    fun next(listedSince: Double?, foreground: Boolean, now: Double): Step {
        if (listedSince != null) {
            if (rowListing != listedSince) {
                rowListing = listedSince
                rowDials = 0
                lastRowDialAt = null
            }
            val line = DiscoveryPolicy.reconnectLine(name, rowDials)
            val due = DiscoveryPolicy.redialRow(rowDials, lastRowDialAt, foreground, now)
            if (!due.now) return Step.Wait(due.recheckAt, line)
            rowDials++
            lastRowDialAt = now
            lastWasRow = true
            dialing = true
            return Step.Dial(line)
        }
        if (rowListing != null) {
            // The row went: a listing that comes back is a new one, dialed at once.
            rowListing = null
            rowLeftAt = now
        }
        if (!remoteAllowed || now >= lostAt + DiscoveryPolicy.REDIAL_WINDOW) {
            remoteAllowed = false
            return Step.Wait(null, DiscoveryPolicy.stoppedLine(name))
        }
        val line = "Reconnecting to $name…"
        if (!foreground) return Step.Wait(null, line)
        val due = rowLeftAt?.let { maxOf(nextRemoteAt, it + DiscoveryPolicy.NETWORK_GRACE) } ?: nextRemoteAt
        if (now < due) return Step.Wait(minOf(due, lostAt + DiscoveryPolicy.REDIAL_WINDOW), line)
        lastWasRow = false
        dialing = true
        return Step.Dial(line)
    }

    /** The dial `next` asked for did not connect. A row's was counted when it started; a remote one waits its back-off. */
    fun dialFailed(now: Double) {
        dialing = false
        if (lastWasRow) return
        remoteFailures++
        nextRemoteAt = now + DiscoveryPolicy.remoteRetryDelay(remoteFailures)
    }

    /** The Mac answered for good (it refused this device's key): no more remote dials; a listed row still counts. */
    fun answeredForGood() { remoteAllowed = false }

    /**
     * Back on the screen: the person is back, so a listed row is dialed at once (iOS's `cameForward`),
     * and, on Android, the remote dials get their window again from now.
     */
    fun cameForward(now: Double) {
        rowDials = 0
        lastRowDialAt = null
        lostAt = now
        remoteFailures = 0
        nextRemoteAt = now
        remoteAllowed = true
        rowLeftAt = null
    }
}
