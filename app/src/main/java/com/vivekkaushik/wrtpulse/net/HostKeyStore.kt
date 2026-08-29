package com.vivekkaushik.wrtpulse.net

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-on-first-use store for router host keys.
 *
 * The rule the app promises the user in onboarding: a key is saved once, on first contact.
 * If a later handshake presents a different key we refuse the connection and hand the caller
 * both keys so the interstitial can show them side by side. Nothing auto-trusts.
 */
class HostKeyStore(private val file: File) {

    private val entries = linkedMapOf<String, HostKey>()
    private var loaded = false

    // One entry per (host, port, key type) — the same rule known_hosts follows. A router can
    // legitimately offer ed25519 and RSA, and which one is negotiated depends on the client's
    // available algorithms, so pinning a single key per host produces false mismatches.
    private fun key(target: SshTarget, type: String) = "${target.host}:${target.port}:$type"

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        file.forEachLine { line ->
            val parts = line.split(' ')
            if (parts.size >= 4) {
                entries[parts[0]] = HostKey(parts[1], parts[2], parts[3])
            }
        }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(
            entries.entries.joinToString("\n") { (k, v) ->
                "$k ${v.type} ${v.base64} ${v.sha256Fingerprint}"
            }
        )
    }

    /** The key pinned for this target and algorithm, if any. */
    @Synchronized
    fun saved(target: SshTarget, type: String): HostKey? {
        load()
        return entries[key(target, type)]
    }

    /** Every key pinned for this target, across algorithms. */
    @Synchronized
    fun savedAll(target: SshTarget): List<HostKey> {
        load()
        val prefix = "${target.host}:${target.port}:"
        return entries.entries.filter { it.key.startsWith(prefix) }.map { it.value }
    }

    @Synchronized
    fun isKnown(target: SshTarget): Boolean = savedAll(target).isNotEmpty()

    /** Records the key for a target. Overwrites — call only after the user has confirmed. */
    @Synchronized
    fun trust(target: SshTarget, hostKey: HostKey) {
        load()
        entries[key(target, hostKey.type)] = hostKey
        persist()
    }

    /** Drops every pinned key for this target — used by "Not mine" and by re-pairing. */
    @Synchronized
    fun forget(target: SshTarget) {
        load()
        val prefix = "${target.host}:${target.port}:"
        val removed = entries.keys.filter { it.startsWith(prefix) }
        if (removed.isNotEmpty()) {
            removed.forEach(entries::remove)
            persist()
        }
    }

    /**
     * @throws SshException.UnknownHostKey on first contact
     * @throws SshException.HostKeyChanged when the saved key doesn't match
     */
    fun verify(target: SshTarget, presented: HostKey) {
        val saved = saved(target, presented.type)
            ?: throw SshException.UnknownHostKey(target, presented)
        if (saved.base64 != presented.base64) {
            throw SshException.HostKeyChanged(target, saved, presented)
        }
    }

    companion object {
        /** "SHA256:Ml3f9K2vQ8…" — base64 without padding, matching OpenSSH and the mockups. */
        fun fingerprint(rawKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
