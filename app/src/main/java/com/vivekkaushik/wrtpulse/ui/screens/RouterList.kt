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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Router
import com.vivekkaushik.wrtpulse.data.RouterStatus
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.RouterTile
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.SectionLabel

/** "just now", "4 min ago", "3 h ago", "2 d ago" */
fun agoLabel(epoch: Long, nowEpoch: Long = System.currentTimeMillis() / 1000): String {
    val d = (nowEpoch - epoch).coerceAtLeast(0)
    return when {
        d < 90 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        else -> "${d / 86_400} d ago"
    }
}

fun RouterEntity.asRouter(connectedIdentity: String?, connectingIdentity: String?): Router {
    // By identity, not address: two saved routers can share 192.168.1.1, and only one of
    // them is the one the session is on.
    val status = when (identity) {
        connectingIdentity -> RouterStatus.Reconnecting
        connectedIdentity -> RouterStatus.Online
        else -> RouterStatus.Saved
    }
    return Router(
        name = name,
        model = model.ifEmpty { host },
        tag = host,
        status = status,
        wanIp = null,
        detail = (if (privateKey != null) "key · " else "") +
            (if (status == RouterStatus.Online) "connected" else agoLabel(lastSeenEpoch)),
        meshNode = isMeshNode,
        switcherDetail = listOf(host, summary.substringBefore(" · ")).filter { it.isNotBlank() }.joinToString(" · "),
        latencyMs = null,
    )
}

/**
 * Whether a saved router answers to [query]. Every whitespace-separated word has to appear
 * somewhere — display name, host, host:port, model or the OpenWrt summary — so "openwrt 2.1"
 * narrows the way a person expects. Case does not matter; a blank query matches everything.
 */
fun routerMatches(e: RouterEntity, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = listOf(e.name, e.host, "${e.host}:${e.port}", e.username, e.model, e.summary)
        .joinToString("\n").lowercase()
    return words.all { it in haystack }
}

/** A section of the list: the routers filed under one group name, or under none. */
internal data class RouterSection(val group: String?, val routers: List<RouterEntity>)

/**
 * A group name as typed → as stored: trimmed, inner runs of space collapsed, at most 24
 * characters, and null when nothing is left — blank is how a router leaves its group.
 */
internal fun routerGroupName(input: String): String? =
    input.trim().replace(Regex("\\s+"), " ").take(24).trim().ifEmpty { null }

/**
 * Where a router is filed. Its own group first; a mesh node with none sits with its primary,
 * so tagging the primary is enough to keep a mesh together. [all] is the whole saved list,
 * so the primary is found even when a search has hidden it.
 */
internal fun effectiveGroup(e: RouterEntity, all: List<RouterEntity>): String? =
    e.groupName ?: e.meshPrimary?.let { p -> all.firstOrNull { it.identity == p }?.groupName }

/**
 * Every group in use, once each, in a fixed order — the filter chips and the edit dialog's
 * shortcuts. Pinned groups come first, then the rest, each run alphabetical. A pin on a name
 * no router carries any more changes nothing; it simply waits for the name to come back.
 */
internal fun groupNames(all: List<RouterEntity>, pinned: Set<String> = emptySet()): List<String> =
    all.mapNotNull { effectiveGroup(it, all) }.distinct()
        .sortedWith(compareBy<String> { it !in pinned }.thenBy { it.lowercase() })

/**
 * The list in sections: pinned groups first, then the other named groups, each run in
 * alphabetical order, then whatever is in none, last. With no groups at all there is one
 * unnamed section and the list looks as it always did. Inside a section the incoming order
 * holds, except that a mesh node moves to sit right after its primary when both are there,
 * so a mesh reads as one thing.
 */
internal fun routerSections(
    shown: List<RouterEntity>,
    all: List<RouterEntity> = shown,
    pinned: Set<String> = emptySet(),
): List<RouterSection> {
    val byGroup = shown.groupBy { effectiveGroup(it, all) }
    fun ordered(list: List<RouterEntity>): List<RouterEntity> {
        val here = list.map { it.identity }.toSet()
        val placed = mutableSetOf<Long>()
        val out = mutableListOf<RouterEntity>()
        for (e in list) {
            if (e.id in placed || (e.isMeshNode && e.meshPrimary in here)) continue
            out += e; placed += e.id
            list.filter { it.isMeshNode && it.meshPrimary == e.identity && it.id !in placed }
                .forEach { out += it; placed += it.id }
        }
        return out
    }
    val named = byGroup.keys.filterNotNull()
        .sortedWith(compareBy<String> { it !in pinned }.thenBy { it.lowercase() })
        .map { RouterSection(it, ordered(byGroup.getValue(it))) }
    val rest = byGroup[null].orEmpty()
    return if (rest.isEmpty()) named else named + RouterSection(null, ordered(rest))
}

