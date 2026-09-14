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
    }.toString()

    companion object {
        fun fromJson(text: String): MeshProfile? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
            }
            val ssids = o.optJSONArray("ssids")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    MeshSsid(it.optString("band"), it.optString("ssid"), it.optString("encryption"),
                        it.optString("key"), it.optBoolean("hidden"))
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
            )
        }
    }
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

    /** What one AP amounts to, for telling a node's copy from the primary's original. */
    private data class ApShape(val device: String, val ssid: String, val encryption: String, val key: String, val hidden: Boolean, val ft: Boolean, val domain: String)

    /**
     * True when a node's copied APs match what the primary would write today. Compared as
     * sets, so section names and order do not matter; a renamed SSID, a new password, a
     * hidden flag or an added LAN SSID all count as drift.
     */
    fun apsInSync(profile: MeshProfile, radios: List<WifiRadio>, existing: List<WifiNetwork>): Boolean {
        val wanted = radios.flatMap { radio ->
            ssidsFor(profile, radio.band).map { s ->
                val ft = roamingCapable(s.encryption)
                ApShape(radio.section, s.ssid, s.encryption, if (s.encryption == "none") "" else s.key, s.hidden, ft, if (ft) mobilityDomain(s.ssid) else "")
            }
        }.toSet()
        val have = existing.filter { it.section.startsWith(AP_PREFIX) && it.mode == "ap" }.map { n ->
            ApShape(n.device, n.ssid, n.encryption, if (n.encryption == "none") "" else n.key, n.hidden, n.ieee80211r, if (n.ieee80211r) n.mobilityDomain else "")
        }.toSet()
        return wanted == have
    }

    /** "SSID or password changed" / "1 SSID added" — what a node's drift looks like. */
    fun driftSummary(profile: MeshProfile, radios: List<WifiRadio>, existing: List<WifiNetwork>): String {
        val wanted = radios.flatMap { r -> ssidsFor(profile, r.band).map { it.ssid } }.toSet()
        val have = existing.filter { it.section.startsWith(AP_PREFIX) && it.mode == "ap" }.map { it.ssid }.toSet()
        val added = wanted - have
        val gone = have - wanted
        return when {
            added.isNotEmpty() && gone.isEmpty() -> "${added.size} SSID${if (added.size == 1) "" else "s"} to add: ${added.joinToString(", ")}"
            gone.isNotEmpty() && added.isEmpty() -> "${gone.size} SSID${if (gone.size == 1) "" else "s"} to drop: ${gone.joinToString(", ")}"
            added.isNotEmpty() -> "SSIDs differ: ${gone.joinToString(", ")} → ${added.joinToString(", ")}"
            existing.any { it.section.startsWith(AP_PREFIX) && it.ieee80211r && it.mobilityDomain != mobilityDomain(it.ssid) } ->
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
