package me.saffer.sill.android

import me.saffer.sill.android.protocol.Kind
import me.saffer.sill.android.protocol.Payloads
import me.saffer.sill.android.protocol.TextFocus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Kind 64 (Sources/StreamProtocol/TextFocus.swift): the Mac's focused text field, and the hello's opt-in. */
class TextFocusTest {
    private fun parse(json: String) = TextFocus.parse(json.toByteArray())

    @Test fun kindIs64() = assertEquals(64, Kind.TEXT_FOCUS)

    @Test fun helloAsksForIt() {
        assertEquals(1, JSONObject(Payloads.hello("0.6.2", "8", "Fold (F)", textFocus = TextFocus.GENERATION)).getInt("textFocus"))
        assertFalse(JSONObject(Payloads.hello("0.6.2", "8", "Fold (F)")).has("textFocus"))
    }

    @Test fun readsWhatTheMacSends() {
        assertEquals(TextFocus(editable = true, secure = false), parse("""{"editable":true}"""))
        assertEquals(TextFocus(editable = false, secure = false), parse("""{"editable":false}"""))
        assertEquals(TextFocus(editable = true, secure = true), parse("""{"editable":true,"secure":true}"""))
        // A later field is skipped; secure means nothing without a field.
        assertEquals(TextFocus(editable = false, secure = false), parse("""{"editable":false,"secure":true,"caret":[1,2]}"""))
    }

    @Test fun refusesWhatIsNotOne() {
        assertNull(parse("{}"))
        assertNull(parse("""{"editable":"yes"}"""))
        assertNull(parse("not json"))
    }
}
