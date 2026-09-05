package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun telemetry() =
        Telemetry(RouterSession(SshTarget("router.test"), unusedClient, { error("unused") }))

    private fun tickOutput(cpuIdle: Long, cpuTotalBusy: Long, rxBytes: Long, txBytes: Long) = """
        ___wrt___ info
        {"uptime": 1566725, "load": [27520, 24896, 22336], "memory": {"total": 1024000000, "free": 256000000, "buffered": 128000000, "cached": 128000000}}
        ___wrt___ stat
        cpu  $cpuTotalBusy 0 0 $cpuIdle 0 0 0 0
        ___wrt___ netdev
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes      packets errs drop fifo colls carrier compressed
        eth0: $rxBytes 100 0 0 0 0 0 0 $txBytes 50 0 0 0 0 0 0
        br-lan: 999 9 0 0 0 0 0 0 999 9 0 0 0 0 0 0
        ___wrt___ overlay
        /dev/loop0 104857600 35651584 69206016 34% /overlay
        ___wrt___ ifaces
        {"interface": [
          {"interface": "lan", "up": true, "l3_device": "br-lan", "proto": "static",
           "ipv4-address": [{"address": "192.168.1.1", "mask": 24}], "route": []},
          {"interface": "wan", "up": true, "l3_device": "eth0", "proto": "dhcp",
           "ipv4-address": [{"address": "82.44.19.7", "mask": 24}],
           "route": [{"target": "0.0.0.0", "mask": 0, "nexthop": "82.44.19.1"}]}
        ]}
        ___wrt___ essid
    """.trimIndent()

    @Test
    fun `two ticks produce cpu percent and wan throughput`() {
        val t = telemetry()
        t.ingest(Parsers.sections(tickOutput(cpuIdle = 1000, cpuTotalBusy = 1000, rxBytes = 0, txBytes = 0)), 0L)

        assertEquals("82.44.19.7", t.wanIp)
        assertEquals("eth0", t.wanDevice)
        assertEquals(34, t.flashPct)
        assertEquals(50, t.ramPct) // (1024 - 256 - 128 - 128) / 1024
        assertEquals("18d 03:12", t.uptimeLabel)
        assertEquals(0.42f, t.load1, 0.005f)

        // Second tick 1 s later: 100 busy vs 100 idle jiffies -> 50 % CPU; 1.25 MB down in 1 s -> 10 Mbps.
        t.ingest(
            Parsers.sections(tickOutput(cpuIdle = 1100, cpuTotalBusy = 1100, rxBytes = 1_250_000, txBytes = 250_000)),
            1_000_000_000L,
        )
        assertEquals(50, t.cpuPct)
        assertEquals(10f, t.down.last(), 0.01f)
        assertEquals(2f, t.up.last(), 0.01f)
    }

    @Test
    fun `wan device counter reset does not produce a negative rate`() {
        val t = telemetry()
        t.ingest(Parsers.sections(tickOutput(1000, 1000, rxBytes = 5_000_000, txBytes = 1_000_000)), 0L)
        t.ingest(Parsers.sections(tickOutput(1100, 1100, rxBytes = 1_000, txBytes = 500)), 1_000_000_000L)
        assertEquals(0f, t.down.last(), 0.001f)
        assertEquals(0f, t.up.last(), 0.001f)
    }

    @Test
    fun `mbps math`() {
        assertEquals(10f, Telemetry.mbps(1_250_000, 1.0), 0.001f)
        assertEquals(0f, Telemetry.mbps(-5, 1.0), 0.001f)
        assertEquals(0f, Telemetry.mbps(100, 0.0), 0.001f)
    }

    @Test
    fun `bytes label picks a sensible unit`() {
        assertEquals("214.0 GB", Telemetry.bytesLabel(214_000_000_000L))
        assertEquals("38 MB", Telemetry.bytesLabel(38_000_000L))
        assertEquals("5 kB", Telemetry.bytesLabel(5_000L))
    }

    @Test
    fun `chart normalisation shares one scale across both series`() {
        val (down, up) = Telemetry.normalize(listOf(0f, 50f, 200f), listOf(0f, 20f, 100f))
        assertEquals(100f, down.last(), 0.001f)
        assertEquals(50f, up.last(), 0.001f)
        assertEquals(25f, down[1], 0.001f)
    }

    /** The same tick shape after the default route has moved to a Wi-Fi client. */
    private fun movedUpstream(rxBytes: Long, txBytes: Long) = """
        ___wrt___ info
        {"uptime": 1566725, "load": [27520, 24896, 22336], "memory": {"total": 1024000000, "free": 256000000, "buffered": 128000000, "cached": 128000000}}
        ___wrt___ stat
        cpu  1100 0 0 1100 0 0 0 0
        ___wrt___ netdev
        Inter-|   Receive                                                |  Transmit
         face |bytes    packets errs drop fifo frame compressed multicast|bytes      packets errs drop fifo colls carrier compressed
        eth0: 9000000000 100 0 0 0 0 0 0 9000000000 50 0 0 0 0 0 0
        phy0-sta0: $rxBytes 10 0 0 0 0 0 0 $txBytes 5 0 0 0 0 0 0
        ___wrt___ overlay
        /dev/loop0 104857600 35651584 69206016 34% /overlay
        ___wrt___ ifaces
        {"interface": [
          {"interface": "wan", "up": false, "l3_device": "eth0", "proto": "dhcp", "route": []},
          {"interface": "wwan", "up": true, "l3_device": "phy0-sta0", "proto": "dhcp",
           "ipv4-address": [{"address": "192.168.29.51", "mask": 24}],
           "route": [{"target": "0.0.0.0", "mask": 0}]}
        ]}
        ___wrt___ essid
        phy0-sta0 ESSID: "VivekWifi"
    """.trimIndent()

    /**
     * Byte counters belong to a device. When the upstream moves — a wired WAN dropping and a
     * Wi-Fi client taking over — differencing the new device's totals against the old one's
     * would report a fictitious multi-gigabit spike.
     */
    @Test
    fun `a change of upstream device does not spike the throughput chart`() {
        val t = telemetry()
        t.ingest(Parsers.sections(tickOutput(1000, 1000, rxBytes = 9_000_000_000L, txBytes = 9_000_000_000L)), 0L)
        assertEquals("eth0", t.wanDevice)

        t.ingest(Parsers.sections(movedUpstream(rxBytes = 1_000, txBytes = 500)), 1_000_000_000L)
        assertEquals("phy0-sta0", t.wanDevice)
        assertEquals("wwan", t.upstream?.name)
        assertEquals("VivekWifi", t.upstream?.ssid)
        // No delta is drawn across the switch; that tick only seeds the new device.
        assertEquals(0f, t.down.last(), 0.001f)
        assertEquals(0f, t.up.last(), 0.001f)

        // The tick after it measures the new interface normally.
        t.ingest(Parsers.sections(movedUpstream(rxBytes = 1_250_000, txBytes = 500)), 2_000_000_000L)
        assertEquals(10f, t.down.last(), 0.1f)   // 1.249 MB in 1 s ≈ 10 Mbps
    }

    /** Two live links: each is measured on its own counters, and only one drives the chart. */
    @Test
    fun `both upstreams get their own rate`() {
        val t = telemetry()
        fun tick(wanRx: Long, staRx: Long) = """
            ___wrt___ info
            {"uptime": 1, "load": [0,0,0], "memory": {"total": 100, "free": 50, "buffered": 0, "cached": 0}}
            ___wrt___ stat
            cpu  1000 0 0 1000 0 0 0 0
            ___wrt___ netdev
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes      packets errs drop fifo colls carrier compressed
            eth1: $wanRx 100 0 0 0 0 0 0 0 50 0 0 0 0 0 0
            phy0-sta0: $staRx 10 0 0 0 0 0 0 0 5 0 0 0 0 0 0
            ___wrt___ overlay
            /dev/loop0 104857600 35651584 69206016 34% /overlay
            ___wrt___ ifaces
            {"interface": [
              {"interface": "wan", "up": true, "l3_device": "eth1", "proto": "dhcp", "metric": 10,
               "ipv4-address": [{"address": "10.0.0.2", "mask": 24}],
               "route": [{"target": "0.0.0.0", "mask": 0}]},
              {"interface": "wwan_2", "up": true, "l3_device": "phy0-sta0", "proto": "dhcp", "metric": 30,
               "ipv4-address": [{"address": "192.168.1.126", "mask": 24}],
               "route": [{"target": "0.0.0.0", "mask": 0}]}
            ]}
            ___wrt___ essid
            phy0-sta0 ESSID: "Airtel"
        """.trimIndent()

        t.ingest(Parsers.sections(tick(0, 0)), 0L)
        assertEquals(2, t.upstreams.size)
        t.ingest(Parsers.sections(tick(1_250_000, 625_000)), 1_000_000_000L)

        assertEquals(10f, t.rates["eth1"]!!.first, 0.1f)
        assertEquals(5f, t.rates["phy0-sta0"]!!.first, 0.1f)
        // The chart follows the lowest-metric link, not the busiest or the last seen.
        assertEquals("eth1", t.wanDevice)
        assertEquals(10f, t.down.last(), 0.1f)
        assertTrue(t.deviceTotals["phy0-sta0"]!!.startsWith("since boot"))

        // Each uplink also keeps its OWN window. The WAN screen draws one interface at a
        // time, and drawing the shared `down` under every chip put the primary's spikes on
        // a standby WAN that was carrying nothing.
        assertEquals(10f, t.downFor("eth1").last(), 0.1f)
        assertEquals(5f, t.downFor("phy0-sta0").last(), 0.1f)
        assertEquals(Telemetry.WINDOW, t.downFor("phy0-sta0").size)
    }

    /** A device that never held a default route has nothing to draw — a flat line, never the primary's. */
    @Test
    fun `an unseen device draws flat, not the primary's history`() {
        val t = telemetry()
        t.ingest(Parsers.sections(tickOutput(cpuIdle = 1000, cpuTotalBusy = 1000, rxBytes = 0, txBytes = 0)), 0L)
        t.ingest(
            Parsers.sections(tickOutput(cpuIdle = 1100, cpuTotalBusy = 1100, rxBytes = 1_250_000, txBytes = 250_000)),
            1_000_000_000L,
        )
        assertEquals(10f, t.down.last(), 0.01f)                 // the primary really did move
        val flat = t.downFor("phy1-sta9")
        assertEquals(Telemetry.WINDOW, flat.size)
        assertTrue(flat.all { it == 0f })                        // ...but this one did not
        assertTrue(t.upFor("phy1-sta9").all { it == 0f })
    }
}

