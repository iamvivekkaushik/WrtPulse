package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.WifiRadio

/** A guest network the router already has — enough to show it, toggle it, or take it down. */
data class GuestNetwork(
    val ssid: String,
    val key: String,
    val open: Boolean,
    /** True when at least one of its APs is on the air. */
    val enabled: Boolean,
    val bands: List<String>,
    /** The wifi-iface sections that broadcast it. */
    val apSections: List<String>,
    /** The uci network interface it is bridged to. */
    val network: String,
    /** The firewall zone section, when one names it. */
    val zoneSection: String?,
    val zoneName: String?,
    val address: String?,
)

/**
 * One kind of walled-off Wi-Fi the dashboard can put up in a tap: the names it is written
 * under and the traffic it lets through. Guest and IoT are the same recipe — an SSID on its
 * own bridge, subnet, DHCP pool and firewall zone — with one difference in the firewall
 * that is the whole point of each: guests are kept out of the LAN in both directions, while
 * the LAN is let INTO the IoT zone, so a phone on the LAN can still drive the bulbs while
 * the bulbs cannot see the phone.
 */
data class NetworkKind(
    /** "Guest Wi-Fi" — the sheet's title and the dashboard button. */
    val label: String,
    /** "guest network" — how prose refers to it. */
    val noun: String,
    /** The interface / network name. */
    val net: String,
    /** The bridge device section. */
    val dev: String,
    /** The L2 device — short, since ifnames cap at 15 characters. */
    val bridge: String,
    /** The firewall zone section. */
    val zone: String,
    /** The zone name forwardings and rules reference. */
    val zoneName: String,
    /** wifi-iface section base. */
    val ap: String,
    /** "Guest" — what follows the hostname in the suggested SSID. */
    val ssidSuffix: String,
    /** Whether the LAN may open connections into this zone. */
    val lanReaches: Boolean,
    /** Whether client isolation starts on. */
    val isolateDefault: Boolean,
    /** The DHCP lease time: short for passers-by, long for devices that never leave. */
    val leaseTime: String,
    /** The band to default to when the router has it — "2.4G" for IoT, which mostly cannot do 5 GHz. */
    val preferBand: String? = null,
) {
    companion object {
        val GUEST = NetworkKind(
            label = "Guest Wi-Fi", noun = "guest network",
            net = "wrtpulse_guest", dev = "wrtpulse_guest_dev", bridge = "br-guest",
            zone = "wrtpulse_guest", zoneName = "guest", ap = "wrtpulse_guest",
            ssidSuffix = "Guest", lanReaches = false, isolateDefault = true, leaseTime = "1h",
        )
        val IOT = NetworkKind(
            label = "IoT Wi-Fi", noun = "IoT network",
            net = "wrtpulse_iot", dev = "wrtpulse_iot_dev", bridge = "br-iot",
            zone = "wrtpulse_iot", zoneName = "iot", ap = "wrtpulse_iot",
            ssidSuffix = "IoT", lanReaches = true, isolateDefault = false, leaseTime = "12h",
            preferBand = "2.4G",
        )
    }
}

/** What a new guest network should be, as the sheet collects it. */
data class GuestConfig(
    val ssid: String,
    val key: String,
    val open: Boolean,
    /** The radios to broadcast on — one wifi-iface each. */
    val devices: List<String>,
    /** Guest-to-guest blocked at the AP. On by default; that is the point of a guest net. */
    val isolate: Boolean = true,
    /** The router's own address on the guest subnet, e.g. 192.168.3.1. */
    val routerIp: String,
)

/**
 * The dashboard's Guest Wi-Fi and IoT Wi-Fi actions — one store, told which [kind] it is.
 *
 * A guest network is not one setting — it is an isolated SSID with its own subnet, its own
 * DHCP pool, and a firewall zone that reaches the internet but not the LAN. So the whole
 * thing is built, reviewed and applied as one batch across wireless, network, dhcp and
 * firewall, the same staging model the rest of the app uses. This never touches the lan or
 * wan zones, so it cannot lock the app out: no rollback arming, just a reload.
 */
class GuestStore(private val session: RouterSession, val kind: NetworkKind = NetworkKind.GUEST) {

