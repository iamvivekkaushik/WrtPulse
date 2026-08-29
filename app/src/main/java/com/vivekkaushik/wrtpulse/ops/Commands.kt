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
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /** Neighbour survey for the channel chart. */
    fun scan(radioIface: String) = "iwinfo $radioIface scan"

    /** Streams the system log. Cancelling the collector closes the channel. */
    const val LOG_FOLLOW = "logread -f"

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

    /** Package manager differs across releases: apk on 24.10+, opkg before it. */
    const val DETECT_PACKAGE_MANAGER = "command -v apk >/dev/null && echo apk || echo opkg"

    const val REBOOT = "reboot"
}
