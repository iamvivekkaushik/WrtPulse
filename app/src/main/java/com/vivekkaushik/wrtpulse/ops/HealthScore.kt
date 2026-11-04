package com.vivekkaushik.wrtpulse.ops

/**
 * The network health score: one number out of a hundred, four subscores behind it, and every
 * point it takes away is tied to a measurement the screen can show.
 *
 * There is no machine learning here and no provider round trip — the score is computed on the
 * phone from the sweep and the 60-second sample, so it arrives with the data instead of after
 * it. Deductions are deliberately blunt: a link below -80 dBm costs 20, a CPU pegged at 95%
 * costs 25, and the thresholds are the same ones [LocalDiagnostics] flags, so the score and
 * the findings never disagree.
 */
data class HealthScore(
    val overall: Int,
    val wifi: Int,
    /** Null when no mesh/WDS link is configured — the card shows "—" instead of a fake 100. */
    val mesh: Int?,
    val performance: Int,
    val security: Int,
    val meshConfigured: Boolean,
    val grade: Grade,
) {
    enum class Grade(val label: String) { HEALTHY("HEALTHY"), ATTENTION("NEEDS ATTENTION"), RISK("AT RISK") }

    companion object {
        fun of(overall: Int, wifi: Int, mesh: Int?, performance: Int, security: Int): HealthScore {
            val g = when {
                overall >= 80 -> Grade.HEALTHY
                overall >= 60 -> Grade.ATTENTION
                else -> Grade.RISK
            }
            return HealthScore(
                overall = overall.coerceIn(0, 100),
                wifi = wifi.coerceIn(0, 100),
                mesh = mesh?.coerceIn(0, 100),
                performance = performance.coerceIn(0, 100),
                security = security.coerceIn(0, 100),
                meshConfigured = mesh != null,
                grade = g,
            )
        }
    }
}

object NetworkHealth {

    /** Same thresholds the local findings use — one story, two presentations. */
    private const val WEAK_SIGNAL_DBM = -80
    private const val MARGINAL_SIGNAL_DBM = -75
    private const val RETRY_RATE_HIGH = 1_000.0
    private const val RETRY_RATE_MED = 200.0

    /**
     * Weights the overall score is blended from. Mesh only counts when it exists; without a
     * mesh its weight folds into Wi-Fi, because the same radios carry both.
     */
    private const val W_WIFI = 0.35
    private const val W_MESH = 0.15
    private const val W_PERF = 0.30
    private const val W_SEC = 0.20

    /**
     * Computes the score from the sweep's redacted sections and the temporal deltas. Both are
     * optional in the sense that a missing piece just scores from what is there — but a score
     * with no sample behind it is still labelled with what it did not measure.
     */
    fun compute(sections: Map<String, String>, temporal: TemporalStats?): HealthScore {
        val ifaces = LocalDiagnostics.wirelessIfaces(sections)
        val modes = LocalDiagnostics.interfaceModes(sections["wifi_status"].orEmpty())
        val meshConfigured = LocalDiagnostics.meshConfigured(sections)

        val wifi = wifiScore(sections, temporal, ifaces, modes)
        val mesh = if (meshConfigured) meshScore(temporal, modes) else null
        val performance = performanceScore(sections, temporal)
        val security = securityScore(ifaces, sections)

        val overall = if (mesh != null) {
            (wifi * W_WIFI + mesh * W_MESH + performance * W_PERF + security * W_SEC).toInt()
        } else {
            // No mesh: its 15% goes to Wi-Fi (35→50), performance and security unchanged.
            (wifi * (W_WIFI + W_MESH) + performance * W_PERF + security * W_SEC).toInt()
        }
        return HealthScore.of(overall, wifi, mesh, performance, security)
    }

