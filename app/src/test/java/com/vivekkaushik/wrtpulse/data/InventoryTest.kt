package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertTrue(reserve.startsWith("uci show dhcp")) // idempotence guard first
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
}
