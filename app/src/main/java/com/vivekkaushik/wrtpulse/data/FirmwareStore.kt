package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.FirmwareStatus
import com.vivekkaushik.wrtpulse.ops.ImageCheck
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.UpgradeCheck
import java.io.File

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

    /**
     * Packages the user installed that the default image does not carry — what a plain
     * flash loses. Read alongside the upgrade check, because owut is what knows.
     */
    var userPackages by mutableStateOf<List<String>>(emptyList()); private set

    // ---- the reboot watch: screen 42 ----

    /** What was running when the flash was sent, for the before → after line. */
    var beforeBoard by mutableStateOf<BoardInfo?>(null); private set

    /** What answered when the router came back; null until it has. */
    var afterBoard by mutableStateOf<BoardInfo?>(null); private set

    /** One line per connection attempt, oldest first, the way screen 42 draws the log. */
    val watchLog = mutableStateListOf<String>()
    var watchElapsedS by mutableIntStateOf(0); private set

    /** How the watch ended: null while running, else one of the [WatchEnd] states. */
    var watchEnd by mutableStateOf<WatchEnd?>(null); private set

    /** Result of the post-flash reinstall, when one was run. */
    var reinstallOutput by mutableStateOf<String?>(null); private set

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
            // The names behind "N modified default packages": what a plain flash would lose.
            progress = "Listing your packages…"
            userPackages = runCatching {
                Parsers.userPackages(session.exec(Commands.USER_PACKAGES, timeoutMs = 120_000).stdout)
            }.getOrDefault(emptyList())
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
     * Pulls the config archive off the router and onto the phone — see [ConfigArchive] for
     * how. Here it is a gate: the flash is not offered until this has happened or been
     * waived out loud.
     */
    suspend fun backUp(directory: File): String {
        busy = true
        return try {
            when (val pulled = ConfigArchive.pull(session, directory, board?.release) { progress = it }) {
                is ConfigArchive.Pull.Done -> {
                    backupFile = pulled.file
                    backupWaived = false
                    "Backed up ${pulled.file.length() / 1024} kB to ${pulled.file.name}"
                }
                is ConfigArchive.Pull.Failed -> "Failed: ${pulled.why}"
            }
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

    /**
     * An image picked on the phone, pushed to the router over stdin the way a restore
     * archive is. Judged by size before it goes — /tmp is RAM — and by `sysupgrade -T`
     * after, which is the check that knows whether it belongs on this board.
     */
    suspend fun uploadLocalImage(name: String, bytes: ByteArray): String {
        if (!looksLikeImage(name)) {
            return "Failed: $name is not a sysupgrade image (.bin, .itb, .img or .img.gz)."
        }
        if (bytes.isEmpty()) return "Failed: the file is empty."
        tooSmallToHold(bytes.size.toLong())?.let { return "Failed: $it" }
        busy = true
        progress = "Sending ${bytes.size / 1024 / 1024} MB to the router's RAM…"
        return try {
            val got = session.execWithInput(Commands.LOCAL_IMAGE_RECEIVE, bytes, timeoutMs = 600_000)
            val size = Parsers.byteCount(got.stdout)
            if (!got.ok || size != bytes.size.toLong()) {
                runCatching { session.exec(Commands.discardImage(Commands.MANUAL_IMAGE), timeoutMs = 15_000) }
                return "Failed: the router received ${size ?: 0} of ${bytes.size} bytes."
            }
            progress = "Hashing…"
            val sha = Parsers.sha256(
                session.exec(Commands.imageSha256(Commands.MANUAL_IMAGE), timeoutMs = 120_000).stdout
            )
            if (sha != BackupStore.sha256(bytes)) {
                runCatching { session.exec(Commands.discardImage(Commands.MANUAL_IMAGE), timeoutMs = 15_000) }
                return "Failed: the router's hash of the file does not match the phone's."
            }
            image = ImageCheck(Commands.MANUAL_IMAGE, bytes.size.toLong(), sha)
            load()
            "Uploaded $name · ${bytes.size / 1024 / 1024} MB · hash verified"
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
        beforeBoard = board
        watchLog.clear()
        watchEnd = null
        afterBoard = null
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

    /**
     * Waits for the router to come back, then says what came back.
     *
     * Retries the connection until [BoardInfo] answers, logging each attempt with the
     * elapsed time, and gives up after [WATCH_LIMIT_S] without calling that a failure — the
     * router may still be flashing, and the one thing never to do then is power it off.
     * A changed or unknown host key ends the watch: that is the wiped-settings case, and the
     * router-list first-contact flow is where it continues.
     */
    suspend fun watchReboot() {
        if (watchEnd != null || !flashing) return
        val started = System.currentTimeMillis()
        // Nothing answers for the first while — sysupgrade is writing flash — so the first
        // attempt waits rather than burning a connect timeout on a router mid-write.
        delay(WATCH_FIRST_WAIT_MS)
        while (watchEnd == null) {
            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
            watchElapsedS = elapsed
            val host = session.target.host
            try {
                val out = session.exec(Commands.BOARD, timeoutMs = 8_000)
                val came = Parsers.board(out.stdout)
                afterBoard = came
                watchLog += watchLine(System.currentTimeMillis() / 1000, host, elapsed, answered = true)
                watchEnd = if (came.revision.isNotEmpty() && came.revision == beforeBoard?.revision) {
                    WatchEnd.Unchanged
                } else {
                    WatchEnd.Back
                }
                runCatching { load() }
                return
            } catch (e: SshException.HostKeyChanged) {
                watchLog += watchLine(System.currentTimeMillis() / 1000, host, elapsed, answered = true)
                watchEnd = WatchEnd.NewKey
                return
            } catch (e: SshException.UnknownHostKey) {
                watchLog += watchLine(System.currentTimeMillis() / 1000, host, elapsed, answered = true)
                watchEnd = WatchEnd.NewKey
                return
            } catch (e: SshException) {
                watchLog += watchLine(System.currentTimeMillis() / 1000, host, elapsed, answered = false)
                if (watchLog.size > WATCH_LOG_LINES) watchLog.removeAt(0)
            }
            if ((System.currentTimeMillis() - started) / 1000 >= WATCH_LIMIT_S) {
                watchEnd = WatchEnd.GaveUp
                return
            }
            delay(WATCH_INTERVAL_MS)
        }
    }

    /** Puts the user's packages back on the new image. Only offered once the router is back. */
    suspend fun reinstallPackages(): String {
        if (userPackages.isEmpty()) return "Nothing to reinstall."
        busy = true
        progress = "Reinstalling ${userPackages.size} package(s)…"
        return try {
            val out = session.exec(Commands.reinstall(userPackages), timeoutMs = 600_000)
            val text = (out.stdout.trim() + "\n" + out.stderr.trim()).trim()
            reinstallOutput = text.ifBlank { null }
            if (out.ok) "Reinstalled ${userPackages.joinToString(", ")}"
            else "Failed: the package manager exited ${out.exitCode}"
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
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

    companion object {

        /** The transfer lives in [ConfigArchive]; these stay so the gate reads as one thing. */
        fun decodeHex(text: String): ByteArray? = ConfigArchive.decodeHex(text)

        const val MAX_BACKUP_BYTES = ConfigArchive.MAX_BACKUP_BYTES

        const val FLASHING_MESSAGE =
            "Flashing. Do not power the router off — it will reboot on its own."

        /** How long to keep trying before saying "still no answer" — not "failed". */
        const val WATCH_LIMIT_S = 600
        const val WATCH_INTERVAL_MS = 10_000L
        const val WATCH_FIRST_WAIT_MS = 20_000L
        const val WATCH_LOG_LINES = 40

        /** "14:22:31 connecting to 192.168.0.1 — no answer · 23 s", the log line screen 42 draws. */
        fun watchLine(nowEpochS: Long, host: String, elapsedS: Int, answered: Boolean): String {
            val t = java.time.Instant.ofEpochSecond(nowEpochS).atZone(java.time.ZoneId.systemDefault())
            val clock = "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
            return if (answered) "$clock $host answered · $elapsedS s"
            else "$clock connecting to $host — no answer · $elapsedS s"
        }

        /** "0:43", the elapsed counter. */
        fun elapsedLabel(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

        /** The file types sysupgrade takes. Anything else is refused before it is even sent. */
        fun looksLikeImage(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".bin") || lower.endsWith(".itb") ||
                lower.endsWith(".img") || lower.endsWith(".img.gz")
        }

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

/** How the reboot watch finished. */
enum class WatchEnd {
    /** The router answered with a different revision — the upgrade landed. */
    Back,
    /** The router answered with the SAME revision. Said in red, not as success. */
    Unchanged,
    /** It answered with a key the app does not know: settings were wiped; continue from the router list. */
    NewKey,
    /** Nothing answered inside the limit. The router may still be flashing. */
    GaveUp,
}
