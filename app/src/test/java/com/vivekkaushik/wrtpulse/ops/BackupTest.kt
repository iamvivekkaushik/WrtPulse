package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.BackupStore
import com.vivekkaushik.wrtpulse.data.ConfigArchive
import com.vivekkaushik.wrtpulse.data.Judgement
import com.vivekkaushik.wrtpulse.data.RestoreCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Writes the archives busybox tar does: ustar headers, GNU long-name records, gzip on top.
 * Kept small and literal so a failing test says which byte is wrong.
 */
private object Tar {
    class Entry(val name: String, val data: ByteArray = ByteArray(0), val type: Char = '0', val longName: Boolean = false)

    fun file(name: String, text: String) = Entry(name, text.toByteArray())
    fun dir(name: String) = Entry(name, type = '5')

    fun gz(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            if (e.longName) {
                val nameBytes = (e.name + "\u0000").toByteArray()
                out.write(header("././@LongLink", nameBytes.size.toLong(), 'L'))
                padded(out, nameBytes)
                out.write(header(e.name.take(100), e.data.size.toLong(), e.type))
            } else {
                out.write(header(e.name, e.data.size.toLong(), e.type))
            }
            padded(out, e.data)
        }
        out.write(ByteArray(1024))
        return gzip(out.toByteArray())
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun header(name: String, size: Long, type: Char): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, off)
        put(0, name)
        put(100, "0000644 ")
        put(108, "0000000 ")
        put(116, "0000000 ")
        put(124, "%011o ".format(size))
        put(136, "%011o ".format(0L))
        put(148, "        ")
        h[156] = type.code.toByte()
        put(257, "ustar ")
        put(263, "00")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, "%06o  ".format(sum))
        return h
    }

    private fun padded(out: ByteArrayOutputStream, data: ByteArray) {
        out.write(data)
        val rem = data.size % 512
        if (rem != 0) out.write(ByteArray(512 - rem))
    }
}

private const val SYSTEM_CONFIG = """
config system
    option hostname 'OpenWrt'
    option timezone 'IST-5:30'

config timeserver 'ntp'
    list server '0.openwrt.pool.ntp.org'
"""

private const val NETWORK_CONFIG = """
config interface 'loopback'
    option device 'lo'
    option proto 'static'
    option ipaddr '127.0.0.1'

config interface 'lan'
    option device 'br-lan'
    option proto 'static'
    option ipaddr '192.168.2.1'
    option netmask '255.255.255.0'
"""

private fun backupArchive() = Tar.gz(
    Tar.dir("etc/config/"),
    Tar.file("etc/config/system", SYSTEM_CONFIG),
    Tar.file("etc/config/network", NETWORK_CONFIG),
    Tar.file("etc/dropbear/dropbear_ed25519_host_key", "not really a key"),
    Tar.file("etc/passwd", "root:x:0:0:root:/root:/bin/ash"),
)

class TarArchiveTest {

    @Test
    fun `a sysupgrade archive opens and names its members as the router stores them`() {
        val tar = TarArchive.open(backupArchive())!!
        assertEquals(
            listOf("etc/config", "etc/config/system", "etc/config/network", "etc/dropbear/dropbear_ed25519_host_key", "etc/passwd"),
            tar.entries.map { it.name },
        )
        assertTrue(tar.entries.first().isDirectory)
        assertFalse(tar.entries[1].isDirectory)
        assertTrue(tar.looksLikeOpenWrtConfig)
        assertTrue(tar.unsafePaths.isEmpty())
    }

    @Test
    fun `file contents come back byte for byte`() {
        val tar = TarArchive.open(backupArchive())!!
        assertEquals(SYSTEM_CONFIG, tar.readText("etc/config/system"))
        assertEquals("root:x:0:0:root:/root:/bin/ash", tar.readText("etc/passwd"))
        assertNull(tar.read("etc/config"))
        assertNull(tar.read("etc/nothing"))
    }

    @Test
    fun `GNU long names are applied to the record that follows`() {
        val long = "etc/" + "x".repeat(120) + "/config"
        val tar = TarArchive.open(Tar.gz(Tar.Entry(long, "v".toByteArray(), longName = true)))!!
        assertEquals(listOf(long), tar.entries.map { it.name })
        assertEquals("v", tar.readText(long))
    }

