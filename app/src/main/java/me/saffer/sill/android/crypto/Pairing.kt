package me.saffer.sill.android.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Pairing and identity math from Sources/StreamProtocol (Pairing.swift, RemoteIdentity.swift),
// byte for byte: the Mac checks every proof this side makes, and this side checks the Mac's.

/** base64url without padding (RFC 4648 §5). */
object Base64Url {
    fun encode(d: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(d)

    /** Nil for anything that is not canonical unpadded base64url. */
    fun decode(text: String): ByteArray? {
        if (!text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' } || text.length % 4 == 1) return null
        val d = runCatching { java.util.Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
        return if (encode(d) == text) d else null
    }
}

object Spki {
    /** SEQUENCE { SEQUENCE { ecPublicKey, prime256v1 }, BIT STRING (0x00 ‖ point) }: 26 bytes, then the point. */
    private val PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")

    fun der(point: ByteArray): ByteArray = PREFIX + point

    /** SHA-256 of the SPKI of a 65-byte uncompressed P-256 point: every pin compares this. */
    fun fingerprint(point: ByteArray): ByteArray = sha256(der(point))

    /** The uncompressed point (04 ‖ X ‖ Y) of a P-256 public key; nil for any other key. */
    fun point(key: java.security.PublicKey): ByteArray? {
        val ec = key as? ECPublicKey ?: return null
        if (ec.params.curve.field.fieldSize != 256) return null
        fun fixed(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
                b.size < 32 -> ByteArray(32 - b.size) + b
                else -> return ByteArray(0)
            }
        }
        val x = fixed(ec.w.affineX); val y = fixed(ec.w.affineY)
        if (x.size != 32 || y.size != 32) return null
        return byteArrayOf(4) + x + y
    }

    fun fingerprint(key: java.security.PublicKey): ByteArray? = point(key)?.let { fingerprint(it) }

    /** A P-256 public key from its uncompressed point, for checking the Mac's kind 18 signature. */
    fun publicKey(point: ByteArray): ECPublicKey? = runCatching {
        if (point.size != 65 || point[0] != 4.toByte()) return null
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val w = ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65)))
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(w, params)) as ECPublicKey
    }.getOrNull()
}

/** The Mac ID: Crockford base32 of the fingerprint's first 10 bytes, 16 characters. */
object MacId {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun make(fingerprint: ByteArray): String {
        if (fingerprint.size < 10) return ""
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (i in 0 until 10) {
            buffer = (buffer shl 8) or (fingerprint[i].toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(ALPHABET[(buffer shr bits) and 31]) }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return sb.toString()
    }

