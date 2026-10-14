package me.saffer.sill.android.net

import me.saffer.sill.android.crypto.Base64Url
import me.saffer.sill.android.crypto.DeviceIdentity
import me.saffer.sill.android.crypto.MacId
import me.saffer.sill.android.crypto.PairLink
import me.saffer.sill.android.crypto.PairingProof
import me.saffer.sill.android.crypto.SafeText
import me.saffer.sill.android.crypto.Tls
import me.saffer.sill.android.crypto.DialException
import me.saffer.sill.android.protocol.Kind
import me.saffer.sill.android.protocol.MessageReader
import me.saffer.sill.android.protocol.PairResult
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.Wire
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class PairOutcome {
    data class Paired(val mac: SavedMac, val endpoint: Endpoint) : PairOutcome()
    data class Failed(val message: String) : PairOutcome()
}

/**
 * Pairing at the Mac's remote door (docs/remote-access-plan.md §3.5–3.6): one connection with ALPN
 * sill-pair/1, one kind 19 out, one kind 20 back, then the Mac closes it. By the QR link the Mac is
 * pinned to the link's key before a byte is sent; by the typed code nothing is pinned, and the
 * proofs bind both keys (a relay in the middle makes this device's proof name the relay's key).
 */
object Pairer {
    /**
     * By the typed code. `pin`: after an ask at a home door, the key that ask saw (the code the Mac
     * shows is for that connection's Mac); else none, and the proofs bind both keys.
     */
    fun byCode(endpoint: Endpoint, code: String, deviceName: String, model: String, store: SavedMacs, pin: ByteArray? = null): PairOutcome {
        val conn = try {
            Tls.dial(endpoint.host, endpoint.port, Tls.PAIRING_ALPN, pin = pin)
        } catch (e: DialException) {
            return PairOutcome.Failed(e.message ?: "Couldn’t reach ${endpoint.host}.")
        }
        val key = PairingProof.codeKey(code, conn.macFingerprint)
        // A home door's address changes with the network: only a remote one is worth typing again.
        val typed = if (pin == null) listOf(endpoint.toString()) else emptyList()
        return exchange(conn, key, "code", deviceName, model, endpoint, store, typed = typed)
    }

    sealed class AskOutcome {
        /** The Mac shows a code now: type it (pinned to `macFingerprint`). */
        data class Shown(val macFingerprint: ByteArray) : AskOutcome()
        data class Refused(val message: String) : AskOutcome()
    }

    /**
     * The home door's "pair me" (kind 19 "ask", docs/home-pairing-plan.md §3.1): the Mac opens its
     * pairing window by itself and answers "shown", or says why not. Taking any key; the code then
     * goes only to the key this ask saw.
     */
    fun ask(found: HomeMac, deviceName: String, model: String, cancel: Boolean = false, pin: ByteArray? = null): AskOutcome {
        val conn = try {
            Tls.dial(found.host, found.port, Tls.PAIRING_ALPN, pin = pin)
        } catch (e: DialException) {
            return AskOutcome.Refused(e.message ?: "Couldn’t reach ${found.serviceName}.")
        }
        val socket = conn.socket
        try {
            val req = Payloads.pairRequest(if (cancel) "cancel" else "ask", "", deviceName, model)
            socket.outputStream.apply { write(Wire.encodeJson(Kind.PAIR_REQUEST, req)); flush() }
            socket.soTimeout = 15_000
            val msg = runCatching { MessageReader(socket.inputStream) {}.next() }.getOrNull()
                ?: return AskOutcome.Refused("${found.serviceName} closed the connection without answering.")
            val r = PairResult.parse(msg.payload) ?: return AskOutcome.Refused("${found.serviceName}’s answer couldn’t be read.")
            return when (r.reason) {
                "shown" -> AskOutcome.Shown(conn.macFingerprint)
                "openOnMac" -> AskOutcome.Refused("In Sill on ${found.serviceName}, choose Pair iPhone or iPad…, then tap it again.")
                "locked" -> AskOutcome.Refused("Unlock ${found.serviceName}, then tap it again.")
                "busy" -> AskOutcome.Refused("${found.serviceName} is pairing another device. Try again in a minute.")
                "closed" -> AskOutcome.Refused(if (cancel) "Cancelled." else "${found.serviceName} isn’t taking pairing requests right now.")
                else -> AskOutcome.Refused(r.message?.let { SafeText.label(it, 300) } ?: "${found.serviceName} didn’t show a code (${r.reason}).")
            }
        } catch (e: Exception) {
            return AskOutcome.Refused("Pairing failed: ${e.message ?: e}")
        } finally {
            runCatching { socket.close() }
        }
    }

