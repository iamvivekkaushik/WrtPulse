package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshAuth
import com.vivekkaushik.wrtpulse.net.SshClient
import com.vivekkaushik.wrtpulse.net.SshConnection
import com.vivekkaushik.wrtpulse.net.SshTarget
import com.vivekkaushik.wrtpulse.ops.Parsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiStoreTest {

    private val unusedClient = object : SshClient {
        override suspend fun probeHostKey(target: SshTarget) = error("unused")
        override suspend fun connect(target: SshTarget, auth: SshAuth, connectTimeoutMs: Long): SshConnection =
            error("unused")
    }

    private fun store() = WifiStore(RouterSession(SshTarget("t"), unusedClient, { error("unused") }))

    @Test
    fun `uci show wireless parses radios and ap networks`() {
        val uci = Parsers.uciShow(
            """
            wireless.radio0=wifi-device
            wireless.radio0.type='mac80211'
            wireless.radio0.band='2g'
            wireless.radio0.channel='11'
            wireless.radio0.htmode='HE40'
            wireless.radio1=wifi-device
            wireless.radio1.band='5g'
            wireless.radio1.htmode='HE80'
            wireless.default_radio0=wifi-iface
            wireless.default_radio0.device='radio0'
            wireless.default_radio0.mode='ap'
            wireless.default_radio0.ssid='Casa'
            wireless.default_radio0.encryption='sae'
            wireless.default_radio0.key='tr0ub4dor&3'
            wireless.@wifi-iface[1]=wifi-iface
            wireless.@wifi-iface[1].device='radio1'
            wireless.@wifi-iface[1].ssid='Casa-Guest'
            wireless.@wifi-iface[1].encryption='psk2'
            wireless.@wifi-iface[1].key='guestpass'
            wireless.@wifi-iface[1].disabled='1'
            wireless.mesh0=wifi-iface
            wireless.mesh0.device='radio1'
            wireless.mesh0.mode='mesh'
            wireless.mesh0.ssid='mesh-net'
            """.trimIndent()
        )
        val (radios, networks) = Parsers.wireless(uci)
        assertEquals(2, radios.size)
        val r0 = radios.first { it.section == "radio0" }
        assertEquals("2.4G", r0.band)
        assertEquals("11", r0.channel)
        assertEquals("HE40", r0.htmode)

        assertEquals(2, networks.size) // mesh skipped
        val guest = networks.first { it.ssid == "Casa-Guest" }
        assertEquals("@wifi-iface[1]", guest.section)
        assertTrue(guest.disabled)
        assertEquals("psk2", guest.encryption)
    }

    @Test
    fun `staging the saved value back un-stages it`() {
        val s = store()
        s.stage("default_radio0", "ssid", "Casa", "Cabana")
        assertEquals(1, s.pendingCount)
        s.stage("default_radio0", "ssid", "Casa", "Casa")
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `ops are uci set commands with quoting`() {
        val s = store()
        s.stage("default_radio0", "key", "old", "it's a secret")
        s.stage("radio0", "channel", "6", "11")
        assertEquals(
            listOf(
                "set wireless.default_radio0.key='it'\\''s a secret'",
                "set wireless.radio0.channel='11'",
            ),
            s.ops(),
        )
    }

    @Test
    fun `diff masks password values`() {
        val s = store()
        s.stage("default_radio0", "key", "hunter22", "correcthorse")
        val lines = s.diffLines().map { it.first }
        assertEquals("- default_radio0.key='••••••••'", lines[0])
        assertTrue(lines[1].startsWith("+ default_radio0.key='c"))
        assertFalse(lines[1].contains("correcthorse"))
    }

    @Test
    fun `value returns staged over saved`() {
        val s = store()
        assertEquals("Casa", s.value("x", "ssid", "Casa"))
        s.stage("x", "ssid", "Casa", "Cabana")
        assertEquals("Cabana", s.value("x", "ssid", "Casa"))
        s.revert()
        assertEquals("Casa", s.value("x", "ssid", "Casa"))
        assertEquals(0, s.pendingCount)
    }

    // ---- editing networks ----

    /**
     * Switching a network to open has to remove the key, not blank it: an empty
     * `option key ''` line keeps the old value visible in config history and reads as
     * "there is a password" to anything parsing the file.
     */
    @Test
    fun `clearing a key deletes the option instead of setting it empty`() {
        val s = store()
        s.stage("default_radio0", "encryption", "psk2", "none")
        s.stage("default_radio0", "key", "hunter22", "")
        assertTrue(s.ops().contains("delete wireless.default_radio0.key"))
        assertFalse(s.ops().any { it.startsWith("set wireless.default_radio0.key") })
        // The diff shows the removal as a deletion, with no replacement line.
        val lines = s.diffLines().map { it.first }
        assertTrue(lines.contains("- default_radio0.key='••••••••'"))
        assertFalse(lines.any { it.startsWith("+ default_radio0.key") })
    }

    @Test
    fun `changedIn reports only the section that was touched`() {
        val s = store()
        s.stage("default_radio0", "ssid", "Casa", "Cabana")
        assertTrue(s.changedIn("default_radio0"))
        assertFalse(s.changedIn("default_radio1"))
        // Prefix match must not spill: radio0 and radio0_guest are different sections.
        assertFalse(s.changedIn("default_radio"))
    }

    @Test
    fun `hidden is read back from uci`() {
        val uci = Parsers.uciShow(
            """
            wireless.quiet=wifi-iface
            wireless.quiet.device='radio0'
            wireless.quiet.ssid='Quiet'
            wireless.quiet.hidden='1'
            """.trimIndent()
        )
        val (_, networks) = Parsers.wireless(uci)
        assertTrue(networks.single().hidden)
    }

    @Test
    fun `a too-short passphrase is reported and an open network is not`() {
        val s = store()
        s.networks.add(
            com.vivekkaushik.wrtpulse.ops.WifiNetwork(
                section = "default_radio0", device = "radio0", ssid = "Casa",
                encryption = "psk2", key = "hunter22", disabled = false,
            )
        )
        assertTrue(s.problems().isEmpty())

        s.stage("default_radio0", "key", "hunter22", "abc")
        assertEquals(1, s.problems().size)
        assertTrue(s.problems().single().startsWith("Casa: a WPA password"))

        // Going open drops the requirement along with the key.
        s.stage("default_radio0", "encryption", "psk2", "none")
        s.stage("default_radio0", "key", "hunter22", "")
        assertTrue(s.problems().isEmpty())
    }

    @Test
    fun `a blank ssid is reported`() {
        val s = store()
        s.networks.add(
            com.vivekkaushik.wrtpulse.ops.WifiNetwork(
                section = "default_radio0", device = "radio0", ssid = "Casa",
                encryption = "none", key = "", disabled = false,
            )
        )
        s.stage("default_radio0", "ssid", "Casa", "   ")
        assertEquals(1, s.problems().size)
        assertTrue(s.problems().single().contains("needs a name"))
    }

    // ---- creating networks ----

    @Test
    fun `an access point draft writes one named section`() {
        val s = store()
        s.addDraft(devices = listOf("radio0"), mode = "ap", ssid = "Guest", encryption = "psk2", key = "hunter22")
        assertEquals(
            listOf(
                "set wireless.wrtpulse_guest=wifi-iface",
                "set wireless.wrtpulse_guest.device='radio0'",
                "set wireless.wrtpulse_guest.mode='ap'",
                "set wireless.wrtpulse_guest.ssid='Guest'",
                "set wireless.wrtpulse_guest.encryption='psk2'",
                "set wireless.wrtpulse_guest.key='hunter22'",
                "set wireless.wrtpulse_guest.network='lan'",
            ),
            s.ops(),
        )
        // An AP is bridged to a network that already exists — nothing else to configure.
        assertTrue(s.networkOps().isEmpty())
        assertTrue(s.firewallLines().isEmpty())
        assertEquals(1, s.pendingCount)
    }

    /**
     * Two additions in one batch is exactly where `@wifi-iface[-1]` stops being usable:
     * both would answer to it. Named sections keep them apart.
     */
    @Test
    fun `two drafts in one batch address different sections`() {
        val s = store()
        s.addDraft(listOf("radio0"), "ap", "Guest", "psk2", "hunter22")
        s.addDraft(listOf("radio1"), "ap", "Guest", "psk2", "hunter22")
        val created = s.ops().filter { it.endsWith("=wifi-iface") }
        assertEquals(
            listOf("set wireless.wrtpulse_guest=wifi-iface", "set wireless.wrtpulse_guest_2=wifi-iface"),
            created,
        )
    }

    @Test
    fun `a section name is derived from the ssid and stripped to uci characters`() {
        assertEquals("wrtpulse_casa_guest", WifiStore.sectionBase("Casa Guest!", "ap"))
        assertEquals("wrtpulse_ap", WifiStore.sectionBase("  ", "ap"))     // nothing usable left
        assertEquals("wrtpulse_caf", WifiStore.sectionBase("Café", "sta")) // non-ascii dropped
        assertEquals("wrtpulse_test", WifiStore.sectionBase("WrtPulse-Test", "ap")) // no double prefix
        assertEquals("a_2", WifiStore.free("a", setOf("a")))
        assertEquals("a_3", WifiStore.free("a", setOf("a", "a_2")))
    }

    @Test
    fun `an open draft carries no key option`() {
        val s = store()
        s.addDraft(listOf("radio0"), "ap", "Lobby", "none", "")
        assertFalse(s.ops().any { it.contains(".key=") })
        assertTrue(s.ops().contains("set wireless.wrtpulse_lobby.encryption='none'"))
    }

    @Test
    fun `a hidden draft sets hidden, and a client never does`() {
        val ap = store().apply { addDraft(listOf("radio0"), "ap", "Quiet", "psk2", "hunter22", hidden = true) }
        assertTrue(ap.ops().contains("set wireless.wrtpulse_quiet.hidden='1'"))
        val sta = store().apply { addDraft(listOf("radio0"), "sta", "Upstream", "psk2", "hunter22") }
        assertFalse(sta.ops().any { it.contains(".hidden=") })
    }

    /**
     * A station with no network behind it associates and then sits there with no address,
     * and one outside the wan zone gives the LAN no way out — so both must ride along.
     */
    @Test
    fun `a client draft also brings up wwan and joins the wan zone`() {
        val s = store()
        s.addDraft(listOf("radio0"), "sta", "Cafe WiFi", "psk2", "hunter22")
        assertTrue(s.ops().contains("set wireless.wrtpulse_cafe_wifi.mode='sta'"))
        assertTrue(s.ops().contains("set wireless.wrtpulse_cafe_wifi.network='wwan'"))
        assertEquals(
            listOf("set network.wwan=interface", "set network.wwan.proto='dhcp'"),
            s.networkOps(),
        )
        assertEquals(1, s.firewallLines().size)
        assertTrue(s.commitLine().contains("uci commit wireless && uci commit network"))
    }

    /**
     * This router already has a `wwan` uplink. Reusing that name would rewrite the interface
     * the router is currently reaching the internet through.
     */
    @Test
    fun `a client never reuses an interface name the router already has`() {
        val s = store()
        s.interfaces.addAll(listOf("lan", "wan", "wwan"))
        s.addDraft(listOf("radio0"), "sta", "Cafe WiFi", "psk2", "hunter22")
        assertTrue(s.ops().contains("set wireless.wrtpulse_cafe_wifi.network='wwan_2'"))
        assertEquals(
            listOf("set network.wwan_2=interface", "set network.wwan_2.proto='dhcp'"),
            s.networkOps(),
        )
        assertEquals(listOf("+ firewall.@zone[wan].network += 'wwan_2'"), s.firewallLines())
    }

    @Test
    fun `drafts show in the diff with the password masked, and can be dropped`() {
        val s = store()
        val draft = s.addDraft(listOf("radio1"), "ap", "Guest", "sae", "correcthorse")
        val lines = s.diffLines().map { it.first }
        assertTrue(lines.contains("+ wrtpulse_guest=wifi-iface"))
        assertTrue(lines.contains("+ wrtpulse_guest.ssid='Guest'"))
        assertFalse(lines.any { it.contains("correcthorse") })
        assertTrue(lines.any { it.startsWith("+ wrtpulse_guest.key='c") })

        s.removeDraft(draft.id)
        assertEquals(0, s.pendingCount)
        assertTrue(s.ops().isEmpty())
    }

    @Test
    fun `reverting drops drafts along with edits`() {
        val s = store()
        s.stage("radio0", "channel", "6", "11")
        s.addDraft(listOf("radio0"), "ap", "Guest", "psk2", "hunter22")
        assertEquals(2, s.pendingCount)
        s.revert()
        assertEquals(0, s.pendingCount)
    }

    @Test
    fun `a draft quotes like any other value`() {
        val s = store()
        s.addDraft(listOf("radio0"), "ap", "it's mine", "psk2", "hunter22")
        assertTrue(s.ops().contains("set wireless.wrtpulse_it_s_mine.ssid='it'\\''s mine'"))
    }

    @Test
    fun `client wifi-iface sections are read back from uci`() {
        val uci = Parsers.uciShow(
            """
            wireless.radio0=wifi-device
            wireless.radio0.band='2g'
            wireless.wwan=wifi-iface
            wireless.wwan.device='radio0'
            wireless.wwan.mode='sta'
            wireless.wwan.ssid='Cafe WiFi'
            wireless.wwan.encryption='psk2'
            wireless.wwan.network='wwan'
            """.trimIndent()
        )
        val (_, networks) = Parsers.wireless(uci)
        assertEquals(1, networks.size)
        assertTrue(networks[0].isClient)
        assertEquals("wwan", networks[0].network)
        assertEquals("Cafe WiFi", networks[0].ssid)
    }

    @Test
    fun `encryption labels`() {
        assertEquals("WPA3-SAE", Parsers.encryptionLabel("sae"))
        assertEquals("WPA2/3", Parsers.encryptionLabel("sae-mixed"))
        assertEquals("WPA2-PSK", Parsers.encryptionLabel("psk2+ccmp"))
        assertEquals("OPEN", Parsers.encryptionLabel("none"))
    }
}

class ScanCommandTest {

    /**
     * A band with no SSID has no netdev, so iwinfo has nothing to scan through. The temp
     * interface must be removed whether or not the scan itself worked.
     */
    @org.junit.Test
    fun `temporary scan interface is created and always torn down`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.scanViaTempInterface("phy0")
        org.junit.Assert.assertTrue(cmd.contains("iw phy phy0 interface add wrtpulse-scan type managed"))
        org.junit.Assert.assertTrue(cmd.contains("ip link set wrtpulse-scan up"))
        org.junit.Assert.assertTrue(cmd.contains("iwinfo wrtpulse-scan scan"))
        // Deleted before creating (stale leftovers) and after scanning, so nothing persists.
        org.junit.Assert.assertEquals(2, Regex("iw dev wrtpulse-scan del").findAll(cmd).count())
        // Output is captured first so the teardown cannot swallow the results.
        org.junit.Assert.assertTrue(cmd.indexOf("R=") < cmd.lastIndexOf("iw dev wrtpulse-scan del"))
        org.junit.Assert.assertTrue(cmd.trimEnd().endsWith("echo \"\$R\""))
        org.junit.Assert.assertTrue(!cmd.contains("uci"))   // nothing persistent is written
    }
}

