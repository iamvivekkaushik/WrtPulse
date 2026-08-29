package com.vivekkaushik.wrtpulse.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HostKeyStoreTest {

    private fun store() = HostKeyStore(File.createTempFile("known-hosts", ".txt").apply { delete() })
    private val target = SshTarget("192.168.2.1")
    private fun key(type: String, body: String) = HostKey(type, body, HostKeyStore.fingerprint(body.toByteArray()))

    @Test
    fun `first contact is unknown, never silently trusted`() {
        val store = store()
        val presented = key("ssh-ed25519", "AAAAC3Nza")
        val error = runCatching { store.verify(target, presented) }.exceptionOrNull()
        assertTrue("$error", error is SshException.UnknownHostKey)
        assertNull(store.saved(target, "ssh-ed25519"))
    }

    @Test
    fun `trusted key verifies and a swapped key of the same type is blocked`() {
        val store = store()
        val trusted = key("ssh-ed25519", "AAAAC3Nza")
        store.trust(target, trusted)
        store.verify(target, trusted)

        val impostor = key("ssh-ed25519", "AAAAC3EVIL")
        val error = runCatching { store.verify(target, impostor) }.exceptionOrNull()
        assertTrue("$error", error is SshException.HostKeyChanged)
        assertEquals(trusted, (error as SshException.HostKeyChanged).saved)
    }

    /**
     * Regression: a phone without BouncyCastle negotiates an RSA host key while a desktop
     * negotiates ed25519 for the same router. Pinning one key per host turned that into a
     * false "identity changed" alarm — the loudest screen in the app.
     */
    @Test
    fun `keys are pinned per algorithm, so a different type is first contact not a mismatch`() {
        val store = store()
        val ed = key("ssh-ed25519", "AAAAC3Nza")
        store.trust(target, ed)

        val rsa = key("ssh-rsa", "AAAAB3Nza")
        val error = runCatching { store.verify(target, rsa) }.exceptionOrNull()
        assertTrue("expected first contact for the new type, got $error", error is SshException.UnknownHostKey)

        store.trust(target, rsa)
        store.verify(target, ed)
        store.verify(target, rsa)
        assertEquals(2, store.savedAll(target).size)
        assertTrue(store.isKnown(target))
    }

    @Test
    fun `forget drops every algorithm for the target and survives a reload`() {
        val file = File.createTempFile("known-hosts", ".txt").apply { delete() }
        HostKeyStore(file).apply {
            trust(target, key("ssh-ed25519", "AAAAC3Nza"))
            trust(target, key("ssh-rsa", "AAAAB3Nza"))
            trust(SshTarget("192.168.2.2"), key("ssh-ed25519", "AAAAOTHER"))
        }
        // Reload from disk: persistence must round-trip.
        val reloaded = HostKeyStore(file)
        assertEquals(2, reloaded.savedAll(target).size)
        reloaded.forget(target)
        assertTrue(reloaded.savedAll(target).isEmpty())
        assertTrue(reloaded.isKnown(SshTarget("192.168.2.2")))
    }

    @Test
    fun `fingerprints match the openssh form shown in the ui`() {
        // ssh-keygen prints base64 without padding.
        val fp = HostKeyStore.fingerprint("hello".toByteArray())
        assertTrue(fp.startsWith("SHA256:"))
        assertTrue("must not be padded", !fp.endsWith("="))
        assertEquals("SHA256:LPJNul+wow4m6Dsqxbning8mCepOJTpx9Rl93y3sQNM".length, fp.length)
    }
}
