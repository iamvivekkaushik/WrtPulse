package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IwinfoParserTest {

    private val sample = """
        phy1-ap0  ESSID: "Casa"
                  Access Point: AA:BB:CC:DD:EE:01
                  Mode: Master  Channel: 36 (5.180 GHz)  HT Mode: HE80
                  Tx-Power: 23 dBm  Link Quality: unknown/70
                  Signal: unknown  Noise: -95 dBm
                  Encryption: WPA3 SAE (CCMP)
                  Type: nl80211  HW Mode(s): 802.11ac/ax

        phy1-sta0  ESSID: "Casa-Upstairs"
                  Access Point: 5A:8B:1C:44:E2:90
                  Mode: Client  Channel: 100 (5.500 GHz)  HT Mode: HE80
                  Signal: -61 dBm  Noise: -95 dBm
                  Encryption: WPA2 PSK (CCMP)
    """.trimIndent()

    @Test
    fun `each interface block is read, and only a station reports a signal`() {
        val ifaces = Parsers.iwinfo(sample)
        assertEquals(2, ifaces.size)

        val ap = ifaces.first { it.ifname == "phy1-ap0" }
        assertEquals("Casa", ap.essid)
        assertEquals(36, ap.channel)
        assertEquals("AA:BB:CC:DD:EE:01", ap.bssid)
        // An AP prints "Signal: unknown"; that must not become a number.
        assertNull(ap.signalDbm)
        assertTrue(!ap.isClient)

        val sta = ifaces.first { it.ifname == "phy1-sta0" }
        assertTrue(sta.isClient)
        assertEquals(-61, sta.signalDbm)
        assertEquals(100, sta.channel)
    }

    @Test
    fun `a hidden interface name is not carried through as the word unknown`() {
        val ifaces = Parsers.iwinfo("phy0-ap0  ESSID: unknown\n          Mode: Master  Channel: 6")
        assertEquals("", ifaces.single().essid)
    }
}

class ScanCellDetailTest {

    @Test
    fun `scan cells carry the bssid and a readable security tag`() {
        val cells = Parsers.scanCells(
            """
            Cell 01 - Address: AA:BB:CC:DD:EE:01
                      ESSID: "Casa-Upstairs"
                      Mode: Master  Channel: 100
                      Signal: -61 dBm  Quality: 49/70
                      Encryption: WPA2 PSK (CCMP)
            Cell 02 - Address: C0:25:E9:71:0A:3D
                      ESSID: "CoffeeBar"
                      Mode: Master  Channel: 48
                      Signal: -82 dBm  Quality: 20/70
                      Encryption: none
            """.trimIndent()
        )
        assertEquals("AA:BB:CC:DD:EE:01", cells[0].bssid)
        assertEquals("WPA2", cells[0].encryption)
        assertEquals(3, cells[0].bars)
        assertEquals("OPEN", cells[1].encryption)
        assertEquals(1, cells[1].bars)
    }

    @Test
    fun `a hidden network is not offered as one you can join`() {
        val cells = Parsers.scanCells(
            "Cell 01 - Address: AA:BB:CC:DD:EE:01\n ESSID: unknown\n Mode: Master  Channel: 6\n Signal: -70 dBm"
        )
        assertTrue(!cells.single().named)
    }

    @Test
    fun `security labels`() {
        assertEquals("WPA3", Parsers.securityLabel("WPA3 SAE (CCMP)"))
        assertEquals("WPA3", Parsers.securityLabel("mixed WPA2/WPA3 PSK/SAE (CCMP)"))
        assertEquals("WPA2", Parsers.securityLabel("WPA2 PSK (CCMP)"))
        assertEquals("OPEN", Parsers.securityLabel("none"))
        assertEquals("OPEN", Parsers.securityLabel(""))
    }
}

class FirewallZoneTest {

    @Test
    fun `zones list the networks they cover`() {
        val uci = Parsers.uciShow(
            """
            firewall.@zone[0]=zone
            firewall.@zone[0].name='lan'
            firewall.@zone[0].network='lan'
            firewall.@zone[1]=zone
            firewall.@zone[1].name='wan'
            firewall.@zone[1].network='wan wan6 wwan'
            firewall.@rule[3]=rule
            firewall.@rule[3].name='wan'
            """.trimIndent()
        )
        val zones = Parsers.firewallZones(uci)
        // The rule called "wan" is not a zone and must not appear.
        assertEquals(2, zones.size)
        assertEquals(listOf("wan", "wan6", "wwan"), zones.first { it.name == "wan" }.networks)
    }
}

class ChannelPlanTest {

    private fun cell(channel: Int) = ScanCell(channel, -60, "n$channel")

