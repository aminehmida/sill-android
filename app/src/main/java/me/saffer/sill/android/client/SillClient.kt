package me.saffer.sill.android.client

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import me.saffer.sill.android.BuildConfig
import me.saffer.sill.android.crypto.MacId
import me.saffer.sill.android.crypto.MacInfoCheck
import me.saffer.sill.android.crypto.PairLink
import me.saffer.sill.android.crypto.SafeText
import me.saffer.sill.android.crypto.Tls
import me.saffer.sill.android.crypto.DialException
import me.saffer.sill.android.net.DialOrder
import me.saffer.sill.android.net.Dialer
import me.saffer.sill.android.net.Endpoint
import me.saffer.sill.android.net.LinkStats
import me.saffer.sill.android.net.PairOutcome
import me.saffer.sill.android.net.Pairer
import me.saffer.sill.android.net.SavedMac
import me.saffer.sill.android.net.SavedMacs
import me.saffer.sill.android.net.Session
import me.saffer.sill.android.protocol.AppInfo
import me.saffer.sill.android.protocol.Blobs
import me.saffer.sill.android.protocol.Goodbye
import me.saffer.sill.android.protocol.HostSettingsState
import me.saffer.sill.android.protocol.Input
import me.saffer.sill.android.protocol.Kind
import me.saffer.sill.android.protocol.LockedMac
import me.saffer.sill.android.protocol.ScrollDirection
import me.saffer.sill.android.protocol.MacInfo
import me.saffer.sill.android.protocol.MacMenu
import me.saffer.sill.android.protocol.MacMenuItem
import me.saffer.sill.android.protocol.MacPointer
import me.saffer.sill.android.protocol.Message
import me.saffer.sill.android.protocol.ParameterSets
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.PointerAction
import me.saffer.sill.android.protocol.ScrollPhase
import me.saffer.sill.android.protocol.SettingsChange
import me.saffer.sill.android.protocol.SignedMacInfo
import me.saffer.sill.android.protocol.StreamSettings
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.protocol.WindowInfo
import me.saffer.sill.android.protocol.WindowList
import me.saffer.sill.android.protocol.parseAppList
import me.saffer.sill.android.video.VideoDecoder

/** The Mac's cursor, drawn under this device's pointer sprite (kind 14). Sizes in Mac points. */
class CursorSprite(val image: ImageBitmap, val hotX: Float, val hotY: Float, val width: Float, val height: Float)

/**
 * The client (iOSClient/StreamClient.swift's job): the saved Macs, pairing, one session at a time,
 * the catalog, the settings, the menus, the pointer, and everything the screens show. Compose state
 * lives on the main thread; the session's threads post to it. Frames never touch the main thread.
 */
class SillClient(private val app: Context) : Session.Listener, VideoDecoder.Events {
    private val main = Handler(Looper.getMainLooper())
    private val store = SavedMacs(app)
    val decoder = VideoDecoder(this)

    sealed class Phase {
        data object Idle : Phase()
        data class Connecting(val macName: String, val reconnecting: Boolean) : Phase()
        data object Streaming : Phase()
    }

    // MARK: what the screens show
    var phase by mutableStateOf<Phase>(Phase.Idle); private set
    var status by mutableStateOf<String?>(null); private set
    var savedMacs by mutableStateOf(store.all()); private set
    var currentMac by mutableStateOf<SavedMac?>(null); private set
    var macName by mutableStateOf("Mac"); private set
    var windows by mutableStateOf(listOf<WindowInfo>()); private set
    var active by mutableStateOf<StreamSource>(StreamSource.None); private set
    val thumbnails = mutableStateMapOf<Long, ImageBitmap>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    var apps by mutableStateOf(listOf<AppInfo>()); private set
    var videoSize by mutableStateOf<Pair<Int, Int>?>(null); private set
    var cursor by mutableStateOf<CursorSprite?>(null); private set
    var hostGestures by mutableIntStateOf(0); private set
    var hostVersion by mutableStateOf<String?>(null); private set
    var stats by mutableStateOf<LinkStats?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var pairing by mutableStateOf(false); private set
    var pairError by mutableStateOf<String?>(null); private set
    var hasSettings by mutableStateOf(false); private set
    var route by mutableStateOf<String?>(null); private set

    // What this Mac's window list says beyond the windows (Sill for Mac 0.6).
    /** The clipboard generation the Mac takes (1: text); 0: send it no kind 32. */
    var hostClipboard by mutableIntStateOf(0); private set
    /** 1: the Mac repeats a key held down, so a hardware keyboard's characters may go as keys. */
    var hostKeyRepeat by mutableIntStateOf(0); private set
    /** Why the Mac shows nothing of itself (locked, another user on its console); nil: it shows itself. */
    var lockWhy by mutableStateOf<LockedMac.Why?>(null); private set
    /** The off-console view: this account's own desktop while another user has the screen, to look at. */
    var offConsoleView by mutableStateOf(false); private set
    /** What covers the stream: another user on the Mac, then "Unlocking…" until the next keyframe. */
    var lockShade by mutableStateOf<LockedMac.Shade?>(null); private set
    private var shadeTimeout: Runnable? = null
    /** This session's key is on the Mac's trust list (kind 16); nil before the Mac says, or an older Mac. */
    val sessionPaired: Boolean? get() = hostState?.sessionPaired
    /** The Mac has sound: its settings carry Send Audio, on or off. */
    val macHasSound: Boolean get() = shownSettings?.sendAudio != null

    // The Mac's sound (audio/) and the clipboard (clipboard/): set once, at start, by MainActivity.
    @Volatile var sound: SoundPlayer? = null
        set(value) { field = value; value?.muted = soundMuted }
    var clipboard: ClipboardBridge? = null
        set(value) { field = value; value?.enabled = shareClipboard }

    // Home: the Macs Bonjour lists on this network, and a pairing ask waiting for its code.
    var homeMacs by mutableStateOf(listOf<me.saffer.sill.android.net.HomeMac>()); private set
    // Every change of what the network lists is a look for the automatic reconnect: a Mac that wakes is dialed.
    private val discovery = me.saffer.sill.android.net.HomeDiscovery(app) { homeMacs = it; reconnectIfDue() }
    data class HomeAsk(val found: me.saffer.sill.android.net.HomeMac, val macFingerprint: ByteArray)
    var homeAsk by mutableStateOf<HomeAsk?>(null); private set
    var asking by mutableStateOf<String?>(null); private set

    // The settings panel (kinds 16, 17).
    val ledger = SettingsLedger()
    var shownSettings by mutableStateOf<StreamSettings?>(null); private set
    var hostState by mutableStateOf<HostSettingsState?>(null); private set
    var settingsRevision by mutableIntStateOf(0); private set

