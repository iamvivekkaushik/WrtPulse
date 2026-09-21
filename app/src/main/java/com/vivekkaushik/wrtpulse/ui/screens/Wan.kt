package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import com.vivekkaushik.wrtpulse.ui.HoldButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vivekkaushik.wrtpulse.data.LanV6
import com.vivekkaushik.wrtpulse.data.Socket
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.data.V6Mode
import com.vivekkaushik.wrtpulse.data.WanRow
import com.vivekkaushik.wrtpulse.data.WanStore
import com.vivekkaushik.wrtpulse.ops.PingResult
import com.vivekkaushik.wrtpulse.ui.PullToRefresh
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.ThroughputChart
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The pages behind the WAN card — design screens 26 through 29. */
internal enum class WanPage { Hub, Port, Ipv4, Ipv6 }

/**
 * Internet & WAN gateways.
 *
 * One interface at a time: the hub picks it, the three pages edit it, and the review sheet
 * applies everything at once with the router's rollback armed underneath.
 */
@Composable
fun WanSection(
    store: WanStore?,
    live: Telemetry?,
    latencyMs: Int,
    onBack: () -> Unit,
    /** Full-screen pages hide the tab bar, the way the design draws them. */
    onFullScreen: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var page by remember(store) { mutableStateOf(WanPage.Hub) }
    var reviewOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }

    com.vivekkaushik.wrtpulse.ui.LiveRefresh(store, WAN_REFRESH_MS)
    LaunchedEffect(page) { onFullScreen(page != WanPage.Hub) }
    androidx.activity.compose.BackHandler(enabled = page != WanPage.Hub) { page = WanPage.Hub }

    val title = when (page) {
        WanPage.Hub -> "Internet & WAN"
        WanPage.Port -> "${store?.selected.orEmpty()} · port & VLAN"
        WanPage.Ipv4 -> "${store?.selected.orEmpty()} · IPv4 protocol"
        WanPage.Ipv6 -> "${store?.selected.orEmpty()} · IPv6"
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar(title, { if (page == WanPage.Hub) onBack() else page = WanPage.Hub }) {
            Text(
                "$latencyMs ms",
                style = mono(10.5f, 500, Wrt.TextTertiary),
                modifier = Modifier
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to manage its uplinks.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        PullToRefresh(Modifier.weight(1f), onRefresh = { if (!store.applying && !store.refreshPaused) store.load() }) {
            when (page) {
                WanPage.Hub -> WanHub(
                    store = store,
                    live = live,
                    onOpen = { page = it },
                    onTest = { scope.launch { store.runTest() } },
                    onAdd = { addOpen = true },
                )
                WanPage.Port -> PortPage(store)
                WanPage.Ipv4 -> Ipv4Page(store)
                WanPage.Ipv6 -> Ipv6Page(store)
            }
        }
        if (store.pendingCount > 0) {
            FormActionBar(
                pendingCount = store.pendingCount,
                countLabel = "Unsaved gateway changes",
                saveLabel = "Review & Apply",
                saveEnabled = true,
                onCancel = { store.revert() },
                onSave = { reviewOpen = true },
            )
        }
    }

    SheetHost(visible = reviewOpen, onDismiss = { reviewOpen = false }) {
        WanReviewSheet(
            store = store,
            onApply = { scope.launch { if (store!!.apply()) reviewOpen = false } },
            onRevertAll = { store?.revert(); reviewOpen = false },
        )
    }
    SheetHost(visible = addOpen, onDismiss = { addOpen = false }) {
        if (store != null) {
            AddWiredUplinkSheet(
                store = store,
                onCancel = { addOpen = false },
                onCreate = { socket, proto ->
                    store.addWiredUplink(socket, proto)
                    addOpen = false
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 26 — the hub
// ---------------------------------------------------------------------------

@Composable
private fun WanHub(
    store: WanStore,
    live: Telemetry?,
    onOpen: (WanPage) -> Unit,
    onTest: () -> Unit,
    onAdd: () -> Unit,
) {
    val rows = store.wanRows()
    val selected = rows.firstOrNull { it.section == store.selected } ?: rows.firstOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!store.loaded) {
            Text(
                store.error ?: "reading interfaces over ssh…",
                style = mono(10.5f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
            )
        }
        store.notice?.let { NoteCard(it) }
        store.subnetClashes().forEach { ProblemCard(it) }
        if (rows.isEmpty() && store.loaded) {
            NoteCard(
                "No interface here is in the firewall's wan zone or carrying a default route, " +
                    "so this router has no uplink of its own to manage. Add one below to put " +
                    "the ISP on an ethernet socket."
            )
        }
        // One or two uplinks fill the width, the way the hub is drawn. Past that, weight
        // squeezes each chip until its metric and device name ellipsize into nothing, so the
        // row scrolls sideways instead and every chip keeps its natural, readable width.
        if (rows.size <= 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    WanChip(row, row.section == store.selected, Modifier.weight(1f)) { store.select(row.section) }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { row ->
                    WanChip(row, row.section == store.selected, Modifier.widthIn(min = 150.dp)) {
                        store.select(row.section)
                    }
                }
            }
        }
        selected?.let { row ->
            LiveCard(row, live)
            TestCard(store, row, onTest)
            CycleCard(store, row)
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp),
            ) {
                NavRow("Physical port & VLAN", store.portLabel, divider = true) {
                    onOpen(WanPage.Port)
                }
                NavRow(
                    "IPv4 protocol",
                    WanStore.protoLabel(store.proto),
                    divider = true,
                ) { onOpen(WanPage.Ipv4) }
                NavRow(
                    "IPv6",
                    listOfNotNull(
                        store.v6Mode.label,
                        store.pdSize.takeIf { it != "auto" }?.let { "/$it" },
                    ).joinToString(" · "),
                    divider = false,
                ) { onOpen(WanPage.Ipv6) }
            }
        }
        if (rows.isNotEmpty()) FailoverCard(store, rows)
        // A wired uplink is one batch: the socket leaves the LAN, an interface is created on
        // it, and the firewall's wan zone takes it. Failover between the uplinks that exist is
        // still the metric above; health-checked failover is mwan3, which the app does not
        // configure.
        AddUplinkCard(store, onAdd)
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Every uplink in the order it will be tried, dragged rather than numbered.
 *
 * The metric is still the only thing a router without mwan3 has, but a free-text number is a
 * poor way to express "this one first": it lets two uplinks hold the same value, invites
 * off-by-one edits, and asks the reader to remember that lower wins. The list says it
 * directly — top is first — and [WanStore.stageFailoverOrder] restamps the numbers underneath.
 */
@Composable
private fun FailoverCard(store: WanStore, rows: List<WanRow>) {
    val ordered = rows.sortedBy { it.metric }
    // The drop is handled inside a pointerInput block, which is not rebuilt on every
    // recomposition — so the list it reorders has to be the current one, not the one captured
    // when the gesture detector was installed.
    val latest by rememberUpdatedState(ordered)
    val haptics = LocalHapticFeedback.current

    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragBy by remember { mutableFloatStateOf(0f) }
    // One row plus the gap above it: the distance the finger has to travel to displace a
    // neighbour. Measured rather than assumed, because the whole UI is density-scaled.
    var slotPx by remember { mutableIntStateOf(0) }

    fun targetOf(from: Int, by: Float): Int =
        if (from < 0 || slotPx <= 0) -1
        else (from + (by / slotPx).roundToInt()).coerceIn(0, latest.lastIndex)

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("Failover order", style = sans(13f, 600))
        Text(
            if (ordered.size > 1) {
                "Hold a row and drag it. The top uplink carries the traffic; the rest wait for " +
                    "its route to disappear."
            } else {
                "One uplink, so nothing to fail over to. A second one would be ordered here."
            },
            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
        val dragTo = targetOf(dragFrom, dragBy)
        ordered.forEachIndexed { i, row ->
            // While a row is held, the ones it has passed slide out of its way by exactly one
            // slot, so the gap under the finger is where the row will land.
            val shift = when {
                i == dragFrom -> dragBy
                dragFrom < 0 || dragTo < 0 -> 0f
                dragFrom < dragTo && i > dragFrom && i <= dragTo -> -slotPx.toFloat()
                dragTo < dragFrom && i >= dragTo && i < dragFrom -> slotPx.toFloat()
                else -> 0f
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Outside the padding, so the measurement is the whole slot — row plus the
                    // gap above it — which is the distance a neighbour has to travel. Taken from
                    // the first row alone: a row wearing an EDITED tag can be a shade taller, and
                    // a pitch that changed mid-drag would make the list slip under the finger.
                    .onSizeChanged { if (i == 0 && it.height > 0) slotPx = it.height }
                    .padding(top = 10.dp)
                    .zIndex(if (i == dragFrom) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .then(
                        if (ordered.size < 2) Modifier
                        else Modifier.pointerInput(row.section, ordered.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragFrom = i
                                    dragBy = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragBy += amount.y
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val to = targetOf(from, dragBy)
                                    if (from >= 0 && to >= 0 && to != from) {
                                        val next = latest.map { it.section }.toMutableList()
                                        next.add(to, next.removeAt(from))
                                        store.stageFailoverOrder(next)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    dragFrom = -1
                                    dragBy = 0f
                                },
                                onDragCancel = { dragFrom = -1; dragBy = 0f },
                            )
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(if (row.up) Wrt.Green else Wrt.TextTertiary, 6.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(row.section, style = sans(12.5f, 650))
                        if (row.primary) MonoTag("PRIMARY", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8f)
                        if (row.metricChanged) MonoTag("EDITED", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8f)
                    }
                    Text(
                        listOfNotNull(WanStore.protoLabel(row.proto).lowercase(), row.device.ifEmpty { null })
                            .joinToString(" · "),
                        style = mono(9.5f, 500, Wrt.TextDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetricBadge(row.metric)
                if (ordered.size > 1) DragGrip(held = i == dragFrom)
            }
        }
        Text(
            "$ uci set network.<wan>.metric='${WanStore.FAILOVER_STEP}'",
            style = mono(9.5f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** The metric the row's position gives it — shown because it is what lands in the config. */
@Composable
private fun MetricBadge(metric: Int) {
    Box(
        Modifier
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(7.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("$metric", style = mono(11.5f, 600, Wrt.TextSecondary))
    }
}

/** Three bars: the usual "this row can be picked up", lit while it is. */
@Composable
private fun DragGrip(held: Boolean) {
    Column(
        Modifier.width(16.dp).alpha(if (held) 1f else 0.55f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(3) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(
                        if (held) Wrt.Accent else Wrt.TextDim,
                        RoundedCornerShape(1.dp),
                    )
            )
        }
    }
}

@Composable
private fun WanChip(row: WanRow, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .border(
                1.dp,
                if (selected) Wrt.Accent.copy(alpha = 0.45f) else Wrt.BorderCard,
                RoundedCornerShape(12.dp),
            )
            .background(
                if (selected) Wrt.Accent.copy(alpha = 0.05f) else Wrt.BgCard,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusDot(if (row.up) Wrt.Green else Wrt.TextTertiary, 6.dp, pulse = row.up)
            Text(
                row.section,
                style = sans(12.5f, 650, if (row.up) Wrt.TextPrimary else Wrt.TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.primary) MonoTag("PRIMARY", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8f)
        }
        Text(
            listOfNotNull(
                if (row.primary) WanStore.protoLabel(row.proto).lowercase() else "metric ${row.metric}",
                row.device.ifEmpty { null },
            ).joinToString(" · "),
            style = mono(10f, 500, Wrt.TextDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun LiveCard(row: WanRow, live: Telemetry?) {
    val rate = live?.rates?.get(row.device)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("${row.section.uppercase()} · ${WanStore.protoLabel(row.proto).uppercase()}", size = 9.5f)
            FlexSpacer()
            StatusDot(if (row.up) Wrt.Accent else Wrt.Amber, 5.dp, pulse = row.up)
            Spacer(Modifier.width(6.dp))
            Text(
                if (row.up) "LIVE" else "DOWN",
                style = mono(9f, 600, if (row.up) Wrt.Accent else Wrt.Amber),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f)) {
                SectionLabel("PUBLIC IPV4", size = 9f)
                Text(
                    row.address.ifEmpty { "—" },
                    style = mono(13f, 600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                SectionLabel("IPV6 PREFIX (PD)", size = 9f)
                Text(
                    row.v6Prefix.ifEmpty { "—" },
                    style = mono(12f, 600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (live != null) {
            Box(Modifier.fillMaxWidth().height(46.dp).padding(top = 10.dp)) {
                // This uplink's own window. The shared live.down/up follow the primary, and
                // drawing them here put the primary's spikes under a WAN that was down.
                ThroughputChart(live.downFor(row.device), live.upFor(row.device), Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("↓ ${"%.1f".format(rate?.first ?: 0f)}", style = mono(11f, 600, Wrt.Accent))
            Spacer(Modifier.width(10.dp))
            Text("↑ ${"%.1f".format(rate?.second ?: 0f)}", style = mono(11f, 600, Wrt.Blue))
            Spacer(Modifier.width(6.dp))
            Text("Mbps · 60 s", style = mono(9.5f, 500, Wrt.TextDim))
            FlexSpacer()
            Text(WanStore.uptimeLabel(row.uptimeS), style = mono(9.5f, 500, Wrt.TextDim))
        }
    }
}

@Composable
private fun TestCard(store: WanStore, row: WanRow, onTest: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Test connection", style = sans(13f, 600))
                // Naming the bound device is the point: the result belongs to this uplink,
                // not to whichever one happens to hold the default route.
                Text(
                    "gateway, two resolvers and a name — icmp ping" +
                        row.device.takeIf { it.isNotEmpty() }?.let { " via $it" }.orEmpty(),
                    style = mono(10f, 500, Wrt.TextDim),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(
                Modifier
                    .background(if (store.testing) Wrt.BgDeep else Wrt.Accent, RoundedCornerShape(9.dp))
                    .clickable(enabled = !store.testing, onClick = onTest)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    if (store.testing) "Testing…" else "Run test",
                    style = sans(11.5f, 650, if (store.testing) Wrt.TextDim else Wrt.OnAccent),
                )
            }
        }
        if (store.pings.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                store.pings.forEach { result -> PingTile(result, Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Stop and restart, the way LuCI's interface page offers them — `ifdown` and `ifup` on this
 * one uplink. Stop takes a second tap, because on the primary uplink it is everyone's
 * internet; the app itself rides the LAN and is not at risk, which the note says outright.
 */
@Composable
private fun CycleCard(store: WanStore, row: WanRow) {
    val scope = rememberCoroutineScope()
    val busy = store.cycling != null
    val mine = store.cycling == row.section
    /** True while Stop is being held: the note below turns into the warning for that. */
    var holding by remember(row.section) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (row.up) "Restart or stop" else "Start", style = sans(13f, 600))
                Text(
                    when {
                        mine -> "waiting for netifd…"
                        row.up -> "ifup ${row.section} · ifdown ${row.section}"
                        else -> "ifup ${row.section} — the interface is down"
                    },
                    style = mono(10f, 500, Wrt.TextDim),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(
                Modifier
                    .background(if (busy) Wrt.BgDeep else Wrt.Accent, RoundedCornerShape(9.dp))
                    .clickable(enabled = !busy) { scope.launch { store.restart(row.section) } }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    when {
                        mine -> "Working…"
                        row.up -> "Restart"
                        else -> "Start"
                    },
                    style = sans(11.5f, 650, if (busy) Wrt.TextDim else Wrt.OnAccent),
                )
            }
            if (row.up) {
                HoldButton(
                    "Stop", "Hold to stop", danger = true, enabled = !busy, height = 32.dp,
                    onHoldingChange = { holding = it },
                ) { scope.launch { store.stop(row.section) } }
            }
        }
        Text(
            when {
                holding && row.primary ->
                    "This is the uplink carrying the default route: stopping it takes the " +
                        "internet away from every device until you start it again. The app keeps " +
                        "working — it reaches the router over the LAN."
                holding -> "Stopped means down until Start: no address, no route, and netifd does not retry."
                row.up -> "Restart is ifup: the address is renewed, a PPPoE session redialled, a Wi-Fi " +
                    "client re-associated. Clients see a short gap."
                else -> "The interface is down. Start runs ifup; a wired line answers in seconds, " +
                    "PPPoE and Wi-Fi clients can take longer."
            },
            style = sans(10.5f, 400, if (holding) Wrt.AmberText else Wrt.TextDim, lineHeight = 16.sp),
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun PingTile(result: PingResult, modifier: Modifier) {
    Column(
        modifier
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(result.label, style = mono(9f, 500, Wrt.TextDim), maxLines = 1)
        Text(
            when {
                result.error != null -> result.error
                result.rttMs == null -> "no reply"
                else -> "${result.rttMs.toInt()} ms · ${result.lossPct}%"
            },
            style = mono(11.5f, 600, if (result.ok) Wrt.Green else Wrt.Red),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NavRow(title: String, detail: String, divider: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = sans(13f, 600), modifier = Modifier.weight(1f))
            Text(
                detail,
                style = mono(10.5f, 500, Wrt.TextTertiary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
        }
        if (divider) com.vivekkaushik.wrtpulse.ui.HorizontalHairline()
    }
}

// ---------------------------------------------------------------------------
// Screen 27 — physical port, 802.1q VLAN, MAC, MTU
// ---------------------------------------------------------------------------

@Composable
private fun PortPage(store: WanStore) {
    // Every socket on the case, plus whatever the WAN sits on now if the case does not list
    // it — a netdev the board names oddly still has to be pickable back.
    val ports = store.sockets().let { listed ->
        if (store.port.isEmpty() || listed.any { it.id == store.port }) listed
        else listed + store.socketFor(store.port)
    }
    val tagged = store.vlanId.isNotEmpty()
    var customMtu by remember { mutableStateOf(store.mtu.isNotEmpty() && store.mtu !in WanStore.MTU_CHOICES) }
    var customMac by remember { mutableStateOf(store.macaddr.isNotEmpty()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (store.wirelessUplink) {
            // Nothing on this page applies to a radio: the device is assigned by netifd from
            // the wireless config, the tag would have nowhere to go, and the MAC belongs to
            // the radio. MTU is the one setting that still means something.
            NoteCard(
                "This uplink is a Wi-Fi client, not a socket. Its device " +
                    "(${store.portLabel.substringAfter("· ")}) comes from the wireless config, " +
                    "so the port, VLAN tag and MAC are set in Network · Wireless — or not at all."
            )
            Column {
                FieldLabel("MTU")
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    MaskChip("1500 default", store.mtu.isEmpty() || store.mtu == "1500") {
                        store.stageMtu("")
                    }
                    MaskChip("1492", store.mtu == "1492") { store.stageMtu("1492") }
                }
            }
            store.problems().forEach { ProblemCard(it) }
            return@Column
        }
        Column {
            FieldLabel("PHYSICAL PORT")
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ports.forEach { socket ->
                    MaskChip(socketChipLabel(socket), socket.id == store.port) { store.stagePort(socket.id) }
                }
            }
            val chosen = store.selectedSocket
            Text(
                when {
                    chosen?.inLan == true && store.swconfig ->
                        "${chosen.label} is in the LAN today. Applying moves it out of the LAN's " +
                            "switch VLAN into one of its own, and the WAN rides that VLAN's netdev."
                    chosen?.inLan == true ->
                        "${chosen.label} is in the LAN bridge today. Applying takes it out of the " +
                            "bridge so it can carry the WAN."
                    else -> "The socket the ISP is plugged into."
                },
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        ToggleCard {
            ToggleRow(
                title = "ISP requires VLAN tagging",
                body = "802.1q — common on FTTH, where the ISP hands you a tagged line",
                checked = tagged,
                divider = tagged,
            ) {
                store.stageVlan(if (tagged) "" else "100")
            }
            if (tagged) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    FieldLabel("VLAN ID")
                    FormTextField(store.vlanId, { store.stageVlan(it.filter { c -> c.isDigit() }.take(4)) })
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("802.1P PRIORITY (PCP)")
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        listOf("0", "1", "3", "5", "7").forEach { value ->
                            MaskChip(value, store.pcp == value) { store.stagePcp(value) }
                        }
                    }
                    Text(
                        "maps to ${store.deviceName} · written as " +
                            "egress_qos_mapping '0:${store.pcp}'",
                        style = mono(10f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
        ToggleCard {
            ToggleRow(
                title = "Custom / clone MAC",
                // The design offers "clone this phone's MAC". Android has not handed out its
                // own MAC since 6.0 — it answers 02:00:00:00:00:00 — so offering it would be
                // offering a value that cannot work.
                body = "Some ISPs bind the line to the first MAC they see. Android will not " +
                    "reveal this phone's own MAC, so type the one the ISP expects.",
                checked = customMac,
                divider = customMac,
            ) {
                customMac = !customMac
                if (!customMac) store.stageMac("")
            }
            if (customMac) {
                Column(Modifier.padding(bottom = 12.dp)) {
                    FormTextField(store.macaddr, { store.stageMac(it.lowercase()) })
                    Text(
                        "aa:bb:cc:dd:ee:ff — the first octet has to be even.",
                        style = sans(10.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Column {
            FieldLabel("MTU")
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MaskChip("1500 default", store.mtu.isEmpty() || store.mtu == "1500") {
                    customMtu = false; store.stageMtu("")
                }
                MaskChip("1492 pppoe", store.mtu == "1492") { customMtu = false; store.stageMtu("1492") }
                MaskChip("Custom", customMtu) { customMtu = true }
            }
            if (customMtu) FormTextField(store.mtu, { store.stageMtu(it.filter { c -> c.isDigit() }.take(5)) })
            Text(
                "Standard ethernet is 1500 — PPPoE frames need 1492 for the 8 bytes of header.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        store.problems().forEach { ProblemCard(it) }
        Text(
            "$ uci set network.${store.selected}.device='${store.deviceName}'",
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Screen 28 — the IPv4 protocol
// ---------------------------------------------------------------------------

@Composable
private fun Ipv4Page(store: WanStore) {
    val config = store.current
    val proto = store.proto

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            store.protoChoices().take(4).forEach { (name, available) ->
                ProtoChip(name, name == proto, available) { if (available) store.stageProto(name) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            store.protoChoices().drop(4).forEach { (name, available) ->
                ProtoChip(name, name == proto, available) { if (available) store.stageProto(name) }
            }
        }
        Text(
            "Switching protocol keeps the port, VLAN and MAC settings — those belong to the " +
                "device, not the protocol.",
            style = sans(11f, 400, Wrt.TextSecondary, lineHeight = 17.sp),
        )
        store.protoChoices().firstOrNull { !it.second && it.first == proto }?.let { (name, _) ->
            NoteCard(
                "${WanStore.protoLabel(name)} needs a package this router does not have. " +
                    "Install ${WanStore.protoPackage(name)} from System · Packages first — " +
                    "netifd can only run a protocol whose handler is installed."
            )
        }
        when (proto) {
            "pppoe" -> {
                Column {
                    FieldLabel("USERNAME")
                    FormTextField(
                        store.option("username", config?.username.orEmpty()),
                        { store.stageOption("username", config?.username.orEmpty(), it) },
                    )
                }
                Column {
                    FieldLabel("PASSWORD")
                    FormTextField(
                        store.option("password", config?.password.orEmpty()),
                        { store.stageOption("password", config?.password.orEmpty(), it) },
                        password = true,
                    )
                    Text(
                        "Written to /etc/config/network on the router, and masked in the review " +
                            "sheet. It is stored there in the clear, as OpenWrt keeps it.",
                        style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Column {
                    FieldLabel("SERVICE NAME · OPTIONAL")
                    FormTextField(
                        store.option("service", config?.serviceName.orEmpty()),
                        { store.stageOption("service", config?.serviceName.orEmpty(), it) },
                    )
                }
                Column {
                    FieldLabel("KEEPALIVE")
                    FormTextField(
                        store.option("keepalive", config?.keepalive.orEmpty()),
                        { store.stageOption("keepalive", config?.keepalive.orEmpty(), it) },
                    )
                    Text(
                        "Failures,interval — OpenWrt's default is 5,1. Empty leaves it out.",
                        style = sans(10.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            "static" -> {
                Column {
                    FieldLabel("IPV4 ADDRESS")
                    FormTextField(
                        store.option("ipaddr", config?.ipaddr.orEmpty()),
                        { store.stageOption("ipaddr", config?.ipaddr.orEmpty(), it) },
                    )
                }
                Column {
                    FieldLabel("NETMASK")
                    FormTextField(
                        store.option("netmask", config?.netmask.orEmpty()),
                        { store.stageOption("netmask", config?.netmask.orEmpty(), it) },
                    )
                }
                Column {
                    FieldLabel("GATEWAY")
                    FormTextField(
                        store.option("gateway", config?.gateway.orEmpty()),
                        { store.stageOption("gateway", config?.gateway.orEmpty(), it) },
                    )
                }
            }
            "dhcp" -> NoteCard(
                "DHCP takes the address, gateway and resolvers from the ISP. Nothing else to set."
            )
            else -> if (proto.isNotEmpty() && !store.protoEditable(proto)) {
                NoteCard(
                    "${WanStore.protoLabel(proto)} has settings this screen cannot write yet, " +
                        "so it is shown rather than edited. The Terminal tab can set them."
                )
            }
        }
        store.problems().forEach { ProblemCard(it) }
        Text(
            "$ uci set network.${store.selected}.proto='$proto'",
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun ProtoChip(name: String, selected: Boolean, available: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        Modifier
            .let {
                when {
                    selected -> it.background(Wrt.Accent, shape)
                    available -> it.border(1.dp, Wrt.BorderCard, shape)
                    else -> it.border(1.dp, Wrt.BorderHair, shape)
                }
            }
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Text(
            WanStore.protoLabel(name),
            style = mono(
                10.5f,
                if (selected) 600 else 500,
                when {
                    selected -> Wrt.OnAccent
                    available -> Wrt.TextSecondary
                    else -> Wrt.TextFaint
                },
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Screen 29 — IPv6
// ---------------------------------------------------------------------------

@Composable
private fun Ipv6Page(store: WanStore) {
    val mode = store.v6Mode
    val link = store.links.firstOrNull { it.name == store.v6Section || it.name == store.selected }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToggleCard {
            ToggleRow(
                title = "Enable IPv6 uplink",
                body = link?.v6Prefix?.takeIf { it.isNotEmpty() }?.let { "delegated $it" }
                    ?: "no prefix delegated yet",
                checked = mode != V6Mode.Off,
                divider = false,
            ) {
                store.stageV6Mode(if (mode == V6Mode.Off) V6Mode.Native else V6Mode.Off)
            }
        }
        if (mode != V6Mode.Off) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                V6Mode.entries.filterNot { it == V6Mode.Off }.forEach { option ->
                    val available = when (option) {
                        V6Mode.PppoeDual -> store.proto == "pppoe"
                        V6Mode.SixToFour -> store.protoAvailable("6to4")
                        else -> true
                    }
                    ModeRow(
                        title = option.label,
                        body = when {
                            option == V6Mode.PppoeDual && !available ->
                                "Only for a PPPoE line — this WAN is ${WanStore.protoLabel(store.proto)}"
                            option == V6Mode.SixToFour && !available ->
                                "Needs the 6to4 package, which this router does not have"
                            else -> option.body
                        },
                        selected = option == mode,
                        enabled = available,
                    ) { store.stageV6Mode(option) }
                }
            }
            Column {
                FieldLabel("PREFIX DELEGATION SIZE")
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    WanStore.PD_CHOICES.forEach { size ->
                        MaskChip(if (size == "auto") "Auto" else "/$size", store.pdSize == size) {
                            store.stagePdSize(size)
                        }
                    }
                }
                Text(
                    "The block of addresses asked of the ISP, to split across local subnets. " +
                        "Auto takes whatever the ISP offers.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Column {
                FieldLabel("LAN ADDRESSING")
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanV6.entries.forEach { option ->
                        ModeRow(
                            title = option.label,
                            body = option.body,
                            selected = option == store.lanV6,
                            enabled = true,
                            trailing = if (option == LanV6.Auto) "RECOMMENDED" else null,
                        ) { store.stageLanV6(option) }
                    }
                }
                Text(
                    "This half is odhcpd's, in /etc/config/dhcp — the review sheet commits both.",
                    style = sans(10.5f, 400, Wrt.TextDim),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        store.problems().forEach { ProblemCard(it) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ModeRow(
    title: String,
    body: String,
    selected: Boolean,
    enabled: Boolean,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (selected) Wrt.Accent.copy(alpha = 0.45f) else Wrt.BorderCard,
                RoundedCornerShape(12.dp),
            )
            .background(
                if (selected) Wrt.Accent.copy(alpha = 0.05f) else Wrt.BgCard,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .border(
                    1.5.dp,
                    if (selected) Wrt.Accent else Wrt.BorderInput,
                    RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(Wrt.Accent, RoundedCornerShape(50)))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    title,
                    style = sans(13f, 600, if (enabled) Wrt.TextPrimary else Wrt.TextDim),
                )
                trailing?.let { MonoTag(it, Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8f) }
            }
            Text(
                body,
                style = sans(10.5f, 400, if (enabled) Wrt.TextDim else Wrt.TextFaint, lineHeight = 15.sp),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 30 — apply & connect, with the rollback armed
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// A new wired uplink
// ---------------------------------------------------------------------------

/** "Port 5 · 1G · in LAN" — what a socket chip says. */
private fun socketChipLabel(socket: Socket): String = listOfNotNull(
    socket.label,
    socket.speedMbps?.let { if (it >= 1000) "${it / 1000}G" else "${it}M" } ?: if (socket.up) "up" else "down",
    if (socket.inLan) "in LAN" else null,
).joinToString(" · ")

/**
 * The hub's way in. The design draws "Add WAN"; this is the wired half of it — a socket the
 * LAN gives up, an interface on it, and the firewall's wan zone. A Wi-Fi client uplink is
 * still made in Network · Wireless, where the radio is.
 */
@Composable
private fun AddUplinkCard(store: WanStore, onAdd: () -> Unit) {
    val used = store.usedSockets()
    val free = store.sockets().filterNot { it.id in used }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp),
    ) {
        NavRow(
            "Add wired uplink",
            when {
                !store.loaded -> ""
                free.isEmpty() -> "no free socket"
                else -> free.joinToString(", ") { it.label }
            },
            divider = true,
        ) { if (free.isNotEmpty()) onAdd() }
        Text(
            "Puts the ISP on an ethernet socket: the socket leaves the LAN, a new interface is " +
                "created on it, and the firewall's wan zone takes it. Failover between the uplinks " +
                "that exist is the metric above; health-checked failover is mwan3, which the app " +
                "does not configure.",
            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
            modifier = Modifier.padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun AddWiredUplinkSheet(
    store: WanStore,
    onCancel: () -> Unit,
    onCreate: (Socket, String) -> Unit,
) {
    val sockets = store.sockets()
    val used = store.usedSockets()
    // The socket with a live link that no uplink owns is almost always the one the cable was
    // just plugged into, so it starts selected.
    var chosenId by remember {
        mutableStateOf(
            sockets.firstOrNull { it.id !in used && it.up && store.uplinkBlock(it) == null }?.id
                ?: sockets.firstOrNull { it.id !in used && store.uplinkBlock(it) == null }?.id,
        )
    }
    var proto by remember { mutableStateOf("dhcp") }
    val chosen = sockets.firstOrNull { it.id == chosenId }
    val name = store.nextUplinkName()

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Text("Add wired uplink", style = sans(16f, 650), modifier = Modifier.padding(top = 14.dp))
        Text(
            "Plug the ISP into a socket and pick it here. The link is the surest tell: the " +
                "socket that comes up with the cable in is the one.",
            style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.padding(top = 14.dp)) {
            FieldLabel("SOCKET")
            if (sockets.isEmpty()) {
                Text(
                    "The case reports no ethernet sockets.",
                    style = sans(11f, 400, Wrt.TextDim),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                sockets.forEach { socket ->
                    val taken = socket.id in used
                    MaskChip(
                        socketChipLabel(socket) + if (taken) " · WAN" else "",
                        socket.id == chosenId,
                    ) { if (!taken) chosenId = socket.id }
                }
            }
        }
        Column(Modifier.padding(top = 14.dp)) {
            FieldLabel("IPV4 PROTOCOL")
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                store.protoChoices().filter { it.first in setOf("dhcp", "pppoe", "static") }
                    .forEach { (p, available) ->
                        ProtoChip(p, p == proto, available) { if (available) proto = p }
                    }
            }
            Text(
                when (proto) {
                    "pppoe" -> "Username and password go in on the IPv4 page once the uplink exists."
                    "static" -> "Address, gateway and DNS go in on the IPv4 page once the uplink exists."
                    else -> "Most ISP routers hand out an address by DHCP."
                },
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        chosen?.let { socket ->
            Column(Modifier.padding(top = 14.dp)) {
                FieldLabel("WHAT CHANGES")
                store.wiredUplinkPreview(socket, proto).forEach { line ->
                    Text(
                        "· $line",
                        style = sans(11.5f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (chosen != null && store.uplinkBlock(chosen) == null) {
            PrimaryButton("Stage $name on ${chosen.label}") { onCreate(chosen, proto) }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (chosen != null) "Not from here — see above" else "Pick a socket",
                    style = sans(13.5f, 650, Wrt.TextDim),
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp).clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", style = sans(13f, 600, Wrt.TextSecondary))
        }
    }
}

@Composable
private fun WanReviewSheet(store: WanStore?, onApply: () -> Unit, onRevertAll: () -> Unit) {
    if (store == null) return
    val problems = store.problems()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Review gateway changes", style = sans(16f, 650))
            FlexSpacer()
            Text("uci batch · ${store.ops().size} ops", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            store.packages().forEach { pkg ->
                Text("# $pkg", style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
                store.diffLines()
                    .filter { it.first.substringAfter(' ').startsWith("$pkg.") }
                    .forEach { (line, added) ->
                        Text(
                            line,
                            style = mono(11f, 500, if (added) Wrt.Green else Wrt.Red, lineHeight = 19.sp),
                        )
                    }
                Spacer(Modifier.height(8.dp))
            }
            Text(store.commitLine(), style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
        }
        store.notes().forEach { note ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                    .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Amber)
                Text(note, style = sans(12f, 400, Wrt.AmberText, lineHeight = 18.sp))
            }
        }
        // The rollback is the whole reason this screen can offer the change at all.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WrtIcons.Shield, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Accent)
            Text(
                "Rollback armed — the router keeps a copy of /etc/config/network and puts it " +
                    "back unless WrtPulse reaches it again within " +
                    "${if (store.touchesSwitch()) WanStore.SWITCH_ROLLBACK_SECONDS else WanStore.ROLLBACK_SECONDS} s. " +
                    "Re-reading the config is what confirms it, not the command returning 0.",
                style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
            )
        }
        problems.forEach { ProblemCard(it, top = 10.dp) }
        store.error?.let {
            Text(
                it,
                style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        val label = if (store.applying) "Applying…" else "Apply & Connect"
        if (problems.isEmpty()) {
            PrimaryButton(label, onClick = onApply)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = sans(13.5f, 650, Wrt.TextDim))
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(42.dp).clickable(onClick = onRevertAll),
            contentAlignment = Alignment.Center,
        ) {
            Text("Discard all", style = sans(13f, 600, Wrt.Red))
        }
        Text(
            "Nothing runs until you apply.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
