package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.FirewallZone
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.NetDev
import com.vivekkaushik.wrtpulse.ops.NetDevice
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.PingResult
import com.vivekkaushik.wrtpulse.ops.WanConfig
import com.vivekkaushik.wrtpulse.ops.WanLink

/** How the LAN is told to address itself over IPv6 — design screen 29's four choices. */
enum class LanV6(val label: String, val body: String) {
    Auto("Auto", "Negotiates the best scheme from ISP capabilities"),
    Slaac("SLAAC", "Devices self-generate addresses from the advertised prefix"),
    Stateful("Stateful DHCPv6", "The router assigns and tracks every client address"),
    NonAddress("Non-address", "DNS and routes only — no local IPv6 allocation"),
}

/** How the IPv6 uplink is obtained — design screen 29's mode list. */
enum class V6Mode(val label: String, val body: String) {
    Off("Off", "No IPv6 uplink"),
    Native("DHCPv6 native", "ISP router advertisement plus a DHCPv6 client"),
    PppoeDual("PPPoE dual-stack", "Request IPv6 over the existing PPPoE session"),
    Relay("Passthrough / relay", "odhcpd relays the ISP's IPv6 straight to the LAN"),
    SixToFour("6to4 tunnel", "Encapsulate IPv6 in IPv4 — no ISP support needed"),
}

/** One row of the WAN hub. */
data class WanRow(
    val section: String,
    val proto: String,
    val device: String,
    val up: Boolean,
    val primary: Boolean,
    /** Staged if edited, else what the interface carries — lower wins the default route. */
    val metric: Int,
    val metricChanged: Boolean = false,
    val address: String,
    val v6Prefix: String,
    val uptimeS: Long,
)

/**
 * Internet & WAN gateways — design screens 26-30.
 *
 * The same staging contract as [WifiStore] and [LanStore], with one addition the other two
 * do not need: applying here can cut the link that issued the command. So an apply arms a
 * rollback on the router first — the old `/etc/config/network` goes back unless the app
 * reappears and confirms — and the confirmation is the app having actually re-read the
 * router, not the command having returned 0.
 */
class WanStore(private val session: RouterSession) {

    val links = mutableStateListOf<WanLink>()
    val zones = mutableStateListOf<FirewallZone>()
    val devs = mutableStateListOf<NetDev>()
    val deviceSections = mutableStateListOf<NetDevice>()

    /** uci interface name -> its configured form. */
    val configs = mutableStateMapOf<String, WanConfig>()

    /** Protocols netifd can actually bring up here. */
    val protos = mutableStateListOf<String>()

    /** `uci show dhcp` for the LAN's IPv6 half, which lives in odhcpd's config. */
    private val dhcpUci = mutableStateMapOf<String, String>()

    /** The LAN's own subnet, for working out whether this session comes in over the WAN. */
    private var lanCidr by mutableStateOf<Pair<Long, Int>?>(null)

    var loaded by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var testing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** The last connection test, newest run replacing the previous one. */
    val pings = mutableStateListOf<PingResult>()

    /** Set when the router put the old config back because the app never came back. */
    var rolledBack by mutableStateOf(false); private set

    /** The WAN being edited. Every screen past the hub is about one interface. */
    var selected by mutableStateOf(""); private set

    val staged = mutableStateMapOf<String, Pair<String, String>>()
    val stagedLists = mutableStateMapOf<String, Pair<List<String>, List<String>>>()

    /** `config device` sections the apply would create, keyed by the name they will carry. */
    val deviceDrafts = mutableStateMapOf<String, NetDevice>()

    /**
     * Sections the apply has to create before it can set options on them, as uci path to
     * section type — `dhcp.wan6` to `dhcp`. Relay mode needs one: odhcpd has to be told
     * which interface it is relaying FROM, and on most routers no such section exists yet.
     */
    val sectionDrafts = mutableStateMapOf<String, String>()

    val pendingCount: Int
        get() = staged.size + stagedLists.size + deviceDrafts.size + sectionDrafts.size

