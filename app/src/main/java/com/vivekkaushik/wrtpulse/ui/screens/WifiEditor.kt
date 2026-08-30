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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.WifiStore
import com.vivekkaushik.wrtpulse.ops.ChannelPlan
import com.vivekkaushik.wrtpulse.ops.ScanCell
import com.vivekkaushik.wrtpulse.ops.WifiRadio
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch


/**
 * One radio: whether it is on, the channel and width it runs, and how crowded that channel
 * actually is. The Network tab used to carry all of this inline; it lives here now that the
 * Wireless page owns everything wireless.
 */
@Composable
fun RadioScreen(store: WifiStore, radio: WifiRadio, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val channel = store.value(radio.section, "channel", radio.channel)
    val htmode = store.value(radio.section, "htmode", radio.htmode)
    val on = store.radioEnabled(radio.section)
    val savedDisabled = if (radio.disabled) "1" else "0"

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("${radio.section} · ${bandHeader(radio.band)}", onBack) {
            Text(htmode.ifEmpty { "—" }, style = mono(10.5f, 500, Wrt.TextTertiary))
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ToggleCard {
                ToggleRow(
                    "Radio enabled",
                    if (on) "Networks on this radio can broadcast"
                    else "Every network on this radio is saved but silent",
                    on,
                    divider = false,
                ) {
                    store.stage(radio.section, "disabled", savedDisabled, if (on) "1" else "0")
                }
            }
            if (!on) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(15.dp), tint = Wrt.Amber)
                    Text(
                        "This is what LuCI reports as \"Wireless is disabled\". Nothing on " +
                            "${radio.section} reaches the air until it is switched on.",
                        style = sans(11.5f, 400, Wrt.AmberText, lineHeight = 17.sp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("CHANNEL", size = 9.5f)
                        if (store.staged.containsKey("${radio.section}.channel")) StatusDot(Wrt.Accent, 5.dp)
                    }
                    ChannelSelect(
                        value = channel,
                        options = channelsFor(radio.band),
                        highlight = store.staged.containsKey("${radio.section}.channel"),
                        onPick = { store.stage(radio.section, "channel", radio.channel, it) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("WIDTH", size = 9.5f)
                        if (store.staged.containsKey("${radio.section}.htmode")) StatusDot(Wrt.Accent, 5.dp)
                    }
                    ChannelSelect(
                        value = ChannelPlan.widthLabel(htmode),
                        options = ChannelPlan.widths(radio.htmode, radio.band).map { ChannelPlan.widthLabel(it) },
                        highlight = store.staged.containsKey("${radio.section}.htmode"),
                        onPick = { picked ->
                            ChannelPlan.widths(radio.htmode, radio.band)
                                .firstOrNull { ChannelPlan.widthLabel(it) == picked }
                                ?.let { store.stage(radio.section, "htmode", radio.htmode, it) }
                        },
                    )
                }
            }
            LiveChannelChartCard(store, radio, channel)
            val nets = store.networks.count { it.device == radio.section }
            Text(
                "$nets network${if (nets == 1) "" else "s"} on this radio.",
                style = sans(11f, 400, Wrt.TextDim),
                modifier = Modifier.padding(start = 2.dp),
            )
            Spacer(Modifier.height(4.dp))
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
                if (cells == null) "Scan" else "Rescan",
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
            // The chart shows the crowding; this says what to do about it.
            ChannelPlan.advise(radio.band, cells)?.let { advice ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    StatusDot(Wrt.Accent, 6.dp)
                    Text(
                        advice.headline,
                        style = sans(11.5f, 400, Wrt.TextPrimary.copy(alpha = 0.85f)),
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .background(Wrt.Accent, RoundedCornerShape(7.dp))
                            .clickable {
                                store.stage(radio.section, "channel", radio.channel, "${advice.channel}")
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("Use best", style = sans(11f, 650, Wrt.OnAccent))
                    }
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