    @Test
    fun `leading slashes and dot-slash are normalised, dot-dot is flagged`() {
        val tar = TarArchive.open(
            Tar.gz(Tar.file("/etc/config/a", "1"), Tar.file("./etc/config/b", "2"), Tar.file("etc/../../root/.ssh/x", "3"))
        )!!
        assertEquals(listOf("etc/config/a", "etc/config/b", "etc/../../root/.ssh/x"), tar.entries.map { it.name })
        assertEquals(listOf("etc/../../root/.ssh/x"), tar.unsafePaths)
    }

    @Test
    fun `things that are not a gzipped tar are refused rather than half-read`() {
        assertNull(TarArchive.open(ByteArray(0)))
        assertNull(TarArchive.open("plain text".toByteArray()))
        assertNull(TarArchive.open(Tar.gzip("gzipped but not a tar, and long enough to be a header block".repeat(20).toByteArray())))
        assertNotNull(TarArchive.open(Tar.gz(Tar.file("etc/config/system", "x"))))
        assertFalse(TarArchive.isGzip("x".toByteArray()))
    }
}

class BackupParsersTest {

    @Test
    fun `sysupgrade -l is sorted, deduplicated and kept to absolute paths`() {
        val out = """
            /etc/config/system
            /etc/config/network
            /etc/config/network
            Saving config files...
            /etc/dropbear/dropbear_ed25519_host_key
        """.trimIndent()
        assertEquals(
            listOf("/etc/config/network", "/etc/config/system", "/etc/dropbear/dropbear_ed25519_host_key"),
            Parsers.backupFileList(out),
        )
    }

    @Test
    fun `a tar complaint makes the whole listing a failure`() {
        assertEquals(listOf("etc/config/system", "etc/passwd"), Parsers.tarListing("etc/config/system\netc/passwd\n").getOrThrow())
        val bad = Parsers.tarListing("etc/config/system\ntar: short read\n")
        assertTrue(bad.isFailure)
        assertEquals("tar: short read", bad.exceptionOrNull()!!.message)
        assertTrue(Parsers.tarListing("gzip: invalid magic").isFailure)
    }

    @Test
    fun `uci file options are read out of the block they belong to`() {
        assertEquals("OpenWrt", Parsers.uciFileOption(SYSTEM_CONFIG, "system", null, "hostname"))
        assertEquals("192.168.2.1", Parsers.uciFileOption(NETWORK_CONFIG, "interface", "lan", "ipaddr"))
        // The loopback comes first and also has an ipaddr; the name must be honoured.
        assertEquals("127.0.0.1", Parsers.uciFileOption(NETWORK_CONFIG, "interface", "loopback", "ipaddr"))
        assertNull(Parsers.uciFileOption(NETWORK_CONFIG, "interface", "wan", "ipaddr"))
        assertNull(Parsers.uciFileOption(SYSTEM_CONFIG, "system", null, "nothing"))
    }

    @Test
    fun `shell words honour quotes`() {
        assertEquals(listOf("option", "hostname", "my router"), Parsers.shellWords("option hostname 'my router'"))
        assertEquals(listOf("option", "key", ""), Parsers.shellWords("option key ''"))
        assertEquals(listOf("config", "interface", "lan"), Parsers.shellWords("config interface \"lan\""))
        assertEquals(emptyList<String>(), Parsers.shellWords("   "))
    }
}

class BackupCommandsTest {

    @Test
    fun `the restore lands in tmp, arrives on stdin and is counted`() {
        assertTrue(Commands.RESTORE_FILE.startsWith("/tmp/"))
        assertTrue(Commands.RESTORE_RECEIVE.contains("cat > ${Commands.RESTORE_FILE}"))
        assertTrue(Commands.RESTORE_RECEIVE.contains("wc -c < ${Commands.RESTORE_FILE}"))
        assertTrue(Commands.RESTORE_LIST.startsWith("tar -tzf ${Commands.RESTORE_FILE}"))
        assertTrue(Commands.RESTORE_APPLY.startsWith("sysupgrade -r ${Commands.RESTORE_FILE}"))
        assertTrue(Commands.RESTORE_SHA256.contains("sha256sum '${Commands.RESTORE_FILE}'"))
    }

    /** `sysupgrade -r` does not reboot; the app does, and only after a 0 exit. Never in one line. */
    @Test
    fun `the unpack and the reboot are separate commands`() {
        assertFalse(Commands.RESTORE_APPLY.contains("reboot"))
    }

