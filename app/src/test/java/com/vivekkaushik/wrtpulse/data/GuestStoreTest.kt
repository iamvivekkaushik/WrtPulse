package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSubnetTest {

    @Test
    fun `the guest subnet dodges every 192-168 network already in use`() {
        assertEquals("192.168.3.1", GuestStore.freeGuestSubnet(listOf("192.168.1.1")))
        assertEquals("192.168.4.1", GuestStore.freeGuestSubnet(listOf("192.168.1.1", "192.168.3.1")))
        // A CIDR-suffixed address still parses to its network.
        assertEquals("192.168.4.1", GuestStore.freeGuestSubnet(listOf("192.168.3.1")))
    }

    @Test
    fun `a router on 10-x never collides, so guest lands on the default`() {
        assertEquals("192.168.3.1", GuestStore.freeGuestSubnet(listOf("10.0.0.1", "172.16.0.1")))
    }

    @Test
    fun `the hostname seeds the SSID, but a bare OpenWrt does not`() {
        assertEquals("home.gw-Guest", GuestStore.suggestSsid("home.gw"))
        assertEquals("OpenWrt-Guest", GuestStore.suggestSsid("OpenWrt"))
        assertEquals("OpenWrt-Guest", GuestStore.suggestSsid(null))
    }

    @Test
    fun `a generated passphrase is four words`() {
        assertEquals(4, GuestStore.passphrase().split("-").size)
    }
}

class GuestOpsTest {

    private val cfg = GuestConfig(
        ssid = "Casa-Guest", key = "amber-cedar-delta-ember", open = false,
        devices = listOf("radio0", "radio1"), isolate = true, routerIp = "192.168.3.1",
    )

    @Test
    fun `create builds the interface, dhcp pool, zone and one AP per radio`() {
        val ops = GuestStore.createOps(cfg)
        // network: a bridge device and a static interface on it
        assertTrue(ops.contains("set network.wrtpulse_guest_dev.type='bridge'"))
        assertTrue(ops.contains("set network.wrtpulse_guest.ipaddr='192.168.3.1'"))
        assertTrue(ops.contains("set network.wrtpulse_guest.netmask='255.255.255.0'"))
        // dhcp on that interface
        assertTrue(ops.contains("set dhcp.wrtpulse_guest.interface='wrtpulse_guest'"))
        // zone reaches wan and nothing else
        assertTrue(ops.contains("set firewall.wrtpulse_guest.input='REJECT'"))
        assertTrue(ops.contains("set firewall.wrtpulse_guest.forward='REJECT'"))
        assertTrue(ops.contains("set firewall.wrtpulse_guest_wan.dest='wan'"))
        // one AP section per radio, suffixed because there are two
        assertTrue(ops.contains("set wireless.wrtpulse_guest_radio0=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_guest_radio1=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_guest_radio0.network='wrtpulse_guest'"))
        assertTrue(ops.contains("set wireless.wrtpulse_guest_radio0.isolate='1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_guest_radio0.key='amber-cedar-delta-ember'"))
    }

    @Test
    fun `input REJECT is paired with DHCP and DNS accept rules, or guests get no lease`() {
        val ops = GuestStore.createOps(cfg)
        assertTrue(ops.contains("set firewall.wrtpulse_guest_dhcp.dest_port='67'"))
        assertTrue(ops.contains("set firewall.wrtpulse_guest_dns.dest_port='53'"))
        assertTrue(ops.contains("set firewall.wrtpulse_guest_dns.proto='tcpudp'"))
    }

    @Test
    fun `a single radio gets the bare section name, not a suffix`() {
        val ops = GuestStore.createOps(cfg.copy(devices = listOf("radio0")))
        assertTrue(ops.contains("set wireless.wrtpulse_guest=wifi-iface"))
        assertFalse(ops.any { it.contains("wrtpulse_guest_radio0") })
    }

    @Test
    fun `an open network writes encryption none and never a key`() {
        val ops = GuestStore.createOps(cfg.copy(open = true, devices = listOf("radio0")))
        assertTrue(ops.contains("set wireless.wrtpulse_guest.encryption='none'"))
        assertFalse(ops.any { it.contains(".key=") })
    }

    @Test
    fun `isolation off omits the isolate option`() {
        val ops = GuestStore.createOps(cfg.copy(isolate = false, devices = listOf("radio0")))
        assertFalse(ops.any { it.endsWith(".isolate='1'") })
    }

