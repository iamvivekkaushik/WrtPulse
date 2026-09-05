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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.LanClient
import com.vivekkaushik.wrtpulse.data.LanStore
import com.vivekkaushik.wrtpulse.data.PortState
import com.vivekkaushik.wrtpulse.data.ResvRow
import com.vivekkaushik.wrtpulse.data.SwVlanRow
import com.vivekkaushik.wrtpulse.data.VlanRow
import com.vivekkaushik.wrtpulse.ops.IpMath
import com.vivekkaushik.wrtpulse.ops.NetDev
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

private enum class LanTab(val label: String) {
    Subnet("Subnet"), Dhcp("DHCP"), Leases("Leases"), Vlans("VLANs")
}

/**
 * LAN & local network — design screens 21-25 behind one set of tabs.
 *
 * Everything here is staged in [LanStore] and applied in one pass, including the one change
 * that ends the session it is issued from: moving the router's own address. That case gets
 * its own end state rather than a spinner that will never resolve.
 */
@Composable
fun LanScreen(
    store: LanStore?,
    latencyMs: Int,
    onBack: () -> Unit,
    /** The router now answers somewhere else — the saved entry has to follow it. */
    onMoved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(LanTab.Subnet) }
    var reviewOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ResvRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var prefill by remember { mutableStateOf<LanClient?>(null) }
    var creatingVlan by remember { mutableStateOf(false) }
    var uciPath by remember { mutableStateOf<String?>(null) }
    var uciText by remember { mutableStateOf<String?>(null) }

    com.vivekkaushik.wrtpulse.ui.LiveRefresh(store, LAN_REFRESH_MS)
    LaunchedEffect(uciPath) {
        val path = uciPath
        uciText = null
        if (path != null && store != null) uciText = store.showUci(path)
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("LAN & local network", onBack) {
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
                Text("Connect to a router to manage its LAN.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        val moved = store.movedTo
        if (moved != null) {
            MovedPanel(moved) { onMoved(moved) }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LanTab.entries.forEach { entry ->
                FilterChip(entry.label, tab == entry, size = 11.5f, padH = 12.dp, padV = 5.dp) {
                    tab = entry
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                LanTab.Subnet -> SubnetTab(store) { uciPath = it }
                LanTab.Dhcp -> DhcpTab(store)
                LanTab.Leases -> LeasesTab(
                    store = store,
                    onReserve = { client -> prefill = client; adding = true },
                    onEdit = { row -> editing = row },
                    onAdd = { prefill = null; adding = true },
                )
                LanTab.Vlans -> VlansTab(store) { creatingVlan = true }
            }
        }

        if (store.pendingCount > 0) {
            FormActionBar(
                pendingCount = store.pendingCount,
                countLabel = if (store.pendingCount == 1) "1 unsaved change" else "${store.pendingCount} unsaved changes",
                saveLabel = "Review & Apply",
                saveEnabled = true,
                onCancel = { store.revert() },
                onSave = { reviewOpen = true },
            )
        }
    }

    SheetHost(visible = reviewOpen, onDismiss = { reviewOpen = false }) {
        LanReviewSheet(
            store = store,
            onApply = {
                scope.launch {
                    if (store!!.apply()) reviewOpen = false
                }
            },
            onRevertAll = { store?.revert(); reviewOpen = false },
        )
    }
    SheetHost(visible = adding || editing != null, onDismiss = { adding = false; editing = null }) {
        if (store != null) {
            ReservationSheet(
                store = store,
                row = editing,
                prefill = prefill,
                onCancel = { adding = false; editing = null },
                onSave = { name, mac, ip ->
                    val row = editing
                    when {
                        row?.section != null -> store.editReservation(row.section, name, mac, ip)
                        row?.draftId != null -> store.updateDraft(row.draftId, name, mac, ip)
                        else -> store.addReservation(name, mac, ip)
                    }
                    adding = false
                    editing = null
                },
            )
        }
    }
    SheetHost(visible = creatingVlan, onDismiss = { creatingVlan = false }) {
        if (store != null) {
            CreateVlanSheet(
                store = store,
                onCancel = { creatingVlan = false },
                onCreate = { id ->
                    // A swconfig board gets a switch_vlan; a DSA board gets a bridge-vlan.
                    if (store.switchDev != null || store.swVlans.isNotEmpty()) store.addSwVlan(id)
                    else store.addVlan(id)
                    creatingVlan = false
                },
            )
        }
    }
    SheetHost(visible = uciPath != null, onDismiss = { uciPath = null }) {
        UciSheetContent(uciPath.orEmpty(), uciText) { uciPath = null }
    }
}

// ---------------------------------------------------------------------------
// Screen 21 — subnet & gateway
// ---------------------------------------------------------------------------

@Composable
private fun SubnetTab(store: LanStore, onShowUci: (String) -> Unit) {
    val net = store.net
    val live = store.live
    val prefix = store.prefix
    val parsed = IpMath.parse(store.routerIp)
    val broadcast = parsed?.let { IpMath.format(IpMath.broadcastOf(it, prefix)) }
    val device = (net?.device ?: live?.device).orEmpty()
    val mac = store.devs.firstOrNull { it.name == device }?.mac?.uppercase().orEmpty()
    var addingDns by remember { mutableStateOf(false) }
    var customMask by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        if (!store.loaded) {
            Text(
                store.error ?: "reading network config over ssh…",
                style = mono(10.5f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
            )
        }
        FactsCard(
            listOf(
                "IPV4" to live?.address.orEmpty().ifBlank { net?.ipaddr.orEmpty() },
                "NETMASK" to net?.netmask.orEmpty().ifBlank { IpMath.netmaskOf(prefix) },
                "BROADCAST" to broadcast.orEmpty(),
                "GATEWAY MAC" to mac,
            )
        )
        // The design labels this field GATEWAY IP. It is the router's own LAN address —
        // `network.lan.ipaddr` — and `network.lan.gateway` is a different option entirely,
        // so the label says which one is being written.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("ROUTER IP")
                if (store.movesAddress) StatusDot(Wrt.Accent, 5.dp)
            }
            FormTextField(store.routerIp, { store.stageRouterIp(it) }) {
                if (parsed != null) Icon(WrtIcons.Check, null, Modifier.size(15.dp), tint = Wrt.Green)
                else Icon(WrtIcons.Warning, null, Modifier.size(15.dp), tint = Wrt.Red)
            }
            Text(
                if (parsed != null) "Valid — each octet 0–255"
                else "Four numbers 0–255, separated by dots.",
                style = sans(10.5f, 400, if (parsed != null) Wrt.TextDim else Wrt.Red),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Column {
            FieldLabel("SUBNET MASK")
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                MaskChip("/24 · 255.255.255.0", prefix == 24 && !customMask) {
                    customMask = false; store.stageNetmask(IpMath.netmaskOf(24))
                }
                MaskChip("/16", prefix == 16 && !customMask) {
                    customMask = false; store.stageNetmask(IpMath.netmaskOf(16))
                }
                MaskChip("Custom", customMask || (prefix != 24 && prefix != 16)) { customMask = true }
            }
            if (customMask || (prefix != 24 && prefix != 16)) {
                FormTextField(store.netmask, { store.stageNetmask(it) })
                Text(
                    IpMath.prefixOf(store.netmask)?.let { "/$it · ${IpMath.usableHosts(it)} usable addresses" }
                        ?: "A netmask is a run of ones then zeros — 255.255.255.0, not 255.0.255.0.",
                    style = sans(10.5f, 400, if (IpMath.prefixOf(store.netmask) != null) Wrt.TextDim else Wrt.Red),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
        Column {
            FieldLabel("DNS OVERRIDE")
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                store.dns.forEach { entry ->
                    RemovableChip(entry) { store.stageDns(store.dns - entry) }
                }
                DashedChip("Add DNS") { addingDns = true }
            }
            Text(
                "The resolvers the router itself uses. Clients still ask the router.",
                style = sans(10.5f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (store.movesAddress) {
            NoteCard(
                "Changing the router IP ends this connection. The phone keeps an address on " +
                    "the old subnet until its lease renews, so reconnect at ${store.routerIp} " +
                    "after that — the app cannot follow the router across subnets on its own."
            )
        }
        store.problems().forEach { ProblemCard(it) }
        Row(
            Modifier.padding(top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$ uci set network.${store.section}.ipaddr='${store.routerIp}'",
                style = mono(10f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "· view command",
                style = mono(10f, 600, Wrt.Accent),
                modifier = Modifier.clickable { onShowUci("network.${store.section}") },
            )
        }
    }

    if (addingDns) {
        TextEntryDialog(
            title = "Add DNS server",
            hint = "9.9.9.9",
            validate = { IpMath.valid(it) },
            error = "Not an IPv4 address.",
            onDismiss = { addingDns = false },
            onDone = { store.stageDns(store.dns + it.trim()); addingDns = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Screen 22 — DHCP server
// ---------------------------------------------------------------------------

@Composable
private fun DhcpTab(store: LanStore) {
    var addingOption by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    val leaseCount = store.leases.size

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("DHCP server", style = sans(14f, 650))
                Text(
                    if (store.pool == null) "no dhcp section for ${store.section}"
                    else listOf(
                        (if (store.dnsmasqRunning) "dnsmasq on " else "dnsmasq stopped · ") +
                            (store.net?.device ?: store.live?.device).orEmpty(),
                        "$leaseCount active lease${if (leaseCount == 1) "" else "s"}",
                    ).joinToString(" · "),
                    style = sans(11f, 400, if (store.dnsmasqRunning) Wrt.TextDim else Wrt.Amber),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            com.vivekkaushik.wrtpulse.ui.WToggle(store.dhcpOn) { store.toggleDhcp() }
        }
        if (store.pool != null) {
            PoolCard(store)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("START OFFSET")
                    FormTextField(store.poolStart.toString(), { store.stagePoolStart(it.filter { c -> c.isDigit() }) })
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("MAX LEASES")
                    FormTextField(store.poolLimit.toString(), { store.stagePoolLimit(it.filter { c -> c.isDigit() }) })
                }
            }
            Column {
                FieldLabel("LEASE TIME")
                Row(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LanStore.LEASE_CHOICES.forEach { choice ->
                        MaskChip(
                            if (choice == "infinite") "∞" else choice.replace("h", " h").replace("d", " d"),
                            store.leaseTime == choice,
                        ) { store.stageLeaseTime(choice) }
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Advanced — DHCP options", style = sans(13f, 600))
                        Text(
                            "${store.dhcpOptions.size} custom option${if (store.dhcpOptions.size == 1) "" else "s"}",
                            style = sans(10.5f, 400, Wrt.TextDim),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        if (advancedOpen) WrtIcons.ChevronUp else WrtIcons.ChevronDown,
                        null,
                        Modifier.size(13.dp),
                        tint = Wrt.TextDim,
                    )
                }
                if (advancedOpen) {
                    store.dhcpOptions.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(0.dp, Color.Transparent)
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "opt ${option.substringBefore(',')}",
                                style = mono(10f, 600, Wrt.Accent),
                                modifier = Modifier.width(56.dp),
                            )
                            Text(
                                dhcpOptionLabel(option),
                                style = mono(11f, 500, Wrt.TextSecondary),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Icon(
                                WrtIcons.Close,
                                "remove",
                                Modifier.size(13.dp).clickable {
                                    store.stageDhcpOptions(store.dhcpOptions - option)
                                },
                                tint = Wrt.TextDim,
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { addingOption = true }.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(WrtIcons.Plus, null, Modifier.size(11.dp), tint = Wrt.TextTertiary)
                        Text("Add option", style = sans(11.5f, 600, Wrt.TextTertiary))
                    }
                }
            }
        }
        store.problems().forEach { ProblemCard(it) }
        Text(
            "$ uci show dhcp.${store.pool?.section ?: store.section}",
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    if (addingOption) {
        TextEntryDialog(
            title = "Add DHCP option",
            hint = "42,192.168.1.10",
            validate = { it.contains(',') && it.substringBefore(',').toIntOrNull() != null },
            error = "dnsmasq wants code,value — e.g. 42,192.168.1.10.",
            onDismiss = { addingOption = false },
            onDone = { store.stageDhcpOptions(store.dhcpOptions + it.trim()); addingOption = false },
        )
    }
}

/** The pool as a band across the subnet: static below, pool, then whatever is above it. */
@Composable
private fun PoolCard(store: LanStore) {
    val network = store.networkAddress
    val range = store.poolRange
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        SectionLabel(
            "ADDRESS POOL — " + (network?.let { "${IpMath.format(it)}/${store.prefix}" } ?: "—"),
            size = 9.5f,
            tracking = 0.12,
        )
        if (network == null || range == null) {
            Text(
                "The pool does not fit inside this subnet.",
                style = sans(11f, 500, Wrt.Red),
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        val total = (IpMath.broadcastOf(network, store.prefix) - network).toFloat()
        val staticShare = ((range.first - network).toFloat() / total).coerceIn(0f, 1f)
        val poolShare = ((range.last - range.first + 1).toFloat() / total).coerceIn(0f, 1f)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(24.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(7.dp))
                .background(Wrt.BgCardDim, RoundedCornerShape(7.dp)),
        ) {
            // The router's own address, then the static range, then the pool.
            Box(Modifier.weight(0.04f).fillMaxSize().background(Wrt.Amber.copy(alpha = 0.55f)))
            Box(Modifier.weight(staticShare.coerceAtLeast(0.01f)).fillMaxSize())
            Box(Modifier.weight(poolShare.coerceAtLeast(0.01f)).fillMaxSize().background(Wrt.Accent.copy(alpha = 0.3f)))
            Box(Modifier.weight((1f - staticShare - poolShare).coerceAtLeast(0.01f)).fillMaxSize())
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ".${range.first - network}–.${range.last - network} pool",
                style = mono(9.5f, 600, Wrt.Accent),
            )
            FlexSpacer()
            Text(
                "${range.last - range.first + 1} addresses · ${store.leases.size} used",
                style = mono(9.5f, 500, Wrt.TextTertiary),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(8.dp).background(Wrt.Amber.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
            Text("${store.routerIp} gateway (reserved)", style = mono(9.5f, 500, Wrt.TextTertiary))
        }
    }
}

/** dnsmasq's numeric options, named where the number is one people meet. */
private fun dhcpOptionLabel(option: String): String {
    val code = option.substringBefore(',')
    val value = option.substringAfter(',', "")
    val name = when (code) {
        "3" -> "router"
        "6" -> "dns"
        "42" -> "ntp"
        "44" -> "netbios-ns"
        "119" -> "search"
        "121" -> "static-route"
        "252" -> "wpad"
        else -> "option $code"
    }
    return "$name · $value"
}

// ---------------------------------------------------------------------------
// Screens 23 & 24 — leases and reservations
// ---------------------------------------------------------------------------

@Composable
private fun LeasesTab(
    store: LanStore,
    onReserve: (LanClient) -> Unit,
    onEdit: (ResvRow) -> Unit,
    onAdd: () -> Unit,
) {
    var showReservations by remember { mutableStateOf(false) }
    val clients = store.activeClients()
    val rows = store.reservationRows()
    val now = remember(store.leases.size) { System.currentTimeMillis() / 1000 }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(10.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                SegmentTab("Active clients · ${clients.size}", !showReservations, Modifier.weight(1f)) {
                    showReservations = false
                }
                SegmentTab("Reservations · ${rows.count { !it.deleting }}", showReservations, Modifier.weight(1f)) {
                    showReservations = true
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            ) {
                if (!store.loaded) {
                    Text(
                        store.error ?: "reading leases over ssh…",
                        style = mono(9.5f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
                if (!showReservations) {
                    clients.forEach { client -> ClientLeaseRow(client, now) { onReserve(client) } }
                    if (store.loaded && clients.isEmpty()) {
                        EmptyLine("Nothing has taken a lease and nothing is in the neighbour table.")
                    }
                } else {
                    rows.forEach { row ->
                        ReservationRow(
                            store = store,
                            row = row,
                            onEdit = { onEdit(row) },
                        )
                    }
                    if (rows.isEmpty()) EmptyLine("No static leases. The + button adds one.")
                }
                Spacer(Modifier.height(78.dp))
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
                .size(52.dp)
                .background(Wrt.Accent, RoundedCornerShape(16.dp))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WrtIcons.Plus, "add reservation", Modifier.size(22.dp), tint = Wrt.OnAccent)
        }
    }
}

@Composable
private fun ClientLeaseRow(client: LanClient, nowS: Long, onReserve: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    client.name.ifBlank { "Unknown" },
                    style = sans(13.5f, 600, if (client.name.isBlank()) Wrt.TextSecondary else Wrt.TextPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (client.reserved) ReservedBadge()
                else MonoTag(LanStore.leaseLeft(client.expiry, nowS), size = 8.5f, border = Wrt.BorderFaint)
            }
            Text(
                "${client.ip} · ${client.mac}",
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (!client.reserved) {
            Row(
                Modifier
                    .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onReserve)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(WrtIcons.Pin, null, Modifier.size(11.dp), tint = Wrt.Accent)
                Text("Reserve IP", style = sans(11f, 600, Wrt.Accent))
            }
        }
    }
    com.vivekkaushik.wrtpulse.ui.HorizontalHairline(Wrt.BorderRow)
}

@Composable
private fun ReservationRow(store: LanStore, row: ResvRow, onEdit: () -> Unit) {
    val actions = if (row.deleting) {
        listOf(
            RevealAction("Undo", WrtIcons.Reboot, Wrt.Accent) {
                row.section?.let { store.undoDelete("dhcp.$it") }
            }
        )
    } else {
        listOf(
            RevealAction("Edit", WrtIcons.Pencil, Wrt.Accent, onEdit),
            RevealAction("Delete", WrtIcons.Trash, Wrt.Red) {
                if (row.draftId != null) store.removeDraft(row.draftId)
                else row.section?.let { store.stageDelete("dhcp.$it") }
            },
        )
    }
    SwipeToReveal(actions = actions, resetKey = row.key) { modifier ->
        Row(
            modifier
                .fillMaxWidth()
                // Opaque first, then the tint. A 5%-alpha row let the swipe actions parked
                // behind it show straight through, so an untouched row looked half-swiped.
                .background(Wrt.BgScreen, RoundedCornerShape(13.dp))
                .background(
                    when {
                        row.deleting -> Wrt.Red.copy(alpha = 0.06f)
                        row.isNew -> Wrt.Accent.copy(alpha = 0.06f)
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(13.dp),
                )
                .clickable(onClick = onEdit)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        row.name.ifBlank { row.mac.ifBlank { "unnamed" } },
                        style = sans(13.5f, 600, if (row.deleting) Wrt.TextDim else Wrt.TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        row.deleting -> MonoTag("DELETING", Wrt.Red, Wrt.Red.copy(alpha = 0.5f), 8.5f)
                        row.isNew -> MonoTag("NEW", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8.5f)
                        row.changed -> MonoTag("EDITED", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8.5f)
                        else -> ReservedBadge()
                    }
                }
                Text(
                    "${row.ip} · ${row.mac}",
                    style = mono(10.5f, 500, Wrt.TextDim),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(WrtIcons.Pencil, null, Modifier.size(15.dp), tint = Wrt.TextTertiary)
        }
    }
    com.vivekkaushik.wrtpulse.ui.HorizontalHairline(Wrt.BorderRow)
}

@Composable
private fun ReservedBadge() {
    Row(
        Modifier
            .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(WrtIcons.Lock, null, Modifier.size(8.dp), tint = Wrt.Accent)
        Text("RESERVED", style = mono(8.5f, 600, Wrt.Accent))
    }
}

/** Design screen 24 — the add / edit reservation sheet. */
@Composable
private fun ReservationSheet(
    store: LanStore,
    row: ResvRow?,
    prefill: LanClient?,
    onCancel: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by remember(row, prefill) { mutableStateOf(row?.name ?: prefill?.name.orEmpty()) }
    var mac by remember(row, prefill) { mutableStateOf(row?.mac ?: prefill?.mac.orEmpty()) }
    var ip by remember(row, prefill) {
        mutableStateOf(row?.ip ?: prefill?.ip?.ifBlank { store.suggestedIp() } ?: store.suggestedIp())
    }
    var picking by remember { mutableStateOf(false) }
    val macOk = LanStore.validMac(mac)
    val placement = store.addressPlacement(ip)
    val ipOk = IpMath.valid(ip) && placement != null &&
        placement != "outside the LAN subnet" && placement != "the router's own address"
    val clash = store.takenAddresses(row?.section, row?.draftId, mac).contains(IpMath.parse(ip))

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Text(
            if (row == null) "Add IP reservation" else "Edit IP reservation",
            style = sans(16f, 650),
            modifier = Modifier.padding(top = 14.dp),
        )
        Column(Modifier.padding(top = 14.dp)) {
            FieldLabel("DEVICE NAME")
            FormTextField(name, { name = it })
        }
        Column(Modifier.padding(top = 12.dp)) {
            FieldLabel("MAC ADDRESS")
            FormTextField(mac, { mac = it.lowercase() }) {
                if (macOk) Icon(WrtIcons.Check, null, Modifier.size(15.dp), tint = Wrt.Green)
            }
            Row(
                Modifier.padding(top = 7.dp).clickable { picking = !picking },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(WrtIcons.WiredDevice, null, Modifier.size(12.dp), tint = Wrt.Accent)
                Text("Pick from active devices", style = sans(11.5f, 600, Wrt.Accent))
            }
            if (picking) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .border(1.dp, Wrt.BorderCard, RoundedCornerShape(11.dp))
                        .background(Wrt.BgDeep, RoundedCornerShape(11.dp)),
                ) {
                    store.activeClients().filterNot { it.reserved }.take(12).forEach { client ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mac = client.mac
                                    if (name.isBlank()) name = client.name
                                    if (client.ip.isNotBlank()) ip = client.ip
                                    picking = false
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                client.name.ifBlank { "Unknown" },
                                style = sans(12f, 600),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("${client.ip} · ${client.mac}", style = mono(9.5f, 500, Wrt.TextDim))
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(top = 12.dp)) {
            FieldLabel("IP ADDRESS")
            FormTextField(ip, { ip = it }) {
                if (ipOk && !clash) Icon(WrtIcons.Check, null, Modifier.size(15.dp), tint = Wrt.Green)
            }
            Row(
                Modifier.padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(if (ipOk && !clash) Wrt.Green else Wrt.Amber, 5.dp)
                Text(
                    when {
                        !IpMath.valid(ip) -> "Not an IPv4 address"
                        clash -> "Already held by another device or reservation"
                        placement == null -> "No LAN subnet to check against"
                        else -> (if (ipOk) "Free · " else "") + placement
                    },
                    style = sans(11f, 600, if (ipOk && !clash) Wrt.Green else Wrt.Amber),
                )
            }
        }
        Text(
            "$ uci ${if (row?.section != null) "set dhcp.${row.section}.ip" else "add dhcp host"}",
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (macOk && ipOk && !clash) {
            PrimaryButton(if (row == null) "Save reservation" else "Update reservation") {
                onSave(name, mac, ip)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(12.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (!macOk) "Enter a MAC address" else "Pick a free address in the subnet",
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
        Text(
            "Staged only — nothing runs until you apply.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Screen 25 — VLANs & port matrix
// ---------------------------------------------------------------------------

@Composable
private fun VlansTab(store: LanStore, onCreate: () -> Unit) {
    val ports = Parsers.switchPorts(store.devs)
    val rows = store.vlanRows()

    val swconfig = store.switchDev != null || store.swVlans.isNotEmpty()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (swconfig) {
            SwitchPortsCard(store)
        } else {
            PortsCard(ports, swconfig = false)
        }
        if (rows.isNotEmpty() || swconfig) {
            Row(
                Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("tap a port chip to cycle:", style = mono(9.5f, 500, Wrt.TextDim))
                Box(Modifier.background(Wrt.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("U untagged", style = mono(9f, 600, Wrt.OnAccent))
                }
                Box(
                    Modifier
                        .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("T tagged", style = mono(9f, 600, Wrt.Accent))
                }
            }
        }
        rows.forEach { row -> VlanCard(store, row, ports) }
        if (swconfig) {
            NoteCard(
                "These are port numbers on a switch chip, not sockets on the case. The CPU " +
                    "port is the router's own wire into the chip — a VLAN without it tagged is " +
                    "one the router cannot see at all."
            )
            store.swVlanRows().forEach { row -> SwVlanCard(store, row) }
        }
        if (rows.isEmpty() && !swconfig && store.loaded) {
            NoteCard(
                "This router has no VLAN configuration — every port is in one flat bridge. " +
                    "Creating a VLAN here adds a `bridge-vlan` section on ${store.lanBridge.ifEmpty { "the LAN bridge" }}; " +
                    "it carries no addresses until an interface names it as its device."
            )
        }
        if (swconfig || store.lanBridge.isNotEmpty()) {
            GhostButton("Create VLAN", onClick = onCreate)
        }
        store.problems().forEach { ProblemCard(it) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PortsCard(ports: List<NetDev>, swconfig: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SWITCH PORTS", size = 9.5f, tracking = 0.12)
            FlexSpacer()
            Text(
                "${ports.count { it.carrier }} up · ${ports.count { !it.carrier }} down",
                style = mono(10f, 500, Wrt.TextTertiary),
            )
        }
        if (ports.isEmpty()) {
            Text(
                "No switch ports — this board's netdevs are all virtual.",
                style = sans(11f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ports.forEach { port ->
                Column(
                    // Five sockets share the row; one or two would look like a progress bar
                    // stretched across the card, so a short row keeps the chip's own width.
                    if (ports.size >= 3) Modifier.weight(1f) else Modifier.width(84.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .border(
                                1.dp,
                                if (port.carrier) Wrt.Green.copy(alpha = 0.45f) else Wrt.BorderCard,
                                RoundedCornerShape(8.dp),
                            )
                            .background(Wrt.BgDeep, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StatusDot(if (port.carrier) Wrt.Green else Wrt.DotOff, 6.dp, pulse = port.carrier)
                    }
                    Text(
                        port.name.uppercase(),
                        style = mono(9f, 600, if (port.carrier) Wrt.TextPrimary else Wrt.TextDim),
                        maxLines = 1,
                    )
                    Text(
                        port.speedMbps?.let { if (it >= 1000) "${it / 1000}G" else "${it}M" } ?: "—",
                        style = mono(8.5f, 500, if (port.carrier) Wrt.TextDim else Wrt.DotOff),
                    )
                }
            }
        }
        if (swconfig) {
            // On a swconfig board the sockets live behind the switch chip and never appear as
            // netdevs — the kernel only shows the CPU port. Saying so beats drawing one port
            // and calling it the panel.
            Text(
                "The sockets sit behind the switch chip and are not netdevs — the kernel " +
                    "exposes only the CPU port. `swconfig dev switch0 show` has the per-socket " +
                    "link state.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

/**
 * The chip's sockets with the link state it reports.
 *
 * On a swconfig board the sockets are not netdevs, so this is the only place their state
 * exists — and the only way to tell which number is which hole on the case: plug a cable in
 * and watch which one lights up.
 */
@Composable
private fun SwitchPortsCard(store: LanStore) {
    val dev = store.switchDev
    val sockets = store.switchSockets()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SWITCH PORTS — ${dev?.name?.uppercase() ?: "SWCONFIG"}", size = 9.5f, tracking = 0.12)
            FlexSpacer()
            Text(
                "${sockets.count { store.socketUp(it) }} up · ${sockets.count { !store.socketUp(it) }} down",
                style = mono(10f, 500, Wrt.TextTertiary),
            )
        }
        if (sockets.isEmpty()) {
            Text(
                "The switch reported no ports.",
                style = sans(11f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sockets.forEach { port ->
                val up = store.socketUp(port)
                Column(
                    if (sockets.size >= 3) Modifier.weight(1f) else Modifier.width(84.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .border(
                                1.dp,
                                if (up) Wrt.Green.copy(alpha = 0.45f) else Wrt.BorderCard,
                                RoundedCornerShape(8.dp),
                            )
                            .background(Wrt.BgDeep, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        StatusDot(if (up) Wrt.Green else Wrt.DotOff, 6.dp, pulse = up)
                    }
                    Text(
                        "P$port",
                        style = mono(9f, 600, if (up) Wrt.TextPrimary else Wrt.TextDim),
                        maxLines = 1,
                    )
                    Text(
                        store.socketSpeed(port)?.let { if (it >= 1000) "${it / 1000}G" else "${it}M" } ?: "—",
                        style = mono(8.5f, 500, if (up) Wrt.TextDim else Wrt.DotOff),
                    )
                }
            }
        }
        dev?.cpuPort?.let { cpu ->
            Text(
                "Port $cpu is the CPU port — the wire to the router itself, not a socket. " +
                    (dev.model.takeIf { it.isNotEmpty() }?.let { "$it. " } ?: "") +
                    "Plug a cable in and re-read to learn which number is which hole.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}

/** One swconfig VLAN, with a chip per socket and the CPU port shown apart from them. */
@Composable
private fun SwVlanCard(store: LanStore, row: SwVlanRow) {
    val block = store.swVlanDeleteBlock(row)
    val cpu = store.switchDev?.cpuPort
    val cpuState = cpu?.let { store.swStateOf(row, it) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (row.changed || row.isNew) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard,
                RoundedCornerShape(13.dp),
            )
            .background(if (row.deleting) Wrt.Red.copy(alpha = 0.05f) else Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VLAN ${row.vlan}", style = sans(13.5f, 650))
            if (row.vlan == store.lanSwVlan) Text("LAN", style = sans(11f, 500, Wrt.TextSecondary))
            if (row.isNew) MonoTag("NEW", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8.5f)
            if (row.deleting) MonoTag("DELETING", Wrt.Red, Wrt.Red.copy(alpha = 0.5f), 8.5f)
            FlexSpacer()
            Text(row.device, style = mono(9.5f, 500, Wrt.TextDim))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            store.switchSockets().forEach { port ->
                val state = store.swStateOf(row, port)
                PortChip(
                    label = "P$port " + when (state) {
                        PortState.Off -> "—"
                        PortState.Untagged -> "U"
                        PortState.Tagged -> "T"
                    },
                    state = state,
                    enabled = !row.deleting,
                    modifier = Modifier.weight(1f),
                ) { store.cycleSwPort(row, port) }
            }
        }
        if (cpu != null && cpuState != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("CPU", style = mono(9f, 600, Wrt.TextDim))
                PortChip(
                    label = "P$cpu " + when (cpuState) {
                        PortState.Off -> "—"
                        PortState.Untagged -> "U"
                        PortState.Tagged -> "T"
                    },
                    state = cpuState,
                    enabled = !row.deleting,
                    modifier = Modifier.width(96.dp),
                ) { store.cycleSwPort(row, cpu) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (block != null) {
                Text(block, style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.weight(1f))
            } else {
                Text(
                    "ports '${com.vivekkaushik.wrtpulse.ops.Parsers.swPortsValue(row.ports)}'",
                    style = mono(9.5f, 500, Wrt.TextDim),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        row.deleting -> "Undo delete"
                        row.draftId != null -> "Discard"
                        else -> "Delete VLAN"
                    },
                    style = sans(11.5f, 600, if (row.deleting) Wrt.Accent else Wrt.Red),
                    modifier = Modifier
                        .clickable {
                            when {
                                row.deleting -> row.section?.let { store.undoDelete("network.$it") }
                                row.draftId != null -> store.removeSwVlanDraft(row.draftId)
                                row.section != null -> store.stageDelete("network.${row.section}")
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The matrix cell: untagged filled, tagged outlined, off hairlined. */
@Composable
private fun PortChip(
    label: String,
    state: PortState,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(34.dp)
            .let {
                when (state) {
                    PortState.Untagged -> it.background(Wrt.Accent, RoundedCornerShape(8.dp))
                    PortState.Tagged -> it.border(1.dp, Wrt.Accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    PortState.Off -> it.border(1.dp, Wrt.BorderFaint, RoundedCornerShape(8.dp))
                }
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = mono(
                10f, 600,
                when (state) {
                    PortState.Untagged -> Wrt.OnAccent
                    PortState.Tagged -> Wrt.Accent
                    PortState.Off -> Wrt.TextDim
                },
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun VlanCard(store: LanStore, row: VlanRow, ports: List<NetDev>) {
    val block = store.vlanDeleteBlock(row)
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (row.changed || row.isNew) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard,
                RoundedCornerShape(13.dp),
            )
            .background(if (row.deleting) Wrt.Red.copy(alpha = 0.05f) else Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VLAN ${row.vlan}", style = sans(13.5f, 650))
            if (row.vlan == store.lanVlan && row.device == store.lanBridge) {
                Text("LAN", style = sans(11f, 500, Wrt.TextSecondary))
            }
            if (row.isNew) MonoTag("NEW", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f), 8.5f)
            if (row.deleting) MonoTag("DELETING", Wrt.Red, Wrt.Red.copy(alpha = 0.5f), 8.5f)
            FlexSpacer()
            Text(
                "${row.netdev} · ${store.clientsOn(row.netdev)} clients",
                style = mono(9.5f, 500, Wrt.TextDim),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ports.forEach { port ->
                val state = store.stateOf(row, port.name)
                val label = port.name.uppercase().replace("LAN", "L") + " " + when (state) {
                    PortState.Off -> "—"
                    PortState.Untagged -> "U"
                    PortState.Tagged -> "T"
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .let {
                            when (state) {
                                PortState.Untagged -> it.background(Wrt.Accent, RoundedCornerShape(8.dp))
                                PortState.Tagged -> it.border(1.dp, Wrt.Accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                PortState.Off -> it.border(1.dp, Wrt.BorderFaint, RoundedCornerShape(8.dp))
                            }
                        }
                        .clickable(enabled = !row.deleting) { store.cyclePort(row, port.name) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = mono(
                            10f, 600,
                            when (state) {
                                PortState.Untagged -> Wrt.OnAccent
                                PortState.Tagged -> Wrt.Accent
                                PortState.Off -> Wrt.TextDim
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (block != null) {
                Text(block, style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.weight(1f))
            } else {
                FlexSpacer()
                Text(
                    when {
                        row.deleting -> "Undo delete"
                        row.draftId != null -> "Discard"
                        else -> "Delete VLAN"
                    },
                    style = sans(11.5f, 600, if (row.deleting) Wrt.Accent else Wrt.Red),
                    modifier = Modifier
                        .clickable {
                            when {
                                row.deleting -> row.section?.let { store.undoDelete("network.$it") }
                                row.draftId != null -> store.removeVlanDraft(row.draftId)
                                row.section != null -> store.stageDelete("network.${row.section}")
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CreateVlanSheet(store: LanStore, onCancel: () -> Unit, onCreate: (Int) -> Unit) {
    var text by remember { mutableStateOf(store.freeVlanId().toString()) }
    val id = text.toIntOrNull()
    val taken = store.vlanRows().any { it.vlan == id && it.device == store.lanBridge }
    val ok = id != null && id in 1..4094 && !taken

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Text("Create VLAN", style = sans(16f, 650), modifier = Modifier.padding(top = 14.dp))
        Text(
            "On ${store.lanBridge.ifEmpty { "the LAN bridge" }}. It appears as " +
                "${store.lanBridge}.${text} and carries nothing until ports are added and an " +
                "interface names it.",
            style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(Modifier.padding(top = 14.dp)) {
            FieldLabel("VLAN ID")
            FormTextField(text, { text = it.filter { c -> c.isDigit() }.take(4) })
            Text(
                when {
                    id == null -> "A VLAN id is a number."
                    id !in 1..4094 -> "The standard allows 1 to 4094."
                    taken -> "${store.lanBridge} already has VLAN $id."
                    else -> "Free on ${store.lanBridge}."
                },
                style = sans(10.5f, 400, if (ok) Wrt.TextDim else Wrt.Red),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        if (ok) {
            PrimaryButton("Stage VLAN $id") { onCreate(id!!) }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Pick a free VLAN id", style = sans(13.5f, 650, Wrt.TextDim))
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

// ---------------------------------------------------------------------------
// Review, and the one apply that ends the session
// ---------------------------------------------------------------------------

@Composable
private fun LanReviewSheet(store: LanStore?, onApply: () -> Unit, onRevertAll: () -> Unit) {
    if (store == null) return
    val problems = store.problems()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Review changes", style = sans(16f, 650))
            FlexSpacer()
            Text("uci batch · ${store.ops().size} ops", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Text(
            "These commands run on the router when you apply.",
            style = sans(12f, 400, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 4.dp),
        )
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
        problems.forEach { ProblemCard(it, top = 10.dp) }
        store.error?.let {
            Text(
                it,
                style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        val label = when {
            store.applying -> "Applying…"
            store.pendingCount == 1 -> "Apply 1 change"
            else -> "Apply ${store.pendingCount} changes"
        }
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
            Text("Revert all", style = sans(13f, 600, Wrt.Red))
        }
        Text(
            "Nothing runs until you apply.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The end state of a subnet move. There is no session left to re-read the router with, and
 * the phone is still on the old subnet, so the screen says exactly that instead of showing
 * stale numbers.
 */
@Composable
private fun MovedPanel(address: String, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(WrtIcons.Lan, null, Modifier.size(28.dp), tint = Wrt.Accent)
        Text("The router moved to $address", style = sans(17f, 650), modifier = Modifier.padding(top = 14.dp))
        Text(
            "The uci batch committed and netifd reloaded, which took this connection with it. " +
                "This phone still holds a lease on the old subnet — turn Wi-Fi off and on to get " +
                "one on the new one, then connect to $address.",
            style = sans(12.5f, 400, Wrt.TextSecondary, lineHeight = 19.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Host keys are pinned per address, so $address is a first contact and its " +
                "fingerprint is shown for you to accept. If a different router once answered " +
                "on $address, the changed-key warning appears instead — that is the old pin, " +
                "not an interception.",
            style = sans(12.5f, 400, Wrt.TextDim, lineHeight = 19.sp),
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Back to routers", onClick = onDone)
    }
}

// ---------------------------------------------------------------------------
// Small shared pieces — also used by the WAN screens, which are the same shapes
// ---------------------------------------------------------------------------

@Composable
internal fun FactsCard(facts: List<Pair<String, String>>) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        SectionLabel(label, size = 9f, tracking = 0.12)
                        Text(
                            value.ifBlank { "—" },
                            style = mono(13f, 600),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (pair.size == 1) FlexSpacer()
            }
        }
    }
}

@Composable
internal fun MaskChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .let {
                if (selected) it.background(Wrt.Accent, RoundedCornerShape(9.dp))
                else it.border(1.dp, Wrt.BorderCard, RoundedCornerShape(9.dp))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Text(text, style = mono(10.5f, if (selected) 600 else 500, if (selected) Wrt.OnAccent else Wrt.TextSecondary))
    }
}

@Composable
internal fun RemovableChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(9.dp))
            .background(Wrt.BgCard, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(text, style = mono(11f, 500))
        Icon(WrtIcons.Close, "remove", Modifier.size(10.dp).clickable(onClick = onRemove), tint = Wrt.TextDim)
    }
}

@Composable
internal fun DashedChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(WrtIcons.Plus, null, Modifier.size(11.dp), tint = Wrt.TextTertiary)
        Text(text, style = sans(11.5f, 600, Wrt.TextTertiary))
    }
}

@Composable
internal fun SegmentTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(30.dp)
            .let { if (selected) it.background(Wrt.TermTabActive, RoundedCornerShape(8.dp)) else it }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = sans(11.5f, if (selected) 600 else 500, if (selected) Wrt.TextPrimary else Wrt.TextDim),
            maxLines = 1,
        )
    }
}

@Composable
internal fun NoteCard(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        // Centred on the card: hanging off the first line looks like a mistake once the note
        // runs to three or four lines, which most of them do.
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WrtIcons.Warning, null, Modifier.size(15.dp), tint = Wrt.Amber)
        Text(text, style = sans(11f, 400, Wrt.AmberText, lineHeight = 17.sp))
    }
}

@Composable
internal fun ProblemCard(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = top)
            .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .background(Wrt.Red.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WrtIcons.Warning, null, Modifier.size(15.dp), tint = Wrt.Red)
        Text(text, style = sans(11f, 500, Wrt.Red, lineHeight = 17.sp))
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = sans(12f, 500, Wrt.TextDim),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 22.dp),
        textAlign = TextAlign.Center,
    )
}

/** One field, validated as it is typed — what the design's chip rows add through. */
@Composable
internal fun TextEntryDialog(
    title: String,
    hint: String,
    validate: (String) -> Boolean,
    error: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val ok = validate(text.trim())
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderInput, RoundedCornerShape(16.dp))
                .background(Wrt.BgSheet, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(title, style = sans(15f, 650))
            Box {
                FormTextField(text, { text = it })
                if (text.isEmpty()) {
                    Text(
                        hint,
                        style = mono(12.5f, 500, Wrt.TextFaint),
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                    )
                }
            }
            if (text.isNotEmpty() && !ok) {
                Text(error, style = sans(10.5f, 500, Wrt.Red), modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { GhostButton("Cancel", onClick = onDismiss) }
                Box(Modifier.weight(1f)) {
                    if (ok) PrimaryButton("Add") { onDone(text.trim()) }
                    else GhostButton("Add", onClick = {})
                }
            }
        }
    }
}
