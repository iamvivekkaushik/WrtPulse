package com.vivekkaushik.wrtpulse.ops

import org.json.JSONObject

/** `ubus call system board`. */
data class BoardInfo(
    val model: String,
    val boardName: String,
    val release: String,
    val revision: String,
    val target: String,
    val hostname: String = "",
) {
    /** "OpenWrt 23.05.3 · r23809 · MediaTek MT7986A" — the onboarding subtitle. */
    val summary: String get() = listOf(release, revision, target).filter { it.isNotBlank() }.joinToString(" · ")
}

/** `ubus call system info`. */
data class SystemInfo(
    val uptimeSeconds: Long,
    val load1: Double,
    val load5: Double,
    val load15: Double,
    val memTotal: Long,
    val memFree: Long,
    val memBuffered: Long,
    val memCached: Long,
) {
    /** OpenWrt reports "used" the way LuCI does: total minus free/buffered/cached. */
    val memUsedPercent: Int
        get() = if (memTotal <= 0) 0
        else (((memTotal - memFree - memBuffered - memCached).toDouble() / memTotal) * 100).toInt().coerceIn(0, 100)

    /** "18d 04:12" */
    val uptimeLabel: String
        get() {
            val d = uptimeSeconds / 86_400
            val h = (uptimeSeconds % 86_400) / 3_600
            val m = (uptimeSeconds % 3_600) / 60
            return "%dd %02d:%02d".format(d, h, m)
        }
}

/** One sample of `/proc/stat`'s aggregate cpu line; percentage needs two samples. */
data class CpuSample(val idle: Long, val total: Long) {
    fun percentSince(previous: CpuSample?): Int {
        if (previous == null) return 0
        val dTotal = total - previous.total
        val dIdle = idle - previous.idle
        if (dTotal <= 0) return 0
        return (((dTotal - dIdle).toDouble() / dTotal) * 100).toInt().coerceIn(0, 100)
    }
}

/**
 * Whatever the router is actually reaching the internet through right now — the interface
 * holding the default route, wired or wireless.
 */
data class Upstream(
    /** The uci interface: "wan", "wwan", … */
    val name: String,
    /** The netdev its counters live under: "eth1", "phy0-sta0", … */
    val device: String,
    val address: String?,
    val proto: String,
    /** Set when the upstream is a wireless client, so the card can name the network joined. */
    val ssid: String? = null,
    /** Route metric: with more than one default route, the lowest is the one Linux uses. */
    val metric: Int = 0,
    /** False for an interface whose only default route is IPv6. */
    val hasV4: Boolean = true,
) {
    val wireless: Boolean get() = ssid != null
}

/** Cumulative byte counters for one interface. */
data class NetCounters(val iface: String, val rxBytes: Long, val txBytes: Long)

/** A DHCP lease from /tmp/dhcp.leases. */
data class Lease(val expiry: Long, val mac: String, val ip: String, val hostname: String?)

/** One AP interface from `ubus call network.wireless status`. */
data class WifiIface(
    val radio: String,
    val section: String,
    val ifname: String,
    val ssid: String,
    val band: String,     // "2.4G", "5G", "6G"
    val up: Boolean,
    val mode: String = "ap",
)

/** One `wifi-device` section from `uci show wireless`. */
data class WifiRadio(
    val section: String,       // "radio0"
    val band: String,          // "2.4G", "5G", "6G", "?"
    val channel: String,       // "11", "auto"
    val htmode: String,        // "HE40", ...
    val disabled: Boolean,
    val country: String = "",  // regulatory domain, e.g. "IN"
)

/** One `wifi-iface` section from `uci show wireless` — an AP we serve or a network we join. */
data class WifiNetwork(
    val section: String,       // "default_radio0" or "@wifi-iface[0]"
    val device: String,        // "radio0"
    val ssid: String,
    val encryption: String,    // raw uci value: "psk2", "sae", "none", ...
    val key: String,
    val disabled: Boolean,
    val mode: String = "ap",   // "ap" broadcasts, "sta" joins someone else's network
    val network: String = "",  // the uci network it is bridged to: "lan", "wwan", ...
    val hidden: Boolean = false,
    val isolate: Boolean = false,
) {
    val isClient: Boolean get() = mode == "sta"
}

