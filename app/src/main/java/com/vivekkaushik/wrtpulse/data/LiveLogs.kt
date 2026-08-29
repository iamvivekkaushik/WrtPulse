package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.LogEntry
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.delay

/** `logread -f` as a growing list of parsed [LogLine]s. Reconnects if the stream drops. */
class LiveLogs(private val session: RouterSession) {

    val logs = mutableStateListOf<LogLine>()
    var newLines by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set

    suspend fun run() {
        while (true) {
            try {
                val flow = session.streamLines(Commands.LOG_FOLLOW)
                running = true
                flow.collect { line -> add(line) }
                running = false // stream ended: link dropped
            } catch (e: SshException) {
                running = false
                if (e is SshException.HostKeyChanged) return
            }
            delay(2_000)
        }
    }

    private fun add(line: String) {
        val entry = Parsers.logread(line) ?: return
        logs.add(entry.toLogLine(line))
        if (logs.size > MAX) {
            repeat(logs.size - MAX) { logs.removeAt(0) }
        }
        if (newLines < 999) newLines++
    }

    fun clearNewLines() {
        newLines = 0
    }

    companion object {
        const val MAX = 400

        fun LogEntry.toLogLine(raw: String) = LogLine(time, colorFor(severity, src), src, msg, tok, raw)

        fun colorFor(severity: String, src: String): Color = when {
            severity in listOf("err", "crit", "alert", "emerg") -> Wrt.Red
            severity in listOf("warn", "warning") -> Wrt.Amber
            src.startsWith("dnsmasq") -> Wrt.Blue
            src.startsWith("hostapd") || src.startsWith("wpa_supplicant") -> Wrt.Amber
            src.startsWith("dropbear") -> Wrt.Accent
            src == "kernel" -> Wrt.TextTertiary
            src.startsWith("firewall") -> Wrt.Red
            else -> Wrt.TextSecondary
        }
    }
}
