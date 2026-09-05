package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.NETDEV_LINES
import com.vivekkaushik.wrtpulse.ops.PROTO_LS
import com.vivekkaushik.wrtpulse.ops.WAN_DUMP
import com.vivekkaushik.wrtpulse.ops.WAN_FIREWALL_UCI
import com.vivekkaushik.wrtpulse.ops.WAN_NETWORK_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val DHCP_V6_UCI = """
    dhcp.lan=dhcp
    dhcp.lan.interface='lan'
    dhcp.lan.start='100'
    dhcp.lan.limit='150'
""".trimIndent()

class WanStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    /** [host] is where the app is talking from — inside the LAN unless a test says otherwise. */
    private fun store(host: String = "192.168.1.1"): WanStore =
        WanStore(RouterSession(SshTarget(host), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "net" to WAN_NETWORK_UCI,
                    "fw" to WAN_FIREWALL_UCI,
                    "dhcp" to DHCP_V6_UCI,
                    "dump" to WAN_DUMP,
                    "links" to NETDEV_LINES,
                    "protos" to PROTO_LS,
                )
            )
        }

    // ---- the hub ----

    /** The LAN is up and healthy and is not an uplink; wan6 rides wan rather than racing it. */
    @Test
    fun `the uplinks are the wan zone and whatever holds a default route`() {
        val rows = store().wanRows()
        assertEquals(listOf("wan", "wwan"), rows.map { it.section })
    }

    @Test
    fun `the live one comes first and is marked primary`() {
        val rows = store().wanRows()
        assertTrue(rows.first().up)
        assertTrue(rows.first().primary)
        assertFalse(rows.last().up)
        assertFalse(rows.last().primary)
    }

    @Test
    fun `a row carries what the hub prints`() {
        val wan = store().wanRows().first()
        assertEquals("pppoe", wan.proto)
        assertEquals("pppoe-wan", wan.device)
        assertEquals("82.44.19.7", wan.address)
        assertEquals(1571052, wan.uptimeS)
    }

    /** The delegated prefix belongs to wan6, and the card for wan has to find it anyway. */
    @Test
    fun `the selection defaults to the primary uplink`() {
        assertEquals("wan", store().selected)
    }

    // ---- the hub: failover by metric ----

    /** Config before live: the field edits the config, and netifd's number can be a default. */
    @Test
    fun `a metric reads from the config and shows staged edits`() {
        val s = store()
        assertEquals(20, s.metricOf("wwan"))
        assertEquals(0, s.metricOf("wan"))
        s.stageMetric("wan", "30")
        assertEquals(30, s.metricOf("wan"))
        assertTrue(s.wanRows().single { it.section == "wan" }.metricChanged)
        assertEquals(listOf("set network.wan.metric='30'"), s.ops())
    }

    /** Which uplink is primary stays what the kernel is actually doing until the apply lands. */
    @Test
    fun `staging a metric does not move the primary badge`() {
        val s = store()
        s.stageMetric("wan", "99")
        assertTrue(s.wanRows().single { it.section == "wan" }.primary)
    }

    @Test
    fun `a metric that is not a number is refused`() {
        val s = store()
        s.stageMetric("wan", "fast")
        assertTrue(s.problems().any { it.contains("metric 'fast'") })
    }

    /** The one thing people expect from failover that metric does not do. */
    @Test
    fun `changing a metric says what failover by metric is and is not`() {
        val s = store()
        s.stageMetric("wan", "10")
        val notes = s.notes()
        assertTrue(notes.any { it.contains("lower number carries the default route") })
        assertTrue(notes.any { it.contains("needs mwan3") })
    }

    @Test
    fun `two uplinks on the same metric are called out`() {
        val s = store()
        s.stageMetric("wan", "20")
        assertTrue(s.notes().any { it.contains("both have metric 20") })
    }

    /** One interface changed is one ifup; two changed needs netifd to reload them all. */
    @Test
    fun `reordering two uplinks reloads the network rather than ifup-ing one`() {
        val one = store()
        one.stageMetric("wwan", "5")
        assertEquals(setOf("wwan"), one.touchedInterfaces())
        assertTrue(one.commitLine().endsWith("ifup wwan"))

        val two = store()
        two.stageMetric("wan", "30")
        two.stageMetric("wwan", "10")
        assertEquals(setOf("wan", "wwan"), two.touchedInterfaces())
        assertTrue(two.commitLine().endsWith("/etc/init.d/network reload"))
    }

    /**
     * The user's report: change the metric once, apply, change it again — refused with "Pick
     * the socket the ISP is plugged into". The ifup from the first apply bounced the Wi-Fi
     * uplink, and for the seconds it spent re-associating it had no live device, so the
     * radio test fell back to false and the socket check fired against a metric edit.
     */
    @Test
    fun `a metric edit is not refused while the wifi uplink re-associates`() {
        val bouncing = WanStore(RouterSession(SshTarget("192.168.0.134"), unusedClient, { error("unused") }))
        bouncing.ingest(
            mapOf(
                "net" to """
                    network.lan=interface
                    network.lan.device='br-lan'
                    network.lan.proto='static'
                    network.lan.ipaddr='192.168.0.1/24'
                    network.wwan=interface
                    network.wwan.proto='dhcp'
                    network.wwan.metric='10'
                """.trimIndent(),
                "fw" to """
                    firewall.@zone[1]=zone
                    firewall.@zone[1].name='wan'
                    firewall.@zone[1].network='wwan'
                """.trimIndent(),
                "dhcp" to "",
                // Mid-reassociation: the interface exists, is down, and has no device at all.
                "dump" to """{"interface":[{"interface":"wwan","up":false,"available":true,"proto":"dhcp","metric":10}]}""",
                "links" to NETDEV_LINES,
                "protos" to PROTO_LS,
            )
        )
        assertEquals("wwan", bouncing.selected)
        assertTrue(bouncing.wirelessUplink)
        bouncing.stageMetric("wwan", "20")
        assertTrue(bouncing.problems().none { it.contains("socket") })
        assertTrue(bouncing.problems().isEmpty())
    }

    /** Checks belong to what is being written: a metric on wwan must not audit wan's socket. */
    @Test
    fun `an untouched interface's missing socket does not block a metric edit elsewhere`() {
        val s = store()
        // Make the selected interface look socket-less, as an unfinished wired WAN would.
        s.select("wan")
        s.stage("network.wan.device", "eth1.201", "")
        s.revert()
        s.stageMetric("wwan", "5")
        assertTrue(s.problems().isEmpty())
    }

    /** But writing the device itself still has to name a socket. */
    @Test
    fun `writing an empty device is still refused`() {
        val s = store()
        s.stage("network.wan.device", "eth1.201", "")
        assertTrue(s.problems().any { it.contains("socket") })
    }

    // ---- screen 27: port, VLAN, MAC, MTU ----

    @Test
    fun `a tagged wan reads back as port plus vlan`() {
        val s = store()
        assertEquals("eth1", s.port)
        assertEquals("201", s.vlanId)
        assertNotNull(s.wanDevice)
    }

    /** Ports come from the switch, and the one in use is included even if it is not a socket. */
    @Test
    fun `the port list covers the sockets`() {
        val ports = store().availablePorts()
        assertTrue(ports.containsAll(listOf("wan", "lan1", "eth1")))
        assertFalse(ports.any { it.startsWith("phy") })
    }

    /**
     * A tag is not a string on the interface: netifd needs a `config device` of type 8021q,
     * and the interface has to name it. Without the device, the line comes up untagged and
     * the ISP never sees a thing.
     */
    @Test
    fun `tagging a plain port creates the device and repoints the interface`() {
        val s = store()
        s.select("wwan")
        s.stageVlan("100")
        val ops = s.ops()
        assertTrue(ops.contains("set network.wrtpulse_phy0sta0_100=device"))
        assertTrue(ops.contains("set network.wrtpulse_phy0sta0_100.type='8021q'"))
        assertTrue(ops.contains("set network.wrtpulse_phy0sta0_100.vid='100'"))
        assertTrue(ops.contains("set network.wwan.device='phy0-sta0.100'"))
        // The device has to exist before the interface names it.
        assertTrue(ops.indexOf("set network.wrtpulse_phy0sta0_100=device") < ops.indexOf("set network.wwan.device='phy0-sta0.100'"))
    }

    /** An existing 8021q section is edited rather than duplicated. */
    @Test
    fun `changing the tag on a wan that already has one edits nothing new`() {
        val s = store()
        s.stageVlan("300")
        assertEquals(1, s.deviceDrafts.size)
        assertEquals("eth1.300", s.deviceDrafts.keys.single())
        assertTrue(s.ops().contains("set network.wan.device='eth1.300'"))
    }

    @Test
    fun `clearing the tag points the interface back at the raw port`() {
        val s = store()
        s.stageVlan("")
        assertEquals(listOf("set network.wan.device='eth1'"), s.ops())
        assertTrue(s.deviceDrafts.isEmpty())
    }

    @Test
    fun `changing the port keeps the tag`() {
        val s = store()
        s.stagePort("lan4")
        assertEquals("lan4", s.port)
        assertEquals("201", s.vlanId)
        assertTrue(s.ops().any { it.contains("device='lan4.201'") })
    }

    /**
     * MAC and MTU can sit on the interface or on the device, and the device wins wherever
     * both are set — so writing to the interface while a device section exists would look
     * applied and do nothing.
     */
    @Test
    fun `mac and mtu are written where they will be read`() {
        val s = store()
        s.stageMac("a4:83:e7:2b:11:22")
        s.stageMtu("1480")
        assertTrue(s.ops().contains("set network.wan_vlan.macaddr='a4:83:e7:2b:11:22'"))
        assertTrue(s.ops().contains("set network.wan_vlan.mtu='1480'"))
    }

    /**
     * This router keeps mtu on the interface while a device section also exists. Reading it
     * has to find it there, and writing to the device has to take the interface copy with
     * it — otherwise the config holds two values and only one of them does anything.
     */
    @Test
    fun `a value on the interface is read, and cleared when the device takes over`() {
        val s = store()
        assertEquals("1492", s.mtu)
        s.stageMtu("1480")
        val ops = s.ops()
        assertTrue(ops.contains("set network.wan_vlan.mtu='1480'"))
        assertTrue(ops.contains("delete network.wan.mtu"))
    }

    @Test
    fun `an untagged wan writes them on the interface`() {
        val s = store()
        s.select("wwan")
        s.stageMtu("1400")
        assertTrue(s.ops().contains("set network.wwan.mtu='1400'"))
    }

    /** netifd has no `pcp` option; a priority is an egress QoS mapping. */
    @Test
    fun `a pcp value becomes an egress qos mapping`() {
        val s = store()
        s.stagePcp("5")
        assertTrue(s.ops().contains("set network.wan_vlan.egress_qos_mapping='0:5'"))
        assertEquals("5", s.pcp)
        // Zero is the default and is written by removing the option.
        s.stagePcp("0")
        assertEquals("0", s.pcp)
    }

    /**
     * The router this was first run against has a Wi-Fi client for an uplink. It has no
     * `device` option at all — netifd assigns phy0-sta0 from the wireless config — so the
     * port page had nothing to show and, worse, the "pick a socket" refusal blocked every
     * apply on that router, including changes that had nothing to do with ports.
     */
    @Test
    fun `a radio-backed uplink is not asked for a socket`() {
        val s = store()
        s.select("wwan")
        assertTrue(s.wirelessUplink)
        assertTrue(s.portLabel.startsWith("Wi-Fi client"))
        s.stageProto("dhcp")
        assertTrue(s.problems().none { it.contains("socket") })
    }

    @Test
    fun `a wired uplink still has to name its socket`() {
        val s = store()
        assertFalse(s.wirelessUplink)
        assertEquals("eth1 · vlan 201 · 802.1q · mtu 1492", s.portLabel)
    }

    // ---- screen 28: the protocol ----

    @Test
    fun `only protocols with a handler are on offer`() {
        val s = store()
        assertTrue(s.protoAvailable("pppoe"))
        assertFalse(s.protoAvailable("map"))
        assertEquals(
            listOf("dhcp", "static", "pppoe", "l2tp", "pptp", "dslite", "map"),
            s.protoChoices().map { it.first },
        )
        assertEquals(listOf(true, true, true, false, false, false, false), s.protoChoices().map { it.second })
    }

    @Test
    fun `a protocol with no handler cannot be applied`() {
        val s = store()
        s.stageProto("map")
        assertTrue(s.problems().any { it.contains("no handler") && it.contains("map") })
    }

    /**
     * A protocol the app cannot fill in is refused rather than written half-configured — an
     * interface set to l2tp with no server is an interface that never comes up.
     */
    @Test
    fun `a protocol this screen cannot fill in is refused`() {
        assertFalse(store().protoEditable("dslite"))
        assertTrue(store().protoEditable("pppoe"))
    }

    @Test
    fun `pppoe needs its username`() {
        val s = store()
        s.stageOption("username", "bb4019822@airtel", "")
        assertTrue(s.problems().any { it.contains("username") })
    }

    @Test
    fun `a static wan needs an address`() {
        val s = store()
        s.stageProto("static")
        assertTrue(s.problems().any { it.contains("static WAN needs") })
        s.stageOption("ipaddr", "", "82.44.19.7")
        assertTrue(s.problems().none { it.contains("static WAN needs") })
    }

    @Test
    fun `an mtu the drivers would refuse is refused here`() {
        val s = store()
        s.stageMtu("300")
        assertTrue(s.problems().any { it.contains("below 576") })
        s.stageMtu("68000")
        assertTrue(s.problems().any { it.contains("past what the drivers") })
    }

    /** A MAC with an odd first octet is a multicast address and cannot be a source. */
    @Test
    fun `a multicast mac is refused`() {
        val s = store()
        s.stageMac("a5:83:e7:2b:11:22")
        assertTrue(s.problems().any { it.contains("multicast") })
        s.stageMac("nonsense")
        assertTrue(s.problems().any { it.contains("not a MAC") })
    }

    @Test
    fun `a vlan id outside the standard range is refused`() {
        val s = store()
        s.stageVlan("5000")
        assertTrue(s.problems().any { it.contains("1 to 4094") })
    }

    // ---- screen 29: IPv6 ----

    @Test
    fun `a dhcpv6 companion reads as a native uplink`() {
        assertEquals(V6Mode.Native, store().v6Mode)
        assertEquals("56", store().pdSize)
    }

    @Test
    fun `turning the uplink off disables the companion`() {
        val s = store()
        s.stageV6Mode(V6Mode.Off)
        assertEquals(V6Mode.Off, s.v6Mode)
        assertTrue(s.ops().contains("set network.wan6.disabled='1'"))
    }

    /** Dual-stack rides the PPPoE session, so a second interface would fight it. */
    @Test
    fun `pppoe dual-stack asks the session and shuts the companion down`() {
        val s = store()
        s.stageV6Mode(V6Mode.PppoeDual)
        assertEquals(V6Mode.PppoeDual, s.v6Mode)
        val ops = s.ops()
        assertTrue(ops.contains("set network.wan.ipv6='auto'"))
        assertTrue(ops.contains("set network.wan6.disabled='1'"))
    }

    /**
     * Relay is odhcpd forwarding the ISP's advertisements, and it takes BOTH halves: the LAN
     * relays, and the upstream is marked master so odhcpd knows what it is relaying from.
     * Writing only the LAN half is the classic way to end up with relay mode that relays
     * nothing.
     */
    @Test
    fun `relay mode configures the lan and the upstream it relays from`() {
        val s = store()
        s.stageV6Mode(V6Mode.Relay)
        assertEquals(V6Mode.Relay, s.v6Mode)
        val ops = s.ops()
        assertTrue(ops.contains("set dhcp.lan.ra='relay'"))
        assertTrue(ops.contains("set dhcp.lan.dhcpv6='relay'"))
        assertTrue(ops.contains("set dhcp.wan6=dhcp"))
        assertTrue(ops.contains("set dhcp.wan6.master='1'"))
        assertTrue(ops.contains("set dhcp.wan6.ndp='relay'"))
        // The section has to be created before anything is set on it.
        assertTrue(ops.indexOf("set dhcp.wan6=dhcp") < ops.indexOf("set dhcp.wan6.master='1'"))
        // This router's wan6 is already a native dhcpv6 interface, so nothing in `network`
        // actually changes — only odhcpd's config does.
        assertEquals(listOf("dhcp"), s.packages())
        assertTrue(s.notes().any { it.contains("public") && it.contains("firewall") })
    }

    /** And leaving relay has to take the upstream's master section back out with it. */
    @Test
    fun `leaving relay clears both halves`() {
        val relaying = WanStore(
            RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })
        )
        relaying.ingest(
            mapOf(
                "net" to WAN_NETWORK_UCI,
                "fw" to WAN_FIREWALL_UCI,
                "dhcp" to """
                    dhcp.lan=dhcp
                    dhcp.lan.interface='lan'
                    dhcp.lan.ra='relay'
                    dhcp.lan.dhcpv6='relay'
                    dhcp.lan.ndp='relay'
                    dhcp.wan6=dhcp
                    dhcp.wan6.interface='wan6'
                    dhcp.wan6.master='1'
                    dhcp.wan6.ra='relay'
                """.trimIndent(),
                "dump" to WAN_DUMP,
                "links" to NETDEV_LINES,
                "protos" to PROTO_LS,
            )
        )
        assertEquals(V6Mode.Relay, relaying.v6Mode)
        relaying.stageV6Mode(V6Mode.Native)
        val ops = relaying.ops()
        assertTrue(ops.contains("set dhcp.lan.ra='server'"))
        assertTrue(ops.contains("delete dhcp.wan6.master"))
        assertTrue(ops.contains("delete dhcp.wan6.ra"))
    }

    @Test
    fun `6to4 is only offered where its handler exists`() {
        assertFalse(store().protoAvailable("6to4"))
    }

    @Test
    fun `the requested prefix length is written, and auto removes it`() {
        val s = store()
        s.stagePdSize("60")
        assertTrue(s.ops().contains("set network.wan6.reqprefix='60'"))
        s.stagePdSize("auto")
        assertTrue(s.ops().contains("delete network.wan6.reqprefix"))
    }

    @Test
    fun `lan addressing maps onto ra and dhcpv6`() {
        val s = store()
        s.stageLanV6(LanV6.Slaac)
        assertEquals(LanV6.Slaac, s.lanV6)
        assertTrue(s.ops().contains("set dhcp.lan.dhcpv6='disabled'"))
        assertTrue(s.ops().contains("add_list dhcp.lan.ra_flags='none'"))

        s.revert()
        s.stageLanV6(LanV6.Stateful)
        assertEquals(LanV6.Stateful, s.lanV6)
        assertTrue(s.ops().contains("add_list dhcp.lan.ra_flags='managed-config'"))
    }

    // ---- applying ----

    @Test
    fun `a device change needs netifd, an interface change only needs ifup`() {
        val withDevice = store()
        withDevice.stageVlan("300")
        assertTrue(withDevice.touchesDevice())
        assertTrue(withDevice.commitLine().contains("/etc/init.d/network reload"))

        val plain = store()
        plain.stageOption("username", "bb4019822@airtel", "someone@isp")
        assertFalse(plain.touchesDevice())
        assertTrue(plain.commitLine().contains("ifup wan"))
    }

    /** The PPPoE password is the one secret on this screen and must not print. */
    @Test
    fun `the password is masked in the diff`() {
        val s = store()
        s.stageOption("password", "hunter2", "correct-horse")
        val diff = s.diffLines().map { it.first }
        assertTrue(diff.any { it.contains("password='••••••••'") })
        assertTrue(diff.none { it.contains("hunter2") })
        assertTrue(diff.none { it.contains("correct-horse") })
    }

    @Test
    fun `every apply warns that the internet drops`() {
        val s = store()
        s.stageProto("dhcp")
        assertTrue(s.notes().any { it.contains("internet drops") })
        assertTrue(s.notes().any { it.contains("keeps the port, VLAN and MAC") })
    }

    /**
     * The case the rollback exists for: the app is reaching the router from outside its LAN,
     * so it is talking over the very link being changed.
     */
    @Test
    fun `a session from outside the lan is called out`() {
        val remote = store(host = "203.0.113.9")
        remote.stageProto("dhcp")
        assertTrue(remote.remoteSession)
        assertTrue(remote.notes().any { it.contains("outside its LAN") })

        val local = store(host = "192.168.1.1")
        assertFalse(local.remoteSession)
        local.stageProto("dhcp")
        assertTrue(local.notes().none { it.contains("outside its LAN") })
    }

    @Test
    fun `a new tagged device warns that the tag has to be the right one`() {
        val s = store()
        s.stageVlan("300")
        assertTrue(s.notes().any { it.contains("VLAN 300") && it.contains("nothing comes up") })
    }

    @Test
    fun `everything staged counts once and revert clears it`() {
        val s = store()
        s.stageProto("dhcp")
        s.stageMtu("1500")
        s.stageVlan("300")
        assertTrue(s.pendingCount >= 3)
        s.revert()
        assertEquals(0, s.pendingCount)
        assertEquals(emptyList<String>(), s.ops())
    }
}

