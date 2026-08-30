package com.vivekkaushik.wrtpulse.ops

import com.vivekkaushik.wrtpulse.data.PackageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** apk joins name and version with the same dash it allows inside names. */
class NameVersionTest {

    @Test
    fun `splits a plain name and version`() {
        assertEquals("busybox" to "1.36.1-r2", Parsers.splitNameVersion("busybox-1.36.1-r2"))
    }

    @Test
    fun `keeps dashes that belong to the name`() {
        assertEquals(
            "kmod-nf-conntrack" to "6.6.63-r1",
            Parsers.splitNameVersion("kmod-nf-conntrack-6.6.63-r1"),
        )
    }

    @Test
    fun `handles a version with no release suffix`() {
        assertEquals("luci-app-firewall" to "24.10", Parsers.splitNameVersion("luci-app-firewall-24.10"))
    }

    @Test
    fun `a bare name keeps an empty version rather than eating its own tail`() {
        assertEquals("nlbwmon" to "", Parsers.splitNameVersion("nlbwmon"))
    }
}

class PackageSizeTest {

    @Test
    fun `opkg reports bare bytes`() {
        assertEquals(66013L, Parsers.packageSize("66013"))
    }

    @Test
    fun `apk reports human units`() {
        assertEquals(47104L, Parsers.packageSize("46.0 KiB"))
    }

    @Test
    fun `an unknown size stays unknown instead of reading as zero`() {
        assertNull(Parsers.packageSize(""))
        assertNull(Parsers.packageSize("   "))
    }
}

class InstalledPackagesTest {

    // What Commands.PACKAGES normalises apk's binary database down to.
    private val apk = """
        busybox-1.36.1-r2|442368
        kmod-nf-conntrack-6.6.63-r1|1.2 MiB
        nlbwmon-2023.06.15-r1|46.0 KiB
    """.trimIndent()

    private val opkg = """
        base-files|1552-r23809|66013|
        libgcc1|12.3.0-r4|92160|auto
        luci-base|git-24.045|512000|
    """.trimIndent()

    @Test
    fun `apk lines split into name version and size`() {
        val list = Parsers.installedPackages(apk, "apk")
        assertEquals(3, list.size)
        val conntrack = list.first { it.name == "kmod-nf-conntrack" }
        assertEquals("6.6.63-r1", conntrack.version)
        assertEquals(1_258_291L, conntrack.sizeBytes)
    }

    @Test
    fun `opkg lines carry the dependency flag apk has no room for`() {
        val list = Parsers.installedPackages(opkg, "opkg")
        assertEquals(3, list.size)
        assertTrue(list.first { it.name == "libgcc1" }.auto)
        assertFalse(list.first { it.name == "base-files" }.auto)
        assertEquals(66013L, list.first { it.name == "base-files" }.sizeBytes)
    }

    @Test
    fun `a package with no size still appears — the list is the point, not the numbers`() {
        val list = Parsers.installedPackages("dropbear|\nbusybox|", "apk")
        assertEquals(listOf("busybox", "dropbear"), list.map { it.name })
        assertNull(list.first().sizeBytes)
    }

    @Test
    fun `noise without a separator is dropped`() {
        assertTrue(Parsers.installedPackages("ERROR: apk: unknown option\n", "apk").isEmpty())
    }
}

class UpgradableTest {

    @Test
    fun `opkg names the installed version and the offered one`() {
        val list = Parsers.upgradablePackages(
            "luci-base - git-23.093 - git-24.045\nzlib - 1.2.13-r1 - 1.3-r1",
            "opkg",
        )
        assertEquals(2, list.size)
        assertEquals("git-23.093", list[0].version)
        assertEquals("git-24.045", list[0].upgradeTo)
    }

    @Test
    fun `apk carries the old version inside the upgradable marker`() {
        val list = Parsers.upgradablePackages(
            "zlib-1.3-r1 aarch64 {zlib} (Zlib) [upgradable from: zlib-1.2.13-r1]",
            "apk",
        )
        assertEquals(1, list.size)
        assertEquals("zlib", list[0].name)
        assertEquals("1.2.13-r1", list[0].version)
        assertEquals("1.3-r1", list[0].upgradeTo)
    }
}