/** The same for the design-time list, which has no entity behind it. */
fun demoRouterMatches(r: Router, query: String): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = listOf(r.name, r.model, r.tag, r.wanIp.orEmpty(), r.switcherDetail).joinToString("\n").lowercase()
    return words.all { it in haystack }
}

@Composable
fun RouterListScreen(
    saved: List<RouterEntity>?,
    connectedIdentity: String?,
    connectingIdentity: String?,
    error: String?,
    onOpenRouter: (Router) -> Unit,
    onOpenSaved: (RouterEntity) -> Unit,
    onAdd: () -> Unit,
    onDelete: (RouterEntity) -> Unit = {},
    /** Name, address and group together: all live on the same card and all are local-only edits. */
    onEdit: (RouterEntity, String, String, Int, String?) -> Unit = { _, _, _, _, _ -> },
    /** The About screen: version and libraries. Reachable here so it needs no router. */
    onAbout: () -> Unit = {},
    /** Groups kept at the top of the list; a tap on a section's pin toggles it. */
    pinned: Set<String> = emptySet(),
    onTogglePin: (String) -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf<RouterEntity?>(null) }
    var renaming by remember { mutableStateOf<RouterEntity?>(null) }
    var filter by remember { mutableIntStateOf(0) }
    val filters = listOf("All", "Home", "Office", "Parents")
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    /** The chip in force on the saved list; null is All. */
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    val groups = saved?.let { groupNames(it, pinned) }.orEmpty()
    // A group that was renamed or emptied away leaves its chip behind: fall back to All.
    if (selectedGroup != null && selectedGroup !in groups) selectedGroup = null
    val shownSaved = saved
        ?.filter { routerMatches(it, query) }
        ?.filter { selectedGroup == null || effectiveGroup(it, saved) == selectedGroup }
    val shownDemo = when (filter) {
        1 -> Demo.routers.filter { it.tag == "HOME" }
        2 -> Demo.routers.filter { it.tag == "OFFICE" }
        3 -> Demo.routers.filter { it.tag == "PARENTS" }
        else -> Demo.routers
    }.filter { demoRouterMatches(it, query) }
    val count = shownSaved?.size ?: shownDemo.size
    // Back closes the search before it does anything else on this screen.
    BackHandler(enabled = searching) { searching = false; query = "" }
    Box(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        Column(Modifier.fillMaxSize()) {
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = { query = it },
                    onClose = { searching = false; query = "" },
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("Routers", style = sans(17f, 650))
                    Text("$count", style = mono(11.5f, 500, Wrt.TextDim))
                    FlexSpacer()
                    Icon(
                        WrtIcons.Search, "search",
                        Modifier.size(19.dp).clickable { searching = true },
                        tint = Wrt.TextTertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        WrtIcons.Info, "about WrtPulse",
                        Modifier.size(19.dp).clickable(onClick = onAbout),
                        tint = Wrt.TextTertiary,
                    )
                }
            }
            if (saved == null) {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    filters.forEachIndexed { i, f ->
                        FilterChip(f, selected = i == filter, onClick = { filter = i })
                    }
                }
            } else if (groups.isNotEmpty()) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    FilterChip("All", selected = selectedGroup == null, onClick = { selectedGroup = null })
                    groups.forEach { g -> FilterChip(g, selected = g == selectedGroup, onClick = { selectedGroup = g }) }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .background(Wrt.Red.copy(alpha = 0.07f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(error, style = sans(12f, 500, Wrt.Red))
                }
                Spacer(Modifier.height(10.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (saved != null && shownSaved != null) {
                    val sections = routerSections(shownSaved, saved, pinned)
                    sections.forEachIndexed { i, section ->
                        // Headers only once there is more than one place to be: a list with no
                        // groups is one unnamed section and gets none.
                        if (sections.size > 1 || section.group != null) {
                            val g = section.group
                            GroupHeader(
                                g ?: "Ungrouped", section.routers.size, first = i == 0,
                                // Only a real group can be pinned; the remainder has no name to keep.
                                pinned = g?.let { it in pinned },
                                onTogglePin = g?.let { { onTogglePin(it) } },
                            )
                        }
                        section.routers.forEach { e ->
                            SwipeToReveal(
                                actions = listOf(
                                    RevealAction("Edit", WrtIcons.Pencil, Wrt.Accent) { renaming = e },
                                    RevealAction("Delete", WrtIcons.Trash, Wrt.Red) { confirmDelete = e },
                                ),
                                resetKey = e.id,
                                corner = 14.dp,
                            ) { swipe ->
                                RouterCard(
                                    e.asRouter(connectedIdentity, connectingIdentity),
                                    onClick = { onOpenSaved(e) },
                                    modifier = swipe,
                                )
                            }
                        }
                    }
                    if (saved.isEmpty()) {
                        Text(
                            "No routers saved yet — add one below.",
                            style = mono(11f, 500, Wrt.TextDim),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                        )
                    } else if (shownSaved.isEmpty()) {
                        Text(
                            "Nothing matches \u201c${query.trim()}\u201d — try a name, address or model.",
                            style = mono(11f, 500, Wrt.TextDim),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    shownDemo.forEach { r -> RouterCard(r, onClick = { onOpenRouter(r) }) }
                    if (shownDemo.isEmpty() && query.isNotBlank()) {
                        Text(
                            "Nothing matches \u201c${query.trim()}\u201d",
                            style = mono(11f, 500, Wrt.TextDim),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(90.dp))
            }
        }
        renaming?.let { entity ->
            EditRouterDialog(
                entity = entity,
                connectedIdentity = connectedIdentity,
                groups = groups,
                onDismiss = { renaming = null },
                onConfirm = { name, host, port, group ->
                    renaming = null
                    onEdit(entity, name, host, port, group)
                },
            )
        }
        confirmDelete?.let { entity ->
            ForgetRouterDialog(
                entity = entity,
                connectedIdentity = connectedIdentity,
                onDismiss = { confirmDelete = null },
                onConfirm = { onDelete(entity); confirmDelete = null },
            )
        }
        // FAB
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
                .shadow(14.dp, RoundedCornerShape(17.dp), ambientColor = Wrt.Accent, spotColor = Wrt.Accent)
                .size(56.dp)
                .background(Wrt.Accent, RoundedCornerShape(17.dp))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WrtIcons.Plus, "add router", Modifier.size(24.dp), tint = Wrt.OnAccent)
        }
    }
}

/** Replaces the title row while a search is open. Focused on open, so typing starts at once. */
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(WrtIcons.Search, null, Modifier.size(17.dp), tint = Wrt.Accent)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("Name, address or model", style = sans(14f, 500, Wrt.TextFaint))
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = sans(14f, 500),
                singleLine = true,
                cursorBrush = SolidColor(Wrt.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Icon(
            WrtIcons.Close, "close search",
            Modifier.size(18.dp).clickable(onClick = onClose),
            tint = Wrt.TextTertiary,
        )
    }
}

