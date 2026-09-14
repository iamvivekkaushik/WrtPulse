package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.DhcpPool
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.LanNet
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.MeshExtraNet
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.MeshPeer
import com.vivekkaushik.wrtpulse.ops.MeshProfile
import com.vivekkaushik.wrtpulse.ops.MeshRadioPlan
import com.vivekkaushik.wrtpulse.ops.MeshSsid
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.SwPort
import com.vivekkaushik.wrtpulse.ops.SwitchDev
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import com.vivekkaushik.wrtpulse.ops.WifiRadio
import com.vivekkaushik.wrtpulse.ops.WpadSwap
import kotlinx.coroutines.delay

/** Whether a node's Wi-Fi still matches the primary's, as of the last check. */
sealed interface NodeSync {
    data object Unknown : NodeSync
    data object InSync : NodeSync
    data class OutOfDate(val summary: String) : NodeSync
    data class Unreachable(val why: String) : NodeSync
}

/** One saved node as the primary sees it right now. */
data class MeshNode(
    val entity: RouterEntity,
    val online: Boolean,
    val rttMs: Double?,
    /** The mesh link's signal, for a wireless node whose peer the primary can name. */
    val signalDbm: Int?,
)

/**
 * The mesh, from the primary's side: whether its SSIDs hand clients off (802.11r/k/v),
 * whether it has an 802.11s point for wireless nodes, which nodes answer, and the profile
 * a node is built from.
 *
 * Everything a node needs to know about the primary is captured here on every read and
 * handed to [profileSink], which the app seals into the primary's saved row. The join wizard
 * on the node then works from that copy, because the app is connected to the node at that
 * point, not to the primary.
 */
class MeshStore(private val session: RouterSession) : Refreshable {

    val radios = mutableStateListOf<WifiRadio>()
    val networks = mutableStateListOf<WifiNetwork>()
    var lan by mutableStateOf<LanNet?>(null); private set
    var pool by mutableStateOf<DhcpPool?>(null); private set
    var board by mutableStateOf<BoardInfo?>(null); private set
    var meshCapable by mutableStateOf(false); private set
    var wpad by mutableStateOf(""); private set
    var manager by mutableStateOf("opkg"); private set
    var overlayFreeKb by mutableStateOf<Long?>(null); private set
    val peers = mutableStateListOf<MeshPeer>()
    val leases = mutableStateListOf<Lease>()
    private val neighbourIps = mutableStateListOf<String>()

    /** radio section → the MAC of its first netdev. */
    val radioMacs = mutableStateMapOf<String, String>()

    /** radio section → the channel it is actually on, from iwinfo — what `auto` resolved to. */
    val operatingChannels = mutableStateMapOf<String, Int>()

    /** The mesh-point netdevs `iw dev` lists: the config has one, and the driver brought it up. */
    private val meshIfnames = mutableStateListOf<String>()

    /** A pre-mesh snapshot on this router's own flash: present, and the LAN address and hostname it holds. */
    var onRouterSnapshot by mutableStateOf(false); private set
    var onRouterSnapshotLan by mutableStateOf<String?>(null); private set
    var onRouterSnapshotHostname by mutableStateOf<String?>(null); private set

    /**
     * Restores the snapshot the node keeps and reboots it. "restored" when the reboot is
     * away; the link drops right after, which is the expected end of this.
     */
    suspend fun restoreOnRouterSnapshot(): String {
        val out = try {
            session.exec(Commands.NODE_RESTORE_SNAPSHOT, timeoutMs = 120_000).stdout.trim()
        } catch (e: SshException) {
            return "Failed: ${e.message}"
        }
        return when {
            out.contains("restored") -> "restored"
            out.contains("absent") -> "Failed: the router holds no pre-mesh snapshot."
            else -> "Failed: sysupgrade -r refused the archive. Nothing was rebooted."
        }
    }

    /** This router's own mesh point MAC, which is how a primary's peer list names it. */
    var ownMeshMac by mutableStateOf<String?>(null); private set

    /** address → round trip, from the last read's pings; absent means it did not answer. */
    private val pings = mutableStateMapOf<String, Double?>()

    /** The saved rows that are this primary's nodes. Set by the app; read for the pings. */
    var nodeEntities by mutableStateOf<List<RouterEntity>>(emptyList())

