package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BridgeVlan
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.DhcpPool
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.LanLive
import com.vivekkaushik.wrtpulse.ops.LanNet
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.Neigh
import com.vivekkaushik.wrtpulse.ops.NetDev
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.Reservation
import com.vivekkaushik.wrtpulse.ops.SwPort
import com.vivekkaushik.wrtpulse.ops.SwitchDev
import com.vivekkaushik.wrtpulse.ops.SwitchVlan
import com.vivekkaushik.wrtpulse.ops.VlanPort

/** A static lease the user has drawn up. Nothing exists on the router until apply. */
data class ResvDraft(
    val id: Int,
    val name: String,
    val mac: String,
    val ip: String,
)

/** A VLAN staged onto a bridge. Ports are added by tapping the matrix, same as a saved one. */
data class VlanDraft(
    val id: Int,
    val device: String,
    val vlan: Int,
    val ports: List<VlanPort>,
)

/** How a port sits in a VLAN. The matrix cycles through these in order. */
enum class PortState { Off, Untagged, Tagged }

/**
 * The LAN: its subnet, the DHCP server on it, the static leases, and the switch VLANs behind
 * it — design screens 21-25.
 *
 * Same contract as [WifiStore]: every edit is staged here, the review sheet shows the exact
 * uci operations, and the router hears nothing until apply. Two config files are in play
 * (`network` and `dhcp`) and they are committed together, because a subnet that moves
 * without its DHCP pool leaves every client asking the wrong router for an address.
 */
class LanStore(private val session: RouterSession) : Refreshable {

    /** The interface this screen is about. Every OpenWrt install calls it `lan`. */
    val section = "lan"

    var net by mutableStateOf<LanNet?>(null); private set
    var live by mutableStateOf<LanLive?>(null); private set
    var pool by mutableStateOf<DhcpPool?>(null); private set
    val reservations = mutableStateListOf<Reservation>()
    val leases = mutableStateListOf<Lease>()
    val neighbours = mutableStateListOf<Neigh>()
    val devs = mutableStateListOf<NetDev>()
    val vlans = mutableStateListOf<BridgeVlan>()
    val swVlans = mutableStateListOf<SwitchVlan>()

    /** The switch chips, on the boards that have one. Empty on DSA. */
    val swDevs = mutableStateListOf<SwitchDev>()

    /** The `network` config as read, for the handful of lookups that need the raw map. */
    private val networkUci = mutableStateMapOf<String, String>()

    /** swconfig VLANs the apply would create. */
    val swVlanDrafts = mutableStateListOf<SwVlanDraft>()

    /** dnsmasq's process, not its config: `ignore '0'` with nothing running serves nobody. */
    var dnsmasqRunning by mutableStateOf(false); private set

    override var loaded by mutableStateOf(false); private set
    override var applying by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    /**
     * Set once an apply has moved the router's own address. The session is gone at that
     * point — there is nothing left to re-read, so the screen stops showing state and says
     * where the router went instead.
     */
    var movedTo by mutableStateOf<String?>(null); private set

    /** Full uci path → (saved value, staged value). */
    val staged = mutableStateMapOf<String, Pair<String, String>>()

    /** Full uci path → (saved list, staged list), for `dns` and `dhcp_option`. */
    val stagedLists = mutableStateMapOf<String, Pair<List<String>, List<String>>>()

    /** Sections staged for `uci delete`, as full paths: `dhcp.cfg0a3b`, `network.vlan20`. */
    val deletions = mutableStateListOf<String>()

    val resvDrafts = mutableStateListOf<ResvDraft>()
    val vlanDrafts = mutableStateListOf<VlanDraft>()
    private var nextDraftId by mutableIntStateOf(1)

