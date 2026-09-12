package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.ops.Commands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterOpsTest {

    /**
     * The reboot must be backgrounded: running it in the foreground kills the channel before
     * the reply arrives, leaving the app unable to tell success from a dropped connection.
     */
    @Test
    fun `reboot is scheduled so the command can answer first`() {
        assertTrue(Commands.REBOOT.contains("sleep 1; reboot"))
        assertTrue(Commands.REBOOT.trimEnd().endsWith("echo scheduled"))
        assertTrue(Commands.REBOOT.contains("&"))
    }

    @Test
    fun `speedtest fetches up to 100 MB or 10 seconds, whichever ends first`() {
        val cmd = Commands.speedtestDownload()
        // Plain http: the payload is discarded, and TLS on a small MIPS core was the ceiling
        // (65 Mbps pegged vs 232 over http on the same router, same endpoint).
        assertTrue(cmd.contains("--max-time 10 "))
        assertTrue(cmd.contains("'http://speed.cloudflare.com/__down?bytes=99999999'"))  // 100 MB is a 403
        assertTrue(!cmd.contains("https://"))
        // Running out of clock is the expected end of a leg, not a failure.
        assertTrue(cmd.contains("rc=\$?; [ \$rc -eq 28 ] && rc=0"))
        // Without curl there is no clock or partial count, so the fallback is a smaller fixed fetch.
        assertTrue(cmd.contains("__down?bytes=20000000'; { uclient-fetch"))
        assertTrue(cmd.contains("-O /dev/null"))
        // curl reports its own timing, with the handshake separated out, on a line of its own
        // so the CPU sample that follows does not land on the same line.
        assertTrue(cmd.contains("curl -s -o /dev/null --max-time 10 -w '%{size_download} %{size_upload} %{time_total} %{time_pretransfer}\\n'"))
        // /proc/stat either side of the transfer, so a pegged core can be reported as such.
        assertEquals(2, Regex("grep '\\^cpu ' /proc/stat").findAll(cmd).count())
        assertTrue(cmd.trimEnd().endsWith("exit \$rc"))
        assertTrue(cmd.contains("uclient-fetch"))
        assertTrue(cmd.contains("wget"))            // fallback when uclient-fetch is absent
        assertTrue(cmd.contains("echo 20000000"))   // the fallback's only number
        assertEquals(10, Commands.SPEEDTEST_SECONDS)
    }

    @Test
    fun `upload leg streams a scratch file and cleans it up`() {
        val prepare = Commands.speedtestPrepareUpload(5_000_000)
        // Sparse: a 100 MB file cannot be written into a 128 MB router's tmpfs, but a hole of
        // that size reads as zeros for free and curl still gets a regular file with a length.
        assertTrue(prepare.contains("of=${Commands.SPEEDTEST_UPLOAD_FILE} bs=1 count=0 seek=5000000"))
        assertEquals(100_000_000L, Commands.SPEEDTEST_UP_BYTES)

        val upload = Commands.speedtestUpload(5_000_000)
        assertTrue(upload.contains("--max-time 10 "))
        assertTrue(upload.contains("rc=\$?; [ \$rc -eq 28 ] && rc=0"))
        assertTrue(upload.contains("http://speed.cloudflare.com/__up"))
        assertTrue(!upload.contains("https://"))
        assertEquals(2, Regex("grep '\\^cpu ' /proc/stat").findAll(upload).count())
        // curl is preferred: uclient-fetch stalls partway through a large body and the far
        // end resets the connection, which was verified against the user's OpenWrt 25.12.5.
        assertTrue(upload.indexOf("curl") < upload.indexOf("uclient-fetch"))
        // Streamed, not buffered: --data-binary @file read 20 MB into RAM and got curl OOM-killed
        // on a 128 MB router, which the app then misreported as curl being absent.
        assertTrue(upload.contains("-T ${Commands.SPEEDTEST_UPLOAD_FILE} -X POST"))
        assertTrue(!upload.contains("--data-binary"))
        assertTrue(upload.contains("--post-file=${Commands.SPEEDTEST_UPLOAD_FILE}"))
        assertTrue(upload.contains("echo 5000000"))

        assertEquals("rm -f ${Commands.SPEEDTEST_UPLOAD_FILE}", Commands.SPEEDTEST_CLEANUP)
        assertTrue(Commands.SPEEDTEST_UPLOAD_FILE.startsWith("/tmp/")) // RAM, not flash
    }

    /** curl's four numbers: the transfer time is total minus pre-transfer, handshake excluded. */
    @Test
    fun `curl's own timing is used and the handshake is left out`() {
        val down = RouterOps.parseTransfer("20000000 0 11.863704 2.315796\n", wall = 14.0)
        assertEquals(20_000_000L, down!!.bytes)
        assertEquals(9.547908, down.seconds, 1e-6)
        assertEquals(null, down.cpuPct)

        val up = RouterOps.parseTransfer("0 20480000 6.381377 0.9\n", wall = 8.0)
        assertEquals(20_480_000L, up!!.bytes)
        assertEquals(5.481377, up.seconds, 1e-6)
    }

    /** As the router prints it: a /proc/stat line, curl's line, another /proc/stat line. */
    @Test
    fun `cpu samples around the transfer give its busy share`() {
        // 300 jiffies elapsed, 3 idle: the core was 99% busy — a MIPS 74Kc doing curl's work.
        val pegged = RouterOps.parseTransfer(
            """
            cpu  227061 0 144470 7054062 0 0 253178 0 0 0
            20000000 0 2.47 0.05
            cpu  227240 0 144506 7054065 0 0 253297 0 0 0
            """.trimIndent(),
            wall = 3.0,
        )
        assertEquals(20_000_000L, pegged!!.bytes)
        assertEquals(2.42, pegged.seconds, 1e-6)
        assertEquals(99, pegged.cpuPct)
        assertTrue(SpeedResult(downCpuPct = pegged.cpuPct).cpuLimited)

        // Mostly idle: the line, not the router, set the number.
        val idle = RouterOps.parseTransfer(
            "cpu  100 0 100 1000 0 0 0 0 0 0\n20000000 0 2.0 0.1\ncpu  110 0 110 1280 0 0 0 0 0 0\n",
            wall = 3.0,
        )
        assertEquals(6, idle!!.cpuPct)
        assertTrue(!SpeedResult(downCpuPct = idle.cpuPct, upCpuPct = 40).cpuLimited)
        assertTrue(!SpeedResult().cpuLimited)
    }

    /** The fallback echoes only the byte count; the wall clock around the exec times it. */
    @Test
    fun `an echoed byte count is timed by the wall clock`() {
        assertEquals(Transfer(20_000_000L, 5.5), RouterOps.parseTransfer("20000000", wall = 5.5))
        // Sampled, but nothing moved: still not a measurement.
        assertEquals(null, RouterOps.parseTransfer("cpu  1 0 1 10 0 0 0 0\ncpu  2 0 2 20 0 0 0 0", wall = 5.5))
        assertEquals(null, RouterOps.parseTransfer("", wall = 5.5))
        assertEquals(null, RouterOps.parseTransfer("Killed", wall = 5.5))
        // Nothing moved: a 200 with no body is not a measurement.
        assertEquals(null, RouterOps.parseTransfer("0 0 1.2 0.3", wall = 5.5))
    }

    @Test
    fun `a result without an upload still reports the download`() {
        val onlyDown = SpeedResult(downMbps = 40.5f, downBytes = 20_000_000, downSeconds = 3.9)
        assertTrue(!onlyDown.hasUpload)
        assertEquals(40.5f, onlyDown.downMbps, 0.01f)

        val both = SpeedResult(downMbps = 40.5f, upMbps = 12.2f, upBytes = 5_000_000, upSeconds = 3.3)
        assertTrue(both.hasUpload)
    }

    @Test
    fun `throughput maths matches the reported byte count`() {
        // 20 MB in 2 s is 80 Mbps.
        assertEquals(80f, Telemetry.mbps(20_000_000, 2.0), 0.01f)
        assertEquals(0f, Telemetry.mbps(20_000_000, 0.0), 0.01f)
    }
}