    @Test
    fun `an SSID with a quote cannot break out of the uci value`() {
        val ops = GuestStore.createOps(cfg.copy(ssid = "it's open", devices = listOf("radio0")))
        assertTrue(ops.any { it.contains("""ssid='it'\''s open'""") })
    }

    @Test
    fun `remove deletes every section create made`() {
        val net = GuestNetwork(
            ssid = "Casa-Guest", key = "x", open = false, enabled = true,
            bands = listOf("radio0", "radio1"),
            apSections = listOf("wrtpulse_guest_radio0", "wrtpulse_guest_radio1"),
            network = "wrtpulse_guest", zoneSection = "wrtpulse_guest", zoneName = "guest", address = null,
        )
        val ops = GuestStore.removeOps(net)
        assertTrue(ops.contains("delete wireless.wrtpulse_guest_radio0"))
        assertTrue(ops.contains("delete network.wrtpulse_guest"))
        assertTrue(ops.contains("delete network.wrtpulse_guest_dev"))
        assertTrue(ops.contains("delete dhcp.wrtpulse_guest"))
        assertTrue(ops.contains("delete firewall.wrtpulse_guest"))
        assertTrue(ops.contains("delete firewall.wrtpulse_guest_wan"))
        assertTrue(ops.contains("delete firewall.wrtpulse_guest_dns"))
    }

    private val guestNet = GuestNetwork(
        ssid = "Casa-Guest", key = "x", open = false, enabled = true,
        bands = listOf("radio0"), apSections = listOf("wrtpulse_guest"),
        network = "wrtpulse_guest", zoneSection = "wrtpulse_guest", zoneName = "guest", address = null,
    )

    @Test
    fun `remove also deletes the mesh trunks built on the network, and only its own`() {
        val uci = mapOf(
            "network.wrtpulse_trunk_guest_mesh" to "device",
            "network.wrtpulse_trunk_guest_mesh.name" to "phy0-mesh0.3",
            "network.wrtpulse_trunk_guest_sw" to "switch_vlan",
            "network.wrtpulse_trunk_guest_sw.vlan" to "3",
            "network.wrtpulse_trunk_iot_mesh" to "device",
            "network.lan" to "interface",
        )
        val ops = GuestStore.removeOps(guestNet, NetworkKind.GUEST, uci)
        assertTrue(ops.contains("delete network.wrtpulse_trunk_guest_mesh"))
        assertTrue(ops.contains("delete network.wrtpulse_trunk_guest_sw"))
        assertFalse(ops.any { it.contains("trunk_iot") })
        assertFalse(ops.any { it.contains(".name") || it.contains(".vlan") })
        // No trunks, nothing extra.
        assertEquals(GuestStore.removeOps(guestNet), GuestStore.removeOps(guestNet, NetworkKind.GUEST, mapOf("network.lan" to "interface")))
    }

    /** A firewall section as uci holds it: [name] null for an anonymous one. */
    private data class Sec(val id: String, val type: String, val name: String?, val opts: Map<String, String>)

    /** How `uci show` spells a section: its name, or `@type[i]` counting every section of the type. */
    private fun ref(all: List<Sec>, s: Sec): String =
        s.name ?: "@${s.type}[${all.takeWhile { it !== s }.count { it.type == s.type }}]"

    private fun show(all: List<Sec>): String = buildString {
        all.forEach { s ->
            val r = ref(all, s)
            append("firewall.$r=${s.type}\n")
            s.opts.forEach { (k, v) -> append("firewall.$r.$k='$v'\n") }
        }
    }

    /** Runs the batch's firewall deletes the way uci does, one at a time; returns the ids deleted. */
    private fun applyDeletes(start: List<Sec>, ops: List<String>): Set<String> {
        val live = start.toMutableList()
        val gone = mutableSetOf<String>()
        val anon = Regex("""@(\w+)\[(\d+)]""")
        ops.filter { it.startsWith("delete firewall.") }.map { it.removePrefix("delete firewall.") }.forEach { path ->
            val m = anon.matchEntire(path)
            val hit = if (m != null) {
                live.filter { it.type == m.groupValues[1] }.getOrNull(m.groupValues[2].toInt())
                    ?: error("$path is out of range when the batch reaches it")
            } else {
                live.firstOrNull { it.name == path }
            }
            if (hit != null) { live.remove(hit); gone += hit.id }
        }
        return gone
    }

