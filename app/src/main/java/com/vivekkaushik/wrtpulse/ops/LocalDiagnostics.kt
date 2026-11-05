package com.vivekkaushik.wrtpulse.ops

import org.json.JSONObject

/**
 * The checks that run on the phone, before anything is offered to a provider: a signal below
 * -80 dBm, climbing tx-failed, a full /overlay, two DHCP scopes, a bridge without STP, wpad
 * without mesh support, a dead internet with a live first hop.
 *
 * Every rule has to be provable from data already on the screen: a [Finding] carries its
 * evidence (the numbers), the exact read-only command that reproduces it, and the raw lines it
 * was read from. An AI can still find what these miss — that is the point of sending it more
 * than the verdict — but the obvious answer arriving in milliseconds, offline, costs nothing.
 */

enum class Severity { HIGH, MED, LOW }

/** Which part of the network a finding drags down on the health card. */
enum class Domain { WIFI, MESH, PERFORMANCE, SECURITY }

data class Finding(
    val severity: Severity,
    val domain: Domain,
    /** One line, plain words first: "Mesh backhaul is marginal at -84 dBm". */
    val title: String,
    /** The numbers that prove it: "tx retries +1,875 over 60 s, tx failed +21". */
    val evidence: String,
    /** The exact read-only command that shows the same thing from any shell. */
    val command: String,
    /** The raw lines the numbers came from, capped so one finding cannot fill the screen. */
    val raw: String,
)

object LocalDiagnostics {

    /** Any signal at or beyond this is a client at the edge of the radio's reach. */
    const val WEAK_SIGNAL_DBM = -80
    const val MARGINAL_SIGNAL_DBM = -75

    /** Retries per minute that mean a link is retransmitting more than it is delivering. */
    const val RETRY_RATE_HIGH = 1_000.0
    const val RETRY_RATE_MED = 200.0

    /**
     * Runs every local rule against the sweep's (redacted) sections and, when the 60-second
     * sample ran, its temporal deltas. Order is by severity, then domain, so the first row is
     * the one to fix first.
     */
    fun run(sections: Map<String, String>, temporal: TemporalStats?): List<Finding> {
        val out = mutableListOf<Finding>()
        wirelessIfaces(sections).let { ifaces ->
            signalFindings(sections, temporal, ifaces, out)
            encryptionFindings(ifaces, out)
            meshPackageFindings(sections, ifaces, out)
        }
        temporalFindings(temporal, out)
        overlayFinding(sections, out)
        dhcpFindings(sections, out)
        bridgeFindings(sections, out)
        firewallFindings(sections, out)
        return out.sortedWith(
            compareBy({ it.severity }, { it.domain }),
        )
    }

    // ---------- radio ----------

    /** Weak clients, and weak backhaul links specifically — the Floor1 story. */
    private fun signalFindings(
        sections: Map<String, String>,
        temporal: TemporalStats?,
        ifaces: List<WifiIface>,
        out: MutableList<Finding>,
    ) {
        val modes = interfaceModes(sections["wifi_status"].orEmpty())
        val stations = if (temporal != null && temporal.stations.isNotEmpty()) {
            temporal.stations.map { Triple(it.iface, it.mac, it.signalDbm) }
        } else {
            Parsers.stations(sections["assoc"].orEmpty()).map { Triple(it.iface, it.mac, it.signalDbm) }
        }
        stations.filter { it.third < MARGINAL_SIGNAL_DBM }.forEach { (iface, mac, signal) ->
            val backhaul = isBackhaulIface(iface, ifaces, modes)
            val severity = when {
                signal < WEAK_SIGNAL_DBM -> Severity.HIGH
                else -> Severity.MED
            }
            out += Finding(
                severity = severity,
                domain = if (backhaul) Domain.MESH else Domain.WIFI,
                title = if (backhaul) {
                    "Mesh/WDS backhaul is weak at $signal dBm"
                } else {
                    "A client is at the edge of coverage: $signal dBm"
                },
                evidence = "$mac on $iface at $signal dBm" +
                    (temporal?.let { " during a %.0f s window".format(it.windowSeconds) } ?: ""),
                command = "iw dev $iface station dump",
                raw = rawAround(sections["assoc"].orEmpty(), mac, "signal:"),
            )
        }
    }

