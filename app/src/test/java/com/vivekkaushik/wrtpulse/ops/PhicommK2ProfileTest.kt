package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhicommK2ProfileTest {
    private fun profile(role: PhicommK2WdsRole) = PhicommK2Profile(
        role = role,
        ssid = "Upstream-WDS",
        key = "correct-horse-battery",
        managementIp = "192.168.1.2",
    )

    @Test
    fun apWdsBuildsBridgeStpAndDisablesDhcp() {
        val p = profile(PhicommK2WdsRole.ApWds)
        val ops = p.uciOps()

        assertTrue(ops.contains("set network.br-lan.type='bridge'"))
        assertTrue(ops.contains("add_list network.br-lan.ports='eth0.1'"))
        assertTrue(ops.contains("set network.br-lan.stp='1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_wds.mode='ap'"))
        assertTrue(ops.contains("set wireless.wrtpulse_wds.wds='1'"))
        assertTrue(ops.contains("set dhcp.lan.ignore='1'"))
    }

    @Test
    fun clientWdsIsStaOnLanNotWan() {
        val p = profile(PhicommK2WdsRole.ClientWds)
        val ops = p.uciOps()

        assertTrue(ops.contains("set wireless.wrtpulse_wds.mode='sta'"))
        assertTrue(ops.contains("set wireless.wrtpulse_wds.network='lan'"))
        assertFalse(ops.any { it.contains("firewall") })
        assertTrue(p.notes().any { it.contains("transparent bridging") })
    }

    @Test
    fun invalidProfileIsRefusedBeforeApply() {
        val p = PhicommK2Profile(
            role = PhicommK2WdsRole.ClientWds,
            ssid = "",
            key = "short",
            managementIp = "",
            lanPorts = emptyList(),
        )
        assertTrue(p.problems().size >= 4)
    }
}