    val pendingCount: Int
        get() = staged.size + stagedLists.size + deletions.size + resvDrafts.size +
            vlanDrafts.size + swVlanDrafts.size

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
        if (movedTo != null) return
        try {
            val out = session.exec(Commands.lanState(section), timeoutMs = 20_000)
                .requireOk("read lan").stdout
            ingest(Parsers.sections(out))
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    /**
     * Everything [load] does apart from the round trip. Split out because it is where all the
     * parsing lives: the tests feed it captured router output and never need a connection.
     */
    fun ingest(parts: Map<String, String>) {
        val network = Parsers.uciShow(parts["net"].orEmpty())
        val dhcp = Parsers.uciShow(parts["dhcp"].orEmpty())
        net = Parsers.lanNet(network, section)
        live = Parsers.interfaceStatus(parts["live"].orEmpty())
        pool = Parsers.dhcpPools(dhcp).firstOrNull { it.interfaceName == section }
        reservations.clear(); reservations.addAll(Parsers.reservations(dhcp).sortedBy { sortKey(it.ip) })
        leases.clear(); leases.addAll(Parsers.leases(parts["leases"].orEmpty()))
        neighbours.clear(); neighbours.addAll(Parsers.neighEntries(parts["neigh"].orEmpty()))
        devs.clear(); devs.addAll(Parsers.netdevs(parts["links"].orEmpty()))
        vlans.clear(); vlans.addAll(Parsers.bridgeVlans(network))
        swVlans.clear(); swVlans.addAll(Parsers.switchVlans(network))
        swDevs.clear(); swDevs.addAll(Parsers.switchDevs(parts["swconfig"].orEmpty()))
        networkUci.clear(); networkUci.putAll(network)
        dnsmasqRunning = parts["dnsmasq"].orEmpty().contains("running")
    }

    /** The raw uci behind a section — what "view command" opens. */
    suspend fun showUci(path: String): String = try {
        session.exec(Commands.showUci(path), timeoutMs = 8_000).stdout.trim()
            .ifEmpty { "$path has no options set." }
    } catch (e: SshException) {
        e.message ?: "could not read $path"
    }

    // -----------------------------------------------------------------------
    // Staging
    // -----------------------------------------------------------------------

    fun stage(path: String, saved: String, value: String) {
        if (value == saved) staged.remove(path) else staged[path] = saved to value
    }

    fun value(path: String, saved: String): String = staged[path]?.second ?: saved

    /**
     * Stages a value whose spelling is not significant.
     *
     * `ports '3 5 0t'` and `ports '0t 3 5'` are the same port map, so cycling a chip all the
     * way round must not leave a staged change that rewrites the option for nothing. The
     * `-` line in the diff still shows the file's own spelling.
     */
    private fun stageNormalised(
        path: String,
        saved: String,
        value: String,
        normalise: (String) -> String,
    ) {
        if (normalise(value) == normalise(saved)) staged.remove(path)
        else staged[path] = saved to value
    }

    fun stageList(path: String, saved: List<String>, values: List<String>) {
        if (values == saved) stagedLists.remove(path) else stagedLists[path] = saved to values
    }

    fun list(path: String, saved: List<String>): List<String> = stagedLists[path]?.second ?: saved

    fun stageDelete(path: String) {
        if (path !in deletions) deletions.add(path)
    }

    fun undoDelete(path: String) = deletions.remove(path)

    fun isDeleting(path: String) = path in deletions

    fun revert() {
        staged.clear()
        stagedLists.clear()
        deletions.clear()
        resvDrafts.clear()
        vlanDrafts.clear()
        swVlanDrafts.clear()
        error = null
    }

    // ---- the subnet ----

    private val ipPath get() = "network.$section.ipaddr"
    private val maskPath get() = "network.$section.netmask"
    val dnsPath get() = "network.$section.dns"

    /**
     * True when this router spells its address as CIDR. Those configs carry no `netmask`
     * option at all, so the two edits have to be written back as one.
     */
    val cidrStyle: Boolean get() = net?.cidrPrefix != null

    /**
     * The saved netmask, whatever form the router keeps it in: the option, else the prefix
     * embedded in `ipaddr`, else what netifd reports for the live interface.
     */
    private val savedNetmask: String
        get() = net?.netmask.orEmpty().ifBlank {
            val bits = net?.cidrPrefix ?: live?.prefix?.takeIf { it in 1..32 }
            bits?.let { IpMath.netmaskOf(it) } ?: "255.255.255.0"
        }

    /** The router's LAN address as the screen should show it: staged if edited, else saved. */
    val routerIp: String get() = value(ipPath, net?.ipaddr.orEmpty())
    val netmask: String get() = value(maskPath, savedNetmask)
    val prefix: Int get() = IpMath.prefixOf(netmask) ?: 24
    val dns: List<String> get() = list(dnsPath, net?.dns.orEmpty())

    fun stageRouterIp(value: String) = stage(ipPath, net?.ipaddr.orEmpty(), value.trim())

    fun stageNetmask(value: String) = stage(maskPath, savedNetmask, value.trim())

    fun stageDns(values: List<String>) = stageList(dnsPath, net?.dns.orEmpty(), values)

    /** True once the staged address differs from the one this session is talking to. */
    val movesAddress: Boolean
        get() = net?.ipaddr?.let { it.isNotEmpty() && routerIp != it } == true

    // ---- the DHCP server ----

    private fun dhcpPath(option: String) = "dhcp.${pool?.section ?: section}.$option"

    val dhcpOn: Boolean get() = value(dhcpPath("ignore"), if (pool?.ignore == true) "1" else "0") != "1"
    val poolStart: Int get() = value(dhcpPath("start"), (pool?.start ?: 100).toString()).toIntOrNull() ?: 0
    val poolLimit: Int get() = value(dhcpPath("limit"), (pool?.limit ?: 150).toString()).toIntOrNull() ?: 0
    val leaseTime: String get() = value(dhcpPath("leasetime"), pool?.leasetime.orEmpty().ifBlank { "12h" })
    val dhcpOptions: List<String> get() = list(dhcpPath("dhcp_option"), pool?.options.orEmpty())

    fun toggleDhcp() = stage(dhcpPath("ignore"), if (pool?.ignore == true) "1" else "0", if (dhcpOn) "1" else "0")

    fun stagePoolStart(value: String) = stage(dhcpPath("start"), (pool?.start ?: 100).toString(), value.trim())

    fun stagePoolLimit(value: String) = stage(dhcpPath("limit"), (pool?.limit ?: 150).toString(), value.trim())

    fun stageLeaseTime(value: String) =
        stage(dhcpPath("leasetime"), pool?.leasetime.orEmpty().ifBlank { "12h" }, value)

    fun stageDhcpOptions(values: List<String>) =
        stageList(dhcpPath("dhcp_option"), pool?.options.orEmpty(), values)

    /** The subnet the pool is measured against — the staged one, because that is what applies. */
    val networkAddress: Long? get() = IpMath.parse(routerIp)?.let { IpMath.networkOf(it, prefix) }

    val poolRange: LongRange?
        get() = networkAddress?.let { IpMath.poolRange(it, prefix, poolStart, poolLimit) }

    // ---- static leases ----

    /**
     * Every device the router can see, reservation or not: leases first, then anything in the
     * neighbour table that never took a lease (a statically configured machine, or a wired
     * client whose lease has expired but is still talking).
     */
    fun activeClients(): List<LanClient> {
        val byMac = linkedMapOf<String, LanClient>()
        leases.forEach { lease ->
            byMac[lease.mac] = LanClient(
                mac = lease.mac,
                ip = lease.ip,
                name = lease.hostname.orEmpty(),
                expiry = lease.expiry,
            )
        }
        neighbours
            // A v6 link-local neighbour is not something DHCP handed out, and a neighbour on
            // another interface is not on this LAN — on a router repeating an upstream in the
            // same /24, the upstream's own gateway turned up here as a client.
            .filter {
                it.mac.isNotEmpty() && it.state != "FAILED" && IpMath.valid(it.ip) &&
                    (lanDevice.isEmpty() || it.dev == lanDevice)
            }
            .forEach { neigh ->
                if (byMac[neigh.mac] == null) {
                    byMac[neigh.mac] = LanClient(neigh.mac, neigh.ip, "", 0)
                }
            }
        // Rows, not the saved list: a reservation staged a moment ago has to show as one, or
        // the row keeps offering Reserve IP and a second tap stages a duplicate.
        val reserved = reservationRows().filterNot { it.deleting }.associateBy { it.mac }
        return byMac.values
            .map { it.copy(reservedIp = reserved[it.mac]?.ip) }
            .sortedBy { sortKey(it.ip) }
    }

    /** Reservations as the list should render them, staged edits and deletions included. */
    fun reservationRows(): List<ResvRow> {
        val saved = reservations.map { resv ->
            val path = "dhcp.${resv.section}"
            ResvRow(
                key = path,
                section = resv.section,
                draftId = null,
                name = value("$path.name", resv.name),
                mac = value("$path.mac", resv.mac),
                ip = value("$path.ip", resv.ip),
                isNew = false,
                changed = staged.keys.any { it.startsWith("$path.") },
                deleting = path in deletions,
            )
        }
        val drafts = resvDrafts.map { draft ->
            ResvRow(
                key = "draft:${draft.id}",
                section = null,
                draftId = draft.id,
                name = draft.name,
                mac = draft.mac,
                ip = draft.ip,
                isNew = true,
                changed = true,
                deleting = false,
            )
        }
        return (saved + drafts).sortedBy { sortKey(it.ip) }
    }

    fun addReservation(name: String, mac: String, ip: String): ResvDraft {
        val draft = ResvDraft(nextDraftId++, name.trim(), mac.trim().lowercase(), ip.trim())
        resvDrafts.add(draft)
        return draft
    }

    fun editReservation(section: String, name: String, mac: String, ip: String) {
        val saved = reservations.firstOrNull { it.section == section } ?: return
        stage("dhcp.$section.name", saved.name, name.trim())
        stage("dhcp.$section.mac", saved.mac, mac.trim().lowercase())
        stage("dhcp.$section.ip", saved.ip, ip.trim())
    }

    fun updateDraft(id: Int, name: String, mac: String, ip: String) {
        val index = resvDrafts.indexOfFirst { it.id == id }
        if (index >= 0) {
            resvDrafts[index] = resvDrafts[index]
                .copy(name = name.trim(), mac = mac.trim().lowercase(), ip = ip.trim())
        }
    }

    fun removeDraft(id: Int) = resvDrafts.removeAll { it.id == id }

    /**
     * Addresses something already holds — what a new reservation must not land on.
     *
     * [exceptMac] is the device being reserved, and the address it holds right now is the
     * whole point: pinning the lease a device already has is the common case, and counting
     * that lease as a clash refused it.
     */
    fun takenAddresses(
        exceptSection: String? = null,
        exceptDraft: Int? = null,
        exceptMac: String? = null,
    ): Set<Long> {
        val mac = exceptMac?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return (reservationRows()
            .filterNot { it.section != null && it.section == exceptSection }
            .filterNot { it.draftId != null && it.draftId == exceptDraft }
            .filterNot { mac != null && it.mac == mac }
            .filterNot { it.deleting }
            .mapNotNull { IpMath.parse(it.ip) } +
            leases.filterNot { mac != null && it.mac == mac }.mapNotNull { IpMath.parse(it.ip) })
            .toSet()
    }

    /** The address the add-reservation sheet should open with. */
    fun suggestedIp(): String {
        val network = networkAddress ?: return ""
        val router = IpMath.parse(routerIp) ?: 0
        return IpMath.firstFree(network, prefix, takenAddresses(), poolRange, router).orEmpty()
    }

    /** Where an address sits, for the sheet's line under the field. */
    fun addressPlacement(ip: String): String? {
        val value = IpMath.parse(ip) ?: return null
        val network = networkAddress ?: return null
        if (IpMath.networkOf(value, prefix) != network) return "outside the LAN subnet"
        val range = poolRange
        return when {
            value == IpMath.parse(routerIp) -> "the router's own address"
            range != null && value in range -> "inside the DHCP pool"
            range != null && value < range.first -> "in the static range below the pool"
            else -> "in the static range above the pool"
        }
    }

    // ---- VLANs ----

    /** The netdev the LAN interface is on — the only one whose neighbours are LAN clients. */
    val lanDevice: String get() = (live?.device ?: net?.device).orEmpty()

    /** The bridge the LAN interface sits on: `br-lan`, or `br-lan.1` reduced to its bridge. */
    val lanBridge: String
        get() = (net?.device ?: live?.device).orEmpty().substringBefore('.')

    /** The VLAN carrying the LAN interface, when the interface names one. */
    val lanVlan: Int?
        get() = (net?.device ?: "").substringAfter('.', "").toIntOrNull()

    fun vlanRows(): List<VlanRow> {
        val saved = vlans.map { vlan ->
            val path = "network.${vlan.section}"
            VlanRow(
                key = path,
                section = vlan.section,
                draftId = null,
                device = vlan.device,
                vlan = vlan.vlan,
                ports = portsOf(path, vlan.ports),
                isNew = false,
                changed = path in stagedLists,
                deleting = path in deletions,
            )
        }
        val drafts = vlanDrafts.map { draft ->
            VlanRow(
                key = "vlandraft:${draft.id}",
                section = null,
                draftId = draft.id,
                device = draft.device,
                vlan = draft.vlan,
                ports = draft.ports,
                isNew = true,
                changed = true,
                deleting = false,
            )
        }
        return (saved + drafts).sortedBy { it.vlan }
    }

    private fun portsOf(path: String, saved: List<VlanPort>): List<VlanPort> =
        list("$path.ports", saved.map { it.token() }).map { Parsers.vlanPort(it) }

    /** Off → untagged → tagged → off, the cycle the design's port chips describe. */
    fun cyclePort(row: VlanRow, port: String) {
        val next = when (stateOf(row, port)) {
            PortState.Off -> PortState.Untagged
            PortState.Untagged -> PortState.Tagged
            PortState.Tagged -> PortState.Off
        }
        setPort(row, port, next)
    }

    fun stateOf(row: VlanRow, port: String): PortState {
        val entry = row.ports.firstOrNull { it.name == port } ?: return PortState.Off
        return if (entry.tagged) PortState.Tagged else PortState.Untagged
    }

    fun setPort(row: VlanRow, port: String, state: PortState) {
        val without = row.ports.filterNot { it.name == port }
        val updated = when (state) {
            PortState.Off -> without
            // An untagged port needs the PVID or nothing arriving on it belongs to this VLAN.
            PortState.Untagged -> without + VlanPort(port, tagged = false, pvid = true)
            PortState.Tagged -> without + VlanPort(port, tagged = true, pvid = false)
        }.sortedBy { it.name }
        if (row.draftId != null) {
            val index = vlanDrafts.indexOfFirst { it.id == row.draftId }
            if (index >= 0) vlanDrafts[index] = vlanDrafts[index].copy(ports = updated)
        } else if (row.section != null) {
            val savedPorts = vlans.firstOrNull { it.section == row.section }?.ports.orEmpty()
            // Sorted on both sides for the same reason the swconfig map is normalised: a
            // round trip through the chips must come back to "nothing staged".
            stageList(
                "network.${row.section}.ports",
                savedPorts.map { it.token() }.sorted(),
                updated.map { it.token() },
            )
        }
    }

    fun addVlan(vlan: Int): VlanDraft {
        val draft = VlanDraft(nextDraftId++, lanBridge.ifEmpty { "br-lan" }, vlan, emptyList())
        vlanDrafts.add(draft)
        return draft
    }

    fun removeVlanDraft(id: Int) = vlanDrafts.removeAll { it.id == id }

    /** The next VLAN id nothing holds — what the create sheet opens with. */
    fun freeVlanId(): Int {
        val taken = (vlans.map { it.vlan } + vlanDrafts.map { it.vlan }).toSet()
        return (1..4094).firstOrNull { it !in taken } ?: 4094
    }

    /**
     * Whether a VLAN can be deleted from its row. The one the LAN interface rides cannot:
     * removing it takes away the netdev the router's own address is on, and nothing left in
     * the app could put it back.
     */
    fun vlanDeleteBlock(row: VlanRow): String? = when {
        row.draftId != null -> null
        row.vlan == lanVlan && row.device == lanBridge ->
            "VLAN ${row.vlan} carries ${net?.device} — the LAN interface itself sits on it."
        else -> null
    }

    // ---- swconfig VLANs ----

    /** The chip this screen edits. Boards with two switches are rare; the first is the one. */
    val switchDev: SwitchDev? get() = swDevs.firstOrNull()

    /** Sockets on the chip, CPU port excluded — those are the ones a cable goes into. */
    fun switchSockets(): List<Int> {
        val dev = switchDev ?: return emptyList()
        val count = if (dev.ports > 0) dev.ports else 6
        return (0 until count).filter { it != dev.cpuPort }
    }

    /**
     * The VLAN the LAN rides on a swconfig board.
     *
     * Not `network.lan.device` — that is `br-lan`. The VLAN is named by the bridge's member,
     * `eth0.1`, so the search goes through the bridge's port list as well as the interface's
     * own device and the legacy `ifname`.
     */
    val lanSwVlan: Int?
        get() {
            val device = net?.device.orEmpty()
            device.substringAfter('.', "").toIntOrNull()?.let { return it }
            val members = buildList {
                // A `config device` bridge lists its members in `ports`.
                networkUci.entries.filter { it.value == "device" && it.key.count { c -> c == '.' } == 1 }
                    .forEach { (key, _) ->
                        val section = key.substringAfter('.')
                        if (networkUci["network.$section.name"] == device) {
                            addAll(Parsers.uciList(networkUci["network.$section.ports"].orEmpty())
                                .flatMap { it.split(' ') })
                        }
                    }
                // Pre-bridge configs put the members straight on the interface.
                addAll(Parsers.uciList(networkUci["network.${section}.ifname"].orEmpty())
                    .flatMap { it.split(' ') })
            }
            return members.mapNotNull { it.substringAfter('.', "").toIntOrNull() }.firstOrNull()
        }

    /** True when the chip is in VLAN mode at all; adding VLANs does nothing while it is off. */
    val vlanModeOn: Boolean
        get() {
            val switchSection = networkUci.entries
                .firstOrNull { it.value == "switch" && it.key.count { c -> c == '.' } == 1 }
                ?.key?.substringAfter('.') ?: return true
            val value = value(
                "network.$switchSection.enable_vlan",
                networkUci["network.$switchSection.enable_vlan"].orEmpty(),
            )
            // Absent means the driver default, which is on for every board that ships VLANs.
            return value != "0"
        }

    fun swVlanRows(): List<SwVlanRow> {
        val saved = swVlans.map { vlan ->
            val path = "network.${vlan.section}"
            SwVlanRow(
                key = path,
                section = vlan.section,
                draftId = null,
                device = vlan.device,
                vlan = vlan.vlan,
                ports = Parsers.swPorts(value("$path.ports", vlan.ports)),
                isNew = false,
                changed = "$path.ports" in staged,
                deleting = path in deletions,
            )
        }
        val drafts = swVlanDrafts.map { draft ->
            SwVlanRow(
                key = "swdraft:${draft.id}",
                section = null,
                draftId = draft.id,
                device = draft.device,
                vlan = draft.vlan,
                ports = draft.ports,
                isNew = true,
                changed = true,
                deleting = false,
            )
        }
        return (saved + drafts).sortedBy { it.vlan }
    }

    fun swStateOf(row: SwVlanRow, port: Int): PortState {
        val entry = row.ports.firstOrNull { it.port == port } ?: return PortState.Off
        return if (entry.tagged) PortState.Tagged else PortState.Untagged
    }

    /** Off → untagged → tagged → off, the same cycle the DSA matrix uses. */
    fun cycleSwPort(row: SwVlanRow, port: Int) {
        val next = when (swStateOf(row, port)) {
            PortState.Off -> PortState.Untagged
            PortState.Untagged -> PortState.Tagged
            PortState.Tagged -> PortState.Off
        }
        setSwPort(row, port, next)
    }

    fun setSwPort(row: SwVlanRow, port: Int, state: PortState) {
        val without = row.ports.filterNot { it.port == port }
        val updated = when (state) {
            PortState.Off -> without
            PortState.Untagged -> without + SwPort(port, tagged = false)
            PortState.Tagged -> without + SwPort(port, tagged = true)
        }.sortedBy { it.port }
        if (row.draftId != null) {
            val index = swVlanDrafts.indexOfFirst { it.id == row.draftId }
            if (index >= 0) swVlanDrafts[index] = swVlanDrafts[index].copy(ports = updated)
        } else if (row.section != null) {
            val savedPorts = swVlans.firstOrNull { it.section == row.section }?.ports.orEmpty()
            stageNormalised(
                "network.${row.section}.ports",
                savedPorts,
                Parsers.swPortsValue(updated),
            ) { Parsers.swPortsValue(Parsers.swPorts(it)) }
        }
    }

    /**
     * Stages a new swconfig VLAN with the CPU port already tagged in it.
     *
     * Without the CPU port the router cannot see the VLAN at all, so the app puts it there
     * rather than leaving the user to discover that by locking a socket into a VLAN nothing
     * can reach.
     */
    fun addSwVlan(vlan: Int): SwVlanDraft {
        val dev = switchDev
        val cpu = dev?.cpuPort
        // A VLAN added while the chip is not in VLAN mode does nothing at all. Turning the
        // mode on is staged alongside, so it shows in the diff rather than happening quietly
        // or being left as a puzzle.
        if (!vlanModeOn) {
            networkUci.entries
                .firstOrNull { it.value == "switch" && it.key.count { c -> c == '.' } == 1 }
                ?.key?.substringAfter('.')
                ?.let { switchSection ->
                    stage(
                        "network.$switchSection.enable_vlan",
                        networkUci["network.$switchSection.enable_vlan"].orEmpty(),
                        "1",
                    )
                }
        }
        val draft = SwVlanDraft(
            id = nextDraftId++,
            device = dev?.name ?: swVlans.firstOrNull()?.device ?: "switch0",
            vlan = vlan,
            ports = if (cpu != null) listOf(SwPort(cpu, tagged = true)) else emptyList(),
        )
        swVlanDrafts.add(draft)
        return draft
    }

    fun removeSwVlanDraft(id: Int) = swVlanDrafts.removeAll { it.id == id }

    fun freeSwVlanId(): Int {
        val taken = (swVlans.map { it.vlan } + swVlanDrafts.map { it.vlan }).toSet()
        return (1..4094).firstOrNull { it !in taken } ?: 4094
    }

    /**
     * Why a swconfig VLAN cannot be deleted from its row: the LAN rides one of them, and
     * removing it takes the netdev the router's own address sits on.
     */
    fun swVlanDeleteBlock(row: SwVlanRow): String? = when {
        row.draftId != null -> null
        row.vlan == lanSwVlan ->
            "VLAN ${row.vlan} carries ${net?.device} through ${lanMember()} — the LAN itself is on it."
        else -> null
    }

    /** `eth0.1` — the bridge member that ties the LAN to its VLAN, for the refusal's wording. */
    private fun lanMember(): String {
        val vlan = lanSwVlan ?: return "the LAN bridge"
        val base = swVlans.firstOrNull()?.device?.let { if (it.startsWith("switch")) "eth0" else it }
        return "${base ?: "eth0"}.$vlan"
    }

    /** Whether a socket is up right now — the only way to tell which number is which case hole. */
    fun socketUp(port: Int): Boolean = switchDev?.links?.get(port)?.up == true

    fun socketSpeed(port: Int): Int? = switchDev?.links?.get(port)?.speedMbps

    /** Devices seen on a VLAN's netdev, for the row's "N clients". */
    fun clientsOn(netdev: String): Int =
        neighbours.count { it.dev == netdev && it.mac.isNotEmpty() && IpMath.valid(it.ip) }

    // -----------------------------------------------------------------------
    // What applying would run
    // -----------------------------------------------------------------------

    /** uci packages the staged work touches, in commit order. */
    fun packages(): List<String> {
        val paths = staged.keys + stagedLists.keys + deletions +
            (if (resvDrafts.isNotEmpty()) setOf("dhcp.x") else emptySet()) +
            (if (vlanDrafts.isNotEmpty()) setOf("network.x") else emptySet())
        return listOf("network", "dhcp").filter { pkg -> paths.any { it.startsWith("$pkg.") } }
    }

    /**
     * The address writes, which depend on how the router spells its own address. A config
     * carrying `ipaddr '192.168.1.1/24'` has no `netmask` option, and writing the two
     * separately would leave the prefix behind on the old value — so both edits collapse
     * into one CIDR write.
     */
    private fun addressOps(): List<String> {
        if (ipPath !in staged && maskPath !in staged) return emptyList()
        if (cidrStyle) return listOf("set $ipPath='$routerIp/$prefix'")
        return listOfNotNull(
            staged[ipPath]?.let { "set $ipPath='${Commands.escapeValue(it.second)}'" },
            staged[maskPath]?.let { "set $maskPath='${Commands.escapeValue(it.second)}'" },
        )
    }

    fun ops(): List<String> {
        val alive = { path: String -> deletions.none { path.startsWith("$it.") } }
        val scalars = addressOps() + staged.entries
            .filter { alive(it.key) && it.key != ipPath && it.key != maskPath }
            .sortedBy { it.key }
            .map { (path, change) ->
                // An emptied option is removed rather than set to '': uci keeps the empty
                // string, and netifd reads that as a configured value of nothing.
                if (change.second.isEmpty()) "delete $path"
                else "set $path='${Commands.escapeValue(change.second)}'"
            }
        val lists = stagedLists.entries
            .filter { alive(it.key) }
            .sortedBy { it.key }
            .flatMap { (path, change) -> Commands.listOps(path, change.second) }
        val removals = deletions.sorted().map { "delete $it" }
        val newReservations = resvDrafts.flatMap { draft ->
            val name = resvSection(draft)
            listOf("set dhcp.$name=host") + resvOptions(draft).map { (option, value) ->
                "set dhcp.$name.$option='${Commands.escapeValue(value)}'"
            }
        }
        val newSwVlans = swVlanDrafts.flatMap { draft ->
            val name = swVlanSection(draft)
            listOf(
                "set network.$name=switch_vlan",
                "set network.$name.device='${draft.device}'",
                "set network.$name.vlan='${draft.vlan}'",
                "set network.$name.ports='${Parsers.swPortsValue(draft.ports)}'",
            )
        }
        val newVlans = vlanDrafts.flatMap { draft ->
            val name = vlanSection(draft)
            listOf(
                "set network.$name=bridge-vlan",
                "set network.$name.device='${draft.device}'",
                "set network.$name.vlan='${draft.vlan}'",
            ) + Commands.listOps("network.$name.ports", draft.ports.map { it.token() })
                // A brand new list has nothing to delete first.
                .filterNot { it.startsWith("delete ") }
        }
        return scalars + lists + removals + newReservations + newVlans + newSwVlans
    }

    private fun swVlanSection(draft: SwVlanDraft): String =
        WifiStore.free("swvlan${draft.vlan}", swVlans.map { it.section }.toSet())

    private fun resvOptions(draft: ResvDraft): List<Pair<String, String>> = buildList {
        if (draft.name.isNotEmpty()) add("name" to draft.name)
        add("mac" to draft.mac)
        add("ip" to draft.ip)
    }

    /** `- old` / `+ new` for the review sheet, grouped the way the diff reads. */
    fun diffLines(): List<Pair<String, Boolean>> = buildList {
        if (cidrStyle && (ipPath in staged || maskPath in staged)) {
            add("- $ipPath='${net?.ipaddr}/${net?.cidrPrefix}'" to false)
            add("+ $ipPath='$routerIp/$prefix'" to true)
        }
        staged.entries
            .filter { entry -> deletions.none { entry.key.startsWith("$it.") } }
            .filterNot { cidrStyle && (it.key == ipPath || it.key == maskPath) }
            .sortedBy { it.key }
            .forEach { (path, change) ->
                add("- $path='${change.first}'" to false)
                if (change.second.isNotEmpty()) add("+ $path='${change.second}'" to true)
            }
        stagedLists.entries.sortedBy { it.key }.forEach { (path, change) ->
            change.first.forEach { add("- $path='$it'" to false) }
            change.second.forEach { add("+ $path='$it'" to true) }
        }
        deletions.sorted().forEach { add("- $it" to false) }
        resvDrafts.forEach { draft ->
            val name = resvSection(draft)
            add("+ dhcp.$name=host" to true)
            resvOptions(draft).forEach { (option, value) -> add("+ dhcp.$name.$option='$value'" to true) }
        }
        swVlanDrafts.forEach { draft ->
            val name = swVlanSection(draft)
            add("+ network.$name=switch_vlan" to true)
            add("+ network.$name.device='${draft.device}'" to true)
            add("+ network.$name.vlan='${draft.vlan}'" to true)
            add("+ network.$name.ports='${Parsers.swPortsValue(draft.ports)}'" to true)
        }
        vlanDrafts.forEach { draft ->
            val name = vlanSection(draft)
            add("+ network.$name=bridge-vlan" to true)
            add("+ network.$name.vlan='${draft.vlan}'" to true)
            draft.ports.forEach { add("+ network.$name.ports='${it.token()}'" to true) }
        }
    }

    fun commitLine(): String {
        val packages = packages().ifEmpty { listOf("network") }
        return "$ " + packages.joinToString(" && ") { "uci commit $it" } + " && " +
            reloadLabel()
    }

    private fun reloadLabel(): String = when {
        movesAddress -> "/etc/init.d/network reload   # this connection ends here"
        packages().contains("network") && packages().contains("dhcp") ->
            "/etc/init.d/network reload && /etc/init.d/dnsmasq restart"
        packages().contains("network") -> "/etc/init.d/network reload"
        else -> "/etc/init.d/dnsmasq restart"
    }

    /**
     * A uci section name from the device name — `nas_backup` reads better in
     * /etc/config/dhcp than the `cfg0a91b2` uci would have generated, and it is what the
     * review sheet shows.
     */
    private fun resvSection(draft: ResvDraft): String {
        val taken = reservations.map { it.section }.toSet() +
            resvDrafts.filter { it.id != draft.id }.map { resvSectionBase(it) }
        return WifiStore.free(resvSectionBase(draft), taken)
    }

    private fun resvSectionBase(draft: ResvDraft): String {
        val slug = draft.name.lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
            .joinToString("")
            .trim('_')
            .replace(Regex("_+"), "_")
            .take(24)
        return slug.ifEmpty { "host_" + draft.mac.filter { it.isLetterOrDigit() }.takeLast(6) }
    }

    private fun vlanSection(draft: VlanDraft): String {
        val taken = vlans.map { it.section }.toSet()
        return WifiStore.free("vlan${draft.vlan}", taken)
    }

    // -----------------------------------------------------------------------
    // Refusals and warnings
    // -----------------------------------------------------------------------

    /** Changes the router would reject, or that would leave nothing able to fix them. */
    fun problems(): List<String> = buildList {
        addAll(subnetProblems())
        addAll(dhcpProblems())
        addAll(reservationProblems())
        addAll(vlanProblems())
        addAll(swVlanProblems())
    }

    private fun subnetProblems(): List<String> = buildList {
        val ip = routerIp
        if (ip.isEmpty()) return@buildList
        val parsed = IpMath.parse(ip)
        if (parsed == null) {
            add("$ip is not an IPv4 address — four numbers, 0 to 255, no leading zeros.")
            return@buildList
        }
        val mask = netmask
        val bits = IpMath.prefixOf(mask)
        if (bits == null) {
            add("$mask is not a usable netmask: it has to be a run of ones then zeros.")
            return@buildList
        }
        if (bits > 30) {
            add("A /$bits leaves no addresses for clients. A LAN needs /30 or wider.")
            return@buildList
        }
        val network = IpMath.networkOf(parsed, bits)
        if (parsed == network) add("$ip is the subnet's network address; a host cannot hold it.")
        if (parsed == IpMath.broadcastOf(parsed, bits)) {
            add("$ip is the subnet's broadcast address; a host cannot hold it.")
        }
        dns.filterNot { IpMath.valid(it) }.forEach { add("DNS $it is not an IPv4 address.") }
    }

    private fun dhcpProblems(): List<String> = buildList {
        if (!dhcpOn) return@buildList
        val network = networkAddress ?: return@buildList
        if (poolStart <= 0) add("The DHCP pool has to start at 1 or more.")
        if (poolLimit <= 0) add("A DHCP pool of $poolLimit addresses would serve nobody.")
        if (poolStart <= 0 || poolLimit <= 0) return@buildList
        val range = IpMath.poolRange(network, prefix, poolStart, poolLimit)
        if (range == null) {
            add("The pool starts past the end of ${IpMath.format(network)}/$prefix.")
            return@buildList
        }
        val wanted = network + poolStart + poolLimit - 1
        if (wanted > IpMath.broadcastOf(network, prefix) - 1) {
            add(
                "$poolLimit addresses from .$poolStart runs past " +
                    "${IpMath.format(IpMath.broadcastOf(network, prefix))} — the subnet only " +
                    "holds ${IpMath.usableHosts(prefix)} hosts."
            )
        }
        IpMath.parse(routerIp)?.let { router ->
            if (router in range) {
                add("The pool covers the router's own address ${IpMath.format(router)}.")
            }
        }
        if (Parsers.leaseSeconds(leaseTime) == null) {
            add("$leaseTime is not a lease time dnsmasq understands — try 12h, 24h, 7d or infinite.")
        }
    }

    private fun reservationProblems(): List<String> = buildList {
        val network = networkAddress
        val rows = reservationRows().filterNot { it.deleting }
        val seenIp = mutableMapOf<String, String>()
        val seenMac = mutableMapOf<String, String>()
        rows.forEach { row ->
            val label = row.name.ifBlank { row.mac.ifBlank { "a reservation" } }
            if (!validMac(row.mac)) {
                add("$label needs a MAC address in the form aa:bb:cc:dd:ee:ff.")
            }
            val ip = IpMath.parse(row.ip)
            if (ip == null) {
                add("$label needs an IPv4 address.")
            } else {
                if (network != null && IpMath.networkOf(ip, prefix) != network) {
                    add("$label is on ${row.ip}, outside ${IpMath.format(network)}/$prefix.")
                }
                if (row.ip == routerIp) add("$label is on the router's own address.")
                seenIp.put(row.ip, label)?.let { add("$it and $label both reserve ${row.ip}.") }
            }
            if (validMac(row.mac)) {
                seenMac.put(row.mac, label)?.let { add("$it and $label are both ${row.mac}.") }
            }
        }
    }

    /**
     * What a swconfig port map has to satisfy before it can be applied.
     *
     * These are the mistakes that take a board off the network with nothing left to fix it
     * from, which is why the matrix was read-only until now.
     */
    private fun swVlanProblems(): List<String> = buildList {
        val rows = swVlanRows().filterNot { it.deleting }
        if (rows.isEmpty()) return@buildList
        val cpu = switchDev?.cpuPort
        rows.forEach { row ->
            if (row.vlan !in 1..4094) add("VLAN ${row.vlan} is outside the 1-4094 the standard allows.")
            if (row.ports.isEmpty()) add("VLAN ${row.vlan} has no ports, so it does nothing.")
            // The CPU port is the wire to the router itself. A VLAN without it exists only
            // between the sockets; the router cannot see it, so nothing can route or serve
            // DHCP on it.
            if (cpu != null && rows.size > 1 && row.ports.none { it.port == cpu }) {
                add(
                    "VLAN ${row.vlan} does not include the CPU port ($cpu) — the router itself " +
                        "cannot see that VLAN, so nothing on it reaches the internet."
                )
            }
            if (cpu != null && rows.size > 1 && row.ports.any { it.port == cpu && !it.tagged }) {
                add(
                    "With more than one VLAN the CPU port has to be tagged (${cpu}t), or the " +
                        "router cannot tell the VLANs apart."
                )
            }
        }
        // A socket can only be untagged in one VLAN: that is what its ingress VLAN is.
        rows.flatMap { row -> row.ports.filterNot { it.tagged }.map { it.port to row.vlan } }
            .groupBy({ it.first }, { it.second })
            .filter { it.value.size > 1 }
            .forEach { (port, vlans) ->
                add("Port $port is untagged in VLAN ${vlans.joinToString(" and ")} — it can only be untagged in one.")
            }
        lanSwVlan?.let { lanVlan ->
            val lanRow = rows.firstOrNull { it.vlan == lanVlan }
            if (lanRow == null) {
                add(
                    "VLAN $lanVlan carries the LAN, and this change removes it. The router's " +
                        "own address goes with it."
                )
            } else if (cpu != null && lanRow.ports.none { it.port == cpu }) {
                add(
                    "VLAN $lanVlan carries the LAN. Without the CPU port the router drops off " +
                        "its own network, including this app."
                )
            }
        }
        if (!vlanModeOn && rows.size > 1) {
            add("VLAN mode is off on ${switchDev?.name ?: "the switch"}, so these VLANs would do nothing.")
        }
    }

    /**
     * True when the chip did not say which port is the CPU.
     *
     * Every guard about the CPU port depends on knowing which one it is, so when the chip
     * will not say, the screen says that instead of implying the checks ran.
     */
    val cpuPortUnknown: Boolean get() = switchDev != null && switchDev?.cpuPort == null

    private fun vlanProblems(): List<String> = buildList {
        val rows = vlanRows().filterNot { it.deleting }
        rows.groupBy { it.device to it.vlan }
            .filter { it.value.size > 1 }
            .forEach { (key, _) -> add("${key.first} already has a VLAN ${key.second}.") }
        rows.forEach { row ->
            if (row.vlan !in 1..4094) add("VLAN ${row.vlan} is outside the 1-4094 the standard allows.")
            if (row.vlan == lanVlan && row.device == lanBridge && row.ports.isEmpty()) {
                add(
                    "VLAN ${row.vlan} carries ${net?.device}. With no ports it stops existing, " +
                        "and the router's LAN address goes with it."
                )
            }
        }
        deletions.filter { it.startsWith("network.") }.forEach { path ->
            val vlan = vlans.firstOrNull { "network.${it.section}" == path } ?: return@forEach
            if (vlan.vlan == lanVlan && vlan.device == lanBridge) {
                add("Deleting VLAN ${vlan.vlan} removes ${net?.device}, the LAN's own netdev.")
            }
        }
    }

    /**
     * What the user has to read before applying. None of these stop an apply — they are the
     * consequences the router will not warn about, said before the fact rather than after.
     */
    fun notes(): List<String> = buildList {
        if (movesAddress) {
            val from = net?.ipaddr.orEmpty()
            add(
                "The router moves from $from to $routerIp. This connection ends the moment " +
                    "netifd reloads — the app cannot follow it, because this phone still holds " +
                    "an address on the old subnet until its lease renews."
            )
            add(
                "Reconnect at $routerIp once the phone has a new lease — turning Wi-Fi off and " +
                    "on is usually enough. The saved router is updated to the new address."
            )
            add(
                // Learned the hard way on a router moved to an address a DIFFERENT router had
                // once answered on: the pin for that address was still there, so the app
                // showed the red interception warning rather than a first-contact prompt.
                "Host keys are pinned per address, so $routerIp is a first contact and its " +
                    "fingerprint is shown for you to accept. If a different router ever " +
                    "answered on $routerIp, you get the changed-key warning instead — that is " +
                    "the stale pin, not an interception."
            )
        }
        if (!dhcpOn && pool?.ignore == false) {
            add(
                "With the DHCP server off, clients keep the addresses they hold until the lease " +
                    "expires and then have none. Anything with a static address is unaffected."
            )
        }
        poolRange?.let { range ->
            val dropped = leases.mapNotNull { IpMath.parse(it.ip) }.count { it !in range }
            val poolChanged = staged.keys.any { it.endsWith(".start") || it.endsWith(".limit") }
            if (poolChanged && dropped > 0) {
                add(
                    "$dropped device${if (dropped == 1) "" else "s"} currently hold an address " +
                        "outside the new pool. They keep it until the lease expires, then get one " +
                        "inside it — which changes the address anything pointing at them by number uses."
                )
            }
        }
        reservationRows().filterNot { it.deleting }.forEach { row ->
            val ip = IpMath.parse(row.ip) ?: return@forEach
            if (row.isNew && poolRange?.contains(ip) == true) {
                add(
                    "${row.name.ifBlank { row.mac }} is reserved inside the DHCP pool. dnsmasq " +
                        "honours that, but the address is also one it could offer another device " +
                        "before this one asks."
                )
            }
        }
        deletions.filter { it.startsWith("dhcp.") }.forEach { path ->
            val resv = reservations.firstOrNull { "dhcp.${it.section}" == path } ?: return@forEach
            add(
                "${resv.name.ifBlank { resv.mac }} goes back to a pool address at its next renewal, " +
                    "so ${resv.ip} stops being where it answers."
            )
        }
        val swTouched = staged.keys.any { key ->
            swVlans.any { key == "network.${it.section}.ports" }
        } || swVlanDrafts.isNotEmpty()
        if (swTouched) {
            val up = switchSockets().filter { socketUp(it) }
            add(
                "Switch port numbers are the chip's, not the case's. Right now " +
                    (if (up.isEmpty()) "no socket has a link" else "port ${up.joinToString(" and ")} " +
                        "${if (up.size == 1) "has" else "have"} a link") +
                    " — plug a cable in and re-read this screen to tell which number is which hole."
            )
            // Only ports this change strands. A board can have ports that were never in any
            // VLAN — port 6 on the Atheros AR8337 in the reference router is up and in none —
            // and warning about those every time would bury the one that matters.
            val wasMember = { port: Int ->
                swVlans.any { vlan -> Parsers.swPorts(vlan.ports).any { it.port == port } }
            }
            val orphaned = switchSockets().filter { port ->
                wasMember(port) &&
                    swVlanRows().filterNot { it.deleting }.none { row -> row.ports.any { it.port == port } }
            }
            if (orphaned.isNotEmpty()) {
                add(
                    "Port ${orphaned.joinToString(", ")} would be in no VLAN at all. A socket in " +
                        "no VLAN is dead — nothing plugged into it reaches anything."
                )
            }
        }
        if (swTouched && cpuPortUnknown) {
            add(
                "${switchDev?.name ?: "The switch"} did not report which port is the CPU, so " +
                    "the app cannot warn you about removing it. A VLAN missing the CPU port is " +
                    "invisible to the router — usually the tagged one in the existing list."
            )
        }
        if (swVlanDrafts.isNotEmpty()) {
            // The reason someone splits a swconfig VLAN in the first place.
            add(
                "A new VLAN is a socket separated from the others, and nothing more. To make it " +
                    "an internet uplink it still needs an interface on " +
                    "${lanMember().substringBefore('.')}.${swVlanDrafts.first().vlan} and a place " +
                    "in the firewall's wan zone — neither of which this screen writes yet."
            )
        }
        if (stagedLists.keys.any { it.startsWith("network.") && it.endsWith(".ports") } ||
            vlanDrafts.isNotEmpty()
        ) {
            add(
                "Port changes take effect on the wired sockets only. Wireless clients ride the " +
                    "bridge, so they stay up — but a machine on a port this VLAN no longer covers " +
                    "loses the LAN until it is moved back."
            )
        }
        if (stagedLists.containsKey(dnsPath)) {
            add(
                "These resolvers are the ones the router itself uses. Clients still ask the " +
                    "router, which is what dnsmasq advertises over DHCP."
            )
        }
    }

    // -----------------------------------------------------------------------
    // Applying
    // -----------------------------------------------------------------------

    suspend fun apply(): Boolean {
        if (pendingCount == 0 || applying) return true
        problems().firstOrNull()?.let { error = it; return false }
        applying = true
        error = null
        val packages = packages()
        val moves = movesAddress
        try {
            beforeApply?.invoke()
        } catch (e: SshException) {
            error = "Snapshot before apply failed: ${e.message}"
            applying = false
            return false
        }
        val target = routerIp
        val script = Commands.uciBatch(
            ops(),
            packages,
            Commands.lanReload(
                network = "network" in packages,
                dhcp = "dhcp" in packages,
                movesAddress = moves,
            ),
        )
        return try {
            session.exec(script, timeoutMs = 60_000).requireOk("uci batch")
            if (moves) {
                // The reload is detached, so a 0 here only means the batch committed. The
                // link goes down a second later either way.
                movedTo = target
            } else {
                revert()
                load()
            }
            true
        } catch (e: SshException) {
            // Moving the router's own address takes the connection with it. The command
            // reached the router; only the reply could not come back.
            if (moves && (e is SshException.Disconnected || e is SshException.Timeout)) {
                movedTo = target
                true
            } else {
                error = e.message
                false
            }
        } finally {
            applying = false
        }
    }

    companion object {
        private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        fun validMac(mac: String): Boolean = MAC.matches(mac.trim().lowercase())

        /** Sorts addresses numerically — string order puts .100 before .2. */
        fun sortKey(ip: String): Long = IpMath.parse(ip) ?: Long.MAX_VALUE

        /** dnsmasq's own vocabulary for the lease-time chips. */
        val LEASE_CHOICES = listOf("1h", "12h", "24h", "7d", "infinite")

        /** "11 h left", or "expired" — what a lease row shows next to the name. */
        fun leaseLeft(expiry: Long, nowS: Long): String {
            if (expiry == 0L) return "no lease"
            val left = expiry - nowS
            return when {
                left <= 0 -> "expired"
                left < 3600 -> "${left / 60} m left"
                left < 86400 -> "${left / 3600} h left"
                else -> "${left / 86400} d left"
            }
        }
    }
}

/** One device on the LAN as the leases tab lists it. */
data class LanClient(
    val mac: String,
    val ip: String,
    val name: String,
    /** Unix seconds the lease runs out; 0 for a device that never took one. */
    val expiry: Long,
    val reservedIp: String? = null,
) {
    val reserved: Boolean get() = reservedIp != null
}

/** One row of the reservations list: saved, edited, staged for deletion, or brand new. */
data class ResvRow(
    val key: String,
    val section: String?,
    val draftId: Int?,
    val name: String,
    val mac: String,
    val ip: String,
    val isNew: Boolean,
    val changed: Boolean,
    val deleting: Boolean,
)

/** A swconfig VLAN staged onto the chip. */
data class SwVlanDraft(
    val id: Int,
    val device: String,
    val vlan: Int,
    val ports: List<SwPort>,
)

/** One row of the swconfig VLAN matrix. */
data class SwVlanRow(
    val key: String,
    val section: String?,
    val draftId: Int?,
    val device: String,
    val vlan: Int,
    val ports: List<SwPort>,
    val isNew: Boolean,
    val changed: Boolean,
    val deleting: Boolean,
)

/** One row of the VLAN matrix. */
data class VlanRow(
    val key: String,
    val section: String?,
    val draftId: Int?,
    val device: String,
    val vlan: Int,
    val ports: List<VlanPort>,
    val isNew: Boolean,
    val changed: Boolean,
    val deleting: Boolean,
) {
    val netdev: String get() = "$device.$vlan"
}
