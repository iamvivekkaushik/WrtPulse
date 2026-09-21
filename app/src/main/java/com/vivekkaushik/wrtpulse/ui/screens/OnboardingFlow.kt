package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.HostKey
import com.vivekkaushik.wrtpulse.net.HostKeyStore
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.net.SshKeys
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.net.WrtRuntime
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives onboarding screens 01–03 against the real transport.
 *
 * The order matters for the security story: [connect] only ever *probes* — the key exchange
 * completes and we hang up before auth, so the password can't reach a router the user hasn't
 * confirmed. Auth happens in [confirmFirstContact] (after the user says "that's mine") or
 * immediately when the presented key already matches the pinned one.
 */
class OnboardingFlow(
    private val scope: CoroutineScope,
    private val client: SshClient,
    private val hostKeys: HostKeyStore,
) {
    var host by mutableStateOf("")
    var port by mutableStateOf("22")
    var username by mutableStateOf("root")
    var password by mutableStateOf("")
    var gateway by mutableStateOf<String?>(null)

    /**
     * Which saved router this attempt belongs to — the scope its host key is pinned under, and
     * the row [persist] writes. A fresh add gets a new one; opening a saved router takes the
     * row's. See [SshTarget.identity] for why the address cannot play this part.
     */
    var identity: String = RouterEntity.newIdentity()
        private set

    /** Set when connecting a saved router that has a stored key, or after installing one. */
    var keyPem: ByteArray? = null

    /**
     * Saved routers that also answer at this address but hold a different key, by name. The
     * first-contact screen shows them: a router someone reflashed and then re-added by
     * address would otherwise be saved a second time without a word about the old entry.
     */
    var sameAddress by mutableStateOf<List<String>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var probed by mutableStateOf<HostKey?>(null)
        private set
    var board by mutableStateOf<BoardInfo?>(null)
        private set
    var keyChange by mutableStateOf<SshException.HostKeyChanged?>(null)
        private set

    val target: SshTarget
        get() = SshTarget(
            host = host.trim(),
            port = port.trim().toIntOrNull() ?: 22,
            username = username.trim().ifEmpty { "root" },
            identity = identity,
        )

    /** "Add router": a blank form under a new identity. */
    /** The router was renamed from the System screen: what the board says follows. */
    fun hostnameChanged(hostname: String) { board = board?.copy(hostname = hostname) }

    fun startNew() {
        identity = RouterEntity.newIdentity()
        keyPem = null
        password = ""
        sameAddress = emptyList()
    }

    /** Open a saved router: its address, its user, and its identity so its own pins apply. */
    fun startSaved(entity: RouterEntity) {
        identity = entity.identity
        host = entity.host
        port = entity.port.toString()
        username = entity.username
        sameAddress = emptyList()
    }

    val routerName: String
        get() = board?.hostname?.takeIf { it.isNotBlank() }
            ?: board?.model?.takeIf { it.isNotBlank() }
            ?: host

    fun connect(onFirstContact: () -> Unit, onConnected: () -> Unit, onKeyChanged: () -> Unit) {
        if (busy) return
        val t = target
        connectBlock(t.host)?.let { error = it; return }
        scope.launch {
            busy = true
            error = null
            try {
                val presented = client.probeHostKey(t)
                probed = presented
                val saved = hostKeys.saved(t, presented.type)
                when {
                    saved == null -> {
                        // Nothing pinned under this identity. Before calling it a first
                        // contact, ask whether a saved router at this address already holds
                        // this very key: then it is that router, typed in again, and it
                        // must not be saved twice — nor asked to confirm a fingerprint it
                        // confirmed once already.
                        val here = runCatching {
                            WrtRuntime.db.routers().atAddress(t.host, t.port, t.username)
                        }.getOrDefault(emptyList())
                        val twin = twinOf(presented, here) { hostKeys.saved(it.sshTarget, presented.type) }
                        if (twin != null) {
                            identity = twin.identity
                            openSession(target)
                            onConnected()
                        } else {
                            sameAddress = here.filter { it.identity != identity }.map { it.name }
                            onFirstContact()
                        }
                    }
                    saved.base64 == presented.base64 -> {
                        openSession(t)
                        onConnected()
                    }
                    else -> {
                        keyChange = SshException.HostKeyChanged(t, saved, presented)
                        onKeyChanged()
                    }
                }
            } catch (e: SshException) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    /** "Yes, that's my router" — pin the key, then authenticate for the first time. */
    fun confirmFirstContact(onConnected: () -> Unit, onFailed: () -> Unit) {
        val key = probed ?: return
        if (busy) return
        val t = target
        scope.launch {
            busy = true
            error = null
            hostKeys.trust(t, key)
            try {
                openSession(t)
                onConnected()
            } catch (e: SshException) {
                error = friendly(e)
                onFailed()
            } finally {
                busy = false
            }
        }
    }

    /** "Not mine — go back" — drop anything pinned for this target. */
    fun rejectFirstContact() {
        hostKeys.forget(target)
        probed = null
    }

    /** The interstitial's 3 s hold completed — replace the pinned key and reconnect. */
    fun trustChangedKey(onConnected: () -> Unit, onFailed: () -> Unit) {
        val change = keyChange ?: return
        if (busy) return
        scope.launch {
            busy = true
            error = null
            hostKeys.trust(change.target, change.presented)
            WrtRuntime.session?.clearBlock()
            try {
                openSession(change.target)
                keyChange = null
                onConnected()
            } catch (e: SshException) {
                error = friendly(e)
                onFailed()
            } finally {
                busy = false
            }
        }
    }

    fun dropChangedKey() {
        keyChange = null
    }

    private fun authProvider(): suspend () -> SshAuth {
        val pem = if (password.isEmpty()) keyPem else null
        if (pem != null) return { SshAuth.PrivateKey(pem.copyOf()) }
        val secret = password.toCharArray()
        return { SshAuth.Password(secret.copyOf()) }
    }

    private suspend fun openSession(t: SshTarget) {
        val session = RouterSession(t, client, credentials = authProvider())
        val live = session.ensureConnected()
        board = Parsers.board(live.exec(Commands.BOARD).requireOk(Commands.BOARD).stdout)
        WrtRuntime.session?.takeIf { it !== session }?.let { old -> runCatching { old.disconnect() } }
        WrtRuntime.session = session
        persist(t)
    }

    /** Saves the router and the sealed password so the next launch skips onboarding. */
    private suspend fun persist(t: SshTarget) {
        runCatching {
            val dao = WrtRuntime.db.routers()
            val existing = dao.byIdentity(identity)
            dao.upsert(
                RouterEntity(
                    id = existing?.id ?: 0,
                    name = savedName(existing?.name, routerName),
                    host = t.host,
                    port = t.port,
                    username = t.username,
                    model = board?.model.orEmpty(),
                    summary = board?.summary.orEmpty(),
                    credential = if (keyPem == null) WrtRuntime.vault.seal(password.toByteArray()) else null,
                    privateKey = keyPem?.let { WrtRuntime.vault.seal(it) },
                    lastSeenEpoch = System.currentTimeMillis() / 1000,
                    identity = identity,
                ).keptAcrossReconnect(existing)
            )
        }
    }

    /**
     * Screen 03's "Install the app's key": generate ed25519 on the phone, append the public
     * half to dropbear's authorized_keys over the existing session, then PROVE the key works
     * by opening a fresh key-auth connection before the password is discarded.
     */
    fun installAppKey(onDone: (Boolean) -> Unit) {
        if (busy) return
        val t = target
        scope.launch {
            busy = true
            error = null
            try {
                val live = WrtRuntime.session ?: throw SshException.Disconnected()
                val generated = SshKeys.generateEd25519()
                live.exec(Commands.installKey(generated.publicLine), timeoutMs = 10_000)
                    .requireOk("install authorized key")
                val keySession = RouterSession(t, client, credentials = {
                    SshAuth.PrivateKey(generated.privatePem.copyOf())
                })
                keySession.ensureConnected()
                runCatching { live.disconnect() }
                WrtRuntime.session = keySession
                keyPem = generated.privatePem
                password = ""
                persist(t)
                onDone(true)
            } catch (e: SshException) {
                error = friendly(e)
                onDone(false)
            } finally {
                busy = false
            }
        }
    }

    companion object {

        /**
         * The saved router, among those at this address, that [presented] belongs to — or
         * null when none holds this key, which makes the router in hand a new one.
         *
         * Same key, same router: a Deco that was added once and is being typed in again is
         * found here and reused. A different key is a different router (or the same board
         * after a reflash, which the first-contact screen says out loud), and address alone
         * is never enough to say otherwise.
         */
        fun twinOf(
            presented: HostKey,
            atAddress: List<RouterEntity>,
            pinned: (RouterEntity) -> HostKey?,
        ): RouterEntity? = atAddress.firstOrNull { pinned(it)?.base64 == presented.base64 }

        /**
         * The name to write for a router that is already saved.
         *
         * A name the user typed is theirs; the hostname is only a starting suggestion for a
         * router nothing is saved for yet. This used to write the derived name every time,
         * so reconnecting silently undid a rename — most visibly after a subnet move, where
         * reconnecting is the only way back in. The cost of the rule is a name that stays
         * behind if the router's hostname later changes, which the router list's Edit action
         * fixes in two taps; an overwritten rename could not be fixed at all.
         */
        fun savedName(existing: String?, derived: String): String =
            existing?.trim()?.takeIf { it.isNotEmpty() } ?: derived

        /**
         * A reconnect rewrites the row from what the router just said, and is not a reason to
         * forget what only this app knows about it: which mesh primary it belongs to, the
         * profile its nodes are built from, and the group it is filed under on the list. Every
         * column that is written from the app side, not the router side, has to be listed here,
         * or the REPLACE upsert quietly nulls it on the next connect.
         */
        fun RouterEntity.keptAcrossReconnect(existing: RouterEntity?): RouterEntity =
            if (existing == null) this else copy(
                meshPrimary = existing.meshPrimary,
                meshBackhaul = existing.meshBackhaul,
                meshSnapshot = existing.meshSnapshot,
                meshMac = existing.meshMac,
                meshProfile = existing.meshProfile,
                groupName = existing.groupName,
            )

        /**
         * Why Connect cannot proceed, or null.
         *
         * The address is the only thing genuinely required. An EMPTY PASSWORD is legitimate
         * and must not be blocked: a freshly flashed OpenWrt has no root password at all, and
         * an empty one is exactly what `ssh root@192.168.1.1` sends. Blocking it here made
         * the app unable to reach the routers most in need of setting up.
         */
        fun connectBlock(host: String): String? =
            if (host.isEmpty()) "Enter the router's address." else null
    }

    private fun friendly(e: SshException): String = when (e) {
        is SshException.Unreachable -> "Can't reach ${e.target.label} — check the address and that you're on the router's network."
        is SshException.Timeout -> "The router didn't answer in time."
        is SshException.AuthFailed -> "The router rejected that password."
        is SshException.CommandFailed -> "Signed in, but a setup command failed: ${e.stderr.ifEmpty { "exit ${e.exitCode}" }}"
        is SshException.UnknownHostKey -> "This router isn't recognised yet."
        is SshException.HostKeyChanged -> "This router's key changed — confirm it before connecting."
        is SshException.Disconnected -> "The connection dropped while setting up."
    }
}
