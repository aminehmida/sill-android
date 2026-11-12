package me.saffer.sill.android.client

import android.view.KeyEvent
import me.saffer.sill.android.protocol.KeyMods

/**
 * Keys as the Mac takes them (Input.swift's `.key`): USB HID keyboard usages with
 * UIKeyModifierFlags bits (iOSClient/KeyChords.swift, PortraitStreamScreen's HIDKey).
 */
object Keys {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val LEFT_CONTROL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_OPTION = 0xE2
    const val LEFT_COMMAND = 0xE3

    /** The modifier keys a set of flags holds, in the order a keyboard presses them: ⌃ ⇧ ⌥ ⌘. */
    fun modifierKeys(mods: Long): List<Pair<Int, Long>> = buildList {
        if (mods and KeyMods.CONTROL != 0L) add(LEFT_CONTROL to KeyMods.CONTROL)
        if (mods and KeyMods.SHIFT != 0L) add(LEFT_SHIFT to KeyMods.SHIFT)
        if (mods and KeyMods.OPTION != 0L) add(LEFT_OPTION to KeyMods.OPTION)
        if (mods and KeyMods.COMMAND != 0L) add(LEFT_COMMAND to KeyMods.COMMAND)
    }

    /** A hardware key event's modifiers. The PC keyboard's Windows (Meta) key is ⌘, Alt is ⌥. */
    fun modifiers(meta: Int): Long {
        var m = 0L
        if (meta and KeyEvent.META_SHIFT_ON != 0) m = m or KeyMods.SHIFT
        if (meta and KeyEvent.META_CTRL_ON != 0) m = m or KeyMods.CONTROL
        if (meta and KeyEvent.META_ALT_ON != 0) m = m or KeyMods.OPTION
        if (meta and KeyEvent.META_META_ON != 0) m = m or KeyMods.COMMAND
        return m
    }

    /** The modifier bit a modifier key's own usage stands for. */
    fun modifierBit(usage: Int): Long = when (usage) {
        0xE0, 0xE4 -> KeyMods.CONTROL
        0xE1, 0xE5 -> KeyMods.SHIFT
        0xE2, 0xE6 -> KeyMods.OPTION
        0xE3, 0xE7 -> KeyMods.COMMAND
        else -> 0L
    }

    /** Caps Lock is on: a state of this device's keyboard, never a key held down for the Mac. */
    fun capsLock(meta: Int): Boolean = meta and KeyEvent.META_CAPS_LOCK_ON != 0

    /**
     * The keys the text path never delivers, which go to the Mac as keys whatever is held (KeyChords'
     * ForwardedKeys.keyUsages): the arrows, escape, forward delete, home, end, page up and down, F1 to
     * F12, and ⌘, ⌃ and ⌥ themselves (so a click while one is held is a modified click on the Mac).
     * Shift is not one: it changes the character typed, which the text path already has.
     */
    val KEY_USAGES: Set<Int> = setOf(
        0x4F, 0x50, 0x51, 0x52,                                                   // right, left, down, up
        0x29, 0x4C, 0x4A, 0x4D, 0x4B, 0x4E,                                       // escape, forward delete, home, end, page up and down
        0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45,   // F1…F12
        0xE0, 0xE2, 0xE3, 0xE4, 0xE6, 0xE7,                                       // control, option, command, left and right
    )

    /** Return and tab: with shift held they go as keys too ("\n" and "\t" as text carry no shift). */
    val SHIFTED_KEY_USAGES: Set<Int> = setOf(ENTER, TAB)

    /**
     * The keys a keyboard types characters with, which go to the Mac as keys while the Mac types them
     * (KeyChords' characterUsages): the letters, the digits, Return, Delete, Tab, Space, the punctuation
     * keys every layout has (- = [ ] \ ; ' ` , . /) and Shift's two keys. The keypad, the ISO keyboards'
     * extra keys and Caps Lock stay the text path's whoever types.
     */
    val CHARACTER_USAGES: Set<Int> = (0x04..0x28).toSet() + setOf(BACKSPACE, TAB, SPACE) + (0x2D..0x31) + (0x33..0x38) + setOf(0xE1, 0xE5)

