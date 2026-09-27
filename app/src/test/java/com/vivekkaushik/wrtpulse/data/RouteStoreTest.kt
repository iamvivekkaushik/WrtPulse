package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.IP_ROUTE_V4
import com.vivekkaushik.wrtpulse.ops.IP_ROUTE_V6
import com.vivekkaushik.wrtpulse.ops.ROUTES_DUMP
import com.vivekkaushik.wrtpulse.ops.ROUTES_NETWORK_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun parts(network: String = ROUTES_NETWORK_UCI) = mapOf(
        "net" to network,
        "dump" to ROUTES_DUMP,
        "v4" to IP_ROUTE_V4,
        "v6" to IP_ROUTE_V6,
    )

    private fun store(network: String = ROUTES_NETWORK_UCI): RouteStore =
        RouteStore(RouterSession(SshTarget("192.168.0.1"), unusedClient, { error("unused") })).apply {
            ingest(parts(network))
        }

    // ---- reading ----

    @Test
    fun `the interfaces a route can use exclude loopback`() {
        assertEquals(listOf("lan", "wan"), store().interfaces)
    }

    /** The list is the config, and each row says whether the kernel actually carries it. */
    @Test
    fun `rows overlay the kernel table on the config`() {
        val rows = store().rows()
        assertEquals(listOf("@route[0]", "vpn_black", "@route[1]", "@route6[0]"), rows.map { it.section })
        assertNotNull(rows[0].live)                       // 10.20.0.0/16 via 192.168.0.2 dev br-lan
        assertEquals("br-lan", rows[0].live!!.dev)
        assertNotNull(rows[1].live)                       // the blackhole has no dev; matched on target
        assertEquals("blackhole", rows[1].live!!.type)
        assertNull(rows[2].live)                          // 10.30.0.0/16 is configured but not installed
        assertNotNull(rows[3].live)                       // the route6
    }

    @Test
    fun `the kernel table flags the entries static routes produced and puts defaults first`() {
        val k = store().kernelRows()
        assertEquals("0.0.0.0/0", k[0].first.dst)
        assertEquals(10, k[0].first.metric)
        assertEquals("0.0.0.0/0", k[1].first.dst)
        assertEquals(30, k[1].first.metric)
        assertEquals(3, k.count { it.second })
        assertTrue(k.single { it.first.dst == "10.20.0.0/16" }.second)
        assertFalse(k.single { it.first.dst == "192.168.0.0/24" }.second)
    }

    // ---- adding ----

    @Test
    fun `a staged route becomes a named section with only the options it sets`() {
        val s = store()
        val d = s.newDraft().copy(target = "10.99.0.0/24", gateway = "192.168.0.2", iface = "lan", metric = "50")
        assertNull(s.stageRoute(d))
        assertEquals(
            listOf(
                "set network.wrtpulse_route_1=route",
                "set network.wrtpulse_route_1.interface='lan'",
                "set network.wrtpulse_route_1.target='10.99.0.0/24'",
                "set network.wrtpulse_route_1.gateway='192.168.0.2'",
                "set network.wrtpulse_route_1.metric='50'",
            ),
            s.ops(),
        )
        val row = s.rows().last()
        assertTrue(row.isDraft)
        assertNull(row.live)
        assertEquals(5, s.rows().size)
        assertEquals("$ uci commit network && /etc/init.d/network reload", s.commitLine())
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `an ipv6 route is a route6 section`() {
        val s = store()
        val d = s.newDraft(ipv6 = true).copy(target = "2001:db8:1::/48", gateway = "fe80::1", iface = "wan")
        assertNull(s.stageRoute(d))
        assertTrue(s.ops().contains("set network.wrtpulse_route6_1=route6"))
        assertTrue(s.ops().contains("set network.wrtpulse_route6_1.gateway='fe80::1'"))
    }

    @Test
    fun `a blackhole writes its type and no gateway`() {
        val s = store()
        val d = s.newDraft().copy(target = "10.66.0.0/16", iface = "lan", type = "blackhole")
        assertNull(s.stageRoute(d))
        assertTrue(s.ops().contains("set network.wrtpulse_route_1.type='blackhole'"))
        assertFalse(s.ops().any { it.contains("gateway") })
    }

    @Test
    fun `the default interface is the lan`() {
        assertEquals("lan", store().newDraft().iface)
    }

    // ---- editing in place ----

    /**
     * An anonymous section has only its index for a name, so an edit rewrites it where it is:
     * deleting and re-adding would renumber every route after it.
     */
    @Test
    fun `editing a saved route rewrites the section in place`() {
        val s = store()
        val d = s.newDraft(s.routes[0]).copy(metric = "9")
        assertNull(s.stageRoute(d))
        assertEquals(listOf("set network.@route[0].metric='9'"), s.ops())
        val diff = s.diffLines().map { it.first }
        assertTrue(diff.contains("- network.@route[0].metric='5'"))
        assertTrue(diff.contains("+ network.@route[0].metric='9'"))
        // The edited route shows once, as itself.
        val rows = s.rows()
        assertEquals(4, rows.size)
        assertTrue(rows.single { it.section == "@route[0]" }.isDraft)
        assertEquals("9", rows.single { it.section == "@route[0]" }.metric)
        assertEquals(d, s.draftFor("@route[0]"))
    }

    @Test
    fun `an option removed by the edit is deleted`() {
        val s = store()
        val d = s.newDraft(s.routes[0]).copy(metric = "")
        assertNull(s.stageRoute(d))
        assertEquals(listOf("delete network.@route[0].metric"), s.ops())
    }

    /** Saving a pre-CIDR route moves the mask into the target and drops the old option. */
    @Test
    fun `editing a legacy route retires its netmask`() {
        val s = store()
        val d = s.newDraft(s.routes[2])
        assertEquals("10.30.0.0/16", d.target)
        assertNull(s.stageRoute(d))
        assertEquals(
            listOf("delete network.@route[1].netmask", "set network.@route[1].target='10.30.0.0/16'"),
            s.ops(),
        )
    }

    @Test
    fun `an edit supersedes a toggle on the same section`() {
        val s = store()
        s.toggleDisabled("vpn_black")
        assertNull(s.stageRoute(s.newDraft(s.routes[1]).copy(metric = "3")))
        assertEquals(listOf("set network.vpn_black.metric='3'"), s.ops())
    }

    // ---- deleting and toggling ----

    /** `delete @route[0]` renumbers `@route[1]`, so the batch removes from the top down, and last. */
    @Test
    fun `deletions run last and highest index first`() {
        val s = store()
        s.removeRoute("@route[0]")
        s.removeRoute("@route[1]")
        s.toggleDisabled("vpn_black")
        assertNull(s.stageRoute(s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")))
        val ops = s.ops()
        assertEquals("set network.vpn_black.disabled='1'", ops[0])
        assertTrue(ops[1].startsWith("set network.wrtpulse_route_1="))
        assertEquals(listOf("delete network.@route[1]", "delete network.@route[0]"), ops.takeLast(2))
        assertTrue(s.rows().single { it.section == "@route[0]" }.deleting)
    }

    @Test
    fun `deleting a route the kernel carries says what happens to its traffic`() {
        val s = store()
        s.removeRoute("@route[0]")
        assertTrue(s.warnings().any { it.contains("kernel carries 10.20.0.0/16") })
        s.undoDelete("@route[0]")
        s.removeRoute("@route[1]")
        assertTrue(s.warnings().isEmpty())
    }

    @Test
    fun `removing a draft drops it rather than staging a deletion`() {
        val s = store()
        assertNull(s.stageRoute(s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")))
        s.removeRoute("wrtpulse_route_1")
        assertEquals(0, s.pendingCount)
        assertTrue(s.deletions.isEmpty())
    }

    @Test
    fun `toggling disabled stages one flip and toggling back clears it`() {
        val s = store()
        s.toggleDisabled("vpn_black")
        assertEquals(listOf("set network.vpn_black.disabled='1'"), s.ops())
        assertTrue(s.rows().single { it.section == "vpn_black" }.disabled)
        s.toggleDisabled("vpn_black")
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `revert leaves nothing pending`() {
        val s = store()
        s.removeRoute("@route[0]")
        s.toggleDisabled("vpn_black")
        assertNull(s.stageRoute(s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")))
        s.revert()
        assertEquals(0, s.pendingCount)
        assertEquals(4, s.rows().size)
    }

    // ---- refusals ----

    @Test
    fun `a destination that is not a network is refused with the reason`() {
        val s = store()
        val base = s.newDraft().copy(iface = "lan")
        assertEquals(
            "A route needs a destination — 10.20.0.0/16, or 0.0.0.0/0 for everything.",
            s.routeProblem(base),
        )
        assertEquals(
            "'10.99.0.0/33' is not an IPv4 network — 10.20.0.0/16, or 0.0.0.0/0 for everything.",
            s.routeProblem(base.copy(target = "10.99.0.0/33")),
        )
        assertEquals(
            "'2001:db8::/32' is an IPv6 destination — switch the route to IPv6.",
            s.routeProblem(base.copy(target = "2001:db8::/32")),
        )
        assertEquals(
            "'10.1.1.1' is an IPv4 destination — switch the route to IPv4.",
            s.routeProblem(base.copy(ipv6 = true, target = "10.1.1.1")),
        )
        // A bare address is a host route, and 0.0.0.0/0 is the default.
        assertNull(s.routeProblem(base.copy(target = "10.1.1.1")))
        assertNull(s.routeProblem(base.copy(target = "0.0.0.0/0")))
        assertNull(s.routeProblem(base.copy(ipv6 = true, target = "::/0", iface = "wan")))
    }

    @Test
    fun `the interface has to exist and the gateway has to be an address`() {
        val s = store()
        val d = s.newDraft().copy(target = "10.99.0.0/24")
        assertEquals(
            "There is no interface called 'guest' — netifd would ignore the route without a word.",
            s.routeProblem(d.copy(iface = "guest")),
        )
        assertEquals("Pick the interface the route leaves through.", s.routeProblem(d.copy(iface = "")))
        assertEquals("'192.168.0.999' is not an IPv4 address.", s.routeProblem(d.copy(iface = "lan", gateway = "192.168.0.999")))
        assertEquals("'fe80::1' is not an IPv4 address.", s.routeProblem(d.copy(iface = "lan", gateway = "fe80::1")))
    }

    @Test
    fun `a blackhole with a gateway, a bad mtu and a bad table are refused`() {
        val s = store()
        val d = s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")
        assertEquals(
            "A blackhole route has no gateway — the kernel answers for the destination itself.",
            s.routeProblem(d.copy(type = "blackhole", gateway = "192.168.0.2")),
        )
        assertEquals("An MTU below 576 breaks IPv4 — 1500 is standard.", s.routeProblem(d.copy(mtu = "100")))
        assertEquals("'fast' is not a metric. Lower wins; 0 to a few hundred is usual.", s.routeProblem(d.copy(metric = "fast")))
        assertEquals("'vpn' is not a routing table — a number, or main, local, default.", s.routeProblem(d.copy(table = "vpn")))
        assertNull(s.routeProblem(d.copy(table = "main")))
        assertNull(s.routeProblem(d.copy(table = "100")))
    }

    @Test
    fun `a refused draft is not staged`() {
        val s = store()
        assertNotNull(s.stageRoute(s.newDraft().copy(target = "junk", iface = "lan")))
        assertEquals(0, s.pendingCount)
    }

    // ---- warnings ----

    @Test
    fun `a gateway outside the interface's subnet is called out unless onlink`() {
        val s = store()
        val d = s.newDraft().copy(target = "10.99.0.0/24", gateway = "192.168.5.1", iface = "lan")
        assertNull(s.stageRoute(d))
        assertTrue(s.warnings().any { it.contains("192.168.5.1 is not inside lan's subnet (192.168.0.1/24)") })
        assertNull(s.stageRoute(d.copy(onlink = true)))
        assertTrue(s.warnings().none { it.contains("not inside") })
        assertNull(s.stageRoute(d.copy(gateway = "192.168.0.2", onlink = false)))
        assertTrue(s.warnings().none { it.contains("not inside") })
    }

    @Test
    fun `a second default route names the one that exists`() {
        val s = store()
        assertNull(s.stageRoute(s.newDraft().copy(target = "0.0.0.0/0", gateway = "192.168.0.2", iface = "lan", metric = "50")))
        val warning = s.warnings().single { it.contains("second default route") }
        assertTrue(warning.contains("via 192.168.68.1 (metric 10)"))
        assertTrue(warning.contains("this one has metric 50"))
    }

    @Test
    fun `a route over the lan's own subnet is called out`() {
        val s = store()
        assertNull(s.stageRoute(s.newDraft().copy(target = "192.168.0.0/16", gateway = "192.168.0.2", iface = "lan")))
        assertTrue(s.warnings().any { it.contains("covers the LAN's own subnet") })
    }

    // ---- across reads ----

    /** The hub refreshes every few seconds; a re-read must keep the drafts and the deletions. */
    @Test
    fun `a live refresh keeps what is staged`() {
        val s = store()
        assertNull(s.stageRoute(s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")))
        s.removeRoute("@route[0]")
        s.ingest(parts())
        assertEquals(2, s.pendingCount)
        assertEquals(5, s.rows().size)
    }

    @Test
    fun `new sections never reuse a name the router already has`() {
        val uci = ROUTES_NETWORK_UCI + "\nnetwork.wrtpulse_route_7=route\nnetwork.wrtpulse_route_7.interface='lan'\nnetwork.wrtpulse_route_7.target='10.7.0.0/16'"
        val s = store(uci)
        assertEquals(8, s.newDraft().id)
    }

    /** The apply is the WAN screen's: `network` copied aside, the watcher armed, one batch. */
    @Test
    fun `the apply script commits network and reloads it under a rollback`() {
        val s = store()
        assertNull(s.stageRoute(s.newDraft().copy(target = "10.99.0.0/24", iface = "lan")))
        val script = Commands.wanApply(s.ops(), listOf("network"), Commands.NETWORK_RELOAD, 30)
        assertTrue(script.contains("cp /etc/config/network"))
        assertTrue(script.contains("uci commit network && /etc/init.d/network reload"))
        assertFalse(script.contains("uci commit firewall"))
    }

    // ---- anonymous sections: the batch run the way uci runs it ----

    /**
     * A named route counts in `@route[i]` like any other. Sorted as text, `4g_route` came before
     * `@route` and went first, so every anonymous delete after it hit the route below its target.
     */
    @Test
    fun `a named route ahead of anonymous ones is deleted after them, and edits land where aimed`() {
        val (s, uci) = used()
        s.removeRoute("4g_route") // named, at 1: as text `4` sorts ahead of `@`
        s.removeRoute("@route[2]") // lab
        s.toggleDisabled("@route[3]") // vpn, above both
        assertNull(s.stageRoute(s.newDraft(s.routes.single { it.section == "@route[0]" }).copy(metric = "9")))
        assertEquals(
            listOf(
                "set network.@route[3].disabled='1'",
                "set network.@route[0].metric='9'",
                "delete network.@route[2]",
                "delete network.4g_route",
            ),
            s.ops(),
        )
        uci.run(s.ops()).assertLeaves(
            usedRoutes,
            deleted = setOf("lte", "lab"),
            edited = mapOf("vpn" to mapOf("disabled" to "1"), "office" to mapOf("metric" to "9")),
        )
        assertEquals(batchChanges(s.ops()), reviewChanges(s.diffLines()))
    }

    private fun route(
        id: String, section: String?, iface: String, target: String, gateway: String, vararg more: Pair<String, String>,
    ) = FakeUci.Section(id, "route", section, mapOf("interface" to iface, "target" to target, "gateway" to gateway, *more))

    /** LuCI's `@route[i]` with a hand-named `4g_route` among them, and a route6 that is not their type. */
    private val usedRoutes = listOf(
        FakeUci.Section("lan", "interface", "lan", mapOf("device" to "br-lan", "proto" to "static", "ipaddr" to "192.168.0.1", "netmask" to "255.255.255.0")),
        FakeUci.Section("wan", "interface", "wan", mapOf("device" to "eth0.2", "proto" to "dhcp")),
        FakeUci.Section("loopback", "interface", "loopback", mapOf("device" to "lo")),
        // @route[0], the named 4g_route at 1, then @route[2] and [3]
        route("office", null, "lan", "10.20.0.0/16", "192.168.0.2", "metric" to "5"),
        route("lte", "4g_route", "wan", "10.40.0.0/16", "192.168.68.1"),
        route("lab", null, "wan", "10.30.0.0/16", "192.168.68.1"),
        route("vpn", null, "lan", "10.50.0.0/16", "192.168.0.3"),
        FakeUci.Section("v6", "route6", null, mapOf("interface" to "wan", "target" to "2001:db8::/32", "gateway" to "fe80::1")),
    )

    /** A store loaded from [usedRoutes], and the uci its batch will run against. */
    private fun used(): Pair<RouteStore, FakeUci> {
        val uci = FakeUci("network", usedRoutes)
        return store(uci.show()) to uci
    }
}
