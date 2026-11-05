package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSamplingTest {

    private fun tick(
        uptime: String,
        retries: Long = 0,
        failed: Long = 0,
        signal: Int = -55,
        netdev: String = NETDEV,
        ping: String = PING_OK,
        inet: String = PING_OK,
    ): Map<String, String> = mapOf(
        "uptime" to uptime,
        "loadavg" to "0.40 0.30 0.25 1/60 1200",
        "cpu" to "cpu  100 0 100 7800 0 0 0 0",
        "mem" to "MemTotal:       61440 kB\nMemAvailable:   24576 kB",
        "netdev" to netdev,
        "assoc" to
            "Station aa:bb:cc:dd:ee:ff (on wlan1)\n\tsignal:\t$signal dBm\n\ttx bitrate:\t6.0 MBit/s\n" +
                "\ttx retries:\t$retries\n\ttx failed:\t$failed",
        "ping" to ping,
        "inet" to inet,
    )

    @Test
    fun `parseTick reads counters, stations and both pings`() {
        val s = AiSampling.parseTick(tick("1000"), atMs = 1_000)
        assertEquals(1000L, s.uptimeSeconds)
        assertEquals(0.40, s.load1, 1e-9)
        assertEquals(61440L, s.memTotalKb)
        assertEquals(24576L, s.memAvailKb)
        assertEquals(60, s.memPct)
        assertEquals(1, s.stations.size)
        assertEquals(0L, s.stations[0].txRetries)
        assertEquals(-55, s.stations[0].signalDbm)
        assertEquals("aa:bb:cc:dd:ee:ff", s.stations[0].mac)
        assertEquals(0, s.ping!!.lossPct)
        assertEquals(14.7, s.ping!!.avgMs!!, 1e-9)
        assertEquals(18.2, s.ping!!.maxMs!!, 1e-9)
        assertTrue(s.net.containsKey("wlan1"))
        // The CPU aggregate line parses with idle+idle counted.
        assertEquals(7800L, s.cpu!!.idle)
        assertEquals(8000L, s.cpu!!.total)
    }

    @Test
    fun `parseTick survives a router without iw and without ping replies`() {
        val s = AiSampling.parseTick(
            mapOf(
                "uptime" to "10",
                "loadavg" to "0.1 0.1 0.1 1/50 900",
                "cpu" to "cpu  1 0 1 78 0 0 0 0",
                "mem" to "MemTotal:   61440 kB\nMemAvailable:   40000 kB",
                "netdev" to NETDEV,
                "assoc" to "aa:bb:cc:dd:ee:ff  -52 dBm / -95 dBm (SNR 43)  0 ms ago\n  RX: 780.0 MBit/s   1234 Pkts.\n  TX: 866.7 MBit/s   4321 Pkts.",
                "ping" to "",
                "inet" to "ping: bad address '1.1.1.1'",
            ),
            atMs = 0,
        )
        // iwinfo assoclist shape: station known, retries not measured (zeros, not lies).
        assertEquals(1, s.stations.size)
        assertEquals(0L, s.stations[0].txRetries)
        // A failed ping run is total loss, not "unknown".
        assertNull(s.ping)
        assertEquals(100, s.internet!!.lossPct)
    }

    @Test
    fun `analyze differences the retries failed cpu and rates over the window`() {
        val first = AiSampling.parseTick(tick("1000", retries = 500, failed = 3), 0)
        val second = AiSampling.parseTick(tick("1060", retries = 2375, failed = 24), 30_000)
        val t = AiSampling.analyze(listOf(first, second))!!
        assertEquals(60.0, t.windowSeconds, 1e-9)
        assertEquals(1875L, t.retryDelta)
        assertEquals(21L, t.failedDelta)
        // cpu: idle 7800→7860 of total 8000→8060 → 80% busy? dTotal=60, dIdle=60 → 0%.
        assertEquals(0, t.cpuPct)
        assertEquals(1, t.stations.size)
        assertEquals(1875L, t.stations[0].retriesDelta)
    }

    @Test
    fun `analyze computes cpu percent between samples`() {
        val first = AiSampling.parseTick(tick("1000"), 0)
        // idle 7800→7830, total 8000→8060: half the new jiffies are idle → 50% busy.
        val second = AiSampling.parseTick(tick("1060").toMutableMap().apply {
            put("cpu", "cpu  130 0 100 7830 0 0 0 0")
        }, 30_000)
        val t = AiSampling.analyze(listOf(first, second))!!
        assertEquals(50, t.cpuPct)
    }

    @Test
    fun `a station that reconnected does not count as a cure`() {
        val first = AiSampling.parseTick(tick("1000", retries = 5000, failed = 100), 0)
        val second = AiSampling.parseTick(tick("1060", retries = 10, failed = 0), 30_000)
        val t = AiSampling.analyze(listOf(first, second))!!
        // Counters reset below their previous values → clamped at zero, never negative.
        assertEquals(0L, t.retryDelta)
        assertEquals(0L, t.failedDelta)
    }

    @Test
    fun `one sample claims nothing`() {
        assertNull(AiSampling.analyze(listOf(AiSampling.parseTick(tick("1000"), 0))))
    }

    @Test
    fun `three pings fold into one loss and the worst max`() {
        val ok = AiSampling.parseTick(tick("1000"), 0)
        val flaky = AiSampling.parseTick(
            tick("1010").toMutableMap().apply { put("inet", PING_LOSSY) },
            10_000,
        )
        val ok2 = AiSampling.parseTick(tick("1020"), 20_000)
        val t = AiSampling.analyze(listOf(ok, flaky, ok2))!!
        assertEquals(3, t.sampleCount)
        // internet: 9 sent, 8 received → 11% loss, worst max 210ms.
        assertEquals(9, t.internet!!.sent)
        assertEquals(8, t.internet!!.received)
        assertEquals(11, t.internet!!.lossPct)
        assertEquals(210.0, t.internet!!.maxMs!!, 1e-9)
        assertEquals(0, t.gateway!!.lossPct)
    }

    @Test
    fun `interface rates come out per second`() {
        val first = AiSampling.parseTick(tick("1000"), 0)
        val second = AiSampling.parseTick(
            tick("1060").toMutableMap().apply {
                put("netdev", NETDEV.replace("1000000", "1600000").replace("500000", "800000"))
            },
            30_000,
        )
        val t = AiSampling.analyze(listOf(first, second))!!
        val wlan1 = t.interfaces.first { it.iface == "wlan1" }
        // 600000 bytes / 60 s / 1024 → 9.77 kB/s.
        assertEquals(9.77, wlan1.rxKbS, 0.05)
        assertEquals(4.88, wlan1.txKbS, 0.05)
    }

    private companion object {
        val NETDEV =
            "Inter-|   Receive                                                |  Transmit\n" +
                " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" +
                "  wlan1: 1000000    2000    0    0    0     0          0         0  500000    1000    0    0    0     0       0          0"

        val PING_OK =
            "PING 1.1.1.1 (1.1.1.1): 56 data bytes\n\n--- 1.1.1.1 ping statistics ---\n" +
                "3 packets transmitted, 3 packets received, 0% packet loss\n" +
                "round-trip min/avg/max = 12.1/14.7/18.2 ms"

        val PING_LOSSY =
            "PING 1.1.1.1 (1.1.1.1): 56 data bytes\n\n--- 1.1.1.1 ping statistics ---\n" +
                "3 packets transmitted, 2 packets received, 33% packet loss\n" +
                "round-trip min/avg/max = 150.0/180.5/210.0 ms"
    }
}