    @Test
    fun `the entry read asks for the file list`() {
        assertTrue(Commands.BACKUP_INFO.contains("sysupgrade -l"))
        assertTrue(Commands.BACKUP_INFO.contains("${Commands.SECTION} files"))
        assertTrue(Commands.BACKUP_INFO.contains("df -k /tmp"))
    }
}

class ConfigArchiveNameTest {

    @Test
    fun `names round-trip and hosts are made filename-safe`() {
        val name = ConfigArchive.fileName("192.168.2.1", 1_756_500_000)
        assertEquals("wrtpulse-192.168.2.1-1756500000.tar.gz", name)
        assertEquals("192.168.2.1" to 1_756_500_000L, ConfigArchive.parseName(name))
        assertEquals("wrtpulse-fe80__1-1756500000.tar.gz", ConfigArchive.fileName("fe80::1", 1_756_500_000))
        assertEquals("bpi-r3-lab" to 1_756_500_000L, ConfigArchive.parseName("wrtpulse-bpi-r3-lab-1756500000.tar.gz"))
    }

    @Test
    fun `other files in the directory are not backups`() {
        assertNull(ConfigArchive.parseName("notes.txt"))
        assertNull(ConfigArchive.parseName("wrtpulse-host-abc.tar.gz"))
        assertNull(ConfigArchive.parseName("backup-192.168.2.1-1756500000.tar.gz"))
    }
}

/** The gates, in the order the user meets them. */
class RestoreGateTest {

    private fun candidate(
        onRouter: Boolean = true,
        listing: List<String>? = listOf("etc/config/system"),
        refusal: String? = null,
        hostname: String? = "OpenWrt",
        lan: String? = "192.168.2.1",
        hostKey: Boolean = true,
    ) = RestoreCandidate(
        source = "test.tar.gz",
        bytes = byteArrayOf(1),
        sha256 = "00",
        entries = buildList {
            add(TarEntry("etc/config/system", 1, false))
            if (hostKey) add(TarEntry("etc/dropbear/dropbear_ed25519_host_key", 1, false))
        },
        hostname = hostname,
        lanAddress = lan,
        onRouter = onRouter,
        routerListing = listing,
        routerRefusal = refusal,
    )

    private val board = BoardInfo("Router", "x", "OpenWrt 25.12.5", "r1", "mediatek/filogic", hostname = "OpenWrt")

    @Test
    fun `every gate met means no block`() {
        assertNull(BackupStore.restoreBlock(candidate()))
    }

    @Test
    fun `nothing staged asks for a backup first`() {
        assertTrue(BackupStore.restoreBlock(null)!!.contains("Choose"))
    }

    @Test
    fun `the archive has to be on the router before anything else is said`() {
        assertTrue(BackupStore.restoreBlock(candidate(onRouter = false))!!.contains("Send"))
    }

    @Test
    fun `the router's own tar has the last word`() {
        assertTrue(BackupStore.restoreBlock(candidate(refusal = "tar: short read"))!!.contains("short read"))
        assertTrue(BackupStore.restoreBlock(candidate(listing = emptyList()))!!.contains("nothing"))
        assertTrue(BackupStore.restoreBlock(candidate(listing = null))!!.contains("nothing"))
    }

    @Test
    fun `a matching archive gets only the standing warnings`() {
        val w = BackupStore.restoreWarnings(candidate(), board, "192.168.2.1")
        assertEquals(2, w.size)
        assertTrue(w[0].contains("replaces"))
        assertTrue(w[1].contains("reboots"))
    }

    @Test
    fun `an archive from another router is named as such`() {
        val w = BackupStore.restoreWarnings(candidate(hostname = "bpi-r3-lab", lan = "192.168.1.1"), board, "192.168.2.1")
        assertTrue(w.any { it.contains("'bpi-r3-lab'") && it.contains("'OpenWrt'") })
        assertTrue(w.any { it.contains("192.168.1.1") && it.contains("192.168.2.1") })
        assertTrue(w.any { it.contains("host key") })
    }

    @Test
    fun `the LAN warning only fires when the current host is an address`() {
        val w = BackupStore.restoreWarnings(candidate(lan = "192.168.1.1"), board, "home.gw")
        assertFalse(w.any { it.contains("LAN address") })
    }

