package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCommandsTest {

    // ---------- classification ----------

    @Test
    fun `read-only commands are SAFE`() {
        listOf(
            "iw dev wlan1 station dump",
            "iwinfo",
            "uci show wireless",
            "uci get network.lan.ipaddr",
            "logread | tail -n 40",
            "ping -c 3 -W 2 1.1.1.1",
            "ubus call system board",
            "cat /proc/net/dev",
            "df -k /overlay",
            "ip route show",
            "opkg list-installed",
        ).forEach { cmd ->
            assertEquals(cmd, CommandSafety.SAFE, SafeCommands.classify(cmd))
        }
    }

    @Test
    fun `the destructive list is never copyable however it is spelled`() {
        listOf(
            "reboot",
            "reboot -f",
            "sleep 5 && reboot",
            "firstboot -y && reboot -f",
            "sysupgrade -n /tmp/firmware.bin",
            "echo saved > /tmp/x; mtd write /tmp/firmware.bin firmware",
            "rm -rf /overlay",
            "dd if=/dev/zero of=/dev/mtdblock3",
        ).forEach { cmd ->
            assertEquals(cmd, CommandSafety.BLOCKED, SafeCommands.classify(cmd))
            assertTrue(SafeCommands.reasonFor(cmd)!!.contains("refuses to copy"))
        }
    }

    @Test
    fun `config-changing commands are CHANGE and say what they change`() {
        listOf(
            "uci set wireless.radio0.channel='6'",
            "uci add_list firewall.@zone[0].network='guest'",
            "uci commit wireless",
            "wifi",
            "wifi up",
            "/etc/init.d/dnsmasq restart",
            "ifup wan",
            "opkg install wpad-mesh-mbedtls",
            "apk remove wpad-basic-wolfssl",
            "ip link set wlan1 down",
        ).forEach { cmd ->
            assertEquals(cmd, CommandSafety.CHANGE, SafeCommands.classify(cmd))
            assertTrue(SafeCommands.reasonFor(cmd)!!.contains("changes the router"))
        }
    }

    @Test
    fun `an unknown command defaults to CHANGE not SAFE`() {
        assertEquals(CommandSafety.CHANGE, SafeCommands.classify("myCustomScript --do things"))
        assertEquals(CommandSafety.CHANGE, SafeCommands.classify("foo bar baz"))
    }

    // ---------- extraction ----------

    @Test
    fun `extract pulls command lines out of an answer and classifies them`() {
        val answer = """
            VERDICT:
            NEEDS ATTENTION — the mesh backhaul is marginal.
            ADVICE:
            - Check the link from the router side:
            iw dev wlan1 station dump
            Move the node closer or aim the antennas.
            - Then pin the channel:
            uci set wireless.radio0.channel='6'; uci commit wireless
            - If all else fails, reflash: sysupgrade -n /tmp/firmware.bin
            VALIDATION:
            ping -c 10 -W 2 1.1.1.1
        """.trimIndent()
        val commands = SafeCommands.extract(answer)
        val texts = commands.map { it.text }
        assertTrue(texts.contains("iw dev wlan1 station dump"))
        assertTrue(texts.contains("ping -c 10 -W 2 1.1.1.1"))
        // The uci line arrives without its bullet and is classified as a change.
        val uci = commands.first { it.text.startsWith("uci set") }
        assertEquals(CommandSafety.CHANGE, uci.safety)
        // The destructive advice is extracted — to be refused, not to be hidden.
        val sysupgrade = commands.first { it.text.startsWith("sysupgrade") }
        assertEquals(CommandSafety.BLOCKED, sysupgrade.safety)
        // Prose lines never become commands.
        assertFalse(texts.any { it.contains("Move the node") })
        assertFalse(texts.any { it.contains("marginal") })
    }

    @Test
    fun `sentences that merely mention a tool are not commands`() {
        val answer = """
            FINDINGS:
            - Ping the router from a laptop to see latency.
            - The uci config looks fine, hostapd is running.
            ANSWER:
            The channel is busy tonight.
        """.trimIndent()
        val commands = SafeCommands.extract(answer)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `duplicate command lines appear once`() {
        val answer = "iw dev wlan0 station dump\niw dev wlan0 station dump"
        assertEquals(1, SafeCommands.extract(answer).size)
    }

    @Test
    fun `reasons are null only for safe commands`() {
        assertNull(SafeCommands.reasonFor("iwinfo"))
        assertTrue(SafeCommands.reasonFor("uci set wireless.radio0.channel='1'") != null)
        assertTrue(SafeCommands.reasonFor("reboot") != null)
    }
}