    /**
     * 2.4 GHz channels are 5 MHz apart and 20 MHz wide, so a neighbour four channels away is
     * interference you cannot take turns with — worse than one sharing your channel.
     */
    @Test
    fun `overlap counts against a channel more than sharing it does`() {
        val cells = listOf(cell(1), cell(1), cell(3), cell(9))
        val advice = ChannelPlan.advise("2.4G", cells)!!
        // ch1 carries two co-channel neighbours plus ch3 bleeding in; ch6 is hit by both
        // ch3 and ch9; ch11 only by ch9. Least interfered-with wins even though nothing
        // sits on ch6 at all.
        assertEquals(11, advice.channel)
        assertEquals(0, advice.onChannel)
        assertEquals(1, advice.overlapping)
    }

    @Test
    fun `an empty band gives no advice at all`() {
        assertNull(ChannelPlan.advise("2.4G", emptyList()))
    }

    @Test
    fun `5 GHz only counts neighbours on the same channel`() {
        val advice = ChannelPlan.advise("5G", listOf(cell(36), cell(36), cell(40)))!!
        assertEquals(0, advice.onChannel)
        assertEquals(0, advice.overlapping)
        assertTrue(advice.channel != 36 && advice.channel != 40)
    }

    @Test
    fun `the summary reads as a sentence fragment`() {
        assertEquals("2 neighbors, no overlap", ChannelAdvice(11, 2, 0).summary)
        assertEquals("1 neighbor, 3 overlapping", ChannelAdvice(6, 1, 3).summary)
    }

    /** On a busy band "clearest" next to "6 neighbors" reads as a contradiction. */
    @Test
    fun `the headline only claims clear when the channel really is`() {
        assertEquals("ch 44 is clear", ChannelAdvice(44, 0, 0).headline)
        assertEquals("ch 11 least busy — 6 neighbors, 1 overlapping", ChannelAdvice(11, 6, 1).headline)
    }

    /** Changing the width must not change the radio's generation. */
    @Test
    fun `widths keep whatever prefix the driver already uses`() {
        assertEquals(listOf("HE20", "HE40", "HE80", "HE160"), ChannelPlan.widths("HE80", "5G"))
        assertEquals(listOf("HT20", "HT40"), ChannelPlan.widths("HT40", "2.4G"))
        // No htmode set yet: fall back to something valid for the band.
        assertEquals(listOf("HT20", "HT40"), ChannelPlan.widths("", "2.4G"))
        assertEquals("80 MHz", ChannelPlan.widthLabel("VHT80"))
        assertEquals("—", ChannelPlan.widthLabel(""))
    }

    @Test
    fun `2 point 4 GHz only offers the three non-overlapping channels`() {
        assertEquals(listOf(1, 6, 11), ChannelPlan.candidates("2.4G"))
    }
}

class UpstreamTest {

    /** wan is wired and down; the router is reaching the internet through a Wi-Fi client. */
    private val dump = """
        {"interface": [
          {"interface": "lan", "up": true, "l3_device": "br-lan", "proto": "static",
           "ipv4-address": [{"address": "192.168.2.1", "mask": 24}], "route": []},
          {"interface": "wan", "up": false, "l3_device": "eth1", "proto": "dhcp",
           "route": [{"target": "0.0.0.0", "mask": 0, "nexthop": "192.168.1.1"}]},
          {"interface": "wwan", "up": true, "l3_device": "phy0-sta0", "proto": "dhcp",
           "ipv4-address": [{"address": "192.168.29.51", "mask": 24}],
           "route": [{"target": "0.0.0.0", "mask": 0, "nexthop": "192.168.29.1"}]}
        ]}
    """.trimIndent()

    /**
     * The whole point: the upstream is whichever interface holds the default route. Asking
     * for the one named "wan" reported a dead wired link while the traffic went over Wi-Fi.
     */
    @Test
    fun `the upstream is the interface holding the default route, not the one called wan`() {
        val up = Parsers.upstream(dump)!!
        assertEquals("wwan", up.name)
        assertEquals("phy0-sta0", up.device)
        assertEquals("192.168.29.51", up.address)
        assertEquals("dhcp", up.proto)
    }

    @Test
    fun `an interface that is down is never the upstream`() {
        // wan carries a default route but is down; it must not win.
        assertTrue(Parsers.upstream(dump)!!.name != "wan")
    }

    @Test
    fun `a wireless upstream is named by the network it joined`() {
        val up = Parsers.upstream(dump, mapOf("phy0-sta0" to "VivekWifi"))!!
        assertEquals("VivekWifi", up.ssid)
        assertTrue(up.wireless)
        // A wired upstream has no SSID and must not claim to be wireless.
        assertFalse(Parsers.upstream(dump, mapOf("eth1" to "nope"))!!.wireless)
    }

