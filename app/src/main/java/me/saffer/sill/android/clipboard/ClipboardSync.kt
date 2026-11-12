package me.saffer.sill.android.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.saffer.sill.android.BuildConfig
import me.saffer.sill.android.client.ClipboardBridge
import me.saffer.sill.android.crypto.SafeText
import me.saffer.sill.android.protocol.KeyMods
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.protocol.WindowList
import java.security.SecureRandom

/**
 * A moment's line after a Paste: "Pasted on ‹Mac›.", "Sent to ‹Mac›’s clipboard…", "Too long to send…"
 * or "Didn’t reach…", in the chip's place and under the Settings group's Paste. `at`: when it came
 * (elapsedRealtime, s), so two of the same words in a row are two notes.
 */
data class ClipboardNote(val text: String, val spoken: String, val warning: Boolean, val at: Double)

/**
 * The clipboard, this device's side (kind 32; iOSClient's StreamClient+Clipboard, docs/clipboard-plan.md).
 * ClipboardPolicy has the rules; this reads and writes Android's clipboard and keeps the session's state.
 *
 * - The Mac's text: written as plain text marked as Sill's (an extra naming when it was written) and as
 *   a remote device's (Android 13's EXTRA_IS_REMOTE_DEVICE, for apps that carry clipboards across
 *   devices: iOS writes it `.localOnly`); never offered back to the Mac; cleared at a look 10 minutes
 *   later if still there (iOS's expiration; Android has none of its own). Nothing is marked sensitive,
 *   as iOS marks nothing concealed. Android 13 and later show their own confirmation of each write.
 * - This device's text: Sill reads only the clip's description (its time, its types, Sill's marker),
 *   which never brings Android 12's "pasted from your clipboard" toast, to offer the chip; the text is
 *   read only when the person taps Paste (the chip's, or the Settings row's), and counts as sent only
 *   once the Mac has read it (a ping after it comes back: the receipt). Since Android 10 only the app in
 *   front reads the clipboard, so the looks come when the window gains focus (`onFocusChanged`), at a
 *   session's start soon after, and on each change while Sill is in front (OnPrimaryClipChangedListener).
 * - Paste right away: with Paste on ‹Mac› Right Away on, the receipt also presses ⌘V on the Mac a moment
 *   later (ClipboardPolicy.pastesOnReceipt, PASTE_DELAY): this device presses it, as a key, through the
 *   `pressKey` the Paste hands over; the Mac only writes the text.
 * - The subscription: `{}` once the Mac's list says it takes the clipboard and the switch is on, `stop`
 *   when the switch goes off.
 *
 * The clip's time (ClipDescription.getTimestamp, ms) stands for iOS's changeCount: a clip without one
 * (0) is never offered by the chip, as nothing tells its copies apart; the Settings row's Paste still
 * sends it. Everything here is main thread.
 */
