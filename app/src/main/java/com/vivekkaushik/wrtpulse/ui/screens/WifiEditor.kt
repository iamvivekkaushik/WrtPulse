package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.WifiStore
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.ScanCell
import kotlinx.coroutines.launch
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import com.vivekkaushik.wrtpulse.ops.WifiRadio
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

@Composable
fun WifiEditorScreen(
    ticker: LiveTicker,
    store: WifiStore?,
    liveLatencyMs: Int? = null,
    routerName: String,
    pendingCount: Int,
    onRouterTap: () -> Unit,
    onReviewApply: () -> Unit,
    onRevert: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
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
            if (store != null) LiveRadios(store) else DemoRadios(pendingCount)
        }
        if (pendingCount > 0) {
            Column(Modifier.background(Wrt.BgPendingBar)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.Accent.copy(alpha = 0.35f)))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("±$pendingCount", style = mono(11f, 600, Wrt.Accent))
                    Text("$pendingCount pending changes", style = sans(12.5f, 600))
                    FlexSpacer()
                    Text(
                        "Revert",
                        style = sans(12f, 600, Wrt.TextSecondary),
                        modifier = Modifier.clickable(onClick = onRevert).padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    Box(
                        Modifier
                            .background(Wrt.Accent, RoundedCornerShape(9.dp))
                            .clickable(onClick = onReviewApply)
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text("Review & Apply", style = sans(12.5f, 650, Wrt.OnAccent))
                    }
                }
            }
        }
    }
}

// ---------- live path ----------

@Composable
private fun LiveRadios(store: WifiStore) {
    val radios = store.radios.toList()
    val networks = store.networks.toList()
    var expandedRadio by remember { mutableStateOf<String?>(null) }
    val expandKey = expandedRadio ?: radios.firstOrNull { !it.disabled }?.section ?: radios.firstOrNull()?.section

    if (!store.loaded) {
        Text(
            store.error ?: "Reading wireless config…",
            style = mono(11f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
        )
        return
    }
    radios.forEach { radio ->
        val nets = networks.filter { it.device == radio.section }
        val channel = store.value(radio.section, "channel", radio.channel)
        if (radio.section == expandKey) {
            Row(
                Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel("${radio.section.uppercase()} — ${bandHeader(radio.band)}", tracking = 0.14)
                FlexSpacer()
                Text(radio.htmode.ifEmpty { "—" }, style = mono(10f, 500, Wrt.TextTertiary))
            }
            nets.firstOrNull()?.let { net -> LiveSsidEditorCard(store, radio, net, channel) }
            LiveChannelChartCard(store, radio, channel)
            nets.drop(1).forEach { net -> ExtraSsidRow(store, net) }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .clickable { expandedRadio = radio.section }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                SectionLabel("${radio.section.uppercase()} — ${bandHeader(radio.band)}", tracking = 0.14)
                MonoTag("CH $channel · ${widthLabel(radio.htmode)}", size = 9f)
                FlexSpacer()
                Text("${nets.size} SSID${if (nets.size == 1) "" else "s"}", style = mono(10f, 500, Wrt.TextTertiary))
                Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
            }
        }
    }
}

private fun bandHeader(band: String) = when (band) {
    "2.4G" -> "2.4 GHZ"
    "5G" -> "5 GHZ"
    "6G" -> "6 GHZ"
    else -> band
}

private fun widthLabel(htmode: String): String {
    val digits = htmode.dropWhile { !it.isDigit() }
    return if (digits.isEmpty()) "—" else "$digits MHz"
}

private fun channelsFor(band: String) = when (band) {
    "5G" -> listOf("auto", "36", "40", "44", "48", "149", "153", "157", "161")
    "6G" -> listOf("auto", "1", "33", "65", "97", "129", "161", "193")
    else -> listOf("auto", "1", "6", "11", "13")
}

