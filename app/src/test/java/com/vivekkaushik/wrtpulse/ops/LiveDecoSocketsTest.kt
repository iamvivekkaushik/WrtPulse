package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Deco M4R as a primary, exactly as it answered the mesh read mid-setup (captured
 * 2026-09-15): a seven-port chip with two holes, WAN on port 3, port 5 held for the node.
 * The drawing showed a stock five-socket router once; this is the data it had to work with.
 */
class LiveDecoSocketsTest {
    @Test
    fun `the live deco primary draws two sockets, wan and the held one`() {
        val raw = javaClass.classLoader!!.getResource("deco_live_primary.txt")!!.readText()
        val parts = Parsers.sections(raw)
        val net = Parsers.uciShow(parts["net"].orEmpty())
        val sw = Parsers.switchDevs(parts["swconfig"].orEmpty())
        val board = Parsers.boardSwitchPorts(parts["boardsw"].orEmpty())
        assertEquals("sw:5", MeshOps.heldSetupPort(net))
        assertEquals(
            listOf(CaseSocket("sw:3", "WAN", SocketRole.Wan), CaseSocket("sw:5", "lan2", SocketRole.Held)),
            MeshOps.caseSockets(net, sw, board),
        )
    }

    /** With the router's stored undo gone, the config alone is enough to put the socket back. */
    @Test
    fun `the release regenerated from the config undoes the hold`() {
        val raw = javaClass.classLoader!!.getResource("deco_live_primary.txt")!!.readText()
        val net = Parsers.uciShow(Parsers.sections(raw)["net"].orEmpty())
        assertEquals(
            listOf(
                "set network.@switch_vlan[0].ports='0t 5'",
                "delete network.wrtpulse_setup_vlan",
                "delete network.wrtpulse_setup",
                "delete network.wrtpulse_setup_dev",
                "delete firewall.wrtpulse_setup",
            ),
            MeshOps.setupReleaseOps(net, MeshOps.heldSetupPort(net)!!),
        )
    }
}
