package com.vivekkaushik.wrtpulse.ops

/**
 * The shell WrtPulse runs on the router. Every command lives here so the app can honour its
 * promise to show the exact command behind any action.
 *
 * The dashboard deliberately uses ONE batched script per tick: a phone on Wi-Fi pays 5-20 ms
 * per round trip, and eight separate execs per second would cost more than the data is worth.
 */
object Commands {

    const val SECTION = "___wrt___"

    /** `ubus call system board` — model, board id, OpenWrt release. */
    const val BOARD = "ubus call system board"

    /** One tick of dashboard state. Sections are delimited so the parser can split cheaply. */
    val DASHBOARD_TICK = listOf(
        "echo $SECTION info" to "ubus call system info",
        "echo $SECTION stat" to "grep '^cpu ' /proc/stat",
        "echo $SECTION netdev" to "cat /proc/net/dev",
        "echo $SECTION overlay" to "df -k /overlay | tail -n1",
        "echo $SECTION wan" to "ubus call network.interface.wan status 2>/dev/null || " +
            "ubus call network.interface.wan6 status 2>/dev/null || echo '{}'",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /** Wireless config as UCI key=value lines. */
    const val WIRELESS_CONFIG = "uci show wireless"

    /** Everything needed to build the client list, in one round trip. */
    val CLIENTS = listOf(
        "echo $SECTION leases" to "cat /tmp/dhcp.leases 2>/dev/null",
        "echo $SECTION neigh" to "ip neigh show",
        "echo $SECTION wifi" to "ubus call network.wireless status",
        "echo $SECTION assoc" to
            "for i in \$(iwinfo 2>/dev/null | grep ESSID | cut -d' ' -f1); do " +
            "echo \"# \$i\"; iwinfo \$i assoclist; done",
        "echo $SECTION blocked" to "uci show firewall 2>/dev/null | grep wrtpulse-block- || true",
        "echo $SECTION resv" to "uci show dhcp 2>/dev/null | grep -i host || true",
        "echo $SECTION nlbwbin" to "command -v nlbw 2>/dev/null || true",
        "echo $SECTION nlbw" to "nlbw -c json 2>/dev/null || true",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * Everything the install-consent dialog needs, one round trip: package manager, the
     * packages an install would pull in, the space each will occupy, and free overlay space.
     * The resolve runs once and is reused for the size lookup. `opkg update` first — its
     * package lists live in /tmp and vanish on reboot.
     */
    val NLBW_PLAN: String = listOf(
        "echo $SECTION pm",
        DETECT_PACKAGE_MANAGER,
        "if command -v apk >/dev/null 2>&1; then PLAN=\$(apk add --simulate nlbwmon 2>&1); " +
            "else opkg update >/dev/null 2>&1; PLAN=\$(opkg install --noaction nlbwmon 2>&1); fi",
        "echo $SECTION plan",
        "echo \"\$PLAN\"",
        "echo $SECTION sizes",
        // "<package>|<size>" per resolved package; apk reports human units, opkg raw bytes.
        "for p in \$(echo \"\$PLAN\" | sed -n 's/.*Installing \\([^ ]*\\).*/\\1/p'); do " +
            "if command -v apk >/dev/null 2>&1; then " +
            "s=\$(apk info --size \"\$p\" 2>/dev/null | sed -n '2p'); " +
            "else s=\$(opkg info \"\$p\" 2>/dev/null | sed -n 's/^Installed-Size: *//p' | head -1); " +
            "[ -z \"\$s\" ] && s=\$(opkg info \"\$p\" 2>/dev/null | sed -n 's/^Size: *//p' | head -1); " +
            "s=\"\$s B\"; fi; " +
            "echo \"\$p|\$s\"; done",
        "echo $SECTION df",
        "df -k /overlay | tail -n1",
    ).joinToString("; ")

    /** Installs, enables, and starts nlbwmon; succeeds only if the nlbw binary lands. */
    val NLBW_INSTALL: String =
        "if command -v apk >/dev/null; then apk add nlbwmon >/dev/null 2>&1; " +
        "else opkg install nlbwmon >/dev/null 2>&1; fi; " +
        "/etc/init.d/nlbwmon enable 2>/dev/null; /etc/init.d/nlbwmon start 2>/dev/null; " +
        "command -v nlbw"

    /** Cuts a client's WAN access with a named REJECT rule; reversed by [unblockClient]. */
    fun blockClient(mac: String): String = listOf(
        "uci add firewall rule >/dev/null",
        "uci set firewall.@rule[-1].name='wrtpulse-block-$mac'",
        "uci set firewall.@rule[-1].src='lan'",
        "uci set firewall.@rule[-1].dest='wan'",
        "uci set firewall.@rule[-1].src_mac='$mac'",
        "uci set firewall.@rule[-1].proto='all'",
        "uci set firewall.@rule[-1].target='REJECT'",
        "uci commit firewall",
        "/etc/init.d/firewall reload >/dev/null 2>&1",
    ).joinToString(" && ")

    fun unblockClient(mac: String): String =
        "s=\$(uci show firewall | grep \"wrtpulse-block-$mac\" | cut -d. -f2); " +
        "[ -n \"\$s\" ] && uci delete firewall.\$s && uci commit firewall && " +
        "/etc/init.d/firewall reload >/dev/null 2>&1; :"

    /** Busybox ships the applet as ether-wake; standalone installs name it etherwake. */
    fun wake(mac: String): String =
        "ether-wake -i br-lan '$mac' 2>/dev/null || etherwake -i br-lan '$mac'"

    /** Finds the dhcp host section holding a MAC, if any. */
    private fun hostSectionFor(mac: String) =
        "uci show dhcp 2>/dev/null | grep -i \"\\.mac='$mac'\" | cut -d. -f2 | head -1"

    /** DHCP reservation: updates the existing entry for this MAC, or adds one. */
    fun reserveIp(mac: String, ip: String, name: String): String =
        "s=\$(${hostSectionFor(mac)}); " +
        "if [ -n \"\$s\" ]; then uci set dhcp.\$s.ip='$ip'; else " +
        "uci add dhcp host >/dev/null; uci set dhcp.@host[-1].name='$name'; " +
        "uci set dhcp.@host[-1].mac='$mac'; uci set dhcp.@host[-1].ip='$ip'; fi && " +
        "uci commit dhcp && /etc/init.d/dnsmasq restart >/dev/null 2>&1"

    /** Drops the reservation so the client goes back to a pool address. */
    fun releaseIp(mac: String): String =
        "s=\$(${hostSectionFor(mac)}); " +
        "[ -n \"\$s\" ] && uci delete dhcp.\$s && uci commit dhcp && " +
        "/etc/init.d/dnsmasq restart >/dev/null 2>&1; :"

    /** Neighbour survey for the channel chart, through an interface already on the radio. */
    fun scan(radioIface: String) = "iwinfo $radioIface scan"

    /**
     * Survey a radio that has no interface of its own — a band with no SSID configured has
     * no netdev, so nothing can scan through it. Adds a station interface just long enough
     * to scan, then removes it. Nothing is written to uci.
     */
    fun scanViaTempInterface(phy: String): String {
        val temp = "wrtpulse-scan"
        return "iw dev $temp del >/dev/null 2>&1; " +
            "iw phy $phy interface add $temp type managed >/dev/null 2>&1 || " +
            "{ echo 'ERR add'; exit 1; }; " +
            "ip link set $temp up >/dev/null 2>&1; " +
            "R=\$(iwinfo $temp scan 2>&1); " +
            "iw dev $temp del >/dev/null 2>&1; " +
            "echo \"\$R\""
    }

    /**
     * Streams the system log. `logread -f` only emits entries logged from now on, which
     * leaves the screen blank on a quiet router, so the recent buffer is printed first.
     * If this build's logread lacks -l the dump fails quietly and the follow still runs.
     */
    const val LOG_BACKLOG = 200

    const val LOG_FOLLOW = "logread -l $LOG_BACKLOG 2>/dev/null; logread -f"

    /** Snapshot of the current wireless config, used as the "before" side of the diff. */
    const val WIRELESS_EXPORT = "uci export wireless"

    /**
     * Applies staged UCI changes atomically-ish: the batch either parses and runs, or nothing
     * is committed. Reload happens only after a successful commit.
     */
    fun uciBatch(operations: List<String>, commitPackage: String, reload: String): String =
        buildString {
            append("uci batch <<'WRTPULSE_EOF'\n")
            operations.forEach { append(it).append('\n') }
            append("WRTPULSE_EOF\n")
            append("uci commit $commitPackage && $reload")
        }

    /**
     * Appends the app's public key to dropbear's authorized_keys, idempotently.
     * The key line is base64 + spaces — safe inside single quotes.
     */
    fun installKey(publicLine: String): String {
        val f = "/etc/dropbear/authorized_keys"
        return "mkdir -p /etc/dropbear && touch $f && " +
            "(grep -qF '$publicLine' $f || echo '$publicLine' >> $f) && chmod 600 $f"
    }

    /** Package manager differs across releases: apk on 24.10+, opkg before it. */
    const val DETECT_PACKAGE_MANAGER = "command -v apk >/dev/null && echo apk || echo opkg"

    /**
     * Reboots a second after the exec returns, so the app gets a clean reply instead of
     * losing the channel mid-command and having to guess whether the reboot took.
     */
    const val REBOOT = "(sleep 1; reboot) >/dev/null 2>&1 & echo scheduled"

    /** Public, unauthenticated fixed-size download; the app times the round trip itself. */
    const val SPEEDTEST_HOST = "speed.cloudflare.com"

    /**
     * Pulls [bytes] from the speed-test endpoint on the router and discards it. Echoes the
     * byte count so a silent failure can't be mistaken for an instant download.
     */
    fun speedtestDownload(bytes: Long): String =
        "URL='https://$SPEEDTEST_HOST/__down?bytes=$bytes'; " +
        "{ uclient-fetch -q -O /dev/null \"\$URL\" || wget -q -O /dev/null \"\$URL\"; } && echo $bytes"

    /** Scratch payload for the upload leg; /tmp is RAM, so it is cleaned up straight after. */
    const val SPEEDTEST_UPLOAD_FILE = "/tmp/wrtpulse-speedtest.bin"

    /** Built before the timed leg so writing the file isn't counted as upload time. */
    fun speedtestPrepareUpload(bytes: Long): String =
        "dd if=/dev/zero of=$SPEEDTEST_UPLOAD_FILE bs=1024 count=${bytes / 1024} 2>/dev/null && echo ready"

    /**
     * curl first: OpenWrt's uclient-fetch accepts --post-file but stalls partway through a
     * large body and the far end resets, so it cannot measure an upload.
     */
    fun speedtestUpload(bytes: Long): String =
        "URL='https://$SPEEDTEST_HOST/__up'; " +
        "if command -v curl >/dev/null 2>&1; then " +
        "curl -s -o /dev/null --data-binary @$SPEEDTEST_UPLOAD_FILE \"\$URL\"; else " +
        "uclient-fetch -q -O /dev/null --post-file=$SPEEDTEST_UPLOAD_FILE \"\$URL\"; fi && echo $bytes"

    const val SPEEDTEST_CLEANUP = "rm -f $SPEEDTEST_UPLOAD_FILE"
}
