package me.saffer.sill.android.ui.input

import me.saffer.sill.android.ui.input.TextComposition.Output
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** TextComposition after iOS's Tests/checks/text-composition, and ImeDocument, Android's input connection over it. */
class TextCompositionTest {
    /** The Mac's text field: what the outputs leave there (a backspace deletes one character). */
    private class Mac(var text: String = "") {
        fun apply(outputs: List<Output>) {
            for (o in outputs) when (o) {
                is Output.Text -> text += o.text
                is Output.Backspaces -> repeat(o.count) { text = Graphemes.dropLast(text) }
            }
        }
    }

    private fun text(t: String) = Output.Text(t)
    private fun bs(n: Int) = Output.Backspaces(n)

    @Test fun zhuyinComposesOnTheDevice() {
        val c = TextComposition()
        c.setMarked("ㄋ", Span(1, 0))
        c.setMarked("ㄋㄧˇ", Span(3, 0))
        assertTrue("a composition is not sent", c.sent.isEmpty() && c.isComposing && c.markedRange == Span(0, 3))
        assertEquals(Span(3, 0), c.selectedRange)
        assertEquals("the candidate replaces the composition and goes out", listOf(text("你")), c.insert("你"))
        assertTrue(!c.isComposing && c.sent == "你")
    }

    @Test fun unmarkSendsTheComposition() {
        val c = TextComposition()
        c.insert("a")
        c.setMarked("你好", Span(2, 0))
        assertEquals(Span(1, 2), c.markedRange)
        assertTrue(c.length == 3 && c.document == "a你好")
        assertEquals(Span(3, 0), c.selectedRange)
        assertEquals(listOf(text("你好")), c.unmark())
        assertEquals(emptyList<Output>(), c.unmark())
        val j = TextComposition()
        j.insert("x")
        j.setMarked("きょうは", Span(1, 2))
        assertEquals("the input method's caret inside its composition", Span(2, 2), j.selectedRange)
    }

    @Test fun backspace() {
        val c = TextComposition()
        c.setMarked("ㄋㄧ", Span(2, 0))
        assertTrue(c.deleteBackward().isEmpty() && c.marked == "ㄋ")
        assertTrue(c.deleteBackward().isEmpty() && !c.isComposing)
        assertEquals("with nothing remembered it still reaches the Mac", listOf(bs(1)), c.deleteBackward())
        c.insert("你好")
        assertEquals(listOf(bs(1)), c.deleteBackward())
        assertEquals("你", c.sent)
        val e = TextComposition()
        e.setMarked("ㄅ", Span(1, 0)); e.setMarked("", Span(0, 0))
        assertTrue("an empty composition ends it", !e.isComposing && e.sent.isEmpty() && e.markedRange == null)
    }

    @Test fun dictationCorrections() {
        val c = TextComposition(); val mac = Mac()
        mac.apply(c.insert("I sea it"))
        mac.apply(c.replace(Span(2, 3), "see"))
        assertEquals("I see it", mac.text)
        val out = c.replace(Span(8, 0), ".")
        mac.apply(out)
        assertTrue(out == listOf(text(".")) && mac.text == "I see it.")
        // A long dictation: its corrections land where they were made.
        val d = TextComposition(); val dm = Mac()
        var phrase = 0
        while (d.sent.length < 1_500) {
            phrase++
            val start = d.length
            dm.apply(d.insert("wurd$phrase "))
            dm.apply(d.replace(Span(start, d.length - start), "word$phrase, "))
            assertEquals(d.document, dm.text)
        }
        dm.apply(d.replace(Span(0, 4), "Word"))
        assertTrue(dm.text == d.document && dm.text.startsWith("Word1, "))
    }

    @Test fun charactersNotUnits() {
        val c = TextComposition(); val mac = Mac()
        mac.apply(c.insert("a👍🏽é"))
        val out = c.deleteBackward()
        mac.apply(out)
        assertTrue("one backspace per character", out == listOf(bs(1)) && mac.text == "a👍🏽")
        mac.apply(c.replace(Span(1, c.document.length - 1), "b"))
        assertEquals("a replacement over an emoji deletes it once", "ab", mac.text)
        mac.apply(c.insert("e"))
        val accent = c.insert("́")
        mac.apply(accent)
        assertEquals("a combining mark retypes the character whole", listOf(bs(1), text("é")), accent)
    }

    @Test fun forget() {
        val c = TextComposition(); val mac = Mac()
        mac.apply(c.insert("hello"))
        c.setMarked("ㄏㄠ", Span(2, 0))
        val out = c.forget()
        mac.apply(out)
        assertTrue("what is composed goes first", out == listOf(text("ㄏㄠ")) && mac.text == "helloㄏㄠ")
        assertTrue(c.sent.isEmpty() && !c.isComposing && c.length == 0)
        // A correction taken before the forget sends nothing.
        val d = TextComposition(); val dm = Mac("earlier ")
        dm.apply(d.insert("hello"))
        val taken = d.generation
        dm.apply(d.forget())
        dm.apply(d.insert("x"))
        assertTrue(d.replace(Span(0, 5), "Hello.", taken).isEmpty() && dm.text == "earlier hellox")
        assertEquals(TextComposition.Refusal.PAST_END, d.refusal(Span(9, 1), "y"))
    }

