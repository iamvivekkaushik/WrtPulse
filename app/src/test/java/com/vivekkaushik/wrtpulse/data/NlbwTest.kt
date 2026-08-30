package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NlbwTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun inventory() = Inventory(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))

    @Test
    fun `nlbw rows sum per mac and skip the null mac`() {
        val hosts = Parsers.nlbwHosts(
            """
            {"columns":["family","mac","ip","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts","layer7"],
             "data":[
               ["ipv4","AA:5C:1E:88:04:2B","192.168.2.34",5,1000,10,200,4,"http"],
               ["ipv4","aa:5c:1e:88:04:2b","192.168.2.34",2,500,5,100,2,"dns"],
               ["ipv4","00:00:00:00:00:00","0.0.0.0",1,999,1,999,1,""]
             ]}
            """.trimIndent()
        )
        assertEquals(1, hosts.size)
        assertEquals("aa:5c:1e:88:04:2b", hosts[0].mac)
        assertEquals(1500L, hosts[0].downBytes)
        assertEquals(300L, hosts[0].upBytes)
    }

    private fun tick(rx: Long, tx: Long, hasNlbw: Boolean = true) = mapOf(
        "leases" to "2000000 aa:5c:1e:88:04:2b 192.168.2.34 pixel-8 *",
        "neigh" to "192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE",
        "assoc" to """
            # phy1-ap0
            AA:5C:1E:88:04:2B  -52 dBm / -95 dBm (SNR 43)  0 ms ago
                RX: 780.0 MBit/s   1234 Pkts.
                TX: 866.7 MBit/s   4321 Pkts.
        """.trimIndent(),
        "wifi" to "{}",
        "blocked" to "",
        "nlbwbin" to if (hasNlbw) "/usr/sbin/nlbw" else "",
        "nlbw" to if (hasNlbw)
            """{"columns":["mac","rx_bytes","tx_bytes"],"data":[["aa:5c:1e:88:04:2b",$rx,$tx]]}"""
        else "",
    )

    @Test
    fun `with nlbwmon installed a phy link rate is never shown as usage`() {
        val inv = inventory()
        inv.ingest(tick(0, 0), 1_000_000L)
        assertEquals(true, inv.nlbwPresent)
        // Single sample: no rate yet — must render "—", not the 866.7 PHY link rate.
        assertNull(inv.clients.single().downMbps)

        inv.ingest(tick(6_250_000, 625_000), 1_000_005L) // +5 s: 6.25 MB down, 0.625 MB up
        val c = inv.clients.single()
        assertEquals(10f, c.downMbps!!, 0.01f)
        assertEquals(1f, c.upMbps!!, 0.01f)
    }

    @Test
    fun `without nlbwmon the phy link rate is the honest fallback`() {
        val inv = inventory()
        inv.ingest(tick(0, 0, hasNlbw = false), 1_000_000L)
        assertEquals(false, inv.nlbwPresent)
        assertEquals(866.7f, inv.clients.single().downMbps!!, 0.01f)
    }

    @Test
    fun `remerge never disturbs the rate baseline`() {
        val inv = inventory()
        inv.ingest(tick(0, 0), 1_000_000L)
        inv.ingest(tick(6_250_000, 625_000), 1_000_005L)
        assertEquals(10f, inv.clients.single().downMbps!!, 0.01f)

        // A rename lands 2 s later: same counters re-ingested without updating rates.
        inv.ingest(tick(6_250_000, 625_000), 1_000_007L, updateRates = false)
        assertEquals(10f, inv.clients.single().downMbps!!, 0.01f)
    }

    @Test
    fun `unchanged counters decay over the window instead of snapping to zero`() {
        // nlbwmon folds long flows into its db only every ~30 s; between refreshes the
        // counters we read do not move. Rates are computed over the whole sample window.
        val inv = inventory()
        inv.ingest(tick(0, 0), 1_000_000L)
        inv.ingest(tick(6_250_000, 625_000), 1_000_005L)
        inv.ingest(tick(6_250_000, 625_000), 1_000_010L) // stale nlbw db, +5 s
        // 6.25 MB over the 10 s window = 5 Mbps, not a false zero.
        assertEquals(5f, inv.clients.single().downMbps!!, 0.01f)
    }

    @Test
    fun `same-second duplicate tick keeps the existing baseline`() {
        val inv = inventory()
        inv.ingest(tick(0, 0), 1_000_000L)
        inv.ingest(tick(6_250_000, 625_000), 1_000_005L)
        inv.ingest(tick(6_260_000, 626_000), 1_000_005L) // action refresh in the same second
        assertEquals(10f, inv.clients.single().downMbps!!, 0.01f)
    }

    @Test
    fun `remerge after a rename keeps the measured rates`() {
        val inv = inventory()
        fun tick(rx: Long, tx: Long) = mapOf(
            "leases" to "2000000 aa:5c:1e:88:04:2b 192.168.2.34 pixel-8 *",
            "neigh" to "192.168.2.34 dev br-lan lladdr aa:5c:1e:88:04:2b REACHABLE",
            "assoc" to "",
            "wifi" to "{}",
            "blocked" to "",
            "nlbwbin" to "/usr/sbin/nlbw",
            "nlbw" to """{"columns":["mac","rx_bytes","tx_bytes"],"data":[["aa:5c:1e:88:04:2b",$rx,$tx]]}""",
        )
        inv.ingest(tick(0, 0), 1_000_000L)
        inv.ingest(tick(6_250_000, 625_000), 1_000_005L)
        assertEquals(10f, inv.clients.single().downMbps!!, 0.01f)

        inv.nameOverrides = mapOf("aa:5c:1e:88:04:2b" to "Pixel")
        inv.remerge()
        val c = inv.clients.single()
        assertEquals("Pixel", c.name)
        assertEquals(10f, c.downMbps!!, 0.01f) // not reset to 0 by re-merging the same snapshot
    }

    /** Rows from the user's router: per-protocol lines that must fold into one host total. */
    @Test
    fun `cumulative usage and top protocols reach the client`() {
        val inv = inventory()
        inv.ingest(
            mapOf(
                "leases" to "2000000 38:f9:d3:b9:35:f2 192.168.2.186 macbook *",
                "neigh" to "192.168.2.186 dev br-lan lladdr 38:f9:d3:b9:35:f2 REACHABLE",
                "assoc" to "",
                "wifi" to "{}",
                "blocked" to "",
                "nlbwbin" to "/usr/sbin/nlbw",
                "nlbw" to """{"columns":["family","proto","port","mac","ip","conns","rx_bytes","rx_pkts","tx_bytes","tx_pkts","layer7"],
                    "data":[[4,"TCP",443,"38:F9:D3:B9:35:F2","192.168.2.186",11,17005297,41313,87656668,44646,"HTTPS"],
                            [4,"UDP",443,"38:f9:d3:b9:35:f2","192.168.2.186",8,1073168,1576,1098786,1282,"QUIC"],
                            [4,"TCP",22,"38:f9:d3:b9:35:f2","192.168.2.186",1,3337,12,3457,14,"SSH"]]}""",
            ),
            1_000_000L,
        )
        val c = inv.clients.single()
        assertEquals(17_005_297L + 1_073_168L + 3_337L, c.usageDown)
        assertEquals(87_656_668L + 1_098_786L + 3_457L, c.usageUp)
        assertEquals(c.usageDown!! + c.usageUp!!, c.usageTotal)
        // Busiest protocol first, and the mixed-case MAC folded into the same host.
        assertEquals("HTTPS", c.apps.first().first)
        assertEquals(listOf("HTTPS", "QUIC", "SSH"), c.apps.map { it.first })
        assertEquals(inv.totals["38:f9:d3:b9:35:f2"]!!.totalBytes, c.usageTotal)
    }

    @Test
    fun `missing nlbw binary drives the offer`() {
        val inv = inventory()
        assertNull(inv.nlbwPresent)
        inv.ingest(mapOf("nlbwbin" to "", "nlbw" to ""), 1_000L)
        assertEquals(false, inv.nlbwPresent)
    }

    @Test
    fun `opkg install plan parses sizes and free space`() {
        val plan = Parsers.installPlan(
            mapOf(
                "pm" to "opkg",
                "plan" to "Installing nlbwmon (1.1.1-1) to root...\nDownloading https://...\nConfiguring nlbwmon.",
                "sizes" to "nlbwmon|46080 B",
                "df" to "/dev/loop0 104857600 35651584 69206016 34% /overlay",
            )
        )
        assertNull(plan.problem)
        assertEquals("opkg", plan.packageManager)
        assertEquals(listOf("nlbwmon" to 46_080L as Long?), plan.packages)
        assertEquals(46_080L, plan.totalBytes)
        assertEquals(69_206_016L, plan.availKb)
    }

    /** Verified against the user's OpenWrt 25.12.5 router (apk-tools 3, aarch64). */
    @Test
    fun `apk plan sums installed sizes of every resolved package`() {
        val plan = Parsers.installPlan(
            mapOf(
                "pm" to "apk",
                "plan" to "(1/2) Installing kmod-nf-conntrack-netlink (6.12.94-r1)\n" +
                    "(2/2) Installing nlbwmon (2025.06.02~29236be6-r1)\n" +
                    "OK: 33.1 MiB in 191 packages",
                "sizes" to "kmod-nf-conntrack-netlink|32 KiB\nnlbwmon|46 KiB",
                "df" to "/dev/ubi0_3             111016      3948    102228   4% /overlay",
            )
        )
        assertNull(plan.problem)
        assertEquals(2, plan.packages.size)
        assertEquals(78L * 1024, plan.totalBytes) // 32 KiB + 46 KiB
        assertEquals(102_228L, plan.availKb)
    }

    @Test
    fun `human byte sizes parse across units`() {
        assertEquals(46L * 1024, Parsers.humanBytes("46 KiB"))
        assertEquals((1.2 * 1024 * 1024).toLong(), Parsers.humanBytes("1.2 MiB"))
        assertEquals(512L, Parsers.humanBytes("512 B"))
        assertEquals(26_113L, Parsers.humanBytes("26113 B"))
        assertNull(Parsers.humanBytes(""))
        assertNull(Parsers.humanBytes("unknown"))
    }

    @Test
    fun `a package with an unknown size makes the total unknown, not wrong`() {
        val plan = Parsers.installPlan(
            mapOf(
                "pm" to "apk",
                "plan" to "(1/2) Installing kmod-nf-conntrack-netlink (6.12.94-r1)\n(2/2) Installing nlbwmon (1-r1)",
                "sizes" to "kmod-nf-conntrack-netlink|\nnlbwmon|46 KiB",
                "df" to "/dev/ubi0_3 111016 3948 102228 4% /overlay",
            )
        )
        assertEquals(2, plan.packages.size)
        assertNull(plan.totalBytes)
    }

    @Test
    fun `plan with no resolution reports a problem`() {
        val plan = Parsers.installPlan(
            mapOf("pm" to "opkg", "plan" to "Unknown package 'nlbwmon'.", "sizes" to "", "df" to "")
        )
        assertTrue(plan.problem != null)
        assertFalse(plan.packages.isNotEmpty())
    }
}
