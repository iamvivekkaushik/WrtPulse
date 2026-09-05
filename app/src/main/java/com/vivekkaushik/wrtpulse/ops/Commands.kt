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
        // Every interface, so the upstream can be found by which one holds the default
        // route rather than by assuming it is called "wan".
        "echo $SECTION ifaces" to "ubus call network.interface dump 2>/dev/null || echo '{}'",
        "echo $SECTION essid" to "iwinfo 2>/dev/null | grep ESSID || true",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /** Wireless config as UCI key=value lines. */
    const val WIRELESS_CONFIG = "uci show wireless"

    /** Network config, read alongside it so a new uplink can be given an unused name. */
    const val NETWORK_CONFIG = "uci show network"

    /** Firewall config — which zone each network sits in, for the interface list. */
    const val FIREWALL_CONFIG = "uci show firewall"

    /** Every wireless interface that is actually up: mode, channel, and a station's signal. */
    const val IWINFO = "iwinfo 2>/dev/null"

    /** Associated stations per interface, so each SSID can report how many clients it has. */
    const val ASSOC_COUNTS =
        "for i in \$(iwinfo 2>/dev/null | grep ESSID | cut -d' ' -f1); do " +
        "echo \"# \$i\"; iwinfo \$i assoclist 2>/dev/null; done"

    /** The raw uci lines behind one wifi-iface — what a long-press reveals. */
    fun showSection(section: String) = "uci show wireless.$section 2>/dev/null"

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

    /** The install-consent plan for the usage meter the Clients screen offers. */
    val NLBW_PLAN: String = installPlan("nlbwmon")

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
        uciBatch(operations, listOf(commitPackage), reload)

    /**
     * The same, across more than one config file — joining an upstream network touches
     * `wireless` and `network` together, and half of that landing would leave a station
     * with nowhere to get an address.
     */
    fun uciBatch(operations: List<String>, commitPackages: List<String>, reload: String): String =
        buildString {
            append("uci batch <<'WRTPULSE_EOF'\n")
            operations.forEach { append(it).append('\n') }
            append("WRTPULSE_EOF\n")
            append(commitPackages.joinToString(" && ") { "uci commit $it" })
            append(" && ").append(reload)
        }

    /** The uci network a router-as-client interface is bridged to. */
    const val WWAN = "wwan"

    /**
     * Puts the upstream client interface in the WAN firewall zone. Without this the station
     * associates and gets an address, but nothing behind the router is masqueraded onto it —
     * the router joins the network and the LAN still has no way out.
     *
     * The zone is found by walking `@zone[i]` and matching its name, because the wan zone is
     * an anonymous section in every stock config, and matching `name='wan'` textually would
     * also hit a firewall rule that happens to be called wan.
     */
    fun attachToWanZone(network: String): String = attachToZone(network, "wan")

    /** The same for any named zone — a guest AP belongs somewhere other than wan. */
    fun attachToZone(network: String, zone: String): String =
        "i=0; z=''; " +
        "while uci -q get firewall.@zone[\$i] >/dev/null 2>&1; do " +
        "[ \"\$(uci -q get firewall.@zone[\$i].name)\" = '$zone' ] && { z=\"@zone[\$i]\"; break; }; " +
        "i=\$((i+1)); done; " +
        "[ -n \"\$z\" ] && { uci -q del_list firewall.\$z.network='$network'; " +
        "uci add_list firewall.\$z.network='$network'; uci commit firewall; " +
        "/etc/init.d/firewall reload >/dev/null 2>&1; }; :"

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

    // ── Packages ──────────────────────────────────────────────────────────────
    // 24.10 and later ship apk, everything before it ships opkg, and a single build of the
    // app is expected to manage both. Every command below asks which one is present rather
    // than assuming, and normalises the answer so the parsers don't have to know.

    /**
     * A package name is about to be interpolated into a shell command, so the alphabet is
     * the guard: feed names are letters, digits and `._+-`, and a leading dash would read
     * as a flag.
     */
    fun safePackageName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 96 &&
            name.first().isLetterOrDigit() &&
            name.all { it.isLetterOrDigit() || it in "._+-" }

    /**
     * Installed packages as `name|version|size|auto`. opkg keeps a plain-text status file
     * that already carries every field, including whether the package was pulled in as a
     * dependency, so one awk pass over it is the whole answer.
     */
    private const val OPKG_INSTALLED =
        "awk -F': ' '" +
        "/^Package: /{p=\$2} " +
        "/^Version: /{v=\$2} " +
        "/^Installed-Size: /{s=\$2} " +
        "/^Auto-Installed: yes/{a=\"auto\"} " +
        "/^\$/{if(p!=\"\")print p\"|\"v\"|\"s\"|\"a; p=\"\";v=\"\";s=\"\";a=\"\"} " +
        "END{if(p!=\"\")print p\"|\"v\"|\"s\"|\"a}' /usr/lib/opkg/status 2>/dev/null"

    /**
     * The same for apk, whose database is binary. `apk info --size` is handed every
     * installed name at once — one process for the list rather than one per package, which
     * on a router with 200 packages is the difference between a screen and a wait. It
     * answers in pairs (a `<name>-<version> installed size:` header, then the number), so
     * awk folds each pair onto one line.
     *
     * Both lookups have a fallback because apk's subcommands have moved between major
     * versions: worst case the list arrives with no sizes, which the UI shows as "—".
     */
    private const val APK_INSTALLED =
        "N=\$(apk info 2>/dev/null); " +
        "[ -z \"\$N\" ] && N=\$(apk list --installed 2>/dev/null | cut -d' ' -f1); " +
        "S=\$(apk info --size \$N 2>/dev/null | awk '/size:/{n=\$1; if((getline v)>0) print n\"|\"v}'); " +
        "[ -z \"\$S\" ] && S=\$(echo \"\$N\" | sed 's/\$/|/'); " +
        "echo \"\$S\""

    /**
     * How long ago the package index was refreshed. A stale index is the usual reason a
     * package "doesn't exist", so the screen says the age rather than leaving the user to
     * guess. -1 means no index directory was found — the age is then simply not shown.
     */
    private const val FEED_AGE =
        "L=\$(ls -1t /var/opkg-lists/* /tmp/opkg-lists/* /usr/lib/opkg/lists/* " +
        "/var/cache/apk/* /tmp/apk/* 2>/dev/null | head -n1); " +
        "if [ -n \"\$L\" ]; then echo \$(( \$(date +%s) - \$(date -r \"\$L\" +%s) )); else echo -1; fi"

    /** Everything the package screen needs, in one round trip. */
    val PACKAGES: String = listOf(
        "echo $SECTION pm",
        DETECT_PACKAGE_MANAGER,
        "echo $SECTION installed",
        "if command -v apk >/dev/null 2>&1; then $APK_INSTALLED; else $OPKG_INSTALLED; fi",
        "echo $SECTION upgradable",
        "if command -v apk >/dev/null 2>&1; then apk list --upgradable 2>/dev/null; " +
            "else opkg list-upgradable 2>/dev/null; fi",
        "echo $SECTION feed",
        FEED_AGE,
        "echo $SECTION df",
        "df -k /overlay | tail -n1",
    ).joinToString("; ")

    /**
     * Searches the feed. The grep runs on the router: the full package list is megabytes on
     * a router with the standard feeds enabled, and only the matches are worth the air time.
     */
    fun searchPackages(term: String, limit: Int = 80): String =
        "{ if command -v apk >/dev/null 2>&1; then apk list 2>/dev/null; " +
        "else opkg list 2>/dev/null; fi; } | grep -i -- '$term' | head -n $limit"

    /** Whatever the package manager can say about one package, shown raw. */
    fun packageInfo(name: String): String =
        "if command -v apk >/dev/null 2>&1; then " +
        "apk list '$name' 2>/dev/null | head -n 4; " +
        "apk info --description '$name' 2>/dev/null; " +
        "apk info --size '$name' 2>/dev/null; " +
        "else opkg info '$name' 2>/dev/null; fi"

    /** Re-downloads the package index. */
    const val UPDATE_FEED = "if command -v apk >/dev/null 2>&1; then apk update; else opkg update; fi"

    /**
     * Everything the install-consent dialog needs, one round trip: package manager, the
     * packages an install would pull in, the space each will occupy, and free overlay space.
     * The resolve runs once and is reused for the size lookup. `opkg update` first — its
     * package lists live in /tmp and vanish on reboot.
     */
    fun installPlan(pkg: String): String = listOf(
        "echo $SECTION pm",
        DETECT_PACKAGE_MANAGER,
        "if command -v apk >/dev/null 2>&1; then PLAN=\$(apk add --simulate '$pkg' 2>&1); " +
            "else opkg update >/dev/null 2>&1; PLAN=\$(opkg install --noaction '$pkg' 2>&1); fi",
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

    /**
     * The mirror image, for removal: what the manager would take out. Sizes aren't asked for
     * here — the packages are installed, so the app already knows what each one occupies.
     */
    fun removePlan(pkg: String): String = listOf(
        "echo $SECTION pm",
        DETECT_PACKAGE_MANAGER,
        "if command -v apk >/dev/null 2>&1; then PLAN=\$(apk del --simulate '$pkg' 2>&1); " +
            "else PLAN=\$(opkg remove --noaction '$pkg' 2>&1); fi",
        "echo $SECTION plan",
        "echo \"\$PLAN\"",
        "echo $SECTION df",
        "df -k /overlay | tail -n1",
    ).joinToString("; ")

    /**
     * Installs, then starts the service if the package ships one — an OpenWrt package's
     * init script is installed disabled often enough that "installed but doing nothing" is
     * the more surprising outcome. Failure keeps its exit code so the app can report it.
     */
    fun installPackage(pkg: String): String =
        "if command -v apk >/dev/null 2>&1; then apk add '$pkg' || exit 1; " +
        "else opkg install '$pkg' || exit 1; fi; " +
        "if [ -x /etc/init.d/$pkg ]; then /etc/init.d/$pkg enable >/dev/null 2>&1; " +
        "/etc/init.d/$pkg start >/dev/null 2>&1; fi; echo installed"

    /** Stops the service first so removal doesn't leave a running daemon with no files. */
    fun removePackage(pkg: String): String =
        "if [ -x /etc/init.d/$pkg ]; then /etc/init.d/$pkg stop >/dev/null 2>&1; " +
        "/etc/init.d/$pkg disable >/dev/null 2>&1; fi; " +
        "if command -v apk >/dev/null 2>&1; then apk del '$pkg'; else opkg remove '$pkg'; fi"

    /** One package at a time, deliberately — see PackageStore for why there is no upgrade-all. */
    fun upgradePackage(pkg: String): String =
        "if command -v apk >/dev/null 2>&1; then apk upgrade '$pkg'; else opkg upgrade '$pkg'; fi"

    // ── Services ──────────────────────────────────────────────────────────────
    // An init script's state lives in three places, and none of them knows the other two:
    // the script itself carries its START order and whether procd manages it, /etc/rc.d
    // says whether it starts at boot, and procd's own table says whether it is running now.
    // One round trip reads all three so the list can't show a half-answer.

    /** Init script names arrive from a directory listing, but they still reach a shell. */
    fun safeServiceName(name: String): Boolean = safePackageName(name)

    /**
     * Every executable init script as `name|start|enabled|daemon`.
     *
     * The last field answers "would this still be running if it worked", which is the whole
     * basis for calling anything stopped. `USE_PROCD=1` is NOT that test, though it reads
     * like it: it marks a script that uses procd's helpers, which the one-shots use too.
     * On the reference router `firewall`, `system`, `ucitrack`, `urandom_seed`,
     * `packet_steering` and `gpio_switch` all set it, all ran correctly at boot, and all
     * exited — reporting six healthy scripts as stopped services.
     *
     * `procd_set_param respawn` is the real signal — it is the script asking procd to keep
     * the process alive, which is exactly the claim "it should still be running" needs.
     * A supervised `command` alone is not enough: the same router's `urandom_seed` declares
     * one, and `/sbin/urandom_seed` seeds the pool and exits by design.
     *
     * A daemon that deliberately omits respawn reads as a boot script, and that is the
     * direction to fail in: it suppresses an alarm rather than inventing one, and
     * [Parsers.services] corrects it the moment procd reports the thing running.
     */
    private const val INIT_SCRIPTS =
        "for f in /etc/init.d/*; do " +
        "[ -f \"\$f\" ] && [ -x \"\$f\" ] || continue; " +
        "n=\${f##*/}; " +
        "o=\$(sed -n 's/^START=\\([0-9]*\\).*/\\1/p' \"\$f\" | head -n1); " +
        "e=''; ls /etc/rc.d/S??\"\$n\" >/dev/null 2>&1 && e=enabled; " +
        "p=''; grep -q 'procd_set_param respawn' \"\$f\" && p=procd; " +
        "echo \"\$n|\$o|\$e|\$p\"; " +
        "done"

    /** Everything the services screen needs, in one round trip. */
    val SERVICES: String = listOf(
        "echo $SECTION scripts",
        INIT_SCRIPTS,
        "echo $SECTION running",
        "ubus call service list 2>/dev/null || echo '{}'",
    ).joinToString("; ")

    /**
     * What the detail sheet shows: the top of the init script — where OpenWrt puts START,
     * STOP and the procd declaration — plus the processes actually carrying the name, and
     * the rc.d symlinks that decide boot order.
     */
    fun serviceInfo(name: String): String = listOf(
        "echo $SECTION head",
        "head -n 20 '/etc/init.d/$name' 2>/dev/null",
        "echo $SECTION procs",
        "ps w 2>/dev/null | grep -F -- '$name' | grep -v grep | head -n 6",
        "echo $SECTION boot",
        "ls -1 /etc/rc.d/ 2>/dev/null | grep -F -- '$name' || true",
    ).joinToString("; ")

    /**
     * One init-script verb. procd's start and stop return before the daemon has actually
     * come up or gone away, so the settle is part of the command — the reload that follows
     * reads a settled router rather than a racing one. The exit code stays the script's.
     */
    fun serviceAction(name: String, action: ServiceAction): String =
        "'/etc/init.d/$name' ${action.verb}; rc=\$?; sleep 1; exit \$rc"

    // ── Firmware ──────────────────────────────────────────────────────────────
    // Flashing is the one action here that cannot be taken back, so every step before it is
    // a read: what tool the router has, what the upgrade server says, what the image hashes
    // to, and whether sysupgrade itself accepts the file. The write is one line at the end.

    /**
     * An image path is about to be interpolated into a shell command, and unlike a package
     * name it arrives from a directory listing rather than a fixed alphabet. Confining it to
     * /tmp is also a safety property in its own right: the image has to live in RAM, and a
     * path pointing anywhere else means something has gone wrong upstream.
     */
    fun safeImagePath(path: String): Boolean =
        path.startsWith("/tmp/") && path.length <= 200 && !path.contains("..") &&
            path.all { it.isLetterOrDigit() || it in "._/-+" }

    /** A pasted download URL, kept to characters that cannot end a quoted shell word. */
    fun safeImageUrl(url: String): Boolean =
        (url.startsWith("https://") || url.startsWith("http://")) && url.length <= 400 &&
            url.all { it.isLetterOrDigit() || it in ":/._~%?=&+-" }

    /** A pasted sha256, checked before it is compared against anything. */
    fun safeSha256(sha: String): Boolean =
        sha.length == 64 && sha.all { it.isDigit() || it in "abcdefABCDEF" }

    /**
     * owut on 24.10 and later, auc before it, and neither on a stripped build — in which case
     * the screen falls back to a URL the user supplies.
     */
    const val DETECT_UPGRADE_TOOL =
        "if command -v owut >/dev/null 2>&1; then echo owut; " +
        "elif command -v auc >/dev/null 2>&1; then echo auc; else echo none; fi"

    /**
     * Sysupgrade images already sitting in /tmp, as `path|bytes`.
     *
     * The net is wide on purpose: owut names its download `/tmp/firmware.bin`, with nothing
     * in the name to say what it is. An earlier version globbed `*sysupgrade*` and so
     * reported a perfectly good download as a failure.
     */
    private const val STAGED_IMAGES =
        "for f in /tmp/*.bin /tmp/*.itb /tmp/*.img /tmp/*.img.gz; do " +
        "[ -f \"\$f\" ] || continue; echo \"\$f|\$(wc -c < \"\$f\")\"; done"

    /** Everything the firmware screen reads on entry, in one round trip. */
    val FIRMWARE: String = listOf(
        "echo $SECTION board",
        BOARD,
        "echo $SECTION tool",
        DETECT_UPGRADE_TOOL,
        // /tmp is tmpfs — this is the RAM the image has to fit into, not flash.
        "echo $SECTION tmp",
        "df -k /tmp | tail -n1",
        "echo $SECTION images",
        STAGED_IMAGES,
    ).joinToString("; ")

    /**
     * Asks the upgrade server what it would build. Read-only: it resolves the profile,
     * compares versions and package lists, and prints its own verdict on whether upgrading
     * is safe. Talks to the network, so it is slower than it looks.
     */
    const val UPGRADE_CHECK = "owut check 2>&1"

    /** The same with owut's reasoning, shown when the verdict is not the safe one. */
    const val UPGRADE_CHECK_VERBOSE = "owut check --verbose 2>&1"

    /**
     * Builds the image on the ASU server, downloads it and verifies it — owut does all three
     * under `download`. Deliberately not `owut upgrade`, which would install it too and take
     * the confirmation step away from the user.
     */
    const val UPGRADE_DOWNLOAD = "owut download 2>&1"

    /**
     * Packages the user installed that are not in the default image, by name — what a plain
     * flash loses and what screen 42 offers to put back. owut knows because it diffs the
     * installed set against the profile's defaults.
     */
    const val USER_PACKAGES = "owut list -f fs-user 2>/dev/null"

    /**
     * Puts the user's packages back after a flash. apk on 24.10+, opkg before it — same
     * split the packages screen uses. Names are validated by [safePackageName] first.
     */
    fun reinstall(packages: List<String>): String {
        val names = packages.filter { safePackageName(it) }.joinToString(" ")
        return "if command -v apk >/dev/null 2>&1; then apk add $names 2>&1; " +
            "else opkg update >/dev/null 2>&1; opkg install $names 2>&1; fi"
    }

    /** The config archive sysupgrade itself would carry across, written where it can be read. */
    const val BACKUP_FILE = "/tmp/wrtpulse-backup.tar.gz"

    // ── Firewall ──────────────────────────────────────────────────────────────
    // fw4 reads /etc/config/firewall and nothing else the app touches, so the whole section
    // is uci in and `firewall reload` out. The reload is rollback-armed like a WAN change:
    // a rule that rejects the phone's own SSH is the one mistake this screen can make that
    // the screen itself cannot undo.

    /** Config, engine state, and the lease table the device pickers read from. */
    val FIREWALL_STATE = listOf(
        "echo $SECTION firewall" to "uci show firewall 2>/dev/null",
        "echo $SECTION service" to "ubus call service list '{\"name\":\"firewall\"}' 2>/dev/null || echo '{}'",
        // fw4 is the nftables engine on 22.03+; its absence means iptables-era fw3.
        "echo $SECTION engine" to "command -v fw4 >/dev/null 2>&1 && echo fw4 || echo fw3",
        // Neither engine is a daemon — fw4 loads a ruleset and exits — so "running" means the
        // ruleset is in the kernel: the fw4 table under nftables, or a zone chain under iptables.
        "echo $SECTION active" to
            "(nft list tables 2>/dev/null | grep -q 'inet fw4' && echo active) || " +
            "(iptables -S zone_lan_input >/dev/null 2>&1 && echo active) || echo inactive",
        "echo $SECTION reloaded" to "stat -c %Y /var/run/fw4.state 2>/dev/null || echo",
        "echo $SECTION now" to "date +%s",
        "echo $SECTION leases" to "cat /tmp/dhcp.leases 2>/dev/null",
        // The router's own listeners — a forward onto one of these locks the app out.
        "echo $SECTION listen" to "netstat -tln 2>/dev/null | awk 'NR>2{print \$4}' || true",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    const val FIREWALL_RELOAD = "/etc/init.d/firewall reload"
    const val FIREWALL_ROLLBACK_DIR = "/tmp/wrtpulse-fw"

    /**
     * Applies firewall operations with the same failsafe as [wanApply]: the config is copied
     * first and a detached watcher puts it back and reloads unless [FIREWALL_CONFIRM] lands
     * within [seconds]. A rule that rejects the phone's own session is the one change here
     * that would otherwise need a cable to undo.
     */
    fun firewallApply(operations: List<String>, seconds: Int = 15): String = buildString {
        append("mkdir -p $FIREWALL_ROLLBACK_DIR && ")
        append("cp /etc/config/firewall $FIREWALL_ROLLBACK_DIR/firewall && ")
        append("rm -f $FIREWALL_ROLLBACK_DIR/confirm && ")
        append("(sleep $seconds; [ -f $FIREWALL_ROLLBACK_DIR/confirm ] && exit 0; ")
        append("cp $FIREWALL_ROLLBACK_DIR/firewall /etc/config/firewall; ")
        append("$FIREWALL_RELOAD; echo rolled-back > $FIREWALL_ROLLBACK_DIR/last) ")
        append(">/dev/null 2>&1 &\n")
        append("uci batch <<'WRTPULSE_EOF'\n")
        operations.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append("uci commit firewall && $FIREWALL_RELOAD; echo applied")
    }

    /** Disarms the firewall rollback — sent once the app has re-read the router. */
    const val FIREWALL_CONFIRM = "touch $FIREWALL_ROLLBACK_DIR/confirm && echo confirmed"

    /** Whether the last watcher fired. Read on the next load so a revert is never silent. */
    const val FIREWALL_LAST = "cat $FIREWALL_ROLLBACK_DIR/last 2>/dev/null; rm -f $FIREWALL_ROLLBACK_DIR/last"

    // ── Factory reset ─────────────────────────────────────────────────────────
    // The red zone has to name what it is about to erase, so it reads the config it is
    // about to throw away first. Nothing here writes; the destructive line is FACTORY_RESET
    // and it runs only from the hold.

    /** Everything the reset screen names before it lets the hold arm. */
    val RESET_SUMMARY = listOf(
        "echo $SECTION wireless" to "uci show wireless 2>/dev/null",
        "echo $SECTION network" to "uci show network 2>/dev/null",
        "echo $SECTION firewall" to "uci show firewall 2>/dev/null",
        "echo $SECTION dhcp" to "uci show dhcp 2>/dev/null",
        "echo $SECTION packages" to USER_PACKAGES,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * `firstboot -y && reboot` — wipes /overlay and restarts.
     *
     * Detached and delayed for the same reason as [flash]: the reboot kills the SSH session,
     * and a command still attached to it would look like a failure when it is the point.
     */
    const val FACTORY_RESET = "(sleep 1; firstboot -y && reboot) >/dev/null 2>&1 & echo resetting"

    /** Writes the backup and answers with its size, so the app can refuse an absurd one. */
    const val BACKUP_CREATE =
        "rm -f $BACKUP_FILE; sysupgrade -b $BACKUP_FILE >/dev/null 2>&1 && wc -c < $BACKUP_FILE"

    /**
     * The hex encoder, used when no base64 exists. `/1 "%02x"` is one byte per iteration,
     * so the output is continuous hex with nothing to strip.
     */
    private const val HEXDUMP = "hexdump -v -e '/1 \"%02x\"'"

    /**
     * Reads the archive back through the exec channel, which carries text, so the bytes have
     * to be encoded on the router.
     *
     * There is no encoder that is always present, and this took three passes against a real
     * router to get right. The reference device has NO `base64`, no `openssl` and no `od` —
     * `busybox --list` does not work there either. What it does have is `hexdump`. So the
     * encoders are tried in order against a one-byte probe, cheaper than encoding the file
     * twice to find out, and base64 is preferred only because it halves the transfer.
     *
     * Every encoder is fed on STDIN, never a file operand: some builds' `base64` takes no
     * filename, which produced a marker line and an empty payload. Redirection is the one
     * calling convention all of them share.
     *
     * The first line names the encoding — `b64`, `hex`, or `none` when the router cannot do
     * it at all, which is a real answer and not a failure to parse.
     */
    const val BACKUP_READ =
        "F=$BACKUP_FILE; " +
        "if echo t | base64 >/dev/null 2>&1; then echo b64; base64 < \"\$F\"; " +
        "elif echo t | busybox base64 >/dev/null 2>&1; then echo b64; busybox base64 < \"\$F\"; " +
        "elif echo t | openssl base64 >/dev/null 2>&1; then echo b64; openssl base64 -in \"\$F\"; " +
        "elif echo t | $HEXDUMP >/dev/null 2>&1; then echo hex; $HEXDUMP < \"\$F\"; " +
        "elif echo t | od -An -v -tx1 >/dev/null 2>&1; then echo hex; od -An -v -tx1 < \"\$F\" | tr -d ' \\n'; " +
        "else echo none; fi"

    /** /tmp is RAM. The copy on the router goes as soon as the phone has it. */
    const val BACKUP_CLEANUP = "rm -f $BACKUP_FILE"

    // ── Backup & restore ──────────────────────────────────────────────────────
    // A backup is read-only on the router and ends up on the phone. A restore is the mirror:
    // the archive is judged on the phone, sent up, judged AGAIN by the router's own tar, and
    // only then unpacked over /. The unpack is the one write, and the reboot follows it.

    /** What goes into a backup — sysupgrade's own list, one absolute path per line. */
    const val BACKUP_LIST = "sysupgrade -l 2>/dev/null"

    /**
     * The extra paths a backup carries beyond /etc/config — the user's own additions, one
     * per line. This is the file "Edit list" writes.
     */
    const val SYSUPGRADE_CONF = "/etc/sysupgrade.conf"
    const val READ_SYSUPGRADE_CONF = "cat $SYSUPGRADE_CONF 2>/dev/null"

    /**
     * Rewrites the extra-paths list wholesale. Each line is validated by [safeBackupPath]
     * before it gets here; the heredoc is quoted so nothing in it is expanded.
     */
    fun writeSysupgradeConf(paths: List<String>): String = buildString {
        append("cat > $SYSUPGRADE_CONF <<'WRTPULSE_EOF'\n")
        paths.filter { safeBackupPath(it) }.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append("echo written; ").append(BACKUP_LIST)
    }

    /** An absolute path with no shell in it and no way to climb out of /. */
    fun safeBackupPath(path: String): Boolean =
        path.startsWith("/") && !path.contains("..") &&
            Regex("^[A-Za-z0-9/._@+-]{2,200}$").matches(path)

    /** Everything the backup screen reads on entry, in one round trip. */
    val BACKUP_INFO: String = listOf(
        "echo $SECTION board",
        BOARD,
        "echo $SECTION files",
        BACKUP_LIST,
        "echo $SECTION conf",
        READ_SYSUPGRADE_CONF,
        // The archive being restored has to fit in RAM alongside everything else there.
        "echo $SECTION tmp",
        "df -k /tmp | tail -n1",
    ).joinToString("; ")

    /** Where an archive being restored lands. /tmp, so the reboot that follows removes it. */
    const val RESTORE_FILE = "/tmp/wrtpulse-restore.tar.gz"

    /** Receives the archive on stdin and answers with the byte count actually written. */
    const val RESTORE_RECEIVE = "rm -f $RESTORE_FILE; cat > $RESTORE_FILE && wc -c < $RESTORE_FILE"

    /** The router's hash of what arrived, compared with the phone's before anything else. */
    val RESTORE_SHA256: String = imageSha256(RESTORE_FILE)

    /** The router's own tar reading the archive. If this fails, so would the restore. */
    const val RESTORE_LIST = "tar -tzf $RESTORE_FILE 2>&1"

    /**
     * The unpack. `sysupgrade -r` is `tar -C / -xzf` with sysupgrade's file checks in front
     * of it; it does not reboot on its own, so the app sends [REBOOT] once it has returned 0.
     */
    const val RESTORE_APPLY = "sysupgrade -r $RESTORE_FILE 2>&1"

    const val RESTORE_CLEANUP = "rm -f $RESTORE_FILE"

    /** Where a manually supplied image is put, so it lands under [safeImagePath] too. */
    const val MANUAL_IMAGE = "/tmp/wrtpulse-sysupgrade.bin"

    /**
     * A sysupgrade image pushed from the phone over stdin, the way a restore archive is.
     * Lands under [MANUAL_IMAGE] so [safeImagePath] covers it and `sysupgrade -T` judges it
     * before anything else does.
     */
    const val LOCAL_IMAGE_RECEIVE =
        "rm -f $MANUAL_IMAGE; cat > $MANUAL_IMAGE && wc -c < $MANUAL_IMAGE"

    /** Fetches a user-supplied image and answers with the byte count actually written. */
    fun downloadImage(url: String, dest: String = MANUAL_IMAGE): String =
        "rm -f '$dest'; { curl -fsSL -o '$dest' '$url' 2>/dev/null || " +
        "uclient-fetch -q -O '$dest' '$url'; } && wc -c < '$dest'"

    fun imageSha256(path: String): String = "sha256sum '$path' | cut -d' ' -f1"

    /**
     * Gives the RAM back. A downloaded image sits in tmpfs until the router reboots, and the
     * app is what put it there, so the app can take it away again.
     */
    fun discardImage(path: String): String = "rm -f '$path'"

    /**
     * What the server says the image weighs, so the RAM check can run BEFORE /tmp is filled
     * with a truncated file. Only curl can ask; a router without it simply gets no answer,
     * and the app treats "unknown" as "don't block" rather than inventing a number.
     */
    fun urlContentLength(url: String): String =
        "curl -sIL '$url' 2>/dev/null | " +
        "awk 'tolower(\$1)==\"content-length:\"{print \$2}' | tr -d '\\r' | tail -n1"

    /**
     * sysupgrade's own dry run: it reads the image's metadata and refuses one built for a
     * different device. This is the check that stands between a typo and a brick, so it runs
     * even when owut has already verified the download.
     */
    fun imageTest(path: String): String = "sysupgrade -T '$path' 2>&1"

    /**
     * The flash. Detached like [REBOOT] and for the same reason — sysupgrade takes the
     * connection down with it, and a foreground command would never get to answer.
     *
     * `-n` discards config, which also resets the LAN address and regenerates dropbear's
     * host key; [FirmwareStore] is where the user is told that.
     */
    fun flash(path: String, keepSettings: Boolean): String =
        "(sleep 1; sysupgrade ${if (keepSettings) "" else "-n "}'$path') >/dev/null 2>&1 & echo flashing"

    // ── SSH keys ──────────────────────────────────────────────────────────────
    // dropbear reads /etc/dropbear/authorized_keys, one key per line. The file is the whole
    // access-control list for this router, and the app is holding one of its entries, so
    // every write here is narrower than it looks: never rewrite the file wholesale, only
    // append a validated line or drop the one line that carries a known blob.

    const val AUTHORIZED_KEYS = "/etc/dropbear/authorized_keys"

    /**
     * A public key as it may be interpolated into a shell command and appended to the file
     * that decides who can log in.
     *
     * The blob is base64 and the type is from a fixed set, so both are checked against their
     * own alphabet rather than escaped. Anything with a newline is refused outright: one
     * line is one key, and a smuggled newline would be a second entry nobody agreed to.
     */
    fun safePublicKeyLine(line: String): Boolean = parsePublicKey(line) != null

    /** The types dropbear actually accepts, so a typo cannot become a dead entry. */
    private val KEY_TYPES = setOf(
        "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256",
        "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521",
        "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com",
    )

    /**
     * Splits a pasted line into (type, blob, comment), or null when it is not a key.
     *
     * The comment is rebuilt from a safe alphabet rather than trusted: it is free text that
     * would otherwise reach a shell, and no key stops working for want of punctuation in
     * its label.
     */
    fun parsePublicKey(line: String): Triple<String, String, String>? {
        val text = line.trim()
        if (text.isEmpty() || text.length > 4096) return null
        if (text.any { it == '\n' || it == '\r' }) return null
        val parts = text.split(Regex("\\s+"), limit = 3)
        if (parts.size < 2) return null
        val type = parts[0]
        val blob = parts[1]
        if (type !in KEY_TYPES) return null
        if (blob.length < 16 || !blob.all { it.isLetterOrDigit() || it in "+/=" }) return null
        val comment = parts.getOrElse(2) { "" }
            .filter { it.isLetterOrDigit() || it in "._@- " }
            .trim()
            .take(120)
        return Triple(type, blob, comment)
    }

    /** Everything the SSH keys screen needs, in one round trip. */
    val SSH_KEYS: String = listOf(
        "echo $SECTION keys",
        "cat $AUTHORIZED_KEYS 2>/dev/null",
        "echo $SECTION perms",
        "ls -l $AUTHORIZED_KEYS 2>/dev/null",
        "echo $SECTION dropbear",
        "uci show dropbear 2>/dev/null",
    ).joinToString("; ")

    /**
     * Appends the app's public key to dropbear's authorized_keys, idempotently.
     * The key line is base64 + spaces — safe inside single quotes.
     */
    fun installKey(publicLine: String): String {
        val f = AUTHORIZED_KEYS
        return "mkdir -p /etc/dropbear && touch $f && " +
            "(grep -qF '$publicLine' $f || echo '$publicLine' >> $f) && chmod 600 $f"
    }

    /**
     * Drops the one line carrying this blob. Matched on the blob rather than the whole line
     * because the comment is cosmetic and may have been edited on the router; the blob is
     * the key. `grep -vF` on a fixed string, written to a temp file and moved into place, so
     * a full disk truncates the temp copy rather than the access list.
     */
    fun removeKey(blob: String): String {
        val f = AUTHORIZED_KEYS
        // grep exits 1 when it prints nothing, which for `-v` is the perfectly good outcome
        // of removing the only line. Chaining on && therefore skipped the mv and left the
        // key in place — exactly in the last-key case that matters most. Exit 2 is the real
        // error, so the status is checked rather than assumed.
        return "[ -f $f ] || { echo missing; exit 1; }; " +
            "grep -vF '$blob' $f > $f.tmp; rc=\$?; " +
            "if [ \$rc -le 1 ]; then mv $f.tmp $f && chmod 600 $f && echo removed; " +
            "else rm -f $f.tmp; echo \"grep failed: \$rc\"; exit 1; fi"
    }


    // -----------------------------------------------------------------------
    // LAN & local network — design screens 20-25
    // -----------------------------------------------------------------------

    /**
     * One line per netdev: name, operstate, carrier, link speed, MAC, and whether real
     * hardware sits behind it.
     *
     * Read out of sysfs rather than from `ubus call network.device status` because sysfs is
     * on every target and needs no JSON: the switch-port row has to work on an ath79 box
     * with three files in /bin as much as on filogic. `speed` is unreadable while a port is
     * down, hence the fallbacks.
     */
    const val NETDEVS =
        "for d in /sys/class/net/*; do n=\"\${d##*/}\"; " +
        "echo \"\$n \$(cat \$d/operstate 2>/dev/null || echo unknown) " +
        "\$(cat \$d/carrier 2>/dev/null || echo -) " +
        "\$(cat \$d/speed 2>/dev/null || echo -) " +
        "\$(cat \$d/address 2>/dev/null || echo -) " +
        "\$([ -e \$d/device ] && echo phy || echo virt) " +
        // Every wireless netdev has a phy80211 link. Naming is no test: OpenWrt 24.10 calls
        // them phy0-ap0 and phy0-sta0, and older releases called them wlan0.
        "\$([ -e \$d/phy80211 ] && echo wifi || echo wired)\"; done"

    /**
     * Everything the LAN screen reads, in one round trip: both config files, what netifd
     * says is actually live, the lease file, and the ports.
     */
    fun lanState(section: String = "lan"): String = listOf(
        "echo $SECTION net" to NETWORK_CONFIG,
        "echo $SECTION dhcp" to "uci show dhcp 2>/dev/null",
        "echo $SECTION live" to "ubus call network.interface.$section status 2>/dev/null || echo '{}'",
        "echo $SECTION leases" to "cat /tmp/dhcp.leases 2>/dev/null",
        "echo $SECTION neigh" to "ip neigh show",
        "echo $SECTION links" to NETDEVS,
        // Whether the DHCP server is actually serving, as opposed to configured to.
        "echo $SECTION dnsmasq" to "pgrep dnsmasq >/dev/null 2>&1 && echo running || echo stopped",
        "echo $SECTION swconfig" to SWCONFIG,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * The switch chip, on the boards that still have one.
     *
     * `help` carries the port count and which port is the CPU — the two facts that decide
     * whether a VLAN edit can be offered at all — and `show` is the only place the per-socket
     * link state exists, because a swconfig board's sockets are not netdevs. Empty on a DSA
     * board, where the binary is not installed.
     */
    val SWCONFIG =
        // `exit 0` would end the whole batched script and take any later section with it, so
        // the absent-binary case is a plain empty branch.
        "if command -v swconfig >/dev/null 2>&1; then swconfig list; " +
        "for d in \$(swconfig list | sed -n 's/^Found:*[[:space:]]*\\([^ ]*\\).*/\\1/p'); do " +
        "echo \"# \$d\"; swconfig dev \$d help 2>/dev/null; " +
        "swconfig dev \$d show 2>/dev/null; done; fi"

    /**
     * A uci list is replaced wholesale: `set` on a list option collapses it to one value, so
     * the old list is deleted and the new one built back up with `add_list`. An empty list
     * is the delete on its own.
     */
    fun listOps(path: String, values: List<String>): List<String> =
        listOf("delete " + path) + values.map { "add_list " + path + "='" + escapeValue(it) + "'" }

    /** uci values travel single-quoted inside the batch heredoc; a quote must not break out. */
    fun escapeValue(value: String): String = value.replace("'", "'\\''")

    /**
     * Reload after LAN changes. dnsmasq only needs a restart; anything in `network` needs
     * netifd, and moving the router's own address takes the session with it — which is why
     * the reload is detached and the caller treats a dropped link as success.
     */
    fun lanReload(network: Boolean, dhcp: Boolean, movesAddress: Boolean): String = when {
        movesAddress ->
            // The reply cannot come back over a link this command is about to take down.
            "(sleep 1; /etc/init.d/network reload" +
                (if (dhcp) "; /etc/init.d/dnsmasq restart" else "") +
                ") >/dev/null 2>&1 & echo scheduled"
        network && dhcp -> "/etc/init.d/network reload >/dev/null 2>&1; /etc/init.d/dnsmasq restart >/dev/null 2>&1; echo done"
        network -> "/etc/init.d/network reload >/dev/null 2>&1; echo done"
        dhcp -> "/etc/init.d/dnsmasq restart >/dev/null 2>&1; echo done"
        else -> "echo done"
    }

    // -----------------------------------------------------------------------
    // Internet & WAN gateways — design screens 26-30
    // -----------------------------------------------------------------------

    /** Everything the WAN screens read, in one round trip. */
    val WAN_STATE = listOf(
        "echo $SECTION net" to NETWORK_CONFIG,
        "echo $SECTION fw" to FIREWALL_CONFIG,
        // The v6 half of the LAN is configured in dhcp, not network.
        "echo $SECTION dhcp" to "uci show dhcp 2>/dev/null",
        "echo $SECTION dump" to "ubus call network.interface dump 2>/dev/null || echo '{}'",
        "echo $SECTION links" to NETDEVS,
        // Which protocols netifd can actually bring up on this router.
        "echo $SECTION protos" to "ls /lib/netifd/proto 2>/dev/null",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * The connection test: three pings each at the gateway, and at two resolvers beyond it.
     *
     * Split that way on purpose — the gateway answering while 1.1.1.1 does not is a
     * different fault from the gateway itself being unreachable, and the screen can only say
     * which if it measures both.
     */
    fun pingTest(gateway: String, device: String): String = buildList {
        // A standby uplink holds no default route and so has no gateway to ping. Pinging
        // loopback instead — what this used to do — reports a healthy "gateway" for a link
        // that has none, so the section is simply left out and the store says so.
        if (gateway.isNotBlank()) add("echo $SECTION gw" to pingOne(gateway, device))
        add("echo $SECTION dns1" to pingOne("1.1.1.1", device))
        add("echo $SECTION dns2" to pingOne("8.8.8.8", device))
        // Name resolution is a separate failure from reachability, and the one people meet.
        add("echo $SECTION name" to pingOne("openwrt.org", device))
    }.joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * `-I <device>` is what makes this a test of one uplink rather than of the router.
     *
     * Without it every packet follows the default route, so testing a standby WAN silently
     * measured the primary and reported it as the standby's own result.
     */
    private fun pingOne(target: String, device: String) =
        "ping -c 3 -W 2 -q " +
            (if (device.isBlank()) "" else "-I '${escapeValue(device)}' ") +
            "'$target' 2>&1 || true"

    /**
     * Arms the rollback, then applies. The router keeps a copy of `/etc/config/network` and
     * puts it back unless the app confirms within [seconds] — which is the only protection
     * available when the change being applied is the one that carries the connection.
     *
     * The watcher is detached from this session on purpose: it has to outlive the link.
     */
    fun wanApply(operations: List<String>, reload: String, seconds: Int = 30): String = buildString {
        append("mkdir -p $ROLLBACK_DIR && ")
        append("cp /etc/config/network $ROLLBACK_DIR/network && ")
        append("rm -f $ROLLBACK_DIR/confirm && ")
        append("(sleep $seconds; [ -f $ROLLBACK_DIR/confirm ] && exit 0; ")
        append("cp $ROLLBACK_DIR/network /etc/config/network; ")
        append("/etc/init.d/network reload; echo rolled-back > $ROLLBACK_DIR/last) ")
        append(">/dev/null 2>&1 &\n")
        append("uci batch <<'WRTPULSE_EOF'\n")
        operations.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append("uci commit network && ").append(reload).append("; echo applied")
    }

    const val ROLLBACK_DIR = "/tmp/wrtpulse-wan"

    /**
     * Disarms the rollback. Called once the app has re-read the router across the change, so
     * "the app came back" is what confirms it rather than the command returning 0 — an
     * `ifup` answers long before the line is actually up.
     */
    const val WAN_CONFIRM = "touch $ROLLBACK_DIR/confirm && echo confirmed"

    /** Whether the watcher put the old config back while the app was away. */
    const val WAN_ROLLBACK_STATE =
        "cat $ROLLBACK_DIR/last 2>/dev/null; [ -f $ROLLBACK_DIR/confirm ] && echo confirmed || echo pending"

    /** Brings one interface down and up — the gentle reload when no device section changed. */
    fun ifup(name: String) = "ifup '$name' >/dev/null 2>&1; echo done"

    /** A new or edited `config device` needs netifd to rebuild it, which ifup will not do. */
    const val NETWORK_RELOAD = "/etc/init.d/network reload >/dev/null 2>&1; echo done"

    /** The raw uci lines behind one LAN section — what "view command" reveals. */
    fun showUci(path: String) = "uci show $path 2>/dev/null"

    /** dropbear's own view of whether a password will still get you in. */
    const val PASSWORD_AUTH_HELP =
        "uci set dropbear.@dropbear[0].PasswordAuth='off'; " +
        "uci set dropbear.@dropbear[0].RootPasswordAuth='off'; " +
        "uci commit dropbear; /etc/init.d/dropbear restart"

}
