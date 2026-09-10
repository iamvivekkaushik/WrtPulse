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
    fun `speedtest fetches the requested size and reports it back`() {
        val cmd = Commands.speedtestDownload(20_000_000)
        assertTrue(cmd.contains("https://speed.cloudflare.com/__down?bytes=20000000"))
        assertTrue(cmd.contains("-O /dev/null"))
        // curl reports its own timing, with the handshake separated out.
        assertTrue(cmd.contains("curl -s -o /dev/null -w '%{size_download} %{size_upload} %{time_total} %{time_pretransfer}'"))
        assertTrue(cmd.contains("uclient-fetch"))
        assertTrue(cmd.contains("wget"))            // fallback when uclient-fetch is absent
        assertTrue(cmd.contains("echo 20000000"))   // the fallback's only number
    }

    @Test
    fun `upload leg streams a scratch file and cleans it up`() {
        val prepare = Commands.speedtestPrepareUpload(5_000_000)
        assertTrue(prepare.contains("if=/dev/zero"))
        assertTrue(prepare.contains("count=4882"))          // 5 MB in 1 KiB blocks
        assertTrue(prepare.contains(Commands.SPEEDTEST_UPLOAD_FILE))

        val upload = Commands.speedtestUpload(5_000_000)
        assertTrue(upload.contains("https://speed.cloudflare.com/__up"))
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
        assertEquals(20_000_000L, down!!.first)
        assertEquals(9.547908, down.second, 1e-6)

        val up = RouterOps.parseTransfer("0 20480000 6.381377 0.9\n", wall = 8.0)
        assertEquals(20_480_000L, up!!.first)
        assertEquals(5.481377, up.second, 1e-6)
    }

    /** The fallback echoes only the byte count; the wall clock around the exec times it. */
    @Test
    fun `an echoed byte count is timed by the wall clock`() {
        assertEquals(20_000_000L to 5.5, RouterOps.parseTransfer("20000000", wall = 5.5))
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
