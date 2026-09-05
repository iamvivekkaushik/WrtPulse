package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.DmzDraft
import com.vivekkaushik.wrtpulse.data.FirewallStore
import com.vivekkaushik.wrtpulse.data.ForwardDraft
import com.vivekkaushik.wrtpulse.data.RuleDraft
import com.vivekkaushik.wrtpulse.ops.Lease
import com.vivekkaushik.wrtpulse.ops.Parsers.FwForward
import com.vivekkaushik.wrtpulse.ops.Parsers.FwRule
import com.vivekkaushik.wrtpulse.ops.Parsers.FwZone
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Firewall & security — design screens 31 to 37
// ---------------------------------------------------------------------------

private enum class FwTab(val label: String) {
    Forwards("Forwards"), Rules("Rules"), Zones("Zones"), Dmz("DMZ"), Defaults("Defaults")
}

private sealed interface FwRoute {
    data object Hub : FwRoute
    data class Tabs(val tab: FwTab) : FwRoute
    data class Forward(val draft: ForwardDraft) : FwRoute
    data class Rule(val draft: RuleDraft) : FwRoute
}

/** The firewall section, with its own stack so back walks the flow rather than leaving it. */
@Composable
fun FirewallSection(store: FirewallStore?, latencyMs: Int, onBack: () -> Unit, onFullScreen: (Boolean) -> Unit) {
    val stack = remember { mutableStateListOf<FwRoute>(FwRoute.Hub) }
    val route = stack.last()
    fun push(r: FwRoute) = stack.add(r)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    // The system back has to walk this stack too, or a form drops the user at the Network
    // home with the hub and the tabs skipped. Wan.kt does the same for its pages.
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }
    LaunchedEffect(route) { onFullScreen(route is FwRoute.Forward || route is FwRoute.Rule) }
    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    when (val r = route) {
        FwRoute.Hub -> FirewallHub(store, latencyMs, onBack = onBack) { push(FwRoute.Tabs(it)) }
        is FwRoute.Tabs -> FirewallTabs(
            store = store,
            latencyMs = latencyMs,
            initial = r.tab,
            onBack = { pop() },
            onAddForward = { push(FwRoute.Forward(it)) },
            onAddRule = { push(FwRoute.Rule(it)) },
        )
        is FwRoute.Forward -> if (store != null) AddForwardScreen(store, r.draft, onBack = { pop() }) else pop()
        is FwRoute.Rule -> if (store != null) AddRuleScreen(store, r.draft, onBack = { pop() }) else pop()
    }
}

// ---------------------------------------------------------------------------
// 31 · Hub
// ---------------------------------------------------------------------------

@Composable
private fun FirewallHub(store: FirewallStore?, latencyMs: Int, onBack: () -> Unit, onOpen: (FwTab) -> Unit) {
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Firewall & security", onBack) { LatencyTag(latencyMs) }
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to manage its firewall.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EngineCard(store)
            store.notice?.let { NoticeLine(it, if (store.rolledBack) Wrt.Amber else Wrt.Accent) }
            store.error?.let { ProblemCard(it) }
            val forwards = store.forwardRows()
            val rules = store.ruleRows()
            val zones = store.zoneRows()
            val defaults = store.defaults()
            val dmz = store.dmz()
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                HubRow(WrtIcons.Forwarding, "Port forwarding", "${forwards.count { it.enabled }} active · DNAT") { onOpen(FwTab.Forwards) }
                HairDivider()
                HubRow(
                    WrtIcons.Rules, "Traffic rules",
                    "${rules.size} rule${if (rules.size == 1) "" else "s"}" +
                        rules.count { it.scheduled }.takeIf { it > 0 }?.let { " · $it scheduled" }.orEmpty(),
                ) { onOpen(FwTab.Rules) }
                HairDivider()
                HubRow(
                    WrtIcons.Zones, "Zones & NAT",
                    "${zones.size} zone${if (zones.size == 1) "" else "s"}" +
                        zones.filter { it.masq }.takeIf { it.isNotEmpty() }?.let { " · masq on ${it.joinToString(", ") { z -> z.name }}" }.orEmpty(),
                ) { onOpen(FwTab.Zones) }
                HairDivider()
                HubRow(
                    WrtIcons.ShieldOff, "DMZ — exposed host",
                    if (dmz.enabled) "on · ${dmz.targetIp}" else "off",
                    subtitleColor = if (dmz.enabled) Wrt.Red else Wrt.TextDim,
                ) { onOpen(FwTab.Dmz) }
                HairDivider()
                HubRow(
                    WrtIcons.Defaults, "Defaults & DoS defense",
                    "input ${defaults.input.lowercase()} · syn-flood ${if (defaults.synFlood) "on" else "off"}",
                    last = true,
                ) { onOpen(FwTab.Defaults) }
            }
            CommandLine("$ ubus call service list '{\"name\":\"firewall\"}'")
        }
    }
}

