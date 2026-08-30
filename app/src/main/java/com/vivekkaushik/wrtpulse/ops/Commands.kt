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

}