    fun byLink(link: PairLink, deviceName: String, model: String, store: SavedMacs, also: Endpoint? = null): PairOutcome {
        val endpoints = buildList {
            also?.let { add(it) }
            link.addresses.mapNotNull { Endpoint.parse(it, link.port) }.forEach { add(it) }
        }.distinctBy { it.key }
        if (endpoints.isEmpty()) return PairOutcome.Failed("The pairing link names no address. Type the Mac’s address instead.")
        val conn = try {
            Dialer.dial(endpoints, Tls.PAIRING_ALPN, link.fingerprint)
        } catch (e: DialException) {
            return PairOutcome.Failed(e.message ?: "Couldn’t reach ${link.name}.")
        }
        val endpoint = Endpoint(conn.host, conn.port)
        // The link's addresses are the Mac's own list: keep them to dial until its kind 18 says more.
        val typed = endpoints.map { it.toString() }
        return exchange(conn, link.secret, "qr", deviceName, model, endpoint, store, typed = typed)
    }

    private fun exchange(
        conn: me.saffer.sill.android.crypto.TlsConnection, key: ByteArray, method: String, deviceName: String, model: String,
        endpoint: Endpoint, store: SavedMacs, typed: List<String>,
    ): PairOutcome {
        val device = DeviceIdentity.get()
        val socket = conn.socket
        try {
            val proof = PairingProof.deviceProof(key, device.fingerprint, conn.macFingerprint)
            val req = Payloads.pairRequest(method, Base64Url.encode(proof), deviceName, model)
            socket.outputStream.apply { write(Wire.encodeJson(Kind.PAIR_REQUEST, req)); flush() }
            socket.soTimeout = 15_000
            val msg = runCatching { MessageReader(socket.inputStream) {}.next() }.getOrNull()
                ?: return PairOutcome.Failed("The Mac closed the connection without answering.")
            if (msg.kind != Kind.PAIR_RESULT || msg.payload.size > Wire.MAX_PAIRING_PAYLOAD) {
                return PairOutcome.Failed("The Mac answered something other than a pairing result.")
            }
            val r = PairResult.parse(msg.payload) ?: return PairOutcome.Failed("The Mac’s answer couldn’t be read.")
            if (!r.ok) return PairOutcome.Failed(refusal(r))
            val macProof = r.proof?.let { Base64Url.decode(it) }
            if (macProof == null || !PairingProof.isValidMacProof(macProof, key, conn.macFingerprint, device.fingerprint)) {
                return PairOutcome.Failed("The Mac’s answer didn’t prove it knows the code. Nothing was saved.")
            }
            val macID = MacId.make(conn.macFingerprint)
            if (r.macID != macID) return PairOutcome.Failed("The Mac’s answer named another key. Nothing was saved.")
            val now = Wire.now()
            val existing = store.byID(macID)
            val mac = SavedMac(
                macID = macID,
                fingerprint = conn.macFingerprint,
                name = SafeText.label(r.name ?: "", 64).ifEmpty { existing?.name ?: "Mac" },
                recognitionKey = r.recognitionKey,
                remotePort = existing?.remotePort ?: endpoint.port,
                addresses = existing?.addresses ?: emptyList(),
                typedAddresses = (listOf(endpoint.toString()) + typed + (existing?.typedAddresses ?: emptyList())).distinct().take(8),
                infoIssuedAt = existing?.infoIssuedAt ?: 0.0,
                lastWorked = endpoint.key,
                pairedAt = now,
                lastConnectedAt = existing?.lastConnectedAt,
            )
            store.upsert(mac)
            return PairOutcome.Paired(mac, endpoint)
        } catch (e: Exception) {
            return PairOutcome.Failed("Pairing failed: ${e.message ?: e}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Kind 20's refusals in this device's words (PairResult.knownReasons), else the Mac's own. */
    private fun refusal(r: PairResult): String = when (r.reason) {
        "code" -> "That code didn’t match." + (r.triesLeft?.let { " $it ${if (it == 1) "try" else "tries"} left." } ?: "")
        "closed" -> "The Mac isn’t showing a pairing code. In Sill on the Mac, choose Pair iPhone or iPad…"
        "expired" -> "That code expired. Choose New Code on the Mac."
        "stopped" -> "The Mac stopped pairing after too many wrong codes. Open its pairing window again."
        "busy" -> "The Mac is busy." + (r.retryAfter?.let { " Try again in ${it.toInt().coerceAtLeast(1)} s." } ?: " Try again shortly.")
        "locked" -> "Unlock the Mac, then try again."
        "shown", "openOnMac" -> "Choose Pair iPhone or iPad… in Sill on the Mac, then enter its code."
        else -> r.message?.let { SafeText.label(it, 300) }?.takeIf { it.isNotEmpty() } ?: "The Mac refused (${r.reason ?: "no reason"})."
    }
}

/**
 * Several addresses of one Mac at once (RemoteConnector): attempts start 1 s apart, or at once when
 * the one before fails; the first to finish its TLS handshake wins and the rest are closed. 20 s in
 * all.
 */
object Dialer {
    fun dial(endpoints: List<Endpoint>, alpn: String, pin: ByteArray?, overallMs: Long = 20_000): me.saffer.sill.android.crypto.TlsConnection {
        require(endpoints.isNotEmpty())
        val pool = Executors.newCachedThreadPool { r -> Thread(r, "sill-dial").apply { isDaemon = true } }
        val results = LinkedBlockingQueue<Result<me.saffer.sill.android.crypto.TlsConnection>>()
        val done = AtomicBoolean(false)
        var next = 0
        var pending = 0
        var best: DialException? = null
        val deadline = System.currentTimeMillis() + overallMs
        var nextStart = 0L
        fun start() {
            val e = endpoints[next++]
            pending++
            pool.execute { results.put(runCatching { Tls.dial(e.host, e.port, alpn, pin) }) }
            nextStart = System.currentTimeMillis() + 1_000
        }
        fun rank(r: DialException.Reason) = when (r) {
            DialException.Reason.WRONG_MAC -> 0
            DialException.Reason.REFUSED_KEY -> 1
            DialException.Reason.REFUSED -> 2
            DialException.Reason.NOT_SILL -> 3
            DialException.Reason.DNS -> 5
            DialException.Reason.NETWORK -> 4
            DialException.Reason.TIMEOUT -> 6
        }
        try {
            start()
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) throw best ?: DialException(DialException.Reason.TIMEOUT, "The Mac didn’t answer.")
                val wake = if (next < endpoints.size) minOf(nextStart, deadline) else deadline
                val r = results.poll(maxOf(1, wake - now), TimeUnit.MILLISECONDS)
                if (r == null) {
                    if (next < endpoints.size && System.currentTimeMillis() >= nextStart) start()
                    continue
                }
                pending--
                r.onSuccess { conn ->
                    done.set(true)
                    // Close whatever else finishes later.
                    val left = pending
                    if (left > 0) pool.execute { repeat(left) { results.poll(25, TimeUnit.SECONDS)?.getOrNull()?.socket?.close() } }
                    return conn
                }
                val e = r.exceptionOrNull()
                val de = e as? DialException ?: DialException(DialException.Reason.NETWORK, e?.message ?: "failed", e)
                if (best == null || rank(de.reason) < rank(best!!.reason)) best = de
                if (next < endpoints.size) start() else if (pending == 0) throw best!!
            }
        } finally {
            pool.shutdown()
        }
    }
}
