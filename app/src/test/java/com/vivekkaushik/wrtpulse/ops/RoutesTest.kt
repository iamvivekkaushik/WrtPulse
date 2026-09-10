package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `uci show network` with every route shape the app has to read: an anonymous IPv4 route, a
 * named blackhole, a pre-CIDR `target` + `netmask` pair, and a route6.
 */
internal val ROUTES_NETWORK_UCI = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.0.1'
    network.lan.netmask='255.255.255.0'
    network.wan=interface
    network.wan.device='eth0.2'
    network.wan.proto='dhcp'
    network.loopback=interface
    network.loopback.device='lo'
    network.@route[0]=route
    network.@route[0].interface='lan'
    network.@route[0].target='10.20.0.0/16'
    network.@route[0].gateway='192.168.0.2'
    network.@route[0].metric='5'
    network.vpn_black=route
    network.vpn_black.interface='lan'
    network.vpn_black.target='10.9.0.0/16'
    network.vpn_black.type='blackhole'
    network.@route[1]=route
    network.@route[1].interface='wan'
    network.@route[1].target='10.30.0.0'
    network.@route[1].netmask='255.255.0.0'
    network.@route[1].gateway='192.168.68.1'
    network.@route6[0]=route6
    network.@route6[0].interface='wan'
    network.@route6[0].target='2001:db8::/32'
    network.@route6[0].gateway='fe80::1'
""".trimIndent()

/** `ip -4 route show` on the same router: two defaults, the static routes, and the link routes. */
internal val IP_ROUTE_V4 = """
    default via 192.168.68.1 dev eth0.2 proto static src 192.168.68.115 metric 10
    default via 10.143.245.33 dev phy1-sta0 proto static src 10.143.245.231 metric 30
    10.20.0.0/16 via 192.168.0.2 dev br-lan proto static metric 5
    blackhole 10.9.0.0/16 proto static
    10.143.245.0/24 dev phy1-sta0 proto static scope link metric 30
    192.168.0.0/24 dev br-lan proto kernel scope link src 192.168.0.1
    192.168.68.0/24 dev eth0.2 proto static scope link metric 10
    unreachable 10.50.0.0/16 proto static
""".trimIndent()

internal val IP_ROUTE_V6 = """
    2001:db8::/32 via fe80::1 dev eth0.2 proto static metric 1024 pref medium
    fe80::/64 dev br-lan proto kernel metric 256 pref medium
    default via fe80::1 dev eth0.2 proto static metric 512 pref medium
""".trimIndent()

/** `ubus call network.interface dump`, cut to what the route screen reads: device and subnet. */
internal val ROUTES_DUMP = """
{"interface":[
 {"interface":"lan","up":true,"available":true,"proto":"static","device":"br-lan",
  "l3_device":"br-lan","uptime":100,
  "ipv4-address":[{"address":"192.168.0.1","mask":24}],"route":[]},
 {"interface":"wan","up":true,"available":true,"proto":"dhcp","device":"eth0.2",
  "l3_device":"eth0.2","uptime":50,"metric":10,
  "ipv4-address":[{"address":"192.168.68.115","mask":24}],
  "route":[{"target":"0.0.0.0","mask":0,"nexthop":"192.168.68.1","source":""}]}
]}
""".trimIndent()

class StaticRouteParseTest {

    private val routes = Parsers.staticRoutes(Parsers.uciShow(ROUTES_NETWORK_UCI))

    @Test
    fun `every route section comes back in file order`() {
        assertEquals(listOf("@route[0]", "vpn_black", "@route[1]", "@route6[0]"), routes.map { it.section })
        assertEquals(listOf(false, false, false, true), routes.map { it.ipv6 })
    }

    @Test
    fun `a route carries its options and defaults to unicast`() {
        val r = routes[0]
        assertEquals("10.20.0.0/16", r.target)
        assertEquals("192.168.0.2", r.gateway)
        assertEquals("lan", r.iface)
        assertEquals("5", r.metric)
        assertEquals("unicast", r.type)
        assertFalse(r.disabled)
        assertEquals(setOf("interface", "target", "gateway", "metric"), r.options)
    }

    @Test
    fun `a blackhole keeps its type`() {
        assertEquals("blackhole", routes[1].type)
        assertEquals("", routes[1].gateway)
    }

    /** Pre-CIDR configs split the mask out; the app folds it back in and remembers it was there. */
    @Test
    fun `a legacy netmask is folded into the target`() {
        val legacy = routes[2]
        assertEquals("10.30.0.0/16", legacy.target)
        assertTrue("netmask" in legacy.options)
    }

    @Test
    fun `a board with no routes has none`() {
        assertEquals(emptyList<StaticRoute>(), Parsers.staticRoutes(Parsers.uciShow("network.lan=interface")))
    }
}

class KernelRouteParseTest {

    private val v4 = Parsers.kernelRoutes(IP_ROUTE_V4, ipv6 = false)
    private val v6 = Parsers.kernelRoutes(IP_ROUTE_V6, ipv6 = true)

    @Test
    fun `every line is a route and default is normalised`() {
        assertEquals(8, v4.size)
        val default = v4[0]
        assertEquals("0.0.0.0/0", default.dst)
        assertEquals("192.168.68.1", default.via)
        assertEquals("eth0.2", default.dev)
        assertEquals(10, default.metric)
        assertEquals("static", default.proto)
        assertEquals("192.168.68.115", default.src)
        assertEquals("unicast", default.type)
    }

    @Test
    fun `special kinds are read off the front of the line`() {
        val black = v4.single { it.dst == "10.9.0.0/16" }
        assertEquals("blackhole", black.type)
        assertEquals("", black.dev)
        assertNull(black.metric)
        assertEquals("unreachable", v4.single { it.dst == "10.50.0.0/16" }.type)
    }

    @Test
    fun `a link route has a scope and no gateway`() {
        val link = v4.single { it.dst == "192.168.0.0/24" }
        assertEquals("", link.via)
        assertEquals("br-lan", link.dev)
        assertEquals("kernel", link.proto)
        assertEquals("link", link.scope)
        assertEquals("192.168.0.1", link.src)
    }

    @Test
    fun `ipv6 lines parse the same way`() {
        assertEquals(3, v6.size)
        assertEquals("::/0", v6[2].dst)
        assertEquals(512, v6[2].metric)
        assertEquals("fe80::1", v6[0].via)
        assertTrue(v6.all { it.ipv6 })
    }

    @Test
    fun `empty output is no routes`() {
        assertEquals(emptyList<KernelRoute>(), Parsers.kernelRoutes("", ipv6 = false))
        assertEquals(emptyList<KernelRoute>(), Parsers.kernelRoutes("\n\n", ipv6 = true))
    }
}
