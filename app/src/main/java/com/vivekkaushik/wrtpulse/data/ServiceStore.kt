package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.RouterService
import com.vivekkaushik.wrtpulse.ops.ServiceAction

/**
 * The router's init scripts, read on demand rather than on a tick — a service's state
 * changes when somebody changes it, and reading it walks every file in /etc/init.d.
 *
 * Every action here runs against the machine the app is currently talking through, which
 * makes some of them self-defeating in a way an install never is: the exec that stops
 * dropbear cannot report back. [actionBlock] is where that is kept out of a list row.
 */
class ServiceStore(private val session: RouterSession) {

    val services = mutableStateListOf<RouterService>()

    var loaded by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    val runningCount: Int get() = services.count { it.running }

    /** Only procd services count: a boot script that has exited is not a stopped service. */
    val stoppedCount: Int get() = services.count { !it.running && it.procd }

    val enabledCount: Int get() = services.count { it.enabled }

    /**
     * Enabled at boot but not running — the only state in this list that is actually wrong,
     * as opposed to simply switched off.
     */
    val failedCount: Int get() = services.count { !it.running && it.procd && it.enabled }

    suspend fun load() {
        loading = true
        try {
            val out = session.exec(Commands.SERVICES, timeoutMs = 45_000)
            val sections = Parsers.sections(out.stdout)
            val list = Parsers.services(sections["scripts"].orEmpty(), sections["running"].orEmpty())
            services.clear(); services.addAll(list)
            error = if (list.isEmpty()) "No init scripts found under /etc/init.d." else null
            loaded = true
        } catch (e: SshException) {
            error = "Couldn't read services: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** The head of the init script, the processes carrying its name, and its rc.d links. */
    suspend fun info(name: String): String = try {
        if (!Commands.safeServiceName(name)) "Unsupported service name."
        else {
            val out = session.exec(Commands.serviceInfo(name), timeoutMs = 30_000)
            val sections = Parsers.sections(out.stdout)
            listOfNotNull(
                sections["head"]?.trim()?.ifEmpty { null },
                sections["procs"]?.trim()?.ifEmpty { null }?.let { "\n── processes ──\n$it" },
                sections["boot"]?.trim()?.ifEmpty { null }?.let { "\n── /etc/rc.d ──\n$it" },
            ).joinToString("\n").ifEmpty { "/etc/init.d/$name gave nothing back." }
        }
    } catch (e: SshException) {
        "Failed: ${e.message}"
    }

    /**
     * Runs one init-script verb, then reloads so the screen shows the router's answer rather
     * than the app's hope — procd starts are asynchronous and a start that fails its first
     * respawn looks identical to one that worked until the list is read again.
     */
    suspend fun act(name: String, action: ServiceAction): String {
        if (!Commands.safeServiceName(name)) return "Failed: unsupported service name."
        actionBlock(name, action)?.let { return "Failed: $it" }
        busy = true
        return try {
            val out = session.exec(Commands.serviceAction(name, action), timeoutMs = 90_000)
            load()
            when {
                !out.ok -> {
                    val why = (out.stderr.trim().ifEmpty { out.stdout.trim() })
                        .lines().lastOrNull { it.isNotBlank() } ?: "exit ${out.exitCode}"
                    "Failed: $why"
                }
                // Device-verified with cron on a router with no crontabs: the init script
                // exits 0 and the daemon never comes up. Reporting the exit code alone would
                // have called that a success while the row below it still read "stopped".
                else -> refusedSilently(name, action) ?: "${action.label} $name"
            }
        } catch (e: SshException) {
            "Failed: ${e.message}"
        } finally {
            busy = false
        }
    }

    /**
     * An init script that exits 0 without reaching the state that was asked for. OpenWrt
     * scripts do this routinely — a daemon with nothing to do starts, finds nothing, and
     * leaves — and the exit code alone would report it as done.
     *
     * Read after [load], so it compares against what the router says now rather than what
     * the command claimed. An unknown service is not second-guessed.
     */
    private fun refusedSilently(name: String, action: ServiceAction): String? {
        val service = services.firstOrNull { it.name == name } ?: return null
        return when (action) {
            ServiceAction.Start, ServiceAction.Restart, ServiceAction.Reload ->
                if (service.running) null
                else "Failed: $name ran without error but still isn't running."
            ServiceAction.Stop ->
                if (!service.running) null else "Failed: $name is still running."
            ServiceAction.Enable ->
                if (service.enabled) null
                else "Failed: $name still isn't set to start at boot."
            ServiceAction.Disable ->
                if (!service.enabled) null
                else "Failed: $name is still set to start at boot."
        }
    }

    companion object {

        /**
         * Actions the app will not drive from a list row, and why.
         *
         * These three are the ground the app is standing on. The exec that stops dropbear
         * never returns — the command kills the channel carrying it, and the app cannot even
         * report what happened. Restarting `network` takes the TCP session with it. A
         * disabled dropbear survives until the next reboot and then locks the router. None
         * of this is the package manager refusing; the tools would all do it happily.
         *
         * A user who genuinely means one of these has a terminal two tabs away — the point
         * is that it can't happen from a row in a list.
         */
        fun actionBlock(name: String, action: ServiceAction): String? = when {
            name == "dropbear" && action in SESSION_ENDING ->
                "dropbear is the SSH server this app is connected through. " +
                    "${action.label} would cut the connection carrying the command — and a " +
                    "disabled dropbear stays gone after the next reboot."
            name == "network" && action in SESSION_ENDING ->
                "Restarting network takes the router's addresses down and this session with " +
                    "them. Reload applies configuration changes without dropping the link."
            name == "firewall" && (action == ServiceAction.Stop || action == ServiceAction.Disable) ->
                "A stopped firewall leaves the router's own services reachable from the WAN side."
            else -> null
        }

        private val SESSION_ENDING = setOf(ServiceAction.Stop, ServiceAction.Restart, ServiceAction.Disable)

        /** Allowed, but the user should read a sentence first. */
        fun actionWarning(name: String, action: ServiceAction): String? {
            if (action == ServiceAction.Start || action == ServiceAction.Enable) return null
            val what = when {
                name.startsWith("wpad") || name.startsWith("hostapd") ->
                    "Wi-Fi drops while this restarts. If you reach this router over Wi-Fi, you " +
                        "lose it — and if the service doesn't come back, you lose it for good."
                name == "dnsmasq" || name == "odhcpd" ->
                    "LAN clients lose DNS and DHCP until it is back. Existing leases keep " +
                        "working; a device asking for a new one won't get an address."
                name == "uhttpd" -> "The LuCI web interface goes away until it is back."
                name == "rpcd" -> "LuCI logins and ubus RPC stop working until it is back."
                name == "log" -> "The system log buffer is lost, and the Live logs screen goes quiet."
                name == "firewall" -> "The ruleset is flushed and rebuilt — traffic is unfiltered " +
                    "for the moment that takes."
                else -> null
            }
            val boot = if (action == ServiceAction.Disable) {
                "It also stays stopped after a reboot until it is enabled again."
            } else null
            return listOfNotNull(what, boot).joinToString(" ").ifEmpty { null }
        }

        /**
         * Which verbs to offer for a service. A boot script has already run and exited, so
         * stop and reload have nothing to act on; only a procd daemon gets the full set.
         */
        fun actionsFor(service: RouterService): List<ServiceAction> = buildList {
            if (service.running) {
                add(ServiceAction.Restart)
                add(ServiceAction.Reload)
                add(ServiceAction.Stop)
            } else {
                add(ServiceAction.Start)
                if (!service.oneShot) add(ServiceAction.Restart)
            }
            add(if (service.enabled) ServiceAction.Disable else ServiceAction.Enable)
        }
    }
}