    fun isWellFormed(t: String) = t.length == 16 && t.all { it in ALPHABET }
}

/** The 12-digit typed code with its Damm check digit (PairingCode). */
object PairingCode {
    private val TABLE = arrayOf(
        intArrayOf(0, 3, 1, 7, 5, 9, 8, 6, 4, 2), intArrayOf(7, 0, 9, 2, 1, 5, 4, 8, 6, 3), intArrayOf(4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
        intArrayOf(1, 7, 5, 0, 9, 8, 3, 4, 2, 6), intArrayOf(6, 1, 2, 3, 0, 4, 5, 9, 7, 8), intArrayOf(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
        intArrayOf(5, 8, 6, 9, 7, 2, 0, 1, 3, 4), intArrayOf(8, 9, 4, 5, 3, 6, 2, 0, 1, 7), intArrayOf(9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
        intArrayOf(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
    )

    sealed class Check { data class Ok(val code: String) : Check(); data object Length : Check(); data object Typo : Check() }

    fun check(input: String): Check {
        val kept = input.filter { it != ' ' && it != '-' && it != ' ' }
        if (kept.length != 12 || !kept.all { it in '0'..'9' }) return Check.Length
        var interim = 0
        for (c in kept) interim = TABLE[interim][c - '0']
        return if (interim == 0) Check.Ok(kept) else Check.Typo
    }

    fun grouped(code: String) = code.chunked(4).joinToString(" ")
}

/**
 * The proofs of one pairing exchange (PairingProof). Each side takes the other's fingerprint from the
 * TLS session, never from a message.
 */
object PairingProof {
    private const val LABEL = "sill-pair-v1"
    private const val ROUNDS = 600_000

    /** The typed path's key: PBKDF2-HMAC-SHA256(code, "sill-pair-v1" ‖ fp_mac, 600,000, 32). */
    fun codeKey(code: String, macFingerprint: ByteArray): ByteArray {
        val salt = LABEL.toByteArray() + macFingerprint
        val spec = PBEKeySpec(code.toCharArray(), salt, ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun deviceProof(key: ByteArray, deviceFingerprint: ByteArray, macFingerprint: ByteArray): ByteArray =
        hmac(key, "$LABEL device".toByteArray() + byteArrayOf(0) + deviceFingerprint + macFingerprint)

    fun isValidMacProof(proof: ByteArray, key: ByteArray, macFingerprint: ByteArray, deviceFingerprint: ByteArray): Boolean =
        MessageDigest.isEqual(proof, hmac(key, "$LABEL mac".toByteArray() + byteArrayOf(0) + macFingerprint + deviceFingerprint))

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
}

/**
 * The Bonjour TXT record's `r` (RecognitionTag): base64url of p ‖ HMAC-SHA256(recognitionKey,
 * "sill-tag-v1" ‖ p)[0..<6], p 6 fresh bytes per registration. Recognises a saved Mac on a network
 * whatever its Bonjour name; replayable, so a hint, never authentication (a saved Mac is always
 * dialed pinned to its key).
 */
object RecognitionTag {
    fun matches(tag: String, recognitionKey: ByteArray): Boolean {
        val bytes = Base64Url.decode(tag) ?: return false
        if (bytes.size != 12 || recognitionKey.isEmpty()) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(recognitionKey, "HmacSHA256")) }
            .doFinal("sill-tag-v1".toByteArray() + bytes.copyOfRange(0, 6)).copyOfRange(0, 6)
        return MessageDigest.isEqual(mac, bytes.copyOfRange(6, 12))
    }
}

/** The QR code's content: sill://pair?v=1&m=…&k=…&s=…&n=…&p=…&a=…  (PairLink). */
data class PairLink(
    val macID: String, val fingerprint: ByteArray, val secret: ByteArray, val name: String, val port: Int, val addresses: List<String>,
) {
    companion object {
        sealed class Result { data class Ok(val link: PairLink) : Result(); data class Failed(val why: String) : Result() }

        fun parse(text: String): Result {
            val t = text.trim()
            val notALink = Result.Failed("That isn’t a Sill pairing link.")
            if (!t.lowercase().startsWith("sill://pair")) return notALink
            val rest = t.substring("sill://pair".length)
            if (!(rest.isEmpty() || rest.startsWith("?") || rest.startsWith("/?"))) return notALink
            val query = rest.substringAfter('?', "")
            // Values are percent-encoded down to RFC 3986's unreserved characters and ":" (PairLink.url),
            // so a "+" never stands for a space.
            val items: List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
                dec(k) to dec(v)
            }
            fun all(key: String) = items.filter { it.first == key }.map { it.second }
            fun single(key: String): String? = all(key).takeIf { it.size == 1 }?.first()?.takeIf { it.isNotEmpty() }
            if (single("v") != "1") return Result.Failed("This pairing link is from a newer Sill. Update Sill on this device.")
            val m = single("m")?.takeIf { MacId.isWellFormed(it) } ?: return Result.Failed("The pairing link is damaged.")
            val fp = single("k")?.let { Base64Url.decode(it) }?.takeIf { it.size == 32 } ?: return Result.Failed("The pairing link is damaged.")
            val secret = single("s")?.let { Base64Url.decode(it) }?.takeIf { it.size == 16 } ?: return Result.Failed("The pairing link is damaged.")
            val port = single("p")?.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return Result.Failed("The pairing link is damaged.")
            if (m != MacId.make(fp)) return Result.Failed("The pairing link is damaged.")
            val name = SafeText.label(all("n").firstOrNull() ?: "", 64).ifEmpty { "Mac" }
            return Result.Ok(PairLink(m, fp, secret, name, port, all("a").take(5)))
        }
    }
}

/** Kind 18: the MacInfo bytes and the fingerprint of the key that signed them, when the signature holds. */
object MacInfoCheck {
    fun verify(info: ByteArray, point: ByteArray, sig: ByteArray): ByteArray? {
        val key = Spki.publicKey(point) ?: return null
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(info); verify(sig) }
        }.getOrDefault(false)
        return if (ok) Spki.fingerprint(point) else null
    }
}

/**
 * SafeText.label (SafeText.swift), the same rules: whitespace of every kind becomes one space,
 * other control and format characters (bidirectional overrides, zero-width characters) are removed,
 * runs of spaces collapse, the ends are trimmed, at most `limit` characters are kept.
 */
object SafeText {
    fun label(s: String, limit: Int): String {
        val sb = StringBuilder()
        var pendingSpace = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()) {
                pendingSpace = sb.isNotEmpty()
                continue
            }
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() || type == Character.UNASSIGNED.toInt()) continue
            if (pendingSpace) { sb.append(' '); pendingSpace = false }
            sb.appendCodePoint(cp)
        }
        var out = sb.toString()
        if (out.codePointCount(0, out.length) > limit) out = out.substring(0, out.offsetByCodePoints(0, limit)).trimEnd(' ')
        return out
    }
}

fun sha256(d: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(d)

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) + Character.digit(s[2 * it + 1], 16)).toByte() }
