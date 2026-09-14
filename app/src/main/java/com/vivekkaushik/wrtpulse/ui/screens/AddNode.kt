package com.vivekkaushik.wrtpulse.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.JoinStep
import com.vivekkaushik.wrtpulse.data.MeshJoin
import com.vivekkaushik.wrtpulse.data.NodeSetup
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ops.Backhaul
import com.vivekkaushik.wrtpulse.ops.CaseSocket
import com.vivekkaushik.wrtpulse.ops.MeshOps
import com.vivekkaushik.wrtpulse.ops.SocketRole
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

// ---------------------------------------------------------------------------
// Add a node — one instruction per screen
// ---------------------------------------------------------------------------

private enum class AddStep { Cable1, Cable2, Finding, Password, Review, Progress, Done }

/**
 * The cable-first guide on the primary's side, as a stepper: a large illustration, one
 * headline, one line of help, one button pinned at the bottom. The primary holds a LAN socket
 * apart, the user runs a cable, the primary finds the new router and signs in over the cable,
 * and the join runs through that hop — this phone never leaves the primary's Wi-Fi.
 */
@Composable
fun AddNodeScreen(hooks: MeshHooks, onBack: () -> Unit, onDone: () -> Unit) {
    val primary = hooks.current
    val store = hooks.store
    val scope = rememberCoroutineScope()
    val setup = remember { hooks.newSetup() }
    var join by remember { mutableStateOf<MeshJoin?>(null) }
    var step by remember { mutableStateOf(AddStep.Cable1) }
    var signing by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var wiping by remember { mutableStateOf(false) }
    var wipeArmed by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    val reduced = rememberReducedMotion()

    LaunchedEffect(setup) { setup?.run() }
    // Leaving before the join has the socket: give it back now rather than in twenty minutes.
    // Once a join is writing, the socket is its to return (the join's beforeFollow does it).
    DisposableEffect(setup) {
        onDispose {
            val j = join
            if (setup != null && (j == null || (!j.applying && !j.done))) CoroutineScope(Dispatchers.IO).launch { setup.release() }
        }
    }
    DisposableEffect(join) {
        val j = join
        onDispose { if (j != null) CoroutineScope(Dispatchers.IO).launch { j.close() } }
    }

    // The primary notices the cable on its own; the cable screens step aside when it does.
    val phase = setup?.phase
    LaunchedEffect(phase) {
        if ((step == AddStep.Cable1 || step == AddStep.Cable2) &&
            phase in setOf(NodeSetup.Phase.Finding, NodeSetup.Phase.Found, NodeSetup.Phase.Connecting, NodeSetup.Phase.Ready)
        ) step = AddStep.Finding
    }
    val j = join
    LaunchedEffect(j?.done) { if (j?.done == true) step = AddStep.Done }
    if (step == AddStep.Done && j != null && j.backhaul == Backhaul.Wireless) {
        LaunchedEffect(j) { while (true) { j.checkLink(); delay(5_000) } }
    }

    LaunchedEffect(wipeArmed) { if (wipeArmed) { delay(6_000); wipeArmed = false } }

    fun back() {
        when (step) {
            AddStep.Cable1 -> onBack()
            AddStep.Cable2 -> step = AddStep.Cable1
            AddStep.Finding -> step = AddStep.Cable2
            AddStep.Password -> step = AddStep.Finding
            AddStep.Review -> step = AddStep.Password
            AddStep.Progress -> if (j?.applying != true) step = AddStep.Review
            AddStep.Done -> onDone()
        }
    }
    BackHandler { back() }

    fun retryDiscovery() {
        if (setup == null) return
        scope.launch { setup.release(); setup.run() }
        step = AddStep.Cable1
    }

    fun signIn() {
        if (signing || setup == null || store == null) return
        if (join != null) { step = AddStep.Review; return }
        scope.launch {
            signing = true
            signInError = null
            val session = setup.connect()
            if (session != null) {
                // The guest and IoT trunks on this router, before the node is told to use them.
                runCatching { store.ensureTrunks() }
                val nj = hooks.newJoinVia(setup)
                if (nj != null) {
                    nj.beforeFollow = { setup.release(keepCable = true) }
                    runCatching { nj.load() }
                    join = nj
                    step = AddStep.Review
                } else {
                    signInError = "Could not start the join from here. Try again."
                }
            } else {
                signInError = setup.error
            }
            signing = false
        }
    }

    val primaryName = primary?.name ?: "this router"
    val candidates = hooks.saved.filter { primary != null && it.identity != primary.identity && !it.isMeshNode }
    val model = setup?.board?.model?.ifBlank { null }
    val socket = setup?.portLabel ?: "?"
    // The primary's real sockets, the held one marked from the setup's own reading.
    val sockets = store?.caseSockets(setup?.port).orEmpty()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
            // Top bar: an X, because the guide is a modal flow rather than a page in a stack.
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(Wrt.BgBar).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(WrtIcons.Close, "close", Modifier.size(22.dp).clickable { onBack() }, tint = Wrt.TextPrimary)
                Text("Add a node", style = sans(16f, 650), modifier = Modifier.weight(1f))
                primary?.let { MonoTag(it.name, size = 10.5f) }
            }
            // Progress: five segments and the step label.
            val idx = when (step) {
                AddStep.Cable1 -> 1; AddStep.Cable2 -> 2; AddStep.Finding -> 3; AddStep.Password -> 4; else -> 5
            }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { i ->
                        val c by animateColorAsState(if (i <= idx) Wrt.Accent else Wrt.ProgressTrack, tween(300), label = "seg$i")
                        Box(Modifier.weight(1f).height(3.dp).background(c, RoundedCornerShape(2.dp)))
                    }
                }
                Text(
                    when (step) { AddStep.Progress -> "JOINING"; AddStep.Done -> "DONE"; else -> "STEP $idx OF 5" },
                    style = mono(10f, 500, Wrt.TextDim, letterSpacing = 1.sp),
                )
            }

            if (primary == null || store == null || setup == null) {
                Text("Not connected.", style = sans(12f, 400, Wrt.TextDim), modifier = Modifier.padding(14.dp))
                return@Column
            }

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    else (slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it / 8 } + fadeIn(tween(250))) togetherWith fadeOut(tween(120))
                },
                label = "addStep",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (s) {
                        AddStep.Cable1 -> {
                            HeroWell { BigRouter(primaryName, isPrimary = true, sockets = sockets, socketLabel = socket, reduced = reduced) }
                            Headline(
                                "Plug a cable into socket $socket of $primaryName",
                                "It is held apart for the new router. Any Ethernet cable will do.",
                            )
                        }
                        AddStep.Cable2 -> {
                            HeroWell { BigRouter("new router", isPrimary = false, sockets = emptyList(), socketLabel = socket, reduced = reduced) }
                            Headline(
                                "Plug the other end into any LAN socket of the new router",
                                "Not its WAN socket — usually the odd-coloured one.",
                            )
                        }
                        AddStep.Finding -> {
                            val found = phase == NodeSetup.Phase.Found || phase == NodeSetup.Phase.Connecting || phase == NodeSetup.Phase.Ready
                            HeroWell {
                                RouterPair(
                                    if (found) PairPhase.Found else PairPhase.Finding,
                                    primaryName, model ?: "new router", sockets, socket, reduced,
                                )
                            }
                            when {
                                phase == NodeSetup.Phase.Failed -> Headline("Could not find it", setup.error ?: "Nothing answered on $socket.")
                                found -> Headline("Found a ${model ?: "router"}", "It answers on $socket. Leave the cable in from here on.")
                                phase == NodeSetup.Phase.Finding -> Headline("Looking for the router…", "$primaryName is listening on $socket. This takes a few seconds.")
                                else -> Headline("Waiting for the cable…", "Nothing on $socket yet. Check both ends are seated; the light on the socket comes on when it is.")
                            }
                            if (setup.candidates.size > 1) {
                                Note("${setup.candidates.size} devices answer on that socket; the first is tried. Unplug anything else on it.", Wrt.Amber)
                            }
                        }
                        AddStep.Password -> {
                            val refused = phase == NodeSetup.Phase.Failed
                            HeroWell {
                                RouterPair(
                                    if (signing || wiping) PairPhase.Signing else if (refused) PairPhase.Failed else PairPhase.Found,
                                    primaryName, model ?: "new router", sockets, socket, reduced,
                                )
                            }
                            if (setup.failsafe) {
                                Headline(
                                    "It booted into failsafe mode",
                                    "The reset button was held while it started, so it is running with no services and " +
                                        "none of its saved settings: nothing can join from there. Power it off and on to boot " +
                                        "normally — or, if it should start over as a fresh install, reset it from here.",
                                )
                                Note("Resetting wipes every setting on it. The cable stays in and $primaryName finds it again once it is back up.", Wrt.Amber)
                                signInError?.takeIf { !it.contains("failsafe") }?.let { Text(it, style = mono(11f, 500, Wrt.Red)) }
                            } else {
                                Headline("Enter its root password", "$primaryName signs in over the cable. A fresh OpenWrt install has none: leave this empty. The password never leaves your network.")
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("ROOT PASSWORD", style = mono(10f, 500, Wrt.TextDim, letterSpacing = 1.sp))
                                    FormTextField(setup.password, { setup.password = it }, password = true, masked = true)
                                    signInError?.let { Text(it, style = mono(11f, 500, Wrt.Red)) }
                                }
                            }
                        }
                        AddStep.Review -> {
                            val jj = join
                            if (jj == null) Headline("Ready to join", "Reading the router…")
                            else ReviewBody(jj, primaryName, socket)
                        }
                        AddStep.Progress -> {
                            val jj = join
                            val failed = jj != null && !jj.applying && !jj.done && jj.error != null
                            if (failed) {
                                Headline(
                                    "Could not finish",
                                    if (jj!!.rolledBack) "Nothing changed on $primaryName. ${jj.name} put its own config back and is exactly as it was."
                                    else jj.error ?: "The join stopped.",
                                )
                            } else {
                                Headline("Joining…", "Everything runs through $primaryName over the cable; this phone stays where it is.")
                            }
                            if (jj != null) ProgressList(jj.steps, reduced)
                        }
                        AddStep.Done -> {
                            val jj = join
                            val wired = jj?.backhaul != Backhaul.Wireless
                            HeroWell(height = 92.dp) {
                                MeshTopology(
                                    primaryName,
                                    listOf(TopoNode(jj?.name ?: "node", wired, true, jj?.linkSignalDbm, TopoSync.Ok)),
                                    pushing = false, height = 92.dp,
                                )
                            }
                            Headline(
                                "${jj?.name ?: "It"} is now a node of $primaryName",
                                if (wired) "Leave the cable in — that is its wired backhaul."
                                else "Unplug the cable and place it where it should live. Its mesh point finds $primaryName on its own; if the link never comes up, plug the cable back in — it works wired on any socket now.",
                            )
                        }
                    }
                }
            }

            // Pinned footer: the one status line and the one button.
            val status: Triple<String, Color, Boolean>? = when (step) {
                AddStep.Cable1, AddStep.Cable2 -> when (phase) {
                    NodeSetup.Phase.Isolating -> Triple("setting a socket apart…", Wrt.Accent, true)
                    NodeSetup.Phase.Failed -> Triple(setup.error ?: "failed", Wrt.Red, false)
                    else -> Triple("socket $socket held · waiting for the cable…", Wrt.TextDim, true)
                }
                AddStep.Finding -> when (phase) {
                    NodeSetup.Phase.Found, NodeSetup.Phase.Connecting, NodeSetup.Phase.Ready -> Triple("a router answers on $socket", Wrt.Green, false)
                    NodeSetup.Phase.Finding -> Triple("cable in $socket; looking for the router…", Wrt.Accent, true)
                    NodeSetup.Phase.Failed -> Triple(setup.error ?: "failed", Wrt.Red, false)
                    else -> Triple("socket $socket held · waiting for the cable…", Wrt.TextDim, true)
                }
                AddStep.Password -> when {
                    wiping -> Triple("resetting through $primaryName…", Wrt.Accent, true)
                    signing -> Triple("signing in through $primaryName…", Wrt.Accent, true)
                    setup.failsafe -> Triple("failsafe mode · no services running", Wrt.Amber, false)
                    phase == NodeSetup.Phase.Failed -> Triple(setup.error?.removeSuffix(".") ?: "sign-in failed", Wrt.Red, false)
                    else -> Triple("${model ?: "router"} · reached on $socket", Wrt.Green, false)
                }
                AddStep.Done -> {
                    val jj = join
                    when {
                        jj == null -> null
                        jj.backhaul != Backhaul.Wireless -> Triple("${jj.newAddress} · wired · Wi-Fi up to date", Wrt.Green, false)
                        !jj.linkChecked -> Triple("${jj.newAddress} · wireless · checking the mesh link…", Wrt.TextDim, true)
                        jj.linkSignalDbm == null -> Triple("${jj.newAddress} · wireless · no mesh peer yet", Wrt.Amber, true)
                        jj.linkSignalDbm!! < -75 -> Triple("${jj.newAddress} · wireless · ${jj.linkSignalDbm} dBm, move it closer", Wrt.Amber, false)
                        else -> Triple("${jj.newAddress} · wireless · ${jj.linkSignalDbm} dBm", Wrt.Green, false)
                    }
                }
                else -> null
            }
            val found = phase == NodeSetup.Phase.Found || phase == NodeSetup.Phase.Connecting || phase == NodeSetup.Phase.Ready
            val plan = if (step == AddStep.Review) join?.plan() else null
            val joinFailed = step == AddStep.Progress && join?.let { !it.applying && !it.done && it.error != null } == true
            val (primaryLabel, primaryEnabled, primaryColor, primaryGo) = when (step) {
                AddStep.Cable1 -> FooterAction("Next", phase != NodeSetup.Phase.Failed, Wrt.Accent) { step = AddStep.Cable2 }
                AddStep.Cable2 -> FooterAction("I've plugged it in", true, Wrt.Accent) { step = AddStep.Finding }
                AddStep.Finding ->
                    if (phase == NodeSetup.Phase.Failed) FooterAction("Try again", true, Wrt.Amber) { retryDiscovery() }
                    else FooterAction("Continue", found, Wrt.Accent) { step = AddStep.Password }
                AddStep.Password ->
                    if (setup.failsafe) FooterAction(
                        if (wiping) "Resetting…" else if (wipeArmed) "Tap again — wipes its settings" else "Reset it and reboot",
                        !wiping, if (wipeArmed) Wrt.Red else Wrt.Amber,
                    ) {
                        if (!wipeArmed) wipeArmed = true
                        else scope.launch {
                            wipeArmed = false
                            wiping = true
                            if (setup.wipeAndReboot()) { step = AddStep.Finding; setup.run() }
                            wiping = false
                        }
                    }
                    else FooterAction(if (signing) "Signing in…" else "Sign in", !signing, Wrt.Accent) { signIn() }
                AddStep.Review -> FooterAction(
                    "Join", plan != null && plan.problems.isEmpty(), Wrt.Accent,
                ) {
                    val jj = join ?: return@FooterAction
                    step = AddStep.Progress
                    scope.launch { jj.apply { outcome -> hooks.persistVia(setup, jj, outcome) } }
                }
                AddStep.Progress ->
                    if (joinFailed) FooterAction("Try again", true, Wrt.Amber) { step = AddStep.Review }
                    else FooterAction("Joining…", false, Wrt.Accent) {}
                AddStep.Done -> FooterAction("Open ${join?.name ?: "the node"}", true, Wrt.Accent) {
                    val row = hooks.saved.firstOrNull { it.identity == setup.identity }
                    if (row != null) hooks.onOpenRouter(row) else onDone()
                }
            }
            val ghost: Pair<String, () -> Unit>? = when (step) {
                AddStep.Cable1 -> if (candidates.isNotEmpty()) "Join a router already in this app" to { showSaved = true } else null
                AddStep.Cable2 -> "Back" to { step = AddStep.Cable1 }
                AddStep.Finding ->
                    if (phase == NodeSetup.Phase.Failed) "Back" to { step = AddStep.Cable2 }
                    else if (found) "That's not the right router" to { retryDiscovery() }
                    else "Back" to { step = AddStep.Cable2 }
                AddStep.Password -> "Back" to { step = AddStep.Finding }
                AddStep.Review -> "Back" to { step = AddStep.Password }
                AddStep.Progress -> if (joinFailed) "Back to Mesh" to { onBack() } else null
                AddStep.Done -> "Back to Mesh" to { onDone() }
            }
            Column(
                Modifier.fillMaxWidth().background(Wrt.BgScreen).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderRow).offset(y = (-12).dp))
                status?.let { (text, tone, pulse) ->
                    Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(tone, 8.dp, pulse = pulse && !reduced, periodMs = 1600)
                        Text(text, style = mono(11f, 500, tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                PrimaryButton(primaryLabel, Modifier.alpha(if (primaryEnabled) 1f else 0.4f), color = primaryColor) { if (primaryEnabled) primaryGo() }
                ghost?.let { (label, go) -> GhostButton(label, onClick = go) }
            }
        }
        SheetHost(visible = showSaved, onDismiss = { showSaved = false }) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 18.dp)) {
                Text("Already in this app?", style = sans(16f, 650))
                Text(
                    "A router this app can already reach joins from its own Mesh page: the app connects to it and opens the join for $primaryName there.",
                    style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp), modifier = Modifier.padding(top = 8.dp),
                )
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
                        GhostButton("Connect & join", Modifier.width(132.dp), border = Wrt.Accent.copy(alpha = 0.5f), textColor = Wrt.Accent) {
                            primary?.let { hooks.onConnectToJoin(r, it) }
                        }
                    }
                }
            }
        }
    }
}

