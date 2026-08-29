package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.Neigh
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

    suspend fun run(tickMs: Long = 5_000L) {
        while (true) {
            try {
                val result = session.exec(Commands.CLIENTS, timeoutMs = 10_000)
                ingest(Parsers.sections(result.stdout), System.currentTimeMillis() / 1000)
                stale = false
            } catch (e: SshException) {
                stale = true
                if (e is SshException.HostKeyChanged) return
            }
            delay(tickMs)
        }
    }

    internal fun ingest(sections: Map<String, String>, nowEpoch: Long) {
        val leases = Parsers.leases(sections["leases"].orEmpty())
        val neigh = Parsers.neighEntries(sections["neigh"].orEmpty())
        val stations = Parsers.stations(sections["assoc"].orEmpty())
        val wifi = Parsers.wirelessStatus(sections["wifi"].orEmpty())

        val merged = merge(leases, neigh, stations, wifi, nowEpoch)
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
        ): List<Client> {
            val leaseByMac = leases.associateBy { it.mac }
            val ifaceInfo = wifi.associateBy { it.ifname }
            val lanNeigh = neigh
                .filter { it.dev.startsWith("br-") && !it.ip.contains(':') && it.state != "FAILED" }
                .associateBy { it.mac }

            fun name(mac: String) =
                leaseByMac[mac]?.hostname ?: "device-${mac.takeLast(5).replace(":", "")}"

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
                    signalDbm = st.signalDbm,
                    leaseLabel = lease(st.mac)?.let { leaseLabel(it.expiry, nowEpoch) },
                    // iwinfo rates are PHY rates; TX is router->client, so it maps to "down".
                    downMbps = st.txMbps.toFloat(),
                    upMbps = st.rxMbps.toFloat(),
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
                        leaseLabel = lease(n.mac)?.let { leaseLabel(it.expiry, nowEpoch) },
                    )
                }
            val onlineMacs = wirelessMacs + wired.map { it.mac }

            val offline = leases
                .filter { it.mac !in onlineMacs && it.expiry > nowEpoch }
                .map { l ->
                    Client(
                        name = l.hostname ?: "device-${l.mac.takeLast(5).replace(":", "")}",
                        ip = l.ip,
                        mac = l.mac,
                        network = "",
                        bars = -1,
                        offline = true,
                    )
                }

            return (wireless.sortedByDescending { it.bars } + wired.sortedBy { it.name } + offline.sortedBy { it.name })
        }
    }
}