@Composable
private fun EngineCard(store: FirewallStore) {
    val engine = store.engine
    val defaults = store.defaults()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val running = engine?.running == true
            StatusDot(if (running) Wrt.Green else Wrt.Amber, 7.dp, pulse = running)
            Text(
                when {
                    engine == null -> if (store.loading) "Reading…" else "Engine state unknown"
                    running -> "Engine active"
                    else -> "Engine stopped"
                },
                style = sans(13.5f, 650),
            )
            engine?.let { MonoTag(if (it.engine == "fw4") "fw4 · nftables" else "fw3 · iptables", size = 9f) }
            FlexSpacer()
            engine?.reloadedAgoSec?.let { Text("reload ${ago(it)}", style = mono(9.5f, 500, Wrt.TextDim)) }
        }
        Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("SYN-FLOOD", if (defaults.synFlood) "protected" else "off", if (defaults.synFlood) Wrt.Green else Wrt.Amber, Modifier.weight(1f))
            StatTile("WAN PING", if (store.wanPing()) "answers" else "hidden", if (store.wanPing()) Wrt.Amber else Wrt.Green, Modifier.weight(1f))
            StatTile("FORWARDS", "${store.forwardRows().count { it.enabled }} active", Wrt.TextPrimary, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = mono(9f, 500, Wrt.TextDim))
        Text(value, style = mono(11.5f, 600, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: Color = Wrt.TextDim,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Wrt.Accent)
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600))
            Text(subtitle, style = sans(10.5f, 400, subtitleColor), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
}

// ---------------------------------------------------------------------------
// The tabbed body shared by 32 · 34 · 35 · 36 · 37
// ---------------------------------------------------------------------------

