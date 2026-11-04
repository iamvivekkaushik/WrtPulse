package com.vivekkaushik.wrtpulse.ops

/**
 * The temporal half of the AI analysis: three point-in-time samples of the router's counters,
 * thirty seconds apart, and the deltas between them.
 *
 * One snapshot can say "tx failed is 21"; only two samples can say "tx failed grew by 21 in
 * the last sixty seconds, all of it on wlan1, while the signal sat at -84 dBm" — which is the
 * difference between a number and a diagnosis. Everything here is parsed and differenced on
 * the phone; the router only ever runs the read-only [Commands.AI_TICK].
 */

/** `ping -q -c 3 <target>` as the three numbers a diagnosis needs. */
data class PingStats(
    val sent: Int,
    val received: Int,
    val avgMs: Double?,
    val maxMs: Double?,
) {
    val lossPct: Int
        get() = if (sent <= 0) 100 else ((sent - received) * 100.0 / sent).toInt().coerceIn(0, 100)
}

/** One associated client as `iw dev <if> station dump` reports it — the retry story. */
data class StationCounters(
    val iface: String,
    val mac: String,
    val signalDbm: Int,
    val txRetries: Long,
    val txFailed: Long,
    val txMbps: Double,
    val rxMbps: Double,
)

/** One sample. Fields are null when the router could not answer that part. */
data class AiTickSample(
    /** Phone clock when the reply was parsed — only a fallback for the window. */
    val atMs: Long,
    /** Router clock, from /proc/uptime — the honest way to measure the window. */
    val uptimeSeconds: Long,
    val load1: Double,
    val cpu: CpuSample?,
    val memTotalKb: Long,
    val memAvailKb: Long,
    val net: Map<String, NetCounters>,
    val stations: List<StationCounters>,
    val ping: PingStats?,
    val internet: PingStats?,
) {
    val memPct: Int
        get() = if (memTotalKb <= 0) 0 else (((memTotalKb - memAvailKb) * 100.0) / memTotalKb).toInt().coerceIn(0, 100)
}

/** A per-client delta over the sampling window — the evidence behind a "weak link" claim. */
data class StationDelta(
    val iface: String,
    val mac: String,
    val signalDbm: Int,
    val retriesDelta: Long,
    val failedDelta: Long,
    val txMbps: Double,
)

/** One interface's traffic rate over the window. */
data class InterfaceRate(
    val iface: String,
    val rxKbS: Double,
    val txKbS: Double,
)

/** What the samples proved when compared. Null fields mean "could not be measured", not zero. */
data class TemporalStats(
    val windowSeconds: Double,
    val sampleCount: Int,
    val cpuPct: Int?,
    val load1: Double,
    val loadDelta: Double,
    val memPct: Int,
    val memPctDelta: Int,
    val retryDelta: Long,
    val failedDelta: Long,
    val worstSignalDbm: Int?,
    val gateway: PingStats?,
    val internet: PingStats?,
    val stations: List<StationDelta>,
    val interfaces: List<InterfaceRate>,
) {
    /** The two-line summary the report and the payload both carry. */
    fun summaryLines(): List<String> = buildList {
        add("window: %.0f s over $sampleCount samples".format(windowSeconds))
        cpuPct?.let { add("CPU $it%") } ?: add("CPU n/a (needs two samples)")
        add("load %.2f (%+.2f)".format(load1, loadDelta))
        add("RAM $memPct% (${if (memPctDelta >= 0) "+" else ""}$memPctDelta)")
        if (retryDelta > 0 || failedDelta > 0) {
            add("tx retries +${"%,d".format(retryDelta)}, tx failed +${"%,d".format(failedDelta)}")
        } else {
            add("tx retries stable, tx failed stable")
        }
        worstSignalDbm?.let { add("worst signal $it dBm") }
        gateway?.let { add("gateway ping ${it.lossPct}% loss${it.avgMs?.let { a -> ", %.0f ms avg".format(a) } ?: ""}") }
        internet?.let { add("internet ping ${it.lossPct}% loss${it.avgMs?.let { a -> ", %.0f ms avg".format(a) } ?: ""}") }
        interfaces.sortedByDescending { it.rxKbS + it.txKbS }.take(3).forEach {
            add("%s: rx %.0f kB/s, tx %.0f kB/s".format(it.iface, it.rxKbS, it.txKbS))
        }
    }
}