    @Test
    fun `an archive without a host key never warns about one`() {
        val w = BackupStore.restoreWarnings(candidate(hostname = "other", hostKey = false), board, "192.168.2.1")
        assertFalse(w.any { it.contains("host key") })
    }
}

class RestoreJudgementTest {

    private fun why(bytes: ByteArray) = (BackupStore.judge(bytes) as Judgement.Refused).why

    @Test
    fun `a real backup is accepted`() {
        assertTrue(BackupStore.judge(backupArchive()) is Judgement.Ok)
    }

    @Test
    fun `empty, oversized, non-gzip and non-tar are each refused with the reason`() {
        assertTrue(why(ByteArray(0)).contains("empty"))
        assertTrue(why("hello".toByteArray()).contains("gzip"))
        assertTrue(why(Tar.gzip("not a tar at all, but long enough that it could pretend".repeat(20).toByteArray())).contains("tar"))
        assertTrue(why(ByteArray((BackupStore.MAX_RESTORE_BYTES + 1).toInt())).contains("MB"))
    }

    @Test
    fun `an archive that climbs out of root is refused, whatever else it holds`() {
        val bad = Tar.gz(Tar.file("etc/config/system", "x"), Tar.file("etc/../../root/.profile", "y"))
        assertTrue(why(bad).contains("climb"))
    }

    @Test
    fun `a tarball with nothing under etc is not a router backup`() {
        assertTrue(why(Tar.gz(Tar.file("photos/cat.jpg", "meow"))).contains("etc/"))
    }
}

class BackupAgeTest {
    @Test
    fun `ages read the way the System row shows them`() {
        val now = 1_000_000L
        assertEquals("just now", BackupStore.ageLabel(now - 30, now))
        assertEquals("12 min ago", BackupStore.ageLabel(now - 12 * 60, now))
        assertEquals("3 h ago", BackupStore.ageLabel(now - 3 * 3600 - 10, now))
        assertEquals("6 d ago", BackupStore.ageLabel(now - 6 * 86_400, now))
        assertEquals("just now", BackupStore.ageLabel(now + 500, now))
    }

    @Test
    fun `sha256 matches the router's sha256sum`() {
        // `printf abc | sha256sum` on the reference router.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupStore.sha256("abc".toByteArray()),
        )
    }
}

/**
 * A sysupgrade backup carries /etc/config and /etc/dropbear — it does NOT carry
 * /etc/openwrt_release, so the version an archive came from cannot be read back out of it.
 * The app knows it at capture time and writes it alongside.
 */
class ArchiveReleaseTest {

    private fun tempDir(): java.io.File =
        java.io.File(System.getProperty("java.io.tmpdir"), "wrtpulse-rel-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `the release is written beside the archive and read back`() {
        val dir = tempDir()
        val archive = java.io.File(dir, ConfigArchive.fileName("192.168.0.1", 1_788_542_312))
        archive.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(ConfigArchive.releaseOf(archive))

        ConfigArchive.releaseFile(archive).writeText("OpenWrt 25.12.5")
        assertEquals("OpenWrt 25.12.5", ConfigArchive.releaseOf(archive))
        assertEquals(archive.name + ".release", ConfigArchive.releaseFile(archive).name)
        dir.deleteRecursively()
    }

    /** An archive from before this existed simply has no version, which is the truth. */
    @Test
    fun `a blank or missing sidecar is no version at all`() {
        val dir = tempDir()
        val archive = java.io.File(dir, ConfigArchive.fileName("h", 1_788_542_312))
        archive.writeBytes(byteArrayOf(1))
        ConfigArchive.releaseFile(archive).writeText("   ")
        assertNull(ConfigArchive.releaseOf(archive))
        dir.deleteRecursively()
    }

    /** The sidecar is not a backup, so the pruner must never count or delete it as one. */
    @Test
    fun `the sidecar is not mistaken for an archive`() {
        val dir = tempDir()
        val files = (1..3).map { java.io.File(dir, ConfigArchive.fileName("h", 1_700_000_000L + it)) }
        val sidecars = files.map { ConfigArchive.releaseFile(it) }
        assertNull(ConfigArchive.parseName(sidecars.first().name))
        assertEquals(1, BackupStore.prune(files + sidecars, 2).size)
        dir.deleteRecursively()
    }
}