@Composable
private fun LiveSsidEditorCard(store: WifiStore, radio: WifiRadio, net: WifiNetwork, channel: String) {
    val ssid = store.value(net.section, "ssid", net.ssid)
    val key = store.value(net.section, "key", net.key)
    val savedDisabled = if (net.disabled) "1" else "0"
    val enabled = store.value(net.section, "disabled", savedDisabled) != "1"
    val ssidChanged = store.staged.containsKey("${net.section}.ssid")
    val keyChanged = store.staged.containsKey("${net.section}.key")
    val channelChanged = store.staged.containsKey("${radio.section}.channel")
    var reveal by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = ssid,
                    onValueChange = { store.stage(net.section, "ssid", net.ssid, it) },
                    textStyle = sans(15f, 650),
                    singleLine = true,
                    cursorBrush = SolidColor(Wrt.Accent),
                )
            }
            if (ssidChanged) StatusDot(Wrt.Accent, 5.dp)
            MonoTag(Parsers.encryptionLabel(net.encryption), size = 9f)
            WToggle(enabled) { store.stage(net.section, "disabled", savedDisabled, if (enabled) "1" else "0") }
        }
        Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("PASSWORD", size = 9.5f)
            if (keyChanged) StatusDot(Wrt.Accent, 5.dp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(42.dp)
                .border(1.dp, if (keyChanged) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = key,
                    onValueChange = { store.stage(net.section, "key", net.key, it) },
                    textStyle = mono(13f, 500),
                    singleLine = true,
                    cursorBrush = SolidColor(Wrt.Accent),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation('•'),
                )
            }
            Icon(
                WrtIcons.Eye,
                if (reveal) "hide" else "show",
                Modifier.size(17.dp).clickable { reveal = !reveal },
                tint = if (reveal) Wrt.Accent else Wrt.TextTertiary,
            )
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("CHANNEL", size = 9.5f)
                    if (channelChanged) StatusDot(Wrt.Accent, 5.dp)
                }
                ChannelSelect(
                    value = channel,
                    options = channelsFor(radio.band),
                    highlight = channelChanged,
                    onPick = { store.stage(radio.section, "channel", radio.channel, it) },
                )
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("WIDTH", size = 9.5f)
                StaticBox(widthLabel(radio.htmode))
            }
        }
    }
}

@Composable
private fun ExtraSsidRow(store: WifiStore, net: WifiNetwork) {
    val savedDisabled = if (net.disabled) "1" else "0"
    val enabled = store.value(net.section, "disabled", savedDisabled) != "1"
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            store.value(net.section, "ssid", net.ssid),
            style = sans(13.5f, 650, if (enabled) Wrt.TextPrimary else Wrt.TextSecondary),
            modifier = Modifier.weight(1f),
        )
        MonoTag(Parsers.encryptionLabel(net.encryption), size = 9f)
        WToggle(enabled) { store.stage(net.section, "disabled", savedDisabled, if (enabled) "1" else "0") }
    }
}

@Composable
private fun ChannelSelect(value: String, options: List<String>, highlight: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(40.dp)
                .border(1.dp, if (highlight) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = mono(13f, 500))
            FlexSpacer()
            Icon(WrtIcons.ChevronDown, null, Modifier.size(12.dp), tint = Wrt.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = mono(12.5f, 500, if (option == value) Wrt.Accent else Wrt.TextPrimary)) },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

@Composable
private fun StaticBox(value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(40.dp)
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = mono(13f, 500, Wrt.TextSecondary))
    }
}