    private val firewallWithStrays = listOf(
        Sec("defaults", "defaults", null, emptyMap()),
        Sec("lanZone", "zone", null, mapOf("name" to "lan")),
        Sec("wanZone", "zone", null, mapOf("name" to "wan")),
        Sec("lanWan", "forwarding", null, mapOf("src" to "lan", "dest" to "wan")),
        Sec("guestZone", "zone", "wrtpulse_guest", mapOf("name" to "guest")),
        Sec("guestWan", "forwarding", "wrtpulse_guest_wan", mapOf("src" to "guest", "dest" to "wan")),
        Sec("luciLanGuest", "forwarding", null, mapOf("src" to "lan", "dest" to "guest")),
        Sec("lanIot", "forwarding", null, mapOf("src" to "lan", "dest" to "iot")),
        Sec("luciGuestLan", "forwarding", null, mapOf("src" to "guest", "dest" to "lan")),
        Sec("screenLanGuest", "forwarding", "wrtpulse_zone_lan_guest", mapOf("src" to "lan", "dest" to "guest")),
        Sec("wanRenew", "rule", null, mapOf("name" to "Allow-DHCP-Renew", "src" to "wan", "target" to "ACCEPT")),
        Sec("printer", "rule", null, mapOf("name" to "Guest-Printer", "src" to "guest", "dest" to "lan", "target" to "ACCEPT")),
        Sec("guestDhcp", "rule", "wrtpulse_guest_dhcp", mapOf("name" to "Guest-DHCP", "src" to "guest", "target" to "ACCEPT")),
        Sec("guestPing", "rule", null, mapOf("name" to "Guest-Ping", "src" to "guest", "target" to "ACCEPT")),
        Sec("nas", "redirect", null, mapOf("name" to "NAS", "src" to "wan", "dest" to "lan", "target" to "DNAT")),
        Sec("guestCam", "redirect", null, mapOf("name" to "Cam", "src" to "guest", "dest" to "lan", "target" to "DNAT")),
    )

    @Test
    fun `remove deletes every firewall section that names the zone, anonymous ones highest index first`() {
        val fw = Parsers.firewallConfig(Parsers.uciShow(show(firewallWithStrays)))
        val refs = GuestStore.zoneReferences(fw, "guest")
        // Named sections count: luciLanGuest is @forwarding[2], luciGuestLan @forwarding[4]; printer @rule[1], guestPing @rule[3].
        assertEquals(
            listOf(
                "@forwarding[4]", "@forwarding[2]", "@redirect[1]", "@rule[3]", "@rule[1]",
                "wrtpulse_guest_wan", "wrtpulse_zone_lan_guest", "wrtpulse_guest_dhcp",
            ),
            refs,
        )
        val ops = GuestStore.removeOps(guestNet, NetworkKind.GUEST, emptyMap(), fw)
        // Every anonymous delete runs before any named firewall delete.
        val fwOps = ops.filter { it.startsWith("delete firewall.") }
        assertTrue(fwOps.takeWhile { it.contains("@") }.size == fwOps.count { it.contains("@") })
    }

    @Test
    fun `run as uci would, the batch deletes exactly the zone and what names it`() {
        val fw = Parsers.firewallConfig(Parsers.uciShow(show(firewallWithStrays)))
        val gone = applyDeletes(firewallWithStrays, GuestStore.removeOps(guestNet, NetworkKind.GUEST, emptyMap(), fw))
        assertEquals(
            setOf("guestZone", "guestWan", "luciLanGuest", "luciGuestLan", "screenLanGuest", "printer", "guestDhcp", "guestPing", "guestCam"),
            gone,
        )
        // Spelled out: the LAN keeps its way out, and the IoT zone keeps its forwarding.
        assertFalse("lanWan" in gone)
        assertFalse("lanIot" in gone)
    }

    @Test
    fun `lowest index first would delete the wrong forwarding`() {
        // The hazard the ordering exists for, on the same config: deleting @forwarding[2] first
        // shifts every later forwarding down one, so @forwarding[4] is then the screen's
        // forwarding and luciGuestLan survives.
        val fw = Parsers.firewallConfig(Parsers.uciShow(show(firewallWithStrays)))
        val ascending = GuestStore.zoneReferences(fw, "guest").filter { it.startsWith("@forwarding") }.sorted()
        val gone = applyDeletes(firewallWithStrays, ascending.map { "delete firewall.$it" })
        assertEquals(setOf("luciLanGuest", "screenLanGuest"), gone)
    }

