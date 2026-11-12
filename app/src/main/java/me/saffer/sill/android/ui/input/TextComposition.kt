package me.saffer.sill.android.ui.input

import java.text.BreakIterator

// What the stream's keyboard sink holds for the input method (iOSClient/TextComposition.swift, #138):
// the text an input method is still composing (Zhuyin's ㄋㄧˇ, Pinyin's nihao, Japanese kana before
// conversion, a Korean syllable being built, dictation's words before they are final), kept on the
// device and sent only once chosen (or, as it stands, when the Mac's caret is about to move or the sink
// lets go of the keyboard), and what was typed since the Mac's caret was last moved by something else,
// so dictation and an input method can revise what they put there (backspaces on the Mac, then the new
// text), all but what went before a Return or a Tab typed, which is final on the Mac (`fence`).
//
// Pure: no Android types, unit tested on the JVM. Offsets are UTF-16, as InputConnection's are; the Mac
// is sent whole characters (grapheme clusters), as a backspace there deletes one.

/** A range of UTF-16 offsets. */
data class Span(val location: Int, val length: Int) {
    val end: Int get() = location + length
}

/** Grapheme clusters: what the Mac deletes with one backspace. */
internal object Graphemes {
    fun split(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val it = BreakIterator.getCharacterInstance()
        it.setText(s)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(s.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    fun count(s: String): Int = split(s).size

    fun dropLast(s: String): String = split(s).dropLast(1).joinToString("")

    fun last(s: String): String? = split(s).lastOrNull()

    fun suffix(s: String, n: Int): String = split(s).takeLast(n).joinToString("")
}

class TextComposition {
    /** What goes to the Mac: text, or that many backspaces (each one deletes one character there). */
    sealed class Output {
        data class Text(val text: String) : Output()
        data class Backspaces(val count: Int) : Output()
    }

    /** Why a correction ([replace]) sends nothing. */
    enum class Refusal(val words: String) {
        FORGOTTEN("a range taken before a forget or a trim"),
        PAST_END("a range that starts past what was typed"),
        FINAL("it would change what went before a Return or a Tab typed"),
    }

    /** What was sent since the last [forget], which the input method may still revise but before the [fence]. */
    var sent = ""
        private set
    /** The text being composed, never sent while it is; null when nothing is. */
    var marked: String? = null
        private set
    /** The selection inside the marked text, relative to its start. */
    var markedSelection = Span(0, 0)
        private set
    /**
     * What the input method selected of what was sent, for its next edit, which replaces it (Korean's
     * syllables built again, the character a backspace deletes). Never empty, never while composing, and
     * never wholly before the fence; null is the caret at the end.
     */
    var selection: Span? = null
        private set
    /** Which document positions were taken in: each forget and each trim starts a new one. */
    var generation = 0
        private set
    /**
     * Where what was sent stops being final: just after the last Return or Tab typed as text. Nothing the
     * input method does reaches back before it; only the person's own backspace does.
     */
    var fence = 0
        private set
    /** What a selection that reached back across the fence would have deleted before it, which its deletion left in place. */
    var retype = ""
        private set

    /** The document the input method sees: what was sent, then what is being composed. */
    val document: String get() = sent + (marked ?: "")
    val length: Int get() = sent.length + (marked?.length ?: 0)
    val isComposing: Boolean get() = marked != null
    val markedRange: Span? get() = marked?.let { Span(sent.length, it.length) }

    /** The caret at the end, or where the input method put it inside the marked text, or what it selected for its next edit. */
    val selectedRange: Span
        get() {
            if (marked == null) return selection ?: Span(length, 0)
            return Span(sent.length + markedSelection.location, markedSelection.length)
        }

    /**
     * The input method selects `range` for its next edit: kept, inside what was sent, when it selects
     * something while nothing is composed; an empty one, or one wholly before the fence, puts the caret
     * back at the end. One that reaches back across the fence from after it is kept ([retype]).
     */
    fun select(range: Span) {
        if (marked != null) return
        val count = sent.length
        val start = range.location.coerceIn(0, count)
        val end = (range.location + range.length).coerceIn(start, count)
        selection = if (end > start && end > fence) Span(start, end - start) else null
        if (selection != null) retype = ""
    }

    /** Text typed or committed: it replaces the marked text if there is any, else the selection, and goes to the Mac. */
    fun insert(text: String): List<Output> {
        val sel = selection
        if (marked == null && sel != null) {
            val new = sentReplacing(sel, text)
            if (!keepsFinal(new)) { selection = null; return emptyList() }
            return commit(new)
        }
        val rest = typedAfterRetype(text) ?: return emptyList()
        return commit(sent + rest)
    }

    /**
     * The input method's composition so far (an empty one ends it: nothing is sent). One that starts over
     * a selection replaces it: the selected text goes from the Mac first.
     */
    fun setMarked(text: String?, selected: Span): List<Output> {
        if (text.isNullOrEmpty()) { marked = null; markedSelection = Span(0, 0); return emptyList() }
        var out = emptyList<Output>()
        val sel = selection
        if (marked == null && sel != null) out = deleteSelection(sel)
        retype = ""
        marked = text
        val count = text.length
        val location = selected.location.coerceIn(0, count)
        markedSelection = Span(location, selected.length.coerceIn(0, count - location))
        return out
    }

    /** The composition is accepted as it stands: sent. */
    fun unmark(): List<Output> {
        val m = marked ?: return emptyList()
        retype = ""
        return commit(sent + m)
    }

    /**
     * Backspace: inside a composition it edits the composition; otherwise it deletes the selection if
     * there is one, else one backspace to the Mac, whether or not this side remembers what it deletes. It
     * is the person's, the one edit that goes back across the fence.
     */
    fun deleteBackward(): List<Output> {
        retype = ""
        val m = marked
        if (m != null) {
            val text = Graphemes.dropLast(m)
            setMarked(text, Span(text.length, 0))
            return emptyList()
        }
        selection?.let { return deleteSelection(it) }
        if (sent.isEmpty()) return listOf(Output.Backspaces(1))
        return commit(Graphemes.dropLast(sent))
    }

    /**
     * `range` of the document replaced with `text` (dictation's corrections, an input method's own edits):
     * any composition ends, and the Mac is brought to the new document by deleting back to where the old
     * and new first differ and typing the rest. Nothing is sent when it would land elsewhere than it
     * means, or change what is final ([refusal]).
     */
    fun replace(range: Span, text: String, taken: Int? = null): List<Output> {
        if (refusal(range, text, taken) != null) return emptyList()
        retype = ""
        return commit(replacing(range, text))
    }

    /** Why [replace] would send nothing, or null when it goes. */
    fun refusal(range: Span, text: String, taken: Int? = null): Refusal? {
        if (taken != null && taken != generation) return Refusal.FORGOTTEN
        if (range.location > length) return Refusal.PAST_END
        if (!keepsFinal(replacing(range, text))) return Refusal.FINAL
        return null
    }

    /**
     * The Mac's caret is about to move some other way (a click, an arrow, a shortcut, a pick): what is
     * being composed goes to the Mac first, as it stands, where the caret still is; then what was sent is
     * forgotten, nothing of it is final any more, and every position the input method holds is stale.
     */
    fun forget(): List<Output> {
        val out = unmark()
        sent = ""
        generation += 1
        selection = null
        fence = 0
        retype = ""
        return out
    }

    /** The tail is past [LIMIT] characters and nothing is being composed: the sink trims it at its next turn. */
    val wantsTrim: Boolean get() = marked == null && sent.length > LIMIT && Graphemes.count(sent) > LIMIT

    /** The tail's last [KEEP] characters are kept, and the fence with them. Every offset moves. */
    fun trim() {
        if (!wantsTrim) return
        val kept = Graphemes.suffix(sent, KEEP)
        fence = maxOf(fence - (sent.length - kept.length), 0)
        sent = kept
        generation += 1
        selection = null
        retype = ""
    }

    /** The document with `range` of it replaced by `text`, the range cut at the document's end. */
    private fun replacing(range: Span, text: String): String {
        val doc = document
        val start = range.location.coerceIn(0, doc.length)
        val end = (range.location + range.length).coerceIn(start, doc.length)
        return doc.substring(0, start) + text + doc.substring(end)
    }

    /** Whether `new` keeps what lies before the fence as the Mac has it, unit for unit. */
    private fun keepsFinal(new: String): Boolean = new.startsWith(sent.substring(0, fence.coerceAtMost(sent.length)))

    /** A selection deleted: what of it follows the fence goes from the Mac; what of it lies before, final, stays and becomes [retype]. */
    private fun deleteSelection(sel: Span): List<Output> {
        if (sel.location >= fence) return commit(sentReplacing(sel, ""))
        val kept = sent.substring(sel.location, fence)
        val out = commit(sentReplacing(Span(fence, sel.location + sel.length - fence), ""))
        retype = kept
        return out
    }

    /** What of text typed right after a selection's deletion is still to be typed; null when it would change what is final. */
    private fun typedAfterRetype(text: String): String? {
        val pending = retype
        retype = ""
        if (pending.isEmpty()) return text
        if (text.startsWith(pending)) return text.substring(pending.length)
        if (pending.startsWith(text)) { retype = pending.substring(text.length); return "" }
        return null
    }

    /** What was sent with `range` of it replaced by `text`: a selection's edit. */
    private fun sentReplacing(range: Span, text: String): String {
        val lower = range.location.coerceIn(0, sent.length)
        val upper = (range.location + range.length).coerceIn(lower, sent.length)
        return sent.substring(0, lower) + text + sent.substring(upper)
    }

    /**
     * The document becomes `new`, all of it sent: backspaces over what differs, then the rest. While `new`
     * keeps what is final the difference is looked for after the fence, so no backspace reaches into it.
     * A Return or a Tab this sends puts the fence just after the last one.
     */
    private fun commit(new: String): List<Output> {
        selection = null
        marked = null
        markedSelection = Span(0, 0)
        val from = if (keepsFinal(new)) fence.coerceAtMost(sent.length) else 0
        val old = Graphemes.split(sent.substring(from))
        val now = Graphemes.split(new.substring(from.coerceAtMost(new.length)))
        var common = 0
        while (common < old.size && common < now.size && old[common] == now[common]) common++
        val out = ArrayList<Output>()
        if (old.size > common) out.add(Output.Backspaces(old.size - common))
        if (now.size > common) out.add(Output.Text(now.subList(common, now.size).joinToString("")))
        sent = new
        fence = minOf(fence, new.length)
        val typed = (out.lastOrNull() as? Output.Text)?.text
        if (typed != null) TextInputRules.endOfReturnOrTab(typed)?.let { end -> fence = new.length - typed.length + end }
        return out
    }

    companion object {
        /** Past this many characters, the tail is due a trim. */
        const val LIMIT = 4_000
        /** What a trim keeps, in characters. */
        const val KEEP = 1_000
    }
}

/** The sink's rules beside its document. */
object TextInputRules {
    /**
     * Where the last Return or Tab ("\n", "\r" or "\t") in text typed for the Mac ends, in UTF-16 units
     * from the text's start, or null when it carries none: what came before it, the Return or the Tab
     * included, is final ([TextComposition.fence]).
     */
    fun endOfReturnOrTab(text: String): Int? {
        var end: Int? = null
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code || cp == '\r'.code || cp == '\t'.code) end = i
        }
        return end
    }

