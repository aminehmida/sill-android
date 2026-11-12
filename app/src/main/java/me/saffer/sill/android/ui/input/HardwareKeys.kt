package me.saffer.sill.android.ui.input

import me.saffer.sill.android.client.Keys
import me.saffer.sill.android.protocol.KeyMods

// A hardware keyboard's keys on their way to the Mac (iOSClient/KeyChords.swift's ForwardedKeys and
// TextComposition.swift's KeyTyping, #162). Pure: no Android types, unit tested on the JVM.

/**
 * Who types a hardware keyboard's characters: the Mac, which is sent them as keys (so it repeats a held
 * one at its own Delay Until Repeat and Key Repeat Rate, and its own layout and dead keys apply), or
 * this device's text path, which types what Android's key character map gives and sends it as text.
 * Asked as each key goes down; the key's up follows the way its down went ([ForwardedKeys]).
 */
enum class KeyTyping {
    /** An input method is composing: what it passed on goes after its composition, which goes first. */
    COMPOSING,
    /**
     * The text path types the characters, as before: for a Mac that repeats no key (a Sill for Mac from
     * before key repeat), for a keyboard layout in another script than the Latin alphabet (the layout
     * chosen on the device says which script is meant, and the Mac's own layout would not give it), for
     * the software keyboard's key events, and while the key row has a modifier latched (the character
     * then makes the latched shortcut). The keys the text path never delivers still go as keys.
     */
    TEXT,
    /** The Mac types them: the hardware keyboard's layout types the Latin alphabet directly. */
    KEYS;

    companion object {
        /** Whether the session's Mac repeats a key held down: its window list's `keyRepeat`, any generation from 1. */
        fun macRepeats(keyRepeat: Int?): Boolean = (keyRepeat ?: 0) >= 1

        /**
         * The typing for a key going down now. `latinLayout`: the hardware keyboard's layout types the
         * Latin alphabet ([latinLayout]); `softKeyboard`: the key came from an input method's key event.
         */
        fun of(composing: Boolean, latched: Boolean, macRepeats: Boolean, softKeyboard: Boolean, latinLayout: Boolean): KeyTyping {
            if (composing) return COMPOSING
            if (!macRepeats || latched || softKeyboard) return TEXT
            return if (latinLayout) KEYS else TEXT
        }

        /**
         * Whether a hardware keyboard's layout types the Latin alphabet directly, by what its letter keys
         * give unshifted (code points; 0 for none): every one a letter of the Latin script (QWERTY, AZERTY,
         * QWERTZ, Dvorak…), none Cyrillic, Greek, Arabic, Hebrew…
         */
        fun latinLayout(letters: List<Int>): Boolean = letters.isNotEmpty() && letters.all { cp ->
            cp > 0 && Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN
        }

        /**
         * Whether an input method's language (a BCP 47 tag, and its likely script) types the Latin
         * alphabet directly, so that what it composes may go to the Mac as it comes: written in the Latin
         * script, and not one whose Latin keyboard composes (Vietnamese, Hawaiian).
         */
        fun typesOnMac(languageTag: String?, script: String?): Boolean {
            if (languageTag.isNullOrEmpty() || script != "Latn") return false
            val language = languageTag.substringBefore('-').substringBefore('_').lowercase()
            return language !in COMPOSING_LATIN
        }

        /** The languages written in the Latin alphabet whose keyboards compose what is typed. */
        val COMPOSING_LATIN = setOf("vi", "haw")
    }
}

/**
 * A hardware keyboard's keys sent to the Mac as keys. A key goes to the Mac as a key, or to the text
 * path, when it goes down (by its usage, the modifiers and who types the characters then: [goesAsKey]),
 * and its up follows the same way whatever the modifiers or the typing say by then, so ⌘'s own release
 * and a key let go after ⌘ reach the Mac, and a key the text path took never sends a stray up.
 *
 * A key's down carries the modifiers the key event gives it (and Shift for a letter typed with Caps Lock
 * on: [carried]); a modifier's own key and every up carry only the modifier keys down on the Mac.
 */
