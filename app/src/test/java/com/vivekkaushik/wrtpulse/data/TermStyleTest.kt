package com.vivekkaushik.wrtpulse.data

import androidx.compose.ui.graphics.Color
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermStyleTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun engine(cols: Int = 40, rows: Int = 6) =
        TermEngine(RouterSession(SshTarget("t"), unusedClient, { error("unused") }), cols, rows)

    private val esc = 27.toChar().toString()

    // ---------- the SGR parameter machine ----------

    @Test
    fun `the basic eight map to the palette, and reset clears them`() {
        assertEquals(Wrt.Red, sgr(listOf(31), Attr.Default).fg)
        assertEquals(Wrt.Green, sgr(listOf(32), Attr.Default).fg)
        assertEquals(Wrt.Blue, sgr(listOf(44), Attr.Default).bg)
        assertEquals(Attr.Default, sgr(listOf(0), sgr(listOf(31, 1), Attr.Default)))
    }

    @Test
    fun `bright colours come from the top half of the table`() {
        assertEquals(ansi256(9), sgr(listOf(91), Attr.Default).fg)
        assertEquals(ansi256(12), sgr(listOf(104), Attr.Default).bg)
    }

    @Test
    fun `attributes accumulate and can be switched off one at a time`() {
        val a = sgr(listOf(1, 4, 7, 31), Attr.Default)
        assertTrue(a.bold && a.underline && a.inverse)
        val b = sgr(listOf(24), a)
        assertTrue(b.bold && b.inverse)
        assertFalse(b.underline)
        assertEquals(Wrt.Red, b.fg)
    }

    @Test
    fun `39 and 49 drop back to the terminal's own colours without touching the rest`() {
        val a = sgr(listOf(1, 31, 42), Attr.Default)
        val b = sgr(listOf(39, 49), a)
        assertNull(b.fg)
        assertNull(b.bg)
        assertTrue(b.bold)
    }

    /**
     * The extended forms swallow the parameters after them. Folding over each parameter on
     * its own would read the `5` as blink and the index as nothing at all.
     */
    @Test
    fun `256-colour consumes its parameters and lets the next one through`() {
        val a = sgr(listOf(38, 5, 208, 1), Attr.Default)
        assertEquals(ansi256(208), a.fg)
        assertTrue(a.bold)
    }

    @Test
    fun `truecolor consumes four parameters and lets the next one through`() {
        val a = sgr(listOf(38, 2, 255, 128, 0, 4), Attr.Default)
        assertEquals(Color(255, 128, 0), a.fg)
        assertTrue(a.underline)
    }

    @Test
    fun `a malformed extended colour is ignored rather than throwing`() {
        assertNull(sgr(listOf(38), Attr.Default).fg)
        assertNull(sgr(listOf(38, 2, 255), Attr.Default).fg)
    }

    @Test
    fun `the 256 table is the named colours, then the cube, then the grey ramp`() {
        assertEquals(Wrt.Red, ansi256(1))
        assertEquals(Color(0, 0, 0), ansi256(16))     // cube origin
        assertEquals(Color(255, 255, 255), ansi256(231)) // cube corner
        assertEquals(Color(95, 0, 0), ansi256(52))    // second level on one axis only
        assertEquals(Color(8, 8, 8), ansi256(232))    // ramp start
        assertEquals(Color(238, 238, 238), ansi256(255))
    }

    @Test
    fun `reverse video resolves the defaults before swapping them`() {
        val span = sgr(listOf(7), Attr.Default).span()
        assertEquals(Wrt.TermBg, span.color)
        assertEquals(Wrt.TermText, span.background)
    }

    // ---------- what reaches the screen ----------

    @Test
    fun `a colour sequence paints only the text that follows it`() {
        val e = engine()
        e.feed("${esc}[31mERR${esc}[0m ok")
        val line = e.screen[0]
        assertEquals("ERR ok", line.text)
        assertEquals(1, line.spanStyles.size)
        assertEquals(0, line.spanStyles[0].start)
        assertEquals(3, line.spanStyles[0].end)
        assertEquals(Wrt.Red, line.spanStyles[0].item.color)
    }

    @Test
    fun `neighbouring cells with the same look become one span`() {
        val e = engine()
        e.feed("${esc}[32mgreen${esc}[0m")
        assertEquals(1, e.screen[0].spanStyles.size)
    }

    @Test
    fun `plain output carries no spans at all`() {
        val e = engine()
        e.feed("nothing to see")
        assertTrue(e.screen[0].spanStyles.isEmpty())
    }

    @Test
    fun `the pen survives across lines the way a terminal's does`() {
        val e = engine()
        e.feed("${esc}[34mone\r\ntwo")
        assertEquals(Wrt.Blue, e.screen[0].spanStyles.single().item.color)
        assertEquals(Wrt.Blue, e.screen[1].spanStyles.single().item.color)
    }

    /** `ls --color` writes the name, resets, then writes the next one. */
    @Test
    fun `alternating colours produce one span each`() {
        val e = engine()
        e.feed("${esc}[1;34mdir${esc}[0m  ${esc}[32mrun${esc}[0m")
        val spans = e.screen[0].spanStyles
        assertEquals("dir  run", e.screen[0].text)
        assertEquals(2, spans.size)
        assertEquals(0 to 3, spans[0].start to spans[0].end)
        assertEquals(5 to 8, spans[1].start to spans[1].end)
        assertEquals(Wrt.Green, spans[1].item.color)
    }

    // ---------- SGR must not disturb the cursor bookkeeping ----------

    /**
     * A colour change moves nothing, so a line that exactly fills the width must still hold
     * its deferred wrap across one.
     */
    @Test
    fun `a colour change does not break the deferred wrap`() {
        val e = engine(cols = TermEngine.MIN_COLS, rows = 3)
        e.feed("01234567")         // exactly fills row 0
        e.feed("${esc}[31m")
        assertEquals(0, e.cursorRow)
        e.feed("X")                // the wrap was still pending
        assertEquals(listOf("01234567", "X"), e.text)
    }

    /** `tput clear` can put an SGR between the home and the erase. */
    @Test
    fun `a colour change between home and erase still counts as clear`() {
        val e = engine()
        e.feed("old\r\nrows\r\n")
        e.feed("${esc}[H${esc}[0m${esc}[J")
        assertEquals(listOf(""), e.text)
    }

    @Test
    fun `styling is dropped when a row is erased`() {
        val e = engine()
        e.feed("${esc}[31mred${esc}[0m")
        e.feed("\r${esc}[2Kplain")
        assertEquals("plain", e.screen[0].text)
        assertTrue(e.screen[0].spanStyles.isEmpty())
    }


    // ---------- the shell's own prompt ----------
    //
    // ash sends `root@OpenWrt:~# ` with no escapes at all, so it used to be the one grey
    // thing left on a coloured screen.

    @Test
    fun `the prompt is coloured even though the shell sends it bare`() {
        val e = engine()
        e.feed("root@OpenWrt:~# ")
        val line = e.screen[0]
        val spans = line.spanStyles
        assertEquals(2, spans.size)
        assertEquals(Wrt.Green, spans[0].item.color)
        assertEquals(0 to "root@OpenWrt".length, spans[0].start to spans[0].end)
        assertEquals(Wrt.TextDim, spans[1].item.color)
        assertEquals("root@OpenWrt".length to "root@OpenWrt:~#".length, spans[1].start to spans[1].end)
    }

    @Test
    fun `what was typed after the prompt keeps the default colour`() {
        val e = engine()
        e.feed("root@OpenWrt:/etc# ls -al")
        val spans = e.screen[0].spanStyles
        assertEquals(2, spans.size)
        assertEquals("root@OpenWrt:/etc#".length, spans.last().end)
    }

    @Test
    fun `a non-root prompt is matched too`() {
        val e = engine()
        e.feed("user@gw:~$ ")
        assertEquals(2, e.screen[0].spanStyles.size)
    }

    @Test
    fun `output that merely contains an address is left alone`() {
        val e = engine()
        e.feed("ssh-rsa AAAAB3Nz vivek@laptop")
        assertTrue(e.screen[0].spanStyles.isEmpty())
    }

    @Test
    fun `a terminator without the space that follows a prompt is not one`() {
        assertTrue(highlightPrompt("a@b:c#d").spanStyles.isEmpty())
        assertTrue(highlightPrompt("root@host").spanStyles.isEmpty())
    }

    /** A shell with its own coloured PS1 must keep exactly what it sent. */
    @Test
    fun `a prompt the shell coloured itself is not repainted`() {
        val e = engine()
        e.feed("${esc}[35mroot@OpenWrt:~#${esc}[0m ")
        val spans = e.screen[0].spanStyles
        assertEquals(1, spans.size)
        assertEquals(ansi256(5), spans[0].item.color)
    }
}
