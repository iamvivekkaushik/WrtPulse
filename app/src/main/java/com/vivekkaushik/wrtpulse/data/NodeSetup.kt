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
        return try {
            val out = session.exec(Commands.BOARD, timeoutMs = 15_000)
            if (!out.ok || !out.stdout.contains("board_name")) {
                fail(
                    if (out.stderr.contains("closed", ignoreCase = true) || out.exitCode == 255) "The router refused that password."
                    else "No answer over the cable: ${out.stderr.trim().lines().lastOrNull().orEmpty()}"
                )
                return null
            }
            board = Parsers.board(out.stdout)
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
     * Puts the socket back in the LAN. With [keepCable] the node has joined and its cable is
     * its backhaul; without it, a socket that still has a cable in it stays held — whatever is
     * on that cable is not a node, and the primary keeps refusing it until it is unplugged.
     */
    suspend fun release(keepCable: Boolean = false) {
        if (released) return
        val out = runCatching {
            primary.exec(if (keepCable) Commands.SETUP_RELEASE_KEEP_CABLE else Commands.SETUP_RELEASE, timeoutMs = 40_000).stdout
        }.getOrNull().orEmpty()
        released = !out.contains("still-cabled")
    }

    private fun fail(why: String) {
        error = why
        phase = Phase.Failed
    }

    companion object {
        private const val FIND_NANOS = 90_000_000_000L

        /** `ssh-ed25519 AAAA… comment` lines → pinnable keys. */
        fun parseHostKeys(text: String): List<HostKey> = text.lineSequence().mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size < 2 || !(f[0].startsWith("ssh-") || f[0].startsWith("ecdsa-"))) return@mapNotNull null
            val raw = runCatching { Base64.getDecoder().decode(f[1]) }.getOrNull() ?: return@mapNotNull null
            HostKey(f[0], f[1], HostKeyStore.fingerprint(raw))
        }.toList()
    }
}
