package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.InstallPlan
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.Neigh
import com.vivekkaushik.wrtpulse.ops.NlbwHost
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.Station
import com.vivekkaushik.wrtpulse.ops.WifiIface
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.delay

/**
 * Live client list and SSID summary, fed by [Commands.CLIENTS] — one batched round trip.
 * Slower cadence than [Telemetry]: association lists don't change second to second.
 */
class Inventory(private val session: RouterSession) {

    val clients = mutableStateListOf<Client>()
    val ssids = mutableStateListOf<Ssid>()
    var stale by mutableStateOf(true); private set

    /** User renames from the local DB; applied during merge. */
    var nameOverrides by mutableStateOf<Map<String, String>>(emptyMap())

    /** null until the first tick answers; false drives the "install nlbwmon" offer. */
    var nlbwPresent by mutableStateOf<Boolean?>(null)
        private set

    private var lastSections: Map<String, String>? = null

    // Sliding window of nlbw counter samples. nlbwmon folds long-lived flows into its
    // database only every refresh_interval (30 s by default), so rates divided over a
    // single 5 s tick read as 0,0,0,0,0,6x. Dividing over the window smooths that out.
    private val nlbwSamples = ArrayDeque<Pair<Long, Map<String, NlbwHost>>>()
    private var lastUsage: Map<String, Pair<Float, Float>> = emptyMap()

    /** Cumulative per-host counters for the current nlbwmon accounting period. */
    var totals: Map<String, NlbwHost> = emptyMap()
        private set

    suspend fun run(tickMs: Long = 5_000L) {
        while (true) {
            tickOnce()
            delay(tickMs)
        }
    }

    suspend fun tickOnce() {
        try {
            val result = session.exec(Commands.CLIENTS, timeoutMs = 10_000)
            ingest(Parsers.sections(result.stdout), System.currentTimeMillis() / 1000)
            stale = false
        } catch (e: SshException) {
            stale = true
            if (e is SshException.HostKeyChanged) throw kotlinx.coroutines.CancellationException("blocked")
        }
    }

    /**
     * Re-applies the last snapshot, e.g. after a rename lands. Never advances the rate
     * baseline — re-reading identical counters at a later time would zero every rate.
     */
    fun remerge() {
        lastSections?.let { ingest(it, System.currentTimeMillis() / 1000, updateRates = false) }
    }

    /** Appends one counter sample and recomputes per-MAC (down, up) Mbps over the window. */
    private fun sampleUsage(hosts: List<NlbwHost>, nowEpoch: Long) {
        val byMac = hosts.associateBy { it.mac }
        // A second sample in the same epoch second (action refresh racing the loop) would
        // make dt = 0; keep the existing baseline instead.
        if (nlbwSamples.isNotEmpty() && nowEpoch <= nlbwSamples.last().first) return
        nlbwSamples.addLast(nowEpoch to byMac)
        while (nlbwSamples.size > MAX_NLBW_SAMPLES) nlbwSamples.removeFirst()
        val (oldEpoch, old) = nlbwSamples.first()
        val dt = (nowEpoch - oldEpoch).toDouble()
        if (dt <= 0) return // single sample so far — no rate yet
        lastUsage = byMac.mapNotNull { (mac, h) ->
            val prev = old[mac] ?: return@mapNotNull null
            val down = Telemetry.mbps(h.downBytes - prev.downBytes, dt)
            val up = Telemetry.mbps(h.upBytes - prev.upBytes, dt)
            mac to (down to up)
        }.toMap()
    }

    /** Fetches what an nlbwmon install would need — sizes and free space for the consent dialog. */
    suspend fun planNlbwInstall(): InstallPlan = try {
        val out = session.exec(Commands.NLBW_PLAN, timeoutMs = 60_000)
        Parsers.installPlan(Parsers.sections(out.stdout))
    } catch (e: SshException) {
        InstallPlan("opkg", emptyList(), null, "Failed: ${e.message}")
    }