    /** The letters A to Z, by their keys' places. */
    val LETTER_USAGES = 0x04..0x1D

    /** Keys that always go as keys, not text ([KEY_USAGES]). */
    fun alwaysKey(usage: Int): Boolean = usage in KEY_USAGES

    fun usage(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> 0x27
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xE0
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xE1
        KeyEvent.KEYCODE_ALT_LEFT -> 0xE2
        KeyEvent.KEYCODE_META_LEFT -> 0xE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE4
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xE5
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xE6
        KeyEvent.KEYCODE_META_RIGHT -> 0xE7
        else -> null
    }

    /**
     * A hardware key's place on the keyboard (its Linux scan code, as the kernel maps a USB or Bluetooth
     * keyboard's HID usages) back to that usage, for the keys whose characters a layout decides: the
     * letters, digits and punctuation. Android's layouts (German, French…) remap those keys' key codes
     * (Z and Y swapped, A and Q…), but the Mac takes a key by its place, as from a keyboard plugged into
     * it, and its own layout makes the character (iOS sends UIKey.keyCode, the place, too).
     */
    fun usageForScanCode(scanCode: Int): Int? = SCAN_USAGES[scanCode]

    /** A key event's usage: by its place for a character key ([usageForScanCode]), else by its key code (so a remapped modifier stays what the person made it). */
    fun usage(keyCode: Int, scanCode: Int): Int? {
        val byKey = usage(keyCode)
        val byPlace = usageForScanCode(scanCode)
        return if (byKey != null && byPlace != null && placed(byKey) && placed(byPlace)) byPlace else byKey
    }

    /** The keys a layout gives their characters: letters, digits and the punctuation keys. */
    private fun placed(usage: Int): Boolean = usage in 0x04..0x27 || usage in 0x2D..0x38

    private val SCAN_USAGES: Map<Int, Int> = buildMap {
        // KEY_1…KEY_9, KEY_0
        for (i in 0..8) put(2 + i, 0x1E + i)
        put(11, 0x27)
        put(12, 0x2D); put(13, 0x2E)                    // - =
        // KEY_Q…KEY_P
        listOf(0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, 0x12, 0x13).forEachIndexed { i, u -> put(16 + i, u) }
        put(26, 0x2F); put(27, 0x30)                    // [ ]
        // KEY_A…KEY_L
        listOf(0x04, 0x16, 0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F).forEachIndexed { i, u -> put(30 + i, u) }
        put(39, 0x33); put(40, 0x34); put(41, 0x35)     // ; ' `
        put(43, 0x31)                                   // \
        // KEY_Z…KEY_M
        listOf(0x1D, 0x1B, 0x06, 0x19, 0x05, 0x11, 0x10).forEachIndexed { i, u -> put(44 + i, u) }
        put(51, 0x36); put(52, 0x37); put(53, 0x38)     // , . /
    }

    /** A typed character's key on a US keyboard, for a latched ⌃, ⌥ or ⌘ (HIDKey.usage(for:)). */
    fun usage(ch: Char): Int? {
        val c = ch.lowercaseChar()
        return when (c) {
            in 'a'..'z' -> 0x04 + (c - 'a')
            in '1'..'9' -> 0x1E + (c - '1')
            '0' -> 0x27
            '\n' -> ENTER
            '\t' -> TAB
            ' ' -> SPACE
            '-' -> 0x2D; '=' -> 0x2E; '[' -> 0x2F; ']' -> 0x30; '\\' -> 0x31
            ';' -> 0x33; '\'' -> 0x34; '`' -> 0x35; ',' -> 0x36; '.' -> 0x37; '/' -> 0x38
            else -> null
        }
    }
}