    private fun wifiScore(
        sections: Map<String, String>,
        temporal: TemporalStats?,
        ifaces: List<LocalDiagnostics.WifiIface>,
        modes: Map<String, String>,
    ): Int {
        var score = 100
        // Signal: clients at the edge are the most common Wi-Fi complaint on the wire.
        val stations = if (temporal != null && temporal.stations.isNotEmpty()) {
            temporal.stations.map { Triple(it.iface, it.signalDbm, LocalDiagnostics.isBackhaulIface(it.iface, ifaces, modes)) }
        } else {
            Parsers.stations(sections["assoc"].orEmpty()).map {
                Triple(it.iface, it.signalDbm, LocalDiagnostics.isBackhaulIface(it.iface, ifaces, modes))
            }
        }
        val clientSignals = stations.filter { !it.third }.map { it.second }
        val worstClient = clientSignals.minOrNull() ?: 0
        when {
            worstClient < -85 -> score -= 30
            worstClient < WEAK_SIGNAL_DBM -> score -= 20
            worstClient < MARGINAL_SIGNAL_DBM -> score -= 10
        }
        // Retransmissions: retries per minute and failed frames over the sample window.
        if (temporal != null) {
            val minutes = (temporal.windowSeconds / 60.0).coerceAtLeast(1.0 / 60.0)
            val rate = temporal.retryDelta / minutes
            when {
                rate > RETRY_RATE_HIGH -> score -= 25
                rate > RETRY_RATE_MED -> score -= 15
                rate > 50 -> score -= 8
            }
            when {
                temporal.failedDelta > 10 -> score -= 15
                temporal.failedDelta > 0 -> score -= 8
            }
            temporal.gateway?.let { if (it.lossPct > 0) score -= 15 }
        }
        return score
    }

    /** The backhaul's own signal and retries — a mesh is as strong as its weakest hop. */
    private fun meshScore(temporal: TemporalStats?, modes: Map<String, String>): Int {
        var score = 100
        if (temporal == null) return score
        val backhaul = temporal.stations.filter { modes[it.iface] == "sta" || modes[it.iface] == "mesh" }
        val worst = backhaul.minOfOrNull { it.signalDbm } ?: 0
        when {
            worst < -85 -> score -= 30
            worst < WEAK_SIGNAL_DBM -> score -= 20
            worst < MARGINAL_SIGNAL_DBM -> score -= 10
        }
        val minutes = (temporal.windowSeconds / 60.0).coerceAtLeast(1.0 / 60.0)
        val rate = backhaul.sumOf { it.retriesDelta } / minutes
        when {
            rate > RETRY_RATE_HIGH -> score -= 25
            rate > RETRY_RATE_MED -> score -= 15
            rate > 50 -> score -= 8
        }
        when {
            backhaul.sumOf { it.failedDelta } > 10 -> score -= 15
            backhaul.sumOf { it.failedDelta } > 0 -> score -= 8
        }
        return score
    }

    private fun performanceScore(sections: Map<String, String>, temporal: TemporalStats?): Int {
        var score = 100
        if (temporal != null) {
            when {
                temporal.cpuPct != null && temporal.cpuPct >= 95 -> score -= 25
                temporal.cpuPct != null && temporal.cpuPct >= 70 -> score -= 10
            }
            when {
                temporal.load1 >= 4 -> score -= 25
                temporal.load1 >= 2 -> score -= 15
            }
            when {
                temporal.memPct >= 95 -> score -= 25
                temporal.memPct >= 85 -> score -= 15
            }
            val inet = temporal.internet
            val gw = temporal.gateway
            when {
                inet?.lossPct == 100 && gw?.lossPct != 100 -> score -= 25 // internet down
                inet?.lossPct == 100 && gw?.lossPct == 100 -> score -= 30 // everything down
                (inet?.lossPct ?: 0) in 1..99 -> score -= 10
            }
            inet?.avgMs?.let { if (it > 150) score -= 5 }
        } else {
            // No sample yet: fall back to the sweep's own load and RAM reading.
            val info = runCatching { Parsers.systemInfo(sections["info"].orEmpty()) }.getOrNull()
            when {
                (info?.load1 ?: 0.0) >= 4 -> score -= 25
                (info?.load1 ?: 0.0) >= 2 -> score -= 15
            }
        }
        sections["overlay"].orEmpty().lineSequence().firstOrNull()?.let { line ->
            val used = Parsers.overlayUsedPercent(line)
            val availMb = (Parsers.overlayAvailKb(line) ?: 0L) / 1024.0
            when {
                availMb in 0.0..1.0 -> score -= 20
                used >= 90 -> score -= 10
            }
        }
        return score
    }

    private fun securityScore(ifaces: List<LocalDiagnostics.WifiIface>, sections: Map<String, String>): Int {
        var score = 100
        val openNetworks = ifaces.count {
            it.mode == "ap" && it.encryption == "none" && !it.disabled
        }
        if (openNetworks > 0) score -= (openNetworks * 40).coerceAtMost(40)
        if (wanZoneAcceptingInput(sections["firewall"].orEmpty())) score -= 25
        return score
    }

    private fun wanZoneAcceptingInput(firewall: String): Boolean =
        LocalDiagnostics.uciSections(firewall, "zone")
            .any { (_, opts) -> opts["name"] == "wan" && opts["input"] == "ACCEPT" }
}
