package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import java.io.File
import java.util.Base64

/**
 * The router's config archive, on its way to the phone.
 *
 * `sysupgrade -b` writes it to the router's RAM; it comes back base64 (or hex) over the exec
 * channel rather than over SFTP — a config backup is tens of kilobytes, and a backup that only
 * exists on the device about to be reflashed is not a backup at all. Shared by the firmware
 * gate and the backup screen, so there is exactly one way an archive is pulled and named.
 */
object ConfigArchive {

    sealed interface Pull {
        data class Done(val file: File) : Pull
        data class Failed(val why: String) : Pull
    }

    /** The exec channel buffers the whole reply, so an unreasonable archive is refused. */
    const val MAX_BACKUP_BYTES = 2L * 1024 * 1024

    private val NAME = Regex("^wrtpulse-(.+)-(\\d{9,11})\\.tar\\.gz$")

    suspend fun pull(session: RouterSession, directory: File, onProgress: (String) -> Unit = {}): Pull = try {
        onProgress("Writing the config archive…")
        val made = session.exec(Commands.BACKUP_CREATE, timeoutMs = 120_000)
        val size = Parsers.byteCount(made.stdout)
        when {
            !made.ok || size == null || size <= 0 ->
                Pull.Failed("sysupgrade -b produced nothing.")
            size > MAX_BACKUP_BYTES ->
                Pull.Failed(
                    "the archive is ${size / 1024} kB — too large to read over the command " +
                        "channel. Copy it off with scp instead."
                )
            else -> {
                onProgress("Reading it back…")
                val encoded = session.exec(Commands.BACKUP_READ, timeoutMs = 180_000)
                val bytes = decodeArchive(encoded.stdout)
                if (encoded.stdout.trim() == "none" || encoded.stdout.isBlank()) {
                    Pull.Failed(
                        "this router has no base64, hexdump or od, so the archive cannot be " +
                            "encoded for transfer. Copy it off with scp instead."
                    )
                } else if (bytes == null || bytes.size.toLong() != size) {
                    Pull.Failed(
                        "the archive did not arrive intact — the router made $size bytes and " +
                            "${bytes?.size ?: 0} came back."
                    )
                } else {
                    directory.mkdirs()
                    val file = File(directory, fileName(session.target.host))
                    file.writeBytes(bytes)
                    runCatching { session.exec(Commands.BACKUP_CLEANUP, timeoutMs = 15_000) }
                    Pull.Done(file)
                }
            }
        }
    } catch (e: SshException) {
        Pull.Failed(e.message ?: "connection lost")
    }

    /** The host as a filename can carry it. */
    fun safeHost(host: String): String = host.replace(Regex("[^A-Za-z0-9.-]"), "_")

    /** `wrtpulse-<host>-<epoch>.tar.gz`. */
    fun fileName(host: String, epochSeconds: Long = System.currentTimeMillis() / 1000): String =
        "wrtpulse-${safeHost(host)}-$epochSeconds.tar.gz"

    /** The host and time out of a name [fileName] made; null for any other file. */
    fun parseName(name: String): Pair<String, Long>? {
        val m = NAME.matchEntire(name) ?: return null
        val epoch = m.groupValues[2].toLongOrNull() ?: return null
        return m.groupValues[1] to epoch
    }

    /**
     * The archive as [Commands.BACKUP_READ] sent it. Its first line names the encoding,
     * because which one the router could offer is not knowable in advance.
     */
    fun decodeArchive(text: String): ByteArray? {
        val body = text.trim()
        val marker = body.lineSequence().firstOrNull()?.trim()
        val payload = body.substringAfter('\n', "")
        return when (marker) {
            // base64 arrives wrapped at 76 columns, so the MIME decoder — the strict one
            // rejects the newlines outright.
            "b64" -> runCatching { Base64.getMimeDecoder().decode(payload.trim()) }.getOrNull()
            "hex" -> decodeHex(payload)
            else -> null
        }
    }

    /**
     * `od -An -tx1` output, once the spaces and newlines are gone. Rejects anything that is
     * not a whole number of hex bytes rather than returning a half-decoded archive — a
     * corrupt backup that looks like a backup is worse than none.
     */
    fun decodeHex(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it.isDigit() || it in "abcdefABCDEF" }) return null
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }
}
