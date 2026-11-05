package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptTest {

    @Test
    fun `redact masks wireless keys`() {
        val uci = """
            wireless.radio0.type='mt7603e'
            wireless.radio0.channel='6'
            wireless.default_radio0.key='hunter2hunter2'
            wireless.@wifi-iface[1].key='another-secret'
        """.trimIndent()
        val out = AiPrompt.redact(uci)
        assertFalse(out.contains("hunter2hunter2"))
        assertFalse(out.contains("another-secret"))
        assertTrue(out.contains("wireless.default_radio0.key='<redacted>'"))
        assertTrue(out.contains("wireless.@wifi-iface[1].key='<redacted>'"))
        // The innocuous lines survive word for word — redaction must not eat the diagnosis.
        assertTrue(out.contains("wireless.radio0.channel='6'"))
    }

    @Test
    fun `redact masks pppoe passwords and psk in any case`() {
        val out = AiPrompt.redact(
            "network.wan.password='pppoe-secret'\n" +
                "network.wan.Passwd='mixed-case'\n" +
                "wireless.guest.psk='wpa-passphrase'\n" +
                "wireless.wds.KEYS='abc'",
        )
        assertFalse(out.contains("pppoe-secret"))
        assertFalse(out.contains("mixed-case"))
        assertFalse(out.contains("wpa-passphrase"))
        assertFalse(out.contains("'abc'"))
        assertEquals(4, Regex("'<redacted>'").findAll(out).count())
    }

    @Test
    fun `redact leaves ssids and hostnames alone`() {
        val out = AiPrompt.redact(
            "wireless.@wifi-iface[0].ssid='Home 5G'\n" +
                "dhcp.lan.leasetime='12h'\n" +
                "network.lan.ipaddr='192.168.1.1'",
        )
        assertEquals(
            "wireless.@wifi-iface[0].ssid='Home 5G'\n" +
                "dhcp.lan.leasetime='12h'\n" +
                "network.lan.ipaddr='192.168.1.1'",
            out,
        )
    }

    @Test
    fun `redact does not cut on an equals inside a value`() {
        // A value containing "=" cannot smuggle the secret past the line match.
        val out = AiPrompt.redact("wireless.a.key='pass=word'")
        assertFalse(out.contains("pass=word"))
        assertTrue(out.contains("key='<redacted>'"))
    }

    @Test
    fun `the user message carries the question and the labeled blocks`() {
        val sections = mapOf(
            "board" to """{"model":"K2G"}""",
            "info" to """{"uptime":100}""",
            "wireless" to "wireless.radio0.channel='6'",
        )
        val out = AiPrompt.buildUserContent(sections, "why is it slow?")
        assertTrue(out.contains("Operator question: why is it slow?"))
        assertTrue(out.contains("### BOARD (ubus call system board)"))
        assertTrue(out.contains("### WIRELESS CONFIG (uci show wireless)"))
        assertTrue(out.contains("wireless.radio0.channel='6'"))
        assertTrue(out.contains("Secrets were redacted"))
    }

    @Test
    fun `no question reads as none`() {
        val out = AiPrompt.buildUserContent(mapOf("info" to "x"), "  ")
        assertTrue(out.contains("Operator question: none"))
    }

    @Test
    fun `empty sections are left out rather than sent as absent config`() {
        val out = AiPrompt.buildUserContent(mapOf("wireless" to "", "info" to "x"), "")
        assertFalse(out.contains("WIRELESS CONFIG"))
        assertTrue(out.contains("### SYSTEM"))
    }

    @Test
    fun `a huge section is capped and says so`() {
        val big = (1..500).joinToString("\n") { "line $it" }
        val out = AiPrompt.buildUserContent(mapOf("log" to big), "")
        assertTrue(out.contains("only the first ${AiPrompt.MAX_SECTION_LINES} lines are included"))
        assertFalse(out.contains("line 500\n"))
        assertTrue(out.contains("line 1\n"))
    }

    @Test
    fun `the system prompt demands the shape the screen renders`() {
        assertTrue(AiPrompt.SYSTEM.contains("VERDICT:"))
        assertTrue(AiPrompt.SYSTEM.contains("CONFIDENCE:"))
        assertTrue(AiPrompt.SYSTEM.contains("FINDINGS:"))
        assertTrue(AiPrompt.SYSTEM.contains("ROOT CAUSE:"))
        assertTrue(AiPrompt.SYSTEM.contains("SOLUTION PLAN:"))
        assertTrue(AiPrompt.SYSTEM.contains("VALIDATION:"))
        assertTrue(AiPrompt.SYSTEM.contains("VERIFY FIRST"))
        assertTrue(AiPrompt.SYSTEM.contains("does not execute your commands"))
        assertTrue(AiPrompt.SYSTEM.contains("do not guess interface"))
        assertTrue(AiPrompt.SYSTEM.contains("rollback"))
        assertFalse(AiPrompt.SYSTEM.contains("```"))
    }

    @Test
    fun `professional payload labels firewall routes wireless status and packages`() {
        val out = AiPrompt.buildUserContent(
            mapOf(
                "firewall" to "firewall.lan.input='ACCEPT'",
                "performance" to "LOADAVG\n0.12 0.20 0.18\nMEMORY\nMemAvailable: 18000 kB",
                "routes" to "default via 192.168.1.1 dev br-lan",
                "wifi_status" to "radio0: up",
                "packages" to "wpad-mesh-mbedtls - 2023-09-08",
            ),
            "",
        )
        assertTrue(out.contains("FIREWALL CONFIG"))
        assertTrue(out.contains("PERFORMANCE (load, CPU, memory, interfaces, storage)"))
        assertTrue(out.contains("ROUTES"))
        assertTrue(out.contains("LIVE WIRELESS STATUS"))
        assertTrue(out.contains("INSTALLED WIRELESS PACKAGES"))
    }

    @Test
    fun `dhcp config has its own labeled block`() {
        val out = AiPrompt.buildUserContent(mapOf("dhcpconf" to "dhcp.lan.interface='lan'"), "")
        assertTrue(out.contains("DHCP CONFIG (uci show dhcp)"))
    }

    @Test
    fun `local findings and sample deltas ride along when they exist`() {
        val out = AiPrompt.buildUserContent(
            mapOf("info" to "x"),
            "",
            findings = listOf("HIGH — Mesh backhaul is weak (tx retries +1,875 in 60 s)"),
            temporal = listOf("window: 60 s over 3 samples", "tx retries +1,875, tx failed +21"),
            health = "Network health 78/100 (Wi-Fi 82, Mesh 64, Performance 91, Security 76)",
        )
        assertTrue(out.contains("NETWORK HEALTH: Network health 78/100"))
        assertTrue(out.contains("60-SECOND PERFORMANCE SAMPLE (deltas between three samples)"))
        assertTrue(out.contains("window: 60 s over 3 samples"))
        assertTrue(out.contains("LOCAL DIAGNOSTIC FINDINGS (computed on the phone from raw counters)"))
        assertTrue(out.contains("- HIGH — Mesh backhaul is weak"))
    }

    @Test
    fun `no findings or sample leaves the payload as it was`() {
        val out = AiPrompt.buildUserContent(mapOf("info" to "x"), "")
        assertFalse(out.contains("LOCAL DIAGNOSTIC FINDINGS"))
        assertFalse(out.contains("60-SECOND PERFORMANCE SAMPLE"))
        assertFalse(out.contains("NETWORK HEALTH"))
    }

    @Test
    fun `the ready-made analyses each carry a focused question`() {
        assertEquals(11, AiPrompt.Mode.entries.size)
        AiPrompt.Mode.entries.forEach { mode ->
            assertTrue(mode.question.isNotBlank())
            assertTrue(mode.label.isNotBlank())
        }
        assertTrue(AiPrompt.Mode.WIFI.question.contains("Wi-Fi"))
        assertTrue(AiPrompt.Mode.MESH.question.contains("mesh"))
        assertTrue(AiPrompt.Mode.OUTAGE.question.contains("down"))
        assertTrue(AiPrompt.Mode.LAN.question.contains("DHCP"))
        assertTrue(AiPrompt.Mode.RADIO.question.contains("channel"))
        assertTrue(AiPrompt.Mode.CLIENT_LINK.question.contains("RSSI"))
        assertTrue(AiPrompt.Mode.NETWORK.question.contains("DNS"))
        assertTrue(AiPrompt.Mode.MESH_EXPERT.question.contains("BATMAN-ADV"))
        assertTrue(AiPrompt.Mode.AUTOMATIC.question.contains("read-only"))
    }

    @Test
    fun `the system prompt tells the provider to build on the phone-side findings`() {
        assertTrue(AiPrompt.SYSTEM.contains("local findings"))
        assertTrue(AiPrompt.SYSTEM.contains("sample deltas"))
        assertTrue(AiPrompt.SYSTEM.contains("its own line"))
    }
}
