package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryTest {

    private val wifiJson = """
        {
          "radio0": {
            "up": true,
            "config": { "band": "2g", "channel": "1" },
            "interfaces": [
              { "section": "default_radio0", "ifname": "phy0-ap0", "config": { "mode": "ap", "ssid": "Casa-IoT" } }
            ]
          },
          "radio1": {
            "up": true,
            "config": { "hwmode": "11a" },
            "interfaces": [
              { "section": "default_radio1", "ifname": "phy1-ap0", "config": { "mode": "ap", "ssid": "Casa" } },
              { "section": "guest", "ifname": "phy1-ap1", "config": { "mode": "ap", "ssid": "Casa-Guest" } },
              { "section": "mesh0", "ifname": "phy1-mesh0", "config": { "mode": "mesh", "ssid": "mesh-net" } }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `wireless status parses bands and skips non-ap modes`() {
        val ifaces = Parsers.wirelessStatus(wifiJson)
        assertEquals(3, ifaces.size)
        assertEquals("2.4G", ifaces.first { it.ssid == "Casa-IoT" }.band)
        assertEquals("5G", ifaces.first { it.ssid == "Casa" }.band)   // hwmode fallback
        assertTrue(ifaces.none { it.ssid == "mesh-net" })
    }

    @Test
    fun `neigh entries keep device and skip entries without a mac`() {
        val text = """
            192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE
            192.168.2.87 dev br-lan lladdr 24:6f:28:ae:52:c0 STALE
            100.64.0.1 dev eth0 lladdr 00:01:5c:aa:bb:cc REACHABLE
            192.168.2.99 dev br-lan FAILED
            fe80::1 dev br-lan lladdr aa:5c:1e:88:04:2b router REACHABLE
        """.trimIndent()
        val entries = Parsers.neighEntries(text)
        assertEquals(4, entries.size)
        assertEquals("br-lan", entries[0].dev)
        assertEquals("aa:5c:1e:88:04:2b", entries[0].mac)
    }

    @Test
    fun `merge classifies wireless wired and offline`() {
        val now = 1_000_000L
        val leases = Parsers.leases(
            """
            ${now + 82800} aa:5c:1e:88:04:2b 192.168.2.34 pixel-8 01:aa:5c:1e:88:04:2b
            ${now + 3600} 00:11:32:6f:b2:44 192.168.2.10 synology-nas *
            ${now + 7200} de:ad:be:ef:00:01 192.168.2.77 sleeping-laptop *
            """.trimIndent()
        )
        val neigh = Parsers.neighEntries(
            """
            192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE
            192.168.2.10 dev br-lan lladdr 00:11:32:6f:b2:44 STALE
            100.64.0.1 dev eth0 lladdr 00:01:5c:aa:bb:cc REACHABLE
            """.trimIndent()
        )
        val stations = Parsers.stations(
            """
            # phy1-ap0
            AA:5C:1E:88:04:2B  -52 dBm / -95 dBm (SNR 43)  0 ms ago
                RX: 780.0 MBit/s   1234 Pkts.
                TX: 866.7 MBit/s   4321 Pkts.
            """.trimIndent()
        )
        val wifi = Parsers.wirelessStatus(wifiJson)

        val clients = Inventory.merge(leases, neigh, stations, wifi, now)
        assertEquals(3, clients.size)

        val pixel = clients.first { it.mac == "aa:5c:1e:88:04:2b" }
        assertEquals("pixel-8", pixel.name)
        assertEquals("192.168.2.34", pixel.ip)
        assertEquals("Casa · 5G", pixel.network)
        assertEquals(4, pixel.bars)
        assertEquals(-52, pixel.signalDbm)
        assertEquals("lease 23 h", pixel.leaseLabel)
        assertEquals(866.7f, pixel.downMbps!!, 0.01f)

        val nas = clients.first { it.mac == "00:11:32:6f:b2:44" }
        assertEquals(-1, nas.bars)
        assertEquals("LAN", nas.network)
        assertEquals("synology-nas", nas.name)

        val sleeper = clients.first { it.mac == "de:ad:be:ef:00:01" }
        assertTrue(sleeper.offline)

        // The WAN gateway neighbour on eth0 must never appear as a client.
        assertTrue(clients.none { it.mac == "00:01:5c:aa:bb:cc" })
    }

    @Test
    fun `expired lease is not shown as offline`() {
        val now = 1_000_000L
        val leases = Parsers.leases("${now - 10} de:ad:be:ef:00:02 192.168.2.78 gone-device *")
        val clients = Inventory.merge(leases, emptyList(), emptyList(), emptyList(), now)
        assertTrue(clients.isEmpty())
    }

    @Test
    fun `blocked rules and rename overrides apply in merge`() {
        val blocked = Parsers.blockedMacs(
            "firewall.@rule[12].name='wrtpulse-block-aa:5c:1e:88:04:2b'"
        )
        assertEquals(setOf("aa:5c:1e:88:04:2b"), blocked)

        val now = 1_000_000L
        val leases = Parsers.leases("${now + 3600} aa:5c:1e:88:04:2b 192.168.2.34 pixel-8 *")
        val neigh = Parsers.neighEntries("192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE")
        val clients = Inventory.merge(
            leases, neigh, emptyList(), emptyList(), now,
            blockedMacs = blocked,
            nameOverrides = mapOf("aa:5c:1e:88:04:2b" to "Vivek's Pixel"),
        )
        val c = clients.single()
        assertTrue(c.blocked)
        assertEquals("Vivek's Pixel", c.name)
        assertTrue(c.editable)
    }

    @Test
    fun `action commands have expected shapes`() {
        val block = com.vivekkaushik.wrtpulse.ops.Commands.blockClient("aa:bb:cc:dd:ee:ff")
        assertTrue(block.contains("name='wrtpulse-block-aa:bb:cc:dd:ee:ff'"))
        assertTrue(block.contains("src_mac='aa:bb:cc:dd:ee:ff'"))
        assertTrue(block.contains("target='REJECT'"))
        assertTrue(block.endsWith("/etc/init.d/firewall reload >/dev/null 2>&1"))

        val unblock = com.vivekkaushik.wrtpulse.ops.Commands.unblockClient("aa:bb:cc:dd:ee:ff")
        assertTrue(unblock.contains("grep \"wrtpulse-block-aa:bb:cc:dd:ee:ff\""))
        assertTrue(unblock.contains("uci delete firewall.\$s"))
        assertTrue(unblock.trimEnd().endsWith("; :")) // absent rule is success, not an error

        assertTrue(com.vivekkaushik.wrtpulse.ops.Commands.wake("aa:bb:cc:dd:ee:ff").contains("ether-wake"))

        val reserve = com.vivekkaushik.wrtpulse.ops.Commands.reserveIp("aa:bb:cc:dd:ee:ff", "192.168.2.34", "pixel-8")
        assertTrue(reserve.contains("dhcp.@host[-1].ip='192.168.2.34'"))
        assertTrue(reserve.startsWith("s=")) // looks for an existing section for this MAC first
    }

    @Test
    fun `dhcp reservations are read back and attached to the client`() {
        val uci = Parsers.uciShow(
            """
            dhcp.@host[0]=host
            dhcp.@host[0].name='pixel-8'
            dhcp.@host[0].mac='AA:5C:1E:88:04:2B'
            dhcp.@host[0].ip='192.168.2.50'
            dhcp.printer=host
            dhcp.printer.mac='00:11:32:6f:b2:44'
            dhcp.printer.ip='192.168.2.51'
            dhcp.@host[1]=host
            dhcp.@host[1].mac='de:ad:be:ef:00:09'
            dhcp.lan=dhcp
            dhcp.lan.interface='lan'
            """.trimIndent()
        )
        val reservations = Parsers.dhcpReservations(uci)
        // Named and anonymous sections both count; one without an ip does not.
        assertEquals(2, reservations.size)
        assertEquals("192.168.2.50", reservations["aa:5c:1e:88:04:2b"]) // lower-cased to match leases
        assertEquals("192.168.2.51", reservations["00:11:32:6f:b2:44"])

        val now = 1_000_000L
        val leases = Parsers.leases("${now + 3600} aa:5c:1e:88:04:2b 192.168.2.34 pixel-8 *")
        val neigh = Parsers.neighEntries("192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE")
        val client = Inventory.merge(
            leases, neigh, emptyList(), emptyList(), now, reservations = reservations,
        ).single()
        // Still on its old pool address until the lease renews — both are worth showing.
        assertEquals("192.168.2.34", client.ip)
        assertEquals("192.168.2.50", client.staticIp)
    }

    @Test
    fun `reserve updates an existing entry and release removes it`() {
        val reserve = com.vivekkaushik.wrtpulse.ops.Commands.reserveIp(
            "aa:bb:cc:dd:ee:ff", "192.168.2.50", "pixel",
        )
        // Reuses the section when the MAC already has one, instead of silently doing nothing.
        assertTrue(reserve.contains("uci set dhcp.\$s.ip='192.168.2.50'"))
        assertTrue(reserve.contains("uci add dhcp host"))
        assertTrue(reserve.contains("/etc/init.d/dnsmasq restart"))

        val release = com.vivekkaushik.wrtpulse.ops.Commands.releaseIp("aa:bb:cc:dd:ee:ff")
        assertTrue(release.contains("uci delete dhcp.\$s"))
        assertTrue(release.contains("grep -i"))          // uci may store the MAC upper-cased
        assertTrue(release.trimEnd().endsWith("; :"))    // no reservation is success, not failure
    }

    @Test
    fun `signal to bars thresholds`() {
        assertEquals(4, Inventory.barsFor(-50))
        assertEquals(3, Inventory.barsFor(-60))
        assertEquals(2, Inventory.barsFor(-70))
        assertEquals(1, Inventory.barsFor(-80))
    }

    @Test
    fun `lease label formats hours and minutes`() {
        assertEquals("lease 23 h", Inventory.leaseLabel(1_000_000 + 82_800, 1_000_000))
        assertEquals("lease 30 min", Inventory.leaseLabel(1_000_000 + 1_800, 1_000_000))
        assertNull(Inventory.leaseLabel(0, 1_000_000))
    }

    /**
     * Wired and offline clients both carry `bars = -1`, so the list icon used to render them
     * identically — a machine on the cable looked exactly like one that had gone home. The
     * two are only separable together with [Client.offline].
     */
    @Test
    fun `a wired client is not confused with an offline one`() {
        val wired = Client(name = "NitishPC", ip = "192.168.2.170", mac = "d8:c4:97:d5:eb:d1", network = "LAN", bars = -1)
        val gone = wired.copy(name = "laptop", offline = true)
        assertTrue(wired.wired)
        assertFalse(gone.wired)
    }

    @Test
    fun `a wireless client is never wired, whatever its signal`() {
        val strong = Client(name = "phone", ip = "192.168.2.1", mac = "a", network = "5G", bars = 4)
        val weak = strong.copy(bars = 1)
        assertFalse(strong.wired)
        assertFalse(weak.wired)
    }
}
