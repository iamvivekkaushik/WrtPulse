package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.FirewallStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal val FIREWALL_UCI = """
    firewall.@defaults[0]=defaults
    firewall.@defaults[0].input='REJECT'
    firewall.@defaults[0].output='ACCEPT'
    firewall.@defaults[0].forward='REJECT'
    firewall.@defaults[0].syn_flood='1'
    firewall.@zone[0]=zone
    firewall.@zone[0].name='lan'
    firewall.@zone[0].network='lan' 'lan2'
    firewall.@zone[0].input='ACCEPT'
    firewall.@zone[0].output='ACCEPT'
    firewall.@zone[0].forward='ACCEPT'
    firewall.@zone[1]=zone
    firewall.@zone[1].name='wan'
    firewall.@zone[1].network='wan' 'wan6'
    firewall.@zone[1].input='REJECT'
    firewall.@zone[1].output='ACCEPT'
    firewall.@zone[1].forward='REJECT'
    firewall.@zone[1].masq='1'
    firewall.@zone[1].mtu_fix='1'
    firewall.guest=zone
    firewall.guest.name='guest'
    firewall.guest.network='guest'
    firewall.@forwarding[0]=forwarding
    firewall.@forwarding[0].src='lan'
    firewall.@forwarding[0].dest='wan'
    firewall.@rule[0]=rule
    firewall.@rule[0].name='Allow-Ping'
    firewall.@rule[0].src='wan'
    firewall.@rule[0].proto='icmp'
    firewall.@rule[0].icmp_type='echo-request'
    firewall.@rule[0].target='ACCEPT'
    firewall.kids=rule
    firewall.kids.name='Kids tablet — night cutoff'
    firewall.kids.src='lan'
    firewall.kids.src_ip='192.168.1.62'
    firewall.kids.dest='wan'
    firewall.kids.target='REJECT'
    firewall.kids.weekdays='Mon Tue Wed Thu Fri'
    firewall.kids.start_time='21:00'
    firewall.kids.stop_time='07:00'
    firewall.@redirect[0]=redirect
    firewall.@redirect[0].name='Home Assistant'
    firewall.@redirect[0].src='wan'
    firewall.@redirect[0].src_dport='8123'
    firewall.@redirect[0].dest='lan'
    firewall.@redirect[0].dest_ip='192.168.1.10'
    firewall.@redirect[0].dest_port='8123'
    firewall.@redirect[0].proto='tcp'
    firewall.@redirect[0].target='DNAT'
    firewall.@redirect[1]=redirect
    firewall.@redirect[1].name='Plex'
    firewall.@redirect[1].src='wan'
    firewall.@redirect[1].src_dport='32400'
    firewall.@redirect[1].dest_ip='192.168.1.10'
    firewall.@redirect[1].proto='tcp'
    firewall.@redirect[1].target='DNAT'
    firewall.@redirect[1].enabled='0'
    firewall.@redirect[2]=redirect
    firewall.@redirect[2].name='Masq exception'
    firewall.@redirect[2].src='lan'
    firewall.@redirect[2].target='SNAT'
    firewall.@redirect[2].src_dip='10.0.0.1'
""".trimIndent()

private fun config() = Parsers.firewallConfig(Parsers.uciShow(FIREWALL_UCI))

class FirewallConfigTest {

    @Test
    fun `defaults come through with their flags`() {
        val d = config().defaults
        assertEquals("REJECT", d.input)
        assertEquals("ACCEPT", d.output)
        assertTrue(d.synFlood)
        assertFalse(d.dropInvalid)
    }

    @Test
    fun `zones keep their order, networks, and NAT flags`() {
        val zones = config().zones
        assertEquals(listOf("lan", "wan", "guest"), zones.map { it.name })
        assertEquals(listOf("wan", "wan6"), zones[1].networks)
        assertTrue(zones[1].masq)
        assertTrue(zones[1].mtuFix)
        assertFalse(zones[0].masq)
    }

    @Test
    fun `a zone without its own policies inherits the defaults`() {
        val guest = config().zones.single { it.name == "guest" }
        assertEquals("REJECT", guest.input)
        assertEquals("REJECT", guest.forward)
        assertEquals("ACCEPT", guest.output)
    }

    @Test
    fun `only DNAT redirects are forwards, and enabled defaults to on`() {
        val f = config().forwards
        assertEquals(listOf("Home Assistant", "Plex"), f.map { it.name })
        assertTrue(f[0].enabled)
        assertFalse(f[1].enabled)
        assertEquals("8123", f[0].destPort)
        assertEquals("", f[1].destPort)
    }

