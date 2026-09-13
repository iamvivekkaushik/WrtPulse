package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.MeshOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's Deco as primary: one WPA2 SSID per band, plus the mesh point the app wrote. */
private val PRIMARY_WIRELESS = """
    wireless.radio0=wifi-device
    wireless.radio0.type='mac80211'
    wireless.radio0.band='5g'
    wireless.radio0.channel='auto'
    wireless.radio0.htmode='VHT80'
    wireless.radio0.country='IN'
    wireless.default_radio0=wifi-iface
    wireless.default_radio0.device='radio0'
    wireless.default_radio0.network='lan'
    wireless.default_radio0.mode='ap'
    wireless.default_radio0.ssid='Casa'
    wireless.default_radio0.encryption='psk2'
    wireless.default_radio0.key='hunter22'
    wireless.radio1=wifi-device
    wireless.radio1.type='mac80211'
    wireless.radio1.band='2g'
    wireless.radio1.channel='11'
    wireless.radio1.htmode='HT40'
    wireless.radio1.country='IN'
    wireless.default_radio1=wifi-iface
    wireless.default_radio1.device='radio1'
    wireless.default_radio1.network='lan'
    wireless.default_radio1.mode='ap'
    wireless.default_radio1.ssid='Casa'
    wireless.default_radio1.encryption='none'
    wireless.wrtpulse_guest=wifi-iface
    wireless.wrtpulse_guest.device='radio1'
    wireless.wrtpulse_guest.network='wrtpulse_guest'
    wireless.wrtpulse_guest.mode='ap'
    wireless.wrtpulse_guest.ssid='Casa-Guest'
    wireless.wrtpulse_guest.encryption='psk2'
    wireless.wrtpulse_guest.key='guestpass'
    wireless.wrtpulse_mesh=wifi-iface
    wireless.wrtpulse_mesh.device='radio0'
    wireless.wrtpulse_mesh.mode='mesh'
    wireless.wrtpulse_mesh.mesh_id='OpenWrt-mesh'
    wireless.wrtpulse_mesh.encryption='sae'
    wireless.wrtpulse_mesh.key='meshkey-meshkey-meshkey'
    wireless.wrtpulse_mesh.network='lan'
""".trimIndent()

private val PRIMARY_NETWORK = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.0.1/24'
""".trimIndent()

private val PRIMARY_DHCP = """
    dhcp.lan=dhcp
    dhcp.lan.interface='lan'
    dhcp.lan.start='100'
    dhcp.lan.limit='150'
""".trimIndent()

private val STATUS = """
    { "radio0": { "up": true, "config": { "band": "5g" }, "interfaces": [
        { "section": "default_radio0", "ifname": "phy0-ap0", "config": { "mode": "ap", "ssid": "Casa" } },
        { "section": "wrtpulse_mesh", "ifname": "phy0-mesh0", "config": { "mode": "mesh", "ssid": "" } } ] },
      "radio1": { "up": true, "config": { "band": "2g" }, "interfaces": [
        { "section": "default_radio1", "ifname": "phy1-ap0", "config": { "mode": "ap", "ssid": "Casa" } } ] } }
""".trimIndent()

private val IWINFO = """
    phy0-ap0   ESSID: "Casa"
              Access Point: 9C:A2:F4:00:00:01
              Mode: Master  Channel: 149 (5.745 GHz)  HT Mode: VHT80
              Encryption: WPA2 PSK (CCMP)
    phy1-ap0   ESSID: "Casa"
              Access Point: 9C:A2:F4:00:00:02
              Mode: Master  Channel: 11 (2.462 GHz)  HT Mode: HT40
              Encryption: none
""".trimIndent()

private val MACS = """
    	Interface phy1-ap0
    		addr 9c:a2:f4:00:00:02
    		type AP
    	Interface phy0-ap0
    		addr 9c:a2:f4:00:00:01
    		type AP
    	Interface phy0-mesh0
    		addr 9c:a2:f4:00:00:03
    		type mesh point
""".trimIndent()

private val PEERS = """
    # phy0-mesh0
    Station de:ad:be:ef:00:01 (on phy0-mesh0)
    	signal:  	-58 dBm
    	mesh plink:	ESTAB
