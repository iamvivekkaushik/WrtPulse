package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.AuthorizedKey
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.DropbearAuth
import com.vivekkaushik.wrtpulse.ops.Parsers

/**
 * dropbear's authorized_keys — the list of identities that can log into this router.
 *
 * The app is holding one of those entries, which makes this file different from every other
 * one it edits: the row it must not delete is the row it is standing on. [removalBlock] is
 * where that is kept out of a swipe.
 */
class SshKeyStore(
    private val session: RouterSession,
    /**
     * The app's own public line, derived from the stored private key. Null when this router
     * was added with a password and has no app key yet.
     */
    private val appPublicLine: String?,
) {

    val keys = mutableStateListOf<AuthorizedKey>()

    var auth by mutableStateOf(DropbearAuth()); private set
    var fileMode by mutableStateOf<String?>(null); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** True when the key this app authenticates with is actually on the router. */
    val appKeyInstalled: Boolean get() = keys.any { it.isAppKey }

    val hasAppKey: Boolean get() = appPublicLine != null

    suspend fun load() {
        loading = true
        try {
            val out = session.exec(Commands.SSH_KEYS, timeoutMs = 30_000)
            val sections = Parsers.sections(out.stdout)
            val list = Parsers.authorizedKeys(sections["keys"].orEmpty(), appPublicLine)
            keys.clear(); keys.addAll(list)
            auth = Parsers.dropbearAuth(sections["dropbear"].orEmpty())
            fileMode = sections["perms"].orEmpty().trim().lines()
                .firstOrNull { it.isNotBlank() }?.trim()?.substringBefore(' ')
            error = null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read authorized_keys: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** Appends a pasted public key. Idempotent — installing twice adds one line. */
    suspend fun add(line: String): String {
        val parsed = Commands.parsePublicKey(line)
            ?: return "Failed: that is not a public key line. Paste the contents of a .pub " +
                "file — it starts with ssh-ed25519 or ssh-rsa."
        val (type, blob, comment) = parsed
        if (keys.any { it.blob == blob }) return "That key is already installed."
        busy = true
        return try {
            val rebuilt = listOf(type, blob, comment).filter { it.isNotBlank() }.joinToString(" ")
            val out = session.exec(Commands.installKey(rebuilt), timeoutMs = 30_000)
            load()
            if (out.ok && keys.any { it.blob == blob }) {
                "Installed ${Parsers.keyFingerprint(blob).take(20)}…"
            } else {
                "Failed: ${out.stderr.trim().ifEmpty { "the key did not land in the file" }}"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    /**
     * Drops one key. Re-reads afterwards and confirms it is gone, because a write to the
     * file that decides who can log in is not something to take on trust.
     */
    suspend fun remove(key: AuthorizedKey): String {
        removalBlock(key, keys.size, auth)?.let { return "Failed: $it" }
        busy = true
        return try {
            val out = session.exec(Commands.removeKey(key.blob), timeoutMs = 30_000)
            load()
            if (!out.ok) {
                "Failed: ${out.stderr.trim().ifEmpty { "exit ${out.exitCode}" }}"
            } else if (keys.any { it.blob == key.blob }) {
                "Failed: the key is still in the file."
            } else {
                "Removed ${key.comment.ifBlank { key.shortType }}"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    /**
     * Installs the app's own key on a router that was added with a password.
     *
     * Mirrors onboarding: the key goes in, and nothing is claimed until it has been used to
     * authenticate. Here the proof is the reload — if the key were bad the next command
     * would still work on the existing session, so the caller is told exactly what was and
     * was not established.
     */
    suspend fun installAppKey(): String {
        val line = appPublicLine
            ?: return "Failed: this router has no app key stored. Re-add it from the router " +
                "list to generate one."
        if (appKeyInstalled) return "The app's key is already installed."
        return add(line).let {
            if (it.startsWith("Failed")) it else "Installed the app's key"
        }
    }

    companion object {

        /**
         * Non-null means the key is not offered for deletion, and this is why.
         *
         * dropbear will happily remove any of these: nothing on the router knows that one of
         * these lines is how the phone in your hand gets in. The app does.
         */
        fun removalBlock(key: AuthorizedKey, total: Int, auth: DropbearAuth): String? = when {
            key.isAppKey ->
                "This is the key WrtPulse is signed in with. Removing it ends this session " +
                    "and the app cannot reconnect."
            total <= 1 && !auth.passwordsAccepted ->
                "This is the only key, and dropbear is not accepting passwords. Removing it " +
                    "locks everyone out of this router until you reach it physically."
            else -> null
        }

        /** Removable, but the user should read a sentence first. */
        fun removalWarning(total: Int, auth: DropbearAuth): String? = when {
            total <= 1 && auth.passwordsAccepted ->
                "That was the last key. Password login is still enabled, so you are not " +
                    "locked out — but the app will fall back to a password."
            else -> null
        }

        /**
         * Why the app shows the password-auth setting but does not flip it.
         *
         * Changing it needs `/etc/init.d/dropbear restart`, which ends the session running
         * the command — the same reason [ServiceStore] refuses to restart dropbear from a
         * list row. And getting it wrong locks you out of the router for good. The commands
         * are shown instead, to be run in the Terminal where the user can see what happens.
         */
        const val PASSWORD_AUTH_NOTE =
            "Turning passwords off takes a dropbear restart, which ends this session — and " +
                "if the key does not work you are locked out until you can reach the router " +
                "physically. The app shows the commands rather than running them for you."
    }
}
