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

    /** Keys that always go as keys, not text (KeyChords' keyUsages): arrows, esc, ⌦, home, end, pages, F-keys, modifiers. */
    fun alwaysKey(usage: Int): Boolean = usage in 0x4F..0x52 || usage == ESCAPE || usage == 0x4C || usage == 0x4A ||
        usage == 0x4D || usage == 0x4B || usage == 0x4E || usage in 0x3A..0x45 || usage in 0xE0..0xE7

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