/**
 * A typed router name, or null when there is nothing usable in it.
 *
 * The name is the only thing identifying a card once several routers are saved, so a blank
 * one is refused rather than accepted and rendered as an empty row.
 */
internal fun routerName(input: String): String? =
    input.trim().take(48).ifBlank { null }

/**
 * A typed address, normalised, or null when there is nothing usable in it.
 *
 * Accepts what people actually paste: a bare address, one with a scheme or trailing slash
 * from a browser bar, or `host:port`. IPv6 keeps its colons — only a single trailing
 * `:digits` is read as a port, which is the one case that is unambiguous.
 */
internal fun routerAddress(input: String): Pair<String, Int?>? {
    val text = input.trim()
        .removePrefix("ssh://")
        .removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
        .trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val colons = text.count { it == ':' }
    if (colons == 1) {
        val host = text.substringBefore(':')
        val port = text.substringAfter(':').toIntOrNull()
        if (host.isNotEmpty() && port != null) {
            return if (port in 1..65535) host to port else null
        }
    }
    return text.take(255) to null
}

/**
 * Why an edited entry cannot be saved, or null when it can.
 *
 * Another entry on the same address is not a reason. Two routers on two networks can both
 * answer at 192.168.1.1; each row is opened on its own, carries its own credential and its
 * own pinned host key, and the key is what tells them apart at connect time.
 */
internal fun routerEditBlock(name: String, address: String): String? {
    if (routerName(name) == null) return "A router needs a name to show on its card."
    routerAddress(address) ?: return "Enter an address — an IP or a hostname."
    return null
}

/**
 * What changing a saved entry's address does and does not do.
 *
 * The first line is the one worth having: this moves where the app looks, not where the
 * router answers. Someone reading "change router IP" can reasonably expect the opposite,
 * and the screen that does the opposite is one tab away.
 */
