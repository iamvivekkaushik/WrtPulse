package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.InstallPlan
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.RemovePlan
import com.vivekkaushik.wrtpulse.ops.RouterPackage

/**
 * The router's package manager, read on demand rather than on a tick — the installed list
 * changes only when somebody changes it, and reading it costs a full `apk info` sweep.
 *
 * Nothing here writes to the router without an explicit tap: install, remove and upgrade all
 * run a read-only simulation first so the consent dialog can state what would happen.
 */
class PackageStore(private val session: RouterSession) {

    /** "apk" on 24.10 and later, "opkg" before it. */
    var manager by mutableStateOf("opkg"); private set

    val installed = mutableStateListOf<RouterPackage>()
    val upgrades = mutableStateListOf<RouterPackage>()
    val results = mutableStateListOf<RouterPackage>()

    var availKb by mutableStateOf<Long?>(null); private set
    var feedAgeSeconds by mutableStateOf<Long?>(null); private set

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var searching by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /** Set after a search so the empty state can tell "no matches" from "not searched yet". */
    var searchedTerm by mutableStateOf<String?>(null); private set

    private val sizes: Map<String, Long?> get() = installed.associate { it.name to it.sizeBytes }

    val installedNames: Set<String> get() = installed.mapTo(mutableSetOf()) { it.name }

