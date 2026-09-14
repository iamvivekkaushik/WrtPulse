package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.HostKey
import com.vivekkaushik.wrtpulse.net.HostKeyStore
import com.vivekkaushik.wrtpulse.net.JumpSession
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * Adding a node from the primary's side, with a cable and nothing else.
 *
 * The new router is plugged by one of its LAN sockets into a LAN socket of the primary. This
 * watches the primary's sockets for the one that comes up, takes that socket out of the LAN
 * into a bridge of its own so the new router's DHCP server and address touch nothing, finds
 * the router there by its IPv6 link-local address, and opens a [JumpSession] to it through
 * the primary. From then on the join runs exactly as it would from the phone, over the hop.
 * The socket goes back into the LAN once the node has its address on the primary's subnet —
 * or by itself after twenty minutes, if the phone never gets that far.
 */
class NodeSetup(
    private val primary: RouterSession,
    private val hostKeys: HostKeyStore,
) {
    enum class Phase { Isolating, Watching, Finding, Found, Connecting, Ready, Failed }

    var phase by mutableStateOf(Phase.Watching); private set
    var port by mutableStateOf<String?>(null); private set
    val candidates = mutableStateListOf<String>()
    var nodeAddress by mutableStateOf<String?>(null)
    var password by mutableStateOf("")
    var board by mutableStateOf<BoardInfo?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var note by mutableStateOf<String?>(null); private set
    var released by mutableStateOf(false); private set

    /** The router answered from failsafe mode: no services, no saved config, nothing to join with. */
    var failsafe by mutableStateOf(false); private set

    /** The identity the new router's row and pins will carry, decided before it has a row. */
    val identity: String = RouterEntity.newIdentity()

    var jump: JumpSession? = null; private set

    /** The socket as the case labels it, when the board file says; else the netdev or port number. */
    val portLabel: String get() = port?.let { p -> if (p.startsWith("sw:")) "port ${p.removePrefix("sw:")}" else p } ?: "?"

    /**
     * Watches for the cable, isolates the socket, finds the router. Runs until [Phase.Found]
     * or [Phase.Failed]; cancelling it mid-way leaves the socket to the router's own timer.
     */
    suspend fun run() {
        try {
            error = null
            // The socket is held BEFORE the cable goes in. A router plugged into a live LAN
            // socket, even for the seconds it takes to notice, is a second DHCP server on the
            // household network; held first, it is never on it at all.
            phase = Phase.Isolating
            var networkUci = Parsers.uciShow(primary.exec(Commands.NETWORK_CONFIG, timeoutMs = 10_000).stdout)
            var links = readLinks()
            val held = MeshOps.heldSetupPort(networkUci) ?: MeshOps.freeLanSockets(links.first, links.second, networkUci).firstOrNull()
                ?: run { fail("Every LAN socket on the primary has something in it. Free one up for the new router."); return }
            if (MeshOps.heldSetupPort(networkUci) == null) {
                hold(networkUci, held) ?: return
                networkUci = Parsers.uciShow(primary.exec(Commands.NETWORK_CONFIG, timeoutMs = 10_000).stdout)
            }
            port = held
            phase = Phase.Watching
            // Now the cable. It should land in the held socket; if it lands in another free
            // one, the hold moves there — and the moment of exposure is the one thing avoided
            // above, so that other socket is released and re-held rather than just watched.
            while (true) {
                delay(2_000)
                val now = readLinks()
                if (MeshOps.socketUp(held, now.first, now.second)) break
                val other = MeshOps.newlyUpPort(links.first, now.first, links.second, now.second, networkUci)
                links = now
                if (other != null && other != held) {
                    note = "The cable went into $other, not ${portLabel}. Moving the hold there."
                    runCatching { primary.exec(Commands.SETUP_RELEASE_KEEP_CABLE, timeoutMs = 40_000) }
                    networkUci = Parsers.uciShow(primary.exec(Commands.NETWORK_CONFIG, timeoutMs = 10_000).stdout)
                    hold(networkUci, other) ?: return
                    port = other
                    break
                }
            }
            phase = Phase.Finding
            val deadline = System.nanoTime() + FIND_NANOS
            while (System.nanoTime() < deadline) {
                delay(3_000)
                val seen = primary.exec(Commands.SETUP_DISCOVER, timeoutMs = 15_000).stdout
                    .lines().map { it.trim() }.filter { it.startsWith("fe80") }
                if (seen.isNotEmpty()) {
                    candidates.clear(); candidates.addAll(seen)
                    nodeAddress = seen.first()
                    phase = Phase.Found
                    return
                }
                note = "Nothing answers on $portLabel yet. Give the router a minute to boot."
            }
            fail("No router answered on $portLabel. Is the cable in one of its LAN sockets, and is it powered?")
        } catch (e: SshException) {
            fail(e.message ?: "the primary stopped answering")
        }
    }

    /** Isolates one socket on the primary, with the router's own carrier-gated undo armed. */
    private suspend fun hold(networkUci: Map<String, String>, socket: String): Unit? {
        val isolate = MeshOps.setupPortOps(networkUci, socket)
            ?: run { fail("$socket is not in the LAN, so it cannot be held apart."); return null }
        val release = MeshOps.setupReleaseOps(MeshOps.afterIsolation(networkUci, socket), socket)
        primary.exec(Commands.setupApply(isolate, release, Commands.socketLinkCheck(socket)), timeoutMs = 40_000)
            .requireOk("isolate setup port")
        return Unit
    }

    private suspend fun readLinks(): Pair<List<com.vivekkaushik.wrtpulse.ops.NetDev>, List<com.vivekkaushik.wrtpulse.ops.SwitchDev>> {
        val parts = Parsers.sections(primary.exec(Commands.SETUP_LINKS, timeoutMs = 15_000).stdout)
        return Parsers.netdevs(parts["links"].orEmpty()) to Parsers.switchDevs(parts["swconfig"].orEmpty())
    }

    /**
     * Opens the hop with the password in hand — empty on a fresh install — and reads the
     * router's board and host keys. The keys are pinned now, under the identity its row will
     * carry, so the first direct contact at its new address later is not a first contact.
     */
    suspend fun connect(): JumpSession? {
        val addr = nodeAddress ?: return null
        phase = Phase.Connecting
        error = null
        val target = SshTarget("$addr%${MeshOps.SETUP_BRIDGE}", 22, "root", identity)
        val session = JumpSession(primary, target, password)
        failsafe = false
        return try {
            val out = session.exec(Commands.HOP_PROBE, timeoutMs = 15_000)
            val json = when (val v = hopVerdict(out)) {
                is HopVerdict.Board -> v.json
                HopVerdict.Refused -> { fail("The router refused that password."); return null }
                is HopVerdict.Failsafe -> {
                    failsafe = true
                    fail("${v.boardName.ifBlank { "The router" }} is in failsafe mode.")
                    return null
                }
                is HopVerdict.NoAnswer -> { fail("No answer over the cable: ${v.detail}"); return null }
            }
            board = Parsers.board(json)
            val keys = session.exec(Commands.HOST_KEY_LINES, timeoutMs = 10_000).stdout
            parseHostKeys(keys).forEach { hostKeys.trust(SshTarget("pending", 22, "root", identity), it) }
            jump = session
            phase = Phase.Ready
            session
        } catch (e: SshException) {
            fail(e.message ?: "the hop failed")
            null
        }
    }

    /**
     * Wipes a router that answered from failsafe mode and reboots it into a fresh install.
     * The cable stays where it is and the socket stays held, so [run] finds it again once
     * it is up — a minute or two.
     */
    suspend fun wipeAndReboot(): Boolean {
        val addr = nodeAddress ?: return false
        val target = SshTarget("$addr%${MeshOps.SETUP_BRIDGE}", 22, "root", identity)
        val out = runCatching { JumpSession(primary, target, password).exec(Commands.FAILSAFE_WIPE, timeoutMs = 30_000) }.getOrNull()
        if (out?.stdout?.contains("wiped") != true) {
            fail("Could not reset it: ${out?.stderr?.trim()?.lines()?.lastOrNull().orEmpty().ifBlank { "no answer" }}")
            return false
        }
        failsafe = false
        error = null
        board = null
        nodeAddress = null
        candidates.clear()
        note = "Resetting and rebooting; it answers again in a minute or two."
        phase = Phase.Watching
        return true
    }

    /**
     * Puts the socket back in the LAN. With [keepCable] the node has joined and its cable is
     * its backhaul; without it, a socket that still has a cable in it stays held — whatever is
     * on that cable is not a node, and the primary keeps refusing it until it is unplugged.
     */
    suspend fun release(keepCable: Boolean = false) {
        if (released) return
        val out = runCatching {
            primary.exec(if (keepCable) Commands.SETUP_RELEASE_KEEP_CABLE else Commands.SETUP_RELEASE, timeoutMs = 40_000).stdout
        }.getOrNull().orEmpty()
        if (out.contains("still-cabled")) return
        // The stored script lives in /tmp: a reboot wipes it while the uci change that holds
        // the socket survives, and "nothing held" would then leave the socket dead for good.
        if (!out.contains("released")) runCatching { releaseHeldSocketFromConfig(primary) }
        released = true
    }

    private fun fail(why: String) {
        error = why
        phase = Phase.Failed
    }

    /** What the first command over the hop came back as. */
    sealed interface HopVerdict {
        data class Board(val json: String) : HopVerdict
        /** dropbear on the far side closed the session: the password was wrong. */
        data object Refused : HopVerdict
        data class Failsafe(val boardName: String) : HopVerdict
        data class NoAnswer(val detail: String) : HopVerdict
    }

    companion object {
        /** A fresh install can take this long to come up after a reset; found or not by then. */
        private const val FIND_NANOS = 150_000_000_000L

        /**
         * Reads [Commands.HOP_PROBE]'s reply. A wrong password shows as dropbear closing the
         * session before any command ran, so stdout is empty; failsafe answers with the marker
         * and the board name but no ubus; a normal router answers with the board JSON.
         */
        fun hopVerdict(out: com.vivekkaushik.wrtpulse.net.ExecResult): HopVerdict {
            val parts = Parsers.sections(out.stdout)
            val head = out.stdout.substringBefore(Commands.SECTION).lines().map { it.trim() }
            val board = parts["board"].orEmpty()
            val brace = board.indexOf('{')
            if (brace >= 0 && board.contains("board_name")) return HopVerdict.Board(board.substring(brace))
            if (head.contains("wrtpulse-failsafe")) {
                return HopVerdict.Failsafe(head.firstOrNull { it.isNotEmpty() && it != "wrtpulse-failsafe" }.orEmpty())
            }
            val err = out.stderr.trim()
            if (out.stdout.isBlank() && (err.contains("closed", ignoreCase = true) || err.contains("auth", ignoreCase = true))) return HopVerdict.Refused
            return HopVerdict.NoAnswer(err.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { "nothing came back" })
        }

        /** `ssh-ed25519 AAAA… comment` lines → pinnable keys. */
        fun parseHostKeys(text: String): List<HostKey> = text.lineSequence().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 2 || !(f[0].startsWith("ssh-") || f[0].startsWith("ecdsa-"))) return@mapNotNull null
            val raw = runCatching { Base64.getDecoder().decode(f[1]) }.getOrNull() ?: return@mapNotNull null
            HostKey(f[0], f[1], HostKeyStore.fingerprint(raw))
        }.toList()
    }
}

/**
 * Puts a held setup socket back from the config alone, for when the router's stored undo
 * script is gone — consumed without effect, or lost to a reboot — while the uci sections that
 * isolate the socket are still there. True when something was put back.
 */
suspend fun releaseHeldSocketFromConfig(session: RouterSession): Boolean {
    val uci = Parsers.uciShow(session.exec(Commands.NETWORK_CONFIG, timeoutMs = 10_000).stdout)
    val held = MeshOps.heldSetupPort(uci) ?: return false
    val ops = MeshOps.setupReleaseOps(uci, held)
    if (ops.isEmpty()) return false
    val out = session.exec(
        Commands.uciBatch(ops, MeshOps.SETUP_PACKAGES, "/etc/init.d/network reload >/dev/null 2>&1; ${Commands.FIREWALL_RELOAD} >/dev/null 2>&1; echo released"),
        timeoutMs = 40_000,
    )
    return out.stdout.contains("released")
}
