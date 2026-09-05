package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.WanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ubus call network.interface dump` on a PPPoE line with a dual-stack companion: the WAN is
 * up on a tagged device, wan6 holds the delegated prefix, and the LAN has no default route.
 */
internal val WAN_DUMP = """
{"interface":[
 {"interface":"lan","up":true,"available":true,"proto":"static","device":"br-lan",
  "l3_device":"br-lan","uptime":184523,
  "ipv4-address":[{"address":"192.168.1.1","mask":24}],
  "route":[{"target":"192.168.1.0","mask":24,"nexthop":"0.0.0.0"}]},
 {"interface":"wan","up":true,"available":true,"proto":"pppoe","device":"eth1.201",
  "l3_device":"pppoe-wan","uptime":1571052,"metric":0,
  "ipv4-address":[{"address":"82.44.19.7","mask":32}],
  "dns-server":["8.8.8.8","1.1.1.1"],
  "route":[{"target":"0.0.0.0","mask":0,"nexthop":"10.64.64.64","source":""}]},
 {"interface":"wan6","up":true,"available":true,"proto":"dhcpv6","device":"eth1.201",
  "l3_device":"pppoe-wan","uptime":1571040,"metric":0,
  "ipv6-address":[{"address":"2a02:8071:b3c::1","mask":128}],
  "ipv6-prefix":[{"address":"2a02:8071:b3c::","mask":56}],
  "route":[{"target":"::","mask":0,"nexthop":"fe80::1"}]},
 {"interface":"wwan","up":false,"available":true,"proto":"dhcp","device":"phy0-sta0",
  "l3_device":"phy0-sta0","uptime":0,"metric":20}
]}
""".trimIndent()

internal val WAN_NETWORK_UCI = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.1'
    network.lan.netmask='255.255.255.0'
    network.wan_vlan=device
    network.wan_vlan.name='eth1.201'
    network.wan_vlan.type='8021q'
    network.wan_vlan.ifname='eth1'
    network.wan_vlan.vid='201'
    network.wan=interface
    network.wan.device='eth1.201'
    network.wan.proto='pppoe'
    network.wan.username='bb4019822@airtel'
    network.wan.password='hunter2'
    network.wan.keepalive='5 1'
    network.wan.mtu='1492'
    network.wan6=interface
    network.wan6.device='eth1.201'
    network.wan6.proto='dhcpv6'
    network.wan6.reqprefix='56'
    network.wwan=interface
    network.wwan.proto='dhcp'
    network.wwan.device='phy0-sta0'
    network.wwan.metric='20'
""".trimIndent()

internal val WAN_FIREWALL_UCI = """
    firewall.@zone[0]=zone
    firewall.@zone[0].name='lan'
    firewall.@zone[0].network='lan'
    firewall.@zone[1]=zone
    firewall.@zone[1].name='wan'
    firewall.@zone[1].network='wan' 'wan6' 'wwan'
""".trimIndent()

/** `ls /lib/netifd/proto` on a router with PPPoE but no MAP-E. */
internal val PROTO_LS = """
    dhcp.sh
    dhcpv6.sh
    none.sh
    ppp.sh
    pppoe.sh
    static.sh
""".trimIndent()

class WanLinkTest {

    private val links = Parsers.wanLinks(WAN_DUMP)

    @Test
    fun `every interface but loopback comes back`() {
        assertEquals(listOf("lan", "wan", "wan6", "wwan"), links.map { it.name })
    }

    @Test
    fun `the pppoe wan carries its address and its default route`() {
        val wan = links.single { it.name == "wan" }
        assertTrue(wan.up)
        assertEquals("pppoe", wan.proto)
        // l3_device is what traffic actually leaves on, not the configured device.
        assertEquals("pppoe-wan", wan.device)
        assertEquals("82.44.19.7", wan.address)
        assertEquals("82.44.19.7/32", wan.cidr)
        assertTrue(wan.hasDefaultRoute)
        assertEquals("10.64.64.64", wan.gateway)
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), wan.dns)
    }

    /** A LAN route is not a default route, however up the interface is. */
    @Test
    fun `an interface with only a subnet route is not an uplink`() {
        val lan = links.single { it.name == "lan" }
        assertFalse(lan.hasDefaultRoute)
        assertEquals("", lan.gateway)
    }

    /** The delegated prefix is a different field from the interface's own address. */
    @Test
    fun `the delegated prefix comes off wan6`() {
        val wan6 = links.single { it.name == "wan6" }
        assertEquals("2a02:8071:b3c::/56", wan6.v6Prefix)
        assertEquals("2a02:8071:b3c::1/128", wan6.v6Address)
        assertTrue(wan6.hasDefaultRoute)
    }

    @Test
    fun `a down interface keeps its metric`() {
        val wwan = links.single { it.name == "wwan" }
        assertFalse(wwan.up)
        assertEquals(20, wwan.metric)
        assertFalse(wwan.hasDefaultRoute)
    }

    @Test
    fun `an empty reply is no links`() {
        assertEquals(emptyList<WanLink>(), Parsers.wanLinks("{}"))
        assertEquals(emptyList<WanLink>(), Parsers.wanLinks(""))
    }
}

class WanConfigTest {

    private val uci = Parsers.uciShow(WAN_NETWORK_UCI)

    @Test
    fun `a pppoe interface reads out of uci`() {
        val wan = Parsers.wanConfig(uci, "wan")!!
        assertEquals("pppoe", wan.proto)
        assertEquals("eth1.201", wan.device)
        assertEquals("bb4019822@airtel", wan.username)
        assertEquals("hunter2", wan.password)
        assertEquals("5 1", wan.keepalive)
        assertEquals("1492", wan.mtu)
    }

    @Test
    fun `the v6 companion carries the requested prefix length`() {
        assertEquals("56", Parsers.wanConfig(uci, "wan6")!!.reqprefix)
    }

    @Test
    fun `a device section is not an interface`() {
        assertNull(Parsers.wanConfig(uci, "wan_vlan"))
    }

    @Test
    fun `the 8021q device section reads back whole`() {
        val device = Parsers.netDevices(uci).single { it.name == "eth1.201" }
        assertEquals("8021q", device.type)
        assertEquals("eth1", device.ifname)
        assertEquals("201", device.vid)
        assertEquals("wan_vlan", device.section)
    }
}

class ProtoHandlerTest {

    private val protos = Parsers.protoHandlers(PROTO_LS)

    /** netifd can only run a protocol whose handler script is installed. */
    @Test
    fun `the handler list is what the router can actually do`() {
        assertTrue("pppoe" in protos)
        assertTrue("dhcpv6" in protos)
        assertFalse("map" in protos)
        assertFalse("dslite" in protos)
    }

    /** static and dhcp are built into netifd itself, script or no script. */
    @Test
    fun `the built-ins are always available`() {
        val empty = Parsers.protoHandlers("")
        assertTrue("static" in empty)
        assertTrue("dhcp" in empty)
        assertTrue("none" in empty)
    }

    @Test
    fun `a package name is named for the missing ones`() {
        assertEquals("map", WanStore.protoPackage("map"))
        assertEquals("ds-lite", WanStore.protoPackage("dslite"))
        assertEquals("ppp-mod-pppoe", WanStore.protoPackage("pppoe"))
    }
}

class PingParseTest {

    private val good = """
        PING 1.1.1.1 (1.1.1.1): 56 data bytes

        --- 1.1.1.1 ping statistics ---
        3 packets transmitted, 3 packets received, 0% packet loss
        round-trip min/avg/max = 12.1/14.7/18.2 ms
    """.trimIndent()

    @Test
    fun `loss and average rtt come off the summary`() {
        val result = Parsers.pingResult(good, "1.1.1.1", "1.1.1.1")
        assertEquals(0, result.lossPct)
        assertEquals(14.7, result.rttMs!!, 0.01)
        assertTrue(result.ok)
        assertNull(result.error)
    }

    /** Total loss is a reachable stack with nothing answering — not a parse failure. */
    @Test
    fun `total loss is reported as loss`() {
        val text = """
            --- 8.8.8.8 ping statistics ---
            3 packets transmitted, 0 packets received, 100% packet loss
        """.trimIndent()
        val result = Parsers.pingResult(text, "8.8.8.8", "8.8.8.8")
        assertEquals(100, result.lossPct)
        assertNull(result.rttMs)
        assertFalse(result.ok)
    }

    /**
     * The two failures worth telling apart: a name that will not resolve means DNS is broken
     * while the line may be fine, and no route means the line itself is down.
     */
    @Test
    fun `resolution and routing failures are named`() {
        assertEquals(
            "cannot resolve",
            Parsers.pingResult("ping: bad address 'openwrt.org'", "dns name", "openwrt.org").error,
        )
        assertEquals(
            "no route",
            Parsers.pingResult("ping: sendto: Network unreachable", "gateway", "10.0.0.1").error,
        )
    }

    @Test
    fun `no output at all is not a pass`() {
        val result = Parsers.pingResult("", "gateway", "—")
        assertFalse(result.ok)
        assertEquals("no output", result.error)
    }
}

class WanCommandTest {

    @Test
    fun `the wan read is one script with every section`() {
        listOf("net", "fw", "dhcp", "dump", "links", "protos").forEach {
            assertTrue(it, Commands.WAN_STATE.contains("echo ${Commands.SECTION} $it"))
        }
        assertTrue(Commands.WAN_STATE.contains("ls /lib/netifd/proto"))
    }

    @Test
    fun `the test pings the gateway, two resolvers and a name`() {
        val script = Commands.pingTest("10.64.64.64", "eth1")
        assertTrue(script.contains("ping -c 3 -W 2 -q -I 'eth1' '10.64.64.64'"))
        assertTrue(script.contains("'1.1.1.1'"))
        assertTrue(script.contains("'8.8.8.8'"))
        assertTrue(script.contains("'openwrt.org'"))
    }

    /**
     * A standby uplink holds no default route. This used to ping loopback and label the
     * reply "gateway", which reported a healthy gateway for a link that has none.
     */
    @Test
    fun `a missing gateway is left out rather than faked with loopback`() {
        val script = Commands.pingTest("", "eth1")
        assertFalse(script.contains("127.0.0.1"))
        assertFalse(script.contains("${Commands.SECTION} gw"))
        assertTrue(script.contains("'1.1.1.1'"))
    }

    /**
     * The order is the whole point: the copy and the watcher have to be in place BEFORE the
     * batch runs, or a change that kills the link has nothing to undo it.
     */
    @Test
    fun `the rollback is armed before the batch`() {
        val script = Commands.wanApply(listOf("set network.wan.proto='pppoe'"), Commands.ifup("wan"), 30)
        val copy = script.indexOf("cp /etc/config/network")
        val watcher = script.indexOf("sleep 30")
        val batch = script.indexOf("uci batch")
        assertTrue(copy in 0 until batch)
        assertTrue(watcher in 0 until batch)
        // Detached, because it has to outlive the connection it was issued on.
        assertTrue(script.contains(") >/dev/null 2>&1 &"))
        assertTrue(script.contains("uci commit network"))
    }

    @Test
    fun `the watcher stands down only for a confirm file`() {
        val script = Commands.wanApply(emptyList(), Commands.NETWORK_RELOAD, 30)
        assertTrue(script.contains("[ -f ${Commands.ROLLBACK_DIR}/confirm ] && exit 0"))
        assertTrue(Commands.WAN_CONFIRM.contains("touch ${Commands.ROLLBACK_DIR}/confirm"))
    }

    @Test
    fun `uptime reads the way the hub shows it`() {
        assertEquals("up 18 d 04:12", WanStore.uptimeLabel(18 * 86_400 + 4 * 3_600 + 12 * 60))
        assertEquals("up 02:05", WanStore.uptimeLabel(2 * 3_600 + 5 * 60))
        assertEquals("—", WanStore.uptimeLabel(0))
    }
}
