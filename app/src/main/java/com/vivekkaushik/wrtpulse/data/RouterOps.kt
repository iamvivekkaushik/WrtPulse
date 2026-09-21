package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers

/** Outcome of a WAN speed test. Upload can fail on its own without losing the download. */
data class SpeedResult(
    val downMbps: Float = 0f,
    val downBytes: Long = 0,
    val downSeconds: Double = 0.0,
    val upMbps: Float = 0f,
    val upBytes: Long = 0,
    val upSeconds: Double = 0.0,
    val error: String? = null,
    val uploadError: String? = null,
    /** How busy the router's CPU was during each leg, 0–100; null when it could not be sampled. */
    val downCpuPct: Int? = null,
    val upCpuPct: Int? = null,
) {
    val hasUpload: Boolean get() = upBytes > 0 && upMbps > 0f

    /**
     * The router, not the line, set the number. A core with no idle left during the transfer
     * could not have moved bytes any faster whatever the link offered.
     */
    val cpuLimited: Boolean get() = cpuPct >= CPU_LIMITED_PCT

    /** The busier of the two legs, for the dialog's note. */
    val cpuPct: Int get() = maxOf(downCpuPct ?: 0, upCpuPct ?: 0)

    companion object {
        const val CPU_LIMITED_PCT = 90
    }
}

/** One leg as the router reported it: bytes moved, seconds it took, how busy the CPU was. */
data class Transfer(val bytes: Long, val seconds: Double, val cpuPct: Int? = null)

/** Which leg is running, for the dialog's progress line. */
enum class SpeedPhase { Download, Upload }

/** One-shot router actions from the dashboard's quick-action row. */
class RouterOps(private val session: RouterSession, private val telemetry: Telemetry? = null) {

    suspend fun reboot(): String = try {
        session.exec(Commands.REBOOT, timeoutMs = 10_000)
        "Reboot sent — the router goes down in a moment"
    } catch (e: SshException) {
        // The link can die before the reply lands; the command still reached the router.
        if (e is SshException.Disconnected || e is SshException.Timeout) "Reboot sent"
        else "Failed: ${e.message}"
    }

    /**
     * Measures WAN throughput with one download and then one upload run on the router, each
     * up to 100 MB or [Commands.SPEEDTEST_SECONDS], whichever ends first, dividing what moved
     * by how long it took. It is the router's number as much as the line's: a single small
     * core pushing bytes through curl can be the ceiling, so each leg also samples how busy
     * the CPU was. The dashboard tick is held for the duration — it would otherwise share
     * that same core.
     */
    suspend fun speedtest(onPhase: (SpeedPhase) -> Unit = {}): SpeedResult {
        telemetry?.paused = true
        try {
            onPhase(SpeedPhase.Download)
            val down = timedTransfer(Commands.speedtestDownload())
                ?: return SpeedResult(error = "Download failed — is the router online?")

            onPhase(SpeedPhase.Upload)
            val up = try {
                session.exec(Commands.speedtestPrepareUpload(), timeoutMs = 60_000)
                    .takeIf { it.ok }
                    ?.let { timedTransfer(Commands.speedtestUpload()) }
            } catch (e: SshException) {
                null
            } finally {
                runCatching { session.exec(Commands.SPEEDTEST_CLEANUP, timeoutMs = 15_000) }
            }

            return SpeedResult(
                downMbps = Telemetry.mbps(down.bytes, down.seconds),
                downBytes = down.bytes,
                downSeconds = down.seconds,
                downCpuPct = down.cpuPct,
                upMbps = up?.let { Telemetry.mbps(it.bytes, it.seconds) } ?: 0f,
                upBytes = up?.bytes ?: 0,
                upSeconds = up?.seconds ?: 0.0,
                upCpuPct = up?.cpuPct,
                uploadError = if (up == null) "Upload failed — is the router online?" else null,
            )
        } finally {
            telemetry?.paused = false
        }
    }

    /**
     * Runs one leg and returns the bytes it confirmed plus how long they took to move.
     *
     * With curl the router reports its own numbers — bytes down, bytes up, total time, and
     * the time before the first byte moved — and the transfer time is the difference of the
     * last two, so DNS and connection setup are left out; a leg the clock cut short reports
     * what had arrived by then. Without curl the router runs the same clock itself and
     * reports bytes and seconds of its own, so the SSH round trip does not pad the time.
     */
    private suspend fun timedTransfer(command: String): Transfer? = try {
        val started = System.nanoTime()
        val result = session.exec(command, timeoutMs = 180_000)
        val wall = (System.nanoTime() - started) / 1e9
        val parsed = parseTransfer(result.stdout, wall)
        if (!result.ok || parsed == null || parsed.seconds <= 0.05) null else parsed
    } catch (e: SshException) {
        null
    }

    companion object {
        /**
         * A transfer command's output → what moved, how long it took, how busy the CPU was.
         *
         * The transfer line is the last one that is not a /proc/stat sample: four fields are
         * curl's `size_download size_upload time_total time_pretransfer`, whichever size is
         * non-zero being the leg that ran; two are the no-curl fallback's own count and clock,
         * the bytes moved and the seconds they took as read on the router; one is a bare byte
         * count, timed by [wall]. The `cpu` lines either side of it give the core's share of
         * that time. Anything else is a failed transfer.
         */
        fun parseTransfer(stdout: String, wall: Double): Transfer? {
            val lines = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val samples = lines.mapNotNull(Parsers::cpuSample)
            val cpuPct = if (samples.size >= 2) samples.last().percentSince(samples.first()) else null
            val fields = lines.lastOrNull { !it.startsWith("cpu ") }?.split(Regex("\\s+")).orEmpty()
            val (bytes, seconds) = when (fields.size) {
                1 -> (fields[0].toLongOrNull() ?: return null) to wall
                2 -> (fields[0].toLongOrNull() ?: return null) to (fields[1].toDoubleOrNull() ?: return null)
                4 -> {
                    val down = fields[0].toLongOrNull() ?: return null
                    val up = fields[1].toLongOrNull() ?: return null
                    val total = fields[2].toDoubleOrNull() ?: return null
                    val pre = fields[3].toDoubleOrNull() ?: return null
                    (if (up > 0) up else down) to ((total - pre).takeIf { it > 0 } ?: wall)
                }
                else -> return null
            }
            return if (bytes <= 0) null else Transfer(bytes, seconds, cpuPct)
        }
    }

}
