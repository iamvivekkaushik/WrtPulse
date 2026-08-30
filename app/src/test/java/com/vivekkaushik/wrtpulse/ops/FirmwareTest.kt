package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.FirmwareStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captured verbatim from `owut check` on the reference router, 2026-08-31. */
private val OWUT_CHECK = """
    ASU-Server      https://sysupgrade.openwrt.org
    Upstream        https://downloads.openwrt.org
    Target          mediatek/filogic
    Profile         jiorouter_ax6000-jidu6101
    Package-arch    aarch64_cortex-a53
    Version-from    25.12.5 r33051-f5dae5ece4 (kernel 6.12.94)
    Version-to      25.12.5 r33051-f5dae5ece4 (kernel 6.12.94)
    29 packages are out-of-date
    There are 0 missing and 1 modified default packages
    It is safe to proceed with an upgrade (re-run with '--verbose' for details)
""".trimIndent()

/** The same shape when a genuine release upgrade is on offer. */
private val OWUT_UPGRADE = """
    Target          mediatek/filogic
    Profile         jiorouter_ax6000-jidu6101
    Version-from    25.12.5 r33051-f5dae5ece4 (kernel 6.12.94)
    Version-to      26.03.1 r34112-abcdef0123 (kernel 6.12.99)
    4 packages are out-of-date
    There are 0 missing and 0 modified default packages
    It is safe to proceed with an upgrade
""".trimIndent()

class UpgradeCheckTest {

    private val check = Parsers.upgradeCheck(OWUT_CHECK)

    @Test
    fun `the label-value table becomes fields`() {
        assertEquals("mediatek/filogic", check.target)
        assertEquals("jiorouter_ax6000-jidu6101", check.profile)
        assertEquals("https://sysupgrade.openwrt.org", check.server)
    }

    @Test
    fun `a value keeps its own single spaces`() {
        assertEquals("25.12.5 r33051-f5dae5ece4 (kernel 6.12.94)", check.versionFrom)
    }

    @Test
    fun `owut's own verdict is what decides safe`() {
        assertTrue(check.safe)
    }

    /**
     * The wording differs by one word, and matching on "safe" alone would read a refusal as
     * approval — the single most dangerous parsing mistake available in this screen.
     */
    @Test
    fun `a refusal is not read as approval`() {
        val refused = Parsers.upgradeCheck(
            "Version-from    25.12.5 r1\nIt is not safe to proceed with an upgrade"
        )
        assertFalse(refused.safe)
    }

    @Test
    fun `free text under the table is kept as notes, in order`() {
        assertEquals(3, check.notes.size)
        assertTrue(check.notes[0].startsWith("29 packages"))
    }

    @Test
    fun `package counts are read out of the notes`() {
        assertEquals(29, check.outdatedPackages)
        assertEquals(0, check.missingPackages)
        assertEquals(1, check.modifiedPackages)
    }

    /**
     * The reference router's actual state: no newer release, but stale packages. Calling
     * this "up to date" would hide the only reason to run an attended sysupgrade.
     */
    @Test
    fun `same version with stale packages is still work worth doing`() {
        assertTrue(check.sameVersion)
        assertTrue(check.hasWork)
    }

    @Test
    fun `a real release upgrade is not a same-version rebuild`() {
        val upgrade = Parsers.upgradeCheck(OWUT_UPGRADE)
        assertFalse(upgrade.sameVersion)
        assertTrue(upgrade.hasWork)
        assertTrue(upgrade.safe)
    }

    @Test
    fun `a current router with nothing stale has no work`() {
        val current = Parsers.upgradeCheck(
            "Version-from    25.12.5 r1\nVersion-to      25.12.5 r1\n" +
                "0 packages are out-of-date\nIt is safe to proceed with an upgrade"
        )
        assertTrue(current.sameVersion)
        assertFalse(current.hasWork)
    }

    @Test
    fun `unreadable output leaves empty fields rather than throwing`() {
        val junk = Parsers.upgradeCheck("ash: owut: not found")
        assertTrue(junk.fields.isEmpty())
        assertFalse(junk.safe)
    }
}

class FirmwareStatusTest {

