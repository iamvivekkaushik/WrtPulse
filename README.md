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
| **Network** | Radios and wireless interfaces. Add or edit APs and station (client) links, change channel, width, encryption and SSID. Every change is staged, shown as a diff, and applied in one `uci batch`. Neighbour scans suggest the least busy channel. |
| **Terminal** | A real SSH shell with a VT screen model — cursor addressing, scrollback, selection and paste — and multiple tabs on one connection. |
| **System** | Live logs (`logread -f`), packages, services, firmware, backup & restore, regulatory domain, and SSH keys. |

### System, in more detail

- **Packages** — the installed list, available updates, and feed search, on both `apk` (24.10+)
  and `opkg`. Installs and removals run the package manager's own dry run first and show what
  it said. A short list of packages the router needs in order to boot, or to stay reachable,
  has no Remove button at all.
- **Services** — every init script, with whether it is running, whether it starts at boot, and
  its PID. Start, stop, restart, reload, enable, disable — each showing the exact command
  before it runs.
- **Firmware** — attended sysupgrade through `owut`, gated: back up the config to the phone,
  ask the upgrade server, build and download the image, let `sysupgrade -T` check it, and only
  then offer the flash behind a three-second hold.
- **Backup & restore** — `sysupgrade -b` pulled onto the phone, kept in app-private storage,
  shared or saved from there. Restore goes the other way through three gates: the phone reads
  the archive (a real tar reader — it refuses anything that is not an OpenWrt config or that
  climbs out of `/`), the router hashes what arrived, and the router's own `tar -t` lists it.
  Only then is `sysupgrade -r` offered, behind a three-second hold, with the reboot after it.
  Archives from another router are flagged by hostname and LAN address before the hold.
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

324 JVM tests, mostly over `ops/` — real command output captured from a router, parsed and
pinned. Where an outside authority exists it is used: key fingerprints are checked against
`ssh-keygen -lf` rather than against the app's own maths.

Kotlin 2.2.10 · AGP 9.2.1 · Compose BOM 2026.02.01 · Room 2.8.4 · minSdk 28 · targetSdk 36.
SSH is JSch with BouncyCastle, which Android needs for ed25519.

## Not done yet

Scheduled tasks is a placeholder on the System screen. Factory reset is drawn but inert. The
restore has been built and gated but not yet run end to end on a live router — everything up
to and including the router's `tar -tzf` listing has. The firmware flash itself has been built and gated but not yet run end to end
on a live router — everything up to and including `sysupgrade -T` has.

There is no license file yet, so default copyright applies.
