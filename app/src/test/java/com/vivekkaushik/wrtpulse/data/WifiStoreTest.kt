package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store() = WifiStore(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))

    @Test
    fun `uci show wireless parses radios and ap networks`() {
        val uci = Parsers.uciShow(
            """
            wireless.radio0=wifi-device
            wireless.radio0.type='mac80211'
            wireless.radio0.band='2g'
            wireless.radio0.channel='11'
            wireless.radio0.htmode='HE40'
            wireless.radio1=wifi-device
            wireless.radio1.band='5g'
            wireless.radio1.htmode='HE80'
            wireless.default_radio0=wifi-iface
            wireless.default_radio0.device='radio0'
            wireless.default_radio0.mode='ap'
            wireless.default_radio0.ssid='Casa'
            wireless.default_radio0.encryption='sae'
            wireless.default_radio0.key='tr0ub4dor&3'
            wireless.@wifi-iface[1]=wifi-iface
            wireless.@wifi-iface[1].device='radio1'
            wireless.@wifi-iface[1].ssid='Casa-Guest'
            wireless.@wifi-iface[1].encryption='psk2'
            wireless.@wifi-iface[1].key='guestpass'
            wireless.@wifi-iface[1].disabled='1'
            wireless.mesh0=wifi-iface
            wireless.mesh0.device='radio1'
            wireless.mesh0.mode='mesh'
            wireless.mesh0.ssid='mesh-net'
            """.trimIndent()
        )
        val (radios, networks) = Parsers.wireless(uci)
        assertEquals(2, radios.size)
        val r0 = radios.first { it.section == "radio0" }
        assertEquals("2.4G", r0.band)
        assertEquals("11", r0.channel)
        assertEquals("HE40", r0.htmode)

        assertEquals(2, networks.size) // mesh skipped
        val guest = networks.first { it.ssid == "Casa-Guest" }
        assertEquals("@wifi-iface[1]", guest.section)
        assertTrue(guest.disabled)
        assertEquals("psk2", guest.encryption)
    }

    @Test
    fun `staging the saved value back un-stages it`() {
        val s = store()
        s.stage("default_radio0", "ssid", "Casa", "Cabana")
        assertEquals(1, s.pendingCount)
        s.stage("default_radio0", "ssid", "Casa", "Casa")
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `ops are uci set commands with quoting`() {
        val s = store()
        s.stage("default_radio0", "key", "old", "it's a secret")
        s.stage("radio0", "channel", "6", "11")
        assertEquals(
            listOf(
                "set wireless.default_radio0.key='it'\\''s a secret'",
                "set wireless.radio0.channel='11'",
            ),
            s.ops(),
        )
    }

    @Test
    fun `diff masks password values`() {
        val s = store()
        s.stage("default_radio0", "key", "hunter22", "correcthorse")
        val lines = s.diffLines().map { it.first }
        assertEquals("- default_radio0.key='••••••••'", lines[0])
        assertTrue(lines[1].startsWith("+ default_radio0.key='c"))
        assertFalse(lines[1].contains("correcthorse"))
    }

    @Test
    fun `value returns staged over saved`() {
        val s = store()
        assertEquals("Casa", s.value("x", "ssid", "Casa"))
        s.stage("x", "ssid", "Casa", "Cabana")
        assertEquals("Cabana", s.value("x", "ssid", "Casa"))
        s.revert()
        assertEquals("Casa", s.value("x", "ssid", "Casa"))
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `encryption labels`() {
        assertEquals("WPA3-SAE", Parsers.encryptionLabel("sae"))
        assertEquals("WPA2/3", Parsers.encryptionLabel("sae-mixed"))
        assertEquals("WPA2-PSK", Parsers.encryptionLabel("psk2+ccmp"))
        assertEquals("OPEN", Parsers.encryptionLabel("none"))
    }
}

class ScanCommandTest {

    /**
     * A band with no SSID has no netdev, so iwinfo has nothing to scan through. The temp
     * interface must be removed whether or not the scan itself worked.
     */
    @org.junit.Test
    fun `temporary scan interface is created and always torn down`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.scanViaTempInterface("phy0")
        org.junit.Assert.assertTrue(cmd.contains("iw phy phy0 interface add wrtpulse-scan type managed"))
        org.junit.Assert.assertTrue(cmd.contains("ip link set wrtpulse-scan up"))
        org.junit.Assert.assertTrue(cmd.contains("iwinfo wrtpulse-scan scan"))
        // Deleted before creating (stale leftovers) and after scanning, so nothing persists.
        org.junit.Assert.assertEquals(2, Regex("iw dev wrtpulse-scan del").findAll(cmd).count())
        // Output is captured first so the teardown cannot swallow the results.
        org.junit.Assert.assertTrue(cmd.indexOf("R=") < cmd.lastIndexOf("iw dev wrtpulse-scan del"))
        org.junit.Assert.assertTrue(cmd.trimEnd().endsWith("echo \"\$R\""))
        org.junit.Assert.assertTrue(!cmd.contains("uci"))   // nothing persistent is written
    }
}

class ScanParserTest {
    @org.junit.Test
    fun `iwinfo scan cells parse`() {
        val cells = com.vivekkaushik.wrtpulse.ops.Parsers.scanCells(
            """
            Cell 01 - Address: AA:BB:CC:DD:EE:01
                      ESSID: "neighbor-one"
                      Mode: Master  Channel: 6
                      Signal: -72 dBm  Quality: 38/70
                      Encryption: WPA2 PSK (CCMP)
            Cell 02 - Address: AA:BB:CC:DD:EE:02
                      ESSID: unknown
                      Mode: Master  Channel: 11
                      Signal: -85 dBm  Quality: 15/70
                      Encryption: none
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(2, cells.size)
        org.junit.Assert.assertEquals(6, cells[0].channel)
        org.junit.Assert.assertEquals(-72, cells[0].signalDbm)
        org.junit.Assert.assertEquals("neighbor-one", cells[0].ssid)
        org.junit.Assert.assertEquals(11, cells[1].channel)
    }
}
