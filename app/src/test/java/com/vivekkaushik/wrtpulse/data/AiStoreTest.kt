package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.AiSettings
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    /** A store whose transport is scripted: what the fake received lands in [sent]. */
    private data class Scripted(val store: AiStore, val sent: MutableList<String>)

    private fun store(
        scripted: String = "VERDICT: healthy.",
        deltas: List<String> = listOf("VERDICT:", " healthy."),
    ): Scripted {
        val sent = mutableListOf<String>()
        val fake = { settings: AiSettings, system: String, user: String, onDelta: (String) -> Unit ->
            sent += "$system\u0000$user"
            deltas.forEach(onDelta)
            scripted
        }
        val session = RouterSession(SshTarget("t"), unusedClient, { error("unused") })
        return Scripted(AiStore(session, fake), sent)
    }

    private fun sections(): Map<String, String> = mapOf(
        "board" to """{"model":"Phicomm K2G","release":{"version":"23.05.5","target":"ramips/mt7620"}}""",
        "info" to """{"uptime":1584000,"load":[65536,32768,16384],"memory":{"total":134217728,"free":33554432,"buffered":8388608,"cached":16777216}}""",
        "overlay" to "/dev/root 12800 5120 7680 40% /overlay",
        "wireless" to """
            wireless.radio0.type='mt7603e'
            wireless.radio0.channel='6'
            wireless.radio0.hwmode='11g'
            wireless.default_radio0.key='top-secret-passphrase'
            wireless.default_radio0.ssid='Home'
            wireless.@wifi-iface[1].ssid='Guest'
        """.trimIndent(),
        "network" to "network.lan.ipaddr='192.168.8.1'\nnetwork.wan.password='pppoe-secret'",
        "dhcp" to "1760000000 aa:bb:cc:dd:ee:ff 192.168.8.100 phone 01:aa:bb:cc:dd:ee:ff\n1760000001 11:22:33:44:55:66 192.168.8.101 tv 01:11:22:33:44:55:66",
        "assoc" to
            "Station aa:bb:cc:dd:ee:ff (on wlan0)\n\tsignal:\t-52 dBm\n\ttx bitrate:\t72.2 MBit/s\n" +
            "Station 11:22:33:44:55:66 (on wlan1)\n\tsignal:\t-67 dBm\n\ttx bitrate:\t6.0 MBit/s",
        "log" to "Sun Nov  2 10:00:00 2026 daemon.err hostapd: wlan0: could not set channel\nSun Nov  2 10:05:00 2026 user.warn firewall: config reload took 3s",
    )

    @Test
    fun `ingest summarises the sweep into chips`() {
        val s = AiStore(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))
        s.ingest(sections())
        val snap = s.snapshot
        assertNotNull(snap)
        assertEquals("Phicomm K2G · 23.05.5", snap!!.boardLabel)
        assertEquals("18d 08:00", snap.uptimeLabel)
        assertEquals(1.0, snap.load1, 1e-9)
        assertEquals(56, snap.memPct) // (134217728-33554432-8388608-16777216)/134217728 = 56.25
        assertEquals(7680L, snap.flashFreeKb)
        assertEquals(2, snap.wifiClients)
        assertEquals(2, snap.leases)
        assertEquals(2, snap.warnings)
        assertEquals(listOf("Home", "Guest"), snap.ssids)
        assertTrue(snap.chips.any { it == "2 wifi clients" })
        assertTrue(snap.chips.any { it.startsWith("load 1.00") })
    }

    @Test
    fun `ingest redacts every secret before anything can be sent`() {
        val s = AiStore(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))
        s.ingest(sections())
        val payload = s.snapshot!!.payloadFor("")
        assertFalse(payload.contains("top-secret-passphrase"))
        assertFalse(payload.contains("pppoe-secret"))
        assertTrue(payload.contains("key='<redacted>'"))
        assertTrue(payload.contains("password='<redacted>'"))
        // What stays is what a diagnosis needs.
        assertTrue(payload.contains("channel='6'"))
        assertTrue(payload.contains("ssid='Home'"))
    }

    @Test
    fun `ingest of nothing keeps the previous snapshot`() {
        val s = AiStore(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))
        s.ingest(sections())
        val before = s.snapshot
        s.ingest(emptyMap())
        assertEquals(before, s.snapshot)
    }

    @Test
    fun `analyze streams deltas into the answer and ends on the full text`() = runBlocking {
        val (s, sent) = store()
        s.ingest(sections())
        s.analyze(AiSettings(apiKey = "k"), "why is it slow?")
        assertEquals("VERDICT: healthy.", s.answer)
        assertEquals(
            "why is it slow?",
            Regex("Operator question: (.*)").find(sent.single().split("\u0000")[1])!!.groupValues[1],
        )
        assertNull(s.error)
        assertFalse(s.analyzing)
    }

    @Test
    fun `the payload the operator previews is what analyze sends`() = runBlocking {
        val (s, sent) = store()
        s.ingest(sections())
        s.analyze(AiSettings(apiKey = "k"), "")
        val sentUser = sent.single().split("\u0000")[1]
        assertEquals(s.snapshot!!.payloadFor(""), sentUser)
    }

    @Test
    fun `analyze without a snapshot says so instead of sending`() = runBlocking {
        val (s, sent) = store()
        s.analyze(AiSettings(apiKey = "k"), "")
        assertEquals("Read the router first — there is nothing to analyze yet.", s.error)
        assertNull(s.answer)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a new run replaces the previous answer`() = runBlocking {
        val (s, _) = store()
        s.ingest(sections())
        s.analyze(AiSettings(apiKey = "k"), "")
        assertEquals("VERDICT: healthy.", s.answer)
        val (again, _) = store(scripted = "VERDICT: at risk.")
        again.ingest(sections())
        again.analyze(AiSettings(apiKey = "k"), "")
        assertEquals("VERDICT: at risk.", again.answer)
    }
}