    /** Runs the install the user just approved. opkg on slow flash can take a while. */
    suspend fun installNlbw(): String {
        val msg = action(
            Commands.NLBW_INSTALL,
            "Installed — usage appears within a tick or two",
            timeoutMs = 120_000,
        )
        if (!msg.startsWith("Failed")) nlbwPresent = true
        return msg
    }

    /** Runs one action command, then refreshes the list so the UI shows the router's truth. */
    private suspend fun action(command: String, okMessage: String, timeoutMs: Long = 15_000): String = try {
        val result = session.exec(command, timeoutMs = timeoutMs)
        if (result.exitCode == 0) {
            tickOnce()
            okMessage
        } else {
            "Failed: ${result.stderr.trim().ifEmpty { "exit ${result.exitCode}" }}"
        }
    } catch (e: SshException) {
        "Failed: ${e.message}"
    }

    suspend fun setBlocked(mac: String, blocked: Boolean): String = action(
        if (blocked) Commands.blockClient(mac) else Commands.unblockClient(mac),
        if (blocked) "Blocked — firewall reloaded" else "Unblocked — firewall reloaded",
    )

    suspend fun wake(mac: String): String =
        action(Commands.wake(mac), "Magic packet sent")

    suspend fun reserve(mac: String, ip: String, name: String): String =
        action(Commands.reserveIp(mac, ip, name), "Reserved $ip — dnsmasq restarted")

    internal fun ingest(sections: Map<String, String>, nowEpoch: Long, updateRates: Boolean = true) {
        lastSections = sections
        val leases = Parsers.leases(sections["leases"].orEmpty())
        val neigh = Parsers.neighEntries(sections["neigh"].orEmpty())
        val stations = Parsers.stations(sections["assoc"].orEmpty())
        val wifi = Parsers.wirelessStatus(sections["wifi"].orEmpty())
        val blocked = Parsers.blockedMacs(sections["blocked"].orEmpty())

        sections["nlbwbin"]?.let { nlbwPresent = it.isNotBlank() }
        val hosts = Parsers.nlbwHosts(sections["nlbw"].orEmpty())
        // Cumulative counters need no sampling — they are whatever the router reports now.
        if (sections.containsKey("nlbw")) totals = hosts.associateBy { it.mac }
        if (updateRates) sampleUsage(hosts, nowEpoch)
        // null = nlbwmon absent (PHY link rates are the honest fallback); with it installed,
        // a MAC with no data must read "—", never its link rate.
        val usage = if (nlbwPresent == true) lastUsage else null

        val merged = merge(leases, neigh, stations, wifi, nowEpoch, blocked, nameOverrides, usage, totals)
        clients.clear(); clients.addAll(merged)

        val bySsid = wifi.groupBy { it.ssid }
        val stationIfaces = stations.groupBy { it.iface }
        ssids.clear()
        ssids.addAll(
            bySsid.map { (name, ifaces) ->
                Ssid(
                    name = name,
                    bands = ifaces.map { it.band }.distinct().sorted(),
                    clients = ifaces.sumOf { stationIfaces[it.ifname]?.size ?: 0 },
                    enabled = ifaces.any { it.up },
                )
            }.sortedByDescending { it.clients }
        )
    }