    override var loaded by mutableStateOf(false); private set
    override var applying by mutableStateOf(false); private set
    override val refreshPaused: Boolean get() = swapping
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set

    /** The wpad swap is running detached and the radios are restarting. */
    var swapping by mutableStateOf(false); private set
    var swapLog by mutableStateOf<String?>(null); private set

    /** The `network` config as read, for the trunk and extra-network lookups. */
    private val networkUci = mutableStateMapOf<String, String>()
    private val swDevs = mutableStateListOf<SwitchDev>()

    /** A LAN socket this router is holding apart for a node setup — left behind if a setup was abandoned. */
    var heldSetupPort by mutableStateOf<String?>(null); private set

    var setupNotice by mutableStateOf<String?>(null); private set

    /** Puts a held socket back into the LAN — unless a cable is still in it, which it says. */
    suspend fun releaseSetupPort() {
        val out = runCatching { session.exec(Commands.SETUP_RELEASE, timeoutMs = 40_000).stdout }.getOrNull().orEmpty()
        setupNotice = if (out.contains("still-cabled")) "Something is still plugged into that socket. Unplug it, then release." else null
        load()
    }

    /** identity → whether the node's copied SSIDs match this router's, from [checkNodes]. */
    val nodeSync = mutableStateMapOf<String, NodeSync>()

    /** identity → the mesh point MAC the node reported when last read; truer than the saved row. */
    val liveMeshMacs = mutableStateMapOf<String, String>()
    var syncing by mutableStateOf(false); private set
    var syncNotice by mutableStateOf<String?>(null); private set

    /** Where the profile goes after every read. */
    var profileSink: (suspend (MeshProfile) -> Unit)? = null
    var profile by mutableStateOf<MeshProfile?>(null); private set

    val identity: String get() = session.target.identity ?: "${session.target.host}:${session.target.port}"

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    override suspend fun load() {
        try {
            val ips = nodeEntities.map { it.host }.distinct()
            val out = session.exec(Commands.meshState(ips), timeoutMs = 25_000).requireOk("read mesh").stdout
            ingest(Parsers.sections(out))
            loaded = true
            error = null
            // A node has no profile to offer: its SSIDs are copies, its LAN is someone else's.
            profile = profileFrom().also { p -> if (!configuredAsNode) profileSink?.let { sink -> runCatching { sink(p) } } }
        } catch (e: SshException) {
            error = e.message
        }
    }

    /** [load] without the round trip — where the parsing lives, and what the tests drive. */
    fun ingest(parts: Map<String, String>) {
        val (r, n) = Parsers.wireless(Parsers.uciShow(parts["uci"].orEmpty()))
        radios.clear(); radios.addAll(r)
        networks.clear(); networks.addAll(n)
        val network = Parsers.uciShow(parts["net"].orEmpty())
        networkUci.clear(); networkUci.putAll(network)
        swDevs.clear(); swDevs.addAll(Parsers.switchDevs(parts["swconfig"].orEmpty()))
        heldSetupPort = MeshOps.heldSetupPort(network)
        lan = Parsers.lanNet(network)
        pool = Parsers.dhcpPools(Parsers.uciShow(parts["dhcp"].orEmpty())).firstOrNull { it.interfaceName == "lan" }
        board = parts["board"]?.takeIf { it.isNotBlank() }?.let { runCatching { Parsers.board(it) }.getOrNull() }
        meshCapable = parts["capable"].orEmpty().trim() == "yes"
        wpad = parts["wpad"].orEmpty().trim().lines().firstOrNull().orEmpty()
        manager = parts["pm"].orEmpty().trim().ifEmpty { "opkg" }
        overlayFreeKb = parts["df"].orEmpty().trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull()
        peers.clear(); peers.addAll(Parsers.meshPeers(parts["peers"].orEmpty()))
        leases.clear(); leases.addAll(Parsers.leases(parts["leases"].orEmpty()))
        neighbourIps.clear()
        neighbourIps.addAll(Parsers.neighEntries(parts["neigh"].orEmpty()).filter { !it.ip.contains(':') }.map { it.ip })
        val status = Parsers.wirelessStatus(parts["status"].orEmpty())
        val ifnameRadio = status.associate { it.ifname to it.radio }
        radioMacs.clear()
        meshIfnames.clear()
        ownMeshMac = null
        Parsers.iwDevs(parts["macs"].orEmpty()).forEach { dev ->
            if (dev.type.contains("mesh", ignoreCase = true)) {
                meshIfnames += dev.ifname
                if (ownMeshMac == null && dev.mac.isNotEmpty()) ownMeshMac = dev.mac
            }
            val radio = ifnameRadio[dev.ifname] ?: radioOfIfname(dev.ifname) ?: return@forEach
            if (dev.mac.isNotEmpty()) radioMacs.putIfAbsent(radio, dev.mac)
        }
        operatingChannels.clear()
        Parsers.iwinfo(parts["iwinfo"].orEmpty()).forEach { iface ->
            val radio = ifnameRadio[iface.ifname] ?: radioOfIfname(iface.ifname) ?: return@forEach
            iface.channel?.let { operatingChannels.putIfAbsent(radio, it) }
        }
        pings.clear(); pings.putAll(Parsers.pingResults(parts["ping"].orEmpty()))
        val snap = parts["presnap"].orEmpty().trim().lines().map { it.trim() }
        onRouterSnapshot = snap.firstOrNull() == "present"
        onRouterSnapshotLan = snap.getOrNull(1)?.substringBefore('/')?.takeIf { onRouterSnapshot && it.isNotEmpty() }
        onRouterSnapshotHostname = snap.getOrNull(2)?.takeIf { onRouterSnapshot && it.isNotEmpty() }
    }

