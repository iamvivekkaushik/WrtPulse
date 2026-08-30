package com.vivekkaushik.wrtpulse.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ed25519 keygen must resolve through BouncyCastle on Android (the JDK15+ classes are stripped). */
@RunWith(AndroidJUnit4::class)
class KeyGenAndroidTest {

    @Test
    fun generatesAndReloadsEd25519() {
        val kp = SshKeys.generateEd25519("wrtpulse-test")
        assertTrue(String(kp.privatePem).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(kp.publicLine.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
        // The private key must load back the way connect() will feed it to JSch.
        val reloaded = KeyPair.load(JSch(), kp.privatePem, null)
        assertEquals(KeyPair.ED25519, reloaded.keyType)
        reloaded.dispose()
    }
}
