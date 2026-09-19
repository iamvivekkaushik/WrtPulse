package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A connection that only remembers what it was asked to run. */
private class RecordingConnection(override val target: SshTarget) : SshConnection {
    val execs = mutableListOf<String>()
    val inputs = mutableListOf<Pair<String, String>>()
    override val hostKey = HostKey("ssh-ed25519", "AAAA", "SHA256:x")
    override val isConnected = true
    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        execs += command
        return ExecResult("", "", 0)
    }
    override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): ExecResult {
        inputs += command to input.toString(Charsets.UTF_8)
        return ExecResult("", "", 0)
    }
    override suspend fun ping(): Long = 1
    override fun stream(command: String): Flow<String> = emptyFlow()
    override suspend fun openShell(cols: Int, rows: Int): SshShell = error("unused")
    override fun close() {}
}

class RouterSessionTest {

    private val target = SshTarget("192.168.0.1", identity = "primary")
    private val connection = RecordingConnection(target)
    private val client = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = connection.hostKey
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection = connection
    }
    private val session = RouterSession(target, client, { SshAuth.Password(CharArray(0)) })

    @Test
    fun `an ordinary command is one exec request`() = runBlocking {
        session.exec("uci show network")
        assertEquals(listOf("uci show network"), connection.execs)
        assertTrue(connection.inputs.isEmpty())
    }

    /** dropbear drops the connection on an exec request past 9000 bytes; the script goes on stdin instead. */
    @Test
    fun `a command past dropbear's exec limit goes over as a script on stdin`() = runBlocking {
        val script = buildString { repeat(400) { append("uci set wireless.wrtpulse_ap_$it.ssid='Home'\n") } }
        assertFalse(RouterSession.fitsExec(script))
        session.exec(script)
        assertTrue(connection.execs.isEmpty())
        assertEquals(listOf(RouterSession.STDIN_SCRIPT to script), connection.inputs)
        // The far shell drains stdin before it runs anything, so the script itself cannot be read by a command inside it.
        assertEquals("sh -c \"\$(cat)\"", RouterSession.STDIN_SCRIPT)
    }

    @Test
    fun `the limit is measured in bytes, the way dropbear counts it`() {
        assertTrue(RouterSession.fitsExec("x".repeat(RouterSession.MAX_EXEC_BYTES)))
        assertFalse(RouterSession.fitsExec("x".repeat(RouterSession.MAX_EXEC_BYTES + 1)))
        // Three bytes per character: a command of non-ASCII SSIDs runs out of room sooner than its length suggests.
        assertFalse(RouterSession.fitsExec("日".repeat(RouterSession.MAX_EXEC_BYTES / 3 + 1)))
    }
}
