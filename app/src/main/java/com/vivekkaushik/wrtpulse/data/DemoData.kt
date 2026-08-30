package com.vivekkaushik.wrtpulse.data

import androidx.compose.ui.graphics.Color
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

enum class RouterStatus { Online, Reconnecting, Offline, Saved }

data class Router(
    val name: String,
    val model: String,
    val tag: String,
    val status: RouterStatus,
    val wanIp: String?,
    val detail: String,      // third line on the list card ("23 clients", "snapshot r26550", "last seen 2 d ago")
    val switcherDetail: String,
    val latencyMs: Int?,     // null while not connected
)

data class Client(
    val name: String,
    val ip: String,
    val mac: String,
    val network: String,     // "Casa · 5G"
    val bars: Int,           // 1..4 wireless signal, -1 wired, -2 blocked
    val barColor: Color = Wrt.Green,
    val editable: Boolean = false,
    val blocked: Boolean = false,
    val offline: Boolean = false,
    val signalDbm: Int? = null,
    val leaseLabel: String? = null,
    val downMbps: Float? = null,
    val upMbps: Float? = null,
    /** Cumulative traffic this nlbwmon accounting period, when nlbwmon is installed. */
    val usageDown: Long? = null,
    val usageUp: Long? = null,
    val apps: List<Pair<String, Long>> = emptyList(),
    /** Address reserved for this MAC in dnsmasq, when one is set. */
    val staticIp: String? = null,
) {
    val usageTotal: Long get() = (usageDown ?: 0) + (usageUp ?: 0)
}

data class Ssid(
    val name: String,
    val bands: List<String>,
    val clients: Int,
    val enabled: Boolean,
)

data class Snippet(val title: String, val command: String, val highlighted: Boolean = false)

data class LogTemplate(val color: Color, val src: String, val msg: String, val tok: String)

data class LogLine(val time: String, val color: Color, val src: String, val msg: String, val tok: String, val raw: String = "")

object Demo {
    val routers = listOf(
        Router("home.gw", "GL.iNet GL-MT6000", "HOME", RouterStatus.Online, "82.44.19.7", "23 clients", "GL-MT6000 · 23 clients", null),
        Router("office.gw", "Linksys WRT3200ACM", "OFFICE", RouterStatus.Online, "195.13.88.2", "41 clients", "WRT3200ACM · 41 clients", 18),
        Router("bpi-r3-lab", "Banana Pi BPI-R3", "LAB", RouterStatus.Reconnecting, "10.0.30.1", "snapshot r26550", "BPI-R3 · reconnecting…", null),
        Router("parents-ap", "TP-Link Archer C7 v2", "PARENTS", RouterStatus.Offline, null, "last seen 2 d ago", "Archer C7 · last seen 2 d", null),
    )

    val clients = listOf(
        Client("pixel-8", "192.168.1.34", "aa:5c:1e:88:04:2b", "Casa · 5G", 4, editable = true),
        Client("macbook-pro", "192.168.1.21", "3c:22:fb:90:11:5e", "Casa · 5G", 3),
        Client("chromecast-living", "192.168.1.48", "54:60:09:2c:8a:11", "Casa-IoT · 2.4G", 2),
        Client("esp32-sensor", "192.168.1.87", "24:6f:28:ae:52:c0", "Casa-IoT · 2.4G", 1, barColor = Wrt.Amber),
        Client("synology-nas", "192.168.1.10", "00:11:32:6f:b2:44", "LAN · 2.5G", -1),
        Client("unknown-device", "—", "e8:9f:80:1d:33:7a", "", -2, blocked = true),
    )

    val ssids = listOf(
        Ssid("Casa", listOf("2.4G", "5G"), 14, true),
        Ssid("Casa-IoT", listOf("2.4G"), 8, true),
        Ssid("Casa-Guest", listOf("5G"), 0, false),
    )

    val snippets = listOf(
        Snippet("Restart Wi-Fi", "wifi reload", highlighted = true),
        Snippet("Show DHCP leases", "cat /tmp/dhcp.leases"),
        Snippet("Live conntrack", "watch -n1 'wc -l /proc/net/nf_conntrack'"),
        Snippet("Restart dnsmasq", "/etc/init.d/dnsmasq restart"),
        Snippet("Free space", "df -h /overlay"),
    )

    val logPool = listOf(
        LogTemplate(Wrt.Blue, "dnsmasq-dhcp", "DHCPACK(br-lan)", "192.168.1.34 pixel-8"),
        LogTemplate(Wrt.Amber, "hostapd", "wlan1: STA associated", "aa:5c:1e:88:04:2b"),
        LogTemplate(Wrt.TextTertiary, "kernel", "br-lan: port 3 entered forwarding state", ""),
        LogTemplate(Wrt.Red, "firewall", "DROP wan in tcp dpt:23 src", "203.0.113.9"),
        LogTemplate(Wrt.Blue, "dnsmasq-dhcp", "DHCPREQUEST(br-lan)", "192.168.1.87 esp32-a1"),
        LogTemplate(Wrt.Accent, "dropbear", "Pubkey auth succeeded for root from", "192.168.1.34"),
        LogTemplate(Wrt.Amber, "hostapd", "wlan0: STA disassociated", "3c:22:fb:90:11:5e"),
        LogTemplate(Wrt.TextTertiary, "kernel", "eth1: link up, 2500 Mbps full duplex", ""),
        LogTemplate(Wrt.Blue, "dnsmasq", "using nameserver", "9.9.9.9#53"),
        LogTemplate(Wrt.Red, "firewall", "REJECT lan→wan udp dpt:445 src", "192.168.1.62"),
    )

    const val SAVED_HOST_KEY = "SHA256:Ml3f9K2vQ8xJ4nP7wRsT1uYbC6dE0aGhIjLmN5oZqXk"
    const val NEW_HOST_KEY = "SHA256:e177vBqU3cW9kD5mA8sF2gH6jK1lZ4xN7pR0tY3uQmR"
}