    fun carriesReturnOrTab(text: String): Boolean = endOfReturnOrTab(text) != null

    /** Whether text is written in the Latin script, or none (digits, punctuation, spaces, combining marks). */
    fun isLatinOrCommon(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> Unit
                else -> return false
            }
        }
        return true
    }
}

/**
 * Android's InputConnection calls over the document ([TextComposition]): what each one sends the Mac.
 * Android's input methods differ from iPadOS's text system in one way that matters here: a Latin
 * keyboard may compose the word it is typing (Samsung Keyboard always, others with suggestions), where
 * iPadOS's types each letter. So a composition is one of two kinds, decided as it starts:
 *  - **live**: the person's keyboard types the Latin alphabet directly and the composition starts with
 *    one Latin character, as a key typed: its text goes to the Mac as it comes, each change as backspaces
 *    and new letters (the composing region is text already sent, edited by [TextComposition.replace]),
 *    so plain Latin typing reaches the Mac letter by letter;
 *  - **marked** (iOS's marked text): anything else (Chinese, Japanese, Korean, Vietnamese, a voice IME, a
 *    dictated phrase or a swiped word that arrives several characters at once): kept on the device, shown
 *    over the picture, and only the chosen text is typed on the Mac.
 */
class ImeDocument {
    val composition = TextComposition()