class ClipboardSync(context: Context) : ClipboardBridge {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val board: ClipboardManager? = app.getSystemService(ClipboardManager::class.java)
    private val a11y: AccessibilityManager? = app.getSystemService(AccessibilityManager::class.java)
    private val prefs = app.getSharedPreferences("sill", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    // MARK: what the screens show (Compose state)

    /** The chip "Paste on ‹Mac›" is offered (ClipboardPolicy.offersChip) until Paste, Dismiss or its time. */
    var chip by mutableStateOf(false); private set
    /** The note after a Paste, for NOTE_LIFETIME; the chip's place shows it over the chip. */
    var note by mutableStateOf<ClipboardNote?>(null); private set
    /** The Mac's last list says it takes and sends the clipboard (and it is not locked): the Settings group shows. */
    var shared by mutableStateOf(false); private set
    /** This device's clipboard had text at the last look: the Settings row's Paste is enabled by it. */
    var deviceHasText by mutableStateOf(false); private set
    /** Paste on ‹Mac› Right Away: on unless turned off; this device's own, never sent. */
    var pasteRightAway by mutableStateOf(prefs.getBoolean(RIGHT_AWAY_KEY, true)); private set
    /** Bumped each time the Mac's text lands on this device's clipboard (iOS shows nothing for it). */
    var fromMac by mutableIntStateOf(0); private set
    /** Android 13 and later confirm every write to the clipboard themselves: Sill adds no word of its own. */
    val systemConfirmsCopies: Boolean = Build.VERSION.SDK_INT >= 33

    fun changePasteRightAway(on: Boolean) {
        pasteRightAway = on
        prefs.edit().putBoolean(RIGHT_AWAY_KEY, on).apply()
    }

    // MARK: ClipboardBridge

    override var enabled: Boolean = true
        set(value) {
            field = value
            updateSubscription()
            if (!value) hideChip()
        }

    // The session's.
    private var send: ((ByteArray) -> Unit)? = null
    private val connected: Boolean get() = send != null
    private var hostClipboard: Int? = null
    private var subscribed = false
    private var streaming = false
    private var macName = ""
    private val macWord: String get() = macName.ifEmpty { "the Mac" }
    private var lastSent: ClipboardPolicy.Sent? = null

    /** A Paste waiting for its receipt: the ping's random payload, the clip's time when the text went. */
    private class Receipt(val nonce: ByteArray, val changeCount: Long, val pressKey: (Int, Long) -> Unit) {
        /** Its time ran out ("Didn’t reach…" shown, the chip offered again); a receipt that comes later still counts. */
        var late = false
    }
    private var receipt: Receipt? = null
    private var receiptExpiry: Runnable? = null
    private var pasteWork: Runnable? = null
    private var noteExpiry: Runnable? = null

    // Sill in front, and the chip's time.
    private var focused = false
    private var focusReported = false
    private var activatedAt: Double? = null
    private var chipOnScreen = false
    private var chipShownFor = 0.0
    private var chipCountingSince: Double? = null
    private var chipExpiry: Runnable? = null
    private var chipAnnounced = false
    /** The clip the chip was offered for, should its Dismiss find the clipboard unreadable. */
    private var chipStamp = 0L
    private var expiryCheck: Runnable? = null

    /** The clip's time Sill last handled (sent, written from the Mac, the chip dismissed or timed out), kept across launches. */
    private var handled: Long?
        get() = if (prefs.contains(HANDLED_KEY)) prefs.getLong(HANDLED_KEY, 0L) else null
        set(value) { prefs.edit().apply { if (value == null) remove(HANDLED_KEY) else putLong(HANDLED_KEY, value) }.apply() }

    init {
        // Delivered only while Sill may read the clipboard (in front, Android 10's rule), on the main thread.
        board?.addPrimaryClipChangedListener { look("the clipboard changed") }
        a11y?.addTouchExplorationStateChangeListener { updateChipClock() }
    }

    override fun onWindowList(list: WindowList, send: (ByteArray) -> Unit) {
        this.send = send
        macName = SafeText.label(list.macName, 64)
        streaming = list.active != StreamSource.None
        val value = ClipboardPolicy.hostClipboard(list.clipboard, list.locked)
        val took = ClipboardPolicy.hostTakes(hostClipboard)
        hostClipboard = value
        val takes = ClipboardPolicy.hostTakes(value)
        shared = takes
        updateSubscription()
        if (!takes) hideChip()
        if (!took && takes && ClipboardPolicy.looksAtSessionStart(activatedAt, uptime())) look("the session started")
    }

    override fun onMessage(payload: ByteArray) {
        val now = glance()
        val text = ClipboardPolicy.deviceWrites(payload, enabled, hostClipboard, lastSent, now?.stamp ?: UNKNOWN) ?: return
        val b = board ?: return
        val writtenAt = System.currentTimeMillis()
        val clip = ClipData.newPlainText(LABEL, text)
        clip.description.extras = PersistableBundle().apply {
            putLong(MARKER, writtenAt)
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true)
        }
        try {
            b.setPrimaryClip(clip)
        } catch (e: RuntimeException) {
            log("clipboard: the Mac's text could not be written (${e.javaClass.simpleName})")
            return
        }
        // Its time becomes the handled one while Sill may read it; the marker says so at any later look.
        glance()?.let { g -> if (g.ownWrittenAt == writtenAt && g.stamp > 0) handled = g.stamp }
        deviceHasText = true
        fromMac++
        hideChip()
        expiryCheck?.let(main::removeCallbacks)
        expiryCheck = Runnable { expiryCheck = null; look("the Mac's text may have expired") }
            .also { main.postDelayed(it, (ClipboardPolicy.DEVICE_EXPIRY * 1000).toLong() + 1_000) }
        log("clipboard: wrote ${ClipboardPolicy.utf8Length(text)} bytes from the Mac")
    }