    /** `phy0-ap0` → `radio0`, the naming every release since 21.02 uses. */
    private fun radioOfIfname(ifname: String): String? =
        Regex("^phy(\\d+)-").find(ifname)?.groupValues?.get(1)?.let { "radio$it" }

    // -----------------------------------------------------------------------
    // What the screen reads
    // -----------------------------------------------------------------------

    /**
     * True when this router's own config is a node's, whoever wrote it: its LAN points at a
     * gateway, its DHCP server is told to serve nobody, and it carries the sections the join
     * writes. What the saved row says is separate — a phone that lost its record, or never
     * had one, can tell from this alone.
     */
    val configuredAsNode: Boolean
        get() = lan?.gateway.orEmpty().isNotEmpty() && pool?.ignore == true &&
            networks.any { it.section.startsWith(MeshOps.AP_PREFIX) || it.section == MeshOps.MESH_SECTION }

    /** The gateway a node's LAN points at — its primary's address. */
    val nodeGateway: String? get() = lan?.gateway?.takeIf { it.isNotEmpty() }

    /** How a configured node reaches its primary, judged from whether it has a mesh point. */
    val nodeBackhaul: com.vivekkaushik.wrtpulse.ops.Backhaul
        get() = if (meshIface != null) com.vivekkaushik.wrtpulse.ops.Backhaul.Wireless else com.vivekkaushik.wrtpulse.ops.Backhaul.Wired

    /** True when this router is acting as a primary: hand-off or a mesh link is on, or it has nodes. */
    val actsAsPrimary: Boolean get() = roamingOn || meshIface != null || nodeEntities.isNotEmpty() || peers.isNotEmpty()

    /** The primary's own SSIDs on the LAN — what every node copies. */
    val lanAps: List<WifiNetwork>
        get() = networks.filter { it.mode == "ap" && it.section != MeshOps.MESH_SECTION && (it.network == "lan" || it.network.isEmpty()) }

    /** Roaming is on when every SSID that can hand off does, and there is at least one. */
    val roamingOn: Boolean
        get() {
            val capable = lanAps.filter { MeshOps.roamingCapable(it.encryption) }
            return lanAps.isNotEmpty() && capable.all { it.ieee80211r } && lanAps.any { it.ieee80211r }
        }

    /** True when no SSID on the LAN can carry 802.11r — every one is open or WPA1-mixed. */
    val roamingImpossible: Boolean get() = lanAps.isNotEmpty() && lanAps.none { MeshOps.roamingCapable(it.encryption) }

    val meshIface: WifiNetwork? get() = networks.firstOrNull { it.section == MeshOps.MESH_SECTION && it.isMesh }
    val meshUp: Boolean get() = meshIface != null && meshIfnames.isNotEmpty()