    @Test fun aReturnIsFinal() {
        val c = TextComposition(); val mac = Mac()
        mac.apply(c.insert("send the build"))
        val typed = c.insert("\n")
        assertEquals(listOf(text("\n")), typed)
        mac.apply(typed)
        assertTrue(c.sent == "send the build\n" && c.fence == 15)
        val out = c.replace(Span(0, 14), "Send the build.")
        assertTrue("a correction before the Return sends nothing", out.isEmpty() && mac.text == "send the build\n")
        assertEquals(TextComposition.Refusal.FINAL, c.refusal(Span(0, 14), "Send the build."))
        c.select(Span(14, 1))
        assertNull("the Return selected is wholly final", c.selection)
        val back = c.deleteBackward()
        mac.apply(back)
        assertTrue("the person's backspace goes back across it", back == listOf(bs(1)) && mac.text == "send the build" && c.fence == 14)
        mac.apply(c.insert("ok"))
        val corrected = c.replace(Span(14, 2), "OK")
        mac.apply(corrected)
        assertTrue(corrected == listOf(bs(2), text("OK")) && mac.text == "send the buildOK")
    }

    @Test fun koreanAfterAReturn() {
        val k = TextComposition(); val mac = Mac()
        for (t in listOf("한", "\n", "ㄱ")) mac.apply(k.insert(t))
        k.select(Span(2, 1))
        assertEquals(Span(2, 1), k.selection)
        mac.apply(k.deleteBackward()); mac.apply(k.insert("그"))
        k.select(Span(2, 1))
        mac.apply(k.deleteBackward()); mac.apply(k.insert("글"))
        assertTrue("한⏎글 once", mac.text == "한\n글" && k.sent == mac.text)
        // Its rebuild of the Return with the syllable: the Return goes once.
        val c = TextComposition(); val cm = Mac()
        for (t in listOf("한", "글", "\n", "ㅎ")) cm.apply(c.insert(t))
        c.select(Span(2, 2))
        val deleted = c.deleteBackward()
        cm.apply(deleted)
        assertTrue(deleted == listOf(bs(1)) && cm.text == "한글\n" && c.retype == "\n")
        val again = c.insert("\n")
        assertTrue("no second Return", again.isEmpty() && cm.text == "한글\n")
    }

    @Test fun crlfAndTrim() {
        val d = TextComposition(); val dm = Mac()
        dm.apply(d.insert("x\r"))
        val lf = d.insert("\n")
        dm.apply(lf)
        assertTrue("the LF typed apart: no backspace over the CR", lf == listOf(text("\n")) && d.sent == "x\r\n" && d.fence == 3)
        val c = TextComposition()
        c.insert("a".repeat(TextComposition.LIMIT + 10))
        assertTrue(c.wantsTrim)
        val g = c.generation
        c.trim()
        assertTrue(c.sent.length == TextComposition.KEEP && c.generation == g + 1 && !c.wantsTrim)
    }

    @Test fun rules() {
        assertEquals(null, TextInputRules.endOfReturnOrTab("abc"))
        assertEquals(4, TextInputRules.endOfReturnOrTab("abc\ndef"))
        assertEquals(5, TextInputRules.endOfReturnOrTab("a\nb\r\nc"))
        assertTrue(TextInputRules.isLatinOrCommon("é1 ,"))
        assertFalse(TextInputRules.isLatinOrCommon("ㄋ"))
        assertFalse(TextInputRules.isLatinOrCommon("に"))
    }

    /** 5,000 random edit sequences: the Mac's text is always what was sent, never what is composed. */
    @Test fun randomEditsKeepTheMacEqual() {
        val rng = Random(138)
        val pieces = listOf("a", "bc", "你", "👍🏽", "é", " ", "\n", "ok", "한")
        repeat(5_000) {
            val c = TextComposition(); val mac = Mac()
            repeat(rng.nextInt(1, 25)) {
                when (rng.nextInt(7)) {
                    0, 1 -> mac.apply(c.insert(pieces.random(rng)))
                    2 -> c.setMarked(pieces.random(rng) + pieces.random(rng), Span(1, 0)).let(mac::apply)
                    3 -> mac.apply(c.unmark())
                    4 -> mac.apply(c.deleteBackward())
                    5 -> {
                        val at = rng.nextInt(0, c.sent.length + 1)
                        mac.apply(c.replace(Span(at, rng.nextInt(0, c.sent.length - at + 1)), pieces.random(rng)))
                    }
                    6 -> c.select(Span(rng.nextInt(0, c.sent.length + 1), rng.nextInt(0, 3)))
                }
                // The Mac's text is what was sent: never what is composed.
                assertEquals(c.sent, mac.text)
            }
            mac.apply(c.forget())
            assertFalse(c.isComposing)
        }
    }