    override fun onSessionEnded() {
        send = null
        hostClipboard = null
        shared = false
        subscribed = false
        streaming = false
        lastSent = null
        receipt = null
        receiptExpiry?.let(main::removeCallbacks); receiptExpiry = null
        pasteWork?.let(main::removeCallbacks); pasteWork = null       // a ⌘V for this session never goes to the next
        hideChip()
        chipOnScreen = false
        noteExpiry?.let(main::removeCallbacks); noteExpiry = null
        note = null
    }

    /**
     * Focus comes just after this (`onFocusChanged`), and the look with it. Without focus reports (not
     * wired), a second later Sill takes itself as in front for good: a poorer stand-in.
     */
    override fun onForeground() {
        if (focusReported) return
        main.postDelayed({ if (!focusReported) { focused = true; becameActive() } }, 1_000)
    }

    // MARK: Sill in front

    /**
     * The activity's window gained or lost focus (Activity.onWindowFocusChanged): iOS's becoming active
     * and resigning. Only with focus may Sill read the clipboard (Android 10), so the chip looks then, and
     * its time runs only then.
     */
    fun onFocusChanged(hasFocus: Boolean) {
        val first = !focusReported
        focusReported = true
        if (hasFocus == focused && !first) return
        // The first report replaces the stand-in's guess, and its look is not one twice.
        if (first) activatedAt = null
        focused = hasFocus
        updateChipClock()
        if (hasFocus) becameActive()
    }

    private fun becameActive() {
        val now = uptime()
        // A dialog closing and the window's focus coming back in a moment: one look.
        activatedAt?.let { if (now - it < 0.5) return }
        activatedAt = now
        look("Sill became active")
    }

    // MARK: The subscription

    private fun updateSubscription() {
        val (step, after) = ClipboardPolicy.deviceSubscription(hostClipboard, enabled, subscribed)
        subscribed = after
        when (step) {
            ClipboardPolicy.Subscription.SUBSCRIBE -> { send?.invoke(ClipboardPolicy.subscription); log("clipboard: subscribed") }
            ClipboardPolicy.Subscription.STOP -> { send?.invoke(ClipboardPolicy.stopping); log("clipboard: stopped") }
            ClipboardPolicy.Subscription.NONE -> Unit
        }
    }

    // MARK: The chip

    /** What the clipboard holds, read without its text: nil while Sill may not read it (not in front). */
    private class Glance(val stamp: Long, val hasText: Boolean, val ownWrittenAt: Long?)

    private fun glance(): Glance? {
        if (!focused) return null
        val d = (try { board?.primaryClipDescription } catch (e: RuntimeException) { return null }) ?: return Glance(0L, false, null)
        val own = d.extras?.getLong(MARKER, 0L)?.takeIf { it > 0 }
        val text = d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        return Glance(d.timestamp, text, own)
    }

    /** Offers the chip when ClipboardPolicy says; reads only the clip's description. */
    private fun look(why: String) {
        val g = glance() ?: return
        deviceHasText = g.hasText
        val own = g.ownWrittenAt
        if (own != null) {
            // The Mac's text, written here: never offered back, and gone once it is 10 minutes old.
            if (g.stamp > 0 && handled != g.stamp) handled = g.stamp
            if (ClipboardPolicy.ownTextExpired(own, System.currentTimeMillis())) {
                try { board?.clearPrimaryClip() } catch (ignored: RuntimeException) { }
                deviceHasText = false
                log("clipboard: $why: the Mac's text expired, cleared")
            }
            return
        }
        // Not while a Paste waits for its receipt: the chip comes back if it never comes.
        val waiting = receipt?.let { !it.late } ?: false
        val offer = g.stamp > 0 && !waiting &&
            ClipboardPolicy.offersChip(connected, hostClipboard, enabled, g.hasText, g.stamp, handled)
        log("clipboard: $why: clip at ${g.stamp}, handled ${handled ?: "none"}${if (waiting) ", a Paste waiting for its receipt" else ""}: ${if (offer) "the chip" else "no chip"}")
        if (!offer || chip) return
        chip = true
        chipStamp = g.stamp
        chipShownFor = 0.0
        chipAnnounced = false
        updateChipClock()
    }

