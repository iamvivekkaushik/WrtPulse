package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.TarArchive
import com.vivekkaushik.wrtpulse.ops.TarEntry
import java.io.File
import java.security.MessageDigest

/** A config archive kept on the phone, in the app's private storage. */
data class LocalBackup(val file: File, val host: String, val createdEpoch: Long, val bytes: Long) {
    val name: String get() = file.name
}

/**
 * An archive the user wants to put back. Judged on the phone before the router sees it, and
 * by the router before anything is unpacked. Immutable: every step returns a new one.
 */
data class RestoreCandidate(
    /** Where it came from — a picked file's name, or a local backup's. */
    val source: String,
    val bytes: ByteArray,
    val sha256: String,
    val entries: List<TarEntry>,
    /** `option hostname` inside etc/config/system, when the archive has one. */
    val hostname: String?,
    /** The LAN address inside etc/config/network, when the archive has one. */
    val lanAddress: String?,
    /** Set once the router has the bytes and its hash matches [sha256]. */
    val onRouter: Boolean = false,
    /** What the router's own tar found in the file; null before it looked. */
    val routerListing: List<String>? = null,
    /** tar's complaint, when it could not read the file. */
    val routerRefusal: String? = null,
) {
    val fileCount: Int get() = entries.count { !it.isDirectory }

    /** dropbear's host key is part of a backup; restoring one from elsewhere changes it. */
    val carriesHostKey: Boolean
        get() = entries.any { it.name.startsWith("etc/dropbear/") && it.name.contains("host_key") }
}

/** [BackupStore.judge]'s answer: the opened archive, or the reason it must not be restored. */
sealed interface Judgement {
    data class Ok(val tar: TarArchive) : Judgement
    data class Refused(val why: String) : Judgement
}

/**
 * Backup & restore, as a set of reads with one write at the end.
 *
 * A backup is `sysupgrade -b` pulled onto the phone (see [ConfigArchive]). A restore goes
 * the other way through three gates — the phone reads the archive, the router hashes what
 * arrived, the router's own tar lists it — and only then does [restore] unpack it over / and
 * reboot. [restoreBlock] is what stands in front of that: if any gate is unmet there is no
 * button.
 */
class BackupStore(private val session: RouterSession, private val directory: File) {

    var board by mutableStateOf<BoardInfo?>(null); private set

    /** What `sysupgrade -l` says a backup carries. */
    var files by mutableStateOf<List<String>>(emptyList()); private set

    /** Free RAM in /tmp, which is where a restore archive has to sit. */
    var tmpFreeKb by mutableStateOf<Long?>(null); private set

    /** Archives on this phone, newest first — this router's and other routers' alike. */
    var local by mutableStateOf<List<LocalBackup>>(emptyList()); private set

    var candidate by mutableStateOf<RestoreCandidate?>(null); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** A line describing whatever long operation is running, for the screen to show. */
    var progress by mutableStateOf<String?>(null); private set

    /** What `sysupgrade -r` printed, kept when it refused so the user can read why. */
    var restoreOutput by mutableStateOf<String?>(null); private set

    /** Set once the archive is unpacked and the reboot sent; the session dies after this. */
    var restoring by mutableStateOf(false); private set

    val host: String get() = session.target.host
    val safeHost: String get() = ConfigArchive.safeHost(host)

    /** The newest archive taken from THIS router, which is what the System row reports. */
    val lastBackup: LocalBackup? get() = local.firstOrNull { it.host == safeHost }

    /** Cheap: a directory listing. Called on entry and after anything that changes it. */
    fun refreshLocal() {
        local = (directory.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                ConfigArchive.parseName(f.name)?.let { (h, t) -> LocalBackup(f, h, t, f.length()) }
            }
            .sortedByDescending { it.createdEpoch }
    }

    suspend fun load() {
        refreshLocal()
        loading = true
        try {
            val out = session.exec(Commands.BACKUP_INFO, timeoutMs = 30_000)
            val sections = Parsers.sections(out.stdout)
            board = runCatching { Parsers.board(sections["board"].orEmpty()) }.getOrNull()
            files = Parsers.backupFileList(sections["files"].orEmpty())
            tmpFreeKb = Parsers.overlayAvailKb(sections["tmp"].orEmpty())
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read backup state: ${e.message}"
        } finally {
            loading = false
        }
    }

