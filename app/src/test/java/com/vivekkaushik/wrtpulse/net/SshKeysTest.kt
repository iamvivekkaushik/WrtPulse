package com.vivekkaushik.wrtpulse.net

import com.vivekkaushik.wrtpulse.ops.Commands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeysTest {

    @Test
    fun `generated keypair has openssh shapes`() {
        val kp = SshKeys.generateEd25519("wrtpulse")
        val priv = String(kp.privatePem)
        assertTrue(priv.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(priv.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        val parts = kp.publicLine.split(" ")
        assertEquals(3, parts.size)
        assertEquals("ssh-ed25519", parts[0])
        assertEquals("wrtpulse", parts[2])
        assertTrue(parts[1].startsWith("AAAAC3NzaC1lZDI1NTE5"))
    }

    @Test
    fun `two generations differ`() {
        val a = SshKeys.generateEd25519()
        val b = SshKeys.generateEd25519()
        assertTrue(a.publicLine != b.publicLine)
    }

    @Test
    fun `install command is idempotent and quotes the key`() {
        val cmd = Commands.installKey("ssh-ed25519 AAAAC3Nza wrtpulse")
        assertEquals(
            "mkdir -p /etc/dropbear && touch /etc/dropbear/authorized_keys && " +
                "(grep -qF 'ssh-ed25519 AAAAC3Nza wrtpulse' /etc/dropbear/authorized_keys || " +
                "echo 'ssh-ed25519 AAAAC3Nza wrtpulse' >> /etc/dropbear/authorized_keys) && " +
                "chmod 600 /etc/dropbear/authorized_keys",
            cmd,
        )
    }
}