    /**
     * Whether the chip itself is on screen (ClipboardChip reports it): its time runs only then, and
     * TalkBack is told the first time it shows for a copy.
     */
    fun chipShown(onScreen: Boolean) {
        if (onScreen == chipOnScreen) return
        chipOnScreen = onScreen
        if (onScreen && chip && !chipAnnounced) {
            chipAnnounced = true
            announce(ClipboardPolicy.Copy.chipAnnouncement(macWord))
        }
        updateChipClock()
    }

    /** Dismiss, or its time on screen was up: handled for the clip on the clipboard now. */
    fun dismissChip() {
        if (!chip) return
        handled = glance()?.stamp?.takeIf { it > 0 } ?: chipStamp
        hideChip()
    }

    private fun hideChip() {
        chipExpiry?.let(main::removeCallbacks); chipExpiry = null
        chipCountingSince = null
        if (chip) chip = false
    }

    /** The chip's CHIP_LIFETIME, counted only while ClipboardPolicy.chipCounts says, from what it has spent. */
    private fun updateChipClock() {
        val now = uptime()
        chipCountingSince?.let { chipShownFor += now - it; chipCountingSince = null }
        chipExpiry?.let(main::removeCallbacks); chipExpiry = null
        if (!chip) return
        val talkBack = a11y?.let { it.isEnabled && it.isTouchExplorationEnabled } == true
        if (!ClipboardPolicy.chipCounts(chipOnScreen, focused, talkBack)) return
        val left = ClipboardPolicy.CHIP_LIFETIME - chipShownFor
        if (left <= 0) { dismissChip(); return }
        chipCountingSince = now
        chipExpiry = Runnable { chipExpiry = null; log("clipboard: the chip's time is up"); dismissChip() }
            .also { main.postDelayed(it, (left * 1000).toLong()) }
    }

    // MARK: Paste on the Mac

    /**
     * The chip's or the Settings row's Paste, on the person's tap: reads this device's clipboard (Android
     * 12 and later say so in a toast, as iOS's Paste control is the person's consent) and sends its text
     * to the Mac as kind 32 on the session's connection, then the receipt, a ping of RECEIPT_LENGTH random
     * bytes through `sendPing` on the same connection. When the receipt's pong comes (`onReceipt`) with
     * Paste on ‹Mac› Right Away on, ⌘V goes through `pressKey` (a key with its modifiers' own keys around
     * it: SillClient.press). `worstRttMs`: the worst recent round trip, for how long the receipt is awaited.
     */
    fun paste(worstRttMs: Int?, sendPing: (ByteArray) -> Unit, pressKey: (usage: Int, modifiers: Long) -> Unit) {
        val clip = try { board?.primaryClip } catch (e: RuntimeException) { null }
        val strings = clip?.let { c -> (0 until c.itemCount).mapNotNull { i -> textOf(c.getItemAt(i)) } }.orEmpty()
        when (val step = ClipboardPolicy.deviceSends(strings, connected, hostClipboard, enabled)) {
            is ClipboardPolicy.DeviceSend.Send -> {
                val out = send ?: return
                out(step.payload)
                // Its receipt: until it comes nothing says it was sent, and the copy is not dealt with.
                val nonce = ByteArray(ClipboardPolicy.RECEIPT_LENGTH).also(random::nextBytes)
                sendPing(nonce)
                val count = clip?.description?.timestamp ?: 0L
                strings.firstOrNull { it.isNotEmpty() }?.let { lastSent = ClipboardPolicy.Sent(it, count) }
                receipt = Receipt(nonce, count, pressKey)
                hideChip()
                receiptExpiry?.let(main::removeCallbacks)
                val wait = ClipboardPolicy.receiptWait(worstRttMs)
                receiptExpiry = Runnable { receiptLate() }.also { main.postDelayed(it, (wait * 1000).toLong()) }
                log("clipboard: sent ${step.payload.size} bytes of JSON to the Mac, its receipt awaited for $wait s")
            }
            ClipboardPolicy.DeviceSend.TooLong ->
                showNote(ClipboardPolicy.Copy.tooLong(macWord), ClipboardPolicy.Copy.tooLong(macWord), warning = true)
            ClipboardPolicy.DeviceSend.Nothing -> log("clipboard: Paste: nothing to send")
        }
    }

