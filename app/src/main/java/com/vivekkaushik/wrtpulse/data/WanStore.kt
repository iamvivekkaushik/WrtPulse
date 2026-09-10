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
import com.vivekkaushik.wrtpulse.ops.SwPort
import com.vivekkaushik.wrtpulse.ops.SwitchDev
import com.vivekkaushik.wrtpulse.ops.SwitchVlan
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
 * One socket on the case that a WAN could sit on.
 *
 * On a DSA board [id] is the netdev (`wan`, `lan4`); on a swconfig board it is `sw:<n>`, the
 * switch port number, because there the socket has no netdev of its own — it reaches the
 * CPU through a VLAN. [inLan] says whether the LAN owns it today, which decides whether
 * moving the WAN onto it also has to take it away from the LAN.
 */
data class Socket(
    val id: String,
    val label: String,
    val up: Boolean,
    val speedMbps: Int?,
    val inLan: Boolean,
) {
    val switchPort: Int? get() = id.removePrefix("sw:").toIntOrNull().takeIf { id.startsWith("sw:") }
}

/** A swconfig VLAN the apply would create to carry a WAN socket to the CPU. */
data class SwitchVlanDraft(val section: String, val device: String, val vlan: Int, val ports: List<SwPort>)

/**
 * Internet & WAN gateways — design screens 26-30.
 *
 * The same staging contract as [WifiStore] and [LanStore], with one addition the other two
 * do not need: applying here can cut the link that issued the command. So an apply arms a
 * rollback on the router first — the old `/etc/config/network` goes back unless the app
 * reappears and confirms — and the confirmation is the app having actually re-read the
 * router, not the command having returned 0.
 */
class WanStore(private val session: RouterSession) : Refreshable {

    val links = mutableStateListOf<WanLink>()
    val zones = mutableStateListOf<FirewallZone>()
    val devs = mutableStateListOf<NetDev>()
    val deviceSections = mutableStateListOf<NetDevice>()

    /** The switch chips on a swconfig board, and the VLANs the config carves on them. Empty on DSA. */
    val swDevs = mutableStateListOf<SwitchDev>()
    val swVlans = mutableStateListOf<SwitchVlan>()

    /** The `network` config as read, for the lookups that need the raw map. */
    private val networkUci = mutableStateMapOf<String, String>()

    /** uci interface name -> its configured form. */
    val configs = mutableStateMapOf<String, WanConfig>()

    /** Protocols netifd can actually bring up here. */
    val protos = mutableStateListOf<String>()

    /** `uci show dhcp` for the LAN's IPv6 half, which lives in odhcpd's config. */
    private val dhcpUci = mutableStateMapOf<String, String>()

    /** The LAN's own subnet, for working out whether this session comes in over the WAN. */
    private var lanCidr by mutableStateOf<Pair<Long, Int>?>(null)

    override var loaded by mutableStateOf(false); private set
    override var applying by mutableStateOf(false); private set
    /** Which interface is being tested, so only that one's card says so. */
    var testingSection by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /**
     * The last connection test per interface. A test is about one uplink, so its result
     * belongs to that uplink — one shared list meant every chip showed the primary's run.
     */
    val pingsBySection = mutableStateMapOf<String, List<PingResult>>()

    /** The selected interface's last test, empty until it has been tested itself. */
    val pings: List<PingResult> get() = pingsBySection[selected].orEmpty()

    val testing: Boolean get() = testingSection == selected && selected.isNotEmpty()

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

    /**
     * swconfig VLANs the apply would create, keyed by section name. A socket on such a board
     * reaches the CPU only through a VLAN, so putting the WAN on a socket the LAN does not
     * own means carving one for it — `ports '5 0t'`, and the WAN rides `eth0.<vid>`.
     */
    val switchVlanDrafts = mutableStateMapOf<String, SwitchVlanDraft>()

    val pendingCount: Int
        get() = staged.size + stagedLists.size + deviceDrafts.size + sectionDrafts.size +
            switchVlanDrafts.size