@Composable
private fun LiveChannelChartCard(store: WifiStore, radio: WifiRadio, channel: String) {
    val scope = rememberCoroutineScope()
    val cells = store.scans[radio.section]
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("CHANNEL — ${bandHeader(radio.band)}", size = 9.5f)
            FlexSpacer()
            Text(
                when {
                    store.scanning -> "scanning…"
                    cells != null -> "${cells.size} neighbors heard"
                    else -> "not scanned yet"
                },
                style = mono(10f, 500, if (store.scanning) Wrt.Amber else Wrt.TextTertiary),
            )
            Text(
                "Scan",
                style = sans(11.5f, 600, if (store.scanning) Wrt.TextDim else Wrt.Accent),
                modifier = Modifier.clickable(enabled = !store.scanning) {
                    scope.launch { store.scan(radio.section) }
                },
            )
        }
        if (cells != null) {
            val ourChannel = channel.toIntOrNull()
            val axis = chartAxis(radio.band, cells, ourChannel)
            LiveChannelChart(
                cells = cells,
                axis = axis,
                ourChannel = ourChannel,
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                axis.forEach { ch ->
                    Text(
                        "$ch",
                        style = mono(9.5f, if (ch == ourChannel) 600 else 500, if (ch == ourChannel) Wrt.Accent else Wrt.TextDim),
                    )
                }
            }
        } else if (!store.scanning) {
            Text(
                "Survey nearby access points to judge how crowded this channel is.",
                style = sans(11f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // A scan that fails has to say so here; the card is the only place the user is looking.
        store.error?.let { message ->
            Text(
                message,
                style = sans(11f, 500, Wrt.Red, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Sorted label channels: fixed comb for 2.4 GHz, observed channels elsewhere. */
private fun chartAxis(band: String, cells: List<ScanCell>, ourChannel: Int?): List<Int> =
    if (band == "2.4G") listOf(1, 3, 6, 9, 11, 13)
    else (cells.map { it.channel } + listOfNotNull(ourChannel)).distinct().sorted().ifEmpty { listOf(36, 149) }

/**
 * Neighbor-occupancy arcs, one per heard AP: x from the channel's position on the axis,
 * height from signal strength. Our own channel is the accent arc.
 */
@Composable
private fun LiveChannelChart(cells: List<ScanCell>, axis: List<Int>, ourChannel: Int?, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val baseY = h * 0.875f
        val lo = axis.first().toFloat()
        val hi = axis.last().toFloat()
        fun xFor(ch: Int): Float =
            if (hi > lo) ((ch - lo) / (hi - lo)) * (w * 0.9f) + w * 0.05f else w / 2f
        fun arc(ch: Int, signal: Int, fill: Color, stroke: Color, sw: Float) {
            val cx = xFor(ch)
            // -40 dBm → tall, -90 dBm → low
            val strength = ((signal + 90) / 50f).coerceIn(0.08f, 1f)
            val top = baseY - strength * (h * 0.72f)
            val halfW = w * 0.11f
            val p = Path().apply {
                moveTo(cx - halfW, baseY)
                quadraticTo(cx, top, cx + halfW, baseY)
            }
            drawPath(p, fill)
            drawPath(p, stroke, style = Stroke(sw.dp.toPx()))
        }
        drawLine(Wrt.ProgressTrack, Offset(0f, baseY), Offset(w, baseY), 1.dp.toPx())
        cells.filter { it.channel != ourChannel }.forEach { cell ->
            arc(cell.channel, cell.signalDbm, Wrt.TextTertiary.copy(alpha = 0.10f), Wrt.DotOff, 1f)
        }
        // neighbors sharing our channel are the contention that matters — amber
        cells.filter { it.channel == ourChannel }.forEach { cell ->
            arc(cell.channel, cell.signalDbm, Wrt.Amber.copy(alpha = 0.14f), Wrt.Amber.copy(alpha = 0.55f), 1.2f)
        }
        ourChannel?.let { arc(it, -45, Wrt.Accent.copy(alpha = 0.18f), Wrt.Accent, 1.5f) }
    }
}

// ---------- demo path (design fidelity, no session) ----------

@Composable
private fun DemoRadios(pendingCount: Int) {
    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("RADIO0 — 2.4 GHZ", tracking = 0.14)
        FlexSpacer()
        Text("MT7986 · 802.11ax", style = mono(10f, 500, Wrt.TextTertiary))
    }
    DemoSsidEditorCard(changed = pendingCount > 0)
    ChannelChartCard()
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SectionLabel("RADIO1 — 5 GHZ", tracking = 0.14)
        MonoTag("CH 36 · 80 MHz", size = 9f)
        FlexSpacer()
        Text("2 SSIDs", style = mono(10f, 500, Wrt.TextTertiary))
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
}

@Composable
private fun DemoSsidEditorCard(changed: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Casa", style = sans(15f, 650))
            MonoTag("WPA3-SAE", size = 9f)
            FlexSpacer()
            WToggle(true)
        }
        Row(Modifier.padding(top = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("PASSWORD", size = 9.5f)
            if (changed) StatusDot(Wrt.Accent, 5.dp)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(42.dp)
                .border(1.dp, if (changed) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("tr0ub4dor&3", style = mono(13f, 500), modifier = Modifier.weight(1f))
            Icon(WrtIcons.Eye, "show", Modifier.size(17.dp), tint = Wrt.TextTertiary)
            Icon(WrtIcons.Qr, "QR code", Modifier.size(17.dp), tint = Wrt.TextTertiary)
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("CHANNEL", size = 9.5f)
                    if (changed) StatusDot(Wrt.Accent, 5.dp)
                }
                DemoSelectBox("11", highlight = changed)
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("WIDTH", size = 9.5f)
                DemoSelectBox("40 MHz")
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("TX POWER", size = 9.5f)
            Box(Modifier.weight(1f).height(15.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(Wrt.ProgressTrack, RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth(0.78f).height(4.dp).background(Wrt.Accent, RoundedCornerShape(2.dp)))
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = 0.dp)
                        .fillMaxWidth(0.78f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .background(Wrt.TextPrimary, CircleShape)
                            .border(3.dp, Wrt.Accent, CircleShape)
                    )
                }
            }
            Text("20 dBm", style = mono(11.5f, 500, Wrt.TextTertiary))
        }
    }
}

@Composable
private fun DemoSelectBox(value: String, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(40.dp)
            .border(1.dp, if (highlight) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderInput, RoundedCornerShape(10.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = mono(13f, 500))
        FlexSpacer()
        Icon(WrtIcons.ChevronDown, null, Modifier.size(12.dp), tint = Wrt.TextDim)
    }
}

@Composable
private fun ChannelChartCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("CHANNEL — 2.4 GHZ", size = 9.5f)
            FlexSpacer()
            Text("11 neighbors heard", style = mono(10f, 500, Wrt.TextTertiary))
        }
        ChannelChart(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = mono(9.5f, 500, Wrt.TextDim))
            Text("3", style = mono(9.5f, 500, Wrt.TextDim))
            Text("6", style = mono(9.5f, 500, Wrt.Amber))
            Text("9", style = mono(9.5f, 500, Wrt.TextDim))
            Text("11", style = mono(9.5f, 600, Wrt.Accent))
            Text("13", style = mono(9.5f, 500, Wrt.TextDim))
        }
    }
}