    /**
     * A pong of RECEIPT_LENGTH bytes (a ping's own are 8): when it is this Paste's, the Mac has read the
     * text and the copy is dealt with; ⌘V follows with Paste on ‹Mac› Right Away on. Main thread.
     */
    fun onReceipt(payload: ByteArray) {
        val r = receipt ?: return
        if (!r.nonce.contentEquals(payload)) return
        receiptExpiry?.let(main::removeCallbacks); receiptExpiry = null
        receipt = null
        if (r.changeCount > 0) handled = r.changeCount
        hideChip()
        log("clipboard: the Mac has read it (its receipt${if (r.late) ", after its time ran out)" else ")"}")
        if (!pastesNow(r.late)) { receiptNoted(pasted = false); return }
        pasteOnMac(r.pressKey)
    }

    /** ClipboardPolicy.pastesOnReceipt: the switch, a receipt in time, something streaming, the Mac still sharing (no tour here). */
    private fun pastesNow(late: Boolean): Boolean =
        ClipboardPolicy.pastesOnReceipt(pasteRightAway, late, streaming, hostClipboard, tourShows = false)

    /** ⌘V on the Mac, PASTE_DELAY after the receipt, then "Pasted on ‹Mac›."; the session's end cancels it. */
    private fun pasteOnMac(pressKey: (Int, Long) -> Unit) {
        pasteWork?.let(main::removeCallbacks)
        val work = Runnable {
            pasteWork = null
            if (!connected || !pastesNow(late = false)) { receiptNoted(pasted = false); return@Runnable }
            pressKey(ClipboardPolicy.PASTE_KEY, KeyMods.COMMAND)
            log("clipboard: ⌘V sent to the Mac")
            receiptNoted(pasted = true)
        }
        pasteWork = work
        main.postDelayed(work, (ClipboardPolicy.PASTE_DELAY * 1000).toLong())
    }

    private fun receiptNoted(pasted: Boolean) {
        val (text, spoken) = ClipboardPolicy.receiptNote(pasted, macWord)
        showNote(text, spoken, warning = false)
    }

    /** No receipt in time: "Didn’t reach…", and the copy not dealt with, so the chip offers it again. */
    private fun receiptLate() {
        receiptExpiry = null
        val r = receipt ?: return
        if (r.late) return
        r.late = true
        showNote(ClipboardPolicy.Copy.notSent(macWord), ClipboardPolicy.Copy.notSent(macWord), warning = true)
        log("clipboard: no receipt in time: did not reach the Mac")
        look("a Paste did not reach the Mac")
    }

    private fun showNote(text: String, spoken: String, warning: Boolean) {
        note = ClipboardNote(text, spoken, warning, uptime())
        announce(spoken)
        noteExpiry?.let(main::removeCallbacks)
        noteExpiry = Runnable { noteExpiry = null; note = null }
            .also { main.postDelayed(it, (ClipboardPolicy.NOTE_LIFETIME * 1000).toLong()) }
    }

    /** Told to TalkBack, as iOS posts its announcements (the note may show where no view of it is). */
    @Suppress("DEPRECATION")
    private fun announce(words: String) {
        val m = a11y ?: return
        if (!m.isEnabled) return
        val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        e.text.add(words)
        e.packageName = app.packageName
        e.className = javaClass.name
        try { m.sendAccessibilityEvent(e) } catch (ignored: IllegalStateException) { }
    }

    /** An item's text (rich text's plain text, as iOS sends); a link's address; never a file's or content's URI. */
    private fun textOf(item: ClipData.Item): String? {
        item.text?.let { return it.toString() }
        val uri = item.uri ?: return null
        return if (uri.scheme == "content" || uri.scheme == "file" || uri.scheme == "android.resource") null else uri.toString()
    }

    private fun uptime(): Double = SystemClock.elapsedRealtime() / 1000.0

    private fun log(line: String) { if (BuildConfig.DEBUG) Log.i(TAG, line) }

    companion object {
        private const val TAG = "Sill"
        private const val RIGHT_AWAY_KEY = "pasteRightAway"
        private const val HANDLED_KEY = "clipboardHandled"
        /** On every clip Sill writes from the Mac's text, its value when it was written (wall clock, ms). */
        const val MARKER = "me.saffer.sill.clipboard"
        private const val LABEL = "Sill"
        /** A clip's time while Sill may not read the clipboard: never one Sill sent. */
        private const val UNKNOWN = -1L
    }
}
