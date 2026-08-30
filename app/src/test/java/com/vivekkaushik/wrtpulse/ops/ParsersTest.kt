package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real OpenWrt 23.05 output shapes. */
class ParsersTest {

    @Test
    fun `board json maps to the onboarding summary`() {
        val board = Parsers.board(
            """
            {"kernel":"5.15.150","hostname":"home","system":"MediaTek MT7986A",
             "model":"GL.iNet GL-MT6000","board_name":"glinet,gl-mt6000",
             "release":{"distribution":"OpenWrt","version":"23.05.3","revision":"r23809-234f1a2efa",
             "target":"mediatek/filogic"}}
            """.trimIndent()
        )
        assertEquals("GL.iNet GL-MT6000", board.model)
        assertEquals("glinet,gl-mt6000", board.boardName)
        assertEquals("OpenWrt 23.05.3", board.release)
        assertTrue(board.summary.startsWith("OpenWrt 23.05.3 · r23809-234f1a2efa · MediaTek MT7986A"))
    }

    @Test
    fun `system info unscales load and computes used memory like luci`() {
        val info = Parsers.systemInfo(
            """
            {"uptime":1570332,"localtime":1756470000,"load":[27525,24903,20316],
             "memory":{"total":1006632960,"free":700000000,"shared":1000,"buffered":6000000,
             "available":800000000,"cached":100000000}}
            """.trimIndent()
        )
        assertEquals("18d 04:12", info.uptimeLabel)
        assertEquals(0.42, info.load1, 0.01)
        // (total - free - buffered - cached) / total
        assertEquals(19, info.memUsedPercent)
    }

    @Test
    fun `cpu percent needs two samples and counts iowait as idle`() {
        val first = Parsers.cpuSample("cpu  1000 0 500 8000 500 0 0 0")!!
        val second = Parsers.cpuSample("cpu  1200 0 600 8600 500 0 0 0")!!
        assertEquals(0, first.percentSince(null))
        // busy delta 300, total delta 900 -> 33%
        assertEquals(33, second.percentSince(first))
        assertNull(Parsers.cpuSample("intr 12345"))
    }

    @Test
    fun `net dev yields rx and tx byte counters per interface`() {
        val counters = Parsers.netCounters(
            """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:    1234      12    0    0    0     0          0         0     1234      12    0    0    0     0       0          0
             pppoe-wan: 987654321 1000    0    0    0     0          0         0 123456789     900    0    0    0     0       0          0
            """.trimIndent()
        )
        val wan = counters.getValue("pppoe-wan")
        assertEquals(987654321L, wan.rxBytes)
        assertEquals(123456789L, wan.txBytes)
        assertTrue(counters.containsKey("lo"))
    }


    @Test
    fun `overlay percent read from df`() {
        assertEquals(34, Parsers.overlayUsedPercent("/dev/ubi0_1  102400  34816  67584  34% /overlay"))
        assertEquals(0, Parsers.overlayUsedPercent(""))
    }

    @Test
    fun `sections split a batched tick`() {
        val parsed = Parsers.sections(
            """
            ___wrt___ info
            {"uptime":10}
            ___wrt___ stat
            cpu  1 2 3 4
            ___wrt___ netdev
            header
            """.trimIndent()
        )
        assertEquals(listOf("info", "stat", "netdev"), parsed.keys.toList())
        assertEquals("""{"uptime":10}""", parsed["info"])
        assertEquals("cpu  1 2 3 4", parsed["stat"])
    }

    @Test
    fun `leases keep hostnames and drop the star placeholder`() {
        val leases = Parsers.leases(
            """
            1756476000 aa:5c:1e:88:04:2b 192.168.1.34 pixel-8 01:aa:5c:1e:88:04:2b
            1756477112 3c:22:fb:90:11:5e 192.168.1.21 * *
            """.trimIndent()
        )
        assertEquals(2, leases.size)
        assertEquals("pixel-8", leases[0].hostname)
        assertNull(leases[1].hostname)
        assertEquals("192.168.1.21", leases[1].ip)
    }

    @Test
    fun `neighbours map macs to addresses`() {
        val neigh = Parsers.neighbours(
            """
            192.168.1.10 dev br-lan lladdr 00:11:32:6F:B2:44 REACHABLE
            192.168.1.99 dev br-lan  FAILED
            """.trimIndent()
        )
        assertEquals(mapOf("00:11:32:6f:b2:44" to "192.168.1.10"), neigh)
    }

    @Test
    fun `assoclist yields signal and rates per interface`() {
        val stations = Parsers.stations(
            """
            # wlan1
            AA:5C:1E:88:04:2B  -52 dBm / -95 dBm (SNR 43)  0 ms ago
	            RX: 780.0 MBit/s, 80 MHz, VHT-MCS 9   1234 Pkts.
	            TX: 866.7 MBit/s, 80 MHz, VHT-MCS 9   4321 Pkts.
            # wlan0
            3C:22:FB:90:11:5E  -71 dBm / -95 dBm (SNR 24)  10 ms ago
	            RX: 144.4 MBit/s   10 Pkts.
	            TX: 130.0 MBit/s   20 Pkts.
            """.trimIndent()
        )
        assertEquals(2, stations.size)
        assertEquals("wlan1", stations[0].iface)
        assertEquals("aa:5c:1e:88:04:2b", stations[0].mac)
        assertEquals(-52, stations[0].signalDbm)
        assertEquals(780.0, stations[0].rxMbps, 0.01)
        assertEquals(866.7, stations[0].txMbps, 0.01)
        assertEquals("wlan0", stations[1].iface)
        assertEquals(-71, stations[1].signalDbm)
    }

    @Test
    fun `uci show strips quotes`() {
        val uci = Parsers.uciShow(
            """
            wireless.radio0=wifi-device
            wireless.radio0.channel='11'
            wireless.@wifi-iface[0].ssid='Casa'
            wireless.@wifi-iface[0].disabled='0'
            """.trimIndent()
        )
        assertEquals("11", uci["wireless.radio0.channel"])
        assertEquals("Casa", uci["wireless.@wifi-iface[0].ssid"])
        assertEquals("wifi-device", uci["wireless.radio0"])
    }

    @Test
    fun `uci batch wraps operations in a heredoc and commits once`() {
        val script = Commands.uciBatch(
            listOf("set wireless.radio0.channel='11'", "set wireless.@wifi-iface[2].disabled='0'"),
            commitPackage = "wireless",
            reload = "wifi reload",
        )
        assertTrue(script.startsWith("uci batch <<'WRTPULSE_EOF'"))
        assertTrue(script.contains("set wireless.radio0.channel='11'"))
        assertTrue(script.trimEnd().endsWith("uci commit wireless && wifi reload"))
    }
}