/** Cumulative per-host byte counters from `nlbw -c json`, client's perspective. */
data class NlbwHost(
    val mac: String,
    val downBytes: Long,
    val upBytes: Long,
    /** layer7 protocol → total bytes, e.g. "HTTPS" to 104_000_000. */
    val apps: Map<String, Long> = emptyMap(),
) {
    val totalBytes: Long get() = downBytes + upBytes

    /** Busiest protocols first — what this device is actually doing. */
    val topApps: List<Pair<String, Long>>
        get() = apps.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
}

/** What installing nlbwmon would do — the consent dialog's facts. */
data class InstallPlan(
    val packageManager: String,          // "opkg" or "apk"
    val packages: List<Pair<String, Long?>>, // name to installed size in bytes (null = unknown)
    val availKb: Long?,                  // overlay space before install
    val problem: String?,                // set when the plan itself failed (no net, no package)
) {
    val totalBytes: Long? get() =
        if (packages.isNotEmpty() && packages.all { it.second != null }) packages.sumOf { it.second!! } else null
}

/** One neighbouring AP from `iwinfo <iface> scan`. */
data class ScanCell(
    val channel: Int,
    val signalDbm: Int,
    val ssid: String,
    val bssid: String = "",
    /** Already in the form the UI shows: "WPA2", "WPA3", "OPEN". */
    val encryption: String = "",
) {
    /** iwinfo reports a hidden network's name literally as "unknown". */
    val named: Boolean get() = ssid.isNotBlank() && ssid != "unknown"

    /** Four-bar scale: -55 dBm and better is full, -85 and worse is one. */
    val bars: Int get() = when {
        signalDbm >= -55 -> 4
        signalDbm >= -68 -> 3
        signalDbm >= -78 -> 2
        else -> 1
    }
}

/** One interface as `iwinfo` (no arguments) describes it — AP or station alike. */
data class IwinfoIface(
    val ifname: String,
    val essid: String,
    val bssid: String,
    val mode: String,        // "Master" for an AP, "Client" for a station
    val channel: Int?,
    val signalDbm: Int?,     // stations report this; APs usually say "unknown"
    val encryption: String,
) {
    val isClient: Boolean get() = mode.equals("Client", true)
}

/** One `zone` section of /etc/config/firewall. */
data class FirewallZone(val section: String, val name: String, val networks: List<String>)

/** One `logread -f` line, split for the log screen. */
data class LogEntry(
    val time: String,       // "14:02:07"
    val severity: String,   // "info", "warn", "err", ...
    val src: String,        // "dnsmasq-dhcp", "hostapd", "kernel"
    val msg: String,        // message with the trailing token removed
    val tok: String,        // trailing MAC/IP highlight, possibly empty
)

/** One `ip neigh show` entry that has a MAC. */
data class Neigh(val ip: String, val dev: String, val mac: String, val state: String)

/** One associated wireless station from `iwinfo <iface> assoclist`. */
data class Station(
    val iface: String,
    val mac: String,
    val signalDbm: Int,
    val rxMbps: Double,
    val txMbps: Double,
)

object Parsers {

