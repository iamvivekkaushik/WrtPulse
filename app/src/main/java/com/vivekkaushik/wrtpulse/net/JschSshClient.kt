package com.vivekkaushik.wrtpulse.net

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import com.jcraft.jsch.HostKey as JschHostKey
import com.jcraft.jsch.HostKeyRepository as JschHostKeyRepository

/**
 * JSch-backed transport (mwiede fork).
 *
 * Host keys are checked inside the key exchange through a custom [JschHostKeyRepository]:
 * an unknown or changed key fails the handshake *before* JSch reaches the auth phase, so a
 * password is never put on the wire to a router we don't recognise.
 */
class JschSshClient(
    private val hostKeys: HostKeyStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SshClient {

    override suspend fun probeHostKey(target: SshTarget): HostKey = withContext(io) {
        val captor = CapturingRepository(alwaysReject = true)
        val session = newSession(target, captor).second
        try {
            session.connect(PROBE_TIMEOUT_MS)
            // A server with no host key check would land here; hang up and report what we saw.
            captor.presented ?: error("no host key presented by ${target.label}")
        } catch (e: JSchException) {
            captor.presented ?: throw e.toSshException(target)
        } finally {
            runCatching { session.disconnect() }
        }
    }

    override suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        connectTimeoutMs: Long,
    ): SshConnection = withContext(io) {
        val captor = CapturingRepository(alwaysReject = false, store = hostKeys, target = target)
        val (jsch, session) = newSession(target, captor)
        when (auth) {
            is SshAuth.Password -> {
                val plain = String(auth.password)
                session.setPassword(plain)
                session.userInfo = PasswordUserInfo(plain)
            }
            is SshAuth.PrivateKey -> jsch.addIdentity(
                target.label,
                auth.pem,
                null,
                auth.passphrase?.let { String(it).toByteArray() },
            )
        }
        try {
            session.connect(connectTimeoutMs.toInt())
        } catch (e: JSchException) {
            runCatching { session.disconnect() }
            captor.rejection?.let { throw it }
            throw e.toSshException(target)
        }
        val key = captor.presented
            ?: error("connected to ${target.label} without a host key")
        JschConnection(target, key, session, io)
    }

    private fun newSession(target: SshTarget, repo: JschHostKeyRepository): Pair<JSch, Session> {
        val jsch = JSch()
        jsch.hostKeyRepository = repo
        val session = jsch.getSession(target.username, target.host, target.port).apply {
            setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            setServerAliveInterval(15_000)
            setServerAliveCountMax(3)
        }
        return jsch to session
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 8_000
    }
}

/**
 * Sees the server key during KEX. In probe mode it always rejects (so authentication never
 * runs); otherwise it defers to [HostKeyStore] and records why it said no.
 */
private class CapturingRepository(
    private val alwaysReject: Boolean,
    private val store: HostKeyStore? = null,
    private val target: SshTarget? = null,
) : JschHostKeyRepository {

    @Volatile var presented: HostKey? = null
    @Volatile var rejection: SshException? = null

    override fun check(host: String?, key: ByteArray?): Int {
        val raw = key ?: return JschHostKeyRepository.NOT_INCLUDED
        val seen = HostKey(
            type = runCatching { JschHostKey(host, raw).type }.getOrDefault("ssh-unknown"),
            base64 = Base64.getEncoder().encodeToString(raw),
            sha256Fingerprint = HostKeyStore.fingerprint(raw),
        )
        presented = seen
        if (alwaysReject) return JschHostKeyRepository.NOT_INCLUDED

        val t = target ?: return JschHostKeyRepository.NOT_INCLUDED
        val saved = store?.saved(t, seen.type)
        return when {
            saved == null -> {
                rejection = SshException.UnknownHostKey(t, seen)
                JschHostKeyRepository.NOT_INCLUDED
            }
            saved.base64 != seen.base64 -> {
                rejection = SshException.HostKeyChanged(t, saved, seen)
                JschHostKeyRepository.CHANGED
            }
            else -> JschHostKeyRepository.OK
        }
    }

    // Trust is granted explicitly through HostKeyStore, never as a side effect of connecting.
    override fun add(hostkey: JschHostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "wrtpulse"
    override fun getHostKey(): Array<JschHostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<JschHostKey> = emptyArray()
}

/** Answers dropbear's keyboard-interactive prompt with the same password. */
private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String = password
    override fun promptPassword(message: String?) = true
    override fun promptPassphrase(message: String?) = false
    override fun promptYesNo(message: String?) = false
    override fun showMessage(message: String?) = Unit
    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?,
    ): Array<String>? = prompt?.map { password }?.toTypedArray()
}