class PackageSearchTest {

    @Test
    fun `opkg search keeps the description`() {
        val list = Parsers.packageSearchResults(
            "zlib - 1.3-r1 - Library implementing the deflate compression method",
            "opkg",
        )
        assertEquals("zlib", list[0].name)
        assertEquals("Library implementing the deflate compression method", list[0].description)
    }

    @Test
    fun `apk search marks what is already on the router`() {
        val list = Parsers.packageSearchResults(
            "tcpdump-4.99.4-r1 aarch64 {tcpdump} (BSD-3-Clause) [installed]\n" +
                "tcpdump-mini-4.99.4-r1 aarch64 {tcpdump} (BSD-3-Clause)",
            "apk",
        )
        assertEquals(2, list.size)
        assertTrue(list[0].installed)
        assertFalse(list[1].installed)
        assertEquals("tcpdump-mini", list[1].name)
    }
}

class InstallPlanTest {

    @Test
    fun `the plan names the package the user actually asked for`() {
        val plan = Parsers.installPlan(
            mapOf("pm" to "apk", "plan" to "ERROR: unable to select packages"),
            "tcpdump",
        )
        assertTrue(plan.problem!!.contains("tcpdump"))
    }
}

class RemovePlanTest {

    private val sizes = mapOf<String, Long?>("nlbwmon" to 47_104L, "libnl-tiny" to 20_480L)

    @Test
    fun `apk purge lines become the removal list`() {
        val plan = Parsers.removePlan(
            mapOf(
                "pm" to "apk",
                "plan" to "(1/2) Purging nlbwmon (2023.06.15-r1)\n(2/2) Purging libnl-tiny (2023-r1)",
                "df" to "/dev/root  102400  20480  81920  20% /overlay",
            ),
            sizes,
        )
        assertNull(plan.problem)
        assertEquals(listOf("nlbwmon", "libnl-tiny"), plan.packages.map { it.first })
        assertEquals(67_584L, plan.totalBytes)
    }

    @Test
    fun `opkg phrases removal differently and still parses`() {
        val plan = Parsers.removePlan(
            mapOf("pm" to "opkg", "plan" to "Removing package nlbwmon from root..."),
            sizes,
        )
        assertNull(plan.problem)
        assertEquals(listOf("nlbwmon"), plan.packages.map { it.first })
    }

    @Test
    fun `a dependency refusal is reported, not swallowed into an empty removal`() {
        val plan = Parsers.removePlan(
            mapOf(
                "pm" to "opkg",
                "plan" to "Package libnl-tiny is depended upon by packages:\n\tiwinfo",
            ),
        )
        assertNotNull(plan.problem)
        assertTrue(plan.problem!!.contains("depends"))
    }

    @Test
    fun `nothing matched reads as nothing to remove rather than success`() {
        val plan = Parsers.removePlan(mapOf("pm" to "apk", "plan" to ""))
        assertNotNull(plan.problem)
        assertTrue(plan.packages.isEmpty())
    }

    @Test
    fun `an unknown size leaves the total unknown instead of understating it`() {
        val plan = Parsers.removePlan(
            mapOf("pm" to "apk", "plan" to "(1/2) Purging nlbwmon (1-r1)\n(2/2) Purging mystery (1-r1)"),
            sizes,
        )
        assertNull(plan.totalBytes)
    }
}

class FeedAgeTest {

    @Test
    fun `an age in seconds becomes a phrase`() {
        assertEquals("list just refreshed", PackageStore.feedAgeLabel(30))
        assertEquals("list 45 min old", PackageStore.feedAgeLabel(2_700))
        assertEquals("list 5 h old", PackageStore.feedAgeLabel(18_000))
        assertEquals("list 3 d old", PackageStore.feedAgeLabel(259_200))
    }

    @Test
    fun `no index directory means no claim about freshness`() {
        assertNull(Parsers.feedAgeSeconds("-1"))
        assertNull(PackageStore.feedAgeLabel(Parsers.feedAgeSeconds("-1")))
        assertEquals(7_200L, Parsers.feedAgeSeconds("7200\n"))
    }
}