    suspend fun load() {
        loading = true
        try {
            val out = session.exec(Commands.PACKAGES, timeoutMs = 60_000)
            val sections = Parsers.sections(out.stdout)
            manager = sections["pm"].orEmpty().trim().ifEmpty { "opkg" }
            val list = Parsers.installedPackages(sections["installed"].orEmpty(), manager)
            val pending = Parsers.upgradablePackages(sections["upgradable"].orEmpty(), manager)
            installed.clear(); installed.addAll(list)
            upgrades.clear(); upgrades.addAll(pending)
            availKb = Parsers.overlayAvailKb(sections["df"].orEmpty())
            feedAgeSeconds = Parsers.feedAgeSeconds(sections["feed"].orEmpty())
            error = if (list.isEmpty()) "$manager returned no installed packages." else null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read packages: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** Searches the feed. Returns the error to show, or null when the results list is current. */
    suspend fun search(term: String) {
        val query = term.trim()
        if (query.length < 2) {
            results.clear()
            searchedTerm = null
            return
        }
        if (!Commands.safePackageName(query)) {
            error = "Search terms are letters, digits and . _ + -"
            return
        }
        searching = true
        try {
            val out = session.exec(Commands.searchPackages(query), timeoutMs = 60_000)
            val found = Parsers.packageSearchResults(out.stdout, manager)
            val here = installedNames
            results.clear()
            results.addAll(found.map { if (it.name in here) it.copy(installed = true) else it })
            searchedTerm = query
            error = null
        } catch (e: SshException) {
            error = "Search failed: ${e.message}"
        } finally {
            searching = false
        }
    }

    /** Whatever the manager can say about one package, shown raw in the detail sheet. */
    suspend fun info(name: String): String = try {
        if (!Commands.safePackageName(name)) "Unsupported package name."
        else session.exec(Commands.packageInfo(name), timeoutMs = 30_000).stdout.trim()
            .ifEmpty { "$manager has nothing on file for $name." }
    } catch (e: SshException) {
        "Failed: ${e.message}"
    }

    suspend fun planInstall(name: String): InstallPlan = try {
        if (!Commands.safePackageName(name)) {
            InstallPlan(manager, emptyList(), availKb, "Unsupported package name.")
        } else {
            val out = session.exec(Commands.installPlan(name), timeoutMs = 90_000)
            Parsers.installPlan(Parsers.sections(out.stdout), name)
        }
    } catch (e: SshException) {
        InstallPlan(manager, emptyList(), availKb, "Failed: ${e.message}")
    }

    suspend fun planRemove(name: String): RemovePlan = try {
        when {
            !Commands.safePackageName(name) ->
                RemovePlan(manager, emptyList(), availKb, "Unsupported package name.")
            removalBlock(name) != null ->
                RemovePlan(manager, emptyList(), availKb, removalBlock(name))
            else -> {
                val out = session.exec(Commands.removePlan(name), timeoutMs = 60_000)
                val plan = Parsers.removePlan(Parsers.sections(out.stdout), sizes)
                // Removing a package also removes whatever it orphaned, so the guard has to
                // read the whole plan and not just the row that was tapped.
                val swept = blockedInPlan(plan.packages.map { it.first })
                if (plan.problem == null && swept != null) plan.copy(problem = swept) else plan
            }
        }
    } catch (e: SshException) {
        RemovePlan(manager, emptyList(), availKb, "Failed: ${e.message}")
    }

    suspend fun install(name: String): String =
        run(Commands.installPackage(name), "Installed $name", name)

    suspend fun remove(name: String): String = when (val block = removalBlock(name)) {
        null -> run(Commands.removePackage(name), "Removed $name", name)
        else -> "Failed: $block"
    }

    suspend fun upgrade(name: String): String =
        run(Commands.upgradePackage(name), "Upgraded $name", name)

    /** Re-downloads the package index, then reloads so the "N updates" count is current. */
    suspend fun refreshFeed(): String = try {
        busy = true
        val out = session.exec(Commands.UPDATE_FEED, timeoutMs = 120_000)
        if (out.ok) {
            load()
            "Package list refreshed"
        } else {
            "Failed: ${out.stderr.trim().ifEmpty { "exit ${out.exitCode}" }}"
        }
    } catch (e: SshException) {
        "Failed: ${e.message}"
    } finally {
        busy = false
    }

    /** Runs one write, then reloads so the screen shows the router's answer, not the app's hope. */
    private suspend fun run(command: String, okMessage: String, name: String): String {
        if (!Commands.safePackageName(name)) return "Failed: unsupported package name."
        busy = true
        return try {
            val out = session.exec(command, timeoutMs = 300_000)
            if (out.ok) {
                load()
                okMessage
            } else {
                val why = (out.stderr.trim().ifEmpty { out.stdout.trim() })
                    .lines().lastOrNull { it.isNotBlank() } ?: "exit ${out.exitCode}"
                "Failed: $why"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    companion object {

        /**
         * Packages this app refuses to remove.
         *
         * Neither apk nor opkg will stop you: nothing depends on dropbear, so the tool
         * removes it happily and the router goes silent in the middle of the command that
         * did it. The rest of the list is what the router needs to boot, get an address, or
         * be configured at all. A user who genuinely means to remove one of these has a
         * terminal two tabs away — the point is that it can't happen from a list row.
         */
        private val CRITICAL = setOf(
            "dropbear", "openssh-server",
            "busybox", "base-files", "libc", "musl", "kernel", "urandom-seed",
            "procd", "procd-seccomp", "ubox", "ubusd", "ubus", "libubus", "libubox",
            "uci", "libuci", "netifd", "mtd", "fstools",
            "firewall4", "fw4", "nftables", "dnsmasq", "dnsmasq-full",
            "odhcpd", "odhcpd-ipv6only",
            "apk", "apk-tools", "opkg", "openwrt-keyring",
        )

        /** Non-null means the Remove button is not offered, and this is the reason why. */
        fun removalBlock(name: String): String? = when {
            name in CRITICAL ->
                "$name is part of how the router boots and how this app reaches it. " +
                    "Removing it from a list row is not something the app will do."
            else -> null
        }

        /**
         * The first reason, if any, that a whole removal plan touches something untouchable.
         * Removing a package takes its orphaned dependencies too, so the plan is what gets
         * checked — not the row the user tapped.
         */
        fun blockedInPlan(names: List<String>): String? = names.firstNotNullOfOrNull { removalBlock(it) }

        /** Removable, but the user should read a sentence first. */
        fun removalWarning(name: String): String? = when {
            name.startsWith("wpad") || name.startsWith("hostapd") ->
                "Wi-Fi stops until a replacement is installed. If you reach this router over " +
                    "Wi-Fi, you lose it."
            name.startsWith("kmod-") ->
                "Kernel modules are built against the running kernel — reinstalling one needs a " +
                    "feed that still matches this exact build."
            name.startsWith("luci") ->
                "The LuCI web interface loses this component."
            else -> null
        }

        /**
         * Upgrading packages in place is not OpenWrt's supported path — the feed tracks the
         * next release, so a package can pull in libraries the running firmware doesn't have,
         * and flash fills up fast. Worth saying once, next to the button.
         */
        const val UPGRADE_CAUTION =
            "OpenWrt's supported upgrade path is a firmware image, not the package feed. " +
                "Upgrading one package can pull in libraries built for a newer release."

        fun feedAgeLabel(seconds: Long?): String? = when {
            seconds == null -> null
            seconds < 120 -> "list just refreshed"
            seconds < 7_200 -> "list ${seconds / 60} min old"
            seconds < 172_800 -> "list ${seconds / 3600} h old"
            else -> "list ${seconds / 86_400} d old"
        }
    }
}
