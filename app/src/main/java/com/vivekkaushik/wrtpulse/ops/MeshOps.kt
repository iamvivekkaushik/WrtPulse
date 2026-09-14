package com.vivekkaushik.wrtpulse.ops

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** One SSID the mesh carries on every node: the primary's, copied. */
data class MeshSsid(
    val band: String,          // "2.4G", "5G", "6G"
    val ssid: String,
    val encryption: String,    // raw uci value
    val key: String,
    val hidden: Boolean,
)

/** What a node's radio of one band should be set to, so every node sits where the primary does. */
data class MeshRadioPlan(val band: String, val channel: String, val htmode: String, val country: String)

/**
 * What a node is built from: everything about the primary's LAN and Wi-Fi that has to be the
 * same on every node. Captured on the primary, sealed into its saved row, and read back by the
 * join wizard when the app is connected to the router that is becoming a node.
 */
data class MeshProfile(
    val primaryIdentity: String,
    val primaryName: String,
    val primaryIp: String,
    val prefix: Int,
    val poolStart: Int,
    val poolLimit: Int,
    val ssids: List<MeshSsid>,
    val radios: List<MeshRadioPlan>,
    /** The 802.11s network, once the primary has one. Null means wired nodes only. */
    val meshId: String?,
    val meshKey: String?,
    val meshBand: String?,
    /** Addresses seen on the primary's LAN when the profile was taken: leases, neighbours, nodes. */
    val taken: List<String>,
    /** The primary's own radio MACs, so a node can tell which peer is the primary. */
    val primaryMacs: List<String>,
    val capturedEpoch: Long,
    /** Guest, IoT and any other bridged Wi-Fi network the primary carries beside the LAN. */
    val extras: List<MeshExtraNet> = emptyList(),
) {
    val wirelessReady: Boolean get() = meshId != null && meshKey != null && meshBand != null

    fun ssidFor(band: String): MeshSsid? = ssids.firstOrNull { it.band == band } ?: ssids.firstOrNull()

    fun radioFor(band: String): MeshRadioPlan? = radios.firstOrNull { it.band == band }

    fun toJson(): String = JSONObject().apply {
        put("primaryIdentity", primaryIdentity)
        put("primaryName", primaryName)
        put("primaryIp", primaryIp)
        put("prefix", prefix)
        put("poolStart", poolStart)
        put("poolLimit", poolLimit)
        put("ssids", JSONArray().apply {
            ssids.forEach {
                put(JSONObject().apply {
                    put("band", it.band); put("ssid", it.ssid); put("encryption", it.encryption)
                    put("key", it.key); put("hidden", it.hidden)
                })
            }
        })
        put("radios", JSONArray().apply {
            radios.forEach {
                put(JSONObject().apply {
                    put("band", it.band); put("channel", it.channel); put("htmode", it.htmode); put("country", it.country)
                })
            }
        })
        meshId?.let { put("meshId", it) }
        meshKey?.let { put("meshKey", it) }
        meshBand?.let { put("meshBand", it) }
        put("taken", JSONArray(taken))
        put("primaryMacs", JSONArray(primaryMacs))
        put("capturedEpoch", capturedEpoch)
        put("extras", JSONArray().apply {
            extras.forEach { x ->
                put(JSONObject().apply {
                    put("name", x.name); put("vid", x.vid); put("isolate", x.isolate)
                    put("ssids", JSONArray().apply {
                        x.ssids.forEach {
                            put(JSONObject().apply {
                                put("band", it.band); put("ssid", it.ssid); put("encryption", it.encryption)
                                put("key", it.key); put("hidden", it.hidden)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    companion object {
        fun fromJson(text: String): MeshProfile? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            }
            fun ssidsOf(holder: JSONObject) = holder.optJSONArray("ssids")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    MeshSsid(it.optString("band"), it.optString("ssid"), it.optString("encryption"),
                        it.optString("key"), it.optBoolean("hidden"))
                }
            }.orEmpty()
            val ssids = ssidsOf(o)
            val extras = o.optJSONArray("extras")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { x ->
                    val name = x.optString("name"); val vid = x.optInt("vid", 0)
                    if (name.isEmpty() || vid <= 0) null else MeshExtraNet(name, vid, x.optBoolean("isolate"), ssidsOf(x))
                }
            }.orEmpty()
            val radios = o.optJSONArray("radios")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    MeshRadioPlan(it.optString("band"), it.optString("channel"), it.optString("htmode"), it.optString("country"))
                }
            }.orEmpty()
            if (!o.has("primaryIdentity") || !o.has("primaryIp")) return null
            return MeshProfile(
                primaryIdentity = o.optString("primaryIdentity"),
                primaryName = o.optString("primaryName"),
                primaryIp = o.optString("primaryIp"),
                prefix = o.optInt("prefix", 24),
                poolStart = o.optInt("poolStart", 100),
                poolLimit = o.optInt("poolLimit", 150),
                ssids = ssids,
                radios = radios,
                meshId = o.optString("meshId").ifEmpty { null },
                meshKey = o.optString("meshKey").ifEmpty { null },
                meshBand = o.optString("meshBand").ifEmpty { null },
                taken = strings("taken"),
                primaryMacs = strings("primaryMacs"),
                capturedEpoch = o.optLong("capturedEpoch"),
                extras = extras,
            )
        }
    }
}

/**
 * A network beside the LAN that nodes mirror: the primary's guest or IoT Wi-Fi. Its traffic
 * has to land in the primary's own bridge for it, not the LAN, so it rides the backhaul in
 * a VLAN of its own — [vid] — tagged over the mesh point and over the LAN sockets alike.
 */
data class MeshExtraNet(
    /** Short name, from the primary's interface: `guest`, `iot`. */
    val name: String,
    val vid: Int,
    val isolate: Boolean,
    val ssids: List<MeshSsid>,
) {
    /** `wrtpulse_x_guest` — the node's interface and the stem of its sections. */
    val section: String get() = "wrtpulse_x_$name"
    /** `br-x-guest`, inside the 15-character netdev limit for any short name. */
    val bridge: String get() = "br-x-${name.take(8)}"
}

/** How a node reaches its primary. */
enum class Backhaul(val uci: String, val label: String) {
    Wired("wired", "Wired"),
    Wireless("wireless", "Wireless");

    companion object {
        fun of(value: String?): Backhaul? = entries.firstOrNull { it.uci == value }
    }
}

/** The wpad build a router runs, and what it would have to become for 802.11s. */
data class WpadSwap(val remove: String, val install: String)

/**
 * The router about to become a node, as the wizard read it. Plain data so the ops it turns
 * into can be checked without a connection.
 */
data class MeshNodeState(
    val networkUci: Map<String, String>,
    val dhcpUci: Map<String, String>,
    val radios: List<WifiRadio>,
    val networks: List<WifiNetwork>,
    val swDevs: List<SwitchDev>,
    val boardPorts: Map<String, List<BoardPort>>,
    val meshCapable: Boolean,
    val wpad: String,
    val manager: String,
    val overlayFreeKb: Long?,
    /** radio section → MAC of its first netdev, for the primary's peer list. */
    val radioMacs: Map<String, String> = emptyMap(),
    val hostname: String = "",
) {
    val lan: LanNet? get() = Parsers.lanNet(networkUci)
    val swconfig: Boolean get() = swDevs.isNotEmpty()
}

/** What the wizard will write, in one place so the review card and the batch agree. */
data class NodePlan(
    val name: String,
    val hostname: String,
    val backhaul: Backhaul,
    val address: String,
    val prefix: Int,
    val ops: List<String>,
    val packages: List<String>,
    val swap: WpadSwap?,
    val problems: List<String>,
    val notes: List<String>,
    /** The node radio carrying the mesh point, for a wireless node. */
    val meshRadio: String?,
)

/**
 * The OpenWrt recipe for a mesh, as pure functions over parsed config.
 *
 * Roaming is 802.11r/k/v on every AP that carries the same SSID and key; a node is the
 * classic "dumb AP" — static address in the primary's subnet, DHCP and firewall off, the WAN
 * socket folded into the LAN — plus, for a wireless node, an 802.11s point bridged into
 * the same LAN. Everything here is unit-tested against captured configs; nothing here
 * touches a router.
 */
object MeshOps {

    /** The section the primary's and every node's mesh point is written to. */
    const val MESH_SECTION = "wrtpulse_mesh"

    // -----------------------------------------------------------------------
    // The setup port: a LAN socket on the primary, held apart for a new router
    // -----------------------------------------------------------------------

    const val SETUP_IFACE = "wrtpulse_setup"
    const val SETUP_DEVICE = "wrtpulse_setup_dev"
    const val SETUP_VLAN = "wrtpulse_setup_vlan"
    const val SETUP_BRIDGE = "br-setup"

    /**
     * Takes one LAN socket out of the primary's LAN and gives it a bridge of its own, so the
     * router cabled into it — still 192.168.1.1 with its own DHCP server — is on a wire the
     * household never sees. The primary reaches it there over IPv6 link-local, which needs no
     * IPv4 agreement at all. On DSA the socket is a netdev in `br-lan`; on swconfig it is a
     * chip port that has to be carved into a VLAN of its own first.
     *
     * [port] is a netdev (`lan3`) or `sw:<n>`. Returns null when the port cannot be found in
     * the LAN.
     */
    fun setupPortOps(networkUci: Map<String, String>, port: String): List<String>? {
        val ops = mutableListOf<String>()
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val bridge = Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }
        val member: String
        if (port.startsWith("sw:")) {
            val n = port.removePrefix("sw:").toIntOrNull() ?: return null
            val vlans = Parsers.switchVlans(networkUci)
            val lanVid = Parsers.lanSwitchVlan(networkUci) ?: return null
            val lanVlan = vlans.firstOrNull { it.vlan == lanVid } ?: return null
            val lanPorts = Parsers.swPorts(lanVlan.ports)
            if (lanPorts.none { it.port == n && !it.tagged }) return null
            val cpu = lanPorts.firstOrNull { it.tagged } ?: return null
            val vid = (3..4000).first { id -> vlans.none { it.vlan == id } }
            val base = Parsers.lanSwitchMember(networkUci)?.substringBefore('.') ?: "eth0"
            ops += "set network.${lanVlan.section}.ports='${Parsers.swPortsValue(lanPorts.filterNot { it.port == n })}'"
            ops += "set network.$SETUP_VLAN=switch_vlan"
            ops += "set network.$SETUP_VLAN.device='${lanVlan.device}'"
            ops += "set network.$SETUP_VLAN.vlan='$vid'"
            ops += "set network.$SETUP_VLAN.ports='${Parsers.swPortsValue(listOf(SwPort(n, false), cpu))}'"
            member = "$base.$vid"
        } else {
            if (bridge == null || port !in bridge.ports) return null
            ops += Commands.listOps("network.${bridge.section}.ports", bridge.ports.filterNot { it == port })
            Parsers.bridgeVlans(networkUci).filter { v -> v.ports.any { it.name == port } }.forEach { v ->
                ops += Commands.listOps("network.${v.section}.ports", v.ports.filterNot { it.name == port }.map { it.token() })
            }
            member = port
        }
        ops += "set network.$SETUP_DEVICE=device"
        ops += "set network.$SETUP_DEVICE.name='$SETUP_BRIDGE'"
        ops += "set network.$SETUP_DEVICE.type='bridge'"
        ops += "add_list network.$SETUP_DEVICE.ports='$member'"
        ops += "set network.$SETUP_IFACE=interface"
        ops += "set network.$SETUP_IFACE.device='$SETUP_BRIDGE'"
        // No address: the kernel gives the bridge a link-local one on its own, which is all
        // the hop needs, and nothing here can collide with anything.
        ops += "set network.$SETUP_IFACE.proto='none'"
        // A zone of its own, or fw4 rejects input on the unzoned interface — including the
        // neighbour advertisements without which the router on it can never be reached.
        // Input from one router the user is setting up, for the minutes it takes; no forwarding.
        ops += "set firewall.$SETUP_ZONE=zone"
        ops += "set firewall.$SETUP_ZONE.name='$SETUP_ZONE_NAME'"
        ops += "set firewall.$SETUP_ZONE.input='ACCEPT'"
        ops += "set firewall.$SETUP_ZONE.output='ACCEPT'"
        ops += "set firewall.$SETUP_ZONE.forward='REJECT'"
        ops += "add_list firewall.$SETUP_ZONE.network='$SETUP_IFACE'"
        return ops
    }

    const val SETUP_ZONE = "wrtpulse_setup"
    const val SETUP_ZONE_NAME = "setup"

    /** The config files [setupPortOps] and [setupReleaseOps] touch, in commit order. */
    val SETUP_PACKAGES = listOf("network", "firewall")

    /**
     * The `uci show network` map as it will read once [setupPortOps] has applied — what the
     * undo has to be computed against, and it has to exist before the isolation runs so the
     * router can hold it. Lists are spelled the way `uci show` prints them, quotes stripped.
     */
    fun afterIsolation(networkUci: Map<String, String>, port: String): Map<String, String> {
        val out = networkUci.toMutableMap()
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val bridge = Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }
        val member: String
        if (port.startsWith("sw:")) {
            val n = port.removePrefix("sw:").toIntOrNull() ?: return out
            val vlans = Parsers.switchVlans(networkUci)
            val lanVlan = vlans.firstOrNull { it.vlan == Parsers.lanSwitchVlan(networkUci) } ?: return out
            val lanPorts = Parsers.swPorts(lanVlan.ports)
            val cpu = lanPorts.firstOrNull { it.tagged } ?: return out
            val vid = (3..4000).first { id -> vlans.none { it.vlan == id } }
            out["network.${lanVlan.section}.ports"] = Parsers.swPortsValue(lanPorts.filterNot { it.port == n })
            out["network.$SETUP_VLAN"] = "switch_vlan"
            out["network.$SETUP_VLAN.device"] = lanVlan.device
            out["network.$SETUP_VLAN.vlan"] = vid.toString()
            out["network.$SETUP_VLAN.ports"] = Parsers.swPortsValue(listOf(SwPort(n, false), cpu))
            member = (Parsers.lanSwitchMember(networkUci)?.substringBefore('.') ?: "eth0") + ".$vid"
        } else {
            if (bridge != null) out["network.${bridge.section}.ports"] = bridge.ports.filterNot { it == port }.joinToString("' '")
            Parsers.bridgeVlans(networkUci).filter { v -> v.ports.any { it.name == port } }.forEach { v ->
                out["network.${v.section}.ports"] = v.ports.filterNot { it.name == port }.joinToString("' '") { it.token() }
            }
            member = port
        }
        out["network.$SETUP_DEVICE"] = "device"
        out["network.$SETUP_DEVICE.name"] = SETUP_BRIDGE
        out["network.$SETUP_DEVICE.type"] = "bridge"
        out["network.$SETUP_DEVICE.ports"] = member
        out["network.$SETUP_IFACE"] = "interface"
        out["network.$SETUP_IFACE.device"] = SETUP_BRIDGE
        out["network.$SETUP_IFACE.proto"] = "none"
        return out
    }

    /** The mirror image: the setup sections go, and the socket rejoins the LAN it came from. */
    fun setupReleaseOps(networkUci: Map<String, String>, port: String): List<String> {
        val ops = mutableListOf<String>()
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val bridge = Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }
        if (port.startsWith("sw:")) {
            val n = port.removePrefix("sw:").toIntOrNull()
            val lanVid = Parsers.lanSwitchVlan(networkUci)
            val lanVlan = Parsers.switchVlans(networkUci).firstOrNull { it.vlan == lanVid }
            if (n != null && lanVlan != null) {
                val ports = Parsers.swPorts(lanVlan.ports)
                if (ports.none { it.port == n }) {
                    ops += "set network.${lanVlan.section}.ports='${Parsers.swPortsValue(ports + SwPort(n, false))}'"
                }
            }
            if (networkUci.containsKey("network.$SETUP_VLAN")) ops += "delete network.$SETUP_VLAN"
        } else if (bridge != null) {
            if (port !in bridge.ports) ops += Commands.listOps("network.${bridge.section}.ports", bridge.ports + port)
            Parsers.bridgeVlans(networkUci).filter { v -> v.device == lanDevice && v.ports.none { it.name == port } && v.ports.any { !it.tagged } }
                .forEach { v -> ops += Commands.listOps("network.${v.section}.ports", v.ports.map { it.token() } + "$port:u*") }
        }
        if (networkUci.containsKey("network.$SETUP_IFACE")) ops += "delete network.$SETUP_IFACE"
        if (networkUci.containsKey("network.$SETUP_DEVICE")) ops += "delete network.$SETUP_DEVICE"
        // The zone is written unconditionally: the network map says nothing about the
        // firewall file, and deleting an absent section is a no-op in a batch.
        ops += "delete firewall.$SETUP_ZONE"
        return ops
    }

    /** The socket a primary is holding apart, from its config: `lan3`, `sw:4`, or null. */
    fun heldSetupPort(networkUci: Map<String, String>): String? {
        val member = Parsers.uciList(networkUci["network.$SETUP_DEVICE.ports"].orEmpty()).firstOrNull() ?: return null
        val vid = networkUci["network.$SETUP_VLAN.vlan"]
        if (vid != null && member.endsWith(".$vid")) {
            val n = Parsers.swPorts(networkUci["network.$SETUP_VLAN.ports"].orEmpty()).firstOrNull { !it.tagged }?.port ?: return null
            return "sw:$n"
        }
        return member
    }

    /** LAN sockets with nothing plugged in — the ones the setup can hold apart before a cable arrives. */
    fun freeLanSockets(devs: List<NetDev>, sw: List<SwitchDev>, networkUci: Map<String, String>): List<String> {
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val lanPorts = Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }?.ports.orEmpty()
        val dsa = Parsers.switchPorts(devs).filter { !it.carrier && (lanPorts.isEmpty() || it.name in lanPorts) && !it.name.startsWith("wan") }.map { it.name }
        if (dsa.isNotEmpty() || sw.isEmpty()) return dsa
        val lanVid = Parsers.lanSwitchVlan(networkUci)
        val lanSw = Parsers.switchVlans(networkUci).firstOrNull { it.vlan == lanVid }?.let { Parsers.swPorts(it.ports) }
            ?.filterNot { it.tagged }?.map { it.port }.orEmpty()
        return sw.flatMap { dev -> lanSw.filter { p -> dev.links[p]?.up != true && p != dev.cpuPort }.map { "sw:$it" } }
    }

    /** Whether one socket has a link right now. */
    fun socketUp(port: String, devs: List<NetDev>, sw: List<SwitchDev>): Boolean =
        if (port.startsWith("sw:")) {
            val n = port.removePrefix("sw:").toIntOrNull()
            sw.any { dev -> dev.links[n]?.up == true }
        } else devs.firstOrNull { it.name == port }?.carrier == true

    /**
     * The socket a cable just went into: physical, wired, in the LAN, down before and up now.
     * DSA sockets are netdevs with carrier; swconfig sockets are chip ports with link state.
     */
    fun newlyUpPort(
        before: List<NetDev>, after: List<NetDev>,
        beforeSw: List<SwitchDev>, afterSw: List<SwitchDev>,
        networkUci: Map<String, String>,
    ): String? {
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        val lanPorts = Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }?.ports.orEmpty()
        val wasUp = before.filter { it.carrier }.map { it.name }.toSet()
        Parsers.switchPorts(after).firstOrNull { it.carrier && it.name !in wasUp && (lanPorts.isEmpty() || it.name in lanPorts) }
            ?.let { return it.name }
        val lanVid = Parsers.lanSwitchVlan(networkUci)
        val lanSw = Parsers.switchVlans(networkUci).firstOrNull { it.vlan == lanVid }?.let { Parsers.swPorts(it.ports) }
            ?.filterNot { it.tagged }?.map { it.port }.orEmpty()
        afterSw.forEach { dev ->
            val was = beforeSw.firstOrNull { it.name == dev.name }?.links.orEmpty()
            dev.links.values.firstOrNull { l -> l.up && was[l.port]?.up != true && l.port != dev.cpuPort && (lanSw.isEmpty() || l.port in lanSw) }
                ?.let { return "sw:${it.port}" }
        }
        return null
    }

    /** Sections a node's copied SSIDs land in: `wrtpulse_ap_radio0`, then `wrtpulse_ap_radio0_2`. */
    const val AP_PREFIX = "wrtpulse_ap_"
    fun apSection(radio: String, index: Int = 0) = if (index == 0) "$AP_PREFIX$radio" else "$AP_PREFIX${radio}_${index + 1}"

    private val DFS_5G = 52..144

    /**
     * hostapd's own default, so a node someone configured by hand still agrees: the first
     * four hex digits of md5 over the SSID *and the newline `echo` appends to it*.
     */
    fun mobilityDomain(ssid: String): String {
        val digest = MessageDigest.getInstance("MD5").digest((ssid + "\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(4)
    }

    /** Whether hand-off can be turned on for this encryption: FT needs a WPA2/WPA3 key. */
    fun roamingCapable(encryption: String): Boolean = encryption in setOf("psk2", "sae", "sae-mixed")

    /**
     * The options that make one AP part of the roaming set. 802.11k/v go on regardless; 802.11r
     * only where the key exchange supports it. Open networks and WPA1-mixed get the note.
     */
    fun roamingOptions(ssid: String, encryption: String): List<Pair<String, String>> = buildList {
        if (roamingCapable(encryption)) {
            add("ieee80211r" to "1")
            add("mobility_domain" to mobilityDomain(ssid))
            add("ft_over_ds" to "0")
            add("ft_psk_generate_local" to "1")
        }
        add("ieee80211k" to "1")
        add("bss_transition" to "1")
    }

    /** The uci lines that turn roaming on across the primary's own LAN APs. */
    fun roamingOps(lanAps: List<WifiNetwork>): List<String> = lanAps
        .filter { it.mode == "ap" && it.section != MESH_SECTION }
        .flatMap { ap ->
            roamingOptions(ap.ssid, ap.encryption).map { (option, value) ->
                "set wireless.${ap.section}.$option='$value'"
            }
        }

    /** The mirror image: the hand-off options go, the SSIDs stay exactly as they were. */
    fun roamingOffOps(lanAps: List<WifiNetwork>): List<String> = lanAps
        .filter { it.mode == "ap" && it.section != MESH_SECTION }
        .flatMap { ap ->
            listOf("ieee80211r", "mobility_domain", "ft_over_ds", "ft_psk_generate_local", "ieee80211k", "bss_transition")
                .map { "delete wireless.${ap.section}.$it" }
        }

    /** The SSIDs whose hand-off would be skipped, for the review card. */
    fun roamingNotes(lanAps: List<WifiNetwork>): List<String> = lanAps
        .filter { it.mode == "ap" && it.section != MESH_SECTION && !roamingCapable(it.encryption) }
        .map { ap ->
            if (ap.encryption == "none" || ap.encryption.isEmpty()) {
                "${ap.ssid} is open: clients still roam, but the hand-off needs a WPA2 or WPA3 password to be seamless."
            } else {
                "${ap.ssid} uses ${Parsers.encryptionLabel(ap.encryption)}: hand-off is skipped, since WPA1 clients cannot do it."
            }
        }

    /** An 802.11s point on [radio], bridged into the LAN like an AP would be. */
    fun meshIfaceOps(section: String, radio: String, meshId: String, key: String): List<String> = listOf(
        "set wireless.$section=wifi-iface",
        "set wireless.$section.device='$radio'",
        "set wireless.$section.mode='mesh'",
        "set wireless.$section.mesh_id='${Commands.escapeValue(meshId)}'",
        "set wireless.$section.encryption='sae'",
        "set wireless.$section.key='${Commands.escapeValue(key)}'",
        "set wireless.$section.network='lan'",
        "set wireless.$section.mesh_fwding='1'",
    )

    fun isDfs(band: String, channel: Int): Boolean = band == "5G" && channel in DFS_5G

    /**
     * Where a mesh radio has to sit: a fixed, non-DFS channel, because every peer must share it
     * and a radar hit would take the whole backhaul down for a minute. Keeps what the radio is
     * on now when that qualifies; otherwise the bottom of the band at the radio's width.
     */
    fun pinnedChannel(radio: WifiRadio, operatingChannel: Int?): Int? {
        val configured = radio.channel.toIntOrNull()
        val current = configured ?: operatingChannel
        if (current != null && !isDfs(radio.band, current) && current > 0) return if (configured != null) null else current
        val width = ChannelPlan.widthOf(radio.htmode)
        return ChannelPlan.candidates(radio.band, width).firstOrNull()
    }

    /** The uci line that pins it, or nothing when the radio already sits on a fixed safe channel. */
    fun pinChannelOps(radio: WifiRadio, operatingChannel: Int?): List<String> =
        pinnedChannel(radio, operatingChannel)?.let { listOf("set wireless.${radio.section}.channel='$it'") }.orEmpty()

    /** The problems with a primary's mesh radio, before anything is written. */
    fun meshRadioProblems(radio: WifiRadio?): List<String> = buildList {
        if (radio == null) { add("This router has no 5 GHz radio for the mesh link."); return@buildList }
        if (radio.country.isBlank()) add("Set the country on ${radio.section} first (System · Country): without one, 5 GHz channels above 48 cannot be used.")
        if (radio.disabled) add("${radio.section} is switched off.")
    }

    /**
     * The wpad build to install for 802.11s, or null when what is there already has it.
     * `wpad-basic-<ssl>` and `wpad-mini` become `wpad-mesh-<ssl>`; the full `wpad`, `wpad-<ssl>`
     * and `wpad-mesh-*` are all mesh-capable.
     */
    fun wpadSwap(installed: String, meshCapable: Boolean): WpadSwap? {
        if (meshCapable) return null
        val name = installed.trim()
        if (name.isEmpty()) return null
        val ssl = name.substringAfterLast('-', "").takeIf { it in setOf("mbedtls", "openssl", "wolfssl") } ?: "mbedtls"
        return when {
            name.startsWith("wpad-mesh") -> null
            name.startsWith("wpad-basic") || name == "wpad-mini" -> WpadSwap(name, "wpad-mesh-$ssl")
            else -> null
        }
    }

    /** A hostname the kernel and every neighbour will accept: letters, digits and dashes. */
    fun hostnameOf(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-')
        return cleaned.take(63).ifEmpty { "node" }
    }

    /** "Node 2" for a primary with one node already, and so on. */
    fun defaultNodeName(existing: Int): String = "Node ${existing + 2}"

    /**
     * The address a node takes: the lowest free host below the DHCP pool, so it never collides
     * with a lease; above the pool when the pool starts at the bottom. Null when the subnet is
     * full, which the wizard reports rather than guessing.
     */
    fun freeNodeAddress(profile: MeshProfile, alsoTaken: Set<String> = emptySet()): String? {
        val router = IpMath.parse(profile.primaryIp) ?: return null
        val network = IpMath.networkOf(router, profile.prefix)
        val broadcast = IpMath.broadcastOf(network, profile.prefix)
        val taken = (profile.taken + alsoTaken).mapNotNull { IpMath.parse(it) }.toSet()
        val pool = IpMath.poolRange(network, profile.prefix, profile.poolStart, profile.poolLimit)
        fun free(c: Long) = c != router && c !in taken && c > network && c < broadcast
        if (pool != null) {
            for (c in (network + 2)..(pool.first - 1)) if (free(c)) return IpMath.format(c)
            for (c in (pool.last + 1)..(broadcast - 1)) if (free(c)) return IpMath.format(c)
        }
        for (c in (network + 2)..(broadcast - 1)) if (free(c)) return IpMath.format(c)
        return null
    }

    fun randomKey(length: Int = 24): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /**
     * Everything the batch writes on the node, in dependency order: hostname, the LAN address
     * and its gateway, the DHCP server off, the WAN socket into the LAN, then the wireless —
     * the node's own SSIDs replaced by the primary's, and a mesh point for a wireless node.
     */
    fun nodeOps(profile: MeshProfile, node: MeshNodeState, name: String, backhaul: Backhaul, address: String): List<String> {
        val ops = mutableListOf<String>()
        val net = node.networkUci
        val lan = node.lan

        // ---- hostname ----
        ops += "set system.@system[0].hostname='${hostnameOf(name)}'"

        // ---- the LAN: static, in the primary's subnet, the primary as gateway and resolver ----
        ops += "set network.lan.proto='static'"
        if (lan?.cidrPrefix != null || (lan != null && lan.netmask.isEmpty())) {
            ops += "set network.lan.ipaddr='$address/${profile.prefix}'"
            if (lan?.netmask?.isNotEmpty() == true) ops += "delete network.lan.netmask"
        } else {
            ops += "set network.lan.ipaddr='$address'"
            ops += "set network.lan.netmask='${IpMath.netmaskOf(profile.prefix)}'"
        }
        ops += "set network.lan.gateway='${profile.primaryIp}'"
        ops += Commands.listOps("network.lan.dns", listOf(profile.primaryIp))
        if (net.containsKey("network.lan.ip6assign")) ops += "delete network.lan.ip6assign"

        // ---- the DHCP server off; the primary serves the whole LAN ----
        val pool = Parsers.dhcpPools(node.dhcpUci).firstOrNull { it.interfaceName == "lan" }?.section ?: "lan"
        if (node.dhcpUci["dhcp.$pool"] != "dhcp") ops += "set dhcp.$pool=dhcp"
        ops += "set dhcp.$pool.interface='lan'"
        ops += "set dhcp.$pool.ignore='1'"
        listOf("ra", "dhcpv6", "ra_slaac", "ra_flags", "ndp").forEach { option ->
            if (node.dhcpUci.containsKey("dhcp.$pool.$option")) ops += "delete dhcp.$pool.$option"
        }
        if (node.dhcpUci["dhcp.wan"] == "dhcp") ops += "delete dhcp.wan"

        // ---- the WAN socket becomes a LAN socket ----
        ops += foldWanOps(node)
        if (net["network.wan"] == "interface") ops += "delete network.wan"
        if (net["network.wan6"] == "interface") ops += "delete network.wan6"

        // ---- wireless: the primary's SSIDs, and the mesh point ----
        node.networks.filter { it.mode == "ap" && (it.network == "lan" || it.network.isEmpty()) }
            .forEach { ops += "delete wireless.${it.section}" }
        node.networks.filter { it.section == MESH_SECTION }.forEach { ops += "delete wireless.${it.section}" }
        val meshRadio = if (backhaul == Backhaul.Wireless) meshRadioOf(profile, node) else null
        ops += nodeRadioOps(profile, node.radios, meshRadio)
        ops += nodeApOps(profile, node.radios, existing = emptyList())
        ops += nodeExtraOps(profile, node.radios, meshRadio, meshIfname = null, node.networkUci, node.swDevs, existingNetworks = emptyList())
        if (meshRadio != null && profile.meshId != null && profile.meshKey != null) {
            ops += meshIfaceOps(MESH_SECTION, meshRadio, profile.meshId, profile.meshKey)
        }
        return ops
    }

    /**
     * The radio settings a node takes from the primary: country and width on every radio, and
     * the channel on the radio that carries the mesh point — a mesh peer on another channel is
     * no peer at all. A wired node keeps its own channel (auto when it had none), since two
     * radios on one channel in one house only compete. Written at the join and again by every
     * sync, so a channel change on the primary reaches the nodes.
     */
    fun nodeRadioOps(profile: MeshProfile, radios: List<WifiRadio>, meshRadio: String?): List<String> {
        val ops = mutableListOf<String>()
        radios.forEach { radio ->
            val plan = profile.radioFor(radio.band)
            ops += "set wireless.${radio.section}.disabled='0'"
            if (plan != null) {
                if (plan.country.isNotBlank()) ops += "set wireless.${radio.section}.country='${plan.country}'"
                if (radio.section == meshRadio && plan.channel.isNotBlank() && plan.channel != "auto") {
                    ops += "set wireless.${radio.section}.channel='${plan.channel}'"
                } else if (radio.channel.isBlank()) {
                    ops += "set wireless.${radio.section}.channel='auto'"
                }
                if (plan.htmode.isNotBlank()) ops += "set wireless.${radio.section}.htmode='${plan.htmode}'"
            }
        }
        return ops
    }

    /** True when a node's radios carry what [nodeRadioOps] would write; [meshRadio] is the node's mesh radio, if any. */
    fun radiosInSync(profile: MeshProfile, radios: List<WifiRadio>, meshRadio: String?): Boolean =
        radioDrift(profile, radios, meshRadio) == null

    /** "5 GHz channel 149 → 36" — the first radio setting that differs, or null. */
    fun radioDrift(profile: MeshProfile, radios: List<WifiRadio>, meshRadio: String?): String? {
        radios.forEach { radio ->
            val plan = profile.radioFor(radio.band) ?: return@forEach
            if (radio.disabled) return "${radio.band} radio is off"
            if (plan.country.isNotBlank() && radio.country != plan.country) return "country ${radio.country.ifBlank { "unset" }} → ${plan.country}"
            if (radio.section == meshRadio && plan.channel.isNotBlank() && plan.channel != "auto" && radio.channel != plan.channel) {
                return "${radio.band} channel ${radio.channel} → ${plan.channel}"
            }
            if (plan.htmode.isNotBlank() && radio.htmode != plan.htmode) return "${radio.band} width ${radio.htmode.ifBlank { "unset" }} → ${plan.htmode}"
        }
        return null
    }

    /** The SSIDs a node radio carries: every LAN SSID of its band, else the other band's. */
    fun ssidsFor(profile: MeshProfile, band: String): List<MeshSsid> =
        profile.ssids.filter { it.band == band }.ifEmpty { profile.ssidFor(band)?.let { listOf(it) }.orEmpty() }

    /**
     * The AP sections a node should carry, written fresh: every section this app wrote before
     * goes, then one per (radio, LAN SSID of the primary) comes back with the hand-off options.
     * The same lines serve the join and a later sync, so a node cannot drift from what the
     * primary would have written today.
     */
    fun nodeApOps(profile: MeshProfile, radios: List<WifiRadio>, existing: List<WifiNetwork>): List<String> {
        val ops = mutableListOf<String>()
        existing.filter { it.section.startsWith(AP_PREFIX) }.forEach { ops += "delete wireless.${it.section}" }
        radios.forEach { radio ->
            ssidsFor(profile, radio.band).forEachIndexed { index, ssid ->
                val section = apSection(radio.section, index)
                ops += "set wireless.$section=wifi-iface"
                ops += "set wireless.$section.device='${radio.section}'"
                ops += "set wireless.$section.mode='ap'"
                ops += "set wireless.$section.ssid='${Commands.escapeValue(ssid.ssid)}'"
                ops += "set wireless.$section.network='lan'"
                ops += "set wireless.$section.encryption='${ssid.encryption}'"
                if (ssid.encryption != "none" && ssid.key.isNotEmpty()) {
                    ops += "set wireless.$section.key='${Commands.escapeValue(ssid.key)}'"
                }
                if (ssid.hidden) ops += "set wireless.$section.hidden='1'"
                roamingOptions(ssid.ssid, ssid.encryption).forEach { (option, value) ->
                    ops += "set wireless.$section.$option='$value'"
                }
            }
        }
        return ops
    }

    const val EXTRA_PREFIX = "wrtpulse_x_"

    /** `phy0-mesh0` for `radio0` — how the wifi scripts name a mesh point, absent a live reading. */
    fun meshIfnameFor(radio: String): String = "phy${radio.filter { it.isDigit() }.ifEmpty { "0" }}-mesh0"

    /** The node's wired sockets on DSA: the plain netdevs in its LAN bridge. Empty on swconfig. */
    private fun dsaLanPorts(networkUci: Map<String, String>): List<String> {
        val lanDevice = networkUci["network.lan.device"].orEmpty()
        return Parsers.netDevices(networkUci).firstOrNull { it.name == lanDevice }?.ports.orEmpty()
            .filter { !it.contains('.') && !it.startsWith("phy") && !it.startsWith("wlan") }
    }

    /**
     * Everything a node needs for the primary's extra networks: per network a bridge of its
     * own, the VLAN that carries it over the mesh point and over every wired socket, an
     * interface with no address (the primary owns the subnet), and an AP per band the node
     * has. A band the node lacks is left out, as is a network the primary has no SSID for.
     * Existing `wrtpulse_x_*` sections are replaced wholesale, so a sync cannot drift.
     */
    fun nodeExtraOps(
        profile: MeshProfile,
        radios: List<WifiRadio>,
        meshRadio: String?,
        /** The mesh point's live netdev when known; derived from the radio otherwise. */
        meshIfname: String?,
        networkUci: Map<String, String>,
        swDevs: List<SwitchDev>,
        existingNetworks: List<WifiNetwork>,
    ): List<String> {
        val ops = mutableListOf<String>()
        existingNetworks.filter { it.section.startsWith(EXTRA_PREFIX) }.forEach { ops += "delete wireless.${it.section}" }
        networkUci.keys.filter { it.startsWith("network.$EXTRA_PREFIX") && it.count { c -> c == '.' } == 1 }
            .forEach { ops += "delete ${it}" }
        val mesh = meshRadio?.let { meshIfname ?: meshIfnameFor(it) }
        val lanPorts = dsaLanPorts(networkUci)
        val vlans = Parsers.switchVlans(networkUci)
        val lanVlan = Parsers.lanSwitchVlan(networkUci)?.let { id -> vlans.firstOrNull { it.vlan == id } }
        val base = Parsers.lanSwitchMember(networkUci)?.substringBefore('.') ?: "eth0"
        profile.extras.forEach { x ->
            val bands = radios.map { it.band }.toSet()
            val carried = x.ssids.filter { it.band in bands }
            if (carried.isEmpty()) return@forEach
            val sec = x.section
            val ports = mutableListOf<String>()
            if (mesh != null) {
                ops += "set network.${sec}_mesh=device"
                ops += "set network.${sec}_mesh.type='8021q'"
                ops += "set network.${sec}_mesh.ifname='$mesh'"
                ops += "set network.${sec}_mesh.vid='${x.vid}'"
                ops += "set network.${sec}_mesh.name='$mesh.${x.vid}'"
                ports += "$mesh.${x.vid}"
            }
            if (swDevs.isNotEmpty() && lanVlan != null) {
                // One tagged VLAN across the LAN's sockets and the CPU; the CPU side is eth0.<vid>.
                val members = Parsers.swPorts(lanVlan.ports).map { SwPort(it.port, true) }
                ops += "set network.${sec}_vlan=switch_vlan"
                ops += "set network.${sec}_vlan.device='${lanVlan.device}'"
                ops += "set network.${sec}_vlan.vlan='${x.vid}'"
                ops += "set network.${sec}_vlan.ports='${Parsers.swPortsValue(members)}'"
                ports += "$base.${x.vid}"
            } else {
                lanPorts.forEach { p ->
                    val d = "${sec}_${p.filter { it.isLetterOrDigit() }}"
                    ops += "set network.$d=device"
                    ops += "set network.$d.type='8021q'"
                    ops += "set network.$d.ifname='$p'"
                    ops += "set network.$d.vid='${x.vid}'"
                    ops += "set network.$d.name='$p.${x.vid}'"
                    ports += "$p.${x.vid}"
                }
            }
            ops += "set network.${sec}_dev=device"
            ops += "set network.${sec}_dev.name='${x.bridge}'"
            ops += "set network.${sec}_dev.type='bridge'"
            ports.forEach { ops += "add_list network.${sec}_dev.ports='$it'" }
            ops += "set network.$sec=interface"
            ops += "set network.$sec.device='${x.bridge}'"
            ops += "set network.$sec.proto='none'"
            radios.forEach { radio ->
                carried.filter { it.band == radio.band }.forEachIndexed { index, ssid ->
                    val ap = "${sec}_ap_${radio.section}" + if (index == 0) "" else "_${index + 1}"
                    ops += "set wireless.$ap=wifi-iface"
                    ops += "set wireless.$ap.device='${radio.section}'"
                    ops += "set wireless.$ap.mode='ap'"
                    ops += "set wireless.$ap.ssid='${Commands.escapeValue(ssid.ssid)}'"
                    ops += "set wireless.$ap.network='$sec'"
                    ops += "set wireless.$ap.encryption='${ssid.encryption}'"
                    if (ssid.encryption != "none" && ssid.key.isNotEmpty()) ops += "set wireless.$ap.key='${Commands.escapeValue(ssid.key)}'"
                    if (ssid.hidden) ops += "set wireless.$ap.hidden='1'"
                    if (x.isolate) ops += "set wireless.$ap.isolate='1'"
                    roamingOptions(ssid.ssid, ssid.encryption).forEach { (option, value) -> ops += "set wireless.$ap.$option='$value'" }
                }
            }
        }
        return ops
    }

    /** What one AP amounts to, for telling a node's copy from the primary's original. */
    private data class ApShape(val device: String, val ssid: String, val encryption: String, val key: String, val hidden: Boolean, val ft: Boolean, val domain: String, val net: String = "lan", val isolate: Boolean = false)

    /**
     * True when a node's copied APs match what the primary would write today. Compared as
     * sets, so section names and order do not matter; a renamed SSID, a new password, a
     * hidden flag or an added LAN SSID all count as drift.
     */
    fun apsInSync(profile: MeshProfile, radios: List<WifiRadio>, existing: List<WifiNetwork>): Boolean {
        val bands = radios.map { it.band }.toSet()
        val wanted = radios.flatMap { radio ->
            ssidsFor(profile, radio.band).map { s ->
                val ft = roamingCapable(s.encryption)
                ApShape(radio.section, s.ssid, s.encryption, if (s.encryption == "none") "" else s.key, s.hidden, ft, if (ft) mobilityDomain(s.ssid) else "")
            } + profile.extras.flatMap { x ->
                x.ssids.filter { it.band == radio.band && it.band in bands }.map { s ->
                    val ft = roamingCapable(s.encryption)
                    ApShape(radio.section, s.ssid, s.encryption, if (s.encryption == "none") "" else s.key, s.hidden, ft, if (ft) mobilityDomain(s.ssid) else "", x.section, x.isolate)
                }
            }
        }.toSet()
        val have = existing.filter { (it.section.startsWith(AP_PREFIX) || it.section.startsWith(EXTRA_PREFIX)) && it.mode == "ap" }.map { n ->
            val net = if (n.section.startsWith(EXTRA_PREFIX)) n.network else "lan"
            ApShape(n.device, n.ssid, n.encryption, if (n.encryption == "none") "" else n.key, n.hidden, n.ieee80211r, if (n.ieee80211r) n.mobilityDomain else "", net, n.isolate && net != "lan")
        }.toSet()
        return wanted == have
    }

    /** "SSID or password changed" / "1 SSID added" — what a node's drift looks like. */
    fun driftSummary(profile: MeshProfile, radios: List<WifiRadio>, existing: List<WifiNetwork>): String {
        val bands = radios.map { it.band }.toSet()
        val wanted = (radios.flatMap { r -> ssidsFor(profile, r.band).map { it.ssid } } +
            profile.extras.flatMap { x -> x.ssids.filter { it.band in bands }.map { it.ssid } }).toSet()
        val have = existing.filter { (it.section.startsWith(AP_PREFIX) || it.section.startsWith(EXTRA_PREFIX)) && it.mode == "ap" }.map { it.ssid }.toSet()
        val added = wanted - have
        val gone = have - wanted
        return when {
            added.isNotEmpty() && gone.isEmpty() -> "${added.size} SSID${if (added.size == 1) "" else "s"} to add: ${added.joinToString(", ")}"
            gone.isNotEmpty() && added.isEmpty() -> "${gone.size} SSID${if (gone.size == 1) "" else "s"} to drop: ${gone.joinToString(", ")}"
            added.isNotEmpty() -> "SSIDs differ: ${gone.joinToString(", ")} → ${added.joinToString(", ")}"
            existing.any { (it.section.startsWith(AP_PREFIX) || it.section.startsWith(EXTRA_PREFIX)) && it.ieee80211r && it.mobilityDomain != mobilityDomain(it.ssid) } ->
                "hand-off domain does not match the SSID"
            else -> "password, security or hand-off differs"
        }
    }

    /** The node radio that carries the mesh point: the primary's band, else nothing. */
    fun meshRadioOf(profile: MeshProfile, node: MeshNodeState): String? {
        val band = profile.meshBand ?: return null
        return node.radios.firstOrNull { it.band == band }?.section
    }

    /**
     * The WAN socket into the LAN, in whichever of the three shapes the board has: a DSA port
     * into the bridge (and its VLAN, on a VLAN-filtering bridge), a swconfig port into the
     * LAN's switch VLAN with the WAN's VLAN gone, or a separate WAN netdev into the bridge.
     * A board whose LAN is one bare port has nothing to fold.
     */
    fun foldWanOps(node: MeshNodeState): List<String> {
        val net = node.networkUci
        val wanDevice = (net["network.wan.device"] ?: net["network.wan.ifname"]).orEmpty().trim()
        val lanDevice = net["network.lan.device"].orEmpty()
        val devices = Parsers.netDevices(net)
        val ops = mutableListOf<String>()

        if (node.swconfig) {
            val lanVlanId = Parsers.lanSwitchVlan(net) ?: return emptyList()
            val vlans = Parsers.switchVlans(net)
            val lanVlan = vlans.firstOrNull { it.vlan == lanVlanId } ?: return emptyList()
            val wanVlanId = wanDevice.substringAfter('.', "").toIntOrNull()
            val wanVlan = wanVlanId?.let { id -> vlans.firstOrNull { it.vlan == id && it.device == lanVlan.device } }
            val chip = node.swDevs.firstOrNull { it.name == lanVlan.device } ?: node.swDevs.first()
            val boardWan = node.boardPorts[chip.name].orEmpty().firstOrNull { it.role == "wan" }?.num
            val wanPort = boardWan
                ?: wanVlan?.let { Parsers.swPorts(it.ports).firstOrNull { p -> !p.tagged && p.port != chip.cpuPort }?.port }
                ?: return emptyList()
            val lanPorts = Parsers.swPorts(lanVlan.ports)
            if (lanPorts.none { it.port == wanPort }) {
                ops += "set network.${lanVlan.section}.ports='${Parsers.swPortsValue(lanPorts + SwPort(wanPort, false))}'"
            }
            if (wanVlan != null && wanVlan.section != lanVlan.section) ops += "delete network.${wanVlan.section}"
            return ops
        }

        if (wanDevice.isEmpty()) return emptyList()
        // `wan.100` on an ISP VLAN: the socket under the tag is what goes into the bridge.
        val tagged = devices.firstOrNull { it.name == wanDevice && it.type == "8021q" }
        val port = tagged?.ifname?.ifBlank { null } ?: wanDevice.substringBefore('.')
        if (port.isEmpty() || port.startsWith("br-")) return emptyList()
        // The LAN rides `br-lan`, or `br-lan.1` on a VLAN-filtering bridge; either way the
        // bridge is the device before the dot.
        val bridgeName = lanDevice.substringBefore('.')
        val bridge = devices.firstOrNull { it.name == bridgeName && it.type == "bridge" }
        if (bridge != null) {
            if (port !in bridge.ports) {
                ops += Commands.listOps("network.${bridge.section}.ports", bridge.ports + port)
            }
            // On a filtering bridge a member in no VLAN reaches nothing: the socket joins the
            // LAN's VLAN untagged, as its PVID, the way the other LAN sockets do.
            val vlans = Parsers.bridgeVlans(net).filter { it.device == bridge.name }
            val lanVlan = vlans.firstOrNull { it.netdev == lanDevice }
                ?: vlans.firstOrNull { it.vlan == 1 }.takeIf { lanDevice == bridge.name }
            if (lanVlan != null && lanVlan.ports.none { it.name == port }) {
                ops += Commands.listOps("network.${lanVlan.section}.ports", lanVlan.ports.map { it.token() } + "$port:u*")
            }
            return ops
        }
        // Pre-bridge configs list the members straight on the interface.
        val legacy = net["network.lan.ifname"].orEmpty()
        if (legacy.isNotEmpty()) {
            val members = Parsers.uciList(legacy).flatMap { it.split(' ') }.filter { it.isNotBlank() }
            if (port !in members) ops += "set network.lan.ifname='${(members + port).joinToString(" ")}'"
        }
        return ops
    }

    /** The config files [nodeOps] writes into, in commit order. */
    val NODE_PACKAGES = listOf("system", "network", "dhcp", "wireless")

    /** Why a node cannot be made from this router right now, or nothing. */
    fun nodeProblems(
        profile: MeshProfile,
        node: MeshNodeState,
        backhaul: Backhaul,
        address: String?,
        alreadyNodeOf: String?,
    ): List<String> = buildList {
        if (alreadyNodeOf != null && alreadyNodeOf != profile.primaryIdentity) {
            add("This router is already a node of another primary. Leave that mesh first.")
        }
        if (node.lan == null) add("This router has no `lan` interface to work with.")
        if (address == null) add("No free address below the DHCP pool on ${profile.primaryIp}/${profile.prefix}.")
        if (node.radios.isEmpty()) add("This router has no radios; a node without Wi-Fi extends nothing.")
        if (profile.ssids.isEmpty()) add("The primary has no SSID on its LAN to copy. Set one up on ${profile.primaryName} first.")
        if (backhaul == Backhaul.Wireless) {
            if (!profile.wirelessReady) add("${profile.primaryName} has no mesh link yet. Turn on wireless mesh there first.")
            else if (meshRadioOf(profile, node) == null) add("This router has no ${profile.meshBand} radio, which is the band the mesh link uses.")
            val swap = wpadSwap(node.wpad, node.meshCapable)
            if (swap != null) {
                val free = node.overlayFreeKb
                if (free != null && free < MIN_SWAP_KB) add("Only $free kB free on the overlay; the mesh-capable wpad needs about $MIN_SWAP_KB kB.")
            } else if (!node.meshCapable && node.wpad.isEmpty()) {
                add("No wpad package found on this router, so its 802.11s support cannot be worked out.")
            }
        }
        if (node.wpad == "wpad-mini") add("wpad-mini cannot do 802.11r hand-off. Install wpad-basic-mbedtls (or the mesh build) first.")
    }

    /** What the review card says beyond the ops themselves. */
    fun nodeNotes(profile: MeshProfile, node: MeshNodeState, backhaul: Backhaul, swap: WpadSwap?): List<String> = buildList {
        add("DHCP, DNS and the firewall turn off on this router; ${profile.primaryName} keeps doing that for the whole LAN.")
        add("Its WAN socket becomes a LAN socket, so the cable to ${profile.primaryName} can stay where it is.")
        if (backhaul == Backhaul.Wireless) {
            add("Wireless nodes share ${profile.primaryName}'s ${profile.meshBand ?: "5 GHz"} channel ${profile.radioFor(profile.meshBand ?: "5G")?.channel ?: ""}; the 2.4 GHz SSID stays reachable even if the link never comes up.")
            if (swap != null) add("${swap.install} replaces ${swap.remove} first, while this router still has internet through its WAN socket. Wi-Fi here drops for about a minute.")
        }
        addAll(roamingNotes(profile.ssids.map { WifiNetwork(section = "", device = "", ssid = it.ssid, encryption = it.encryption, key = it.key, disabled = false, network = "lan") }))
        add("A backup of this router is saved to this phone first; Leave mesh puts it back.")
    }

    const val MIN_SWAP_KB = 1024L

    /** How long the node keeps the new config before putting the old one back on its own. */
    const val ROLLBACK_SECONDS = 180

    /** Candidates a node might be — the `OpenWrt` leases on the primary, for the wizard's hint. */
    fun nodeCandidates(leases: List<Lease>, knownHosts: Set<String>): List<Lease> = leases
        .filter { (it.hostname.isNullOrEmpty() || it.hostname.equals("OpenWrt", ignoreCase = true)) && it.ip !in knownHosts }
}
