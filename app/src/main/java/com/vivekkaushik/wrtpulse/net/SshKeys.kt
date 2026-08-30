package com.vivekkaushik.wrtpulse.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/** Generates the app's SSH identity. */
object SshKeys {

    class Generated(val privatePem: ByteArray, val publicLine: String)

    /**
     * A fresh ed25519 keypair: private key in OpenSSH PEM (what [SshAuth.PrivateKey] takes),
     * public key as one authorized_keys line. On Android the generator resolves to the
     * BouncyCastle implementation, same as the handshake signatures.
     */
    fun generateEd25519(comment: String = "wrtpulse"): Generated {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            // EdDSA keys only exist in the OpenSSH v1 container; the legacy PEM writer throws.
            val priv = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toByteArray()
            return Generated(priv, String(pub, Charsets.UTF_8).trim())
        } finally {
            kp.dispose()
        }
    }
}