private data class FooterAction(val label: String, val enabled: Boolean, val color: Color, val go: () -> Unit)

@Composable
private fun HeroWell(height: androidx.compose.ui.unit.Dp? = null, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier).clip(RoundedCornerShape(14.dp)).background(Wrt.BgDeep),
        contentAlignment = Alignment.TopCenter,
    ) {
        // The drawings fill their width at a fixed aspect ratio, drawn for a phone. On a
        // foldable's inner screen that width is 630 dp and the router grew 430 dp tall,
        // pushing the headline off the first screen; the well still spans the page, the
        // drawing inside it stops at the phone's width and sits centred.
        Box(Modifier.widthIn(max = HeroDrawingMaxWidth).fillMaxWidth()) { content() }
    }
}

/** A phone's content width: what every hero drawing was drawn for. */
private val HeroDrawingMaxWidth = 340.dp

@Composable
private fun Headline(title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = sans(22f, 650, lineHeight = 28.sp))
        Text(sub, style = sans(13f, 400, Wrt.TextSecondary, lineHeight = 19.sp))
    }
}

@Composable
private fun Note(text: String, tone: Color) {
    Text(text, style = sans(11f, 400, tone, lineHeight = 16.sp))
}

@Composable
private fun ReviewBody(join: MeshJoin, primaryName: String, socket: String) {
    val plan = join.plan()
    val p = join.profile
    Headline("Ready to join", "${join.name.ifBlank { "It" }} becomes a node of $primaryName with the same Wi-Fi everywhere.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NAME", style = mono(10f, 500, Wrt.TextDim, letterSpacing = 1.sp))
        FormTextField(join.name, { join.name = it })
        Text("hostname ${MeshOps.hostnameOf(join.name)}", style = mono(10f, 500, Wrt.TextDim))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("BACKHAUL", style = mono(10f, 500, Wrt.TextDim, letterSpacing = 1.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Backhaul.entries.forEach { b -> FilterChip(b.label, selected = join.backhaul == b) { join.backhaul = b } }
        }
        Note(
            when (join.backhaul) {
                Backhaul.Wired -> "The cable stays in. The node gets its own 5 GHz channel."
                Backhaul.Wireless -> if (p.wirelessReady) "Joins ${p.meshId} on ${p.meshBand} once the cable comes out at the end. Wireless nodes share $primaryName's channel."
                else "$primaryName has no mesh link yet. Turn it on from its Mesh page first."
            },
            if (join.backhaul == Backhaul.Wireless && !p.wirelessReady) Wrt.Amber else Wrt.TextDim,
        )
    }
    val bands = join.state?.radios?.map { it.band }?.toSet().orEmpty()
    val copied = p.ssids.count { it.band in bands } + p.extras.sumOf { x -> x.ssids.count { it.band in bands } }
    Column(
        Modifier.fillMaxWidth().border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp)).background(Wrt.BgCard, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        ReviewRow("address", plan?.address ?: "…", first = true)
        ReviewRow("Wi-Fi", if (join.loaded) "$copied SSID${if (copied == 1) "" else "s"} copied" else "…")
        ReviewRow("backhaul", "${join.backhaul.label.lowercase()} · $socket")
        plan?.swap?.let { ReviewRow("package", it.install) }
        ReviewRow("backup", "saved first")
    }
    Note(
        "DHCP, DNS and the firewall turn off on ${join.name.ifBlank { "the node" }}; $primaryName keeps doing that. " +
            "If it can't be reached within ${MeshOps.ROLLBACK_SECONDS} s it restores itself.",
        Wrt.TextDim,
    )
    plan?.problems?.forEach { Note(it, Wrt.Red) }
    join.error?.let { Note(it, Wrt.Red) }
}

