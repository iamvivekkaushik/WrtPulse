package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.DHCP_UCI
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.LAN_STATUS
import com.vivekkaushik.wrtpulse.ops.NETDEV_LINES
import com.vivekkaushik.wrtpulse.ops.NETWORK_UCI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three leases and a device that never took one but is in the neighbour table. */
private val LEASES = """
    1789412345 aa:5c:1e:88:04:2b 192.168.1.34 pixel-8 01:aa:5c:1e:88:04:2b
    1789400000 3c:22:fb:90:11:5e 192.168.1.21 macbook-pro *
    1789390000 24:6f:28:ae:52:c0 192.168.1.87 * *
""".trimIndent()

/**
 * `ip neigh show` is the whole table, not the LAN's share of it: link-local v6 entries, and
 * neighbours on other interfaces — including, on a router repeating an upstream inside the
 * same /24, the upstream's own gateway.
 */
private val NEIGH = """
    192.168.1.34 dev br-lan.1 lladdr aa:5c:1e:88:04:2b ref 1 used 0/0/0 probes 4 REACHABLE
    192.168.1.10 dev br-lan.1 lladdr 00:11:32:6f:b2:44 ref 1 used 0/0/0 probes 1 REACHABLE
    192.168.1.55 dev br-lan.10 lladdr 9c:8e:cd:11:22:33 ref 1 used 0/0/0 probes 1 STALE
    192.168.1.1 dev phy0-sta0 lladdr 14:a7:2b:ea:57:1c ref 1 used 0/0/0 probes 1 REACHABLE
    fe80::3098:37ff:fee5:fcaf dev br-lan.1 lladdr 32:98:37:e5:fc:af ref 1 used 0/0/0 probes 1 STALE
""".trimIndent()

class LanStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store(): LanStore =
        LanStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "net" to NETWORK_UCI,
                    "dhcp" to DHCP_UCI,
                    "live" to LAN_STATUS,
                    "leases" to LEASES,
                    "neigh" to NEIGH,
                    "links" to NETDEV_LINES,
                    "dnsmasq" to "running",
                )
            )
        }

    // ---- reading ----

    @Test
    fun `one round trip fills the whole screen`() {
        val s = store()
        assertEquals("192.168.1.1", s.routerIp)
        assertEquals(24, s.prefix)
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), s.dns)
        assertEquals(100, s.poolStart)
        assertEquals(150, s.poolLimit)
        assertEquals("12h", s.leaseTime)
        assertTrue(s.dhcpOn)
        assertTrue(s.dnsmasqRunning)
        assertEquals(2, s.reservations.size)
        assertEquals(3, s.leases.size)
        assertEquals(2, s.vlans.size)
        assertEquals("br-lan", s.lanBridge)
        assertEquals(1, s.lanVlan)
    }

    @Test
    fun `the pool is the range dnsmasq will actually hand out`() {
        val range = store().poolRange!!
        assertEquals("192.168.1.100", com.vivekkaushik.wrtpulse.ops.IpMath.format(range.first))
        assertEquals("192.168.1.249", com.vivekkaushik.wrtpulse.ops.IpMath.format(range.last))
    }

    /** A lease and a neighbour entry for the same MAC are one device, not two rows. */
    @Test
    fun `active clients merge leases with the neighbour table`() {
        val clients = store().activeClients()
        assertEquals(
            listOf("192.168.1.10", "192.168.1.21", "192.168.1.34", "192.168.1.87"),
            clients.map { it.ip },
        )
        assertEquals(1, clients.count { it.mac == "aa:5c:1e:88:04:2b" })
    }

    /** Nothing DHCP handed out is a v6 link-local address, so none belong in this list. */
    @Test
    fun `link-local v6 neighbours are not lan clients`() {
        assertTrue(store().activeClients().none { it.ip.startsWith("fe80") })
    }

    /**
     * This router repeats an upstream that is inside its own /24, so the upstream gateway
     * sits in the neighbour table on the station interface. It is not on this LAN, and it
     * turned up as a client offering a Reserve IP button that would have written a
     * reservation for somebody else's router.
     */
    @Test
    fun `a neighbour on another interface is not a lan client`() {
        val clients = store().activeClients()
        assertTrue(clients.none { it.mac == "14:a7:2b:ea:57:1c" })
        assertEquals("br-lan.1", store().lanDevice)
    }

    @Test
    fun `a client with a reservation is marked as one`() {
        val nas = store().activeClients().single { it.mac == "00:11:32:6f:b2:44" }
        assertTrue(nas.reserved)
        assertEquals("192.168.1.10", nas.reservedIp)
        assertFalse(store().activeClients().single { it.ip == "192.168.1.34" }.reserved)
    }

    /** A staged reservation has to show on the client row, or a second tap stages a duplicate. */
    @Test
    fun `a staged reservation marks its client as reserved`() {
        val s = store()
        assertFalse(s.activeClients().single { it.ip == "192.168.1.34" }.reserved)
        s.addReservation("pixel", "aa:5c:1e:88:04:2b", "192.168.1.34")
        assertTrue(s.activeClients().single { it.mac == "aa:5c:1e:88:04:2b" }.reserved)
    }

    /** And a reservation staged for deletion stops claiming its device. */
    @Test
    fun `a reservation staged for deletion stops marking its client`() {
        val s = store()
        assertTrue(s.activeClients().single { it.mac == "00:11:32:6f:b2:44" }.reserved)
        s.stageDelete("dhcp.cfg0a91b2")
        assertFalse(s.activeClients().single { it.mac == "00:11:32:6f:b2:44" }.reserved)
    }

    @Test
    fun `clients are counted per vlan netdev`() {
        assertEquals(2, store().clientsOn("br-lan.1"))
        assertEquals(1, store().clientsOn("br-lan.10"))
    }

    /** The v6 entry on br-lan.1 must not double-count the device that owns it. */
    @Test
    fun `vlan client counts ignore v6 neighbours`() {
        assertEquals(3, store().neighbours.count { it.dev == "br-lan.1" })
        assertEquals(2, store().clientsOn("br-lan.1"))
    }

    // ---- the subnet ----

    @Test
    fun `moving the router address is one op in the network package`() {
        val s = store()
        s.stageRouterIp("192.168.2.1")
        assertTrue(s.movesAddress)
        assertEquals(listOf("set network.lan.ipaddr='192.168.2.1'"), s.ops())
        assertEquals(listOf("network"), s.packages())
    }

    @Test
    fun `staging the saved value back is not a change`() {
        val s = store()
        s.stageRouterIp("192.168.2.1")
        s.stageRouterIp("192.168.1.1")
        assertEquals(0, s.pendingCount)
        assertFalse(s.movesAddress)
    }

    /** The one change that ends the session it is issued from has to say so. */
    @Test
    fun `a subnet move warns that the connection ends`() {
        val s = store()
        s.stageRouterIp("192.168.2.1")
        val notes = s.notes()
        assertTrue(notes.any { it.contains("This connection ends") })
        assertTrue(notes.any { it.contains("192.168.2.1") })
        assertTrue(notes.any { it.contains("first contact") })
        // A stale pin from another router on that address reads as interception unless the
        // note says otherwise — which is what it looked like on the first router moved.
        assertTrue(notes.any { it.contains("changed-key warning") })
    }

    @Test
    fun `an address that is not one is refused`() {
        val s = store()
        s.stageRouterIp("192.168.1.300")
        assertTrue(s.problems().any { it.contains("not an IPv4 address") })
    }

    @Test
    fun `a mask with a hole in it is refused`() {
        val s = store()
        s.stageNetmask("255.0.255.0")
        assertTrue(s.problems().any { it.contains("not a usable netmask") })
    }

    /** A /31 has two addresses and both are reserved; no client could ever join it. */
    @Test
    fun `a subnet too small to hold a client is refused`() {
        val s = store()
        s.stageNetmask("255.255.255.254")
        assertTrue(s.problems().any { it.contains("no addresses for clients") })
    }

    @Test
    fun `the router cannot hold the network or broadcast address`() {
        val network = store().apply { stageRouterIp("192.168.1.0") }
        assertTrue(network.problems().any { it.contains("network address") })
        val broadcast = store().apply { stageRouterIp("192.168.1.255") }
        assertTrue(broadcast.problems().any { it.contains("broadcast address") })
    }

    @Test
    fun `a dns entry that is not an address is refused`() {
        val s = store()
        s.stageDns(s.dns + "not-an-ip")
        assertTrue(s.problems().any { it.contains("DNS not-an-ip") })
    }

    /** uci replaces a whole list, so the ops delete it and build it back. */
    @Test
    fun `changing dns rewrites the list`() {
        val s = store()
        s.stageDns(listOf("1.1.1.1"))
        assertEquals(
            listOf("delete network.lan.dns", "add_list network.lan.dns='1.1.1.1'"),
            s.ops(),
        )
        assertTrue(s.notes().any { it.contains("Clients still ask the router") })
    }

    // ---- the DHCP server ----

    @Test
    fun `turning the server off sets ignore and says what clients lose`() {
        val s = store()
        s.toggleDhcp()
        assertFalse(s.dhcpOn)
        assertEquals(listOf("set dhcp.lan.ignore='1'"), s.ops())
        assertEquals(listOf("dhcp"), s.packages())
        assertTrue(s.notes().any { it.contains("until the lease expires") })
    }

    @Test
    fun `a pool running past the end of the subnet is refused`() {
        val s = store()
        s.stagePoolStart("200")
        assertTrue(s.problems().any { it.contains("runs past") })
    }

    @Test
    fun `a pool covering the router's own address is refused`() {
        val s = store()
        s.stagePoolStart("1")
        assertTrue(s.problems().any { it.contains("router's own address") })
    }

    @Test
    fun `an empty pool is refused`() {
        val s = store()
        s.stagePoolLimit("0")
        assertTrue(s.problems().any { it.contains("would serve nobody") })
    }

    @Test
    fun `a lease time dnsmasq cannot read is refused`() {
        val s = store()
        s.stageLeaseTime("a while")
        assertTrue(s.problems().any { it.contains("not a lease time") })
    }

    /** Shrinking the pool leaves current leases outside it, which changes addresses later. */
    @Test
    fun `shrinking the pool warns about the leases it no longer covers`() {
        val s = store()
        s.stagePoolStart("100")
        s.stagePoolLimit("20")
        assertTrue(s.notes().any { it.contains("outside the new pool") })
    }

    @Test
    fun `removing a dhcp option rewrites the option list`() {
        val s = store()
        s.stageDhcpOptions(s.dhcpOptions - "42,192.168.1.10")
        assertEquals(
            listOf(
                "delete dhcp.lan.dhcp_option",
                "add_list dhcp.lan.dhcp_option='6,9.9.9.9,1.1.1.1'",
            ),
            s.ops(),
        )
    }

    // ---- static leases ----

    @Test
    fun `a new reservation is a named host section`() {
        val s = store()
        s.addReservation("nas-backup", "00:11:32:6f:xx", "192.168.1.60")
        s.updateDraft(s.resvDrafts.single().id, "nas-backup", "00:11:32:6f:b2:45", "192.168.1.60")
        assertEquals(
            listOf(
                "set dhcp.nas_backup=host",
                "set dhcp.nas_backup.name='nas-backup'",
                "set dhcp.nas_backup.mac='00:11:32:6f:b2:45'",
                "set dhcp.nas_backup.ip='192.168.1.60'",
            ),
            s.ops(),
        )
        assertEquals(listOf("dhcp"), s.packages())
    }

    /** A name that already exists as a section gets the `_2` suffix rather than overwriting. */
    @Test
    fun `a section name collision is uniquified`() {
        val s = store()
        s.addReservation("printer", "84:2a:fd:1c:99:31", "192.168.1.62")
        assertTrue(s.ops().contains("set dhcp.printer_2=host"))
    }

    @Test
    fun `a nameless reservation is named after its mac`() {
        val s = store()
        s.addReservation("", "84:2a:fd:1c:99:31", "192.168.1.62")
        assertTrue(s.ops().any { it.startsWith("set dhcp.host_") && it.endsWith("=host") })
    }

    @Test
    fun `a reservation needs a real mac`() {
        val s = store()
        s.addReservation("nas", "00:11:32", "192.168.1.60")
        assertTrue(s.problems().any { it.contains("aa:bb:cc:dd:ee:ff") })
    }

    @Test
    fun `a reservation outside the subnet is refused`() {
        val s = store()
        s.addReservation("nas", "00:11:32:6f:b2:45", "10.0.0.5")
        assertTrue(s.problems().any { it.contains("outside 192.168.1.0/24") })
    }

    @Test
    fun `a reservation on the router's own address is refused`() {
        val s = store()
        s.addReservation("nas", "00:11:32:6f:b2:45", "192.168.1.1")
        assertTrue(s.problems().any { it.contains("router's own address") })
    }

    @Test
    fun `two reservations for one address are refused`() {
        val s = store()
        s.addReservation("nas", "00:11:32:6f:b2:45", "192.168.1.61")
        assertTrue(s.problems().any { it.contains("both reserve 192.168.1.61") })
    }

    @Test
    fun `one mac reserved twice is refused`() {
        val s = store()
        s.addReservation("nas-again", "84:2a:fd:1c:99:30", "192.168.1.62")
        assertTrue(s.problems().any { it.contains("are both 84:2a:fd:1c:99:30") })
    }

    /**
     * A subnet move invalidates every reservation on the old one. Applying both halves would
     * leave dnsmasq refusing to start, so it is stopped here instead.
     */
    @Test
    fun `moving the subnet flags the reservations left behind`() {
        val s = store()
        s.stageRouterIp("192.168.2.1")
        val problems = s.problems()
        assertTrue(problems.any { it.contains("synology-nas") && it.contains("outside 192.168.2.0/24") })
        assertTrue(problems.any { it.contains("printer-hp") })
    }

    @Test
    fun `editing a reservation stages the options that changed`() {
        val s = store()
        s.editReservation("printer", "printer-hp", "84:2a:fd:1c:99:30", "192.168.1.62")
        assertEquals(listOf("set dhcp.printer.ip='192.168.1.62'"), s.ops())
    }

    @Test
    fun `deleting a reservation says what the device loses`() {
        val s = store()
        s.stageDelete("dhcp.printer")
        assertEquals(listOf("delete dhcp.printer"), s.ops())
        assertTrue(s.notes().any { it.contains("printer-hp") && it.contains("192.168.1.61") })
    }

    /** An edit to a section that is about to be deleted is noise; the delete supersedes it. */
    @Test
    fun `an option staged on a deleted section never reaches the batch`() {
        val s = store()
        s.editReservation("printer", "printer-hp", "84:2a:fd:1c:99:30", "192.168.1.62")
        s.stageDelete("dhcp.printer")
        assertEquals(listOf("delete dhcp.printer"), s.ops())
    }

    @Test
    fun `a deleted reservation stops blocking its own address`() {
        val s = store()
        s.stageDelete("dhcp.printer")
        s.addReservation("new-printer", "84:2a:fd:1c:99:31", "192.168.1.61")
        assertTrue(s.problems().none { it.contains("both reserve") })
    }

    /**
     * "Reserve IP" on a client row means "pin the address you already have". The lease that
     * device holds is not a clash with itself — counting it as one refused the commonest
     * action on the screen.
     */
    @Test
    fun `a device's own lease is not a clash when reserving it`() {
        val s = store()
        val leased = IpMath.parse("192.168.1.34")!!
        assertTrue(leased in s.takenAddresses())
        assertFalse(leased in s.takenAddresses(exceptMac = "aa:5c:1e:88:04:2b"))
        // Somebody else's lease still is.
        assertTrue(leased in s.takenAddresses(exceptMac = "3c:22:fb:90:11:5e"))
    }

    /** Editing a reservation must not report its own address as taken either. */
    @Test
    fun `a reservation's own address is not a clash when editing it`() {
        val s = store()
        val own = IpMath.parse("192.168.1.61")!!
        assertTrue(own in s.takenAddresses())
        assertFalse(own in s.takenAddresses(exceptSection = "printer"))
    }

    @Test
    fun `the suggested address is the first free one under the pool`() {
        assertEquals("192.168.1.2", store().suggestedIp())
    }

    @Test
    fun `the sheet says where an address sits`() {
        val s = store()
        assertEquals("in the static range below the pool", s.addressPlacement("192.168.1.60"))
        assertEquals("inside the DHCP pool", s.addressPlacement("192.168.1.120"))
        assertEquals("in the static range above the pool", s.addressPlacement("192.168.1.250"))
        assertEquals("the router's own address", s.addressPlacement("192.168.1.1"))
        assertEquals("outside the LAN subnet", s.addressPlacement("10.0.0.5"))
        assertNull(s.addressPlacement("not-an-ip"))
    }

    /** dnsmasq honours it, but it is also an address it could offer someone else first. */
    @Test
    fun `a reservation inside the pool is allowed with a warning`() {
        val s = store()
        s.addReservation("nas", "00:11:32:6f:b2:45", "192.168.1.120")
        assertTrue(s.problems().isEmpty())
        assertTrue(s.notes().any { it.contains("inside the DHCP pool") })
    }

    // ---- VLANs ----

    @Test
    fun `a port chip cycles off to untagged to tagged and back`() {
        val s = store()
        val vlan10 = s.vlanRows().single { it.vlan == 10 }
        assertEquals(PortState.Off, s.stateOf(vlan10, "lan1"))
        s.cyclePort(vlan10, "lan1")
        assertEquals(PortState.Untagged, s.stateOf(s.vlanRows().single { it.vlan == 10 }, "lan1"))
        s.cyclePort(s.vlanRows().single { it.vlan == 10 }, "lan1")
        assertEquals(PortState.Tagged, s.stateOf(s.vlanRows().single { it.vlan == 10 }, "lan1"))
        s.cyclePort(s.vlanRows().single { it.vlan == 10 }, "lan1")
        assertEquals(PortState.Off, s.stateOf(s.vlanRows().single { it.vlan == 10 }, "lan1"))
        // Back where it started, so nothing is left staged.
        assertEquals(0, s.pendingCount)
    }

    /** An untagged port with no PVID would receive frames that belong to no VLAN. */
    @Test
    fun `an untagged port is written with the pvid marker`() {
        val s = store()
        s.setPort(s.vlanRows().single { it.vlan == 10 }, "lan1", PortState.Untagged)
        assertTrue(s.ops().contains("add_list network.@bridge-vlan[1].ports='lan1:u*'"))
        assertTrue(s.ops().contains("delete network.@bridge-vlan[1].ports"))
    }

    @Test
    fun `the vlan carrying the lan interface cannot be deleted from its row`() {
        val s = store()
        assertNotNull(s.vlanDeleteBlock(s.vlanRows().single { it.vlan == 1 }))
        assertNull(s.vlanDeleteBlock(s.vlanRows().single { it.vlan == 10 }))
    }

    @Test
    fun `deleting the lan's own vlan is refused even if it is staged`() {
        val s = store()
        s.stageDelete("network.@bridge-vlan[0]")
        assertTrue(s.problems().any { it.contains("removes br-lan.1") })
    }

    @Test
    fun `emptying the lan vlan of ports is refused`() {
        val s = store()
        val vlan1 = s.vlanRows().single { it.vlan == 1 }
        vlan1.ports.forEach { s.setPort(s.vlanRows().single { row -> row.vlan == 1 }, it.name, PortState.Off) }
        assertTrue(s.problems().any { it.contains("With no ports it stops existing") })
    }

    @Test
    fun `another vlan can be deleted, and it lands in the network package`() {
        val s = store()
        s.stageDelete("network.@bridge-vlan[1]")
        assertEquals(listOf("delete network.@bridge-vlan[1]"), s.ops())
        assertTrue(s.problems().isEmpty())
    }

    @Test
    fun `a new vlan takes the lowest free id and is written to the lan bridge`() {
        val s = store()
        assertEquals(2, s.freeVlanId())
        s.addVlan(2)
        assertEquals(
            listOf(
                "set network.vlan2=bridge-vlan",
                "set network.vlan2.device='br-lan'",
                "set network.vlan2.vlan='2'",
            ),
            s.ops(),
        )
    }

    @Test
    fun `a duplicate vlan id on the same bridge is refused`() {
        val s = store()
        s.addVlan(10)
        assertTrue(s.problems().any { it.contains("already has a VLAN 10") })
    }

    @Test
    fun `a vlan id outside the standard range is refused`() {
        val s = store()
        s.addVlan(5000)
        assertTrue(s.problems().any { it.contains("1-4094") })
    }

    @Test
    fun `port changes warn about the wired sockets only`() {
        val s = store()
        s.setPort(s.vlanRows().single { it.vlan == 10 }, "lan1", PortState.Tagged)
        assertTrue(s.notes().any { it.contains("wired sockets only") })
    }

    // ---- the batch as a whole ----

    @Test
    fun `both config files commit together, network first`() {
        val s = store()
        s.stagePoolLimit("120")
        s.stageDns(listOf("1.1.1.1"))
        assertEquals(listOf("network", "dhcp"), s.packages())
        assertEquals(
            "$ uci commit network && uci commit dhcp && " +
                "/etc/init.d/network reload && /etc/init.d/dnsmasq restart",
            s.commitLine(),
        )
    }

    @Test
    fun `the commit line says outright when the connection ends`() {
        val s = store()
        s.stageRouterIp("192.168.2.1")
        assertTrue(s.commitLine().contains("this connection ends here"))
    }

    @Test
    fun `the diff shows the old value leaving and the new one arriving`() {
        val s = store()
        s.stagePoolStart("50")
        assertEquals(
            listOf("- dhcp.lan.start='100'" to false, "+ dhcp.lan.start='50'" to true),
            s.diffLines(),
        )
    }

    /** An emptied option is removed, not set to the empty string netifd would then read. */
    @Test
    fun `clearing an option deletes it`() {
        val s = store()
        s.stage("network.lan.ip6assign", "60", "")
        assertEquals(listOf("delete network.lan.ip6assign"), s.ops())
    }

    @Test
    fun `everything staged counts once and revert clears all of it`() {
        val s = store()
        s.stageRouterIp("192.168.1.2")
        s.stageDns(listOf("1.1.1.1"))
        s.stageDelete("dhcp.printer")
        s.addReservation("nas", "00:11:32:6f:b2:45", "192.168.1.60")
        s.addVlan(2)
        assertEquals(5, s.pendingCount)
        s.revert()
        assertEquals(0, s.pendingCount)
        assertEquals(emptyList<String>(), s.ops())
    }

    @Test
    fun `lease countdowns read as time left`() {
        assertEquals("11 h left", LanStore.leaseLeft(nowS = 0, expiry = 11 * 3600 + 60))
        assertEquals("45 m left", LanStore.leaseLeft(nowS = 0, expiry = 45 * 60))
        assertEquals("2 d left", LanStore.leaseLeft(nowS = 0, expiry = 2 * 86400 + 5))
        assertEquals("expired", LanStore.leaseLeft(nowS = 100, expiry = 50))
        assertEquals("no lease", LanStore.leaseLeft(nowS = 100, expiry = 0))
    }

    @Test
    fun `a mac is validated in the shape uci writes`() {
        assertTrue(LanStore.validMac("aa:5c:1e:88:04:2b"))
        assertTrue(LanStore.validMac("AA:5C:1E:88:04:2B"))
        assertFalse(LanStore.validMac("aa:5c:1e:88:04"))
        assertFalse(LanStore.validMac("aa-5c-1e-88-04-2b"))
        assertFalse(LanStore.validMac(""))
    }

    /**
     * A config that keeps its address as `ipaddr '192.168.1.1/24'` has no netmask option, so
     * writing address and mask as two ops would drop the prefix. Both collapse into one.
     */
    @Test
    fun `a CIDR config is written back as CIDR`() {
        val s = cidrStore()
        assertTrue(s.cidrStyle)
        assertEquals("192.168.1.1", s.routerIp)
        assertEquals(24, s.prefix)
        assertEquals("255.255.255.0", s.netmask)
        assertTrue(s.problems().isEmpty())

        s.stageRouterIp("192.168.5.1")
        assertEquals(listOf("set network.lan.ipaddr='192.168.5.1/24'"), s.ops())
        assertTrue(s.movesAddress)
    }

    @Test
    fun `a mask change on a CIDR config moves the prefix, not a netmask option`() {
        val s = cidrStore()
        s.stageNetmask("255.255.0.0")
        assertEquals(listOf("set network.lan.ipaddr='192.168.1.1/16'"), s.ops())
        assertEquals(
            listOf(
                "- network.lan.ipaddr='192.168.1.1/24'" to false,
                "+ network.lan.ipaddr='192.168.1.1/16'" to true,
            ),
            s.diffLines(),
        )
    }

    /** And a config with a real netmask option still writes both, separately. */
    @Test
    fun `a netmask config writes address and mask as two ops`() {
        val s = store()
        s.stageRouterIp("192.168.5.1")
        s.stageNetmask("255.255.0.0")
        assertEquals(
            listOf(
                "set network.lan.ipaddr='192.168.5.1'",
                "set network.lan.netmask='255.255.0.0'",
            ),
            s.ops(),
        )
    }

    private fun cidrStore(): LanStore =
        LanStore(RouterSession(SshTarget("192.168.1.1"), unusedClient, { error("unused") })).apply {
            ingest(
                mapOf(
                    "net" to com.vivekkaushik.wrtpulse.ops.NETWORK_UCI_CIDR,
                    "dhcp" to DHCP_UCI,
                    "live" to "{}",
                    "leases" to LEASES,
                    "neigh" to NEIGH,
                    "links" to NETDEV_LINES,
                    "dnsmasq" to "running",
                )
            )
        }

    /** A board with no bridge VLANs has none to show, and nothing pretends otherwise. */
    @Test
    fun `an swconfig board reports its vlans read-only`() {
        val s = LanStore(RouterSession(SshTarget("192.168.2.1"), unusedClient, { error("unused") }))
        s.ingest(
            mapOf(
                "net" to """
                    network.lan=interface
                    network.lan.ifname='eth0.1'
                    network.lan.proto='static'
                    network.lan.ipaddr='192.168.2.1'
                    network.lan.netmask='255.255.255.0'
                    network.@switch_vlan[0]=switch_vlan
                    network.@switch_vlan[0].device='switch0'
                    network.@switch_vlan[0].vlan='1'
                    network.@switch_vlan[0].ports='0 1 2 3 6t'
                """.trimIndent(),
                "dhcp" to "",
                "live" to "{}",
                "leases" to "",
                "neigh" to "",
                "links" to "eth0 up 1 100 02:00:00:00:00:02 phy wired",
                "dnsmasq" to "stopped",
            )
        )
        assertTrue(s.vlanRows().isEmpty())
        assertEquals(1, s.swVlans.size)
        assertFalse(s.dnsmasqRunning)
        // No dhcp section for lan at all: the pool fields fall back rather than crash.
        assertNull(s.pool)
        assertEquals(100, s.poolStart)
    }
}
