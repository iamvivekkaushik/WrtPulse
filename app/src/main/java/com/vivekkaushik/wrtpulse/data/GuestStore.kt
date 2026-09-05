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
 * The dashboard's Guest Wi-Fi action.
 *
 * A guest network is not one setting — it is an isolated SSID with its own subnet, its own
 * DHCP pool, and a firewall zone that reaches the internet but not the LAN. So the whole
 * thing is built, reviewed and applied as one batch across wireless, network, dhcp and
 * firewall, the same staging model the rest of the app uses. This never touches the lan or
 * wan zones, so it cannot lock the app out: no rollback arming, just a reload.
 */
class GuestStore(private val session: RouterSession) {

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
            existing = detect(nets, Parsers.firewallConfig(firewall))
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read the guest network: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** A sensible starting point for the create sheet, computed from what the router has. */
    fun defaults(hostname: String?): GuestConfig = GuestConfig(
        ssid = suggestSsid(hostname),
        key = passphrase(),
        open = false,
        devices = radios.map { it.section },
        routerIp = freeGuestSubnet(takenAddresses),
    )

    // ---- applying ----

    suspend fun create(cfg: GuestConfig): Boolean = run(createOps(cfg), "Guest network is up.")

    suspend fun remove(): Boolean {
        val net = existing ?: return true
        return run(removeOps(net), "Guest network removed.")
    }

    /** Enable/disable without tearing anything down — just the APs' `disabled` flag. */
    suspend fun setEnabled(on: Boolean): Boolean {
        val net = existing ?: return false
        val ops = net.apSections.map { "set wireless.$it.disabled='${if (on) "0" else "1"}'" }
        return run(ops, if (on) "Guest network switched on." else "Guest network switched off.", listOf("wireless"), "wifi reload")
    }

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

        fun suggestSsid(hostname: String?): String {
            val base = hostname?.trim()?.takeIf { it.isNotBlank() && !it.equals("OpenWrt", true) } ?: "OpenWrt"
            return "$base-Guest".take(32)
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
        fun apSection(device: String, singleBand: Boolean): String =
            if (singleBand) AP else "${AP}_${device.filter { it.isLetterOrDigit() }}"

        /** The full recipe, as uci operations in commit order. */
        fun createOps(cfg: GuestConfig): List<String> = buildList {
            val mask = IpMath.netmaskOf(PREFIX)
            // A bridge device, so one or several radios' APs land in the same L2 segment.
            add("set network.$DEV=device")
            add("set network.$DEV.type='bridge'")
            add("set network.$DEV.name='$BRIDGE'")
            add("set network.$NET=interface")
            add("set network.$NET.proto='static'")
            add("set network.$NET.device='$BRIDGE'")
            add("set network.$NET.ipaddr='${cfg.routerIp}'")
            add("set network.$NET.netmask='$mask'")

            add("set dhcp.$NET=dhcp")
            add("set dhcp.$NET.interface='$NET'")
            add("set dhcp.$NET.start='100'")
            add("set dhcp.$NET.limit='150'")
            add("set dhcp.$NET.leasetime='1h'")

            add("set firewall.$ZONE=zone")
            add("set firewall.$ZONE.name='$ZONE_NAME'")
            add("set firewall.$ZONE.network='$NET'")
            add("set firewall.$ZONE.input='REJECT'")
            add("set firewall.$ZONE.output='ACCEPT'")
            add("set firewall.$ZONE.forward='REJECT'")
            add("set firewall.${ZONE}_wan=forwarding")
            add("set firewall.${ZONE}_wan.src='$ZONE_NAME'")
            add("set firewall.${ZONE}_wan.dest='wan'")
            // input REJECT would also block DHCP and DNS to the router, leaving guests with
            // no lease and no name resolution — so both are allowed back explicitly.
            add("set firewall.${ZONE}_dhcp=rule")
            add("set firewall.${ZONE}_dhcp.name='Guest-DHCP'")
            add("set firewall.${ZONE}_dhcp.src='$ZONE_NAME'")
            add("set firewall.${ZONE}_dhcp.proto='udp'")
            add("set firewall.${ZONE}_dhcp.dest_port='67'")
            add("set firewall.${ZONE}_dhcp.target='ACCEPT'")
            add("set firewall.${ZONE}_dns=rule")
            add("set firewall.${ZONE}_dns.name='Guest-DNS'")
            add("set firewall.${ZONE}_dns.src='$ZONE_NAME'")
            add("set firewall.${ZONE}_dns.proto='tcpudp'")
            add("set firewall.${ZONE}_dns.dest_port='53'")
            add("set firewall.${ZONE}_dns.target='ACCEPT'")

            val single = cfg.devices.size == 1
            cfg.devices.forEach { device ->
                val s = apSection(device, single)
                add("set wireless.$s=wifi-iface")
                add("set wireless.$s.device='$device'")
                add("set wireless.$s.mode='ap'")
                add("set wireless.$s.ssid='${Commands.escapeValue(cfg.ssid)}'")
                add("set wireless.$s.network='$NET'")
                if (cfg.open) {
                    add("set wireless.$s.encryption='none'")
                } else {
                    add("set wireless.$s.encryption='psk2'")
                    add("set wireless.$s.key='${Commands.escapeValue(cfg.key)}'")
                }
                if (cfg.isolate) add("set wireless.$s.isolate='1'")
            }
        }

        fun createDiff(cfg: GuestConfig): List<String> =
            createOps(cfg).map { "+ " + it.removePrefix("set ") }

        /** Tears down exactly what [createOps] built, plus any stock guest bits detected. */
        fun removeOps(net: GuestNetwork): List<String> = buildList {
            net.apSections.forEach { add("delete wireless.$it") }
            add("delete network.$NET")
            add("delete network.$DEV")
            add("delete dhcp.$NET")
            net.zoneSection?.let { add("delete firewall.$it") }
            add("delete firewall.${ZONE}_wan")
            add("delete firewall.${ZONE}_dhcp")
            add("delete firewall.${ZONE}_dns")
        }.distinct()

        /**
         * Finds a guest network in the config: the app's own, or a stock one recognised by a
         * zone named "guest". The APs are the wifi-ifaces bound to that zone's networks.
         */
        fun detect(networks: List<WifiNetwork>, firewall: Parsers.FirewallConfig): GuestNetwork? {
            val zone = firewall.zones.firstOrNull { it.name == ZONE_NAME }
            val guestNets = (zone?.networks ?: emptyList()).toMutableSet()
            // The app's own interface, even before a zone exists to name it.
            if (networks.any { it.network == NET }) guestNets += NET
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