    // The Mac's menus (kinds 24, 25, 27).
    val menuState = MenuState()
    var menuRevision by mutableIntStateOf(0); private set
    var hasMenus by mutableStateOf(false); private set

    // This device's own settings, never sent.
    private val prefs = app.getSharedPreferences("sill", Context.MODE_PRIVATE)
    var gesturesEnabled by mutableStateOf(prefs.getBoolean("trackpadGestures", true)); private set
    var showHud by mutableStateOf(prefs.getBoolean("hud", false)); private set
    fun setGestures(on: Boolean) { gesturesEnabled = on; prefs.edit().putBoolean("trackpadGestures", on).apply() }
    fun setHud(on: Boolean) { showHud = on; prefs.edit().putBoolean("hud", on).apply() }
    /** Mute: this device alone, at once; the Mac keeps sending. */
    var soundMuted by mutableStateOf(prefs.getBoolean("soundMuted", false)); private set
    /** Headphones or a Bluetooth output went away while the Mac's sound played: muted, as a video app pauses, and said. Main thread. */
    fun headphonesOut() { if (soundMuted) return; changeSoundMuted(true); notice("Sound muted: headphones disconnected.") }
    fun changeSoundMuted(on: Boolean) { noteAction(); soundMuted = on; sound?.muted = on; prefs.edit().putBoolean("soundMuted", on).apply() }
    /** Share Clipboard: this device's own switch. */
    var shareClipboard by mutableStateOf(prefs.getBoolean("shareClipboard", true)); private set
    fun changeShareClipboard(on: Boolean) { noteAction(); shareClipboard = on; clipboard?.enabled = on; prefs.edit().putBoolean("shareClipboard", on).apply() }
    /** Reverse Scrolling (ScrollDirection): the input views read the same preference when a scroll begins. */
    var reverseScroll by mutableStateOf(prefs.getBoolean(ScrollDirection.PREF, false)); private set
    fun changeReverseScroll(on: Boolean) { noteAction(); reverseScroll = on; prefs.edit().putBoolean(ScrollDirection.PREF, on).apply() }
    /** Hold to Show Window Controls, in seconds (0.2 to 2; 0.5 by default). */
    var holdToShowControls by mutableStateOf(prefs.getFloat("holdToShowControls", 0.5f).toDouble()); private set
    fun changeHoldToShowControls(s: Double) { holdToShowControls = s.coerceIn(0.2, 2.0); prefs.edit().putFloat("holdToShowControls", holdToShowControls.toFloat()).apply() }
    /** Larger Picture, on a phone held upright: the picture takes the thumbnails' place. */
    var largerPicture by mutableStateOf(prefs.getBoolean("largerPicture", false)); private set
    fun changeLargerPicture(on: Boolean) { noteAction(); largerPicture = on; prefs.edit().putBoolean("largerPicture", on).apply() }

    // The pointer sprite (PointerPresence): the Mac's while the Mac or another device moved it last,
    // this device's own only for the trackpad.
    var macPointer by mutableStateOf<Offset?>(null); private set
    var pointerHere by mutableStateOf(true); private set
    var ownPointer by mutableStateOf(Offset(0.5f, 0.5f)); private set
    /** This device's own arrow shows only after the trackpad moved it, never for a finger or typing. */
    var ownPointerVisible by mutableStateOf(false); private set
    /** Bumped when a report moves the anchor the trackpad's cursor carries on from. */
    var pointerReseeds by mutableIntStateOf(0); private set

    // MARK: session bookkeeping (main thread)
    @Volatile private var session: Session? = null
    private var sessionReady = false
    private var generation = 0
    private var goodbye: Goodbye? = null
    /** The automatic reconnect (DiscoveryPolicy, issue #69), the look it asked for, and whether its own dial is under way. */
    private var autoReconnect: me.saffer.sill.android.net.ReconnectClock? = null
    private var reconnectCheck: Runnable? = null
    private var reconnectDialing = false
    /** An open door's session: the key its first connection saw, which its reconnect pins (DiscoveryPolicy.reconnectTrust). */
    private var openKey: ByteArray? = null
    private var userClosed = false
    private var lastAutoDesktopAt = 0L
    private var lastUserPickAt = 0L
    private var previousActive: StreamSource = StreamSource.None
    private var panel: Pair<Double, Double>? = null
    var textScale by mutableStateOf<Double?>(null); private set
    private var lastViewportJson: String? = null
    private var viewportPending: Runnable? = null
    private var unsupportedSize: Pair<Int, Int>? = null
    private var suspendTask: Runnable? = null
    private var resumeMac: SavedMac? = null
    @Volatile private var params: ParameterSets? = null
    /** A session with an open door's unsaved Mac: never reconnected to by its saved record (it has none). */
    private var openSession = false

    val deviceName: String = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
    private val deviceLabel = "$deviceName (${Build.MODEL})"
    val displayFps: Int = runCatching {
        (app.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate }.toInt()
    }.getOrDefault(60)

    // MARK: pairing

    fun pairWithCode(address: String, code: String) {
        val endpoint = Endpoint.parse(address, 7455) ?: run { pairError = "Enter the Mac’s address, as its pairing window shows it."; return }
        val check = me.saffer.sill.android.crypto.PairingCode.check(code)
        val digits = when (check) {
            is me.saffer.sill.android.crypto.PairingCode.Check.Ok -> check.code
            me.saffer.sill.android.crypto.PairingCode.Check.Length -> { pairError = "A code has 12 digits."; return }
            me.saffer.sill.android.crypto.PairingCode.Check.Typo -> { pairError = "That code has a typo. Check it against your Mac."; return }
        }
        runPairing { Pairer.byCode(endpoint, digits, deviceName, Build.MODEL, store) }
    }

    fun pairWithLink(text: String, extraAddress: String? = null) {
        val link = when (val r = PairLink.parse(text)) {
            is PairLink.Companion.Result.Ok -> r.link
            is PairLink.Companion.Result.Failed -> { pairError = r.why; return }
        }
        val also = extraAddress?.takeIf { it.isNotBlank() }?.let { Endpoint.parse(it, link.port) }
        runPairing { Pairer.byLink(link, deviceName, Build.MODEL, store, also) }
    }

    private fun runPairing(work: () -> PairOutcome) {
        if (pairing) return
        pairing = true; pairError = null
        Thread({
            val outcome = runCatching(work).getOrElse { PairOutcome.Failed(it.message ?: it.toString()) }
            main.post {
                pairing = false
                when (outcome) {
                    is PairOutcome.Failed -> pairError = outcome.message
                    is PairOutcome.Paired -> {
                        savedMacs = store.all()
                        connect(outcome.mac)
                    }
                }
            }
        }, "sill-pair").start()
    }