    /** A live composition's region in what was sent; null when none is live. */
    var liveRegion: Span? = null
        private set

    /** The composing region the input method should be told of, or null. */
    val composingSpan: Span? get() = composition.markedRange ?: liveRegion

    /** Something to tell the Mac or the input method about: what was typed, or a composition. */
    val holdsAnything: Boolean get() = composition.sent.isNotEmpty() || composition.isComposing || liveRegion != null

    fun commitText(text: String): List<TextComposition.Output> {
        val live = liveRegion
        if (live != null) {
            liveRegion = null
            return composition.replace(live, text)
        }
        return composition.insert(text)
    }

    /**
     * The composition so far, the caret at `newCursorPosition` as InputConnection counts it (above 0: from
     * its end, less one; otherwise from its start). `latinTyping`: the keyboard in use types the Latin
     * alphabet directly ([KeyTyping.typesOnMac]), and is not a voice input method.
     */
    fun setComposingText(text: String, newCursorPosition: Int, latinTyping: Boolean): List<TextComposition.Output> {
        val live = liveRegion
        if (live != null) {
            val out = composition.replace(live, text)
            // Refused (it would change what is final): the region is left as it was.
            if (out.isNotEmpty() || composition.refusal(live, text) == null) {
                liveRegion = if (text.isEmpty()) null else Span(live.location, text.length)
            }
            return out
        }
        if (!composition.isComposing && text.isNotEmpty() && startsLive(text, latinTyping)) {
            val at = composition.selection?.location ?: composition.sent.length
            val before = composition.sent
            val out = composition.insert(text)
            liveRegion = if (composition.sent != before || out.isNotEmpty()) Span(at, text.length) else null
            return out
        }
        val caret = if (newCursorPosition > 0) text.length + newCursorPosition - 1 else newCursorPosition
        return composition.setMarked(text, Span(caret.coerceIn(0, text.length), 0))
    }

