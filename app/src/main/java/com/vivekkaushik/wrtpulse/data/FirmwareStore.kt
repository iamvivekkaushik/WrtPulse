package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.FirmwareStatus
import com.vivekkaushik.wrtpulse.ops.ImageCheck
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.UpgradeCheck
import java.io.File
import java.util.Base64

/**
 * The firmware upgrade path, as a set of gates rather than a wizard.
 *
 * Everything up to and including `sysupgrade -T` is a read: the app asks the upgrade server
 * what it would build, pulls the config archive off the router, fetches the image and lets
 * sysupgrade itself judge whether the file belongs on this device. Only [flash] writes, and
 * [flashBlock] is what stands in front of it — if any gate is unmet there is no button.
 */
class FirmwareStore(private val session: RouterSession) {

    var status by mutableStateOf(FirmwareStatus()); private set
    var board by mutableStateOf<BoardInfo?>(null); private set
    var check by mutableStateOf<UpgradeCheck?>(null); private set

    /** owut's own reasoning, fetched only when its verdict is not the safe one. */
    var checkDetail by mutableStateOf<String?>(null); private set

    var image by mutableStateOf<ImageCheck?>(null); private set

    /** Where the config archive landed on the phone, once it is off the router. */
    var backupFile by mutableStateOf<File?>(null); private set
    var backupWaived by mutableStateOf(false); private set

    /** Keeping settings is the default because wiping them also moves the router. */
    var keepSettings by mutableStateOf(true)

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** A line describing whatever long operation is running, for the screen to show. */
    var progress by mutableStateOf<String?>(null); private set

    /** Set once sysupgrade has been launched; the session is expected to die after this. */
    var flashing by mutableStateOf(false); private set

    val backupDone: Boolean get() = backupFile != null

    suspend fun load() {
        loading = true
        try {
            val out = session.exec(Commands.FIRMWARE, timeoutMs = 30_000)
            val sections = Parsers.sections(out.stdout)
            board = Parsers.board(sections["board"].orEmpty())
            status = Parsers.firmwareStatus(sections)
            // An image staged by an earlier visit is still an image; adopt it, but it has to
            // pass the dry run again before anything can be done with it.
            val staged = status.images.lastOrNull()
            if (image == null && staged != null) {
                image = ImageCheck(path = staged.first, sizeBytes = staged.second)
            }
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read firmware state: ${e.message}"
        } finally {
            loading = false
        }
    }

