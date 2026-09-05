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
