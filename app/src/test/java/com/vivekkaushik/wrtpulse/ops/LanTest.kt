package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `uci show network` on a DSA board: a bridge with named ports, two bridge VLANs, and the
 * LAN interface riding VLAN 1 rather than the bare bridge.
 */
internal val NETWORK_UCI = """
    network.loopback=interface
    network.loopback.device='lo'
    network.loopback.proto='static'
    network.loopback.ipaddr='127.0.0.1'
    network.loopback.netmask='255.0.0.0'
    network.globals=globals
    network.globals.ula_prefix='fd8e:1f4f:3c9d::/48'
    network.@device[0]=device
    network.@device[0].name='br-lan'
    network.@device[0].type='bridge'
    network.@device[0].ports='lan1' 'lan2' 'lan3' 'lan4'
    network.@bridge-vlan[0]=bridge-vlan
    network.@bridge-vlan[0].device='br-lan'
    network.@bridge-vlan[0].vlan='1'
    network.@bridge-vlan[0].ports='lan1:u*' 'lan2:u*' 'lan3:u*' 'lan4:t'
    network.@bridge-vlan[1]=bridge-vlan
    network.@bridge-vlan[1].device='br-lan'
    network.@bridge-vlan[1].vlan='10'
    network.@bridge-vlan[1].ports='lan3:u*' 'lan4:t'
    network.lan=interface
    network.lan.device='br-lan.1'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.1'
    network.lan.netmask='255.255.255.0'
    network.lan.ip6assign='60'
    network.lan.dns='9.9.9.9' '1.1.1.1'
    network.wan=interface
    network.wan.device='wan'
    network.wan.proto='dhcp'
""".trimIndent()

internal val DHCP_UCI = """
    dhcp.@dnsmasq[0]=dnsmasq
    dhcp.@dnsmasq[0].domainneeded='1'
    dhcp.@dnsmasq[0].localise_queries='1'
    dhcp.lan=dhcp
    dhcp.lan.interface='lan'
    dhcp.lan.start='100'
    dhcp.lan.limit='150'
    dhcp.lan.leasetime='12h'
    dhcp.lan.dhcp_option='6,9.9.9.9,1.1.1.1' '42,192.168.1.10'
    dhcp.wan=dhcp
    dhcp.wan.interface='wan'
    dhcp.wan.ignore='1'
    dhcp.cfg0a91b2=host
    dhcp.cfg0a91b2.name='synology-nas'
    dhcp.cfg0a91b2.mac='00:11:32:6F:B2:44'
    dhcp.cfg0a91b2.ip='192.168.1.10'
    dhcp.printer=host
    dhcp.printer.name='printer-hp'
    dhcp.printer.mac='84:2A:FD:1C:99:30'
    dhcp.printer.ip='192.168.1.61'
""".trimIndent()

/**
 * [Commands.NETDEVS] output on the same board: four sockets, a WAN, the conduit, and the
 * radios — whose netdevs OpenWrt 24.10 names phy0-ap0 rather than wlan0.
 */
internal val NETDEV_LINES = """
    br-lan up 1 - 02:11:22:33:44:55 virt wired
    br-lan.1 up 1 - 02:11:22:33:44:55 virt wired
    eth0 up 1 2500 02:11:22:33:44:55 phy wired
    lan1 up 1 1000 02:11:22:33:44:56 phy wired
    lan2 up 1 1000 02:11:22:33:44:57 phy wired
    lan3 down 0 - 02:11:22:33:44:58 phy wired
    lan4 down 0 -1 02:11:22:33:44:59 phy wired
    lo unknown 1 - 00:00:00:00:00:00 virt wired
    phy0-ap0 up 1 - 02:11:22:33:44:60 phy wifi
    phy1-ap0 up 1 - 02:11:22:33:44:61 phy wifi
    wan up 1 2500 02:11:22:33:44:5a phy wired
""".trimIndent()

