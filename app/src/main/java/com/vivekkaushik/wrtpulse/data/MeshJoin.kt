package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshKeys
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Backhaul
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.MeshNodeState
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.MeshProfile
import com.vivekkaushik.wrtpulse.ops.NodePlan
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.WanLink
import kotlinx.coroutines.delay
import java.io.File

/** One line of the apply checklist. */
data class JoinStep(val label: String, val state: State, val detail: String? = null) {
    enum class State { Pending, Running, Done, Failed }
}

/** What the app has to write down once the node has moved — or moved back. */
data class JoinOutcome(
    /** Where the saved row should point now. */
    val host: String,
    val joined: Boolean,
    val backhaul: Backhaul,
    val snapshot: String?,
    val meshMac: String?,
    /** A key this join installed on the node, to keep alongside the row. Null when it already had one. */
    val installedKeyPem: ByteArray?,
)

/**
 * Turns the router the app is connected to into a node of [profile]'s primary.
 *
 * The order is the whole design. The app's key goes on first so the snapshot taken next still
 * lets the app in after a Leave; the wpad swap runs while the node still has internet through
 * its WAN socket; the batch is the one thing that moves the address, and it runs under a
 * rollback the node enforces on its own. Then the app has to find the node at its new
 * address — the phone hops to the home Wi-Fi by itself, since the node now carries it — and
 * confirm within the window. Only after that do the services the primary owns get switched off.
 */
