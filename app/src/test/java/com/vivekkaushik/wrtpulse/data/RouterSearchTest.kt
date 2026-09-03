package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.screens.routerMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterSearchTest {

    private val home = RouterEntity(
        id = 1, name = "OpenWrt", host = "192.168.2.1", port = 22, username = "root",
        model = "TP Link MR500", summary = "OpenWrt 25.12.5 · r33051 · ARMv8",
        credential = null, lastSeenEpoch = 0,
    )
    private val lab = RouterEntity(
        id = 2, name = "bpi-r3-lab", host = "10.0.0.1", port = 2222, username = "admin",
        model = "Banana Pi BPI-R3", summary = "OpenWrt 24.10.2 · r28739 · ARMv8",
        credential = null, lastSeenEpoch = 0,
    )

    @Test
    fun `a blank query matches everything`() {
        assertTrue(routerMatches(home, ""))
        assertTrue(routerMatches(home, "   "))
    }

    @Test
    fun `name, address, model and release each match, case aside`() {
        assertTrue(routerMatches(home, "openwrt"))
        assertTrue(routerMatches(home, "192.168.2"))
        assertTrue(routerMatches(home, "tp"))
        assertTrue(routerMatches(home, "mr500"))
        assertTrue(routerMatches(home, "25.12"))
        assertTrue(routerMatches(lab, "BPI"))
        assertTrue(routerMatches(lab, "10.0.0.1:2222"))
        assertTrue(routerMatches(lab, "admin"))
    }

    @Test
    fun `every word has to land somewhere`() {
        assertTrue(routerMatches(home, "openwrt 2.1"))
        assertTrue(routerMatches(lab, "lab banana"))
        assertFalse(routerMatches(home, "openwrt banana"))
    }

    @Test
    fun `what is not there does not match`() {
        assertFalse(routerMatches(home, "10.0"))
        assertFalse(routerMatches(lab, "tp"))
        assertFalse(routerMatches(home, "zzz"))
    }
}