class PackageGuardTest {

    @Test
    fun `the app will not remove what it is talking to the router through`() {
        assertNotNull(PackageStore.removalBlock("dropbear"))
        assertNotNull(PackageStore.removalBlock("busybox"))
        assertNotNull(PackageStore.removalBlock("netifd"))
        assertNotNull(PackageStore.removalBlock("apk-tools"))
    }

    @Test
    fun `ordinary packages are removable`() {
        assertNull(PackageStore.removalBlock("nlbwmon"))
        assertNull(PackageStore.removalBlock("tcpdump"))
        assertNull(PackageStore.removalBlock("luci-app-firewall"))
    }

    @Test
    fun `a plan that sweeps in something critical is blocked by what it sweeps`() {
        assertNull(PackageStore.blockedInPlan(listOf("nlbwmon", "kmod-nf-conntrack-netlink")))
        assertNotNull(PackageStore.blockedInPlan(listOf("luci-app-firewall", "firewall4")))
    }

    @Test
    fun `losing wifi or a kernel module is worth a sentence first`() {
        assertNotNull(PackageStore.removalWarning("wpad-openssl"))
        assertNotNull(PackageStore.removalWarning("kmod-nf-conntrack"))
        assertNull(PackageStore.removalWarning("tcpdump"))
    }
}

/** Package names reach a shell, so the alphabet is the guard. */
class PackageNameGuardTest {

    @Test
    fun `real package names pass`() {
        assertTrue(Commands.safePackageName("nlbwmon"))
        assertTrue(Commands.safePackageName("kmod-nf-conntrack-netlink"))
        assertTrue(Commands.safePackageName("libstdcpp6"))
        assertTrue(Commands.safePackageName("zoneinfo-asia"))
        assertTrue(Commands.safePackageName("gcc-libs+"))
    }

    @Test
    fun `shell metacharacters do not`() {
        assertFalse(Commands.safePackageName("foo; reboot"))
        assertFalse(Commands.safePackageName("foo\$(reboot)"))
        assertFalse(Commands.safePackageName("foo'"))
        assertFalse(Commands.safePackageName("foo bar"))
        assertFalse(Commands.safePackageName("`id`"))
        assertFalse(Commands.safePackageName(""))
    }

    @Test
    fun `a leading dash would read as a flag`() {
        assertFalse(Commands.safePackageName("-rf"))
        assertFalse(Commands.safePackageName("--purge"))
    }
}

/** The commands themselves: both managers must be reachable from one build. */
class PackageCommandTest {

    @Test
    fun `every package command asks which manager is present`() {
        listOf(
            Commands.PACKAGES,
            Commands.UPDATE_FEED,
            Commands.searchPackages("tcpdump"),
            Commands.packageInfo("tcpdump"),
            Commands.installPlan("tcpdump"),
            Commands.removePlan("tcpdump"),
            Commands.installPackage("tcpdump"),
            Commands.removePackage("tcpdump"),
            Commands.upgradePackage("tcpdump"),
        ).forEach { command ->
            assertTrue(command, command.contains("command -v apk"))
            assertTrue(command, command.contains("opkg"))
        }
    }

    @Test
    fun `the snapshot carries every section the store reads`() {
        listOf("pm", "installed", "upgradable", "feed", "df").forEach {
            assertTrue(it, Commands.PACKAGES.contains("echo ${Commands.SECTION} $it"))
        }
    }

    @Test
    fun `removal stops the service before taking its files away`() {
        val command = Commands.removePackage("nlbwmon")
        assertTrue(command.indexOf("/etc/init.d/nlbwmon stop") < command.indexOf("apk del"))
    }

    @Test
    fun `a failed install does not report success from the service step`() {
        assertTrue(Commands.installPackage("nlbwmon").contains("|| exit 1"))
    }

    @Test
    fun `the nlbwmon consent plan is the same plan every package gets`() {
        assertEquals(Commands.installPlan("nlbwmon"), Commands.NLBW_PLAN)
    }
}
