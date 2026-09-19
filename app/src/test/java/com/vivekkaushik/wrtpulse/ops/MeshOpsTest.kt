package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fresh 23.05 DSA box as flashed: four LAN ports in a bridge, a `wan` port, radios off. */
internal val FRESH_DSA_NETWORK = """
    network.loopback=interface
    network.loopback.device='lo'
    network.@device[0]=device
    network.@device[0].name='br-lan'
    network.@device[0].type='bridge'
    network.@device[0].ports='lan1' 'lan2' 'lan3' 'lan4'
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.1'
    network.lan.netmask='255.255.255.0'
    network.lan.ip6assign='60'
    network.wan=interface
    network.wan.device='wan'
    network.wan.proto='dhcp'
    network.wan6=interface
    network.wan6.device='wan'
    network.wan6.proto='dhcpv6'
""".trimIndent()

internal val FRESH_DHCP = """
    dhcp.lan=dhcp
    dhcp.lan.interface='lan'
    dhcp.lan.start='100'
    dhcp.lan.limit='150'
    dhcp.lan.leasetime='12h'
    dhcp.lan.dhcpv4='server'
    dhcp.lan.dhcpv6='server'
    dhcp.lan.ra='server'
    dhcp.lan.ra_slaac='1'
    dhcp.lan.ra_flags='managed-config' 'other-config'
    dhcp.wan=dhcp
    dhcp.wan.interface='wan'
    dhcp.wan.ignore='1'
""".trimIndent()

internal val FRESH_WIRELESS = """
    wireless.radio0=wifi-device
    wireless.radio0.type='mac80211'
    wireless.radio0.band='2g'
    wireless.radio0.channel='1'
    wireless.radio0.htmode='HT20'
    wireless.radio0.disabled='1'
    wireless.default_radio0=wifi-iface
    wireless.default_radio0.device='radio0'
    wireless.default_radio0.network='lan'
    wireless.default_radio0.mode='ap'
    wireless.default_radio0.ssid='OpenWrt'
    wireless.default_radio0.encryption='none'
    wireless.radio1=wifi-device
    wireless.radio1.type='mac80211'
    wireless.radio1.band='5g'
    wireless.radio1.channel='36'
    wireless.radio1.htmode='VHT80'
    wireless.radio1.disabled='1'
    wireless.default_radio1=wifi-iface
    wireless.default_radio1.device='radio1'
    wireless.default_radio1.network='lan'
    wireless.default_radio1.mode='ap'
    wireless.default_radio1.ssid='OpenWrt'
    wireless.default_radio1.encryption='none'
""".trimIndent()

/** The Deco with its two case sockets: LAN on VLAN 1 (port 3 and 5), WAN on VLAN 2 (port 4). */
internal val SWCONFIG_NODE_NETWORK = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.1.1/24'
    network.br_lan=device
    network.br_lan.name='br-lan'
    network.br_lan.type='bridge'
    network.br_lan.ports='eth0.1'
    network.@switch[0]=switch
    network.@switch[0].name='switch0'
    network.@switch[0].reset='1'
    network.@switch[0].enable_vlan='1'
    network.@switch_vlan[0]=switch_vlan
    network.@switch_vlan[0].device='switch0'
    network.@switch_vlan[0].vlan='1'
    network.@switch_vlan[0].ports='3 5 0t'
    network.@switch_vlan[1]=switch_vlan
    network.@switch_vlan[1].device='switch0'
    network.@switch_vlan[1].vlan='2'
    network.@switch_vlan[1].ports='4 0t'
    network.wan=interface
    network.wan.device='eth0.2'
    network.wan.proto='dhcp'