    private fun encryptionFindings(ifaces: List<WifiIface>, out: MutableList<Finding>) {
        ifaces.filter { it.mode == "ap" && it.encryption == "none" && !it.disabled }.forEach { iface ->
            out += Finding(
                severity = Severity.HIGH,
                domain = Domain.SECURITY,
                title = "Open network: '${iface.ssid.ifBlank { iface.section }}' has no encryption",
                evidence = "${iface.section}.encryption='none'",
                command = "uci show wireless | grep encryption",
                raw = "${iface.section}.ssid='${iface.ssid}'\n${iface.section}.encryption='none'",
            )
        }
    }

    /**
     * 802.11s mesh needs wpad-mesh-* (or wpad-full); wpad-basic cannot do SAE/11s and the
     * interfaces never come up. WDS over WPA2 works on basic wpad, so only 11s is flagged.
     */
    private fun meshPackageFindings(
        sections: Map<String, String>,
        ifaces: List<WifiIface>,
        out: MutableList<Finding>,
    ) {
        val hasMesh = ifaces.any { it.mode == "mesh" && !it.disabled }
        if (!hasMesh) return
        val packages = sections["packages"].orEmpty()
        val basic = Regex("""wpad-basic(?:-(?:wolfssl|mbedtls|openssl))?\b""").find(packages)?.value
        if (basic != null) {
            out += Finding(
                severity = Severity.HIGH,
                domain = Domain.MESH,
                title = "Mesh is configured but $basic cannot run 802.11s",
                evidence = "wpad-mesh-* or wpad-full is required for mode='mesh'",
                command = "opkg list-installed | grep wpad",
                raw = packages.lineSequence().filter { it.contains("wpad") || it.contains("hostapd") }
                    .joinToString("\n"),
            )
        }
    }

    // ---------- temporal ----------

    private fun temporalFindings(temporal: TemporalStats?, out: MutableList<Finding>) {
        if (temporal == null) return
        val minutes = (temporal.windowSeconds / 60.0).coerceAtLeast(1.0 / 60.0)

        // The retries and failed deltas, blamed on the station that produced them.
        if (temporal.retryDelta > 0 || temporal.failedDelta > 0) {
            val worst = temporal.stations.maxByOrNull { it.retriesDelta + it.failedDelta }
            val rate = temporal.retryDelta / minutes
            val severity = when {
                temporal.failedDelta > 10 || rate > RETRY_RATE_HIGH -> Severity.HIGH
                temporal.failedDelta > 0 || rate > RETRY_RATE_MED -> Severity.MED
                else -> Severity.LOW
            }
            val evidence = buildString {
                if (temporal.retryDelta > 0) {
                    append("tx retries +${"%,d".format(temporal.retryDelta)}")
                    append(" in %.0f s".format(temporal.windowSeconds))
                }
                if (temporal.failedDelta > 0) {
                    if (isNotEmpty()) append(", ")
                    append("tx failed +${"%,d".format(temporal.failedDelta)}")
                }
                worst?.let {
                    append(" — worst link ${it.mac} on ${it.iface} at ${it.signalDbm} dBm")
                }
            }
            // The raw view is rebuilt from the deltas so it always matches the evidence's numbers.
            val rawView = temporal.stations
                .filter { it.retriesDelta > 0 || it.failedDelta > 0 }
                .joinToString("\n") {
                    "%s %s: retries +%,d, failed +%,d, %d dBm".format(
                        it.iface, it.mac, it.retriesDelta, it.failedDelta, it.signalDbm,
                    )
                }
                .ifBlank { temporal.summaryLines().joinToString("\n") }
            out += Finding(
                severity = severity,
                domain = Domain.WIFI,
                title = "A wireless link is retransmitting heavily",
                evidence = evidence,
                command = worst?.let { "iw dev ${it.iface} station dump" } ?: "iw dev wlan0 station dump",
                raw = rawView,
            )
        }

        // Internet bracketed by the gateway ping: lost after the first hop vs lost before it.
        val gw = temporal.gateway
        val inet = temporal.internet
        when {
            inet?.lossPct == 100 && (gw == null || gw.lossPct < 100) -> out += Finding(
                severity = Severity.HIGH,
                domain = Domain.PERFORMANCE,
                title = "Internet is down while the first hop answers",
                evidence = "gateway ${gw?.received ?: 0}/${gw?.sent ?: 0} replies, internet 0/${inet.sent}",
                command = "ping -c 3 -W 2 1.1.1.1",
                raw = "gateway: ${gw?.let { "${it.received}/${it.sent} replies, ${it.lossPct}% loss" } ?: "not measured"}\n" +
                    "internet: 0/${inet.sent} replies, 100% loss",
            )
            inet?.lossPct == 100 && gw?.lossPct == 100 -> out += Finding(
                severity = Severity.HIGH,
                domain = Domain.PERFORMANCE,
                title = "No connectivity at all — the first hop is lost too",
                evidence = "gateway 0/${gw.sent}, internet 0/${inet.sent}",
                command = "ip route show default",
                raw = "gateway: 0/${gw.sent} replies\ninternet: 0/${inet.sent} replies",
            )
            inet != null && inet.lossPct in 1..99 -> out += Finding(
                severity = Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "Packet loss on the internet path: ${inet.lossPct}%",
                evidence = "${inet.received}/${inet.sent} replies" +
                    (inet.maxMs?.let { ", worst ${"%.0f".format(it)} ms" } ?: ""),
                command = "ping -c 10 -W 2 1.1.1.1",
                raw = "internet: ${inet.received}/${inet.sent} replies, ${inet.lossPct}% loss" +
                    (inet.avgMs?.let { ", %.0f ms avg".format(it) } ?: ""),
            )
            gw != null && gw.lossPct in 1..99 -> out += Finding(
                severity = Severity.MED,
                domain = Domain.WIFI,
                title = "Packet loss before the gateway: ${gw.lossPct}%",
                evidence = "${gw.received}/${gw.sent} replies on the first hop — loss on the LAN/Wi-Fi side",
                command = "ping -c 10 -W 2 <gateway>",
                raw = "gateway: ${gw.received}/${gw.sent} replies, ${gw.lossPct}% loss",
            )
        }

        if (temporal.cpuPct != null && temporal.cpuPct >= 90) {
            out += Finding(
                severity = Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "CPU is saturated at ${temporal.cpuPct}%",
                evidence = "load %.2f (%+.2f) over %.0f s".format(
                    temporal.load1, temporal.loadDelta, temporal.windowSeconds,
                ),
                command = "top -b -n 1 | head -n 15",
                raw = temporal.summaryLines().joinToString("\n"),
            )
        }
        if (temporal.memPct >= 85) {
            out += Finding(
                severity = if (temporal.memPct >= 95) Severity.HIGH else Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "Memory pressure: ${temporal.memPct}% used",
                evidence = "RAM moved ${if (temporal.memPctDelta >= 0) "+" else ""}${temporal.memPctDelta} points " +
                    "over %.0f s".format(temporal.windowSeconds),
                command = "free",
                raw = temporal.summaryLines().joinToString("\n"),
            )
        }
    }

