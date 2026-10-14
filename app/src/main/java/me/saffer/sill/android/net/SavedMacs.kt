package me.saffer.sill.android.net

import android.content.Context
import me.saffer.sill.android.crypto.Base64Url
import me.saffer.sill.android.crypto.MacId
import me.saffer.sill.android.protocol.MacAddress
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Mac this device paired with (SavedMacs.swift): its pin, its name, how to reach it. The record
 * is refused unless its Mac ID is its fingerprint's, so a damaged store can never pin a stranger.
 */
data class SavedMac(
    val macID: String,
    val fingerprint: ByteArray,
    val name: String,
    val recognitionKey: String?,
    val remotePort: Int,
    /** From the Mac's signed kind 18, in its dial order. */
    val addresses: List<MacAddress>,
    /** What the person typed when pairing ("mac.tail1234.ts.net", "100.101.102.103:7455"). */
    val typedAddresses: List<String>,
    val infoIssuedAt: Double,
    /** "host|port" of the last address a session reached. */
    val lastWorked: String?,
    val pairedAt: Double,
    val lastConnectedAt: Double?,
    val revoked: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("macID", macID)
        .put("fingerprint", Base64Url.encode(fingerprint))
        .put("name", name)
        .put("recognitionKey", recognitionKey)
        .put("remotePort", remotePort)
        .put("addresses", JSONArray(addresses.map { a ->
            JSONObject().put("host", a.host).put("kind", a.kind).put("via", a.via).also { o -> a.port?.let { o.put("port", it) } }
        }))
        .put("typedAddresses", JSONArray(typedAddresses))
        .put("infoIssuedAt", infoIssuedAt)
        .put("lastWorked", lastWorked)
        .put("pairedAt", pairedAt)
        .put("lastConnectedAt", lastConnectedAt)
        .put("revoked", revoked)

    override fun equals(other: Any?) = other is SavedMac && other.toJson().toString() == toJson().toString()
    override fun hashCode() = macID.hashCode()

    companion object {
        fun fromJson(o: JSONObject): SavedMac? = runCatching {
            val fp = Base64Url.decode(o.getString("fingerprint")) ?: return null
            val macID = o.getString("macID")
            if (fp.size != 32 || MacId.make(fp) != macID) return null
            val addrs = o.optJSONArray("addresses") ?: JSONArray()
            val typed = o.optJSONArray("typedAddresses") ?: JSONArray()
            SavedMac(
                macID = macID,
                fingerprint = fp,
                name = o.optString("name", "Mac"),
                recognitionKey = o.optString("recognitionKey").takeIf { it.isNotEmpty() && it != "null" },
                remotePort = o.optInt("remotePort", 7455),
                addresses = (0 until addrs.length()).mapNotNull { i ->
                    val a = addrs.optJSONObject(i) ?: return@mapNotNull null
                    MacAddress(a.getString("host"), if (a.has("port")) a.optInt("port") else null, a.optString("kind"), a.optString("via"))
                },
                typedAddresses = (0 until typed.length()).map { typed.getString(it) },
                infoIssuedAt = o.optDouble("infoIssuedAt", 0.0),
                lastWorked = o.optString("lastWorked").takeIf { it.isNotEmpty() && it != "null" },
                pairedAt = o.optDouble("pairedAt", 0.0),
                lastConnectedAt = o.optDouble("lastConnectedAt").takeIf { it.isFinite() && it > 0 },
                revoked = o.optBoolean("revoked"),
            )
        }.getOrNull()
    }
}

/** The saved Macs, newest pairing first, at most 16. */
class SavedMacs(context: Context) {
    private val prefs = context.getSharedPreferences("sill", Context.MODE_PRIVATE)

    fun all(): List<SavedMac> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { SavedMac.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun byID(macID: String) = all().firstOrNull { it.macID == macID }

    @Synchronized
    fun upsert(mac: SavedMac) {
        val list = all().filter { it.macID != mac.macID }.toMutableList()
        list.add(0, mac)
        save(list.take(16))
    }

    @Synchronized
    fun update(macID: String, change: (SavedMac) -> SavedMac) {
        val list = all().map { if (it.macID == macID) change(it) else it }
        save(list)
    }

    @Synchronized
    fun remove(macID: String) = save(all().filter { it.macID != macID })

    fun clear() = save(emptyList())

    private fun save(list: List<SavedMac>) {
        prefs.edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    companion object { private const val KEY = "savedMacs" }
}

/** An address as typed or as the Mac lists it: host and port, IPv6 without brackets. */
data class Endpoint(val host: String, val port: Int) {
    val key get() = "$host|$port"
    override fun toString() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        /** "host", "host:port", "[v6]:port" or a bare IPv6 address; nil for anything else. */
        fun parse(raw: String, defaultPort: Int): Endpoint? {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("sill://")) return null
            if (t.startsWith("[")) {
                val close = t.indexOf(']')
                if (close < 0) return null
                val host = t.substring(1, close)
                val rest = t.substring(close + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: return null else defaultPort
                return if (port in 1..65535 && host.isNotEmpty()) Endpoint(host, port) else null
            }
            val colons = t.count { it == ':' }
            if (colons > 1) return Endpoint(t, defaultPort)            // a bare IPv6 address
            if (colons == 1) {
                val host = t.substringBefore(':')
                val port = t.substringAfter(':').toIntOrNull() ?: return null
                return if (port in 1..65535 && host.isNotEmpty() && host.none { it.isWhitespace() }) Endpoint(host, port) else null
            }
            return if (t.none { it.isWhitespace() || it == '/' }) Endpoint(t, defaultPort) else null
        }

        fun isTailscale(host: String): Boolean {
            if (host.endsWith(".ts.net", ignoreCase = true)) return true
            if (host.lowercase().startsWith("fd7a:115c:a1e0:")) return true
            val parts = host.split('.')
            if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
                val a = parts[0].toInt(); val b = parts[1].toInt()
                return a == 100 && b in 64..127
            }
            return false
        }

        fun isLoopback(host: String) = host == "127.0.0.1" || host == "::1" || host.equals("localhost", true)
    }
}

/**
 * The order a saved Mac is dialed in (RemoteDialPolicy): loopback, then VPN names and addresses
 * (Tailscale's), then the rest as the Mac listed them, then what was typed; the address that last
 * worked goes first; duplicates once.
 */
object DialOrder {
    fun endpoints(mac: SavedMac): List<Endpoint> {
        val listed = mac.addresses.map { Endpoint(it.host, it.port ?: mac.remotePort) to it.kind }
        val typed = mac.typedAddresses.mapNotNull { Endpoint.parse(it, mac.remotePort) }
        fun rank(e: Endpoint, kind: String?): Int = when {
            Endpoint.isLoopback(e.host) -> 0
            kind == "vpn" || Endpoint.isTailscale(e.host) -> if (e.host.any(Char::isLetter) && !e.host.contains(':')) 1 else 2
            kind == "lan" -> 3
            kind == "internet" -> 4
            else -> 5
        }
        val all = (listed + typed.map { it to null }).sortedBy { (e, k) -> rank(e, k) }.map { it.first }
        val ordered = LinkedHashMap<String, Endpoint>()
        mac.lastWorked?.let { lw ->
            val host = lw.substringBeforeLast('|'); val port = lw.substringAfterLast('|').toIntOrNull()
            if (host.isNotEmpty() && port != null && port in 1..65535) Endpoint(host, port).let { ordered[it.key] = it }
        }
        for (e in all) ordered.putIfAbsent(e.key, e)
        return ordered.values.toList()
    }
}
