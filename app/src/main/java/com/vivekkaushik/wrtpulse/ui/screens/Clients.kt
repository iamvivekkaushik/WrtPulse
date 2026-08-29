package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollState as rememberHScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.data.Client
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.SignalBars
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

@Composable
fun ClientsScreen(ticker: LiveTicker, live: Inventory?, routerName: String, onRouterTap: () -> Unit) {
    var expandedMac by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    val all = if (live != null) live.clients.toList() else Demo.clients

    val wireless = all.filter { it.bars > 0 }
    val wired = all.filter { it.bars == -1 && !it.offline && !it.blocked }
    val blocked = all.filter { it.blocked }
    val offline = all.filter { it.offline }
    val shown = when (filter) {
        1 -> wireless
        2 -> wired
        3 -> blocked
        4 -> offline
        else -> all
    }
    val defaultExpand = shown.firstOrNull { !it.blocked && !it.offline }?.mac

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = ticker.latencyMs,
            onRouterTap = onRouterTap,
            trailing = { Icon(WrtIcons.MoreVert, "menu", Modifier.size(18.dp), tint = Wrt.TextTertiary) },
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("Clients", style = sans(16f, 650))
            Text("${all.size}", style = mono(11.5f, 500, Wrt.Accent))
            FlexSpacer()
            Icon(WrtIcons.Search, "search", Modifier.size(18.dp), tint = Wrt.TextTertiary)
        }
        Row(
            Modifier
                .horizontalScroll(rememberHScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "All ${all.size}",
                "Wireless ${wireless.size}",
                "Wired ${wired.size}",
                "Blocked ${blocked.size}",
                "Offline ${offline.size}",
            ).forEachIndexed { i, label ->
                FilterChip(label, selected = i == filter, size = 11.5f, padH = 11.dp, padV = 5.dp, onClick = { filter = i })
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val expandKey = expandedMac ?: defaultExpand
            shown.forEach { client ->
                if (client.mac == expandKey && !client.blocked && !client.offline) {
                    ExpandedClientCard(client, ticker)
                } else {
                    ClientRow(client, onClick = { if (!client.blocked && !client.offline) expandedMac = client.mac })
                }
            }
            if (live != null && shown.isEmpty()) {
                Text(
                    if (live.stale) "Waiting for the router…" else "Nothing here",
                    style = mono(11f, 500, Wrt.TextDim),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ExpandedClientCard(client: Client, ticker: LiveTicker) {
    Column(
        Modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (client.bars > 0) SignalBars(client.bars, client.barColor)
            else Icon(WrtIcons.WiredDevice, "wired", Modifier.size(15.dp), tint = Wrt.TextTertiary)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(client.name, style = sans(14f, 650))
                    if (client.editable) Icon(WrtIcons.Pencil, "rename", Modifier.size(11.dp), tint = Wrt.TextDim)
                }
                Text(
                    "${client.ip} · ${client.mac}",
                    style = mono(10f, 500, Wrt.TextDim),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (client.network.isNotEmpty()) MonoTag(client.network, size = 9f)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 11.dp)
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Live values are iwinfo PHY link rates, not momentary throughput.
            val down = client.downMbps ?: ticker.clientDown
            val up = client.upMbps ?: ticker.clientUp
            Text("↓ ${String.format("%.1f", down)} Mb/s", style = mono(11f, 600, Wrt.Accent), maxLines = 1)
            Text("↑ ${String.format("%.1f", up)} Mb/s", style = mono(11f, 600, Wrt.Blue), maxLines = 1)
            FlexSpacer()
            Text(client.signalDbm?.let { "$it dBm" } ?: "-52 dBm", style = mono(10f, 500, Wrt.TextTertiary), maxLines = 1)
            Text(client.leaseLabel ?: "lease 23 h", style = mono(10f, 500, Wrt.TextDim), maxLines = 1)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ClientAction(Modifier.weight(1f), "Block", Wrt.Red, Wrt.Red.copy(alpha = 0.4f))
            ClientAction(Modifier.weight(1f), "Static IP", Wrt.TextSecondary, Wrt.BorderInput)
            ClientAction(Modifier.weight(1f), "WoL", Wrt.TextSecondary, Wrt.BorderInput)
            ClientAction(Modifier.weight(1f), "Rename", Wrt.TextSecondary, Wrt.BorderInput)
        }
    }
}

@Composable
private fun ClientAction(modifier: Modifier, label: String, textColor: Color, border: Color) {
    Box(
        modifier
            .height(33.dp)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable { },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = sans(11.5f, 600, textColor))
    }
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp).alpha(if (client.blocked || client.offline) 0.65f else 1f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            when {
                client.blocked -> Icon(WrtIcons.Blocked, "blocked", Modifier.size(15.dp), tint = Wrt.Red)
                client.bars < 0 -> Icon(WrtIcons.WiredDevice, "wired", Modifier.size(15.dp), tint = Wrt.TextTertiary)
                else -> SignalBars(client.bars, client.barColor)
            }
            Column(Modifier.weight(1f)) {
                Text(client.name, style = sans(13.5f, 600, if (client.blocked || client.offline) Wrt.TextSecondary else Wrt.TextPrimary))
                Text(
                    "${client.ip} · ${client.mac}",
                    style = mono(10f, 500, Wrt.TextDim),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            when {
                client.blocked -> MonoTag("BLOCKED", color = Wrt.Red, border = Wrt.Red.copy(alpha = 0.45f), size = 9f)
                client.offline -> MonoTag("OFFLINE", color = Wrt.TextDim, border = Wrt.BorderFaint, size = 9f)
                client.network.isNotEmpty() -> MonoTag(client.network, size = 9f)
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(Wrt.BorderRow))
    }
}
