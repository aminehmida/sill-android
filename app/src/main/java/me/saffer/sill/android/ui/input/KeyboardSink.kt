package me.saffer.sill.android.ui.input

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import me.saffer.sill.android.client.Keys
import me.saffer.sill.android.client.SillClient
import me.saffer.sill.android.protocol.KeyMods

/**
 * Where typing goes (InputOverlay's text half): an invisible view that holds the soft keyboard. Typed
 * text reaches the Mac as text (Input.text), return, tab and delete as "\n", "\t" and "\u0008".
 * An IME that composes (Samsung Keyboard, Gboard's suggestions) is followed by its difference, so
 * the Mac sees the same letters the field would hold. With ⌃, ⌥ or ⌘ latched on the key row, a
 * single letter goes as that shortcut instead. Hardware keys: arrows, esc, F-keys and anything with
 * a modifier go as keys (HID usages); the rest as text.
 */
@SuppressLint("ViewConstructor")
class KeyboardSink(context: Context, private val client: SillClient) : View(context) {
    var latched: () -> Long = { 0L }
    var spendLatch: () -> Unit = {}
    /** The keyboard came up or went down (by this view or the system). */
    var onKeyboardShown: ((Boolean) -> Unit)? = null

    private var composing = ""
    private val heldUsages = HashMap<Int, Int>()   // keyCode → usage, for keys sent as keys

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun show() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(this)
        imm.showSoftInput(this, 0)
        onKeyboardShown?.invoke(true)
    }

    fun hide() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
        onKeyboardShown?.invoke(false)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible password: no suggestions, no autocorrect, letters committed as typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        composing = ""
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (composing.isNotEmpty()) { replaceComposing(t); composing = "" } else type(t)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                replaceComposing(text?.toString() ?: "")
                return true
            }

            override fun finishComposingText(): Boolean { composing = ""; return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { type("\u0008") }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKey(event, down = true)
                else if (event.action == KeyEvent.ACTION_UP) handleKey(event, down = false)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean { type("\n"); return true }
        }
    }

    /** The IME replaced its composing text: send the difference (backspaces, then new letters). */
    private fun replaceComposing(new: String) {
        var p = 0
        while (p < composing.length && p < new.length && composing[p] == new[p]) p++
        repeat(composing.length - p) { client.text("\u0008") }
        if (p < new.length) client.text(new.substring(p))
        composing = new
    }

    private fun type(t: String) {
        if (t.isEmpty()) return
        val mods = latched() and (KeyMods.CONTROL or KeyMods.OPTION or KeyMods.COMMAND)
        if (mods != 0L && t.length == 1) {
            val usage = if (t == "\u0008") Keys.BACKSPACE else Keys.usage(t[0])
            if (usage != null) {
                client.press(usage, latched())
                spendLatch()
                return
            }
        }
        client.text(t)
        if (latched() != 0L) spendLatch()
    }

    /** Every key sent as a key and not yet up goes up (InputOverlay: when the overlay stops taking keys). */
    fun releaseKeys() {
        if (heldUsages.isEmpty()) return
        var held = heldUsages.values.fold(0L) { m, u -> m or Keys.modifierBit(u) }
        for ((_, u) in heldUsages.entries.toList().sortedBy { Keys.modifierBit(it.value) != 0L }) {
            held = held and Keys.modifierBit(u).inv()
            client.key(u, false, held)
        }
        heldUsages.clear()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) releaseKeys()
    }

    override fun onDetachedFromWindow() {
        releaseKeys()
        super.onDetachedFromWindow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, down = true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, down = false) || super.onKeyUp(keyCode, event)

    /** A hardware key (or one an IME sends as a key event). */
    fun handleKey(e: KeyEvent, down: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        val usage = Keys.usage(e.keyCode)
        val mods = Keys.modifiers(e.metaState)
        val chord = mods and (KeyMods.CONTROL or KeyMods.OPTION or KeyMods.COMMAND) != 0L
        if (!down) {
            heldUsages.remove(e.keyCode)?.let { u ->
                val held = if (Keys.modifierBit(u) != 0L) mods and Keys.modifierBit(u).inv() else mods
                client.key(u, false, held)
                return true
            }
            return usage != null
        }
        if (usage != null && (Keys.alwaysKey(usage) || chord)) {
            // Shift alone goes with the text it changes, as on iOS.
            if (usage == 0xE1 || usage == 0xE5) return true
            heldUsages[e.keyCode] = usage
            client.key(usage, true, mods)
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { type("\n"); return true }
            KeyEvent.KEYCODE_TAB -> { type("\t"); return true }
            KeyEvent.KEYCODE_DEL -> { type("\u0008"); return true }
        }
        val ch = e.getUnicodeChar(e.metaState)
        if (ch != 0 && ch and KeyCharacterMapCombining == 0) {
            type(String(Character.toChars(ch)))
            return true
        }
        return usage != null
    }

    private companion object {
        const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