    suspend fun backUp(): String {
        busy = true
        return try {
            when (val pulled = ConfigArchive.pull(session, directory) { progress = it }) {
                is ConfigArchive.Pull.Done -> {
                    refreshLocal()
                    "Backed up ${pulled.file.length() / 1024} kB to ${pulled.file.name}"
                }
                is ConfigArchive.Pull.Failed -> "Failed: ${pulled.why}"
            }
        } finally {
            busy = false
            progress = null
        }
    }

    fun delete(backup: LocalBackup): String {
        val gone = backup.file.delete()
        refreshLocal()
        return if (gone) "Deleted ${backup.name}" else "Failed: couldn't delete ${backup.name}"
    }

    /** Judges bytes from anywhere on the phone. Nothing here touches the router. */
    fun stage(source: String, bytes: ByteArray): String {
        val tar = when (val verdict = judge(bytes)) {
            is Judgement.Refused -> return "Failed: ${verdict.why}"
            is Judgement.Ok -> verdict.tar
        }
        candidate = RestoreCandidate(
            source = source,
            bytes = bytes,
            sha256 = sha256(bytes),
            entries = tar.entries,
            hostname = tar.readText("etc/config/system")
                ?.let { Parsers.uciFileOption(it, "system", null, "hostname") },
            lanAddress = tar.readText("etc/config/network")
                ?.let { Parsers.uciFileOption(it, "interface", "lan", "ipaddr") },
        )
        restoreOutput = null
        return "Ready: ${tar.entries.count { !it.isDirectory }} files, ${bytes.size / 1024} kB"
    }

    fun stageLocal(backup: LocalBackup): String =
        runCatching { backup.file.readBytes() }.getOrNull()?.let { stage(backup.name, it) }
            ?: "Failed: couldn't read ${backup.name}"

    /** Forgets the candidate, and takes the copy off the router if one got there. */
    suspend fun discard(): String {
        val c = candidate ?: return "Nothing staged."
        candidate = null
        restoreOutput = null
        if (c.onRouter) runCatching { session.exec(Commands.RESTORE_CLEANUP, timeoutMs = 15_000) }
        return "Discarded ${c.source}"
    }

    /** Sends the archive up and has the router judge it: its hash first, then its own tar. */
    suspend fun upload(): String {
        val c = candidate ?: return "Failed: nothing staged."
        val needKb = c.bytes.size / 1024
        tmpFreeKb?.let { free ->
            if (needKb > free) return "Failed: the archive needs $needKb kB but /tmp has $free kB free."
        }
        busy = true
        progress = "Sending $needKb kB to the router…"
        return try {
            val got = session.execWithInput(Commands.RESTORE_RECEIVE, c.bytes, timeoutMs = 180_000)
            val size = Parsers.byteCount(got.stdout)
            if (!got.ok || size != c.bytes.size.toLong()) {
                runCatching { session.exec(Commands.RESTORE_CLEANUP, timeoutMs = 15_000) }
                return "Failed: the router received ${size ?: 0} of ${c.bytes.size} bytes."
            }
            progress = "Checking the hash…"
            val sha = Parsers.sha256(session.exec(Commands.RESTORE_SHA256, timeoutMs = 60_000).stdout)
            if (sha != c.sha256) {
                runCatching { session.exec(Commands.RESTORE_CLEANUP, timeoutMs = 15_000) }
                return "Failed: the router's hash of the file does not match the phone's."
            }
            progress = "Asking the router's tar to read it…"
            val listed = session.exec(Commands.RESTORE_LIST, timeoutMs = 60_000)
            val listing = Parsers.tarListing(listed.stdout + "\n" + listed.stderr)
            val refusal = listing.exceptionOrNull()?.message
                ?: if (!listed.ok) "tar exited ${listed.exitCode}" else null
            candidate = c.copy(
                onRouter = true,
                routerListing = listing.getOrNull()?.takeIf { refusal == null },
                routerRefusal = refusal,
            )
            if (refusal != null) "Failed: the router's tar refused the archive."
            else "On the router · hash verified · ${listing.getOrNull()?.size ?: 0} members listed"
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
            progress = null
        }
    }

