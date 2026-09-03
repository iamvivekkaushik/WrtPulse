package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/** Transport contract. Screens and repositories only ever see this, never JSch types. */
interface SshClient {

    /**
     * Completes the key exchange, reads the server's host key and hangs up *before*
     * authenticating. Safe to call with no credentials — nothing secret is sent.
     */
    suspend fun probeHostKey(target: SshTarget): HostKey

    /**
     * Opens an authenticated connection. The host key is verified against [HostKeyStore]
     * first; on mismatch or first contact this throws before any credential is transmitted.
     */
    suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        connectTimeoutMs: Long = 12_000,
    ): SshConnection
}

interface SshConnection : Closeable {
    val target: SshTarget
    val hostKey: HostKey
    val isConnected: Boolean

    /** Runs a command to completion and collects both streams. */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): ExecResult

    /**
     * Runs a command with [input] on its stdin. This is how bytes get ONTO the router: the
     * exec channel carries stdin raw, so an archive goes up without an encoder — whereas
     * stdout comes back as text and needs one (see `Commands.BACKUP_READ`).
     */
    suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long = 60_000): ExecResult

    /** Round-trip time of a no-op command — the latency chip in the top bar. */
    suspend fun ping(): Long

    /** Long-lived line stream, e.g. `logread -f`. Cancelling the collector closes the channel. */
    fun stream(command: String): Flow<String>

    /** Interactive PTY for the terminal screen. */
    suspend fun openShell(cols: Int = 80, rows: Int = 24): SshShell
}

interface SshShell : Closeable {
    val output: Flow<String>
    suspend fun write(text: String)
    suspend fun resize(cols: Int, rows: Int)
}
