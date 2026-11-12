package me.saffer.sill.android.audio

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import me.saffer.sill.android.BuildConfig
import me.saffer.sill.android.client.SoundPlayer
import java.util.concurrent.Executors

/**
 * The Mac's sound on this device (iOSClient's AudioOutput, docs/audio-plan.md §7.3–7.5): kind 43's formats
 * and packets in, through the playout model (AudioPlayout, which decides) and the decoder (EldDecoder),
 * onto the timeline an AudioTrack plays (SoundEngine). Every line of Sill's runs on `sill.audio`; the
 * writer that feeds the track has a thread of its own.
 *
 * The engine runs while a format has come, the sound is not muted, the app is on screen, no call holds the
 * audio and a packet came within 10 s. It stops (after a 25 ms ramp) on mute, in the background, at stop()
 * and after 10 s without a packet, and the next packet starts it again. While the sound cannot be heard
 * nothing is decoded, and each packet still counts for the floor and the cover. No audio focus is asked
 * for (iOS: .playback mixing with others): another app's music keeps playing. Headphones or a Bluetooth
 * output going away while the sound plays mute it here and call `onHeadphonesOut` (main thread), as a
 * video app pauses. A call (the audio mode not normal) stops it until the call ends, as iOS's
 * interruption does.
 *
 * stop() forgets the stream's timing (the model's session reset), the decoder and the stats, and keeps the
 * format: the Mac sends no new format when a locked Mac unlocks or the app comes back within the session's
 * grace, so the next packet of that epoch starts the sound again (half a second after the stop at the
 * earliest, as a join: decoded, dropped whole, the next faded in). A new format replaces it.
 *
 * Threads: SillClient hands formats, packets and frames over from its reader thread (stamped there),
 * mute from the main thread; takeSecond comes on Session's timer thread and reads under `statsLock`.
 */
class MacSound(context: Context) : SoundPlayer {
    private val app = context.applicationContext

    /** Headphones or a Bluetooth output went away while the sound played: muted here; the client says so. Main thread. */
    @Volatile var onHeadphonesOut: (() -> Unit)? = null

    override val codecs: List<String> by lazy { if (decoderName != null) listOf(AudioCodec.AAC_ELD) else emptyList() }
    private val decoderName: String? by lazy { EldDecoder.findDecoder() }

    @Volatile private var mutedShown = false
    override var muted: Boolean
        get() = mutedShown
        set(value) { mutedShown = value; post { muteHere(value) } }

