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
)

/** One `wifi-device` section from `uci show wireless`. */
data class WifiRadio(
    val section: String,       // "radio0"
    val band: String,          // "2.4G", "5G", "6G", "?"
    val channel: String,       // "11", "auto"
    val htmode: String,        // "HE40", ...
    val disabled: Boolean,
)

/** One AP `wifi-iface` section from `uci show wireless`. */
data class WifiNetwork(
    val section: String,       // "default_radio0" or "@wifi-iface[0]"
    val device: String,        // "radio0"
    val ssid: String,
    val encryption: String,    // raw uci value: "psk2", "sae", "none", ...
    val key: String,
    val disabled: Boolean,
)

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

    /** WAN interface status from ubus: address plus the L3 device to meter. */
    fun wanStatus(json: String): Pair<String?, String?> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null to null
        val address = o.optJSONArray("ipv4-address")?.optJSONObject(0)?.optString("address")
        val device = o.optString("l3_device").ifBlank { o.optString("device") }.ifBlank { null }
        return address?.ifBlank { null } to device
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
                if (ssid.isEmpty() || cfg?.optString("mode", "ap") != "ap") continue
                result += WifiIface(
                    radio = radio,
                    section = o.optString("section"),
                    ifname = o.optString("ifname"),
                    ssid = ssid,
                    band = band,
                    up = up && o.optString("ifname").isNotEmpty(),
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

    /** `uci show wireless` → structured radios and AP networks. */
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
                )
                "wifi-iface" -> if ((opt(section, "mode") ?: "ap") == "ap") networks += WifiNetwork(
                    section = section,
                    device = opt(section, "device").orEmpty(),
                    ssid = opt(section, "ssid").orEmpty(),
                    encryption = opt(section, "encryption").orEmpty().ifEmpty { "none" },
                    key = opt(section, "key").orEmpty(),
                    disabled = opt(section, "disabled") == "1",
                )
            }
        }
        return radios to networks
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