@Composable
private fun FirewallTabs(
    store: FirewallStore?,
    latencyMs: Int,
    initial: FwTab,
    onBack: () -> Unit,
    onAddForward: (ForwardDraft) -> Unit,
    onAddRule: (RuleDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(initial) }
    var reviewOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Firewall & security", onBack) { LatencyTag(latencyMs) }
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to manage its firewall.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FwTab.entries.forEach { entry ->
                FilterChip(entry.label, tab == entry, size = 11f, padH = 11.dp, padV = 5.dp) { tab = entry }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                FwTab.Forwards -> ForwardsTab(
                    store,
                    onAdd = { onAddForward(store.newForwardDraft()) },
                    // An edit already staged reopens as itself, so a second edit does not stack.
                    onEdit = { onAddForward(store.forwardDraftFor(it.section) ?: store.newForwardDraft(it)) },
                )
                FwTab.Rules -> RulesTab(store) { onAddRule(store.newRuleDraft()) }
                FwTab.Zones -> ZonesTab(store)
                FwTab.Dmz -> DmzTab(store)
                FwTab.Defaults -> DefaultsTab(store)
            }
        }
        if (store.pendingCount > 0) {
            val dmzOn = store.dmzDraft?.enabled == true
            FormActionBar(
                pendingCount = store.pendingCount,
                countLabel = if (dmzOn) "Exposes a host" else "Unsaved firewall rules",
                saveLabel = "Review & Apply",
                saveEnabled = true,
                onCancel = { store.revert() },
                onSave = { reviewOpen = true },
            )
        }
    }
    SheetHost(visible = reviewOpen, onDismiss = { reviewOpen = false }) {
        FirewallReviewSheet(
            store = store,
            onApply = { scope.launch { if (store!!.apply()) reviewOpen = false } },
            onRevertAll = { store?.revert(); reviewOpen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// 32 · Port forwards
// ---------------------------------------------------------------------------

@Composable
private fun ForwardsTab(store: FirewallStore, onAdd: () -> Unit, onEdit: (FwForward) -> Unit) {
    val rows = store.forwardRows()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (rows.isEmpty() && store.loaded) {
            EmptyCard("No port forwards. Nothing from the WAN reaches a LAN device until one is added.")
        }
        rows.forEach { f ->
            val isDraft = store.forwardDraftFor(f.section) != null
            SwipeToReveal(
                actions = listOf(
                    RevealAction("Edit", WrtIcons.Pencil, Wrt.TextSecondary) { onEdit(f) },
                    RevealAction("Delete", WrtIcons.Trash, Wrt.Red) { store.removeForward(f.section) },
                ),
                resetKey = f.section,
                base = Wrt.BgCard,
            ) { modifier ->
                ForwardCard(f, modifier, changed = isDraft || "firewall.${f.section}.enabled" in store.staged) {
                    if (!isDraft) store.toggleForward(f.section)
                }
            }
        }
        Text(
            "Swipe left for edit · delete. Toggles apply after review.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = TextAlign.Center,
        )
        DashedAction("Add port forward", onAdd)
    }
}

@Composable
private fun ForwardCard(f: FwForward, modifier: Modifier, changed: Boolean, onToggle: () -> Unit) {
    val on = f.enabled
    val text = if (on) Wrt.TextPrimary else Wrt.TextSecondary
    val dim = if (on) Wrt.TextTertiary else Wrt.TextDim
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, if (changed) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(f.name.ifEmpty { f.section }, style = sans(13.5f, 650, text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            MonoTag(protoLabel(f.proto), color = dim, size = 8.5f)
            FlexSpacer()
            WToggle(on, onToggle)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${f.src} :${f.srcPort}", style = mono(11f, 500, dim))
            Icon(WrtIcons.ArrowRight, null, Modifier.size(13.dp), tint = if (on) Wrt.Accent else Wrt.BorderInput)
            Text(
                (f.destIp.ifEmpty { "this router" }) + " :" + f.destPort.ifEmpty { f.srcPort },
                style = mono(11f, 500, text),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 33 · Add forward — collision guard
// ---------------------------------------------------------------------------

@Composable
private fun AddForwardScreen(store: FirewallStore, initial: ForwardDraft, onBack: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var pickOpen by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val problem = store.forwardProblem(draft)
    val portTaken = draft.srcPort.trim().toIntOrNull()?.let { it in store.listening } == true
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Firewall & security", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (draft.replaces != null) "Edit port forward" else "Add port forward", style = sans(16f, 650))
                FlexSpacer()
                Text("Presets", style = sans(11f, 600, Wrt.Accent))
            }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FirewallStore.PRESETS.forEach { (name, port) ->
                    val on = draft.srcPort == "$port"
                    FilterChip("$name $port", on, size = 9.5f, padH = 9.dp, padV = 4.dp, mono = true) {
                        draft = draft.copy(
                            srcPort = "$port", destPort = "",
                            name = draft.name.ifEmpty { name.lowercase() },
                            proto = if (name == "WireGuard") "udp" else "tcp",
                        )
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.5f)) {
                    FieldLabel("NAME")
                    FormTextField(draft.name, { draft = draft.copy(name = it) })
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("PROTOCOL")
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FirewallStore.PROTOCOLS.forEach { (value, label) ->
                            SegmentCell(label, draft.proto == value, Modifier.weight(if (value == "tcp udp") 1.2f else 1f)) { draft = draft.copy(proto = value) }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("EXTERNAL PORT")
                    FormTextField(draft.srcPort, { draft = draft.copy(srcPort = it.filter(Char::isDigit).take(5)) }) {
                        if (portTaken) Icon(WrtIcons.Warning, null, Modifier.size(14.dp), tint = Wrt.Amber)
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("INTERNAL PORT")
                    FormTextField(draft.destPort, { draft = draft.copy(destPort = it.filter(Char::isDigit).take(5)) }) {
                        if (draft.destPort.isEmpty() || draft.destPort == draft.srcPort) Text("= external", style = mono(8.5f, 500, Wrt.TextDim))
                    }
                }
            }
            if (portTaken) {
                val port = draft.srcPort.toInt()
                val suggestion = store.suggestPort(port)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp)
                        .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                        .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        if (port == FirewallStore.SSH_PORT) "Port $port is the router's own SSH (Dropbear). Forwarding it will lock this app out — pick another external port."
                        else "Port $port is one the router itself listens on — forwarding it takes it from the router.",
                        style = sans(11f, 400, Wrt.AmberText, lineHeight = 16.sp),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "use $suggestion",
                        style = mono(10f, 600, Wrt.Amber),
                        modifier = Modifier.clickable {
                            // The service still lives on its own port inside; only the knock moves.
                            draft = draft.copy(srcPort = "$suggestion", destPort = draft.destPort.ifEmpty { "$port" })
                        },
                    )
                }
            }
            Column(Modifier.padding(top = 11.dp)) {
                FieldLabel("INTERNAL IP")
                FormTextField(draft.destIp, { draft = draft.copy(destIp = it.filter { c -> c.isDigit() || c == '.' }) })
                Row(
                    Modifier.padding(top = 7.dp).clickable { pickOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(WrtIcons.WiredDevice, null, Modifier.size(12.dp), tint = Wrt.Accent)
                    Text("Pick connected device", style = sans(11.5f, 600, Wrt.Accent))
                    Text("· from dhcp.leases", style = mono(9.5f, 500, Wrt.TextDim))
                }
            }
            if (attempted && problem != null && !portTaken) ProblemCard(problem, top = 10.dp)
            Spacer(Modifier.height(13.dp))
            PrimaryButton(if (draft.replaces != null) "Stage change" else "Stage forward") {
                attempted = true
                if (store.stageForward(draft) == null) onBack()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
    SheetHost(visible = pickOpen, onDismiss = { pickOpen = false }) {
        LeasePicker(store.leases, onPick = { draft = draft.copy(destIp = it.ip, name = draft.name.ifEmpty { it.hostname.orEmpty() }); pickOpen = false }) { pickOpen = false }
    }
}

@Composable
private fun LeasePicker(leases: List<Lease>, onPick: (Lease) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Connected devices", style = sans(16f, 650))
            FlexSpacer()
            Text("dhcp.leases · ${leases.size}", style = mono(10.5f, 500, Wrt.TextDim))
        }
        if (leases.isEmpty()) {
            Text("No leases — the router's DHCP has handed out nothing.", style = sans(12f, 400, Wrt.TextDim), modifier = Modifier.padding(vertical = 18.dp))
        }
        Column(Modifier.padding(top = 10.dp).verticalScroll(rememberScrollState())) {
            leases.sortedBy { it.hostname ?: "~" }.forEachIndexed { i, lease ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(lease) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(WrtIcons.WiredDevice, null, Modifier.size(15.dp), tint = Wrt.TextTertiary)
                    Column(Modifier.weight(1f)) {
                        Text(lease.hostname?.ifBlank { null } ?: "unnamed", style = sans(13f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lease.mac, style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(lease.ip, style = mono(12f, 600, Wrt.Accent))
                }
                if (i != leases.lastIndex) HairDivider()
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Text("Cancel", style = sans(13f, 600, Wrt.TextSecondary))
        }
    }
}

// ---------------------------------------------------------------------------
// 34 · Traffic rules — schedule
// ---------------------------------------------------------------------------

private enum class RuleFilter(val label: String) { WanIn("WAN in"), LanOut("LAN out"), Custom("Custom") }

@Composable
private fun RulesTab(store: FirewallStore, onAdd: () -> Unit) {
    var filter by remember { mutableStateOf(RuleFilter.WanIn) }
    var open by remember { mutableStateOf<String?>(null) }
    val all = store.ruleRows()
    val rows = all.filter {
        when (filter) {
            RuleFilter.WanIn -> it.src == "wan" && it.dest.isEmpty()
            RuleFilter.LanOut -> it.src == "lan" || (it.src.isNotEmpty() && it.src != "wan" && it.dest == "wan")
            RuleFilter.Custom -> !(it.src == "wan" && it.dest.isEmpty()) && !(it.src == "lan" || (it.src.isNotEmpty() && it.src != "wan" && it.dest == "wan"))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .padding(3.dp),
        ) {
            RuleFilter.entries.forEach { f ->
                val n = all.count { r ->
                    when (f) {
                        RuleFilter.WanIn -> r.src == "wan" && r.dest.isEmpty()
                        RuleFilter.LanOut -> r.src == "lan" || (r.src.isNotEmpty() && r.src != "wan" && r.dest == "wan")
                        RuleFilter.Custom -> true
                    }
                }
                val blocking = f == RuleFilter.LanOut && rows.any { it.target != "ACCEPT" } && filter == f
                Box(
                    Modifier
                        .weight(if (filter == f) 1.3f else 1f)
                        .height(28.dp)
                        .background(if (filter == f) Wrt.BgCardDim else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { filter = f },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (filter == f && blocking) "${f.label} · blocking" else if (f == RuleFilter.Custom) f.label else "${f.label}${if (filter != f && n > 0) " $n" else ""}",
                        style = sans(11f, if (filter == f) 600 else 500, if (filter == f) Wrt.TextPrimary else Wrt.TextDim),
                        maxLines = 1,
                    )
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (rows.isEmpty() && store.loaded) {
                EmptyCard(
                    when (filter) {
                        RuleFilter.WanIn -> "No rules on WAN input — the zone's own policy decides."
                        RuleFilter.LanOut -> "Nothing blocked from the LAN outwards."
                        RuleFilter.Custom -> "No zone-to-zone or device rules."
                    }
                )
            }
            rows.forEach { r ->
                val isDraft = store.ruleDraftFor(r.section) != null
                SwipeToReveal(
                    actions = listOf(
                        RevealAction("Delete", WrtIcons.Trash, Wrt.Red) { store.removeRule(r.section) },
                    ),
                    resetKey = r.section,
                    base = Wrt.BgCard,
                ) { modifier ->
                    RuleCard(
                        r, modifier,
                        changed = isDraft || "firewall.${r.section}.enabled" in store.staged,
                        expanded = open == r.section,
                        onExpand = { open = if (open == r.section) null else r.section },
                        onToggle = { if (!isDraft) store.toggleRule(r.section) },
                    )
                }
            }
            DashedAction("Create traffic rule", onAdd)
        }
    }
}

@Composable
private fun RuleCard(r: FwRule, modifier: Modifier, changed: Boolean, expanded: Boolean, onExpand: () -> Unit, onToggle: () -> Unit) {
    val on = r.enabled
    val text = if (on) Wrt.TextPrimary else Wrt.TextSecondary
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, if (changed || r.scheduled) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.name.ifEmpty { r.section }, style = sans(13.5f, 650, text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TargetBadge(r.target, on)
            WToggle(on, onToggle)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOfNotNull(r.src.ifEmpty { "device" }, r.srcIp.ifEmpty { null }).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(WrtIcons.ArrowRight, null, Modifier.size(12.dp), tint = Wrt.TextDim)
            Text(
                listOfNotNull(
                    r.dest.ifEmpty { "device" },
                    r.destIp.ifEmpty { null },
                    listOfNotNull(r.proto.ifEmpty { null }, r.destPort.ifEmpty { null }).joinToString(" ").ifEmpty { "any port" },
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (r.scheduled) {
            Column(Modifier.padding(top = 11.dp)) {
                HairDivider()
                Row(Modifier.padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(WrtIcons.Clock, null, Modifier.size(13.dp), tint = Wrt.Accent)
                    SectionLabel("SCHEDULE", size = 9.5f)
                    FlexSpacer()
                    Icon(if (expanded) WrtIcons.ChevronUp else WrtIcons.ChevronDown, null, Modifier.size(12.dp), tint = Wrt.TextDim)
                }
                if (expanded) {
                    Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FirewallStore.WEEKDAYS.forEach { d ->
                            val sel = r.weekdays.isEmpty() || d in r.weekdays
                            Box(
                                Modifier.weight(1f).height(30.dp)
                                    .background(if (sel) Wrt.Accent else Color.Transparent, RoundedCornerShape(7.dp))
                                    .border(1.dp, if (sel) Color.Transparent else Wrt.BorderInput, RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text(d.take(2), style = mono(9f, 600, if (sel) Wrt.OnAccent else Wrt.TextDim)) }
                        }
                    }
                    Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeTile("FROM", r.startTime.ifEmpty { "00:00" }, Modifier.weight(1f))
                        TimeTile("TO", r.stopTime.ifEmpty { "24:00" }, Modifier.weight(1f))
                    }
                    Text(
                        "→ " + listOfNotNull(
                            r.weekdays.takeIf { it.isNotEmpty() }?.let { "option weekdays '${it.joinToString(" ")}'" },
                            r.startTime.takeIf { it.isNotEmpty() }?.let { "start_time '$it'" },
                            r.stopTime.takeIf { it.isNotEmpty() }?.let { "stop_time '$it'" },
                        ).joinToString(" · "),
                        style = mono(9.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        listOfNotNull(
                            r.weekdays.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.take(2) },
                            listOfNotNull(r.startTime.ifEmpty { null }, r.stopTime.ifEmpty { null }).joinToString("–").ifEmpty { null },
                        ).joinToString(" · "),
                        style = mono(10f, 500, Wrt.TextTertiary),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeTile(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .height(38.dp)
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(9.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = mono(9f, 500, Wrt.TextDim))
        Text(value, style = mono(12f, 600))
    }
}

@Composable
private fun TargetBadge(target: String, on: Boolean) {
    val (label, color) = when (target.uppercase()) {
        "ACCEPT" -> "ALLOW" to Wrt.Green
        "REJECT" -> "REJECT" to Wrt.Amber
        else -> "BLOCK" to Wrt.Red
    }
    val tint = if (on) color else Wrt.TextDim
    Text(
        label,
        style = mono(8.5f, 600, tint),
        modifier = Modifier
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.5.dp),
    )
}

// ---------------------------------------------------------------------------
// Add rule
// ---------------------------------------------------------------------------

@Composable
private fun AddRuleScreen(store: FirewallStore, initial: RuleDraft, onBack: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var attempted by remember { mutableStateOf(false) }
    var scheduled by remember { mutableStateOf(initial.startTime.isNotEmpty() || initial.weekdays.isNotEmpty()) }
    val zones = store.zoneRows().map { it.name }.ifEmpty { listOf("lan", "wan") }
    val problem = store.ruleProblem(draft)
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Firewall & security", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Create traffic rule", style = sans(16f, 650), modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1.5f)) {
                    FieldLabel("NAME")
                    FormTextField(draft.name, { draft = draft.copy(name = it) })
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("ACTION")
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("ACCEPT" to "Allow", "REJECT" to "Reject", "DROP" to "Block").forEach { (v, l) ->
                            SegmentCell(l, draft.target == v, Modifier.weight(1f), when (v) { "ACCEPT" -> Wrt.Green; "REJECT" -> Wrt.Amber; else -> Wrt.Red }) { draft = draft.copy(target = v) }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("FROM ZONE")
                    FormSelect(draft.src.ifEmpty { "device" }, zones + "device") { draft = draft.copy(src = if (it == "device") "" else it) }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("TO ZONE")
                    FormSelect(draft.dest.ifEmpty { "device" }, zones + "device") { draft = draft.copy(dest = if (it == "device") "" else it) }
                }
            }
            Text(
                "\"device\" is the router itself — a rule to it governs input, from it governs output.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("SOURCE IP")
                    FormTextField(draft.srcIp, { draft = draft.copy(srcIp = it.filter { c -> c.isDigit() || c in "./" }) })
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("DESTINATION IP")
                    FormTextField(draft.destIp, { draft = draft.copy(destIp = it.filter { c -> c.isDigit() || c in "./" }) })
                }
            }
            Row(Modifier.padding(top = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("PROTOCOL")
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (FirewallStore.PROTOCOLS + ("" to "any")).forEach { (value, label) ->
                            SegmentCell(label, draft.proto == value, Modifier.weight(1f)) { draft = draft.copy(proto = value) }
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("DEST PORT")
                    FormTextField(draft.destPort, { draft = draft.copy(destPort = it.filter { c -> c.isDigit() || c in "- ," }) }) {
                        if (draft.destPort.isEmpty()) Text("any", style = mono(8.5f, 500, Wrt.TextDim))
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .border(1.dp, if (scheduled) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard, RoundedCornerShape(13.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(WrtIcons.Clock, null, Modifier.size(13.dp), tint = Wrt.Accent)
                    SectionLabel("SCHEDULE", size = 9.5f)
                    FlexSpacer()
                    WToggle(scheduled) {
                        scheduled = !scheduled
                        if (!scheduled) draft = draft.copy(weekdays = emptyList(), startTime = "", stopTime = "")
                        else if (draft.startTime.isEmpty()) draft = draft.copy(startTime = "21:00", stopTime = "07:00")
                    }
                }
                if (scheduled) {
                    Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FirewallStore.WEEKDAYS.forEach { d ->
                            val sel = d in draft.weekdays
                            Box(
                                Modifier.weight(1f).height(30.dp)
                                    .background(if (sel) Wrt.Accent else Color.Transparent, RoundedCornerShape(7.dp))
                                    .border(1.dp, if (sel) Color.Transparent else Wrt.BorderInput, RoundedCornerShape(7.dp))
                                    .clickable {
                                        draft = draft.copy(weekdays = if (sel) draft.weekdays - d else FirewallStore.WEEKDAYS.filter { it in draft.weekdays || it == d })
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text(d.take(2), style = mono(9f, 600, if (sel) Wrt.OnAccent else Wrt.TextDim)) }
                        }
                    }
                    Text(
                        if (draft.weekdays.isEmpty()) "No day picked = every day." else "",
                        style = mono(9.5f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldLabel("FROM")
                            FormTextField(draft.startTime, { draft = draft.copy(startTime = it.filter { c -> c.isDigit() || c == ':' }.take(5)) })
                        }
                        Column(Modifier.weight(1f)) {
                            FieldLabel("TO")
                            FormTextField(draft.stopTime, { draft = draft.copy(stopTime = it.filter { c -> c.isDigit() || c == ':' }.take(5)) })
                        }
                    }
                    Text(
                        "→ " + listOfNotNull(
                            draft.weekdays.takeIf { it.isNotEmpty() }?.let { "option weekdays '${it.joinToString(" ")}'" },
                            draft.startTime.takeIf { it.isNotEmpty() }?.let { "start_time '$it'" },
                            draft.stopTime.takeIf { it.isNotEmpty() }?.let { "stop_time '$it'" },
                        ).joinToString(" · ").ifEmpty { "no schedule options" } +
                            " · router-local time",
                        style = mono(9.5f, 500, Wrt.TextDim, lineHeight = 15.sp),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (attempted && problem != null) ProblemCard(problem, top = 10.dp)
            Spacer(Modifier.height(13.dp))
            PrimaryButton("Stage rule") {
                attempted = true
                if (store.stageRule(draft) == null) onBack()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 35 · Zones · NAT · traffic matrix
// ---------------------------------------------------------------------------

@Composable
private fun ZonesTab(store: FirewallStore) {
    val zones = store.zoneRows()
    var open by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (zones.isEmpty() && store.loaded) EmptyCard("No zones — fw4 has nothing to attach networks to.")
        zones.forEach { z ->
            ZoneCard(store, z, expanded = open == z.section || z.name == "lan" || z.name == "wan") {
                open = if (open == z.section) null else z.section
            }
        }
        if (zones.size >= 2) ForwardingMatrix(store, zones)
    }
}

@Composable
private fun ZoneCard(store: FirewallStore, z: FwZone, expanded: Boolean, onExpand: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(zoneColor(z.name), RoundedCornerShape(2.dp)))
            Text(z.name.ifEmpty { z.section }, style = sans(13.5f, 650))
            Text(
                z.networks.joinToString(" · ").ifEmpty { if (expanded) "no networks" else summaryOf(z) },
                style = mono(9.5f, 500, Wrt.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) WrtIcons.ChevronDown else WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
        }
        if (expanded) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("input" to z.input, "output" to z.output, "forward" to z.forward).forEach { (dir, policy) ->
                    PolicyTile(dir.uppercase(), policy, Modifier.weight(1f), changed = "firewall.${z.section}.$dir" in store.staged) {
                        store.setZonePolicy(z.section, dir, nextPolicy(policy))
                    }
                }
            }
            if (z.name == "wan" || z.masq || z.mtuFix) {
                Column(Modifier.padding(top = 10.dp)) {
                    HairDivider()
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            WToggle(z.masq) { store.setZoneFlag(z.section, "masq", !z.masq) }
                            Text("Masquerade (NAT)", style = sans(11f, 600, Wrt.TextSecondary))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            WToggle(z.mtuFix) { store.setZoneFlag(z.section, "mtu_fix", !z.mtuFix) }
                            Text("MSS clamp", style = sans(11f, 600, Wrt.TextSecondary))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyTile(label: String, policy: String, modifier: Modifier = Modifier, changed: Boolean = false, recommended: Boolean = false, onClick: () -> Unit) {
    Column(
        modifier
            .border(1.dp, if (changed) Wrt.Accent.copy(alpha = 0.5f) else if (recommended) Wrt.Accent.copy(alpha = 0.35f) else Wrt.BorderHair, RoundedCornerShape(8.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = mono(8.5f, 500, Wrt.TextDim))
            if (recommended) {
                Text(
                    "REC", style = mono(6.5f, 600, Wrt.Accent),
                    modifier = Modifier.border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
        Text(policy, style = mono(10.5f, 600, policyColor(policy)))
    }
}

@Composable
private fun ForwardingMatrix(store: FirewallStore, zones: List<FwZone>) {
    val names = zones.map { it.name }.filter { it.isNotEmpty() }
    // Destinations across, sources down — "from ↓ to →", with wan first as the design has it.
    val cols = names.sortedBy { if (it == "wan") 0 else if (it == "lan") 1 else 2 }.take(4)
    val rows = names.sortedBy { if (it == "lan") 0 else if (it == "wan") 3 else 1 }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        SectionLabel("INTER-ZONE FORWARDING", size = 9.5f)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("from ↓ to →", style = mono(9f, 500, Wrt.TextDim), modifier = Modifier.weight(1.2f).padding(vertical = 6.dp, horizontal = 4.dp))
            cols.forEach { c ->
                Text(c, style = mono(9f, 600, Wrt.TextTertiary), modifier = Modifier.weight(1f).padding(vertical = 6.dp), textAlign = TextAlign.Center)
            }
        }
        rows.forEach { src ->
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(src, style = mono(10f, 600), modifier = Modifier.weight(1.2f).padding(horizontal = 4.dp))
                cols.forEach { dest ->
                    if (src == dest) {
                        Box(Modifier.weight(1f).height(32.dp).background(Wrt.BgDeep, RoundedCornerShape(7.dp)).border(1.dp, Wrt.BorderHair, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                            Text("—", style = mono(9.5f, 500, Wrt.BorderInput))
                        }
                    } else {
                        val allowed = store.forwardingAllowed(src, dest)
                        val tint = if (allowed) Wrt.Green else Wrt.Red
                        Box(
                            Modifier.weight(1f).height(32.dp)
                                .background(tint.copy(alpha = if (allowed) 0.1f else 0.08f), RoundedCornerShape(7.dp))
                                .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
                                .clickable { store.toggleForwarding(src, dest) },
                            contentAlignment = Alignment.Center,
                        ) { Text(if (allowed) "✓ allow" else "× block", style = mono(9.5f, 600, tint)) }
                    }
                }
            }
        }
        Text("Tap a cell to toggle · maps to config forwarding", style = mono(9.5f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 9.dp))
    }
}

// ---------------------------------------------------------------------------
// 36 · DMZ
// ---------------------------------------------------------------------------

@Composable
private fun DmzTab(store: FirewallStore) {
    val dmz = store.dmz()
    var pickOpen by remember { mutableStateOf(false) }
    var exceptText by remember(dmz.except) { mutableStateOf(dmz.except.joinToString(", ")) }
    val zones = store.zoneRows().map { it.name }.filter { it.isNotEmpty() }.ifEmpty { listOf("wan") }
    fun update(block: DmzDraft.() -> DmzDraft) = store.setDmz(dmz.block())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                .background(Wrt.Red.copy(alpha = 0.04f), RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enable DMZ", style = sans(14f, 650, Wrt.Red))
                Text("All unmatched inbound WAN traffic → one host", style = sans(10.5f, 400, Wrt.DangerSub), modifier = Modifier.padding(top = 2.dp))
            }
            WToggle(dmz.enabled) { update { copy(enabled = !enabled) } }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                .background(Wrt.Red.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Red)
            Text(
                "Exposing a host bypasses inbound NAT firewall protection. Use only for game consoles or isolated servers — never for a NAS with your data.",
                style = sans(11.5f, 400, Wrt.DangerLoss, lineHeight = 18.sp),
            )
        }
        Column {
            FieldLabel("TARGET HOST")
            FormTextField(dmz.targetIp, { v -> update { copy(targetIp = v.filter { c -> c.isDigit() || c == '.' }) } })
            Row(
                Modifier.padding(top = 7.dp).clickable { pickOpen = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(WrtIcons.WiredDevice, null, Modifier.size(12.dp), tint = Wrt.Accent)
                Text("Select from active clients", style = sans(11.5f, 600, Wrt.Accent))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FieldLabel("SOURCE INTERFACE")
                FormSelect(dmz.src, zones) { v -> update { copy(src = v) } }
            }
            Column(Modifier.weight(1f)) {
                FieldLabel("EXCEPT PORTS")
                FormTextField(exceptText, { v ->
                    exceptText = v.filter { c -> c.isDigit() || c in ", " }
                    val ports = exceptText.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
                    update { copy(except = ports) }
                })
            }
        }
        Text(
            "Port ${FirewallStore.SSH_PORT} stays with the router whatever is typed — it is how this app gets in. " +
                "Ports with their own forward already win over the DMZ.",
            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
        ) {
            Text("# stages as", style = mono(10.5f, 500, Wrt.TextDim, lineHeight = 18.sp))
            if (dmz.enabled) {
                val ranges = FirewallStore.dmzRanges(dmz.except)
                Text("+ config redirect 'wrtpulse_dmz_1'${if (ranges.size > 1) " … _${ranges.size}" else ""}", style = mono(10.5f, 500, Wrt.Green, lineHeight = 18.sp))
                Text("+   option src '${dmz.src}' · option dest_ip '${dmz.targetIp.ifEmpty { "?" }}'", style = mono(10.5f, 500, Wrt.Green, lineHeight = 18.sp))
                Text(
                    "+   src_dport " + ranges.joinToString(" · ") { (a, b) -> if (a == b) "$a" else "$a-$b" },
                    style = mono(10.5f, 500, Wrt.Green, lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("- every wrtpulse_dmz_* redirect", style = mono(10.5f, 500, if (store.config.forwards.any { it.isDmz }) Wrt.Red else Wrt.TextDim, lineHeight = 18.sp))
            }
        }
    }
    SheetHost(visible = pickOpen, onDismiss = { pickOpen = false }) {
        LeasePicker(store.leases, onPick = { update { copy(targetIp = it.ip) }; pickOpen = false }) { pickOpen = false }
    }
}

// ---------------------------------------------------------------------------
// 37 · Defaults & DoS defense
// ---------------------------------------------------------------------------

@Composable
private fun DefaultsTab(store: FirewallStore) {
    val d = store.defaults()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            ToggleRow("Respond to ping from WAN", "Off = stealth · toggles Allow-Ping rule", store.wanPing()) { store.setWanPing(!store.wanPing()) }
            HairDivider()
            ToggleRow("SYN-flood protection", "Rate-limits TCP handshakes · syn_flood", d.synFlood) { store.setDefaultFlag("syn_flood", !d.synFlood) }
            HairDivider()
            ToggleRow("Drop invalid packets", "Discards malformed conntrack states · drop_invalid", d.dropInvalid) { store.setDefaultFlag("drop_invalid", !d.dropInvalid) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            SectionLabel("GLOBAL DEFAULT POLICIES", size = 9.5f)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PolicyTile("INPUT", d.input, Modifier.weight(1f), changed = "firewall.${d.section}.input" in store.staged, recommended = true) { store.setDefault("input", nextPolicy(d.input)) }
                PolicyTile("OUTPUT", d.output, Modifier.weight(1f), changed = "firewall.${d.section}.output" in store.staged) { store.setDefault("output", nextPolicy(d.output)) }
                PolicyTile("FORWARD", d.forward, Modifier.weight(1f), changed = "firewall.${d.section}.forward" in store.staged, recommended = true) { store.setDefault("forward", nextPolicy(d.forward)) }
            }
            Text("Recommended: reject unsolicited input & forward, allow output. Tap a tile to cycle.", style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WrtIcons.Shield, null, Modifier.size(16.dp), tint = Wrt.Accent)
            Text(
                "Lockout failsafe — applying runs firewall reload; if SSH can't reconnect in ${FirewallStore.ROLLBACK_SECONDS} s, rules auto-revert.",
                style = sans(11.5f, 400, Wrt.AccentBody, lineHeight = 17.sp),
            )
        }
        CommandLine(
            store.ops().firstOrNull()?.let { "$ uci $it" }
                ?: "$ uci set firewall.${d.section}.syn_flood='${if (d.synFlood) 1 else 0}'",
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600))
            Text(subtitle, style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp))
        }
        WToggle(on, onToggle)
    }
}

// ---------------------------------------------------------------------------
// Review
// ---------------------------------------------------------------------------

@Composable
private fun FirewallReviewSheet(store: FirewallStore?, onApply: () -> Unit, onRevertAll: () -> Unit) {
    if (store == null) return
    val problems = store.problems()
    val exposes = store.dmzDraft?.enabled == true
    var typed by remember { mutableStateOf("") }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Review changes", style = sans(16f, 650))
            FlexSpacer()
            Text("uci batch · ${store.ops().size} ops", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Text(
            "These commands run on the router when you apply, then `${com.vivekkaushik.wrtpulse.ops.Commands.FIREWALL_RELOAD}`. " +
                "If the app cannot reconnect within ${FirewallStore.ROLLBACK_SECONDS} s the router restores the old file itself.",
            style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 17.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("# firewall", style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
            store.diffLines().forEach { (line, added) ->
                Text(line, style = mono(11f, 500, if (added) Wrt.Green else Wrt.Red, lineHeight = 19.sp))
            }
            Text("uci commit firewall && ${com.vivekkaushik.wrtpulse.ops.Commands.FIREWALL_RELOAD}", style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
        }
        store.warnings().forEach { note ->
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
        problems.forEach { ProblemCard(it, top = 10.dp) }
        if (exposes) {
            Column(Modifier.padding(top = 12.dp)) {
                FieldLabel("TYPE EXPOSE TO CONFIRM")
                FormTextField(typed, { typed = it.uppercase().take(6) })
            }
        }
        store.error?.let { Text(it, style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp), modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(14.dp))
        val label = when {
            store.applying -> "Applying…"
            store.pendingCount == 1 -> "Apply 1 change"
            else -> "Apply ${store.pendingCount} changes"
        }
        val ready = problems.isEmpty() && !store.applying && (!exposes || typed == "EXPOSE")
        if (ready) {
            PrimaryButton(label, color = if (exposes) Wrt.Red else Wrt.Accent, textColor = if (exposes) Wrt.OnRed else Wrt.OnAccent, onClick = onApply)
        } else {
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(if (exposes && typed != "EXPOSE") "Type EXPOSE above" else label, style = sans(13.5f, 650, Wrt.TextDim)) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp).clickable(onClick = onRevertAll), contentAlignment = Alignment.Center) {
            Text("Discard all", style = sans(13f, 600, Wrt.TextSecondary))
        }
    }
}

// ---------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------

@Composable
private fun LatencyTag(latencyMs: Int) {
    Text(
        "$latencyMs ms",
        style = mono(10.5f, 500, Wrt.TextTertiary),
        modifier = Modifier.border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun HairDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))

@Composable
private fun CommandLine(text: String) {
    Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = mono(10f, 500, Wrt.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun NoticeLine(text: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (color == Wrt.Amber) WrtIcons.Warning else WrtIcons.Check, null, Modifier.padding(top = 1.dp).size(15.dp), tint = color)
        Text(text, style = sans(11.5f, 400, if (color == Wrt.Amber) Wrt.AmberText else Wrt.AccentBody, lineHeight = 17.sp))
    }
}

@Composable
private fun EmptyCard(text: String) {
    Text(
        text,
        style = sans(12f, 400, Wrt.TextDim, lineHeight = 18.sp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(14.dp),
    )
}

@Composable
private fun DashedAction(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(WrtIcons.Plus, null, Modifier.size(14.dp), tint = Wrt.TextTertiary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = sans(12f, 600, Wrt.TextTertiary))
    }
}

@Composable
private fun SegmentCell(label: String, selected: Boolean, modifier: Modifier = Modifier, color: Color = Wrt.Accent, onClick: () -> Unit) {
    Box(
        modifier
            .height(42.dp)
            .background(if (selected) color else Color.Transparent, RoundedCornerShape(9.dp))
            .border(1.dp, if (selected) Color.Transparent else Wrt.BorderInput, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = mono(9.5f, if (selected) 600 else 500, if (selected) (if (color == Wrt.Accent || color == Wrt.Green) Wrt.OnAccent else Wrt.OnRed) else Wrt.TextDim))
    }
}

private fun protoLabel(proto: String): String = when (proto.trim().lowercase()) {
    "tcp" -> "TCP"; "udp" -> "UDP"; "tcp udp", "udp tcp", "tcpudp" -> "TCP+UDP"; "" -> "ANY"; else -> proto.uppercase()
}

private fun policyColor(policy: String): Color = when (policy.uppercase()) {
    "ACCEPT" -> Wrt.Green; "REJECT" -> Wrt.Red; "DROP" -> Wrt.Red; else -> Wrt.TextSecondary
}

private fun nextPolicy(policy: String): String {
    val i = FirewallStore.POLICIES.indexOf(policy.uppercase())
    return FirewallStore.POLICIES[(i + 1) % FirewallStore.POLICIES.size]
}

private fun zoneColor(name: String): Color = when (name) {
    "lan" -> Wrt.Accent; "wan" -> Wrt.Red; "guest" -> Wrt.Amber; else -> Color(0xFF5CA9F2)
}

private fun summaryOf(z: FwZone) = "input ${z.input.lowercase()} · fwd ${z.forward.lowercase()}"

private fun ago(sec: Long): String = when {
    sec < 60 -> "${sec}s ago"
    sec < 3600 -> "${sec / 60} min ago"
    sec < 86_400 -> "${sec / 3600} h ago"
    else -> "${sec / 86_400} d ago"
}
