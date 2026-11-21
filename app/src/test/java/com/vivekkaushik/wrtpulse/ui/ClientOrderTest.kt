package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.data.Client
import com.vivekkaushik.wrtpulse.ui.screens.stableOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientOrderTest {

    private fun c(mac: String, bars: Int = 3, offline: Boolean = false) =
        Client(name = "dev-$mac", ip = "192.168.1.$mac", mac = mac, network = "LAN", bars = bars, offline = offline)

    private fun macs(list: List<Client>) = list.map { it.mac }

    @Test
    fun `nothing held yet takes the fresh order`() {
        assertEquals(listOf("a", "b", "c"), macs(stableOrder(emptyList(), listOf(c("a"), c("b"), c("c")))))
    }

    @Test
    fun `a signal reshuffle does not move the rows`() {
        val held = listOf("a", "b", "c")
        val reshuffled = listOf(c("c", bars = 4), c("a", bars = 2), c("b", bars = 1))
        assertEquals(held, macs(stableOrder(held, reshuffled)))
    }

    @Test
    fun `the rows carry the fresh data, not the held one`() {
        val out = stableOrder(listOf("a", "b"), listOf(c("b"), c("a", offline = true)))
        assertEquals(true, out.first().offline)
    }

    @Test
    fun `a device that joins goes at the bottom, below everything already shown`() {
        val held = listOf("a", "b", "c")
        val fresh = listOf(c("new", bars = 4), c("a"), c("b"), c("c"))
        assertEquals(listOf("a", "b", "c", "new"), macs(stableOrder(held, fresh)))
    }

    @Test
    fun `a device that left drops out and the rest keep their order`() {
        val held = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), macs(stableOrder(held, listOf(c("c"), c("a")))))
    }
}