    @Test
    fun `removal deletes the bridge only once no network section names it`() {
        val cmd = GuestStore.removeReload(NetworkKind.IOT)
        assertTrue(cmd.startsWith(GuestStore.RELOAD))
        assertTrue(cmd.contains("[ -e /sys/class/net/br-iot ]"))
        assertTrue(cmd.contains("""! uci -q show network | grep -q "'br-iot'""""))
        assertTrue(cmd.contains("ip link del br-iot"))
        assertFalse(cmd.contains("br-guest"))
        // A missing bridge is not a failure.
        assertTrue(cmd.endsWith("; true"))
    }

    // ---- anonymous sections: the batch run the way uci runs it ----

    /**
     * A guest network made in LuCI, found by its zone: its APs are `@wifi-iface[i]`, one per
     * band, and detect() lists them in file order. Deleted in that order, the second delete took
     * the IoT AP after them and left the 5 GHz guest AP on the air.
     */
    @Test
    fun `removing a stock guest network deletes exactly its APs`() {
        val wireless = FakeUci("wireless", stockGuestWireless)
        val (_, networks) = Parsers.wireless(Parsers.uciShow(wireless.show()))
        val firewall = Parsers.firewallConfig(
            Parsers.uciShow(
                """
                firewall.@zone[0]=zone
                firewall.@zone[0].name='lan'
                firewall.@zone[0].network='lan'
                firewall.@zone[1]=zone
                firewall.@zone[1].name='wan'
                firewall.@zone[1].network='wan' 'wan6'
                firewall.@zone[2]=zone
                firewall.@zone[2].name='guest'
                firewall.@zone[2].network='guest'
                """.trimIndent()
            )
        )
        val net = GuestStore.detect(networks, firewall)!!
        assertEquals(listOf("@wifi-iface[2]", "@wifi-iface[3]"), net.apSections)
        // Only the wireless lines run here: the firewall's is a single `@zone[i]`, and the rest
        // name the app's own sections, which a network made in LuCI does not have.
        val ops = GuestStore.removeOps(net).filter { it.startsWith("delete wireless.") }
        assertEquals(listOf("delete wireless.@wifi-iface[3]", "delete wireless.@wifi-iface[2]"), ops)
        wireless.run(ops).assertLeaves(stockGuestWireless, deleted = setOf("guest2g", "guest5g"))
    }

    private fun ap(id: String, section: String?, device: String, ssid: String, network: String, vararg more: Pair<String, String>) =
        FakeUci.Section(
            id, "wifi-iface", section,
            mapOf("device" to device, "mode" to "ap", "ssid" to ssid, "encryption" to "psk2", "key" to "$ssid-password", "network" to network, *more),
        )

    /** The stock APs by name, a guest network's two added in LuCI, and an IoT AP added after those. */
    private val stockGuestWireless = listOf(
        FakeUci.Section("radio0", "wifi-device", "radio0", mapOf("type" to "mac80211", "band" to "2g", "channel" to "1", "htmode" to "HE20")),
        FakeUci.Section("radio1", "wifi-device", "radio1", mapOf("type" to "mac80211", "band" to "5g", "channel" to "36", "htmode" to "HE80")),
        // default_radio0 and default_radio1 are @wifi-iface[0] and [1], then @wifi-iface[2] to [4]
        ap("casa2g", "default_radio0", "radio0", "Casa", "lan"),
        ap("casa5g", "default_radio1", "radio1", "Casa", "lan"),
        ap("guest2g", null, "radio0", "Casa-Guest", "guest", "isolate" to "1"),
        ap("guest5g", null, "radio1", "Casa-Guest", "guest", "isolate" to "1"),
        ap("iot", null, "radio0", "Casa-IoT", "iot"),
    )
}

class GuestDetectTest {

    private fun ap(section: String, network: String, disabled: Boolean = false, enc: String = "psk2") =
        WifiNetwork(section, "radio0", "Casa-Guest", enc, "secretpass", disabled, "ap", network)

    private fun zone(name: String, network: String) = Parsers.uciShow(
        """
        firewall.z=zone
        firewall.z.name='$name'
        firewall.z.network='$network'
        firewall.z.input='REJECT'
        """.trimIndent()
    ).let { Parsers.firewallConfig(it) }

    @Test
    fun `a node's copy of the primary's guest network is mirrored, not managed`() {
        val nets = listOf(
            ap("wrtpulse_x_guest_ap_radio0", "wrtpulse_x_guest"),
            ap("wrtpulse_x_guest_ap_radio1", "wrtpulse_x_guest").copy(device = "radio1"),
            ap("wrtpulse_x_iot_ap_radio0", "wrtpulse_x_iot").copy(ssid = "Casa-IoT"),
            ap("wrtpulse_ap_radio0", "lan"),
        )
        // Nothing of its own to manage on either kind…
        assertNull(GuestStore.detect(nets, zone("guest", "wrtpulse_guest")))
        assertNull(GuestStore.detect(nets, Parsers.firewallConfig(emptyMap()), NetworkKind.IOT))
        // …but the primary's copies are there to show.
        val guest = GuestStore.mirrored(nets, NetworkKind.GUEST)!!
        assertEquals("Casa-Guest", guest.ssid)
        assertEquals(listOf("radio0", "radio1"), guest.bands)
        assertTrue(guest.enabled)
        assertFalse(guest.open)
        assertEquals("Casa-IoT", GuestStore.mirrored(nets, NetworkKind.IOT)!!.ssid)
        assertEquals(listOf("radio0"), GuestStore.mirrored(nets, NetworkKind.IOT)!!.bands)
    }

    @Test
    fun `a router with its own guest network mirrors nothing`() {
        val nets = listOf(ap("wrtpulse_guest", "wrtpulse_guest"), ap("default_radio0", "lan"))
        assertNull(GuestStore.mirrored(nets, NetworkKind.GUEST))
        assertNull(GuestStore.mirrored(nets, NetworkKind.IOT))
    }

    @Test
    fun `a network with a guest zone is detected with its APs`() {
        val net = GuestStore.detect(
            listOf(ap("wrtpulse_guest", "wrtpulse_guest"), ap("default_radio0", "lan")),
            zone("guest", "wrtpulse_guest"),
        )
        assertEquals("Casa-Guest", net!!.ssid)
        assertEquals(listOf("wrtpulse_guest"), net.apSections)
        assertTrue(net.enabled)
        assertFalse(net.open)
        assertEquals("z", net.zoneSection)
    }

    @Test
    fun `the app's own interface is found even before a zone names it`() {
        val net = GuestStore.detect(
            listOf(ap("wrtpulse_guest", "wrtpulse_guest")),
            Parsers.firewallConfig(emptyMap()),
        )
        assertEquals("wrtpulse_guest", net!!.network)
        assertNull(net.zoneSection)
    }

    @Test
    fun `enabled is false when every guest AP is disabled`() {
        val net = GuestStore.detect(
            listOf(ap("wrtpulse_guest", "wrtpulse_guest", disabled = true)),
            zone("guest", "wrtpulse_guest"),
        )
        assertFalse(net!!.enabled)
    }

    @Test
    fun `no guest zone and no guest interface means nothing to manage`() {
        val net = GuestStore.detect(
            listOf(ap("default_radio0", "lan")),
            zone("lan", "lan"),
        )
        assertNull(net)
    }

    @Test
    fun `an open guest network reads back as open`() {
        val net = GuestStore.detect(
            listOf(ap("wrtpulse_guest", "wrtpulse_guest", enc = "none")),
            zone("guest", "wrtpulse_guest"),
        )
        assertTrue(net!!.open)
    }
}

/**
 * The IoT network is the guest recipe under its own names, with the one firewall difference
 * that makes it useful: the LAN reaches in, the devices cannot reach out to the LAN.
 */
class IotOpsTest {

    private val cfg = GuestConfig(
        ssid = "Casa-IoT", key = "amber-cedar-delta-ember", open = false,
        devices = listOf("radio0"), isolate = false, routerIp = "192.168.4.1",
    )
    private val ops = GuestStore.createOps(cfg, NetworkKind.IOT)

    @Test
    fun `iot lives under its own names and never touches the guest sections`() {
        assertTrue(ops.contains("set network.wrtpulse_iot_dev.name='br-iot'"))
        assertTrue(ops.contains("set network.wrtpulse_iot.ipaddr='192.168.4.1'"))
        assertTrue(ops.contains("set dhcp.wrtpulse_iot.interface='wrtpulse_iot'"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot.name='iot'"))
        assertTrue(ops.contains("set wireless.wrtpulse_iot.network='wrtpulse_iot'"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot_dhcp.name='IoT-DHCP'"))
        assertFalse(ops.any { it.contains("wrtpulse_guest") })
    }

    /** The point of the zone: LAN → IoT is forwarded, IoT → LAN never is. */
    @Test
    fun `the lan is let into the iot zone, one way`() {
        assertTrue(ops.contains("set firewall.wrtpulse_iot_lan=forwarding"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot_lan.src='lan'"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot_lan.dest='iot'"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot_wan.dest='wan'"))
        assertTrue(ops.contains("set firewall.wrtpulse_iot.forward='REJECT'"))
        assertFalse(ops.any { it.contains(".src='iot'") && it.contains("dest='lan'") })
        // Guests get no such forwarding.
        assertFalse(GuestStore.createOps(cfg, NetworkKind.GUEST).any { it.contains("_lan=forwarding") })
    }

    /** Devices that never leave keep their address; passers-by get an hour. */
    @Test
    fun `iot leases are long and isolation is off unless asked`() {
        assertTrue(ops.contains("set dhcp.wrtpulse_iot.leasetime='12h'"))
        assertFalse(ops.any { it.endsWith(".isolate='1'") })
        assertTrue(GuestStore.createOps(cfg.copy(isolate = true), NetworkKind.IOT).any { it.endsWith(".isolate='1'") })
        assertFalse(NetworkKind.IOT.isolateDefault)
        assertTrue(NetworkKind.GUEST.isolateDefault)
    }

    @Test
    fun `remove takes the lan forwarding with it`() {
        val net = GuestNetwork(
            ssid = "Casa-IoT", key = "x", open = false, enabled = true, bands = listOf("radio0"),
            apSections = listOf("wrtpulse_iot"), network = "wrtpulse_iot",
            zoneSection = "wrtpulse_iot", zoneName = "iot", address = null,
        )
        val ops = GuestStore.removeOps(net, NetworkKind.IOT)
        assertTrue(ops.contains("delete firewall.wrtpulse_iot_lan"))
        assertTrue(ops.contains("delete firewall.wrtpulse_iot"))
        assertTrue(ops.contains("delete network.wrtpulse_iot"))
        assertFalse(ops.any { it.contains("wrtpulse_guest") })
    }

    @Test
    fun `the ssid says IoT and the default band is 2_4 GHz when the router has one`() {
        assertEquals("home.gw-IoT", GuestStore.suggestSsid("home.gw", NetworkKind.IOT))
        val radios = listOf(radio("radio0", "2.4G"), radio("radio1", "5G"))
        assertEquals(listOf("radio0"), GuestStore.defaultRadios(radios, NetworkKind.IOT))
        assertEquals(listOf("radio0", "radio1"), GuestStore.defaultRadios(radios, NetworkKind.GUEST))
        // A 5 GHz-only router still gets its one radio rather than nothing.
        assertEquals(listOf("radio1"), GuestStore.defaultRadios(listOf(radio("radio1", "5G")), NetworkKind.IOT))
    }

    /** An IoT zone is found under its own name, and a guest zone is not mistaken for it. */
    @Test
    fun `detection is by the kind's zone`() {
        val ap = WifiNetwork("wrtpulse_iot", "radio0", "Casa-IoT", "psk2", "secretpass", false, "ap", "wrtpulse_iot")
        val fw = Parsers.firewallConfig(
            Parsers.uciShow("firewall.z=zone\nfirewall.z.name='iot'\nfirewall.z.network='wrtpulse_iot'")
        )
        assertEquals("Casa-IoT", GuestStore.detect(listOf(ap), fw, NetworkKind.IOT)!!.ssid)
        assertNull(GuestStore.detect(listOf(ap), fw, NetworkKind.GUEST))
    }

    private fun radio(section: String, band: String) =
        com.vivekkaushik.wrtpulse.ops.WifiRadio(section, band, "auto", "HT20", disabled = false)
}