class ForwardedKeys {
    /** One key event for the Mac. */
    data class Key(val usage: Int, val down: Boolean, val modifiers: Long)

    /** The keys whose down went to the Mac and whose up has not, in the order they went down. */
    private val downKeys = ArrayList<Int>()
    val down: List<Int> get() = downKeys

    fun isDown(usage: Int): Boolean = usage in downKeys

    /** A key pressed: its down, when it goes to the Mac as a key; null when the text path takes it. */
    fun began(usage: Int, modifiers: Long, typing: KeyTyping, capsLock: Boolean = false): Key? {
        if (!goesAsKey(usage, modifiers, typing)) return null
        if (usage !in downKeys) downKeys.add(usage)
        val isModifier = Keys.modifierBit(usage) != 0L
        return Key(usage, true, if (isModifier) held else carried(usage, modifiers, capsLock))
    }

    /** A key released, or its press cancelled: its up, when its down went to the Mac, with the modifier keys still down there. */
    fun ended(usage: Int): Key? {
        val i = downKeys.indexOf(usage)
        if (i < 0) return null
        downKeys.removeAt(i)
        return Key(usage, false, held)
    }

    /** Every key still down on the Mac going up, the last pressed first: the sink no longer takes keys. */
    fun releaseAll(): List<Key> {
        val ups = ArrayList<Key>()
        while (downKeys.isNotEmpty()) ended(downKeys.last())?.let { ups.add(it) }
        return ups
    }

    /** The modifier bits of the modifier keys down on the Mac. */
    val held: Long get() = downKeys.fold(0L) { m, u -> m or Keys.modifierBit(u) }

    companion object {
        /** The keys the text path never delivers ([Keys.KEY_USAGES]). */
        val KEY_USAGES: Set<Int> get() = Keys.KEY_USAGES
        /** Return and tab: with shift held they go as keys too. */
        val SHIFTED_KEY_USAGES: Set<Int> get() = Keys.SHIFTED_KEY_USAGES
        /** The keys a keyboard types characters with ([Keys.CHARACTER_USAGES]). */
        val CHARACTER_USAGES: Set<Int> get() = Keys.CHARACTER_USAGES
        /** The letters A to Z, by their keys' places. */
        val LETTER_USAGES: IntRange get() = Keys.LETTER_USAGES

        private const val SHORTCUT_MAKERS = KeyMods.CONTROL or KeyMods.OPTION or KeyMods.COMMAND

        /**
         * Whether a key pressed with these modifiers goes to the Mac as a key: one of [KEY_USAGES], return
         * or tab with shift held, any key while ⌘, ⌃ or ⌥ is held (shortcuts), and, while the Mac types
         * the characters, every key that types one.
         */
        fun goesAsKey(usage: Int, modifiers: Long, typing: KeyTyping): Boolean =
            usage in KEY_USAGES || modifiers and SHORTCUT_MAKERS != 0L ||
                (usage in SHIFTED_KEY_USAGES && modifiers and KeyMods.SHIFT != 0L) ||
                (typing == KeyTyping.KEYS && usage in CHARACTER_USAGES)

        /**
         * The modifiers a key's down carries: the key event's, and Shift with them for a letter typed with
         * Caps Lock on and neither ⌘, ⌃ nor ⌥ held, so the Mac types it in capitals (Caps Lock is this
         * device's own; its key stays the text path's). A shortcut keeps its modifiers as they are.
         */
        fun carried(usage: Int, modifiers: Long, capsLock: Boolean): Long {
            if (usage !in LETTER_USAGES || !capsLock || modifiers and SHORTCUT_MAKERS != 0L) return modifiers
            return modifiers or KeyMods.SHIFT
        }
    }
}