/**
 * The dashboard's cadence. The chip that read "433 ms" was the cost of this poll, not the
 * round trip — and on a router where the poll costs more than the interval, a flat delay
 * means the app polls back to back and adds to the load it is reporting.
 */
class TickPacingTest {

    @Test
    fun `a cheap tick keeps the base interval`() {
        assertEquals(1_000L, Telemetry.nextDelayMs(lastTickMs = 60, base = 1_000L))
        assertEquals(1_000L, Telemetry.nextDelayMs(lastTickMs = 0, base = 1_000L))
    }

    /** Measured on the reference repeater: 450-1300 ms per tick on a 1 s interval. */
    @Test
    fun `an expensive tick waits at least as long as it took`() {
        assertEquals(1_327L, Telemetry.nextDelayMs(lastTickMs = 1327, base = 1_000L))
        assertEquals(1_000L, Telemetry.nextDelayMs(lastTickMs = 520, base = 1_000L))
    }

    /** Period = tick + delay, so waiting the tick's own cost holds the duty cycle at <= 50%. */
    @Test
    fun `the duty cycle never exceeds half`() {
        listOf(60, 520, 900, 1327, 2500).forEach { tick ->
            val period = tick + Telemetry.nextDelayMs(tick, base = 1_000L)
            assertTrue("tick=$tick", tick.toDouble() / period <= 0.5)
        }
    }

    @Test
    fun `a pathologically slow router still updates within the cap`() {
        assertEquals(Telemetry.MAX_GAP_MS, Telemetry.nextDelayMs(lastTickMs = 30_000, base = 1_000L))
    }

    @Test
    fun `latency is sampled every few ticks rather than every one`() {
        assertTrue(Telemetry.PING_EVERY_TICKS > 1)
        val pinged = (0 until 12).count { it % Telemetry.PING_EVERY_TICKS == 0 }
        assertEquals(3, pinged)
    }
}
