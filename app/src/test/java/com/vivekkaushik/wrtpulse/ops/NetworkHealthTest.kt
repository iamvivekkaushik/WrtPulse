package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHealthTest {

    private fun sections(
        assoc: String = "",
        wireless: String = WIRELESS_PLAIN_AP,
        firewall: String = FIREWALL_DEFAULT,
        overlay: String = "/dev/root 12800 5120 7680 40% /overlay",
        info: String = """{"uptime":1584000,"load":[65536,32768,16384],"memory":{"total":134217728,"free":33554432,"buffered":8388608,"cached":16777216}}""",
        wifiStatus: String = "",
    ): Map<String, String> = mapOf(
        "assoc" to assoc,
        "wireless" to wireless,
        "firewall" to firewall,
        "overlay" to overlay,
        "info" to info,
        "wifi_status" to wifiStatus,
        "packages" to "wpad-mesh-mbedtls - 2023-09-08",
    )

    private fun temporal(
        retryDelta: Long = 0,
        failedDelta: Long = 0,
        cpuPct: Int? = 10,
        load1: Double = 0.3,
        memPct: Int = 50,
        internetLossPct: Int = 0,
        gatewayLossPct: Int = 0,
        stations: List<StationDelta> = emptyList(),
    ): TemporalStats = TemporalStats(
        windowSeconds = 60.0,
        sampleCount = 3,
        cpuPct = cpuPct,
        load1 = load1,
        loadDelta = 0.0,
        memPct = memPct,
        memPctDelta = 0,
        retryDelta = retryDelta,
        failedDelta = failedDelta,
        worstSignalDbm = stations.minOfOrNull { it.signalDbm },
        gateway = PingStats(9, 9 * (100 - gatewayLossPct) / 100, 2.0, 4.0),
        internet = PingStats(9, 9 * (100 - internetLossPct) / 100, 20.0, 40.0),
        stations = stations,
        interfaces = emptyList(),
    )

    @Test
    fun `a healthy router with no sample scores full marks`() {
        val h = NetworkHealth.compute(sections(), null)
        assertEquals(100, h.overall)
        assertEquals(HealthScore.Grade.HEALTHY, h.grade)
        assertEquals(100, h.wifi)
        assertEquals(100, h.performance)
        assertEquals(100, h.security)
        assertNull(h.mesh)
    }

    @Test
    fun `a weak client drags wifi down without inventing a mesh score`() {
        val h = NetworkHealth.compute(
            sections(assoc = "Station aa:bb:cc:dd:ee:ff (on wlan0)\n\tsignal:\t-84 dBm"),
            null,
        )
        assertTrue(h.wifi < 100)
        // -84 is between -85 and -80 → -20.
        assertEquals(80, h.wifi)
        assertNull(h.mesh)
        // Wi-Fi carries the mesh's weight too when there is no mesh: 80*0.5 + 100*0.3 + 100*0.2 = 90.
        assertEquals(90, h.overall)
    }

    @Test
    fun `retries and failed frames compound against the wifi score`() {
        val h = NetworkHealth.compute(
            sections(),
            temporal(retryDelta = 1875, failedDelta = 21),
        )
        // 1875/min is above the HIGH retry rate (-25) and failed > 10 (-15): 100-40 = 60.
        assertEquals(60, h.wifi)
        // 60*0.5 + 100*0.3 + 100*0.2 = 80 — exactly on the HEALTHY boundary.
        assertEquals(80, h.overall)
        assertEquals(HealthScore.Grade.HEALTHY, h.grade)
    }

    @Test
    fun `a mesh backhaul only counts against mesh when one is configured`() {
        val modes = """{"radio1":{"up":true,"interfaces":[{"section":"wds1","ifname":"wlan1","config":{"mode":"sta"}}]}}"""
        val stations = listOf(StationDelta("wlan1", "aa:bb:cc:dd:ee:ff", -84, 900, 12, 6.0))
        val withMesh = NetworkHealth.compute(
            sections(
                assoc = "Station aa:bb:cc:dd:ee:ff (on wlan1)\n\tsignal:\t-84 dBm",
                wireless = WIRELESS_WDS_STA,
                wifiStatus = modes,
            ),
            temporal(retryDelta = 900, failedDelta = 12, stations = stations),
        )
        assertNotNull(withMesh.mesh)
        // Mesh: -84 → -20, 900 retries/min → -15, failed 12 → -15: 100-50 = 50.
        assertEquals(50, withMesh.mesh!!)
        // The same retries land on Wi-Fi too (the radios carry both): -15 rate, -15 failed = 70.
        assertEquals(70, withMesh.wifi)

        val noMesh = NetworkHealth.compute(sections(assoc = "Station aa:bb:cc:dd:ee:ff (on wlan0)\n\tsignal:\t-84 dBm"), null)
        assertNull(noMesh.mesh)
    }

    @Test
    fun `an internet outage costs performance more than a clean line`() {
        val down = NetworkHealth.compute(
            sections(),
            temporal(internetLossPct = 100, gatewayLossPct = 0),
        )
        assertEquals(75, down.performance)
        val clean = NetworkHealth.compute(sections(), temporal())
        assertEquals(100, clean.performance)
    }

    @Test
    fun `cpu load and memory pressure all land on performance`() {
        val h = NetworkHealth.compute(
            sections(),
            temporal(cpuPct = 96, load1 = 4.5, memPct = 96),
        )
        // -25 (cpu) -25 (load) -25 (mem) = 25.
        assertEquals(25, h.performance)
        // 100*0.5 + 25*0.3 + 100*0.2 = 77 — attention, not yet risk.
        assertEquals(HealthScore.Grade.ATTENTION, h.grade)
    }

    @Test
    fun `an open network and an open wan zone cut security to the bone`() {
        val h = NetworkHealth.compute(
            sections(
                wireless = "wireless.default_radio0=wifi-iface\nwireless.default_radio0.mode='ap'\n" +
                    "wireless.default_radio0.ssid='Cafe'\nwireless.default_radio0.encryption='none'",
                firewall = "firewall.@zone[0]=zone\nfirewall.@zone[0].name='wan'\nfirewall.@zone[0].input='ACCEPT'",
            ),
            null,
        )
        // -40 (open network) -25 (wan input) = 35.
        assertEquals(35, h.security)
    }

    @Test
    fun `a full overlay drags performance even without a sample`() {
        val h = NetworkHealth.compute(sections(overlay = "/dev/root 12800 12700 100 99% /overlay"), null)
        // info load is 1.0 → no deduction; overlay full → -20.
        assertEquals(80, h.performance)
    }

    @Test
    fun `high load without a sample still counts`() {
        // load1 = 163840/65536 = 2.5 → the -15 band.
        val loaded = """{"uptime":100,"load":[163840,32768,16384],"memory":{"total":134217728,"free":33554432,"buffered":8388608,"cached":16777216}}"""
        val h = NetworkHealth.compute(sections(info = loaded), null)
        assertEquals(85, h.performance)
    }

    private companion object {
        val WIRELESS_PLAIN_AP =
            "wireless.default_radio0=wifi-iface\nwireless.default_radio0.mode='ap'\n" +
                "wireless.default_radio0.ssid='Home'\nwireless.default_radio0.encryption='psk2'"
        val WIRELESS_WDS_STA =
            "wireless.wds1=wifi-iface\nwireless.wds1.device='radio1'\nwireless.wds1.mode='sta'\n" +
                "wireless.wds1.wds='1'\nwireless.wds1.ssid='Backhaul'\nwireless.wds1.encryption='psk2'"
        val FIREWALL_DEFAULT =
            "firewall.@zone[0]=zone\nfirewall.@zone[0].name='lan'\nfirewall.@zone[0].input='ACCEPT'\n" +
                "firewall.@zone[1]=zone\nfirewall.@zone[1].name='wan'\nfirewall.@zone[1].input='REJECT'"
    }
}
