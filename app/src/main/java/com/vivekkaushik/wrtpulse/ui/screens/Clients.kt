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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import kotlinx.coroutines.launch
import com.vivekkaushik.wrtpulse.data.Client
import com.vivekkaushik.wrtpulse.data.Demo
import androidx.compose.runtime.LaunchedEffect
import com.vivekkaushik.wrtpulse.data.Inventory
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.ops.InstallPlan
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
fun ClientsScreen(
    ticker: LiveTicker,
    live: Inventory?,
    liveLatencyMs: Int? = null,
    routerName: String,
    onRouterTap: () -> Unit,
    onRename: (mac: String, name: String) -> Unit = { _, _ -> },
) {
    var expandedMac by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    var byUsage by remember { mutableStateOf(false) }
    val hasUsage = live?.nlbwPresent == true && live.totals.isNotEmpty()
    val all = (if (live != null) live.clients.toList() else Demo.clients)
        .let { if (byUsage && hasUsage) it.sortedByDescending { c -> c.usageTotal } else it }

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
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
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
        var nlbwDialogOpen by remember { mutableStateOf(false) }
        if (nlbwDialogOpen && live != null) {
            NlbwInstallDialog(live, onDismiss = { nlbwDialogOpen = false })
        }
        if (live != null && live.nlbwPresent == false) {
            NlbwOfferBanner(onSetUp = { nlbwDialogOpen = true })
        } else if (hasUsage) {
            UsageSummaryStrip(
                clients = all,
                sortedByUsage = byUsage,
                onToggleSort = { byUsage = !byUsage },
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val expandKey = expandedMac ?: defaultExpand
            shown.forEach { client ->
                val expandable = live != null || (!client.blocked && !client.offline)
                if (client.mac == expandKey && expandable) {
                    ExpandedClientCard(client, ticker, live, onRename)
                } else {
                    ClientRow(client, onClick = { if (expandable) expandedMac = client.mac })
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

/**
 * What nlbwmon buys you, made visible: total traffic this accounting period, the busiest
 * device, and a tap to rank the list by usage.
 */
@Composable
private fun UsageSummaryStrip(clients: List<Client>, sortedByUsage: Boolean, onToggleSort: () -> Unit) {
    val down = clients.sumOf { it.usageDown ?: 0L }
    val up = clients.sumOf { it.usageUp ?: 0L }
    val top = clients.maxByOrNull { it.usageTotal }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            .border(
                1.dp,
                if (sortedByUsage) Wrt.Accent.copy(alpha = 0.45f) else Wrt.BorderCard,
                RoundedCornerShape(12.dp),
            )
            .background(
                if (sortedByUsage) Wrt.Accent.copy(alpha = 0.06f) else Wrt.BgCard,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onToggleSort)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("↓ ${Telemetry.bytesLabel(down)}", style = mono(12f, 600, Wrt.Accent), maxLines = 1, softWrap = false)
                Text("↑ ${Telemetry.bytesLabel(up)}", style = mono(12f, 600, Wrt.Blue), maxLines = 1, softWrap = false)
                Text("this period", style = sans(10.5f, 400, Wrt.TextDim), maxLines = 1, softWrap = false)
            }
            if (top != null && top.usageTotal > 0) {
                Text(
                    "busiest: ${top.name} · ${Telemetry.bytesLabel(top.usageTotal)}",
                    style = sans(10.5f, 400, Wrt.TextSecondary),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Text(
            if (sortedByUsage) "By usage ✓" else "By usage",
            style = sans(11.5f, 600, if (sortedByUsage) Wrt.Accent else Wrt.TextTertiary),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun NlbwOfferBanner(onSetUp: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(Wrt.Accent.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Per-client usage", style = sans(12.5f, 600))
            Text(
                "Install nlbwmon on the router to meter each device",
                style = sans(10.5f, 400, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .background(Wrt.Accent, RoundedCornerShape(7.dp))
                .clickable(onClick = onSetUp)
                .padding(horizontal = 11.dp, vertical = 6.dp)
        ) { Text("Set up", style = sans(11.5f, 600, Wrt.OnAccent)) }
    }
}

/** Nothing installs until the user has seen the size and the space that will remain. */
@Composable
private fun NlbwInstallDialog(live: Inventory, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<InstallPlan?>(null) }
    var installing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { plan = live.planNlbwInstall() }

    Dialog(onDismissRequest = { if (!installing) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Install nlbwmon?", style = sans(15f, 650))
            Text(
                "Adds a small bandwidth accounting service to the router.",
                style = sans(11.5f, 400, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(14.dp))
            val p = plan
            when {
                p == null -> Text("Checking package feed and free space…", style = mono(11f, 500, Wrt.TextDim))
                p.problem != null -> Text(p.problem!!, style = sans(12f, 500, Wrt.Red))
                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Wrt.BorderHair, RoundedCornerShape(11.dp))
                        .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                ) {
                    val total = p.totalBytes
                    val freeBytes = p.availKb?.let { it * 1024 }
                    PlanRow(
                        "Install size",
                        total?.let {
                            preciseBytes(it) +
                                if (p.packages.size > 1) " · ${p.packages.size} packages" else ""
                        } ?: "unknown (${p.packageManager})",
                    )
                    PlanRow("Free space now", freeBytes?.let { preciseBytes(it) } ?: "—")
                    PlanRow(
                        "After install",
                        if (total != null && freeBytes != null) "≈ ${preciseBytes(freeBytes - total)}"
                        else "—",
                        highlight = true,
                    )
                    Text(
                        "Sizes come from ${p.packageManager}; free space from df /overlay.",
                        style = sans(9.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (result != null) {
                Text(
                    result!!,
                    style = mono(10.5f, 500, if (result!!.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (result?.startsWith("Failed") == false) {
                PrimaryButton("Done", onClick = onDismiss)
            } else if (p != null && p.problem == null) {
                PrimaryButton(
                    if (installing) "Installing…" else "Install",
                    onClick = {
                        if (!installing) {
                            installing = true
                            result = null
                            scope.launch {
                                result = live.installNlbw()
                                installing = false
                            }
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                GhostButton("Cancel", onClick = { if (!installing) onDismiss() })
            } else {
                GhostButton("Close", onClick = onDismiss)
            }
        }
    }
}

/** Enough precision that a small install still visibly moves the "after" figure. */
private fun preciseBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000L -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Composable
private fun PlanRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = mono(10.5f, 500, Wrt.TextDim))
        FlexSpacer()
        Text(value, style = mono(12f, if (highlight) 600 else 500, if (highlight) Wrt.Accent else Wrt.TextPrimary))
    }
}

@Composable
private fun ExpandedClientCard(
    client: Client,
    ticker: LiveTicker,
    live: Inventory?,
    onRename: (mac: String, name: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var actionMsg by remember(client.mac) { mutableStateOf<String?>(null) }
    var actionBusy by remember(client.mac) { mutableStateOf(false) }
    var renameOpen by remember(client.mac) { mutableStateOf(false) }
    var reserveOpen by remember(client.mac) { mutableStateOf(false) }

    fun runAction(block: suspend () -> String) {
        if (live == null || actionBusy) return
        actionBusy = true
        actionMsg = "Working…"
        scope.launch {
            actionMsg = block()
            actionBusy = false
        }
    }

    if (renameOpen) {
        WrtInputDialog(
            title = "Rename client",
            label = "NAME",
            initial = client.name,
            confirmLabel = "Save name",
            onDismiss = { renameOpen = false },
            onConfirm = { value ->
                renameOpen = false
                if (value.isNotBlank()) onRename(client.mac, value.trim())
            },
        )
    }
    if (reserveOpen) {
        WrtInputDialog(
            title = "Static IP for ${client.name}",
            label = "IP ADDRESS",
            initial = client.staticIp ?: client.ip.takeIf { it != "—" } ?: "",
            confirmLabel = "Reserve",
            keyboard = KeyboardType.Number,
            onDismiss = { reserveOpen = false },
            onConfirm = { value ->
                reserveOpen = false
                if (value.isNotBlank()) runAction { live!!.reserve(client.mac, value.trim(), client.name) }
            },
        )
    }
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
                    style = mono(9f, 500, Wrt.TextDim),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (client.staticIp != null) {
                MonoTag("STATIC", color = Wrt.Blue, border = Wrt.Blue.copy(alpha = 0.45f), size = 9f)
            }
            if (client.network.isNotEmpty()) MonoTag(client.network, size = 9f)
        }
        if (client.staticIp != null && client.staticIp != client.ip) {
            // The reservation only takes effect on the client's next DHCP renewal.
            Text(
                "reserved ${client.staticIp} · applies on next renewal",
                style = mono(9.5f, 500, Wrt.Blue),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 6.dp),
            )
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
            // Rates take the flexible half: PHY link rates are long enough to overflow the
            // row, and if anything has to give it should be them, not the signal/lease pair.
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (client.offline) {
                    Text("offline", style = mono(11f, 600, Wrt.TextDim), maxLines = 1)
                } else {
                    val down = client.downMbps ?: if (live != null) null else ticker.clientDown
                    val up = client.upMbps ?: if (live != null) null else ticker.clientUp
                    Text(
                        "↓ ${down?.let { String.format("%.1f", it) } ?: "—"} Mb/s",
                        style = mono(11f, 600, Wrt.Accent),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "↑ ${up?.let { String.format("%.1f", it) } ?: "—"} Mb/s",
                        style = mono(11f, 600, Wrt.Blue),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                client.signalDbm?.let { "$it dBm" } ?: if (live != null) "—" else "-52 dBm",
                style = mono(10f, 500, Wrt.TextTertiary),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                client.leaseLabel ?: if (live != null) "no lease" else "lease 23 h",
                style = mono(10f, 500, Wrt.TextDim),
                maxLines = 1,
                softWrap = false,
            )
        }
        if (client.usageDown != null || client.usageUp != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USAGE · THIS PERIOD", style = mono(9f, 600, Wrt.TextDim, letterSpacing = 0.12.em), maxLines = 1, softWrap = false)
                    FlexSpacer()
                    Text("↓ ${Telemetry.bytesLabel(client.usageDown ?: 0)}", style = mono(11f, 600, Wrt.Accent), maxLines = 1, softWrap = false)
                    Text("↑ ${Telemetry.bytesLabel(client.usageUp ?: 0)}", style = mono(11f, 600, Wrt.Blue), maxLines = 1, softWrap = false)
                }
                if (client.apps.isNotEmpty()) {
                    Text(
                        client.apps.joinToString(" · ") { (name, bytes) -> "$name ${Telemetry.bytesLabel(bytes)}" },
                        style = mono(10f, 500, Wrt.TextTertiary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (client.blocked) {
                ClientAction(Modifier.weight(1f), "Unblock", Wrt.Green, Wrt.Green.copy(alpha = 0.4f)) {
                    runAction { live!!.setBlocked(client.mac, false) }
                }
            } else {
                ClientAction(Modifier.weight(1f), "Block", Wrt.Red, Wrt.Red.copy(alpha = 0.4f)) {
                    runAction { live!!.setBlocked(client.mac, true) }
                }
            }
            if (client.staticIp != null) {
                ClientAction(Modifier.weight(1f), "Un-static", Wrt.Blue, Wrt.Blue.copy(alpha = 0.45f)) {
                    runAction { live!!.release(client.mac) }
                }
            } else {
                ClientAction(Modifier.weight(1f), "Static IP", Wrt.TextSecondary, Wrt.BorderInput) {
                    if (live != null) reserveOpen = true
                }
            }
            ClientAction(Modifier.weight(1f), "WoL", Wrt.TextSecondary, Wrt.BorderInput) {
                runAction { live!!.wake(client.mac) }
            }
            ClientAction(Modifier.weight(1f), "Rename", Wrt.TextSecondary, Wrt.BorderInput) {
                if (live != null) renameOpen = true
            }
        }
        if (actionMsg != null) {
            Text(
                actionMsg!!,
                style = mono(10f, 500, if (actionMsg!!.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WrtInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    keyboard: KeyboardType = KeyboardType.Ascii,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text(title, style = sans(15f, 650))
            Spacer(Modifier.height(14.dp))
            SectionLabel(label)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(44.dp)
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = mono(13f, 500),
                    singleLine = true,
                    cursorBrush = SolidColor(Wrt.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(confirmLabel, onClick = { onConfirm(value) })
            Spacer(Modifier.height(6.dp))
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}

@Composable
private fun ClientAction(
    modifier: Modifier,
    label: String,
    textColor: Color,
    border: Color,
    onClick: () -> Unit = {},
) {
    Box(
        modifier
            .height(33.dp)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
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
                    style = mono(9f, 500, Wrt.TextDim),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    client.blocked -> MonoTag("BLOCKED", color = Wrt.Red, border = Wrt.Red.copy(alpha = 0.45f), size = 9f)
                    client.offline -> MonoTag("OFFLINE", color = Wrt.TextDim, border = Wrt.BorderFaint, size = 9f)
                    client.network.isNotEmpty() -> MonoTag(client.network, size = 9f)
                }
                if (client.staticIp != null) {
                    Text(
                        "STATIC",
                        style = mono(8.5f, 600, Wrt.Blue),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (client.usageTotal > 0) {
                    Text(
                        Telemetry.bytesLabel(client.usageTotal),
                        style = mono(9.5f, 500, Wrt.TextDim),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.dp).background(Wrt.BorderRow))
    }
}
