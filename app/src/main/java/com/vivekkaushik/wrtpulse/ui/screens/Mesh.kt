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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.BackupStore
import com.vivekkaushik.wrtpulse.data.JoinOutcome
import com.vivekkaushik.wrtpulse.data.JoinStep
import com.vivekkaushik.wrtpulse.data.MeshJoin
import com.vivekkaushik.wrtpulse.data.MeshStore
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ops.Backhaul
import com.vivekkaushik.wrtpulse.ops.MeshOps
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
    /** Records that the connected router is a node of [primary] — its config already says so. */
    val markNode: suspend (node: RouterEntity, primary: RouterEntity, backhaul: Backhaul, meshMac: String?) -> Unit = { _, _, _, _ -> },
)

// ---------------------------------------------------------------------------
// The Mesh page
// ---------------------------------------------------------------------------

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
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Mesh", onBack) {
            latencyMs?.let { MonoTag("$it ms", size = 10f) }
            MonoTag(routerName, size = 10.5f)
        }
        PullToRefresh(Modifier.weight(1f), enabled = store != null, onRefresh = { store?.takeIf { !it.applying && !it.refreshPaused }?.load() }) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    store == null -> Text("Not connected.", style = sans(12f, 400, Wrt.TextDim))
                    current?.isMeshNode == true -> NodeView(hooks, current, onLeave)
                    // The router says it is a node and the phone has no record of it: offer
                    // to write the record, not to join again.
                    store.loaded && store.configuredAsNode && current != null -> NodeRecoveryCard(hooks, store, current)
                    else -> {
                        if (!store.loaded && store.error == null) {
                            Text("Reading the router…", style = sans(12f, 400, Wrt.TextDim))
                        }
                        store.error?.let { Text(it, style = sans(11.5f, 500, Wrt.Red)) }
                        RoamingCard(store)
                        MeshLinkCard(store)
                        val candidates = hooks.saved.filter {
                            it.identity != store.identity && it.meshProfile != null && !it.isMeshNode
                        }
                        // A plain router that could become a node gets the join offer first; a
                        // router already acting as a primary is never offered to join anything.
                        if (store.loaded && !store.actsAsPrimary && nodes.isEmpty() && candidates.isNotEmpty()) JoinCard(candidates, onJoin)
                        NodesCard(store, hooks, onAddNode)
                        Text(
                            "Guest and IoT networks stay on the primary; nodes carry the LAN's SSIDs only.",
                            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
                            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val MESH_REFRESH_MS = 12_000L

@Composable
private fun MeshCard(title: String, body: String, content: @Composable () -> Unit) {
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

@Composable
private fun NodesCard(store: MeshStore, hooks: MeshHooks, onAdd: () -> Unit) {
    val scope = rememberCoroutineScope()
    val onOpen = hooks.onOpenRouter
    val nodes = store.nodes()
    val total = nodes.size + store.strayPeers().size
    val ready = store.roamingOn || store.meshIface != null
    MeshCard(
        if (total == 0) "Nodes" else "$total node${if (total == 1) "" else "s"}",
        if (total == 0) {
            "None yet. A node is another OpenWrt router that carries this one's SSIDs and leaves " +
                "DHCP, DNS and the firewall to it."
        } else {
            "Each node carries this router's SSIDs and leaves DHCP, DNS and the firewall to it."
        },
    ) {
        if (store.loaded) {
            if (!ready) NoteLine("Turn on hand-off above first, so the nodes join a Wi-Fi that hands clients over cleanly.", Wrt.TextDim)
            PrimaryButton("Add a node", Modifier.padding(top = 12.dp), onClick = onAdd)
            if (nodes.isNotEmpty()) {
                val stale = store.nodeSync.values.count { it is com.vivekkaushik.wrtpulse.data.NodeSync.OutOfDate }
                val unread = store.nodeSync.values.count { it is com.vivekkaushik.wrtpulse.data.NodeSync.Unreachable }
                when {
                    store.syncing -> NoteLine("Talking to the nodes…", Wrt.TextDim)
                    stale > 0 -> NoteLine(
                        "This router's Wi-Fi changed since $stale node${if (stale == 1) "" else "s"} copied it. " +
                            "Pushing rewrites SSIDs, channels and hand-off from what is here now; each node reloads its Wi-Fi for about 15 s.",
                        Wrt.Amber,
                    )
                    unread > 0 -> NoteLine(
                        "$unread node${if (unread == 1) " could" else "s could"} not be read just now. A push still tries every node.",
                        Wrt.TextDim,
                    )
                }
                // Always offered: a node whose read failed is exactly the one that may need it.
                PrimaryButton(
                    if (store.syncing) "Working…" else "Push Wi-Fi to ${nodes.size} node${if (nodes.size == 1) "" else "s"}",
                    Modifier.padding(top = 10.dp),
                    color = if (stale > 0) Wrt.Amber else Wrt.Accent,
                ) { if (!store.syncing) scope.launch { store.pushNodes(hooks.openNode, all = true) } }
                GhostButton("Check again", Modifier.padding(top = 8.dp)) { if (!store.syncing) scope.launch { store.checkNodes(hooks.openNode) } }
            }
            store.syncNotice?.let { NoteLine(it, Wrt.Accent) }
        }
        nodes.forEach { node ->
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onOpen(node.entity) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(if (node.online) Wrt.Green else Wrt.TextDim, 8.dp, pulse = node.online)
                Column(Modifier.weight(1f)) {
                    Text(node.entity.name, style = sans(13f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            node.entity.host,
                            Backhaul.of(node.entity.meshBackhaul)?.label?.lowercase(),
                            node.rttMs?.let { "%.0f ms".format(it) } ?: if (node.online) null else "no answer",
                            node.signalDbm?.let { "$it dBm" },
                        ).joinToString(" · "),
                        style = mono(10.5f, 500, Wrt.TextDim),
                    )
                    when (val sync = store.nodeSync[node.entity.identity]) {
                        is com.vivekkaushik.wrtpulse.data.NodeSync.InSync ->
                            Text("Wi-Fi up to date", style = mono(10f, 500, Wrt.Green), modifier = Modifier.padding(top = 2.dp))
                        is com.vivekkaushik.wrtpulse.data.NodeSync.OutOfDate ->
                            Text("Wi-Fi out of date · ${sync.summary}", style = mono(10f, 500, Wrt.Amber), modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        is com.vivekkaushik.wrtpulse.data.NodeSync.Unreachable ->
                            Text("could not read its Wi-Fi · ${sync.why}", style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else -> {}
                    }
                }
                Icon(WrtIcons.ChevronRight, null, Modifier.size(14.dp), tint = Wrt.TextDim)
            }
        }
        // Peers the mesh point sees that no saved row claims are nodes too — joined by hand,
        // or by this app on a phone that is gone. Listed, not hidden.
        store.strayPeers().forEach { peer ->
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(if (peer.established) Wrt.Green else Wrt.Amber, 8.dp, pulse = peer.established)
                Column(Modifier.weight(1f)) {
                    Text("Mesh peer", style = sans(13f, 600))
                    Text(
                        listOfNotNull(
                            peer.mac,
                            "wireless",
                            peer.signalDbm?.let { "$it dBm" },
                            if (peer.established) null else "linking",
                        ).joinToString(" · "),
                        style = mono(10.5f, 500, Wrt.TextDim),
                    )
                    Text(
                        "Not saved in this app. Add it from the router list to manage it here.",
                        style = sans(10.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
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

// ---------------------------------------------------------------------------
// Add a node — the guide on the primary's side
// ---------------------------------------------------------------------------

/**
 * The join itself runs on the node, with the app connected to it. This page is the bridge:
 * it says what a node needs to be, lists the saved routers that could become one, and sends
 * the app across to whichever the user picks — straight into the join for this primary.
 */
@Composable
fun AddNodeScreen(hooks: MeshHooks, onBack: () -> Unit) {
    val primary = hooks.current
    val store = hooks.store
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Add a node", onBack) { primary?.let { MonoTag(it.name, size = 10.5f) } }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (primary == null || store == null) {
                Text("Not connected.", style = sans(12f, 400, Wrt.TextDim))
                return@Column
            }
            MeshCard(
                "How it works",
                "A node is a second router running OpenWrt. The app connects to it, and its Mesh page " +
                    "offers to join ${primary.name}: a backup is saved, the config is written under a " +
                    "rollback, and the app follows it to its new address. Three things to have ready:",
            ) {
                GuideLine("1", "OpenWrt on the second router, and a way for this phone to reach it: its own Wi-Fi, " +
                    "or a cable from your phone or laptop to one of its LAN sockets. A fresh install has Wi-Fi off and " +
                    "answers at 192.168.1.1 over the cable.")
                GuideLine("2", if (store.meshIface != null) {
                    "For a wired node, a cable from its WAN socket to a LAN socket of ${primary.name}. " +
                        "For a wireless node the cable is only needed during the join, while it downloads the mesh-capable wpad."
                } else {
                    "A cable from its WAN socket to a LAN socket of ${primary.name}. Wireless nodes need the mesh link " +
                        "turned on here first."
                })
                GuideLine("3", "This phone knows the ${store.lanAps.firstOrNull()?.ssid ?: "home"} Wi-Fi, because the node " +
                    "starts carrying it partway through and the phone has to follow it there on its own.")
            }
            val candidates = hooks.saved.filter { it.identity != primary.identity && !it.isMeshNode }
            MeshCard(
                "Pick the router",
                if (candidates.isEmpty()) "No other saved router yet. Add the one that should become a node."
                else "The app connects to it and opens the join for ${primary.name} there.",
            ) {
                candidates.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = sans(13f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(r.host, r.model).filter { it.isNotBlank() }.joinToString(" · "), style = mono(10.5f, 500, Wrt.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        GhostButton("Connect & join", border = Wrt.Accent.copy(alpha = 0.5f), textColor = Wrt.Accent) {
                            hooks.onConnectToJoin(r, primary)
                        }
                    }
                }
                PrimaryButton("Add a new router", Modifier.padding(top = 14.dp)) { hooks.onAddRouterToJoin(primary) }
                NoteLine("Adding it works like any router: address, password, fingerprint. Once it is in, the join opens by itself.", Wrt.TextDim)
            }
        }
    }
}

@Composable
private fun GuideLine(number: String, text: String) {
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(20.dp).border(1.dp, Wrt.Accent.copy(alpha = 0.6f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { Text(number, style = mono(10f, 700, Wrt.Accent)) }
        Text(text, style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 17.sp), modifier = Modifier.weight(1f))
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
            ". This phone has no record of that — the join ran from elsewhere, or the record was lost.",
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
private fun NodeView(hooks: MeshHooks, entity: RouterEntity, onLeave: () -> Unit) {
    val primary = hooks.saved.firstOrNull { it.identity == entity.meshPrimary }
    val primaryName = primary?.name ?: "its primary"
    MeshCard(
        "Node of $primaryName",
        "This router carries $primaryName's SSIDs and leaves DHCP, DNS and the firewall to it. " +
            "Its WAN socket is a LAN socket now.",
    ) {
        StateLine(
            listOfNotNull(entity.host, Backhaul.of(entity.meshBackhaul)?.label?.lowercase()?.let { "$it backhaul" }).joinToString(" · "),
            Wrt.Green,
        )
        if (primary != null) {
            GhostButton("Open $primaryName", Modifier.padding(top = 12.dp)) { hooks.onOpenRouter(primary) }
        }
    }
    MeshCard(
        "Leave the mesh",
        if (entity.meshSnapshot != null) {
            "Restores the backup taken just before this router joined, then reboots. It comes back " +
                "as its own router, at its old address, behind its own WAN socket."
        } else {
            "The backup from before it joined is not on this phone, so leaving only forgets the " +
                "mesh here; the router stays a node until it is reset by hand."
        },
    ) {
        TwoTapButton(
            if (entity.meshSnapshot != null) "Restore and leave" else "Forget the mesh",
            "Tap again to leave",
            danger = true,
            onConfirm = onLeave,
        )
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
        refresh(loud = false)
    }
    DisposableEffect(join) { onDispose { CoroutineScope(Dispatchers.IO).launch { join.close() } } }
    val canGoBack = !join.applying && !join.done
    fun back() { if (step > 0 && step < 3) step-- else onBack() }
    BackHandler(enabled = canGoBack) { back() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Join ${join.profile.primaryName}", onBack = { if (canGoBack) back() }) {
            MonoTag("step ${minOf(step + 1, 4)} of 4", size = 10f)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (step) {
                0 -> NameStep(join, refreshing, refreshNote, onRefresh = { scope.launch { refresh(loud = true) } }) { step = 1 }
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
        if (join.done) "This router is a node of ${join.profile.primaryName} at ${join.newAddress}." else "Stay near ${join.profile.primaryName}; the phone changes Wi-Fi on its own partway through.",
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
        PrimaryButton(if (join.done) "Open the node" else "Back to the router") {
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
    entity: RouterEntity,
    onBack: () -> Unit,
    onLeft: (lanAddress: String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val snapshotName = entity.meshSnapshot
    LaunchedEffect(backup) {
        if (backup == null || snapshotName == null) return@LaunchedEffect
        if (!backup.loaded) backup.load()
        backup.refreshLocal()
        val local = backup.local.firstOrNull { it.file.name == snapshotName }
        result = if (local != null) backup.stageLocal(local) else "Failed: $snapshotName is not on this phone."
    }
    BackHandler(enabled = !busy && backup?.restoring != true) { onBack() }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Leave the mesh", onBack = { if (!busy && backup?.restoring != true) onBack() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val candidate = backup?.candidate
            if (backup?.restoring == true) {
                MeshCard("Restored — rebooting", "The pre-mesh config is unpacked and the router is restarting with it. The connection has already dropped; that is expected.") {
                    candidate?.lanAddress?.let { NoteLine("It comes back at $it, on its own subnet, behind its own WAN socket. The saved entry follows it there.", Wrt.Amber) }
                    PrimaryButton("Done", Modifier.padding(top = 12.dp)) { onLeft(candidate?.lanAddress) }
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