    companion object {

        /** 8 samples x 5 s ticks ≈ a 40 s window — longer than nlbwmon's 30 s refresh. */
        const val MAX_NLBW_SAMPLES = 8

        /** iwinfo signal → the 4-bar meter the design uses. */
        fun barsFor(dbm: Int): Int = when {
            dbm >= -55 -> 4
            dbm >= -65 -> 3
            dbm >= -73 -> 2
            else -> 1
        }

        fun leaseLabel(expiry: Long, nowEpoch: Long): String? {
            val left = expiry - nowEpoch
            return when {
                expiry <= 0 || left <= 0 -> null
                left >= 3600 -> "lease ${left / 3600} h"
                else -> "lease ${(left / 60).coerceAtLeast(1)} min"
            }
        }

        /**
         * Wireless = in an assoclist. Wired = LAN-bridge neighbour that isn't wireless.
         * Offline = has a live DHCP lease but no presence on the network right now.
         */
        fun merge(
            leases: List<Lease>,
            neigh: List<Neigh>,
            stations: List<Station>,
            wifi: List<WifiIface>,
            nowEpoch: Long,
            blockedMacs: Set<String> = emptySet(),
            nameOverrides: Map<String, String> = emptyMap(),
            usage: Map<String, Pair<Float, Float>>? = null,
            totals: Map<String, NlbwHost> = emptyMap(),
        ): List<Client> {
            val leaseByMac = leases.associateBy { it.mac }
            val ifaceInfo = wifi.associateBy { it.ifname }
            val lanNeigh = neigh
                .filter { it.dev.startsWith("br-") && !it.ip.contains(':') && it.state != "FAILED" }
                .associateBy { it.mac }

            fun name(mac: String) = nameOverrides[mac]
                ?: leaseByMac[mac]?.hostname
                ?: "device-${mac.takeLast(5).replace(":", "")}"

            fun lease(mac: String) = leaseByMac[mac]

            val wireless = stations.map { st ->
                val info = ifaceInfo[st.iface]
                val bars = barsFor(st.signalDbm)
                Client(
                    name = name(st.mac),
                    ip = lease(st.mac)?.ip ?: lanNeigh[st.mac]?.ip ?: "—",
                    mac = st.mac,
                    network = info?.let { "${it.ssid} · ${it.band}" } ?: st.iface,
                    bars = bars,
                    barColor = if (bars <= 1) Wrt.Amber else Wrt.Green,
                    editable = true,
                    blocked = st.mac in blockedMacs,
                    signalDbm = st.signalDbm,
                    leaseLabel = lease(st.mac)?.let { leaseLabel(it.expiry, nowEpoch) },
                    // Real usage from nlbwmon when installed (missing MAC = no data, shown
                    // as "—"); iwinfo PHY link rates only when nlbwmon is absent
                    // (TX is router->client, so it maps to "down").
                    downMbps = if (usage != null) usage[st.mac]?.first else st.txMbps.toFloat(),
                    upMbps = if (usage != null) usage[st.mac]?.second else st.rxMbps.toFloat(),
                    usageDown = totals[st.mac]?.downBytes,
                    usageUp = totals[st.mac]?.upBytes,
                    apps = totals[st.mac]?.topApps.orEmpty(),
                )
            }
            val wirelessMacs = wireless.map { it.mac }.toSet()

            val wired = lanNeigh.values
                .filter { it.mac !in wirelessMacs }
                .map { n ->
                    Client(
                        name = name(n.mac),
                        ip = n.ip,
                        mac = n.mac,
                        network = "LAN",
                        bars = -1,
                        editable = true,
                        blocked = n.mac in blockedMacs,
                        leaseLabel = lease(n.mac)?.let { leaseLabel(it.expiry, nowEpoch) },
                        downMbps = usage?.get(n.mac)?.first,
                        upMbps = usage?.get(n.mac)?.second,
                        usageDown = totals[n.mac]?.downBytes,
                        usageUp = totals[n.mac]?.upBytes,
                        apps = totals[n.mac]?.topApps.orEmpty(),
                    )
                }
            val onlineMacs = wirelessMacs + wired.map { it.mac }

            val offline = leases
                .filter { it.mac !in onlineMacs && it.expiry > nowEpoch }
                .map { l ->
                    Client(
                        name = nameOverrides[l.mac] ?: l.hostname ?: "device-${l.mac.takeLast(5).replace(":", "")}",
                        ip = l.ip,
                        mac = l.mac,
                        network = "",
                        bars = -1,
                        editable = true,
                        blocked = l.mac in blockedMacs,
                        offline = true,
                        usageDown = totals[l.mac]?.downBytes,
                        usageUp = totals[l.mac]?.upBytes,
                        apps = totals[l.mac]?.topApps.orEmpty(),
                    )
                }

            return (wireless.sortedByDescending { it.bars } + wired.sortedBy { it.name } + offline.sortedBy { it.name })
        }
    }
}