@Composable
private fun ReviewRow(label: String, value: String, first: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().then(if (first) Modifier else Modifier.rowTopLine()).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = mono(12f, 400, Wrt.TextDim))
        Text(value, style = mono(12f, 400, Wrt.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Modifier.rowTopLine(): Modifier = drawBehind {
    drawLine(Wrt.BorderRow, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
}

// ---------------------------------------------------------------------------
// The join's progress: a ticked list with one spinner that walks down it
// ---------------------------------------------------------------------------

@Composable
private fun ProgressList(steps: List<JoinStep>, reduced: Boolean) {
    val running = steps.indexOfFirst { it.state == JoinStep.State.Running }
    val rowH = 46.dp
    Box(Modifier.fillMaxWidth()) {
        Column {
            steps.forEach { s ->
                Row(Modifier.fillMaxWidth().height(rowH).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        when (s.state) {
                            JoinStep.State.Pending, JoinStep.State.Running -> Box(Modifier.size(14.dp).border(1.5.dp, Wrt.DotOff, RoundedCornerShape(50)))
                            JoinStep.State.Done -> Tick(reduced)
                            JoinStep.State.Failed -> Icon(WrtIcons.Close, null, Modifier.size(16.dp), tint = Wrt.Red)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.label, style = sans(12.5f, 400, if (s.state == JoinStep.State.Pending) Wrt.TextDim else Wrt.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = when (s.state) {
                            JoinStep.State.Running -> s.detail ?: "running…"
                            JoinStep.State.Done, JoinStep.State.Failed -> s.detail
                            JoinStep.State.Pending -> null
                        }
                        detail?.let { Text(it, style = mono(10.5f, 400, if (s.state == JoinStep.State.Failed) Wrt.Red else Wrt.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        if (running >= 0) {
            val top by animateDpAsState(rowH * running + 2.dp, tween(300, easing = FastOutSlowInEasing), label = "spinnerTop")
            Box(Modifier.offset(y = top).size(20.dp), contentAlignment = Alignment.Center) { Spinner(reduced) }
        }
    }
}

@Composable
private fun Spinner(reduced: Boolean) {
    val rot by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "spinRot",
    )
    Canvas(Modifier.size(14.dp)) {
        val w = 1.5.dp.toPx()
        drawCircle(Wrt.BorderHair, style = Stroke(w))
        drawArc(Wrt.Accent, if (reduced) -90f else rot - 90f, 90f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A tick that draws itself in over 250 ms the first time it appears. */
@Composable
private fun Tick(reduced: Boolean) {
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) progress.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 24f
        val path = Path().apply { moveTo(5f * u, 12.5f * u); lineTo(9.5f * u, 17f * u); lineTo(19f * u, 7f * u) }
        val m = PathMeasure().apply { setPath(path, false) }
        val seg = Path()
        m.getSegment(0f, m.length * progress.value, seg, true)
        drawPath(seg, Wrt.Green, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------
// Illustrations
// ---------------------------------------------------------------------------

/** Which of the four LAN sockets a label like `lan2` or `port 3` names, on the stock drawing. */
internal fun heldSocketIndex(label: String): Int =
    Regex("(\\d+)").find(label)?.value?.toIntOrNull()?.coerceIn(1, 4) ?: 2

/** The router nobody has read yet: one WAN and four LANs, as the design drew it. */
internal fun stockSockets(): List<CaseSocket> =
    listOf(CaseSocket("wan", "WAN", SocketRole.Wan)) + (1..4).map { CaseSocket("lan$it", "lan$it", SocketRole.Lan) }

/**
 * The socket the plug goes into. On a real socket list it is the held one; on the stock
 * drawing the number in the label picks it, as it always did. Never off the end.
 */
internal fun targetIndex(sockets: List<CaseSocket>, socketLabel: String, isPrimary: Boolean): Int {
    val held = sockets.indexOfFirst { it.role == SocketRole.Held }
    if (held >= 0) return held
    if (!isPrimary) return sockets.indexOfFirst { it.role == SocketRole.Lan }.coerceAtLeast(0)
    return heldSocketIndex(socketLabel).coerceIn(0, (sockets.size - 1).coerceAtLeast(0))
}

/**
 * One router, side-on and zoomed in on its socket row, so WAN vs LAN is unmistakable. On the
 * primary the held socket is the one lit; on the new router any LAN socket will do and the
 * WAN socket carries a red cross. A plug seats itself into the target socket on a loop.
 */
@Composable
private fun BigRouter(name: String, isPrimary: Boolean, sockets: List<CaseSocket>, socketLabel: String, reduced: Boolean) {
    val measurer = rememberTextMeasurer()
    // The primary is drawn as it is; a router nobody has read yet is drawn as the stock one.
    val socks = if (isPrimary) sockets.ifEmpty { stockSockets() } else stockSockets()
    val loop = rememberInfiniteTransition(label = "bigRouter")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "plug")
    val breathe by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "breathe")
    val target = targetIndex(socks, socketLabel, isPrimary)
    val labelStyle = mono(10f, 500, Wrt.TextDim)
    Canvas(Modifier.fillMaxWidth().aspectRatio(320f / 220f)) {
        val u = size.width / 320f
        val stroke = 1.5.dp.toPx()
        val bx = 20f; val by = 36f; val bw = 280f; val bh = 100f
        val n = socks.size
        // Two sockets sit at the stock size; a switch with eight shrinks them to fit the case.
        val gap = 12f; val sh = 28f; val sy = 84f
        val sw = minOf(36f, (bw - 40f - gap * (n - 1)) / n)
        val sx0 = bx + (bw - (n * sw + (n - 1) * gap)) / 2f
        fun sx(i: Int) = sx0 + i * (sw + gap)
        val tx = sx(target) + sw / 2f
        // body
        drawRoundRect(Wrt.TextSecondary, Offset(bx * u, by * u), Size(bw * u, bh * u), CornerRadius(10f * u), style = Stroke(stroke))
        drawCircle(if (isPrimary) Wrt.Green else Wrt.DotOff, 3f * u, Offset((bx + bw - 16f) * u, (by + 14f) * u))
        text(measurer, name, (bx + 2f) * u, (by + bh + 18f) * u, mono(11f, 500, Wrt.TextSecondary), centered = false)
        // sockets
        val breathing = 0.5f + 0.5f * (0.5f - 0.5f * cos(2.0 * PI * breathe).toFloat())
        socks.forEachIndexed { i, s ->
            val x = sx(i)
            val wan = s.role == SocketRole.Wan
            val tgt = i == target
            val free = s.role == SocketRole.Free
            val col = if (wan) Wrt.Blue else if (tgt) Wrt.Accent else if (isPrimary) Wrt.TextSecondary else Wrt.Accent
            val op = when {
                wan -> if (isPrimary) 0.6f else 0.9f
                tgt -> if (reduced) 1f else breathing
                free -> 0.25f
                isPrimary -> 0.4f
                else -> 0.55f
            }
            val c = col.copy(alpha = op)
            // The notch and the contact line are drawn at the socket's own width.
            val nx = x + sw / 3f; val nw = sw / 3f
            val lx0 = x + sw * 0.22f; val lx1 = x + sw * 0.78f
            drawRoundRect(c, Offset(x * u, sy * u), Size(sw * u, sh * u), CornerRadius(4f * u), style = Stroke(stroke))
            drawRect(Wrt.BgDeep, Offset(nx * u, sy * u), Size(nw * u, 5f * u))
            drawRect(c, Offset(nx * u, sy * u), Size(nw * u, 5f * u), style = Stroke(1.3.dp.toPx()))
            drawLine(c.copy(alpha = op * 0.6f), Offset(lx0 * u, (sy + 20f) * u), Offset(lx1 * u, (sy + 20f) * u), 1.dp.toPx())
            if (!tgt) {
                val label = if (wan) "WAN" else if (isPrimary) s.label else "LAN"
                text(measurer, label, (x + sw / 2f) * u, (sy + sh + 14f) * u, labelStyle.copy(color = if (wan) Wrt.Blue else Wrt.TextDim))
            }
            if (wan && !isPrimary) {
                drawLine(Wrt.Red, Offset(lx0 * u, (sy + 6f) * u), Offset(lx1 * u, (sy + 22f) * u), stroke, StrokeCap.Round)
                drawLine(Wrt.Red, Offset(lx1 * u, (sy + 6f) * u), Offset(lx0 * u, (sy + 22f) * u), stroke, StrokeCap.Round)
            }
        }
        // callout over the target socket
        val cb = Path().apply {
            moveTo((sx(target) - 2f) * u, (sy - 6f) * u); lineTo((sx(target) - 2f) * u, (sy - 9f) * u)
            lineTo((sx(target) + sw + 2f) * u, (sy - 9f) * u); lineTo((sx(target) + sw + 2f) * u, (sy - 6f) * u)
        }
        drawPath(cb, Wrt.Accent, style = Stroke(1.dp.toPx()))
        text(measurer, if (isPrimary) "$socketLabel · held for the new router" else "any LAN socket", tx * u, (sy - 14f) * u, labelStyle.copy(color = Wrt.Accent))
        // plug: free → seated over the first quarter, held, then fades out and starts again
        val seat = sy + 4f
        val free = sy + 92f
        val (py, alpha) = if (reduced) seat to 1f else {
            val k = (t / 0.25f).coerceIn(0f, 1f)
            val e = FastOutSlowInEasing.transform(k)
            (free + (seat - free) * e) to (if (t < 0.85f) 1f else 1f - (t - 0.85f) / 0.15f)
        }
        val pc = Wrt.TextPrimary.copy(alpha = alpha)
        drawRoundRect(Wrt.BgDeep, Offset((tx - 13f) * u, py * u), Size(26f * u, 40f * u), CornerRadius(3f * u))
        drawRoundRect(pc, Offset((tx - 13f) * u, py * u), Size(26f * u, 40f * u), CornerRadius(3f * u), style = Stroke(stroke))
        drawRoundRect(pc, Offset((tx + 13f) * u, (py + 8f) * u), Size(6f * u, 18f * u), CornerRadius(1f * u), style = Stroke(1.3.dp.toPx()))
        listOf(-7f, -2.5f, 2.5f, 7f).forEach { dx ->
            drawLine(pc, Offset((tx + dx) * u, (py + 4f) * u), Offset((tx + dx) * u, (py + 14f) * u), 1.2.dp.toPx())
        }
        drawLine(Wrt.TextSecondary.copy(alpha = alpha), Offset(tx * u, (py + 40f) * u), Offset(tx * u, (py + 100f) * u), stroke)
        if (!reduced) {
            val ring = when {
                t < 0.25f -> 0f
                t < 0.28f -> (t - 0.25f) / 0.03f
                t < 0.42f -> 1f - (t - 0.28f) / 0.14f
                else -> 0f
            }
            if (ring > 0f) drawCircle(Wrt.Accent.copy(alpha = ring), 26f * u, Offset(tx * u, (sy + sh / 2f) * u), style = Stroke(stroke))
        }
    }
}

private enum class PairPhase { Finding, Found, Signing, Failed }

/** Two routers joined by a cable: a pulse runs along it while the primary looks, a tick lands when it finds. */
@Composable
private fun RouterPair(phase: PairPhase, primaryName: String, nodeName: String, sockets: List<CaseSocket>, socketLabel: String, reduced: Boolean) {
    val measurer = rememberTextMeasurer()
    val loop = rememberInfiniteTransition(label = "pair")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "cablePulse")
    val found = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == PairPhase.Finding) found.snapTo(0f)
        else if (reduced) found.snapTo(1f)
        else if (found.value < 1f) found.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val primarySocks = sockets.ifEmpty { stockSockets() }
    val held = targetIndex(primarySocks, socketLabel, isPrimary = true)
    val labelStyle = mono(10f, 500, Wrt.TextSecondary)
    Canvas(Modifier.fillMaxWidth().aspectRatio(332f / 150f)) {
        val u = size.width / 332f
        val stroke = 1.5.dp.toPx()
        fun socket(x: Float, col: Color, op: Float) {
            val c = col.copy(alpha = op)
            drawRoundRect(c, Offset(x * u, 46f * u), Size(16f * u, 12f * u), CornerRadius(2f * u), style = Stroke(stroke))
            drawRect(Wrt.BgDeep, Offset((x + 5f) * u, 46f * u), Size(6f * u, 3f * u))
            drawRect(c, Offset((x + 5f) * u, 46f * u), Size(6f * u, 3f * u), style = Stroke(1.2.dp.toPx()))
        }
        /** Draws a router's socket row and answers with the x of each socket's centre. */
        fun router(x: Float, name: String, socks: List<CaseSocket>, heldIndex: Int?, pip: Color): List<Float> {
            drawRoundRect(Wrt.TextSecondary, Offset(x * u, 22f * u), Size(120f * u, 42f * u), CornerRadius(6f * u), style = Stroke(stroke))
            drawCircle(pip, 2.5f * u, Offset((x + 110f) * u, 31f * u))
            // Sockets sit 20 apart as drawn; a wider chip packs them tighter to stay on the case.
            val pitch = minOf(20f, (96f - 16f) / (socks.size - 1).coerceAtLeast(1))
            text(measurer, name, (x + 60f) * u, 143f * u, labelStyle)
            val centres = ArrayList<Float>(socks.size)
            for (i in socks.indices) {
                val role = socks[i].role
                val sx = x + 12f + pitch * i
                val col = when {
                    heldIndex == i -> Wrt.Accent
                    role == SocketRole.Wan -> Wrt.Blue
                    else -> Wrt.TextSecondary
                }
                val op = when {
                    heldIndex == i -> 1f
                    role == SocketRole.Wan -> 0.6f
                    role == SocketRole.Free -> 0.25f
                    else -> 0.45f
                }
                socket(sx, col, op)
                centres += sx + 8f
            }
            return centres
        }
        val isFound = phase != PairPhase.Finding
        val nodeSocks = stockSockets()
        val primaryXs = router(14f, primaryName, primarySocks, held, Wrt.Green)
        val nodeXs = router(198f, if (isFound) nodeName else "new router", nodeSocks, 1, if (!isFound) Wrt.DotOff else if (phase == PairPhase.Failed) Wrt.Red else Wrt.Green)
        // cable: from the primary's held socket to the new router's first LAN socket
        val a = primaryXs.getOrElse(held) { primaryXs.last() }; val b = nodeXs[1]
        val cable = Path().apply { moveTo(a * u, 66f * u); cubicTo(a * u, 120f * u, b * u, 120f * u, b * u, 66f * u) }
        val cableCol = when (phase) { PairPhase.Failed -> Wrt.Red; PairPhase.Signing -> Wrt.Accent; else -> Wrt.TextSecondary }
        drawPath(cable, cableCol, style = Stroke(stroke))
        fun plug(x: Float) {
            drawRoundRect(Wrt.BgDeep, Offset((x - 6f) * u, 48f * u), Size(12f * u, 18f * u), CornerRadius(1.5f * u))
            drawRoundRect(Wrt.TextPrimary, Offset((x - 6f) * u, 48f * u), Size(12f * u, 18f * u), CornerRadius(1.5f * u), style = Stroke(stroke))
            drawRoundRect(Wrt.TextPrimary, Offset((x + 6f) * u, 52f * u), Size(3f * u, 8f * u), CornerRadius(0.5f * u), style = Stroke(1.2.dp.toPx()))
            listOf(-3f, -1f, 1f, 3f).forEach { dx -> drawLine(Wrt.TextPrimary, Offset((x + dx) * u, 50f * u), Offset((x + dx) * u, 55f * u), 1.dp.toPx()) }
        }
        plug(a); plug(b)
        if (phase == PairPhase.Finding && !reduced) {
            val m = PathMeasure().apply { setPath(cable, false) }
            val segLen = 16f * u
            val start = pulse * (m.length - segLen)
            val seg = Path()
            m.getSegment(start, start + segLen, seg, true)
            drawPath(seg, Wrt.Accent, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (isFound && phase != PairPhase.Failed) {
            // ring out of the node's pip, then the tick draws in
            val f = found.value
            val ringP = (f / 0.55f).coerceIn(0f, 1f)
            if (ringP < 1f || reduced) {
                val r = 4f + 8f * ringP
                val op = if (reduced) 0.6f else 0.9f * (1f - ringP)
                drawCircle(Wrt.Green.copy(alpha = op), (if (reduced) 8f else r) * u, Offset(308f * u, 31f * u), style = Stroke(1.2.dp.toPx()))
            }
            val tickP = ((f - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (tickP > 0f) {
                val tick = Path().apply { moveTo(300f * u, 10f * u); lineTo(305f * u, 15f * u); lineTo(314f * u, 5f * u) }
                val m = PathMeasure().apply { setPath(tick, false) }
                val seg = Path()
                m.getSegment(0f, m.length * tickP, seg, true)
                drawPath(seg, Wrt.Green, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private fun DrawScope.text(measurer: TextMeasurer, s: String, x: Float, baseline: Float, style: TextStyle, centered: Boolean = true) {
    val laid = measurer.measure(AnnotatedString(s), style)
    drawText(laid, topLeft = Offset(if (centered) x - laid.size.width / 2f else x, baseline - laid.size.height * 0.8f))
}