    private val thread = HandlerThread("sill.audio", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    /** Where decoders are made and let go, off the audio thread. */
    private val maker = Executors.newSingleThreadExecutor { r -> Thread(r, "sill.audio.codec").apply { isDaemon = true } }
    @Volatile private var released = false

    // On `sill.audio`.
    private val model = AudioPlayout()
    private var decoder: EldDecoder? = null
    /** The epoch playing: its packet size, and whether this device plays it. */
    private class Playing(val epoch: Int, val framesPerPacket: Int, val playable: Boolean)
    private var playing: Playing? = null
    /** The last format, kept across stop(): its epoch's next packet starts the sound again. */
    private var kept: AudioFormat? = null
    /** Each kept packet's frames (after its priming), by the model's id. */
    private val frames = HashMap<Int, Pair<FloatArray, FloatArray>>()
    private var engine: SoundEngine? = null
    /** Engines turned down, waiting to be let go. */
    private val fading = ArrayList<SoundEngine>()
    /** No new engine before this (one failed to start). */
    private var engineRetryAt = 0.0
    private var mutedHere = false
    private var foreground = true
    private var inCall = false
    /** Packets of the kept format start nothing before this (half a second after a stop()). */
    private var quietUntil = 0.0
    private var lastPacketAt: Double? = null
    /** When this thread last finished starting or stopping an engine or making a decoder (rule 6). */
    private var busyUntil = 0.0
    private var deadlineTask: Runnable? = null
    private var secondTask: Runnable? = null
    private var refresh = 1.0 / 60
    private var streamFPS = 60.0
    /** The output's clock to the model, and the route its latency was measured on. */
    private val feed = ClockFeed(model)
    private var routeId = -1

    // The reader thread's: until a format comes, frames are only kept here (the last 32).
    private val formatLock = Any()
    private var formatSeen = false
    private val framesBeforeFormat = ArrayDeque<DoubleArray>()
    private var keptEpoch: Int? = null

    // The last closed second, for the stats.
    private val statsLock = Any()
    private var lastSecond: AudioPlayout.Second? = null
    private var lateSinceTaken = 0
    private var anyPlayed = false

    private val audioManager = app.getSystemService(AudioManager::class.java)

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) post { headphonesOut() }
        }
    }

    private var started = 0
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) { started++; setForeground(true) }
        override fun onActivityStopped(activity: Activity) { started = maxOf(0, started - 1); if (started == 0) setForeground(false) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private val modeListener: Any? = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { post { checkCall() } }
    } else null

    init {
        ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        (app as? Application)?.registerActivityLifecycleCallbacks(lifecycle)
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        foreground = state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        if (Build.VERSION.SDK_INT >= 31) {
            audioManager?.addOnModeChangedListener({ it.run() }, modeListener as AudioManager.OnModeChangedListener)
        }
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val hz = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: display?.refreshRate ?: 60f
        if (hz > 0) refresh = 1.0 / hz
        post { model.latency(0.0, 0.005, refresh, streamFPS) }
    }

    // MARK: - From the client

    /** One kind 43 payload with its header's stamp (host wall clock, s). Reader thread; never blocks. */
    override fun onMessage(payload: ByteArray, hostTimestamp: Double) {
        if (released) return
        val came = now()
        when (val m = AudioMessage.parse(payload) ?: return) {
            is AudioMessage.Format -> {
                // The session's first format hands the frames kept before it to the model first.
                val before = synchronized(formatLock) {
                    val b = if (formatSeen) emptyList() else framesBeforeFormat.toList()
                    formatSeen = true
                    framesBeforeFormat.clear()
                    keptEpoch = m.format.epoch?.takeIf { it in 0..0xFFFF }?.toInt()
                    b
                }
                post {
                    for (f in before) model.frame(f[0], f[1])
                    receive(m.format)
                }
            }
            is AudioMessage.Packets -> {
                // After a stop(): packets of the kept format's epoch start it again, its frames first.
                val before = synchronized(formatLock) {
                    if (formatSeen || keptEpoch != m.packets.epoch) emptyList() else {
                        formatSeen = true
                        framesBeforeFormat.toList().also { framesBeforeFormat.clear() }
                    }
                }
                post {
                    for (f in before) model.frame(f[0], f[1])
                    receive(m.packets, hostTimestamp, came)
                }
            }
        }
    }

    /** A video frame's stamp, taken as it came (rule 3). Reader thread; never blocks. */
    override fun onVideoFrame(hostTimestamp: Double) {
        if (released) return
        val arrival = now()
        val seen = synchronized(formatLock) {
            if (!formatSeen) {
                if (framesBeforeFormat.size >= FRAMES_KEPT_BEFORE_FORMAT) framesBeforeFormat.removeFirst()
                framesBeforeFormat.addLast(doubleArrayOf(hostTimestamp, arrival))
            }
            formatSeen
        }
        if (seen) post { model.frame(hostTimestamp, arrival) }
    }

    /** Stop playing and forget the stream's timing; the format stays for its epoch's next packet. Any thread. */
    override fun stop() {
        synchronized(formatLock) { formatSeen = false; framesBeforeFormat.clear() }
        post {
            stopEngine("stopped", fade = true)
            model.reset(AudioPlayout.Reset.SESSION)
            feed.forget()
            frames.clear()
            decoder?.release()
            decoder = null
            playing = null
            lastPacketAt = null
            secondTask?.let(handler::removeCallbacks)
            secondTask = null
            quietUntil = now() + QUIET_AFTER_STOP
            synchronized(statsLock) { lastSecond = null; lateSinceTaken = 0; anyPlayed = false }
        }
    }

    /**
     * The last closed second's (median ms the sound trailed the picture, -1 for none played in it) and the
     * packets too late to play since the last call; nil before anything played. Timer thread.
     */
    override fun takeSecond(): Pair<Int, Int>? = synchronized(statsLock) {
        if (!anyPlayed) return null
        val r = Pair(lastSecond?.behindMs ?: -1, lateSinceTaken)
        lateSinceTaken = 0
        r
    }

    /** The app is going away for good. */
    override fun release() {
        if (released) return
        released = true
        runCatching { app.unregisterReceiver(noisy) }
        (app as? Application)?.unregisterActivityLifecycleCallbacks(lifecycle)
        if (Build.VERSION.SDK_INT >= 31) runCatching { audioManager?.removeOnModeChangedListener(modeListener as AudioManager.OnModeChangedListener) }
        handler.post {
            stopEngine("released", fade = false)
            fading.forEach { it.stop() }
            fading.clear()
            decoder?.release()
            decoder = null
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            maker.shutdown()
        }
    }

    /** The stream's frame rate, for the guard's one frame (rule 4): the host's `stream.fps`. Any thread. */
    fun setStreamFPS(fps: Int) = post {
        if (fps > 0) { streamFPS = fps.toDouble(); feed.forget(); feedReading(now()) }
    }

    /** The pings' median round trip of the last second, ms (rule 13's threshold); nil: none. Any thread. */
    fun setRtt(medianMs: Int?) = post { model.rtt(medianMs?.takeIf { it >= 0 }?.let { it / 1000.0 }) }

    /** The session is away from home (the jitter cover's start and bounds, rule 2). Any thread. */
    fun setAway(away: Boolean) = post { model.away(away) }

    /** The last second's numbers, for a HUD. Any thread. */
    val latestSecond: AudioPlayout.Second? get() = synchronized(statsLock) { lastSecond }

    // MARK: - Formats and packets

    private fun receive(f: AudioFormat, again: Boolean = false) {
        val epoch = f.epoch?.takeIf { it in 0..0xFFFF }?.toInt() ?: return
        val fpp = f.framesPerPacket ?: return
        val packetFrames = fpp.coerceIn(1, 1 shl 20).toInt()
        val priming = (f.primingFrames ?: 0).coerceIn(0, 1 shl 20).toInt()
        val cookie = f.cookie ?: ByteArray(0)
        if (!again) { kept = f; quietUntil = 0.0 }
        if (!model.format(epoch, packetFrames, priming, cookie)) return
        decoder?.release()
        decoder = null
        val asc = EldConfig.audioSpecificConfig(cookie)
        val name = decoderName
        val playable = f.codec == AudioCodec.AAC_ELD && (f.sampleRate ?: 48_000L) == 48_000L &&
            (f.channels ?: 2L) == 2L && fpp in 1..8192 && asc != null && name != null
        if (playable && asc != null && name != null) {
            decoder = EldDecoder.create(name, asc, packetFrames, maker)
            busyUntil = now()
        }
        playing = Playing(epoch, packetFrames, playable && decoder != null)
        val info = asc?.let { EldConfig.parse(it) }
        if (decoder != null) {
            val what = f.app ?: when (f.source) { AudioFormat.SOURCE_DESKTOP -> "the whole Mac"; AudioFormat.SOURCE_TEST -> "a test tone"; else -> "?" }
            Log.i(TAG, "audio: format ${f.codec} ${f.sampleRate} Hz ${f.channels} ch, $packetFrames frames (priming $priming), " +
                "epoch $epoch, $what${if (again) " (again, after a stop)" else ""}; config $info on $name")
            if (info != null && info.frameLength != packetFrames) Log.w(TAG, "audio: the config says ${info.frameLength} frames a packet, the format $packetFrames")
        } else {
            Log.i(TAG, "audio: cannot play ${f.codec ?: "an unnamed codec"}" + if (playable) " (the decoder refused it)" else "")
        }
    }

    private fun receive(p: AudioPackets, stamp: Double, came: Double) {
        var fmt = playing
        if (fmt == null) {
            // After a stop(): the kept format's epoch starts again.
            val k = kept ?: return
            if (k.epoch != p.epoch.toLong()) return
            receive(k, again = true)
            fmt = playing ?: return
        }
        if (p.epoch != fmt.epoch || !fmt.playable) return
        val dec = decoder ?: return
        if (dec.broken) {
            Log.w(TAG, "audio: the decoder gives nothing; no sound this epoch")
            playing = Playing(fmt.epoch, fmt.framesPerPacket, false)
            return
        }
        val now = now()
        lastPacketAt = now
        startSecondTimer()
        updateSilence(now)
        if (!model.muted && engine == null && now >= engineRetryAt) startEngine()
        val step = fmt.framesPerPacket / AudioPlayout.RATE
        for ((i, data) in p.packets.withIndex()) {
            val t = now()
            feedReading(t)
            val plan = model.packet(p.epoch, (p.seq + i) and 0xFFFF_FFFFL, stamp + i * step, p.segmentStart && i == 0,
                came = if (came < busyUntil) came else null, now = t)
            if (plan.decode) {
                if (plan.resetFirst) dec.reset()
                val decoded = dec.decode(data)
                plan.id?.let { id ->
                    val keep = fmt.framesPerPacket - plan.dropFront
                    if (decoded != null && decoded.first.size == fmt.framesPerPacket) {
                        frames[id] = decoded.first.copyOfRange(plan.dropFront, fmt.framesPerPacket) to
                            decoded.second.copyOfRange(plan.dropFront, fmt.framesPerPacket)
                    } else {
                        // Silence in its place; the decoder starts afresh at the next packet.
                        model.decodeFailed()
                        frames[id] = FloatArray(keep) to FloatArray(keep)
                    }
                }
            }
            apply(plan.output)
        }
        armDeadline()
    }

    /** What the model says: schedule, or forget. */
    private fun apply(out: AudioPlayout.Output) {
        for (id in out.discards) frames.remove(id)
        for (s in out.schedules) schedule(s)
        model.takeJump()?.let { (by, cause) -> Log.i(TAG, "audio: jump ${"%+.0f".format(by * 1000)} ms (${cause.words})") }
    }

    private fun schedule(s: AudioPlayout.Schedule) {
        val pcm = frames[s.id] ?: return
        try {
            val e = engine ?: return
            val end = minOf(s.offset + s.count, pcm.first.size)
            if (s.offset >= end) return
            var l = pcm.first.copyOfRange(s.offset, end)
            var r = pcm.second.copyOfRange(s.offset, end)
            if (s.change != 0) AudioPlayout.change(l, r, s.change).let { l = it.first; r = it.second }
            if (s.fadeIn) { AudioPlayout.fade(l, true); AudioPlayout.fade(r, true) }
            if (s.fadeOut) { AudioPlayout.fade(l, false); AudioPlayout.fade(r, false) }
            e.schedule(l, r, s.at)
        } finally {
            if (s.part == AudioPlayout.Schedule.Part.TAIL) frames.remove(s.id)
        }
    }

    /** The timeline as the output last stamped it, and its latency when it moved (ClockFeed). */
    private fun feedReading(now: Double) {
        val e = engine ?: return
        feed.feed(e.clock, e.ioBuffer, refresh, streamFPS, now)?.let { apply(it) }
    }

    // MARK: - The tail's deadline and the second

    private fun armDeadline() {
        deadlineTask?.let(handler::removeCallbacks)
        deadlineTask = null
        val d = model.deadline ?: return
        val task = Runnable {
            deadlineTask = null
            val now = now()
            feedReading(now)
            // Its deadline as the fresh reading has it, and no later than now.
            apply(model.tick(maxOf(now, model.deadline ?: now)))
            armDeadline()
        }
        deadlineTask = task
        // The handler counts whole ms: up to the next, never early (an early tick fades a tail the next
        // packet would have followed); the IO buffer's 2 ms of slack covers the lateness.
        handler.postDelayed(task, maxOf(0L, Math.ceil((d - now()) * 1000).toLong()))
    }

    private fun startSecondTimer() {
        if (secondTask != null) return
        val task = object : Runnable {
            override fun run() {
                closeSecond()
                if (secondTask === this) handler.postDelayed(this, 1000)
            }
        }
        secondTask = task
        handler.postDelayed(task, 1000)
    }

    private fun closeSecond() {
        val now = now()
        if (modeListener == null) checkCall()
        val s = model.takeSecond(now)
        synchronized(statsLock) {
            lastSecond = s
            lateSinceTaken += s.late
            if (s.placed > 0) anyPlayed = true
        }
        if (BuildConfig.DEBUG && (engine != null || s.placed > 0 || s.duplicates > 0)) {
            val behind = s.behindMs?.let { "$it ms behind the picture" } ?: "nothing played"
            Log.d(TAG, "audio: $behind; need ${ms(s.need)} (jitter ${ms(s.cover)}); late ${s.late}; error ${s.error?.let { "%+.1f ms".format(it * 1000) } ?: "–"}; " +
                "frames +${s.framesAdded} -${s.framesDropped}; jumps ${s.jumps}" + (if (s.duplicates > 0) "; duplicates ${s.duplicates}" else "") +
                (if (s.joins > 0) "; joins ${s.joins}" else ""))
        }
        val last = lastPacketAt
        if (last != null && now - last >= IDLE_AFTER) {
            // Rule 14: idle. The tail in hand has faded already.
            if (engine != null) stopEngine("idle", fade = false)
            secondTask?.let(handler::removeCallbacks)
            secondTask = null
            lastPacketAt = null
        }
    }

    // MARK: - The engine

    private fun startEngine() {
        checkCall()
        if (inCall) return
        var made: SoundEngine? = null
        made = SoundEngine.start(app, handler, onDead = { post { made?.let(::engineDied) } }, onRouting = { e -> routeChanged(e) })
        if (made != null) {
            engine = made
            apply(model.reset(AudioPlayout.Reset.PLAYBACK))   // a new timeline: what waited for the last one goes
            feed.forget()
            routeId = made.routeId
            Log.i(TAG, "audio: engine on, ${made.routeName}, chunk ${made.chunkFrames} frames")
        } else {
            engineRetryAt = now() + ENGINE_RETRY
            Log.w(TAG, "audio: no sound: the output did not start")
        }
        busyUntil = now()
    }

    /**
     * Stops the engine; `fade` turns it down first (25 ms) and lets it go once that has been heard. The
     * model's timeline goes with it.
     */
    private fun stopEngine(reason: String, fade: Boolean) {
        deadlineTask?.let(handler::removeCallbacks)
        deadlineTask = null
        apply(model.reset(AudioPlayout.Reset.PLAYBACK))
        frames.clear()
        val e = engine ?: return
        engine = null
        if (fade) {
            e.turnDown()
            fading.add(e)
            val wait = FADE_WAIT + (e.clock.latency() ?: 0.0).coerceIn(0.0, 0.25)
            handler.postDelayed({ if (fading.remove(e)) { e.stop(); busyUntil = now() } }, (wait * 1000).toLong())
        } else {
            e.stop()
            busyUntil = now()
        }
        if (reason != "idle") Log.i(TAG, "audio: engine off ($reason)")
    }

    private fun engineDied(e: SoundEngine) {
        if (e !== engine) { if (fading.remove(e)) e.stop(); return }
        engine = null
        e.stop()
        apply(model.reset(AudioPlayout.Reset.PLAYBACK))
        frames.clear()
        busyUntil = now()
        Log.w(TAG, "audio: the output died; the next packet starts another")
    }

    /** The output moved (headphones in, a Bluetooth speaker): the next packet is placed by time at its latency. */
    private fun routeChanged(e: SoundEngine) {
        if (e !== engine) return
        val id = e.routeId
        if (id == routeId) return
        val before = routeId
        routeId = id
        e.clock.forgetLatency()
        feed.forget()
        if (before != -1) {
            apply(model.reset(AudioPlayout.Reset.JUMP))
            Log.i(TAG, "audio: route → ${e.routeName}")
        }
    }

    private fun muteHere(on: Boolean) {
        if (mutedHere == on) return
        mutedHere = on
        // Unmuting is the person asking for sound: an output that failed to start is tried again.
        if (!on) engineRetryAt = 0.0
        updateSilence(now())
        if (on) stopEngine("muted", fade = true) else Log.i(TAG, "audio: unmuted")
    }

    private fun setForeground(on: Boolean) = post {
        if (foreground == on) return@post
        foreground = on
        updateSilence(now())
        if (!on) stopEngine("in the background", fade = true)
    }

    /** A call (the audio mode other than normal) stops the sound until it ends, as iOS's interruption. */
    private fun checkCall() {
        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val call = mode != AudioManager.MODE_NORMAL
        if (call == inCall) return
        inCall = call
        updateSilence(now())
        if (call) stopEngine("a call", fade = false) else Log.i(TAG, "audio: the call is over")
    }

    /**
     * Nothing is decoded while the sound cannot be heard: muted, in the background, a call, or just after a
     * stop(). Each packet still counts for the floor and the cover, and once it can be heard again the
     * decoder's state is stale, so the next packet starts it afresh (a playback reset, rule 9).
     */
    private fun updateSilence(now: Double) {
        val silent = mutedHere || !foreground || inCall || now < quietUntil
        if (silent == model.muted) return
        model.mute(silent)
        if (!silent) apply(model.reset(AudioPlayout.Reset.PLAYBACK))
    }

    /** Headphones or a Bluetooth output gone while the sound plays: muted at once, and the client told. */
    private fun headphonesOut() {
        if (playing == null || mutedHere || !foreground || lastPacketAt == null) return
        mutedHere = true
        mutedShown = true
        updateSilence(now())
        stopEngine("headphones disconnected", fade = true)
        main.post { onHeadphonesOut?.invoke() }
    }

    private fun post(block: () -> Unit) {
        if (!released) handler.post(block)
    }

    private fun now(): Double = System.nanoTime() / 1e9
    private fun ms(s: Double) = "${AudioPlayout.rounded(s * 1000).toInt()} ms"

    private companion object {
        const val TAG = "Sill"
        /** About half a second of frames at 60 fps: the median the guard starts from. */
        const val FRAMES_KEPT_BEFORE_FORMAT = 32
        /** Idle: no packet for this long stops the engine (rule 14). */
        const val IDLE_AFTER = 10.0
        /** A fading stop: the 25 ms ramp and a little more, plus the output's latency. */
        const val FADE_WAIT = 0.05
        const val QUIET_AFTER_STOP = 0.5
        const val ENGINE_RETRY = 5.0
    }
}
