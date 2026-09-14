package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.ops.CaseSocket
import com.vivekkaushik.wrtpulse.ops.SocketRole
import com.vivekkaushik.wrtpulse.ui.screens.heldSocketIndex
import com.vivekkaushik.wrtpulse.ui.screens.stockSockets
import com.vivekkaushik.wrtpulse.ui.screens.targetIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class AddNodeTest {
    @Test
    fun `the held socket's number picks the lit socket on the stock drawing`() {
        assertEquals(2, heldSocketIndex("lan2"))
        assertEquals(3, heldSocketIndex("port 3"))
        assertEquals(1, heldSocketIndex("lan1"))
        // Off the drawing's four LAN sockets: clamp rather than point at nothing.
        assertEquals(4, heldSocketIndex("lan7"))
        assertEquals(1, heldSocketIndex("port 0"))
        // No number at all: the middle-ish socket, as the design shows.
        assertEquals(2, heldSocketIndex("?"))
        assertEquals(2, heldSocketIndex("eth"))
    }

    /** A real socket list lights the held one wherever it sits; the stock router keeps the old rule. */
    @Test
    fun `the plug goes into the held socket, or the first lan on a router nobody has read`() {
        val stock = stockSockets()
        assertEquals(5, stock.size)
        assertEquals(SocketRole.Wan, stock.first().role)
        assertEquals(3, targetIndex(stock, "port 3", isPrimary = true))
        assertEquals(1, targetIndex(stock, "?", isPrimary = false))          // first LAN
        // The Deco: two sockets, WAN on the first, the second held — index 1, not "port 5" → 4.
        val deco = listOf(CaseSocket("sw:3", "WAN", SocketRole.Wan), CaseSocket("sw:5", "lan2", SocketRole.Held))
        assertEquals(1, targetIndex(deco, "port 5", isPrimary = true))
        // A real list with no held socket still never points off the end.
        val two = listOf(CaseSocket("sw:3", "lan1", SocketRole.Lan), CaseSocket("sw:5", "lan2", SocketRole.Lan))
        assertEquals(1, targetIndex(two, "port 5", isPrimary = true))
    }
}
