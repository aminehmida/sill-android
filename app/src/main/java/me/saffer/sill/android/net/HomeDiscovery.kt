package me.saffer.sill.android.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * A Mac's home door as Bonjour lists it on this network (`_sill._tcp`): its name, where it answers,
 * and its TXT record's `p` (how the door speaks, HomeDoorTXT) and `r` (the recognition tag that
 * names a saved Mac). `since`: when this listing of the name began (seconds, elapsedRealtime), the
 * same for as long as the name stays listed, a rebuilt browser included; a new value is the Mac
 * listed afresh, which the automatic reconnect dials at once (DiscoveryPolicy.redialRow).
 */
data class HomeMac(val serviceName: String, val host: String, val port: Int, val door: Door, val tag: String?, val since: Double = 0.0) {
    /** HomeDoorTXT: no `p` is a plain door (a Sill from before pairing at home), "0" open, anything else pairing required. */
    enum class Door { PLAIN, PAIRING_REQUIRED, OPEN }

    val endpoint get() = Endpoint(host, port)
}

/**
 * The network browser at home (StreamClient's Bonjour half, on Android's NSD): every Mac running
 * Sill on this Wi-Fi, resolved one at a time (NSD resolves one service at a time before Android 14),
 * re-resolved when it is listed again. Never peer-to-peer.
 *
 * It recovers (issue #69, DiscoveryPolicy): the browser is made again whenever the app comes back to
 * the screen, and after it fails or stops by itself (1, 2, 4, 8, 16, then 30 s), the results so far
 * shown beside the new browser's for `REBUILD_SETTLE` so a Mac that stays listed never leaves the
 * rows. A result resolved again without a TXT record keeps the record this browser last read for its
 * name (DiscoveryPolicy.rowRecords on iOS). Main thread.
 */