    private val sections = mapOf(
        "tool" to "owut",
        "tmp" to "tmpfs                   1010900     12345    998555   1% /tmp",
        "images" to "/tmp/openwrt-25.12.5-mediatek-filogic-jiorouter_ax6000-jidu6101-squashfs-sysupgrade.bin|9437184",
    )

    @Test
    fun `the tool, the RAM and the staged image come out of one read`() {
        val status = Parsers.firmwareStatus(sections)
        assertEquals("owut", status.tool)
        assertTrue(status.hasTool)
        assertEquals(998555L, status.tmpFreeKb)
        assertEquals(1, status.images.size)
        assertEquals(9437184L, status.images[0].second)
    }

    @Test
    fun `a router with no upgrade tool still reports cleanly`() {
        val status = Parsers.firmwareStatus(mapOf("tool" to "none", "tmp" to "", "images" to ""))
        assertEquals("none", status.tool)
        assertFalse(status.hasTool)
        assertTrue(status.images.isEmpty())
    }

    @Test
    fun `a zero-byte or unparseable image line is not offered as an image`() {
        assertTrue(Parsers.stagedImages("/tmp/broken.bin|0").isEmpty())
        assertTrue(Parsers.stagedImages("ls: no such file").isEmpty())
    }
}

/**
 * owut saves to `/tmp/firmware.bin` — nothing in that name says it is a sysupgrade image.
 * Globbing for `*sysupgrade*` reported a successful download as a failure on the real router.
 */
class SavedImagePathTest {

    @Test
    fun `owut's own line is where the image path comes from`() {
        assertEquals(
            "/tmp/firmware.bin",
            Parsers.savedImagePath("Downloading imagen\nImage saved : /tmp/firmware.bin"),
        )
    }

    @Test
    fun `spacing around the colon does not matter`() {
        assertEquals("/tmp/firmware.bin", Parsers.savedImagePath("Image saved: /tmp/firmware.bin"))
        assertEquals("/tmp/x.bin", Parsers.savedImagePath("image saved   :   /tmp/x.bin"))
    }

    @Test
    fun `output without the line yields nothing rather than a guess`() {
        assertNull(Parsers.savedImagePath("Build failed on the server"))
        assertNull(Parsers.savedImagePath(""))
    }

    @Test
    fun `a firmware bin with no sysupgrade in its name is still found by the listing`() {
        assertEquals(
            listOf("/tmp/firmware.bin" to 9437184L),
            Parsers.stagedImages("/tmp/firmware.bin|9437184"),
        )
    }
}

class FirmwareScalarsTest {

    @Test
    fun `a sha256 is picked out of whatever surrounds it`() {
        val sum = "a".repeat(64)
        assertEquals(sum, Parsers.sha256("$sum\n"))
        assertNull(Parsers.sha256("sha256sum: /tmp/x.bin: No such file"))
    }

    @Test
    fun `wc -c answers are read as the last bare number`() {
        assertEquals(9437184L, Parsers.byteCount("9437184"))
        assertEquals(512L, Parsers.byteCount("Downloading…\n512"))
        assertNull(Parsers.byteCount("no such file"))
    }
}

/** These strings reach a shell, and one of them decides where an image is written. */
class FirmwareGuardTest {

    @Test
    fun `an image path must be in tmp and carry no shell syntax`() {
        assertTrue(Commands.safeImagePath("/tmp/openwrt-sysupgrade.bin"))
        assertFalse(Commands.safeImagePath("/etc/passwd"))
        assertFalse(Commands.safeImagePath("/tmp/../etc/passwd"))
        assertFalse(Commands.safeImagePath("/tmp/x.bin; reboot"))
        assertFalse(Commands.safeImagePath("/tmp/x.bin' ; reboot #"))
    }

    @Test
    fun `a download URL must be http and free of quoting`() {
        assertTrue(Commands.safeImageUrl("https://downloads.openwrt.org/a/b-sysupgrade.bin"))
        assertFalse(Commands.safeImageUrl("file:///etc/shadow"))
        assertFalse(Commands.safeImageUrl("https://x.test/a' ; reboot #"))
        assertFalse(Commands.safeImageUrl("https://x.test/\$(reboot)"))
    }

