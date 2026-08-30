package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the top bar and the router list render. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val latencyMs: Long) : ConnectionState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState

    /** Host key mismatch — hard stop, routes to the interstitial. Never retried automatically. */
    data class Blocked(val reason: SshException.HostKeyChanged) : ConnectionState
    data class Failed(val error: SshException) : ConnectionState
}

/**
 * Owns one router's connection: opens it, keeps it warm, and retries with backoff. Connection
 * setup is serialised; commands run on independent SSH channels and may overlap.
 *
 * Reconnect follows the design's rule — amber is non-blocking and retries, red never does.
 */
class RouterSession(
    val target: SshTarget,
    private val client: SshClient,
    private val credentials: suspend () -> SshAuth,
    private val maxAttempts: Int = 5,
) {
    private val mutex = Mutex()
    private var connection: SshConnection? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val isConnected: Boolean get() = connection?.isConnected == true

    /** Opens the connection, or returns the live one. Backs off between attempts. */
    suspend fun ensureConnected(): SshConnection = mutex.withLock { connectLocked() }

    private suspend fun connectLocked(): SshConnection {
        connection?.takeIf { it.isConnected }?.let { return it }
        if (_state.value is ConnectionState.Blocked) {
            throw (_state.value as ConnectionState.Blocked).reason
        }
        var attempt = 0
        var lastError: SshException
        while (true) {
            attempt++
            _state.value =
                if (attempt == 1) ConnectionState.Connecting
                else ConnectionState.Reconnecting(attempt, maxAttempts)
            try {
                val fresh = client.connect(target, credentials())
                connection = fresh
                _state.value = ConnectionState.Connected(runCatching { fresh.ping() }.getOrDefault(0L))
                return fresh
            } catch (e: SshException.HostKeyChanged) {
                _state.value = ConnectionState.Blocked(e)
                throw e
            } catch (e: SshException.UnknownHostKey) {
                // First contact is a user decision, not a retry case.
                _state.value = ConnectionState.Failed(e)
                throw e
            } catch (e: SshException.AuthFailed) {
                _state.value = ConnectionState.Failed(e)
                throw e
            } catch (e: SshException) {
                lastError = e
                if (attempt >= maxAttempts) {
                    _state.value = ConnectionState.Failed(lastError)
                    throw lastError
                }
                delay(backoffMs(attempt))
            }
        }
    }

    /** Runs a command on the shared connection, reconnecting once if the link dropped. */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): ExecResult {
        val existing = connection?.takeIf { it.isConnected } ?: ensureConnected()
        return try {
            existing.exec(command, timeoutMs)
        } catch (e: SshException.Disconnected) {
            connection = null
            ensureConnected().exec(command, timeoutMs)
        }
    }

    /** Interactive PTY on the shared connection — the terminal screen. */
    suspend fun openShell(cols: Int = 48, rows: Int = 30): SshShell =
        (connection?.takeIf { it.isConnected } ?: ensureConnected()).openShell(cols, rows)

    /** Long-lived line stream (e.g. `logread -f`) on the shared connection. */
    suspend fun streamLines(command: String): kotlinx.coroutines.flow.Flow<String> =
        (connection?.takeIf { it.isConnected } ?: ensureConnected()).stream(command)

    /** Measures RTT and publishes it, so the latency chip stays honest about the live link. */
    suspend fun refreshLatency() {
        val live = connection?.takeIf { it.isConnected } ?: return
        try {
            _state.value = ConnectionState.Connected(live.ping())
        } catch (e: CancellationException) {
            throw e
        } catch (_: SshException) {
            connection = null
            _state.value = ConnectionState.Reconnecting(1, maxAttempts)
        }
    }

    /** After the user accepts a new key on the interstitial, the block is lifted here. */
    fun clearBlock() {
        if (_state.value is ConnectionState.Blocked) _state.value = ConnectionState.Idle
    }

    suspend fun disconnect() = mutex.withLock {
        connection?.close()
        connection = null
        _state.value = ConnectionState.Idle
    }

    /** 0.5 s, 1 s, 2 s, 4 s, capped at 8 s — fast enough to feel live, slow enough to not hammer. */
    private fun backoffMs(attempt: Int): Long = (500L shl (attempt - 1)).coerceAtMost(8_000L)
}