class HomeDiscovery(context: Context, private val onChange: (List<HomeMac>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** DEBUG only (MainActivity's extras): browse a test type, and reach what it lists at another address. */
    var serviceType = SERVICE_TYPE
    var endpointOverride: Endpoint? = null
    private val main = Handler(Looper.getMainLooper())

    /** This browser's own results, by service name. */
    private val fresh = LinkedHashMap<String, HomeMac>()
    /** The replaced browser's results, shown beside `fresh` until `settleEnds`. */
    private var kept: List<HomeMac> = emptyList()
    private var settleEnds: Double? = null
    /** The TXT record this browser last read for each name it lists (door, tag). */
    private val records = HashMap<String, Pair<HomeMac.Door, String?>>()
    /** When each name shown was first listed. */
    private val since = HashMap<String, Double>()

    private val queue = ArrayDeque<NsdServiceInfo>()
    private val retriedResolve = HashSet<String>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    /** Each browser made; the callbacks of an older one are ignored. */
    private var generation = 0

    /** Between start() and stop(): the app is on screen. */
    private var wanted = false
    private var startedAt = 0.0
    private var failures = 0
    private var failedAt: Double? = null
    private var rebuildTask: Runnable? = null
    private var settleTask: Runnable? = null

    /**
     * The app came to the screen: the browser is made again (one made before may have stopped
     * delivering while the app was away), what was listed kept beside it while it settles.
     */
    fun start() {
        if (wanted) return
        wanted = true
        rebuildIfDue(cameForward = true)
    }

    /**
     * The app left the screen: no browsing. What was listed stays for the next start(), whose browser
     * settles beside it, so the rows do not empty and fill again at every return.
     */
    fun stop() {
        wanted = false
        rebuildTask?.let(main::removeCallbacks); rebuildTask = null
        stopBrowser()
    }

    /** What the rows show now. */
    private fun shown(): List<HomeMac> =
        DiscoveryPolicy.browserResults(kept, fresh.values.toList(), { it.serviceName }, settleEnds, now())

    private fun publish() {
        val list = shown()
        since.keys.retainAll(list.map { it.serviceName }.toSet())
        onChange(list)
    }

    private fun rebuildIfDue(cameForward: Boolean) {
        rebuildTask?.let(main::removeCallbacks); rebuildTask = null
        val now = now()
        val due = DiscoveryPolicy.rebuildBrowser(failedAt, failures, cameForward, wanted, now)
        if (due.now) {
            rebuild(cameForward)
        } else if (due.recheckAt != null) {
            rebuildTask = Runnable { rebuildIfDue(false) }.also { main.postDelayed(it, ((due.recheckAt - now) * 1000).toLong().coerceAtLeast(10)) }
        }
    }

    /** The browser made again, the rows' results so far kept beside it until it settles. */
    private fun rebuild(cameForward: Boolean) {
        val why = if (cameForward) "back on the screen" else "after $failures failure${if (failures == 1) "" else "s"} in a row"
        kept = shown()
        fresh.clear()
        records.clear()
        queue.clear()
        retriedResolve.clear()
        failedAt = null
        if (cameForward) failures = 0
        settleEnds = now() + DiscoveryPolicy.REBUILD_SETTLE
        settleTask?.let(main::removeCallbacks)
        settleTask = Runnable { kept = emptyList(); settleEnds = null; publish() }
            .also { main.postDelayed(it, (DiscoveryPolicy.REBUILD_SETTLE * 1000).toLong()) }
        stopBrowser()
        makeBrowser()
        Log.i(TAG, "browser: made again ($why); ${kept.size} kept beside it for ${DiscoveryPolicy.REBUILD_SETTLE.toInt()} s")
        publish()
    }

    private fun makeBrowser() {
        val gen = ++generation
        startedAt = now()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                // Stopped without stopServiceDiscovery (the network went, the system's daemon restarted).
                main.post { if (gen == generation && listener === this) browserFailed("stopped by itself") }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (gen == generation && listener === this) browserFailed("failed to start: $errorCode") }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { if (gen == generation) enqueue(info) }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = main.post { if (gen == generation) lost(info.serviceName) }.let { }
        }
        listener = l
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { main.post { if (gen == generation && listener === l) browserFailed("discoverServices: ${it.message}") } }
    }

    private fun stopBrowser() {
        val l = listener ?: return
        listener = null
        generation++
        queue.clear()
        runCatching { nsd.stopServiceDiscovery(l) }
    }

    /**
     * The browser failed or stopped by itself: the rows stay as they are until the new one comes,
     * after DiscoveryPolicy's back-off, on the screen only.
     */
    private fun browserFailed(why: String) {
        if (failedAt != null) return
        val now = now()
        failures = DiscoveryPolicy.browserFailures(failures, startedAt, now)
        failedAt = now
        settleTask?.let(main::removeCallbacks); settleTask = null
        kept = shown()
        fresh.clear()
        settleEnds = Double.POSITIVE_INFINITY
        stopBrowser()
        Log.w(TAG, "browser: $why; made again in ${DiscoveryPolicy.browserRetryDelay(failures).toInt()} s" + if (wanted) "" else ", once back on the screen")
        rebuildIfDue(cameForward = false)
    }

    private fun lost(name: String) {
        queue.removeAll { it.serviceName == name }
        records.remove(name)
        val wasShown = fresh.remove(name) != null || kept.any { it.serviceName == name }
        kept = kept.filter { it.serviceName != name }
        if (wasShown) publish()
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (queue.none { it.serviceName == info.serviceName }) queue.add(info)
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = queue.poll() ?: return
        resolving = true
        val gen = generation
        // The slot is NSD's, not the browser's: a resolve still under way for a replaced browser frees
        // it when it ends, and its answer is dropped.
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        // Another resolve still held NSD's one slot: once more, a moment later.
                        if (gen == generation && errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retriedResolve.add(info.serviceName)) {
                            main.postDelayed({ if (gen == generation) enqueue(info) }, 250)
                        }
                        next()
                    }
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        if (gen == generation) resolved(s)
                        next()
                    }
                }
            })
        }.onFailure { resolving = false; main.post { next() } }
    }

    private fun resolved(s: NsdServiceInfo) {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            s.hostAddresses.firstOrNull { it is Inet4Address } ?: s.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION") s.host
        } ?: return
        val attrs = s.attributes
        // A result with no TXT entry at all reads with the record this browser last read for its name
        // (none: a plain door, as before); a record that comes replaces it, whatever it says.
        val record: Pair<HomeMac.Door, String?> = if (attrs.isEmpty()) {
            records[s.serviceName] ?: (HomeMac.Door.PLAIN to null)
        } else {
            val p = attrs["p"] ?: attrs["P"]
            val door = when {
                !attrs.containsKey("p") && !attrs.containsKey("P") -> HomeMac.Door.PLAIN
                p != null && String(p, Charsets.UTF_8) == "0" -> HomeMac.Door.OPEN
                else -> HomeMac.Door.PAIRING_REQUIRED
            }
            (door to attrs["r"]?.let { String(it, Charsets.UTF_8) }).also { records[s.serviceName] = it }
        }
        // A link-local IPv6 address keeps its scope ("%wlan0"): without it the address names nothing.
        val host = address.hostAddress ?: return
        val o = endpointOverride
        val first = since.getOrPut(s.serviceName) { now() }
        fresh[s.serviceName] = HomeMac(s.serviceName, o?.host ?: host, o?.port ?: s.port, record.first, record.second, first)
        publish()
    }

    private fun now() = SystemClock.elapsedRealtime() / 1000.0

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        private const val TAG = "SillHome"
    }
}