    @Test
    fun `a sha256 is 64 hex characters and nothing else`() {
        assertTrue(Commands.safeSha256("a".repeat(64)))
        assertFalse(Commands.safeSha256("a".repeat(63)))
        assertFalse(Commands.safeSha256("g".repeat(64)))
    }

    @Test
    fun `keeping settings is the difference between sysupgrade and sysupgrade -n`() {
        assertTrue(Commands.flash("/tmp/x.bin", keepSettings = true).contains("sysupgrade '/tmp/x.bin'"))
        assertTrue(Commands.flash("/tmp/x.bin", keepSettings = false).contains("sysupgrade -n '/tmp/x.bin'"))
    }

    /** Losing the link is the expected outcome, so the command must not need the link. */
    @Test
    fun `the flash is detached so it does not need the session that started it`() {
        val cmd = Commands.flash("/tmp/x.bin", keepSettings = true)
        assertTrue(cmd.contains("&"))
        assertTrue(cmd.trimEnd().endsWith("echo flashing"))
    }
}

/** The gates, in the order the user meets them. */
class FlashGateTest {

    private val untested = ImageCheck("/tmp/x.bin", 9_000_000)
    private val passed = untested.copy(testPassed = true)
    private val refused = untested.copy(testPassed = false)

    private fun block(
        backupDone: Boolean = true,
        backupWaived: Boolean = false,
        image: ImageCheck? = passed,
        checkSafe: Boolean? = true,
    ) = FirmwareStore.flashBlock(backupDone, backupWaived, image, checkSafe)

    @Test
    fun `every gate met means no block`() {
        assertNull(block())
    }

    @Test
    fun `no backup and no explicit waiver blocks first`() {
        assertNotNull(block(backupDone = false))
    }

    @Test
    fun `waiving the backup deliberately is allowed to pass`() {
        assertNull(block(backupDone = false, backupWaived = true))
    }

    @Test
    fun `nothing downloaded blocks`() {
        assertNotNull(block(image = null))
    }

    @Test
    fun `an image sysupgrade has not looked at yet blocks`() {
        assertNotNull(block(image = untested))
    }

    @Test
    fun `an image sysupgrade refused blocks`() {
        assertNotNull(block(image = refused))
    }

    @Test
    fun `owut calling the upgrade unsafe blocks even with a verified image`() {
        assertNotNull(block(checkSafe = false))
    }

    /** The manual-URL path never runs owut, and its absence is not a refusal. */
    @Test
    fun `no check at all does not block the manual path`() {
        assertNull(block(checkSafe = null))
    }
}

class FlashWarningTest {

    private val sameVersion = Parsers.upgradeCheck(OWUT_CHECK)

    @Test
    fun `discarding settings warns about the address and the host key`() {
        val warnings = FirmwareStore.flashWarnings(false, sameVersion, "192.168.2.1")
        assertTrue(warnings.any { it.contains("192.168.1.1") })
        assertTrue(warnings.any { it.contains("192.168.2.1") })
        assertTrue(warnings.any { it.contains("host key") })
    }

    @Test
    fun `keeping settings warns about carrying config across a release instead`() {
        val warnings = FirmwareStore.flashWarnings(true, sameVersion, "192.168.2.1")
        assertTrue(warnings.any { it.contains("carried across") })
        assertFalse(warnings.any { it.contains("192.168.1.1") })
    }

    @Test
    fun `a same-version rebuild says so, so it is not mistaken for a release upgrade`() {
        assertTrue(
            FirmwareStore.flashWarnings(true, sameVersion, null)
                .any { it.contains("same version") }
        )
    }

    @Test
    fun `losing Wi-Fi mid-flash is always worth saying`() {
        assertTrue(
            FirmwareStore.flashWarnings(true, null, null).any { it.contains("Wi-Fi") }
        )
    }
}

/**
 * The reference router has neither a `base64` applet nor openssl — the first attempt at the
 * backup came back exit 127 and empty. There is no encoder that is always present, so the
 * command names the one it used and `od` is the floor.
 */
class ArchiveEncodingTest {

