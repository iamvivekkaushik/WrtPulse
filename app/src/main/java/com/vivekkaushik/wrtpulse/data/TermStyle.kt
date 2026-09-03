package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

/**
 * The sixteen ANSI colours, pulled towards the app's own palette rather than the usual
 * VGA set — `ls` and `grep` should look like they belong in this terminal, not next to it.
 * Index 0 is lifted off pure black so text written in it is still legible on [Wrt.TermBg].
 */
private val ANSI = arrayOf(
    Color(0xFF24302B), // black
    Wrt.Red,
    Wrt.Green,
    Wrt.Amber,
    Wrt.Blue,
    Color(0xFFC88BF2), // magenta
    Wrt.Accent,        // cyan
    Wrt.TermText,      // white
    Wrt.TextDim,       // bright black
    Color(0xFFFF8F82),
    Color(0xFF6FE89B),
    Color(0xFFFFC978),
    Color(0xFF8CC4FF),
    Color(0xFFDCA9FF),
    Color(0xFF6BF2DE),
    Color(0xFFF2FAF7),
)

/** Dimming is an alpha rather than a second palette, so it works on any colour. */
private const val DIM_ALPHA = 0.55f

/**
 * xterm's 256-colour table: the sixteen named colours, then a 6x6x6 RGB cube, then a
 * 24-step grey ramp. Anything outside the table falls back to the default foreground.
 */
internal fun ansi256(index: Int): Color = when (index) {
    in 0..15 -> ANSI[index]
    in 16..231 -> {
        val n = index - 16
        // Each axis has six levels, and they are not evenly spaced: 0 then 95..255 by 40.
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(n / 36), level((n / 6) % 6), level(n % 6))
    }
    in 232..255 -> {
        val grey = 8 + (index - 232) * 10
        Color(grey, grey, grey)
    }
    else -> Wrt.TermText
}

