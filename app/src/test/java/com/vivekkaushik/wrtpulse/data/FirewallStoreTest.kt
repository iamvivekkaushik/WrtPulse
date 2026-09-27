package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.FIREWALL_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store(): FirewallStore =
        FirewallStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "firewall" to FIREWALL_UCI,
                    "service" to """{"firewall":{"instances":{"instance1":{"running":true}}}}""",
                    "engine" to "fw4",
                    "listen" to "0.0.0.0:22\n0.0.0.0:53\n0.0.0.0:80\n",
                    "leases" to "1700000000 aa:bb:cc:dd:ee:01 192.168.1.87 lab 01:aa:bb:cc:dd:ee:01\n",
                )
            )
        }

    // ---- reading ----

    @Test
    fun `a clean load has nothing pending and the DMZ off`() {
        val s = store()
        assertEquals(0, s.pendingCount)
        assertFalse(s.dmz().enabled)
        assertEquals(listOf(22), s.dmz().except)
        assertTrue(s.wanPing())
        assertEquals(setOf(22, 53, 80), s.listening)
    }

    // ---- forwards ----

    @Test
    fun `toggling a forward stages one enabled flip and toggling back clears it`() {
        val s = store()
        s.toggleForward("@redirect[0]")
        assertEquals(listOf("set firewall.@redirect[0].enabled='0'"), s.ops())
        assertFalse(s.forwardRows().first { it.name == "Home Assistant" }.enabled)
        s.toggleForward("@redirect[0]")
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `a disabled forward toggles on by writing enabled 1`() {
        val s = store()
        s.toggleForward("@redirect[1]")
        assertEquals(listOf("set firewall.@redirect[1].enabled='1'"), s.ops())
    }

    @Test
    fun `deleting a forward removes the section and drops its staged edits`() {
        val s = store()
        s.toggleForward("@redirect[1]")
        s.deleteSection("@redirect[1]")
        assertEquals(listOf("delete firewall.@redirect[1]"), s.ops())
        assertFalse(s.forwardRows().any { it.name == "Plex" })
    }

    @Test
    fun `a staged forward becomes a redirect section with DNAT`() {
        val s = store()
        val d = s.newForwardDraft().copy(name = "lab-ssh", proto = "tcp", srcPort = "2222", destIp = "192.168.1.87", destPort = "22")
        assertNull(s.stageForward(d))
        val ops = s.ops()
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}=redirect"))
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}.src_dport='2222'"))
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}.dest_port='22'"))
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}.dest_ip='192.168.1.87'"))
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}.target='DNAT'"))
        assertEquals(3, s.forwardRows().size)
    }

    @Test
    fun `internal port equal to external is left out, since fw4 defaults it`() {
        val s = store()
        val d = s.newForwardDraft().copy(srcPort = "443", destIp = "192.168.1.5", destPort = "443")
        s.stageForward(d)
        assertFalse(s.ops().any { it.contains("dest_port") })
    }

    @Test
    fun `the router's own SSH port is refused with the design's wording`() {
        val s = store()
        val problem = s.forwardProblem(s.newForwardDraft().copy(srcPort = "22", destIp = "192.168.1.87"))
        assertNotNull(problem)
        assertTrue(problem!!.contains("router's own SSH"))
        assertTrue(problem.contains("lock this app out"))
    }

    @Test
    fun `another router listener is refused too, in different words`() {
        val s = store()
        val problem = s.forwardProblem(s.newForwardDraft().copy(srcPort = "80", destIp = "192.168.1.87"))
        assertTrue(problem!!.contains("listens on"))
    }

    @Test
    fun `an external port already forwarded on the same protocol collides`() {
        val s = store()
        val problem = s.forwardProblem(s.newForwardDraft().copy(srcPort = "8123", proto = "tcp", destIp = "192.168.1.5"))
        assertTrue(problem!!.contains("already forwarded by Home Assistant"))
        // UDP on the same port is a different door.
        assertNull(s.forwardProblem(s.newForwardDraft().copy(srcPort = "8123", proto = "udp", destIp = "192.168.1.5")))
    }

    @Test
    fun `editing a forward does not collide with itself`() {
        val s = store()
        val existing = s.forwardRows().first { it.name == "Home Assistant" }
        val d = s.newForwardDraft(existing).copy(destIp = "192.168.1.11")
        assertNull(s.forwardProblem(d))
        s.stageForward(d)
        val ops = s.ops()
        assertEquals("delete firewall.@redirect[0]", ops.first())
        // Not written back under its index: once the old section is gone, @redirect[0] is Plex.
        assertTrue(ops.contains("set firewall.wrtpulse_fwd_${d.id}.dest_ip='192.168.1.11'"))
        assertFalse(ops.any { it.startsWith("set firewall.@redirect[") })
        assertEquals(2, s.forwardRows().size)
        assertEquals(d, s.forwardDraftFor("@redirect[0]"))
    }

    @Test
    fun `removing an edited forward drops the edit and puts the saved one back`() {
        val s = store()
        val existing = s.forwardRows().first { it.name == "Home Assistant" }
        s.stageForward(s.newForwardDraft(existing).copy(destIp = "192.168.1.11"))
        assertNotNull(s.forwardDraftFor(existing.section))
        s.removeForward(existing.section)
        assertNull(s.forwardDraftFor(existing.section))
        assertEquals(0, s.pendingCount)
        assertEquals("192.168.1.10", s.forwardRows().first { it.name == "Home Assistant" }.destIp)
    }

    @Test
    fun `removing a saved forward stages its deletion, removing a new draft just forgets it`() {
        val s = store()
        s.removeForward("@redirect[1]")
        assertEquals(listOf("delete firewall.@redirect[1]"), s.ops())
        val d = s.newForwardDraft().copy(srcPort = "9000", destIp = "192.168.1.9")
        s.stageForward(d)
        assertEquals(2, s.pendingCount)
        s.removeForward("wrtpulse_fwd_${d.id}")
        assertEquals(1, s.pendingCount)
        val r = s.newRuleDraft().copy(name = "tmp")
        s.stageRule(r)
        s.removeRule("wrtpulse_rule_${r.id}")
        assertNull(s.ruleDraftFor("wrtpulse_rule_${r.id}"))
        s.removeRule("kids")
        assertTrue(s.ops().contains("delete firewall.kids"))
    }

    @Test
    fun `the suggested port for 22 is 2222 and is not itself taken`() {
        val s = store()
        assertEquals(2222, s.suggestPort(22))
        assertEquals(8080, s.suggestPort(80))
    }

    @Test
    fun `garbage ports and addresses are refused before anything else`() {
        val s = store()
        assertTrue(s.forwardProblem(s.newForwardDraft().copy(srcPort = "99999", destIp = "192.168.1.5"))!!.contains("External port"))
        assertTrue(s.forwardProblem(s.newForwardDraft().copy(srcPort = "8080", destIp = "nas"))!!.contains("IPv4"))
    }

    // ---- rules ----

    @Test
    fun `a scheduled rule writes fw4's weekdays and times`() {
        val s = store()
        val d = s.newRuleDraft().copy(
            name = "Kids", src = "lan", dest = "wan", srcIp = "192.168.1.62", target = "REJECT",
            weekdays = listOf("Mon", "Tue"), startTime = "21:00", stopTime = "07:00",
        )
        assertNull(s.stageRule(d))
        val ops = s.ops()
        assertTrue(ops.contains("set firewall.wrtpulse_rule_${d.id}=rule"))
        assertTrue(ops.contains("set firewall.wrtpulse_rule_${d.id}.weekdays='Mon Tue'"))
        assertTrue(ops.contains("set firewall.wrtpulse_rule_${d.id}.start_time='21:00'"))
        assertTrue(ops.contains("set firewall.wrtpulse_rule_${d.id}.stop_time='07:00'"))
        assertTrue(s.ruleRows().last().scheduled)
    }

    @Test
    fun `a schedule needs both ends and real clock times`() {
        val s = store()
        assertTrue(s.ruleProblem(s.newRuleDraft().copy(startTime = "21:00"))!!.contains("both"))
        assertTrue(s.ruleProblem(s.newRuleDraft().copy(startTime = "25:00", stopTime = "07:00"))!!.contains("HH:MM"))
        assertNull(s.ruleProblem(s.newRuleDraft().copy(startTime = "21:00", stopTime = "07:00")))
    }

    @Test
    fun `a rule to and from nowhere is refused`() {
        val s = store()
        assertTrue(s.ruleProblem(s.newRuleDraft().copy(src = "", dest = ""))!!.contains("zone"))
    }

    // ---- WAN ping ----

    @Test
    fun `turning WAN ping off disables the Allow-Ping rule rather than deleting it`() {
        val s = store()
        s.setWanPing(false)
        assertEquals(listOf("set firewall.@rule[0].enabled='0'"), s.ops())
        assertFalse(s.wanPing())
        s.setWanPing(true)
        assertEquals(0, s.pendingCount)
    }

    // ---- zones and the matrix ----

    @Test
    fun `zone policy edits are plain scalar sets`() {
        val s = store()
        s.setZonePolicy("@zone[1]", "input", "DROP")
        s.setZoneFlag("@zone[1]", "masq", false)
        assertEquals(
            listOf("set firewall.@zone[1].input='DROP'", "set firewall.@zone[1].masq='0'"),
            s.ops(),
        )
        val wan = s.zoneRows().first { it.name == "wan" }
        assertEquals("DROP", wan.input)
        assertFalse(wan.masq)
    }

    @Test
    fun `blocking an allowed pair deletes its forwarding, allowing a blocked pair adds one`() {
        val s = store()
        assertTrue(s.forwardingAllowed("lan", "wan"))
        s.toggleForwarding("lan", "wan")
        assertFalse(s.forwardingAllowed("lan", "wan"))
        assertEquals(listOf("delete firewall.@forwarding[0]"), s.ops())
        s.toggleForwarding("lan", "wan")
        assertEquals(0, s.pendingCount)

        s.toggleForwarding("guest", "wan")
        assertTrue(s.forwardingAllowed("guest", "wan"))
        assertEquals(
            listOf(
                "set firewall.wrtpulse_zone_guest_wan=forwarding",
                "set firewall.wrtpulse_zone_guest_wan.src='guest'",
                "set firewall.wrtpulse_zone_guest_wan.dest='wan'",
            ),
            s.ops(),
        )
    }

    @Test
    fun `cutting lan off the internet is said out loud`() {
        val s = store()
        s.toggleForwarding("lan", "wan")
        assertTrue(s.warnings().any { it.contains("cuts every LAN client off") })
    }

    // ---- defaults ----

    @Test
    fun `default policies and flags stage against the defaults section`() {
        val s = store()
        s.setDefault("input", "ACCEPT")
        s.setDefaultFlag("drop_invalid", true)
        assertEquals(
            listOf("set firewall.@defaults[0].drop_invalid='1'", "set firewall.@defaults[0].input='ACCEPT'"),
            s.ops(),
        )
        assertTrue(s.warnings().any { it.contains("Default input ACCEPT") })
    }

    // ---- DMZ ----

    @Test
    fun `enabling the DMZ writes one redirect per port range around the exceptions`() {
        val s = store()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = listOf(8123)))
        assertEquals(listOf(22, 8123), s.dmz().except)
        val ops = s.ops()
        assertTrue(ops.contains("set firewall.wrtpulse_dmz_1=redirect"))
        assertTrue(ops.contains("set firewall.wrtpulse_dmz_1.src_dport='1-21'"))
        assertTrue(ops.contains("set firewall.wrtpulse_dmz_2.src_dport='23-8122'"))
        assertTrue(ops.contains("set firewall.wrtpulse_dmz_3.src_dport='8124-65535'"))
        assertTrue(ops.contains("set firewall.wrtpulse_dmz_3.dest_ip='192.168.1.50'"))
        assertEquals(3, ops.count { it.endsWith("=redirect") })
        assertTrue(s.warnings().any { it.contains("DMZ host") })
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `the SSH port cannot be removed from the exceptions`() {
        val s = store()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = emptyList()))
        assertEquals(listOf(22), s.dmz().except)
    }

    @Test
    fun `a DMZ without a valid host cannot be applied`() {
        val s = store()
        s.setDmz(DmzDraft(enabled = true, targetIp = "", src = "wan", except = emptyList()))
        assertTrue(s.problems().single().contains("IPv4"))
    }

    @Test
    fun `putting the DMZ back as it was leaves nothing pending`() {
        val s = store()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = emptyList()))
        s.setDmz(DmzDraft(enabled = false, targetIp = "", src = "wan", except = listOf(22)))
        assertEquals(0, s.pendingCount)
    }

    // ---- the DMZ once applied: the batch run as uci runs it, then read back ----
    //
    // Every redirect the app writes for the DMZ names a port range, so it is the
    // `wrtpulse_dmz_<n>` name that marks them as the DMZ, not a missing src_dport.

    @Test
    fun `an applied DMZ reads back on its tab and stays off the forward list`() {
        val (s, uci) = twoForwards()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = listOf(8123)))
        s.applyTo(uci)
        assertEquals(listOf("1-21", "23-8122", "8124-65535"), dmzSections(uci).map { uci.options(it)!!["src_dport"] })

        assertEquals(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = listOf(22, 8123)), s.dmz())
        assertEquals(0, s.pendingCount)
        // The tab handing back what it read is no change.
        s.setDmz(s.dmz())
        assertEquals(0, s.pendingCount)
        assertEquals(listOf("Home Assistant", "NAS"), s.forwardRows().map { it.name })
    }

    @Test
    fun `turning an applied DMZ off deletes every one of its redirects and nothing else`() {
        val (s, uci) = twoForwards()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = listOf(8123)))
        s.applyTo(uci)
        s.setDmz(s.dmz().copy(enabled = false))
        assertEquals(
            listOf("delete firewall.wrtpulse_dmz_1", "delete firewall.wrtpulse_dmz_2", "delete firewall.wrtpulse_dmz_3"),
            s.ops(),
        )
        s.applyTo(uci)
        assertEquals(twoForwardsFirewall.map { it.id }, uci.ids)
        twoForwardsFirewall.forEach { assertEquals("section ${it.id}", it.options, uci.options(it.id)) }
        assertFalse(s.dmz().enabled)
        assertEquals(listOf("Home Assistant", "NAS"), s.forwardRows().map { it.name })
    }

    @Test
    fun `re-ranging an applied DMZ writes it afresh and leaves no stale range behind`() {
        val (s, uci) = twoForwards()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.50", src = "wan", except = listOf(8123)))
        s.applyTo(uci)
        s.setDmz(s.dmz().copy(targetIp = "192.168.1.60", except = listOf(22)))
        s.applyTo(uci)
        assertEquals(listOf("wrtpulse_dmz_1", "wrtpulse_dmz_2"), dmzSections(uci))
        assertEquals(
            mapOf(
                "name" to "DMZ 192.168.1.60 · ports 23–65535", "src" to "wan", "src_dport" to "23-65535", "dest" to "lan",
                "dest_ip" to "192.168.1.60", "proto" to "tcp udp", "target" to "DNAT",
            ),
            uci.options("wrtpulse_dmz_2"),
        )
        assertEquals(DmzDraft(enabled = true, targetIp = "192.168.1.60", src = "wan", except = listOf(22)), s.dmz())
    }

    /** A router with two port forwards, one anonymous and one named, and no DMZ yet. */
    private val twoForwardsFirewall = listOf(
        FakeUci.Section("defaults", "defaults", options = mapOf("input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT")),
        FakeUci.Section("lanZone", "zone", options = mapOf("name" to "lan", "network" to "lan", "input" to "ACCEPT", "output" to "ACCEPT", "forward" to "ACCEPT")),
        FakeUci.Section("wanZone", "zone", options = mapOf("name" to "wan", "network" to "wan", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT", "masq" to "1")),
        FakeUci.Section("lanWan", "forwarding", options = mapOf("src" to "lan", "dest" to "wan")),
        FakeUci.Section("ha", "redirect", options = mapOf("name" to "Home Assistant", "src" to "wan", "src_dport" to "8123", "dest" to "lan", "dest_ip" to "192.168.1.10", "proto" to "tcp", "target" to "DNAT")),
        FakeUci.Section("nas", "redirect", "nas", mapOf("name" to "NAS", "src" to "wan", "src_dport" to "5001", "dest" to "lan", "dest_ip" to "192.168.1.20", "proto" to "tcp", "target" to "DNAT")),
    )

    /** A store loaded from [twoForwardsFirewall]'s `uci show`, and the uci its batches run against. */
    private fun twoForwards(): Pair<FirewallStore, FakeUci> {
        val uci = FakeUci("firewall", twoForwardsFirewall)
        return storeWith(uci.show()) to uci
    }

    /** What [FirewallStore.apply] does, with [uci] as the router: the batch runs, the store reads it back, the drafts go. */
    private fun FirewallStore.applyTo(uci: FakeUci) {
        uci.run(ops())
        ingest(mapOf("firewall" to uci.show(), "engine" to "fw4", "listen" to "0.0.0.0:22\n"))
        revert()
    }

    private fun dmzSections(uci: FakeUci) = uci.ids.filter { it.startsWith("wrtpulse_dmz_") }

    // ---- the batch as a whole ----

    @Test
    fun `edits lead the batch, then deletions, then new sections, and the diff lists the same lines`() {
        val s = store()
        s.deleteSection("kids")
        s.setDefaultFlag("syn_flood", false)
        s.stageForward(s.newForwardDraft().copy(srcPort = "51820", proto = "udp", destIp = "192.168.1.20"))
        val ops = s.ops()
        assertEquals(listOf("set firewall.@defaults[0].syn_flood='0'", "delete firewall.kids"), ops.take(2))
        assertTrue(ops.indexOf("delete firewall.kids") < ops.indexOfFirst { it.endsWith("=redirect") })
        val diff = s.diffLines()
        assertEquals(
            listOf(
                "- firewall.@defaults[0].syn_flood='1'" to false,
                "+ firewall.@defaults[0].syn_flood='0'" to true,
                "- firewall.kids" to false,
            ),
            diff.take(3),
        )
        assertTrue(diff.any { it.first.endsWith(".src_dport='51820'") && it.second })
        assertEquals(steps(ops), reviewSteps(diff))
    }

    @Test
    fun `revert clears every kind of staged change`() {
        val s = store()
        s.toggleForward("@redirect[0]")
        s.deleteSection("kids")
        s.stageRule(s.newRuleDraft().copy(name = "x"))
        s.toggleForwarding("guest", "wan")
        s.setDmz(DmzDraft(true, "192.168.1.50", "wan", emptyList()))
        assertTrue(s.pendingCount >= 5)
        s.revert()
        assertEquals(0, s.pendingCount)
        assertTrue(s.ops().isEmpty())
    }

    // ---- section names must not land on one the router already has ----

    /**
     * The counter used to start at 1 in every store, and a store is rebuilt whenever the session
     * changes — leaving the router-switcher and coming back was enough. The next forward added
     * was then written straight over `wrtpulse_fwd_1`, keeping whatever options the new draft did
     * not set, so the survivor forwarded to the old internal port.
     */
    @Test
    fun `a draft never reuses a section the router already carries`() {
        val first = store()
        val d1 = first.newForwardDraft().copy(name = "a", proto = "tcp", srcPort = "2222", destIp = "192.168.1.87", destPort = "22")
        assertNull(first.stageForward(d1))
        val applied = draftSectionOf(first, d1)

        // The router now holds that section; a fresh store reads it back and must move past it.
        val second = storeWith(FIREWALL_UCI + "\n" + redirectSection(applied, "2222"))
        val d2 = second.newForwardDraft().copy(name = "b", proto = "tcp", srcPort = "3333", destIp = "192.168.1.88", destPort = "22")
        assertNull(second.stageForward(d2))
        val next = draftSectionOf(second, d2)

        assertNotEquals(applied, next)
        assertFalse(second.ops().any { it.startsWith("set firewall.$applied=") })
    }

    /** Rules share the counter, so seeding has to move past the highest of either kind. */
    @Test
    fun `seeding moves past the highest wrtpulse section of either kind`() {
        val s = storeWith(FIREWALL_UCI + "\n" + redirectSection("wrtpulse_fwd_7", "2222"))
        val d = s.newForwardDraft().copy(name = "c", proto = "tcp", srcPort = "4444", destIp = "192.168.1.90", destPort = "22")
        assertNull(s.stageForward(d))
        assertTrue("expected an id past 7, got ${d.id}", d.id > 7)
    }

    // ---- anonymous sections: the batch run the way uci runs it ----
    //
    // `@type[i]` counts the named sections of the type too, and uci resolves it command by
    // command, so every delete renumbers what follows. These run ops() through FakeUci against
    // a config read from its own `uci show`, and check what actually got deleted and edited.

    @Test
    fun `deleting two anonymous forwardings takes exactly those two and keeps lan to wan`() {
        val (s, uci) = used()
        s.toggleForwarding("guest", "wan") // @forwarding[0]
        s.toggleForwarding("guest", "lan") // @forwarding[2], the named one being [1]
        assertEquals(listOf("delete firewall.@forwarding[2]", "delete firewall.@forwarding[0]"), s.ops())
        uci.run(s.ops()).assertOutcome(deleted = setOf("guestWan", "guestLan"))
    }

    /** The hazard, spelled out: lowest index first, the second delete lands on lan → wan. */
    @Test
    fun `the same two deleted lowest index first would take lan to wan`() {
        val uci = FakeUci("firewall", usedFirewall)
            .run(listOf("delete firewall.@forwarding[0]", "delete firewall.@forwarding[2]"))
        assertEquals(setOf("guestWan", "lanWan"), uci.deleted)
    }

    @Test
    fun `rules go highest index first by number, named ones after, and edits land before any delete`() {
        val (s, uci) = used()
        s.removeRule("@rule[2]") // Allow-IGMP
        s.removeRule("@rule[10]") // Block-Telnet: as text it sorts ahead of @rule[2]
        s.removeRule("@rule[3]") // Allow-DHCPv6
        s.removeRule("kids") // named, but it sits ahead of @rule[10] and counts
        s.toggleRule("@rule[13]") // Plex-Out off: four deletes below it
        s.setWanPing(false) // Allow-Ping, @rule[1], off
        assertEquals(
            listOf(
                "set firewall.@rule[13].enabled='0'",
                "set firewall.@rule[1].enabled='0'",
                "delete firewall.@rule[10]",
                "delete firewall.@rule[3]",
                "delete firewall.@rule[2]",
                "delete firewall.kids",
            ),
            s.ops(),
        )
        uci.run(s.ops()).assertOutcome(
            deleted = setOf("igmp", "telnet", "dhcpv6", "kids"),
            edited = mapOf("plexOut" to mapOf("enabled" to "0"), "ping" to mapOf("enabled" to "0")),
        )
    }

    @Test
    fun `an edited anonymous forward is written to a new section, not poured over the next redirect`() {
        val (s, uci) = used()
        val plex = s.forwardRows().first { it.name == "Plex" } // @redirect[1]
        val d = s.newForwardDraft(plex).copy(destIp = "192.168.1.11")
        assertNull(s.stageForward(d))
        s.removeForward("@redirect[0]") // Home Assistant, below it
        s.toggleForward("@redirect[4]") // Game, above it, off
        uci.run(s.ops()).assertOutcome(
            deleted = setOf("ha", "plex"),
            edited = mapOf("game" to mapOf("enabled" to "0")),
            added = mapOf(
                "wrtpulse_fwd_${d.id}" to mapOf(
                    "name" to "Plex", "src" to "wan", "src_dport" to "32400", "dest" to "lan",
                    "dest_ip" to "192.168.1.11", "proto" to "tcp", "target" to "DNAT",
                ),
            ),
        )
        // The row keeps the saved section as its handle, so the sheet still finds the edit.
        assertEquals(d, s.forwardDraftFor("@redirect[1]"))
    }

    @Test
    fun `a new DMZ replaces a hand-written one while a redirect below it goes too`() {
        val (s, uci) = used()
        s.setDmz(DmzDraft(enabled = true, targetIp = "192.168.1.60", src = "wan", except = listOf(22)))
        s.removeForward("@redirect[4]") // Game, just below the old DMZ at @redirect[5]
        s.removeForward("nas") // named, ahead of both
        fun dmz(ports: String, dport: String) = mapOf(
            "name" to "DMZ 192.168.1.60 · ports $ports", "src" to "wan", "src_dport" to dport, "dest" to "lan",
            "dest_ip" to "192.168.1.60", "proto" to "tcp udp", "target" to "DNAT",
        )
        uci.run(s.ops()).assertOutcome(
            deleted = setOf("game", "dmz", "nas"),
            added = mapOf("wrtpulse_dmz_1" to dmz("1–21", "1-21"), "wrtpulse_dmz_2" to dmz("23–65535", "23-65535")),
        )
    }

    @Test
    fun `a batch of every kind runs clean, and the review lists it in the batch's order`() {
        val (s, uci) = used()
        s.setZonePolicy("@zone[3]", "input", "DROP") // iot: guest is a named zone, and still [2]
        s.setDefaultFlag("drop_invalid", true)
        s.toggleForwarding("guest", "lan") // @forwarding[2]
        s.toggleForwarding("lan", "iot") // @forwarding[4]
        s.toggleForwarding("iot", "wan") // new
        s.removeRule("@rule[11]") // Guest-Printer
        s.toggleRule("@rule[13]") // Plex-Out off
        val ha = s.newForwardDraft(s.forwardRows().first { it.name == "Home Assistant" }).copy(destIp = "192.168.1.12")
        assertNull(s.stageForward(ha))
        val rule = s.newRuleDraft().copy(name = "No-Telnet-Out", proto = "tcp", destPort = "23")
        assertNull(s.stageRule(rule))
        val ops = s.ops()
        uci.run(ops).assertOutcome(
            deleted = setOf("guestLan", "lanIot", "printer", "ha"),
            edited = mapOf(
                "iotZone" to mapOf("input" to "DROP"),
                "defaults" to mapOf("drop_invalid" to "1"),
                "plexOut" to mapOf("enabled" to "0"),
            ),
            added = mapOf(
                "wrtpulse_fwd_${ha.id}" to mapOf(
                    "name" to "Home Assistant", "src" to "wan", "src_dport" to "8123", "dest" to "lan",
                    "dest_ip" to "192.168.1.12", "proto" to "tcp", "target" to "DNAT",
                ),
                "wrtpulse_rule_${rule.id}" to mapOf(
                    "name" to "No-Telnet-Out", "src" to "lan", "dest" to "wan", "proto" to "tcp",
                    "dest_port" to "23", "target" to "REJECT",
                ),
                "wrtpulse_zone_iot_wan" to mapOf("src" to "iot", "dest" to "wan"),
            ),
        )
        assertEquals(steps(ops), reviewSteps(s.diffLines()))
    }

    @Test
    fun `an edit of a forward that is being replaced is left out of the review as it is of the batch`() {
        val s = store()
        s.toggleForward("@redirect[1]")
        s.stageForward(s.newForwardDraft(s.forwardRows().first { it.name == "Plex" }).copy(destIp = "192.168.1.11"))
        assertFalse(s.ops().any { it.contains("@redirect[1].enabled") })
        assertFalse(s.diffLines().any { it.first.contains("@redirect[1].enabled") })
        assertEquals(steps(s.ops()), reviewSteps(s.diffLines()))
    }

    private fun redirectSection(name: String, srcPort: String) = """
        firewall.$name=redirect
        firewall.$name.name='existing'
        firewall.$name.src='wan'
        firewall.$name.dest='lan'
        firewall.$name.proto='tcp'
        firewall.$name.src_dport='$srcPort'
        firewall.$name.dest_ip='192.168.1.99'
        firewall.$name.dest_port='9999'
        firewall.$name.target='DNAT'
    """.trimIndent()

    private fun storeWith(firewallUci: String): FirewallStore =
        FirewallStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "firewall" to firewallUci,
                    "service" to """{"firewall":{"instances":{"instance1":{"running":true}}}}""",
                    "engine" to "fw4",
                    "listen" to "0.0.0.0:22\n",
                    "leases" to "",
                )
            )
        }

    private fun draftSectionOf(s: FirewallStore, d: ForwardDraft): String =
        s.ops().first { it.endsWith("=redirect") }
            .removePrefix("set firewall.").removeSuffix("=redirect")

    private fun sec(id: String, type: String, name: String?, vararg options: Pair<String, String>) =
        FakeUci.Section(id, type, name, mapOf(*options))

    /**
     * A firewall after some use: LuCI's anonymous sections with named ones among them. Each
     * named section still takes an index in its type, and so does the SNAT redirect the store
     * does not list as a forward.
     */
    private val usedFirewall = listOf(
        sec("defaults", "defaults", null, "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT", "syn_flood" to "1"),
        sec("lanZone", "zone", null, "name" to "lan", "network" to "lan", "input" to "ACCEPT", "output" to "ACCEPT", "forward" to "ACCEPT"),
        sec("wanZone", "zone", null, "name" to "wan", "network" to "wan", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT", "masq" to "1", "mtu_fix" to "1"),
        sec("guestZone", "zone", "guest", "name" to "guest", "network" to "guest", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT"),
        sec("iotZone", "zone", null, "name" to "iot", "network" to "iot", "input" to "REJECT", "output" to "ACCEPT", "forward" to "REJECT"),
        // @forwarding[0], a named one at 1, then @forwarding[2] to [4]
        sec("guestWan", "forwarding", null, "src" to "guest", "dest" to "wan"),
        sec("lanGuest", "forwarding", "wrtpulse_zone_lan_guest", "src" to "lan", "dest" to "guest"),
        sec("guestLan", "forwarding", null, "src" to "guest", "dest" to "lan"),
        sec("lanWan", "forwarding", null, "src" to "lan", "dest" to "wan"),
        sec("lanIot", "forwarding", null, "src" to "lan", "dest" to "iot"),
        // the stock @rule[0] to [8], kids at 9, @rule[10] and [11], a named one at 12, @rule[13]
        sec("dhcpRenew", "rule", null, "name" to "Allow-DHCP-Renew", "src" to "wan", "proto" to "udp", "dest_port" to "68", "target" to "ACCEPT", "family" to "ipv4"),
        sec("ping", "rule", null, "name" to "Allow-Ping", "src" to "wan", "proto" to "icmp", "icmp_type" to "echo-request", "family" to "ipv4", "target" to "ACCEPT"),
        sec("igmp", "rule", null, "name" to "Allow-IGMP", "src" to "wan", "proto" to "igmp", "family" to "ipv4", "target" to "ACCEPT"),
        sec("dhcpv6", "rule", null, "name" to "Allow-DHCPv6", "src" to "wan", "proto" to "udp", "dest_port" to "546", "family" to "ipv6", "target" to "ACCEPT"),
        sec("mld", "rule", null, "name" to "Allow-MLD", "src" to "wan", "proto" to "icmp", "src_ip" to "fe80::/10", "family" to "ipv6", "target" to "ACCEPT"),
        sec("icmpv6In", "rule", null, "name" to "Allow-ICMPv6-Input", "src" to "wan", "proto" to "icmp", "family" to "ipv6", "target" to "ACCEPT"),
        sec("icmpv6Fwd", "rule", null, "name" to "Allow-ICMPv6-Forward", "src" to "wan", "dest" to "*", "proto" to "icmp", "family" to "ipv6", "target" to "ACCEPT"),
        sec("esp", "rule", null, "name" to "Allow-IPSec-ESP", "src" to "wan", "dest" to "lan", "proto" to "esp", "target" to "ACCEPT"),
        sec("isakmp", "rule", null, "name" to "Allow-ISAKMP", "src" to "wan", "dest" to "lan", "dest_port" to "500", "proto" to "udp", "target" to "ACCEPT"),
        sec("kids", "rule", "kids", "name" to "Kids tablet", "src" to "lan", "src_ip" to "192.168.1.62", "dest" to "wan", "target" to "REJECT"),
        sec("telnet", "rule", null, "name" to "Block-Telnet", "src" to "lan", "dest" to "wan", "proto" to "tcp", "dest_port" to "23", "target" to "REJECT"),
        sec("printer", "rule", null, "name" to "Guest-Printer", "src" to "guest", "dest" to "lan", "dest_ip" to "192.168.1.30", "target" to "ACCEPT"),
        sec("guestDns", "rule", "wrtpulse_guest_dns", "name" to "Guest-DNS", "src" to "guest", "proto" to "tcpudp", "dest_port" to "53", "target" to "ACCEPT"),
        sec("plexOut", "rule", null, "name" to "Plex-Out", "src" to "lan", "dest" to "wan", "proto" to "tcp", "dest_port" to "32400", "target" to "ACCEPT"),
        // @redirect[0] and [1], a named one at 2, a SNAT at 3, @redirect[4], and a hand-written DMZ at 5
        sec("ha", "redirect", null, "name" to "Home Assistant", "src" to "wan", "src_dport" to "8123", "dest" to "lan", "dest_ip" to "192.168.1.10", "dest_port" to "8123", "proto" to "tcp", "target" to "DNAT"),
        sec("plex", "redirect", null, "name" to "Plex", "src" to "wan", "src_dport" to "32400", "dest" to "lan", "dest_ip" to "192.168.1.10", "proto" to "tcp", "target" to "DNAT", "enabled" to "0"),
        sec("nas", "redirect", "nas", "name" to "NAS", "src" to "wan", "src_dport" to "5001", "dest" to "lan", "dest_ip" to "192.168.1.20", "proto" to "tcp", "target" to "DNAT"),
        sec("snat", "redirect", null, "name" to "Masq exception", "src" to "lan", "src_dip" to "10.0.0.1", "target" to "SNAT"),
        sec("game", "redirect", null, "name" to "Game", "src" to "wan", "src_dport" to "27015", "dest" to "lan", "dest_ip" to "192.168.1.40", "proto" to "udp", "target" to "DNAT"),
        sec("dmz", "redirect", null, "name" to "DMZ", "src" to "wan", "dest" to "lan", "dest_ip" to "192.168.1.50", "proto" to "all", "target" to "DNAT"),
    )

    /** A store loaded from [usedFirewall]'s `uci show`, and the uci its batch will run against. */
    private fun used(): Pair<FirewallStore, FakeUci> {
        val uci = FakeUci("firewall", usedFirewall)
        return storeWith(uci.show()) to uci
    }

    /**
     * After the batch: exactly [deleted] gone, every other section of [usedFirewall] as it was
     * apart from the options in [edited], and the sections in [added] new at the end, in that
     * order, with exactly those options.
     */
    private fun FakeUci.assertOutcome(
        deleted: Set<String>,
        edited: Map<String, Map<String, String>> = emptyMap(),
        added: Map<String, Map<String, String>> = emptyMap(),
    ) {
        assertEquals(deleted, this.deleted)
        usedFirewall.filter { it.id !in deleted }.forEach { s ->
            assertEquals("section ${s.id}", s.options + edited[s.id].orEmpty(), options(s.id))
        }
        assertEquals(added.keys.toList(), ids.filter { id -> usedFirewall.none { it.id == id } })
        added.forEach { (name, want) -> assertEquals("section $name", want, options(name)) }
    }

    /** The batch as (sign, path, value) steps: a `set` adds or edits, a `delete` removes. */
    private fun steps(ops: List<String>): List<Triple<Char, String, String>> = ops.map { op ->
        val rest = op.substringAfter(' ')
        if (op.startsWith("delete ")) Triple('-', rest, "")
        else Triple('+', rest.substringBefore('='), rest.substringAfter('='))
    }

    /** The review as the same steps: `- path='old'` followed by `+ path='new'` is one edit. */
    private fun reviewSteps(diff: List<Pair<String, Boolean>>): List<Triple<Char, String, String>> {
        val lines = diff.map { (line, added) ->
            val body = line.drop(2)
            Triple(if (added) '+' else '-', body.substringBefore('='), body.substringAfter('=', ""))
        }
        val out = mutableListOf<Triple<Char, String, String>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val next = lines.getOrNull(i + 1)
            if (line.first == '-' && line.third.isNotEmpty() && next != null && next.first == '+' && next.second == line.second) {
                out += next
                i += 2
            } else {
                out += if (line.first == '-') Triple('-', line.second, "") else line
                i++
            }
        }
        return out
    }
}