    fun clearPairError() { pairError = null }

    // MARK: home (Bonjour)

    /** The home door of a saved Mac on this network, by its recognition tag; never a plain door. */
    fun homeOf(mac: SavedMac): me.saffer.sill.android.net.HomeMac? {
        val key = mac.recognitionKey?.let { me.saffer.sill.android.crypto.Base64Url.decode(it) } ?: return null
        return homeMacs.firstOrNull { h ->
            h.door != me.saffer.sill.android.net.HomeMac.Door.PLAIN && h.tag?.let { me.saffer.sill.android.crypto.RecognitionTag.matches(it, key) } == true
        }
    }

    /** The Macs on this network this device has not paired with. */
    val unpairedHomeMacs: List<me.saffer.sill.android.net.HomeMac>
        get() = homeMacs.filter { h -> savedMacs.none { m -> homeOf(m) == h } }

    /** A tap on an unpaired Mac at home: ask it to show its code (or connect, on an open door). */
    fun tapHome(found: me.saffer.sill.android.net.HomeMac) {
        when (found.door) {
            me.saffer.sill.android.net.HomeMac.Door.PLAIN -> { status = "${found.serviceName} runs an older Sill. Update Sill on the Mac to connect."; return }
            me.saffer.sill.android.net.HomeMac.Door.OPEN -> { connectOpen(found); return }
            me.saffer.sill.android.net.HomeMac.Door.PAIRING_REQUIRED -> Unit
        }
        if (asking != null) return
        asking = found.serviceName; pairError = null
        Thread({
            val r = Pairer.ask(found, deviceName, Build.MODEL)
            main.post {
                asking = null
                when (r) {
                    is Pairer.AskOutcome.Shown -> homeAsk = HomeAsk(found, r.macFingerprint)
                    is Pairer.AskOutcome.Refused -> status = r.message
                }
            }
        }, "sill-ask").start()
    }

    /** The code the Mac showed for this device's ask, over the same door, pinned to the key the ask saw. */
    fun pairHomeCode(code: String) {
        val ask = homeAsk ?: return
        val digits = when (val c = me.saffer.sill.android.crypto.PairingCode.check(code)) {
            is me.saffer.sill.android.crypto.PairingCode.Check.Ok -> c.code
            me.saffer.sill.android.crypto.PairingCode.Check.Length -> { pairError = "A code has 12 digits."; return }
            me.saffer.sill.android.crypto.PairingCode.Check.Typo -> { pairError = "That code has a typo. Check it against your Mac."; return }
        }
        runPairing { Pairer.byCode(ask.found.endpoint, digits, deviceName, Build.MODEL, store, pin = ask.macFingerprint) }
        homeAsk = null
    }

    /** The device's Cancel: the Mac closes the window its ask opened. */
    fun cancelHomeAsk() {
        val ask = homeAsk ?: return
        homeAsk = null
        Thread({ Pairer.ask(ask.found, deviceName, Build.MODEL, cancel = true, pin = ask.macFingerprint) }, "sill-cancel").start()
    }

    /** An open door (the Mac's Require pairing is off): a session with any key, nothing saved. */
    private fun connectOpen(found: me.saffer.sill.android.net.HomeMac) {
        val transient = SavedMac(
            macID = MacId.make(ByteArray(32)), fingerprint = ByteArray(32), name = found.serviceName, recognitionKey = null,
            remotePort = found.port, addresses = emptyList(), typedAddresses = listOf(found.endpoint.toString()),
            infoIssuedAt = 0.0, lastWorked = null, pairedAt = 0.0, lastConnectedAt = null,
        )
        connect(transient, anyKey = true)
    }

    fun startDiscovery() = discovery.start()
    fun stopDiscovery() = discovery.stop()

    /** DEBUG only: browse a test service type, and reach what it lists at `override` (adb reverse). */
    fun debugDiscovery(type: String, override: Endpoint?) {
        discovery.stop()
        discovery.serviceType = type
        discovery.endpointOverride = override
        discovery.start()
    }

    fun forget(mac: SavedMac) {
        if (currentMac?.macID == mac.macID) disconnect()
        store.remove(mac.macID)
        savedMacs = store.all()
    }

    // MARK: connecting