""".trimIndent()

private const val NODE_BOARD = """{ "switch0": { "ports": [ { "num": 0, "device": "eth0" }, { "num": 3, "role": "lan", "index": 1 }, { "num": 5, "role": "lan", "index": 2 }, { "num": 4, "role": "wan" } ] } }"""

private fun profile(mesh: Boolean = true, taken: List<String> = emptyList(), poolStart: Int = 100) = MeshProfile(
    primaryIdentity = "primary-uuid",
    primaryName = "Attic",
    primaryIp = "192.168.0.1",
    prefix = 24,
    poolStart = poolStart,
    poolLimit = 150,
    ssids = listOf(
        MeshSsid("2.4G", "Casa", "psk2", "hunter22", hidden = false),
        MeshSsid("5G", "Casa", "psk2", "hunter22", hidden = false),
    ),
    radios = listOf(
        MeshRadioPlan("2.4G", "6", "HT40", "IN"),
        MeshRadioPlan("5G", "149", "VHT80", "IN"),
    ),
    meshId = if (mesh) "Attic-mesh" else null,
    meshKey = if (mesh) "meshkey-meshkey-meshkey" else null,
    meshBand = if (mesh) "5G" else null,
    taken = taken,
    primaryMacs = listOf("aa:bb:cc:dd:ee:01"),
    capturedEpoch = 1_700_000_000L,
)

private fun node(
    network: String = FRESH_DSA_NETWORK,
    dhcp: String = FRESH_DHCP,
    wireless: String = FRESH_WIRELESS,
    swconfig: String = "",
    board: String = "",
    capable: Boolean = false,
    wpad: String = "wpad-basic-mbedtls",
    free: Long? = 6000,
): MeshNodeState {
    val (radios, networks) = Parsers.wireless(Parsers.uciShow(wireless))
    return MeshNodeState(
        networkUci = Parsers.uciShow(network),
        dhcpUci = Parsers.uciShow(dhcp),
        radios = radios,
        networks = networks,
        swDevs = Parsers.switchDevs(swconfig),
        boardPorts = Parsers.boardSwitchPorts(board),
        meshCapable = capable,
        wpad = wpad,
        manager = "apk",
        overlayFreeKb = free,
    )
}

class MeshRoamingTest {

    /** hostapd derives its default from `echo "$ssid" | md5sum`, newline included. */
    @Test
    fun `mobility domain is what hostapd would pick on its own`() {
        assertEquals("aaf5", MeshOps.mobilityDomain("OpenWrt"))
        assertEquals("1113", MeshOps.mobilityDomain("Casa"))
    }

    @Test
    fun `wpa2 and wpa3 get fast transition, open gets steering only`() {
        val aps = listOf(
            WifiNetwork("home", "radio1", "Casa", "psk2", "hunter22", disabled = false, network = "lan"),
            WifiNetwork("legacy", "radio0", "Casa-Open", "none", "", disabled = false, network = "lan"),
            WifiNetwork(MeshOps.MESH_SECTION, "radio1", "", "sae", "k", disabled = false, mode = "mesh", network = "lan"),
        )
        val ops = MeshOps.roamingOps(aps)
        assertTrue(ops.contains("set wireless.home.ieee80211r='1'"))
        assertTrue(ops.contains("set wireless.home.mobility_domain='1113'"))
        assertTrue(ops.contains("set wireless.home.ft_over_ds='0'"))
        assertTrue(ops.contains("set wireless.home.ft_psk_generate_local='1'"))
        assertTrue(ops.contains("set wireless.home.ieee80211k='1'"))
        assertTrue(ops.contains("set wireless.home.bss_transition='1'"))
        assertTrue(ops.contains("set wireless.legacy.bss_transition='1'"))
        assertFalse(ops.any { it.startsWith("set wireless.legacy.ieee80211r") })
        // The backhaul is never touched.
        assertFalse(ops.any { it.contains(MeshOps.MESH_SECTION) })
        assertEquals(1, MeshOps.roamingNotes(aps).size)
        assertTrue(MeshOps.roamingNotes(aps).single().contains("open"))
    }

    @Test
    fun `turning roaming off deletes only the hand-off options`() {
        val aps = listOf(WifiNetwork("home", "radio1", "Casa", "psk2", "hunter22", disabled = false, network = "lan", ieee80211r = true))
        val ops = MeshOps.roamingOffOps(aps)
        assertTrue(ops.contains("delete wireless.home.ieee80211r"))
        assertTrue(ops.contains("delete wireless.home.mobility_domain"))
        assertFalse(ops.any { it.contains("ssid") || it.contains("key") })
    }

    @Test
    fun `wpa1 mixed is treated like open`() {
        assertFalse(MeshOps.roamingCapable("psk-mixed"))
        assertTrue(MeshOps.roamingCapable("sae-mixed"))
    }
}

class MeshLinkTest {

    private fun radio(channel: String, htmode: String = "VHT80", band: String = "5G", country: String = "IN") =
        WifiRadio("radio1", band, channel, htmode, disabled = false, country = country)

    @Test
    fun `a fixed non-dfs channel is left alone`() {
        assertNull(MeshOps.pinnedChannel(radio("36"), null))
        assertTrue(MeshOps.pinChannelOps(radio("149"), 149).isEmpty())
    }

    @Test
    fun `auto pins to where the radio is, if that is safe`() {
        assertEquals(149, MeshOps.pinnedChannel(radio("auto"), 149))
        assertEquals(listOf("set wireless.radio1.channel='149'"), MeshOps.pinChannelOps(radio("auto"), 149))
    }

    @Test
    fun `a dfs channel moves to the bottom of the band at the radio's width`() {
        assertEquals(36, MeshOps.pinnedChannel(radio("100"), 100))
        assertEquals(36, MeshOps.pinnedChannel(radio("auto"), 52))
        assertEquals(36, MeshOps.pinnedChannel(radio("auto", "VHT40"), null))
    }

    @Test
    fun `the mesh point rides the lan like an ap`() {
        val ops = MeshOps.meshIfaceOps(MeshOps.MESH_SECTION, "radio1", "Attic-mesh", "it's")
        assertEquals("set wireless.wrtpulse_mesh=wifi-iface", ops.first())
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.mode='mesh'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.mesh_id='Attic-mesh'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.encryption='sae'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.key='it'\\''s'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.network='lan'"))
    }

    @Test
    fun `a primary without a country is refused`() {
        assertTrue(MeshOps.meshRadioProblems(radio("36", country = "")).single().contains("country"))
        assertTrue(MeshOps.meshRadioProblems(radio("36")).isEmpty())
        assertTrue(MeshOps.meshRadioProblems(null).single().contains("no 5 GHz"))
    }

    @Test
    fun `wpad basic and mini become the mesh build of the same ssl`() {
        assertEquals(WpadSwap("wpad-basic-mbedtls", "wpad-mesh-mbedtls"), MeshOps.wpadSwap("wpad-basic-mbedtls", false))
        assertEquals(WpadSwap("wpad-basic-openssl", "wpad-mesh-openssl"), MeshOps.wpadSwap("wpad-basic-openssl", false))
        assertEquals(WpadSwap("wpad-mini", "wpad-mesh-mbedtls"), MeshOps.wpadSwap("wpad-mini", false))
        assertNull(MeshOps.wpadSwap("wpad-mesh-mbedtls", false))
        assertNull(MeshOps.wpadSwap("wpad-basic-mbedtls", true))
        assertNull(MeshOps.wpadSwap("", false))
    }

    @Test
    fun `a wireless node with the link down and no 802_11s is offered the swap to finish`() {
        // The stranded node: joined wireless, mesh point written, but wpad-basic cannot run it.
        assertEquals(
            WpadSwap("wpad-basic-mbedtls", "wpad-mesh-mbedtls"),
            MeshOps.finishBackhaulSwap(Backhaul.Wireless, meshCapable = false, linkUp = false, installed = "wpad-basic-mbedtls"),
        )
        // Nothing to finish once the link is up, once the build already has 802.11s, or on a wired node.
        assertNull(MeshOps.finishBackhaulSwap(Backhaul.Wireless, meshCapable = false, linkUp = true, installed = "wpad-basic-mbedtls"))
        assertNull(MeshOps.finishBackhaulSwap(Backhaul.Wireless, meshCapable = true, linkUp = false, installed = "wpad-mesh-mbedtls"))
        assertNull(MeshOps.finishBackhaulSwap(Backhaul.Wired, meshCapable = false, linkUp = false, installed = "wpad-basic-mbedtls"))
        // A build the swap does not know how to grow, and one that has no wpad at all, offer nothing.
        assertNull(MeshOps.finishBackhaulSwap(Backhaul.Wireless, meshCapable = false, linkUp = false, installed = "wpad"))
        assertNull(MeshOps.finishBackhaulSwap(Backhaul.Wireless, meshCapable = false, linkUp = false, installed = ""))
    }

    @Test
    fun `the swap script is one apk transaction and a download-first opkg dance`() {
        val apk = Commands.wpadSwap("wpad-basic-mbedtls", "wpad-mesh-mbedtls", "apk")
        assertTrue(apk.contains("apk add 'wpad-mesh-mbedtls' '!wpad-basic-mbedtls'"))
        assertTrue(apk.contains("wpa_supplicant -vmesh"))
        assertTrue(apk.endsWith("& echo scheduled"))
        val opkg = Commands.wpadSwap("wpad-basic-mbedtls", "wpad-mesh-mbedtls", "opkg")
        assertTrue(opkg.indexOf("opkg download") < opkg.indexOf("opkg remove"))
        assertTrue(opkg.contains("|| opkg install 'wpad-basic-mbedtls'"))
    }

    /**
     * The foreground form for the wired control path: the same swap body, but it waits for the
     * whole thing and prints the verdict instead of backgrounding it. A detached job here was
     * culled with its channel before the download finished, leaving the node on the old build.
     */
    @Test
    fun `the wired swap runs in the foreground and prints its verdict`() {
        val sync = Commands.wpadSwap("wpad-basic-mbedtls", "wpad-mesh-mbedtls", "apk", detached = false)
        assertFalse(sync.contains("& echo scheduled"))
        assertTrue(sync.contains("apk add 'wpad-mesh-mbedtls' '!wpad-basic-mbedtls'"))
        assertTrue(sync.trimEnd().endsWith("cat ${Commands.MESH_DIR}/swap"))
        // Both forms write the same marker, so the verdict the caller reads means the same thing.
        assertTrue(sync.contains("echo ok > ${Commands.MESH_DIR}/swap"))
        assertTrue(sync.contains("echo failed > ${Commands.MESH_DIR}/swap"))
    }
}

class MeshAddressTest {

    @Test
    fun `the node takes the lowest free host below the pool`() {
        assertEquals("192.168.0.2", MeshOps.freeNodeAddress(profile()))
        assertEquals("192.168.0.3", MeshOps.freeNodeAddress(profile(taken = listOf("192.168.0.2"))))
        assertEquals("192.168.0.4", MeshOps.freeNodeAddress(profile(taken = listOf("192.168.0.2")), alsoTaken = setOf("192.168.0.3")))
    }

    @Test
    fun `a pool at the bottom pushes the node above it`() {
        assertEquals("192.168.0.152", MeshOps.freeNodeAddress(profile(poolStart = 2)))
    }

    @Test
    fun `hostnames are letters digits and dashes`() {
        assertEquals("Node-2", MeshOps.hostnameOf("Node 2"))
        assertEquals("Attic-Node", MeshOps.hostnameOf("  Attic / Node! "))
        assertEquals("node", MeshOps.hostnameOf("***"))
        assertEquals("Node 2", MeshOps.defaultNodeName(0))
        assertEquals("Node 4", MeshOps.defaultNodeName(2))
    }

    @Test
    fun `the profile survives json`() {
        val p = profile(taken = listOf("192.168.0.50"))
        val back = MeshProfile.fromJson(p.toJson())
        assertEquals(p, back)
        assertNull(MeshProfile.fromJson("{}"))
        assertNull(MeshProfile.fromJson("not json"))
    }

    @Test
    fun `candidates are the OpenWrt leases nobody saved`() {
        val leases = Parsers.leases(
            """
            1700000000 aa:bb:cc:00:00:01 192.168.0.130 OpenWrt *
            1700000000 aa:bb:cc:00:00:02 192.168.0.131 pixel-8 *
            1700000000 aa:bb:cc:00:00:03 192.168.0.132 * *
            1700000000 aa:bb:cc:00:00:04 192.168.0.133 OpenWrt *
            """.trimIndent()
        )
        val found = MeshOps.nodeCandidates(leases, setOf("192.168.0.133"))
        assertEquals(listOf("192.168.0.130", "192.168.0.132"), found.map { it.ip })
    }
}

class NodeOpsTest {

    @Test
    fun `a fresh dsa box becomes a dumb ap with the primary's ssids`() {
        val ops = MeshOps.nodeOps(profile(), node(), "Node 2", Backhaul.Wired, "192.168.0.2")
        assertEquals("set system.@system[0].hostname='Node-2'", ops.first())
        assertTrue(ops.contains("set network.lan.proto='static'"))
        assertTrue(ops.contains("set network.lan.ipaddr='192.168.0.2'"))
        assertTrue(ops.contains("set network.lan.netmask='255.255.255.0'"))
        assertTrue(ops.contains("set network.lan.gateway='192.168.0.1'"))
        assertTrue(ops.contains("delete network.lan.dns"))
        assertTrue(ops.contains("add_list network.lan.dns='192.168.0.1'"))
        assertTrue(ops.contains("delete network.lan.ip6assign"))
        assertTrue(ops.contains("set dhcp.lan.ignore='1'"))
        assertTrue(ops.contains("delete dhcp.lan.ra"))
        assertTrue(ops.contains("delete dhcp.lan.dhcpv6"))
        assertTrue(ops.contains("delete dhcp.lan.ra_slaac"))
        assertTrue(ops.contains("delete dhcp.lan.ra_flags"))
        assertTrue(ops.contains("delete dhcp.wan"))
        // The wan port joins the bridge, then the interfaces that named it go.
        assertTrue(ops.contains("delete network.@device[0].ports"))
        assertTrue(ops.contains("add_list network.@device[0].ports='lan4'"))
        assertTrue(ops.contains("add_list network.@device[0].ports='wan'"))
        assertTrue(ops.indexOf("add_list network.@device[0].ports='wan'") < ops.indexOf("delete network.wan"))
        assertTrue(ops.contains("delete network.wan"))
        assertTrue(ops.contains("delete network.wan6"))
        // The stock SSIDs go; the primary's come in with hand-off; the radios come on.
        assertTrue(ops.contains("delete wireless.default_radio0"))
        assertTrue(ops.contains("delete wireless.default_radio1"))
        assertTrue(ops.contains("set wireless.radio0.disabled='0'"))
        assertTrue(ops.contains("set wireless.radio1.disabled='0'"))
        assertTrue(ops.contains("set wireless.radio1.country='IN'"))
        assertTrue(ops.contains("set wireless.radio1.htmode='VHT80'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.ssid='Casa'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.key='hunter22'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.encryption='psk2'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.network='lan'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.ieee80211r='1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1.mobility_domain='1113'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio0.ssid='Casa'"))
        // Wired: no mesh point, and the node's channels are its own.
        assertFalse(ops.any { it.contains(MeshOps.MESH_SECTION) })
        assertFalse(ops.contains("set wireless.radio1.channel='149'"))
    }

    @Test
    fun `a wireless node gets the mesh point on the primary's band and channel`() {
        val ops = MeshOps.nodeOps(profile(), node(), "Node 2", Backhaul.Wireless, "192.168.0.2")
        assertTrue(ops.contains("set wireless.wrtpulse_mesh=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.device='radio1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.mesh_id='Attic-mesh'"))
        assertTrue(ops.contains("set wireless.wrtpulse_mesh.key='meshkey-meshkey-meshkey'"))
        assertTrue(ops.contains("set wireless.radio1.channel='149'"))
        // The 2.4 GHz SSID sits on the radio without the mesh point, so the node stays reachable.
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio0.device='radio0'"))
        assertFalse(ops.contains("set wireless.radio0.channel='6'"))
    }

    @Test
    fun `a cidr lan keeps its spelling`() {
        val network = FRESH_DSA_NETWORK
            .replace("network.lan.ipaddr='192.168.1.1'\n    network.lan.netmask='255.255.255.0'", "network.lan.ipaddr='192.168.1.1/24'")
            .replace("network.lan.ipaddr='192.168.1.1'\nnetwork.lan.netmask='255.255.255.0'", "network.lan.ipaddr='192.168.1.1/24'")
        val ops = MeshOps.nodeOps(profile(), node(network = network), "n", Backhaul.Wired, "192.168.0.2")
        assertTrue(ops.contains("set network.lan.ipaddr='192.168.0.2/24'"))
        assertFalse(ops.any { it.startsWith("set network.lan.netmask") })
    }

    @Test
    fun `on a filtering bridge the wan port joins the lan vlan untagged`() {
        val ops = MeshOps.foldWanOps(node(network = NETWORK_UCI))
        assertTrue(ops.contains("add_list network.@device[0].ports='wan'"))
        assertTrue(ops.contains("add_list network.@bridge-vlan[0].ports='wan:u*'"))
        assertTrue(ops.contains("add_list network.@bridge-vlan[0].ports='lan4:t'"))
        assertFalse(ops.any { it.contains("bridge-vlan[1]") })
    }

    @Test
    fun `on swconfig the wan socket joins the lan vlan and the wan vlan goes`() {
        val ops = MeshOps.foldWanOps(node(network = SWCONFIG_NODE_NETWORK, swconfig = SWCONFIG_OUT, board = NODE_BOARD))
        assertEquals(
            listOf("set network.@switch_vlan[0].ports='0t 3 4 5'", "delete network.@switch_vlan[1]"),
            ops,
        )
    }

    @Test
    fun `without a board file the wan socket is the wan vlan's untagged member`() {
        val ops = MeshOps.foldWanOps(node(network = SWCONFIG_NODE_NETWORK, swconfig = SWCONFIG_OUT))
        assertTrue(ops.contains("set network.@switch_vlan[0].ports='0t 3 4 5'"))
    }

    @Test
    fun `a separate wan netdev under an isp tag goes into the bridge bare`() {
        val network = """
            network.lan=interface
            network.lan.device='br-lan'
            network.lan.proto='static'
            network.lan.ipaddr='192.168.1.1'
            network.lan.netmask='255.255.255.0'
            network.br_lan=device
            network.br_lan.name='br-lan'
            network.br_lan.type='bridge'
            network.br_lan.ports='lan1' 'lan2'
            network.wan_vlan=device
            network.wan_vlan.name='eth1.201'
            network.wan_vlan.type='8021q'
            network.wan_vlan.ifname='eth1'
            network.wan_vlan.vid='201'
            network.wan=interface
            network.wan.device='eth1.201'
            network.wan.proto='pppoe'
        """.trimIndent()
        val ops = MeshOps.foldWanOps(node(network = network))
        assertTrue(ops.contains("add_list network.br_lan.ports='eth1'"))
        assertFalse(ops.any { it.contains("eth1.201") })
    }

    @Test
    fun `a single-port board has nothing to fold`() {
        val network = """
            network.lan=interface
            network.lan.device='eth0'
            network.lan.proto='static'
            network.lan.ipaddr='192.168.1.1'
            network.lan.netmask='255.255.255.0'
        """.trimIndent()
        assertTrue(MeshOps.foldWanOps(node(network = network)).isEmpty())
        val ops = MeshOps.nodeOps(profile(), node(network = network), "n", Backhaul.Wired, "192.168.0.2")
        assertFalse(ops.contains("delete network.wan"))
    }

    @Test
    fun `problems stop a join that cannot work`() {
        val p = profile(mesh = false)
        assertTrue(MeshOps.nodeProblems(p, node(), Backhaul.Wired, "192.168.0.2", null).isEmpty())
        assertTrue(MeshOps.nodeProblems(p, node(), Backhaul.Wireless, "192.168.0.2", null).single().contains("no mesh link"))
        val noAddress = MeshOps.nodeProblems(p, node(), Backhaul.Wired, null, null)
        assertTrue(noAddress.single().contains("No free address"))
        assertTrue(MeshOps.nodeProblems(p, node(), Backhaul.Wired, "192.168.0.2", "someone-else").single().contains("another primary"))
        assertTrue(MeshOps.nodeProblems(profile(), node(free = 500), Backhaul.Wireless, "192.168.0.2", null).single().contains("free on the overlay"))
        assertTrue(MeshOps.nodeProblems(profile(), node(capable = true), Backhaul.Wireless, "192.168.0.2", null).isEmpty())
        val only24 = FRESH_WIRELESS.substringBefore("wireless.radio1=wifi-device")
        assertTrue(MeshOps.nodeProblems(profile(), node(wireless = only24, capable = true), Backhaul.Wireless, "192.168.0.2", null).single().contains("no 5G radio"))
        assertTrue(MeshOps.nodeProblems(p, node(wpad = "wpad-mini"), Backhaul.Wired, "192.168.0.2", null).single().contains("wpad-mini"))
    }

    @Test
    fun `the node batch is armed, detached and confirmable`() {
        val script = Commands.nodeApply(listOf("set a.b=c"), MeshOps.NODE_PACKAGES, 180)
        assertTrue(script.contains("sleep 180"))
        assertTrue(script.contains("cp /etc/config/wireless /tmp/wrtpulse-mesh/wireless"))
        assertTrue(script.contains("uci commit system && uci commit network && uci commit dhcp && uci commit wireless"))
        assertTrue(script.trimEnd().endsWith("& echo scheduled"))
        assertTrue(script.contains("/etc/init.d/system reload"))
        assertTrue(Commands.NODE_CONFIRM.contains("touch /tmp/wrtpulse-mesh/confirm"))
        assertTrue(Commands.NODE_SERVICES_OFF.contains("firewall dnsmasq odhcpd"))
    }
}

class NodeSyncTest {

    private val radios = Parsers.wireless(Parsers.uciShow(FRESH_WIRELESS)).first

    /** What a node carries after a join from [profile]: the copied sections, hand-off included. */
    private fun copied(p: MeshProfile): List<WifiNetwork> =
        radios.flatMap { r ->
            MeshOps.ssidsFor(p, r.band).mapIndexed { i, s ->
                val ft = MeshOps.roamingCapable(s.encryption)
                WifiNetwork(MeshOps.apSection(r.section, i), r.section, s.ssid, s.encryption, s.key, disabled = false,
                    network = "lan", hidden = s.hidden, ieee80211r = ft, mobilityDomain = if (ft) MeshOps.mobilityDomain(s.ssid) else "")
            }
        }

    @Test
    fun `a node that copied the profile is in sync, whatever its section names`() {
        val p = profile()
        assertTrue(MeshOps.apsInSync(p, radios, copied(p)))
        val renamedSections = copied(p).map { it.copy(section = "wrtpulse_ap_x_${it.section.hashCode()}") }
        assertTrue(MeshOps.apsInSync(p, radios, renamedSections))
    }

    @Test
    fun `a new password, a renamed ssid or an added ssid is drift`() {
        val p = profile()
        val have = copied(p)
        val newKey = p.copy(ssids = p.ssids.map { it.copy(key = "different1") })
        assertFalse(MeshOps.apsInSync(newKey, radios, have))
        assertEquals("password, security or hand-off differs", MeshOps.driftSummary(newKey, radios, have))
        val renamed = p.copy(ssids = p.ssids.map { it.copy(ssid = "Casa2") })
        assertFalse(MeshOps.apsInSync(renamed, radios, have))
        assertTrue(MeshOps.driftSummary(renamed, radios, have).contains("Casa → Casa2"))
        val added = p.copy(ssids = p.ssids + MeshSsid("5G", "Casa-Work", "sae", "workpass1", hidden = false))
        assertFalse(MeshOps.apsInSync(added, radios, have))
        assertEquals("1 SSID to add: Casa-Work", MeshOps.driftSummary(added, radios, have))
    }

    @Test
    fun `a node whose hand-off domain belongs to an old name is out of date`() {
        val p = profile()
        val have = copied(p).map { it.copy(mobilityDomain = "e8df") }
        assertFalse(MeshOps.apsInSync(p, radios, have))
        assertEquals("hand-off domain does not match the SSID", MeshOps.driftSummary(p, radios, have))
        val right = copied(p).map { it.copy(mobilityDomain = MeshOps.mobilityDomain(it.ssid)) }
        assertTrue(MeshOps.apsInSync(p, radios, right))
    }

    @Test
    fun `a push drops every copied section and writes the current set, two per band if need be`() {
        val p = profile().let { it.copy(ssids = it.ssids + MeshSsid("5G", "Casa-Work", "sae", "workpass1", hidden = true)) }
        val ops = MeshOps.nodeApOps(p, radios, copied(profile()))
        assertTrue(ops.contains("delete wireless.wrtpulse_ap_radio0"))
        assertTrue(ops.contains("delete wireless.wrtpulse_ap_radio1"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1_2=wifi-iface"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1_2.ssid='Casa-Work'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1_2.hidden='1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_ap_radio1_2.ieee80211r='1'"))
        // The 2.4 GHz radio has only the one SSID of its band.
        assertFalse(ops.any { it.startsWith("set wireless.wrtpulse_ap_radio0_2") })
        // Deletions come first, so a section is never set and then dropped.
        assertTrue(ops.indexOfLast { it.startsWith("delete ") } < ops.indexOfFirst { it.startsWith("set ") })
        // The same lines the join writes, so the two can never disagree — both leave out what
        // the node's hostapd cannot take (no probe answer here, so 802.11v).
        val skip = MeshOps.unsupportedRoaming(node().hostapdUnknown)
        assertTrue(MeshOps.nodeOps(profile(), node(), "n", Backhaul.Wired, "192.168.0.2").containsAll(MeshOps.nodeApOps(profile(), radios, emptyList(), skip)))
        assertFalse(MeshOps.nodeOps(profile(), node(), "n", Backhaul.Wired, "192.168.0.2").any { it.contains("bss_transition") })
    }

    @Test
    fun `a wireless node follows the primary's channel and width, a wired one only the width`() {
        val p = profile()
        val plan5 = p.radioFor("5G")!!
        // A fresh box's radios are off and have no country; a joined node's are on and carry the primary's.
        val radios = this.radios.map { it.copy(disabled = false, country = p.radioFor(it.band)?.country.orEmpty(), htmode = p.radioFor(it.band)?.htmode ?: it.htmode) }
        val wireless = MeshOps.nodeRadioOps(p, radios, meshRadio = "radio1")
        assertTrue(wireless.contains("set wireless.radio1.channel='${plan5.channel}'"))
        assertTrue(wireless.contains("set wireless.radio1.htmode='${plan5.htmode}'"))
        val wired = MeshOps.nodeRadioOps(p, radios, meshRadio = null)
        assertFalse(wired.any { it.startsWith("set wireless.radio1.channel=") && !it.endsWith("'auto'") })
        // Drift is judged the same way: the mesh radio on the wrong channel is out of date, a wired one is not.
        val moved = radios.map { if (it.section == "radio1") it.copy(channel = "36") else it }
        assertEquals("5G channel 36 → ${plan5.channel}", MeshOps.radioDrift(p, moved, meshRadio = "radio1"))
        assertNull(MeshOps.radioDrift(p, moved, meshRadio = null))
        val synced = radios.map { if (it.section == "radio1") it.copy(channel = plan5.channel) else it }
        assertTrue(MeshOps.radiosInSync(p, synced, meshRadio = "radio1"))
    }

    @Test
    fun `the mesh watchdog script is installed once and runs from cron`() {
        val script = Commands.MESH_WATCH_INSTALL
        assertTrue(script.contains("cat > /usr/bin/wrtpulse-meshwatch <<'WRTPULSE_EOF'"))
        assertTrue(script.contains("mesh plink:.*ESTAB"))
        assertTrue(script.contains("MESH ID: "))
        assertTrue(script.contains("uci commit wireless; wifi reload"))
        assertTrue(script.contains("grep -q wrtpulse-meshwatch /etc/crontabs/root"))
        assertTrue(script.contains("/etc/init.d/cron enable"))
        assertTrue(Commands.MESH_WATCH_REMOVE.contains("sed -i '/wrtpulse-meshwatch/d'"))
    }

    @Test
    fun `a band the primary lacks borrows the other band's ssid`() {
        val only5 = profile().let { it.copy(ssids = it.ssids.filter { s -> s.band == "5G" }) }
        assertEquals("Casa", MeshOps.ssidsFor(only5, "2.4G").single().ssid)
    }
}

class MeshParsersTest {

    @Test
    fun `iw dev lists every netdev with its mac and type`() {
        val text = """
            	Interface phy1-ap0
            		addr 9C:A2:F4:12:34:56
            		type AP
            	Interface phy0-mesh0
            		addr 9c:a2:f4:12:34:57
            		type mesh point
        """.trimIndent()
        val devs = Parsers.iwDevs(text)
        assertEquals(2, devs.size)
        assertEquals(IwDev("phy1-ap0", "9c:a2:f4:12:34:56", "AP"), devs[0])
        assertEquals("mesh point", devs[1].type)
    }

    @Test
    fun `station dump names each peer with its signal and link state`() {
        val text = """
            # phy0-mesh0
            Station aa:bb:cc:dd:ee:01 (on phy0-mesh0)
            	inactive time:	10 ms
            	signal:  	-61 [-64, -63] dBm
            	mesh llid:	12
            	mesh plink:	ESTAB
            Station aa:bb:cc:dd:ee:02 (on phy0-mesh0)
            	signal:  	-80 dBm
            	mesh plink:	LISTEN
        """.trimIndent()
        val peers = Parsers.meshPeers(text)
        assertEquals(2, peers.size)
        assertEquals(MeshPeer("phy0-mesh0", "aa:bb:cc:dd:ee:01", -61, true), peers[0])
        assertEquals(-80, peers[1].signalDbm)
        assertFalse(peers[1].established)
        assertTrue(Parsers.meshPeers("").isEmpty())
    }

    @Test
    fun `ping results keep the ones that answered and the ones that did not`() {
        val results = Parsers.pingResults("192.168.0.2 1.234\n192.168.0.3 -\n")
        assertEquals(1.234, results["192.168.0.2"]!!, 0.0001)
        assertTrue(results.containsKey("192.168.0.3"))
        assertNull(results["192.168.0.3"])
        assertNotNull(Commands.pingHosts(listOf("192.168.0.2", "bad;rm")).let { assertFalse(it.contains("bad")); it })
        assertEquals("true", Commands.pingHosts(emptyList()))
    }
}

class ExtraNetsTest {

    private val guest = MeshExtraNet(
        "guest", 3, isolate = true,
        ssids = listOf(MeshSsid("2.4G", "Casa-Guest", "psk2", "guestpass", hidden = false), MeshSsid("5G", "Casa-Guest", "psk2", "guestpass", hidden = false)),
    )
    private val iot5only = MeshExtraNet("iot", 4, isolate = false, ssids = listOf(MeshSsid("5G", "Casa-IoT", "psk2", "iotpass1", hidden = false)))
    private val withExtras = profile().copy(extras = listOf(guest, iot5only))
    private val radios = Parsers.wireless(Parsers.uciShow(FRESH_WIRELESS)).first

    @Test
    fun `the profile carries its extra networks through json`() {
        val back = MeshProfile.fromJson(withExtras.toJson())!!
        assertEquals(listOf("guest", "iot"), back.extras.map { it.name })
        assertEquals(3, back.extras[0].vid)
        assertTrue(back.extras[0].isolate)
        assertEquals("Casa-IoT", back.extras[1].ssids.single().ssid)
        // An old profile without the field reads as having none.
        assertTrue(MeshProfile.fromJson(profile().toJson())!!.extras.isEmpty())
    }

    @Test
    fun `a dsa wireless node gets a bridge, a mesh vlan, socket vlans and an ap per band it has`() {
        val net = Parsers.uciShow(FRESH_DSA_NETWORK)
        val ops = MeshOps.nodeExtraOps(withExtras, radios, meshRadio = "radio1", meshIfname = null, net, emptyList(), existingNetworks = emptyList())
        assertTrue(ops.contains("set network.wrtpulse_x_guest_dev.name='br-x-guest'"))
        assertTrue(ops.contains("set network.wrtpulse_x_guest_mesh.name='phy1-mesh0.3'"))
        assertTrue(ops.contains("add_list network.wrtpulse_x_guest_dev.ports='phy1-mesh0.3'"))
        assertTrue(ops.any { it.startsWith("set network.wrtpulse_x_guest_lan1.name='lan1.3'") })
        assertTrue(ops.contains("set network.wrtpulse_x_guest.proto='none'"))
        assertTrue(ops.contains("set wireless.wrtpulse_x_guest_ap_radio0.ssid='Casa-Guest'"))
        assertTrue(ops.contains("set wireless.wrtpulse_x_guest_ap_radio0.isolate='1'"))
        assertTrue(ops.contains("set wireless.wrtpulse_x_guest_ap_radio0.network='wrtpulse_x_guest'"))
        assertTrue(ops.contains("set wireless.wrtpulse_x_iot_ap_radio1.ssid='Casa-IoT'"))
        // IoT is 5 GHz only on the primary, so the 2.4 GHz radio gets no IoT AP.
        assertFalse(ops.any { it.startsWith("set wireless.wrtpulse_x_iot_ap_radio0") })
        assertFalse(ops.any { it.contains("wrtpulse_x_iot_ap_radio1.isolate") })
        // A node with only a 2.4 GHz radio leaves a 5 GHz-only network out entirely.
        val only24 = radios.filter { it.band == "2.4G" }
        val ops24 = MeshOps.nodeExtraOps(withExtras, only24, meshRadio = null, meshIfname = null, net, emptyList(), emptyList())
        assertFalse(ops24.any { it.contains("wrtpulse_x_iot") })
        assertTrue(ops24.any { it.contains("wrtpulse_x_guest_ap_radio0") })
    }

    @Test
    fun `a swconfig node carries the vlans tagged across its lan ports to the cpu`() {
        val net = Parsers.uciShow(SWCONFIG_NODE_NETWORK)
        val sw = Parsers.switchDevs(SWCONFIG_OUT)
        val ops = MeshOps.nodeExtraOps(withExtras, radios, meshRadio = "radio1", meshIfname = "phy1-mesh0", net, sw, emptyList())
        assertTrue(ops.contains("set network.wrtpulse_x_guest_vlan=switch_vlan"))
        assertTrue(ops.contains("set network.wrtpulse_x_guest_vlan.vlan='3'"))
        assertTrue(ops.any { it.startsWith("set network.wrtpulse_x_guest_vlan.ports='") && it.contains("0t") })
        assertTrue(ops.contains("add_list network.wrtpulse_x_guest_dev.ports='eth0.3'"))
        assertTrue(ops.contains("add_list network.wrtpulse_x_guest_dev.ports='phy1-mesh0.3'"))
    }

    @Test
    fun `a sync rewrites the extra sections and counts a missing guest ap as drift`() {
        val net = Parsers.uciShow(FRESH_DSA_NETWORK + "\nnetwork.wrtpulse_x_guest=interface\nnetwork.wrtpulse_x_guest_dev=device")
        val old = WifiNetwork("wrtpulse_x_guest_ap_radio0", "radio0", "Old-Guest", "psk2", "x", disabled = false, network = "wrtpulse_x_guest")
        val ops = MeshOps.nodeExtraOps(withExtras, radios, null, null, net, emptyList(), listOf(old))
        assertTrue(ops.contains("delete wireless.wrtpulse_x_guest_ap_radio0"))
        assertTrue(ops.contains("delete network.wrtpulse_x_guest"))
        assertTrue(ops.contains("delete network.wrtpulse_x_guest_dev"))
        assertTrue(ops.indexOf("delete network.wrtpulse_x_guest_dev") < ops.indexOf("set network.wrtpulse_x_guest_dev=device"))
        // Only LAN copies present: out of date until the guest AP exists.
        val lanOnly = radios.flatMap { r ->
            MeshOps.ssidsFor(withExtras, r.band).mapIndexed { i, s ->
                WifiNetwork(MeshOps.apSection(r.section, i), r.section, s.ssid, s.encryption, s.key, disabled = false, network = "lan",
                    ieee80211r = true, mobilityDomain = MeshOps.mobilityDomain(s.ssid))
            }
        }
        assertFalse(MeshOps.apsInSync(withExtras, radios, lanOnly))
        assertTrue(MeshOps.driftSummary(withExtras, radios, lanOnly).contains("Casa-Guest"))
    }
}

/** The probe asks hostapd itself; grepping the binary was not a test (the full build carries the literal anyway). */
class HostapdProbeTest {

    @Test
    fun `the probe ends with a marker so hostapd exits while still parsing`() {
        val probe = Commands.HOSTAPD_PROBE
        assertTrue(probe.contains("hostapd /tmp/wrtpulse-hostapd-probe.conf"))
        assertTrue(probe.indexOf("bss_transition=1") < probe.indexOf("wrtpulse_probe_end=1"))
        assertTrue(probe.trimEnd().endsWith("rm -f /tmp/wrtpulse-hostapd-probe.conf"))
        // Both the primary's and the would-be node's state reads carry it.
        assertTrue(Commands.meshState(emptyList()).contains("echo ___wrt___ hostapd; "))
        assertTrue(Commands.MESH_NODE_STATE.contains("echo ___wrt___ hostapd; "))
    }

    @Test
    fun `the answer is the set of unknown items, or no answer at all`() {
        assertEquals(emptySet<String>(), Parsers.hostapdUnknownItems("wrtpulse_probe_end\n"))
        assertEquals(setOf("bss_transition"), Parsers.hostapdUnknownItems("bss_transition\nwrtpulse_probe_end\n"))
        assertNull(Parsers.hostapdUnknownItems(""))                      // no hostapd: nothing was parsed
        assertNull(Parsers.hostapdUnknownItems("sh: hostapd: not found"))
    }

    @Test
    fun `unknown items map back to the uci options that produce them`() {
        assertEquals(setOf("bss_transition"), MeshOps.unsupportedRoaming(setOf("bss_transition")))
        assertEquals(setOf("ieee80211k"), MeshOps.unsupportedRoaming(setOf("rrm_beacon_report")))
        assertEquals(setOf("ieee80211r"), MeshOps.unsupportedRoaming(setOf("ft_over_ds")))
        assertEquals(emptySet<String>(), MeshOps.unsupportedRoaming(emptySet()))
        // No answer: leave out the one option a common build lacks rather than write it blind.
        assertEquals(setOf("bss_transition"), MeshOps.unsupportedRoaming(null))
    }

    @Test
    fun `a skipped option is left out of every ap and explained`() {
        val aps = listOf(WifiNetwork("home", "radio1", "Casa", "psk2", "hunter22", disabled = false, network = "lan"))
        val ops = MeshOps.roamingOps(aps, skip = setOf("bss_transition"))
        assertTrue(ops.contains("set wireless.home.ieee80211r='1'"))
        assertTrue(ops.contains("set wireless.home.ieee80211k='1'"))
        assertFalse(ops.any { it.contains("bss_transition") })
        assertTrue(MeshOps.roamingNotes(aps, setOf("bss_transition")).any { it.contains("802.11v") })
        // Losing 802.11r takes its companions with it.
        val noFt = MeshOps.roamingOps(aps, skip = setOf("ieee80211r"))
        assertFalse(noFt.any { it.contains("mobility_domain") || it.contains("ft_") })
        // A node with no probe answer gets the same caution as the primary.
        val node = MeshNodeState(emptyMap(), emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap(), false, "wpad-basic-mbedtls", "apk", null)
        assertEquals(setOf("bss_transition"), MeshOps.unsupportedRoaming(node.hostapdUnknown))
    }
}

/** hostapd rejecting one option silences every SSID on the radio, phone included; the router must undo that alone. */
class WifiApplyRollbackTest {

    @Test
    fun `a wireless apply is armed to restore itself unless confirmed`() {
        val cmd = Commands.wifiApply(listOf("set wireless.default_radio0.ieee80211k='1'"))
        assertTrue(cmd.startsWith("mkdir -p /tmp/wrtpulse-wifi && cp /etc/config/wireless /tmp/wrtpulse-wifi/wireless && "))
        assertTrue(
            cmd.contains(
                "(sleep 90; [ -f /tmp/wrtpulse-wifi/confirm ] && exit 0; cp /tmp/wrtpulse-wifi/wireless /etc/config/wireless; " +
                    "wifi reload; echo rolled-back > /tmp/wrtpulse-wifi/last) >/dev/null 2>&1 &"
            )
        )
        assertTrue(cmd.indexOf(">/dev/null 2>&1 &") < cmd.indexOf("uci batch <<'WRTPULSE_EOF'"))   // armed before applying
        assertTrue(cmd.trimEnd().endsWith("uci commit wireless && wifi reload; echo applied"))
        assertEquals("touch /tmp/wrtpulse-wifi/confirm && echo confirmed", Commands.WIFI_CONFIRM)
        // Rolling back by hand disarms the timer first, so the radios are not reloaded twice.
        assertTrue(Commands.WIFI_ROLLBACK_NOW.startsWith("touch /tmp/wrtpulse-wifi/confirm; cp /tmp/wrtpulse-wifi/wireless /etc/config/wireless && wifi reload"))
        assertTrue(Commands.meshState(emptyList()).contains("echo ___wrt___ wifilast; cat /tmp/wrtpulse-wifi/last"))
    }

    /** As the reference router looked with hostapd refusing bss_transition: the netdev exists, no channel. */
    @Test
    fun `an ap hostapd refused exists but has no channel`() {
        val health = Parsers.apHealth(
            """
            Interface phy1-ap0
            	type AP
            Interface phy0-ap0
            	type AP
            	channel 36 (5180 MHz), width: 80 MHz, center1: 5210 MHz
            Interface phy0-mesh0
            	type mesh point
            	channel 36 (5180 MHz), width: 80 MHz, center1: 5210 MHz
            """.trimIndent()
        )
        assertEquals(mapOf("phy1-ap0" to false, "phy0-ap0" to true), health)
    }
}

/**
 * The add-a-node drawings show the router's own sockets. The reference Deco M4R has two holes
 * on a chip that reports seven ports; drawn as one WAN and four LANs it pointed at a socket
 * that does not exist.
 */
class CaseSocketsTest {

    private val decoSwitch = SwitchDev("switch0", 7, 0, "QCA8337", emptyMap(), emptyMap())
    private val decoBoard = mapOf("switch0" to listOf(BoardPort(0, null, null, "eth0"), BoardPort(3, "lan", 1, null), BoardPort(5, "lan", 2, null)))

    /** As the router's config read mid-setup: WAN carved onto port 3, port 5 held for the node. */
    private val decoMidSetup = Parsers.uciShow(
        """
        network.lan=interface
        network.lan.device='br-lan'
        network.@device[0]=device
        network.@device[0].name='br-lan'
        network.@device[0].type='bridge'
        network.@device[0].ports='eth0.1'
        network.@switch_vlan[0]=switch_vlan
        network.@switch_vlan[0].device='switch0'
        network.@switch_vlan[0].vlan='1'
        network.@switch_vlan[0].ports='0t'
        network.swvlan2=switch_vlan
        network.swvlan2.device='switch0'
        network.swvlan2.vlan='2'
        network.swvlan2.ports='0t 3'
        network.wan=interface
        network.wan.device='eth0.2'
        network.wan.proto='dhcp'
        network.wrtpulse_setup_vlan=switch_vlan
        network.wrtpulse_setup_vlan.device='switch0'
        network.wrtpulse_setup_vlan.vlan='3'
        network.wrtpulse_setup_vlan.ports='0t 5'
        network.wrtpulse_setup_dev=device
        network.wrtpulse_setup_dev.name='br-setup'
        network.wrtpulse_setup_dev.type='bridge'
        network.wrtpulse_setup_dev.ports='eth0.3'
        """.trimIndent()
    )

    @Test
    fun `a two-socket deco is drawn with two sockets, wan and held`() {
        val sockets = MeshOps.caseSockets(decoMidSetup, listOf(decoSwitch), decoBoard)
        assertEquals(
            listOf(CaseSocket("sw:3", "WAN", SocketRole.Wan), CaseSocket("sw:5", "lan2", SocketRole.Held)),
            sockets,
        )
        // The setup's own reading of the held port wins over the config's.
        assertEquals(SocketRole.Held, MeshOps.caseSockets(decoMidSetup, listOf(decoSwitch), decoBoard, held = "sw:5")[1].role)
    }

    @Test
    fun `before anything is held both deco sockets are plain lan with their case labels`() {
        val idle = Parsers.uciShow(
            """
            network.lan=interface
            network.lan.device='br-lan'
            network.@device[0]=device
            network.@device[0].name='br-lan'
            network.@device[0].type='bridge'
            network.@device[0].ports='eth0.1'
            network.@switch_vlan[0]=switch_vlan
            network.@switch_vlan[0].device='switch0'
            network.@switch_vlan[0].vlan='1'
            network.@switch_vlan[0].ports='0t 3 5'
            """.trimIndent()
        )
        assertEquals(
            listOf(CaseSocket("sw:3", "lan1", SocketRole.Lan), CaseSocket("sw:5", "lan2", SocketRole.Lan)),
            MeshOps.caseSockets(idle, listOf(decoSwitch), decoBoard, held = null),
        )
    }

    /** No board file: the ports the VLAN config mentions, numbered as the chip does. */
    @Test
    fun `a switch without a board file shows the ports its vlans use`() {
        val uci = Parsers.uciShow(
            """
            network.lan=interface
            network.lan.device='br-lan'
            network.@device[0]=device
            network.@device[0].name='br-lan'
            network.@device[0].type='bridge'
            network.@device[0].ports='eth0.1'
            network.@switch_vlan[0]=switch_vlan
            network.@switch_vlan[0].device='switch0'
            network.@switch_vlan[0].vlan='1'
            network.@switch_vlan[0].ports='0t 1 2 3 4'
            network.@switch_vlan[1]=switch_vlan
            network.@switch_vlan[1].device='switch0'
            network.@switch_vlan[1].vlan='2'
            network.@switch_vlan[1].ports='0t 5'
            network.wan=interface
            network.wan.device='eth0.2'
            """.trimIndent()
        )
        val sockets = MeshOps.caseSockets(uci, listOf(SwitchDev("switch0", 6, 0, "rtl8367", emptyMap(), emptyMap())), emptyMap(), held = "sw:3")
        assertEquals(listOf("port 1", "port 2", "port 3", "port 4", "WAN"), sockets.map { it.label })
        assertEquals(listOf(SocketRole.Lan, SocketRole.Lan, SocketRole.Held, SocketRole.Lan, SocketRole.Wan), sockets.map { it.role })
    }

    /** DSA: netdevs are the sockets; the held one left the bridge but still sits in its place on the case. */
    @Test
    fun `a dsa board lists wan first then the lan sockets in case order`() {
        val uci = Parsers.uciShow(
            """
            network.lan=interface
            network.lan.device='br-lan'
            network.@device[0]=device
            network.@device[0].name='br-lan'
            network.@device[0].type='bridge'
            network.@device[0].ports='lan1' 'lan2' 'lan4'
            network.wan=interface
            network.wan.device='wan'
            network.wrtpulse_setup_dev=device
            network.wrtpulse_setup_dev.name='br-setup'
            network.wrtpulse_setup_dev.type='bridge'
            network.wrtpulse_setup_dev.ports='lan3'
            """.trimIndent()
        )
        val sockets = MeshOps.caseSockets(uci, emptyList(), emptyMap())
        assertEquals(listOf("WAN", "lan1", "lan2", "lan3", "lan4"), sockets.map { it.label })
        assertEquals(SocketRole.Wan, sockets[0].role)
        assertEquals(SocketRole.Held, sockets[3].role)
        assertEquals("lan3", sockets[3].id)
    }

    @Test
    fun `a config that says nothing about sockets leaves the drawing to the stock router`() {
        assertTrue(MeshOps.caseSockets(emptyMap(), emptyList(), emptyMap()).isEmpty())
        assertTrue(Commands.meshState(emptyList()).contains("echo ___wrt___ boardsw; jsonfilter -i /etc/board.json -e '@.switch'"))
    }
}