    var radios by mutableStateOf<List<WifiRadio>>(emptyList()); private set

    /** The guest network the router has, or null when there is none to manage. */
    var existing by mutableStateOf<GuestNetwork?>(null); private set

    /** Router addresses already in use, so a new guest subnet does not collide. */
    private var takenAddresses by mutableStateOf<List<String>>(emptyList())

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var applying by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val out = session.exec(
                listOf(
                    "echo ${Commands.SECTION} wireless" to Commands.WIRELESS_CONFIG,
                    "echo ${Commands.SECTION} network" to Commands.NETWORK_CONFIG,
                    "echo ${Commands.SECTION} firewall" to Commands.FIREWALL_CONFIG,
                ).joinToString("; ") { (m, c) -> "$m; $c" },
                timeoutMs = 20_000,
            )
            val parts = Parsers.sections(out.stdout)
            val wireless = Parsers.uciShow(parts["wireless"].orEmpty())
            val network = Parsers.uciShow(parts["network"].orEmpty())
            val firewall = Parsers.uciShow(parts["firewall"].orEmpty())
            val (r, nets) = Parsers.wireless(wireless)
            radios = r
            takenAddresses = network.entries
                .filter { it.key.endsWith(".ipaddr") }
                .map { it.value.substringBefore('/') }
                .filter { it.isNotBlank() }
            existing = detect(nets, Parsers.firewallConfig(firewall), kind)
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read the ${kind.noun}: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** A sensible starting point for the create sheet, computed from what the router has. */
    fun defaults(hostname: String?): GuestConfig = GuestConfig(
        ssid = suggestSsid(hostname, kind),
        key = passphrase(),
        open = false,
        devices = defaultRadios(radios, kind),
        isolate = kind.isolateDefault,
        routerIp = freeGuestSubnet(takenAddresses),
    )

    // ---- applying ----

    suspend fun create(cfg: GuestConfig): Boolean = run(createOps(cfg, kind), "${sentence(kind.noun)} is up.")

    suspend fun remove(): Boolean {
        val net = existing ?: return true
        return run(removeOps(net, kind), "${sentence(kind.noun)} removed.")
    }

    /** Enable/disable without tearing anything down — just the APs' `disabled` flag. */
    suspend fun setEnabled(on: Boolean): Boolean {
        val net = existing ?: return false
        val ops = net.apSections.map { "set wireless.$it.disabled='${if (on) "0" else "1"}'" }
        val what = sentence(kind.noun)
        return run(ops, if (on) "$what switched on." else "$what switched off.", listOf("wireless"), "wifi reload")
    }

    private fun sentence(noun: String): String = noun.replaceFirstChar { it.uppercase() }

