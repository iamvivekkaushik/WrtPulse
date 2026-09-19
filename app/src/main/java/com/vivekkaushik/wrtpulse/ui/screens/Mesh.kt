package com.vivekkaushik.wrtpulse.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.BackupStore
import com.vivekkaushik.wrtpulse.data.JoinOutcome
import com.vivekkaushik.wrtpulse.data.JoinStep
import com.vivekkaushik.wrtpulse.data.MeshJoin
import com.vivekkaushik.wrtpulse.data.MeshStore
import com.vivekkaushik.wrtpulse.data.NodeSync
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ops.Backhaul
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.WpadSwap
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.LiveRefresh
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.PullToRefresh
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything the mesh screens need from the app: the stores for the router in hand, the saved
 * rows, and the writes only the app can make. Bundled so the Network tab's signature does
 * not grow by nine parameters.
 */
class MeshHooks(
    val store: MeshStore?,
    val backup: BackupStore?,
    val saved: List<RouterEntity>,
    /** The connected router's own saved row. */
    val current: RouterEntity?,
    /** A join of the connected router to [primary], or null when the primary's profile cannot be read. */
    val newJoin: (primary: RouterEntity) -> MeshJoin?,
    val persist: suspend (JoinOutcome) -> Unit,
    /** The join finished: reconnect to the node at its new address. */
    val onJoined: (RouterEntity) -> Unit,
    /** The node restored its pre-mesh config and is rebooting; [lanAddress] is where it comes back. */
    val onLeft: (lanAddress: String?) -> Unit,
    val onOpenRouter: (RouterEntity) -> Unit,
    /** Add-a-node guide: connect to a saved router and open the join for this primary there. */
    val onConnectToJoin: (node: RouterEntity, primary: RouterEntity) -> Unit = { _, _ -> },
    /** Add-a-node guide: onboard a new router, then open the join for this primary there. */
    val onAddRouterToJoin: (primary: RouterEntity) -> Unit = {},
    /** Set once the app has connected to a router that should join this primary straight away. */
    val pendingJoin: RouterEntity? = null,
    val consumePendingJoin: () -> Unit = {},
    /**
     * Reads the primary live and returns its current profile, or null when it cannot be
     * reached from where the phone is. Through the node's WAN cable it usually can.
     */
    val refreshProfile: suspend (primary: RouterEntity) -> com.vivekkaushik.wrtpulse.ops.MeshProfile? = { null },
    /** Attaches a mesh peer's MAC to a saved node that has none, so the peer list can name it. */
    val adoptPeer: suspend (node: RouterEntity, mac: String) -> Unit = { _, _ -> },
    /** A short session to a node on its saved credentials, or null when it has none. Closed by the caller. */
    val openNode: suspend (RouterEntity) -> com.vivekkaushik.wrtpulse.net.RouterSession? = { null },
    /** A cable-only setup from this primary's side, or null when not connected. */
    val newSetup: () -> com.vivekkaushik.wrtpulse.data.NodeSetup? = { null },
    /** The join of a router reached through [newSetup]'s hop, once it has answered. */
    val newJoinVia: (com.vivekkaushik.wrtpulse.data.NodeSetup) -> MeshJoin? = { null },
    /** Writes the saved row for a router that had none before the join — created on the way. */
    val persistVia: suspend (setup: com.vivekkaushik.wrtpulse.data.NodeSetup, join: MeshJoin, outcome: JoinOutcome) -> Unit = { _, _, _ -> },
    /** Records that the connected router is a node of [primary] — its config already says so. */
    val markNode: suspend (node: RouterEntity, primary: RouterEntity, backhaul: Backhaul, meshMac: String?) -> Unit = { _, _, _, _ -> },
)

// ---------------------------------------------------------------------------
// The Mesh page
// ---------------------------------------------------------------------------

/** Which of the "for every node" rows is open as a sheet. */
private enum class MeshSheet { Handoff, Link, Extras }

/** True inside a bottom sheet: [MeshCard] then draws as a sheet section, not a boxed card. */
private val LocalInSheet = compositionLocalOf { false }

@Composable
fun MeshScreen(
    hooks: MeshHooks,
    latencyMs: Int?,
    routerName: String,
    onBack: () -> Unit,
    onJoin: (RouterEntity) -> Unit,
    onLeave: () -> Unit,
    onAddNode: () -> Unit = {},
) {
    val store = hooks.store
    val current = hooks.current
    val nodes = hooks.saved.filter { store != null && it.meshPrimary == store.identity }
    LaunchedEffect(store, nodes) { store?.nodeEntities = nodes }
    LiveRefresh(store, MESH_REFRESH_MS)
    // A node the app joined but never got the MAC of — the join was closed early, or its last
    // step failed — is claimed by the one unclaimed peer, when there is exactly one of each.
    // Once the nodes have answered a ping, ask each what Wi-Fi it carries.
    val onlineNodes = store?.nodes()?.filter { it.online }?.map { it.entity.identity }.orEmpty()
    LaunchedEffect(store, onlineNodes) {
        if (store != null && onlineNodes.isNotEmpty()) store.checkNodes(hooks.openNode)
    }
    val strays = store?.strayPeers().orEmpty()
    LaunchedEffect(strays.map { it.mac }, nodes) {
        val unnamed = nodes.filter { it.meshMac == null && it.meshBackhaul == Backhaul.Wireless.uci }
        if (strays.size == 1 && unnamed.size == 1) hooks.adoptPeer(unnamed.single(), strays.single().mac)
    }
    // The check logs into each node and reads its mesh point's MAC; a record that differs is stale
    // (mac80211 re-deals interface MACs when interfaces are added) and is brought up to date.
    val liveMacs = store?.liveMeshMacs?.toMap().orEmpty()
    LaunchedEffect(liveMacs, nodes) {
        nodes.forEach { n -> liveMacs[n.identity]?.let { live -> if (live != n.meshMac) hooks.adoptPeer(n, live) } }
    }
    var sheet by remember { mutableStateOf<MeshSheet?>(null) }
    // Kept through the sheet's exit animation, so it does not slide away empty.
    var shown by remember { mutableStateOf(MeshSheet.Handoff) }
    sheet?.let { shown = it }
    val asNode = current?.isMeshNode == true || (store != null && store.loaded && store.configuredAsNode && current != null)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
            FormTopBar("Mesh", onBack) {
                latencyMs?.let { MonoTag("$it ms", size = 10f) }
                MonoTag(routerName, size = 10.5f)
            }
            PullToRefresh(Modifier.weight(1f), enabled = store != null, onRefresh = { store?.takeIf { !it.applying && !it.refreshPaused }?.load() }) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when {
                        store == null -> Text("Not connected.", style = sans(12f, 400, Wrt.TextDim))
                        current?.isMeshNode == true -> NodeView(hooks, store, current, onLeave)
                        // The router says it is a node and the phone has no record of it: offer
                        // to write the record, not to join again.
                        store.loaded && store.configuredAsNode && current != null -> NodeRecoveryCard(hooks, store, current)
                        else -> PrimaryView(store, hooks, nodes, onJoin, openSheet = { sheet = it })
                    }
                }
            }
            if (store != null && !asNode) {
                Column(Modifier.fillMaxWidth().background(Wrt.BgScreen)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderRow))
                    PrimaryButton("Add a node", Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp), onClick = onAddNode)
                }
            }
        }
        SheetHost(visible = sheet != null, onDismiss = { sheet = null }) {
            if (store != null) CompositionLocalProvider(LocalInSheet provides true) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp)) {
                    when (shown) {
                        MeshSheet.Handoff -> RoamingCard(store)
                        MeshSheet.Link -> MeshLinkCard(store)
                        MeshSheet.Extras -> ExtrasCard(store)
                    }
                }
            }
        }
    }
}

