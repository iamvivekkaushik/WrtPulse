package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import java.io.File

/** One run of a loss line. The design sets values in mono and the verdict in bold. */
sealed interface LossText {
    data class Plain(val text: String) : LossText
    data class Mono(val text: String) : LossText
    data class Strong(val text: String) : LossText
}

/**
 * A factory reset, which is the one action in the app with nothing behind it.
 *
 * `firstboot` erases /overlay, so there is no dry run to offer and no rollback to arm — the
 * only honest thing left is to say exactly what is about to go, read from the config itself
 * rather than described in general terms. Everything here is a read except [reset].
 */
class ResetStore(private val session: RouterSession, private val directory: File) {

    var board by mutableStateOf<BoardInfo?>(null); private set
    var summary by mutableStateOf<Parsers.ResetSummary?>(null); private set

    /** Where the safety copy landed on the phone, once it is off the router. */
    var backupFile by mutableStateOf<File?>(null); private set

    /** How many files that copy carries, as `sysupgrade -l` counted them. */
    var backupFiles by mutableStateOf<Int?>(null); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<String?>(null); private set

    /** Set once firstboot is away. The session is expected to die after this. */
    var resetting by mutableStateOf(false); private set

    /** What the screen names before it lets the hold arm. */
    val losses: List<List<LossText>> get() = losses(summary)

    suspend fun load() {
        if (loading) return
        loading = true
        try {
            val out = session.exec(
                "echo ${Commands.SECTION} board; ${Commands.BOARD}; " +
                    "echo ${Commands.SECTION} files; ${Commands.BACKUP_LIST}; " +
                    Commands.RESET_SUMMARY,
                timeoutMs = 60_000,
            )
            val parts = Parsers.sections(out.stdout)
            board = Parsers.board(parts["board"].orEmpty())
            summary = Parsers.resetSummary(parts)
            backupFiles = Parsers.backupFileList(parts["files"].orEmpty()).size.takeIf { it > 0 }
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read what a reset would erase: ${e.message}"
        } finally {
            loading = false
        }
    }

    /**
     * Pulls the config onto the phone first. Not a gate — the user may reset without one —
     * but the screen says which of the two they are doing.
     */
    suspend fun backUp(): String {
        busy = true
        return try {
            when (val pulled = ConfigArchive.pull(session, directory, board?.release) { progress = it }) {
                is ConfigArchive.Pull.Done -> {
                    backupFile = pulled.file
                    "Backed up ${pulled.file.length() / 1024} kB to ${pulled.file.name}"
                }
                is ConfigArchive.Pull.Failed -> "Failed: ${pulled.why}"
            }
        } finally {
            busy = false
            progress = null
        }
    }

    /**
     * `firstboot -y && reboot`.
     *
     * Detached, so the reboot killing the link is not read as a failure. There is no
     * confirming re-read afterwards: the router that comes back has a different host key
     * and a different address, which is the router list's problem, not this screen's.
     */
    suspend fun reset(): String {
        busy = true
        return try {
            session.exec(Commands.FACTORY_RESET, timeoutMs = 20_000)
            resetting = true
            "Reset started — the router is rebooting."
        } catch (e: SshException) {
            // The link dying IS the expected outcome, so a dropped session is not a failure.
            resetting = true
            "Reset started — the link dropped, which is expected."
        } finally {
            busy = false
        }
    }

    companion object {

        /** Where a reset puts the router, and what the app has to be told to expect. */
        const val DEFAULT_ADDRESS = "192.168.1.1"

        /**
         * What the reset erases, as the design's four lines.
         *
         * A line the router has nothing behind is left out rather than shown as zero: an
         * empty count reads as reassurance, and the point of the list is the opposite.
         */
        fun losses(summary: Parsers.ResetSummary?): List<List<LossText>> = buildList {
            val s = summary ?: return@buildList
            if (s.ssids.isNotEmpty()) {
                add(
                    listOf(
                        LossText.Plain("Wi-Fi networks "),
                        LossText.Mono(s.ssids.joinToString(" · ")),
                        LossText.Plain(" — radios come back "),
                        LossText.Strong("disabled"),
                    )
                )
            }
            val counts = buildList {
                if (s.wanProto == "pppoe") add("PPPoE login")
                if (s.forwards > 0) add("${s.forwards} forwards")
                if (s.rules > 0) add("${s.rules} rules")
                if (s.reservations > 0) add("${s.reservations} reservations")
            }
            if (s.lanAddress != null || counts.isNotEmpty()) {
                add(
                    buildList {
                        if (s.lanAddress != null && s.lanAddress != DEFAULT_ADDRESS) {
                            add(LossText.Plain("LAN "))
                            add(LossText.Mono(s.lanAddress))
                            add(LossText.Plain(" → "))
                            add(LossText.Mono(DEFAULT_ADDRESS))
                            if (counts.isNotEmpty()) add(LossText.Plain(" · "))
                        }
                        if (counts.isNotEmpty()) add(LossText.Plain(counts.joinToString(", ")))
                    }
                )
            }
            // Always: there is no router without these, so there is nothing to check first.
            add(
                listOf(
                    LossText.Plain(
                        "Root password, this app's SSH key, and the host key — expect a " +
                            "changed-key warning"
                    )
                )
            )
            if (s.packages.isNotEmpty()) {
                add(
                    listOf(
                        LossText.Plain(
                            if (s.packages.size == 1) "1 package you installed: "
                            else "${s.packages.size} packages you installed: "
                        ),
                        LossText.Mono(s.packages.joinToString(", ")),
                    )
                )
            }
        }
    }
}
