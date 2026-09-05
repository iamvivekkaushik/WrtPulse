# WrtPulse

An Android app for managing OpenWrt routers over SSH.

No agent is installed on the router, no cloud service sits in the middle, and nothing is
scraped out of LuCI. The app opens an SSH session to the router and runs the same commands
you would type yourself — `ubus`, `uci`, `iwinfo`, `logread`, `apk`/`opkg`, `sysupgrade` —
then parses the output. If the router can do it from a shell, the app can do it; if it
cannot, the app does not pretend otherwise.

## What it does

| | |
|---|---|
| **Dashboard** | CPU, RAM, flash, load, uptime and live throughput, one batched command per second. The upstream card follows whichever interface actually holds the default route, so it is right whether the WAN is a cable or a Wi-Fi client. Reboot and a speed test live here. |
| **Clients** | Every device on the router, wireless and wired, with signal, lease, and per-client usage when `nlbwmon` is installed. Rename, block, wake-on-LAN, and DHCP reservations. |
| **Network** | **LAN & local network** — the subnet, the DHCP server on it, static leases, and the switch VLANs behind it. **Internet & WAN gateways** — the uplinks, their port and VLAN tag, the IPv4 protocol, IPv6 and prefix delegation, and a connection test. Radios and wireless interfaces. Add or edit APs and station (client) links, change channel, width, encryption and SSID. Every change is staged, shown as a diff, and applied in one `uci batch`. Neighbour scans suggest the least busy channel. |
| **Terminal** | A real SSH shell with a VT screen model — cursor addressing, scrollback, selection and paste — and multiple tabs on one connection. |
| **System** | Live logs (`logread -f`), packages, services, firmware, backup & restore, regulatory domain, and SSH keys. |

### LAN & local network, in more detail

- **Subnet** — the router's own address and netmask, with the live address, broadcast and
  bridge MAC beside them. Configs that spell the address as `ipaddr '192.168.1.1/24'` are read
  and written back the same way, because those carry no `netmask` option to write to.
- **DHCP server** — on or off, the pool drawn across the subnet as a band, start offset, max
  leases, lease time, and the raw `dhcp_option` list.
- **Leases** — every device the router can see, whether it took a lease or only turned up in
  the neighbour table, and the static leases as their own list. Reserve the address a device
  already holds from its row, or add one by hand; edit and delete by swipe.
- **VLANs** — the port matrix, on both switch models. DSA boards get their `bridge-vlan`
  sections with the sockets by name; swconfig boards get the chip's own port numbers, its
  per-port link state (the only place that exists, since those sockets are not netdevs) and
  which port is the CPU, read from `swconfig dev X help`. Tapping a port cycles it untagged →
  tagged → off. The refusals are the ones that would take the board off the network with
  nothing left to fix it from: a VLAN with no CPU port, an untagged CPU port across two VLANs,
  a socket untagged in two VLANs, and anything that strands the VLAN the LAN itself rides.

Everything is staged and applied in one pass across both `network` and `dhcp`, because a
subnet that moves without its DHCP pool leaves every client asking the wrong router for an
address. The one change that ends the session issuing it — moving the router's own address —
is allowed, and says so before the fact: the connection dies with the reload, the phone keeps
a lease on the old subnet until it renews, the saved router entry follows the router to its
new address, and that address is a first contact for host keys.

### Internet & WAN gateways, in more detail

- **The uplinks** — every interface the firewall treats as a WAN, plus anything actually
  carrying a default route, so a router uplinked over Wi-Fi is listed without being called
  "wan". Lowest route metric first, which is how the kernel decides. Public address, delegated
  IPv6 prefix, live throughput and uptime for whichever one is selected.
- **Connection test** — pings the gateway, two public resolvers and a name, because "the line
  is down" and "DNS is broken" look identical from a browser and different from here.
- **Port, VLAN, MAC, MTU** — the socket the ISP is plugged into, an 802.1q tag written as a
  real `config device` the interface then names, a cloned MAC, and the MTU. An uplink that is
  a radio says so instead of offering ports it does not have.
- **IPv4 protocol** — DHCP, static and PPPoE in full. Every other protocol is listed with
  whether this router can actually run it, read from `/lib/netifd/proto`, and named with the
  package that would provide it — but not edited, because an interface set to a protocol whose
  settings are blank never comes up.
