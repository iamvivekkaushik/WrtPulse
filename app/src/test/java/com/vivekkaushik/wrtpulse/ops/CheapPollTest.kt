package com.vivekkaushik.wrtpulse.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cheap shapes the polling commands moved to — `iw dev`, `iw station dump`, rpcd's
 * txpowerlist, netifd's wireless status — captured on a TP-Link Deco M4R v1 (OpenWrt 25.12.5).
 * Each parser still reads the iwinfo shape too; those cases live in ParsersTest.
 */
private val IW_DEV = """
    phy#1
    	Interface phy1-ap0
    		ifindex 13
    		wdev 0x100000001
    		addr a8:6e:84:93:80:38
    		ssid VivekWifi
    		type AP
    		channel 13 (2472 MHz), width: 20 MHz, center1: 2472 MHz
    		txpower 18.00 dBm
    		multicast TXQ:
    			qsz-byt	qsz-pkt	flows	drops	marks	overlmt	hashcol	tx-bytes	tx-packets
    			0	0	178	0	0	0	1	41350		184
    phy#0
    	Interface phy0-sta0
    		ifindex 11
    		wdev 0x3
    		addr 0a:6e:84:93:80:37
    		ssid JioFiber-Upstairs
    		type managed
    		channel 44 (5220 MHz), width: 80 MHz, center1: 5210 MHz
    		txpower 23.00 dBm
    	Interface phy0-ap0
    		ifindex 9
    		wdev 0x1
    		addr a8:6e:84:93:80:37
    		ssid VivekWifi
    		type AP
    		channel 36 (5180 MHz), width: 80 MHz, center1: 5210 MHz
    		txpower 30.00 dBm
    # link phy1-ap0
    Not connected.
    # link phy0-sta0
    Connected to 8c:ea:1b:12:34:56 (on phy0-sta0)
    	SSID: JioFiber-Upstairs
    	freq: 5220
    	RX: 1284381 bytes (9271 packets)
    	TX: 233490 bytes (2011 packets)
    	signal: -58 dBm
    	rx bitrate: 390.0 MBit/s VHT-MCS 9 80MHz short GI VHT-NSS 1
    	tx bitrate: 433.3 MBit/s VHT-MCS 9 80MHz short GI VHT-NSS 1
    # link phy0-ap0
    Not connected.
""".trimIndent()

private val STATION_DUMP = """
    # phy0-ap0
    Station b8:b4:09:b4:b4:0a (on phy0-ap0)
    	inactive time:	2530 ms
    	rx bytes:	713045
    	signal:  	-52 [-59, -53, -77, -77] dBm
    	signal avg:	-55 [-64, -58, -77, -77] dBm
    	tx bitrate:	7.2 MBit/s VHT-MCS 0 short GI VHT-NSS 1
    	rx bitrate:	292.5 MBit/s VHT-MCS 7 80MHz VHT-NSS 1
    	connected time:	4223 seconds

    Station 6e:42:52:65:8a:e2 (on phy0-ap0)
    	signal:  	-63 [-84, -65, -77, -77] dBm
    	tx bitrate:	97.6 MBit/s VHT-MCS 2 80MHz short GI VHT-NSS 1
    	rx bitrate:	195.1 MBit/s VHT-MCS 2 80MHz short GI VHT-NSS 2
    # phy0-ap1
    # phy1-ap0
    Station e8:16:56:26:00:bd (on phy1-ap0)
    	signal:  	-55 [-55, -70] dBm
    	tx bitrate:	54.0 MBit/s
    	rx bitrate:	6.0 MBit/s
""".trimIndent()

private val TXPOWER_UBUS = """
    # phy0-ap0
    {
    	"results": [
    		{
    			"dbm": 0,
    			"mw": 1,
    			"active": false
    		},
    		{
    			"dbm": 29,
    			"mw": 794,
    			"active": false
    		},
    		{
    			"dbm": 30,
    			"mw": 1000,
    			"active": true
    		}
    	]
    }
    # phy1-ap0
    {
    	"results": [
    		{
    			"dbm": 18,
    			"mw": 63,
    			"active": true
    		},
    		{
    			"dbm": 10,
    			"mw": 10,
    			"active": false
    		}
    	]
    }
    # phy1-ap1
    {
    	"results": [
    	]
    }
""".trimIndent()