    /** The only write: unpack over /, then reboot. Refused unless every gate is met. */
    suspend fun restore(): String {
        val c = candidate ?: return "Failed: nothing staged."
        restoreBlock(c)?.let { return "Failed: $it" }
        busy = true
        progress = "Unpacking the archive over /…"
        return try {
            val out = try {
                session.exec(Commands.RESTORE_APPLY, timeoutMs = 120_000)
            } catch (e: SshException) {
                return "Failed: ${e.message}. The archive may or may not have been unpacked; " +
                    "the router was NOT rebooted. Check it before trying again."
            }
            val text = (out.stdout.trim() + "\n" + out.stderr.trim()).trim()
            restoreOutput = text.ifBlank { null }
            if (!out.ok) {
                "Failed: sysupgrade -r exited ${out.exitCode}. Nothing was rebooted."
            } else {
                progress = "Rebooting…"
                // Like the dashboard's reboot: the link can die before the reply lands.
                runCatching { session.exec(Commands.REBOOT, timeoutMs = 10_000) }
                restoring = true
                RESTORING_MESSAGE
            }
        } finally {
            busy = false
            progress = null
        }
    }

    companion object {

        /** Big enough for any config backup, small enough to hold in memory twice over. */
        const val MAX_RESTORE_BYTES = 8L * 1024 * 1024

        const val RESTORING_MESSAGE =
            "Restored. The router is rebooting — the connection drops now and comes back on its own."

        /** Why these bytes must not go near the router, or the opened archive. */
        fun judge(bytes: ByteArray): Judgement = when {
            bytes.isEmpty() -> Judgement.Refused("the file is empty.")
            bytes.size > MAX_RESTORE_BYTES -> Judgement.Refused(
                "the file is ${bytes.size / 1024 / 1024} MB; a config backup is a few hundred kB at most."
            )
            !TarArchive.isGzip(bytes) -> Judgement.Refused(
                "not a gzip file. OpenWrt backups are .tar.gz archives."
            )
            else -> {
                val tar = TarArchive.open(bytes)
                when {
                    tar == null -> Judgement.Refused(
                        "gzip opened, but the contents are not a tar archive this app can read."
                    )
                    tar.unsafePaths.isNotEmpty() -> Judgement.Refused(
                        "the archive contains paths that climb out of / (${tar.unsafePaths.first()}). " +
                            "Not restoring that."
                    )
                    !tar.looksLikeOpenWrtConfig -> Judgement.Refused(
                        "nothing under etc/ — this is not an OpenWrt configuration backup."
                    )
                    else -> Judgement.Ok(tar)
                }
            }
        }

        /**
         * The first gate that is not met, or null when the restore may be offered. Ordered
         * the way the user works through them, so the screen names the next thing to do.
         */
        fun restoreBlock(candidate: RestoreCandidate?): String? = when {
            candidate == null -> "Choose a backup to restore first."
            !candidate.onRouter -> "Send the archive to the router first."
            candidate.routerRefusal != null ->
                "The router's own tar could not read this archive: ${candidate.routerRefusal}. " +
                    "Unpacking it would fail halfway through."
            candidate.routerListing.isNullOrEmpty() -> "The router's tar found nothing in the archive."
            else -> null
        }

        /**
         * What the user has to read before the last button. None of these block: they are
         * consequences of a choice that is legitimately theirs to make.
         */
        fun restoreWarnings(candidate: RestoreCandidate, board: BoardInfo?, currentHost: String): List<String> =
            buildList {
                val here = board?.hostname?.ifBlank { null }
                val there = candidate.hostname
                if (there != null && here != null && there != here) {
                    add(
                        "The archive is from '$there'; this router is '$here'. After the restore " +
                            "this router answers to that name and carries that configuration."
                    )
                }
                val lan = candidate.lanAddress
                if (lan != null && isIpv4(currentHost) && lan != currentHost) {
                    add(
                        "It sets the LAN address to $lan. The saved entry for $currentHost stops " +
                            "working after the reboot — add the router again at $lan."
                    )
                }
                if (candidate.carriesHostKey && (there == null || here == null || there != here)) {
                    add(
                        "It carries an SSH host key. If it came from another router the app will " +
                            "report a changed key next time; after this restore that is expected, " +
                            "not a sign of interception."
                    )
                }
                add(
                    "Every file in the archive replaces the router's copy. Anything changed " +
                        "since the backup was taken is lost."
                )
                add(
                    "The router reboots when the restore is done. If you reach it over its own " +
                        "Wi-Fi the link drops and comes back with the restored settings."
                )
            }

        /** "just now", "12 min ago", "3 h ago", "6 d ago" — the System row's subtitle. */
        fun ageLabel(epochSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
            val s = (nowSeconds - epochSeconds).coerceAtLeast(0)
            return when {
                s < 60 -> "just now"
                s < 3_600 -> "${s / 60} min ago"
                s < 86_400 -> "${s / 3_600} h ago"
                else -> "${s / 86_400} d ago"
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private fun isIpv4(s: String) = IPV4.matches(s)
    }
}
