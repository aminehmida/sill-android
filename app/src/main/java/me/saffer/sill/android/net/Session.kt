package me.saffer.sill.android.net

import android.os.SystemClock
import android.util.Log
import me.saffer.sill.android.crypto.TlsConnection
import me.saffer.sill.android.protocol.Kind
import me.saffer.sill.android.protocol.Message
import me.saffer.sill.android.protocol.MessageReader
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.Wire
import java.io.BufferedOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One second of what this device saw, for the Mac's log (ClientStats) and the HUD. */
data class LinkStats(val fps: Int, val frameAgeMs: Int, val frameAgeMaxMs: Int, val rttMs: Int, val rttMaxMs: Int)

/**
 * One session connection to a Mac (StreamClient's connection half): the hello first, a writer
 * thread so nobody else waits on the socket, a reader thread, pings every 0.25 s on the monotonic
 * clock, a report every second, and liveness: no byte for max(6 s, 4 × the worst recent round trip)
 * ends it (a payload's every 256 KB counts, so a slow keyframe is not silence).
 */
class Session(val conn: TlsConnection, private val deviceLabel: String, private val hello: String, private val listener: Listener) {
    interface Listener {
        /** On the reader thread. Frames go straight to the decoder from here. */
        fun onMessage(session: Session, msg: Message)
        /** Once, on whichever thread noticed the end. */
        fun onEnded(session: Session, why: String?)
        fun onStats(session: Session, stats: LinkStats) {}
    }

    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val ended = AtomicBoolean(false)
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sill-timer").apply { isDaemon = true } }
    private val lock = Object()

    @Volatile var lastReceivedAt = SystemClock.elapsedRealtime()
        private set

    /** Kind 8 messages sent on this connection: a kind 26 whose `seen` is lower is stale. */
    @Volatile var inputsOnSession = 0
        private set

    // This second's samples.
    private var frames = 0
    private val ages = ArrayList<Int>()
    private val rtts = ArrayList<Int>()
    /** The worst round trip of the last second that had a pong; 0 until one does. */
    @Volatile var worstRecentRttMs = 0
        private set
    @Volatile var lastStats: LinkStats? = null
        private set

    private var pendingMove: String? = null
    private var moveFlush: ScheduledFuture<*>? = null
    private var lastMoveAt = 0L

    val host get() = conn.host