    /**
     * Asks the upgrade server what it would build. When its verdict is not the safe one the
     * verbose run follows, because "not safe" without the reason is not actionable.
     */
    suspend fun runCheck(): String {
        if (status.tool != "owut") return "Failed: this router has no owut."
        busy = true
        progress = "Asking the upgrade server…"
        return try {
            val out = session.exec(Commands.UPGRADE_CHECK, timeoutMs = 180_000)
            val parsed = Parsers.upgradeCheck(out.stdout + "\n" + out.stderr)
            check = parsed
            checkDetail = if (!parsed.safe) {
                progress = "Asking why…"
                runCatching {
                    session.exec(Commands.UPGRADE_CHECK_VERBOSE, timeoutMs = 180_000).stdout.trim()
                }.getOrNull()
            } else null
            error = null
            when {
                parsed.fields.isEmpty() -> "Failed: owut said nothing this app could read."
                !parsed.safe -> "owut will not call this upgrade safe"
                parsed.hasWork -> "Upgrade available"
                else -> "Already current"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    /**
     * Pulls the config archive off the router and onto the phone. It goes out base64 over the
     * exec channel rather than over SFTP — a config backup is tens of kilobytes, and a
     * backup that only exists on the device about to be reflashed is not a backup at all.
     */
    suspend fun backUp(directory: File): String {
        busy = true
        progress = "Writing the config archive…"
        return try {
            val made = session.exec(Commands.BACKUP_CREATE, timeoutMs = 120_000)
            val size = Parsers.byteCount(made.stdout)
            when {
                !made.ok || size == null || size <= 0 ->
                    "Failed: sysupgrade -b produced nothing."
                size > MAX_BACKUP_BYTES ->
                    "Failed: the archive is ${size / 1024} kB — too large to read over the " +
                        "command channel. Copy it off with scp instead."
                else -> {
                    progress = "Reading it back…"
                    val encoded = session.exec(Commands.BACKUP_READ, timeoutMs = 180_000)
                    val bytes = decodeArchive(encoded.stdout)
                    if (encoded.stdout.trim() == "none" || encoded.stdout.isBlank()) {
                        "Failed: this router has no base64, hexdump or od, so the archive " +
                            "cannot be encoded for transfer. Copy it off with scp instead."
                    } else if (bytes == null || bytes.size.toLong() != size) {
                        "Failed: the archive did not arrive intact — the router made " +
                            "$size bytes and ${bytes?.size ?: 0} came back."
                    } else {
                        directory.mkdirs()
                        val file = File(directory, backupName())
                        file.writeBytes(bytes)
                        backupFile = file
                        backupWaived = false
                        runCatching { session.exec(Commands.BACKUP_CLEANUP, timeoutMs = 15_000) }
                        "Backed up ${bytes.size / 1024} kB to ${file.name}"
                    }
                }
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    fun waiveBackup() {
        backupWaived = true
    }

    /**
     * Has the server build the image, then download and verify it — owut does all three
     * under `download`. What lands in /tmp is then re-read from the router rather than
     * guessed at from owut's output.
     */
    suspend fun downloadWithTool(): String {
        if (status.tool != "owut") return "Failed: this router has no owut."
        // No point building an image owut has already said it would not stand behind.
        if (check?.safe == false) {
            return "Failed: owut does not consider this upgrade safe."
        }
        busy = true
        progress = "Building on the server, then downloading…"
        return try {
            val out = session.exec(Commands.UPGRADE_DOWNLOAD, timeoutMs = 900_000)
            load()
            // owut says where it put the file; trust that over the directory listing, and
            // fall back to whatever is staged only when it didn't say.
            val text = out.stdout.trim() + "\n" + out.stderr.trim()
            val stated = Parsers.savedImagePath(text)?.takeIf { Commands.safeImagePath(it) }
            val staged = stated?.let { path ->
                path to (status.images.firstOrNull { it.first == path }?.second ?: 0L)
            } ?: status.images.lastOrNull()
            if (staged == null) {
                val why = text.lines().lastOrNull { it.isNotBlank() } ?: "no image appeared in /tmp"
                "Failed: $why"
            } else {
                image = ImageCheck(path = staged.first, sizeBytes = staged.second.takeIf { it > 0 })
                val size = staged.second.takeIf { it > 0 }?.let { " · ${it / 1024 / 1024} MB" } ?: ""
                "Downloaded ${staged.first.substringAfterLast('/')}$size"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    /** The path for a router with no upgrade tool: an image the user points at by URL. */
    suspend fun downloadFromUrl(url: String, expectedSha: String?): String {
        if (!Commands.safeImageUrl(url)) return "Failed: that does not look like a URL."
        if (expectedSha != null && !Commands.safeSha256(expectedSha)) {
            return "Failed: a sha256 is 64 hex characters."
        }
        // Ask what it weighs before filling /tmp with a partial copy. A router without curl
        // cannot answer, and an unknown size is not treated as a reason to refuse.
        val advertised = runCatching {
            Parsers.byteCount(session.exec(Commands.urlContentLength(url), timeoutMs = 60_000).stdout)
        }.getOrNull()
        tooSmallToHold(advertised)?.let { return "Failed: $it" }
        busy = true
        progress = "Downloading to the router's RAM…"
        return try {
            val out = session.exec(Commands.downloadImage(url), timeoutMs = 900_000)
            val size = Parsers.byteCount(out.stdout)
            if (!out.ok || size == null || size <= 0) {
                "Failed: nothing was downloaded."
            } else {
                progress = "Hashing…"
                val sha = Parsers.sha256(
                    session.exec(Commands.imageSha256(Commands.MANUAL_IMAGE), timeoutMs = 120_000).stdout
                )
                if (expectedSha != null && !expectedSha.equals(sha, ignoreCase = true)) {
                    image = null
                    "Failed: sha256 does not match. Downloaded $sha"
                } else {
                    image = ImageCheck(Commands.MANUAL_IMAGE, size, sha)
                    load()
                    "Downloaded ${size / 1024 / 1024} MB"
                }
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    /** Removes the downloaded image from the router's RAM. */
    suspend fun discardImage(): String {
        val target = image ?: return "Nothing to discard."
        if (!Commands.safeImagePath(target.path)) return "Failed: unsupported image path."
        busy = true
        return try {
            session.exec(Commands.discardImage(target.path), timeoutMs = 30_000)
            image = null
            load()
            "Discarded ${target.path.substringAfterLast('/')}"
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    /**
     * sysupgrade's own verdict on the file. Run even when owut has already verified the
     * download, because this is the check that reads the image's metadata and refuses one
     * built for a different device.
     */
    suspend fun dryRun(): String {
        val target = image ?: return "Failed: nothing downloaded yet."
        if (!Commands.safeImagePath(target.path)) return "Failed: unsupported image path."
        busy = true
        progress = "Asking sysupgrade to check the image…"
        return try {
            val out = session.exec(Commands.imageTest(target.path), timeoutMs = 120_000)
            val text = (out.stdout.trim() + "\n" + out.stderr.trim()).trim()
            image = target.copy(testPassed = out.ok, testOutput = text)
            if (out.ok) "Image accepted for this device"
            else "Failed: sysupgrade refused the image"
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    /**
     * The only write here. Detached on the router like the reboot command, because
     * sysupgrade takes the connection down with it — losing the link is the expected
     * outcome, not a failure to report.
     */
    suspend fun flash(): String {
        val target = image ?: return "Failed: nothing downloaded yet."
        flashBlock(backupDone, backupWaived, image, check?.safe)?.let { return "Failed: $it" }
        busy = true
        return try {
            session.exec(Commands.flash(target.path, keepSettings), timeoutMs = 20_000)
            flashing = true
            FLASHING_MESSAGE
        } catch (e: SshException) {
            if (e is SshException.Disconnected || e is SshException.Timeout) {
                flashing = true
                FLASHING_MESSAGE
            } else {
                "Failed: ${e.message}"
            }
        } finally {
            busy = false
        }
    }

    /** Non-null when the image cannot fit in the router's RAM, which is where it must go. */
    private fun tooSmallToHold(sizeBytes: Long? = null): String? {
        val free = status.tmpFreeKb ?: return null
        val needKb = (sizeBytes ?: return null) / 1024
        return if (needKb > free) {
            "the image needs ${needKb / 1024} MB but /tmp has ${free / 1024} MB free. " +
                "/tmp is RAM, and a truncated image is how routers get bricked."
        } else null
    }

    private fun backupName(): String {
        val host = session.target.host.replace(Regex("[^A-Za-z0-9.-]"), "_")
        return "wrtpulse-$host-${System.currentTimeMillis() / 1000}.tar.gz"
    }

    /**
     * The archive as [Commands.BACKUP_READ] sent it. Its first line names the encoding,
     * because which one the router could offer is not knowable in advance.
     */
    private fun decodeArchive(text: String): ByteArray? {
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

    companion object {

        /**
         * `od -An -tx1` output, once the spaces and newlines are gone. Rejects anything that
         * is not a whole number of hex bytes rather than returning a half-decoded archive —
         * a corrupt backup that looks like a backup is worse than none.
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

        /** The exec channel buffers the whole reply, so an unreasonable archive is refused. */
        const val MAX_BACKUP_BYTES = 2L * 1024 * 1024

        const val FLASHING_MESSAGE =
            "Flashing. Do not power the router off — it will reboot on its own."

        /**
         * The first gate that is not met, or null when the flash may be offered.
         *
         * Ordered the way the user works through them so the screen always names the next
         * thing to do rather than the last thing missing.
         */
        fun flashBlock(
            backupDone: Boolean,
            backupWaived: Boolean,
            image: ImageCheck?,
            checkSafe: Boolean?,
        ): String? = when {
            !backupDone && !backupWaived ->
                "Back up the configuration first, or say explicitly that you don't want one."
            image == null ->
                "No image has been downloaded yet."
            checkSafe == false ->
                "owut does not consider this upgrade safe, and this app takes it at its word."
            image.testPassed == null ->
                "Run the sysupgrade check on the image first."
            image.testPassed == false ->
                "sysupgrade refused this image for this device. Flashing it anyway is how " +
                    "a router stops turning on."
            else -> null
        }

        /**
         * What the user has to read before the last button. None of these block: they are
         * consequences of a choice that is legitimately theirs to make.
         */
        fun flashWarnings(keepSettings: Boolean, check: UpgradeCheck?, lanAddress: String?): List<String> =
            buildList {
                if (!keepSettings) {
                    add(
                        "Discarding settings resets the LAN address to 192.168.1.1" +
                            (lanAddress?.let { ", so the saved entry for $it stops working" } ?: "") +
                            " — you will have to add this router again at its new address."
                    )
                    add(
                        "It also regenerates the router's SSH host key. The app will report a " +
                            "changed key on the next connection; after a wipe that is expected, " +
                            "not a sign of interception."
                    )
                } else {
                    add(
                        "Settings are carried across. Across a major version that can bring " +
                            "forward configuration the new release no longer understands."
                    )
                }
                if (check?.sameVersion == true) {
                    add(
                        "This rebuilds the same version with current packages rather than " +
                            "moving to a new release."
                    )
                }
                (check?.missingPackages ?: 0).takeIf { it > 0 }?.let {
                    add("$it package(s) the server expects are missing from this router.")
                }
                add(
                    "If you reach this router over its own Wi-Fi, the link drops while it " +
                        "flashes and you will not see it finish."
                )
            }
    }
}
