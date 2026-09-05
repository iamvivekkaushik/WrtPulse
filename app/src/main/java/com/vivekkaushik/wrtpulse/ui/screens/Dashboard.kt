package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.RouterOps
import com.vivekkaushik.wrtpulse.data.SpeedPhase
import com.vivekkaushik.wrtpulse.data.SpeedResult
import com.vivekkaushik.wrtpulse.data.Ssid
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.Sparkline
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.ThroughputChart
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

private val cpuSpark = listOf(13f, 11f, 14f, 9f, 12f, 7f, 11f, 5f, 10f, 8f, 12f, 9f)
private val ramSpark = listOf(10f, 9f, 10f, 8f, 9f, 8f, 7f, 8f, 7f, 8f, 7f, 7f)

@Composable
fun DashboardScreen(
    ticker: LiveTicker,
    live: Telemetry?,
    inventory: Inventory?,
    ops: RouterOps? = null,
    guest: com.vivekkaushik.wrtpulse.data.GuestStore? = null,
    board: com.vivekkaushik.wrtpulse.ops.BoardInfo? = null,
    routerName: String,
    onRouterTap: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    var rebootOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var guestOpen by remember { mutableStateOf(false) }
    if (rebootOpen && ops != null) {
        RebootDialog(ops, routerName, onDismiss = { rebootOpen = false })
    }
    if (speedOpen && ops != null) {
        SpeedtestDialog(ops, onDismiss = { speedOpen = false })
    }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = live?.latencyMs ?: ticker.latencyMs,
            pollMs = live?.tickMs,
            onRouterTap = onRouterTap,
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
                QuickAction(
                    Modifier.weight(1f), WrtIcons.Reboot, "Reboot",
                    onClick = if (ops != null) ({ rebootOpen = true }) else null,
                )
                QuickAction(
                    Modifier.weight(1f), WrtIcons.GuestWifi, "Guest Wi-Fi",
                    onClick = if (guest != null) ({ guestOpen = true }) else null,
                )
                QuickAction(
                    Modifier.weight(1f), WrtIcons.Speedtest, "Speedtest",
                    onClick = if (ops != null) ({ speedOpen = true }) else null,
                )
                QuickAction(Modifier.weight(1f), WrtIcons.Prompt, "Terminal", onClick = onOpenTerminal)
            }
        }
    }
    SheetHost(visible = guestOpen, onDismiss = { guestOpen = false }) {
        GuestSheet(guest, board?.hostname, onDismiss = { guestOpen = false })
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
        val up = live?.upstream
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Named after whatever holds the default route, not a fixed "wan" — a router
            // upstreamed through a Wi-Fi client has neither the name nor the device.
            SectionLabel(
                "UPSTREAM · ${(up?.name ?: if (live != null) "—" else "wan").uppercase()}",
                size = 9.5f,
            )
            if (up?.wireless == true) MonoTag("CLIENT", color = Wrt.Blue, border = Wrt.Blue.copy(alpha = 0.5f))
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
            Icon(WrtIcons.Copy, "copy upstream IP", Modifier.size(14.dp), tint = Wrt.TextDim)
            FlexSpacer()
            Text("↓ ${String.format("%.1f", (live?.down ?: ticker.down).last())}", style = mono(12.5f, 600, Wrt.Accent))
            Text("↑ ${String.format("%.1f", (live?.up ?: ticker.up).last())}", style = mono(12.5f, 600, Wrt.Blue))
            Text("Mbps", style = mono(9.5f, 500, Wrt.TextDim))
        }
        // The chart scales the series itself now; both sources hand it raw values.
        ThroughputChart(
            down = live?.down ?: ticker.down,
            up = live?.up ?: ticker.up,
            modifier = Modifier.fillMaxWidth().height(88.dp).padding(top = 6.dp),
        )
        // What the upstream actually is, under the numbers it produces.
        up?.let { u ->
            // The metric only explains anything when the links disagree about it.
            val several = live?.upstreams.orEmpty().map { it.metric }.distinct().size > 1
            Text(
                listOfNotNull(
                    u.ssid?.let { "via $it" },
                    u.device.ifBlank { null },
                    u.proto.ifBlank { null },
                    // Only worth showing when there is another link to compare it against.
                    if (several) "metric ${u.metric}" else null,
                ).joinToString(" · "),
                style = mono(10f, 500, if (u.wireless) Wrt.Blue else Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp)) {
            Text("60 s window", style = mono(10f, 500, Wrt.TextDim))
            FlexSpacer()
            Text(
                live?.totals ?: (if (live != null) "" else "since 09:12 · ↓ 214 GB · ↑ 38 GB"),
                style = mono(10f, 500, Wrt.TextDim),
            )
        }
        // A second link with its own default route is a real thing on a failover setup, and
        // the chart can only follow one of them. The rest get a line each.
        val others = live?.upstreams?.drop(1).orEmpty()
        if (others.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(1.dp).background(Wrt.BorderHair))
            Text(
                "ALSO UP",
                style = mono(9f, 600, Wrt.TextDim, letterSpacing = 0.14.em),
                modifier = Modifier.padding(top = 9.dp),
            )
            val ranked = live?.upstreams.orEmpty().map { it.metric }.distinct().size > 1
            others.forEach { link -> OtherUpstreamRow(link, live?.rates?.get(link.device), ranked) }
        }
    }
}