    /** A v6-only default is better than nothing, but a v4 one is what the card can show. */
    @Test
    fun `a v4 default route wins over a v6-only one`() {
        val both = """
            {"interface": [
              {"interface": "wan6", "up": true, "l3_device": "eth1", "proto": "dhcpv6",
               "route": [{"target": "::", "mask": 0}]},
              {"interface": "wan", "up": true, "l3_device": "eth1", "proto": "dhcp",
               "ipv4-address": [{"address": "10.0.0.2", "mask": 24}],
               "route": [{"target": "0.0.0.0", "mask": 0}]}
            ]}
        """.trimIndent()
        assertEquals("wan", Parsers.upstream(both)!!.name)

        val v6only = """
            {"interface": [
              {"interface": "wan6", "up": true, "l3_device": "eth1", "proto": "dhcpv6",
               "route": [{"target": "::", "mask": 0}]}
            ]}
        """.trimIndent()
        assertEquals("wan6", Parsers.upstream(v6only)!!.name)
    }

    @Test
    fun `no default route anywhere means no upstream`() {
        val none = """{"interface": [{"interface": "lan", "up": true, "route": []}]}"""
        assertNull(Parsers.upstream(none))
        assertNull(Parsers.upstream("{}"))
        assertNull(Parsers.upstream("not json"))
    }

    /** A route to a subnet is not a default route, however tempting the shape. */
    @Test
    fun `a non-default route does not make an interface the upstream`() {
        val subnet = """
            {"interface": [{"interface": "lan", "up": true, "l3_device": "br-lan",
             "route": [{"target": "192.168.9.0", "mask": 24}]}]}
        """.trimIndent()
        assertNull(Parsers.upstream(subnet))
    }

    @Test
    fun `essid lines map interfaces to the network they are on`() {
        val map = Parsers.iwinfoEssids(
            """
            phy0-sta0 ESSID: "VivekWifi"
            phy1-ap0  ESSID: "OpenWrt"
            phy0-ap0  ESSID: unknown
            """.trimIndent()
        )
        assertEquals(mapOf("phy0-sta0" to "VivekWifi", "phy1-ap0" to "OpenWrt"), map)
    }
}

class MultipleUpstreamTest {

    /** A wired link and a Wi-Fi client both holding default routes — a failover setup. */
    private val two = """
        {"interface": [
          {"interface": "wan", "up": true, "l3_device": "eth1", "proto": "dhcp", "metric": 10,
           "ipv4-address": [{"address": "10.0.0.2", "mask": 24}],
           "route": [{"target": "0.0.0.0", "mask": 0}]},
          {"interface": "wan6", "up": true, "l3_device": "eth1", "proto": "dhcpv6", "metric": 10,
           "route": [{"target": "::", "mask": 0}]},
          {"interface": "wwan_2", "up": true, "l3_device": "phy0-sta0", "proto": "dhcp", "metric": 30,
           "ipv4-address": [{"address": "192.168.1.126", "mask": 24}],
           "route": [{"target": "0.0.0.0", "mask": 0}]}
        ]}
    """.trimIndent()

    /**
     * wan and wan6 are one cable with a v4 and a v6 default route. Counting them as two
     * upstreams would invent a redundancy the router does not have.
     */
    @Test
    fun `a dual-stack link counts once, not twice`() {
        val ups = Parsers.upstreams(two)
        assertEquals(2, ups.size)
        assertEquals(listOf("eth1", "phy0-sta0"), ups.map { it.device })
        // The v4 side is the one kept, because it is the one with an address to show.
        assertEquals("wan", ups[0].name)
        assertEquals("10.0.0.2", ups[0].address)
        assertTrue(ups[0].hasV4)
    }

    /** Lowest metric is what the kernel actually uses, so it leads. */
    @Test
    fun `upstreams are ordered by metric`() {
        val ups = Parsers.upstreams(two, mapOf("phy0-sta0" to "Airtel"))
        assertEquals("wan", ups[0].name)
        assertEquals(10, ups[0].metric)
        assertEquals("wwan_2", ups[1].name)
        assertEquals(30, ups[1].metric)
        assertEquals("Airtel", ups[1].ssid)
    }

    /** An IPv6-only link is real but cannot lead a card that shows a v4 address. */
    @Test
    fun `a v6-only link sorts behind a v4 one whatever its metric`() {
        val mixed = """
            {"interface": [
              {"interface": "wan6", "up": true, "l3_device": "eth1", "proto": "dhcpv6", "metric": 1,
               "route": [{"target": "::", "mask": 0}]},
              {"interface": "wwan", "up": true, "l3_device": "phy0-sta0", "proto": "dhcp", "metric": 99,
               "ipv4-address": [{"address": "192.168.1.126", "mask": 24}],
               "route": [{"target": "0.0.0.0", "mask": 0}]}
            ]}
        """.trimIndent()
        val ups = Parsers.upstreams(mixed)
        assertEquals(listOf("wwan", "wan6"), ups.map { it.name })
        assertFalse(ups[1].hasV4)
    }

    @Test
    fun `upstream is just the first of them`() {
        assertEquals(Parsers.upstreams(two).first(), Parsers.upstream(two))
    }
}
