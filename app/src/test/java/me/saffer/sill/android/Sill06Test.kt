package me.saffer.sill.android

import me.saffer.sill.android.client.DeviceIdlePolicy
import me.saffer.sill.android.client.KeyframeRecovery
import me.saffer.sill.android.client.SettingsLedger
import me.saffer.sill.android.protocol.HostSettingsState
import me.saffer.sill.android.protocol.LockedMac
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.ScrollDirection
import me.saffer.sill.android.protocol.SettingsChange
import me.saffer.sill.android.protocol.StreamSource
import me.saffer.sill.android.protocol.TextScale
import me.saffer.sill.android.protocol.ViewportBounds
import me.saffer.sill.android.protocol.WindowList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Sill for Mac 0.6 added to the wire, and the rules ported with it (Sources/StreamProtocol, iOSClient). */
class Sill06Test {
    private fun list(json: String) = WindowList.parse(json.toByteArray())!!

    @Test fun helloSaysWhatThisDevicePlaysAndShows() {
        val o = JSONObject(Payloads.hello("0.6.1", "7", "Pixel (P)", audio = listOf("aac-eld"), offConsoleView = 1))
        assertEquals("aac-eld", o.getJSONArray("audio").getString(0))
        assertEquals(1, o.getInt("offConsoleView"))
        // Without sound, no `audio` key at all: a host then sends no kind 43.
        assertFalse(JSONObject(Payloads.hello("0.6.1", "7", "Pixel (P)")).has("audio"))
    }

    @Test fun windowListFieldsOf06() {
        val l = list("""{"macName":"Mac","windows":[],"active":{"none":{}},"clipboard":1,"keyRepeat":1,"locked":true,"offConsole":true,"offConsoleView":true}""")
        assertEquals(1, l.clipboard); assertEquals(1, l.keyRepeat)
        assertEquals(LockedMac.Why.OTHER_USER, LockedMac.why(l))
        assertTrue(LockedMac.view(l))
        val old = list("""{"macName":"Mac","windows":[],"active":{"none":{}}}""")
        assertNull(old.clipboard); assertNull(old.keyRepeat); assertNull(LockedMac.why(old)); assertFalse(LockedMac.view(old))
        assertEquals(LockedMac.Why.SCREEN_LOCKED, LockedMac.why(list("""{"macName":"Mac","windows":[],"active":{"none":{}},"locked":true}""")))
    }

    @Test fun lockedMacCover() {
        // Another user: covered; the off-console view: not; a locked screen streams its lock screen: no cover.
        assertEquals(LockedMac.Shade.Locked(LockedMac.Why.OTHER_USER), LockedMac.shade(null, LockedMac.Why.OTHER_USER, view = false))
        assertNull(LockedMac.shade(null, LockedMac.Why.OTHER_USER, view = true))
        assertNull(LockedMac.shade(null, LockedMac.Why.SCREEN_LOCKED, view = false))
        // After a cover, "Unlocking…" until a keyframe.
        val unlocking = LockedMac.shade(LockedMac.Shade.Locked(LockedMac.Why.OTHER_USER), null, view = false)
        assertEquals(LockedMac.Shade.Unlocking, unlocking)
        assertEquals(LockedMac.Shade.Unlocking, LockedMac.shadeAfterFrame(unlocking, keyframe = false))
        assertNull(LockedMac.shadeAfterFrame(unlocking, keyframe = true))
        assertTrue(LockedMac.detail(LockedMac.Shade.Locked(LockedMac.Why.SCREEN_LOCKED), paired = true)!!.startsWith("To unlock it from here"))
        assertEquals("The Mac is locked", LockedMac.title("", LockedMac.Shade.Locked(LockedMac.Why.SCREEN_LOCKED)))
    }

    @Test fun settingsOf06() {
        val st = HostSettingsState.parse(
            """{"settings":{"maxFPS":60,"bitrate":15000000,"captureScale":2,"prioritizeSpeed":false,"virtualDisplay":true,"sendAudio":true},
               "persistent":true,"virtualDisplayAvailable":true,"softwareEncoder":false,"audioNote":"Couldn’t capture","sessionPaired":false}""".toByteArray(),
        )!!
        assertEquals(true, st.settings.sendAudio); assertEquals("Couldn’t capture", st.audioNote); assertEquals(false, st.sessionPaired)
        assertEquals("""{"sendAudio":false,"token":3}""", JSONObject(Payloads.changeSettings(3, SettingsChange(sendAudio = false))).let { o ->
            "{\"sendAudio\":${o.getBoolean("sendAudio")},\"token\":${o.getInt("token")}}"
        })
        // The ledger sends Send Audio only to a Mac that reported it.
        val ledger = SettingsLedger()
        ledger.receive(st)
        val (_, out) = ledger.pick(SettingsChange(sendAudio = false), 0)!!
        assertEquals(false, out.sendAudio)
        val old = SettingsLedger()
        old.receive(HostSettingsState.parse("""{"settings":{"maxFPS":60,"bitrate":15000000,"captureScale":2,"prioritizeSpeed":false,"virtualDisplay":true},"persistent":true,"virtualDisplayAvailable":true,"softwareEncoder":false}""".toByteArray())!!)
        assertNull(old.pick(SettingsChange(sendAudio = true), 0))
    }

