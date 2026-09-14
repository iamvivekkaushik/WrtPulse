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

    /**
     * From failsafe mode: mount the overlay, wipe it, reboot into a fresh install. The reboot
     * is detached because the reply cannot outlive it.
     */
    const val FAILSAFE_WIPE =
        "mount_root >/dev/null 2>&1; firstboot -y >/dev/null 2>&1; (sleep 1; reboot -f) >/dev/null 2>&1 & echo wiped"

    /** One tick of dashboard state. Sections are delimited so the parser can split cheaply. */
    val DASHBOARD_TICK = listOf(
        "echo $SECTION info" to "ubus call system info",
        "echo $SECTION stat" to "grep '^cpu ' /proc/stat",
        "echo $SECTION netdev" to "cat /proc/net/dev",
        "echo $SECTION overlay" to "df -k /overlay | tail -n1",
        // Every interface, so the upstream can be found by which one holds the default
        // route rather than by assuming it is called "wan".
        "echo $SECTION ifaces" to "ubus call network.interface dump 2>/dev/null || echo '{}'",
        // netifd already knows every wireless interface's SSID and answers over ubus in
        // ~30 ms of CPU; `iw dev` cost 230 ms and bare `iwinfo` 800 ms per tick on a QCA956x,
        // which at 1 Hz was a quarter to most of the router. Both stay as fallbacks.
        "echo $SECTION essid" to
            "ubus call network.wireless status 2>/dev/null || iw dev 2>/dev/null | grep -E 'Interface|ssid' || " +
            "iwinfo 2>/dev/null | grep ESSID || true",
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /** Wireless config as UCI key=value lines. */
    const val WIRELESS_CONFIG = "uci show wireless"

    /** Network config, read alongside it so a new uplink can be given an unused name. */
    const val NETWORK_CONFIG = "uci show network"

    /** Firewall config — which zone each network sits in, for the interface list. */
    const val FIREWALL_CONFIG = "uci show firewall"

    /** Every wireless netdev, from sysfs — no driver round trip, a few ms. */
    const val WIFI_IFACES = "for d in /sys/class/net/*; do [ -d \"\$d/phy80211\" ] && echo \"\${d##*/}\"; done"

    /**
     * Every wireless interface that is actually up: mode, channel, and a station's signal.
     *
     * `iw dev` plus one `iw dev <x> link` per interface, which is what tells a station's
     * signal and the BSSID it joined; bare `iwinfo` only where iw is missing. On a QCA956x
     * the iwinfo binary costs 0.8 s of CPU per run — it re-probes every phy through hostapd
     * at start-up — against 0.3 s for the iw pair; [Parsers.iwinfo] reads both shapes.
     */
    const val IWINFO =
        "if command -v iw >/dev/null 2>&1; then iw dev 2>/dev/null; " +
        "for i in \$($WIFI_IFACES); do echo \"# link \$i\"; iw dev \"\$i\" link 2>/dev/null; done; " +
        "else iwinfo 2>/dev/null; fi"

    /**
     * The channel widths each phy can actually run, from the capability lines of `iw phy`.
     * Read per phy rather than per interface so a radio whose AP is off is still known: the
     * width picker once offered 160 MHz to a QCA9886 that cannot do it, hostapd refused to
     * start, and the network vanished from every client.
     */
    const val PHY_CAPS =
        "for p in /sys/class/ieee80211/*; do n=\${p##*/}; echo \"# \$n\"; " +
        "iw phy \$n info 2>/dev/null | grep -E 'HT20/HT40|Supported Channel Width|VHT Capabilities|HE PHY Capabilities|HE[0-9]+/|EHT PHY|Beamformer'; done"

    /**
     * Every dBm value each running interface's driver will accept — the TX power picker.
     * rpcd's iwinfo module answers the same question over ubus for 66 ms of CPU where the
     * iwinfo binary takes a second per interface; the binary stays for images without rpcd.
     */
    const val TXPOWER_LISTS =
        "UB=; ubus list iwinfo >/dev/null 2>&1 && UB=1; for i in \$($WIFI_IFACES); do echo \"# \$i\"; " +
        "if [ -n \"\$UB\" ]; then ubus call iwinfo txpowerlist \"{\\\"device\\\":\\\"\$i\\\"}\" 2>/dev/null; " +
        "else iwinfo \$i txpowerlist 2>/dev/null; fi; done"

    /**
     * Associated stations per interface, so each SSID can report how many clients it has.
     * `iw station dump` is 16 ms of CPU per interface; `iwinfo assoclist` was 530 ms, and
     * the clients screen asks every five seconds. [Parsers.stations] reads both shapes.
     */
    const val ASSOC_COUNTS =
        "for i in \$($WIFI_IFACES); do echo \"# \$i\"; " +
        "if command -v iw >/dev/null 2>&1; then iw dev \"\$i\" station dump 2>/dev/null; " +
        "else iwinfo \$i assoclist 2>/dev/null; fi; done"

    /** The raw uci lines behind one wifi-iface — what a long-press reveals. */
    fun showSection(section: String) = "uci show wireless.$section 2>/dev/null"

    /** Everything needed to build the client list, in one round trip. */
    val CLIENTS = listOf(
        "echo $SECTION leases" to "cat /tmp/dhcp.leases 2>/dev/null",
        "echo $SECTION neigh" to "ip neigh show",
        "echo $SECTION wifi" to "ubus call network.wireless status",
        "echo $SECTION assoc" to ASSOC_COUNTS,
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

    /**
     * Which phy each radio section really is. radio0 is phy0 on nearly every router, but that
     * is a convention of the config generator, not a rule: the driver is asked.
     */
    const val PHY_NAMES =
        "for r in \$(uci -q show wireless | sed -n 's/^wireless\\.\\([^.=]*\\)=wifi-device\$/\\1/p'); do " +
        // The radio's uci `path` is the tail of the phy's sysfs device path (pci0000:00/…,
        // platform/ahb/…), so the match costs nothing; `iwinfo nl80211 phyname` is half a
        // second of CPU per radio on ath79 and is kept for a path that does not match (a
        // `+1` multi-phy suffix, or no path at all).
        "p=\$(uci -q get wireless.\$r.path); n=; [ -n \"\$p\" ] && for d in /sys/class/ieee80211/*; do " +
        "case \"\$(readlink -f \"\$d/device\" 2>/dev/null)\" in *\"/\$p\") n=\${d##*/};; esac; done; " +
        "[ -n \"\$n\" ] || n=\$(iwinfo nl80211 phyname \$r 2>/dev/null); echo \"\$r \$n\"; done"

    /**
     * `wrt_scan <iface>` — the survey every scan path runs. Prints the neighbour list in `iw`
     * format (BSS blocks), or iwinfo's Cell format from the last resort; [Parsers.scanCells]
     * reads both.
     *
     * Why not plain `iwinfo <iface> scan`: libiwinfo gives up waiting for the scan-complete
     * event after ~5 s, and an ath10k scan with the AP up takes 5–7 s, so on the reference
     * router's 5 GHz radio it failed three times in four with "Netlink error while awaiting
     * scan results: No event received" — while `iw dev <iface> scan dump` held the finished
     * results every single time. `iw dev <iface> scan` waits for the event properly (4/4,
     * 5–6 s) and ships with every OpenWrt wireless image. When the trigger is refused
     * (`Resource busy (-16)`: a scan is already running) the kernel's cached list is read
     * after a moment; only if that is empty too does iwinfo get three tries. No single quotes
     * in here: [surveyWithRadioDown] wraps it in `sh -c '…'`.
     */
    const val SCAN_FN = "wrt_scan() { R=\$(iw dev \"\$1\" scan 2>&1); case \"\$R\" in *BSS*) echo \"\$R\"; return 0;; esac; " +
        "sleep 3; R=\$(iw dev \"\$1\" scan dump 2>/dev/null); case \"\$R\" in *BSS*) echo \"\$R\"; return 0;; esac; " +
        "for i in 1 2 3; do R=\$(iwinfo \"\$1\" scan 2>&1) && break; sleep 2; done; echo \"\$R\"; }"

    /** Neighbour survey for the channel chart, through an interface already on the radio. */
    fun scan(radioIface: String) = "$SCAN_FN; wrt_scan $radioIface"

    /** Where a detached survey leaves its result — one per radio so two never collide. */
    fun surveyFile(radio: String) = "/tmp/wrtpulse-survey-$radio"

    /**
     * The survey that works on a driver that will not leave its channel while the AP is up:
     * take the radio down, scan through a temporary station interface, bring it back.
     *
     * Detached with setsid on purpose. If the phone running the app is on this very band,
     * the SSH session dies the moment the radio goes down; a job tied to that session would
     * be killed before `wifi up` and leave the band off. The job writes to a scratch file and
     * renames it into place when done, so [readSurvey] sees nothing until the whole thing —
     * radio back up included — has run.
     */
    fun surveyWithRadioDown(radio: String, phy: String): String {
        val temp = "wrtpulse-scan"
        val out = surveyFile(radio)
        val job = "$SCAN_FN; wifi down $radio; sleep 2; " +
            "iw dev $temp del >/dev/null 2>&1; " +
            "iw phy $phy interface add $temp type managed >/dev/null 2>&1; " +
            "ip link set $temp up >/dev/null 2>&1; " +
            "wrt_scan $temp > $out.part 2>&1; " +
            "iw dev $temp del >/dev/null 2>&1; " +
            "wifi up $radio; " +
            "echo \"$SECTION done\" >> $out.part; mv $out.part $out"
        return "rm -f $out $out.part; setsid sh -c '$job' >/dev/null 2>&1 </dev/null & echo started"
    }

    /** The detached survey's result; empty until the job has finished and the radio is back. */
    fun readSurvey(radio: String) = "cat ${surveyFile(radio)} 2>/dev/null"

    /** Tidies up after a detached survey has been collected. */
    fun forgetSurvey(radio: String) = "rm -f ${surveyFile(radio)}"

    /**
     * Survey a radio that has no interface of its own — a band with no SSID configured has
     * no netdev, so nothing can scan through it. Adds a station interface just long enough
     * to scan, then removes it. Nothing is written to uci.
     */
    fun scanViaTempInterface(phy: String): String {
        val temp = "wrtpulse-scan"
        return "$SCAN_FN; iw dev $temp del >/dev/null 2>&1; " +
            "iw phy $phy interface add $temp type managed >/dev/null 2>&1 || " +
            "{ echo 'ERR add'; exit 1; }; " +
            // ath10k refuses to bring a station up beside a running AP (SIOCSIFFLAGS): say so
            // rather than scan through an interface that is down, and never leave it behind.
            "ip link set $temp up >/dev/null 2>&1 || " +
            "{ iw dev $temp del >/dev/null 2>&1; echo 'ERR up'; exit 1; }; " +
            "R=\$(wrt_scan $temp); " +
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

    /**
     * Public, unauthenticated fixed-size download; the app times the round trip itself.
     *
     * Both legs go over plain http on purpose. The payload is throwaway bytes to /dev/null,
     * nothing to protect, and TLS was the ceiling: on a 775 MHz MIPS core with no crypto
     * instructions curl decrypted at ~65 Mbps with the CPU pegged, while the same download
     * over http measured 232 Mbps on the same box. Cloudflare's endpoint answers both.
     */
    const val SPEEDTEST_HOST = "speed.cloudflare.com"

    /**
     * Each leg is one transfer of up to 100 MB, cut off after [SPEEDTEST_SECONDS] — whichever
     * comes first. A 20 MB transfer was over in under a second on a fast line, a one-second
     * sample of a bursty link; 100 MB is several seconds there and the clock bounds it on a
     * slow one. curl reports how much moved either way.
     */
    const val SPEEDTEST_SECONDS = 10

    /** Cloudflare answers 403 to a single object of 100 MB or more; this is the most it gives. */
    const val SPEEDTEST_DOWN_BYTES = 99_999_999L
    const val SPEEDTEST_UP_BYTES = 100_000_000L

    /**
     * Without curl there is no clock and no partial count, so the fallback moves a fixed,
     * smaller amount and the app wall-times it.
     */
    const val SPEEDTEST_FALLBACK_BYTES = 20_000_000L

    /** curl's exit status for hitting `--max-time`; for a speed test that is the normal end. */
    private const val CURL_TIMED_OUT = "[ \$rc -eq 28 ] && rc=0"

    /**
     * The CPU line of /proc/stat, printed before and after a transfer so the app can tell how
     * busy the router was while it ran. A single small core doing the download itself sits at
     * 1% idle; what it reports is its own ceiling, not the line's, and the dialog says so.
     */
    private const val CPU_SAMPLE = "grep '^cpu ' /proc/stat"

    /**
     * What curl prints after a transfer: the bytes moved, the whole wall time, and how much of
     * it went on DNS and connection setup. The app measures from the third number to the
     * second, so a slow lookup on a small router does not read as a slow line. The trailing
     * newline keeps the CPU sample that follows off the same line.
     */
    private const val CURL_TIMING = "-w '%{size_download} %{size_upload} %{time_total} %{time_pretransfer}\\n'"

    /**
     * Pulls up to [bytes] from the speed-test endpoint on the router and discards it, giving
     * up after [seconds].
     *
     * With curl the last line carries its own timing (see [CURL_TIMING]) and the bytes that
     * arrived before the clock ran out; without it a fixed [SPEEDTEST_FALLBACK_BYTES] is
     * fetched and its count echoed, so a silent failure can't be mistaken for an instant
     * download, and the app falls back to timing the round trip itself.
     */
    fun speedtestDownload(bytes: Long = SPEEDTEST_DOWN_BYTES, seconds: Int = SPEEDTEST_SECONDS): String =
        "$CPU_SAMPLE; if command -v curl >/dev/null 2>&1; then " +
        "curl -s -o /dev/null --max-time $seconds $CURL_TIMING 'http://$SPEEDTEST_HOST/__down?bytes=$bytes'; " +
        "rc=\$?; $CURL_TIMED_OUT; else " +
        "URL='http://$SPEEDTEST_HOST/__down?bytes=$SPEEDTEST_FALLBACK_BYTES'; " +
        "{ uclient-fetch -q -O /dev/null \"\$URL\" || wget -q -O /dev/null \"\$URL\"; } && echo $SPEEDTEST_FALLBACK_BYTES; " +
        "rc=\$?; fi; $CPU_SAMPLE; exit \$rc"

    /** Scratch payload for the upload leg, in /tmp so it is cleaned up straight after. */
    const val SPEEDTEST_UPLOAD_FILE = "/tmp/wrtpulse-speedtest.bin"

    /**
     * Built before the timed leg so making the file isn't counted as upload time.
     *
     * Sparse, not written: /tmp is RAM and a 128 MB router has ~40 MB of it free, so a real
     * 100 MB file cannot exist there. A hole in tmpfs reads as zeros without a page being
     * allocated, and curl still sees a regular file with a length — which is what keeps the
     * upload fast; streaming from stdin cost this core half its throughput.
     */
    fun speedtestPrepareUpload(bytes: Long = SPEEDTEST_UP_BYTES): String =
        "dd if=/dev/zero of=$SPEEDTEST_UPLOAD_FILE bs=1 count=0 seek=$bytes 2>/dev/null && echo ready"

    /**
     * curl first: OpenWrt's uclient-fetch accepts --post-file but stalls partway through a
     * large body and the far end resets, so it cannot measure an upload.
     *
     * `-T file -X POST` streams the file. `--data-binary @file` reads all of it into memory
     * first, and on a 128 MB router with the same 20 MB already sitting in tmpfs that got curl
     * killed by the OOM reaper — which the app then reported as "curl missing".
     */
    fun speedtestUpload(bytes: Long = SPEEDTEST_UP_BYTES, seconds: Int = SPEEDTEST_SECONDS): String =
        "URL='http://$SPEEDTEST_HOST/__up'; $CPU_SAMPLE; " +
        "if command -v curl >/dev/null 2>&1; then " +
        "curl -s -o /dev/null --max-time $seconds $CURL_TIMING -T $SPEEDTEST_UPLOAD_FILE -X POST \"\$URL\"; " +
        "rc=\$?; $CURL_TIMED_OUT; else " +
        "uclient-fetch -q -O /dev/null --post-file=$SPEEDTEST_UPLOAD_FILE \"\$URL\" && echo $bytes; rc=\$?; fi; " +
        "$CPU_SAMPLE; exit \$rc"

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
        // Whether offloading can be offered at all: software needs the flow-table module, and
        // hardware only exists on silicon that has an offload engine — the target says which.
        "echo $SECTION offload" to
            "([ -d /sys/module/nf_flow_table ] || ls /lib/modules/*/nf_flow_table.ko >/dev/null 2>&1) && echo software; " +
            "ubus call system board 2>/dev/null | jsonfilter -e '@.release.target' 2>/dev/null",
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
        "echo $SECTION board" to BOARD_SWITCH,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * The switch block of `/etc/board.json`: which chip ports are wired to which case socket,
     * and which is the CPU port. LuCI's switch page reads the same file, which is how it
     * shows "LAN 1" and "LAN 2" on a chip that reports seven ports. Empty on DSA boards and
     * on any board whose file has no switch block.
     */
    val BOARD_SWITCH = "jsonfilter -i /etc/board.json -e '@.switch' 2>/dev/null || true"

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
     * The first thing asked of a router reached over the setup cable. Failsafe mode has no
     * ubus and no overlay, so the board call alone would look like a dead router; the marker
     * file names the state instead, and the board name comes from sysinfo either way.
     */
    const val HOP_PROBE =
        "[ -f /tmp/.failsafe ] && echo wrtpulse-failsafe; cat /tmp/sysinfo/board_name 2>/dev/null; " +
            "echo $SECTION board; $BOARD 2>/dev/null; true"

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
        // Which protocols netifd can actually bring up on this router. PPPoE, PPtP and PPPoA
        // have no script of their own: ppp.sh registers each one only when its pppd plugin
        // is installed, so the plugin directory is listed too.
        "echo $SECTION protos" to "ls /lib/netifd/proto 2>/dev/null; ls /usr/lib/pppd/*/ 2>/dev/null",
        // The switch chip on a swconfig board: its sockets are numbers here, not netdevs.
        "echo $SECTION swconfig" to SWCONFIG,
        "echo $SECTION board" to BOARD_SWITCH,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * Everything the static-routes screen reads: the config, the interface dump (for each
     * interface's live subnet), and what the kernel actually holds for both families.
     */
    val ROUTES_STATE = listOf(
        "echo $SECTION net" to NETWORK_CONFIG,
        "echo $SECTION dump" to "ubus call network.interface dump 2>/dev/null || echo '{}'",
        "echo $SECTION v4" to "ip -4 route show 2>/dev/null",
        "echo $SECTION v6" to "ip -6 route show 2>/dev/null",
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
     *
     * [packages] is every config file the batch writes into, not just `network`. IPv6 on this
     * screen is half `network` and half `dhcp` — odhcpd's `ra`, `dhcpv6` and `ndp` live in the
     * latter — and committing only `network` left the dhcp half sitting in uci's delta: unwritten
     * to disk, invisible because `uci show` merges the delta back, and eventually committed by
     * whichever unrelated screen ran `uci commit dhcp` next.
     *
     * Every committed file is also snapshotted and restored, so a rollback puts back exactly what
     * the apply touched. Restoring `network` alone would leave the dhcp half applied against the
     * old network config — the mismatch a relay setup fails on.
     */
    fun wanApply(
        operations: List<String>,
        packages: List<String>,
        reload: String,
        seconds: Int = 30,
    ): String = buildString {
        val pkgs = packages.ifEmpty { listOf("network") }
        append("mkdir -p $ROLLBACK_DIR && ")
        pkgs.forEach { append("cp /etc/config/$it $ROLLBACK_DIR/$it && ") }
        append("rm -f $ROLLBACK_DIR/confirm && ")
        append("(sleep $seconds; [ -f $ROLLBACK_DIR/confirm ] && exit 0; ")
        pkgs.forEach { append("cp $ROLLBACK_DIR/$it /etc/config/$it; ") }
        append("/etc/init.d/network reload; ")
        if ("dhcp" in pkgs) append("$ODHCPD_RELOAD; ")
        if ("firewall" in pkgs) append("$FIREWALL_RELOAD; ")
        append("echo rolled-back > $ROLLBACK_DIR/last) ")
        append(">/dev/null 2>&1 &\n")
        append("uci batch <<'WRTPULSE_EOF'\n")
        operations.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append(pkgs.joinToString(" && ") { "uci commit $it" })
        append(" && ").append(reload).append("; echo applied")
    }

    /**
     * Router advertisements and DHCPv6 are odhcpd's, and it only re-reads `/etc/config/dhcp` when
     * told to. `network reload` does not tell it, so without this a committed `ra`/`dhcpv6`/`ndp`
     * change sits on disk while clients carry on with the old advertisements. Absent on a router
     * that serves v6 from dnsmasq instead, hence the guard rather than a bare call.
     */
    const val ODHCPD_RELOAD =
        "[ -x /etc/init.d/odhcpd ] && /etc/init.d/odhcpd reload >/dev/null 2>&1; " +
            "[ -x /etc/init.d/dnsmasq ] && /etc/init.d/dnsmasq reload >/dev/null 2>&1; :"

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

    /**
     * Takes one interface down and leaves it there: the address goes, the route goes, a PPPoE
     * session ends, a Wi-Fi client disassociates. Nothing brings it back but [ifup] or a
     * reboot — netifd does not retry a stopped interface.
     */
    fun ifdown(name: String) = "ifdown '$name' >/dev/null 2>&1; echo done"

    /** A new or edited `config device` needs netifd to rebuild it, which ifup will not do. */
    const val NETWORK_RELOAD = "/etc/init.d/network reload >/dev/null 2>&1; echo done"

    /** The raw uci lines behind one LAN section — what "view command" reveals. */
    fun showUci(path: String) = "uci show $path 2>/dev/null"

    /** dropbear's own view of whether a password will still get you in. */
    const val PASSWORD_AUTH_HELP =
        "uci set dropbear.@dropbear[0].PasswordAuth='off'; " +
        "uci set dropbear.@dropbear[0].RootPasswordAuth='off'; " +
        "uci commit dropbear; /etc/init.d/dropbear restart"

    // ── Mesh ──────────────────────────────────────────────────────────────────
    // A primary is read like the wireless screen plus what the nodes need to know; a node is
    // written in one batch under a rollback like the WAN's, because the batch moves the
    // node's own address and the app has to find it again afterwards.

    const val MESH_DIR = "/tmp/wrtpulse-mesh"

    /** Whether the installed wpa_supplicant was built with 802.11s. `wpad-basic-*` was not. */
    const val MESH_CAPABLE = "wpa_supplicant -vmesh >/dev/null 2>&1 && echo yes || echo no"

    /** The wpad variant installed — `wpad-basic-mbedtls`, `wpad-mesh-openssl`, bare `wpad`. */
    const val WPAD_NAME =
        "(opkg list-installed 2>/dev/null || apk list -I 2>/dev/null) | grep -oE '^wpad(-[a-z]+)*' | head -n1"

    /** The hostapd config items the roaming options turn into — what [HOSTAPD_PROBE] asks about. */
    val HOSTAPD_PROBE_ITEMS: List<String> = listOf(
        "bss_transition=1", "rrm_neighbor_report=1", "rrm_beacon_report=1",
        "mobility_domain=a1b2", "ft_over_ds=0", "ft_psk_generate_local=1",
    )

    /** A config item no hostapd knows, so the probe file is always rejected while being parsed. */
    const val HOSTAPD_PROBE_MARKER = "wrtpulse_probe_end"
    private const val HOSTAPD_PROBE_FILE = "/tmp/wrtpulse-hostapd-probe.conf"

    /**
     * Asks hostapd itself which roaming options it accepts, and prints the ones it does not.
     *
     * hostapd rejects a whole radio's config over one unknown item: `bss_transition=1` on
     * wpad-basic logged "unknown configuration item" and `add_iface failed` on both phys of
     * the reference router, and every SSID went dark. Grepping the binary for the item name is
     * not a test — the full build carries the literal for its ubus method whether or not the
     * config parser knows it. So the probe hands hostapd a file of the candidate items plus
     * [HOSTAPD_PROBE_MARKER], which nothing knows: hostapd lists every unknown item and exits
     * while still parsing, before it touches a driver or the running daemon. An answer without
     * the marker means hostapd never parsed the file, and [Parsers.hostapdUnknownItems] says so.
     */
    val HOSTAPD_PROBE: String =
        "printf '%s\\n' interface=wrtpulse-probe0 ssid=wrtpulse-probe ${HOSTAPD_PROBE_ITEMS.joinToString(" ")} " +
        "$HOSTAPD_PROBE_MARKER=1 > $HOSTAPD_PROBE_FILE; " +
        "hostapd $HOSTAPD_PROBE_FILE 2>&1 | grep -oE \"unknown configuration item '[A-Za-z0-9_]+'\" | awk -F\"'\" '{print \$2}'; " +
        "rm -f $HOSTAPD_PROBE_FILE"

    const val WIFI_ROLLBACK_DIR = "/tmp/wrtpulse-wifi"

    /**
     * Arms a rollback, then applies a wireless batch. When hostapd rejects the new config the
     * phone running the app is usually one of the clients that just lost its network, so the
     * router keeps the pre-change file and puts it back on its own unless the app confirms
     * within [seconds]. The app confirms ([WIFI_CONFIRM]) only after [AP_HEALTH] shows every
     * AP beaconing again, and restores at once ([WIFI_ROLLBACK_NOW]) when it shows they are
     * not. Detached so it outlives the link, like [wanApply].
     */
    fun wifiApply(operations: List<String>, seconds: Int = 90): String = buildString {
        append("mkdir -p $WIFI_ROLLBACK_DIR && cp /etc/config/wireless $WIFI_ROLLBACK_DIR/wireless && ")
        append("rm -f $WIFI_ROLLBACK_DIR/confirm $WIFI_ROLLBACK_DIR/last && ")
        append("(sleep $seconds; [ -f $WIFI_ROLLBACK_DIR/confirm ] && exit 0; ")
        append("cp $WIFI_ROLLBACK_DIR/wireless /etc/config/wireless; wifi reload; ")
        append("echo rolled-back > $WIFI_ROLLBACK_DIR/last) >/dev/null 2>&1 &\n")
        append("uci batch <<'WRTPULSE_EOF'\n")
        operations.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append("uci commit wireless && wifi reload; echo applied")
    }

    /** Disarms the wireless rollback — sent once every AP is seen beaconing again. */
    const val WIFI_CONFIRM = "touch $WIFI_ROLLBACK_DIR/confirm && echo confirmed"

    /** Puts the pre-change wireless config back now; disarms the timer first so it does not reload twice. */
    const val WIFI_ROLLBACK_NOW =
        "touch $WIFI_ROLLBACK_DIR/confirm; cp $WIFI_ROLLBACK_DIR/wireless /etc/config/wireless && wifi reload && " +
        "echo rolled-back > $WIFI_ROLLBACK_DIR/last; echo rolled-back"

    /** Whether the router rolled a wireless change back by itself while the app was away; read once. */
    const val WIFI_LAST = "cat $WIFI_ROLLBACK_DIR/last 2>/dev/null; rm -f $WIFI_ROLLBACK_DIR/last"

    /** Every wireless netdev with its type and, only when it is actually on the air, its channel. */
    const val AP_HEALTH = "iw dev 2>/dev/null | grep -E 'Interface|type|channel'"

    /** The config items hostapd refused recently — why an AP did not come back. */
    const val HOSTAPD_REJECTIONS =
        "logread -l 300 2>/dev/null | grep -oE \"unknown configuration item '[A-Za-z0-9_]+'\" | awk -F\"'\" '{print \$2}' | sort -u"

    /** Every mesh point that is up, as `# <ifname>` followed by its `iw station dump`. */
    const val MESH_PEERS =
        "for i in \$(iw dev 2>/dev/null | awk '/Interface/{print \$2}'); do " +
        "if iw dev \$i info 2>/dev/null | grep -q 'type mesh'; then echo \"# \$i\"; " +
        "iw dev \$i station dump 2>/dev/null; fi; done"

    /** Every wireless netdev with its MAC and type — `iw dev` cut to the lines that matter. */
    const val WIFI_MACS = "iw dev 2>/dev/null | grep -E 'Interface|addr|type'"

    /** Free overlay space, the same line the package screen reads. */
    const val OVERLAY_FREE = "df -k /overlay 2>/dev/null | tail -n1"

    /**
     * One ping per node, all at once: five nodes that are off cost one second, not five.
     * Prints `<ip> <rtt-ms>` or `<ip> -` per address. Addresses come from the app's own
     * saved rows and are confined to the characters an address can carry before they get here.
     */
    fun pingHosts(ips: List<String>): String {
        val safe = ips.filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in ".:-" } }
        if (safe.isEmpty()) return "true"
        return safe.joinToString(" ") { ip ->
            "(r=\$(ping -c1 -W1 '$ip' 2>/dev/null | sed -n 's/.*time=\\([0-9.]*\\).*/\\1/p' | head -n1); " +
                "echo \"$ip \${r:--}\") &"
        } + " wait"
    }

    /** Everything the mesh screen reads on a primary, in one round trip. */
    fun meshState(nodeIps: List<String>): String = listOf(
        "echo $SECTION uci" to WIRELESS_CONFIG,
        "echo $SECTION status" to "ubus call network.wireless status 2>/dev/null || echo '{}'",
        "echo $SECTION net" to NETWORK_CONFIG,
        "echo $SECTION dhcp" to "uci show dhcp 2>/dev/null",
        "echo $SECTION iwinfo" to IWINFO,
        "echo $SECTION capable" to MESH_CAPABLE,
        "echo $SECTION wpad" to WPAD_NAME,
        "echo $SECTION hostapd" to HOSTAPD_PROBE,
        "echo $SECTION wifilast" to WIFI_LAST,
        "echo $SECTION pm" to DETECT_PACKAGE_MANAGER,
        "echo $SECTION peers" to MESH_PEERS,
        "echo $SECTION macs" to WIFI_MACS,
        "echo $SECTION leases" to "cat /tmp/dhcp.leases 2>/dev/null",
        "echo $SECTION neigh" to "ip neigh show",
        "echo $SECTION df" to OVERLAY_FREE,
        "echo $SECTION board" to BOARD,
        "echo $SECTION presnap" to NODE_SNAPSHOT_STATE,
        "echo $SECTION swconfig" to SWCONFIG,
        "echo $SECTION boardsw" to BOARD_SWITCH,
        "echo $SECTION ping" to pingHosts(nodeIps),
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /** Everything the join wizard needs to know about the router that is about to become a node. */
    val MESH_NODE_STATE: String = listOf(
        "echo $SECTION net" to NETWORK_CONFIG,
        "echo $SECTION dhcp" to "uci show dhcp 2>/dev/null",
        "echo $SECTION uci" to WIRELESS_CONFIG,
        "echo $SECTION status" to "ubus call network.wireless status 2>/dev/null || echo '{}'",
        "echo $SECTION board" to BOARD_SWITCH,
        "echo $SECTION swconfig" to SWCONFIG,
        "echo $SECTION links" to NETDEVS,
        "echo $SECTION dump" to "ubus call network.interface dump 2>/dev/null || echo '{}'",
        "echo $SECTION capable" to MESH_CAPABLE,
        "echo $SECTION wpad" to WPAD_NAME,
        "echo $SECTION hostapd" to HOSTAPD_PROBE,
        "echo $SECTION pm" to DETECT_PACKAGE_MANAGER,
        "echo $SECTION df" to OVERLAY_FREE,
        "echo $SECTION macs" to WIFI_MACS,
        "echo $SECTION system" to BOARD,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * Replaces the wpad build with the one that has 802.11s, detached, because the swap
     * restarts every radio and takes the link with it. apk does it as one transaction that
     * also pulls the matching hostapd-common; opkg downloads first so the box is never left
     * with no wpad at all, and puts the old one back if the new one will not install.
     * Writes `ok` or `failed` to `$MESH_DIR/swap` when done, which the app polls.
     */
    fun wpadSwap(remove: String, install: String, manager: String): String {
        require(safePackageName(remove) && safePackageName(install)) { "package name" }
        val safeRemove = remove
        val safeInstall = install
        val swap = if (manager == "apk") {
            "apk update >>$MESH_DIR/swap.log 2>&1; apk add '$safeInstall' '!$safeRemove' >>$MESH_DIR/swap.log 2>&1"
        } else {
            "cd /tmp && opkg update >>$MESH_DIR/swap.log 2>&1 && opkg download '$safeInstall' >>$MESH_DIR/swap.log 2>&1 && " +
                "opkg remove '$safeRemove' >>$MESH_DIR/swap.log 2>&1 && " +
                "(opkg install /tmp/${safeInstall}_*.ipk >>$MESH_DIR/swap.log 2>&1 || " +
                "opkg install '$safeRemove' >>$MESH_DIR/swap.log 2>&1); rm -f /tmp/${safeInstall}_*.ipk"
        }
        return "mkdir -p $MESH_DIR && rm -f $MESH_DIR/swap && (" +
            "$swap; [ -x /etc/init.d/wpad ] && /etc/init.d/wpad restart; wifi down; sleep 2; wifi up; sleep 3; " +
            "if wpa_supplicant -vmesh >/dev/null 2>&1; then echo ok > $MESH_DIR/swap; else echo failed > $MESH_DIR/swap; fi" +
            ") >/dev/null 2>&1 & echo scheduled"
    }

    /** What the swap wrote, or `pending` while it is still running. */
    const val SWAP_STATE = "cat $MESH_DIR/swap 2>/dev/null || echo pending; tail -n 3 $MESH_DIR/swap.log 2>/dev/null"

    /**
     * The node batch under a rollback. Like [wanApply], but the reload is detached and the reply
     * comes back before the address moves; the restorer reloads everything it puts back —
     * network, wireless, dnsmasq's pool and the hostname — since a node's batch touches all of
     * them. Nothing here disables a service; that waits for [NODE_CONFIRM] so a rollback has
     * nothing to switch back on.
     */
    fun nodeApply(operations: List<String>, packages: List<String>, seconds: Int): String = buildString {
        val pkgs = packages.ifEmpty { listOf("network") }
        // The copies are taken in the foreground, before anything is committed: a snapshot
        // racing the commit it protects against is no snapshot. A failed copy ends the script
        // with nothing written.
        append("mkdir -p $MESH_DIR && ")
        pkgs.forEach { append("cp /etc/config/$it $MESH_DIR/$it && ") }
        append("rm -f $MESH_DIR/confirm $MESH_DIR/last || exit 1\n")
        append("(sleep $seconds; [ -f $MESH_DIR/confirm ] && exit 0; ")
        pkgs.forEach { append("cp $MESH_DIR/$it /etc/config/$it; ") }
        append("/etc/init.d/network reload; wifi reload; ")
        append("[ -x /etc/init.d/dnsmasq ] && /etc/init.d/dnsmasq restart; $ODHCPD_RELOAD; ")
        append("/etc/init.d/system reload; ")
        append("echo rolled-back > $MESH_DIR/last) ")
        append(">/dev/null 2>&1 &\n")
        append("uci batch <<'WRTPULSE_EOF'\n")
        operations.forEach { append(it).append('\n') }
        append("WRTPULSE_EOF\n")
        append(pkgs.joinToString(" && ") { "uci commit $it" })
        // The reply cannot come back over a link this reload is about to re-address.
        append(" && (sleep 1; /etc/init.d/network reload; wifi reload; /etc/init.d/system reload) >/dev/null 2>&1 & echo scheduled")
    }

    /** Disarms the node's rollback. Its own tiny command, so a slow service stop cannot cut it off. */
    const val NODE_CONFIRM = "mkdir -p $MESH_DIR && touch $MESH_DIR/confirm && echo confirmed"

    /** Whether the node put its old config back while the app was finding it. */
    const val NODE_ROLLBACK_STATE =
        "cat $MESH_DIR/last 2>/dev/null; [ -f $MESH_DIR/confirm ] && echo confirmed || echo pending"

    /**
     * What a node stops doing: the primary serves addresses, names and the firewall for the
     * whole LAN, and a second dnsmasq on the same wire hands out a second set of answers.
     */
    /**
     * A wireless node's safety net, run by cron every minute: when its mesh point has no peer,
     * it scans for the primary's mesh id and moves its radio to whatever channel the primary
     * is on now. A channel change on the primary — from this app, LuCI or a DFS radar event —
     * otherwise strands the node on the old channel with no way to reach it and tell it.
     */
    const val MESH_WATCH_PATH = "/usr/bin/wrtpulse-meshwatch"
    val MESH_WATCH_INSTALL: String = buildString {
        append("cat > $MESH_WATCH_PATH <<'WRTPULSE_EOF'\n")
        append(
            """
            #!/bin/sh
            # WrtPulse: keep this node's mesh point on the primary's channel.
            IF=${'$'}(iw dev 2>/dev/null | awk '/Interface/{i=${'$'}2} /type mesh/{print i}' | head -1)
            [ -n "${'$'}IF" ] || exit 0
            iw dev "${'$'}IF" station dump 2>/dev/null | grep -q 'mesh plink:.*ESTAB' && exit 0
            MID=${'$'}(uci -q get wireless.wrtpulse_mesh.mesh_id); RADIO=${'$'}(uci -q get wireless.wrtpulse_mesh.device)
            [ -n "${'$'}MID" ] && [ -n "${'$'}RADIO" ] || exit 0
            CUR=${'$'}(uci -q get wireless.${'$'}RADIO.channel)
            F=${'$'}(iw dev "${'$'}IF" scan 2>/dev/null | awk -v id="${'$'}MID" '/^BSS/{f=""} /freq:/{f=${'$'}2} /MESH ID: /{ if (substr(${'$'}0, index(${'$'}0, "MESH ID: ")+9)==id) print f }' | head -1)
            [ -n "${'$'}F" ] || exit 0
            F=${'$'}{F%.*}
            if [ "${'$'}F" -ge 5000 ]; then CH=${'$'}(( (F-5000)/5 )); else CH=${'$'}(( (F-2407)/5 )); fi
            [ "${'$'}CH" = "${'$'}CUR" ] && exit 0
            logger -t wrtpulse "mesh peer lost; primary found on channel ${'$'}CH, moving from ${'$'}CUR"
            uci set wireless.${'$'}RADIO.channel="${'$'}CH"; uci commit wireless; wifi reload
            """.trimIndent()
        )
        append("\nWRTPULSE_EOF\n")
        append("chmod +x $MESH_WATCH_PATH; ")
        append("grep -q wrtpulse-meshwatch /etc/crontabs/root 2>/dev/null || ")
        append("echo '* * * * * $MESH_WATCH_PATH' >> /etc/crontabs/root; ")
        append("/etc/init.d/cron enable >/dev/null 2>&1; /etc/init.d/cron restart >/dev/null 2>&1; echo watch")
    }

    /** Takes the watchdog and its cron line off a node that leaves the mesh or goes wired. */
    const val MESH_WATCH_REMOVE =
        "rm -f $MESH_WATCH_PATH; [ -f /etc/crontabs/root ] && sed -i '/wrtpulse-meshwatch/d' /etc/crontabs/root; " +
        "/etc/init.d/cron restart >/dev/null 2>&1; echo unwatched"

    /**
     * The router's own SSH host keys as public lines — what the app pins for a router it has
     * only ever reached through the primary, so its first direct contact is not a first contact.
     */
    const val HOST_KEY_LINES =
        "for f in /etc/dropbear/dropbear_ed25519_host_key /etc/dropbear/dropbear_rsa_host_key /etc/dropbear/dropbear_ecdsa_host_key; do " +
        "[ -f \$f ] && dropbearkey -y -f \$f 2>/dev/null | grep -E '^(ssh|ecdsa)-'; done; :"

    // ── The setup port on a primary ─────────────────────────────────────────
    const val SETUP_DIR = "/tmp/wrtpulse-setup"

    /** Terminator for a heredoc that carries another heredoc inside it. */
    const val FILE_EOF = "WRTPULSE_FILE_EOF"

    /**
     * Applies the isolation and, in the same breath, arms its undoing: a detached timer puts
     * the socket back in the LAN by itself if the app never does — a phone that died mid-setup
     * must not leave a LAN socket dead. [release] is the batch that undoes it, kept on the
     * router so the timer needs nothing from the phone.
     */
    fun setupApply(isolate: List<String>, release: List<String>, linkCheck: String, seconds: Int = 1200): String = buildString {
        // The stored script carries a `uci batch` heredoc of its own, so the wrapper's
        // terminator has to differ from the batch's, or the wrapper ends where the batch does
        // and the rest of the script runs on the spot.
        append("mkdir -p $SETUP_DIR && cat > $SETUP_DIR/release.sh <<'$FILE_EOF'\n")
        append(uciBatch(release, MeshOps.SETUP_PACKAGES, "/etc/init.d/network reload >/dev/null 2>&1; $FIREWALL_RELOAD >/dev/null 2>&1; echo released"))
        append("\nrm -f $SETUP_DIR/release.sh $SETUP_DIR/timer.pid $SETUP_DIR/link.sh\n$FILE_EOF\n")
        // Whether the held socket still has a cable in it. A router left cabled there must
        // never be handed to the LAN by a timer: its DHCP server would be too.
        append("cat > $SETUP_DIR/link.sh <<'$FILE_EOF'\n").append(linkCheck).append("\n$FILE_EOF\n")
        append("[ -f $SETUP_DIR/timer.pid ] && kill \$(cat $SETUP_DIR/timer.pid) 2>/dev/null; ")
        append("(sleep $seconds; while [ -f $SETUP_DIR/release.sh ]; do ")
        append("if [ \"\$(sh $SETUP_DIR/link.sh)\" = \"up\" ]; then sleep 300; else sh $SETUP_DIR/release.sh; fi; done) ")
        append(">/dev/null 2>&1 & echo \$! > $SETUP_DIR/timer.pid; ")
        append(uciBatch(isolate, MeshOps.SETUP_PACKAGES, "/etc/init.d/network reload >/dev/null 2>&1; $FIREWALL_RELOAD >/dev/null 2>&1; echo isolated"))
    }

    /** `up` or `down` for one socket: a netdev's carrier, or a chip port's link. */
    fun socketLinkCheck(port: String): String =
        if (port.startsWith("sw:")) {
            val n = port.removePrefix("sw:")
            "swconfig dev \$(swconfig list | sed -n 's/^Found:*[[:space:]]*\\([^ ]*\\).*/\\1/p' | head -1) port $n get link 2>/dev/null | grep -q 'link:up' && echo up || echo down"
        } else {
            "[ \"\$(cat /sys/class/net/$port/carrier 2>/dev/null)\" = 1 ] && echo up || echo down"
        }

    /**
     * Runs the stored undo now and cancels the timer — unless a cable is still in the held
     * socket, in which case it says so and holds on. Safe when nothing is held.
     */
    const val SETUP_RELEASE =
        "if [ ! -f $SETUP_DIR/release.sh ]; then echo nothing-held; " +
        "elif [ -f $SETUP_DIR/link.sh ] && [ \"\$(sh $SETUP_DIR/link.sh)\" = \"up\" ]; then echo still-cabled; " +
        "else [ -f $SETUP_DIR/timer.pid ] && kill \$(cat $SETUP_DIR/timer.pid) 2>/dev/null; sh $SETUP_DIR/release.sh; fi"

    /** The release a finished join runs: the node is on the LAN now, the cable is meant to stay. */
    const val SETUP_RELEASE_KEEP_CABLE =
        "[ -f $SETUP_DIR/timer.pid ] && kill \$(cat $SETUP_DIR/timer.pid) 2>/dev/null; " +
        "if [ -f $SETUP_DIR/release.sh ]; then sh $SETUP_DIR/release.sh; else echo nothing-held; fi"

    /**
     * Who answers on the setup bridge: the all-hosts ping makes every neighbour speak, and the
     * neighbour table then lists their link-local addresses. One entry is the new router.
     */
    const val SETUP_DISCOVER =
        "ping -6 -c2 -W1 -I ${MeshOps.SETUP_BRIDGE} ff02::1 >/dev/null 2>&1; " +
        "ip -6 neigh show dev ${MeshOps.SETUP_BRIDGE} 2>/dev/null | awk '/^fe80/ && !/FAILED/ {print \$1}'"

    /** The link state the cable watch polls: netdevs on DSA, chip ports on swconfig. */
    val SETUP_LINKS: String = listOf(
        "echo $SECTION links" to NETDEVS,
        "echo $SECTION swconfig" to SWCONFIG,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    // ── The pre-mesh snapshot kept on the node itself ─────────────────────
    /**
     * Where a node keeps the config it had before it joined. On its own flash, so any phone —
     * or this app freshly installed — can put it back; the copy on the phone is a second one.
     * Not in the archive's own file list, so a restore does not carry it forward.
     */
    const val NODE_SNAPSHOT = "/etc/wrtpulse/pre-mesh.tar.gz"

    /** Taken before the batch, while the config is still the router's own. */
    const val NODE_KEEP_SNAPSHOT =
        "mkdir -p /etc/wrtpulse && rm -f $NODE_SNAPSHOT && sysupgrade -b $NODE_SNAPSHOT >/dev/null 2>&1 && wc -c < $NODE_SNAPSHOT"

    /** Whether the node holds one, and the LAN address inside it — what Leave would bring back. */
    const val NODE_SNAPSHOT_STATE =
        "if [ -f $NODE_SNAPSHOT ]; then echo present; " +
        "tar -xzOf $NODE_SNAPSHOT etc/config/network 2>/dev/null | sed -n \"s/.*option ipaddr '\\(.*\\)'/\\1/p\" | head -1; " +
        "tar -xzOf $NODE_SNAPSHOT etc/config/system 2>/dev/null | sed -n \"s/.*option hostname '\\(.*\\)'/\\1/p\" | head -1; " +
        "else echo absent; fi"

    /**
     * Puts the snapshot back and reboots, the way the backup screen restores: `sysupgrade -r`
     * unpacks over /, the file is removed so the restored router does not carry it, and the
     * reboot is detached because the reply cannot outlive it.
     */
    const val NODE_RESTORE_SNAPSHOT =
        "[ -f $NODE_SNAPSHOT ] || { echo absent; exit 0; }; " +
        "if sysupgrade -r $NODE_SNAPSHOT >/dev/null 2>&1; then rm -f $NODE_SNAPSHOT; " +
        "(sleep 1; reboot) >/dev/null 2>&1 & echo restored; else echo failed; fi"

    const val NODE_SERVICES_OFF =
        "for s in firewall dnsmasq odhcpd; do [ -x /etc/init.d/\$s ] && " +
        "{ /etc/init.d/\$s disable; /etc/init.d/\$s stop; } >/dev/null 2>&1; done; " +
        "/etc/init.d/system reload >/dev/null 2>&1; echo done"

    // ── LEDs ──────────────────────────────────────────────────────────────────
    // The router says what LEDs it has and what each can do; the app never assumes a board.

    /** A sysfs LED name: `red:wlan2g`, `tp-link:green:power`, `ath10k-phy0`. No path characters. */
    fun safeLedName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && name.first().isLetterOrDigit() &&
            name.all { it.isLetterOrDigit() || it in ":_.-" }

    /** One tab-separated line per LED: name, max, brightness, trigger list, multi_index, multi_intensity. */
    private const val SYSFS_LEDS =
        "for l in /sys/class/leds/*; do [ -e \"\$l/brightness\" ] || continue; " +
        "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\${l##*/}\" \"\$(cat \"\$l/max_brightness\" 2>/dev/null)\" " +
        "\"\$(cat \"\$l/brightness\" 2>/dev/null)\" \"\$(cat \"\$l/trigger\" 2>/dev/null)\" " +
        "\"\$(cat \"\$l/multi_index\" 2>/dev/null)\" \"\$(cat \"\$l/multi_intensity\" 2>/dev/null)\"; done"

    /**
     * What the device tree wired each LED to do — the only record of a board default once a
     * uci section or the watch has overwritten the live state. A node is named by its `label`,
     * else by colour+function the way leds.sh composes it, else by its node name.
     */
    private const val DT_LEDS =
        "[ -d /proc/device-tree ] && { . /lib/functions/leds.sh 2>/dev/null; " +
        "rd() { [ -f \"\$1\" ] && tr -d '\\0' < \"\$1\"; }; " +
        "for d in /proc/device-tree/*leds*/*/; do [ -d \"\$d\" ] || continue; " +
        "n=\$(rd \"\$d/label\"); [ -n \"\$n\" ] || n=\$(get_dt_led_color_func \"\$d\" 2>/dev/null); " +
        "[ -n \"\$n\" ] || n=\$(basename \"\$d\"); " +
        "printf '%s\\t%s\\t%s\\n' \"\$n\" \"\$(rd \"\$d/linux,default-trigger\")\" \"\$(rd \"\$d/default-state\")\"; done; }; true"

    const val LEDWATCH_PATH = "/usr/bin/wrtpulse-ledwatch"
    const val LEDWATCH_INIT = "/etc/init.d/wrtpulse-ledwatch"
    const val LEDWATCH_STATE_FILE = "/var/run/wrtpulse-ledwatch.state"
    const val LEDWATCH_SERVICE = "wrtpulse-ledwatch"

    /**
     * Whether the watch is installed, enabled, running, what it last showed, and its header —
     * the header is the configuration, so the app reads its own writing back rather than
     * remembering it. Running is asked of procd, not pgrep: this very command line carries
     * the script's name and would match itself.
     */
    val LEDWATCH_STATE: String =
        "[ -x $LEDWATCH_PATH ] && echo installed; " +
        "[ -x $LEDWATCH_INIT ] && $LEDWATCH_INIT enabled 2>/dev/null && echo enabled; " +
        "ubus call service list '{\"name\":\"$LEDWATCH_SERVICE\"}' 2>/dev/null | grep -q '\"running\": *true' && echo running; " +
        "[ -f $LEDWATCH_STATE_FILE ] && echo \"current \$(cat $LEDWATCH_STATE_FILE)\"; " +
        "[ -f /etc/init.d/ledwatch ] && echo legacy; " +
        "sed -n '2,12p' $LEDWATCH_PATH 2>/dev/null | grep '^# '; true"

    /** Everything the LED screen needs, in one round trip. */
    val LEDS: String = listOf(
        "echo $SECTION leds" to SYSFS_LEDS,
        "echo $SECTION dt" to DT_LEDS,
        "echo $SECTION uci" to "uci -q show system",
        "echo $SECTION netdevs" to "ls /sys/class/net 2>/dev/null",
        "echo $SECTION watch" to LEDWATCH_STATE,
    ).joinToString("; ") { (marker, cmd) -> "$marker; $cmd" }

    /**
     * Applies LED sections, then any direct sysfs writes. `led reload` is a restart: it puts
     * every LED it had configured back to the state it saved first, then applies the config
     * again — which is exactly what makes deleting a section restore the board default.
     */
    fun ledApply(uci: List<String>, direct: List<String>): String {
        val tail = (direct + "echo applied").joinToString("; ")
        return if (uci.isEmpty()) tail
        else uciBatch(uci, "system", "/etc/init.d/led reload >/dev/null 2>&1; $tail")
    }

    /**
     * Installs (or rewrites) the watch and starts it. The uci sections of the LEDs it drives
     * are dropped first, or `led reload` would keep fighting it; the hand-installed `ledwatch`
     * this replaces is retired when present, along with its lines in sysupgrade.conf. Both new
     * files are added there so an upgrade keeps them. The two heredocs end differently on
     * purpose — see [setupApply].
     */
    fun ledwatchInstall(script: String, init: String, uciDeletes: List<String>): String = buildString {
        append("cat > $LEDWATCH_PATH <<'WRTPULSE_EOF'\n").append(script).append("\nWRTPULSE_EOF\n")
        append("cat > $LEDWATCH_INIT <<'WRTPULSE_INIT_EOF'\n").append(init).append("\nWRTPULSE_INIT_EOF\n")
        append("chmod 755 $LEDWATCH_PATH $LEDWATCH_INIT; ")
        append("if [ -f /etc/init.d/ledwatch ]; then /etc/init.d/ledwatch stop >/dev/null 2>&1; ")
        append("/etc/init.d/ledwatch disable >/dev/null 2>&1; rm -f /etc/init.d/ledwatch /usr/bin/ledwatch.sh; ")
        append("sed -i '/^\\/usr\\/bin\\/ledwatch\\.sh$/d;/^\\/etc\\/init\\.d\\/ledwatch$/d' $SYSUPGRADE_CONF 2>/dev/null; fi; ")
        append("for p in $LEDWATCH_PATH $LEDWATCH_INIT; do grep -qx \"\$p\" $SYSUPGRADE_CONF 2>/dev/null || echo \"\$p\" >> $SYSUPGRADE_CONF; done; ")
        if (uciDeletes.isNotEmpty()) {
            append("uci batch <<'WRTPULSE_UCI_EOF'\n")
            uciDeletes.forEach { append(it).append('\n') }
            append("WRTPULSE_UCI_EOF\nuci commit system; ")
        }
        append("$LEDWATCH_INIT enable >/dev/null 2>&1; $LEDWATCH_INIT restart >/dev/null 2>&1; sleep 1; echo installed")
    }

    /** Stops and removes the watch, then hands its LEDs back with the given direct writes. */
    fun ledwatchRemove(restore: List<String>): String =
        "$LEDWATCH_INIT stop >/dev/null 2>&1; $LEDWATCH_INIT disable >/dev/null 2>&1; " +
        "rm -f $LEDWATCH_PATH $LEDWATCH_INIT $LEDWATCH_STATE_FILE; " +
        "sed -i '/wrtpulse-ledwatch/d' $SYSUPGRADE_CONF 2>/dev/null; " +
        (restore + "/etc/init.d/led restart >/dev/null 2>&1" + "echo removed").joinToString("; ")

}
