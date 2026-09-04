package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
    /**
     * Round-trip time to the router, measured with a no-op command.
     *
     * This used to be the wall time of the whole batched tick, which is a different thing
     * entirely: on a single-core router the script itself costs 450-1300 ms, so the chip read
     * ~13x the real latency and looked like a network problem. Measured on the reference
     * repeater: tick 520 ms median against an RTT of 40 ms.
     */
    var latencyMs by mutableIntStateOf(0); private set

    /** How long the batched tick took the router to run. Not latency — see [latencyMs]. */
    var tickMs by mutableIntStateOf(0); private set
    var cpuPct by mutableIntStateOf(0); private set
    var ramPct by mutableIntStateOf(0); private set
    var flashPct by mutableIntStateOf(0); private set
    var flashFree by mutableStateOf<String?>(null); private set
    var load1 by mutableFloatStateOf(0f); private set
    var load5 by mutableFloatStateOf(0f); private set
    var load15 by mutableFloatStateOf(0f); private set
    var uptimeLabel by mutableStateOf("—"); private set
    /** Every link with a default route, best first — usually one, two on a failover setup. */
    val upstreams = mutableStateListOf<com.vivekkaushik.wrtpulse.ops.Upstream>()

    /** The one the chart follows: lowest metric, IPv4 preferred. */
    val upstream: com.vivekkaushik.wrtpulse.ops.Upstream? get() = upstreams.firstOrNull()

    /** device → the rate it is carrying right now, Mbps down/up. */
    val rates = mutableStateMapOf<String, Pair<Float, Float>>()

    /** device → "↓ 226 MB · ↑ 120 MB" since boot. */
    val deviceTotals = mutableStateMapOf<String, String>()

    val wanIp: String? get() = upstream?.address
    val wanDevice: String? get() = upstream?.device
    var totals by mutableStateOf<String?>(null); private set

    /** True until the first tick lands, and again whenever a tick fails. */
    var stale by mutableStateOf(true); private set

    private var prevCpu: CpuSample? = null

    /** device → (rx, tx) at the previous tick. A device with no entry cannot be differenced. */
    private val prevCounters = mutableMapOf<String, Pair<Long, Long>>()
    private var prevAtNanos = 0L

    suspend fun run(intervalMs: Long = 1_000L) {
        var ticks = 0
        while (true) {
            val started = System.nanoTime()
            try {
                val result = session.exec(Commands.DASHBOARD_TICK, timeoutMs = 8_000)
                tickMs = ((System.nanoTime() - started) / 1_000_000L).toInt()
                ingest(Parsers.sections(result.stdout), System.nanoTime())
                stale = false
                // One extra round trip, and not on every tick: this codebase batches
                // precisely to avoid paying per round trip, and a latency chip does not need
                // to update at 1 Hz.
                if (ticks % PING_EVERY_TICKS == 0) {
                    session.refreshLatency()?.let { latencyMs = it.toInt() }
                }
            } catch (e: SshException) {
                stale = true
                // A key mismatch is a hard stop — session state is Blocked, nothing to poll.
                if (e is SshException.HostKeyChanged) return
            }
            ticks++
            delay(nextDelayMs(tickMs, intervalMs))
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
        sections["ifaces"]?.let { json ->
            val essids = Parsers.iwinfoEssids(sections["essid"].orEmpty())
            // A tick where ubus failed answers '{}'; holding the last known links beats
            // blinking the card empty for a second.
            if (json.contains("\"interface\"")) {
                upstreams.clear()
                upstreams.addAll(Parsers.upstreams(json, essids))
            }
        }
        sections["overlay"]?.let { line ->
            flashPct = Parsers.overlayUsedPercent(line)
            flashFree = Parsers.overlayAvailKb(line)?.let { "${bytesLabel(it * 1024)} free" }
        }
        sections["netdev"]?.let { text ->
            val counters = Parsers.netCounters(text)
            // Whether a device can be differenced is decided per device below; all this
            // needs to know is that enough time passed to divide by.
            val dt = (nowNanos - prevAtNanos) / 1e9
            val measurable = dt > 0.2
            // Counters belong to a device, so they are differenced per device. A link that
            // has only just appeared — an uplink failing over to Wi-Fi, say — has nothing to
            // difference against, and seeding it beats reporting the whole of its history as
            // one second's traffic.
            upstreams.forEach { link ->
                val now = counters[link.device] ?: return@forEach
                val previous = prevCounters[link.device]
                if (measurable && previous != null) {
                    val rate = mbps(now.rxBytes - previous.first, dt) to
                        mbps(now.txBytes - previous.second, dt)
                    rates[link.device] = rate
                    if (link == upstreams.first()) {
                        shift(down, rate.first)
                        shift(up, rate.second)
                    }
                }
                deviceTotals[link.device] =
                    "since boot · ↓ ${bytesLabel(now.rxBytes)} · ↑ ${bytesLabel(now.txBytes)}"
            }
            prevCounters.clear()
            counters.forEach { (device, c) -> prevCounters[device] = c.rxBytes to c.txBytes }
            prevAtNanos = nowNanos
            totals = upstream?.device?.let(deviceTotals::get)
        }
    }

    private fun shift(list: MutableList<Float>, v: Float) {
        list.removeAt(0)
        list.add(v)
    }

    companion object {
        const val WINDOW = 60
        const val SPARK = 12

        /** Latency is measured every Nth tick; the chip does not need it at 1 Hz. */
        const val PING_EVERY_TICKS = 4

        /** However slow the router, the dashboard still updates at least this often. */
        const val MAX_GAP_MS = 5_000L

        /**
         * How long to wait before the next tick.
         *
         * A fixed 1 s cadence assumes the poll is cheap. It is not on a single-core router:
         * the batched script measured 450-1300 ms on the reference repeater, so a flat 1 s
         * delay had the app polling almost back to back, making it a measurable part of the
         * 73% CPU it was reporting. Waiting at least as long as the last tick took holds the
         * duty cycle at or below half, and changes nothing on a fast router where the tick
         * is a few tens of milliseconds.
         */
        fun nextDelayMs(lastTickMs: Int, base: Long, cap: Long = MAX_GAP_MS): Long =
            lastTickMs.toLong().coerceIn(base, cap)

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