    /**
     * Runs before the batch — the auto-backup hook. Set by the app when "snapshot before
     * every Apply" is on; the store itself knows nothing about backups. A failure here
     * aborts the apply, because a snapshot that silently didn't happen is not a snapshot.
     */
    var beforeApply: (suspend () -> Unit)? = null

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    override suspend fun load() {
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
        swDevs.clear(); swDevs.addAll(Parsers.switchDevs(parts["swconfig"].orEmpty()))
        swVlans.clear(); swVlans.addAll(Parsers.switchVlans(network))
        networkUci.clear(); networkUci.putAll(network)
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
        // A drafted uplink is not in the config yet; a live refresh must not jump off it.
        if ((selected.isEmpty() || selected !in configs) && !selectedIsDraft) {
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
        // An uplink drafted by [addWiredUplink] exists only in the staging maps until the
        // apply lands, and the hub has to show it so the user can see what they are creating.
        val drafted = sectionDrafts.filter { (path, type) -> type == "interface" && path.startsWith("network.") }
            .keys.map { it.removePrefix("network.") }
        val names = ((zoned + routed).filter { name ->
            name in configs || links.any { it.name == name }
        } + drafted).distinct()
        val rows = names.map { name ->
            val link = links.firstOrNull { it.name == name }
            val config = configs[name]
            WanRow(
                section = name,
                proto = value("network.$name.proto", config?.proto.orEmpty().ifEmpty { link?.proto.orEmpty() }),
                device = link?.device?.ifBlank { null }
                    ?: value("network.$name.device", config?.device.orEmpty()),
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
        switchVlanDrafts.clear()
        error = null
        // A drafted uplink that was never applied has nothing left to select.
        if (selected !in configs) selected = wanRows().firstOrNull()?.section.orEmpty()
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

    /**
     * Rewrites the whole order at once from a list of sections, best first: 10, 20, 30.
     *
     * Every uplink is staged rather than only the one that moved, because a metric means
     * nothing on its own — it is a comparison. Restamping the list is what keeps the numbers
     * total and distinct; moving one and leaving the rest is exactly how two uplinks end up
     * sharing a metric, which [notes] then has to complain about.
     *
     * The gap of ten is the convention in OpenWrt's own docs, and it leaves room to drop a
     * third uplink between two without renumbering by hand. Anything already carrying the
     * number it is being given stages nothing: [stage] drops a write equal to the saved value,
     * so reordering two uplinks and putting them back leaves an empty batch.
     */
    fun stageFailoverOrder(sections: List<String>) {
        sections.forEachIndexed { i, section ->
            stageMetric(section, ((i + 1) * FAILOVER_STEP).toString())
        }
    }

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

    /**
     * The swconfig VLAN the selected WAN rides — `eth0.2` names VLAN 2 — as its id and the
     * ports it carries, saved or drafted. Null on DSA, or when the device names no VLAN.
     */
    private fun wanSwitchVlan(): Pair<Int, List<SwPort>>? {
        if (!swconfig) return null
        val vid = value(path("device"), current?.device.orEmpty()).substringAfter('.', "").toIntOrNull()
            ?: return null
        switchVlanDrafts.values.firstOrNull { it.vlan == vid }?.let { return it.vlan to it.ports }
        val saved = swVlans.firstOrNull { it.vlan == vid } ?: return null
        return saved.vlan to Parsers.swPorts(value("network.${saved.section}.ports", saved.ports))
    }

    /**
     * The physical port under the WAN, with any VLAN tag stripped off.
     *
     * On a swconfig board this is the socket id, `sw:5`: the netdev `eth0.2` says which VLAN
     * the WAN rides, and the VLAN's member says which socket that is.
     */
    val port: String
        get() {
            wanSwitchVlan()?.let { (_, ports) ->
                val cpu = switchDev?.cpuPort
                ports.firstOrNull { it.port != cpu }?.let { return "sw:${it.port}" }
            }
            val name = value(path("device"), current?.device.orEmpty())
            val tagged = wanDevice?.takeIf { it.type == "8021q" }
            return tagged?.ifname?.ifBlank { null } ?: name.substringBefore('.')
        }

    /**
     * The 802.1q tag the ISP sees, or "" when the WAN sits on an untagged port.
     *
     * On swconfig the switch strips the VLAN on an untagged member, so `eth0.2` with
     * `ports '5 0t'` is untagged at the socket; only a tagged member (`5t`) carries the tag out.
     */
    val vlanId: String
        get() {
            wanSwitchVlan()?.let { (vid, ports) ->
                val cpu = switchDev?.cpuPort
                val member = ports.firstOrNull { it.port != cpu } ?: return ""
                return if (member.tagged) vid.toString() else ""
            }
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
                selectedSocket?.label ?: port,
                vlanId.takeIf { it.isNotEmpty() }?.let { "vlan $it · 802.1q" },
                mtu.takeIf { it.isNotEmpty() }?.let { "mtu $it" },
            ).joinToString(" · ")
        }

    /** Ports a WAN could sit on: every socket, plus whatever it is on now. */
    fun availablePorts(): List<String> =
        (sockets().map { it.id } + port).filter { it.isNotEmpty() }.distinct()

    // ---- the sockets on the case ----

    /** True on a board whose sockets hang off a swconfig chip rather than being netdevs. */
    val swconfig: Boolean get() = swDevs.isNotEmpty()

    private val switchDev: SwitchDev? get() = swDevs.firstOrNull()

    /** The VLAN the LAN rides on a swconfig board, and its section. */
    private fun lanSwitchVlan(): SwitchVlan? {
        val vlan = Parsers.lanSwitchVlan(networkUci) ?: return null
        return swVlans.firstOrNull { it.vlan == vlan }
    }

    /** `eth0` — what a switch VLAN's netdev is named after, from the LAN's own member `eth0.1`. */
    private fun switchBase(): String =
        Parsers.lanSwitchMember(networkUci)?.substringBefore('.')?.ifBlank { null } ?: "eth0"

    /** The LAN bridge's members on a DSA board, plus anything a legacy `ifname` lists. */
    private fun lanBridgeMembers(): Set<String> {
        val device = networkUci["network.lan.device"].orEmpty()
        val bridge = deviceSections.firstOrNull { it.name == device }
        val legacy = Parsers.uciList(networkUci["network.lan.ifname"].orEmpty()).flatMap { it.split(' ') }
        return (bridge?.ports.orEmpty() + legacy).filter { it.isNotBlank() }.toSet()
    }

    /**
     * Every socket on the case, with whether the LAN owns it.
     *
     * On DSA the sockets are netdevs and the LAN owns the ones in its bridge. On swconfig they
     * are switch port numbers, and the LAN owns the ones in its VLAN; a socket in no VLAN at
     * all reaches nothing, so it is offered as free.
     */
    fun sockets(): List<Socket> {
        val dev = switchDev
        if (dev != null) {
            val lanPorts = lanSwitchVlan()?.let { Parsers.swPorts(it.ports) }.orEmpty().map { it.port }.toSet()
            return Parsers.switchSockets(dev, swVlans).map { n ->
                val link = dev.links[n]
                Socket("sw:$n", "Port $n", link?.up == true, link?.speedMbps, n in lanPorts)
            }
        }
        val members = lanBridgeMembers()
        return Parsers.switchPorts(devs).map { d ->
            Socket(d.name, d.name, d.carrier, d.speedMbps, d.name in members)
        }
    }

    /** The socket behind an id, or a stand-in for a netdev the case does not list (a radio). */
    fun socketFor(id: String): Socket =
        sockets().firstOrNull { it.id == id } ?: Socket(id, id, up = false, speedMbps = null, inLan = false)

    /** The socket the selected WAN sits on, when it is one the case lists. */
    val selectedSocket: Socket? get() = sockets().firstOrNull { it.id == port }

    /** The LAN's sockets as saved — what it keeps after the staged move, minus the moved one. */
    private fun lanSockets(): List<Socket> = sockets().filter { it.inLan }

    /** The `device` option as it will be written — `lan4`, `eth1.201`, `eth0.2`. */
    val deviceName: String get() = value(path("device"), current?.device.orEmpty())

    /** The socket a `device` value sits on, or null for a radio or a name the case does not list. */
    private fun socketOf(device: String): String? {
        if (device.isEmpty()) return null
        if (swconfig) {
            val vid = device.substringAfter('.', "").toIntOrNull() ?: return null
            val cpu = switchDev?.cpuPort
            val ports = switchVlanDrafts.values.firstOrNull { it.vlan == vid }?.ports
                ?: swVlans.firstOrNull { it.vlan == vid }
                    ?.let { Parsers.swPorts(value("network.${it.section}.ports", it.ports)) }
                ?: return null
            return ports.firstOrNull { it.port != cpu }?.let { "sw:${it.port}" }
        }
        val tagged = deviceSections.firstOrNull { it.name == device && it.type == "8021q" }
        return tagged?.ifname?.ifBlank { null } ?: device.substringBefore('.')
    }

    /** Sockets an uplink already sits on, so the add sheet does not offer them. */
    fun usedSockets(): Set<String> = wanRows().mapNotNull { row ->
        socketOf(value("network.${row.section}.device", configs[row.section]?.device.orEmpty()))
    }.toSet()

    /**
     * What [addWiredUplink] would do, in words — the sheet shows it before anything is staged,
     * because "VLAN 2 on switch0, ports 5 0t" is the kind of thing worth reading first.
     */
    fun wiredUplinkPreview(socket: Socket, proto: String): List<String> = buildList {
        val name = nextUplinkName()
        val switchPort = socket.switchPort
        if (switchPort != null) {
            val dev = switchDev
            val cpu = dev?.cpuPort
            val lanVlan = Parsers.lanSwitchVlan(networkUci)
            if (socket.inLan && lanVlan != null) add("${socket.label} leaves the LAN's VLAN $lanVlan.")
            if (cpu == null) {
                add("${dev?.name ?: "The switch"} does not say which port is the CPU, so no VLAN can be written.")
            } else {
                val vid = switchVlanFor(switchPort, cpu)
                val reused = swVlans.any { it.vlan == vid }
                add(
                    (if (reused) "VLAN $vid on ${dev.name} is reused" else "VLAN $vid is created on ${dev.name}") +
                        ": ${socket.label} untagged, CPU port $cpu tagged — netdev ${switchBase()}.$vid."
                )
                add("Interface $name is created on ${switchBase()}.$vid, ${protoLabel(proto)}.")
            }
        } else {
            if (socket.inLan) {
                val bridge = networkUci["network.lan.device"].orEmpty().ifEmpty { "the LAN bridge" }
                add("${socket.label} leaves $bridge.")
            }
            add("Interface $name is created on ${socket.label}, ${protoLabel(proto)}.")
        }
        val zone = zones.firstOrNull { it.name == "wan" }
        when {
            zone == null -> add("No firewall zone is called wan, so $name is not masqueraded until one is.")
            name in zone.networks -> add("The firewall's wan zone already lists $name.")
            else -> add("$name joins the firewall's wan zone.")
        }
        if (socket.inLan && lanSockets().none { it.id != socket.id }) {
            add("The LAN keeps no ethernet socket after this; wired clients have nowhere to plug in.")
        }
        add("It joins the failover order last. Drag it up once it is carrying traffic.")
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

    fun stagePort(value: String) = stageSocket(socketFor(value), vlanId)

    fun stageVlan(id: String) = stageSocket(socketFor(port), id.trim())

    /**
     * Points the WAN at a socket, tagged or not — and takes the socket away from the LAN
     * when the LAN owns it, because a port cannot be in the LAN bridge and carry the WAN.
     *
     * On DSA the socket is a netdev: it leaves the bridge's `ports` (and any bridge VLAN that
     * lists it), and the interface names it — through a `config device` of type 8021q named
     * `<port>.<vid>` when the ISP wants a tag, since without one netifd brings the interface
     * up untagged and the ISP never sees a thing.
     *
     * On swconfig the socket is a switch port and has no netdev. It leaves the LAN's VLAN and
     * gets one of its own — `ports '5 0t'`, the CPU tagged so the netdev `eth0.2` can tell the
     * VLANs apart — and the interface names that netdev. An ISP tag is the same VLAN with the
     * socket tagged too (`5t 0t`) and the VLAN id set to the ISP's: the tag then leaves the
     * socket intact instead of being stacked on `eth0.2` as a second one.
     */
    private fun stageSocket(socket: Socket, newVlan: String) {
        val saved = current?.device.orEmpty()
        deviceDrafts.clear()
        unstageSocketMoves()
        val switchPort = socket.switchPort
        if (switchPort == null) {
            if (socket.inLan) freeFromBridge(socket.id)
            if (newVlan.isEmpty()) {
                stage(path("device"), saved, socket.id)
                return
            }
            val name = "${socket.id}.$newVlan"
            val existing = deviceSections.firstOrNull { it.name == name && it.type == "8021q" }
            if (existing == null) {
                deviceDrafts[name] = NetDevice(
                    section = deviceSectionName(socket.id, newVlan),
                    name = name,
                    type = "8021q",
                    ifname = socket.id,
                    vid = newVlan,
                    macaddr = "",
                    mtu = "",
                    egressQos = "",
                )
            }
            stage(path("device"), saved, name)
            return
        }
        val dev = switchDev ?: return
        if (socket.inLan) freeFromLanSwitchVlan(switchPort)
        val cpu = dev.cpuPort
        if (cpu == null) {
            // Without the CPU port a VLAN cannot be written; stage the intent so [problems]
            // refuses with the reason rather than the screen silently doing nothing.
            stage(path("device"), saved, socket.id)
            return
        }
        val vid = newVlan.toIntOrNull() ?: switchVlanFor(switchPort, cpu)
        val ports = listOf(SwPort(switchPort, tagged = newVlan.isNotEmpty()), SwPort(cpu, tagged = true))
        val existing = swVlans.firstOrNull { it.vlan == vid && it.device == dev.name }
        if (existing != null) {
            stage("network.${existing.section}.ports", existing.ports, Parsers.swPortsValue(ports))
        } else {
            val section = WifiStore.free("swvlan$vid", swVlans.map { it.section }.toSet())
            switchVlanDrafts[section] = SwitchVlanDraft(section, dev.name, vid, ports)
        }
        stage(path("device"), saved, "${switchBase()}.$vid")
    }

    /**
     * The swconfig VLAN a socket should ride to the CPU: one the config already dedicates to
     * it — a WAN VLAN left behind when the interface was deleted, say — else the lowest free
     * id from 2, the way every stock config numbers the WAN.
     */
    private fun switchVlanFor(switchPort: Int, cpu: Int): Int {
        swVlans.firstOrNull { vlan ->
            Parsers.swPorts(vlan.ports).filter { it.port != cpu }.map { it.port } == listOf(switchPort)
        }?.let { return it.vlan }
        val taken = swVlans.map { it.vlan }.toSet() + switchVlanDrafts.values.map { it.vlan }
        return (2..4094).first { it !in taken }
    }

    /** Takes a switch port out of the LAN's VLAN. */
    private fun freeFromLanSwitchVlan(switchPort: Int) {
        val lan = lanSwitchVlan() ?: return
        val kept = Parsers.swPorts(lan.ports).filterNot { it.port == switchPort }
        stage("network.${lan.section}.ports", lan.ports, Parsers.swPortsValue(kept))
    }

    /** Takes a netdev out of the LAN bridge, and out of every bridge VLAN that lists it. */
    private fun freeFromBridge(name: String) {
        val device = networkUci["network.lan.device"].orEmpty()
        val bridge = deviceSections.firstOrNull { it.name == device }
        if (bridge != null && name in bridge.ports) {
            stageList("network.${bridge.section}.ports", bridge.ports, bridge.ports.filterNot { it == name })
        }
        Parsers.bridgeVlans(networkUci).filter { vlan -> vlan.ports.any { it.name == name } }.forEach { vlan ->
            val tokens = vlan.ports.map { it.token() }
            stageList("network.${vlan.section}.ports", tokens, vlan.ports.filterNot { it.name == name }.map { it.token() })
        }
        // Pre-bridge configs list the members straight on the interface.
        val legacy = networkUci["network.lan.ifname"].orEmpty()
        if (bridge == null && legacy.isNotEmpty()) {
            val kept = Parsers.uciList(legacy).flatMap { it.split(' ') }.filterNot { it == name || it.isBlank() }
            stage("network.lan.ifname", legacy, kept.joinToString(" "))
        }
    }

    /** Undoes the LAN-side half of an earlier socket pick, so re-picking does not pile up. */
    private fun unstageSocketMoves() {
        switchVlanDrafts.clear()
        val switchSections = swVlans.map { "network.${it.section}.ports" }.toSet()
        staged.keys.filter { it in switchSections || it == "network.lan.ifname" }.forEach { staged.remove(it) }
        val bridgeSections = deviceSections.filter { it.type == "bridge" }.map { "network.${it.section}.ports" } +
            Parsers.bridgeVlans(networkUci).map { "network.${it.section}.ports" }
        stagedLists.keys.filter { it in bridgeSections }.forEach { stagedLists.remove(it) }
    }

    /** True when the batch reprograms the switch or the LAN bridge — every wired client blinks. */
    fun touchesSwitch(): Boolean {
        if (switchVlanDrafts.isNotEmpty()) return true
        val switchSections = swVlans.map { "network.${it.section}.ports" }.toSet()
        if (staged.keys.any { it in switchSections || it == "network.lan.ifname" }) return true
        val bridgeSections = deviceSections.filter { it.type == "bridge" }.map { "network.${it.section}.ports" } +
            Parsers.bridgeVlans(networkUci).map { "network.${it.section}.ports" }
        return stagedLists.keys.any { it in bridgeSections }
    }

    // ---- a new wired uplink ----

    /** The section a new wired uplink would get: `wan`, or `wan_2` when that is taken. */
    fun nextUplinkName(): String =
        WifiStore.free("wan", configs.keys + sectionDrafts.keys.map { it.removePrefix("network.") })

    /**
     * Creates a WAN interface on a socket, in one batch: the interface, its protocol, a
     * metric that puts it last in the failover order until it is dragged, the socket freed
     * from the LAN and named as the device, and membership of the firewall's wan zone.
     *
     * The router the design had in mind has a `wan` already; the one this was written on had
     * two LAN sockets and no WAN at all, and the only way to get one was the terminal.
     */
    fun addWiredUplink(socket: Socket, proto: String = "dhcp") {
        val highest = wanRows().maxOfOrNull { it.metric } ?: 0
        val name = nextUplinkName()
        sectionDrafts["network.$name"] = "interface"
        selected = name
        stage(path("proto"), "", proto)
        stage(path("metric"), "", (highest + FAILOVER_STEP).toString())
        stageSocket(socket, "")
        val zone = zones.firstOrNull { it.name == "wan" }
        if (zone != null && name !in zone.networks) {
            stageList("firewall.${zone.section}.network", zone.networks, zone.networks + name)
        }
    }

    /** True when the selected uplink exists only in this batch. */
    val selectedIsDraft: Boolean get() = "network.$selected" in sectionDrafts

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
    /**
     * Tests one uplink — by default the selected one, not whichever holds the default route.
     *
     * The pings are bound to that interface's device, so a standby WAN is measured over its
     * own link instead of following the default route out of the primary.
     */
    suspend fun runTest(section: String = selected) {
        if (testingSection != null || section.isEmpty()) return
        testingSection = section
        error = null
        try {
            val link = links.firstOrNull { it.name == section }
            val device = link?.device.orEmpty()
            if (device.isBlank()) {
                // Nothing to bind to, and an unbound ping would measure the default route instead.
                pingsBySection[section] = listOf(downTile(section))
                return
            }
            val gateway = link?.gateway.orEmpty()
            val out = session.exec(Commands.pingTest(gateway, device), timeoutMs = 40_000).stdout
            pingsBySection[section] = pingTiles(Parsers.sections(out), gateway)
        } catch (e: SshException) {
            error = "Test failed: ${e.message}"
        } finally {
            testingSection = null
        }
    }

    // -----------------------------------------------------------------------
    // What applying would run
    // -----------------------------------------------------------------------

    fun packages(): List<String> {
        val paths = staged.keys + stagedLists.keys + sectionDrafts.keys +
            (if (deviceDrafts.isNotEmpty() || switchVlanDrafts.isNotEmpty()) setOf("network.x") else emptySet())
        return listOf("network", "dhcp", "firewall").filter { pkg -> paths.any { it.startsWith("$pkg.") } }
    }

    /**
     * True when the batch changes something `ifup` alone will not pick up: a `config device`
     * created or edited, a switch VLAN or bridge membership, or an interface that does not
     * exist yet.
     */
    fun touchesDevice(): Boolean = deviceDrafts.isNotEmpty() || touchesSwitch() ||
        sectionDrafts.keys.any { it.startsWith("network.") } ||
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
        val switchVlans = switchVlanDrafts.values.sortedBy { it.vlan }.flatMap { draft ->
            listOf(
                "set network.${draft.section}=switch_vlan",
                "set network.${draft.section}.device='${draft.device}'",
                "set network.${draft.section}.vlan='${draft.vlan}'",
                "set network.${draft.section}.ports='${Parsers.swPortsValue(draft.ports)}'",
            )
        }
        // A section has to exist before anything can be set on it, and the device has to
        // exist before the interface names it.
        val sections = sectionDrafts.entries.sortedBy { it.key }.map { (path, type) -> "set $path=$type" }
        return devices + switchVlans + sections + scalars + lists
    }

    fun diffLines(): List<Pair<String, Boolean>> = buildList {
        sectionDrafts.entries.sortedBy { it.key }.forEach { (path, type) ->
            add("+ $path=$type" to true)
        }
        switchVlanDrafts.values.sortedBy { it.vlan }.forEach { draft ->
            add("+ network.${draft.section}=switch_vlan" to true)
            add("+ network.${draft.section}.device='${draft.device}' · vlan '${draft.vlan}'" to true)
            add("+ network.${draft.section}.ports='${Parsers.swPortsValue(draft.ports)}'" to true)
        }
        deviceDrafts.values.forEach { device ->
            add("+ network.${device.section}=device" to true)
            add("+ network.${device.section}.name='${device.name}'" to true)
            add("+ network.${device.section}.type='${device.type}'" to true)
            add("+ network.${device.section}.ifname='${device.ifname}' · vid '${device.vid}'" to true)
        }
        staged.entries.sortedBy { it.key }.forEach { (path, change) ->
            val secret = path.endsWith(".password")
            // An option that had no value — every option of a new section — has nothing to
            // strike out; a red `=''` line would only suggest something was there.
            if (change.first.isNotEmpty() || change.second.isEmpty()) {
                add("- $path='${if (secret) "••••••••" else change.first}'" to false)
            }
            if (change.second.isNotEmpty()) {
                add("+ $path='${if (secret) WifiStore.mask(change.second) else change.second}'" to true)
            }
        }
        stagedLists.entries.sortedBy { it.key }.forEach { (path, change) ->
            change.first.forEach { add("- $path='$it'" to false) }
            change.second.forEach { add("+ $path='$it'" to true) }
        }
    }

    /**
     * The reload the apply runs: one ifup when one interface changed, netifd otherwise — plus
     * odhcpd whenever the batch wrote `dhcp`, since netifd does not carry router advertisements
     * and a committed `ra`/`dhcpv6` change would otherwise go unannounced.
     */
    fun reloadCommand(): String {
        val touched = touchedInterfaces()
        val packages = packages()
        val network = if (touchesDevice() || touched.size > 1) Commands.NETWORK_RELOAD
        else Commands.ifup(touched.firstOrNull() ?: selected)
        return buildString {
            append(network)
            if ("dhcp" in packages) append("; ").append(Commands.ODHCPD_RELOAD)
            // A new interface in the wan zone is masqueraded only once the firewall re-reads.
            if ("firewall" in packages) append("; ").append(Commands.FIREWALL_RELOAD).append(" >/dev/null 2>&1; :")
        }
    }

    fun commitLine(): String {
        val packages = packages().ifEmpty { listOf("network") }
        val touched = touchedInterfaces()
        val reload = if (touchesDevice() || touched.size > 1) "/etc/init.d/network reload"
        else "ifup ${touched.firstOrNull() ?: selected}"
        return "$ " + packages.joinToString(" && ") { "uci commit $it" } + " && " + reload +
            (if ("dhcp" in packages) "; /etc/init.d/odhcpd reload" else "") +
            (if ("firewall" in packages) "; /etc/init.d/firewall reload" else "")
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
        selected in touchedInterfaces() || deviceDrafts.isNotEmpty() || selectedIsDraft

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
            if (writingDevice && swconfig && switchDev?.cpuPort == null) {
                add(
                    "${switchDev?.name ?: "The switch"} did not report which port is the CPU, so " +
                        "a VLAN for the WAN socket cannot be written. Set it from the terminal."
                )
            }
            if (writingDevice && swconfig) {
                val lanVlan = Parsers.lanSwitchVlan(networkUci)
                val wanVlan = deviceName.substringAfter('.', "").toIntOrNull()
                if (lanVlan != null && wanVlan == lanVlan) {
                    add("VLAN $lanVlan is the LAN's own — the WAN cannot share it.")
                }
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
        sectionDrafts.keys.filter { it.startsWith("network.") }.forEach { path ->
            val name = path.removePrefix("network.")
            add(
                "A new interface $name is created on ${value("network.$name.device", "")}. It " +
                    "joins the failover list last; drag it up once it is carrying traffic."
            )
        }
        if (touchesSwitch()) {
            add(
                "Every ethernet client drops for a few seconds while the switch is reprogrammed. " +
                    "Stay on Wi-Fi so the app can come back and confirm before the rollback fires."
            )
            selectedSocket?.takeIf { it.inLan }?.let { socket ->
                val remaining = lanSockets().filterNot { it.id == socket.id }
                add(
                    "${socket.label} leaves the LAN" +
                        if (remaining.isEmpty()) " — and it was the LAN's last ethernet socket, so wired clients have nowhere to plug in."
                        else "; the LAN keeps ${remaining.joinToString(", ") { it.label }}."
                )
            }
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

    /**
     * Uplinks whose address falls inside the LAN's own subnet — the hub shows these whether or
     * not anything is staged, because it is the state of the router, not of the batch.
     *
     * It happens the moment a WAN is plugged into an ISP router that hands out the same
     * 192.168.0.x the LAN uses: two routes to one subnet, and the kernel picks the LAN's.
     */
    fun subnetClashes(): List<String> = buildList {
        val (lanNet, prefix) = lanCidr ?: return@buildList
        wanRows().filter { it.address.isNotEmpty() }.forEach { row ->
            val address = IpMath.parse(row.address) ?: return@forEach
            if (IpMath.networkOf(address, prefix) == lanNet) {
                add(
                    "${row.section} got ${row.address} from the ISP — the same subnet as the LAN, " +
                        "so the router cannot tell the two apart and nothing routes. Move the LAN " +
                        "to another range in LAN · Subnet."
                )
            }
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
        // Reprogramming the switch takes every wired link down and back; a phone on Wi-Fi is
        // fine, but the router's own reload is slower, so the app gets longer to come back.
        val window = if (touchesSwitch()) maxOf(seconds, SWITCH_ROLLBACK_SECONDS) else seconds
        val script = Commands.wanApply(ops(), packages(), reloadCommand(), window)
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

        /** The gap between one uplink's metric and the next when the list is restamped. */
        const val FAILOVER_STEP = 10

        /**
         * The tiles a test produces, from the sections the script echoed.
         *
         * A standby uplink usually holds no default route and so has no gateway to ping;
         * that is reported as such rather than by pinging loopback and labelling the
         * result "gateway".
         */
        fun pingTiles(parts: Map<String, String>, gateway: String): List<PingResult> = listOf(
            if (gateway.isBlank()) PingResult("gateway", "—", 100, null, "no gateway")
            else Parsers.pingResult(parts["gw"].orEmpty(), "gateway", gateway),
            Parsers.pingResult(parts["dns1"].orEmpty(), "1.1.1.1", "1.1.1.1"),
            Parsers.pingResult(parts["dns2"].orEmpty(), "8.8.8.8", "8.8.8.8"),
            Parsers.pingResult(parts["name"].orEmpty(), "dns name", "openwrt.org"),
        )

        /** An interface with no device carries nothing, so there is nothing to measure. */
        fun downTile(section: String): PingResult =
            PingResult("interface", section, 100, null, "down — no device")

        const val ROLLBACK_SECONDS = 30

        /** The window when the batch reprograms the switch — the reload itself takes longer. */
        const val SWITCH_ROLLBACK_SECONDS = 60

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