    // ---------- static config ----------

    private fun overlayFinding(sections: Map<String, String>, out: MutableList<Finding>) {
        val line = sections["overlay"].orEmpty().lineSequence().firstOrNull() ?: return
        val availKb = Parsers.overlayAvailKb(line) ?: return
        val usedPct = Parsers.overlayUsedPercent(line)
        val availMb = availKb / 1024.0
        when {
            availMb < 1.0 -> out += Finding(
                severity = Severity.HIGH,
                domain = Domain.PERFORMANCE,
                title = "/overlay is full: ${"%.2f".format(availMb)} MB free",
                evidence = "writes will fail — logs, uci commits and package installs break first",
                command = "df -k /overlay",
                raw = line,
            )
            usedPct >= 90 -> out += Finding(
                severity = Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "/overlay is ${usedPct}% full",
                evidence = "${"%.1f".format(availMb)} MB free of the writable partition",
                command = "df -k /overlay",
                raw = line,
            )
        }
    }

    /**
     * Two DHCP servers on one L2 is the classic "internet dies for some clients only": half
     * the leases answer to the wrong gateway. The uci config says how many scopes this router
     * itself runs; a scope on an uplink interface is always wrong.
     */
    private fun dhcpFindings(sections: Map<String, String>, out: MutableList<Finding>) {
        val conf = sections["dhcpconf"].orEmpty()
        if (conf.isBlank()) return
        val scopes = dhcpScopes(conf)
        val active = scopes.filter { !it.ignored }
        val uplinkIfaces = uplinkNetworks(sections["network"].orEmpty())
        val onUplink = active.filter { it.interfaceName in uplinkIfaces }
        when {
            onUplink.isNotEmpty() -> out += Finding(
                severity = Severity.HIGH,
                domain = Domain.SECURITY,
                title = "A DHCP server is running on an uplink interface (${onUplink.joinToString { it.interfaceName }})",
                evidence = "dhcp scope '${onUplink.first().section}' serves '${onUplink.first().interfaceName}', " +
                    "which is an uplink — it hands out this router's LAN to the provider's network",
                command = "uci show dhcp",
                raw = conf.lineSequence().filter { it.contains(".interface=") || it.contains(".ignore=") }
                    .joinToString("\n"),
            )
            active.map { it.interfaceName }.distinct().size > 1 -> out += Finding(
                severity = Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "More than one DHCP scope is active (${active.map { it.interfaceName }.distinct().joinToString()})",
                evidence = "scopes on ${active.joinToString { "'${it.interfaceName}'" }} — a second server on the " +
                    "same segment is the classic half-the-clients-lose-internet misconfiguration",
                command = "uci show dhcp",
                raw = conf.lineSequence().filter { it.contains(".interface=") || it.contains(".ignore=") }
                    .joinToString("\n"),
            )
        }
    }