""".trimIndent()

private fun node(name: String, host: String, mac: String? = null) = RouterEntity(
    id = 7, name = name, host = host, port = 22, username = "root", model = "", summary = "",
    credential = null, lastSeenEpoch = 0, identity = "node-$name",
    meshPrimary = "primary-uuid", meshBackhaul = "wireless", meshMac = mac,
)

class MeshStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store(
        wireless: String = PRIMARY_WIRELESS,
        capable: String = "yes",
        wpad: String = "wpad-mesh-mbedtls",
        ping: String = "192.168.0.2 1.5\n192.168.0.3 -",
        nodes: List<RouterEntity> = listOf(node("Bedroom", "192.168.0.2", "de:ad:be:ef:00:01"), node("Garage", "192.168.0.3")),
    ): MeshStore =
        MeshStore(RouterSession(SshTarget("192.168.0.1", identity = "primary-uuid"), unusedClient, { error("unused") })).apply {
            nodeEntities = nodes
            ingest(
                mapOf(
                    "uci" to wireless,
                    "status" to STATUS,
                    "net" to PRIMARY_NETWORK,
                    "dhcp" to PRIMARY_DHCP,
                    "iwinfo" to IWINFO,
                    "capable" to capable,
                    "wpad" to wpad,
                    "pm" to "apk",
                    "peers" to PEERS,
                    "macs" to MACS,
                    "leases" to "1700000000 aa:bb:cc:00:00:01 192.168.0.130 pixel-8 *",
                    "neigh" to "192.168.0.50 dev br-lan lladdr 00:11:22:33:44:55 REACHABLE\nfe80::1 dev br-lan lladdr 00:11:22:33:44:55 STALE",
                    "df" to "/dev/mtdblock4 6976 848 6128 12% /overlay",
                    "board" to """{ "hostname": "Attic", "model": "TP-Link Deco M4R v1", "release": { "distribution": "OpenWrt", "version": "25.12.5" } }""",
                    "ping" to ping,
                )
            )
        }

    /** The Deco after its join, read as the current router: gateway set, DHCP off, copied SSIDs. */
    @Test
    fun `a router whose config is a node's is recognised without a record`() {
        val nodeWireless = """
            wireless.radio0=wifi-device
            wireless.radio0.band='5g'
            wireless.wrtpulse_ap_radio0=wifi-iface
            wireless.wrtpulse_ap_radio0.device='radio0'
            wireless.wrtpulse_ap_radio0.network='lan'
            wireless.wrtpulse_ap_radio0.mode='ap'
            wireless.wrtpulse_ap_radio0.ssid='OpenWrtJio'
            wireless.wrtpulse_ap_radio0.encryption='psk2'
            wireless.wrtpulse_ap_radio0.key='hunter22'
            wireless.wrtpulse_mesh=wifi-iface
            wireless.wrtpulse_mesh.device='radio0'
            wireless.wrtpulse_mesh.mode='mesh'
            wireless.wrtpulse_mesh.mesh_id='OpenWrt-mesh'
        """.trimIndent()
        val s = MeshStore(RouterSession(SshTarget("192.168.2.2", identity = "deco"), unusedClient, { error("unused") }))
        var sunk = false
        s.profileSink = { sunk = true }
        s.ingest(
            mapOf(
                "uci" to nodeWireless,
                "net" to "network.lan=interface\nnetwork.lan.ipaddr='192.168.2.2/24'\nnetwork.lan.gateway='192.168.2.1'",
                "dhcp" to "dhcp.lan=dhcp\ndhcp.lan.interface='lan'\ndhcp.lan.ignore='1'",
                "macs" to MACS,
            )
        )
        assertTrue(s.configuredAsNode)
        assertEquals("192.168.2.1", s.nodeGateway)
        assertEquals(com.vivekkaushik.wrtpulse.ops.Backhaul.Wireless, s.nodeBackhaul)
        assertEquals("9c:a2:f4:00:00:03", s.ownMeshMac)
        assertFalse(sunk)
        // The primary is not a node, whatever its SSIDs say.
        assertFalse(store().configuredAsNode)
        assertTrue(store().actsAsPrimary)
    }

    @Test
    fun `the lan ssids are the ones on lan, guest and mesh excluded`() {
        val s = store()
        assertEquals(listOf("default_radio0", "default_radio1"), s.lanAps.map { it.section })
        assertFalse(s.roamingOn)
        assertFalse(s.roamingImpossible)
        assertEquals(1, s.roamingNotes().size)
        assertTrue(s.roamingOps().contains("set wireless.default_radio0.ieee80211r='1'"))
        assertFalse(s.roamingOps().any { it.startsWith("set wireless.default_radio1.ieee80211r") })
    }

    @Test
    fun `roaming is on once every capable ssid carries it`() {
        val s = store(wireless = PRIMARY_WIRELESS.replace(
            "wireless.default_radio0.key='hunter22'",
            "wireless.default_radio0.key='hunter22'\nwireless.default_radio0.ieee80211r='1'",
        ))
        assertTrue(s.roamingOn)
    }

    @Test
    fun `the mesh point is seen, up, and on 5 GHz`() {
        val s = store()
        assertNotNull(s.meshIface)
        assertTrue(s.meshUp)
        assertEquals("radio0", s.meshRadio?.section)
        assertNull(s.wpadSwap)
        assertTrue(s.meshProblems().isEmpty())
        assertEquals(1, s.peers.size)
    }

    @Test
    fun `a basic wpad build needs the swap`() {
        val s = store(capable = "no", wpad = "wpad-basic-mbedtls")
        assertFalse(s.meshCapable)
        assertEquals("wpad-mesh-mbedtls", s.wpadSwap?.install)
        assertEquals(6128L, s.overlayFreeKb)
    }

    @Test
    fun `the mesh ops pin an auto radio to where it is`() {
        val s = store(wireless = PRIMARY_WIRELESS.substringBefore("wireless.wrtpulse_mesh=wifi-iface"))
        assertNull(s.meshIface)
        val ops = s.meshOps()
        assertEquals("set wireless.radio0.channel='149'", ops.first())
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.mesh_id='Attic-mesh'"))
        assertTrue(ops.any { it.startsWith("set wireless.wrtpulse_mesh.key='") })
    }

    @Test
    fun `nodes answer or not, and a known peer brings its signal`() {
        val nodes = store().nodes()
        assertEquals(2, nodes.size)
        assertTrue(nodes[0].online)
        assertEquals(1.5, nodes[0].rttMs!!, 0.001)
        assertEquals(-58, nodes[0].signalDbm)
        assertFalse(nodes[1].online)
        assertNull(nodes[1].signalDbm)
        assertTrue(store().strayPeers().isEmpty())
        assertEquals(1, store(nodes = emptyList()).strayPeers().size)
    }

    @Test
    fun `the profile carries what a node is built from`() {
        val p = store().profileFrom()
        assertEquals("primary-uuid", p.primaryIdentity)
        assertEquals("Attic", p.primaryName)
        assertEquals("192.168.0.1", p.primaryIp)
        assertEquals(24, p.prefix)
        assertEquals(100, p.poolStart)
        assertEquals(150, p.poolLimit)
        assertEquals(setOf("5G", "2.4G"), p.ssids.map { it.band }.toSet())
        assertEquals("hunter22", p.ssidFor("5G")?.key)
        assertEquals("none", p.ssidFor("2.4G")?.encryption)
        // `auto` resolves to the channel the radio is on, so nodes can copy it.
        assertEquals("149", p.radioFor("5G")?.channel)
        assertEquals("11", p.radioFor("2.4G")?.channel)
        assertEquals("IN", p.radioFor("5G")?.country)
        assertEquals("OpenWrt-mesh", p.meshId)
        assertEquals("meshkey-meshkey-meshkey", p.meshKey)
        assertEquals("5G", p.meshBand)
        assertTrue(p.wirelessReady)
        assertTrue(p.taken.containsAll(listOf("192.168.0.130", "192.168.0.50", "192.168.0.2", "192.168.0.3")))
        assertFalse(p.taken.any { it.contains(':') })
        assertTrue(p.primaryMacs.contains("9c:a2:f4:00:00:01"))
        assertEquals("192.168.0.4", MeshOps.freeNodeAddress(p))
    }
}
