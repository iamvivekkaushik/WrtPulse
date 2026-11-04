package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiagnosticsTest {

    // ---------- signal ----------

    @Test
    fun `a client beyond -80 dBm is a HIGH finding with the mac and iface as evidence`() {
        val findings = LocalDiagnostics.run(sections(assoc = SIGNAL_EDGE), null)
        val weak = findings.first { it.title.contains("edge of coverage") }
        assertEquals(Severity.HIGH, weak.severity)
        assertEquals(Domain.WIFI, weak.domain)
        assertTrue(weak.evidence.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(weak.evidence.contains("-84 dBm"))
        assertEquals("iw dev wlan0 station dump", weak.command)
    }

    @Test
    fun `a backhaul beyond -80 dBm is a MESH-domain finding`() {
        val findings = LocalDiagnostics.run(
            sections(
                assoc = SIGNAL_EDGE_ON_STA,
                wifiStatus = """{"radio1":{"up":true,"interfaces":[{"section":"wds1","ifname":"wlan1","config":{"mode":"sta"}}]}}""",
            ),
            null,
        )
        val weak = findings.first { it.title.contains("backhaul") }
        assertEquals(Domain.MESH, weak.domain)
        assertEquals(Severity.HIGH, weak.severity)
    }

    @Test
    fun `a signal between -75 and -80 is MED, not HIGH`() {
        val findings = LocalDiagnostics.run(sections(assoc = SIGNAL_MARGINAL), null)
        assertEquals(Severity.MED, findings.first { it.title.contains("edge of coverage") }.severity)
    }

    // ---------- temporal ----------

    @Test
    fun `climbing retries and failed become one finding with both numbers`() {
        val t = temporal(retryDelta = 1875, failedDelta = 21, windowSeconds = 60.0)
        val findings = LocalDiagnostics.run(sections(), t)
        val retrans = findings.first { it.title.contains("retransmitting") }
        assertTrue(retrans.evidence.contains("tx retries +1,875"))
        assertTrue(retrans.evidence.contains("tx failed +21"))
        assertTrue(retrans.evidence.contains("in 60 s"))
        assertEquals(Severity.HIGH, retrans.severity)
    }

    @Test
    fun `internet down with a live gateway is separated from everything down`() {
        val down = LocalDiagnostics.run(
            sections(),
            temporal(internetLossPct = 100, internetSent = 9, gatewayLossPct = 0),
        ).first { it.title.contains("Internet is down") }
        assertEquals(Severity.HIGH, down.severity)
        assertTrue(down.evidence.contains("9/9"))

        val all = LocalDiagnostics.run(
            sections(),
            temporal(internetLossPct = 100, internetSent = 9, gatewayLossPct = 100, gatewaySent = 9),
        ).first { it.title.contains("No connectivity") }
        assertEquals(Severity.HIGH, all.severity)
    }

    @Test
    fun `partial internet loss is MED with the numbers`() {
        val findings = LocalDiagnostics.run(
            sections(),
            temporal(internetLossPct = 33, internetSent = 9, internetReceived = 6, internetMaxMs = 210.0),
        )
        val loss = findings.first { it.title.contains("Packet loss on the internet path") }
        assertEquals(Severity.MED, loss.severity)
        assertTrue(loss.evidence.contains("6/9"))
        assertTrue(loss.evidence.contains("worst 210 ms"))
    }

    // ---------- storage, dhcp, bridge, firewall, packages ----------

    @Test
    fun `a nearly full overlay is flagged before it breaks writes`() {
        val full = LocalDiagnostics.run(sections(overlay = "/dev/root 12800 12700 100 99% /overlay"), null)
        assertEquals(Severity.HIGH, full.first { it.title.contains("/overlay is full") }.severity)
        val ninety = LocalDiagnostics.run(sections(overlay = "/dev/root 12800 11500 1300 90% /overlay"), null)
        assertEquals(Severity.MED, ninety.first { it.title.contains("90% full") }.severity)
    }

    @Test
    fun `two active DHCP scopes are flagged as the classic half-the-clients bug`() {
        val findings = LocalDiagnostics.run(sections(dhcpconf = DHCP_TWO_SCOPES), null)
        val finding = findings.first { it.title.contains("More than one DHCP scope") }
        assertEquals(Severity.MED, finding.severity)
        assertTrue(finding.evidence.contains("'lan'"))
        assertTrue(finding.evidence.contains("'guest'"))
    }

    @Test
    fun `a DHCP scope on an uplink interface is HIGH`() {
        val findings = LocalDiagnostics.run(sections(dhcpconf = DHCP_ON_WAN), null)
        val finding = findings.first { it.title.contains("uplink interface") }
        assertEquals(Severity.HIGH, finding.severity)
    }

    @Test
    fun `an ignored second scope is not flagged`() {
        val findings = LocalDiagnostics.run(sections(dhcpconf = DHCP_ONE_IGNORED), null)
        assertFalse(findings.any { it.title.contains("DHCP scope") })
    }

    @Test
    fun `a bridge with several ports and no STP is flagged`() {
        val findings = LocalDiagnostics.run(sections(network = NETWORK_BRIDGE_NO_STP), null)
        val finding = findings.first { it.title.contains("without STP") }
        assertEquals(Severity.MED, finding.severity)
        assertTrue(finding.raw.contains("network.br_lan.type='bridge'"))
    }

    @Test
    fun `a bridge with STP enabled is not flagged`() {
        val network = NETWORK_BRIDGE_NO_STP.replace("network.br_lan.stp='0'", "network.br_lan.stp='1'")
        val findings = LocalDiagnostics.run(sections(network = network), null)
        assertFalse(findings.any { it.title.contains("STP") })
    }

    @Test
    fun `the wan zone accepting input is a security finding`() {
        val findings = LocalDiagnostics.run(sections(firewall = FIREWALL_WAN_ACCEPT), null)
        val finding = findings.first { it.title.contains("wan zone accepts input") }
        assertEquals(Domain.SECURITY, finding.domain)
        assertEquals(Severity.MED, finding.severity)
    }

    @Test
    fun `a mesh config with wpad-basic is flagged as unable to run 11s`() {
        val findings = LocalDiagnostics.run(
            sections(
                wireless = WIRELESS_WITH_MESH,
                packages = "wpad-basic-wolfssl - 2023-09-08\nhostapd-common - 2023-09-08",
            ),
            null,
        )
        val finding = findings.first { it.title.contains("cannot run 802.11s") }
        assertEquals(Severity.HIGH, finding.severity)
        assertEquals(Domain.MESH, finding.domain)
    }

    @Test
    fun `a mesh config with wpad-mesh is not flagged`() {
        val findings = LocalDiagnostics.run(
            sections(
                wireless = WIRELESS_WITH_MESH,
                packages = "wpad-mesh-mbedtls - 2023-09-08",
            ),
            null,
        )
        assertFalse(findings.any { it.title.contains("802.11s") })
    }

    @Test
    fun `an open ap network is a HIGH security finding`() {
        val findings = LocalDiagnostics.run(sections(wireless = WIRELESS_OPEN), null)
        val open = findings.first { it.title.contains("Open network") }
        assertEquals(Severity.HIGH, open.severity)
        assertEquals(Domain.SECURITY, open.domain)
        assertTrue(open.title.contains("Cafe"))
    }

    @Test
    fun `findings sort HIGH first`() {
        val findings = LocalDiagnostics.run(
            sections(
                assoc = SIGNAL_EDGE,
                overlay = "/dev/root 12800 12700 100 99% /overlay",
            ),
            null,
        )
        assertEquals(Severity.HIGH, findings.first().severity)
        assertEquals(findings.sortedWith(compareBy({ it.severity }, { it.domain })), findings)
    }

    @Test
    fun `meshConfigured sees 80211s and WDS sta links but not plain APs`() {
        assertTrue(LocalDiagnostics.meshConfigured(sections(wireless = WIRELESS_WITH_MESH)))
        assertTrue(LocalDiagnostics.meshConfigured(sections(wireless = WIRELESS_WDS_STA)))
        assertFalse(LocalDiagnostics.meshConfigured(sections(wireless = WIRELESS_PLAIN_AP)))
    }

    @Test
    fun `interfaceModes maps the ubus status JSON to ifname-mode`() {
        val modes = LocalDiagnostics.interfaceModes(WIFI_STATUS_JSON)
        assertEquals("ap", modes["wlan0"])
        assertEquals("sta", modes["wlan1"])
    }

    @Test
    fun `uciSections reads anonymous and named sections without mixing them`() {
        val sections = LocalDiagnostics.uciSections(
            "dhcp.lan=dhcp\ndhcp.lan.interface='lan'\ndhcp.lan.ignore='1'\n" +
                "dhcp.@dhcp[0]=dhcp\ndhcp.@dhcp[0].interface='guest'\n" +
                "network.cfg03357=interface\nnetwork.cfg03357.proto='dhcp'",
            "dhcp",
        )
        assertEquals(2, sections.size)
        assertEquals("lan", sections["dhcp.lan"]?.get("interface"))
        assertEquals("1", sections["dhcp.lan"]?.get("ignore"))
        assertEquals("guest", sections["dhcp.@dhcp[0]"]?.get("interface"))
    }

    // ---------- fixtures ----------

    private fun sections(
        assoc: String = "",
        overlay: String = "/dev/root 12800 5120 7680 40% /overlay",
        dhcpconf: String = DHCP_SINGLE,
        network: String = "network.lan.ipaddr='192.168.8.1'",
        firewall: String = FIREWALL_DEFAULT,
        wireless: String = WIRELESS_PLAIN_AP,
        packages: String = "wpad-mesh-mbedtls - 2023-09-08",
        wifiStatus: String = WIFI_STATUS_JSON,
    ): Map<String, String> = mapOf(
        "assoc" to assoc,
        "overlay" to overlay,
        "dhcpconf" to dhcpconf,
        "network" to network,
        "firewall" to firewall,
        "wireless" to wireless,
        "packages" to packages,
        "wifi_status" to wifiStatus,
        "board" to """{"model":"K2G"}""",
    )

    private fun temporal(
        retryDelta: Long = 0,
        failedDelta: Long = 0,
        windowSeconds: Double = 60.0,
        internetLossPct: Int = 0,
        internetSent: Int = 9,
        internetReceived: Int = 9,
        internetMaxMs: Double? = null,
        gatewayLossPct: Int = 0,
        gatewaySent: Int = 9,
    ): TemporalStats = TemporalStats(
        windowSeconds = windowSeconds,
        sampleCount = 3,
        cpuPct = 20,
        load1 = 0.5,
        loadDelta = 0.1,
        memPct = 50,
        memPctDelta = 1,
        retryDelta = retryDelta,
        failedDelta = failedDelta,
        worstSignalDbm = -60,
        gateway = PingStats(gatewaySent, gatewaySent * (100 - gatewayLossPct) / 100, 2.0, 4.0),
        internet = PingStats(internetSent, internetSent * (100 - internetLossPct) / 100, 20.0, internetMaxMs),
        stations = listOf(
            StationDelta("wlan1", "aa:bb:cc:dd:ee:ff", -67, retryDelta, failedDelta, 6.0),
        ),
        interfaces = emptyList(),
    )

    private val SIGNAL_EDGE =
        "Station aa:bb:cc:dd:ee:ff (on wlan0)\n\tsignal:\t-84 dBm\n\ttx bitrate:\t6.0 MBit/s\n\ttx retries:\t10\n\ttx failed:\t2"
    private val SIGNAL_MARGINAL =
        "Station aa:bb:cc:dd:ee:ff (on wlan0)\n\tsignal:\t-78 dBm\n\ttx bitrate:\t24.0 MBit/s"
    private val SIGNAL_EDGE_ON_STA =
        "Station aa:bb:cc:dd:ee:ff (on wlan1)\n\tsignal:\t-84 dBm\n\ttx bitrate:\t6.0 MBit/s"

    private val DHCP_SINGLE =
        "dhcp.lan=dhcp\ndhcp.lan.interface='lan'\ndhcp.lan.start='100'\ndhcp.lan.limit='150'\ndhcp.lan.leasetime='12h'"
    private val DHCP_TWO_SCOPES =
        DHCP_SINGLE + "\ndhcp.guest=dhcp\ndhcp.guest.interface='guest'\ndhcp.guest.start='50'\ndhcp.guest.limit='50'"
    private val DHCP_ON_WAN =
        DHCP_SINGLE + "\ndhcp.wan=dhcp\ndhcp.wan.interface='wan'"
    private val DHCP_ONE_IGNORED =
        DHCP_SINGLE + "\ndhcp.guest=dhcp\ndhcp.guest.interface='guest'\ndhcp.guest.ignore='1'"

    private val NETWORK_BRIDGE_NO_STP =
        "network.br_lan=device\nnetwork.br_lan.name='br-lan'\nnetwork.br_lan.type='bridge'\n" +
            "network.br_lan.ports='lan1 lan2 wlan1'\nnetwork.br_lan.stp='0'"

    private val FIREWALL_DEFAULT =
        "firewall.@zone[0]=zone\nfirewall.@zone[0].name='lan'\nfirewall.@zone[0].input='ACCEPT'\n" +
            "firewall.@zone[1]=zone\nfirewall.@zone[1].name='wan'\nfirewall.@zone[1].input='REJECT'"
    private val FIREWALL_WAN_ACCEPT =
        "firewall.@zone[0]=zone\nfirewall.@zone[0].name='wan'\nfirewall.@zone[0].input='ACCEPT'"

    private val WIRELESS_PLAIN_AP =
        "wireless.radio0=wifi-device\nwireless.radio0.type='mt7603e'\n" +
            "wireless.default_radio0=wifi-iface\nwireless.default_radio0.device='radio0'\n" +
            "wireless.default_radio0.mode='ap'\nwireless.default_radio0.ssid='Home'\n" +
            "wireless.default_radio0.encryption='psk2'\nwireless.default_radio0.key='secret'"
    private val WIRELESS_OPEN =
        "wireless.default_radio0=wifi-iface\nwireless.default_radio0.mode='ap'\n" +
            "wireless.default_radio0.ssid='Cafe'\nwireless.default_radio0.encryption='none'"
    private val WIRELESS_WITH_MESH =
        "wireless.mesh0=wifi-iface\nwireless.mesh0.device='radio1'\nwireless.mesh0.mode='mesh'\n" +
            "wireless.mesh0.mesh_id='backhaul'\nwireless.mesh0.encryption='sae'\nwireless.mesh0.key='secret'"
    private val WIRELESS_WDS_STA =
        "wireless.wds1=wifi-iface\nwireless.wds1.mode='sta'\nwireless.wds1.wds='1'\n" +
            "wireless.wds1.ssid='Backhaul'\nwireless.wds1.encryption='psk2'"

    private val WIFI_STATUS_JSON =
        """{"radio0":{"up":true,"interfaces":[{"section":"default_radio0","ifname":"wlan0","config":{"mode":"ap"}}]},
           "radio1":{"up":true,"interfaces":[{"section":"wds1","ifname":"wlan1","config":{"mode":"sta"}}]}}"""
}
