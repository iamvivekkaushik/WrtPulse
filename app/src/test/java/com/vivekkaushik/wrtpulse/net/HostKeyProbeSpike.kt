package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Phase-0 spike, JVM-side. Skipped unless a host is supplied:
 *
 *   WRTPULSE_HOST=192.168.1.1 ./gradlew testDebugUnitTest --tests '*HostKeyProbeSpike*' -i
 *
 * Proves three things against a real router before any UI work depends on them: the phone-side
 * library completes a key exchange with dropbear, we can read the host key, and we do it
 * without sending a credential.
 */
class HostKeyProbeSpike {

    @Test
    fun `probe reads the host key without authenticating`() = runBlocking {
        val host = System.getenv("WRTPULSE_HOST")
        assumeTrue("set WRTPULSE_HOST to run this spike", !host.isNullOrBlank())

        val port = System.getenv("WRTPULSE_PORT")?.toIntOrNull() ?: 22
        val user = System.getenv("WRTPULSE_USER") ?: "root"
        val target = SshTarget(host!!, port, user)

        val store = HostKeyStore(File.createTempFile("wrtpulse-known-hosts", ".txt"))
        val key = JschSshClient(store).probeHostKey(target)

        println("=== host key for ${target.label} ===")
        println("type:        ${key.type}")
        println("fingerprint: ${key.sha256Fingerprint}")

        check(key.sha256Fingerprint.startsWith("SHA256:")) { "unexpected fingerprint form" }
        check(key.base64.isNotBlank()) { "empty key" }

        // First contact must be refused until the user confirms.
        val verdict = runCatching { store.verify(target, key) }.exceptionOrNull()
        check(verdict is SshException.UnknownHostKey) { "expected UnknownHostKey, got $verdict" }

        // After explicit trust, the same key verifies and a different one is blocked.
        store.trust(target, key)
        store.verify(target, key)
        val changed = runCatching {
            store.verify(target, key.copy(base64 = "AAAA${key.base64}"))
        }.exceptionOrNull()
        check(changed is SshException.HostKeyChanged) { "expected HostKeyChanged, got $changed" }
        println("TOFU store behaved correctly (unknown -> trusted -> changed blocked)")
    }
}
