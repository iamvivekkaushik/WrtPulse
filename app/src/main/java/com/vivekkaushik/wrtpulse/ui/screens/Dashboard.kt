package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.Ssid
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.Sparkline
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.ThroughputChart
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

private val cpuSpark = listOf(13f, 11f, 14f, 9f, 12f, 7f, 11f, 5f, 10f, 8f, 12f, 9f)
private val ramSpark = listOf(10f, 9f, 10f, 8f, 9f, 8f, 7f, 8f, 7f, 8f, 7f, 7f)

@Composable
fun DashboardScreen(
    ticker: LiveTicker,
    live: Telemetry?,
    inventory: Inventory?,
    routerName: String,
    onRouterTap: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = live?.latencyMs ?: ticker.latencyMs,
            onRouterTap = onRouterTap,
            trailing = { Icon(WrtIcons.MoreVert, "menu", Modifier.size(18.dp), tint = Wrt.TextTertiary) },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), "UPTIME", live?.uptimeLabel ?: "18d 04:12")
                StatCard(
                    Modifier.weight(1f),
                    "LOAD 1M",
                    String.format("%.2f", live?.load1 ?: ticker.load),
                    suffix = live?.let { String.format("%.2f %.2f", it.load5, it.load15) } ?: "0.38 0.31",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GaugeCard(Modifier.weight(1f), "CPU", "${live?.cpuPct ?: ticker.cpuPct}") {
                    Sparkline(
                        live?.cpuSpark ?: cpuSpark,
                        Wrt.Accent,
                        Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp),
                        maxY = if (live != null) 100f else 18f,
                    )
                }
                GaugeCard(Modifier.weight(1f), "RAM", "${live?.ramPct ?: ticker.ramPct}") {
                    Sparkline(
                        live?.ramSpark ?: ramSpark,
                        Wrt.TextTertiary,
                        Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp),
                        maxY = if (live != null) 100f else 18f,
                    )
                }
                GaugeCard(Modifier.weight(1f), "FLASH", "${live?.flashPct ?: 34}") {
                    Box(Modifier.fillMaxWidth().padding(top = 11.dp).height(3.dp).background(Wrt.ProgressTrack, RoundedCornerShape(2.dp))) {
                        Box(
                            Modifier
                                .fillMaxWidth(((live?.flashPct ?: 34) / 100f).coerceIn(0.02f, 1f))
                                .height(3.dp)
                                .background(Wrt.TextTertiary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            WanCard(ticker, live)
            SsidCard(if (inventory != null) inventory.ssids.toList() else null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Modifier.weight(1f), WrtIcons.Reboot, "Reboot")
                QuickAction(Modifier.weight(1f), WrtIcons.GuestWifi, "Guest Wi-Fi")
                QuickAction(Modifier.weight(1f), WrtIcons.Speedtest, "Speedtest")
                QuickAction(Modifier.weight(1f), WrtIcons.Prompt, "Terminal", onClick = onOpenTerminal)
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, suffix: String? = null) {
    Column(
        modifier
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
            .background(Wrt.BgCard, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        SectionLabel(label, size = 9.5f)
        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, style = mono(15f, 600))
            if (suffix != null) Text(suffix, style = mono(10.5f, 500, Wrt.TextDim))
        }
    }
}

@Composable
private fun GaugeCard(modifier: Modifier, label: String, value: String, chart: @Composable () -> Unit) {
    Column(
        modifier
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
            .background(Wrt.BgCard, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        SectionLabel(label, size = 9.5f)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = mono(16f, 600))
            Text("%", style = mono(10.5f, 500, Wrt.TextDim), modifier = Modifier.padding(bottom = 1.dp))
        }
        chart()
    }
}

@Composable
private fun WanCard(ticker: LiveTicker, live: Telemetry?) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(start = 13.dp, end = 13.dp, top = 12.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("WAN · ${(live?.wanDevice ?: "pppoe-wan").uppercase()}", size = 9.5f)
            FlexSpacer()
            val isStale = live?.stale == true
            StatusDot(if (isStale) Wrt.Amber else Wrt.Accent, 5.dp, pulse = !isStale)
            Text(
                if (isStale) "WAITING" else "LIVE",
                style = mono(9f, 600, if (isStale) Wrt.Amber else Wrt.Accent, letterSpacing = 0.14.em),
            )
        }
        Row(
            Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(live?.wanIp ?: (if (live != null) "—" else "82.44.19.7"), style = mono(16f, 600))
            Icon(WrtIcons.Copy, "copy WAN IP", Modifier.size(14.dp), tint = Wrt.TextDim)
            FlexSpacer()
            Text("↓ ${String.format("%.1f", (live?.down ?: ticker.down).last())}", style = mono(12.5f, 600, Wrt.Accent))
            Text("↑ ${String.format("%.1f", (live?.up ?: ticker.up).last())}", style = mono(12.5f, 600, Wrt.Blue))
            Text("Mbps", style = mono(9.5f, 500, Wrt.TextDim))
        }
        val (chartDown, chartUp) =
            if (live != null) Telemetry.normalize(live.down, live.up)
            else ticker.down to ticker.up
        ThroughputChart(
            down = chartDown,
            up = chartUp,
            modifier = Modifier.fillMaxWidth().height(88.dp).padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 6.dp)) {
            Text("60 s window", style = mono(10f, 500, Wrt.TextDim))
            FlexSpacer()
            Text(
                live?.totals ?: (if (live != null) "" else "since 09:12 · ↓ 214 GB · ↑ 38 GB"),
                style = mono(10f, 500, Wrt.TextDim),
            )
        }
    }
}

@Composable
private fun SsidCard(liveSsids: List<Ssid>?) {
    val ssids = liveSsids ?: Demo.ssids
    // Local visual state only — real toggling ships with the uci staging flow.
    val enabled = remember(ssids.map { it.name to it.enabled }) {
        androidx.compose.runtime.mutableStateListOf(*ssids.map { it.enabled }.toTypedArray())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 4.dp)
    ) {
        if (ssids.isEmpty()) {
            Text(
                "Waiting for wireless status…",
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(vertical = 14.dp),
            )
        }
        ssids.forEachIndexed { i, ssid ->
            val on = enabled[i]
            val active = on
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(ssid.name, style = sans(13.5f, 650, if (active) Wrt.TextPrimary else Wrt.TextSecondary))
                    ssid.bands.forEach { b ->
                        MonoTag(b, color = if (active) Wrt.TextTertiary else Wrt.TextDim, border = if (active) Wrt.BorderInput else Wrt.BorderFaint)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${ssid.clients}", style = mono(11.5f, 500, if (active) Wrt.TextTertiary else Wrt.TextDim))
                    Text("cl", style = mono(11.5f, 500, Wrt.TextDim))
                }
                WToggle(on) { enabled[i] = !enabled[i] }
            }
            if (i < ssids.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))
        }
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .height(56.dp)
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(11.dp))
            .background(Wrt.BgCard, RoundedCornerShape(11.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = Wrt.TextTertiary)
        Spacer(Modifier.height(5.dp))
        Text(label, style = sans(10.5f, 600, Wrt.TextSecondary))
    }
}
