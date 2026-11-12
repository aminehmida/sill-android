package me.saffer.sill.android.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.Looper
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.saffer.sill.android.client.Keys
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.protocol.KeyMods

/**
 * Where typing goes (InputOverlay's text half): an invisible view that holds the keyboard, the soft one
 * and a hardware one, which reach the Mac while it has focus (it takes it as it joins the window, and
 * again when the window comes back, so a hardware keyboard types, Esc included, as soon as the picture
 * shows, without the Keyboard button).
 *
 * It is a text editor to the input method, so Chinese, Japanese and Korean input methods and voice typing
 * work here: what an input method composes stays on the device, shown over the picture ([composingText]),
 * and only the chosen text is typed on the Mac (TextComposition, [ImeDocument]); plain Latin typing still
 * reaches the Mac letter by letter, whether the keyboard commits each letter or composes the word. What was
 * typed since the Mac's caret last moved is kept, so a correction reaches the Mac as backspaces and new
 * text; a Return or a Tab typed makes what came before it final. Return, tab and delete go as "\n", "\t"
 * and "\u0008". With ⌃, ⌥ or ⌘ latched on the key row, a single character goes as that shortcut instead.
 *
 * Hardware keys ([ForwardedKeys], [KeyTyping]): arrows, esc, F-keys, ⌘ ⌃ ⌥ and anything with them go as
 * keys (HID usages); while the Mac repeats held keys (`hostKeyRepeat` 1 or more) and the hardware keyboard's
 * layout types the Latin alphabet, its letters, digits, punctuation, space, return, tab, delete and shift go
 * as keys too, down and up, and the Mac repeats a held one (Android's own repeats are dropped); otherwise
 * characters go as text, as before.
 */
@SuppressLint("ViewConstructor")
class KeyboardSink(context: Context, private val client: SillClient) : View(context) {
    var latched: () -> Long = { 0L }
    var spendLatch: () -> Unit = {}
    /** The keyboard came up or went down (by this view or the system). */
    var onKeyboardShown: ((Boolean) -> Unit)? = null

    /** What an input method is composing, to show over the picture (VideoPanel's `composing`); null when nothing is. */
    var composingText by mutableStateOf<String?>(null)
        private set

    /**
     * Hardware keys go to the Mac (HardwareKeyboardPolicy's takers): false while something open over the
     * stream takes the keyboard (a panel), so its keys reach nothing on the Mac and Esc falls through to
     * Back, which closes it. Keys down on the Mac come up when it turns false.
     */
    var takesKeys = true
        set(value) {
            if (field == value) return
            field = value
            if (!value) releaseKeys() else ensureFocus()
        }

    private val doc = ImeDocument()
    private val forwarded = ForwardedKeys()
    /** Each hardware key sent as a key, by its key code, to the usage its down went as. */
    private val keyUsages = HashMap<Int, Int>()
    /** A dead key typed on the text path, waiting for the character it accents. */
    private var pendingAccent = 0
    private var connection: SinkConnection? = null
    private var batch = 0
    private var trimQueued = false
    private var monitorToken: Int? = null