    /** The radio the mesh link goes on: 5 GHz when the router has one, else the first. */
    val meshRadio: WifiRadio?
        get() = meshIface?.let { m -> radios.firstOrNull { it.section == m.device } }
            ?: radios.firstOrNull { it.band == "5G" && !it.disabled }
            ?: radios.firstOrNull { !it.disabled }
            ?: radios.firstOrNull()

    /** What has to be installed for 802.11s, or null when the build already has it. */
    val wpadSwap: WpadSwap? get() = MeshOps.wpadSwap(wpad, meshCapable)

    /** Why the mesh link cannot be turned on yet. */
    fun meshProblems(): List<String> = buildList {
        addAll(MeshOps.meshRadioProblems(meshRadio))
        val swap = wpadSwap
        if (!meshCapable && swap == null) add("This wpad build has no 802.11s and the app does not know which package would add it.")
        if (swap != null) overlayFreeKb?.let { if (it < MeshOps.MIN_SWAP_KB) add("Only $it kB free on the overlay; ${swap.install} needs about ${MeshOps.MIN_SWAP_KB} kB.") }
    }

    fun roamingNotes(): List<String> = MeshOps.roamingNotes(lanAps)

    /**
     * APs with hand-off on whose mobility domain no longer matches their SSID — an SSID renamed
     * after 802.11r was switched on. Every AP and node with that name derives the domain from
     * it, so a stale one is an AP the others cannot hand off to, though it looks configured.
     */
    val staleDomains: List<WifiNetwork>
        get() = lanAps.filter { it.ieee80211r && it.mobilityDomain != MeshOps.mobilityDomain(it.ssid) }

    /** Rewrites hand-off on every LAN SSID from its current name — the fix for [staleDomains]. */
    suspend fun repairRoaming(): Boolean =
        run(MeshOps.roamingOps(lanAps), "Hand-off domains now follow the SSIDs. Push the nodes if they are out of date.")

    /** The nodes, with whether each answered the last ping and how the mesh sees it. */
    private fun macOf(e: RouterEntity): String? = liveMeshMacs[e.identity] ?: e.meshMac

    fun nodes(): List<MeshNode> = nodeEntities.map { e ->
        val rtt = pings[e.host]
        val peer = macOf(e)?.let { mac -> peers.firstOrNull { it.mac == mac } }
        MeshNode(e, online = pings.containsKey(e.host) && rtt != null, rttMs = rtt, signalDbm = peer?.signalDbm)
    }

    /** Peers the primary's mesh point holds that no saved node claims — a node added by hand, or one the app forgot. */
    fun strayPeers(): List<MeshPeer> = peers.filter { p -> nodeEntities.none { macOf(it) == p.mac } }

    // -----------------------------------------------------------------------
    // The profile
    // -----------------------------------------------------------------------

    fun profileFrom(): MeshProfile = profileWith(radios.toList(), networks.toList())

    /**
     * The profile this router WOULD offer with the given radios and sections in place of what
     * it has — the shape it is about to take, so nodes can be told before it changes.
     */
    fun profileWith(radiosIn: List<WifiRadio>, networksIn: List<WifiNetwork>): MeshProfile {
        val bandOf = radiosIn.associate { it.section to it.band }
        val lanApsIn = networksIn.filter { it.mode == "ap" && it.section != MeshOps.MESH_SECTION && (it.network == "lan" || it.network.isEmpty()) }
        // Every LAN SSID, visible ones first within a band; a node carries them all.
        val ssids = lanApsIn
            .filter { !it.disabled && it.ssid.isNotEmpty() }
            .sortedBy { it.hidden }
            .mapNotNull { ap ->
                val band = bandOf[ap.device].orEmpty()
                if (band.isEmpty()) null else MeshSsid(band, ap.ssid, ap.encryption, ap.key, ap.hidden)
            }
        val plans = radiosIn.map { r ->
            val channel = r.channel.toIntOrNull()?.toString() ?: operatingChannels[r.section]?.toString() ?: "auto"
            MeshRadioPlan(r.band, channel, r.htmode, r.country)
        }
        val mesh = networksIn.firstOrNull { it.section == MeshOps.MESH_SECTION }
        val extras = extraNets(networksIn, bandOf)
        val lanNet = lan
        val prefix = lanNet?.cidrPrefix ?: IpMath.prefixOf(lanNet?.netmask.orEmpty()) ?: 24
        return MeshProfile(
            primaryIdentity = identity,
            primaryName = board?.hostname?.ifBlank { null } ?: session.target.host,
            primaryIp = lanNet?.ipaddr ?: session.target.host,
            prefix = prefix,
            poolStart = pool?.start ?: 100,
            poolLimit = pool?.limit ?: 150,
            ssids = ssids,
            radios = plans,
            meshId = mesh?.meshId,
            meshKey = mesh?.key,
            meshBand = mesh?.let { bandOf[it.device] },
            taken = (leases.map { it.ip } + neighbourIps + nodeEntities.map { it.host }).distinct(),
            primaryMacs = radioMacs.values.toList(),
            capturedEpoch = System.currentTimeMillis() / 1000,
            extras = extras,
        )
    }

