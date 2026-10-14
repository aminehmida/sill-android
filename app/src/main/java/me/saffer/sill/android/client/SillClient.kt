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

    // Home: the Macs Bonjour lists on this network, and a pairing ask waiting for its code.
    var homeMacs by mutableStateOf(listOf<me.saffer.sill.android.net.HomeMac>()); private set
    private val discovery = me.saffer.sill.android.net.HomeDiscovery(app) { homeMacs = it }
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
    private var lossAt = 0L
    private var reconnectAttempt = 0
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
        if (!reconnecting) { reconnectAttempt = 0; lossAt = 0 }
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
                val conn = Dialer.dial(endpoints, Tls.SESSION_ALPN, if (anyKey) null else mac.fingerprint)
                val hello = Payloads.hello(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), deviceLabel)
                val s = Session(conn, deviceLabel, hello, this)
                main.post {
                    if (gen != generation) { s.close("superseded"); return@post }
                    session = s
                    route = routeWord(conn.host)
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
        if (lossAt != 0L && reason != DialException.Reason.WRONG_MAC && scheduleReconnect(mac)) return
        phase = Phase.Idle
        status = message
    }

    fun disconnect() {
        userClosed = true
        generation++
        resumeMac = null
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
        suspendTask?.let(main::removeCallbacks); suspendTask = null
        session?.resetLiveness()
        discovery.start()
        resumeMac?.let { mac -> resumeMac = null; connect(store.byID(mac.macID) ?: mac, anyKey = openSession) }
    }

    /** The app left the screen: stop browsing (the session itself goes after its grace). */
    fun onStopped() {
        foreground = false
        discovery.stop()
        onBackground()
    }

    private fun scheduleReconnect(mac: SavedMac): Boolean {
        val since = SystemClock.elapsedRealtime() - lossAt
        if (since > 120_000) return false
        val delay = when (reconnectAttempt) { 0 -> 2_000L; 1 -> 4_000L; 2 -> 8_000L; else -> 10_000L }
        reconnectAttempt++
        phase = Phase.Connecting(mac.name, true)
        status = "Reconnecting to ${mac.name}…"
        val gen = ++generation
        main.postDelayed({ if (gen == generation) connect(store.byID(mac.macID) ?: mac, reconnecting = true, anyKey = openSession) }, delay)
        return true
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
    }

    // MARK: Session.Listener

    override fun onMessage(session: Session, msg: Message) {
        when (msg.kind) {
            Kind.FRAME -> {
                session.noteFrame(msg.timestamp)
                decoder.onFrame(msg.payload, msg.isKeyframe, msg.timestamp)
            }
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
            endSessionState()
            if (userClosed || mac == null) return@post
            val g = goodbye
            val name = mac.name
            val (words, reconnect) = goodbyePolicy(g, name, atHome = homeOf(mac) != null)
            Log.i(TAG, "session ended (${why ?: "closed"}), goodbye=${g?.reason}, reconnect=$reconnect")
            if (g?.reason == "removed") store.update(mac.macID) { it.copy(revoked = true) }.also { savedMacs = store.all() }
            if (reconnect) {
                if (lossAt == 0L || wasStreaming) { lossAt = SystemClock.elapsedRealtime(); reconnectAttempt = 0 }
                if (scheduleReconnect(mac)) { status = words ?: "Reconnecting to $name…"; return@post }
            }
            phase = Phase.Idle
            status = words ?: "Lost the connection to $name."
        }
    }

    override fun onStats(session: Session, stats: LinkStats) {
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
        val ids = list.windows.map { it.id }.toSet()
        thumbnails.keys.retainAll(ids)
        val was = active
        active = list.active
        if (first) {
            sessionReady = true
            phase = Phase.Streaming
            status = null
            lossAt = 0; reconnectAttempt = 0
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
    }

    private fun settingsState(state: HostSettingsState) {
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
        val usable = info.addresses.filter { a ->
            !Endpoint.isLoopback(a.host) && !a.host.contains('%') && !a.host.lowercase().startsWith("fe80:")
        }
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
        if (user) lastUserPickAt = SystemClock.uptimeMillis()
        s.sendJson(Kind.SELECT_SOURCE, Payloads.select(source))
    }

    fun launch(app: AppInfo) {
        lastUserPickAt = SystemClock.uptimeMillis()
        session?.sendJson(Kind.LAUNCH_APP, Payloads.launchApp(app.bundleID))
    }

    fun windowCommand(id: Long, action: String) {
        session?.sendJson(Kind.WINDOW_COMMAND, Payloads.windowCommand(id, action))
    }

    /** The stream panel's size in dp, as Viewport's width and height (device points). */
    fun setPanel(widthDp: Double, heightDp: Double) {
        if (panel == widthDp to heightDp) return
        panel = widthDp to heightDp
        sendViewport(immediately = false)
    }

    fun chooseTextScale(scale: Double?) {
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
            val json = Payloads.viewport(w, h, textScale, wantedFps())
            if (json == lastViewportJson) return@Runnable
            lastViewportJson = json
            s.sendJson(Kind.VIEWPORT, json)
        }
        if (immediately) send.run() else { viewportPending = send; main.postDelayed(send, 250) }
    }

    fun changeSettings(change: SettingsChange) {
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
        val s = session ?: return
        val r = menuState.choose(item, SystemClock.uptimeMillis()) ?: return
        s.sendJson(Kind.PRESS_MENU_ITEM, Payloads.pressMenuItem(r.token, r.version, r.id!!, r.title))
    }

    private fun timeoutMs(): Long = maxOf(4_000L, 4L * (stats?.rttMaxMs ?: 0))

    // MARK: input (kind 8), any thread

    /** Positions are frame fractions. `trackpad` says the trackpad drew this device's pointer. */
    fun pointer(action: PointerAction, x: Double, y: Double, trackpad: Boolean = false) {
        val s = session ?: return
        s.sendInput(Input.pointer(action, x, y), isMove = action == PointerAction.MOVE)
        tookPointer(x, y, trackpad)
    }

    fun scroll(x: Double, y: Double, dx: Double, dy: Double) {
        session?.sendInput(Input.scroll(x, y, dx, dy))
        tookPointer(x, y, false)
    }

    fun scrollPhase(phase: ScrollPhase, x: Double, y: Double) {
        session?.sendInput(Input.scrollGesture(phase, x, y))
    }

    fun text(t: String) {
        session?.sendInput(Input.text(t))
        main.post { pointerHere = true; ownPointerVisible = false }
    }

    fun key(usage: Int, down: Boolean, modifiers: Long) {
        session?.sendInput(Input.key(usage, down, modifiers))
    }

    /** A shortcut as a keyboard sends it: its modifiers' own keys around it (KeyChord.press). */
    fun press(usage: Int, modifiers: Long) {
        val s = session ?: return
        val mods = Keys.modifierKeys(modifiers)
        var held = 0L
        for ((u, bit) in mods) { held = held or bit; s.sendInput(Input.key(u, true, held)) }
        s.sendInput(Input.key(usage, true, held))
        s.sendInput(Input.key(usage, false, held))
        for ((u, bit) in mods.reversed()) { held = held and bit.inv(); s.sendInput(Input.key(u, false, held)) }
    }

    fun modifiersDown(modifiers: Long) {
        val s = session ?: return
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
