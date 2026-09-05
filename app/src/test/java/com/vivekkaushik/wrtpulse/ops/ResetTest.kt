package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.LossText
import com.vivekkaushik.wrtpulse.data.ResetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val WIRELESS = """
    wireless.radio0=wifi-device
    wireless.radio0.disabled='0'
    wireless.default_radio0=wifi-iface
    wireless.default_radio0.ssid='Casa'
    wireless.guest=wifi-iface
    wireless.guest.ssid='Casa-Guest'
    wireless.iot=wifi-iface
    wireless.iot.ssid='Casa-IoT'
    wireless.iot.disabled='1'
""".trimIndent()

private val NETWORK = """
    network.lan=interface
    network.lan.proto='static'
    network.lan.ipaddr='192.168.0.1'
    network.lan.netmask='255.255.255.0'
    network.wan=interface
    network.wan.proto='pppoe'
    network.wan.username='someone@isp'
""".trimIndent()

private val FIREWALL = """
    firewall.@defaults[0]=defaults
    firewall.@zone[0]=zone
    firewall.@zone[1]=zone
    firewall.@forwarding[0]=forwarding
    firewall.@rule[0]=rule
    firewall.@rule[1]=rule
    firewall.@rule[2]=rule
    firewall.@redirect[0]=redirect
    firewall.@redirect[1]=redirect
""".trimIndent()

private val DHCP = """
    dhcp.@dnsmasq[0]=dnsmasq
    dhcp.lan=dhcp
    dhcp.wan=dhcp
    dhcp.printer=host
    dhcp.printer.mac='aa:bb:cc:dd:ee:ff'
    dhcp.printer.ip='192.168.0.50'
    dhcp.nas=host
    dhcp.nas.ip='192.168.0.51'
""".trimIndent()

private fun parts(
    wireless: String = WIRELESS,
    network: String = NETWORK,
    firewall: String = FIREWALL,
    dhcp: String = DHCP,
    packages: String = "wireguard-tools 1.0\nmap 3.0\nvnstat 2.9\n",
) = mapOf(
    "wireless" to wireless,
    "network" to network,
    "firewall" to firewall,
    "dhcp" to dhcp,
    "packages" to packages,
)

class ResetSummaryTest {

    @Test
    fun `every SSID is named, including one on a disabled radio`() {
        val s = Parsers.resetSummary(parts())
        assertEquals(listOf("Casa", "Casa-Guest", "Casa-IoT"), s.ssids)
    }

    @Test
    fun `the address, the protocol and the counts come off the config`() {
        val s = Parsers.resetSummary(parts())
        assertEquals("192.168.0.1", s.lanAddress)
        assertEquals("pppoe", s.wanProto)
        assertEquals(2, s.forwards)
        assertEquals(3, s.rules)
        assertEquals(2, s.reservations)
        assertEquals(listOf("wireguard-tools", "map", "vnstat"), s.packages)
    }

    @Test
    fun `a CIDR ipaddr still reads as an address`() {
        val s = Parsers.resetSummary(parts(network = "network.lan.ipaddr='10.0.0.1/24'"))
        assertEquals("10.0.0.1", s.lanAddress)
    }

    @Test
    fun `zones and forwardings are not counted as rules or forwards`() {
        val s = Parsers.resetSummary(parts())
        // firewall.@forwarding[0] is zone-to-zone policy, not a port forward.
        assertEquals(2, s.forwards)
    }

    @Test
    fun `a router with nothing configured summarises to nothing`() {
        val s = Parsers.resetSummary(emptyMap())
        assertTrue(s.ssids.isEmpty())
        assertEquals(null, s.lanAddress)
        assertEquals(0, s.rules)
        assertTrue(s.packages.isEmpty())
    }
}

class ResetLossTest {

    private fun flat(line: List<LossText>) = line.joinToString("") {
        when (it) {
            is LossText.Plain -> it.text
            is LossText.Mono -> it.text
            is LossText.Strong -> it.text
        }
    }

    @Test
    fun `the four design lines are built from the config`() {
        val lines = ResetStore.losses(Parsers.resetSummary(parts())).map(::flat)
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("Casa · Casa-Guest · Casa-IoT"))
        assertTrue(lines[0].endsWith("radios come back disabled"))
        assertTrue(lines[1].contains("192.168.0.1"))
        assertTrue(lines[1].contains("192.168.1.1"))
        assertTrue(lines[1].contains("PPPoE login, 2 forwards, 3 rules, 2 reservations"))
        assertTrue(lines[2].contains("host key"))
        assertTrue(lines[3].startsWith("3 packages you installed: wireguard-tools, map, vnstat"))
    }

    @Test
    fun `values are set in mono so the sentence and the fact stay apart`() {
        val wifi = ResetStore.losses(Parsers.resetSummary(parts())).first()
        assertEquals("Casa · Casa-Guest · Casa-IoT", wifi.filterIsInstance<LossText.Mono>().single().text)
        assertEquals("disabled", wifi.filterIsInstance<LossText.Strong>().single().text)
    }

    @Test
    fun `a router with no Wi-Fi and no packages does not get empty lines`() {
        val lines = ResetStore.losses(
            Parsers.resetSummary(parts(wireless = "", packages = ""))
        ).map(::flat)
        assertEquals(2, lines.size)
        assertFalse(lines.any { it.contains("Wi-Fi networks") })
        assertFalse(lines.any { it.contains("packages you installed") })
    }

    @Test
    fun `a router already on the default address is not told it moves`() {
        val lines = ResetStore.losses(
            Parsers.resetSummary(parts(network = "network.lan.ipaddr='192.168.1.1'"))
        ).map(::flat)
        val lan = lines.single { it.contains("rules") }
        assertFalse(lan.contains("→"))
        assertTrue(lan.startsWith("2 forwards"))
    }

    @Test
    fun `one package is not called one packages`() {
        val lines = ResetStore.losses(
            Parsers.resetSummary(parts(packages = "vnstat 2.9\n"))
        ).map(::flat)
        assertTrue(lines.any { it.startsWith("1 package you installed: vnstat") })
    }

    @Test
    fun `the credentials line is there even when nothing else could be read`() {
        val lines = ResetStore.losses(Parsers.resetSummary(emptyMap())).map(::flat)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("Root password"))
    }

    @Test
    fun `nothing at all is offered when the config was never read`() {
        assertTrue(ResetStore.losses(null).isEmpty())
    }
}

class FactoryResetCommandTest {

    @Test
    fun `the reset detaches, because the reboot takes the link with it`() {
        assertTrue(Commands.FACTORY_RESET.contains("firstboot -y && reboot"))
        assertTrue(Commands.FACTORY_RESET.contains("&"))
        assertTrue(Commands.FACTORY_RESET.trimEnd().endsWith("echo resetting"))
    }

    @Test
    fun `the summary reads config and never writes it`() {
        assertFalse(Commands.RESET_SUMMARY.contains("uci set"))
        assertFalse(Commands.RESET_SUMMARY.contains("uci commit"))
        listOf("wireless", "network", "firewall", "dhcp", "packages").forEach {
            assertTrue(it, Commands.RESET_SUMMARY.contains("${Commands.SECTION} $it"))
        }
    }
}