    /**
     * The bridged Wi-Fi networks beside the LAN — guest, IoT, anything shaped like them: an
     * interface on a bridge device of its own with at least one enabled AP. Each gets a VLAN
     * id from 3 up, skipping ids the switch or bridge VLANs already use, in section order so
     * the number is stable across reads.
     */
    fun extraNets(networksIn: List<WifiNetwork>, bandOf: Map<String, String>): List<MeshExtraNet> {
        val used = (Parsers.switchVlans(networkUci).map { it.vlan } + Parsers.bridgeVlans(networkUci).map { it.vlan } + 1).toMutableSet()
        return extraIfaces().entries.sortedBy { it.value }.mapNotNull { (name, iface) ->
            val aps = networksIn.filter { it.mode == "ap" && it.network == iface && !it.disabled && it.ssid.isNotEmpty() }
            if (aps.isEmpty()) return@mapNotNull null
            val vid = (3..4000).first { it !in used }.also { used += it }
            MeshExtraNet(
                name = name,
                vid = vid,
                isolate = aps.any { it.isolate },
                ssids = aps.sortedBy { it.hidden }.mapNotNull { ap ->
                    val band = bandOf[ap.device].orEmpty()
                    if (band.isEmpty()) null else MeshSsid(band, ap.ssid, ap.encryption, ap.key, ap.hidden)
                },
            )
        }
    }

    /** short name → this primary's interface for each bridged network beside the LAN. */
    private fun extraIfaces(): Map<String, String> {
        val bridges = Parsers.netDevices(networkUci).filter { it.type == "bridge" }.map { it.name }.toSet()
        return networkUci.filter { (k, v) -> v == "interface" && k.count { it == '.' } == 1 }
            .keys.map { it.removePrefix("network.") }
            .filter { it != "lan" && it != "loopback" && it != MeshOps.SETUP_IFACE && networkUci["network.$it.device"] in bridges }
            .associateBy { it.removePrefix("wrtpulse_").filter { c -> c.isLetterOrDigit() }.lowercase().ifEmpty { it } }
    }