    @Test fun statsCarrySoundOnlyOnceItPlayed() {
        assertFalse(JSONObject(Payloads.clientStats(60, 20, 5, "d", 30, 8)).has("audioBehindMs"))
        val o = JSONObject(Payloads.clientStats(60, 20, 5, "d", 30, 8, audioBehindMs = -1, audioLate = 2))
        assertEquals(-1, o.getInt("audioBehindMs")); assertEquals(2, o.getInt("audioLate"))
    }

    @Test fun viewportHeldToWhatAHostTakes() {
        assertNull(Payloads.viewport(0.0, 400.0, null, 60))
        assertNull(ViewportBounds.held(Double.NaN, 1.0, null))
        val o = JSONObject(Payloads.viewport(1e9, 0.5, 10.0, 60)!!)
        assertEquals(16384.0, o.getDouble("width"), 0.0); assertEquals(1.0, o.getDouble("height"), 0.0); assertEquals(4.0, o.getDouble("scale"), 0.0)
        assertFalse(JSONObject(Payloads.viewport(800.0, 600.0, -1.0, 60)!!).has("scale"))
    }

    @Test fun textScale() {
        assertEquals(listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5), TextScale.detents(offered = true))
        assertEquals(1.0, TextScale.position(null, offered = false), 0.0)
        assertEquals(TextScale.OFF, TextScale.position(null, offered = true), 0.0)
        assertNull(TextScale.scaleAt(TextScale.OFF))
        assertEquals("1.25×", TextScale.title(1.25)); assertEquals("1×", TextScale.title(1.0)); assertEquals("Off", TextScale.title(TextScale.OFF))
        assertEquals(0.5, TextScale.snap(0.3, offered = false), 0.0)
        // A window on the virtual display: no Off; in place with the setting on: Off is back.
        val w = StreamSource.Window(3)
        assertFalse(TextScale.offersOff(true, w, onVirtualDisplay = true))
        assertTrue(TextScale.offersOff(true, w, onVirtualDisplay = false))
        assertFalse(TextScale.offersOff(true, StreamSource.Desktop, onVirtualDisplay = null))
        assertTrue(TextScale.selectable(w)); assertFalse(TextScale.selectable(StreamSource.Desktop))
    }

    @Test fun scrollDirection() {
        assertEquals(-1.0, ScrollDirection.factor(ScrollDirection.Source.FINGER, reversed = true), 0.0)
        assertEquals(-1.0, ScrollDirection.factor(ScrollDirection.Source.PAD, reversed = true), 0.0)
        assertEquals(1.0, ScrollDirection.factor(ScrollDirection.Source.WHEEL, reversed = true), 0.0)
        assertEquals(1.0, ScrollDirection.factor(ScrollDirection.Source.FINGER, reversed = false), 0.0)
    }

    @Test fun keyframeRecovery() {
        val r = KeyframeRecovery()
        assertEquals(KeyframeRecovery.Decision.Nothing, r.consider(0.0, active = true, streaming = true, wait = 4.0))
        r.interrupted(10.0)
        assertEquals(KeyframeRecovery.Decision.AskNow, r.consider(10.0, true, true, 4.0))
        // Out and due: no second ask until the wait passes, then a repeat at twice the spacing.
        assertEquals(KeyframeRecovery.Decision.KeyframeDue(14.0), r.consider(11.0, true, true, 4.0))
        assertEquals(KeyframeRecovery.Decision.AskNow, r.consider(14.5, true, true, 4.0))
        assertEquals(1, r.repeats)
        r.pictureBack(15.0, active = true)
        // A loss within 10 s of the picture coming back is a repeat: 8 s spacing now.
        r.interrupted(16.0)
        assertEquals(2, r.repeats)
        assertEquals(KeyframeRecovery.Decision.AskAt(14.5 + 8.0), r.consider(16.0, true, true, 4.0))
        // Not on screen: nothing; the trip away starts the spacing again.
        assertEquals(KeyframeRecovery.Decision.Nothing, r.consider(17.0, active = false, streaming = true, wait = 4.0))
        r.leftForeground()
        assertEquals(0, r.repeats)
        assertEquals(KeyframeRecovery.Decision.AskNow, r.consider(17.0, true, true, 4.0))
        assertEquals(6.0, KeyframeRecovery.answerWait(1500), 0.0)
        assertEquals(4.0, KeyframeRecovery.answerWait(null), 0.0)
    }

    @Test fun deviceIdle() {
        assertEquals(700.0, DeviceIdlePolicy.holdEnds(foreground = true, streaming = true, lastUse = 100.0, now = 200.0)!!, 0.0)
        assertNull(DeviceIdlePolicy.holdEnds(true, true, lastUse = 100.0, now = 701.0))
        assertNull(DeviceIdlePolicy.holdEnds(false, true, 100.0, 200.0))
        assertNull(DeviceIdlePolicy.holdEnds(true, false, 100.0, 200.0))
        // The automatic reconnect never counts as use.
        assertEquals(5.0, DeviceIdlePolicy.sessionStart(previous = 5.0, reconnected = true, now = 50.0))
        assertEquals(50.0, DeviceIdlePolicy.sessionStart(previous = 5.0, reconnected = false, now = 50.0))
    }
}
