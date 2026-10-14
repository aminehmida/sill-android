package me.saffer.sill.android.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.TimeZone
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/**
 * This device's identity at the Mac's doors (RemoteIdentity.swift): one P-256 key per install, in
 * the Android Keystore (never exportable), with the self-signed certificate the Keystore makes for
 * it. Trust is the key alone, pinned as the SHA-256 of its SubjectPublicKeyInfo; the certificate's
 * names and dates mean nothing to either side.
 */
class DeviceIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate, val fingerprint: ByteArray) {
    companion object {
        private const val ALIAS = "sill.device.identity"

        @Volatile private var cached: DeviceIdentity? = null

        @Synchronized
        fun get(): DeviceIdentity {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry) ?: run {
                create()
                ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            }
            val cert = entry.certificate as X509Certificate
            val fp = Spki.fingerprint(cert.publicKey) ?: error("the device key is not P-256")
            return DeviceIdentity(entry.privateKey, cert, fp).also { cached = it }
        }

        /** Whether a key exists already: saved Macs are cleared only when none does (a new install). */
        fun exists(): Boolean = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS)
        }.getOrDefault(false)

        private fun create() {
            val rnd = SecureRandom()
            val cn = ByteArray(8).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
            val serial = BigInteger(127, rnd).setBit(0)
            fun date(y: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, 0, 1) }.time
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                // TLS signs a digest it computed itself (NONE), kind 19 nothing: both are allowed.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=$cn"))
                .setCertificateSerialNumber(serial)
                .setCertificateNotBefore(date(2026))
                .setCertificateNotAfter(date(2100))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
    }
}

/** What a dial ends with: a TLS 1.3 socket with the negotiated protocol and the Mac's key. */
class TlsConnection(val socket: SSLSocket, val macFingerprint: ByteArray, val host: String, val port: Int)

/** Why a dial failed, in words the connect screen can show (RemoteConnector's failure copy). */
class DialException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { TIMEOUT, REFUSED, DNS, WRONG_MAC, REFUSED_KEY, NOT_SILL, NETWORK }
}

/**
 * The doors' TLS (RemoteTLS.swift): TLS 1.3 only, this device's certificate presented, the Mac's key
 * pinned inside the handshake (a mismatch ends it before this device's certificate is sent), one
 * ALPN, SNI "sill" so no MagicDNS name crosses the network in clear, and no resumption (a fresh
 * context per dial).
 */
object Tls {
    const val SESSION_ALPN = "sill/1"
    const val PAIRING_ALPN = "sill-pair/1"
    const val SERVER_NAME = "sill"
    const val DIAL_TIMEOUT_MS = 10_000

    fun dial(host: String, port: Int, alpn: String, pin: ByteArray?, timeoutMs: Int = DIAL_TIMEOUT_MS): TlsConnection {
        val identity = DeviceIdentity.get()
        val trust = PinTrust(pin)
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.keepAlive = true
            runCatching { raw.trafficClass = 0x88 }      // DSCP AF41, interactive video
            val address = try {
                InetSocketAddress(host, port).also { if (it.isUnresolved) throw java.net.UnknownHostException(host) }
            } catch (e: java.net.UnknownHostException) {
                throw DialException(DialException.Reason.DNS, "Couldn’t find $host. Is Tailscale on?", e)
            }
            try {
                raw.connect(address, timeoutMs)
            } catch (e: java.net.SocketTimeoutException) {
                throw DialException(DialException.Reason.TIMEOUT, "$host didn’t answer.", e)
            } catch (e: java.net.ConnectException) {
                throw DialException(DialException.Reason.REFUSED, "$host refused the connection. Is Remote Access on in Sill on the Mac?", e)
            } catch (e: java.io.IOException) {
                throw DialException(DialException.Reason.NETWORK, "Couldn’t reach $host: ${e.message}", e)
            }
            val ctx = SSLContext.getInstance("TLSv1.3")
            ctx.init(arrayOf(IdentityKeys(identity)), arrayOf(trust), SecureRandom())
            val s = ctx.socketFactory.createSocket(raw, SERVER_NAME, port, true) as SSLSocket
            val p = s.sslParameters
            p.protocols = arrayOf("TLSv1.3")
            p.applicationProtocols = arrayOf(alpn)
            p.serverNames = listOf(SNIHostName(SERVER_NAME))
            s.sslParameters = p
            s.soTimeout = timeoutMs
            try {
                s.startHandshake()
            } catch (e: Exception) {
                if (trust.mismatch) throw DialException(DialException.Reason.WRONG_MAC, "The Mac at $host isn’t the one this device paired with.", e)
                throw DialException(DialException.Reason.NOT_SILL, "$host didn’t answer as Sill: ${e.message}", e)
            }
            if (s.applicationProtocol != alpn) {
                s.close()
                throw DialException(DialException.Reason.NOT_SILL, "$host didn’t answer as Sill (ALPN ${s.applicationProtocol}).")
            }
            s.soTimeout = 0
            val fp = trust.peer ?: throw DialException(DialException.Reason.NOT_SILL, "The Mac sent no P-256 key.")
            return TlsConnection(s, fp, host, port)
        } catch (e: DialException) {
            runCatching { raw.close() }
            throw e
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw DialException(DialException.Reason.NETWORK, e.message ?: e.toString(), e)
        }
    }

    /** Pins the Mac's key: the whole of trust. Nothing else about its certificate is looked at. */
    private class PinTrust(val pin: ByteArray?) : X509ExtendedTrustManager() {
        @Volatile var peer: ByteArray? = null
        @Volatile var mismatch = false

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = Spki.fingerprint(leaf.publicKey) ?: throw CertificateException("not a P-256 key")
            peer = fp
            if (pin != null && !java.security.MessageDigest.isEqual(pin, fp)) {
                mismatch = true
                throw CertificateException("pin mismatch")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw CertificateException()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw CertificateException()
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Presents this device's certificate, whatever the Mac's CertificateRequest names. */
    private class IdentityKeys(val identity: DeviceIdentity) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        companion object { const val ALIAS = "device" }
    }
}