internal val LAN_STATUS = """
    {"up":true,"pending":false,"available":true,"autostart":true,"dynamic":false,
     "uptime":184523,"l3_device":"br-lan.1","proto":"static","device":"br-lan.1",
     "ipv4-address":[{"address":"192.168.1.1","mask":24}],"ipv6-address":[]}
""".trimIndent()

/**
 * A stock config that spells the address as CIDR and carries no `netmask` at all — read off
 * a TP-Link Deco M4R v1 running 24.10, where it broke the first version of this screen.
 */
internal val NETWORK_UCI_CIDR = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.1/24'
    network.@switch_vlan[0]=switch_vlan
    network.@switch_vlan[0].device='switch0'
    network.@switch_vlan[0].vlan='1'
    network.@switch_vlan[0].ports='3 5 0t'
""".trimIndent()

class IpMathTest {

    @Test
    fun `dotted quads round-trip`() {
        val value = IpMath.parse("192.168.1.1")
        assertNotNull(value)
        assertEquals("192.168.1.1", IpMath.format(value!!))
    }

    @Test
    fun `an octet over 255 is not an address`() {
        assertNull(IpMath.parse("192.168.1.256"))
        assertNull(IpMath.parse("192.168.1"))
        assertNull(IpMath.parse("192.168.1.1.1"))
        assertNull(IpMath.parse("192.168.a.1"))
        assertNull(IpMath.parse(""))
    }

    /**
     * inet_aton reads a leading zero as octal, so 192.168.010.1 is 192.168.8.1 — never what
     * the person typing it meant. Refused rather than quietly reinterpreted.
     */
    @Test
    fun `leading zeros are refused rather than read as octal`() {
        assertNull(IpMath.parse("192.168.010.1"))
        assertNotNull(IpMath.parse("192.168.0.1"))
    }

    @Test
    fun `netmask and prefix convert both ways`() {
        assertEquals(24, IpMath.prefixOf("255.255.255.0"))
        assertEquals(16, IpMath.prefixOf("255.255.0.0"))
        assertEquals(30, IpMath.prefixOf("255.255.255.252"))
        assertEquals(0, IpMath.prefixOf("0.0.0.0"))
        assertEquals("255.255.255.0", IpMath.netmaskOf(24))
        assertEquals("255.255.254.0", IpMath.netmaskOf(23))
    }

    /** A mask with a hole in it is not one no matter how plausible it looks. */
    @Test
    fun `a non-contiguous mask has no prefix`() {
        assertNull(IpMath.prefixOf("255.0.255.0"))
        assertNull(IpMath.prefixOf("255.255.255.1"))
        assertNull(IpMath.prefixOf("nonsense"))
    }

    @Test
    fun `network and broadcast come off the prefix`() {
        val ip = IpMath.parse("192.168.1.134")!!
        assertEquals("192.168.1.0", IpMath.format(IpMath.networkOf(ip, 24)))
        assertEquals("192.168.1.255", IpMath.format(IpMath.broadcastOf(ip, 24)))
        assertEquals("192.168.0.0", IpMath.format(IpMath.networkOf(ip, 16)))
        assertEquals("192.168.255.255", IpMath.format(IpMath.broadcastOf(ip, 16)))
    }

    @Test
    fun `usable hosts excludes network and broadcast`() {
        assertEquals(254, IpMath.usableHosts(24))
        assertEquals(2, IpMath.usableHosts(30))
        assertEquals(0, IpMath.usableHosts(31))
    }

    /** dnsmasq counts start and limit off the network address, not off the router. */
    @Test
    fun `the pool is start through start plus limit minus one`() {
        val network = IpMath.parse("192.168.1.0")!!
        val range = IpMath.poolRange(network, 24, 100, 150)!!
        assertEquals("192.168.1.100", IpMath.format(range.first))
        assertEquals("192.168.1.249", IpMath.format(range.last))
    }

    /** A limit that runs off the end is clamped to the last host, not allowed to wrap. */
    @Test
    fun `a pool past the broadcast address stops at the last host`() {
        val network = IpMath.parse("192.168.1.0")!!
        val range = IpMath.poolRange(network, 24, 200, 150)!!
        assertEquals("192.168.1.254", IpMath.format(range.last))
    }

    @Test
    fun `a pool starting past the subnet is no pool at all`() {
        val network = IpMath.parse("192.168.1.0")!!
        assertNull(IpMath.poolRange(network, 24, 255, 10))
        assertNull(IpMath.poolRange(network, 24, 0, 10))
        assertNull(IpMath.poolRange(network, 24, 100, 0))
    }

    /** The static range below the pool is where a reservation belongs. */
    @Test
    fun `the first free address prefers the static range`() {
        val network = IpMath.parse("192.168.1.0")!!
        val pool = IpMath.poolRange(network, 24, 100, 150)
        val taken = setOf("192.168.1.2", "192.168.1.3").map { IpMath.parse(it)!! }.toSet()
        assertEquals(
            "192.168.1.4",
            IpMath.firstFree(network, 24, taken, pool, IpMath.parse("192.168.1.1")!!),
        )
    }

    /** With the static range full it falls into the pool rather than answering nothing. */
    @Test
    fun `a full static range falls back to the pool`() {
        val network = IpMath.parse("192.168.1.0")!!
        val pool = IpMath.poolRange(network, 24, 100, 150)
        val taken = (2..99).map { IpMath.parse("192.168.1.$it")!! }.toSet()
        assertEquals(
            "192.168.1.100",
            IpMath.firstFree(network, 24, taken, pool, IpMath.parse("192.168.1.1")!!),
        )
    }

    @Test
    fun `the router's own address is never offered`() {
        val network = IpMath.parse("192.168.1.0")!!
        assertEquals(
            "192.168.1.2",
            IpMath.firstFree(network, 24, emptySet(), null, IpMath.parse("192.168.1.1")!!),
        )
    }

    /** A /16 has 65k addresses; the answer is at the bottom and the scan must not walk them. */
    @Test
    fun `the free scan is bounded on a wide subnet`() {
        val network = IpMath.parse("10.0.0.0")!!
        assertEquals(
            "10.0.0.2",
            IpMath.firstFree(network, 16, emptySet(), null, IpMath.parse("10.0.0.1")!!, scanLimit = 8),
        )
        // Nothing free inside the scan window is reported as nothing, not as a hang.
        val taken = (1..9).map { IpMath.parse("10.0.0.$it")!! }.toSet()
        assertNull(IpMath.firstFree(network, 16, taken, null, 0, scanLimit = 8))
    }
}

class UciListTest {

    /** `uci show` prints a list as `key='a' 'b'`, and uciShow strips only the outer pair. */
    @Test
    fun `a list value splits on the quote boundary`() {
        val uci = Parsers.uciShow(NETWORK_UCI)
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), Parsers.uciList(uci.getValue("network.lan.dns")))
    }

    @Test
    fun `a single value is a one-item list`() {
        assertEquals(listOf("lan"), Parsers.uciList("lan"))
        assertEquals(emptyList<String>(), Parsers.uciList(""))
    }

    /**
     * swconfig's ports are one string with spaces in it, not a list. Splitting on whitespace
     * would turn `0 1 2 5t` into four values and lose what the option means.
     */
    @Test
    fun `a string containing spaces stays one value`() {
        assertEquals(listOf("0 1 2 5t"), Parsers.uciList("0 1 2 5t"))
    }

    /** The firewall zone that used to match neither of its two networks. */
    @Test
    fun `a zone holding two networks matches both`() {
        val zones = Parsers.firewallZones(
            Parsers.uciShow(
                """
                firewall.@zone[1]=zone
                firewall.@zone[1].name='wan'
                firewall.@zone[1].network='wan' 'wan6'
                """.trimIndent()
            )
        )
        assertEquals(listOf("wan", "wan6"), zones.single().networks)
    }
}

class NetDevTest {

    private val devs = Parsers.netdevs(NETDEV_LINES)

    @Test
    fun `each sysfs line becomes a netdev`() {
        assertEquals(11, devs.size)
        val lan1 = devs.single { it.name == "lan1" }
        assertTrue(lan1.carrier)
        assertEquals(1000, lan1.speedMbps)
        assertEquals("02:11:22:33:44:56", lan1.mac)
        assertTrue(lan1.physical)
    }

    @Test
    fun `a down port has no speed`() {
        assertNull(devs.single { it.name == "lan3" }.speedMbps)
        assertFalse(devs.single { it.name == "lan3" }.carrier)
    }

    /** Some drivers answer -1 for a dead port instead of failing the read. */
    @Test
    fun `a negative speed is not a speed`() {
        assertNull(devs.single { it.name == "lan4" }.speedMbps)
    }

    @Test
    fun `bridges and vlan netdevs are not physical`() {
        assertFalse(devs.single { it.name == "br-lan" }.physical)
        assertFalse(devs.single { it.name == "br-lan.1" }.physical)
    }

    /** The sockets on the case, in the order they are printed on it. */
    @Test
    fun `switch ports are the named ports, uplink first`() {
        assertEquals(
            listOf("wan", "lan1", "lan2", "lan3", "lan4"),
            Parsers.switchPorts(devs).map { it.name },
        )
    }

    /**
     * eth0 is the conduit to the switch: permanently up, nothing to plug in. Counting it
     * would make the card's "up · down" tally wrong on every DSA board.
     */
    @Test
    fun `the DSA conduit is not offered as a port`() {
        assertFalse(Parsers.switchPorts(devs).any { it.name == "eth0" })
    }

    /**
     * A radio's netdev is hardware with a sysfs `device` link, so the "is it physical" test
     * alone put phy0-ap0 in the switch-port row — three of the four "ports" on a board that
     * has one.
     */
    @Test
    fun `radios are not switch ports whatever they are called`() {
        assertTrue(devs.single { it.name == "phy0-ap0" }.wireless)
        assertFalse(Parsers.switchPorts(devs).any { it.wireless })
        val old = Parsers.netdevs(
            """
            eth0 up 1 1000 02:00:00:00:00:02 phy wired
            wlan0 up 1 - 02:00:00:00:00:04 phy wifi
            """.trimIndent()
        )
        assertEquals(listOf("eth0"), Parsers.switchPorts(old).map { it.name })
    }

    /** On a board with no DSA names, eth0 and eth1 really are the ports. */
    @Test
    fun `a board without named ports falls back to its ethernets`() {
        val old = Parsers.netdevs(
            """
            br-lan up 1 - 02:00:00:00:00:01 virt wired
            eth0 up 1 1000 02:00:00:00:00:02 phy wired
            eth1 down 0 - 02:00:00:00:00:03 phy wired
            phy0-ap0 up 1 - 02:00:00:00:00:04 phy wifi
            """.trimIndent()
        )
        assertEquals(listOf("eth0", "eth1"), Parsers.switchPorts(old).map { it.name })
    }
}

class LanConfigTest {

    private val uci = Parsers.uciShow(NETWORK_UCI)

    @Test
    fun `the lan interface reads out of uci`() {
        val lan = Parsers.lanNet(uci)!!
        assertEquals("static", lan.proto)
        assertEquals("br-lan.1", lan.device)
        assertEquals("192.168.1.1", lan.ipaddr)
        assertEquals("255.255.255.0", lan.netmask)
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), lan.dns)
    }

    @Test
    fun `a missing section is null rather than an empty one`() {
        assertNull(Parsers.lanNet(uci, "guest"))
    }

    /**
     * netifd accepts `ipaddr '192.168.1.1/24'` with no netmask option, and stock configs use
     * it. Read as a plain address it is not one, which is how it first showed up: a red
     * "not an IPv4 address" against a perfectly good router.
     */
    @Test
    fun `a CIDR address is split from its prefix`() {
        val lan = Parsers.lanNet(Parsers.uciShow(NETWORK_UCI_CIDR))!!
        assertEquals("192.168.1.1", lan.ipaddr)
        assertEquals(24, lan.cidrPrefix)
        assertEquals("", lan.netmask)
    }

    @Test
    fun `a plain address has no embedded prefix`() {
        assertNull(Parsers.lanNet(uci)!!.cidrPrefix)
    }

    /** Older configs spell the same option `ifname`. */
    @Test
    fun `ifname is read where device is absent`() {
        val old = Parsers.uciShow(
            """
            network.lan=interface
            network.lan.ifname='br-lan'
            network.lan.proto='static'
            network.lan.ipaddr='192.168.2.1'
            """.trimIndent()
        )
        assertEquals("br-lan", Parsers.lanNet(old)!!.device)
    }

    @Test
    fun `netifd's own view carries the live address`() {
        val live = Parsers.interfaceStatus(LAN_STATUS)!!
        assertTrue(live.up)
        assertEquals("br-lan.1", live.device)
        assertEquals("192.168.1.1", live.address)
        assertEquals(24, live.prefix)
        assertEquals(184523, live.uptimeS)
    }

    @Test
    fun `an empty ubus reply is not a status`() {
        assertNull(Parsers.interfaceStatus("{}"))
        assertNull(Parsers.interfaceStatus(""))
    }
}

class DhcpConfigTest {

    private val uci = Parsers.uciShow(DHCP_UCI)

    @Test
    fun `the pool for an interface is found by its interface option`() {
        val pool = Parsers.dhcpPools(uci).single { it.interfaceName == "lan" }
        assertEquals("lan", pool.section)
        assertEquals(100, pool.start)
        assertEquals(150, pool.limit)
        assertEquals("12h", pool.leasetime)
        assertFalse(pool.ignore)
        assertEquals(listOf("6,9.9.9.9,1.1.1.1", "42,192.168.1.10"), pool.options)
    }

    @Test
    fun `a pool set to ignore reads as one`() {
        assertTrue(Parsers.dhcpPools(uci).single { it.interfaceName == "wan" }.ignore)
    }

    @Test
    fun `reservations come back address-sorted with lowercased macs`() {
        val resv = Parsers.reservations(uci)
        assertEquals(2, resv.size)
        val nas = resv.single { it.name == "synology-nas" }
        assertEquals("00:11:32:6f:b2:44", nas.mac)
        assertEquals("192.168.1.10", nas.ip)
    }

    /** A host section with neither a MAC nor an address is a stub, not a reservation. */
    @Test
    fun `an empty host section is dropped`() {
        val stub = Parsers.uciShow(
            """
            dhcp.cfg1=host
            dhcp.cfg1.name='placeholder'
            """.trimIndent()
        )
        assertEquals(emptyList<Reservation>(), Parsers.reservations(stub))
    }

    /** One host can carry several MACs; the first is the one a row can show. */
    @Test
    fun `a host with two macs reports the first`() {
        val two = Parsers.uciShow(
            """
            dhcp.dual=host
            dhcp.dual.mac='aa:bb:cc:dd:ee:01' 'aa:bb:cc:dd:ee:02'
            dhcp.dual.ip='192.168.1.9'
            """.trimIndent()
        )
        assertEquals("aa:bb:cc:dd:ee:01", Parsers.reservations(two).single().mac)
    }

    @Test
    fun `lease times parse into seconds`() {
        assertEquals(3600L, Parsers.leaseSeconds("1h"))
        assertEquals(43200L, Parsers.leaseSeconds("12h"))
        assertEquals(604800L, Parsers.leaseSeconds("7d"))
        assertEquals(120L, Parsers.leaseSeconds("2m"))
        assertEquals(90L, Parsers.leaseSeconds("90"))
        assertEquals(Long.MAX_VALUE, Parsers.leaseSeconds("infinite"))
        assertNull(Parsers.leaseSeconds("a while"))
    }
}

class BridgeVlanTest {

    private val uci = Parsers.uciShow(NETWORK_UCI)

    @Test
    fun `bridge vlans come back lowest id first`() {
        val vlans = Parsers.bridgeVlans(uci)
        assertEquals(listOf(1, 10), vlans.map { it.vlan })
        assertEquals("br-lan", vlans.first().device)
        assertEquals("br-lan.10", vlans.last().netdev)
    }

    /** `lan1:u*` is untagged and the port's PVID; `lan4:t` is tagged. */
    @Test
    fun `port tokens carry tagging and pvid`() {
        val vlan1 = Parsers.bridgeVlans(uci).first()
        val lan1 = vlan1.ports.single { it.name == "lan1" }
        assertFalse(lan1.tagged)
        assertTrue(lan1.pvid)
        val lan4 = vlan1.ports.single { it.name == "lan4" }
        assertTrue(lan4.tagged)
    }

    @Test
    fun `a port token round-trips through its rendering`() {
        listOf("lan1:u*", "lan2:t", "lan3:u").forEach { token ->
            assertEquals(token, Parsers.vlanPort(token).token())
        }
    }

    /** A bare port name with no flags is untagged without PVID. */
    @Test
    fun `a token with no flags is plain untagged`() {
        val port = Parsers.vlanPort("lan2")
        assertFalse(port.tagged)
        assertFalse(port.pvid)
    }

    @Test
    fun `swconfig vlans are read as their own thing`() {
        val old = Parsers.uciShow(
            """
            network.@switch[0]=switch
            network.@switch[0].name='switch0'
            network.@switch_vlan[0]=switch_vlan
            network.@switch_vlan[0].device='switch0'
            network.@switch_vlan[0].vlan='1'
            network.@switch_vlan[0].ports='0 1 2 3 6t'
            """.trimIndent()
        )
        val vlan = Parsers.switchVlans(old).single()
        assertEquals("switch0", vlan.device)
        assertEquals(1, vlan.vlan)
        assertEquals("0 1 2 3 6t", vlan.ports)
        // And nothing on such a board is mistaken for a DSA bridge VLAN.
        assertEquals(emptyList<BridgeVlan>(), Parsers.bridgeVlans(old))
    }
}

class LanCommandTest {

    /** The batch has to stay one round trip, with every section the store reads. */
    @Test
    fun `the lan read is one script with named sections`() {
        val script = Commands.lanState()
        listOf("net", "dhcp", "live", "leases", "neigh", "links", "dnsmasq").forEach {
            assertTrue(it, script.contains("echo ${Commands.SECTION} $it"))
        }
        assertTrue(script.contains("ubus call network.interface.lan status"))
    }

    /** `set` on a list option collapses it to one value, so the list is rebuilt instead. */
    @Test
    fun `a list is deleted then added back item by item`() {
        assertEquals(
            listOf(
                "delete network.lan.dns",
                "add_list network.lan.dns='9.9.9.9'",
                "add_list network.lan.dns='1.1.1.1'",
            ),
            Commands.listOps("network.lan.dns", listOf("9.9.9.9", "1.1.1.1")),
        )
    }

    @Test
    fun `an emptied list is just the delete`() {
        assertEquals(listOf("delete dhcp.lan.dhcp_option"), Commands.listOps("dhcp.lan.dhcp_option", emptyList()))
    }

    /**
     * The reply to a reload that moves the router's own address cannot come back over the
     * link it takes down, so that one is detached the way [Commands.REBOOT] is.
     */
    @Test
    fun `a reload that moves the address is detached`() {
        val moving = Commands.lanReload(network = true, dhcp = false, movesAddress = true)
        assertTrue(moving.contains("&"))
        assertTrue(moving.contains("sleep 1"))
        val staying = Commands.lanReload(network = true, dhcp = false, movesAddress = false)
        assertFalse(staying.contains("sleep 1"))
        assertTrue(staying.contains("/etc/init.d/network reload"))
    }

    @Test
    fun `dhcp-only work restarts dnsmasq and leaves netifd alone`() {
        val reload = Commands.lanReload(network = false, dhcp = true, movesAddress = false)
        assertTrue(reload.contains("dnsmasq restart"))
        assertFalse(reload.contains("network reload"))
    }

    @Test
    fun `a quote in a value cannot break out of it`() {
        assertEquals("it'\\''s", Commands.escapeValue("it's"))
    }
}
