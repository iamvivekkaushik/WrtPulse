package com.vivekkaushik.wrtpulse.ui

import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ui.screens.routerHostname
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostnameTest {

    @Test
    fun `a typed hostname becomes letters, digits and dashes`() {
        assertEquals("Living-Room-AP", routerHostname("  Living Room AP "))
        assertEquals("attic-ap", routerHostname("attic_ap!"))
        assertEquals("gw-2", routerHostname("gw.2"))
        assertEquals("OpenWrt", routerHostname("OpenWrt"))
    }

    @Test
    fun `nothing usable is null, not a stand-in — the dialog keeps Save off`() {
        assertNull(routerHostname(""))
        assertNull(routerHostname("   "))
        assertNull(routerHostname("***"))
        assertNull(routerHostname("---"))
    }

    @Test
    fun `the kernel's 63-character limit holds and never leaves a trailing dash`() {
        assertEquals(63, routerHostname("a".repeat(80))!!.length)
        // 62 letters then a space: the cut would land on the dash that space became.
        val cut = routerHostname("a".repeat(62) + " b")!!
        assertEquals(62, cut.length)
        assertTrue(!cut.endsWith("-"))
    }

    @Test
    fun `the command writes system, commits it, and reloads without a reboot`() {
        val cmd = Commands.setHostname("Attic-AP")
        assertTrue(cmd.contains("set system.@system[0].hostname='Attic-AP'"))
        assertTrue(cmd.contains("uci commit system"))
        assertTrue(cmd.contains("/etc/init.d/system reload"))
        assertTrue(!cmd.contains("reboot"))
        assertTrue(cmd.trimEnd().endsWith("echo done"))
    }
}
