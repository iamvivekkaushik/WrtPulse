package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.CpuSample
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.delay

/**
 * Live dashboard state fed by ONE batched command per tick on the shared session.
 * Mirrors [LiveTicker]'s shape so the dashboard renders either source unchanged.
 */
class Telemetry(private val session: RouterSession) {

    /** WAN throughput, Mbps, sliding window — newest point last. */
    val down = mutableStateListOf<Float>().apply { repeat(WINDOW) { add(0f) } }
    val up = mutableStateListOf<Float>().apply { repeat(WINDOW) { add(0f) } }
    val cpuSpark = mutableStateListOf<Float>().apply { repeat(SPARK) { add(0f) } }
    val ramSpark = mutableStateListOf<Float>().apply { repeat(SPARK) { add(0f) } }

    /** Round trip of the whole tick command — what the latency chip honestly measures. */
    var latencyMs by mutableIntStateOf(0); private set
    var cpuPct by mutableIntStateOf(0); private set
    var ramPct by mutableIntStateOf(0); private set
    var flashPct by mutableIntStateOf(0); private set
    var load1 by mutableFloatStateOf(0f); private set
    var load5 by mutableFloatStateOf(0f); private set
    var load15 by mutableFloatStateOf(0f); private set
    var uptimeLabel by mutableStateOf("—"); private set
    var wanIp by mutableStateOf<String?>(null); private set
    var wanDevice by mutableStateOf<String?>(null); private set
    var totals by mutableStateOf<String?>(null); private set

    /** True until the first tick lands, and again whenever a tick fails. */
    var stale by mutableStateOf(true); private set

    private var prevCpu: CpuSample? = null
    private var prevRx = -1L
    private var prevTx = -1L
    private var prevAtNanos = 0L

    suspend fun run(tickMs: Long = 1_000L) {
        while (true) {
            val started = System.nanoTime()
            try {
                val result = session.exec(Commands.DASHBOARD_TICK, timeoutMs = 8_000)
                latencyMs = ((System.nanoTime() - started) / 1_000_000L).toInt()
                ingest(Parsers.sections(result.stdout), System.nanoTime())
                stale = false
            } catch (e: SshException) {
                stale = true
                // A key mismatch is a hard stop — session state is Blocked, nothing to poll.
                if (e is SshException.HostKeyChanged) return
            }
            delay(tickMs)
        }
    }

    internal fun ingest(sections: Map<String, String>, nowNanos: Long) {
        sections["info"]?.let { json ->
            runCatching { Parsers.systemInfo(json) }.getOrNull()?.let { info ->
                ramPct = info.memUsedPercent
                uptimeLabel = info.uptimeLabel
                load1 = info.load1.toFloat()
                load5 = info.load5.toFloat()
                load15 = info.load15.toFloat()
                shift(ramSpark, ramPct.toFloat())
            }
        }
        sections["stat"]?.let { line ->
            Parsers.cpuSample(line)?.let { sample ->
                cpuPct = sample.percentSince(prevCpu)
                prevCpu = sample
                shift(cpuSpark, cpuPct.toFloat())
            }
        }
        sections["wan"]?.let { json ->
            val (address, device) = Parsers.wanStatus(json)
            wanIp = address ?: wanIp
            wanDevice = device ?: wanDevice
        }
        sections["overlay"]?.let { flashPct = Parsers.overlayUsedPercent(it) }
        sections["netdev"]?.let { text ->
            val counters = Parsers.netCounters(text)
            val wan = wanDevice?.let(counters::get) ?: return@let
            if (prevRx >= 0) {
                val dt = (nowNanos - prevAtNanos) / 1e9
                if (dt > 0.2) {
                    shift(down, mbps(wan.rxBytes - prevRx, dt))
                    shift(up, mbps(wan.txBytes - prevTx, dt))
                }
            }
            prevRx = wan.rxBytes
            prevTx = wan.txBytes
            prevAtNanos = nowNanos
            totals = "since boot · ↓ ${bytesLabel(wan.rxBytes)} · ↑ ${bytesLabel(wan.txBytes)}"
        }
    }

    private fun shift(list: MutableList<Float>, v: Float) {
        list.removeAt(0)
        list.add(v)
    }

    companion object {
        const val WINDOW = 60
        const val SPARK = 12

        fun mbps(deltaBytes: Long, dtSeconds: Double): Float =
            if (dtSeconds <= 0 || deltaBytes < 0) 0f
            else (deltaBytes * 8.0 / dtSeconds / 1_000_000.0).toFloat()

        fun bytesLabel(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
            bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
            else -> "%.0f kB".format(bytes / 1e3)
        }

        /**
         * The throughput chart draws values as a % of its height. Both series share one scale
         * (the window peak) so the up line stays proportional to the down line.
         */
        fun normalize(down: List<Float>, up: List<Float>): Pair<List<Float>, List<Float>> {
            val peak = maxOf(down.maxOrNull() ?: 0f, up.maxOrNull() ?: 0f).coerceAtLeast(1f)
            return down.map { it / peak * 100f } to up.map { it / peak * 100f }
        }
    }
}