object AiSampling {

    /** The default cadence: three samples spanning sixty seconds. */
    const val SAMPLE_COUNT = 3
    const val GAP_MS = 30_000L

    /**
     * Parses one tick's sections ([Parsers.sections] output of [Commands.AI_TICK]). Missing
     * parts stay null/empty rather than zero — a router without `iw` has no retry numbers,
     * and reporting that as "0 retries" would be a lie.
     */
    fun parseTick(sections: Map<String, String>, atMs: Long): AiTickSample {
        val uptime = sections["uptime"].orEmpty().trim().toLongOrNull() ?: 0L
        val load1 = sections["loadavg"].orEmpty().trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: 0.0
        val cpu = sections["cpu"].orEmpty().lineSequence().firstOrNull()?.let(Parsers::cpuSample)
        val memTotal = memKb(sections["mem"].orEmpty(), "MemTotal")
        val memAvail = memKb(sections["mem"].orEmpty(), "MemAvailable")
        val net = Parsers.netCounters(sections["netdev"].orEmpty())
        val stations = stationCounters(sections["assoc"].orEmpty())
        return AiTickSample(
            atMs = atMs,
            uptimeSeconds = uptime,
            load1 = load1,
            cpu = cpu,
            memTotalKb = memTotal,
            memAvailKb = memAvail,
            net = net,
            stations = stations,
            ping = pingStats(sections["ping"].orEmpty()),
            internet = pingStats(sections["inet"].orEmpty()),
        )
    }

    /**
     * Differences the samples. Returns null unless at least two answered with an uptime —
     * one sample has no deltas to offer and must not be presented as "stable".
     */
    fun analyze(samples: List<AiTickSample>): TemporalStats? {
        val usable = samples.filter { it.uptimeSeconds > 0 }
        if (usable.size < 2) return null
        val first = usable.first()
        val last = usable.last()
        val window = (last.uptimeSeconds - first.uptimeSeconds).toDouble().let {
            if (it > 0) it else (last.atMs - first.atMs) / 1000.0
        }.coerceAtLeast(1.0)

        val cpuPct = last.cpu?.percentSince(first.cpu)

        val firstStations = first.stations.associateBy { it.mac }
        val stationDeltas = last.stations.mapNotNull { now ->
            val was = firstStations[now.mac] ?: return@mapNotNull null
            // A reconnect resets the counters; a negative delta is a reset, not a cure.
            StationDelta(
                iface = now.iface,
                mac = now.mac,
                signalDbm = now.signalDbm,
                retriesDelta = (now.txRetries - was.txRetries).coerceAtLeast(0),
                failedDelta = (now.txFailed - was.txFailed).coerceAtLeast(0),
                txMbps = now.txMbps,
            )
        }
        val retryDelta = stationDeltas.sumOf { it.retriesDelta }
        val failedDelta = stationDeltas.sumOf { it.failedDelta }

        val firstNet = first.net
        val interfaces = last.net.values.mapNotNull { now ->
            val was = firstNet[now.iface] ?: return@mapNotNull null
            InterfaceRate(
                iface = now.iface,
                rxKbS = (now.rxBytes - was.rxBytes).coerceAtLeast(0) / window / 1024.0,
                txKbS = (now.txBytes - was.txBytes).coerceAtLeast(0) / window / 1024.0,
            )
        }.filter { it.rxKbS > 0.1 || it.txKbS > 0.1 }

        return TemporalStats(
            windowSeconds = window,
            sampleCount = usable.size,
            cpuPct = cpuPct,
            load1 = last.load1,
            loadDelta = last.load1 - first.load1,
            memPct = last.memPct,
            memPctDelta = last.memPct - first.memPct,
            retryDelta = retryDelta,
            failedDelta = failedDelta,
            worstSignalDbm = last.stations.filter { it.signalDbm < 0 }.minOfOrNull { it.signalDbm },
            gateway = combinePings(usable.mapNotNull { it.ping }),
            internet = combinePings(usable.mapNotNull { it.internet }),
            stations = stationDeltas,
            interfaces = interfaces,
        )
    }

