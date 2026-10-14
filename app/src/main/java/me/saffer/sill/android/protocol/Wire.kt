package me.saffer.sill.android.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * The wire format of Sources/StreamProtocol/StreamMessage.swift, which the Mac and every device
 * share: a 14-byte big-endian header, kind(1) timestamp(8, Double) isKeyframe(1) payloadLength(4),
 * then the payload. Change nothing here without the Swift side: the Mac is the other half.
 */
object Kind {
    // Host → device: video
    const val PARAMETER_SETS = 0
    const val FRAME = 1
    // Host → device: the window switcher's catalog (Switcher.swift)
    const val WINDOW_LIST = 2
    const val THUMBNAIL = 3
    const val APP_ICON = 4
    const val APP_LIST = 5
    // Device → host
    const val SELECT_SOURCE = 6
    const val LAUNCH_APP = 7
    const val INPUT = 8
    const val VIEWPORT = 9
    const val PING = 10
    const val PONG = 11
    const val CLIENT_STATS = 12
    const val TICK = 13
    const val CURSOR_SHAPE = 14
    const val WINDOW_COMMAND = 15
    const val HOST_SETTINGS = 16
    const val CHANGE_SETTINGS = 17
    // Remote access and pairing (Remote.swift, Pairing.swift)
    const val MAC_INFO = 18
    const val PAIR_REQUEST = 19
    const val PAIR_RESULT = 20
    const val PAIRING_WANTED = 21
    const val GOODBYE = 22
    // Compatibility (Compatibility.swift)
    const val HELLO = 23
    // The Mac's menus (MacMenu.swift), around the pointer's 26
    const val MAC_MENU = 24
    const val PRESS_MENU_ITEM = 25
    const val MAC_POINTER = 26
    const val FETCH_MENU = 27
    // Trackpad gestures (Gesture.swift)
    const val GESTURE = 28
}

class Message(val kind: Int, val timestamp: Double, val isKeyframe: Boolean, val payload: ByteArray)

object Wire {
    const val HEADER_LENGTH = 14

    /** Any device → host message (StreamMessage.maxClientPayload). */
    const val MAX_CLIENT_PAYLOAD = 1 shl 20
    /** A video frame, as a device accepts it (a Retina keyframe is 1–2 MB). */
    const val MAX_FRAME_PAYLOAD = 32 shl 20
    /** Any other host → device message. */
    const val MAX_OTHER_HOST_PAYLOAD = 4 shl 20
    /** Kind 20, the one message of a pairing connection's answer. */
    const val MAX_PAIRING_PAYLOAD = 4096

    /** Seconds since 1970, as the Mac's timestamps are. */
    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun encode(kind: Int, payload: ByteArray = EMPTY, keyframe: Boolean = false, timestamp: Double = now()): ByteArray {
        val b = ByteBuffer.allocate(HEADER_LENGTH + payload.size)
        b.put(kind.toByte())
        b.putLong(java.lang.Double.doubleToRawLongBits(timestamp))
        b.put(if (keyframe) 1 else 0)
        b.putInt(payload.size)
        b.put(payload)
        return b.array()
    }

    fun encodeJson(kind: Int, json: String): ByteArray = encode(kind, json.toByteArray(Charsets.UTF_8))

    val EMPTY = ByteArray(0)
}

/**
 * Reads one message at a time from a session's stream: the header, then the payload in pieces of
 * at most 256 KB, each piece a sign of life (iOSClient's MessageReader: a 1.6 MB keyframe on a
 * 2 Mbit/s link takes seconds, and liveness counts bytes, not whole messages). A header announcing
 * more than the caps allow ends the session: the Mac never sends that, so it is not the Mac.
 */
class MessageReader(private val input: InputStream, private val onBytes: () -> Unit) {
    private val header = ByteArray(Wire.HEADER_LENGTH)

    /** The next message; throws EOFException at a clean end and IOException otherwise. */
    fun next(): Message {
        readFully(header, 0, Wire.HEADER_LENGTH, atStart = true)
        val bb = ByteBuffer.wrap(header)
        val kind = bb.get().toInt() and 0xFF
        val timestamp = java.lang.Double.longBitsToDouble(bb.getLong())
        val keyframe = bb.get().toInt() == 1
        val length = bb.getInt().toLong() and 0xFFFFFFFFL
        val cap = if (kind == Kind.FRAME) Wire.MAX_FRAME_PAYLOAD else Wire.MAX_OTHER_HOST_PAYLOAD
        if (length > cap) throw IOException("closing: the host announced $length bytes for kind $kind")
        val payload = ByteArray(length.toInt())
        var off = 0
        while (off < payload.size) {
            val n = minOf(PIECE, payload.size - off)
            readFully(payload, off, n, atStart = false)
            off += n
        }
        return Message(kind, timestamp, keyframe, payload)
    }

    private fun readFully(buf: ByteArray, offset: Int, length: Int, atStart: Boolean) {
        var off = offset
        val end = offset + length
        while (off < end) {
            val n = input.read(buf, off, end - off)
            if (n < 0) {
                if (atStart && off == offset) throw EOFException("end of stream")
                throw IOException("the connection ended mid-message")
            }
            off += n
            onBytes()
        }
    }

    companion object {
        private const val PIECE = 256 * 1024
    }
}