private val WIRELESS_STATUS = """
    {
    	"radio0": {
    		"up": true,
    		"config": { "band": "5g", "channel": "36" },
    		"interfaces": [
    			{ "section": "default_radio0", "ifname": "phy0-ap0", "config": { "mode": "ap", "ssid": "VivekWifi", "network": [ "lan" ] } },
    			{ "section": "wwan_sta", "ifname": "phy0-sta0", "config": { "mode": "sta", "ssid": "JioFiber-Upstairs", "network": [ "wwan" ] } }
    		]
    	},
    	"radio1": {
    		"up": true,
    		"config": { "band": "2g", "channel": "13" },
    		"interfaces": [
    			{ "section": "default_radio1", "ifname": "phy1-ap0", "config": { "mode": "ap", "ssid": "VivekWifi", "network": [ "lan" ] } },
    			{ "section": "iot", "config": { "mode": "ap", "ssid": "Vivek_IoT", "network": [ "iot" ] } }
    		]
    	}
    }
""".trimIndent()

class CheapPollTest {

    @Test
    fun `iw dev plus link blocks read as iwinfo interfaces`() {
        val list = Parsers.iwinfo(IW_DEV)
        assertEquals(listOf("phy1-ap0", "phy0-sta0", "phy0-ap0"), list.map { it.ifname })
        val ap = list.first { it.ifname == "phy0-ap0" }
        assertEquals("Master", ap.mode)
        assertEquals("VivekWifi", ap.essid)
        assertEquals("A8:6E:84:93:80:37", ap.bssid)
        assertEquals(36, ap.channel)
        assertEquals(30, ap.txPowerDbm)
        assertNull(ap.signalDbm)
        assertFalse(ap.isClient)
        val sta = list.first { it.ifname == "phy0-sta0" }
        assertTrue(sta.isClient)
        assertEquals("8C:EA:1B:12:34:56", sta.bssid)
        assertEquals(-58, sta.signalDbm)
        assertEquals(44, sta.channel)
        assertEquals(23, sta.txPowerDbm)
        assertEquals("JioFiber-Upstairs", sta.essid)
    }

    @Test
    fun `station dump gives the same stations iwinfo assoclist did`() {
        val s = Parsers.stations(STATION_DUMP)
        assertEquals(3, s.size)
        val first = s[0]
        assertEquals("phy0-ap0", first.iface)
        assertEquals("b8:b4:09:b4:b4:0a", first.mac)
        assertEquals(-52, first.signalDbm)
        assertEquals(7.2, first.txMbps, 0.001)
        assertEquals(292.5, first.rxMbps, 0.001)
        assertEquals("phy1-ap0", s[2].iface)
        assertEquals(-55, s[2].signalDbm)
        assertEquals(54.0, s[2].txMbps, 0.001)
        assertEquals(mapOf("phy0-ap0" to 2, "phy1-ap0" to 1), s.groupingBy { it.iface }.eachCount())
    }

    @Test
    fun `rpcd txpowerlist json reads like the iwinfo listing`() {
        val lists = Parsers.txpowerLists(TXPOWER_UBUS)
        assertEquals(listOf(0, 29, 30), lists["phy0-ap0"])
        assertEquals(listOf(10, 18), lists["phy1-ap0"])
        assertEquals(emptyList<Int>(), lists["phy1-ap1"])
    }

    @Test
    fun `netifd wireless status maps every named interface to its ssid`() {
        val e = Parsers.essids(WIRELESS_STATUS)
        assertEquals(mapOf("phy0-ap0" to "VivekWifi", "phy0-sta0" to "JioFiber-Upstairs", "phy1-ap0" to "VivekWifi"), e)
        // The old shapes still read.
        assertEquals(mapOf("phy0-ap0" to "Casa"), Parsers.essids("Interface phy0-ap0\n\tssid Casa"))
        assertEquals(mapOf("wlan0" to "Casa"), Parsers.essids("wlan0     ESSID: \"Casa\""))
    }

    @Test
    fun `polling commands no longer start the iwinfo binary on a router with iw and rpcd`() {
        // iwinfo remains only behind a fallback branch.
        listOf(Commands.IWINFO, Commands.ASSOC_COUNTS, Commands.TXPOWER_LISTS, Commands.PHY_NAMES, Commands.CLIENTS).forEach { cmd ->
            assertTrue(cmd, cmd.contains("iwinfo"))
            assertTrue(cmd, cmd.contains("else iwinfo") || cmd.contains("|| n=\$(iwinfo") || cmd.contains("else iwinfo"))
        }
        assertTrue(Commands.DASHBOARD_TICK.contains("ubus call network.wireless status 2>/dev/null || iw dev"))
        assertTrue(Commands.CLIENTS.contains(Commands.ASSOC_COUNTS))
        assertFalse(Commands.ASSOC_COUNTS.contains("grep ESSID"))
    }
}
