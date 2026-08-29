package com.vivekkaushik.wrtpulse.net

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Phase-0 spike on real Android hardware — the JVM version proves nothing about the platform's
 * crypto providers, which is where an ed25519 host key actually gets accepted or rejected.
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.wrtHost=192.168.2.1
 */
class HostKeyProbeAndroidTest {

    @Test
    fun probeReadsHostKeyOnDevice() { runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("wrtHost")
        assumeTrue("pass -P…wrtHost=<ip> to run this spike", !host.isNullOrBlank())

        val target = SshTarget(
            host = host!!,
            port = args.getString("wrtPort")?.toIntOrNull() ?: 22,
            username = args.getString("wrtUser") ?: "root",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HostKeyStore(File(context.cacheDir, "spike-known-hosts"))

        val started = System.currentTimeMillis()
        val key = JschSshClient(store).probeHostKey(target)
        val elapsed = System.currentTimeMillis() - started

        println("WRTSPIKE type=${key.type} fp=${key.sha256Fingerprint} kexMs=$elapsed")
        assertTrue("expected a fingerprint", key.sha256Fingerprint.startsWith("SHA256:"))
        assertTrue("expected a key", key.base64.isNotBlank())

        // The Keystore seal is the other platform-dependent piece; exercise it here too.
        val crypto = KeystoreCrypto("wrtpulse.spike.${System.currentTimeMillis()}")
        val secret = "hunter2-not-a-real-password".toByteArray()
        val sealed = crypto.seal(secret)
        assertTrue("sealed blob must not contain plaintext",
            !String(sealed, Charsets.ISO_8859_1).contains("hunter2"))
        assertTrue("round trip must match", crypto.open(sealed).contentEquals(secret))
        println("WRTSPIKE keystore seal/open ok (${sealed.size} bytes)")
    }
    }
}
