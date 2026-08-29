package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
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
        ___wrt___ wan
        {"ipv4-address": [{"address": "82.44.19.7", "mask": 24}], "l3_device": "eth0"}
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
}
