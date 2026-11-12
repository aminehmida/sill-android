package me.saffer.sill.android.ui.input

import android.view.KeyEvent
import me.saffer.sill.android.client.Keys
import me.saffer.sill.android.protocol.KeyMods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KeyTyping and ForwardedKeys, after iOS's Tests/checks/key-strokes and text-composition (KeyTyping). */
class HardwareKeysTest {
    private val a = 0x04
    private val z = 0x1D
    private val shift = 0xE1
    private val command = 0xE3

    @Test fun whoTypes() {
        assertEquals(KeyTyping.COMPOSING, KeyTyping.of(composing = true, latched = false, macRepeats = true, softKeyboard = false, latinLayout = true))
        assertEquals("a Mac before key repeat", KeyTyping.TEXT, KeyTyping.of(false, false, macRepeats = false, softKeyboard = false, latinLayout = true))
        assertEquals("a latch on the key row", KeyTyping.TEXT, KeyTyping.of(false, latched = true, macRepeats = true, softKeyboard = false, latinLayout = true))
        assertEquals("the software keyboard", KeyTyping.TEXT, KeyTyping.of(false, false, true, softKeyboard = true, latinLayout = true))
        assertEquals("a Cyrillic layout", KeyTyping.TEXT, KeyTyping.of(false, false, true, false, latinLayout = false))
        assertEquals(KeyTyping.KEYS, KeyTyping.of(false, false, true, false, latinLayout = true))
        assertTrue(KeyTyping.macRepeats(1) && KeyTyping.macRepeats(2))
        assertFalse(KeyTyping.macRepeats(0) || KeyTyping.macRepeats(null))
    }

    @Test fun layoutsAndLanguages() {
        assertTrue("QWERTY", KeyTyping.latinLayout("aqwzm".map { it.code }))
        assertTrue("AZERTY", KeyTyping.latinLayout("qazwm".map { it.code }))
        assertFalse("Russian", KeyTyping.latinLayout("фйцяь".map { it.code }))
        assertFalse("Greek", KeyTyping.latinLayout("ας;ζμ".map { it.code }))
        assertFalse("no letters", KeyTyping.latinLayout(listOf(0, 0)))
        assertTrue(KeyTyping.typesOnMac("en-US", "Latn"))
        assertTrue(KeyTyping.typesOnMac("fr", "Latn"))
        assertFalse("Vietnamese composes", KeyTyping.typesOnMac("vi-VN", "Latn"))
        assertFalse(KeyTyping.typesOnMac("ja-JP", "Jpan"))
        assertFalse(KeyTyping.typesOnMac("zh-Hans-CN", "Hans"))
        assertFalse(KeyTyping.typesOnMac("ko", "Kore"))
        assertFalse(KeyTyping.typesOnMac("", "Latn"))
    }

    @Test fun whichKeysGoAsKeys() {
        for (u in listOf(0x4F, 0x29, 0x4C, 0x3A, 0x45, 0xE0, 0xE3, 0xE6)) assertTrue(ForwardedKeys.goesAsKey(u, 0, KeyTyping.TEXT))
        assertFalse("shift is the text's while it types", ForwardedKeys.goesAsKey(shift, 0, KeyTyping.TEXT))
        assertFalse("a letter is the text's", ForwardedKeys.goesAsKey(a, KeyMods.SHIFT, KeyTyping.TEXT))
        assertTrue("⌘ makes any key a key", ForwardedKeys.goesAsKey(a, KeyMods.COMMAND, KeyTyping.TEXT))
        assertTrue("⇧Return is a key", ForwardedKeys.goesAsKey(Keys.ENTER, KeyMods.SHIFT, KeyTyping.TEXT))
        assertFalse("Return is the text's", ForwardedKeys.goesAsKey(Keys.ENTER, 0, KeyTyping.TEXT))
        for (u in listOf(a, z, 0x27, Keys.ENTER, Keys.BACKSPACE, Keys.TAB, Keys.SPACE, 0x2D, 0x38, shift, 0xE5)) {
            assertTrue("while the Mac types: $u", ForwardedKeys.goesAsKey(u, 0, KeyTyping.KEYS))
        }
        for (u in listOf(0x32, 0x39, 0x64)) assertFalse("ISO extras and Caps Lock stay the text's: $u", ForwardedKeys.goesAsKey(u, 0, KeyTyping.KEYS))
    }

    @Test fun upsFollowTheirDowns() {
        val f = ForwardedKeys()
        // ⌘S: ⌘'s own key carries what is held; S carries ⌘; the ups carry what is still down.
        assertEquals(ForwardedKeys.Key(command, true, KeyMods.COMMAND), f.began(command, KeyMods.COMMAND, KeyTyping.TEXT))
        assertEquals(ForwardedKeys.Key(0x16, true, KeyMods.COMMAND), f.began(0x16, KeyMods.COMMAND, KeyTyping.TEXT))
        assertEquals("⌘ let go first", ForwardedKeys.Key(command, false, 0), f.ended(command))
        assertEquals("then S, with nothing held", ForwardedKeys.Key(0x16, false, 0), f.ended(0x16))
        assertNull("a key the text path took sends no up", f.ended(a))
        assertNull(f.began(a, 0, KeyTyping.TEXT))
        // While the Mac types: shift and a letter as keys.
        assertEquals(ForwardedKeys.Key(shift, true, KeyMods.SHIFT), f.began(shift, KeyMods.SHIFT, KeyTyping.KEYS))
        assertEquals(ForwardedKeys.Key(a, true, KeyMods.SHIFT), f.began(a, KeyMods.SHIFT, KeyTyping.KEYS))
        assertTrue(f.isDown(a))
        // Every key still down comes up, the last first.
        assertEquals(listOf(ForwardedKeys.Key(a, false, KeyMods.SHIFT), ForwardedKeys.Key(shift, false, 0)), f.releaseAll())
        assertTrue(f.down.isEmpty())
    }

    @Test fun capsLockCarriesShiftForLetters() {
        assertEquals(KeyMods.SHIFT, ForwardedKeys.carried(a, 0, capsLock = true))
        assertEquals("not a digit", 0L, ForwardedKeys.carried(0x1E, 0, capsLock = true))
        assertEquals("a shortcut keeps its modifiers", KeyMods.COMMAND, ForwardedKeys.carried(a, KeyMods.COMMAND, capsLock = true))
        assertEquals(0L, ForwardedKeys.carried(a, 0, capsLock = false))
    }

    @Test fun keysByTheirPlace() {
        // A German layout swaps Z and Y's key codes: the Mac gets the key's place.
        assertEquals("KEYCODE_Z on the key in Y's place (scan code 21)", 0x1C, Keys.usage(KeyEvent.KEYCODE_Z, 21))
        assertEquals("a French layout's A on Q's place (16)", 0x14, Keys.usage(KeyEvent.KEYCODE_A, 16))
        assertEquals("no scan code (the software keyboard): by the key code", z, Keys.usage(KeyEvent.KEYCODE_Z, 0))
        assertEquals("Caps Lock made Control stays Control", Keys.LEFT_CONTROL, Keys.usage(KeyEvent.KEYCODE_CTRL_LEFT, 58))
        assertEquals("an arrow by its key code", Keys.LEFT, Keys.usage(KeyEvent.KEYCODE_DPAD_LEFT, 105))
        assertEquals(Keys.ESCAPE, Keys.usage(KeyEvent.KEYCODE_ESCAPE, 1))
        // Every place maps to a usage the key-code table has too.
        for (scan in 1..60) Keys.usageForScanCode(scan)?.let { u -> assertTrue("scan $scan → $u", u in 0x04..0x38) }
    }
}
