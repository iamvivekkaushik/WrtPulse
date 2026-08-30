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
    fun `speedtest fetches the requested size and echoes it back`() {
        val cmd = Commands.speedtestDownload(20_000_000)
        assertTrue(cmd.contains("https://speed.cloudflare.com/__down?bytes=20000000"))
        assertTrue(cmd.contains("-O /dev/null"))
        assertTrue(cmd.contains("uclient-fetch"))
        assertTrue(cmd.contains("wget"))            // fallback when uclient-fetch is absent
        assertTrue(cmd.trimEnd().endsWith("echo 20000000"))
    }

    @Test
    fun `upload leg posts a scratch file and cleans it up`() {
        val prepare = Commands.speedtestPrepareUpload(5_000_000)
        assertTrue(prepare.contains("if=/dev/zero"))
        assertTrue(prepare.contains("count=4882"))          // 5 MB in 1 KiB blocks
        assertTrue(prepare.contains(Commands.SPEEDTEST_UPLOAD_FILE))

        val upload = Commands.speedtestUpload(5_000_000)
        assertTrue(upload.contains("https://speed.cloudflare.com/__up"))
        // curl is preferred: uclient-fetch stalls partway through a large body and the far
        // end resets the connection, which was verified against the user's OpenWrt 25.12.5.
        assertTrue(upload.indexOf("curl") < upload.indexOf("uclient-fetch"))
        assertTrue(upload.contains("--data-binary @${Commands.SPEEDTEST_UPLOAD_FILE}"))
        assertTrue(upload.contains("--post-file=${Commands.SPEEDTEST_UPLOAD_FILE}"))
        assertTrue(upload.trimEnd().endsWith("echo 5000000"))

        assertEquals("rm -f ${Commands.SPEEDTEST_UPLOAD_FILE}", Commands.SPEEDTEST_CLEANUP)
        assertTrue(Commands.SPEEDTEST_UPLOAD_FILE.startsWith("/tmp/")) // RAM, not flash
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