/** How one cell looks. Null colours mean "the terminal's own default". */
@Immutable
internal data class Attr(
    val fg: Color? = null,
    val bg: Color? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    /** Nothing to draw differently — the common case, worth not spanning at all. */
    val isPlain: Boolean
        get() = fg == null && bg == null && !bold && !dim && !italic && !underline && !inverse

    fun span(): SpanStyle {
        // Reverse video swaps the two, and it has to resolve the defaults first or an
        // un-coloured cell would invert to nothing.
        val front = fg ?: Wrt.TermText
        val back = bg ?: Wrt.TermBg
        val shown = if (inverse) back else front
        return SpanStyle(
            color = if (dim) shown.copy(alpha = DIM_ALPHA) else shown,
            background = if (inverse) front else bg ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    companion object {
        val Default = Attr()
    }
}

/**
 * Applies one SGR sequence (`CSI … m`) on top of [from]. Parameters are consumed in order
 * because the extended-colour forms — `38;5;n` and `38;2;r;g;b` — swallow the ones after
 * them, so this cannot be a simple fold over each parameter on its own.
 */
internal fun sgr(params: List<Int>, from: Attr): Attr {
    var a = from
    var i = 0
    while (i < params.size) {
        when (val p = params[i]) {
            0 -> a = Attr.Default
            1 -> a = a.copy(bold = true)
            2 -> a = a.copy(dim = true)
            3 -> a = a.copy(italic = true)
            4 -> a = a.copy(underline = true)
            7 -> a = a.copy(inverse = true)
            21, 22 -> a = a.copy(bold = false, dim = false)
            23 -> a = a.copy(italic = false)
            24 -> a = a.copy(underline = false)
            27 -> a = a.copy(inverse = false)
            in 30..37 -> a = a.copy(fg = ANSI[p - 30])
            38 -> { a = a.copy(fg = extended(params, i)); i += extendedWidth(params, i) }
            39 -> a = a.copy(fg = null)
            in 40..47 -> a = a.copy(bg = ANSI[p - 40])
            48 -> { a = a.copy(bg = extended(params, i)); i += extendedWidth(params, i) }
            49 -> a = a.copy(bg = null)
            in 90..97 -> a = a.copy(fg = ANSI[p - 90 + 8])
            in 100..107 -> a = a.copy(bg = ANSI[p - 100 + 8])
            else -> Unit // blink, font selection, reports: nothing to draw
        }
        i++
    }
    return a
}

/** The colour named by a `38`/`48` at [at], or null if the sequence is malformed. */
private fun extended(params: List<Int>, at: Int): Color? = when (params.getOrNull(at + 1)) {
    5 -> params.getOrNull(at + 2)?.let { ansi256(it) }
    2 -> {
        val r = params.getOrNull(at + 2)
        val g = params.getOrNull(at + 3)
        val b = params.getOrNull(at + 4)
        if (r != null && g != null && b != null) Color(r, g, b) else null
    }
    else -> null
}

/** How many extra parameters that `38`/`48` consumed. */
private fun extendedWidth(params: List<Int>, at: Int): Int = when (params.getOrNull(at + 1)) {
    5 -> 2
    2 -> 4
    else -> 0
}

/**
 * One row of the grid: the characters and the appearance of each, kept in step by
 * construction so a mid-line insert or delete can never slide the colours off the text.
 */
internal class Row {
    private val chars = StringBuilder()
    private val attrs = ArrayList<Attr>()
    private var cached: AnnotatedString? = null

    val length: Int get() = chars.length

    fun isNotEmpty(): Boolean = chars.isNotEmpty()

    fun setLength(n: Int) {
        if (n >= chars.length) return
        chars.setLength(n)
        while (attrs.size > n) attrs.removeAt(attrs.size - 1)
        cached = null
    }

    fun append(ch: Char, attr: Attr) {
        chars.append(ch)
        attrs.add(attr)
        cached = null
    }

    fun setAt(i: Int, ch: Char, attr: Attr) {
        chars.setCharAt(i, ch)
        attrs[i] = attr
        cached = null
    }

    fun insert(i: Int, count: Int, attr: Attr) {
        chars.insert(i, " ".repeat(count))
        repeat(count) { attrs.add(i, attr) }
        cached = null
    }

    fun delete(from: Int, to: Int) {
        chars.delete(from, to)
        repeat(to - from) { attrs.removeAt(from) }
        cached = null
    }

    override fun toString(): String = chars.toString()

    /** The row as the UI draws it, one span per run of identical appearance. */
    fun annotated(): AnnotatedString {
        cached?.let { return it }
        // A row the shell coloured itself keeps exactly what it sent; only a bare one is
        // offered to the prompt rule.
        val built =
            if (attrs.all { it.isPlain }) highlightPrompt(chars.toString())
            else buildAnnotatedString(chars, attrs)
        cached = built
        return built
    }
}

/**
 * `user@host:path#`, anchored at the start of a row. The terminator has to be followed by a
 * space or end the row, which is what keeps output that merely contains an address from
 * being mistaken for a prompt.
 */
private val PROMPT = Regex("""^([\w.-]+@[\w.-]+)(:[^\s#$]*)?([#$])(?= |$)""")

/**
 * OpenWrt's ash sends a bare prompt — no escapes at all — so the app colours it the way the
 * rest of the UI already draws one: the account green, the path and terminator dim. Applied
 * only to rows the shell left unstyled, so a shell with its own coloured PS1 wins.
 */
internal fun highlightPrompt(line: String): AnnotatedString {
    val m = PROMPT.find(line) ?: return AnnotatedString(line)
    val account = m.groups[1]!!.range.last + 1
    val end = m.groups[3]!!.range.last + 1
    return AnnotatedString.Builder(line).apply {
        addStyle(SpanStyle(color = Wrt.Green), 0, account)
        addStyle(SpanStyle(color = Wrt.TextDim), account, end)
    }.toAnnotatedString()
}

private fun buildAnnotatedString(chars: CharSequence, attrs: List<Attr>): AnnotatedString {
    if (attrs.all { it.isPlain }) return AnnotatedString(chars.toString())
    return AnnotatedString.Builder(chars.toString()).apply {
        var start = 0
        while (start < attrs.size) {
            var end = start + 1
            while (end < attrs.size && attrs[end] == attrs[start]) end++
            if (!attrs[start].isPlain) addStyle(attrs[start].span(), start, end)
            start = end
        }
    }.toAnnotatedString()
}
