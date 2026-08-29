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
        )

    val routerName: String
        get() = board?.hostname?.takeIf { it.isNotBlank() }
            ?: board?.model?.takeIf { it.isNotBlank() }
            ?: host

    fun connect(onFirstContact: () -> Unit, onConnected: () -> Unit, onKeyChanged: () -> Unit) {
        if (busy) return
        val t = target
        if (t.host.isEmpty()) {
            error = "Enter the router's address."
            return
        }
        if (password.isEmpty()) {
            error = "Enter the password."
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                val presented = client.probeHostKey(t)
                probed = presented
                val saved = hostKeys.saved(t, presented.type)
                when {
                    saved == null -> onFirstContact()
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

    private suspend fun openSession(t: SshTarget) {
        val secret = password.toCharArray()
        val session = RouterSession(t, client, credentials = { SshAuth.Password(secret.copyOf()) })
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
            val existing = dao.find(t.host, t.port, t.username)
            dao.upsert(
                RouterEntity(
                    id = existing?.id ?: 0,
                    name = routerName,
                    host = t.host,
                    port = t.port,
                    username = t.username,
                    model = board?.model.orEmpty(),
                    summary = board?.summary.orEmpty(),
                    credential = WrtRuntime.vault.seal(password.toByteArray()),
                    lastSeenEpoch = System.currentTimeMillis() / 1000,
                )
            )
        }
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
