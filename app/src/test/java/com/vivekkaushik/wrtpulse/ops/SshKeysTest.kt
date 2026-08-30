package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.SshKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val APP_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIJ8Yr7bT0kQe3Yv1KcXKxJ0mQqL5nR9wZ2aB4cD6eF7g"
private const val LAPTOP_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIHqW2xY4zL8mN0pQ6rS7tU9vW1xY3zA5bC7dE9fG1hI2"
private const val APP_LINE = "ssh-ed25519 $APP_BLOB wrtpulse"

private val FILE = """
    # added by hand
    ssh-ed25519 $APP_BLOB wrtpulse

    ssh-ed25519 $LAPTOP_BLOB vivek@laptop
""".trimIndent()

class PublicKeyParseTest {

    @Test
    fun `a normal ed25519 line splits into type, blob and comment`() {
        val (type, blob, comment) = Commands.parsePublicKey(APP_LINE)!!
        assertEquals("ssh-ed25519", type)
        assertEquals(APP_BLOB, blob)
        assertEquals("wrtpulse", comment)
    }

    @Test
    fun `a line with no comment is still a key`() {
        assertNotNull(Commands.parsePublicKey("ssh-ed25519 $APP_BLOB"))
    }

    @Test
    fun `unknown key types are refused rather than written into the access list`() {
        assertNull(Commands.parsePublicKey("ssh-dsa $APP_BLOB old"))
        assertNull(Commands.parsePublicKey("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertNull(Commands.parsePublicKey(""))
        assertNull(Commands.parsePublicKey("ssh-ed25519"))
    }

    /** The blob reaches a shell inside single quotes, so its alphabet is the guard. */
    @Test
    fun `a blob carrying shell syntax is not a blob`() {
        assertNull(Commands.parsePublicKey("ssh-ed25519 AAAA';reboot;' x"))
        assertNull(Commands.parsePublicKey("ssh-ed25519 \$(reboot) x"))
    }

    /**
     * One line is one key. A smuggled newline would append a second entry that nobody
     * agreed to, which is the whole risk of pasting into an access list.
     */
    @Test
    fun `a line with an embedded newline is refused outright`() {
        assertNull(Commands.parsePublicKey("ssh-ed25519 $APP_BLOB me\nssh-rsa $LAPTOP_BLOB them"))
    }

    @Test
    fun `comment punctuation that would reach a shell is stripped, not rejected`() {
        val (_, _, comment) = Commands.parsePublicKey("ssh-ed25519 $APP_BLOB vivek@lap';reboot")!!
        assertEquals("vivek@lapreboot", comment)
        assertFalse(comment.contains(";"))
        assertFalse(comment.contains("'"))
    }

    @Test
    fun `safePublicKeyLine agrees with the parser`() {
        assertTrue(Commands.safePublicKeyLine(APP_LINE))
        assertFalse(Commands.safePublicKeyLine("not a key"))
    }
}

class AuthorizedKeysTest {

    @Test
    fun `comments and blank lines are skipped`() {
        assertEquals(2, Parsers.authorizedKeys(FILE).size)
    }

    @Test
    fun `the app's own key is marked when its line is known`() {
        val keys = Parsers.authorizedKeys(FILE, APP_LINE)
        assertTrue(keys.first { it.blob == APP_BLOB }.isAppKey)
        assertFalse(keys.first { it.blob == LAPTOP_BLOB }.isAppKey)
    }

    @Test
    fun `with no app line nothing is claimed as the app's`() {
        assertTrue(Parsers.authorizedKeys(FILE).none { it.isAppKey })
    }

    /** The comment is cosmetic; the blob is the key, so matching is on the blob. */
    @Test
    fun `the app key is recognised even after its label was edited on the router`() {
        val relabelled = "ssh-ed25519 $APP_BLOB renamed-by-hand"
        assertTrue(Parsers.authorizedKeys(relabelled, APP_LINE).single().isAppKey)
    }

    @Test
    fun `a duplicated key is listed once`() {
        assertEquals(1, Parsers.authorizedKeys("$APP_LINE\n$APP_LINE").size)
    }

    @Test
    fun `an empty or unreadable file is no keys, not a crash`() {
        assertTrue(Parsers.authorizedKeys("").isEmpty())
        assertTrue(Parsers.authorizedKeys("cat: can't open: No such file").isEmpty())
    }

    @Test
    fun `the short type drops the protocol noise`() {
        assertEquals("ed25519", Parsers.authorizedKeys(APP_LINE).single().shortType)
        assertEquals(
            "rsa",
            Parsers.authorizedKeys("ssh-rsa $LAPTOP_BLOB x").single().shortType,
        )
    }
}

class KeyFingerprintTest {

    /**
     * OpenSSH hashes the DECODED blob. Hashing the base64 text would produce a
     * plausible-looking fingerprint that matches nothing the user can verify.
     */
    @Test
    fun `the fingerprint is SHA256 of the decoded blob, base64 without padding`() {
        val fp = Parsers.keyFingerprint(APP_BLOB)
        assertTrue(fp.startsWith("SHA256:"))
        assertFalse(fp.endsWith("="))
        assertEquals(Parsers.keyFingerprint(APP_BLOB), fp)
    }

    /**
     * Pinned against `ssh-keygen -lf` for a real ed25519 key generated on the host. This is
     * the only test here that checks the maths against an outside authority rather than
     * against itself — the fingerprint is what a user compares by eye, so it has to agree
     * with OpenSSH character for character.
     */
    @Test
    fun `the fingerprint matches what ssh-keygen prints for the same key`() {
        assertEquals(
            "SHA256:P8PE3dQ45XQ6pxrd5LcHvoaThHtWLvS46EF2MgEMnHs",
            Parsers.keyFingerprint(
                "AAAAC3NzaC1lZDI1NTE5AAAAIKx+s5TL01rU/LCK2stsTXYr4bOvuxocVNT4n6zSzZsu"
            ),
        )
    }

    @Test
    fun `different keys fingerprint differently`() {
        assertFalse(Parsers.keyFingerprint(APP_BLOB) == Parsers.keyFingerprint(LAPTOP_BLOB))
    }

    @Test
    fun `a blob that is not base64 is named unreadable rather than throwing`() {
        assertEquals("unreadable", Parsers.keyFingerprint("!!!not base64!!!"))
    }
}

class DropbearAuthTest {

    @Test
    fun `password auth on is read as on`() {
        val auth = Parsers.dropbearAuth("dropbear.@dropbear[0].PasswordAuth='on'")
        assertEquals(true, auth.passwordAuth)
        assertTrue(auth.passwordsAccepted)
    }

    @Test
    fun `both switches off means only keys get in`() {
        val auth = Parsers.dropbearAuth(
            "dropbear.@dropbear[0].PasswordAuth='off'\n" +
                "dropbear.@dropbear[0].RootPasswordAuth='off'"
        )
        assertFalse(auth.passwordsAccepted)
    }

    /**
     * Half-off is still open: root can log in with a password even when PasswordAuth is off,
     * and reporting that as locked down would be a lie the user acts on.
     */
    @Test
    fun `only one switch off still accepts passwords`() {
        assertTrue(
            Parsers.dropbearAuth("dropbear.@dropbear[0].PasswordAuth='off'").passwordsAccepted
        )
    }

    /** dropbear's own default is to allow passwords, so an unset option is not "off". */
    @Test
    fun `an unset option is not read as disabled`() {
        val auth = Parsers.dropbearAuth("dropbear.@dropbear[0].Port='22'")
        assertNull(auth.passwordAuth)
        assertTrue(auth.passwordsAccepted)
    }
}

/** The row the app is standing on is the row it must not delete. */
class KeyRemovalGuardTest {

    private val appKey = AuthorizedKey("ssh-ed25519", APP_BLOB, "wrtpulse", isAppKey = true)
    private val other = AuthorizedKey("ssh-ed25519", LAPTOP_BLOB, "vivek@laptop")
    private val passwordsOn = DropbearAuth(passwordAuth = true)
    private val passwordsOff = DropbearAuth(passwordAuth = false, rootPasswordAuth = false)

    @Test
    fun `the key this app signs in with is never offered for deletion`() {
        assertNotNull(SshKeyStore.removalBlock(appKey, 2, passwordsOn))
        assertNotNull(SshKeyStore.removalBlock(appKey, 2, passwordsOff))
    }

    @Test
    fun `someone else's key can be removed`() {
        assertNull(SshKeyStore.removalBlock(other, 2, passwordsOn))
    }

    /** Last key plus no password auth is a locked door with the key thrown away. */
    @Test
    fun `the last key is protected when passwords are refused`() {
        assertNotNull(SshKeyStore.removalBlock(other, 1, passwordsOff))
    }

    @Test
    fun `the last key may go while passwords still work, with a warning`() {
        assertNull(SshKeyStore.removalBlock(other, 1, passwordsOn))
        assertNotNull(SshKeyStore.removalWarning(1, passwordsOn))
    }

    @Test
    fun `removing one of several needs no warning`() {
        assertNull(SshKeyStore.removalWarning(3, passwordsOn))
    }
}

class KeyCommandTest {

    @Test
    fun `installing is idempotent rather than appending twice`() {
        val cmd = Commands.installKey(APP_LINE)
        assertTrue(cmd.contains("grep -qF"))
        assertTrue(cmd.contains("chmod 600"))
    }

    /**
     * Matched on the blob, and written through a temp file: a full disk should truncate the
     * copy, not the list of who can log in.
     */
    @Test
    fun `removal matches the blob and never rewrites the file in place`() {
        val cmd = Commands.removeKey(APP_BLOB)
        assertTrue(cmd.contains("grep -vF '$APP_BLOB'"))
        assertTrue(cmd.contains(".tmp"))
        assertTrue(cmd.contains("mv "))
        assertTrue(cmd.contains("chmod 600"))
    }

    /**
     * `grep -v` exits 1 when it prints nothing, which is what removing the ONLY key looks
     * like. Chaining the mv on && meant that case quietly left the key in place.
     */
    @Test
    fun `removing the last key is not treated as a grep failure`() {
        val cmd = Commands.removeKey(APP_BLOB)
        assertFalse(cmd.contains(".tmp && mv"))
        assertTrue(cmd.contains("-le 1"))
    }

    @Test
    fun `a missing file is reported rather than created empty`() {
        assertTrue(Commands.removeKey(APP_BLOB).contains("echo missing"))
    }

    @Test
    fun `the read carries the keys, the file mode and dropbear's policy`() {
        assertTrue(Commands.SSH_KEYS.contains(Commands.AUTHORIZED_KEYS))
        assertTrue(Commands.SSH_KEYS.contains("uci show dropbear"))
        val sections = Parsers.sections(
            "${Commands.SECTION} keys\n$APP_LINE\n${Commands.SECTION} perms\n-rw-------\n" +
                "${Commands.SECTION} dropbear\n"
        )
        assertEquals(setOf("keys", "perms", "dropbear"), sections.keys)
    }
}