/** One extra upstream: what it is, where it goes, and what it is carrying. */
@Composable
private fun OtherUpstreamRow(
    link: com.vivekkaushik.wrtpulse.ops.Upstream,
    rate: Pair<Float, Float>?,
    showMetric: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(link.name.uppercase(), style = mono(11f, 600))
                if (link.wireless) {
                    MonoTag("CLIENT", color = Wrt.Blue, border = Wrt.Blue.copy(alpha = 0.5f), size = 8.5f)
                }
                if (!link.hasV4) MonoTag("IPv6", size = 8.5f)
            }
            Text(
                listOfNotNull(
                    link.ssid?.let { "via $it" } ?: link.address,
                    link.device.ifBlank { null },
                    if (showMetric) "metric ${link.metric}" else null,
                ).joinToString(" · "),
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "↓ ${String.format("%.1f", rate?.first ?: 0f)}",
            style = mono(11f, 600, Wrt.Accent.copy(alpha = 0.75f)),
        )
        Text(
            "↑ ${String.format("%.1f", rate?.second ?: 0f)}",
            style = mono(11f, 600, Wrt.Blue.copy(alpha = 0.75f)),
        )
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

/** Rebooting cuts the network for everyone, so it takes a deliberate 3 s hold. */
@Composable
private fun RebootDialog(ops: RouterOps, routerName: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Reboot $routerName?", style = sans(15f, 650, Wrt.Red))
            Text(
                "Wi-Fi and internet drop for a minute or two while it restarts. " +
                    "The app reconnects on its own once it is back.",
                style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
            if (result != null) {
                Text(
                    result!!,
                    style = mono(10.5f, 500, if (result!!.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Done", onClick = onDismiss)
            } else {
                Spacer(Modifier.height(16.dp))
                HoldToConfirm("Hold to reboot") {
                    scope.launch { result = ops.reboot() }
                }
                Text(
                    "Hold 3 s to confirm",
                    style = sans(10.5f, 400, Wrt.TextDim),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                GhostButton("Cancel", onClick = onDismiss)
            }
        }
    }
}

/** A speed test leaves the house, so it says where it goes and how much it pulls. */
@Composable
private fun SpeedtestDialog(ops: RouterOps, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(SpeedPhase.Download) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Measure download speed", style = sans(15f, 650))
            Text(
                "The router downloads 20 MB from ${Commands.SPEEDTEST_HOST} and uploads 5 MB back. " +
                    "This uses your internet data.",
                style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
            val outcome = result
            if (outcome != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .border(1.dp, Wrt.BorderHair, RoundedCornerShape(11.dp))
                        .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 12.dp)
                ) {
                    if (outcome.error != null) {
                        Text(outcome.error, style = sans(12f, 500, Wrt.Red))
                    } else {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "↓ ${String.format("%.1f", outcome.downMbps)}",
                                    style = mono(18f, 600, Wrt.Accent),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Text(
                                    "${Telemetry.bytesLabel(outcome.downBytes)} · " +
                                        "${String.format("%.1f", outcome.downSeconds)} s",
                                    style = mono(10f, 500, Wrt.TextDim),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                if (outcome.hasUpload) {
                                    Text(
                                        "↑ ${String.format("%.1f", outcome.upMbps)}",
                                        style = mono(18f, 600, Wrt.Blue),
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    Text(
                                        "${Telemetry.bytesLabel(outcome.upBytes)} · " +
                                            "${String.format("%.1f", outcome.upSeconds)} s",
                                        style = mono(10f, 500, Wrt.TextDim),
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                } else {
                                    Text("↑ —", style = mono(18f, 600, Wrt.TextDim), maxLines = 1)
                                }
                            }
                            Text("Mbps", style = mono(10.5f, 500, Wrt.TextDim), modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(
                            outcome.uploadError ?: "Includes connection setup, so both read a little low.",
                            style = sans(10f, 400, if (outcome.uploadError != null) Wrt.Amber else Wrt.TextDim),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                when {
                    running && phase == SpeedPhase.Download -> "Downloading…"
                    running -> "Uploading…"
                    result != null -> "Run again"
                    else -> "Run test"
                },
                onClick = {
                    if (!running) {
                        running = true
                        scope.launch {
                            phase = SpeedPhase.Download
                            result = ops.speedtest(onPhase = { phase = it })
                            running = false
                        }
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            GhostButton(if (result != null) "Close" else "Cancel", onClick = { if (!running) onDismiss() })
        }
    }
}

/** Fills over a 3 s hold; letting go early cancels. Shared with the firmware flash. */
@Composable
fun HoldToConfirm(label: String, onConfirm: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Wrt.Red.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        val pressed = currentEvent.changes.any { it.pressed }
                        if (pressed && !done && !progress.isRunning) {
                            scope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(((1f - progress.value) * 3000).toInt(), easing = LinearEasing),
                                )
                                if (progress.value >= 1f) {
                                    done = true
                                    onConfirm()
                                }
                            }
                        } else if (!pressed && !done) {
                            scope.launch {
                                progress.stop()
                                progress.animateTo(0f, tween(180))
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress.value)
                .height(46.dp)
                .background(Wrt.Red.copy(alpha = 0.3f))
        )
        Text(label, style = sans(13.5f, 650, Wrt.Red))
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