    /** Several pings to the same target, folded: totals for loss, mean of means, worst max. */
    private fun combinePings(stats: List<PingStats>): PingStats? {
        if (stats.isEmpty()) return null
        val sent = stats.sumOf { it.sent }
        val received = stats.sumOf { it.received }
        val avgs = stats.mapNotNull { it.avgMs }
        val maxes = stats.mapNotNull { it.maxMs }
        return PingStats(
            sent = sent,
            received = received,
            avgMs = avgs.takeIf { it.isNotEmpty() }?.average(),
            maxMs = maxes.maxOrNull(),
        )
    }

    /** `MemTotal:      61440 kB` → 61440. */
    private fun memKb(text: String, key: String): Long =
        text.lineSequence().firstOrNull { it.startsWith("$key:") }
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?.firstOrNull() ?: 0L

    /**
     * `ping -q -c 3 <target>` in BusyBox shape:
     * `3 packets transmitted, 3 packets received, 0% packet loss` and
     * `round-trip min/avg/max = 12.1/14.7/18.2 ms`. No output at all (empty section) is a
     * target that never answered — reported as total loss rather than skipped, because the
     * difference between them is exactly what the outage diagnosis turns on.
     */
    private fun pingStats(text: String): PingStats? {
        if (text.isBlank()) return null
        val sent = Regex("""(\d+) packets? transmitted""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val received = Regex("""(\d+) packets? received""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (sent == 0 && received == 0) {
            // No summary line: the ping binary itself failed (no route, bad address).
            return PingStats(sent = 3, received = 0, avgMs = null, maxMs = null)
        }
        val avg = Regex("""min/avg/max(?: stddev)? = [0-9.]+/([0-9.]+)/""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val max = Regex("""min/avg/max(?: stddev)? = [0-9.]+/[0-9.]+/([0-9.]+)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return PingStats(sent = sent, received = received, avgMs = avg, maxMs = max)
    }

    /**
     * `iw station dump` per station: signal, rates, and the `tx retries` / `tx failed` that
     * only this tool reports. The iwinfo fallback shape (one MAC-headed row per client)
     * carries no retry counters — those stations parse with zeros and the analysis says
     * "not measured" rather than "zero".
     */
    private fun stationCounters(text: String): List<StationCounters> {
        val out = mutableListOf<StationCounters>()
        var iface = ""
        var mac: String? = null
        var signal = 0
        var retries = 0L
        var failed = 0L
        var tx = 0.0
        var rx = 0.0
        fun flush() {
            mac?.let {
                out += StationCounters(iface, it, signal, retries, failed, tx, rx)
            }
            mac = null; signal = 0; retries = 0L; failed = 0L; tx = 0.0; rx = 0.0
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") -> flush()
                line.startsWith("Station ") -> {
                    flush()
                    mac = Regex("""\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""").find(line)?.value?.lowercase()
                    Regex("""\(on ([^)]+)\)""").find(line)?.let { iface = it.groupValues[1] }
                }
                // iwinfo assoclist: "aa:bb:..  -52 dBm / -95 dBm (SNR 43)  0 ms ago"
                MAC_ROW.containsMatchIn(line) -> {
                    flush()
                    mac = MAC_ROW.find(line)!!.value.lowercase()
                    signal = Regex("(-?\\d+) dBm").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                line.startsWith("signal:") && mac != null ->
                    signal = Regex("(-?\\d+)").find(line.removePrefix("signal:"))?.groupValues?.get(1)?.toIntOrNull() ?: signal
                line.startsWith("tx retries:") ->
                    retries = line.removePrefix("tx retries:").trim().toLongOrNull() ?: retries
                line.startsWith("tx failed:") ->
                    failed = line.removePrefix("tx failed:").trim().toLongOrNull() ?: failed
                line.startsWith("tx bitrate:") -> tx = rateOf(line)
                line.startsWith("rx bitrate:") -> rx = rateOf(line)
            }
        }
        flush()
        return out
    }

    private val MAC_ROW = Regex("""^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")

    private fun rateOf(line: String): Double =
        Regex("([0-9.]+) MBit/s").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}
