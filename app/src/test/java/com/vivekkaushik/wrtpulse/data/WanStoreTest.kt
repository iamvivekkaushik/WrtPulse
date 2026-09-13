package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.NETDEV_LINES
import com.vivekkaushik.wrtpulse.ops.PROTO_LS
import com.vivekkaushik.wrtpulse.ops.SWCONFIG_OUT
import com.vivekkaushik.wrtpulse.ops.WAN_DUMP
import com.vivekkaushik.wrtpulse.ops.WAN_FIREWALL_UCI
import com.vivekkaushik.wrtpulse.ops.WAN_NETWORK_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- the hub: failover by drag ----

    /** Top of the list is 10 and every one after it another ten, the gap OpenWrt's docs use. */
    @Test
    fun `restamping the order numbers the uplinks ten apart`() {
        val s = store()
        s.stageFailoverOrder(listOf("wwan", "wan"))
        assertEquals(10, s.metricOf("wwan"))
        assertEquals(20, s.metricOf("wan"))
        assertEquals(
            listOf("set network.wan.metric='20'", "set network.wwan.metric='10'"),
            s.ops().sorted(),
        )
    }

    /**
     * The whole list is restamped, but a row already holding its number is not a write —
     * which is what stops a drag from touching interfaces it did not move.
     */
    @Test
    fun `restamping stages only the uplinks whose number actually changes`() {
        val s = store()
        // wwan already carries 20 in the config; wan carries nothing at all.
        s.stageFailoverOrder(listOf("wan", "wwan"))
        assertEquals(listOf("set network.wan.metric='10'"), s.ops())
        assertEquals(setOf("wan"), s.touchedInterfaces())
    }

    /**
     * Dragging a row down and back leaves the order it started in. The one write that stays
     * is the uplink that never had a metric: making the order explicit is a real change to a
     * config that was relying on netifd's default.
     */
    @Test
    fun `dragging an uplink down and back restores the original order`() {
        val s = store()
        s.stageFailoverOrder(listOf("wwan", "wan"))
        s.stageFailoverOrder(listOf("wan", "wwan"))
        assertEquals(10, s.metricOf("wan"))
        assertEquals(20, s.metricOf("wwan"))
        assertEquals(listOf("set network.wan.metric='10'"), s.ops())
    }

    /** Restamping cannot leave two uplinks sharing a number, which a free-text field could. */
    @Test
    fun `restamping never gives two uplinks the same metric`() {
        val s = store()
        s.stageFailoverOrder(listOf("wwan", "wan"))
        val metrics = s.wanRows().map { s.metricOf(it.section) }
        assertEquals(metrics.size, metrics.toSet().size)
        assertFalse(s.notes().any { it.contains("both have metric") })
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

// ---------------------------------------------------------------------------
// A wired uplink on a socket the LAN owns
// ---------------------------------------------------------------------------

/**
 * `uci show network` on the TP-Link Deco M4R as found: both sockets (switch ports 3 and 5) in
 * LAN VLAN 1, no `wan` interface at all, and a Wi-Fi client as the only uplink.
 */
private val DECO_NETWORK_UCI = """
    network.lan=interface
    network.lan.device='br-lan'
    network.lan.proto='static'
    network.lan.ipaddr='192.168.0.1/24'
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
    network.wwan=interface
    network.wwan.proto='dhcp'
    network.wwan.metric='30'
""".trimIndent()

private val DECO_FIREWALL_UCI = """
    firewall.@zone[0]=zone
    firewall.@zone[0].name='lan'
    firewall.@zone[0].network='lan'
    firewall.@zone[1]=zone
    firewall.@zone[1].name='wan'
    firewall.@zone[1].network='wan' 'wan6' 'wwan'
""".trimIndent()

private fun decoDump(wwanAddress: String = "10.143.245.231") = """
{"interface":[
 {"interface":"lan","up":true,"available":true,"proto":"static","device":"br-lan",
  "l3_device":"br-lan","uptime":1000,
  "ipv4-address":[{"address":"192.168.0.1","mask":24}],
  "route":[{"target":"192.168.0.0","mask":24,"nexthop":"0.0.0.0"}]},
 {"interface":"wwan","up":true,"available":true,"proto":"dhcp","device":"phy1-sta0",
  "l3_device":"phy1-sta0","uptime":900,"metric":30,
  "ipv4-address":[{"address":"$wwanAddress","mask":24}],
  "route":[{"target":"0.0.0.0","mask":0,"nexthop":"10.143.245.33","source":""}]}
]}
""".trimIndent()

private val DECO_NETDEVS = """
    br-lan up 1 - a8:6e:84:93:80:38 virt wired
    eth0 up 1 1000 a8:6e:84:93:80:38 phy wired
    eth0.1 up 1 - a8:6e:84:93:80:38 virt wired
    lo unknown 1 - 00:00:00:00:00:00 virt wired
    phy0-ap0 up 1 - a8:6e:84:93:80:39 phy wifi
    phy1-sta0 up 1 - a8:6e:84:93:80:3a phy wifi
""".trimIndent()

/** A chip that will not say which port is the CPU — [Parsers.switchDevs] leaves it null. */
private val SWCONFIG_NO_CPU = """
    Found: switch0 - mdio.0
    # switch0
    switch0: eth0(Generic), ports: 6, vlans: 16
    Port 3:
    	link: port:3 link:up speed:1000baseT full-duplex
    Port 5:
    	link: port:5 link:down
    VLAN 1:
    	ports: 3 5 0t
""".trimIndent()

class WiredUplinkTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun deco(
        network: String = DECO_NETWORK_UCI,
        firewall: String = DECO_FIREWALL_UCI,
        dump: String = decoDump(),
        swconfig: String = SWCONFIG_OUT,
        board: String = "",
    ): WanStore = WanStore(RouterSession(SshTarget("192.168.0.1"), unusedClient, { error("unused") })).apply {
        ingest(
            mapOf(
                "net" to network,
                "fw" to firewall,
                "dhcp" to DHCP_V6_UCI,
                "dump" to dump,
                "links" to DECO_NETDEVS,
                "protos" to PROTO_LS,
                "swconfig" to swconfig,
                "board" to board,
            )
        )
    }

    /** The uplink picker offers the holes by the names printed beside them when the board says. */
    @Test
    fun `a board file labels the sockets as the case does`() {
        val sockets = deco(board = com.vivekkaushik.wrtpulse.ops.DECO_BOARD_SWITCH).sockets()
        assertEquals(listOf("sw:3", "sw:5"), sockets.map { it.id })
        assertEquals(listOf("LAN 1", "LAN 2"), sockets.map { it.label })
    }

    private fun socket(store: WanStore, id: String): Socket = store.sockets().single { it.id == id }

    /** The chip has seven ports; the config puts two of them in a VLAN, and those are the case holes. */
    @Test
    fun `a swconfig board's sockets are the switch ports its vlans name`() {
        val sockets = deco().sockets()
        assertEquals(listOf("sw:3", "sw:5"), sockets.map { it.id })
        assertEquals(listOf("Port 3", "Port 5"), sockets.map { it.label })
        assertTrue(sockets.all { it.inLan })
        assertTrue(sockets.single { it.id == "sw:3" }.up)
        assertFalse(sockets.single { it.id == "sw:5" }.up)
    }

    /** No wired uplink yet, so the Wi-Fi client is the only one that owns a socket — none. */
    @Test
    fun `a wifi client owns no socket`() {
        assertEquals(emptySet<String>(), deco().usedSockets())
    }

    /**
     * The whole move in one batch, in the order uci needs: the VLAN that carries the socket
     * to the CPU, the interface section, then its options — with the socket taken out of the
     * LAN's VLAN, and no firewall change because the stock zone already lists `wan`.
     */
    @Test
    fun `adding a wired uplink on a lan socket carves a vlan and creates wan on it`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        assertEquals(
            listOf(
                "set network.swvlan2=switch_vlan",
                "set network.swvlan2.device='switch0'",
                "set network.swvlan2.vlan='2'",
                "set network.swvlan2.ports='0t 5'",
                "set network.wan=interface",
                "set network.@switch_vlan[0].ports='0t 3'",
                "set network.wan.device='eth0.2'",
                "set network.wan.metric='40'",
                "set network.wan.proto='dhcp'",
            ),
            s.ops(),
        )
        assertEquals(listOf("network"), s.packages())
        assertTrue(s.touchesSwitch())
        assertTrue(s.reloadCommand().startsWith("/etc/init.d/network reload"))
        assertEquals(emptyList<String>(), s.problems())
    }

    @Test
    fun `the drafted uplink is selected and shows on the hub before the apply`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        assertEquals("wan", s.selected)
        val row = s.wanRows().single { it.section == "wan" }
        assertEquals("dhcp", row.proto)
        assertEquals("eth0.2", row.device)
        assertEquals(40, row.metric)
        assertFalse(row.up)
        // The port page reads the socket back through the VLAN, not the netdev name.
        assertEquals("sw:5", s.port)
        assertEquals("", s.vlanId)
        assertTrue(s.portLabel.startsWith("Port 5"))
        assertEquals(setOf("sw:5"), s.usedSockets())
    }

    @Test
    fun `reverting a drafted uplink leaves nothing selected that does not exist`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        s.revert()
        assertEquals(0, s.pendingCount)
        assertEquals("wwan", s.selected)
    }

    /** A zone that does not list the new interface gets it, and the firewall is committed too. */
    @Test
    fun `an uplink the wan zone does not list joins it`() {
        val fw = DECO_FIREWALL_UCI.replace("'wan' 'wan6' 'wwan'", "'wwan'")
        val s = deco(firewall = fw)
        s.addWiredUplink(socket(s, "sw:5"))
        val ops = s.ops()
        assertTrue(ops.contains("delete firewall.@zone[1].network"))
        assertTrue(ops.contains("add_list firewall.@zone[1].network='wwan'"))
        assertTrue(ops.contains("add_list firewall.@zone[1].network='wan'"))
        assertEquals(listOf("network", "firewall"), s.packages())
        assertTrue(s.reloadCommand().contains("/etc/init.d/firewall reload"))
        assertTrue(s.commitLine().contains("uci commit firewall"))
        assertTrue(s.commitLine().contains("/etc/init.d/firewall reload"))
    }

    /** A VLAN left behind by a deleted WAN — `ports '5 0t'` — is reused rather than duplicated. */
    @Test
    fun `a vlan already dedicated to the socket is reused`() {
        val network = DECO_NETWORK_UCI.replace("network.@switch_vlan[0].ports='3 5 0t'", "network.@switch_vlan[0].ports='3 0t'") + """
            
            network.@switch_vlan[1]=switch_vlan
            network.@switch_vlan[1].device='switch0'
            network.@switch_vlan[1].vlan='2'
            network.@switch_vlan[1].ports='5 0t'
        """.trimIndent()
        val s = deco(network = network)
        val five = socket(s, "sw:5")
        assertFalse(five.inLan)
        s.addWiredUplink(five)
        assertTrue(s.switchVlanDrafts.isEmpty())
        assertTrue(s.ops().contains("set network.wan.device='eth0.2'"))
        assertFalse(s.ops().any { it.contains("@switch_vlan[0]") })
        assertTrue(s.wiredUplinkPreview(five, "dhcp").any { it.contains("VLAN 2 on switch0 is reused") })
    }

    @Test
    fun `a second wired uplink gets the next name and the next vlan`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        assertEquals("wan_2", s.nextUplinkName())
        assertTrue(s.wiredUplinkPreview(socket(s, "sw:3"), "pppoe").any { it.contains("VLAN 3 is created") })
    }

    @Test
    fun `a chip that hides its cpu port is refused with the reason`() {
        val s = deco(swconfig = SWCONFIG_NO_CPU)
        s.addWiredUplink(socket(s, "sw:5"))
        assertTrue(s.problems().any { it.contains("did not report which port is the CPU") })
    }

    @Test
    fun `taking the lan's last socket says so`() {
        val network = DECO_NETWORK_UCI.replace("network.@switch_vlan[0].ports='3 5 0t'", "network.@switch_vlan[0].ports='5 0t'")
        val s = deco(network = network)
        val five = socket(s, "sw:5")
        assertTrue(s.wiredUplinkPreview(five, "dhcp").any { it.contains("keeps no ethernet socket") })
        s.addWiredUplink(five)
        assertTrue(s.notes().any { it.contains("last ethernet socket") })
        assertTrue(s.notes().any { it.contains("Every ethernet client drops") })
    }

    @Test
    fun `an uplink inside the lan's own subnet is flagged on the hub`() {
        assertEquals(emptyList<String>(), deco().subnetClashes())
        val clashing = deco(dump = decoDump(wwanAddress = "192.168.0.50")).subnetClashes()
        assertEquals(1, clashing.size)
        assertTrue(clashing.single().contains("wwan got 192.168.0.50"))
    }

    /** The ISP tag on swconfig is the socket tagged in a VLAN whose id is the ISP's, not a second tag on eth0.N. */
    @Test
    fun `an isp tag on swconfig tags the socket in a vlan of that id`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        s.stageVlan("100")
        assertEquals("100", s.vlanId)
        assertEquals("sw:5", s.port)
        assertTrue(s.deviceDrafts.isEmpty())
        val draft = s.switchVlanDrafts.values.single()
        assertEquals(100, draft.vlan)
        assertEquals("0t 5t", com.vivekkaushik.wrtpulse.ops.Parsers.swPortsValue(draft.ports))
        assertTrue(s.ops().contains("set network.wan.device='eth0.100'"))
        // Re-picking did not pile a second removal onto the LAN VLAN.
        assertEquals(1, s.ops().count { it.startsWith("set network.@switch_vlan[0].ports=") })
    }

    /** The LAN's own VLAN is never the WAN's. */
    @Test
    fun `the lan's vlan id is refused as an isp tag`() {
        val s = deco()
        s.addWiredUplink(socket(s, "sw:5"))
        s.stageVlan("1")
        assertTrue(s.problems().any { it.contains("VLAN 1 is the LAN's own") })
    }

    // ---- DSA: the socket is a netdev in the bridge ----

    private val dsaNetwork = WAN_NETWORK_UCI + """
        
        network.br_lan=device
        network.br_lan.name='br-lan'
        network.br_lan.type='bridge'
        network.br_lan.ports='lan1' 'lan2' 'lan3' 'lan4'
    """.trimIndent()

    private fun dsa(): WanStore = WanStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
        ingest(
            mapOf(
                "net" to dsaNetwork,
                "fw" to WAN_FIREWALL_UCI,
                "dhcp" to DHCP_V6_UCI,
                "dump" to WAN_DUMP,
                "links" to NETDEV_LINES,
                "protos" to PROTO_LS,
            )
        )
    }

    @Test
    fun `dsa sockets are the named ports and the bridge says which are the lan's`() {
        val sockets = dsa().sockets()
        assertEquals(listOf("wan", "lan1", "lan2", "lan3", "lan4"), sockets.map { it.id })
        assertFalse(sockets.single { it.id == "wan" }.inLan)
        assertTrue(sockets.filter { it.id.startsWith("lan") }.all { it.inLan })
        assertFalse(dsa().swconfig)
    }

    /** Moving the WAN onto a LAN port takes the port out of the bridge in the same batch. */
    @Test
    fun `picking a bridged port for the wan removes it from the bridge`() {
        val s = dsa()
        s.stagePort("lan4")
        val ops = s.ops()
        assertTrue(ops.contains("set network.wan.device='lan4.201'"))
        assertTrue(ops.contains("delete network.br_lan.ports"))
        assertTrue(ops.contains("add_list network.br_lan.ports='lan3'"))
        assertFalse(ops.contains("add_list network.br_lan.ports='lan4'"))
        assertTrue(s.touchesSwitch())
        assertTrue(s.touchesDevice())
        // Picking another socket afterwards puts lan4 back: only lan3 is removed now.
        s.stagePort("lan3")
        assertTrue(s.ops().contains("add_list network.br_lan.ports='lan4'"))
        assertFalse(s.ops().contains("add_list network.br_lan.ports='lan3'"))
    }

    @Test
    fun `a dsa uplink on a free socket touches no bridge`() {
        val s = dsa()
        s.addWiredUplink(s.sockets().single { it.id == "wan" })
        assertEquals("wan_2", s.selected)
        assertEquals(
            listOf(
                "set network.wan_2=interface",
                "set network.wan_2.device='wan'",
                "set network.wan_2.metric='30'",
                "set network.wan_2.proto='dhcp'",
                "delete firewall.@zone[1].network",
                "add_list firewall.@zone[1].network='wan'",
                "add_list firewall.@zone[1].network='wan6'",
                "add_list firewall.@zone[1].network='wwan'",
                "add_list firewall.@zone[1].network='wan_2'",
            ),
            s.ops(),
        )
        assertFalse(s.touchesSwitch())
    }
}