/** Neighbor-occupancy arcs over the 2.4 GHz band, traced from the design SVG (364x96 space). */
@Composable
private fun ChannelChart(modifier: Modifier) {
    Canvas(modifier) {
        val sx = size.width / 364f
        val sy = size.height / 96f
        fun arc(x0: Float, cx: Float, cy: Float, x1: Float, fill: Color, stroke: Color, sw: Float = 1f) {
            val p = Path().apply {
                moveTo(x0 * sx, 84f * sy)
                quadraticTo(cx * sx, cy * sy, x1 * sx, 84f * sy)
            }
            drawPath(p, fill)
            drawPath(p, stroke, style = Stroke(sw.dp.toPx()))
        }
        // baseline
        drawLine(Wrt.ProgressTrack, Offset(0f, 84f * sy), Offset(size.width, 84f * sy), 1.dp.toPx())
        val greyFill = Wrt.TextTertiary.copy(alpha = 0.10f)
        val greyLine = Wrt.DotOff
        arc(-12f, 28f, 34f, 68f, greyFill, greyLine)
        arc(8f, 48f, 48f, 88f, greyFill, greyLine)
        arc(100f, 140f, 18f, 180f, greyFill, greyLine)
        arc(112f, 152f, 42f, 192f, greyFill, greyLine)
        arc(124f, 164f, 30f, 204f, greyFill, greyLine)
        arc(196f, 236f, 52f, 276f, greyFill, greyLine)
        arc(268f, 308f, 26f, 348f, greyFill, greyLine)
        arc(280f, 320f, 44f, 360f, greyFill, greyLine)
        // amber: channel 6 cluster
        arc(88f, 128f, 12f, 168f, Wrt.Amber.copy(alpha = 0.14f), Wrt.Amber.copy(alpha = 0.55f), 1.2f)
        // accent: our channel 11
        arc(232f, 272f, 8f, 312f, Wrt.Accent.copy(alpha = 0.18f), Wrt.Accent, 1.5f)
    }
}