    /** A bridge with several ports and no STP folds when any two of them get connected. */
    private fun bridgeFindings(sections: Map<String, String>, out: MutableList<Finding>) {
        val network = sections["network"].orEmpty()
        bridges(network).filter { it.portCount > 1 && !it.stp }.forEach { bridge ->
            out += Finding(
                severity = Severity.MED,
                domain = Domain.PERFORMANCE,
                title = "Bridge ${bridge.name} runs ${bridge.portCount} ports without STP",
                evidence = "a loop between two of its ports takes the whole LAN down instead of being blocked",
                command = "uci show network | grep -A2 'type=.bridge.'",
                raw = network.lineSequence()
                    .filter { it.startsWith("network.${bridge.section}.") }
                    .joinToString("\n"),
            )
        }
    }

    private fun firewallFindings(sections: Map<String, String>, out: MutableList<Finding>) {
        val firewall = sections["firewall"].orEmpty()
        wanZoneAcceptingInput(firewall)?.let { zone ->
            out += Finding(
                severity = Severity.MED,
                domain = Domain.SECURITY,
                title = "The wan zone accepts input (${zone.inputPolicy})",
                evidence = "firewall zone '${zone.name}' answers on the uplink; default OpenWrt is REJECT",
                command = "uci show firewall | grep zone",
                raw = zone.lines,
            )
        }
    }

    // ---------- shared parsing (also used by HealthScore) ----------

    /** One `wifi-iface` from `uci show wireless`, anonymous or named. */
    data class WifiIface(
        val section: String,
        val mode: String,
        val ssid: String,
        val encryption: String,
        val wds: Boolean,
        val disabled: Boolean,
    )

    /** `uci show wireless` → every wifi-iface with the options the rules read. */
    fun wirelessIfaces(sections: Map<String, String>): List<WifiIface> =
        uciSections(sections["wireless"].orEmpty(), "wifi-iface").map { (name, opts) ->
            WifiIface(
                section = name,
                mode = opts["mode"].orEmpty(),
                ssid = opts["ssid"].orEmpty(),
                encryption = opts["encryption"].orEmpty(),
                wds = opts["wds"] == "1",
                disabled = opts["disabled"] == "1",
            )
        }

    /** True when the router runs at least one mesh or WDS link — the mesh subscore's gate. */
    fun meshConfigured(sections: Map<String, String>): Boolean =
        wirelessIfaces(sections).any { (!it.disabled) && (it.mode == "mesh" || (it.mode == "sta" && it.wds)) }