private fun JSchException.toSshException(target: SshTarget): SshException {
    val text = message.orEmpty()
    return when {
        cause is UnknownHostException || cause is NoRouteToHostException ->
            SshException.Unreachable(target, this)
        cause is SocketTimeoutException || text.contains("timeout", true) ->
            SshException.Timeout("connecting to ${target.label}")
        text.contains("Auth fail", true) || text.contains("Auth cancel", true) ||
            text.contains("USERAUTH", true) -> SshException.AuthFailed(target, this)
        text.contains("connection is closed", true) -> SshException.Disconnected(this)
        cause != null -> SshException.Unreachable(target, this)
        else -> SshException.Disconnected(this)
    }
}

private class JschConnection(
    override val target: SshTarget,
    override val hostKey: HostKey,
    private val session: Session,
    private val io: CoroutineDispatcher,
) : SshConnection {

    override val isConnected: Boolean get() = session.isConnected

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult = run(command, null, timeoutMs)

    override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): ExecResult =
        run(command, input, timeoutMs)

    private suspend fun run(command: String, input: ByteArray?, timeoutMs: Long): ExecResult = withContext(io) {
        if (!session.isConnected) throw SshException.Disconnected()
        val channel = session.openChannel("exec") as ChannelExec
        val stderr = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            channel.setErrStream(stderr)
            // JSch pumps the stream to the remote stdin on its own thread and sends EOF when
            // it runs dry — which is what lets a `cat > file` on the far end finish.
            if (input != null) channel.setInputStream(ByteArrayInputStream(input))
            val stdout = channel.inputStream
            channel.connect(timeoutMs.toInt())
            val out = stdout.readBytes().toString(Charsets.UTF_8)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) throw SshException.Timeout(command)
                Thread.sleep(5)
            }
            ExecResult(out, stderr.toString(Charsets.UTF_8.name()), channel.exitStatus)
        } catch (e: JSchException) {
            throw e.toSshException(target)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    override suspend fun ping(): Long {
        val started = System.nanoTime()
        exec(":", timeoutMs = 5_000)
        return (System.nanoTime() - started) / 1_000_000
    }

    override fun stream(command: String): Flow<String> = flow {
        if (!session.isConnected) throw SshException.Disconnected()
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            val reader = channel.inputStream.bufferedReader()
            channel.connect(10_000)
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                currentCoroutineContext().ensureActive()
                emit(line)
            }
        } catch (e: JSchException) {
            throw e.toSshException(target)
        } finally {
            runCatching { channel.disconnect() }
        }
    }.flowOn(io)

    override suspend fun openShell(cols: Int, rows: Int): SshShell = withContext(io) {
        if (!session.isConnected) throw SshException.Disconnected()
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color", cols, rows, 0, 0)
        // local -> remote keystrokes
        val toShell = PipedOutputStream()
        channel.setInputStream(PipedInputStream(toShell, 8 * 1024))
        // remote -> local output (call the getter once; JSch builds the pipe on first use)
        val fromShell = channel.getInputStream()
        channel.connect(10_000)
        JschShell(channel, toShell, fromShell, io)
    }

    override fun close() {
        runCatching { session.disconnect() }
    }
}

private class JschShell(
    private val channel: ChannelShell,
    private val toShell: PipedOutputStream,
    private val fromShell: java.io.InputStream,
    private val io: CoroutineDispatcher,
) : SshShell {

    override val output: Flow<String> = flow {
        val buffer = ByteArray(4 * 1024)
        while (currentCoroutineContext().isActive) {
            val read = fromShell.read(buffer)
            if (read < 0) break
            if (read > 0) emit(String(buffer, 0, read, Charsets.UTF_8))
        }
    }.flowOn(io)

    override suspend fun write(text: String) = withContext(io) {
        toShell.write(text.toByteArray(Charsets.UTF_8))
        toShell.flush()
    }

    override suspend fun resize(cols: Int, rows: Int) = withContext(io) {
        channel.setPtySize(cols, rows, 0, 0)
    }

    override fun close() {
        runCatching { toShell.close() }
        runCatching { channel.disconnect() }
    }
}
