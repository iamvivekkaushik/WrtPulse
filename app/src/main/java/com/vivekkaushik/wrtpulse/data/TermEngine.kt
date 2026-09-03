package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
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
 * Colour and the other SGR attributes are carried per cell, so a run of `ls --color` or
 * `grep --color` arrives styled rather than stripped.
 *
 * Full-screen applications (top, vi) still garble: no alternate screen and no scrolling
 * regions.
 */
class TermEngine(
    private val session: RouterSession,
    cols: Int = COLS,
    rows: Int = ROWS,
) {

    /**
     * The pty's size. Both the shell's wrap arithmetic and ours are done against it, so it
     * tracks the pane the output is actually drawn in — 48 columns on a tablet would leave
     * half the width empty and wrap text that had room to spare.
     */
    var cols = cols.coerceAtLeast(MIN_COLS)
        private set
    var rows = rows.coerceAtLeast(MIN_ROWS)
        private set

    /** Scrollback followed by the live grid — what the UI draws, top to bottom. */
    val screen = mutableStateListOf(AnnotatedString(""))

    /** Row the caret sits on, as an index into [screen]. */
    var cursorRow by mutableIntStateOf(0)
        private set

    /** Rows above the cursor, as plain text. */
    val lines: List<String> get() = screen.take(cursorRow).map { it.text }

    /** The whole view as plain text — what the grid says, with the styling dropped. */
    val text: List<String> get() = screen.map { it.text }

    /** The row being written — prompt plus echoed input. */
    var current by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var shell: SshShell? = null

    private val scrollback = mutableListOf<AnnotatedString>()
    private val grid = MutableList(rows) { Row() }
    private var r = 0
    private var c = 0

    /** The appearance the shell has selected; every cell written now takes it. */
    private var pen = Attr.Default

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
            // A resize between the request and here would otherwise never reach the pty.
            runCatching { sh.resize(cols, rows) }
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

    /**
     * Re-sizes the pty and the grid under it. Rows pushed off the top go to scrollback the
     * way they would have scrolled there; nothing is re-flowed, so text already on screen
     * keeps the wrap it was drawn with and only new output uses the new width.
     */
    suspend fun resize(cols: Int, rows: Int) {
        val newCols = cols.coerceAtLeast(MIN_COLS)
        val newRows = rows.coerceAtLeast(MIN_ROWS)
        if (newCols == this.cols && newRows == this.rows) return
        this.cols = newCols
        while (grid.size > newRows) {
            // Keep the cursor's row: give up the rows above it first, then the ones below.
            if (r > 0) {
                scrollback.add(grid.removeAt(0).annotated())
                r--
            } else {
                grid.removeAt(grid.size - 1)
            }
        }
        while (grid.size < newRows) grid.add(Row())
        this.rows = newRows
        r = r.coerceIn(0, newRows - 1)
        c = c.coerceIn(0, newCols - 1)
        wrapPending = false
        trimScrollback()
        sync()
        // A pty that has gone away is not worth surfacing — `connected` already says so.
        runCatching { shell?.resize(newCols, newRows) }
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
            val text =
                if (i < scrollback.size) scrollback[i] else grid[i - scrollback.size].annotated()
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
        // Selecting a colour moves nothing, so it must not disturb the deferred wrap or the
        // cursor-home flag that tells `clear` apart from a redraw.
        if (body.last() == 'm') {
            pen = sgr(params, pen)
            return
        }
        fun p(i: Int, default: Int) = params.getOrNull(i)?.takeIf { it > 0 } ?: default
        wrapPending = false
        val wasJustHomed = justHomed
        justHomed = false
        when (body.last()) {
            'K' -> when (p(0, 0)) {                     // erase in line
                1 -> eraseToStart()
                2 -> grid[r].setLength(0)  // whole line
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
                if (c <= line.length) line.insert(c, p(0, 1), pen)
            }
            else -> Unit                                // modes, reports: nothing to draw
        }
    }

    private fun put(ch: Char) {
        if (wrapPending) {
            c = 0
            lineFeed()
            wrapPending = false
        }
        val line = grid[r]
        // The cursor may sit past the text after a move; pad in blanks, unstyled, so an
        // erase-to-end later does not leave a coloured gap behind.
        while (line.length < c) line.append(' ', Attr.Default)
        if (c < line.length) line.setAt(c, ch, pen) else line.append(ch, pen)
        if (c >= cols - 1) wrapPending = true else c++
    }

    /** Down one row, scrolling the top row into scrollback once the grid is full. */
    private fun lineFeed() {
        if (r < rows - 1) {
            r++
            return
        }
        scrollback.add(grid.removeAt(0).annotated())
        grid.add(Row())
        trimScrollback()
    }

    private fun trimScrollback() {
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
        for (i in 0 until minOf(c, line.length)) line.setAt(i, ' ', pen)
    }

    private fun clearGrid() {
        grid.forEach { it.setLength(0) }
    }

    private fun reset() {
        clearGrid()
        scrollback.clear()
        r = 0
        c = 0
        pen = Attr.Default
        wrapPending = false
    }

    companion object {
        /** Opening size, used until the screen has measured itself. */
        const val COLS = 48
        const val ROWS = 30

        /**
         * Floors, so a sliver of a window still leaves a grid the parser can address. Kept
         * low deliberately: real panes are far larger, and tests want a tiny screen.
         */
        const val MIN_COLS = 8
        const val MIN_ROWS = 2

        const val MAX_SCROLLBACK = 500
    }
}
