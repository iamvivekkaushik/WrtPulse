package com.vivekkaushik.wrtpulse.net

/** Everything needed to open one SSH connection. */
data class SshTarget(
    val host: String,
    val port: Int = 22,
    val username: String = "root",
) {
    val label: String get() = if (port == 22) host else "$host:$port"
}

/** How we authenticate. A password is held only for the life of the call that uses it. */
sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth {
        // data class equals/hashCode on CharArray compares identity; override so tests behave.
        override fun equals(other: Any?) = other is Password && password.contentEquals(other.password)
        override fun hashCode() = password.contentHashCode()
    }

    /** OpenSSH-format private key bytes (optionally passphrase-protected). */
    data class PrivateKey(val pem: ByteArray, val passphrase: CharArray? = null) : SshAuth {
        override fun equals(other: Any?) =
            other is PrivateKey && pem.contentEquals(other.pem) &&
                (passphrase?.contentEquals(other.passphrase ?: charArrayOf()) ?: (other.passphrase == null))
        override fun hashCode() = pem.contentHashCode()
    }
}

/** The server's public key as presented during the handshake. */
data class HostKey(
    val type: String,          // "ssh-ed25519", "ecdsa-sha2-nistp256", …
    val base64: String,        // raw key, base64
    val sha256Fingerprint: String, // "SHA256:Ml3f9K…" — the form shown in the UI
)

/** Result of a non-interactive command. */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    val ok: Boolean get() = exitCode == 0
    fun requireOk(command: String): ExecResult =
        if (ok) this else throw SshException.CommandFailed(command, exitCode, stderr.trim())
}

/** Every failure the transport can produce, so the UI never has to read a stack trace. */
sealed class SshException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(val target: SshTarget, cause: Throwable?) :
        SshException("Can't reach ${target.label}", cause)

    class AuthFailed(val target: SshTarget, cause: Throwable? = null) :
        SshException("Authentication rejected by ${target.label}", cause)

    /** First contact — caller must confirm the key before we continue (onboarding screen 02). */
    class UnknownHostKey(val target: SshTarget, val presented: HostKey) :
        SshException("Unrecognised host key for ${target.label}")

    /** The saved key no longer matches — hard block (screen 14). */
    class HostKeyChanged(val target: SshTarget, val saved: HostKey, val presented: HostKey) :
        SshException("Host key for ${target.label} changed")

    class CommandFailed(val command: String, val exitCode: Int, val stderr: String) :
        SshException("`$command` exited $exitCode${if (stderr.isEmpty()) "" else ": $stderr"}")

    class Disconnected(cause: Throwable? = null) : SshException("Connection lost", cause)

    class Timeout(val what: String) : SshException("Timed out: $what")
}
