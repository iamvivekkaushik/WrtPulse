package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshShell

/**
 * A small VT-style screen: a fixed grid the size of the pty, plus scrollback for rows that
 * scroll off the top. The grid is what makes the shell's own arithmetic work — it counts
 * rows and columns against the size it was given, then addresses them absolutely (`clear`
 * homes the cursor before erasing) or relatively (history recall steps back up over a
 * command that wrapped).
 *
 * Full-screen applications (top, vi) still garble: no alternate screen, scrolling regions
 * or character attributes.
 */
class TermEngine(
    private val session: RouterSession,
    private val cols: Int = COLS,
    private val rows: Int = ROWS,
) {

    /** Scrollback followed by the live grid — what the UI draws, top to bottom. */
    val screen = mutableStateListOf("")

    /** Row the caret sits on, as an index into [screen]. */
    var cursorRow by mutableIntStateOf(0)
        private set

    /** Rows above the cursor. */
    val lines: List<String> get() = screen.take(cursorRow)

    /** The row being written — prompt plus echoed input. */
    var current by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var shell: SshShell? = null

    private val scrollback = mutableListOf<String>()
    private val grid = MutableList(rows) { StringBuilder() }
    private var r = 0
    private var c = 0

    /**
     * DEC deferred wrap: a character written in the last column leaves the cursor there
     * until the next one arrives, so a line that exactly fills the width doesn't wrap early.
     */
    private var wrapPending = false

    /**
     * True only between a cursor-home and the very next byte. `clear` is literally
     * ESC[H ESC[J, while ash's line redraw reaches column 0 with CR — telling them apart
     * decides whether an erase-to-end wipes the whole view or just trims the row.
     */
    private var justHomed = false

    private val seq = StringBuilder()
    private var state = Parse.TEXT

    /** Where the escape parser is: plain text, after ESC, inside CSI, or inside OSC. */
    private enum class Parse { TEXT, ESC, CSI, OSC }

    suspend fun run() {
        try {
            error = null
            val sh = session.openShell(cols, rows)
            shell = sh
            connected = true
            sh.output.collect { chunk -> feed(chunk) }
        } catch (e: SshException) {
            error = e.message
        } finally {
            connected = false
            runCatching { shell?.close() }
            shell = null
        }
    }

    suspend fun send(text: String) {
        try {
            shell?.write(text)
        } catch (e: SshException) {
            error = e.message
            connected = false
        }
    }

    internal fun feed(chunk: String) {
        chunk.forEach { ch -> feedChar(ch) }
        sync()
    }

    /** Mirrors scrollback plus the used part of the grid into Compose state. */
    private fun sync() {
        val lastUsed = maxOf(grid.indexOfLast { it.isNotEmpty() }, r)
        val size = scrollback.size + lastUsed + 1
        while (screen.size > size) screen.removeAt(screen.size - 1)
        for (i in 0 until size) {
            val text = if (i < scrollback.size) scrollback[i] else grid[i - scrollback.size].toString()
            if (i < screen.size) {
                if (screen[i] != text) screen[i] = text
            } else {
                screen.add(text)
            }
        }
        cursorRow = scrollback.size + r
        current = grid[r].toString()
    }

    private fun feedChar(ch: Char) {
        when (state) {
            Parse.TEXT -> text(ch)
            Parse.ESC -> escape(ch)
            Parse.CSI -> {
                seq.append(ch)
                // Parameters and intermediates run 0x20..0x3F; the first byte at 0x40..0x7E ends it.
                if (ch.code in 0x40..0x7E) {
                    csi(seq.toString())
                    seq.setLength(0)
                    state = Parse.TEXT
                }
            }
            Parse.OSC -> {
                // Terminated by BEL, or by ST (ESC backslash).
                if (ch == '' || (ch == '\\' && seq.endsWith(""))) {
                    seq.setLength(0)
                    state = Parse.TEXT
                } else {
                    seq.append(ch)
                }
            }
        }
    }

    private fun text(ch: Char) {
        if (ch != '\u001b') justHomed = false
        when (ch) {
            '' -> { state = Parse.ESC; seq.setLength(0) }
            '\r' -> { c = 0; wrapPending = false }
            '\n' -> { lineFeed(); wrapPending = false }
            '\b' -> { if (c > 0) c--; wrapPending = false }
            '', '' -> Unit // bell, stray DEL
            '\t' -> {
                val stop = minOf(((c / 8) + 1) * 8, cols - 1)
                while (c < stop) put(' ')
            }
            else -> if (ch.code >= 0x20) put(ch)
        }
    }

    private fun escape(ch: Char) {
        // Charset selectors (ESC ( B and friends) carry one more byte that is not text.
        if (seq.isEmpty() && (ch == '(' || ch == ')' || ch == '#' || ch == '%')) {
            seq.append(ch)
            return
        }
        if (seq.isNotEmpty()) { // second byte of a charset selector — swallow it
            seq.setLength(0)
            state = Parse.TEXT
            return
        }
        when (ch) {
            '[' -> { state = Parse.CSI; seq.setLength(0) }
            ']' -> { state = Parse.OSC; seq.setLength(0) }
            'c' -> { reset(); state = Parse.TEXT } // RIS
            else -> state = Parse.TEXT
        }
    }

    /**
     * The sequences busybox ash and `clear` actually use. Erase-in-display defaults to
     * "cursor to end", NOT "whole screen"; cursor position is absolute within the grid,
     * which is what makes `clear` (home, then erase) clear anything at all.
     */
    private fun csi(body: String) {
        if (body.firstOrNull() == '?') return // private modes: cursor visibility, bracketed paste
        val params = body.dropLast(1).split(';').map { it.toIntOrNull() ?: 0 }
        fun p(i: Int, default: Int) = params.getOrNull(i)?.takeIf { it > 0 } ?: default
        wrapPending = false
        val wasJustHomed = justHomed
        justHomed = false
        when (body.last()) {
            'K' -> when (p(0, 0)) {                     // erase in line
                1 -> eraseToStart()
                2 -> grid[r].setLength(0)
                else -> eraseToEnd()
            }
            'J' -> when (p(0, 0)) {                     // erase in display
                1 -> eraseToStart()
                // The UI is one continuous column with no separate scrollback pane, so a
                // screen-clear that left old rows rendered above would look like `clear`
                // did nothing. Drop the scrollback with it.
                2, 3 -> { clearGrid(); scrollback.clear() }
                // Erase-to-end straight after a home is busybox's `clear` (ESC[H ESC[J):
                // wipe the view. A redraw that merely returned to column 0 with CR is not.
                else -> if (wasJustHomed) {
                    clearGrid()
                    scrollback.clear()
                } else {
                    eraseToEnd()
                    for (i in r + 1 until rows) grid[i].setLength(0)
                }
            }
            'A' -> r = (r - p(0, 1)).coerceAtLeast(0)   // up: rewrite a command that wrapped
            'B' -> r = (r + p(0, 1)).coerceAtMost(rows - 1)
            'D' -> c = (c - p(0, 1)).coerceAtLeast(0)
            'C' -> c = (c + p(0, 1)).coerceAtMost(cols - 1)
            'G' -> c = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> r = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {                               // absolute position; `clear` homes here
                r = (p(0, 1) - 1).coerceIn(0, rows - 1)
                c = (p(1, 1) - 1).coerceIn(0, cols - 1)
                justHomed = r == 0 && c == 0
            }
            'P' -> {                                    // delete characters
                val line = grid[r]
                if (c < line.length) line.delete(c, (c + p(0, 1)).coerceAtMost(line.length))
            }
            '@' -> {                                    // insert blanks
                val line = grid[r]
                if (c <= line.length) line.insert(c, " ".repeat(p(0, 1)))
            }
            else -> Unit                                // SGR colours, modes, reports: nothing to draw
        }
    }

    private fun put(ch: Char) {
        if (wrapPending) {
            c = 0
            lineFeed()
            wrapPending = false
        }
        val line = grid[r]
        while (line.length < c) line.append(' ') // cursor may sit past the text after a move
        if (c < line.length) line.setCharAt(c, ch) else line.append(ch)
        if (c >= cols - 1) wrapPending = true else c++
    }

    /** Down one row, scrolling the top row into scrollback once the grid is full. */
    private fun lineFeed() {
        if (r < rows - 1) {
            r++
            return
        }
        scrollback.add(grid.removeAt(0).toString())
        grid.add(StringBuilder())
        if (scrollback.size > MAX_SCROLLBACK) {
            repeat(scrollback.size - MAX_SCROLLBACK) { scrollback.removeAt(0) }
        }
    }

    private fun eraseToEnd() {
        val line = grid[r]
        if (c < line.length) line.setLength(c)
    }

    private fun eraseToStart() {
        val line = grid[r]
        for (i in 0 until minOf(c, line.length)) line.setCharAt(i, ' ')
    }

    private fun clearGrid() {
        grid.forEach { it.setLength(0) }
    }

    private fun reset() {
        clearGrid()
        scrollback.clear()
        r = 0
        c = 0
        wrapPending = false
    }

    companion object {
        /** Matches the pty requested for the shell, so the shell's wrap maths agrees with ours. */
        const val COLS = 48
        const val ROWS = 30
        const val MAX_SCROLLBACK = 500
    }
}