/** The page on a primary — or a router that could become one: the live map first, then what needs a tap. */
@Composable
private fun PrimaryView(
    store: MeshStore,
    hooks: MeshHooks,
    nodes: List<RouterEntity>,
    onJoin: (RouterEntity) -> Unit,
    openSheet: (MeshSheet) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val meshNodes = store.nodes()
    val strays = store.strayPeers()
    val total = meshNodes.size + strays.size
    val offline = meshNodes.filter { !it.online }
    val stale = meshNodes.filter { store.nodeSync[it.entity.identity] is NodeSync.OutOfDate }
    val unread = meshNodes.filter { store.nodeSync[it.entity.identity] is NodeSync.Unreachable }
    fun syncOf(n: com.vivekkaushik.wrtpulse.data.MeshNode) = when (store.nodeSync[n.entity.identity]) {
        is NodeSync.InSync -> TopoSync.Ok
        is NodeSync.OutOfDate -> TopoSync.Stale
        else -> TopoSync.Unknown
    }
    val topo = meshNodes.map { n ->
        TopoNode(n.entity.name, n.entity.meshBackhaul != Backhaul.Wireless.uci, n.online, n.signalDbm, syncOf(n))
    } + strays.map { p -> TopoNode("peer", wired = false, online = p.established, signalDbm = p.signalDbm, sync = TopoSync.Unknown) }

    val aps = store.lanAps
    val handoff = when {
        !store.loaded -> "…" to Wrt.TextDim
        aps.isEmpty() -> "no SSID yet" to Wrt.Amber
        store.roamingOn && store.staleDomains.isNotEmpty() -> "needs repair" to Wrt.Amber
        store.roamingOn -> "on · ${aps.size} SSID${if (aps.size == 1) "" else "s"}" to Wrt.Green
        store.roamingImpossible -> "needs a password" to Wrt.Amber
        else -> "off" to Wrt.TextDim
    }
    val radio = store.meshRadio
    val link = when {
        !store.loaded -> "…" to Wrt.TextDim
        store.swapping -> "installing…" to Wrt.Accent
        !store.meshCapable -> "needs a package" to Wrt.Amber
        store.meshIface == null -> "off" to Wrt.TextDim
        store.meshUp -> ("up" + (radio?.let { " · ${it.band} ch ${it.channel}" } ?: "")) to Wrt.Green
        else -> "configured, not up" to Wrt.Amber
    }
    val extras = store.profile?.extras.orEmpty()
    val trunk = when {
        !store.loaded -> "…" to Wrt.TextDim
        extras.isEmpty() -> "none on this router" to Wrt.TextDim
        store.trunksReady -> "${extras.size} · trunked" to Wrt.Green
        else -> "${extras.size} · not trunked" to Wrt.Amber
    }
    val plural = if (total == 1) "" else "s"
    val names = { list: List<com.vivekkaushik.wrtpulse.data.MeshNode> -> list.joinToString(", ") { it.entity.name } }
    val summaryBits = listOf("hand-off ${if (store.roamingOn) "on" else "off"}", "mesh link ${if (store.meshUp) "up" else if (store.meshIface != null) "down" else "off"}")
    val (headline, line, tone, pulse) = when {
        !store.loaded && store.error == null -> HeroState("Mesh", "reading the router…", Wrt.TextDim, true)
        store.error != null -> HeroState("Could not read the router", store.error!!, Wrt.Red, false)
        total == 0 -> HeroState("Just this router so far", summaryBits.joinToString(" · "), Wrt.TextDim, false)
        store.pushing -> HeroState(
            "Pushing Wi-Fi to ${meshNodes.size} node${if (meshNodes.size == 1) "" else "s"}…",
            "copying SSIDs, channels and hand-off", Wrt.Accent, true,
        )
        offline.isNotEmpty() -> HeroState(
            "${offline.size} of $total node$plural not answering",
            offline.joinToString(" · ") { "${it.entity.name} · ${Backhaul.of(it.entity.meshBackhaul)?.label?.lowercase() ?: "node"} · no reply" },
            Wrt.Red, false,
        )
        stale.isNotEmpty() -> HeroState(
            "$total node$plural up · Wi-Fi out of date on ${stale.size}",
            (summaryBits + (store.nodeSync[stale.first().entity.identity] as NodeSync.OutOfDate).summary).joinToString(" · "),
            Wrt.Amber, false,
        )
        store.syncing -> HeroState("$total node$plural up", (summaryBits + "checking Wi-Fi…").joinToString(" · "), Wrt.Green, true)
        unread.isNotEmpty() || meshNodes.any { store.nodeSync[it.entity.identity] !is NodeSync.InSync } ->
            HeroState("$total node$plural up", summaryBits.joinToString(" · "), Wrt.Green, true)
        else -> HeroState("$total node$plural · everything in sync", (summaryBits + "Wi-Fi in sync").joinToString(" · "), Wrt.Green, true)
    }
    Hero(
        height = if (total > 1) 160.dp else 140.dp,
        headline = headline, line = line, tone = tone, pulse = pulse,
    ) { MeshTopology(hooks.current?.name ?: "this router", topo, store.pushing, height = if (total > 1) 160.dp else 140.dp) }

    // One thing that needs a tap, the most urgent first; nothing while a push is running.
    val held = store.heldSetupPort
    val ready = store.roamingOn || store.meshIface != null
    if (store.loaded && !store.pushing) when {
        held != null -> Attention(
            "A socket is held for a node setup",
            "${if (held.startsWith("sw:")) "Port ${held.removePrefix("sw:")}" else held} is kept apart from the LAN by an add-a-node that did not finish. It goes back on its own once nothing is plugged into it, or now — unplug the cable first, because whatever is on it is not a node.",
            "Release it", note = store.setupNotice,
        ) { scope.launch { store.releaseSetupPort() } }
        offline.isNotEmpty() -> {
            val wireless = offline.any { it.entity.meshBackhaul == Backhaul.Wireless.uci }
            Attention(
                if (offline.size == 1) "${offline.single().entity.name} has not answered" else "${names(offline)} have not answered",
                (if (offline.size == 1) "Its" else "Their") + (if (wireless) " mesh link is down. Check it has power and is within reach of the 5 GHz signal." else " cable link is down. Check it has power and the cable is in at both ends."),
                "Check again",
            ) { scope.launch { store.load(); store.checkNodes(hooks.openNode) } }
        }
        stale.isNotEmpty() -> Attention(
            "${names(stale)} still ${if (stale.size == 1) "has" else "have"} the old Wi-Fi",
            stale.joinToString(" ") { "${it.entity.name}: ${(store.nodeSync[it.entity.identity] as NodeSync.OutOfDate).summary}." } +
                " Pushing rewrites SSIDs, channels and hand-off from what is here now; each node reloads its Wi-Fi for about 15 s.",
            "Push Wi-Fi to ${stale.size} node${if (stale.size == 1) "" else "s"}",
        ) { if (!store.syncing) scope.launch { store.pushNodes(hooks.openNode, all = true) } }
        store.roamingOn && store.staleDomains.isNotEmpty() -> Attention(
            "Hand-off drifted",
            "${store.staleDomains.joinToString(", ") { it.ssid }} ${if (store.staleDomains.size == 1) "was" else "were"} renamed after hand-off went on, so the hand-off domain still belongs to the old name. Phones do a full reconnect instead of a hand-off until it is rewritten. Wi-Fi drops for about 15 seconds.",
            if (store.applying) "Repairing…" else "Repair hand-off",
        ) { if (!store.applying) scope.launch { store.repairRoaming() } }
        meshNodes.isNotEmpty() && !ready -> Attention(
            "Hand-off is off",
            "The nodes carry this router's SSIDs, but phones only move between them without dropping once hand-off is on here.",
            "Turn on hand-off", color = Wrt.Accent,
        ) { openSheet(MeshSheet.Handoff) }
    }
    store.syncNotice?.let { NoteLine(it, Wrt.Accent) }

    val candidates = hooks.saved.filter { it.identity != store.identity && it.meshProfile != null && !it.isMeshNode }
    // A plain router that could become a node gets the join offer; a router already acting
    // as a primary is never offered to join anything.
    if (store.loaded && !store.actsAsPrimary && nodes.isEmpty() && candidates.isNotEmpty()) JoinCard(candidates, onJoin)

    if (total > 0) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("nodes")
            FlexSpacer()
            if (meshNodes.isNotEmpty()) {
                TextButton(if (store.pushing) "Pushing…" else "Push Wi-Fi") { if (!store.syncing) scope.launch { store.pushNodes(hooks.openNode, all = true) } }
                Spacer(Modifier.size(14.dp))
            }
            TextButton(if (store.syncing && !store.pushing) "Checking…" else "Check again") { if (!store.syncing) scope.launch { store.checkNodes(hooks.openNode) } }
        }
        RowsCard {
            meshNodes.forEachIndexed { i, node ->
                val sync = store.nodeSync[node.entity.identity]
                val (chip, chipTone) = when {
                    store.pushing && sync is NodeSync.OutOfDate -> "pushing…" to Wrt.Accent
                    !node.online -> "no reply" to Wrt.TextDim
                    sync is NodeSync.InSync -> "in sync" to Wrt.Green
                    sync is NodeSync.OutOfDate -> sync.summary to Wrt.Amber
                    sync is NodeSync.Unreachable -> "unread" to Wrt.TextDim
                    store.syncing -> "checking" to Wrt.TextDim
                    else -> "unknown" to Wrt.TextDim
                }
                Row(
                    Modifier.fillMaxWidth().then(if (i > 0) Modifier.rowDivider() else Modifier).clickable { hooks.onOpenRouter(node.entity) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusDot(if (node.online) Wrt.Green else Wrt.TextDim, 8.dp, pulse = node.online)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(node.entity.name, style = sans(13f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                node.entity.host,
                                Backhaul.of(node.entity.meshBackhaul)?.label?.lowercase(),
                                node.rttMs?.let { "%.0f ms".format(it) } ?: if (node.online) null else "no reply",
                                node.signalDbm?.let { "$it dBm" },
                            ).joinToString(" · "),
                            style = mono(11f, 400, Wrt.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Chip(chip, chipTone)
                    Icon(WrtIcons.ChevronRight, null, Modifier.size(16.dp), tint = Wrt.TextDim)
                }
            }
            // Peers the mesh point sees that no saved row claims are nodes too — joined by hand,
            // or by this app on a phone that is gone. Listed, not hidden.
            val unnamed = nodes.filter { it.meshMac == null && it.meshBackhaul == Backhaul.Wireless.uci }
            strays.forEachIndexed { i, peer ->
                Row(
                    Modifier.fillMaxWidth().then(if (i > 0 || meshNodes.isNotEmpty()) Modifier.rowDivider() else Modifier).padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).border(1.dp, if (peer.established) Wrt.Green else Wrt.Amber, RoundedCornerShape(50)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(peer.mac, style = mono(11f, 400, Wrt.TextSecondary))
                        Text(
                            listOfNotNull("mesh peer", peer.signalDbm?.let { "$it dBm" }, if (peer.established) "not added by this app" else "linking").joinToString(" · "),
                            style = mono(10f, 400, Wrt.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (unnamed.size == 1) {
                        GhostButton("Adopt", Modifier.width(72.dp), border = Wrt.Accent.copy(alpha = 0.5f), textColor = Wrt.Accent) {
                            scope.launch { hooks.adoptPeer(unnamed.single(), peer.mac) }
                        }
                    }
                }
            }
        }
        if (strays.isNotEmpty() && nodes.none { it.meshMac == null && it.meshBackhaul == Backhaul.Wireless.uci }) {
            Text("A peer not saved in this app joins the list once it is added from the router list.", style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp), modifier = Modifier.padding(horizontal = 2.dp))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("for every node", Modifier.padding(horizontal = 2.dp))
        RowsCard {
            SettingRow("Hand-off between nodes", handoff.first, handoff.second) { openSheet(MeshSheet.Handoff) }
            SettingRow("Wireless mesh link", link.first, link.second, pulse = store.swapping, divider = true) { openSheet(MeshSheet.Link) }
            SettingRow("Guest and IoT on nodes", trunk.first, trunk.second, divider = true) { openSheet(MeshSheet.Extras) }
        }
        Text(
            "Nodes mirror every SSID this router carries — LAN, guest and IoT — on the bands they have; guest and IoT traffic rides the backhaul in a VLAN of its own back to this router.",
            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private data class HeroState(val headline: String, val line: String, val tone: Color, val pulse: Boolean)

/** The map in its well, then the one line that says whether the mesh is fine. */
@Composable
private fun Hero(height: Dp, headline: String, line: String, tone: Color, pulse: Boolean, topology: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(14.dp)).background(Wrt.BgDeep)) { topology() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = sans(22f, 650, lineHeight = 28.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(tone, 8.dp, pulse = pulse, periodMs = 1600)
                Text(line, style = mono(11f, 500, tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The amber card: one title, one line of why, one button. Appears only when something needs a tap. */
@Composable
private fun Attention(title: String, body: String, label: String, color: Color = Wrt.Amber, note: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, color, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = sans(13.5f, 650))
        Text(body, style = sans(11.5f, 400, Wrt.TextSecondary, lineHeight = 17.sp))
        note?.let { Text(it, style = sans(11f, 400, Wrt.Amber, lineHeight = 16.sp)) }
        PrimaryButton(label, color = color, onClick = onClick)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = mono(10f, 500, Wrt.TextDim, letterSpacing = 1.sp), modifier = modifier)
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Text(text, style = sans(11f, 500, Wrt.Accent), modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun Chip(text: String, tone: Color) {
    Box(
        Modifier
            .border(1.dp, if (tone == Wrt.TextDim) Wrt.BorderCard else tone.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .widthIn(max = 120.dp),
    ) { Text(text, style = mono(10f, 500, tone), maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A bordered card whose children are full-width rows separated by hairlines. */
@Composable
private fun RowsCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
    ) { content() }
}

/** A hairline along the top edge, between rows of a [RowsCard]. */
private fun Modifier.rowDivider(): Modifier = drawBehind {
    drawLine(Wrt.BorderRow, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
}

/** One "for every node" row: a title, its state on the right in mono, a chevron. */
@Composable
private fun SettingRow(title: String, value: String, tone: Color, pulse: Boolean = false, divider: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (divider) Modifier.rowDivider() else Modifier).clickable(onClick = onClick).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = sans(13f, 400), maxLines = 1)
        Text(
            value, style = mono(11f, 500, tone), modifier = Modifier.weight(1f).pulsing(pulse),
            textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Icon(WrtIcons.ChevronRight, null, Modifier.size(16.dp), tint = Wrt.TextDim)
    }
}

/** Breathes the alpha of whatever it is on, for text that stands for something in progress. */
@Composable
private fun Modifier.pulsing(enabled: Boolean): Modifier {
    if (!enabled) return this
    val a by rememberInfiniteTransition(label = "textPulse").animateFloat(
        1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "textPulseA",
    )
    return this.alpha(a)
}

private const val MESH_REFRESH_MS = 12_000L

@Composable
private fun MeshCard(title: String, body: String, content: @Composable () -> Unit) {
    if (LocalInSheet.current) {
        // Inside a sheet the sheet is the card: a larger title, the same body, no box.
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = sans(16f, 650))
            Text(body, style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp), modifier = Modifier.padding(top = 8.dp))
            content()
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(title, style = sans(14.5f, 650))
        Text(body, style = sans(11.5f, 400, Wrt.TextSecondary, lineHeight = 17.sp), modifier = Modifier.padding(top = 4.dp))
        content()
    }
}

@Composable
private fun StateLine(text: String, tone: Color) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(tone, 7.dp)
        Text(text, style = mono(11f, 600, tone))
    }
}

@Composable
private fun NoteLine(text: String, tone: Color = Wrt.Amber) {
    Text(text, style = sans(11f, 400, tone, lineHeight = 16.sp), modifier = Modifier.padding(top = 6.dp))
}

/** The uci lines behind a button, folded away until asked for. */
@Composable
private fun OpsFold(ops: List<String>) {
    if (ops.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Text(
        if (open) "Hide the ${ops.size} uci lines" else "Show the ${ops.size} uci lines",
        style = mono(10.5f, 600, Wrt.Accent),
        modifier = Modifier.padding(top = 10.dp).clickable { open = !open },
    )
    if (open) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).background(Wrt.BgDeep, RoundedCornerShape(8.dp)).padding(10.dp),
        ) {
            ops.forEach { Text(it, style = mono(9.5f, 500, Wrt.TextSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A button that asks twice: the first tap arms it, the second does it. */
@Composable
private fun TwoTapButton(label: String, armedLabel: String, danger: Boolean = false, enabled: Boolean = true, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(6_000); armed = false } }
    val tone = if (danger) Wrt.Red else Wrt.Accent
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(44.dp)
            .border(1.dp, if (armed) tone else if (enabled) tone.copy(alpha = 0.5f) else Wrt.BorderCard, RoundedCornerShape(11.dp))
            .background(if (armed) tone.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled) { if (armed) { armed = false; onConfirm() } else armed = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (armed) armedLabel else label, style = sans(12.5f, 650, if (enabled) tone else Wrt.TextDim))
    }
}

@Composable
private fun RoamingCard(store: MeshStore) {
    val scope = rememberCoroutineScope()
    MeshCard(
        "Hand-off between nodes",
        "802.11r fast transition plus 802.11k/v steering on every SSID the LAN carries, so a phone " +
            "walking from one node to the next keeps its connection instead of dropping and rejoining.",
    ) {
        val aps = store.lanAps
        when {
            !store.loaded -> {}
            aps.isEmpty() -> StateLine("no SSID on the LAN yet", Wrt.Amber)
            store.roamingOn -> StateLine("on across ${aps.size} SSID${if (aps.size == 1) "" else "s"}", Wrt.Green)
            store.roamingImpossible -> StateLine("needs a WPA2 or WPA3 password on at least one SSID", Wrt.Amber)
            else -> StateLine("off", Wrt.TextDim)
        }
        store.roamingNotes().forEach { NoteLine(it) }
        if (store.loaded && aps.isNotEmpty()) {
            if (!store.roamingOn && !store.roamingImpossible) {
                OpsFold(store.roamingOps())
                NoteLine("Wi-Fi drops for about 15 seconds while hostapd restarts. A few very old smart-home devices refuse an SSID with 802.11r; turn it off again here if one goes missing.", Wrt.TextDim)
                NoteLine("If the SSIDs are not back within 36 seconds, the previous wireless settings are put back automatically — by the router itself if this phone has lost it.", Wrt.TextDim)
                PrimaryButton(if (store.applying) "Turning on…" else "Turn on hand-off", Modifier.padding(top = 12.dp)) {
                    if (!store.applying) scope.launch { store.enableRoaming() }
                }
            } else if (store.roamingOn) {
                val stale = store.staleDomains
                if (stale.isNotEmpty()) {
                    NoteLine(
                        "${stale.joinToString(", ") { it.ssid }} ${if (stale.size == 1) "was" else "were"} renamed after hand-off went on, so " +
                            "${if (stale.size == 1) "its" else "their"} hand-off domain still belongs to the old name. Phones will do a full " +
                            "reconnect instead of a hand-off until it is rewritten. Wi-Fi drops for about 15 seconds.",
                        Wrt.Amber,
                    )
                    PrimaryButton(if (store.applying) "Repairing…" else "Repair hand-off", Modifier.padding(top = 10.dp), color = Wrt.Amber) {
                        if (!store.applying) scope.launch { store.repairRoaming() }
                    }
                }
                TwoTapButton("Turn off hand-off", "Tap again to turn it off") { scope.launch { store.disableRoaming() } }
            }
        }
        store.notice?.let { NoteLine(it, Wrt.Accent) }
        store.error?.let { NoteLine(it, Wrt.Red) }
    }
}

@Composable
private fun MeshLinkCard(store: MeshStore) {
    val scope = rememberCoroutineScope()
    MeshCard(
        "Wireless mesh link",
        "An 802.11s network on 5 GHz that wireless nodes join and relay for each other. Wired nodes do " +
            "not need it, so leave this off if every node has a cable.",
    ) {
        if (!store.loaded) return@MeshCard
        val radio = store.meshRadio
        val problems = store.meshProblems()
        when {
            store.swapping -> {
                StateLine("installing — the radios restart, the app reconnects", Wrt.Amber)
                store.swapLog?.let { Text(it, style = mono(9.5f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 6.dp)) }
            }
            !store.meshCapable -> {
                StateLine("this wpad build has no 802.11s", Wrt.Amber)
                val swap = store.wpadSwap
                if (swap != null) {
                    NoteLine("${swap.install} replaces ${swap.remove} (about 450 kB more). Wi-Fi on this router drops for about a minute while it swaps; the app reconnects on its own.", Wrt.TextSecondary)
                    store.overlayFreeKb?.let { NoteLine("$it kB free on the overlay", Wrt.TextDim) }
                }
                problems.forEach { NoteLine(it, Wrt.Red) }
                if (swap != null && problems.none { it.startsWith("Only") }) {
                    TwoTapButton("Install ${swap.install}", "Tap again — Wi-Fi drops for a minute") {
                        scope.launch { store.swapWpad() }
                    }
                }
            }
            store.meshIface == null -> {
                StateLine("off · wpad can do 802.11s", Wrt.TextDim)
                problems.forEach { NoteLine(it, Wrt.Red) }
                if (radio != null && problems.isEmpty()) {
                    val pinned = MeshOps.pinnedChannel(radio, store.operatingChannels[radio.section])
                    NoteLine(
                        "Goes on ${radio.section} (${radio.band}) at channel ${pinned ?: radio.channel}, ${radio.htmode}. " +
                            (if (pinned != null) "The channel is pinned there, off DFS, because every peer has to share it. " else "") +
                            "Wireless nodes then sit on that channel too.",
                        Wrt.TextSecondary,
                    )
                    OpsFold(store.meshOps())
                    PrimaryButton(if (store.applying) "Turning on…" else "Turn on mesh link", Modifier.padding(top = 12.dp)) {
                        if (!store.applying) scope.launch { store.enableMesh() }
                    }
                }
            }
            else -> {
                val m = store.meshIface!!
                StateLine(if (store.meshUp) "up · ${m.meshId} on ${m.device}" else "configured, not up · ${m.meshId}", if (store.meshUp) Wrt.Green else Wrt.Amber)
                val peers = store.peers
                NoteLine(
                    if (peers.isEmpty()) "No peers linked yet." else "${peers.size} peer${if (peers.size == 1) "" else "s"} linked · " +
                        peers.joinToString(" · ") { p -> (p.signalDbm?.let { "$it dBm" } ?: "—") },
                    Wrt.TextSecondary,
                )
                TwoTapButton("Turn off mesh link", "Tap again — wireless nodes lose their uplink", danger = true) {
                    scope.launch { store.disableMesh() }
                }
            }
        }
    }
}

/** Guest and IoT on the nodes: what would be mirrored, and whether the backhaul carries it yet. */
@Composable
private fun ExtrasCard(store: MeshStore) {
    val scope = rememberCoroutineScope()
    val extras = store.profile?.extras.orEmpty()
    if (!store.loaded) return
    if (extras.isEmpty()) {
        MeshCard(
            "Guest and IoT on nodes",
            "This router has no guest or IoT network yet. Put one up from the Dashboard and every node " +
                "mirrors it on the next push, on the bands it has; its traffic rides the backhaul in a VLAN of " +
                "its own back here, so the isolation holds on a node exactly as it does on this router.",
        ) {}
        return
    }
    MeshCard(
        "Guest and IoT on nodes",
        "Nodes broadcast these too, on the bands they have. Their traffic rides the backhaul in a VLAN " +
            "of its own back to this router's guest or IoT network, so the isolation holds on a node exactly as it does here.",
    ) {
        extras.forEach { x ->
            val names = x.ssids.map { it.ssid }.distinct().joinToString(", ")
            Text("${x.name}  ·  $names  ·  vlan ${x.vid}", style = mono(10.5f, 500, Wrt.TextSecondary), modifier = Modifier.padding(top = 8.dp))
        }
        if (store.trunksReady) {
            StateLine("trunk in place on the mesh point and the LAN sockets", Wrt.Green)
        } else {
            StateLine("trunk not set up on this router yet", Wrt.Amber)
            NoteLine("A push sets it up first. It can also be done now; guest and IoT Wi-Fi here blink for a moment.", Wrt.TextDim)
            GhostButton(if (store.applying) "Setting up…" else "Set up the trunk", Modifier.padding(top = 10.dp)) {
                if (!store.applying) scope.launch { store.ensureTrunks() }
            }
        }
    }
}

@Composable
private fun JoinCard(candidates: List<RouterEntity>, onJoin: (RouterEntity) -> Unit) {
    MeshCard(
        "Join a mesh",
        "Make this router a node of one of your other routers: it stops routing, keeps its Wi-Fi, " +
            "and carries the primary's SSIDs with seamless hand-off. A backup is saved first and Leave mesh puts it back.",
    ) {
        candidates.forEach { primary ->
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(primary.name, style = sans(13f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(primary.host, style = mono(10.5f, 500, Wrt.TextDim))
                }
            }
            PrimaryButton("Join ${primary.name}", Modifier.padding(top = 8.dp)) { onJoin(primary) }
        }
    }
}

@Composable
private fun NodeRecoveryCard(hooks: MeshHooks, store: MeshStore, entity: RouterEntity) {
    val scope = rememberCoroutineScope()
    val gateway = store.nodeGateway
    val primary = hooks.saved.firstOrNull { it.identity != entity.identity && it.host == gateway }
        ?: hooks.saved.firstOrNull { it.identity != entity.identity && it.meshProfile != null && !it.isMeshNode }
    var busy by remember { mutableStateOf(false) }
    MeshCard(
        "Set up as a node",
        "This router's config is a node's: its LAN points at $gateway as gateway, its DHCP server " +
            "serves nobody, and it carries copied SSIDs" + (if (store.meshIface != null) " and a mesh point" else "") +
            ". This phone has no record of that — the join ran from elsewhere, or the record was lost." +
            (if (store.onRouterSnapshot) " Its pre-mesh config is kept on the router, so Leave mesh works from here regardless." else ""),
    ) {
        if (primary == null) {
            NoteLine("No saved router answers at $gateway. Add the primary to this app first, then come back here.", Wrt.Amber)
        } else {
            NoteLine("Recording it as a node of ${primary.name} (${primary.host}), ${store.nodeBackhaul.label.lowercase()} backhaul, changes nothing on the router.", Wrt.TextSecondary)
            PrimaryButton(if (busy) "Recording…" else "Mark as node of ${primary.name}", Modifier.padding(top = 12.dp)) {
                if (!busy) scope.launch {
                    busy = true
                    hooks.markNode(entity, primary, store.nodeBackhaul, store.ownMeshMac)
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun NodeView(hooks: MeshHooks, store: MeshStore, entity: RouterEntity, onLeave: () -> Unit) {
    val primary = hooks.saved.firstOrNull { it.identity == entity.meshPrimary }
    val primaryName = primary?.name ?: "its primary"
    val wired = entity.meshBackhaul != Backhaul.Wireless.uci
    val peer = store.peers.firstOrNull()
    val linkUp = wired || store.meshUp && peer?.established == true
    val ssids = store.networks.filter { it.mode == "ap" && !it.disabled }.map { it.ssid }.distinct()
    Hero(
        height = 120.dp,
        headline = "Node of $primaryName",
        line = listOfNotNull(
            entity.host,
            "${Backhaul.of(entity.meshBackhaul)?.label?.lowercase() ?: "wired"} backhaul",
            if (!wired) (if (linkUp) peer?.signalDbm?.let { "$it dBm" } else "link down") else null,
        ).joinToString(" · "),
        tone = if (linkUp) Wrt.Green else Wrt.Amber,
        pulse = linkUp,
    ) {
        MeshTopology(
            primaryName,
            listOf(TopoNode(entity.name, wired, linkUp, peer?.signalDbm, if (store.loaded) TopoSync.Ok else TopoSync.Unknown)),
            pushing = false, height = 120.dp,
        )
    }
    RowsCard {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Wi-Fi copied from $primaryName", style = sans(13f, 400), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (!store.loaded) "…" else "${ssids.size} SSID${if (ssids.size == 1) "" else "s"}",
                style = mono(11f, 500, if (ssids.isEmpty() && store.loaded) Wrt.Amber else Wrt.Green),
            )
        }
        if (primary != null) {
            Row(
                Modifier.fillMaxWidth().rowDivider().clickable { hooks.onOpenRouter(primary) }.height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Open $primaryName", style = sans(13f, 400), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(primary.host, style = mono(11f, 500, Wrt.TextSecondary))
                Icon(WrtIcons.ChevronRight, null, Modifier.size(16.dp), tint = Wrt.TextDim)
            }
        }
    }
    Text(
        "This router carries $primaryName's SSIDs and leaves DHCP, DNS and the firewall to it; its WAN socket is a LAN socket now. " +
            "Guest and IoT here are copies too: change them on $primaryName.",
        style = sans(11f, 400, Wrt.TextDim, lineHeight = 16.sp),
        modifier = Modifier.padding(horizontal = 2.dp),
    )
    // A wireless node whose wpad swap did not run during the join carries the mesh point but
    // cannot bring it up, so it is on the cable only. The swap is offered again from here.
    val finishSwap = if (store.loaded) MeshOps.finishBackhaulSwap(Backhaul.of(entity.meshBackhaul) ?: Backhaul.Wired, store.meshCapable, linkUp, store.wpad) else null
    finishSwap?.let { FinishBackhaulCard(store, it, primaryName) }
    val restorable = entity.meshSnapshot != null || store.onRouterSnapshot
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (restorable) {
                "Leaving restores the backup from when it joined, then reboots: its own address, DHCP and firewall come back and it sits behind its own WAN socket again."
            } else {
                "The backup from before it joined is on neither this phone nor the router, so leaving only forgets the mesh here; the router stays a node until it is reset by hand."
            },
            style = sans(11f, 400, Wrt.TextDim, lineHeight = 16.sp),
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        TwoTapButton(
            if (restorable) "Restore and leave" else "Forget the mesh",
            "Tap again to leave",
            danger = true,
            onConfirm = onLeave,
        )
    }
}

/**
 * A wireless node stranded on its cable: the mesh point is written, but the wpad build has no
 * 802.11s to run it. Retries the swap that could not finish during the join — the detached form,
 * since the app reaches the node over its own LAN — and the mesh point comes up on the reload.
 */
@Composable
private fun FinishBackhaulCard(store: MeshStore, swap: WpadSwap, primaryName: String) {
    val scope = rememberCoroutineScope()
    val problems = store.meshProblems()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Amber, RoundedCornerShape(14.dp))
            .background(Wrt.BgCard, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Finish the wireless backhaul", style = sans(13.5f, 650))
        Text(
            "This node joined over the mesh, but its wpad build has no 802.11s, so the mesh point cannot come " +
                "up and it reaches $primaryName over the cable only. Installing ${swap.install} completes the swap " +
                "that could not run during the join; it downloads through $primaryName, so keep the cable in.",
            style = sans(11.5f, 400, Wrt.TextSecondary, lineHeight = 17.sp),
        )
        if (store.swapping) {
            StateLine("installing — the radios restart, the app reconnects", Wrt.Amber)
            store.swapLog?.let { Text(it, style = mono(9.5f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 6.dp)) }
        } else {
            NoteLine("${swap.install} replaces ${swap.remove} (about 450 kB more). Wi-Fi on this node drops for about a minute while it swaps; the app reconnects on its own.", Wrt.TextSecondary)
            store.overlayFreeKb?.let { NoteLine("$it kB free on the overlay", Wrt.TextDim) }
            problems.forEach { NoteLine(it, Wrt.Red) }
            if (problems.none { it.startsWith("Only") }) {
                TwoTapButton("Install ${swap.install}", "Tap again — Wi-Fi drops for a minute") {
                    scope.launch { store.swapWpad() }
                }
            }
        }
        store.notice?.let { NoteLine(it, Wrt.Accent) }
        store.error?.let { NoteLine(it, Wrt.Red) }
    }
}

// ---------------------------------------------------------------------------
// Join wizard
// ---------------------------------------------------------------------------

@Composable
fun JoinMeshScreen(
    join: MeshJoin,
    hooks: MeshHooks,
    persist: suspend (JoinOutcome) -> Unit,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    val primaryRow = hooks.saved.firstOrNull { it.identity == join.profile.primaryIdentity }
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    // The stored copy of the primary may predate its mesh link; a live read wins when it works.
    suspend fun refresh(loud: Boolean) {
        val row = primaryRow ?: return
        refreshing = true
        val fresh = hooks.refreshProfile(row)
        refreshing = false
        if (fresh != null) {
            join.profile = fresh
            refreshNote = if (loud) "Read ${row.name} just now." else null
        } else if (loud) {
            refreshNote = "${row.name} did not answer from here. Its saved copy is what the wizard has."
        }
    }
    LaunchedEffect(join) {
        if (!join.loaded) join.load()
        if (!join.viaPrimary) refresh(loud = false)
    }
    DisposableEffect(join) { onDispose { CoroutineScope(Dispatchers.IO).launch { join.close() } } }
    val canGoBack = !join.applying && !join.done
    // Through the primary the cable is already in, so the cable step is skipped both ways.
    fun back() {
        when {
            step == 2 && join.viaPrimary -> step = 0
            step > 0 && step < 3 -> step--
            else -> onBack()
        }
    }
    BackHandler(enabled = canGoBack) { back() }
    val stepCount = if (join.viaPrimary) 3 else 4
    val shown = if (join.viaPrimary && step >= 2) step else step + 1

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar(if (join.viaPrimary) "Add ${join.name.ifBlank { "a node" }}" else "Join ${join.profile.primaryName}", onBack = { if (canGoBack) back() }) {
            MonoTag("step ${minOf(shown, stepCount)} of $stepCount", size = 10f)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (step) {
                0 -> NameStep(join, refreshing, refreshNote, onRefresh = { scope.launch { refresh(loud = true) } }) { step = if (join.viaPrimary) 2 else 1 }
                1 -> CableStep(join, scope) { step = 2 }
                2 -> ReviewStep(join) {
                    step = 3
                    scope.launch { join.apply(persist) }
                }
                else -> ApplyStep(join, onFinished, onBack = { step = 2 })
            }
        }
    }
}

@Composable
private fun NameStep(
    join: MeshJoin,
    refreshing: Boolean,
    refreshNote: String?,
    onRefresh: () -> Unit,
    onNext: () -> Unit,
) {
    MeshCard(
        "What this node is",
        "The name is for the list and the router's own hostname. The backhaul is how it reaches " +
            "${join.profile.primaryName}: a cable, or the 802.11s mesh link over 5 GHz.",
    ) {
        FieldLabel("NAME")
        FormTextField(join.name, { join.name = it })
        Text("hostname ${MeshOps.hostnameOf(join.name)}", style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))
        FieldLabel("BACKHAUL")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Backhaul.entries.forEach { b ->
                FilterChip(b.label, selected = join.backhaul == b) { join.backhaul = b }
            }
        }
        NoteLine(
            when (join.backhaul) {
                Backhaul.Wired -> "A cable from this router's WAN socket to a LAN socket of ${join.profile.primaryName}. Fastest, and the node gets its own 5 GHz channel."
                Backhaul.Wireless -> if (join.profile.wirelessReady) {
                    "Joins ${join.profile.meshId} on ${join.profile.meshBand}. Nodes relay for each other, so a far node can reach the primary through a nearer one. All wireless nodes share the primary's channel."
                } else {
                    "${join.profile.primaryName} has no mesh link yet. Open Network · Mesh there and turn it on first."
                }
            },
            Wrt.TextSecondary,
        )
        join.error?.let { NoteLine(it, Wrt.Red) }
        if (!join.loaded && join.error == null) NoteLine("Reading this router…", Wrt.TextDim)
        val wirelessBlocked = join.backhaul == Backhaul.Wireless && !join.profile.wirelessReady
        if (wirelessBlocked) {
            NoteLine(
                "The copy of ${join.profile.primaryName} on this phone was taken before any mesh link. If you have " +
                    "turned it on there since, read it again — through the cable this router usually reaches it.",
                Wrt.Amber,
            )
            GhostButton(if (refreshing) "Reading…" else "Read ${join.profile.primaryName} again", Modifier.padding(top = 10.dp)) {
                if (!refreshing) onRefresh()
            }
        }
        refreshNote?.let { NoteLine(it, if (it.startsWith("Read ")) Wrt.Accent else Wrt.TextDim) }
        val ready = join.loaded && !wirelessBlocked
        PrimaryButton(
            when {
                !join.loaded -> "Reading…"
                wirelessBlocked -> "Needs the mesh link on ${join.profile.primaryName}"
                else -> "Next"
            },
            Modifier.padding(top = 14.dp),
            color = if (ready) Wrt.Accent else Wrt.BgDeep,
            textColor = if (ready) Wrt.OnAccent else Wrt.TextDim,
        ) { if (ready) onNext() }
    }
}

@Composable
private fun CableStep(join: MeshJoin, scope: CoroutineScope, onNext: () -> Unit) {
    var checking by remember { mutableStateOf(false) }
    MeshCard(
        "The cable",
        if (join.backhaul == Backhaul.Wired) {
            "Plug a cable from this router's WAN socket into any LAN socket of ${join.profile.primaryName}. " +
                "That socket becomes a LAN socket when it joins, so the cable stays where it is."
        } else if (join.needsSwap) {
            "This router needs the 802.11s wpad build downloaded first, so it needs internet for a minute: " +
                "plug its WAN socket into ${join.profile.primaryName} for now. The cable comes out once it has joined."
        } else {
            "No cable needed: this wpad build already has 802.11s. Keeping one in during the join is still " +
                "the safe way — if the mesh link never comes up, the node stays reachable over it."
        },
    ) {
        val link = join.wan
        when {
            join.cabledToPrimary -> StateLine("WAN holds ${link?.address} from ${join.profile.primaryName}", Wrt.Green)
            link?.up == true -> StateLine("WAN is up at ${link.address}, which is not ${join.profile.primaryName}'s subnet", Wrt.Amber)
            else -> StateLine("nothing on the WAN socket yet", if (join.cableRequired) Wrt.Amber else Wrt.TextDim)
        }
        GhostButton(if (checking) "Checking…" else "Check again", Modifier.padding(top = 12.dp)) {
            if (!checking) scope.launch { checking = true; join.load(); checking = false }
        }
        val ready = !join.cableRequired || join.cabledToPrimary
        PrimaryButton(if (ready) "Next" else "Waiting for the cable", Modifier.padding(top = 10.dp), color = if (ready) Wrt.Accent else Wrt.BgDeep, textColor = if (ready) Wrt.OnAccent else Wrt.TextDim) {
            if (ready) onNext()
        }
    }
}

@Composable
private fun ReviewStep(join: MeshJoin, onApply: () -> Unit) {
    val plan = join.plan()
    if (plan == null) { NoteLine("Reading this router…", Wrt.TextDim); return }
    val p = join.profile
    MeshCard("On this router", "What the join writes here, all in one batch under a ${MeshOps.ROLLBACK_SECONDS}-second rollback.") {
        Column(Modifier.padding(top = 8.dp)) {
            ReviewLine("address", "${plan.address}/${plan.prefix} · gateway and DNS ${p.primaryIp}")
            ReviewLine("hostname", plan.hostname)
            join.state?.radios?.forEach { r ->
                val ssid = p.ssidFor(r.band)
                if (ssid != null) ReviewLine(r.band, "${ssid.ssid} · ${if (ssid.encryption == "none") "open" else ssid.encryption}" +
                    (if (MeshOps.roamingCapable(ssid.encryption)) " · 802.11r/k/v" else " · 802.11k/v") +
                    (if (r.section == plan.meshRadio) " · mesh point on ${p.radioFor(r.band)?.channel?.let { "ch $it" } ?: "the primary's channel"}" else ""))
            }
            ReviewLine("backhaul", plan.backhaul.label.lowercase())
            plan.swap?.let { ReviewLine("package", "${it.install} replaces ${it.remove}") }
        }
        plan.notes.forEach { NoteLine(it, Wrt.TextSecondary) }
        OpsFold(plan.ops)
    }
    if (plan.problems.isNotEmpty()) {
        MeshCard("Not yet", "The join will not run until these are sorted.") {
            plan.problems.forEach { NoteLine(it, Wrt.Red) }
        }
    }
    MeshCard("Then", "Once the config is written, this router changes address and your phone hops to ${p.ssids.firstOrNull()?.ssid ?: "the home Wi-Fi"} on its own. The app finds the node at ${plan.address} and confirms; if it cannot within ${MeshOps.ROLLBACK_SECONDS} seconds, the node puts everything back by itself.") {
        PrimaryButton(
            "Join ${p.primaryName}",
            Modifier.padding(top = 12.dp),
            color = if (plan.problems.isEmpty()) Wrt.Accent else Wrt.BgDeep,
            textColor = if (plan.problems.isEmpty()) Wrt.OnAccent else Wrt.TextDim,
        ) { if (plan.problems.isEmpty()) onApply() }
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = mono(10f, 600, Wrt.TextDim), modifier = Modifier.padding(top = 1.dp))
        Text(value, style = sans(12f, 500, Wrt.TextPrimary, lineHeight = 17.sp))
    }
}

@Composable
private fun ApplyStep(join: MeshJoin, onFinished: () -> Unit, onBack: () -> Unit) {
    MeshCard(
        if (join.done) "Joined" else if (join.rolledBack) "Rolled back" else if (join.error != null) "Stopped" else "Joining…",
        if (join.done) "${if (join.viaPrimary) join.name else "This router"} is a node of ${join.profile.primaryName} at ${join.newAddress}."
        else if (join.viaPrimary) "Everything runs through ${join.profile.primaryName} over the cable; this phone stays where it is."
        else "Stay near ${join.profile.primaryName}; the phone changes Wi-Fi on its own partway through.",
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            join.steps.forEach { s ->
                val tone = when (s.state) {
                    JoinStep.State.Done -> Wrt.Green
                    JoinStep.State.Failed -> Wrt.Red
                    JoinStep.State.Running -> Wrt.Accent
                    JoinStep.State.Pending -> Wrt.TextDim
                }
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(top = 3.dp).size(12.dp).border(1.5.dp, tone, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        if (s.state == JoinStep.State.Done) Icon(WrtIcons.Check, null, Modifier.size(8.dp), tint = tone)
                        if (s.state == JoinStep.State.Failed) Icon(WrtIcons.Close, null, Modifier.size(7.dp), tint = tone)
                        if (s.state == JoinStep.State.Running) StatusDot(tone, 6.dp, pulse = true, periodMs = 1200)
                    }
                    Column {
                        Text(s.label, style = sans(12f, 600, if (s.state == JoinStep.State.Pending) Wrt.TextDim else Wrt.TextPrimary))
                        s.detail?.let { Text(it, style = mono(9.5f, 500, Wrt.TextDim), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        join.error?.let { NoteLine(it, Wrt.Red) }
        if (!join.applying && !join.done) {
            GhostButton("Back", Modifier.padding(top = 12.dp), onClick = onBack)
        }
    }
    if (join.done && join.backhaul == Backhaul.Wireless) {
        LaunchedEffect(join) { while (true) { join.checkLink(); delay(5_000) } }
        MeshCard(
            "Unplug the cable and place the node",
            "Put it where it should live. Its mesh point looks for ${join.profile.primaryName} on its own; " +
                "the line below updates every few seconds while this screen is open.",
        ) {
            val s = join.linkSignalDbm
            when {
                !join.linkChecked -> StateLine("checking…", Wrt.TextDim)
                s == null -> StateLine("no mesh peer yet", Wrt.Amber)
                s < -75 -> StateLine("linked at $s dBm — weak, move it closer", Wrt.Amber)
                else -> StateLine("linked at $s dBm", Wrt.Green)
            }
            NoteLine("If the link never comes up, plug the cable back in: the node works wired on any of its sockets now, and its 2.4 GHz SSID is on the air regardless.", Wrt.TextDim)
        }
    }
    if (join.done || join.rolledBack) {
        PrimaryButton(if (join.done && !join.viaPrimary) "Open the node" else if (join.done) "Done" else if (join.viaPrimary) "Back" else "Back to the router") {
            onFinished()
        }
    }
}

// ---------------------------------------------------------------------------
// Leave
// ---------------------------------------------------------------------------

@Composable
fun LeaveMeshScreen(
    backup: BackupStore?,
    store: MeshStore?,
    entity: RouterEntity,
    onBack: () -> Unit,
    onLeft: (lanAddress: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var routerRestored by remember { mutableStateOf(false) }
    val snapshotName = entity.meshSnapshot
    // The router's own copy is the one to use: it is there whatever phone this is. The
    // phone's copy is the fallback for a node joined before routers kept one.
    val onRouter = store?.onRouterSnapshot == true
    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }
    LaunchedEffect(backup, onRouter) {
        if (onRouter || backup == null || snapshotName == null) return@LaunchedEffect
        if (!backup.loaded) backup.load()
        backup.refreshLocal()
        val local = backup.local.firstOrNull { it.file.name == snapshotName }
        result = if (local != null) backup.stageLocal(local) else "Failed: $snapshotName is not on this phone."
    }
    val rebooting = routerRestored || backup?.restoring == true
    BackHandler(enabled = !busy && !rebooting) { onBack() }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Leave the mesh", onBack = { if (!busy && !rebooting) onBack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val candidate = backup?.candidate
            if (rebooting) {
                val lan = if (routerRestored) store?.onRouterSnapshotLan else candidate?.lanAddress
                MeshCard("Restored — rebooting", "The pre-mesh config is unpacked and the router is restarting with it. The connection has already dropped; that is expected.") {
                    lan?.let { NoteLine("It comes back at $it, on its own subnet, behind its own WAN socket. The saved entry follows it there.", Wrt.Amber) }
                    PrimaryButton("Done", Modifier.padding(top = 12.dp)) { onLeft(lan) }
                }
                return@Column
            }
            if (onRouter && store != null) {
                MeshCard(
                    "What happens",
                    "The config this router had before it joined is kept on the router itself. It goes back on with " +
                        "sysupgrade -r and the router reboots as exactly the router it was: its own address, its own " +
                        "DHCP and firewall, its own SSIDs. No phone needs to hold anything for this.",
                ) {
                    ReviewLine("archive", "on the router")
                    store.onRouterSnapshotLan?.let { ReviewLine("comes back at", it) }
                    store.onRouterSnapshotHostname?.let { ReviewLine("hostname", it) }
                    result?.let { NoteLine(it, if (it.startsWith("Failed")) Wrt.Red else Wrt.TextDim) }
                    TwoTapButton(if (busy) "Restoring…" else "Restore and leave", "Tap again — the router reboots", danger = true, enabled = !busy) {
                        scope.launch {
                            busy = true
                            val out = store.restoreOnRouterSnapshot()
                            if (out == "restored") routerRestored = true else result = out
                            busy = false
                        }
                    }
                    if (result?.startsWith("Failed") == true) {
                        TwoTapButton("Forget the mesh anyway", "Tap again to forget", danger = true) { onLeft(null) }
                    }
                }
                return@Column
            }
            MeshCard(
                "What happens",
                if (snapshotName != null) {
                    "The backup taken just before this router joined goes back on with sysupgrade -r, and the router reboots. " +
                        "It is then exactly the router it was: its own address, its own DHCP and firewall, its own SSIDs."
                } else {
                    "There is no pre-mesh backup on this phone for this router, so the app can only forget the mesh. " +
                        "The router keeps the node config until you restore it or reset it by hand."
                },
            ) {
                if (snapshotName != null) {
                    ReviewLine("archive", snapshotName)
                    candidate?.lanAddress?.let { ReviewLine("comes back at", it) }
                    candidate?.hostname?.let { ReviewLine("hostname", it) }
                    result?.let { NoteLine(it, if (it.startsWith("Failed")) Wrt.Red else Wrt.TextDim) }
                    backup?.progress?.let { NoteLine(it, Wrt.Accent) }
                    val ready = candidate != null && !busy && backup != null
                    TwoTapButton(if (busy) "Restoring…" else "Restore and leave", "Tap again — the router reboots", danger = true, enabled = ready) {
                        scope.launch {
                            busy = true
                            val up = backup!!.upload()
                            result = up
                            if (!up.startsWith("Failed")) result = backup.restore()
                            busy = false
                        }
                    }
                    if (result?.startsWith("Failed") == true) {
                        TwoTapButton("Forget the mesh anyway", "Tap again to forget", danger = true) { onLeft(null) }
                    }
                } else {
                    TwoTapButton("Forget the mesh", "Tap again to forget", danger = true) { onLeft(null) }
                }
            }
        }
    }
}