    private val imm: InputMethodManager get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun show() {
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, 0)
        onKeyboardShown?.invoke(true)
    }

    fun hide() {
        imm.hideSoftInputFromWindow(windowToken, 0)
        onKeyboardShown?.invoke(false)
    }

    /**
     * The Mac's caret may move some other way than typing (a click, a key, a pick, a gesture, another
     * source: StreamClient.onCaretMayMove): what is still composed goes to the Mac first, as it stands,
     * then what was typed is forgotten, and the input method is told. Any thread.
     */
    fun forgetTyped() {
        if (Looper.myLooper() != Looper.getMainLooper()) { post { forgetTyped() }; return }
        if (!doc.holdsAnything) return
        val composed = doc.composition.isComposing || doc.liveRegion != null
        send(doc.forget())
        composingText = null
        imeTold(restart = composed)
    }

    /** Every key sent as a key and not yet up goes up (InputOverlay: when the overlay stops taking keys). */
    fun releaseKeys() {
        for (k in forwarded.releaseAll()) client.key(k.usage, false, k.modifiers)
        keyUsages.clear()
    }

    private fun ensureFocus() {
        if (takesKeys && isAttachedToWindow && !hasFocus()) post { if (takesKeys && !hasFocus() && rootView.findFocus() == null) requestFocus() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { if (takesKeys && !hasFocus()) requestFocus() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) ensureFocus()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) { releaseKeys(); commitComposition() }
    }

    override fun onDetachedFromWindow() {
        releaseKeys()
        commitComposition()
        connection = null
        super.onDetachedFromWindow()
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A plain text field, so every input method and voice typing work; no suggestions or
        // autocorrection (what is typed is the Mac's), nothing learned from it.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // A composition from the last connection is the last input session's: it goes as it stands.
        if (doc.composition.isComposing || doc.liveRegion != null) { send(doc.unmark()); doc.finishComposingText() }
        composingText = null
        val sel = doc.composition.selectedRange
        outAttrs.initialSelStart = sel.location
        outAttrs.initialSelEnd = sel.end
        outAttrs.initialCapsMode = 0
        if (Build.VERSION.SDK_INT >= 30) outAttrs.setInitialSurroundingText(doc.composition.document)
        monitorToken = null
        batch = 0
        return SinkConnection().also { connection = it }
    }

    /** Android's input connection, over [doc]: a call from a connection no longer current changes nothing. */
    private inner class SinkConnection : BaseInputConnection(this@KeyboardSink, false) {
        private val current get() = connection === this

        override fun beginBatchEdit(): Boolean { if (current) batch++; return current }

        override fun endBatchEdit(): Boolean {
            if (!current) return false
            if (batch > 0) batch--
            if (batch == 0) report()
            return batch > 0
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (current) insertText(text?.toString() ?: "", viaKey = false)
            return current
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!current) return false
            val t = text?.toString() ?: ""
            // A latched ⌃, ⌥ or ⌘ and a keyboard that composes even one letter: the shortcut, and the
            // input method starts over (it never composed anything here).
            if (!doc.composition.isComposing && doc.liveRegion == null && latchedShortcut(t)) {
                imeTold(restart = true)
                return true
            }
            // Which kind a composition is matters only as it starts (asking the system costs a call).
            val starts = !doc.composition.isComposing && doc.liveRegion == null
            val out = doc.setComposingText(t, newCursorPosition, latinTyping = starts && latinTyping())
            edit(out)
            // Typed live (a Latin keyboard that composes): the latch is spent, as by a letter committed.
            if (out.isNotEmpty()) consumeLatch()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            if (current) edit(doc.setComposingRegion(start, end))
            return current
        }

        override fun finishComposingText(): Boolean {
            if (current) edit(doc.finishComposingText())
            return current
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (current) deleteBefore(beforeLength)
            return current
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (!current) return false
            val d = doc.composition.document
            var i = doc.composition.selectedRange.location.coerceIn(0, d.length)
            var units = 0
            repeat(beforeLength) { if (i > 0) { val n = Character.charCount(d.codePointBefore(i)); i -= n; units += n } else units++ }
            deleteBefore(units)
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (!current) return false
            doc.setSelection(start, end)
            report()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val d = doc.composition.document
            val s = doc.composition.selectedRange.location.coerceIn(0, d.length)
            return d.substring((s - n).coerceAtLeast(0), s)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            val d = doc.composition.document
            val e = doc.composition.selectedRange.end.coerceIn(0, d.length)
            return d.substring(e, (e + n).coerceAtMost(d.length))
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            val d = doc.composition.document
            val r = doc.composition.selectedRange
            if (r.length == 0) return null
            return d.substring(r.location.coerceIn(0, d.length), r.end.coerceIn(0, d.length))
        }

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            val d = doc.composition.document
            val r = doc.composition.selectedRange
            val start = (r.location - beforeLength).coerceAtLeast(0)
            val end = (r.end + afterLength).coerceAtMost(d.length)
            return SurroundingText(d.substring(start, end), r.location - start, r.end - start, start)
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            if (current && flags and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) monitorToken = request?.token
            return extracted()
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (!current) return false
            when (event.action) {
                KeyEvent.ACTION_DOWN -> handleKey(event, down = true)
                KeyEvent.ACTION_UP -> handleKey(event, down = false)
                KeyEvent.ACTION_MULTIPLE -> if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) event.characters?.let { insertText(it, viaKey = false) }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            if (current) insertText("\n", viaKey = true)
            return current
        }

        override fun performContextMenuAction(id: Int): Boolean = false
        override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

        override fun closeConnection() {
            // The input session ends (another field, the keyboard gone for good): what it composed goes
            // as it stands (super's finishComposingText, ours).
            super.closeConnection()
            if (current) connection = null
        }
    }

    // MARK: the text path

    /**
     * Text from the input method, or a key the text path takes (iOS's insertText): a latched ⌃, ⌥ or ⌘
     * makes a single character a shortcut, a latched ⇧ makes Return and Tab ⇧Return and ⇧Tab; otherwise
     * it goes to the document and from there to the Mac, and the latch is spent.
     */
    private fun insertText(text: String, viaKey: Boolean) {
        if (!doc.composition.isComposing && latchedShortcut(text)) return
        val mods = latched()
        if (!doc.composition.isComposing && mods and KeyMods.SHIFT != 0L && (text == "\n" || text == "\t")) {
            sendLatched(if (text == "\n") Keys.ENTER else Keys.TAB)
            return
        }
        edit(if (viaKey) doc.type(text) else doc.commitText(text))
        consumeLatch()
    }

    /** With ⌃, ⌥ or ⌘ latched, a single character with a key goes as that shortcut. True when it did. */
    private fun latchedShortcut(text: String): Boolean {
        if (latched() and SHORTCUT_MAKERS == 0L || Graphemes.count(text) != 1) return false
        val usage = if (text == "\u0008") Keys.BACKSPACE else Keys.usage(text[0]) ?: return false
        sendLatched(usage)
        return true
    }

    /** Backspace (iOS's deleteBackward): a shortcut with ⌃, ⌥ or ⌘ latched, else one character deleted. */
    private fun deleteBackward() {
        if (!doc.composition.isComposing && latched() and SHORTCUT_MAKERS != 0L) { sendLatched(Keys.BACKSPACE); return }
        edit(doc.deleteBackward())
        consumeLatch()
    }

    /** The input method's deleteSurroundingText: its backspace (a shortcut with ⌃, ⌥ or ⌘ latched). */
    private fun deleteBefore(units: Int) {
        if (units <= 0) return
        if (!doc.composition.isComposing && latched() and SHORTCUT_MAKERS != 0L) { sendLatched(Keys.BACKSPACE); return }
        edit(doc.deleteSurroundingText(units))
        consumeLatch()
    }

    /** One key, down and up, carrying the latched modifiers, then the latch is spent. */
    private fun sendLatched(usage: Int) {
        forgetTyped()
        client.press(usage, latched())
        consumeLatch()
    }

    private fun consumeLatch() {
        if (latched() != 0L) spendLatch()
    }

    /** What an edit leaves for the Mac goes; the composition label and the input method follow. */
    private fun edit(outputs: List<TextComposition.Output>) {
        send(outputs)
        composingText = doc.composition.marked
        if (batch == 0) report()
        if (doc.composition.wantsTrim && !trimQueued) {
            trimQueued = true
            post {
                trimQueued = false
                if (doc.composition.wantsTrim) { doc.composition.trim(); imeTold(restart = true) }
            }
        }
    }

    private fun send(outputs: List<TextComposition.Output>) {
        for (o in outputs) when (o) {
            is TextComposition.Output.Text -> client.text(o.text)
            is TextComposition.Output.Backspaces -> repeat(o.count) { client.text("\u0008") }
        }
    }

    /** The composition goes to the Mac as it stands (the sink lets go of the keyboard, or a key goes mid-composition). */
    private fun commitComposition() {
        if (!doc.composition.isComposing && doc.liveRegion == null) return
        send(doc.unmark())
        doc.finishComposingText()
        composingText = null
        imeTold(restart = true)
    }

    /**
     * The document changed without the input method asking (a forget, a trim, a composition sent): told,
     * by a restart when it may still think it composes, else by the new selection.
     */
    private fun imeTold(restart: Boolean) {
        if (restart) {
            connection = null
            if (isAttachedToWindow) post { if (hasFocus()) imm.restartInput(this) }
        } else report()
    }

    /** The input method hears where the caret and the composition are now. */
    private fun report() {
        if (!isAttachedToWindow) return
        val sel = doc.composition.selectedRange
        val comp = doc.composingSpan
        imm.updateSelection(this, sel.location, sel.end, comp?.location ?: -1, comp?.end ?: -1)
        monitorToken?.let { imm.updateExtractedText(this, it, extracted()) }
    }

    private fun extracted(): ExtractedText = ExtractedText().apply {
        val r = doc.composition.selectedRange
        text = doc.composition.document
        startOffset = 0
        partialStartOffset = -1
        partialEndOffset = -1
        selectionStart = r.location
        selectionEnd = r.end
    }

    /** Whether the input method in use types the Latin alphabet directly (so what it composes goes as it comes). */
    private fun latinTyping(): Boolean {
        val subtype = imm.currentInputMethodSubtype ?: return true
        if (subtype.mode == "voice") return false
        @Suppress("DEPRECATION")
        val tag = subtype.languageTag.ifEmpty { subtype.locale.replace('_', '-') }
        if (tag.isEmpty()) return true
        val script = runCatching { ULocale.addLikelySubtags(ULocale.forLanguageTag(tag)).script }.getOrNull()
        return KeyTyping.typesOnMac(tag, script)
    }

    // MARK: hardware keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, down = true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, down = false) || super.onKeyUp(keyCode, event)

    /** A hardware key, or one an input method sends as a key event. */
    fun handleKey(e: KeyEvent, down: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        if (!down) {
            val usage = keyUsages.remove(e.keyCode)
            if (usage != null) forwarded.ended(usage)?.let { k -> client.key(k.usage, false, k.modifiers); return true }
            return takesKeys && Keys.usage(e.keyCode) != null
        }
        if (!takesKeys) return false
        val soft = e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || e.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0
        val usage = if (soft) Keys.usage(e.keyCode) else Keys.usage(e.keyCode, e.scanCode)
        val mods = Keys.modifiers(e.metaState)
        val capsLock = Keys.capsLock(e.metaState)
        val macRepeats = KeyTyping.macRepeats(client.hostKeyRepeat)
        // Android's own repeat of a key held down on the Mac: the Mac repeats it itself (KeyRepeat); an
        // older Mac gets each repeat as a down again, as before.
        keyUsages[e.keyCode]?.takeIf { forwarded.isDown(it) }?.let { held ->
            if (e.repeatCount > 0) {
                if (!macRepeats) client.key(held, true, if (Keys.modifierBit(held) != 0L) forwarded.held else ForwardedKeys.carried(held, mods, capsLock))
                return true
            }
        }
        // An input method that passes a key on mid-composition: its composition goes first, as it stands.
        if (doc.composition.isComposing || doc.liveRegion != null) commitComposition()
        val typing = KeyTyping.of(
            composing = false, latched = latched() != 0L, macRepeats = macRepeats,
            softKeyboard = soft, latinLayout = !soft && latinLayout(e),
        )
        if (usage != null) {
            forwarded.began(usage, mods, typing, capsLock)?.let { k ->
                keyUsages[e.keyCode] = usage
                pendingAccent = 0
                forgetTyped()
                client.key(k.usage, true, k.modifiers)
                return true
            }
        }
        // The text path.
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { pendingAccent = 0; insertText("\n", viaKey = true); return true }
            KeyEvent.KEYCODE_TAB -> { pendingAccent = 0; insertText("\t", viaKey = true); return true }
            KeyEvent.KEYCODE_DEL -> { pendingAccent = 0; deleteBackward(); return true }
        }
        val ch = e.getUnicodeChar(e.metaState)
        if (ch == 0) return usage != null
        if (ch and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // A dead key: it accents the next character, as a text field's would.
            pendingAccent = ch and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        val accent = pendingAccent
        pendingAccent = 0
        val text = if (accent != 0) {
            val composed = KeyCharacterMap.getDeadChar(accent, ch)
            if (composed != 0) String(Character.toChars(composed)) else String(Character.toChars(accent)) + String(Character.toChars(ch))
        } else String(Character.toChars(ch))
        insertText(text, viaKey = true)
        return true
    }

    /** Whether the hardware keyboard's layout types the Latin alphabet: what a few letter keys give. */
    private fun latinLayout(e: KeyEvent): Boolean {
        val map = e.keyCharacterMap ?: return false
        return KeyTyping.latinLayout(PROBE_KEYS.map { map.get(it, 0) and KeyCharacterMap.COMBINING_ACCENT_MASK })
    }

    private companion object {
        const val SHORTCUT_MAKERS = KeyMods.CONTROL or KeyMods.OPTION or KeyMods.COMMAND
        val PROBE_KEYS = intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_M).toList()
    }
}