- **IPv6** — off, native DHCPv6, dual-stack over an existing PPPoE session, or relay, plus the
  prefix length asked of the ISP and how the LAN addresses itself. Relay writes both halves:
  the LAN relays, and the upstream is marked master so odhcpd knows what it is relaying from.
- **Failover order** — every uplink's route metric on one card. Lower wins the default route;
  the rest carry traffic only once the winner's route is gone. That is the whole of failover on
  a router without mwan3, and the review sheet says so rather than implying a health check.

Applying arms a rollback first. The router copies `/etc/config/network`, starts a detached
watcher, and puts the copy back unless the app reaches it again within 30 seconds — and what
counts as reaching it is the app having re-read the config, not `ifup` returning 0, because
ifup answers long before a PPPoE session is up. Adding a second uplink is not here yet:
failover between two WANs is route metric, which the screen shows, or mwan3, which the app
does not configure.

### System, in more detail

- **Packages** — the installed list, available updates, and feed search, on both `apk` (24.10+)
  and `opkg`. Installs and removals run the package manager's own dry run first and show what
  it said. A short list of packages the router needs in order to boot, or to stay reachable,
  has no Remove button at all.
- **Services** — every init script, with whether it is running, whether it starts at boot, and
  its PID. Start, stop, restart, reload, enable, disable — each showing the exact command
  before it runs.
- **Firmware** — attended sysupgrade through `owut`, five gates on one screen, each carrying
  its own state: back up the config to the phone; ask the upgrade server, whose answer is shown
  as it came (server, target, version, its own verdict quoted) along with the packages a plain
  flash would lose, by name; build and download — streamed, so there is a real progress bar —
  or fetch a URL, or push a `.bin` from the phone; let `sysupgrade -T` check it. Gate five stays
  locked until the four above it are green, then opens a red-zone screen of its own where the
  consequences get the whole display and a three-second hold is the only way past them. After
  the flash a watch screen retries the connection every ten seconds, logs each attempt, re-reads
  the version and says outright in red if it did not change, and offers to put the named
  packages back.
- **Backup & restore** — `sysupgrade -b` pulled onto the phone, kept in app-private storage,
  shared or saved from there; every archive on the phone is listed with the hostname inside it,
  and ones from another router are marked. The paths a backup carries beyond `/etc/config` are
  editable in place (`/etc/sysupgrade.conf`), and a switch takes a snapshot before every Apply
  on the Network screens, keeping the last five. Restore goes the other way through three
  gates drawn as a checklist: the phone reads the archive (a real tar reader — it refuses
  anything that is not an OpenWrt config or that climbs out of `/`), the router hashes what
  arrived, and the router's own `tar -t` lists it. The current config is saved to the phone
  first, and only then is `sysupgrade -r` offered, behind a three-second hold, with the reboot
  after it. Archives from another router are flagged by hostname and LAN address before the hold.
- **Country** — the Wi-Fi regulatory domain, set across every radio at once.
- **SSH keys** — the router's `authorized_keys` with SHA256 fingerprints, marking the entry the
  app itself is signed in with.

## How it decides what to do

Four rules run through the codebase, and most of the odd-looking decisions follow from them.

**Show the command.** Every write names the shell it is about to run, before it runs. This is
why [`ops/Commands.kt`](app/src/main/java/com/vivekkaushik/wrtpulse/ops/Commands.kt) holds every
command in the app rather than scattering strings through the UI.

**Dry run first.** Where the router offers one — `apk add --simulate`, `opkg --noaction`,
`sysupgrade -T`, a `uci` diff — the app runs it and shows the answer instead of inventing its
own prediction.

**Refuse from a list row what cannot be undone.** Nothing on the router knows that one line in
`authorized_keys` is how your phone gets in, or that stopping `dropbear` kills the command
doing the stopping. The app knows, so it does not offer those from a row. A user who means it
has a terminal two tabs away.

**Trust the router over the app.** An exit code of 0 is not proof the thing happened —
`/etc/init.d/cron start` returns 0 on a router with no crontabs and starts nothing. State is
re-read after every write, and the screen reports what the router says, not what the command
claimed.

## Architecture

