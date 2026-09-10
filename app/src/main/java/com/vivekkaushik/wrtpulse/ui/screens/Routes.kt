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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.RouteDraft
import com.vivekkaushik.wrtpulse.data.RouteRow
import com.vivekkaushik.wrtpulse.data.RouteStore
import com.vivekkaushik.wrtpulse.data.WanStore
import com.vivekkaushik.wrtpulse.ops.KernelRoute
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.LiveRefresh
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

private const val ROUTES_REFRESH_MS = 10_000L

private enum class RtTab(val label: String) { Configured("Configured"), Kernel("Kernel table") }

private sealed interface RtRoute {
    data object List : RtRoute
    data class Edit(val draft: RouteDraft) : RtRoute
}

/**
 * Static routes — `config route` / `config route6` over one read, applied with the WAN
 * screen's rollback underneath. The second tab is what the kernel actually holds, kept live.
 */
@Composable
fun RoutesSection(store: RouteStore?, latencyMs: Int, onBack: () -> Unit, onFullScreen: (Boolean) -> Unit) {
    val stack = remember { mutableStateListOf<RtRoute>(RtRoute.List) }
    val route = stack.last()
    fun push(r: RtRoute) = stack.add(r)
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }
    LaunchedEffect(route) { onFullScreen(route is RtRoute.Edit) }
    LiveRefresh(store, ROUTES_REFRESH_MS)

    when (val r = route) {
        RtRoute.List -> RoutesList(store, latencyMs, onBack = onBack, onEdit = { push(RtRoute.Edit(it)) })
        is RtRoute.Edit -> if (store != null) EditRouteScreen(store, r.draft, onBack = { pop() }) else pop()
    }
}