    fun sections(output: String, marker: String = Commands.SECTION): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var name: String? = null
        val body = StringBuilder()
        output.lineSequence().forEach { line ->
            if (line.startsWith(marker)) {
                name?.let { result[it] = body.toString().trim('\n') }
                name = line.removePrefix(marker).trim()
                body.setLength(0)
            } else if (name != null) {
                body.append(line).append('\n')
            }
        }
        name?.let { result[it] = body.toString().trim('\n') }
        return result
    }

    fun board(json: String): BoardInfo {
        val o = JSONObject(json)
        val release = o.optJSONObject("release")
        return BoardInfo(
            model = o.optString("model"),
            boardName = o.optString("board_name"),
            release = release?.let { "${it.optString("distribution")} ${it.optString("version")}".trim() }.orEmpty(),
            revision = release?.optString("revision").orEmpty(),
            target = o.optString("system").ifBlank { release?.optString("target").orEmpty() },
            hostname = o.optString("hostname"),
        )
    }

    fun systemInfo(json: String): SystemInfo {
        val o = JSONObject(json)
        val load = o.optJSONArray("load")
        val memory = o.optJSONObject("memory")
        // ubus reports load scaled by 65536.
        fun loadAt(i: Int) = (load?.optLong(i) ?: 0L) / 65_536.0
        return SystemInfo(
            uptimeSeconds = o.optLong("uptime"),
            load1 = loadAt(0),
            load5 = loadAt(1),
            load15 = loadAt(2),
            memTotal = memory?.optLong("total") ?: 0,
            memFree = memory?.optLong("free") ?: 0,
            memBuffered = memory?.optLong("buffered") ?: 0,
            memCached = memory?.optLong("cached") ?: 0,
        )
    }

    /** Parses the aggregate `cpu` line: user nice system idle iowait irq softirq steal. */
    fun cpuSample(procStatLine: String): CpuSample? {
        val fields = procStatLine.trim().split(Regex("\\s+"))
        if (fields.isEmpty() || fields[0] != "cpu") return null
        val values = fields.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        return CpuSample(idle = values[3] + (values.getOrNull(4) ?: 0), total = values.sum())
    }

    fun netCounters(procNetDev: String): Map<String, NetCounters> =
        procNetDev.lineSequence()
            .drop(2)
            .mapNotNull { line ->
                val (name, rest) = line.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val values = rest.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
                if (values.size < 9) return@mapNotNull null
                val iface = name.trim()
                iface to NetCounters(iface, rxBytes = values[0], txBytes = values[8])
            }
            .toMap()

    /**
     * `ubus call network.interface dump` → the interface carrying the default route.
     *
     * Asking for `network.interface.wan` was the old approach and it only ever answered for
     * a wired uplink; a router reaching the internet through a Wi-Fi client has its default
     * route on a completely different interface. The route table is the only honest source.
     */
    fun upstream(dumpJson: String, essids: Map<String, String> = emptyMap()): Upstream? =
        upstreams(dumpJson, essids).firstOrNull()

    /**
     * Every interface that could carry traffic out, best first.
     *
     * Grouped by device on purpose: `wan` and `wan6` are the same cable with a v4 and a v6
     * default route on it, and listing them as two upstreams would invent a redundancy the
     * router does not have. Two entries here means two actual links.
     */
    fun upstreams(dumpJson: String, essids: Map<String, String> = emptyMap()): List<Upstream> {
        val root = runCatching { JSONObject(dumpJson) }.getOrNull() ?: return emptyList()
        val list = root.optJSONArray("interface") ?: return emptyList()
        val byDevice = linkedMapOf<String, Upstream>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (!o.optBoolean("up")) continue
            val routes = o.optJSONArray("route") ?: continue
            var v4 = false
            var anyDefault = false
            for (r in 0 until routes.length()) {
                val route = routes.optJSONObject(r) ?: continue
                if (route.optInt("mask", -1) != 0) continue
                when (route.optString("target")) {
                    "0.0.0.0" -> { v4 = true; anyDefault = true }
                    "::" -> anyDefault = true
                }
            }
            if (!anyDefault) continue
            val device = o.optString("l3_device").ifBlank { o.optString("device") }
            if (device.isBlank()) continue
            val candidate = Upstream(
                name = o.optString("interface"),
                device = device,
                address = o.optJSONArray("ipv4-address")?.optJSONObject(0)
                    ?.optString("address")?.ifBlank { null },
                proto = o.optString("proto"),
                ssid = essids[device],
                metric = o.optInt("metric", 0),
                hasV4 = v4,
            )
            val held = byDevice[device]
            // One entry per link: the v4 side of a dual-stack pair is the one worth naming,
            // since it is the one with an address the card can show.
            if (held == null || (!held.hasV4 && v4)) byDevice[device] = candidate
        }
        // Lowest metric wins in the kernel, so that is the order to present them in.
        return byDevice.values.sortedWith(compareBy({ !it.hasV4 }, { it.metric }))
    }

    /**
     * The ESSID line of bare `iwinfo`, per interface. One grep-able line each, which is all
     * the dashboard needs to say which network an upstream client is joined to.
     */
    fun iwinfoEssids(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            if (!line.contains("ESSID:")) return@mapNotNull null
            val ifname = line.substringBefore("ESSID:").trim()
            val essid = line.substringAfter("ESSID:").trim().removeSurrounding("\"")
            if (ifname.isEmpty() || essid.isEmpty() || essid.equals("unknown", true)) null
            else ifname to essid
        }
        .toMap()

    /** `df -k /overlay | tail -n1` → available kilobytes, null if the line doesn't parse. */
    fun overlayAvailKb(dfLine: String): Long? {
        val nums = dfLine.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        return nums.getOrNull(2) // 1k-blocks, used, available
    }

    /** `df -k /overlay | tail -n1` → used percent. */
    fun overlayUsedPercent(dfLine: String): Int {
        val fields = dfLine.trim().split(Regex("\\s+"))
        val pct = fields.firstOrNull { it.endsWith("%") } ?: return 0
        return pct.removeSuffix("%").toIntOrNull()?.coerceIn(0, 100) ?: 0
    }

    fun leases(text: String): List<Lease> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(' ')
        if (f.size < 4) return@mapNotNull null
        Lease(
            expiry = f[0].toLongOrNull() ?: 0,
            mac = f[1].lowercase(),
            ip = f[2],
            hostname = f[3].takeIf { it != "*" },
        )
    }.toList()

    /**
     * `ubus call network.wireless status` → the AP interfaces actually running, with the
     * runtime ifname that keys `iwinfo assoclist` back to an SSID and band.
     */
    fun wirelessStatus(json: String): List<WifiIface> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<WifiIface>()
        root.keys().forEach { radio ->
            val r = root.optJSONObject(radio) ?: return@forEach
            val band = bandLabel(
                r.optJSONObject("config")?.optString("band").orEmpty(),
                r.optJSONObject("config")?.optString("hwmode").orEmpty(),
            )
            val up = r.optBoolean("up")
            val ifaces = r.optJSONArray("interfaces") ?: return@forEach
            for (i in 0 until ifaces.length()) {
                val o = ifaces.optJSONObject(i) ?: continue
                val cfg = o.optJSONObject("config")
                val ssid = cfg?.optString("ssid").orEmpty()
                val mode = cfg?.optString("mode", "ap").orEmpty().ifEmpty { "ap" }
                // sta belongs here too: an uplink needs its ifname to report signal.
                if (ssid.isEmpty() || (mode != "ap" && mode != "sta")) continue
                result += WifiIface(
                    radio = radio,
                    section = o.optString("section"),
                    ifname = o.optString("ifname"),
                    ssid = ssid,
                    band = band,
                    up = up && o.optString("ifname").isNotEmpty(),
                    mode = mode,
                )
            }
        }
        return result
    }

    private fun bandLabel(band: String, hwmode: String): String = when {
        band == "2g" -> "2.4G"
        band == "5g" -> "5G"
        band == "6g" -> "6G"
        hwmode.contains('a') -> "5G"
        hwmode.isNotEmpty() -> "2.4G"
        else -> "?"
    }

    // "Sat Aug 30 12:34:56 2026 daemon.notice hostapd: phy0-ap0: AP-STA-CONNECTED aa:bb..."
    private val LOGREAD =
        Regex("""^\w{3} +\w{3} +\d+ +(\d{2}:\d{2}:\d{2}) +\d{4} +([\w-]+)\.(\w+) +([^:]+): ?(.*)$""")
    private val TRAILING_TOKEN =
        Regex("""((?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?(?:#\d+)?)\s*$""")

    /** One line of `logread -f`. Returns null for lines that don't match the syslog shape. */
    fun logread(raw: String): LogEntry? {
        val m = LOGREAD.find(raw.trim()) ?: return null
        val (time, _, severity, tag, message) = m.destructured
        val src = tag.substringBefore('[').trim()
        val tok = TRAILING_TOKEN.find(message)?.groupValues?.get(1).orEmpty()
        val msg = if (tok.isEmpty()) message else message.removeSuffix(tok).trimEnd().removeSuffix(" ")
        return LogEntry(time, severity, src, msg.trim(), tok)
    }

    /**
     * `iwinfo <iface> scan` cells:
     *   Cell 01 - Address: AA:BB:...
     *             ESSID: "neighbor"
     *             Mode: Master  Channel: 6
     *             Signal: -72 dBm  Quality: 38/70
     *             Encryption: WPA2 PSK (CCMP)
     */
    fun scanCells(text: String): List<ScanCell> {
        val cells = mutableListOf<ScanCell>()
        var ssid = ""
        var bssid = ""
        var encryption = ""
        var channel: Int? = null
        var signal: Int? = null
        fun flush() {
            val ch = channel
            val sig = signal
            if (ch != null && sig != null) cells += ScanCell(ch, sig, ssid, bssid, encryption)
            ssid = ""; bssid = ""; encryption = ""; channel = null; signal = null
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("Cell ") -> {
                    flush()
                    bssid = MAC_ANY.find(line)?.value?.uppercase().orEmpty()
                }
                line.startsWith("ESSID:") ->
                    ssid = line.removePrefix("ESSID:").trim().removeSurrounding("\"")
                line.startsWith("Encryption:") ->
                    encryption = securityLabel(line.removePrefix("Encryption:").trim())
                else -> {
                    Regex("Channel: (\\d+)").find(line)?.let { channel = it.groupValues[1].toInt() }
                    Regex("Signal: (-?\\d+) dBm").find(line)?.let { signal = it.groupValues[1].toInt() }
                }
            }
        }
        flush()
        return cells
    }

    /**
     * iwinfo's free-text encryption line ("WPA2 PSK (CCMP)", "mixed WPA/WPA2 PSK", "none")
     * reduced to the tag the scan list shows.
     */
    fun securityLabel(raw: String): String = when {
        raw.isBlank() || raw.equals("none", true) || raw.startsWith("unknown", true) -> "OPEN"
        raw.contains("WPA3", true) || raw.contains("SAE", true) -> "WPA3"
        raw.contains("WPA2", true) -> "WPA2"
        raw.contains("WEP", true) -> "WEP"
        raw.contains("WPA", true) -> "WPA"
        else -> raw.take(8).uppercase()
    }

    /**
     * Bare `iwinfo` — every wireless interface the router has up, one block each. This is
     * the only place a station's own signal is reported, so it is what tells the interface
     * list how well an uplink is actually doing.
     */
    fun iwinfo(text: String): List<IwinfoIface> {
        val result = mutableListOf<IwinfoIface>()
        var ifname = ""
        var essid = ""
        var bssid = ""
        var mode = ""
        var channel: Int? = null
        var signal: Int? = null
        var encryption = ""
        fun flush() {
            if (ifname.isNotEmpty()) {
                result += IwinfoIface(ifname, essid, bssid, mode, channel, signal, encryption)
            }
            ifname = ""; essid = ""; bssid = ""; mode = ""; channel = null; signal = null; encryption = ""
        }
        text.lineSequence().forEach { raw ->
            // A block starts at column 0 with "<ifname>  ESSID: ..."; the rest is indented.
            if (raw.isNotEmpty() && !raw[0].isWhitespace() && raw.contains("ESSID:")) {
                flush()
                ifname = raw.substringBefore("ESSID:").trim()
                essid = raw.substringAfter("ESSID:").trim().removeSurrounding("\"")
                if (essid.equals("unknown", true)) essid = ""
                return@forEach
            }
            val line = raw.trim()
            when {
                line.startsWith("Access Point:") ->
                    bssid = MAC_ANY.find(line)?.value?.uppercase().orEmpty()
                line.startsWith("Encryption:") ->
                    encryption = line.removePrefix("Encryption:").trim()
                else -> {
                    Regex("Mode: (\\w+)").find(line)?.let { mode = it.groupValues[1] }
                    Regex("Channel: (\\d+)").find(line)?.let { channel = it.groupValues[1].toInt() }
                    Regex("Signal: (-?\\d+) dBm").find(line)?.let { signal = it.groupValues[1].toInt() }
                }
            }
        }
        flush()
        return result
    }

    /**
     * `uci show firewall` → the zones and the networks each covers. The interface list uses
     * it to say which zone an SSID actually lands in, which is the thing that decides
     * whether guests can reach the LAN.
     */
    fun firewallZones(uci: Map<String, String>): List<FirewallZone> {
        val zones = mutableListOf<FirewallZone>()
        uci.forEach { (key, value) ->
            if (value != "zone" || key.count { it == '.' } != 1) return@forEach
            val section = key.substringAfter('.')
            val name = uci["firewall.$section.name"].orEmpty()
            // uci show renders a list as one space-separated value.
            val networks = uci["firewall.$section.network"].orEmpty()
                .split(' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            zones += FirewallZone(section, name, networks)
        }
        return zones
    }

    /**
     * `nlbw -c json` → {"columns":[...],"data":[[...]]}. Rows repeat per protocol, so byte
     * counters are summed per MAC. In nlbwmon "rx" is bytes the host received (download).
     */
    fun nlbwHosts(json: String): List<NlbwHost> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val columns = root.optJSONArray("columns") ?: return emptyList()
        var macIdx = -1; var rxIdx = -1; var txIdx = -1; var appIdx = -1
        for (i in 0 until columns.length()) {
            when (columns.optString(i)) {
                "mac" -> macIdx = i
                "rx_bytes" -> rxIdx = i
                "tx_bytes" -> txIdx = i
                "layer7" -> appIdx = i
            }
        }
        if (macIdx < 0 || rxIdx < 0 || txIdx < 0) return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val down = linkedMapOf<String, Long>()
        val up = linkedMapOf<String, Long>()
        val apps = linkedMapOf<String, MutableMap<String, Long>>()
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val mac = row.optString(macIdx).lowercase()
            if (mac.isEmpty() || mac == "00:00:00:00:00:00") continue
            val rx = row.optLong(rxIdx)
            val tx = row.optLong(txIdx)
            down[mac] = (down[mac] ?: 0) + rx
            up[mac] = (up[mac] ?: 0) + tx
            if (appIdx >= 0) {
                val app = row.optString(appIdx).trim()
                if (app.isNotEmpty()) {
                    val perApp = apps.getOrPut(mac) { linkedMapOf() }
                    perApp[app] = (perApp[app] ?: 0) + rx + tx
                }
            }
        }
        return down.keys.map { mac ->
            NlbwHost(mac, down[mac] ?: 0, up[mac] ?: 0, apps[mac].orEmpty())
        }
    }

    /** "46 KiB", "1.2 MiB", "512 B", "26113 B" → bytes. */
    fun humanBytes(text: String): Long? {
        val m = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMG]?)i?B", RegexOption.IGNORE_CASE).find(text.trim())
            ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val scale = when (m.groupValues[2].uppercase()) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }
        return (value * scale).toLong()
    }

    /** Sections of [Commands.NLBW_PLAN] → the consent dialog's numbers. */
    fun installPlan(sections: Map<String, String>): InstallPlan {
        val pm = sections["pm"].orEmpty().trim().ifEmpty { "opkg" }
        val plan = sections["plan"].orEmpty()
        val problem = when {
            plan.contains("Unknown package", ignoreCase = true) ||
                plan.contains("unable to select packages", ignoreCase = true) ||
                (pm == "apk" && plan.contains("ERROR")) ->
                "The $pm feed doesn't offer nlbwmon — is the router online?"
            // Package present but the binary wasn't found: the service exists, just isn't running.
            plan.contains("is up to date", ignoreCase = true) ->
                "nlbwmon is already installed — it only needs to be started."
            !plan.contains("Installing", ignoreCase = true) ->
                "Couldn't resolve the install — is the router online?"
            else -> null
        }
        // "<package>|<size>" lines, one per package the resolve pulled in.
        val packages = sections["sizes"].orEmpty().lineSequence()
            .mapNotNull { line ->
                val name = line.substringBefore('|', "").trim()
                if (name.isEmpty() || !line.contains('|')) return@mapNotNull null
                name to humanBytes(line.substringAfter('|'))
            }
            .distinctBy { it.first }
            .toList()
        return InstallPlan(
            packageManager = pm,
            packages = packages,
            availKb = overlayAvailKb(sections["df"].orEmpty()),
            problem = problem,
        )
    }

    private val BLOCKED_MAC = Regex("wrtpulse-block-((?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})")

    /** MACs with a wrtpulse block rule, from grepped `uci show firewall` lines. */
    fun blockedMacs(text: String): Set<String> =
        BLOCKED_MAC.findAll(text).map { it.groupValues[1].lowercase() }.toSet()

    /** `uci show network` → the interface section names already in use. */
    fun networkInterfaces(uci: Map<String, String>): Set<String> = uci.entries
        .filter { (key, value) -> value == "interface" && key.startsWith("network.") && key.count { it == '.' } == 1 }
        .map { it.key.removePrefix("network.") }
        .toSet()

    /** `uci show wireless` → structured radios and their wifi-iface sections. */
    fun wireless(uci: Map<String, String>): Pair<List<WifiRadio>, List<WifiNetwork>> {
        val radios = mutableListOf<WifiRadio>()
        val networks = mutableListOf<WifiNetwork>()
        fun opt(section: String, option: String) = uci["wireless.$section.$option"]
        uci.forEach { (key, type) ->
            if (!key.startsWith("wireless.") || key.count { it == '.' } != 1) return@forEach
            val section = key.removePrefix("wireless.")
            when (type) {
                "wifi-device" -> radios += WifiRadio(
                    section = section,
                    band = bandLabel(opt(section, "band").orEmpty(), opt(section, "hwmode").orEmpty()),
                    channel = opt(section, "channel").orEmpty().ifEmpty { "auto" },
                    htmode = opt(section, "htmode").orEmpty(),
                    disabled = opt(section, "disabled") == "1",
                    country = opt(section, "country").orEmpty(),
                )
                // ap and sta only: mesh/adhoc/monitor have no SSID card to draw.
                "wifi-iface" -> (opt(section, "mode") ?: "ap").let { mode ->
                    if (mode == "ap" || mode == "sta") networks += WifiNetwork(
                        section = section,
                        device = opt(section, "device").orEmpty(),
                        ssid = opt(section, "ssid").orEmpty(),
                        encryption = opt(section, "encryption").orEmpty().ifEmpty { "none" },
                        key = opt(section, "key").orEmpty(),
                        disabled = opt(section, "disabled") == "1",
                        mode = mode,
                        network = opt(section, "network").orEmpty(),
                        hidden = opt(section, "hidden") == "1",
                        isolate = opt(section, "isolate") == "1",
                    )
                }
            }
        }
        return radios to networks
    }

    /**
     * `uci show dhcp` → MAC to reserved address, for every `host` section that has both.
     * MACs are lower-cased so they match the ones iwinfo and the lease file report.
     */
    fun dhcpReservations(uci: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        uci.forEach { (key, value) ->
            if (value != "host" || key.count { it == '.' } != 1) return@forEach
            val section = key.substringAfter('.')
            val mac = uci["dhcp.$section.mac"]?.lowercase()
            val ip = uci["dhcp.$section.ip"]
            if (!mac.isNullOrBlank() && !ip.isNullOrBlank()) result[mac] = ip
        }
        return result
    }

    /** "psk2" → the tag the design shows on the SSID card. */
    fun encryptionLabel(encryption: String): String = when {
        encryption.startsWith("sae-mixed") -> "WPA2/3"
        encryption.startsWith("sae") -> "WPA3-SAE"
        encryption.startsWith("psk-mixed") -> "WPA/WPA2"
        encryption.startsWith("psk2") -> "WPA2-PSK"
        encryption.startsWith("psk") -> "WPA-PSK"
        encryption.startsWith("wpa") -> "WPA-EAP"
        encryption.startsWith("owe") -> "OWE"
        encryption == "none" || encryption.isEmpty() -> "OPEN"
        else -> encryption.uppercase()
    }

    /** `ip neigh show` as full entries — the device matters to tell LAN from WAN neighbours. */
    fun neighEntries(text: String): List<Neigh> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 2) return@mapNotNull null
        val ip = f[0]
        val dev = f.indexOf("dev").let { if (it >= 0 && it + 1 < f.size) f[it + 1] else "" }
        val mac = f.indexOf("lladdr").let { if (it >= 0 && it + 1 < f.size) f[it + 1].lowercase() else return@mapNotNull null }
        Neigh(ip, dev, mac, f.last())
    }.toList()

    /** `ip neigh show` → MAC by IP, so wired clients with no lease still show up. */
    fun neighbours(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        val ip = f.firstOrNull() ?: return@mapNotNull null
        val idx = f.indexOf("lladdr")
        if (idx == -1 || idx + 1 >= f.size) return@mapNotNull null
        f[idx + 1].lowercase() to ip
    }.toMap()

    /**
     * `iwinfo <iface> assoclist`, prefixed by "# <iface>" lines from [Commands.CLIENTS].
     *
     * Sample:
     *   AA:5C:1E:88:04:2B  -52 dBm / -95 dBm (SNR 43)  0 ms ago
     *       RX: 780.0 MBit/s   1234 Pkts.
     *       TX: 866.7 MBit/s   4321 Pkts.
     */
    fun stations(text: String): List<Station> {
        val stations = mutableListOf<Station>()
        var iface = ""
        var mac: String? = null
        var signal = 0
        var rx = 0.0
        var tx = 0.0
        fun flush() {
            mac?.let { stations += Station(iface, it, signal, rx, tx) }
            mac = null; signal = 0; rx = 0.0; tx = 0.0
        }
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") -> { flush(); iface = line.removePrefix("#").trim() }
                MAC_HEAD.containsMatchIn(line) -> {
                    flush()
                    mac = MAC_HEAD.find(line)!!.value.lowercase()
                    signal = Regex("(-?\\d+) dBm").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                line.startsWith("RX:") -> rx = rateOf(line)
                line.startsWith("TX:") -> tx = rateOf(line)
            }
        }
        flush()
        return stations
    }

    private fun rateOf(line: String) =
        Regex("([\\d.]+)\\s*MBit/s").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private val MAC_HEAD = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /** The same, anywhere in the line — iwinfo prints the BSSID after a label. */
    private val MAC_ANY = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /**
     * `uci show wireless` → flat key/value map, quotes stripped.
     * e.g. wireless.@wifi-iface[0].ssid='Casa'
     */
    fun uciShow(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        val key = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim().removeSurrounding("'")
        key to value
    }.toMap()
}