/**
 * The connection test is about ONE uplink. It used to ping over whatever held the default
 * route and store a single shared result, so every chip showed the primary's run.
 */
class WanConnectionTestTest {

    @Test
    fun `every ping is bound to the interface, or it measures the default route`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.pingTest("192.168.1.1", "phy1-sta0")
        // Four pings, every one of them bound.
        assertEquals(4, Regex("ping -c 3").findAll(cmd).count())
        assertEquals(4, Regex("-I 'phy1-sta0'").findAll(cmd).count())
        assertTrue(cmd.contains("'192.168.1.1'"))
        assertTrue(cmd.contains("'1.1.1.1'"))
        assertTrue(cmd.contains("'openwrt.org'"))
    }

    @Test
    fun `a standby uplink with no gateway pings the resolvers but not loopback`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.pingTest("", "eth1")
        assertFalse(cmd.contains("127.0.0.1"))
        assertFalse(cmd.contains("gw"))
        assertEquals(3, Regex("ping -c 3").findAll(cmd).count())
    }

    @Test
    fun `a device name cannot break out of the bound ping`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.pingTest("10.0.0.1", "eth0'; reboot #")
        // The quote is neutralised, so the payload stays one shell word and never runs.
        assertTrue(cmd.contains("""-I 'eth0'\''; reboot #'"""))
        assertFalse(cmd.contains("""-I 'eth0'; reboot"""))
    }

    @Test
    fun `no gateway is reported as such, never as a healthy loopback reply`() {
        val tiles = WanStore.pingTiles(emptyMap(), gateway = "")
        assertEquals("gateway", tiles.first().label)
        assertEquals("no gateway", tiles.first().error)
        assertFalse(tiles.first().ok)
    }

    @Test
    fun `a real gateway reply is parsed into the gateway tile`() {
        val reply = "3 packets transmitted, 3 received, 0% packet loss\n" +
            "round-trip min/avg/max = 1.1/2.5/3.9 ms"
        val tiles = WanStore.pingTiles(mapOf("gw" to reply), gateway = "192.168.1.1")
        assertEquals("192.168.1.1", tiles.first().target)
        assertTrue(tiles.first().ok)
        assertEquals(0, tiles.first().lossPct)
    }

    @Test
    fun `an interface with no device is called down rather than silently tested`() {
        val tile = WanStore.downTile("wwan_2")
        assertFalse(tile.ok)
        assertTrue(tile.error!!.contains("down"))
    }

    @Test
    fun `results are held per interface, so one uplink's test is not shown under another`() {
        val s = WanStore(
            RouterSession(SshTarget("192.168.1.1"), object : SshClient {
                override suspend fun probeHostKey(target: SshTarget) = error("unused")
                override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
                    error("unused")
            }, { error("unused") })
        )
        s.pingsBySection["wan"] = WanStore.pingTiles(emptyMap(), gateway = "192.168.1.1")
        // Nothing was ever run against this one, so it has nothing to show.
        assertTrue(s.pingsBySection["wwan_2"].orEmpty().isEmpty())
        assertEquals(4, s.pingsBySection["wan"]!!.size)
    }
}