class WiredUplinkRefreshTest {
    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    /** The hub refreshes every few seconds; a re-read must not jump off the uplink being drafted. */
    @Test
    fun `a live refresh keeps the drafted uplink selected`() {
        val parts = mapOf(
            "net" to DECO_NETWORK_UCI, "fw" to DECO_FIREWALL_UCI, "dhcp" to DHCP_V6_UCI,
            "dump" to decoDump(), "links" to DECO_NETDEVS, "protos" to PROTO_LS, "swconfig" to SWCONFIG_OUT,
        )
        val s = WanStore(RouterSession(SshTarget("192.168.0.1"), unusedClient, { error("unused") }))
        s.ingest(parts)
        s.addWiredUplink(s.sockets().single { it.id == "sw:5" })
        s.ingest(parts)
        assertEquals("wan", s.selected)
        assertTrue(s.wanRows().any { it.section == "wan" })
    }
}

/**
 * An MT7620A whose LAN sockets hang off a second chip. `eth0` is the internal switch0's CPU
 * port; switch1's sockets reach it through a port of switch0, so a WAN on one of them is two
 * VLANs on two chips — which the app does not write, and says so.
 */
private val TWO_CHIP_NETWORK_UCI = """
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
    network.@switch_vlan[0].ports='5 6t'
    network.@switch[1]=switch
    network.@switch[1].name='switch1'
    network.@switch[1].reset='1'
    network.@switch[1].enable_vlan='1'
    network.@switch_vlan[1]=switch_vlan
    network.@switch_vlan[1].device='switch1'
    network.@switch_vlan[1].vlan='1'
    network.@switch_vlan[1].ports='0 1 2 3 5t'
""".trimIndent()