@Composable
private fun RoutesList(store: RouteStore?, latencyMs: Int, onBack: () -> Unit, onEdit: (RouteDraft) -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(RtTab.Configured) }
    var reviewOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Static routes", onBack) {
            Text(
                "$latencyMs ms",
                style = mono(10.5f, 500, Wrt.TextTertiary),
                modifier = Modifier.border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to manage its routes.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RtTab.entries.forEach { entry ->
                FilterChip(entry.label, tab == entry, size = 11f, padH = 11.dp, padV = 5.dp) { tab = entry }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                RtTab.Configured -> ConfiguredTab(
                    store,
                    onAdd = { onEdit(store.newDraft()) },
                    // An edit already staged reopens as itself, so a second edit does not stack.
                    onEdit = { row ->
                        onEdit(
                            store.draftFor(row.section)
                                ?: store.newDraft(store.routes.firstOrNull { it.section == row.section })
                        )
                    },
                )
                RtTab.Kernel -> KernelTab(store)
            }
        }
        if (store.pendingCount > 0) {
            FormActionBar(
                pendingCount = store.pendingCount,
                countLabel = "Unsaved route changes",
                saveLabel = "Review & Apply",
                saveEnabled = true,
                onCancel = { store.revert() },
                onSave = { reviewOpen = true },
            )
        }
    }
    SheetHost(visible = reviewOpen, onDismiss = { reviewOpen = false }) {
        RoutesReviewSheet(
            store = store,
            onApply = { scope.launch { if (store!!.apply()) reviewOpen = false } },
            onRevertAll = { store?.revert(); reviewOpen = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Configured routes
// ---------------------------------------------------------------------------

@Composable
private fun ConfiguredTab(store: RouteStore, onAdd: () -> Unit, onEdit: (RouteRow) -> Unit) {
    val rows = store.rows()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (!store.loaded) {
            Text(
                store.error ?: "reading routes over ssh…",
                style = mono(10.5f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
            )
        }
        store.notice?.let { NoteCard(it) }
        if (rows.isEmpty() && store.loaded) {
            RoutesEmptyCard(
                "No static routes. Everything not on a local subnet follows the default route " +
                    "out of the primary uplink; a route here sends one destination somewhere else."
            )
        }
        rows.forEach { row ->
            SwipeToReveal(
                actions = listOf(
                    RevealAction("Edit", WrtIcons.Pencil, Wrt.TextSecondary) { onEdit(row) },
                    RevealAction(
                        if (row.deleting) "Keep" else "Delete",
                        WrtIcons.Trash,
                        Wrt.Red,
                    ) { if (row.deleting) store.undoDelete(row.section) else store.removeRoute(row.section) },
                ),
                resetKey = row.section,
                base = Wrt.BgCard,
            ) { modifier ->
                RouteCard(row, modifier) { if (!row.isDraft) store.toggleDisabled(row.section) }
            }
        }
        if (rows.isNotEmpty()) {
            Text(
                "Swipe left for edit · delete. Toggles apply after review.",
                style = sans(10.5f, 400, Wrt.TextDim),
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
        RoutesDashedAction("Add route", onAdd)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RouteCard(row: RouteRow, modifier: Modifier, onToggle: () -> Unit) {
    val on = !row.disabled && !row.deleting
    val text = if (on) Wrt.TextPrimary else Wrt.TextSecondary
    val dim = if (on) Wrt.TextTertiary else Wrt.TextDim
    Column(
        modifier
            .fillMaxWidth()
            .border(
                1.dp,
                when {
                    row.deleting -> Wrt.Red.copy(alpha = 0.5f)
                    row.changed -> Wrt.Accent.copy(alpha = 0.4f)
                    else -> Wrt.BorderCard
                },
                RoundedCornerShape(13.dp),
            )
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                row.target,
                style = mono(13.5f, 650, text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.ipv6) MonoTag("IPv6", color = dim, size = 8.5f)
            if (row.type != "unicast") MonoTag(row.type, color = Wrt.Amber, size = 8.5f)
            if (row.isDraft) MonoTag(if (row.editing) "EDITED" else "NEW", color = Wrt.Accent, size = 8.5f)
            if (row.deleting) MonoTag("DELETING", color = Wrt.Red, size = 8.5f)
            FlexSpacer()
            if (!row.isDraft && !row.deleting) WToggle(!row.disabled, onToggle)
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(WrtIcons.ArrowRight, null, Modifier.size(13.dp), tint = if (on) Wrt.Accent else Wrt.BorderInput)
            Text(
                (if (row.gateway.isNotEmpty()) "via ${row.gateway} " else "") + "on ${row.iface}" +
                    (if (row.metric.isNotEmpty()) " · metric ${row.metric}" else ""),
                style = mono(11f, 500, dim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val live = row.live
            StatusDot(if (live != null) Wrt.Green else Wrt.DotOff, 6.dp)
            Text(
                when {
                    live != null -> "in kernel"
                    row.isDraft -> "after apply"
                    row.disabled -> "disabled"
                    else -> "not in kernel"
                },
                style = mono(9.5f, 500, if (live != null) Wrt.Green else Wrt.TextDim),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Kernel table
// ---------------------------------------------------------------------------

@Composable
private fun KernelTab(store: RouteStore) {
    val rows = store.kernelRows()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "What the kernel holds right now, from `ip route show` — static routes, the ones netifd " +
                "adds for each interface, and what DHCP handed over. Refreshes every ${ROUTES_REFRESH_MS / 1000} s.",
            style = sans(11f, 400, Wrt.TextSecondary, lineHeight = 17.sp),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (rows.isEmpty() && store.loaded) RoutesEmptyCard("The kernel reported no routes.")
        var family: Boolean? = null
        rows.forEach { (k, static) ->
            if (family != k.ipv6) {
                family = k.ipv6
                Text(
                    if (k.ipv6) "IPV6" else "IPV4",
                    style = mono(9.5f, 600, Wrt.TextDim),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            KernelRow(k, static)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun KernelRow(k: KernelRoute, static: Boolean) {
    val isDefault = k.dst == "0.0.0.0/0" || k.dst == "::/0"
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (static) Wrt.Accent.copy(alpha = 0.35f) else Wrt.BorderCard, RoundedCornerShape(11.dp))
            .background(Wrt.BgCard, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isDefault) "default" else k.dst,
                style = mono(12.5f, 650, if (isDefault) Wrt.Accent else Wrt.TextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (k.type != "unicast") MonoTag(k.type, color = Wrt.Amber, size = 8.5f)
            if (static) MonoTag("STATIC", color = Wrt.Accent, size = 8.5f)
            FlexSpacer()
            k.metric?.let { Text("metric $it", style = mono(9.5f, 500, Wrt.TextDim)) }
        }
        Text(
            listOfNotNull(
                k.via.takeIf { it.isNotEmpty() }?.let { "via $it" },
                k.dev.takeIf { it.isNotEmpty() }?.let { "dev $it" },
                k.proto.takeIf { it.isNotEmpty() }?.let { "proto $it" },
                k.scope.takeIf { it.isNotEmpty() }?.let { "scope $it" },
                k.src.takeIf { it.isNotEmpty() }?.let { "src $it" },
            ).joinToString(" · ").ifEmpty { "—" },
            style = mono(10f, 500, Wrt.TextTertiary),
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// Add / edit
// ---------------------------------------------------------------------------

@Composable
private fun EditRouteScreen(store: RouteStore, initial: RouteDraft, onBack: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var attempted by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(initial.mtu.isNotEmpty() || initial.table.isNotEmpty() || initial.type != "unicast" || initial.onlink) }
    val problem = store.routeProblem(draft)
    val editing = draft.replaces != null
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Static routes", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) "Edit route" else "Add route", style = sans(16f, 650))
                FlexSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MaskChip("IPv4", !draft.ipv6) { if (!editing) draft = draft.copy(ipv6 = false) }
                    MaskChip("IPv6", draft.ipv6) { if (!editing) draft = draft.copy(ipv6 = true) }
                }
            }
            if (editing) {
                Text(
                    "Rewrites ${draft.replaces} in place. The family cannot change — a route6 is a different section type.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(Modifier.padding(top = 12.dp)) {
                FieldLabel("DESTINATION")
                FormTextField(draft.target, { draft = draft.copy(target = it.trim()) })
                Text(
                    if (draft.ipv6) "A prefix — 2001:db8::/32 — or ::/0 for everything."
                    else "A network in CIDR form — 10.20.0.0/16 — a bare address for one host, or 0.0.0.0/0 for everything.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Column(Modifier.padding(top = 11.dp)) {
                FieldLabel("GATEWAY")
                FormTextField(draft.gateway, { draft = draft.copy(gateway = it.trim()) })
                Text(
                    "The next hop, on the interface's own subnet. Leave empty for a route straight out of " +
                        "the interface, or for a ${if (draft.type == "unicast") "blackhole or unreachable" else draft.type} route.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Column(Modifier.padding(top = 11.dp)) {
                FieldLabel("INTERFACE")
                Row(
                    Modifier.padding(top = 6.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    store.interfaces.forEach { name ->
                        val link = store.links.firstOrNull { it.name == name }
                        val detail = link?.let { l -> if (draft.ipv6) l.v6Address.ifEmpty { l.device } else l.cidr.ifEmpty { l.device } }
                        MaskChip(name + (detail?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), draft.iface == name) {
                            draft = draft.copy(iface = name)
                        }
                    }
                }
                if (store.interfaces.isEmpty()) {
                    Text("No interfaces read yet.", style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 5.dp))
                }
            }
            Column(Modifier.padding(top = 11.dp)) {
                FieldLabel("METRIC")
                FormTextField(draft.metric, { draft = draft.copy(metric = it.filter(Char::isDigit).take(10)) })
                Text(
                    "Lower wins when two routes cover the same destination. Empty is 0.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Spacer(Modifier.height(11.dp))
            ToggleCard {
                ToggleRow(
                    title = "Advanced",
                    body = "MTU, routing table, route type, onlink",
                    checked = advanced,
                    divider = advanced,
                ) { advanced = !advanced }
                if (advanced) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("MTU")
                                FormTextField(draft.mtu, { draft = draft.copy(mtu = it.filter(Char::isDigit).take(5)) })
                            }
                            Column(Modifier.weight(1f)) {
                                FieldLabel("TABLE")
                                FormTextField(draft.table, { draft = draft.copy(table = it.trim()) })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FieldLabel("TYPE")
                        Row(
                            Modifier.padding(top = 6.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RouteStore.ROUTE_TYPES.forEach { t ->
                                MaskChip(t, draft.type == t) {
                                    draft = draft.copy(type = t, gateway = if (t == "unicast") draft.gateway else "")
                                }
                            }
                        }
                        Text(
                            when (draft.type) {
                                "unicast" -> "A normal route: forward towards the gateway or out of the interface."
                                "blackhole" -> "Packets are dropped silently."
                                "unreachable" -> "Packets are dropped and the sender is told so (ICMP unreachable)."
                                "prohibit" -> "Packets are dropped with an ICMP administratively-prohibited."
                                "throw" -> "Stop this table's lookup and fall through to the next rule."
                                else -> "Rarely needed outside the kernel's own local table."
                            },
                            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        ToggleRow(
                            title = "Gateway is on-link",
                            body = "Pretend the gateway is directly reachable even when it is outside the subnet",
                            checked = draft.onlink,
                            divider = true,
                        ) { draft = draft.copy(onlink = !draft.onlink) }
                        ToggleRow(
                            title = "Disabled",
                            body = "Keep the route in the config without installing it",
                            checked = draft.disabled,
                            divider = false,
                        ) { draft = draft.copy(disabled = !draft.disabled) }
                    }
                }
            }
            if (attempted && problem != null) ProblemCard(problem, top = 10.dp)
            Text(
                "$ uci set network.${draft.replaces ?: "wrtpulse_route${if (draft.ipv6) "6" else ""}_${draft.id}"}.target='${draft.target}'",
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(13.dp))
            PrimaryButton(if (editing) "Stage change" else "Stage route") {
                attempted = true
                if (store.stageRoute(draft) == null) onBack()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Review
// ---------------------------------------------------------------------------

@Composable
private fun RoutesReviewSheet(store: RouteStore?, onApply: () -> Unit, onRevertAll: () -> Unit) {
    if (store == null) return
    val problems = store.problems()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Review route changes", style = sans(16f, 650))
            FlexSpacer()
            Text("uci batch · ${store.ops().size} ops", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Text(
            "These commands run on the router when you apply, then the network reloads — netifd " +
                "re-evaluates every interface's routes, so the uplinks blink for a second.",
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
                // uci paths are long; a wrapped `+` on its own line reads as a separate change.
                .horizontalScroll(rememberScrollState()),
        ) {
            Text("# network", style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
            store.diffLines().forEach { (line, added) ->
                Text(line, style = mono(11f, 500, if (added) Wrt.Green else Wrt.Red, lineHeight = 19.sp))
            }
            Text(store.commitLine(), style = mono(11f, 500, Wrt.TextDim, lineHeight = 19.sp))
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WrtIcons.Shield, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Accent)
            Text(
                "Rollback armed — the router keeps a copy of /etc/config/network and puts it back " +
                    "unless WrtPulse reaches it again within ${WanStore.ROLLBACK_SECONDS} s. A route that " +
                    "sends the phone's own traffic the wrong way is exactly what this is for.",
                style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
            )
        }
        problems.forEach { ProblemCard(it, top = 10.dp) }
        store.error?.let { Text(it, style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp), modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(14.dp))
        val label = when {
            store.applying -> "Applying…"
            store.pendingCount == 1 -> "Apply 1 change"
            else -> "Apply ${store.pendingCount} changes"
        }
        if (problems.isEmpty() && !store.applying) {
            PrimaryButton(label, onClick = onApply)
        } else {
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = sans(13.5f, 650, Wrt.TextDim)) }
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp).clickable(onClick = onRevertAll), contentAlignment = Alignment.Center) {
            Text("Discard all", style = sans(13f, 600, Wrt.TextSecondary))
        }
    }
}

// ---------------------------------------------------------------------------
// Small parts (the firewall's are private to its file)
// ---------------------------------------------------------------------------

@Composable
private fun RoutesEmptyCard(text: String) {
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
private fun RoutesDashedAction(text: String, onClick: () -> Unit) {
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