    // MARK: ImeDocument: Android's input connection

    @Test fun latinTypingThatComposesGoesLetterByLetter() {
        // Samsung Keyboard composing each word: every letter reaches the Mac as it is typed.
        val d = ImeDocument(); val mac = Mac()
        mac.apply(d.setComposingText("h", 1, latinTyping = true))
        assertEquals("h", mac.text)
        assertEquals(Span(0, 1), d.composingSpan)
        mac.apply(d.setComposingText("he", 1, latinTyping = true))
        mac.apply(d.setComposingText("hel", 1, latinTyping = true))
        mac.apply(d.setComposingText("helo", 1, latinTyping = true))
        assertEquals("helo", mac.text)
        // Its autocorrection on the space: backspaces, then the word.
        mac.apply(d.commitText("hello "))
        assertTrue(mac.text == "hello " && d.composingSpan == null && !d.composition.isComposing)
        // Its backspace inside the word it composes.
        mac.apply(d.setComposingText("w", 1, latinTyping = true))
        mac.apply(d.setComposingText("", 1, latinTyping = true))
        assertEquals("hello ", mac.text)
        // A keyboard that commits each letter: as before.
        mac.apply(d.commitText("x"))
        assertEquals("hello x", mac.text)
    }

    @Test fun cjkComposesOnTheDevice() {
        val d = ImeDocument(); val mac = Mac()
        mac.apply(d.commitText("a"))
        // Pinyin's letters, from an input method in Chinese: nothing reaches the Mac until chosen.
        mac.apply(d.setComposingText("n", 1, latinTyping = false))
        mac.apply(d.setComposingText("ni", 1, latinTyping = false))
        mac.apply(d.setComposingText("nihao", 1, latinTyping = false))
        assertTrue(mac.text == "a" && d.composition.marked == "nihao" && d.composingSpan == Span(1, 5))
        mac.apply(d.commitText("你好"))
        assertTrue(mac.text == "a你好" && !d.composition.isComposing)
        // Japanese kana, accepted as it stands.
        mac.apply(d.setComposingText("にほん", 1, latinTyping = false))
        assertEquals("a你好", mac.text)
        mac.apply(d.finishComposingText())
        assertEquals("a你好にほん", mac.text)
        // A Korean syllable from a keyboard whose language says Latin (unknown): Hangul is never live.
        mac.apply(d.setComposingText("ㅎ", 1, latinTyping = true))
        mac.apply(d.setComposingText("하", 1, latinTyping = true))
        assertEquals("a你好にほん", mac.text)
        mac.apply(d.setComposingText("한", 1, latinTyping = true))
        mac.apply(d.commitText("한"))
        assertEquals("a你好にほん한", mac.text)
    }

    @Test fun dictationComposesOnTheDevice() {
        // Voice typing's hypotheses arrive several characters at once: kept until final.
        val d = ImeDocument(); val mac = Mac()
        mac.apply(d.setComposingText("Hello", 1, latinTyping = true))
        mac.apply(d.setComposingText("Hello world", 1, latinTyping = true))
        assertEquals("", mac.text)
        mac.apply(d.commitText("Hello world."))
        assertEquals("Hello world.", mac.text)
        // A voice input method: never live, even a single letter.
        mac.apply(d.setComposingText("I", 1, latinTyping = false))
        assertEquals("Hello world.", mac.text)
        mac.apply(d.finishComposingText())
        assertEquals("Hello world.I", mac.text)
    }

    @Test fun deletionsAndRegions() {
        val d = ImeDocument(); val mac = Mac()
        mac.apply(d.commitText("ab👍🏽"))
        // Gboard deletes an emoji as two UTF-16 units: one backspace on the Mac.
        val out = d.deleteSurroundingText(2)
        mac.apply(out)
        assertTrue(out == listOf(bs(1)) && mac.text == "ab")
        // Past what is remembered: one backspace per unit.
        assertEquals(listOf(bs(4)), d.deleteSurroundingText(4))
        // A composing region over a word typed: its edits go out as a correction.
        val e = ImeDocument(); val em = Mac()
        em.apply(e.commitText("teh "))
        em.apply(e.setComposingRegion(0, 3))
        em.apply(e.setComposingText("the", 1, latinTyping = true))
        em.apply(e.finishComposingText())
        assertEquals("the ", em.text)
        // A composition on the device goes first when the region moves.
        em.apply(e.setComposingText("にほん", 1, latinTyping = false))
        em.apply(e.setComposingRegion(0, 3))
        assertEquals("the にほん", em.text)
    }

    @Test fun forgetEndsEverything() {
        val d = ImeDocument(); val mac = Mac()
        mac.apply(d.setComposingText("h", 1, latinTyping = true))
        mac.apply(d.forget())
        assertTrue(mac.text == "h" && !d.holdsAnything && d.composingSpan == null)
        mac.apply(d.setComposingText("ㄋ", 1, latinTyping = false))
        mac.apply(d.forget())
        assertTrue("what is composed goes first", mac.text == "hㄋ" && !d.holdsAnything)
    }
}
