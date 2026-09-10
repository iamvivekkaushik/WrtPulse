package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands

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
) {
    val hasUpload: Boolean get() = upBytes > 0 && upMbps > 0f
}

/** Which leg is running, for the dialog's progress line. */
enum class SpeedPhase { Download, Upload }

/** One-shot router actions from the dashboard's quick-action row. */
class RouterOps(private val session: RouterSession) {

    suspend fun reboot(): String = try {
        session.exec(Commands.REBOOT, timeoutMs = 10_000)
        "Reboot sent — the router goes down in a moment"
    } catch (e: SshException) {
        // The link can die before the reply lands; the command still reached the router.
        if (e is SshException.Disconnected || e is SshException.Timeout) "Reboot sent"
        else "Failed: ${e.message}"
    }

    /**
     * Measures WAN throughput by timing a fixed-size download and then an upload of a
     * scratch file. The SSH round trip and connection setup sit inside each measurement, so
     * both read slightly low — fine for "is the line healthy", not a lab instrument.
     */
    suspend fun speedtest(
        downBytes: Long = DEFAULT_DOWN_BYTES,
        upBytes: Long = DEFAULT_UP_BYTES,
        onPhase: (SpeedPhase) -> Unit = {},
    ): SpeedResult {
        onPhase(SpeedPhase.Download)
        val down = timedTransfer(Commands.speedtestDownload(downBytes))
            ?: return SpeedResult(error = "Download failed — is the router online?")

        onPhase(SpeedPhase.Upload)
        val up = try {
            session.exec(Commands.speedtestPrepareUpload(upBytes), timeoutMs = 60_000)
                .takeIf { it.ok }
                ?.let { timedTransfer(Commands.speedtestUpload(upBytes)) }
        } catch (e: SshException) {
            null
        } finally {
            runCatching { session.exec(Commands.SPEEDTEST_CLEANUP, timeoutMs = 15_000) }
        }

        return SpeedResult(
            downMbps = Telemetry.mbps(down.first, down.second),
            downBytes = down.first,
            downSeconds = down.second,
            upMbps = up?.let { Telemetry.mbps(it.first, it.second) } ?: 0f,
            upBytes = up?.first ?: 0,
            upSeconds = up?.second ?: 0.0,
            uploadError = if (up == null) {
                "Upload needs curl on the router — uclient-fetch stalls on large uploads"
            } else null,
        )
    }

    /**
     * Runs one transfer command and returns the bytes it confirmed plus how long it took.
     *
     * With curl the router reports its own numbers — bytes down, bytes up, total time, and the
     * time before the first byte moved — and the transfer time is the difference of the last
     * two, so DNS, TCP and the TLS handshake are left out. Without curl the only number is the
     * byte count, and the wall clock around the SSH exec has to do, handshake and all.
     */
    private suspend fun timedTransfer(command: String): Pair<Long, Double>? = try {
        val started = System.nanoTime()
        val result = session.exec(command, timeoutMs = 180_000)
        val wall = (System.nanoTime() - started) / 1e9
        val parsed = parseTransfer(result.stdout, wall)
        if (!result.ok || parsed == null || parsed.second <= 0.05) null else parsed
    } catch (e: SshException) {
        null
    }

    companion object {
        const val DEFAULT_DOWN_BYTES = 20_000_000L
        // Same size as the download: 5 MB was over in well under a second on a fast line, so
        // the SSH round trip and TLS setup inside the timing dominated and the number read
        // far too low. The payload lives in the router's RAM, but 20 MB fits on anything
        // that can run the app's other features.
        const val DEFAULT_UP_BYTES = 20_000_000L

        /**
         * The last line of a transfer command → (bytes, seconds).
         *
         * Four fields are curl's `size_download size_upload time_total time_pretransfer`;
         * whichever size is non-zero is the leg that ran. One field is the byte count echoed by
         * the fallback, timed by [wall]. Anything else is a failed transfer.
         */
        fun parseTransfer(stdout: String, wall: Double): Pair<Long, Double>? {
            val fields = stdout.trim().lines().lastOrNull()?.trim()?.split(Regex("\\s+")).orEmpty()
            return when (fields.size) {
                1 -> fields[0].toLongOrNull()?.let { it to wall }
                4 -> {
                    val down = fields[0].toLongOrNull() ?: return null
                    val up = fields[1].toLongOrNull() ?: return null
                    val total = fields[2].toDoubleOrNull() ?: return null
                    val pre = fields[3].toDoubleOrNull() ?: return null
                    val bytes = if (up > 0) up else down
                    val seconds = (total - pre).takeIf { it > 0 } ?: wall
                    if (bytes <= 0) null else bytes to seconds
                }
                else -> null
            }
        }
    }

}