```
net/    SSH transport — connection, host keys, key generation, Keystore sealing
ops/    Commands the router runs, and the parsers for what comes back. No Android types.
data/   One store per screen: state, the writes it allows, and the guards on them
ui/     Compose screens and the shared component set
db/     Room — saved routers and local client names
```

`ops/` is deliberately free of Android dependencies, which is what makes the parsers testable
on the JVM. The stores in `data/` hold Compose state and own every command their screen can
issue, including the refusals — `ServiceStore.actionBlock`, `PackageStore.removalBlock`,
`FirmwareStore.flashBlock`, `SshKeyStore.removalBlock` are all the same idea.

## Security

- Passwords and private keys are sealed with an Android Keystore key and stored in Room, never
  in plaintext or in shared preferences.
- The first unseal of each launch is gated behind the device screen lock. If the device has no
  lock configured there is nothing to gate with, and the app says so rather than pretending.
- Host keys are trust-on-first-use: the app reads the key and hangs up **before** authenticating,
  so nothing secret is sent to a router it does not recognise. A changed key stops the
  connection and shows both fingerprints.
- The app can generate its own ed25519 key, install it, and prove it works with a fresh
  key-authenticated connection before the password is discarded.
- Config backups pulled off the router contain its private host keys. They are written to
  app-private storage and only leave through a share sheet you tap.

## Building

Needs a JDK 17+ — the one bundled with Android Studio works:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

770 JVM tests, mostly over `ops/` — real command output captured from a router, parsed and
pinned. Where an outside authority exists it is used: key fingerprints are checked against
`ssh-keygen -lf` rather than against the app's own maths.

Kotlin 2.2.10 · AGP 9.4.0 · Compose BOM 2026.02.01 · Room 2.8.4 · minSdk 28 · targetSdk 36.
SSH is JSch with BouncyCastle, which Android needs for ed25519.

## Releasing

Builds, tests and releases go through fastlane, and GitHub Actions calls the same lanes, so
what CI runs is exactly what runs on a workstation. Needs Ruby 3.1+ (macOS's system Ruby is
too old — `brew install ruby` or rbenv), then:

```bash
bundle install
bundle exec fastlane android check      # unit tests + lint — what CI gates on
bundle exec fastlane android debug      # debug APK
bundle exec fastlane android release    # signed AAB + APK
```

`release` signs with the upload keystore, read from the environment or from an untracked
`keystore.properties` in the repo root:

```properties
KEYSTORE_FILE=/absolute/path/to/release.jks
KEYSTORE_PASSWORD=…
KEY_ALIAS=…
KEY_PASSWORD=…
```

With none of these set the release build is unsigned and the lane refuses — it never quietly
produces something that cannot ship. `VERSION_NAME` and `VERSION_CODE` in the environment
override the Gradle defaults.

**CI.** `.github/workflows/ci.yml` runs `check` and uploads a debug APK on every push and pull
request. `.github/workflows/release.yml` fires on a `v*` tag: it restores the keystore from
`KEYSTORE_BASE64` (`base64 -i release.jks`), stamps `versionName` from the tag and
`versionCode` from the run number, builds the signed AAB and APK, and attaches both to a GitHub
Release. If a `PLAY_JSON_KEY_BASE64` secret (the Play Console service-account JSON, base64) is present it also
uploads the AAB to the internal track; without it that step is skipped, so the pipeline is
useful before Play is set up. Secrets to add under Settings › Secrets and variables › Actions:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`, and optionally `PLAY_JSON_KEY_BASE64`.

## Not done yet

Scheduled tasks is a placeholder on the System screen. Factory reset is drawn but inert. On
the LAN screen the reads and the subnet move are verified against a real router — a router was
moved from 192.168.1.1 to 192.168.0.1 through it, taking the session with it as designed, and
the saved entry followed. The VLAN port matrix is built and gated but has not been applied to
live hardware, and swconfig boards are read-only by design. On the WAN screens the reads and
the connection test are verified against a real router; the editors and the rollback apply are
unit-tested but have not been run against a live uplink. The
restore has been built and gated but not yet run end to end on a live router — everything up
to and including the router's `tar -tzf` listing has. The firmware flash itself, and the reboot
watch after it, have been built and gated but not yet run end to end on a live router —
everything up to and including `sysupgrade -T` has.

There is no license file yet, so default copyright applies.