internal fun routerAddressNotes(
    entity: RouterEntity,
    address: String,
    connectedIdentity: String?,
): List<String> = buildList {
    val host = routerAddress(address)?.first ?: return@buildList
    if (host == entity.host) return@buildList
    add(
        "This changes where the app looks for the router, not the router's own address. " +
            "To move the router itself, use Network · LAN & local network while connected to it."
    )
    add(
        "The host key saved for this entry moves with it. If a different router answers at " +
            "$host, you get the changed-key warning, not a first-contact prompt."
    )
    if (connectedIdentity != null && connectedIdentity == entity.identity) {
        add("The session open on ${entity.host} right now stays on ${entity.host} until you reconnect.")
    }
}

/**
 * What forgetting a saved router costs, beyond the row itself.
 *
 * Deleting the entry is local — it never touches the router — so the things worth saying are
 * the ones the app cannot undo for you: a key it installed stays installed, and a live
 * session outlives its entry.
 */
internal fun forgetRouterNotes(entity: RouterEntity, connectedIdentity: String?): List<String> = buildList {
    if (entity.isMeshNode) {
        add(
            "This router is a mesh node. Its pre-mesh backup is filed under this entry; delete the " +
                "entry and Leave mesh can no longer find it. Leave the mesh from Network · Mesh first " +
                "if you want the router back the way it was."
        )
    }
    if (entity.privateKey != null) {
        add(
            "The app's SSH key stays in this router's authorized_keys. Remove it from " +
                "System · SSH keys first if you want it gone."
        )
    }
    if (connectedIdentity != null && connectedIdentity == entity.identity) {
        add("You are connected to this router now. The session stays open, but the saved entry goes.")
    }
    add("Its saved password or key and its pinned host key are deleted from this phone. Nothing changes on the router.")
}

@Composable
private fun RouterCard(r: Router, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val offline = r.status == RouterStatus.Offline
    val borderColor = when {
        r.status == RouterStatus.Online -> Wrt.Accent.copy(alpha = 0.4f)
        offline -> Wrt.BorderHair
        else -> Wrt.BorderCard
    }
    val (dotColor, statusLabel, pulse, periodMs) = when (r.status) {
        RouterStatus.Online -> Quad(Wrt.Green, "online", true, 2400)
        RouterStatus.Reconnecting -> Quad(Wrt.Amber, "connecting", true, 1600)
        RouterStatus.Offline -> Quad(Wrt.DotOff, "offline", false, 0)
        RouterStatus.Saved -> Quad(Wrt.TextTertiary, "saved", false, 0)
    }
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .background(if (offline) Wrt.BgCardDim else Wrt.BgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .alpha(if (offline) 0.72f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RouterTile(
            ledColor = when (r.status) {
                RouterStatus.Online -> Wrt.Accent
                RouterStatus.Reconnecting -> Wrt.Amber
                RouterStatus.Offline -> Wrt.TextDim
                RouterStatus.Saved -> Wrt.TextTertiary
            },
            dim = offline,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    r.name,
                    style = sans(14.5f, 650, if (offline) Wrt.TextSecondary else Wrt.TextPrimary),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // An address never wraps: the column it sits in gives way to the name instead.
                MonoTag(r.tag, color = if (offline) Wrt.TextDim else Wrt.TextTertiary, border = if (offline) Wrt.BorderFaint else Wrt.BorderInput, size = 8.5f)
                if (r.meshNode) MonoTag("NODE", color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.45f), size = 8f)
            }
            Text(
                r.model,
                style = sans(11.5f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(dotColor, 7.dp, pulse = pulse, periodMs = periodMs)
                Text(
                    statusLabel,
                    style = sans(11f, 600, when (r.status) {
                        RouterStatus.Online -> Wrt.Green
                        RouterStatus.Reconnecting -> Wrt.Amber
                        else -> Wrt.TextDim
                    }),
                )
            }
            if (r.wanIp != null) Text(r.wanIp, style = mono(11f, 500, Wrt.TextTertiary))
            Text(r.detail, style = sans(11f, 400, Wrt.TextDim), maxLines = 1)
        }
    }
}

private data class Quad(val c: Color, val s: String, val p: Boolean, val ms: Int)

/**
 * A section's name over its cards, with how many it holds, and its pin: lit when the group is
 * kept at the top, faint otherwise, a tap either way. [pinned] is null for the unnamed
 * remainder, which has nothing to pin.
 */
@Composable
private fun GroupHeader(name: String, count: Int, first: Boolean, pinned: Boolean?, onTogglePin: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = if (first) 2.dp else 10.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(name.uppercase(), color = if (pinned == true) Wrt.Accent else Wrt.TextDim)
        Text("$count", style = mono(10f, 500, Wrt.TextFaint))
        if (pinned != null && onTogglePin != null) {
            FlexSpacer()
            Icon(
                WrtIcons.Pin, if (pinned) "unpin $name" else "pin $name",
                Modifier.size(15.dp).clickable(onClick = onTogglePin),
                tint = if (pinned) Wrt.Accent else Wrt.TextFaint,
            )
        }
    }
}