    /**
     * `ubus call network.wireless status` → ifname → mode. The JSON nests radios →
     * interfaces → config, and is the only place the runtime interface name and the configured
     * mode meet.
     */
    fun interfaceModes(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val modes = mutableMapOf<String, String>()
            val root = JSONObject(json)
            root.keys().forEach { radio ->
                val interfaces = root.optJSONObject(radio)?.optJSONArray("interfaces") ?: return@forEach
                for (i in 0 until interfaces.length()) {
                    val entry = interfaces.optJSONObject(i) ?: continue
                    val ifname = entry.optString("ifname")
                    val mode = entry.optJSONObject("config")?.optString("mode").orEmpty()
                    if (ifname.isNotBlank() && mode.isNotBlank()) modes[ifname] = mode
                }
            }
            modes
        }.getOrDefault(emptyMap())
    }

    /** A station on an interface the config calls sta/mesh is a backhaul link, not a client. */
    fun isBackhaulIface(iface: String, ifaces: List<WifiIface>, modes: Map<String, String>): Boolean {
        modes[iface]?.let { if (it == "sta" || it == "mesh") return true }
        // netifd names WDS client links "<radio-iface>-sta" and 11s links "meshN" on most
        // releases; without the ubus status map the name is the honest remaining signal.
        if (iface.contains("-sta") || iface.startsWith("mesh")) return true
        return false
    }

    private data class DhcpScope(val section: String, val interfaceName: String, val ignored: Boolean)

    /** `uci show dhcp` → one row per scope, with its interface and whether it is switched off. */
    private fun dhcpScopes(conf: String): List<DhcpScope> =
        uciSections(conf, "dhcp").map { (name, opts) ->
            DhcpScope(
                section = name,
                interfaceName = opts["interface"].orEmpty(),
                ignored = opts["ignore"] == "1",
            )
        }.filter { it.interfaceName.isNotBlank() && it.interfaceName != "loopback" }

    /** Interface names the network config treats as uplinks (wan and anything like it). */
    private fun uplinkNetworks(network: String): Set<String> =
        uciSections(network, "interface")
            .filterValues { it["proto"] == "dhcp" || it["type"] == "wwan" }
            .keys
            .map { it.removePrefix("network.") }
            .toSet() + setOf("wan", "wan6", "wwan")

    private data class Bridge(val section: String, val name: String, val portCount: Int, val stp: Boolean)

    private fun bridges(network: String): List<Bridge> =
        uciSections(network, "device").mapNotNull { (key, opts) ->
            if (opts["type"] != "bridge") return@mapNotNull null
            val ports = (opts["ports"] ?: opts["port"]).orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }
            // uci show keys arrive package-prefixed ("network.br_lan") — strip it so the
            // finding's raw filter and command line up with what `uci show network` prints.
            val name = key.removePrefix("network.")
            Bridge(
                section = name,
                name = opts["name"].orEmpty().ifBlank { name },
                portCount = ports.size,
                stp = opts["stp"] == "1",
            )
        }

    private data class Zone(val name: String, val inputPolicy: String, val lines: String)

    /** The wan zone when it accepts input — the one firewall posture worth a local flag. */
    private fun wanZoneAcceptingInput(firewall: String): Zone? {
        val zones = uciSections(firewall, "zone").mapNotNull { (_, opts) ->
            opts["name"]?.let { name -> name to opts }
        }.toMap()
        val wan = zones.entries.firstOrNull { (name, _) -> name == "wan" } ?: return null
        val input = wan.value["input"].orEmpty()
        if (input != "ACCEPT") return null
        return Zone(
            name = wan.key,
            inputPolicy = input,
            lines = firewall.lineSequence()
                .filter { it.contains(".name='wan'") || (it.contains("@zone[") && it.contains(".input=")) }
                .joinToString("\n"),
        )
    }

    /**
     * Minimal `uci show <pkg>` reader: named sections (`dhcp.lan=dhcp`) and anonymous ones
     * (`firewall.@zone[0]=zone`) become `section-id → options`, values unquoted. A marker line
     * (right side a bare word) ends the previous section; an option line (right side quoted)
     * belongs to whichever section is open.
     */
    fun uciSections(text: String, type: String): Map<String, Map<String, String>> {
        val sections = LinkedHashMap<String, MutableMap<String, String>>()
        var current: String? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val assign = Regex("""^(.+?)=(.+)$""").find(line) ?: return@forEach
            val key = assign.groupValues[1]
            val value = assign.groupValues[2]
            val isMarker = !value.contains("'") && !value.contains('"')
            if (isMarker) {
                val unquoted = value.trim('\'', '"')
                current = if (unquoted == type) key else null
                if (current != null) sections.getOrPut(key) { mutableMapOf() }
            } else if (current != null) {
                val option = key.substringAfterLast('.')
                if (option.isNotBlank()) sections[current]?.set(option, value.trim('\'', '"'))
            }
        }
        return sections
    }

    /** The raw lines around one station's block, capped — evidence, not a dump. */
    private fun rawAround(haystack: String, needle: String, marker: String): String =
        haystack.lineSequence().filter { it.contains(needle) || it.contains(marker) }
            .take(12)
            .joinToString("\n")
}
