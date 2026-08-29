package com.vivekkaushik.wrtpulse.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM sealing backed by a hardware key in the Android Keystore.
 *
 * Secrets (router passwords, SSH private keys) never leave the app as plaintext: callers
 * hand us bytes, we return an opaque blob that only this app on this device can open.
 */
class KeystoreCrypto(private val alias: String = DEFAULT_ALIAS) {

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** [IV length][IV][ciphertext+tag] */
    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + body
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "empty blob" }
        val ivLen = blob[0].toInt()
        require(blob.size > 1 + ivLen) { "truncated blob" }
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val body = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        const val DEFAULT_ALIAS = "wrtpulse.credentials.v1"
    }
}