    private suspend fun run(
        ops: List<String>,
        done: String,
        packages: List<String> = listOf("wireless", "network", "dhcp", "firewall"),
        reload: String = RELOAD,
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
            // A wifi reload can drop the app's own Wi-Fi for a moment; a lost link right after
            // a committed batch is the reload biting, not a failure to apply.
            if (e is SshException.Disconnected || e is SshException.Timeout) {
                notice = "$done The link dropped during the reload, which is expected on Wi-Fi."
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
        // The guest names, kept for the callers and tests that spell them out.
        const val NET = "wrtpulse_guest"          // the interface / network name
        const val DEV = "wrtpulse_guest_dev"      // the bridge device section
        const val BRIDGE = "br-guest"             // the L2 device (short — ifnames cap at 15)
        const val ZONE = "wrtpulse_guest"         // the firewall zone SECTION
        const val ZONE_NAME = "guest"             // the zone NAME forwardings/rules reference
        const val AP = "wrtpulse_guest"           // wifi-iface base

        private const val PREFIX = 24
        val RELOAD =
            "/etc/init.d/network reload >/dev/null 2>&1; " +
                "/etc/init.d/dnsmasq reload >/dev/null 2>&1; " +
                "/etc/init.d/firewall reload >/dev/null 2>&1; wifi reload"

        private val WORDS = listOf(
            "amber", "basalt", "cedar", "delta", "ember", "fjord", "granite", "harbor",
            "indigo", "juniper", "kestrel", "lumen", "meadow", "nimbus", "onyx", "pewter",
            "quarry", "rowan", "slate", "thistle", "umber", "verdant", "willow", "zephyr",
        )

        /** A four-word passphrase, the same shape the Wi-Fi editor offers. */
        fun passphrase(): String {
            val random = java.security.SecureRandom()
            return (1..4).joinToString("-") { WORDS[random.nextInt(WORDS.size)] }
        }

        fun suggestSsid(hostname: String?, kind: NetworkKind = NetworkKind.GUEST): String {
            val base = hostname?.trim()?.takeIf { it.isNotBlank() && !it.equals("OpenWrt", true) } ?: "OpenWrt"
            return "$base-${kind.ssidSuffix}".take(32)
        }

        /**
         * The radios a new network starts on. Every radio, unless the kind prefers a band the
         * router has: most smart-home devices only do 2.4 GHz, and an SSID that is also on
         * 5 GHz leaves some of them trying the band they cannot hear.
         */
        fun defaultRadios(radios: List<WifiRadio>, kind: NetworkKind): List<String> {
            val preferred = kind.preferBand?.let { band -> radios.filter { it.band == band } }.orEmpty()
            return (preferred.ifEmpty { radios }).map { it.section }
        }

        /**
         * The lowest `192.168.N.1` whose /24 nothing else already uses. Guest networks live
         * in 192.168 by convention; a router on 10.x or 172.x simply never collides here.
         */
        fun freeGuestSubnet(taken: List<String>): String {
            val usedThird = taken.mapNotNull { addr ->
                val v = IpMath.parse(addr) ?: return@mapNotNull null
                if ((v ushr 16) == 0xC0A8L) ((v ushr 8) and 0xFF).toInt() else null
            }.toSet()
            // Start at 3: .0 and .1 are the usual LAN, and jumping clear of them reads as
            // deliberately separate rather than "the next subnet along".
            val third = (3..254).firstOrNull { it !in usedThird } ?: 3
            return "192.168.$third.1"
        }

        /** One wifi-iface section per radio: bare base for a single band, suffixed otherwise. */
        fun apSection(device: String, singleBand: Boolean, kind: NetworkKind = NetworkKind.GUEST): String =
            if (singleBand) kind.ap else "${kind.ap}_${device.filter { it.isLetterOrDigit() }}"

        /** The full recipe, as uci operations in commit order. */
        fun createOps(cfg: GuestConfig, kind: NetworkKind = NetworkKind.GUEST): List<String> = buildList {
            val mask = IpMath.netmaskOf(PREFIX)
            val net = kind.net
            val zone = kind.zone
            val zoneName = kind.zoneName
            val tag = kind.ssidSuffix
            // A bridge device, so one or several radios' APs land in the same L2 segment.
            add("set network.${kind.dev}=device")
            add("set network.${kind.dev}.type='bridge'")
            add("set network.${kind.dev}.name='${kind.bridge}'")
            add("set network.$net=interface")
            add("set network.$net.proto='static'")
            add("set network.$net.device='${kind.bridge}'")
            add("set network.$net.ipaddr='${cfg.routerIp}'")
            add("set network.$net.netmask='$mask'")

            add("set dhcp.$net=dhcp")
            add("set dhcp.$net.interface='$net'")
            add("set dhcp.$net.start='100'")
            add("set dhcp.$net.limit='150'")
            add("set dhcp.$net.leasetime='${kind.leaseTime}'")

            add("set firewall.$zone=zone")
            add("set firewall.$zone.name='$zoneName'")
            add("set firewall.$zone.network='$net'")
            add("set firewall.$zone.input='REJECT'")
            add("set firewall.$zone.output='ACCEPT'")
            add("set firewall.$zone.forward='REJECT'")
            add("set firewall.${zone}_wan=forwarding")
            add("set firewall.${zone}_wan.src='$zoneName'")
            add("set firewall.${zone}_wan.dest='wan'")
            if (kind.lanReaches) {
                // One way only. The LAN opens connections into the zone — a phone driving a
                // bulb, a laptop casting to a TV — and replies come back on that connection;
                // nothing in the zone can open one towards the LAN.
                add("set firewall.${zone}_lan=forwarding")
                add("set firewall.${zone}_lan.src='lan'")
                add("set firewall.${zone}_lan.dest='$zoneName'")
            }
            // input REJECT would also block DHCP and DNS to the router, leaving guests with
            // no lease and no name resolution — so both are allowed back explicitly.
            add("set firewall.${zone}_dhcp=rule")
            add("set firewall.${zone}_dhcp.name='$tag-DHCP'")
            add("set firewall.${zone}_dhcp.src='$zoneName'")
            add("set firewall.${zone}_dhcp.proto='udp'")
            add("set firewall.${zone}_dhcp.dest_port='67'")
            add("set firewall.${zone}_dhcp.target='ACCEPT'")
            add("set firewall.${zone}_dns=rule")
            add("set firewall.${zone}_dns.name='$tag-DNS'")
            add("set firewall.${zone}_dns.src='$zoneName'")
            add("set firewall.${zone}_dns.proto='tcpudp'")
            add("set firewall.${zone}_dns.dest_port='53'")
            add("set firewall.${zone}_dns.target='ACCEPT'")

            val single = cfg.devices.size == 1
            cfg.devices.forEach { device ->
                val s = apSection(device, single, kind)
                add("set wireless.$s=wifi-iface")
                add("set wireless.$s.device='$device'")
                add("set wireless.$s.mode='ap'")
                add("set wireless.$s.ssid='${Commands.escapeValue(cfg.ssid)}'")
                add("set wireless.$s.network='$net'")
                if (cfg.open) {
                    add("set wireless.$s.encryption='none'")
                } else {
                    add("set wireless.$s.encryption='psk2'")
                    add("set wireless.$s.key='${Commands.escapeValue(cfg.key)}'")
                }
                if (cfg.isolate) add("set wireless.$s.isolate='1'")
            }
        }

        fun createDiff(cfg: GuestConfig, kind: NetworkKind = NetworkKind.GUEST): List<String> =
            createOps(cfg, kind).map { "+ " + it.removePrefix("set ") }

        /** Tears down exactly what [createOps] built, plus any stock guest bits detected. */
        fun removeOps(net: GuestNetwork, kind: NetworkKind = NetworkKind.GUEST): List<String> = buildList {
            net.apSections.forEach { add("delete wireless.$it") }
            add("delete network.${kind.net}")
            add("delete network.${kind.dev}")
            add("delete dhcp.${kind.net}")
            net.zoneSection?.let { add("delete firewall.$it") }
            add("delete firewall.${kind.zone}_wan")
            if (kind.lanReaches) add("delete firewall.${kind.zone}_lan")
            add("delete firewall.${kind.zone}_dhcp")
            add("delete firewall.${kind.zone}_dns")
        }.distinct()

        /**
         * Finds a network of this kind in the config: the app's own, or a stock one recognised
         * by a zone of the kind's name. The APs are the wifi-ifaces bound to that zone's networks.
         */
        fun detect(
            networks: List<WifiNetwork>,
            firewall: Parsers.FirewallConfig,
            kind: NetworkKind = NetworkKind.GUEST,
        ): GuestNetwork? {
            val zone = firewall.zones.firstOrNull { it.name == kind.zoneName }
            val guestNets = (zone?.networks ?: emptyList()).toMutableSet()
            // The app's own interface, even before a zone exists to name it.
            if (networks.any { it.network == kind.net }) guestNets += kind.net
            if (guestNets.isEmpty()) return null
            val aps = networks.filter { it.mode == "ap" && it.network in guestNets }
            if (aps.isEmpty()) return null
            val first = aps.first()
            return GuestNetwork(
                ssid = first.ssid,
                key = first.key,
                open = first.encryption == "none" || first.encryption.isEmpty(),
                enabled = aps.any { !it.disabled },
                bands = aps.map { it.device },
                apSections = aps.map { it.section },
                network = first.network,
                zoneSection = zone?.section,
                zoneName = zone?.name,
                address = null,
            )
        }
    }
}

private typealias WifiNetwork = com.vivekkaushik.wrtpulse.ops.WifiNetwork