    /**
     * What this primary still lacks to carry its extra networks to the nodes: per network,
     * a tagged sub-interface of the mesh point and of every wired LAN socket, in the
     * network's own bridge. Idempotent; empty when everything is in place.
     */
    fun trunkOps(): List<String> {
        val p = profile ?: (if (radios.isNotEmpty()) profileFrom() else return emptyList())
        val ops = mutableListOf<String>()
        val ifaces = extraIfaces()
        val devices = Parsers.netDevices(networkUci)
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val lanPorts = devices.firstOrNull { it.name == lanDevice }?.ports.orEmpty()
            .filter { !it.contains('.') && !it.startsWith("phy") && !it.startsWith("wlan") }
        val vlans = Parsers.switchVlans(networkUci)
        val lanVlan = Parsers.lanSwitchVlan(networkUci)?.let { id -> vlans.firstOrNull { it.vlan == id } }
        val base = Parsers.lanSwitchMember(networkUci)?.substringBefore('.') ?: "eth0"
        val mesh = meshIfnames.firstOrNull()
        p.extras.forEach { x ->
            val iface = ifaces[x.name] ?: return@forEach
            val bridge = devices.firstOrNull { it.name == networkUci["network.$iface.device"].orEmpty() } ?: return@forEach
            val wanted = mutableListOf<String>()
            fun tagged(ifname: String, suffix: String) {
                val section = "wrtpulse_trunk_${x.name}_$suffix"
                if (networkUci["network.$section"] != "device") {
                    ops += "set network.$section=device"
                    ops += "set network.$section.type='8021q'"
                    ops += "set network.$section.ifname='$ifname'"
                    ops += "set network.$section.vid='${x.vid}'"
                    ops += "set network.$section.name='$ifname.${x.vid}'"
                }
                wanted += "$ifname.${x.vid}"
            }
            if (mesh != null) tagged(mesh, "mesh")
            if (swDevs.isNotEmpty() && lanVlan != null) {
                val section = "wrtpulse_trunk_${x.name}_sw"
                if (networkUci["network.$section"] != "switch_vlan") {
                    val members = Parsers.swPorts(lanVlan.ports).map { SwPort(it.port, true) }
                    ops += "set network.$section=switch_vlan"
                    ops += "set network.$section.device='${lanVlan.device}'"
                    ops += "set network.$section.vlan='${x.vid}'"
                    ops += "set network.$section.ports='${Parsers.swPortsValue(members)}'"
                }
                wanted += "$base.${x.vid}"
            } else {
                lanPorts.forEach { port -> tagged(port, port.filter { it.isLetterOrDigit() }) }
            }
            val missing = wanted.filter { it !in bridge.ports }
            if (missing.isNotEmpty()) ops += Commands.listOps("network.${bridge.section}.ports", bridge.ports + missing)
        }
        return ops
    }

    /** True when every extra network already rides the backhaul. */
    val trunksReady: Boolean get() = loaded && trunkOps().isEmpty()