class ScanParserTest {
    @org.junit.Test
    fun `iwinfo scan cells parse`() {
        val cells = com.vivekkaushik.wrtpulse.ops.Parsers.scanCells(
            """
            Cell 01 - Address: AA:BB:CC:DD:EE:01
                      ESSID: "neighbor-one"
                      Mode: Master  Channel: 6
                      Signal: -72 dBm  Quality: 38/70
                      Encryption: WPA2 PSK (CCMP)
            Cell 02 - Address: AA:BB:CC:DD:EE:02
                      ESSID: unknown
                      Mode: Master  Channel: 11
                      Signal: -85 dBm  Quality: 15/70
                      Encryption: none
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(2, cells.size)
        org.junit.Assert.assertEquals(6, cells[0].channel)
        org.junit.Assert.assertEquals(-72, cells[0].signalDbm)
        org.junit.Assert.assertEquals("neighbor-one", cells[0].ssid)
        org.junit.Assert.assertEquals(11, cells[1].channel)
    }
}

class CreateNetworkCommandTest {

    /**
     * The wan zone is anonymous in every stock config, so it is found by walking `@zone[i]`
     * rather than by grepping for the name — a firewall *rule* called wan must not match.
     */
    @org.junit.Test
    fun `wan zone is found by walking zone sections`() {
        val cmd = com.vivekkaushik.wrtpulse.ops.Commands.attachToWanZone("wwan")
        org.junit.Assert.assertTrue(cmd.contains("uci -q get firewall.@zone[\$i].name"))
        // del_list before add_list, so applying twice leaves one entry, not two.
        org.junit.Assert.assertTrue(cmd.indexOf("del_list") < cmd.indexOf("add_list"))
        org.junit.Assert.assertTrue(cmd.contains("uci commit firewall"))
        // Ends in a true, so "no wan zone" doesn't fail the whole apply.
        org.junit.Assert.assertTrue(cmd.trimEnd().endsWith("; :"))
    }

    @org.junit.Test
    fun `a batch can commit more than one package`() {
        val script = com.vivekkaushik.wrtpulse.ops.Commands.uciBatch(
            listOf("add wireless wifi-iface", "set network.wwan.proto='dhcp'"),
            listOf("wireless", "network"),
            reload = "wifi reload",
        )
        org.junit.Assert.assertTrue(script.startsWith("uci batch <<'WRTPULSE_EOF'"))
        org.junit.Assert.assertTrue(
            script.trimEnd().endsWith("uci commit wireless && uci commit network && wifi reload")
        )
    }
}

class NetworkInterfaceParserTest {
    @org.junit.Test
    fun `interface sections are listed, options are not`() {
        val uci = com.vivekkaushik.wrtpulse.ops.Parsers.uciShow(
            """
            network.loopback=interface
            network.loopback.proto='static'
            network.lan=interface
            network.@device[0]=device
            network.wwan=interface
            network.wwan.proto='dhcp'
            """.trimIndent()
        )
        org.junit.Assert.assertEquals(
            setOf("loopback", "lan", "wwan"),
            com.vivekkaushik.wrtpulse.ops.Parsers.networkInterfaces(uci),
        )
    }
}

class InterfaceRowTest {

    private val unusedClient = object : com.vivekkaushik.wrtpulse.net.SshClient {
        override suspend fun probeHostKey(target: com.vivekkaushik.wrtpulse.net.SshTarget) = error("unused")
        override suspend fun connect(
            target: com.vivekkaushik.wrtpulse.net.SshTarget,
            auth: com.vivekkaushik.wrtpulse.net.SshAuth,
            connectTimeoutMs: Long,
        ) = error("unused")
    }

    private fun store() = WifiStore(
        com.vivekkaushik.wrtpulse.net.RouterSession(
            com.vivekkaushik.wrtpulse.net.SshTarget("t"), unusedClient, { error("unused") },
        )
    ).apply {
        radios.add(com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", false))
        radios.add(com.vivekkaushik.wrtpulse.ops.WifiRadio("radio1", "5G", "36", "HE80", false))
        zones.add(com.vivekkaushik.wrtpulse.ops.FirewallZone("@zone[0]", "lan", listOf("lan")))
        zones.add(com.vivekkaushik.wrtpulse.ops.FirewallZone("@zone[1]", "wan", listOf("wan", "wwan")))
        zones.add(com.vivekkaushik.wrtpulse.ops.FirewallZone("@zone[2]", "guest", listOf("guest")))
        interfaces.addAll(listOf("lan", "wan", "wwan", "guest"))
    }

    private fun ap(
        section: String, device: String, ssid: String, network: String = "lan",
        disabled: Boolean = false, isolate: Boolean = false,
    ) = com.vivekkaushik.wrtpulse.ops.WifiNetwork(
        section, device, ssid, "psk2", "hunter22", disabled, "ap", network, false, isolate,
    )

    @Test
    fun `an access point row counts its clients and names where it lands`() {
        val s = store()
        s.networks.add(ap("default_radio1", "radio1", "Casa"))
        s.sectionIfnames["default_radio1"] = "phy1-ap0"
        s.clientCounts["phy1-ap0"] = 14
        val row = s.interfaceRows().single()
        assertEquals("14 clients · WPA2-PSK · lan", row.detail)
        // The radio's channel belongs on the row that sets it.
        assertEquals("5G · ch 36", row.bands)
        assertFalse(row.isClient)
        assertTrue(row.enabled)
    }

    /** The channel is a property of the radio, so only the first AP on it should claim one. */
    @Test
    fun `a second SSID on the same radio does not repeat the channel`() {
        val s = store()
        s.networks.add(ap("a", "radio1", "Casa"))
        s.networks.add(ap("b", "radio1", "Casa-IoT", network = "guest", isolate = true))
        val rows = s.interfaceRows()
        assertEquals("5G · ch 36", rows[0].bands)
        assertEquals("5G", rows[1].bands)
        assertEquals("0 clients · WPA2-PSK · guest zone · isolated", rows[1].detail)
    }

    @Test
    fun `a disabled network says so instead of counting clients`() {
        val s = store()
        s.networks.add(ap("g", "radio1", "Casa-Guest", network = "guest", disabled = true))
        val row = s.interfaceRows().single()
        assertFalse(row.enabled)
        assertEquals("disabled · WPA2-PSK · guest zone", row.detail)
    }

    @Test
    fun `a client in the wan zone is the uplink and reports its own signal`() {
        val s = store()
        s.networks.add(
            com.vivekkaushik.wrtpulse.ops.WifiNetwork(
                "wwan", "radio1", "Casa-Upstairs", "psk2", "k", false, "sta", "wwan",
            )
        )
        s.sectionIfnames["wwan"] = "phy1-sta0"
        s.live["phy1-sta0"] = com.vivekkaushik.wrtpulse.ops.IwinfoIface(
            "phy1-sta0", "Casa-Upstairs", "5A:8B:1C:44:E2:90", "Client", 100, -61, "WPA2 PSK",
        )
        val row = s.interfaceRows().single()
        assertTrue(row.isClient)
        assertTrue(row.isUplink)
        assertEquals("→ Casa-Upstairs · -61 dBm · wan zone", row.detail)
    }

    /** A client outside the wan zone is joined to something, but nothing routes through it. */
    @Test
    fun `a client in no zone is not marked as the uplink`() {
        val s = store()
        s.networks.add(
            com.vivekkaushik.wrtpulse.ops.WifiNetwork(
                "wwan2", "radio0", "Elsewhere", "psk2", "k", false, "sta", "spare",
            )
        )
        assertFalse(s.interfaceRows().single().isUplink)
    }

    @Test
    fun `one SSID on two radios is one draft, one row, and two sections`() {
        val s = store()
        val draft = s.addDraft(listOf("radio0", "radio1"), "ap", "Casa-Media", "sae", "correcthorse")
        assertEquals(2, draft.sections.size)
        assertEquals(2, draft.sections.values.distinct().size)
        val created = s.ops().filter { it.endsWith("=wifi-iface") }
        assertEquals(2, created.size)
        // Each section is pinned to its own radio.
        assertTrue(s.ops().contains("set wireless.${draft.sections["radio0"]}.device='radio0'"))
        assertTrue(s.ops().contains("set wireless.${draft.sections["radio1"]}.device='radio1'"))

        val row = s.interfaceRows().single { it.isNew }
        assertEquals("2.4G + 5G", row.bands)
        assertEquals(1, s.pendingCount)   // one thing the user made, not two
    }

    @Test
    fun `a client draft can decline the uplink zone`() {
        val s = store()
        s.addDraft(listOf("radio0"), "sta", "Guest net", "psk2", "hunter22", zone = "")
        assertTrue(s.firewallLines().isEmpty())
        // The network still has to exist, or the station has nowhere to get an address.
        assertTrue(s.networkOps().isNotEmpty())
    }

    /**
     * The form offers this as the default interface name, so it must not be one the router
     * is already using — the uplink it is reaching the internet through, typically.
     */
    @Test
    fun `the default uplink name steps around interfaces that exist`() {
        val s = store()
        assertEquals("wwan_2", s.freeUplinkName())
        assertTrue(s.networkExists("wwan"))
        assertFalse(s.networkExists("wwan_2"))
        // A staged uplink counts too, so two clients in one batch don't collide.
        s.addDraft(listOf("radio0"), "sta", "A", "psk2", "hunter22", network = "wwan_2")
        assertEquals("wwan_3", s.freeUplinkName())
    }

    @Test
    fun `a disabled uplink says so instead of reporting a stale signal`() {
        val s = store()
        s.networks.add(
            com.vivekkaushik.wrtpulse.ops.WifiNetwork(
                "wwan", "radio0", "VivekWifi", "psk2", "k", true, "sta", "wwan",
            )
        )
        assertEquals("→ VivekWifi · disabled · wan zone", s.interfaceRows().single().detail)
    }

    /**
     * The failure this came from: an SSID was created on a radio whose wifi-device had
     * disabled='1'. The app showed it as active, the network never appeared on any phone,
     * and LuCI said "Wireless is disabled". Nothing in the app mentioned the radio.
     */
    @Test
    fun `a network on a switched-off radio says so instead of looking active`() {
        val s = store()
        s.radios[0] = com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", true)
        s.networks.add(ap("guest", "radio0", "Casa-2G"))
        val row = s.interfaceRows().single()
        // The interface itself is not disabled — only the radio under it.
        assertTrue(row.enabled)
        assertFalse(row.radioOn)
        assertFalse(row.onAir)
        assertEquals("radio0 is off · WPA2-PSK · lan", row.detail)
    }

    @Test
    fun `staging the radio back on clears the warning without touching the interface`() {
        val s = store()
        s.radios[0] = com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", true)
        s.networks.add(ap("guest", "radio0", "Casa-2G"))
        assertFalse(s.radioEnabled("radio0"))

        s.stage("radio0", "disabled", "1", "0")
        assertTrue(s.radioEnabled("radio0"))
        assertTrue(s.interfaceRows().single().onAir)
        assertEquals(listOf("set wireless.radio0.disabled='0'"), s.ops())
    }

    @Test
    fun `a draft on a dead radio warns that nothing will broadcast`() {
        val s = store()
        s.radios[0] = com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", true)
        s.addDraft(listOf("radio0"), "ap", "Casa-2G", "psk2", "hunter22")
        val row = s.interfaceRows().single { it.isNew }
        assertFalse(row.radioOn)
        assertEquals("radio0 off · nothing will broadcast", row.detail)
    }

    /** One live radio is enough for the draft to reach the air. */
    @Test
    fun `a draft across two radios is not written off when only one is dead`() {
        val s = store()
        s.radios[0] = com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", true)
        s.addDraft(listOf("radio0", "radio1"), "ap", "Casa-Media", "psk2", "hunter22")
        val row = s.interfaceRows().single { it.isNew }
        assertFalse(row.radioOn)                       // not everything it asked for is up
        assertTrue(row.detail.contains("0 clients"))   // but it is not a dead network either
    }

    @Test
    fun `client isolation is written only when asked for`() {
        val s = store()
        s.addDraft(listOf("radio0"), "ap", "IoT", "psk2", "hunter22", isolate = true)
        assertTrue(s.ops().any { it.endsWith(".isolate='1'") })
        val plain = store().apply { addDraft(listOf("radio0"), "ap", "IoT", "psk2", "hunter22") }
        assertFalse(plain.ops().any { it.contains(".isolate") })
    }

    // ---- swipe-to-delete (design screen 3e) ----
    // These use the class's own store()/ap() fixtures. Adding a second "radio0" instead of
    // replacing the fixture's one made radioEnabled() find the enabled copy first, which is
    // how the radio-off case first passed for the wrong reason.

    private fun sta(section: String, device: String, ssid: String, network: String, disabled: Boolean) =
        com.vivekkaushik.wrtpulse.ops.WifiNetwork(
            section, device, ssid, "psk2", "hunter22", disabled, "sta", network, false, false,
        )

    @Test
    fun `a staged delete becomes a uci delete and counts as pending`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertEquals(listOf("delete wireless.wrtpulse"), s.ops())
        assertEquals(1, s.pendingCount)
    }

    @Test
    fun `staging the same delete twice still deletes once`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        s.stageDelete("wrtpulse")
        assertEquals(1, s.ops().size)
    }

    @Test
    fun `a delete can be undone in place`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertTrue(s.isDeleting("wrtpulse"))
        s.undoDelete("wrtpulse")
        assertFalse(s.isDeleting("wrtpulse"))
        assertEquals(0, s.pendingCount)
        assertTrue(s.ops().isEmpty())
    }

    /** Setting an option on a section the same batch is about to delete is noise. */
    @Test
    fun `edits to a deleted section never reach the batch`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stage("wrtpulse", "ssid", "WrtPulse", "Renamed")
        s.stageDelete("wrtpulse")
        assertEquals(listOf("delete wireless.wrtpulse"), s.ops())
        assertTrue(s.diffLines().none { it.first.contains("Renamed") })
    }

    @Test
    fun `the review sheet shows a delete as a removal`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertEquals(listOf("- wrtpulse=wifi-iface" to false), s.diffLines())
    }

    @Test
    fun `revert drops staged deletes with everything else`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        s.revert()
        assertEquals(0, s.pendingCount)
        assertTrue(s.ops().isEmpty())
    }

    /**
     * Every saved interface is swipeable, on the air or not — a deliberate departure from
     * design screen 3e. The protection is that the swipe only STAGES, and the review sheet
     * names the cost; see the deletionNotes tests below.
     */
    @Test
    fun `every saved interface offers the swipe`() {
        val off = store().apply { networks.add(ap("a", "radio0", "Off", disabled = true)) }
        assertTrue(off.interfaceRows().single().deletable)

        val live = store().apply { networks.add(ap("a", "radio0", "Live", disabled = false)) }
        assertTrue(live.interfaceRows().single().deletable)
    }

    @Test
    fun `an unsaved draft is not swipe-deletable`() {
        val s = store()
        s.addDraft(listOf("radio0"), "ap", "Guest", "psk2", "guestpass")
        val draft = s.interfaceRows().single { it.isNew }
        assertFalse(draft.deletable)
    }

    /** Deleting a live AP costs its clients the network, so the sheet has to say so. */
    @Test
    fun `deleting an on-air access point warns that clients lose it`() {
        val s = store()
        s.networks.add(ap("default_radio0", "radio0", "OpenWrt", disabled = false))
        s.stageDelete("default_radio0")
        val note = s.deletionNotes().single()
        assertTrue(note.contains("OpenWrt"))
        assertTrue(note.contains("on the air"))
        assertTrue(note.contains("this phone"))
    }

    /** The uplink is the one deletion that takes the router's own internet with it. */
    @Test
    fun `deleting the uplink warns about losing the router's internet`() {
        val s = store()
        s.networks.add(sta("wwan_2", "radio0", "VivekWifi_5G", "wwan", false))
        s.stageDelete("wwan_2")
        val notes = s.deletionNotes()
        assertTrue(notes.any { it.contains("upstream link") && it.contains("internet") })
        // and still the orphaned network stanza
        assertTrue(notes.any { it.contains("network.wwan") })
    }

    @Test
    fun `a switched-off interface gets no on-air warning`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertTrue(s.deletionNotes().none { it.contains("on the air") })
    }

    /** An enabled interface whose radio is off is not on the air, and is not described as such. */
    @Test
    fun `an interface on a switched-off radio is not called on-air`() {
        val s = store()
        s.radios[0] = com.vivekkaushik.wrtpulse.ops.WifiRadio("radio0", "2.4G", "11", "HT40", true)
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = false))
        s.stageDelete("wrtpulse")
        assertTrue(s.interfaceRows().single().deletable)
        assertTrue(s.deletionNotes().none { it.contains("on the air") })
    }

    @Test
    fun `the row reports it is being deleted so the list can mark it`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertTrue(s.interfaceRows().single().deleting)
    }

    /** A wifi-iface delete does not take the network stanza it pointed at. */
    @Test
    fun `deleting a client interface warns about the uplink it leaves behind`() {
        val s = store()
        s.networks.add(sta("wifinet2", "radio0", "VivekWifi", "wwan", true))
        s.stageDelete("wifinet2")
        val note = s.deletionNotes().single()
        assertTrue(note.contains("network.wwan"))
        assertTrue(note.contains("firewall"))
    }

    /**
     * A switched-off uplink is not carrying anything, so it must not claim that deleting it
     * takes the router offline — that warning is reserved for one that is actually up.
     */
    @Test
    fun `a switched-off uplink does not claim to be carrying the internet`() {
        val s = store()
        s.networks.add(sta("wifinet2", "radio0", "VivekWifi", "wwan", true))
        s.stageDelete("wifinet2")
        assertTrue(s.deletionNotes().none { it.contains("upstream link") })
    }

    @Test
    fun `deleting a switched-off AP has nothing to warn about`() {
        val s = store()
        s.networks.add(ap("wrtpulse", "radio0", "WrtPulse", disabled = true))
        s.stageDelete("wrtpulse")
        assertTrue(s.deletionNotes().isEmpty())
    }
}