    /**
     * The reference router has NO base64, no openssl and no od. It does have hexdump, and
     * that is the only reason a backup is possible there at all.
     */
    @Test
    fun `the read tries every encoder it might find`() {
        assertTrue(Commands.BACKUP_READ.contains("base64"))
        assertTrue(Commands.BACKUP_READ.contains("busybox base64"))
        assertTrue(Commands.BACKUP_READ.contains("openssl base64"))
        assertTrue(Commands.BACKUP_READ.contains("hexdump -v -e"))
        assertTrue(Commands.BACKUP_READ.contains("od -An -v -tx1"))
    }

    /** A router that can do none of them says so, rather than returning an empty archive. */
    @Test
    fun `no encoder at all is a stated answer, not silence`() {
        assertTrue(Commands.BACKUP_READ.contains("else echo none"))
    }

    @Test
    fun `each branch announces which encoding it produced`() {
        assertTrue(Commands.BACKUP_READ.contains("echo b64"))
        assertTrue(Commands.BACKUP_READ.contains("echo hex"))
    }

    /** Probing with one byte beats encoding a 16 kB archive twice to find out. */
    @Test
    fun `encoder availability is probed, not assumed`() {
        assertTrue(Commands.BACKUP_READ.contains("echo t | base64"))
    }

    /**
     * The reference router's base64 exists but takes no filename — the probe passed, the
     * marker printed, and an empty payload came back. Every encoder is fed on stdin.
     */
    @Test
    fun `encoders are fed by redirection, never a file operand`() {
        assertTrue(Commands.BACKUP_READ.contains("base64 < \"\$F\""))
        assertTrue(Commands.BACKUP_READ.contains("hexdump -v -e '/1 \"%02x\"' < \"\$F\""))
        assertFalse(Commands.BACKUP_READ.contains("base64 \"\$F\""))
    }

    @Test
    fun `od output decodes back to the original bytes`() {
        val original = byteArrayOf(0, 1, 2, 127, -1, -128, 65, 66)
        val hex = original.joinToString("") { "%02x".format(it) }
        assertArrayEquals(original, FirmwareStore.decodeHex(hex))
    }

    @Test
    fun `od line breaks and spacing survive the round trip`() {
        assertArrayEquals(
            byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte()),
            FirmwareStore.decodeHex(" de ad\n be ef \n"),
        )
    }

    /** A half-decoded archive that looks like a backup is worse than no backup. */
    @Test
    fun `truncated or non-hex output decodes to nothing rather than to garbage`() {
        assertNull(FirmwareStore.decodeHex("abc"))
        assertNull(FirmwareStore.decodeHex("zz"))
        assertNull(FirmwareStore.decodeHex(""))
        assertNull(FirmwareStore.decodeHex("   "))
    }
}

class FirmwareCommandTest {

    @Test
    fun `the entry read carries the board, the tool, the RAM and staged images`() {
        val sections = Parsers.sections(
            "${Commands.SECTION} board\n{}\n${Commands.SECTION} tool\nowut\n" +
                "${Commands.SECTION} tmp\ntmpfs 1 2 3 1% /tmp\n${Commands.SECTION} images\n"
        )
        assertEquals(setOf("board", "tool", "tmp", "images"), sections.keys)
        assertTrue(Commands.FIRMWARE.contains("ubus call system board"))
        assertTrue(Commands.FIRMWARE.contains("df -k /tmp"))
    }

    /** `owut upgrade` would download and install in one step, skipping every gate. */
    @Test
    fun `the app never invokes owut upgrade`() {
        assertEquals("owut download 2>&1", Commands.UPGRADE_DOWNLOAD)
        assertFalse(Commands.UPGRADE_DOWNLOAD.contains("owut upgrade"))
        assertFalse(Commands.UPGRADE_CHECK.contains("owut upgrade"))
    }

    /** The app put 13 MB in the router's RAM; it should be able to take it back. */
    @Test
    fun `a downloaded image can be discarded, and only from tmp`() {
        assertEquals("rm -f '/tmp/firmware.bin'", Commands.discardImage("/tmp/firmware.bin"))
        assertFalse(Commands.safeImagePath("/etc/config/network"))
    }

    @Test
    fun `the backup is written, measured and then removed from the router`() {
        assertTrue(Commands.BACKUP_CREATE.contains("sysupgrade -b"))
        assertTrue(Commands.BACKUP_CREATE.contains("wc -c"))
        assertTrue(Commands.BACKUP_CLEANUP.contains("rm -f"))
    }
}
