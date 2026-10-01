package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.ExecResult
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.DHCP_UCI
import com.vivekkaushik.wrtpulse.ops.LAN_STATUS
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.NETDEV_LINES
import com.vivekkaushik.wrtpulse.ops.NETWORK_UCI
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A node's LAN as the join writes it: static, CIDR, the primary as gateway and resolver. */
private val NODE_LAN = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.2/24'
    network.lan.gateway='192.168.1.1'
    network.lan.dns='192.168.1.1'
""".trimIndent()

private val unusedClient = object : SshClient {
    override suspend fun probeHostKey(target: SshTarget) = error("unused")
    override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection = error("unused")
}

/** Every command, primary's and nodes', in the order they ran. */
private class Log {
    val lines = mutableListOf<String>()
}

private class Primary(private val log: Log, private val fail: SshException? = null) :
    RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") }) {
    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        log.lines += "primary: ${command.lineSequence().first()}"
        if (command.startsWith("uci batch")) fail?.let { throw it }
        return ExecResult("", "", 0)
    }
}

private class Node(private val name: String, private val log: Log, private val lan: String = NODE_LAN) :
    RouterSession(SshTarget(name), unusedClient, { error("unused") }) {
    val commands = mutableListOf<String>()
    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        commands += command
        val out = when {
            command == Commands.NETWORK_CONFIG -> lan
            command.contains("/follow.sh") -> "armed\n"
            command == Commands.FOLLOW_GO -> "go\n"
            command == Commands.FOLLOW_CANCEL -> "cancelled\n"
            else -> ""
        }
        log.lines += "$name: ${if (command.contains("/follow.sh")) "arm" else command}"
        return ExecResult(out, "", 0)
    }
    override suspend fun disconnect() {}
}

class NodeFollowTest {

    private fun row(id: Long, name: String, host: String, primary: String? = "primary") = RouterEntity(
        id = id, name = name, host = host, port = 22, username = "root", model = "", summary = "",
        credential = null, lastSeenEpoch = 0, identity = "id-$id", meshPrimary = primary,
    )

    /** The shared LAN fixture at 192.168.1.1/24, its reservations moved along so they do not block a move to 192.168.2.0/24. */
    private fun primary(session: RouterSession, dhcp: String = DHCP_UCI.replace("192.168.1.", "192.168.2.")) = LanStore(session).apply {
        ingest(
            mapOf(
                "net" to NETWORK_UCI, "dhcp" to dhcp, "live" to LAN_STATUS,
                "leases" to "", "neigh" to "", "links" to NETDEV_LINES, "dnsmasq" to "running",
            )
        )
    }

    // ---- where a node lands ----

    @Test
    fun `a node keeps its host part in the new subnet`() {
        assertEquals("192.168.2.2", MeshOps.followAddress("192.168.1.2", 24, "192.168.2.1", 24, 100, 150, emptySet()))
        assertEquals("10.0.0.2", MeshOps.followAddress("192.168.1.2", 24, "10.0.0.1", 16, 100, 150, emptySet()))
    }

    @Test
    fun `a node still inside the new subnet stays where it is`() {
        // Wider prefix: 192.168.1.2 is inside 192.168.0.0/16, and not .0.2.
        assertEquals("192.168.1.2", MeshOps.followAddress("192.168.1.2", 24, "192.168.1.1", 16, 100, 150, emptySet()))
        // The router moves within the subnet.
        assertEquals("192.168.1.2", MeshOps.followAddress("192.168.1.2", 24, "192.168.1.254", 24, 100, 150, emptySet()))
    }

    @Test
    fun `a node whose host part is taken, the router's, or past the new subnet takes the lowest free one`() {
        assertEquals("192.168.2.3", MeshOps.followAddress("192.168.1.2", 24, "192.168.2.1", 24, 100, 150, setOf("192.168.2.2")))
        assertEquals("10.0.0.2", MeshOps.followAddress("192.168.1.5", 24, "10.0.0.5", 24, 100, 150, emptySet()))
        // A /25 ends at .127; .200 is not in it any more.
        assertEquals("192.168.1.2", MeshOps.followAddress("192.168.1.200", 24, "192.168.1.1", 25, 50, 70, emptySet()))
        // Inside the new pool is somewhere dnsmasq would hand out too.
        assertEquals("192.168.2.2", MeshOps.followAddress("192.168.1.120", 24, "192.168.2.1", 24, 100, 150, emptySet()))
    }

    @Test
    fun `the node's side keeps its own spelling and moves the resolver only where it was the primary`() {
        val cidr = Parsers.lanNet(Parsers.uciShow(NODE_LAN))
        assertEquals(
            listOf(
                "set network.lan.ipaddr='192.168.2.2/24'",
                "set network.lan.gateway='192.168.2.1'",
                "delete network.lan.dns",
                "add_list network.lan.dns='192.168.2.1'",
            ),
            MeshOps.followOps(cidr, "192.168.2.2", 24, "192.168.2.1", "192.168.1.1"),
        )
        val masked = Parsers.lanNet(
            Parsers.uciShow(
                NODE_LAN.replace("ipaddr='192.168.1.2/24'", "ipaddr='192.168.1.2'\nnetwork.lan.netmask='255.255.255.0'")
                    .replace("dns='192.168.1.1'", "dns='1.1.1.1'")
            )
        )
        assertEquals(
            listOf(
                "set network.lan.ipaddr='10.0.0.2'",
                "set network.lan.netmask='255.255.0.0'",
                "set network.lan.gateway='10.0.0.1'",
            ),
            MeshOps.followOps(masked, "10.0.0.2", 16, "10.0.0.1", "192.168.1.1"),
        )
    }

    // ---- the node's script ----

    @Test
    fun `the arming stores the batch and starts a detached watcher, committing nothing`() {
        val ops = listOf("set network.lan.ipaddr='192.168.2.2/24'", "set network.lan.gateway='192.168.2.1'")
        val cmd = Commands.nodeFollow(ops, gone = "192.168.1.1", arrive = "192.168.2.1", id = "abc123")
        assertTrue(cmd.contains("cat > /tmp/wrtpulse-mesh/follow.batch <<'WRTPULSE_EOF'\n${ops.joinToString("\n")}\nWRTPULSE_EOF\n"))
        assertTrue(cmd.contains("D=/tmp/wrtpulse-mesh; ID=abc123; GONE=192.168.1.1; NEW=192.168.2.1"))
        assertTrue(cmd.endsWith("</dev/null >/dev/null 2>&1 & echo armed"))
        // The only commit is the watcher's, inside the stored script.
        val outside = cmd.substringBefore("cat > /tmp/wrtpulse-mesh/follow.sh") + cmd.substringAfter("\nWRTPULSE_FILE_EOF\n")
        assertFalse(outside.contains("uci commit"))
        assertTrue(Commands.nodeFollow(ops, gone = null, arrive = "192.168.2.1", id = "x").contains("GONE=; NEW=192.168.2.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an address that is not one never reaches the script`() {
        Commands.nodeFollow(emptyList(), gone = "1.2.3.4; reboot", arrive = "192.168.2.1", id = "x")
    }

    // ---- the move ----

    @Test
    fun `a subnet move tells every node first, then moves, then the rows follow`() = runBlocking {
        val log = Log()
        val nodes = mapOf("id-2" to Node("node2", log), "id-3" to Node("node3", log))
        val s = primary(Primary(log))
        val moved = mutableListOf<Pair<String, String>>()
        s.nodes = listOf(row(2, "Node 2", "192.168.1.2"), row(3, "Node 3", "192.168.1.3"))
        s.openNode = { nodes[it.identity] }
        s.nodeMoved = { node, host -> moved += node.name to host }
        s.stageRouterIp("192.168.2.1")
        assertEquals(emptyList<String>(), s.problems())
        assertTrue(s.notes().any { it.contains("Node 2 192.168.1.2 → 192.168.2.2") && it.contains("Node 3 192.168.1.3 → 192.168.2.3") })

        assertTrue(s.apply())
        assertEquals("192.168.2.1", s.movedTo)
        assertEquals(
            listOf(
                "node2: uci show network", "node2: arm",
                "node3: uci show network", "node3: arm",
                "primary: uci batch <<'WRTPULSE_EOF'",
            ),
            log.lines,
        )
        // Both wait for the old address to go quiet; nobody needs a word after.
        val arm = nodes.getValue("id-2").commands.single { it.contains("/follow.sh") }
        assertTrue(arm.contains("GONE=192.168.1.1; NEW=192.168.2.1"))
        assertTrue(arm.contains("set network.lan.ipaddr='192.168.2.2/24'"))
        assertEquals(listOf("Node 2" to "192.168.2.2", "Node 3" to "192.168.2.3"), moved)
        assertEquals(moved, s.followedNodes.toList())
    }

    @Test
    fun `a node that cannot be told keeps the subnet where it is until the user leaves it behind`() = runBlocking {
        val log = Log()
        val reachable = Node("node2", log)
        val s = primary(Primary(log))
        s.nodes = listOf(row(2, "Node 2", "192.168.1.2"), row(3, "Node 3", "192.168.1.3"))
        s.openNode = { if (it.identity == "id-2") reachable else null }
        s.stageRouterIp("192.168.2.1")

        assertFalse(s.apply())
        assertNull(s.movedTo)
        assertEquals(listOf("Node 3: no saved key or password"), s.unreachableNodes.toList())
        assertTrue(s.error!!.startsWith("Nothing was moved."))
        assertTrue(log.lines.none { it.startsWith("primary") })
        // The one that was told is stood down again.
        assertEquals(Commands.FOLLOW_CANCEL, reachable.commands.last())
        assertEquals(1, s.pendingCount)

        assertTrue(s.apply(leaveBehind = true))
        assertEquals("192.168.2.1", s.movedTo)
        assertEquals(listOf("Node 2" to "192.168.2.2"), s.followedNodes.toList())
    }

    @Test
    fun `a prefix change keeps the gateway, so the nodes wait for word that the primary applied`() = runBlocking {
        val log = Log()
        val node = Node("node2", log)
        val s = primary(Primary(log), dhcp = DHCP_UCI)
        s.nodes = listOf(row(2, "Node 2", "192.168.1.2"))
        s.openNode = { node }
        s.stageNetmask("255.255.0.0")
        assertTrue(s.movesSubnet)

        assertTrue(s.apply())
        assertNull(s.movedTo)
        val arm = node.commands.single { it.contains("/follow.sh") }
        assertTrue(arm.contains("GONE=; NEW=192.168.1.1"))
        assertTrue(arm.contains("set network.lan.ipaddr='192.168.1.2/16'"))
        assertEquals(Commands.FOLLOW_GO, node.commands.last())
        assertTrue(log.lines.indexOf("node2: ${Commands.FOLLOW_GO}") > log.lines.indexOfFirst { it.startsWith("primary: uci batch") })
    }

    @Test
    fun `a primary batch that fails stands the nodes down`() = runBlocking {
        val log = Log()
        val node = Node("node2", log)
        val s = primary(Primary(log, fail = SshException.CommandFailed("uci batch", 1, "nope")))
        s.nodes = listOf(row(2, "Node 2", "192.168.1.2"))
        s.openNode = { node }
        s.nodeMoved = { _, _ -> error("must not move the row") }
        s.stageRouterIp("192.168.2.1")

        assertFalse(s.apply())
        assertEquals(Commands.FOLLOW_CANCEL, node.commands.last())
    }

    @Test
    fun `changes that leave the subnet alone touch no node`() = runBlocking {
        val log = Log()
        val s = primary(Primary(log), dhcp = DHCP_UCI)
        s.nodes = listOf(row(2, "Node 2", "192.168.1.2"))
        s.openNode = { error("no node should be opened") }
        s.stagePoolLimit("120")
        assertFalse(s.movesSubnet)
        assertTrue(s.apply())
        assertTrue(log.lines.all { it.startsWith("primary") })
    }

    @Test
    fun `a node already off this lan is said so, not waited for`() = runBlocking {
        val log = Log()
        val s = primary(Primary(log))
        s.nodes = listOf(row(2, "Node 2", "192.168.0.2"))
        s.openNode = { error("a node off the lan cannot be reached") }
        s.stageRouterIp("192.168.2.1")
        assertTrue(s.notes().any { it.startsWith("Node 2 (192.168.0.2) is saved at an address outside this LAN") })
        assertTrue(s.apply())
    }

    // ---- on the node itself ----

    @Test
    fun `a node cannot move its own lan away from its gateway`() {
        val s = LanStore(Primary(Log())).apply {
            ingest(
                mapOf(
                    "net" to NETWORK_UCI.replace("network.lan.ipaddr='192.168.1.1'", "network.lan.ipaddr='192.168.1.2'\nnetwork.lan.gateway='192.168.1.1'"),
                    "dhcp" to DHCP_UCI, "live" to LAN_STATUS, "leases" to "", "neigh" to "", "links" to NETDEV_LINES, "dnsmasq" to "",
                )
            )
        }
        // A new address in the same subnet is fine.
        s.stageRouterIp("192.168.1.3")
        assertTrue(s.problems().none { it.contains("goes out through") })
        s.stageRouterIp("192.168.5.2")
        assertTrue(s.problems().any { it.startsWith("This router's LAN goes out through 192.168.1.1") })
    }
}