    /**
     * A session with a saved Mac: its home door first when Bonjour lists it on this network (home
     * quality, the LAN's latency), then its remote addresses (Tailscale's first), 1 s apart; the first
     * TLS handshake pinned to its key wins. `anyKey`: an open door's unsaved Mac.
     */
    fun connect(mac: SavedMac, reconnecting: Boolean = false, anyKey: Boolean = false) {
        val p = phase
        if (!reconnecting && currentMac?.macID == mac.macID && (p is Phase.Streaming || p is Phase.Connecting)) return
        // A live session replaced (a pairing link confirmed while streaming): nothing of it carries over.
        session?.let { it.close("another connection"); session = null; endSessionState() }
        val gen = ++generation
        currentMac = mac
        macName = mac.name
        userClosed = false
        goodbye = null
        sessionReady = false
        openSession = anyKey
        // A tap starts afresh: no automatic reconnect left, and an open door is dialed with any key, as a tap always was.
        if (!reconnecting) { endReconnect(); openKey = null }
        // The reconnect of an open door's session pins the key that session saw (iOS's finding 3).
        val pin = if (anyKey) openKey.takeIf { reconnecting } else mac.fingerprint
        sessionStartAt = DeviceIdlePolicy.sessionStart(sessionStartAt, reconnecting, uptime())
        phase = Phase.Connecting(mac.name, reconnecting)
        status = if (reconnecting) "Reconnecting to ${mac.name}…" else "Connecting to ${mac.name}…"
        // A pairing that finished after the app left the screen: the session still lets go after its grace.
        if (!foreground) main.post { onBackground() }
        val endpoints = (listOfNotNull(if (anyKey) null else homeOf(mac)?.endpoint) + DialOrder.endpoints(mac)).distinctBy { it.key }
        if (endpoints.isEmpty()) {
            phase = Phase.Idle; status = "No address is saved for ${mac.name}. Pair it again."
            return
        }
        Thread({
            try {
                val conn = Dialer.dial(endpoints, Tls.SESSION_ALPN, pin)
                val hello = Payloads.hello(
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), deviceLabel,
                    audio = sound?.codecs?.takeIf { it.isNotEmpty() }, offConsoleView = LockedMac.VIEW_GENERATION,
                )
                val s = Session(conn, deviceLabel, hello, this, soundStats = { sound?.takeSecond() })
                main.post {
                    if (gen != generation) { s.close("superseded"); return@post }
                    session = s
                    if (anyKey && openKey == null) openKey = conn.macFingerprint
                    route = routeWord(conn.host)
                    // Away (a VPN or the internet) until the Mac says: the sound's jitter allowance (AudioOutput).
                    (sound as? me.saffer.sill.android.audio.MacSound)?.setAway(route != "Wi‑Fi" && route != "on this device")
                    s.start()
                    // A TLS session counts as connected at its first window list (10 s at most).
                    main.postDelayed({
                        if (gen == generation && session === s && !sessionReady) s.close("no window list in 10 s")
                    }, 10_000)
                }
            } catch (e: Exception) {
                val msg = (e as? DialException)?.message ?: e.message ?: e.toString()
                val reason = (e as? DialException)?.reason
                main.post { if (gen == generation) dialFailed(mac, msg, reason) }
            }
        }, "sill-connect").start()
    }

    private fun dialFailed(mac: SavedMac, message: String, reason: DialException.Reason?) {
        Log.i(TAG, "dial ${mac.name} failed: $message")
        val r = autoReconnect
        if (r != null && r.macID == mac.macID && reconnectDialing) {
            reconnectDialing = false
            if (reason == DialException.Reason.WRONG_MAC || reason == DialException.Reason.REFUSED_KEY) {
                // The Mac answered for good: another key (at an open door, whatever now answers under its
                // name), or this device's key refused. Not dialed again by itself; a tap still does.
                endReconnect()
                phase = Phase.Idle
                status = if (reason == DialException.Reason.WRONG_MAC && openSession) {
                    me.saffer.sill.android.net.DiscoveryPolicy.otherKeyLine(mac.name, deviceWord())
                } else message
                return
            }
            // One dial that did not connect: the reconnect looks again when its clock says.
            r.dialFailed(uptime())
            reconnectIfDue()
            return
        }
        phase = Phase.Idle
        status = awayWords(mac, reason) ?: message
    }

    /**
     * Away from home, every address of the Mac a VPN's, and none answered: either end's VPN may be the
     * one off (RemoteCopy.dialFailure's vpnOff, #309), so the words name both.
     */
    private fun awayWords(mac: SavedMac, reason: DialException.Reason?): String? {
        if (homeOf(mac) != null) return null
        if (reason != DialException.Reason.DNS && reason != DialException.Reason.TIMEOUT && reason != DialException.Reason.NETWORK) return null
        val hosts = DialOrder.endpoints(mac).map { it.host }
        if (hosts.isEmpty() || !hosts.all { Endpoint.isTailscale(it) || it.lowercase().endsWith(".ts.net") }) return null
        return "Tailscale looks off on this ${deviceWord()} or on ${mac.name}. Turn it on for both, then tap ${mac.name}."
    }

    private fun deviceWord(): String = if (app.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    fun disconnect() {
        noteAction()
        keepPicture()
        userClosed = true
        generation++
        resumeMac = null
        endReconnect()
        session?.close("disconnected by the user")
        session = null
        endSessionState()
        phase = Phase.Idle
        status = null
    }

    /** The app went to the background: let the session go after a grace, and come back to it. */
    fun onBackground() {
        if (phase !is Phase.Streaming && phase !is Phase.Connecting) return
        val mac = currentMac ?: return
        suspendTask?.let(main::removeCallbacks)
        suspendTask = Runnable {
            resumeMac = mac
            userClosed = true
            generation++
            endReconnect()          // the return starts one afresh (onForeground)
            session?.close("in the background")
            session = null
            endSessionState()
            phase = Phase.Idle
            status = null
        }.also { main.postDelayed(it, 15_000) }
    }

    /** Whether the app is on screen (onStart … onStop). */
    private var foreground = true

    fun onForeground() {
        foreground = true
        frontAt = uptime()
        suspendTask?.let(main::removeCallbacks); suspendTask = null
        session?.resetLiveness()
        // Back on the screen (issue #69): a reconnect still running dials a listed row at once and counts
        // again; then the browser is made again, its first look a look for the reconnect too.
        autoReconnect?.cameForward(uptime())
        discovery.start()
        clipboard?.onForeground()
        reviewIdle()
        considerKeyframe()
        // A session let go in the background comes back through the automatic reconnect (issue #69):
        // dialed at once, tried for a few minutes while the Mac does not answer (asleep, say), and
        // connected to when it does.
        resumeMac?.let { mac -> resumeMac = null; startReconnect(store.byID(mac.macID) ?: mac, onStream = false) }
        reconnectIfDue()
    }

    /** The app left the screen: stop browsing (the session itself goes after its grace). */
    fun onStopped() {
        foreground = false
        discovery.stop()
        recovery.leftForeground()
        sound?.stop()
        reviewIdle()
        onBackground()
    }

    /**
     * A session with `mac` ended by itself, or was let go in the background: the automatic reconnect
     * (DiscoveryPolicy, issue #69). `onStream`: a session that was streaming keeps the stream screen
     * while the reconnect is a blip; the connect screen, with its line, once the Mac looks asleep.
     */
    private fun startReconnect(mac: SavedMac, onStream: Boolean) {
        endReconnect()
        autoReconnect = me.saffer.sill.android.net.ReconnectClock(mac.macID, mac.name, uptime(), onStream)
        reconnectIfDue()
    }

    private fun endReconnect() {
        autoReconnect = null
        reconnectDialing = false
        reconnectCheck?.let(main::removeCallbacks); reconnectCheck = null
    }

    /**
     * The automatic reconnect's look (StreamClient.reconnectIfListed): at a loss, a dial that did not
     * connect, every change of what the network lists, the return to the screen, and the time it said
     * to look again. Its Mac's row listed: a dial at once, then 5, 10, 20 and every 30 s while they do
     * not connect, 12 at most, a listing seen afresh starting again; not listed: its addresses, 2, 4, 8,
     * then every 10 s, for two minutes. Never in the background. Main thread.
     */
    private fun reconnectIfDue() {
        reconnectCheck?.let(main::removeCallbacks); reconnectCheck = null
        val r = autoReconnect ?: return
        if (reconnectDialing || session != null) return
        val mac = store.byID(r.macID) ?: currentMac?.takeIf { it.macID == r.macID } ?: run { endReconnect(); return }
        val now = uptime()
        when (val step = r.next(listedSince = if (openSession) null else homeOf(mac)?.since, foreground = foreground, now = now)) {
            is me.saffer.sill.android.net.ReconnectClock.Step.Dial -> {
                reconnectDialing = true
                connect(mac, reconnecting = true, anyKey = openSession)
                if (phase is Phase.Idle) { endReconnect(); return }      // nothing to dial: connect said why
                if (!r.showsStream) phase = Phase.Connecting(mac.name, false)
                status = step.line
            }
            is me.saffer.sill.android.net.ReconnectClock.Step.Wait -> {
                phase = if (r.showsStream) Phase.Connecting(mac.name, true) else Phase.Idle
                status = step.line
                step.at?.let { at ->
                    reconnectCheck = Runnable { reconnectIfDue() }.also { main.postDelayed(it, ((at - now) * 1000).toLong().coerceAtLeast(10)) }
                }
            }
        }
    }

    private fun endSessionState() {
        decoder.reset()
        params = null
        sessionReady = false
        windows = emptyList(); active = StreamSource.None; previousActive = StreamSource.None
        thumbnails.clear(); apps = emptyList(); videoSize = null; cursor = null; stats = null
        hasSettings = false; hostState = null; shownSettings = null; ledger.reset(); settingsRevision++
        menuState.reset(); menuRevision++; hasMenus = false
        macPointer = null; pointerHere = true
        lastViewportJson = null
        unsupportedSize = null
        route = null
        hostClipboard = 0; hostKeyRepeat = 0
        lockWhy = null; offConsoleView = false; setShade(null)
        sound?.stop()
        clipboard?.onSessionEnded()
        recovery.reset(); keyframeCheck?.let(main::removeCallbacks); keyframeCheck = null
        reviewIdle()
    }

    // MARK: Session.Listener

    override fun onMessage(session: Session, msg: Message) {
        when (msg.kind) {
            Kind.FRAME -> {
                session.noteFrame(msg.timestamp)
                decoder.onFrame(msg.payload, msg.isKeyframe, msg.timestamp)
                sound?.onVideoFrame(msg.timestamp)
                // The first keyframe after a locked Mac's unlock: its picture is back, the cover goes.
                if (msg.isKeyframe && lockShade == LockedMac.Shade.Unlocking) {
                    main.post { if (session === this.session) setShade(LockedMac.shadeAfterFrame(lockShade, keyframe = true)) }
                }
            }
            Kind.AUDIO -> if (session === this.session) sound?.onMessage(msg.payload, msg.timestamp)
            Kind.PARAMETER_SETS -> ParameterSets.parse(msg.payload)?.let { params = it; decoder.onParameterSets(it) }
            Kind.THUMBNAIL -> Blobs.thumbnail(msg.payload)?.let { t ->
                val bmp = BitmapFactory.decodeByteArray(t.jpeg, 0, t.jpeg.size)?.asImageBitmap() ?: return
                main.post { if (session === this.session) thumbnails[t.windowID] = bmp }
            }
            Kind.APP_ICON -> Blobs.icon(msg.payload)?.let { i ->
                val bmp = BitmapFactory.decodeByteArray(i.png, 0, i.png.size)?.asImageBitmap() ?: return
                main.post { icons[i.bundleID] = bmp }
            }
            Kind.CURSOR_SHAPE -> Blobs.cursorShape(msg.payload)?.let { c ->
                val bmp = BitmapFactory.decodeByteArray(c.png, 0, c.png.size)?.asImageBitmap() ?: return
                main.post { if (session === this.session) cursor = CursorSprite(bmp, c.hotX, c.hotY, c.width, c.height) }
            }
            Kind.MAC_POINTER -> MacPointer.parse(msg.payload)?.let { p ->
                // Stale when built before the Mac read this device's latest input, or a move still waits.
                if (p.seen < session.inputsOnSession || session.moveWaiting) return
                main.post { if (session === this.session) macPointerReport(p) }
            }
            else -> main.post { if (session === this.session) handleOnMain(session, msg) }
        }
    }

    override fun onEnded(session: Session, why: String?) {
        main.post {
            if (session !== this.session) return@post
            this.session = null
            val mac = currentMac
            val wasStreaming = sessionReady
            keepPicture()
            endSessionState()
            if (userClosed || mac == null) return@post
            val g = goodbye
            val name = mac.name
            val (words, reconnect) = goodbyePolicy(g, name, atHome = homeOf(mac) != null)
            Log.i(TAG, "session ended (${why ?: "closed"}), goodbye=${g?.reason}, reconnect=$reconnect")
            if (g?.reason == "removed") store.update(mac.macID) { it.copy(revoked = true) }.also { savedMacs = store.all() }
            if (reconnect) {
                val r = autoReconnect
                if (r != null && r.macID == mac.macID && !wasStreaming) {
                    // The reconnect's own dial ended before its first window list, with no goodbye that ends
                    // it: one dial that did not connect, not a new loss (issue #69).
                    reconnectDialing = false
                    r.dialFailed(uptime())
                    reconnectIfDue()
                } else {
                    startReconnect(mac, onStream = wasStreaming)
                }
                // The Mac's own words ("‹Mac› quit Sill.") stay until the reconnect's next line.
                if (words != null && autoReconnect != null) status = words
                return@post
            }
            endReconnect()
            phase = Phase.Idle
            status = words ?: "Lost the connection to $name."
        }
    }

    override fun onStats(session: Session, stats: LinkStats) {
        (sound as? me.saffer.sill.android.audio.MacSound)?.setRtt(stats.rttMs.takeIf { it >= 0 })
        main.post {
            if (session !== this.session) return@post
            this.stats = stats
            val now = SystemClock.uptimeMillis()
            if (ledger.expire(now, timeoutMs())) { notice("$macName didn’t answer. Try again."); refreshSettings() }
            if (menuState.expire(now, timeoutMs())) menuRevision++
        }
    }

    /** GoodbyePolicy: the words, and whether this device reconnects by itself. */
    private fun goodbyePolicy(g: Goodbye?, name: String, atHome: Boolean): Pair<String?, Boolean> = when (g?.reason) {
        null -> null to true
        "quit" -> "$name quit Sill." to true
        "busy" -> "$name is serving as many devices as it can." to true
        "removed" -> "$name removed this device. Pair it again to connect." to false
        "pairingRequired" -> "$name now asks devices to pair. Pair this device again." to false
        // Never back through the remote door; at home its home door still serves this device.
        "remoteOff" -> "Remote Access is off on $name." to atHome
        "internetOff" -> "$name no longer takes connections from the internet." to atHome
        else -> (g.message?.let { SafeText.label(it, 300) }?.takeIf { it.isNotEmpty() } ?: "$name ended the session.") to (g.reconnect == true)
    }

    private fun handleOnMain(session: Session, msg: Message) {
        when (msg.kind) {
            Kind.WINDOW_LIST -> WindowList.parse(msg.payload)?.let { windowList(session, it) }
            Kind.APP_LIST -> apps = parseAppList(msg.payload)
            Kind.HOST_SETTINGS -> HostSettingsState.parse(msg.payload)?.let { settingsState(it) }
            Kind.MAC_INFO -> SignedMacInfo.parse(msg.payload)?.let { macInfo(session, it) }
            // A goodbye without a readable reason is a notice that asks for nothing: no reconnect (GoodbyePolicy).
            Kind.GOODBYE -> goodbye = Goodbye.parse(msg.payload) ?: Goodbye("", null, null, null)
            Kind.CLIPBOARD -> clipboard?.onMessage(msg.payload)
            Kind.PONG -> (clipboard as? me.saffer.sill.android.clipboard.ClipboardSync)?.onReceipt(msg.payload)
            Kind.MAC_MENU -> MacMenu.parse(msg.payload)?.let { m ->
                when (val o = menuState.receive(m)) {
                    is MenuState.Outcome.Pressed -> if (!o.ok) notice(o.note ?: "The menus changed. Open the menu again.")
                    else -> Unit
                }
                hasMenus = menuState.top.isNotEmpty()
                menuRevision++
            }
        }
    }

    private fun windowList(session: Session, list: WindowList) {
        val first = !sessionReady
        windows = list.windows
        macName = SafeText.label(list.macName, 64).ifEmpty { currentMac?.name ?: "Mac" }
        hostGestures = list.gestures ?: 0
        hostVersion = list.hostVersion
        hostClipboard = list.clipboard ?: 0
        hostKeyRepeat = list.keyRepeat ?: 0
        val why = LockedMac.why(list)
        val view = LockedMac.view(list)
        if (why != lockWhy || view != offConsoleView) Log.i(TAG, "lock: ${why ?: "unlocked"}${if (view) ", the off-console view" else ""}")
        lockWhy = why
        offConsoleView = view
        setShade(LockedMac.shade(lockShade, why, view))
        if (why != null) sound?.stop()
        val ids = list.windows.map { it.id }.toSet()
        thumbnails.keys.retainAll(ids)
        val was = active
        active = list.active
        if (first) {
            sessionReady = true
            phase = Phase.Streaming
            status = null
            endReconnect()
            currentMac?.let { mac ->
                store.update(mac.macID) { it.copy(lastWorked = Endpoint(session.conn.host, session.conn.port).key, lastConnectedAt = me.saffer.sill.android.protocol.Wire.now()) }
                savedMacs = store.all()
            }
            // The menus' subscription, then what this device wants: its viewport.
            val sub = menuState.subscribe()
            session.sendJson(Kind.FETCH_MENU, Payloads.fetchMenu(sub.token))
            lastViewportJson = null
            sendViewport(immediately = true)
        }
        // Nothing streams: ask for the Desktop (at most every 10 s), unless the watched window just
        // closed, when the device waits 2 s for the person's own pick first.
        if (list.active == StreamSource.None) {
            val now = SystemClock.uptimeMillis()
            val watchedGone = was is StreamSource.Window && list.windows.none { it.id == was.id }
            if (now - lastAutoDesktopAt > 10_000) {
                if (!watchedGone) {
                    lastAutoDesktopAt = now
                    select(StreamSource.Desktop, user = false)
                } else {
                    val pickedBefore = lastUserPickAt
                    val wait = 2_000L + (stats?.rttMaxMs?.coerceAtLeast(0) ?: 0)
                    main.postDelayed({
                        if (this.session === session && active == StreamSource.None && lastUserPickAt == pickedBefore) {
                            lastAutoDesktopAt = SystemClock.uptimeMillis()
                            select(StreamSource.Desktop, user = false)
                        }
                    }, wait)
                }
            }
        }
        previousActive = was
        clipboard?.onWindowList(list) { bytes -> if (this.session === session) session.send(Kind.CLIPBOARD, bytes) }
        reviewIdle()
    }

    /** The cover over the stream; "Unlocking…" lasts LockedMac.UNLOCKING_TIMEOUT_S at most. */
    private fun setShade(next: LockedMac.Shade?) {
        shadeTimeout?.let(main::removeCallbacks); shadeTimeout = null
        lockShade = next
        if (next == LockedMac.Shade.Unlocking) {
            shadeTimeout = Runnable { if (lockShade == LockedMac.Shade.Unlocking) lockShade = null }
                .also { main.postDelayed(it, (LockedMac.UNLOCKING_TIMEOUT_S * 1000).toLong()) }
        }
    }

    private fun settingsState(state: HostSettingsState) {
        if (state.settings.sendAudio == false) sound?.stop()
        (sound as? me.saffer.sill.android.audio.MacSound)?.let { s ->
            state.stream?.fps?.let(s::setStreamFPS)
            state.away?.let { s.setAway(it.thisConnectionAway) }
        }
        val refused = ledger.receive(state)
        hasSettings = true
        hostState = state
        if (refused.isNotEmpty()) notice("$macName kept its setting.")
        refreshSettings()
        unsupportedSize?.let { (w, h) -> unsupportedSize = null; handleUnsupported(w, h) }
    }

    private fun refreshSettings() {
        shownSettings = ledger.shown
        settingsRevision++
    }

    private fun macInfo(session: Session, signed: SignedMacInfo) {
        val fp = MacInfoCheck.verify(signed.info, signed.key, signed.sig) ?: return
        if (!java.security.MessageDigest.isEqual(fp, session.conn.macFingerprint)) return
        val info = MacInfo.parse(signed.info) ?: return
        if (info.macID != MacId.make(fp)) return
        val mac = store.byID(info.macID) ?: return
        if (!java.security.MessageDigest.isEqual(mac.fingerprint, fp) || info.issuedAt <= mac.infoIssuedAt) return
        // Never link-local (169.254/16, all of fe80::/10, a scope) or loopback (SavedMacs.keepable).
        val usable = info.addresses.filter { a -> SavedMacs.keepable(a.host, allowLoopback = false) }
        store.update(info.macID) { m ->
            m.copy(
                name = SafeText.label(info.name, 64).ifEmpty { m.name },
                remotePort = if (info.remotePort in 1..65535) info.remotePort else m.remotePort,
                addresses = if (info.remoteAccess && usable.isNotEmpty()) usable else m.addresses,
                infoIssuedAt = info.issuedAt,
            )
        }
        savedMacs = store.all()
        currentMac = store.byID(info.macID)
    }

    private fun macPointerReport(p: MacPointer) {
        pointerHere = false
        val pos = p.position
        macPointer = pos?.let { Offset(it.first.toFloat(), it.second.toFloat()) }
        if (pos != null) pointerReseeds++
    }

    // MARK: VideoDecoder.Events (decoder thread)

    override fun onVideoSize(width: Int, height: Int) {
        // A new size can change the rate this device's decoder sustains: the viewport says it again.
        main.post { videoSize = width to height; sendViewport(immediately = false) }
    }

    override fun onUnsupportedSize(width: Int, height: Int) {
        main.post { handleUnsupported(width, height) }
    }

    override fun onPictureInterrupted() {
        main.post {
            if (session == null) return@post
            Log.i(TAG, "picture interrupted: waiting for a keyframe")
            recovery.interrupted(uptime())
            considerKeyframe()
        }
    }

    override fun onKeyframeShown() {
        main.post { recovery.pictureBack(uptime(), foreground) }
    }

    // MARK: the Mac's last picture, for its tile (ConnectionList, MacSnapshots)

    /**
     * The picture as the session leaves it, for the Mac's tile: only from the screen in front, for a
     * saved Mac, while tiles are on (MacSnapshots.save checks again, and never keeps a black frame).
     * Before the session's state goes, since the decoder's reset clears the surface.
     */
    private fun keepPicture() {
        val mac = currentMac ?: return
        if (openSession || !sessionReady) return
        if (!me.saffer.sill.android.net.ConnectionList.takesPreview(me.saffer.sill.android.net.ConnectionPrefs(app).style, foreground, store.byID(mac.macID) != null)) return
        val (w, _) = videoSize?.let { me.saffer.sill.android.net.ConnectionList.previewPixels(it.first, it.second) } ?: return
        decoder.capture(w) { bmp -> bmp?.let { me.saffer.sill.android.net.MacSnapshots.save(app, mac.macID, it) } }
    }

    // MARK: keyframe recovery (kind 44)

    private val recovery = KeyframeRecovery()
    private var keyframeCheck: Runnable? = null

    /** Ask the Mac for a keyframe when KeyframeRecovery says so, and look again when it is due. Main thread. */
    private fun considerKeyframe() {
        keyframeCheck?.let(main::removeCallbacks); keyframeCheck = null
        val s = session ?: return
        val streaming = sessionReady && active != StreamSource.None
        val now = uptime()
        val again = when (val d = recovery.consider(now, foreground, streaming, KeyframeRecovery.answerWait(stats?.rttMaxMs))) {
            KeyframeRecovery.Decision.Nothing -> null
            KeyframeRecovery.Decision.AskNow -> {
                Log.i(TAG, "asking the Mac for a keyframe (repeat ${recovery.repeats})")
                s.send(Kind.KEYFRAME_REQUEST)
                now + KeyframeRecovery.answerWait(stats?.rttMaxMs)
            }
            is KeyframeRecovery.Decision.AskAt -> d.at
            is KeyframeRecovery.Decision.KeyframeDue -> d.at
        } ?: return
        keyframeCheck = Runnable { considerKeyframe() }.also { main.postDelayed(it, ((again - now) * 1000).toLong().coerceAtLeast(50)) }
    }

    // MARK: holding the screen on (DeviceIdlePolicy)

    /** Whether the screen is held on now: streaming, on screen, used in the last 10 minutes. */
    var holdAwake by mutableStateOf(false); private set
    @Volatile private var inputAt = 0.0
    private var controlAt = 0.0
    private var sessionStartAt: Double? = null
    private var frontAt = 0.0
    private var idleCheck: Runnable? = null

    private fun uptime(): Double = SystemClock.elapsedRealtime() / 1000.0

    /** One of the stream screen's controls was used: counts as use. Main thread. */
    fun noteAction() { controlAt = uptime(); if (!holdAwake) reviewIdle() }

    /** Input the person sent to the Mac: any thread. */
    private fun noteInput() {
        inputAt = uptime()
        if (!holdAwake) main.post { reviewIdle() }
    }

    private fun reviewIdle() {
        idleCheck?.let(main::removeCallbacks); idleCheck = null
        val now = uptime()
        val last = listOf(inputAt, controlAt, frontAt, sessionStartAt ?: 0.0).maxOrNull()?.takeIf { it > 0 }
        val streaming = phase is Phase.Streaming && active != StreamSource.None
        val ends = DeviceIdlePolicy.holdEnds(foreground, streaming, last, now)
        holdAwake = ends != null
        if (ends != null) idleCheck = Runnable { reviewIdle() }.also { main.postDelayed(it, ((ends - now) * 1000).toLong() + 50) }
    }

    /** This device's decoder cannot take the stream (a Retina capture): ask the Mac for Standard. */
    private fun handleUnsupported(width: Int, height: Int) {
        val shown = ledger.shown
        if (shown == null) { unsupportedSize = width to height; return }
        if (shown.captureScale > 1.0) {
            notice("This device can’t decode ${width}×$height, so the Mac streams at Standard resolution.")
            changeSettings(SettingsChange(captureScale = 1.0))
        } else {
            notice("The stream is ${width}×$height, more than this device can decode. Pick a smaller window.")
        }
    }

    // MARK: what the screens do

    fun select(source: StreamSource, user: Boolean = true) {
        val s = session ?: return
        onCaretMayMove?.invoke()
        if (user) { lastUserPickAt = SystemClock.uptimeMillis(); noteAction() }
        s.sendJson(Kind.SELECT_SOURCE, Payloads.select(source))
    }

    fun launch(app: AppInfo) {
        onCaretMayMove?.invoke()
        lastUserPickAt = SystemClock.uptimeMillis(); noteAction()
        session?.sendJson(Kind.LAUNCH_APP, Payloads.launchApp(app.bundleID))
    }

    fun windowCommand(id: Long, action: String) {
        onCaretMayMove?.invoke()
        noteAction()
        session?.sendJson(Kind.WINDOW_COMMAND, Payloads.windowCommand(id, action))
    }

    /** The stream panel's size in dp, as Viewport's width and height (device points). */
    fun setPanel(widthDp: Double, heightDp: Double) {
        if (panel == widthDp to heightDp) return
        panel = widthDp to heightDp
        sendViewport(immediately = false)
    }

    fun chooseTextScale(scale: Double?) {
        noteAction()
        textScale = scale
        sendViewport(immediately = false)
    }

    private fun wantedFps(): Int {
        val away = hostState?.away?.thisConnectionAway ?: true
        val lowPower = (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        // Never more than this device's decoder sustains at the stream's size (an A15 does 1080p at 60).
        val decodable = videoSize?.let { (w, h) -> VideoDecoder.maxRate(w, h) } ?: 60
        val max = minOf(maxOf(30, displayFps), maxOf(30, decodable))
        return if (away || lowPower) minOf(60, max) else max
    }

    private fun sendViewport(immediately: Boolean) {
        viewportPending?.let(main::removeCallbacks); viewportPending = null
        val send = Runnable {
            val s = session ?: return@Runnable
            if (!sessionReady) return@Runnable
            val (w, h) = panel ?: return@Runnable
            val json = Payloads.viewport(w, h, textScale, wantedFps()) ?: return@Runnable
            if (json == lastViewportJson) return@Runnable
            lastViewportJson = json
            s.sendJson(Kind.VIEWPORT, json)
        }
        if (immediately) send.run() else { viewportPending = send; main.postDelayed(send, 250) }
    }

    fun changeSettings(change: SettingsChange) {
        noteAction()
        val s = session ?: return
        val (token, out) = ledger.pick(change, SystemClock.uptimeMillis()) ?: return
        s.sendJson(Kind.CHANGE_SETTINGS, Payloads.changeSettings(token, out))
        refreshSettings()
    }

    fun openMenu(item: MacMenuItem) {
        val s = session ?: return
        val r = menuState.open(item, SystemClock.uptimeMillis()) ?: return
        s.sendJson(Kind.FETCH_MENU, Payloads.fetchMenu(r.token, r.version, r.id, r.title))
        menuRevision++
    }

    fun chooseMenuItem(item: MacMenuItem) {
        onCaretMayMove?.invoke()
        noteAction()
        val s = session ?: return
        val r = menuState.choose(item, SystemClock.uptimeMillis()) ?: return
        s.sendJson(Kind.PRESS_MENU_ITEM, Payloads.pressMenuItem(r.token, r.version, r.id!!, r.title))
    }

    private fun timeoutMs(): Long = maxOf(4_000L, 4L * (stats?.rttMaxMs ?: 0))

    // MARK: input (kind 8), any thread

    /**
     * Something that may move the Mac's caret is about to go out (iOS's StreamClient.onCaretMayMove,
     * called before it goes): a click, a key going down, a shortcut, a gesture, a pick, a launch, a
     * window's button, a menu item. The stream screen's keyboard sink sends what an input method is
     * still composing, then forgets what was typed (KeyboardSink.forgetTyped). Main thread.
     */
    var onCaretMayMove: (() -> Unit)? = null

    /** Positions are frame fractions. `trackpad` says the trackpad drew this device's pointer. */
    fun pointer(action: PointerAction, x: Double, y: Double, trackpad: Boolean = false) {
        val s = session ?: return
        if (action == PointerAction.LEFT_DOWN || action == PointerAction.RIGHT_DOWN) onCaretMayMove?.invoke()
        noteInput()
        s.sendInput(Input.pointer(action, x, y), isMove = action == PointerAction.MOVE)
        tookPointer(x, y, trackpad)
    }

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) {
        noteInput()
        session?.sendInput(Input.scroll(x, y, dx, dy))
        tookPointer(x, y, false)
    }

    fun scrollPhase(phase: ScrollPhase, x: Double, y: Double) {
        session?.sendInput(Input.scrollGesture(phase, x, y))
    }

    fun text(t: String) {
        noteInput()
        session?.sendInput(Input.text(t))
        main.post { pointerHere = true; ownPointerVisible = false }
    }

    fun key(usage: Int, down: Boolean, modifiers: Long) {
        if (down) onCaretMayMove?.invoke()
        noteInput()
        session?.sendInput(Input.key(usage, down, modifiers))
    }

    /**
     * The clipboard's Paste (the chip's or the Settings row's): this device's text to the Mac, the
     * receipt's ping after it on this session, and ⌘V once the Mac has it (Paste on ‹Mac› Right Away).
     */
    fun pasteClipboard() {
        noteAction()
        val s = session ?: return
        (clipboard as? me.saffer.sill.android.clipboard.ClipboardSync)?.paste(
            worstRttMs = stats?.rttMaxMs,
            sendPing = { nonce -> if (session === s) s.send(Kind.PING, nonce) },
            pressKey = { usage, modifiers -> if (session === s) press(usage, modifiers) },
        )
    }

    /** A shortcut as a keyboard sends it: its modifiers' own keys around it (KeyChord.press). */
    fun press(usage: Int, modifiers: Long) {
        val s = session ?: return
        onCaretMayMove?.invoke()
        noteInput()
        val mods = Keys.modifierKeys(modifiers)
        var held = 0L
        for ((u, bit) in mods) { held = held or bit; s.sendInput(Input.key(u, true, held)) }
        s.sendInput(Input.key(usage, true, held))
        s.sendInput(Input.key(usage, false, held))
        for ((u, bit) in mods.reversed()) { held = held and bit.inv(); s.sendInput(Input.key(u, false, held)) }
    }

    fun modifiersDown(modifiers: Long) {
        val s = session ?: return
        onCaretMayMove?.invoke()
        var held = 0L
        for ((u, bit) in Keys.modifierKeys(modifiers)) { held = held or bit; s.sendInput(Input.key(u, true, held)) }
    }

    fun modifiersUp(modifiers: Long) {
        val s = session ?: return
        var held = modifiers
        for ((u, bit) in Keys.modifierKeys(modifiers).reversed()) { held = held and bit.inv(); s.sendInput(Input.key(u, false, held)) }
    }

    /** A three- or four-finger gesture for the Mac's own shortcut (kind 28); the Desktop first. */
    fun gesture(name: String, fingers: Int) {
        val s = session ?: return
        if (hostGestures < 1 || !gesturesEnabled) return
        onCaretMayMove?.invoke()
        noteInput()
        if (active is StreamSource.Window) s.sendJson(Kind.SELECT_SOURCE, Payloads.select(StreamSource.Desktop))
        s.sendJson(Kind.GESTURE, Payloads.gesture(name, fingers))
    }

    private fun tookPointer(x: Double, y: Double, trackpad: Boolean) {
        main.post {
            pointerHere = true
            ownPointerVisible = trackpad
            ownPointer = Offset(x.toFloat(), y.toFloat())
            if (!trackpad) macPointer = null
        }
    }

    /** Where the trackpad's cursor carries on from: the Mac's last position while it had the pointer. */
    fun pointerAnchor(): Offset = if (!pointerHere) macPointer ?: ownPointer else ownPointer

    fun notice(text: String) {
        notice = text
        main.removeCallbacks(clearNotice)
        main.postDelayed(clearNotice, 5_000)
    }

    private val clearNotice = Runnable { notice = null }

    private fun routeWord(host: String): String? = when {
        Endpoint.isTailscale(host) -> "through Tailscale"
        Endpoint.isLoopback(host) -> "on this device"
        homeMacs.any { it.host == host } || isPrivate(host) -> "Wi‑Fi"
        else -> null
    }

    private fun isPrivate(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4) return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
        return host.lowercase().startsWith("fe80:") || host.lowercase().startsWith("fd") && !Endpoint.isTailscale(host)
    }

    companion object {
        private const val TAG = "Sill"
        @Volatile private var instance: SillClient? = null
        fun get(context: Context): SillClient = instance ?: synchronized(this) {
            instance ?: SillClient(context.applicationContext).also { instance = it }
        }
    }
}