    @Test
    fun `rules carry fw4's schedule options`() {
        val kids = config().rules.single { it.section == "kids" }
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), kids.weekdays)
        assertEquals("21:00", kids.startTime)
        assertEquals("07:00", kids.stopTime)
        assertTrue(kids.scheduled)
        assertEquals("192.168.1.62", kids.srcIp)
        assertFalse(config().rules.single { it.name == "Allow-Ping" }.scheduled)
    }

    @Test
    fun `forwardings are the inter-zone matrix`() {
        val fw = config().forwardings.single()
        assertEquals("lan", fw.src)
        assertEquals("wan", fw.dest)
    }

    @Test
    fun `engine state reads fw4, running, and the reload age`() {
        val e = Parsers.firewallEngine(
            mapOf(
                "service" to """{ "firewall": { "instances": { "instance1": { "running": true } } } }""",
                "engine" to "fw4\n",
                "reloaded" to "1000",
                "now" to "1180",
            )
        )
        assertTrue(e.running)
        assertEquals("fw4", e.engine)
        assertEquals(180L, e.reloadedAgoSec)
    }

    @Test
    fun `an absent state file means no reload age rather than a huge one`() {
        val e = Parsers.firewallEngine(mapOf("service" to "{}", "engine" to "fw3", "reloaded" to "", "now" to "1180", "active" to "inactive"))
        assertFalse(e.running)
        assertNull(e.reloadedAgoSec)
    }

    @Test
    fun `fw4 counts as running when its table is loaded, procd notwithstanding`() {
        // fw4 is not a daemon: service list shows nothing running on a perfectly healthy router.
        val e = Parsers.firewallEngine(mapOf("service" to """{"firewall":{"instances":{}}}""", "engine" to "fw4", "active" to "active\n"))
        assertTrue(e.running)
        assertFalse(Parsers.firewallEngine(mapOf("service" to "{}", "engine" to "fw4", "active" to "inactive")).running)
    }

    @Test
    fun `listening ports come off netstat's address column`() {
        val ports = Parsers.listeningPorts("0.0.0.0:22\n:::22\n127.0.0.1:53\n192.168.1.1:80\n")
        assertEquals(setOf(22, 53, 80), ports)
    }

    @Test
    fun `clock minutes accept HH-MM and reject the rest`() {
        assertEquals(21 * 60, Parsers.clockMinutes("21:00"))
        assertEquals(7 * 60 + 30, Parsers.clockMinutes("07:30"))
        assertEquals(60, Parsers.clockMinutes("01:00:00"))
        assertNull(Parsers.clockMinutes("25:00"))
        assertNull(Parsers.clockMinutes("9pm"))
    }
}

class DmzRangeTest {

    @Test
    fun `no exceptions is one range over every port`() {
        assertEquals(listOf(1 to 65535), FirewallStore.dmzRanges(emptyList()))
    }

    @Test
    fun `each excepted port splits the range around it`() {
        assertEquals(
            listOf(1 to 21, 23 to 8122, 8124 to 65535),
            FirewallStore.dmzRanges(listOf(22, 8123)),
        )
    }

    @Test
    fun `edge ports and duplicates do not produce empty ranges`() {
        assertEquals(listOf(2 to 65534), FirewallStore.dmzRanges(listOf(1, 65535, 1)))
    }

    @Test
    fun `saved ranges read back as the except list`() {
        val saved = FirewallStore.dmzRanges(listOf(22, 8123)).mapIndexed { i, (a, b) ->
            Parsers.FwForward("wrtpulse_dmz_${i + 1}", "DMZ", "wan", "lan", "tcp udp", "$a-$b", "192.168.1.50", "", true)
        }
        assertEquals(listOf(22, 8123), FirewallStore.savedDmzExcept(saved))
    }

    @Test
    fun `a DMZ written by hand with no port at all excepts nothing`() {
        val saved = listOf(Parsers.FwForward("@redirect[3]", "dmz", "wan", "lan", "tcp udp", "", "192.168.1.50", "", true))
        assertTrue(saved.single().isDmz)
        assertTrue(FirewallStore.savedDmzExcept(saved).isEmpty())
    }
}

class FirewallHelpersTest {

    @Test
    fun `protocol overlap treats any as overlapping everything`() {
        assertTrue(FirewallStore.protoOverlap("tcp", "tcp udp"))
        assertFalse(FirewallStore.protoOverlap("tcp", "udp"))
        assertTrue(FirewallStore.protoOverlap("", "udp"))
    }

    @Test
    fun `cidr check accepts an address or a prefix and nothing else`() {
        assertTrue(FirewallStore.cidrOk("192.168.30.0/24"))
        assertTrue(FirewallStore.cidrOk("10.0.0.5"))
        assertFalse(FirewallStore.cidrOk("192.168.30.0/33"))
        assertFalse(FirewallStore.cidrOk("kids-tablet"))
    }

    @Test
    fun `zone names become uci-safe section fragments`() {
        assertEquals("guest_net", FirewallStore.section("guest-net"))
        assertEquals("lan", FirewallStore.section("lan"))
    }

    @Test
    fun `the apply script copies the file first and arms a watcher that reloads`() {
        val script = Commands.firewallApply(listOf("set firewall.@defaults[0].syn_flood='1'"), seconds = 15)
        assertTrue(script.indexOf("cp /etc/config/firewall") < script.indexOf("uci batch"))
        assertTrue(script.contains("sleep 15"))
        assertTrue(script.contains("${Commands.FIREWALL_RELOAD}; echo rolled-back"))
        assertTrue(script.contains("uci commit firewall && ${Commands.FIREWALL_RELOAD}"))
        assertTrue(script.trimEnd().endsWith("echo applied"))
    }

    @Test
    fun `the state read never writes`() {
        assertFalse(Commands.FIREWALL_STATE.contains("uci set"))
        assertFalse(Commands.FIREWALL_STATE.contains("uci commit"))
        assertFalse(Commands.FIREWALL_STATE.contains(Commands.FIREWALL_RELOAD))
        assertFalse(Commands.FIREWALL_STATE.contains("fw4 reload"))
    }
}
