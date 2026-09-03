package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermEngineTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun engine() = TermEngine(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))

    /** A tiny screen, so scrolling and wrapping can be exercised in a few lines. */
    private fun smallEngine(cols: Int = 10, rows: Int = 3) =
        TermEngine(RouterSession(SshTarget("t"), unusedClient, { error("unused") }), cols, rows)

    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    @Test
    fun `plain lines commit on newline`() {
        val e = engine()
        e.feed("BusyBox v1.36.1 built-in shell (ash)\r\n\r\nroot@gw:~# ")
        assertEquals(listOf("BusyBox v1.36.1 built-in shell (ash)", ""), e.lines.toList())
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `ansi color codes are stripped`() {
        val e = engine()
        e.feed("${esc}[32mroot@gw${esc}[0m:~# ")
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `osc window title is swallowed`() {
        val e = engine()
        e.feed("${esc}]0;root@gw: ~${bel}root@gw:~# ")
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `backspace erase sequence removes the character`() {
        val e = engine()
        e.feed("root@gw:~# lss")
        e.feed("\b${esc}[K") // ash sends BS + erase-to-eol when deleting
        assertEquals("root@gw:~# ls", e.current)
    }

    @Test
    fun `carriage return overwrites from line start`() {
        val e = engine()
        e.feed("progress 10%\rprogress 99%")
        assertEquals("progress 99%", e.current)
    }

    @Test
    fun `cr with shorter rewrite plus erase truncates leftovers`() {
        val e = engine()
        e.feed("a long old line\rnew${esc}[K")
        assertEquals("new", e.current)
    }

    // --- regressions from device use: backspace wiped the screen, history recall garbled ---

    /** `ESC[J` is erase-to-end-of-display, which ash emits on every redraw — not a clear. */
    @Test
    fun `backspace correction does not clear the session`() {
        val e = engine()
        e.feed("BusyBox v1.36.1\r\n")
        e.feed("root@gw:~# lss")
        e.feed("\b${esc}[J") // ash: move back one, erase the rest of the display
        assertEquals(listOf("BusyBox v1.36.1"), e.lines.toList())
        assertEquals("root@gw:~# ls", e.current)
    }

    @Test
    fun `erase-display 0 keeps the rows above the cursor`() {
        val e = engine()
        e.feed("one\r\ntwo\r\nprompt")
        e.feed("${esc}[0J")
        assertEquals(2, e.lines.size)
    }

    /**
     * `clear` homes the cursor and then erases; ED2 on its own does not move the cursor,
     * so ignoring the row in ESC[H left the screen looking untouched.
     */
    @Test
    fun `clear empties the screen and homes the cursor`() {
        val e = engine()
        e.feed("one\r\ntwo\r\nroot@gw:~# clear")
        e.feed("\r\n${esc}[H${esc}[2J")   // what terminfo's clear capability sends
        assertEquals(listOf(""), e.text)
        assertEquals(0, e.cursorRow)
        assertEquals("", e.current)
        assertTrue(e.lines.isEmpty())

        // The other spelling, erase-from-home-to-end, must do the same.
        e.feed("a\r\nb\r\nc")
        assertEquals(3, e.text.size)
        e.feed("${esc}[H${esc}[J")
        assertEquals(listOf(""), e.text)
        assertEquals(0, e.cursorRow)
    }

    /** Up-arrow: ash rewrites the line in place using CR, erase-line and cursor moves. */
    @Test
    fun `history recall redraws the line instead of corrupting it`() {
        val e = engine()
        e.feed("root@gw:~# ")
        e.feed("\r${esc}[Kroot@gw:~# uptime")   // recalled command redrawn from column 0
        assertEquals("root@gw:~# uptime", e.current)
        assertEquals(0, e.lines.size)

        // A second recall replaces a longer line with a shorter one, no leftovers.
        e.feed("\r${esc}[Kroot@gw:~# ls")
        assertEquals("root@gw:~# ls", e.current)
    }

    /**
     * Captured from the user's router: a recalled command longer than the 48-column pty
     * wraps (CR CR LF mid-text); the next recall moves the cursor UP with ESC[nA and
     * erases downward with ESC[J. Ignoring the cursor move stranded the wrapped rows.
     */
    @Test
    fun `recalling over a wrapped command leaves no leftover rows`() {
        val e = engine()
        val prompt = "root@OpenWrt:~# "
        e.feed("$prompt")

        // First recall: a long command that wraps onto a second row.
        e.feed("\r${esc}]0;root@OpenWrt: ~${bel}${prompt}/usr/")
        e.feed("sbin/networksetup -setdhcp \r\r\nWi-Fi${esc}[J")
        assertEquals(2, e.text.size)
        assertEquals("Wi-Fi", e.text.last())
        assertEquals(1, e.cursorRow)

        // Second recall: ash steps back up to the first row, redraws, erases downward.
        e.feed("${esc}[1A\r${esc}]0;root@OpenWrt: ~${bel}${prompt}uptime${esc}[J")
        assertEquals(listOf("${prompt}uptime"), e.text)
        assertEquals(0, e.cursorRow)
        assertEquals("${prompt}uptime", e.current)
        assertTrue(e.lines.isEmpty())
    }

    @Test
    fun `erase to end of display drops the rows below the cursor`() {
        val e = engine()
        e.feed("one\r\ntwo\r\nthree")
        e.feed("${esc}[2A")       // back up to "one"
        e.feed("\rXY${esc}[J")    // rewrite, then erase from the cursor to the end of the screen
        assertEquals(listOf("XY"), e.text)
        assertEquals(0, e.cursorRow)
    }

    @Test
    fun `line feed keeps the column so wrapped output is not indented`() {
        val e = engine()
        e.feed("abc\r\ndef")
        assertEquals(listOf("abc", "def"), e.text)
    }

    @Test
    fun `cursor down past the end creates rows`() {
        val e = engine()
        e.feed("top${esc}[2Bbottom")
        assertEquals(3, e.text.size)
        assertEquals("bottom", e.text.last().trim())
        assertEquals(2, e.cursorRow)
    }

    @Test
    fun `rows scroll into scrollback once the screen is full`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("l1\r\nl2\r\nl3\r\nl4\r\nl5")
        // Three rows on screen, the first two scrolled above them.
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5"), e.text)
        assertEquals(4, e.cursorRow)
        assertEquals("l5", e.current)
    }

    @Test
    fun `clear wipes scrolled-off rows too, so the view really empties`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("l1\r\nl2\r\nl3\r\nl4\r\nl5")
        e.feed("${esc}[H${esc}[2J")
        assertEquals(listOf(""), e.text)
        assertEquals(0, e.cursorRow)
    }

    /** Busybox's clear applet emits ESC[H ESC[J, with no terminfo involved. */
    @Test
    fun `busybox clear form empties the view as well`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("l1\r\nl2\r\nl3\r\nl4\r\nl5")
        assertEquals(5, e.text.size)
        e.feed("${esc}[H${esc}[J")
        assertEquals(listOf(""), e.text)
        assertEquals(0, e.cursorRow)
    }

    /**
     * ash reaches column 0 with CR, not ESC[H, when it redraws a recalled command — that
     * must trim rows, never wipe the view.
     */
    @Test
    fun `redraw that returns to column zero does not count as clear`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("l1\r\nl2\r\nold")
        e.feed("\rnew${esc}[J")
        assertEquals(listOf("l1", "l2", "new"), e.text)
    }

    /** But an erase-to-end mid-line must still only trim from the cursor. */
    @Test
    fun `erase to end away from home keeps earlier rows`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("l1\r\nl2\r\nab")
        e.feed("${esc}[J")
        assertEquals(listOf("l1", "l2", "ab"), e.text)
    }

    @Test
    fun `text wraps at the pty width without an early break`() {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("0123456789")          // exactly fills row 0
        assertEquals(listOf("0123456789"), e.text)
        assertEquals(0, e.cursorRow)
        e.feed("X")                   // the next character starts row 1
        assertEquals(listOf("0123456789", "X"), e.text)
        assertEquals(1, e.cursorRow)
    }

    @Test
    fun `cursor moves let the shell overwrite mid-line`() {
        val e = engine()
        e.feed("root@gw:~# uptimx")
        e.feed("${esc}[1D")   // one left
        e.feed("e")           // overwrite the typo
        assertEquals("root@gw:~# uptime", e.current)

        e.feed("${esc}[12G")  // column-absolute back onto the command
        e.feed("U")
        assertEquals("root@gw:~# Uptime", e.current)
    }

    @Test
    fun `delete and insert characters`() {
        val e = engine()
        e.feed("abcdef${esc}[3D${esc}[2P")   // cursor onto "d", delete two
        assertEquals("abcf", e.current)
        e.feed("${esc}[2@")                  // open two blanks at the cursor
        assertEquals("abc  f", e.current)
    }

    @Test
    fun `erase to start blanks the head of the line`() {
        val e = engine()
        e.feed("hello world${esc}[6G${esc}[1K")
        assertEquals("     " + " world", e.current)
    }

    @Test
    fun `charset selection is swallowed, not printed`() {
        val e = engine()
        e.feed("${esc}(Bready")
        assertEquals("ready", e.current)
    }

    @Test
    fun `sgr colour parameters are ignored`() {
        val e = engine()
        e.feed("${esc}[01;32mroot${esc}[00m@gw")
        assertEquals("root@gw", e.current)
    }

    @Test
    fun `osc terminated by string terminator`() {
        val e = engine()
        e.feed("${esc}]0;title${esc}\\root@gw:~# ")
        assertEquals("root@gw:~# ", e.current)
    }

    @Test
    fun `tabs advance to the next stop`() {
        val e = engine()
        e.feed("ab\tc")
        assertEquals("ab      c", e.current)
    }

    @Test
    fun `logread lines parse`() {
        val entry = Parsers.logread(
            "Sat Aug 30 12:34:56 2026 daemon.notice hostapd: phy0-ap0: AP-STA-CONNECTED aa:5c:1e:88:04:2b"
        )!!
        assertEquals("12:34:56", entry.time)
        assertEquals("notice", entry.severity)
        assertEquals("hostapd", entry.src)
        assertEquals("phy0-ap0: AP-STA-CONNECTED", entry.msg)
        assertEquals("aa:5c:1e:88:04:2b", entry.tok)

        val dhcp = Parsers.logread(
            "Sat Aug 30 12:35:01 2026 daemon.info dnsmasq-dhcp[3121]: DHCPACK(br-lan) 192.168.2.34"
        )!!
        assertEquals("dnsmasq-dhcp", dhcp.src) // [pid] stripped
        assertEquals("192.168.2.34", dhcp.tok)

        val kern = Parsers.logread(
            "Sat Aug 30 12:35:07 2026 kern.err kernel: [162.44] something failed"
        )!!
        assertEquals("kernel", kern.src)
        assertEquals("err", kern.severity)
        assertEquals("", kern.tok)

        assertNull(Parsers.logread("not a syslog line"))
    }

    /**
     * `logread -f` emits only what is logged from now on, so a quiet router showed an empty
     * screen; the recent buffer has to be printed first.
     */
    @Test
    fun `log stream prints the recent buffer before following`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.LOG_FOLLOW
        assertTrue(cmd.startsWith("logread -l ${com.vivekkaushik.wrtpulse.ops.Commands.LOG_BACKLOG}"))
        assertTrue(cmd.trimEnd().endsWith("logread -f"))
        // A build whose logread lacks -l must still reach the follow.
        assertTrue(cmd.contains("2>/dev/null;"))
    }

    @Test
    fun `log colors follow severity then source`() {
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Red, LiveLogs.colorFor("err", "dnsmasq"))
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Blue, LiveLogs.colorFor("info", "dnsmasq-dhcp"))
        assertEquals(com.vivekkaushik.wrtpulse.ui.theme.Wrt.Accent, LiveLogs.colorFor("info", "dropbear"))
    }

    // ---------- pty resize ----------
    //
    // The pty was fixed at 48 columns, which on a tablet or an unfolded phone left half the
    // pane empty and wrapped lines that had room to spare.

    @Test
    fun `a wider pty wraps later`() = runTest {
        val e = smallEngine(cols = 10, rows = 3)
        e.resize(20, 3)
        e.feed("0123456789ABCDEFGHIJ")   // exactly fills the wider row
        assertEquals(listOf("0123456789ABCDEFGHIJ"), e.text)
        assertEquals(0, e.cursorRow)
        e.feed("X")
        assertEquals(1, e.cursorRow)
    }

    @Test
    fun `growing the grid keeps what is already on screen`() = runTest {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("one\r\ntwo\r\n")
        e.resize(10, 8)
        assertEquals(8, e.rows)
        assertEquals(listOf("one", "two", ""), e.text)
        assertEquals(2, e.cursorRow)
    }

    @Test
    fun `shrinking the grid scrolls rows off the top rather than losing them`() = runTest {
        val e = smallEngine(cols = 10, rows = 4)
        e.feed("one\r\ntwo\r\nthree\r\n")
        assertEquals(3, e.cursorRow)
        e.resize(10, 2)
        assertEquals(2, e.rows)
        // Every row survives; the cursor is still on the row it was writing.
        assertEquals(listOf("one", "two", "three", ""), e.text)
        assertEquals(3, e.cursorRow)
    }

    @Test
    fun `the shell keeps writing correctly after a resize`() = runTest {
        val e = smallEngine(cols = 10, rows = 3)
        e.feed("gw:~# ")            // still inside the narrow row
        e.resize(40, 6)
        e.feed("ls -al /etc\r\n")   // would have wrapped twice at ten columns
        assertEquals(listOf("gw:~# ls -al /etc", ""), e.text)
        assertEquals(1, e.cursorRow)
    }

    @Test
    fun `a degenerate size is floored instead of emptying the grid`() = runTest {
        val e = smallEngine(cols = 10, rows = 3)
        e.resize(0, 0)
        assertEquals(TermEngine.MIN_COLS, e.cols)
        assertEquals(TermEngine.MIN_ROWS, e.rows)
        e.feed("hi")
        assertEquals("hi", e.current)
    }
}