    /** The composition is accepted as it stands. */
    fun finishComposingText(): List<TextComposition.Output> {
        if (liveRegion != null) { liveRegion = null; return emptyList() }
        return composition.unmark()
    }

    /**
     * The input method takes text already typed back into a composition (to correct a word): what it
     * composes there goes out as it comes, as a correction. A composition on the device goes first.
     */
    fun setComposingRegion(start: Int, end: Int): List<TextComposition.Output> {
        val out = if (composition.isComposing) composition.unmark() else emptyList()
        val count = composition.sent.length
        val a = minOf(start, end).coerceIn(0, count)
        val b = maxOf(start, end).coerceIn(0, count)
        liveRegion = if (b > a && b > composition.fence) Span(a, b - a) else null
        return out
    }

    /**
     * The input method deletes `before` UTF-16 units before the caret (its backspace, Gboard's when there
     * is text before the caret): the person's backspace, one character at a time, as many as those units
     * hold; past what is remembered, one backspace per unit. A composition on the device goes first.
     */
    fun deleteSurroundingText(before: Int): List<TextComposition.Output> {
        if (before <= 0) return emptyList()
        val out = ArrayList<TextComposition.Output>()
        if (composition.isComposing) out += composition.unmark()
        liveRegion = null
        var left = before.coerceAtMost(MAX_DELETE)
        while (left > 0) {
            val sel = composition.selection
            val units = when {
                sel != null -> sel.length
                composition.sent.isNotEmpty() -> Graphemes.last(composition.sent)?.length ?: 1
                else -> 1
            }
            out += composition.deleteBackward()
            left -= units.coerceAtLeast(1)
        }
        return merge(out)
    }

    /** A backspace key (the soft keyboard's key event, or a hardware Delete the Mac does not type). */
    fun deleteBackward(): List<TextComposition.Output> {
        liveRegion = null
        return composition.deleteBackward()
    }

    /** Text typed by a key (a hardware key the text path takes, Return, Tab): a live composition ends first. */
    fun type(text: String): List<TextComposition.Output> {
        liveRegion = null
        return composition.insert(text)
    }

    fun setSelection(start: Int, end: Int) {
        composition.select(Span(minOf(start, end), kotlin.math.abs(end - start)))
    }

    /** The Mac's caret may move: what is composed goes first, then everything is forgotten. */
    fun forget(): List<TextComposition.Output> {
        liveRegion = null
        return composition.forget()
    }

    /** The composition as it stands, sent: the sink lets go of the keyboard, or a key goes mid-composition. */
    fun unmark(): List<TextComposition.Output> = composition.unmark()

    private fun startsLive(text: String, latinTyping: Boolean): Boolean =
        latinTyping && Graphemes.count(text) == 1 && TextInputRules.isLatinOrCommon(text) && !text[0].isWhitespace()

    /** Consecutive backspaces as one. */
    private fun merge(out: List<TextComposition.Output>): List<TextComposition.Output> {
        val merged = ArrayList<TextComposition.Output>()
        for (o in out) {
            val last = merged.lastOrNull()
            if (o is TextComposition.Output.Backspaces && last is TextComposition.Output.Backspaces) {
                merged[merged.size - 1] = TextComposition.Output.Backspaces(last.count + o.count)
            } else merged += o
        }
        return merged
    }

    private companion object {
        /** A deletion past this is the input method's runaway: the Mac gets at most this many backspaces. */
        const val MAX_DELETE = 64
    }
}