class MeshJoin(
    private val session: RouterSession,
    private val client: SshClient,
    initialProfile: MeshProfile,
    val entity: RouterEntity,
    private val backups: File,
    /** The row's own key, opened by the app, when it signs in with one already. */
    private val existingKeyPem: ByteArray?,
    /** How many nodes the primary has, for the default name. */
    existingNodes: Int,
) {
    /**
     * What the primary looks like. Starts as the copy sealed into its saved row and is replaced
     * when the wizard manages to read the primary live — the copy goes stale the moment the
     * mesh link is turned on there after this router was last connected.
     */
    var profile by mutableStateOf(initialProfile)

    var state by mutableStateOf<MeshNodeState?>(null); private set
    var board by mutableStateOf<BoardInfo?>(null); private set
    var wan by mutableStateOf<WanLink?>(null); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var name by mutableStateOf(MeshOps.defaultNodeName(existingNodes))
    var backhaul by mutableStateOf(Backhaul.Wired)

    val steps = mutableStateListOf<JoinStep>()
    var applying by mutableStateOf(false); private set
    var done by mutableStateOf(false); private set
    var rolledBack by mutableStateOf(false); private set
    var newAddress by mutableStateOf<String?>(null); private set
    var meshMac by mutableStateOf<String?>(null); private set

    /** The session that reached the node at its new address, kept for the link watch. */
    private var follow: RouterSession? = null

    /** The mesh link's signal towards the primary, while the wireless node is being placed. */
    var linkSignalDbm by mutableStateOf<Int?>(null); private set
    var linkChecked by mutableStateOf(false); private set

    suspend fun load() {
        try {
            val out = session.exec(Commands.MESH_NODE_STATE, timeoutMs = 20_000).requireOk("read node").stdout
            ingest(Parsers.sections(out))
            loaded = true
            error = null
        } catch (e: SshException) {
            error = e.message
        }
    }

    fun ingest(parts: Map<String, String>) {
        val network = Parsers.uciShow(parts["net"].orEmpty())
        val (radios, networks) = Parsers.wireless(Parsers.uciShow(parts["uci"].orEmpty()))
        val status = Parsers.wirelessStatus(parts["status"].orEmpty())
        val ifnameRadio = status.associate { it.ifname to it.radio }
        val macs = linkedMapOf<String, String>()
        Parsers.iwDevs(parts["macs"].orEmpty()).forEach { dev ->
            val radio = ifnameRadio[dev.ifname]
                ?: Regex("^phy(\\d+)-").find(dev.ifname)?.groupValues?.get(1)?.let { "radio$it" }
                ?: return@forEach
            if (dev.mac.isNotEmpty()) macs.putIfAbsent(radio, dev.mac)
        }
        board = parts["system"]?.takeIf { it.isNotBlank() }?.let { runCatching { Parsers.board(it) }.getOrNull() }
        state = MeshNodeState(
            networkUci = network,
            dhcpUci = Parsers.uciShow(parts["dhcp"].orEmpty()),
            radios = radios,
            networks = networks,
            swDevs = Parsers.switchDevs(parts["swconfig"].orEmpty()),
            boardPorts = Parsers.boardSwitchPorts(parts["board"].orEmpty()),
            meshCapable = parts["capable"].orEmpty().trim() == "yes",
            wpad = parts["wpad"].orEmpty().trim().lines().firstOrNull().orEmpty(),
            manager = parts["pm"].orEmpty().trim().ifEmpty { "opkg" },
            overlayFreeKb = parts["df"].orEmpty().trim().split(Regex("\\s+")).getOrNull(3)?.toLongOrNull(),
            radioMacs = macs,
            hostname = board?.hostname.orEmpty(),
        )
        wan = Parsers.wanLinks(parts["dump"].orEmpty()).firstOrNull { it.name == "wan" }
    }

    /** True when the WAN socket holds an address from the primary's subnet — the cable is in. */
    val cabledToPrimary: Boolean
        get() {
            val link = wan ?: return false
            val ip = com.vivekkaushik.wrtpulse.ops.IpMath.parse(link.address) ?: return false
            val primary = com.vivekkaushik.wrtpulse.ops.IpMath.parse(profile.primaryIp) ?: return false
            return link.up && com.vivekkaushik.wrtpulse.ops.IpMath.sameSubnet(ip, primary, profile.prefix)
        }

    /** Whether the wpad swap has to happen, which is what makes the cable mandatory for a wireless node. */
    val needsSwap: Boolean get() = backhaul == Backhaul.Wireless && state?.let { MeshOps.wpadSwap(it.wpad, it.meshCapable) } != null

    val cableRequired: Boolean get() = backhaul == Backhaul.Wired || needsSwap

    /** Everything the review shows and the batch runs, from the current name and backhaul. */
    fun plan(): NodePlan? {
        val node = state ?: return null
        val address = MeshOps.freeNodeAddress(profile, alsoTaken = setOf(session.target.host))
        val swap = if (backhaul == Backhaul.Wireless) MeshOps.wpadSwap(node.wpad, node.meshCapable) else null
        val problems = MeshOps.nodeProblems(profile, node, backhaul, address, entity.meshPrimary) +
            (if (cableRequired && !cabledToPrimary && loaded) listOf(
                if (backhaul == Backhaul.Wired) "Plug a cable from this router's WAN socket into a LAN socket of ${profile.primaryName} first."
                else "The wpad swap downloads a package, so this router needs internet: plug its WAN socket into ${profile.primaryName} for now."
            ) else emptyList())
        return NodePlan(
            name = name.trim().ifEmpty { "Node" },
            hostname = MeshOps.hostnameOf(name),
            backhaul = backhaul,
            address = address ?: "",
            prefix = profile.prefix,
            ops = if (address == null) emptyList() else MeshOps.nodeOps(profile, node, name, backhaul, address),
            packages = MeshOps.NODE_PACKAGES,
            swap = swap,
            problems = problems,
            notes = MeshOps.nodeNotes(profile, node, backhaul, swap),
            meshRadio = if (backhaul == Backhaul.Wireless) MeshOps.meshRadioOf(profile, node) else null,
        )
    }

    // -----------------------------------------------------------------------
    // The apply
    // -----------------------------------------------------------------------

    private fun step(index: Int, state: JoinStep.State, detail: String? = null) {
        steps[index] = steps[index].copy(state = state, detail = detail)
    }

    /**
     * Runs the whole join. [persist] is called once the address has moved (joined = true) or
     * once the node is known to have put itself back (joined = false), so the saved row is
     * right either way.
     */
    suspend fun apply(persist: suspend (JoinOutcome) -> Unit): Boolean {
        val plan = plan() ?: return false
        if (plan.problems.isNotEmpty() || applying) return false
        applying = true
        done = false
        rolledBack = false
        error = null
        steps.clear()
        steps += JoinStep("Install the app's SSH key", JoinStep.State.Pending)
        steps += JoinStep("Save a backup to this phone", JoinStep.State.Pending)
        if (plan.swap != null) steps += JoinStep("Install ${plan.swap.install}", JoinStep.State.Pending)
        steps += JoinStep("Write the node config", JoinStep.State.Pending)
        steps += JoinStep("Find the node at ${plan.address}", JoinStep.State.Pending)
        steps += JoinStep("Switch off DHCP, DNS and the firewall here", JoinStep.State.Pending)
        var i = 0
        var installedKey: ByteArray? = null
        var snapshot: String? = null
        try {
            // 1. The key. Before the snapshot, so the archive Leave mesh restores carries it.
            step(i, JoinStep.State.Running)
            val keyPem = existingKeyPem ?: run {
                val generated = SshKeys.generateEd25519()
                session.exec(Commands.installKey(generated.publicLine), timeoutMs = 10_000).requireOk("install key")
                installedKey = generated.privatePem
                generated.privatePem
            }
            step(i, JoinStep.State.Done, if (installedKey != null) "installed" else "already there")
            i++

            // 2. The snapshot.
            step(i, JoinStep.State.Running)
            when (val pulled = ConfigArchive.pull(session, backups, board?.release) { step(i, JoinStep.State.Running, it) }) {
                is ConfigArchive.Pull.Done -> { snapshot = pulled.file.name; step(i, JoinStep.State.Done, pulled.file.name) }
                is ConfigArchive.Pull.Failed -> {
                    step(i, JoinStep.State.Failed, pulled.why)
                    error = "No backup, no join: ${pulled.why}"
                    return false
                }
            }
            i++

            // 3. The swap, while the node still has internet through its WAN.
            if (plan.swap != null) {
                step(i, JoinStep.State.Running, "downloading; Wi-Fi here drops for about a minute")
                val ok = swapWpad(plan.swap.remove, plan.swap.install) { step(i, JoinStep.State.Running, it) }
                if (!ok) {
                    step(i, JoinStep.State.Failed, "the router kept ${plan.swap.remove}")
                    error = "${plan.swap.install} did not install. Nothing else was changed."
                    return false
                }
                step(i, JoinStep.State.Done)
                i++
            }

            // 4. The batch. The reply may not come back — the reload is about to move the address.
            step(i, JoinStep.State.Running)
            val script = Commands.nodeApply(plan.ops, plan.packages, MeshOps.ROLLBACK_SECONDS)
            try {
                session.exec(script, timeoutMs = 30_000).requireOk("uci batch")
            } catch (e: SshException) {
                if (e !is SshException.Disconnected && e !is SshException.Timeout) throw e
            }
            runCatching { session.disconnect() }
            step(i, JoinStep.State.Done, "committed; the node keeps it only if the app finds it within ${MeshOps.ROLLBACK_SECONDS} s")
            i++
            newAddress = plan.address
            persist(JoinOutcome(plan.address, joined = true, plan.backhaul, snapshot, null, installedKey))

            // 5. Find it. Fresh sessions, one dial each, short timeout, until the window closes.
            step(i, JoinStep.State.Running, "your phone joins ${profile.ssids.firstOrNull()?.ssid ?: "the home Wi-Fi"} on its own — stay near ${profile.primaryName}")
            val target = SshTarget(plan.address, entity.port, entity.username, entity.identity)
            val found = findNode(target, keyPem) { step(i, JoinStep.State.Running, it) }
            if (found == null) {
                step(i, JoinStep.State.Failed, "not reached in ${MeshOps.ROLLBACK_SECONDS} s")
                rolledBack = true
                persist(JoinOutcome(entity.host, joined = false, plan.backhaul, snapshot, null, installedKey))
                error = "The node was not reachable at ${plan.address} in time, so it put its old config back on its own. " +
                    "It is still at ${entity.host}."
                return false
            }
            follow = found
            step(i, JoinStep.State.Done, "confirmed")
            i++

            // 6. Only now the services. A rollback has nothing to switch back on.
            step(i, JoinStep.State.Running)
            runCatching { found.exec(Commands.NODE_SERVICES_OFF, timeoutMs = 40_000) }
            meshMac = runCatching {
                Parsers.iwDevs(found.exec(Commands.WIFI_MACS, timeoutMs = 8_000).stdout)
                    .firstOrNull { it.type.contains("mesh") }?.mac
            }.getOrNull()
            persist(JoinOutcome(plan.address, joined = true, plan.backhaul, snapshot, meshMac, installedKey))
            step(i, JoinStep.State.Done)
            done = true
            return true
        } catch (e: SshException) {
            if (i < steps.size) step(i, JoinStep.State.Failed, e.message)
            error = e.message
            return false
        } finally {
            applying = false
        }
    }

    private suspend fun swapWpad(remove: String, install: String, onProgress: (String) -> Unit): Boolean {
        val manager = state?.manager ?: "opkg"
        try {
            session.exec(Commands.wpadSwap(remove, install, manager), timeoutMs = 20_000).requireOk("swap wpad")
        } catch (e: SshException) {
            if (e !is SshException.Disconnected && e !is SshException.Timeout) throw e
        }
        val deadline = System.nanoTime() + SWAP_WAIT_NANOS
        while (System.nanoTime() < deadline) {
            delay(5_000)
            val text = try {
                session.exec(Commands.SWAP_STATE, timeoutMs = 8_000).stdout
            } catch (e: SshException) {
                onProgress("waiting for the radios to come back…")
                continue
            }
            val first = text.trim().lines().firstOrNull()?.trim().orEmpty()
            when (first) {
                "ok" -> return true
                "failed" -> return false
                else -> onProgress("installing…")
            }
        }
        return false
    }

    /**
     * Dials the new address every few seconds until it answers or the node's window closes.
     * Each try is its own session with one attempt and a short timeout, so a dead address
     * costs five seconds, not the whole window. The pin follows the identity, so this is
     * not a first contact; a changed key here means a different router answers there.
     */
    private suspend fun findNode(target: SshTarget, keyPem: ByteArray, onProgress: (String) -> Unit): RouterSession? {
        val deadline = System.nanoTime() + MeshOps.ROLLBACK_SECONDS * 1_000_000_000L
        val started = System.nanoTime()
        while (System.nanoTime() < deadline) {
            val candidate = RouterSession(
                target, client,
                credentials = { SshAuth.PrivateKey(keyPem.copyOf()) },
                maxAttempts = 1,
                connectTimeoutMs = 5_000,
            )
            try {
                val out = candidate.exec(Commands.NODE_CONFIRM, timeoutMs = 8_000)
                if (out.stdout.contains("confirmed")) return candidate
            } catch (e: SshException.HostKeyChanged) {
                error = "Something else answers at ${target.host} with a different host key."
                return null
            } catch (e: SshException) {
                runCatching { candidate.disconnect() }
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000L
            onProgress("${elapsed}s — waiting for ${target.host} to answer")
            delay(5_000)
        }
        return null
    }

    /**
     * For a wireless node once the cable is out: whether its mesh point sees the primary,
     * and how loud. Read through the follow session, since the app is not on the primary.
     */
    suspend fun checkLink() {
        val s = follow ?: return
        try {
            val peers = Parsers.meshPeers(s.exec(Commands.MESH_PEERS, timeoutMs = 8_000).stdout)
            val primary = peers.firstOrNull { it.mac in profile.primaryMacs } ?: peers.firstOrNull { it.established }
            linkSignalDbm = primary?.signalDbm
            linkChecked = true
        } catch (e: SshException) {
            linkChecked = true
            linkSignalDbm = null
        }
    }

    /** Drops the follow session. The app opens its own to the node afterwards. */
    suspend fun close() {
        val s = follow ?: return
        follow = null
        runCatching { s.disconnect() }
    }

    companion object {
        private const val SWAP_WAIT_NANOS = 240_000_000_000L
    }
}