    fun start() {
        // The hello is the first message on the connection, before anything else (Compatibility.swift).
        outbox.put(Wire.encodeJson(Kind.HELLO, hello))
        // The timers first: a reader that meets an end at once shuts the timer down after them.
        timer.scheduleWithFixedDelay({ tick() }, 0, 250, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({ closeSecond() }, 1, 1, TimeUnit.SECONDS)
        Thread({ writeLoop() }, "sill-writer").apply { isDaemon = true }.start()
        Thread({ readLoop() }, "sill-reader").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    fun send(kind: Int, payload: ByteArray = Wire.EMPTY) {
        synchronized(lock) {
            if (ended.get()) return
            if (kind == Kind.INPUT) flushMoveLocked()
            outbox.put(Wire.encode(kind, payload))
            if (kind == Kind.INPUT) inputsOnSession++
        }
    }

    fun sendJson(kind: Int, json: String) = send(kind, json.toByteArray(Charsets.UTF_8))

    /** Input (kind 8). Pointer moves go out at most every 8 ms, the newest one; any other input first flushes a waiting move. */
    fun sendInput(json: String, isMove: Boolean = false) {
        synchronized(lock) {
            if (ended.get()) return
            if (!isMove) {
                flushMoveLocked()
                outbox.put(Wire.encodeJson(Kind.INPUT, json)); inputsOnSession++
                return
            }
            val now = SystemClock.uptimeMillis()
            if (pendingMove == null && now - lastMoveAt >= 8) {
                outbox.put(Wire.encodeJson(Kind.INPUT, json)); inputsOnSession++
                lastMoveAt = now
                return
            }
            pendingMove = json
            if (moveFlush == null) {
                val delay = maxOf(0L, 8 - (now - lastMoveAt))
                moveFlush = try {
                    timer.schedule({ synchronized(lock) { flushMoveLocked() } }, delay, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    null   // the session is ending
                }
            }
        }
    }

    /** Whether a coalesced move still waits (a kind 26 is stale then). */
    val moveWaiting: Boolean get() = synchronized(lock) { pendingMove != null }

    private fun flushMoveLocked() {
        moveFlush?.cancel(false); moveFlush = null
        val m = pendingMove ?: return
        pendingMove = null
        outbox.put(Wire.encodeJson(Kind.INPUT, m)); inputsOnSession++
        lastMoveAt = SystemClock.uptimeMillis()
    }

    /** A frame arrived: its age (host clock to this one, approximate across devices). */
    fun noteFrame(hostTimestamp: Double) {
        val age = ((Wire.now() - hostTimestamp) * 1000).toInt().coerceAtLeast(0)
        synchronized(lock) { frames++; ages.add(age) }
    }

    fun close(why: String? = null) = end(why, closeSocket = true)

    private fun end(why: String?, closeSocket: Boolean) {
        synchronized(lock) {
            if (!ended.compareAndSet(false, true)) return
            timer.shutdownNow()
        }
        outbox.put(POISON)
        if (closeSocket) runCatching { conn.socket.close() }
        Log.i(TAG, "session to ${conn.host} ended: ${why ?: "closed"}")
        listener.onEnded(this, why)
    }

    val isEnded get() = ended.get()

    private fun writeLoop() {
        val out = BufferedOutputStream(conn.socket.outputStream, 64 * 1024)
        try {
            while (true) {
                var m = outbox.take()
                while (true) {
                    if (m === POISON) { out.flush(); return }
                    out.write(m)
                    m = outbox.poll() ?: break
                }
                out.flush()
            }
        } catch (e: Exception) {
            end("write failed: ${e.message}", closeSocket = true)
        }
    }

    private fun readLoop() {
        val reader = MessageReader(conn.socket.inputStream) { lastReceivedAt = SystemClock.elapsedRealtime() }
        try {
            while (!ended.get()) {
                val m = reader.next()
                when (m.kind) {
                    Kind.PONG -> if (m.payload.size == 8) {
                        val sent = java.lang.Double.longBitsToDouble(ByteBuffer.wrap(m.payload).long)
                        val rtt = ((monotonic() - sent) * 1000).toInt().coerceAtLeast(0)
                        synchronized(lock) { rtts.add(rtt) }
                    }
                    Kind.TICK -> Unit
                    else -> listener.onMessage(this, m)
                }
            }
        } catch (e: EOFException) {
            end(null, closeSocket = true)
        } catch (e: Exception) {
            end(if (ended.get()) null else "read failed: ${e.message}", closeSocket = true)
        }
    }

    private fun tick() {
        if (ended.get()) return
        send(Kind.PING, Payloads.ping(monotonic()))
        val limit = maxOf(6_000L, 4L * worstRecentRttMs)
        val silent = SystemClock.elapsedRealtime() - lastReceivedAt
        if (silent > limit) end("connection silent for ${silent / 1000} s", closeSocket = true)
    }

    /** Coming back to the foreground: the silence while away (no reads) is not the Mac's. */
    fun resetLiveness() { lastReceivedAt = SystemClock.elapsedRealtime() }

    private fun closeSecond() {
        if (ended.get()) return
        val stats: LinkStats
        synchronized(lock) {
            val a = ages.sorted(); val r = rtts.sorted()
            stats = LinkStats(
                fps = frames,
                frameAgeMs = if (a.isEmpty()) -1 else a[a.size / 2],
                frameAgeMaxMs = a.lastOrNull() ?: -1,
                rttMs = if (r.isEmpty()) -1 else r[r.size / 2],
                rttMaxMs = r.lastOrNull() ?: -1,
            )
            if (r.isNotEmpty()) worstRecentRttMs = r.last()
            frames = 0; ages.clear(); rtts.clear()
        }
        lastStats = stats
        sendJson(Kind.CLIENT_STATS, Payloads.clientStats(stats.fps, stats.frameAgeMs, stats.rttMs, deviceLabel, stats.frameAgeMaxMs, stats.rttMaxMs))
        listener.onStats(this, stats)
    }

    companion object {
        private const val TAG = "Sill"
        private val POISON = ByteArray(0)
        fun monotonic(): Double = SystemClock.elapsedRealtimeNanos() / 1e9
    }
}
