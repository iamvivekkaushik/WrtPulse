package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.FIREWALL_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(ops.contains("set firewall.@redirect[0].dest_ip='192.168.1.11'"))
        assertEquals(2, s.forwardRows().size)
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

    // ---- the batch as a whole ----

    @Test
    fun `deletions lead the batch and the diff lists the same lines`() {
        val s = store()
        s.deleteSection("kids")
        s.setDefaultFlag("syn_flood", false)
        s.stageForward(s.newForwardDraft().copy(srcPort = "51820", proto = "udp", destIp = "192.168.1.20"))
        val ops = s.ops()
        assertEquals("delete firewall.kids", ops.first())
        assertTrue(ops.indexOf("set firewall.@defaults[0].syn_flood='0'") < ops.indexOfFirst { it.endsWith("=redirect") })
        val diff = s.diffLines()
        assertEquals("- firewall.kids" to false, diff.first())
        assertTrue(diff.any { it.first == "- firewall.@defaults[0].syn_flood='1'" && !it.second })
        assertTrue(diff.any { it.first == "+ firewall.@defaults[0].syn_flood='0'" && it.second })
        assertTrue(diff.any { it.first.endsWith(".src_dport='51820'") && it.second })
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
}
