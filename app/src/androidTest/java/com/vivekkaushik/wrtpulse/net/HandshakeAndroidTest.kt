package com.vivekkaushik.wrtpulse.net

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

/**
 * Proves the full key exchange — including host-key signature verification — completes on
 * Android, where D8 strips jsch's META-INF/versions/15 EdDSA classes.
 *
 * We authenticate with a deliberately wrong password: auth runs only after KEX and host-key
 * verification succeed, so `AuthFailed` is the *passing* outcome. No real credential is used.
 */
class HandshakeAndroidTest {

    private val log = mutableListOf<String>()

    @Test
    fun handshakeCompletesBeforeAuthentication() { runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("wrtHost")
        assumeTrue("pass -P…wrtHost=<ip> to run this spike", !host.isNullOrBlank())

        JSch.setLogger(object : Logger {
            override fun isEnabled(level: Int) = true
            override fun log(level: Int, message: String) {
                log += message
                Log.i("WRTSPIKE", message)
            }
        })

        val target = SshTarget(host!!, args.getString("wrtPort")?.toIntOrNull() ?: 22, "root")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = HostKeyStore(File(context.cacheDir, "handshake-known-hosts").apply { delete() })
        val client = JschSshClient(store)

        val key = client.probeHostKey(target)
        store.trust(target, key)
        Log.i("WRTSPIKE", "trusted ${key.type} ${key.sha256Fingerprint}")

        val wrongPassword = "wrtpulse-spike-not-a-real-password".toCharArray()
        val outcome = runCatching { client.connect(target, SshAuth.Password(wrongPassword)) }
        outcome.getOrNull()?.close()

        val error = outcome.exceptionOrNull()
        val negotiated = log.filter {
            it.contains("kex:", true) || it.contains("server:", true) || it.contains("client:", true)
        }
        Log.i("WRTSPIKE", "outcome=${error?.javaClass?.simpleName ?: "connected"} msg=${error?.message}")

        assertTrue(
            "handshake did not reach authentication — error=$error; negotiation log=$negotiated",
            error is SshException.AuthFailed,
        )
        Log.i("WRTSPIKE", "handshake OK: KEX + ed25519 host-key verification work on this device")
    }
    }
}
