package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshShell

/**
 * A deliberately small terminal: keystrokes go straight to the PTY and the screen renders
 * what comes back (the shell provides echo, history, completion). Handles the control
 * sequences busybox ash actually emits while line-editing — CR, backspace, erase-to-EOL —
 * and strips the rest. Full-screen apps (top, vi) will garble; that's out of scope.
 */
class TermEngine(private val session: RouterSession) {

    /** Completed lines, oldest first. */
    val lines = mutableStateListOf<String>()

    /** The line still being written — prompt plus echoed input. */
    var current by mutableStateOf("")
        private set
    var connected by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var shell: SshShell? = null
    private val cur = StringBuilder()
    private var col = 0
    private val esc = StringBuilder()
    private var inEsc = false

    suspend fun run() {
        try {
            error = null
            val sh = session.openShell()
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
        chunk.forEach { c -> feedChar(c) }
        current = cur.toString()
    }

    private fun feedChar(c: Char) {
        if (inEsc) {
            esc.append(c)
            val body = esc.toString()
            val done = when {
                body.startsWith("[") -> body.length > 1 && c.code in 0x40..0x7E   // CSI ... final byte
                body.startsWith("]") -> c == '\u0007'                             // OSC ... BEL
                else -> true                                                      // ESC + single char
            }
            if (done) {
                if (body.startsWith("[") && c == 'K') eraseToEnd()
                if (body.startsWith("[") && c == 'J') { lines.clear(); resetLine() }
                inEsc = false
                esc.setLength(0)
            }
            return
        }
        when (c) {
            '\u001b' -> inEsc = true
            '\r' -> col = 0
            '\n' -> {
                lines.add(cur.toString())
                if (lines.size > MAX_LINES) repeat(lines.size - MAX_LINES) { lines.removeAt(0) }
                resetLine()
            }
            '\b' -> if (col > 0) col--
            '\u0007' -> Unit // bell
            else -> if (c.code >= 0x20 || c == '\t') {
                if (col < cur.length) cur.setCharAt(col, c) else cur.append(c)
                col++
            }
        }
    }

    private fun eraseToEnd() {
        if (col < cur.length) cur.setLength(col)
    }

    private fun resetLine() {
        cur.setLength(0)
        col = 0
    }

    companion object {
        const val MAX_LINES = 500
    }
}