/** Forgetting a router is local and reversible only by adding it again, so it is confirmed. */
/**
 * Name and address for a saved router — design screen 04's swipe action, widened.
 *
 * Both are local: the name is what the card says, and the address is where the app knocks.
 * Neither reaches the router, which the dialog says outright, because "change the router's
 * IP" is a sentence that can mean either this or the LAN screen's version of it.
 */
@Composable
private fun EditRouterDialog(
    entity: RouterEntity,
    connectedIdentity: String?,
    /** Groups already in use, offered as one-tap shortcuts under the field. */
    groups: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int, String?) -> Unit,
) {
    var name by remember(entity.id) { mutableStateOf(entity.name) }
    var group by remember(entity.id) { mutableStateOf(entity.groupName.orEmpty()) }
    var address by remember(entity.id) {
        mutableStateOf(if (entity.port == 22) entity.host else "${entity.host}:${entity.port}")
    }
    val block = routerEditBlock(name, address)
    val notes = routerAddressNotes(entity, address, connectedIdentity)
    val parsed = routerAddress(address)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Edit router", style = sans(15f, 650))
            Text(
                entity.model.ifBlank { entity.summary }.ifBlank { "saved router" },
                style = mono(10.5f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(14.dp))
            SectionLabel("NAME")
            DialogField(name) { name = it }
            Spacer(Modifier.height(12.dp))
            SectionLabel("ADDRESS")
            DialogField(address) { address = it }
            Text(
                parsed?.let { (host, port) ->
                    "ssh ${entity.username}@$host" + (port ?: entity.port).let { p ->
                        if (p == 22) "" else " -p $p"
                    }
                } ?: "An IP or hostname, optionally host:port.",
                style = mono(10f, 500, if (parsed == null) Wrt.Red else Wrt.TextDim),
                modifier = Modifier.padding(top = 6.dp),
            )
            notes.forEach { note ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(14.dp), tint = Wrt.Amber)
                    Text(note, style = sans(11.5f, 400, Wrt.AmberText, lineHeight = 17.sp))
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionLabel("GROUP")
            DialogField(group) { group = it }
            val chosen = routerGroupName(group)
            Text(
                if (chosen == null) "Optional — Home, Office, a client's name. Blank leaves it in none."
                else "Filed under $chosen on the list.",
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 6.dp),
            )
            if (groups.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    groups.forEach { g ->
                        // Tapping the one already chosen clears it, so a group is one tap either way.
                        FilterChip(g, selected = g == chosen, size = 11f, padH = 10.dp, padV = 4.dp) {
                            group = if (g == chosen) "" else g
                        }
                    }
                }
            }
            if (block != null) {
                Text(
                    block,
                    style = sans(11.5f, 500, Wrt.Red, lineHeight = 17.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (block == null) {
                PrimaryButton("Save") {
                    val (host, port) = routerAddress(address)!!
                    onConfirm(routerName(name)!!, host, port ?: entity.port, routerGroupName(group))
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(Wrt.BgDeep, RoundedCornerShape(12.dp))
                        .border(1.dp, Wrt.BorderInput, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Save", style = sans(13.5f, 600, Wrt.TextDim))
                }
            }
            Spacer(Modifier.height(6.dp))
            GhostButton("Cancel", onClick = onDismiss)
        }
    }
}

/** The dialog's one-line input, in the shape [WrtInputDialog] uses. */
@Composable
internal fun DialogField(value: String, onChange: (String) -> Unit) {
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
            onValueChange = onChange,
            textStyle = mono(13f, 500),
            singleLine = true,
            cursorBrush = SolidColor(Wrt.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ForgetRouterDialog(
    entity: RouterEntity,
    connectedIdentity: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Forget this router?", style = sans(15f, 650))
            Text(
                entity.name,
                style = sans(12.5f, 600, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "${entity.username}@${entity.host}:${entity.port}",
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 3.dp),
            )
            forgetRouterNotes(entity, connectedIdentity).forEach {
                Text(it, style = sans(10.5f, 500, Wrt.AmberText), modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Forget", color = Wrt.Red, textColor = Wrt.OnRed, onClick = onConfirm)
            Spacer(Modifier.height(6.dp))
            GhostButton("Cancel", border = Wrt.TextTertiary, onClick = onDismiss)
        }
    }
}