    /** Writes the trunks this primary lacks, if any; a no-op otherwise. */
    suspend fun ensureTrunks(): Boolean {
        val ops = trunkOps()
        if (ops.isEmpty()) return true
        return run(ops, "Guest and IoT now ride the backhaul to the nodes.", packages = listOf("network"), reload = Commands.NETWORK_RELOAD)
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /** The lines the roaming review shows. */
    fun roamingOps(): List<String> = MeshOps.roamingOps(lanAps)

    suspend fun enableRoaming(): Boolean =
        run(MeshOps.roamingOps(lanAps), "Hand-off is on across ${lanAps.size} SSID${if (lanAps.size == 1) "" else "s"}.")

    suspend fun disableRoaming(): Boolean =
        run(MeshOps.roamingOffOps(lanAps), "Hand-off is off. The SSIDs and passwords are as they were.")

    /** The lines the mesh-link review shows: channel pin plus the mesh point. */
    fun meshOps(): List<String> {
        val radio = meshRadio ?: return emptyList()
        val existing = meshIface
        val meshId = existing?.meshId?.ifEmpty { null } ?: "${MeshOps.hostnameOf(board?.hostname.orEmpty().ifEmpty { "wrtpulse" })}-mesh"
        val key = existing?.key?.ifEmpty { null } ?: MeshOps.randomKey()
        return MeshOps.pinChannelOps(radio, operatingChannels[radio.section]) +
            MeshOps.meshIfaceOps(MeshOps.MESH_SECTION, radio.section, meshId, key)
    }

    suspend fun enableMesh(): Boolean {
        if (meshProblems().isNotEmpty() || !meshCapable) return false
        return run(meshOps(), "The mesh link is up on ${meshRadio?.section}. Wireless nodes can join now.")
    }

    suspend fun disableMesh(): Boolean {
        if (meshIface == null) return true
        return run(listOf("delete wireless.${MeshOps.MESH_SECTION}"), "The mesh link is gone. Wireless nodes lose their uplink.")
    }

    /**
     * Swaps the wpad build for the 802.11s one, detached, then waits for the radios to come
     * back and the marker to say how it went. The link drops in the middle when the phone is
     * on this router's Wi-Fi; the session reconnects on its own.
     */
    suspend fun swapWpad(): Boolean {
        val swap = wpadSwap ?: return meshCapable
        if (swapping) return false
        swapping = true
        error = null
        notice = null
        swapLog = null
        try {
            session.exec(Commands.wpadSwap(swap.remove, swap.install, manager), timeoutMs = 20_000)
                .requireOk("swap wpad")
        } catch (e: SshException) {
            if (e !is SshException.Disconnected && e !is SshException.Timeout) {
                error = e.message
                swapping = false
                return false
            }
        }
        val deadline = System.nanoTime() + SWAP_WAIT_NANOS
        var verdict: String? = null
        while (System.nanoTime() < deadline) {
            delay(5_000)
            val text = try {
                session.exec(Commands.SWAP_STATE, timeoutMs = 8_000).stdout
            } catch (e: SshException) {
                continue
            }
            val first = text.trim().lines().firstOrNull()?.trim().orEmpty()
            swapLog = text.trim().lines().drop(1).joinToString("\n").ifBlank { null }
            if (first == "ok" || first == "failed") { verdict = first; break }
        }
        swapping = false
        return when (verdict) {
            "ok" -> { notice = "${swap.install} is in. The radios are back."; load(); true }
            "failed" -> { error = "${swap.install} did not go in; the router kept ${swap.remove}. See the log below."; load(); false }
            else -> { error = "The router has not answered since the swap. Give it a minute, then pull to refresh."; false }
        }
    }

    // -----------------------------------------------------------------------
    // Keeping the nodes' Wi-Fi in step
    // -----------------------------------------------------------------------

    /**
     * Reads each node's wireless config through a short session the app opens with the node's
     * own saved key, and compares its copied APs with what this router would write today.
     * Nodes that do not answer are said so; nothing is written.
     */
    suspend fun checkNodes(open: suspend (RouterEntity) -> RouterSession?) {
        val p = profile ?: return
        if (syncing) return
        syncing = true
        try {
            nodeEntities.forEach { node ->
                nodeSync[node.identity] = readNode(node, p, open)?.first ?: return@forEach
            }
        } finally {
            syncing = false
        }
    }

    /** One node's verdict, plus what it carries, so a push can build its ops without a second read. */
    private suspend fun readNode(
        node: RouterEntity,
        p: MeshProfile,
        open: suspend (RouterEntity) -> RouterSession?,
    ): Pair<NodeSync, Pair<List<WifiRadio>, List<WifiNetwork>>?>? {
        val session = open(node)
        if (session == null) {
            nodeSync[node.identity] = NodeSync.Unreachable("no saved key or password")
            return null
        }
        return try {
            val out = session.exec(Commands.WIRELESS_CONFIG + "; echo ${Commands.SECTION} macs; " + Commands.WIFI_MACS, timeoutMs = 12_000)
                .requireOk("read node wireless").stdout
            val parts = Parsers.sections("${Commands.SECTION} uci\n" + out)
            val (radios, networks) = Parsers.wireless(Parsers.uciShow(parts["uci"].orEmpty()))
            Parsers.iwDevs(parts["macs"].orEmpty()).firstOrNull { it.type.contains("mesh", ignoreCase = true) }
                ?.mac?.takeIf { it.isNotEmpty() }?.let { liveMeshMacs[node.identity] = it }
            val meshRadio = networks.firstOrNull { it.section == MeshOps.MESH_SECTION }?.device
            val radioDrift = MeshOps.radioDrift(p, radios, meshRadio)
            val verdict = when {
                radioDrift != null -> NodeSync.OutOfDate(radioDrift)
                MeshOps.apsInSync(p, radios, networks) -> NodeSync.InSync
                else -> NodeSync.OutOfDate(MeshOps.driftSummary(p, radios, networks))
            }
            verdict to (radios to networks)
        } catch (e: SshException) {
            NodeSync.Unreachable(e.message ?: "did not answer") to null
        } finally {
            runCatching { session.disconnect() }
        }
    }

    /** True when at least one node was last seen out of date. */
    val anyOutOfDate: Boolean get() = nodeSync.values.any { it is NodeSync.OutOfDate }

    /**
     * Rewrites the copied APs on every out-of-date node from this router's current SSIDs, one
     * `wifi reload` each. Only the wireless file changes and no address moves, so no rollback
     * is armed; a wireless node stays reachable over its mesh link whatever its APs do.
     */
    suspend fun pushNodes(
        open: suspend (RouterEntity) -> RouterSession?,
        /** What to write; the current profile unless the caller knows what this router is about to become. */
        profileToPush: MeshProfile? = profile,
        /** Every node, not only those last seen out of date — before a change that will cut the link. */
        all: Boolean = false,
    ) {
        val p = profileToPush ?: return
        if (syncing) return
        // The primary's side first: the trunks the nodes' guest and IoT traffic will ride.
        if (p.extras.isNotEmpty()) ensureTrunks()
        syncing = true
        syncNotice = null
        var pushed = 0
        var failed = 0
        try {
            nodeEntities.filter { all || nodeSync[it.identity] is NodeSync.OutOfDate }.forEach { node ->
                val session = open(node)
                if (session == null) { failed++; return@forEach }
                try {
                    val out = session.exec(
                        Commands.WIRELESS_CONFIG + "; echo ${Commands.SECTION} net; " + Commands.NETWORK_CONFIG +
                            "; echo ${Commands.SECTION} swconfig; " + Commands.SWCONFIG + "; echo ${Commands.SECTION} macs; " + Commands.WIFI_MACS,
                        timeoutMs = 20_000,
                    ).requireOk("read node").stdout
                    val parts = Parsers.sections("${Commands.SECTION} uci\n" + out)
                    val (radios, networks) = Parsers.wireless(Parsers.uciShow(parts["uci"].orEmpty()))
                    val nodeNet = Parsers.uciShow(parts["net"].orEmpty())
                    val nodeSw = Parsers.switchDevs(parts["swconfig"].orEmpty())
                    val meshDev = Parsers.iwDevs(parts["macs"].orEmpty()).firstOrNull { it.type.contains("mesh", ignoreCase = true) }
                    val meshRadio = networks.firstOrNull { it.section == MeshOps.MESH_SECTION }?.device
                    val ops = MeshOps.nodeRadioOps(p, radios, meshRadio) + MeshOps.nodeApOps(p, radios, networks) +
                        MeshOps.nodeExtraOps(p, radios, meshRadio, meshDev?.ifname, nodeNet, nodeSw, networks)
                    // The watchdog first, so a node whose reload moves it off this channel can
                    // still find its way back; then the batch. A reload that takes the link is
                    // expected and treated below as delivered.
                    if (meshRadio != null) runCatching { session.exec(Commands.MESH_WATCH_INSTALL, timeoutMs = 20_000) }
                    session.exec(Commands.uciBatch(ops, listOf("network", "wireless"), "(sleep 1; /etc/init.d/network reload; wifi reload) >/dev/null 2>&1 & echo scheduled"), timeoutMs = 30_000)
                        .requireOk("uci batch")
                    nodeSync[node.identity] = NodeSync.InSync
                    pushed++
                } catch (e: SshException) {
                    // The reload can take the phone's own Wi-Fi with it when it sits on that node.
                    if (e is SshException.Disconnected || e is SshException.Timeout) {
                        nodeSync[node.identity] = NodeSync.Unknown
                        pushed++
                    } else {
                        nodeSync[node.identity] = NodeSync.Unreachable(e.message ?: "failed")
                        failed++
                    }
                } finally {
                    runCatching { session.disconnect() }
                }
            }
        } finally {
            syncing = false
        }
        syncNotice = when {
            failed == 0 -> "Wi-Fi pushed to $pushed node${if (pushed == 1) "" else "s"}."
            else -> "Pushed to $pushed, could not reach $failed."
        }
    }

    private suspend fun run(
        ops: List<String>,
        done: String,
        packages: List<String> = listOf("wireless"),
        reload: String = "wifi reload",
    ): Boolean {
        if (ops.isEmpty() || applying) return true
        applying = true
        error = null
        notice = null
        return try {
            session.exec(Commands.uciBatch(ops, packages, reload), timeoutMs = 60_000)
                .requireOk("uci batch")
            load()
            notice = done
            true
        } catch (e: SshException) {
            // A wifi reload drops the app's own Wi-Fi for a moment; a lost link right after
            // a committed batch is the reload biting, not a failure to apply. The re-read
            // still matters — it is what refreshes the profile nodes are built from — so it
            // is retried once the link is back rather than left to the next timer tick.
            if (e is SshException.Disconnected || e is SshException.Timeout) {
                notice = "$done The link dropped during the reload, which is expected on Wi-Fi."
                repeat(4) {
                    delay(4_000)
                    load()
                    if (error == null) return@repeat
                }
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
        private const val SWAP_WAIT_NANOS = 240_000_000_000L
    }
}
