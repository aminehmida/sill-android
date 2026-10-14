package me.saffer.sill.android.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.ArrayDeque

/**
 * A Mac's home door as Bonjour lists it on this network (`_sill._tcp`): its name, where it answers,
 * and its TXT record's `p` (how the door speaks, HomeDoorTXT) and `r` (the recognition tag that
 * names a saved Mac).
 */
data class HomeMac(val serviceName: String, val host: String, val port: Int, val door: Door, val tag: String?) {
    /** HomeDoorTXT: no `p` is a plain door (a Sill from before pairing at home), "0" open, anything else pairing required. */
    enum class Door { PLAIN, PAIRING_REQUIRED, OPEN }

    val endpoint get() = Endpoint(host, port)
}

/**
 * The network browser at home (StreamClient's Bonjour half, on Android's NSD): every Mac running
 * Sill on this Wi-Fi, resolved one at a time (NSD resolves one service at a time before Android 14),
 * re-resolved when it is listed again. Never peer-to-peer.
 */
class HomeDiscovery(context: Context, private val onChange: (List<HomeMac>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** DEBUG only (MainActivity's extras): browse a test type, and reach what it lists at another address. */
    var serviceType = SERVICE_TYPE
    var endpointOverride: Endpoint? = null
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, HomeMac>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed: $errorCode"); main.post { listener = null } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueue(info) }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                if (found.remove(info.serviceName) != null) onChange(found.values.toList())
            }.let { }
        }
        listener = l
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "discoverServices: ${it.message}"); listener = null }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(l) }
        queue.clear()
        found.clear()
        onChange(emptyList())
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
        val done = Runnable { resolving = false; next() }
        runCatching {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { main.post(done) }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    main.post {
                        resolved(s)
                        done.run()
                    }
                }
            })
        }.onFailure { main.post(done) }
    }

    private fun resolved(s: NsdServiceInfo) {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            s.hostAddresses.firstOrNull { it is Inet4Address } ?: s.hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION") s.host
        } ?: return
        val attrs = s.attributes
        val p = attrs["p"] ?: attrs["P"]
        val door = when {
            !attrs.containsKey("p") && !attrs.containsKey("P") -> HomeMac.Door.PLAIN
            p != null && String(p, Charsets.UTF_8) == "0" -> HomeMac.Door.OPEN
            else -> HomeMac.Door.PAIRING_REQUIRED
        }
        val tag = attrs["r"]?.let { String(it, Charsets.UTF_8) }
        // A link-local IPv6 address keeps its scope ("%wlan0"): without it the address names nothing.
        val host = address.hostAddress ?: return
        val o = endpointOverride
        found[s.serviceName] = HomeMac(s.serviceName, o?.host ?: host, o?.port ?: s.port, door, tag)
        onChange(found.values.toList())
    }

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        private const val TAG = "SillHome"
    }
}
