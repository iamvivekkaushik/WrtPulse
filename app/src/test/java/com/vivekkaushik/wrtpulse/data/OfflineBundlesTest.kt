package com.vivekkaushik.wrtpulse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching and set resolution for the offline wireless bundles. */
class OfflineBundlesTest {

    private fun bundles(): List<OfflineBundle> = OfflineBundles.parseManifest(
        """
        {
          "bundles": [
            {
              "release": "23.05.5",
              "target": "ramips/mt7620",
              "arch": "mipsel_24kc",
              "totalBytes": 2013265,
              "packages": [
                {"name": "libubox20230523", "version": "2023-05-23-75a3b870-1", "file": "libubox20230523.ipk", "bytes": 20480, "description": "utility library", "root": false},
                {"name": "luci-mod-network", "version": "git-25.222.75657", "file": "luci-mod-network.ipk", "bytes": 56320, "description": "LuCI network modules", "root": true},
                {"name": "wpad-mesh-mbedtls", "version": "2023-09-08-e5ccbfc6-8", "file": "wpad-mesh-mbedtls.ipk", "bytes": 712704, "description": "WPA daemon", "root": true},
                {"name": "hostapd", "version": "2023-09-08-e5ccbfc6-8", "file": "hostapd.ipk", "bytes": 438272, "description": "AP daemon", "root": true}
              ]
            },
            {
              "release": "23.05.0",
              "target": "ramips/mt7620",
              "arch": "mipsel_24kc",
              "totalBytes": 44032,
              "packages": [
                {"name": "iw", "version": "5.19-1", "file": "iw.ipk", "bytes": 44032, "description": "nl80211 tool", "root": true}
              ]
            }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `manifest parses bundles packages and roots in order`() {
        val list = bundles()
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("23.05.5", first.release)
        assertEquals("ramips/mt7620", first.target)
        assertEquals("mipsel_24kc", first.arch)
        assertEquals(4, first.packages.size)
        assertEquals(listOf("luci-mod-network", "wpad-mesh-mbedtls", "hostapd"), first.roots.map { it.name })
        assertEquals(712704L, first.packages[2].bytes)
        assertEquals(56320L, first.packages.first { it.name == "luci-mod-network" }.bytes)
    }

    @Test
    fun `exact match prefers the bundle for this release and target`() {
        val list = bundles()
        val id = RouterId("23.05.5", "ramips/mt7620", "mipsel_24kc")
        assertEquals("23.05.5", OfflineBundles.matchExact(list, id)?.release)
        assertNull(OfflineBundles.matchNear(list, id))
    }

    @Test
    fun `a point release the assets do not carry is a near match not an exact one`() {
        val list = bundles()
        val id = RouterId("23.05.3", "ramips/mt7620", "mipsel_24kc")
        assertNull(OfflineBundles.matchExact(list, id))
        // 23.05.5 is the newest bundle for this arch, so it is the near match offered.
        assertEquals("23.05.5", OfflineBundles.matchNear(list, id)?.release)
    }

    @Test
    fun `another architecture matches nothing`() {
        val list = bundles()
        val id = RouterId("23.05.5", "mediatek/filogic", "aarch64_cortex-a7")
        assertNull(OfflineBundles.matchExact(list, id))
        assertNull(OfflineBundles.matchNear(list, id))
    }

    @Test
    fun `no router id matches nothing`() {
        assertNull(OfflineBundles.matchExact(bundles(), null))
        assertNull(OfflineBundles.matchNear(bundles(), null))
    }

    @Test
    fun `installed packages are skipped and hostapd yields to wpad`() {
        val bundle = bundles()[0]
        // Nothing installed: everything goes in except hostapd, which wpad-mesh-mbedtls
        // provides and conflicts with.
        val fresh = OfflineBundles.resolveInstallSet(bundle, emptySet())
        assertEquals(
            listOf("libubox20230523", "luci-mod-network", "wpad-mesh-mbedtls"),
            fresh.map { it.name },
        )
        assertTrue(fresh.none { it.name == "hostapd" })
    }

    @Test
    fun `hostapd is also dropped when wpad-mesh-mbedtls is already installed`() {
        val bundle = bundles()[0]
        val set = OfflineBundles.resolveInstallSet(bundle, setOf("wpad-mesh-mbedtls"))
        assertTrue(set.none { it.name == "hostapd" || it.name == "wpad-mesh-mbedtls" })
    }

    @Test
    fun `a bundle with no wpad in play installs hostapd normally`() {
        val noWpad = bundles()[0].let { b ->
            b.copy(packages = b.packages.filterNot { it.name == "wpad-mesh-mbedtls" })
        }
        val set = OfflineBundles.resolveInstallSet(noWpad, emptySet())
        assertTrue(set.any { it.name == "hostapd" })
        assertEquals(3, set.size)
    }

    @Test
    fun `everything already installed leaves nothing to do`() {
        val bundle = bundles()[0]
        val installed = bundle.packages.map { it.name }.toSet()
        assertTrue(OfflineBundles.resolveInstallSet(bundle, installed).isEmpty())
    }

    @Test
    fun `conflicting wpad variants are exactly the installed ones minus the target`() {
        assertEquals(
            listOf("wpad-basic-mbedtls"),
            OfflineBundles.conflictingWpad(setOf("wpad-basic-mbedtls", "hostapd-common", "iw", "dropbear")),
        )
        assertEquals(emptyList<String>(), OfflineBundles.conflictingWpad(setOf("iw", "dropbear", "hostapd-common")))
        assertEquals(emptyList<String>(), OfflineBundles.conflictingWpad(setOf("wpad-mesh-mbedtls")))
    }
}