    /**
     * Runs before the batch — the auto-backup hook. Set by the app when "snapshot before
     * every Apply" is on; the store itself knows nothing about backups. A failure here
     * aborts the apply, because a snapshot that silently didn't happen is not a snapshot.
     */
    var beforeApply: (suspend () -> Unit)? = null

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    suspend fun load() {
        try {
            val out = session.exec(Commands.WAN_STATE, timeoutMs = 20_000)
                .requireOk("read wan").stdout
            ingest(Parsers.sections(out))
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    /** [load] without the round trip — where the parsing lives, and what the tests drive. */
    fun ingest(parts: Map<String, String>) {
        val network = Parsers.uciShow(parts["net"].orEmpty())
        val dhcp = Parsers.uciShow(parts["dhcp"].orEmpty())
        links.clear(); links.addAll(Parsers.wanLinks(parts["dump"].orEmpty()))
        zones.clear(); zones.addAll(Parsers.firewallZones(Parsers.uciShow(parts["fw"].orEmpty())))
        devs.clear(); devs.addAll(Parsers.netdevs(parts["links"].orEmpty()))
        deviceSections.clear(); deviceSections.addAll(Parsers.netDevices(network))
        protos.clear(); protos.addAll(Parsers.protoHandlers(parts["protos"].orEmpty()).sorted())
        dhcpUci.clear(); dhcpUci.putAll(dhcp)
        configs.clear()
        Parsers.networkInterfaces(network).forEach { name ->
            Parsers.wanConfig(network, name)?.let { configs[name] = it }
        }
        lanCidr = Parsers.lanNet(network)?.let { lan ->
            val prefix = IpMath.prefixOf(lan.netmask) ?: lan.cidrPrefix ?: 24
            IpMath.parse(lan.ipaddr)?.let { IpMath.networkOf(it, prefix) to prefix }
        }
        if (selected.isEmpty() || selected !in configs) {
            selected = wanRows().firstOrNull()?.section.orEmpty()
        }
    }

    /**
     * The uplinks, best first.
     *
     * A WAN is an interface the firewall treats as one, or any interface actually carrying a
     * default route — which is how a Wi-Fi client uplink gets counted without being named
     * "wan". Lowest metric wins, the way the kernel decides.
     */
    fun wanRows(): List<WanRow> {
        val zoned = zones.filter { it.name == "wan" }.flatMap { it.networks }.toSet()
        val routed = links.filter { it.hasDefaultRoute }.map { it.name }.toSet()
        val names = (zoned + routed).filter { name ->
            name in configs || links.any { it.name == name }
        }.distinct()
        val rows = names.map { name ->
            val link = links.firstOrNull { it.name == name }
            val config = configs[name]
            WanRow(
                section = name,
                proto = config?.proto.orEmpty().ifEmpty { link?.proto.orEmpty() },
                device = link?.device?.ifBlank { null } ?: config?.device.orEmpty(),
                up = link?.up == true,
                primary = false,
                metric = metricOf(name),
                metricChanged = "network.$name.metric" in staged,
                address = link?.address.orEmpty(),
                v6Prefix = link?.v6Prefix.orEmpty(),
                uptimeS = link?.uptimeS ?: 0,
            )
        }
            // A v6 companion rides the same link and is configured from the IPv6 screen, so
            // it is not a separate uplink to choose between.
            .filterNot { row -> row.section.endsWith("6") && names.contains(row.section.dropLast(1)) }
            .sortedWith(compareBy({ !it.up }, { it.metric }, { it.section }))
        val primary = rows.firstOrNull { it.up }?.section
        return rows.map { it.copy(primary = it.section == primary) }
    }

    val current: WanConfig? get() = configs[selected]

    fun select(section: String) {
        selected = section
    }

    // -----------------------------------------------------------------------
    // Staging
    // -----------------------------------------------------------------------

    fun stage(path: String, saved: String, value: String) {
        if (value == saved) staged.remove(path) else staged[path] = saved to value
    }

    fun value(path: String, saved: String): String = staged[path]?.second ?: saved

    fun stageList(path: String, saved: List<String>, values: List<String>) {
        if (values == saved) stagedLists.remove(path) else stagedLists[path] = saved to values
    }

    fun list(path: String, saved: List<String>): List<String> = stagedLists[path]?.second ?: saved

    fun revert() {
        staged.clear()
        stagedLists.clear()
        deviceDrafts.clear()
        sectionDrafts.clear()
        error = null
    }

    private fun path(option: String) = "network.$selected.$option"

    // ---- the hub: failover order ----

    /**
     * An uplink's metric as the screen should show it: staged if edited, else the config,
     * else what netifd reports. Config before live because the live value can be a default
     * netifd filled in, and the field is editing the config.
     */
    fun metricOf(section: String): Int {
        val saved = configs[section]?.metric.orEmpty()
        val shown = value("network.$section.metric", saved)
        return shown.toIntOrNull()
            ?: links.firstOrNull { it.name == section }?.metric
            ?: 0
    }

    /** The raw text in the metric field, so a half-typed value is not snapped to a number. */
    fun metricText(section: String): String =
        value("network.$section.metric", configs[section]?.metric.orEmpty())

    /**
     * Sets an uplink's metric. Lower wins: when two uplinks are up, the default route with the
     * lower metric carries the traffic, and when it goes down the kernel is left with the
     * other — which is all the failover a router without mwan3 has.
     */
    fun stageMetric(section: String, value: String) =
        stage("network.$section.metric", configs[section]?.metric.orEmpty(), value.trim())

    /** Interfaces whose own options this batch touches — decides between ifup and a reload. */
    fun touchedInterfaces(): Set<String> = (staged.keys + stagedLists.keys)
        .filter { it.startsWith("network.") }
        .map { it.removePrefix("network.").substringBefore('.') }
        .filter { it in configs }
        .toSet()

    // ---- screen 27: port, VLAN, MAC, MTU ----

    /** The `config device` the WAN points at, when it points at one rather than a raw port. */
    val wanDevice: NetDevice?
        get() {
            val name = value(path("device"), current?.device.orEmpty())
            return deviceDrafts[name] ?: deviceSections.firstOrNull { it.name == name }
        }

    /** The physical port under the WAN, with any VLAN tag stripped off. */
    val port: String
        get() {
            val name = value(path("device"), current?.device.orEmpty())
            val tagged = wanDevice?.takeIf { it.type == "8021q" }
            return tagged?.ifname?.ifBlank { null } ?: name.substringBefore('.')
        }

    /** The 802.1q tag, or "" when the WAN sits on an untagged port. */
    val vlanId: String
        get() {
            val device = wanDevice
            if (device != null && device.type == "8021q") {
                return device.vid.ifBlank { device.name.substringAfter('.', "") }
            }
            return value(path("device"), current?.device.orEmpty()).substringAfter('.', "")
        }

    /**
     * True when this uplink is a radio rather than a socket.
     *
     * A Wi-Fi client uplink has no `device` option at all — the wifi-iface's `network` puts
     * it there and netifd assigns `phy0-sta0` at runtime. There is no port to pick, no tag
     * to set, and no MAC to clone (that one belongs to the radio), so the port page says so
     * instead of offering four controls that would either do nothing or break the link.
     */
    val wirelessUplink: Boolean
        get() {
            val configured = value(path("device"), current?.device.orEmpty())
            val live = links.firstOrNull { it.name == selected }?.device.orEmpty()
            val name = configured.ifEmpty { live }
            // No device configured and none live: only a radio-attached interface looks like
            // this, because a wired one always names its port in uci. It is also exactly what
            // a Wi-Fi client looks like for the seconds after an ifup while it re-associates
            // — which is when "pick the socket" used to appear against a metric edit.
            if (name.isEmpty()) return true
            val dev = devs.firstOrNull { it.name == name.substringBefore('.') }
            return dev?.wireless == true || name.startsWith("phy") || name.startsWith("wlan")
        }

    /** What the hub's port row says for an uplink with no socket under it. */
    val portLabel: String
        get() = when {
            wirelessUplink -> "Wi-Fi client · " +
                (links.firstOrNull { it.name == selected }?.device?.ifBlank { null } ?: "radio")
            port.isEmpty() -> "unset"
            else -> listOfNotNull(
                port,
                vlanId.takeIf { it.isNotEmpty() }?.let { "vlan $it · 802.1q" },
                mtu.takeIf { it.isNotEmpty() }?.let { "mtu $it" },
            ).joinToString(" · ")
        }

    /** Ports a WAN could sit on: every socket, plus whatever it is on now. */
    fun availablePorts(): List<String> {
        val sockets = Parsers.switchPorts(devs).map { it.name }
        return (sockets + port).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Where MAC and MTU are written.
     *
     * Both can live on the interface or on a `config device`, and the device wins wherever
     * both are set — so a value written to the interface while a device section holds one
     * silently does nothing. The rule: if the WAN rides a device section, write there.
     */
    private fun deviceScope(): String? = wanDevice?.let { "network.${it.section}" }

    private fun macPath(): String = deviceScope()?.let { "$it.macaddr" } ?: path("macaddr")

    private fun mtuPath(): String = deviceScope()?.let { "$it.mtu" } ?: path("mtu")

    // Read whichever is actually set, preferring the device because that is the one netifd
    // obeys when both carry a value.
    private fun savedMac(): String =
        wanDevice?.macaddr?.ifBlank { null } ?: current?.macaddr.orEmpty()

    private fun savedMtu(): String =
        wanDevice?.mtu?.ifBlank { null } ?: current?.mtu.orEmpty()

    val macaddr: String get() = value(macPath(), savedMac())
    val mtu: String get() = value(mtuPath(), savedMtu())

    val pcp: String
        get() {
            val scope = deviceScope() ?: return "0"
            val mapping = value("$scope.egress_qos_mapping", wanDevice?.egressQos.orEmpty())
            return mapping.substringAfter(':', "").ifEmpty { "0" }
        }

    fun stageMac(value: String) {
        stage(macPath(), savedMac(), value.trim())
        // Writing on the device while the interface still holds its own copy would leave a
        // value that reads as effective and is not — the device wins. Remove it with the same
        // batch so the config says one thing.
        if (deviceScope() != null) {
            current?.macaddr?.takeIf { it.isNotEmpty() }?.let { stage(path("macaddr"), it, "") }
        }
    }

    fun stageMtu(value: String) {
        stage(mtuPath(), savedMtu(), value.trim())
        if (deviceScope() != null) {
            current?.mtu?.takeIf { it.isNotEmpty() }?.let { stage(path("mtu"), it, "") }
        }
    }

    fun stagePort(value: String) = stageDevice(value, vlanId)

    fun stageVlan(id: String) = stageDevice(port, id.trim())

    /**
     * Points the WAN at a port, tagged or not.
     *
     * A tag means a `config device` of type 8021q named `<port>.<vid>` has to exist and the
     * interface has to name it; without one, netifd brings the interface up on the untagged
     * port and the ISP never sees a thing.
     */
    private fun stageDevice(newPort: String, newVlan: String) {
        val saved = current?.device.orEmpty()
        deviceDrafts.clear()
        if (newVlan.isEmpty()) {
            stage(path("device"), saved, newPort)
            return
        }
        val name = "$newPort.$newVlan"
        val existing = deviceSections.firstOrNull { it.name == name && it.type == "8021q" }
        if (existing == null) {
            deviceDrafts[name] = NetDevice(
                section = deviceSectionName(newPort, newVlan),
                name = name,
                type = "8021q",
                ifname = newPort,
                vid = newVlan,
                macaddr = "",
                mtu = "",
                egressQos = "",
            )
        }
        stage(path("device"), saved, name)
    }

    fun stagePcp(value: String) {
        val scope = deviceScope() ?: return
        // netifd has no `pcp` option; a priority is written as an egress QoS mapping from the
        // internal class to the 802.1p value, which is what LuCI writes too.
        stage(
            "$scope.egress_qos_mapping",
            wanDevice?.egressQos.orEmpty(),
            if (value == "0") "" else "0:$value",
        )
    }

    private fun deviceSectionName(port: String, vlan: String): String {
        val base = "wrtpulse_${port.filter { it.isLetterOrDigit() }}_$vlan"
        return WifiStore.free(base, deviceSections.map { it.section }.toSet())
    }

    // ---- screen 28: the IPv4 protocol ----

    val proto: String get() = value(path("proto"), current?.proto.orEmpty())

    fun stageProto(value: String) = stage(path("proto"), current?.proto.orEmpty(), value)

    fun protoAvailable(name: String): Boolean = name in protos

    fun protoChoices(): List<Pair<String, Boolean>> = PROTO_ORDER.map { it to protoAvailable(it) }

    /** Protocols whose own settings this screen can edit; the rest are refused, not faked. */
    fun protoEditable(name: String): Boolean = name in EDITABLE_PROTOS

    fun option(name: String, saved: String): String = value(path(name), saved)

    fun stageOption(name: String, saved: String, value: String) =
        stage(path(name), saved, value.trim())

    // ---- screen 29: IPv6 ----

    /** The v6 companion interface, by convention the WAN's name with a 6 on the end. */
    val v6Section: String get() = "${selected}6"

    val v6Config: WanConfig? get() = configs[v6Section]

    val v6Mode: V6Mode
        get() {
            val v6 = v6Config
            val wanV6 = value("network.$selected.ipv6", "")
            val v6Proto = value("network.$v6Section.proto", v6?.proto.orEmpty())
            val disabled =
                value("network.$v6Section.disabled", if (v6?.disabled == true) "1" else "0") == "1"
            return when {
                v6Proto == "6to4" -> V6Mode.SixToFour
                wanV6 == "auto" || wanV6 == "1" -> V6Mode.PppoeDual
                relayOn() -> V6Mode.Relay
                v6Proto.isNotEmpty() && !disabled -> V6Mode.Native
                else -> V6Mode.Off
            }
        }

    private fun relayOn(): Boolean =
        value("dhcp.lan.ra", dhcpUci["dhcp.lan.ra"].orEmpty()) == "relay"

    val pdSize: String
        get() = value("network.$v6Section.reqprefix", v6Config?.reqprefix.orEmpty())
            .ifEmpty { "auto" }

    val lanV6: LanV6
        get() {
            val ra = value("dhcp.lan.ra", dhcpUci["dhcp.lan.ra"].orEmpty())
            val dhcpv6 = value("dhcp.lan.dhcpv6", dhcpUci["dhcp.lan.dhcpv6"].orEmpty())
            val flags = list("dhcp.lan.ra_flags", Parsers.uciList(dhcpUci["dhcp.lan.ra_flags"].orEmpty()))
            return when {
                flags.contains("managed-config") -> LanV6.Stateful
                dhcpv6 == "disabled" || flags.contains("none") -> LanV6.Slaac
                flags == listOf("other-config") -> LanV6.NonAddress
                ra.isEmpty() && dhcpv6.isEmpty() -> LanV6.Auto
                else -> LanV6.Auto
            }
        }

    /**
     * Writes one IPv6 mode across both config files.
     *
     * The modes are different mechanisms, not one setting: a native uplink is its own
     * `dhcpv6` interface, dual-stack rides the PPPoE session and has no interface of its own,
     * and a relay is odhcpd forwarding the ISP's advertisements rather than the router
     * handing out anything of its own.
     */
    fun stageV6Mode(mode: V6Mode) {
        val v6 = v6Config
        val savedDisabled = if (v6?.disabled == true) "1" else "0"
        val savedProto = v6?.proto.orEmpty()
        val savedRa = dhcpUci["dhcp.lan.ra"].orEmpty()
        val savedDhcpv6 = dhcpUci["dhcp.lan.dhcpv6"].orEmpty()
        fun clearRelay() {
            if (savedRa != "relay") return
            stage("dhcp.lan.ra", savedRa, "server")
            stage("dhcp.lan.dhcpv6", savedDhcpv6, "server")
            stage("dhcp.lan.ndp", dhcpUci["dhcp.lan.ndp"].orEmpty(), "")
            // The upstream's relay half goes with it; a stray master section would keep
            // odhcpd relaying on an interface nothing else expects it to.
            sectionDrafts.remove("dhcp.$v6Section")
            listOf("master", "ra", "dhcpv6", "ndp").forEach { option ->
                stage("dhcp.$v6Section.$option", dhcpUci["dhcp.$v6Section.$option"].orEmpty(), "")
            }
        }
        when (mode) {
            V6Mode.Off -> {
                stage("network.$v6Section.disabled", savedDisabled, "1")
                stage("network.$selected.ipv6", "", "0")
                clearRelay()
            }
            V6Mode.Native -> {
                stage("network.$v6Section.proto", savedProto, "dhcpv6")
                stage(
                    "network.$v6Section.device",
                    v6?.device.orEmpty(),
                    value(path("device"), current?.device.orEmpty()),
                )
                stage("network.$v6Section.disabled", savedDisabled, "0")
                stage("network.$selected.ipv6", "", "")
                clearRelay()
            }
            V6Mode.PppoeDual -> {
                // The PPPoE session negotiates IPv6 itself; a second interface fights it.
                stage("network.$selected.ipv6", "", "auto")
                stage("network.$v6Section.disabled", savedDisabled, "1")
                clearRelay()
            }
            V6Mode.Relay -> {
                stage("network.$v6Section.proto", savedProto, "dhcpv6")
                stage("network.$v6Section.disabled", savedDisabled, "0")
                // The LAN relays what it is given...
                stage("dhcp.lan.ra", savedRa, "relay")
                stage("dhcp.lan.dhcpv6", savedDhcpv6, "relay")
                stage("dhcp.lan.ndp", dhcpUci["dhcp.lan.ndp"].orEmpty(), "relay")
                // ...and odhcpd has to be told where it is relaying FROM. Without a master
                // section on the upstream the LAN half relays nothing, which is the failure
                // this mode is famous for.
                if (dhcpUci["dhcp.$v6Section"] != "dhcp") sectionDrafts["dhcp.$v6Section"] = "dhcp"
                stage("dhcp.$v6Section.interface", dhcpUci["dhcp.$v6Section.interface"].orEmpty(), v6Section)
                stage("dhcp.$v6Section.master", dhcpUci["dhcp.$v6Section.master"].orEmpty(), "1")
                stage("dhcp.$v6Section.ra", dhcpUci["dhcp.$v6Section.ra"].orEmpty(), "relay")
                stage("dhcp.$v6Section.dhcpv6", dhcpUci["dhcp.$v6Section.dhcpv6"].orEmpty(), "relay")
                stage("dhcp.$v6Section.ndp", dhcpUci["dhcp.$v6Section.ndp"].orEmpty(), "relay")
            }
            V6Mode.SixToFour -> {
                stage("network.$v6Section.proto", savedProto, "6to4")
                stage("network.$v6Section.disabled", savedDisabled, "0")
                clearRelay()
            }
        }
    }

    fun stagePdSize(value: String) = stage(
        "network.$v6Section.reqprefix",
        v6Config?.reqprefix.orEmpty(),
        if (value == "auto") "" else value,
    )

    fun stageLanV6(mode: LanV6) {
        val savedRa = dhcpUci["dhcp.lan.ra"].orEmpty()
        val savedDhcpv6 = dhcpUci["dhcp.lan.dhcpv6"].orEmpty()
        val savedFlags = Parsers.uciList(dhcpUci["dhcp.lan.ra_flags"].orEmpty())
        when (mode) {
            // odhcpd's own defaults, which is what "auto" means: advertise and serve.
            LanV6.Auto -> {
                stage("dhcp.lan.ra", savedRa, "server")
                stage("dhcp.lan.dhcpv6", savedDhcpv6, "server")
                stageList("dhcp.lan.ra_flags", savedFlags, emptyList())
            }
            LanV6.Slaac -> {
                stage("dhcp.lan.ra", savedRa, "server")
                stage("dhcp.lan.dhcpv6", savedDhcpv6, "disabled")
                stageList("dhcp.lan.ra_flags", savedFlags, listOf("none"))
            }
            LanV6.Stateful -> {
                stage("dhcp.lan.ra", savedRa, "server")
                stage("dhcp.lan.dhcpv6", savedDhcpv6, "server")
                stageList("dhcp.lan.ra_flags", savedFlags, listOf("managed-config", "other-config"))
            }
            LanV6.NonAddress -> {
                stage("dhcp.lan.ra", savedRa, "server")
                stage("dhcp.lan.dhcpv6", savedDhcpv6, "server")
                stageList("dhcp.lan.ra_flags", savedFlags, listOf("other-config"))
            }
        }
    }

    // -----------------------------------------------------------------------
    // The connection test
    // -----------------------------------------------------------------------

    /**
     * Pings the gateway, two public resolvers and a name — four answers that separate "the
     * line is down" from "DNS is broken", which look identical from a browser.
     */
    suspend fun runTest() {
        if (testing) return
        testing = true
        error = null
        try {
            val gateway = links.firstOrNull { it.hasDefaultRoute }?.gateway.orEmpty()
            val out = session.exec(Commands.pingTest(gateway), timeoutMs = 40_000).stdout
            val parts = Parsers.sections(out)
            pings.clear()
            pings += Parsers.pingResult(parts["gw"].orEmpty(), "gateway", gateway.ifBlank { "—" })
            pings += Parsers.pingResult(parts["dns1"].orEmpty(), "1.1.1.1", "1.1.1.1")
            pings += Parsers.pingResult(parts["dns2"].orEmpty(), "8.8.8.8", "8.8.8.8")
            pings += Parsers.pingResult(parts["name"].orEmpty(), "dns name", "openwrt.org")
        } catch (e: SshException) {
            error = "Test failed: ${e.message}"
        } finally {
            testing = false
        }
    }

    // -----------------------------------------------------------------------
    // What applying would run
    // -----------------------------------------------------------------------

    fun packages(): List<String> {
        val paths = staged.keys + stagedLists.keys + sectionDrafts.keys +
            (if (deviceDrafts.isNotEmpty()) setOf("network.x") else emptySet())
        return listOf("network", "dhcp").filter { pkg -> paths.any { it.startsWith("$pkg.") } }
    }

    /** True when a `config device` is created or edited, which ifup alone will not pick up. */
    fun touchesDevice(): Boolean = deviceDrafts.isNotEmpty() ||
        (staged.keys + stagedLists.keys).any { key ->
            deviceSections.any { key.startsWith("network.${it.section}.") }
        }

    fun ops(): List<String> {
        val scalars = staged.entries.sortedBy { it.key }.map { (path, change) ->
            if (change.second.isEmpty()) "delete $path"
            else "set $path='${Commands.escapeValue(change.second)}'"
        }
        val lists = stagedLists.entries.sortedBy { it.key }
            .flatMap { (path, change) -> Commands.listOps(path, change.second) }
        val devices = deviceDrafts.values.flatMap { device ->
            listOf(
                "set network.${device.section}=device",
                "set network.${device.section}.name='${device.name}'",
                "set network.${device.section}.type='${device.type}'",
                "set network.${device.section}.ifname='${device.ifname}'",
                "set network.${device.section}.vid='${device.vid}'",
            )
        }
        // A section has to exist before anything can be set on it, and the device has to
        // exist before the interface names it.
        val sections = sectionDrafts.entries.sortedBy { it.key }.map { (path, type) -> "set $path=$type" }
        return devices + sections + scalars + lists
    }

    fun diffLines(): List<Pair<String, Boolean>> = buildList {
        sectionDrafts.entries.sortedBy { it.key }.forEach { (path, type) ->
            add("+ $path=$type" to true)
        }
        deviceDrafts.values.forEach { device ->
            add("+ network.${device.section}=device" to true)
            add("+ network.${device.section}.name='${device.name}'" to true)
            add("+ network.${device.section}.type='${device.type}'" to true)
            add("+ network.${device.section}.ifname='${device.ifname}' · vid '${device.vid}'" to true)
        }
        staged.entries.sortedBy { it.key }.forEach { (path, change) ->
            val secret = path.endsWith(".password")
            add("- $path='${if (secret) "••••••••" else change.first}'" to false)
            if (change.second.isNotEmpty()) {
                add("+ $path='${if (secret) WifiStore.mask(change.second) else change.second}'" to true)
            }
        }
        stagedLists.entries.sortedBy { it.key }.forEach { (path, change) ->
            change.first.forEach { add("- $path='$it'" to false) }
            change.second.forEach { add("+ $path='$it'" to true) }
        }
    }

    /** The reload the apply runs: one ifup when one interface changed, netifd otherwise. */
    fun reloadCommand(): String {
        val touched = touchedInterfaces()
        return if (touchesDevice() || touched.size > 1) Commands.NETWORK_RELOAD
        else Commands.ifup(touched.firstOrNull() ?: selected)
    }

    fun commitLine(): String {
        val packages = packages().ifEmpty { listOf("network") }
        val touched = touchedInterfaces()
        return "$ " + packages.joinToString(" && ") { "uci commit $it" } + " && " +
            if (touchesDevice() || touched.size > 1) "/etc/init.d/network reload"
            else "ifup ${touched.firstOrNull() ?: selected}"
    }

    // -----------------------------------------------------------------------
    // Refusals and warnings
    // -----------------------------------------------------------------------

    /**
     * True when this batch writes the selected interface's own options — its port, protocol
     * or settings. Checks on those are only owed then: a metric typed on another uplink from
     * the hub, or a v6 change, must not be refused because the selected interface already
     * lacked a socket or a protocol before the app arrived.
     */
    private fun editingSelected(): Boolean =
        selected in touchedInterfaces() || deviceDrafts.isNotEmpty()

    fun problems(): List<String> = buildList {
        val proto = proto
        if (editingSelected()) {
            when {
                proto.isEmpty() -> add("This interface has no protocol set.")
                !protoAvailable(proto) -> add(
                    "This router has no handler for '${protoLabel(proto)}' — install " +
                        "${protoPackage(proto)} first."
                )
                !protoEditable(proto) -> add(
                    "WrtPulse does not edit ${protoLabel(proto)} settings yet. Choosing it " +
                        "here would leave the interface half-configured, so set it from the terminal."
                )
            }
            // A socket is only required when the socket is what is being written. A Wi-Fi
            // client uplink has none, and an untouched wired one keeps whatever it had — the
            // refusal here blocked a metric edit on a router whose WAN is a radio.
            val writingDevice = path("device") in staged || deviceDrafts.isNotEmpty()
            if (port.isEmpty() && !wirelessUplink && writingDevice) {
                add("Pick the socket the ISP is plugged into.")
            }
        }
        configs.keys.forEach { section ->
            val text = metricText(section)
            if (text.isNotEmpty() && (text.toIntOrNull() == null || text.toInt() < 0)) {
                add("$section's metric '$text' is not a number. Lower wins; 0 to a few hundred is usual.")
            }
        }
        vlanId.takeIf { it.isNotEmpty() }?.let { id ->
            if (id.toIntOrNull()?.let { it in 1..4094 } != true) {
                add("A VLAN id has to be a number from 1 to 4094.")
            }
        }
        mtu.takeIf { it.isNotEmpty() }?.let { value ->
            when (val n = value.toIntOrNull()) {
                null -> add("$value is not an MTU.")
                else -> when {
                    n < 576 -> add("An MTU below 576 breaks IPv4 — 1500 is standard, 1492 for PPPoE.")
                    n > 9200 -> add("$n is past what the drivers here will take.")
                }
            }
        }
        macaddr.takeIf { it.isNotEmpty() }?.let { value ->
            if (!LanStore.validMac(value)) {
                add("$value is not a MAC address.")
            } else if (value.substringBefore(':').toInt(16) and 1 == 1) {
                add("$value is a multicast address — a MAC's first octet has to be even.")
            }
        }
        if (editingSelected() && proto == "pppoe" && option("username", current?.username.orEmpty()).isEmpty()) {
            add("PPPoE needs the username the ISP issued.")
        }
        if (editingSelected() && proto == "static") {
            if (!IpMath.valid(option("ipaddr", current?.ipaddr.orEmpty()))) {
                add("A static WAN needs its own IPv4 address.")
            }
            option("gateway", current?.gateway.orEmpty()).takeIf { it.isNotEmpty() }?.let {
                if (!IpMath.valid(it)) add("$it is not a gateway address.")
            }
            option("netmask", current?.netmask.orEmpty()).takeIf { it.isNotEmpty() }?.let {
                if (IpMath.prefixOf(it) == null) add("$it is not a usable netmask.")
            }
        }
    }

    /** True when this session reaches the router from outside its own LAN. */
    val remoteSession: Boolean
        get() {
            val lan = lanCidr ?: return false
            val here = IpMath.parse(session.target.host) ?: return false
            return IpMath.networkOf(here, lan.second) != lan.first
        }

    fun notes(): List<String> = buildList {
        add(
            "The internet drops for roughly 20 seconds while the gateway comes back up on the " +
                "new settings. Anything mid-download loses it."
        )
        if (remoteSession) {
            add(
                "This app reaches the router from outside its LAN, so it is talking over the " +
                    "link being changed. If the new settings do not come up, the rollback is " +
                    "the only way back in."
            )
        }
        deviceDrafts.values.firstOrNull()?.let { draft ->
            add(
                "A tagged device ${draft.name} is created and the interface moved onto it. If " +
                    "the ISP does not actually want VLAN ${draft.vid}, nothing comes up at all."
            )
        }
        val metricsTouched = staged.keys.filter { it.endsWith(".metric") }
        if (metricsTouched.isNotEmpty()) {
            add(
                "Metric is the whole of the failover: the uplink with the lower number carries " +
                    "the default route, and the other takes over only when the first one's " +
                    "route disappears — a dead cable or a dropped PPPoE session. A line that is " +
                    "up but not passing traffic is not failed over from; that needs mwan3."
            )
            val rows = wanRows()
            rows.groupBy { it.metric }.filter { it.value.size > 1 }.forEach { (metric, tied) ->
                add(
                    "${tied.joinToString(" and ") { it.section }} both have metric $metric, so " +
                        "which one carries traffic is up to the kernel, not you."
                )
            }
        }
        if (staged.keys.any { it.endsWith(".proto") }) {
            add(
                "Switching protocol keeps the port, VLAN and MAC settings — those live on the " +
                    "device, not the protocol."
            )
        }
        if (staged.keys.any { it.endsWith(".macaddr") }) {
            add(
                "A cloned MAC can take minutes to work: the ISP's DHCP server may hold the old " +
                    "lease until it expires."
            )
        }
        if (v6Mode == V6Mode.Relay && staged.keys.any { it.startsWith("dhcp.lan.") }) {
            add(
                "Relay hands the ISP's IPv6 straight to your clients. They get public " +
                    "addresses with no NAT in front of them, so the firewall is all that " +
                    "stands between them and the internet."
            )
        }
        if (staged.keys.any { it.startsWith("dhcp.") } || stagedLists.keys.any { it.startsWith("dhcp.") }) {
            add("The LAN's IPv6 settings belong to odhcpd, so its config is committed too.")
        }
    }

    // -----------------------------------------------------------------------
    // Applying, with the rollback armed
    // -----------------------------------------------------------------------

    /**
     * Applies with a rollback armed on the router.
     *
     * The sequence matters: the router copies the config and starts a detached watcher BEFORE
     * the batch runs, so a change that kills the link still gets undone. The app then has to
     * come back and re-read the router to confirm; `ifup` returning 0 proves nothing, because
     * it answers long before a PPPoE session is up.
     */
    suspend fun apply(seconds: Int = ROLLBACK_SECONDS): Boolean {
        if (pendingCount == 0 || applying) return true
        problems().firstOrNull()?.let { error = it; return false }
        applying = true
        error = null
        notice = null
        rolledBack = false
        val script = Commands.wanApply(ops(), reloadCommand(), seconds)
        return try {
            beforeApply?.invoke()
            session.exec(script, timeoutMs = 60_000).requireOk("uci batch")
            // Re-reading is the confirmation. If the link went with the change, this throws,
            // and the router puts the old config back on its own.
            load()
            session.exec(Commands.WAN_CONFIRM, timeoutMs = 15_000)
            revert()
            load()
            notice = "Applied — rollback disarmed"
            true
        } catch (e: SshException) {
            error = e.message
            checkRollback()
            false
        } finally {
            applying = false
        }
    }

    /**
     * Whether the watcher already restored the old config. Called when an apply lost the
     * link, so the screen says what the router did rather than guessing.
     */
    suspend fun checkRollback() {
        rolledBack = runCatching {
            session.exec(Commands.WAN_ROLLBACK_STATE, timeoutMs = 15_000)
                .stdout.contains("rolled-back")
        }.getOrDefault(false)
        if (rolledBack) {
            notice = "The router put the old settings back — the app could not reach it in time."
            revert()
            runCatching { load() }
        }
    }

    companion object {
        const val ROLLBACK_SECONDS = 30

        /** The design's protocol row, in its order. */
        val PROTO_ORDER = listOf("dhcp", "static", "pppoe", "l2tp", "pptp", "dslite", "map")

        /**
         * The protocols whose own settings this screen can write. The rest are listed so the
         * screen tells the truth about what the router supports, and refused for editing
         * rather than written half-configured.
         */
        val EDITABLE_PROTOS = setOf("dhcp", "static", "pppoe", "none")

        val MTU_CHOICES = listOf("1500", "1492")

        val PD_CHOICES = listOf("auto", "48", "56", "60", "64")

        fun protoLabel(name: String): String = when (name) {
            "dhcp" -> "DHCP"
            "static" -> "Static"
            "pppoe" -> "PPPoE"
            "l2tp" -> "L2TP"
            "pptp" -> "PPTP"
            "dslite" -> "DS-Lite"
            "map" -> "MAP-E"
            "" -> "unset"
            else -> name
        }

        /** The package that ships a protocol handler, for the "needs a package" note. */
        fun protoPackage(name: String): String = when (name) {
            "pppoe" -> "ppp-mod-pppoe"
            "l2tp" -> "xl2tpd"
            "pptp" -> "ppp-mod-pptp"
            "dslite" -> "ds-lite"
            "map" -> "map"
            "6to4" -> "6to4"
            else -> name
        }

        /** "up 18 d 04:12", the way the hub shows uptime. */
        fun uptimeLabel(seconds: Long): String {
            if (seconds <= 0) return "—"
            val days = seconds / 86_400
            val hours = (seconds % 86_400) / 3_600
            val minutes = (seconds % 3_600) / 60
            return if (days > 0) "up %d d %02d:%02d".format(days, hours, minutes)
            else "up %02d:%02d".format(hours, minutes)
        }
    }
}