private const val TWO_CHIP_BOARD = """{ "switch0": { "ports": [ { "num": 6, "device": "eth0" }, { "num": 4, "role": "wan" } ] } }"""

class TwoChipUplinkTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long) = error("unused")
    }

    private fun store(board: String = TWO_CHIP_BOARD): WanStore =
        WanStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "net" to TWO_CHIP_NETWORK_UCI,
                    "fw" to DECO_FIREWALL_UCI,
                    "dhcp" to DHCP_V6_UCI,
                    "dump" to "{}",
                    "links" to "eth0 up 1 1000 02:00:00:00:00:02 phy wired\neth0.1 up 1 - 02:00:00:00:00:02 virt wired",
                    "protos" to PROTO_LS,
                    "swconfig" to TWO_CHIPS_OUT,
                    "board" to board,
                )
            )
        }

    /** Both chips' holes are listed; the uplink chip's come first and keep the plain ids. */
    @Test
    fun `sockets on both chips are listed, each under its own chip`() {
        val sockets = store().sockets()
        assertEquals(listOf("sw:4", "sw:switch1:0", "sw:switch1:1", "sw:switch1:2", "sw:switch1:3"), sockets.map { it.id })
        assertEquals("WAN", sockets.first().label)
        assertEquals("switch1 port 0", sockets[1].label)
        assertEquals(listOf("switch0", "switch1", "switch1", "switch1", "switch1"), sockets.map { it.chip })
        // In the LAN by each chip's own VLAN 1, not the other's.
        assertFalse(sockets.single { it.id == "sw:4" }.inLan)
        assertTrue(sockets.single { it.id == "sw:switch1:2" }.inLan)
        assertTrue(sockets.single { it.id == "sw:switch1:0" }.up)
        assertEquals(0, sockets.single { it.id == "sw:switch1:0" }.switchPort)
        assertEquals("switch1", sockets.single { it.id == "sw:switch1:0" }.switchChip)
    }

    /** The board file says eth0 is switch0's — so switch0 is where a WAN VLAN goes. */
    @Test
    fun `a socket on the uplink chip is wired as before`() {
        val s = store()
        val socket = s.sockets().single { it.id == "sw:4" }
        assertNull(s.uplinkBlock(socket))
        s.addWiredUplink(socket)
        assertTrue(s.ops().contains("set network.swvlan2.device='switch0'"))
        assertTrue(s.ops().contains("set network.swvlan2.ports='4 6t'"))
        assertTrue(s.ops().contains("set network.wan.device='eth0.2'"))
        assertEquals(emptyList<String>(), s.problems())
    }

    /** A socket on the other chip is refused with the reason, and never half-wired. */
    @Test
    fun `a socket on the second chip is refused with the reason`() {
        val s = store()
        val socket = s.sockets().single { it.id == "sw:switch1:2" }
        val block = s.uplinkBlock(socket)!!
        assertTrue(block, block.contains("on switch1") && block.contains("through switch0"))
        assertEquals(listOf(block), s.wiredUplinkPreview(socket, "dhcp").take(1))
        s.addWiredUplink(socket)
        assertTrue(s.problems().any { it == block })
        assertFalse(s.ops().any { it.contains("switch_vlan") })
        assertFalse(s.ops().any { it.contains("@switch_vlan[1].ports") })
    }

    /**
     * Without a board file the first chip is taken as the uplink chip, as it always was, and
     * its sockets are the ports its VLANs name — port 5 here, since nothing says port 4 is a hole.
     */
    @Test
    fun `without a board file the first chip carries the uplink`() {
        val s = store(board = "")
        val ids = s.sockets().map { it.id }
        assertEquals(listOf("sw:5", "sw:switch1:0", "sw:switch1:1", "sw:switch1:2", "sw:switch1:3"), ids)
        assertNull(s.uplinkBlock(s.sockets().single { it.id == "sw:5" }))
        assertNotNull(s.uplinkBlock(s.sockets().single { it.id == "sw:switch1:2" }))
    }
}
