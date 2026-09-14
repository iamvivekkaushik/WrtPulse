package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.LedStore
import com.vivekkaushik.wrtpulse.ops.LedMode
import com.vivekkaushik.wrtpulse.ops.LedOps
import com.vivekkaushik.wrtpulse.ops.LedPattern
import com.vivekkaushik.wrtpulse.ops.LedwatchConfig
import com.vivekkaushik.wrtpulse.ops.RouterLed
import com.vivekkaushik.wrtpulse.ops.WatchState
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.HorizontalHairline
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.PullToRefresh
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch

/**
 * System · LEDs. The top card is the connectivity watch — colours per state, chosen from
 * what the router's LEDs can actually make together; below it every LED the kernel lists,
 * each with the modes its own triggers allow.
 */
@Composable
fun LedsScreen(store: LedStore?, latencyMs: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("LEDs", onBack) {
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
                Text("Connect to a router to see its LEDs.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        PullToRefresh(Modifier.fillMaxSize(), onRefresh = { if (!store.applying && !store.loading) store.load() }) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                store.error?.let { Text(it, style = sans(11f, 500, Wrt.Red)) }
                store.notice?.let {
                    Text(it, style = mono(10.5f, 500, Wrt.Accent), modifier = Modifier.clickable { /* dismiss on reload */ })
                }
                when {
                    store.loading && !store.loaded -> Text("Reading /sys/class/leds…", style = sans(11.5f, 500, Wrt.TextDim))
                    store.loaded && store.leds.isEmpty() -> Unit
                    store.loaded -> {
                        WatchCard(store)
                        SectionLabel("LEDS · ${store.leds.size}", tracking = 0.14)
                        LedCard(store, onEdit = { editing = it })
                        GhostButton("Reset all to board defaults") {
                            if (!store.applying) scope.launch { store.resetAll() }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    val sysfs = editing
    val led = sysfs?.let { s -> store?.leds?.firstOrNull { it.sysfs == s } }
    if (store != null && led != null) {
        LedModeDialog(store, led, onDismiss = { editing = null })
    }
}

// ── the connectivity watch ────────────────────────────────────────────────────

@Composable
private fun WatchCard(store: LedStore) {
    val scope = rememberCoroutineScope()
    val palette = store.palette
    val watch = store.watch
    val installed = watch?.installed == true
    // Picks follow the router until the user touches them; a reload after Save shows the
    // router's copy. Once the lens is edited the installed colours may no longer be anything
    // this palette can make ("Custom"), and then the palette's own defaults take over — a
    // watch is only ever installed with colours the chosen LEDs can show.
    val base = store.watchConfig
        ?.takeIf { cfg -> cfg.states.values.all { p -> palette.any { it.id == p.id } } }
        ?: LedOps.defaultWatchConfig(palette)
    var picks by remember(watch, palette) {
        mutableStateOf(base?.states?.mapValues { it.value.id }.orEmpty())
    }
    var interval by remember(watch) { mutableStateOf(base?.intervalS ?: 5) }
    var lensOpen by remember { mutableStateOf(false) }
    val coloured = store.leds.filter { it.multicolor || it.colour != null }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(WrtIcons.Leds, null, Modifier.size(18.dp), tint = Wrt.TextTertiary)
            Column(Modifier.weight(1f)) {
                Text("Connectivity watch", style = sans(13f, 600))
                Text(
                    when {
                        !installed -> "Not installed · colours the LEDs white, yellow or red by internet state"
                        watch?.running == true -> "Running · showing ${stateLabel(watch.current)}" +
                            (if (watch.enabled) "" else " · not at boot")
                        watch?.enabled == true -> "Stopped · starts at boot"
                        else -> "Installed · stopped"
                    },
                    style = sans(10.5f, 400, if (installed && watch?.running != true) Wrt.Amber else Wrt.TextDim),
                )
            }
            if (installed) {
                WToggle(watch?.running == true) {
                    if (!store.applying) scope.launch { store.setWatchEnabled(watch?.running != true) }
                }
            }
        }

        if (watch?.legacy == true) {
            Text(
                "A hand-installed ledwatch service is on this router. Installing the watch from here replaces it.",
                style = sans(10.5f, 500, Wrt.Amber, lineHeight = 15.sp),
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalHairline()

        // The lens: which LEDs sit behind one window and mix into colours.
        Row(
            Modifier.fillMaxWidth().clickable { lensOpen = !lensOpen }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Lens", style = sans(12.5f, 600))
                Text(
                    store.lens.map { s -> LedOps.label(s) }.sorted().joinToString(" · ").ifEmpty { "No LEDs picked" },
                    style = sans(10.5f, 400, Wrt.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(if (lensOpen) WrtIcons.ChevronUp else WrtIcons.ChevronDown, null, Modifier.size(13.dp), tint = Wrt.TextDim)
        }
        if (lensOpen) {
            Text(
                "The LEDs that share one window. Colours below are what they can make together — a red, green and blue LED behind one lens give seven.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (coloured.isEmpty()) store.leds else coloured).forEach { led ->
                    FilterChip(led.label + " · " + (led.colour ?: "no colour"), led.sysfs in store.lens, size = 11f) {
                        store.toggleLens(led.sysfs)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        HorizontalHairline()

        if (palette.size < 2) {
            Text(
                "This lens cannot tell three states apart. Pick more LEDs, or a multicolour one.",
                style = sans(11f, 500, Wrt.Amber), modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            WatchState.values().forEachIndexed { i, st ->
                StateRow(
                    state = st,
                    palette = palette,
                    picked = picks[st],
                    previewing = store.previewing,
                    onPick = { picks = picks + (st to it.id) },
                    onPreview = { scope.launch { store.flash(it) } },
                )
                if (i < WatchState.values().size - 1) HorizontalHairline()
            }
            HorizontalHairline()
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Check every", style = sans(11.5f, 500, Wrt.TextSecondary))
                FlexSpacer()
                listOf(3, 5, 10, 30).forEach { s ->
                    FilterChip("$s s", interval == s, size = 11f, mono = true, padH = 9.dp, padV = 4.dp) { interval = s }
                }
            }
            val chosen = WatchState.values().mapNotNull { st -> palette.firstOrNull { it.id == picks[st] }?.let { st to it } }.toMap()
            val cfg = if (chosen.size == WatchState.values().size) LedwatchConfig(chosen, intervalS = interval) else null
            val working = store.applying
            PrimaryButton(
                when {
                    working -> "Working…"
                    installed -> "Save colours"
                    else -> "Install on router"
                },
                color = if (working || cfg == null) Wrt.BorderCard else Wrt.Accent,
                textColor = if (working || cfg == null) Wrt.TextDim else Wrt.OnAccent,
            ) {
                if (!working && cfg != null) scope.launch { store.installWatch(cfg) }
            }
            if (installed) {
                Spacer(Modifier.height(6.dp))
                GhostButton("Remove the watch", textColor = Wrt.Red, border = Wrt.Red.copy(alpha = 0.4f)) {
                    if (!working) scope.launch { store.removeWatch() }
                }
            }
            Text(
                "Installs a small script and a procd service (START 97, kept across upgrades). It pings 1.1.1.1, 8.8.8.8 and 9.9.9.9 out of the upstream device.",
                style = sans(10f, 400, Wrt.TextFaint, lineHeight = 14.sp),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun stateLabel(key: String?): String = WatchState.values().firstOrNull { it.key == key }?.label?.lowercase() ?: (key ?: "—")

@Composable
private fun StateRow(
    state: WatchState,
    palette: List<LedPattern>,
    picked: String?,
    previewing: Boolean,
    onPick: (LedPattern) -> Unit,
    onPreview: (LedPattern) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.label, style = sans(12.5f, 600))
                Text(state.body, style = sans(10.5f, 400, Wrt.TextDim))
            }
            val chosen = palette.firstOrNull { it.id == picked }
            if (chosen != null) {
                Text(
                    if (previewing) "showing…" else "Preview",
                    style = mono(10f, 600, if (previewing) Wrt.TextDim else Wrt.Accent),
                    modifier = Modifier
                        .border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp))
                        .clickable(enabled = !previewing) { onPreview(chosen) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            palette.forEach { p -> PatternChip(p, p.id == picked) { onPick(p) } }
        }
    }
}

/** A colour swatch with its name; the filled border marks the chosen one. */
@Composable
private fun PatternChip(pattern: LedPattern, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .border(1.dp, if (selected) Wrt.Accent else Wrt.BorderCard, shape)
            .background(if (selected) Wrt.Accent.copy(alpha = 0.12f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(Color(pattern.swatch), CircleShape)
                .border(1.dp, Wrt.BorderInput, CircleShape),
        )
        Text(pattern.label, style = sans(11f, if (selected) 600 else 500, if (selected) Wrt.TextPrimary else Wrt.TextSecondary), maxLines = 1)
    }
}

// ── the LED list ──────────────────────────────────────────────────────────────

@Composable
private fun LedCard(store: LedStore, onEdit: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp),
    ) {
        store.leds.forEachIndexed { i, led ->
            val watched = led.sysfs in store.watchedLeds
            val mode = store.modeOf(led)
            Row(
                Modifier
                    .fillMaxWidth()
                    .let { if (watched) it else it.clickable { onEdit(led.sysfs) } }
                    .padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LedDot(led)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(led.label, style = sans(13f, 600))
                        if (led.multicolor) MonoTag("RGB")
                        if (watched) MonoTag("WATCH", color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f))
                    }
                    Text(
                        led.sysfs + " · " + (if (watched) "driven by the connectivity watch" else LedOps.describe(mode)) +
                            (if (mode is LedMode.BoardDefault && !watched) " · now ${LedOps.liveLabel(led).lowercase()}" else ""),
                        style = mono(10f, 500, Wrt.TextDim),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!watched) Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
            }
            if (i < store.leds.size - 1) HorizontalHairline()
        }
    }
}

/** The LED's colour, lit to its live state; a driver LED with no colour word is grey. */
@Composable
private fun LedDot(led: RouterLed) {
    val colour = Color(LedOps.swatch(led.colour ?: led.channels.firstOrNull()))
    val on = led.lit || led.trigger != "none"
    Box(
        Modifier
            .size(12.dp)
            .background(if (on) colour else colour.copy(alpha = 0.25f), CircleShape)
            .border(1.dp, if (on) colour.copy(alpha = 0.6f) else Wrt.BorderInput, CircleShape),
    )
}

// ── per-LED mode dialog ───────────────────────────────────────────────────────

@Composable
private fun LedModeDialog(store: LedStore, led: RouterLed, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = store.modeOf(led)
    var draft by remember(led.sysfs) { mutableStateOf<LedMode?>(null) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun apply(mode: LedMode) {
        if (working) return
        working = true
        failure = null
        scope.launch {
            val ok = store.setMode(led.sysfs, mode)
            working = false
            if (ok) onDismiss() else failure = store.error
        }
    }

    Dialog(onDismissRequest = { if (!working) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LedDot(led)
                Text(led.label, style = sans(15f, 650), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    led.sysfs,
                    "now ${LedOps.liveLabel(led).lowercase()}",
                    "max ${led.maxBrightness}",
                    store.dtOf(led.sysfs)?.let { dt -> "board: ${dt.trigger?.let(LedOps::triggerLabel) ?: if (dt.defaultOn == true) "on" else "off"}" },
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
            )

            val d = draft
            Spacer(Modifier.height(14.dp))
            when (d) {
                null -> {
                    SectionLabel("MODE", size = 9f, tracking = 0.14)
                    Spacer(Modifier.height(6.dp))
                    LedOps.modesFor(led).forEach { mode ->
                        val isCurrent = mode::class == current::class && (mode !is LedMode.Raw || mode == current)
                        ModeRow(
                            label = LedOps.modeLabel(mode),
                            body = when (mode) {
                                LedMode.BoardDefault -> "Delete this LED's setting; the device tree decides again"
                                is LedMode.Blink -> "Pick on and off times"
                                is LedMode.Netdev -> "Follow a network device's link or traffic"
                                is LedMode.Raw -> mode.trigger
                                else -> null
                            },
                            current = isCurrent,
                        ) {
                            when (mode) {
                                is LedMode.Blink -> draft = if (current is LedMode.Blink) current else mode
                                is LedMode.Netdev -> draft = if (current is LedMode.Netdev) current else LedMode.Netdev(store.netdevs.firstOrNull().orEmpty())
                                else -> apply(mode)
                            }
                        }
                    }
                }
                is LedMode.Blink -> {
                    SectionLabel("BLINK · MS", size = 9f, tracking = 0.14)
                    Spacer(Modifier.height(6.dp))
                    listOf(100 to 100, 200 to 200, 500 to 500, 1000 to 1000, 100 to 900, 2000 to 2000).forEach { (on, off) ->
                        val pick = LedMode.Blink(on, off)
                        ModeRow(
                            label = when {
                                on == off && on <= 200 -> "Fast · $on/$off"
                                on == off && on <= 500 -> "Medium · $on/$off"
                                on == off -> "Slow · $on/$off"
                                else -> "Pulse · $on/$off"
                            },
                            body = null,
                            current = d == pick,
                        ) { draft = pick }
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(if (working) "Working…" else "Blink ${d.onMs}/${d.offMs} ms") { apply(d) }
                }
                is LedMode.Netdev -> {
                    SectionLabel("DEVICE", size = 9f, tracking = 0.14)
                    Spacer(Modifier.height(6.dp))
                    if (store.netdevs.isEmpty()) {
                        Text("No network devices were listed.", style = sans(11f, 500, Wrt.Amber))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            store.netdevs.forEach { dev ->
                                FilterChip(dev, d.dev == dev, size = 11f, mono = true) { draft = d.copy(dev = dev) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel("LIGHT ON", size = 9f, tracking = 0.14)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip("link", d.link, size = 11f, mono = true) { draft = d.copy(link = !d.link) }
                        FilterChip("tx", d.tx, size = 11f, mono = true) { draft = d.copy(tx = !d.tx) }
                        FilterChip("rx", d.rx, size = 11f, mono = true) { draft = d.copy(rx = !d.rx) }
                    }
                    Spacer(Modifier.height(12.dp))
                    val ok = d.dev.isNotBlank() && (d.link || d.tx || d.rx)
                    PrimaryButton(
                        if (working) "Working…" else "Follow ${d.dev.ifEmpty { "—" }}",
                        color = if (ok && !working) Wrt.Accent else Wrt.BorderCard,
                        textColor = if (ok && !working) Wrt.OnAccent else Wrt.TextDim,
                    ) { if (ok) apply(d) }
                }
                else -> Unit
            }

            failure?.let {
                Text(it, style = mono(10.5f, 500, Wrt.Red), modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(10.dp))
            GhostButton(if (d == null) "Close" else "Back") {
                if (!working) { if (d == null) onDismiss() else { draft = null; failure = null } }
            }
        }
    }
}

@Composable
private fun ModeRow(label: String, body: String?, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(if (current) Wrt.Accent else Wrt.DotOff, size = 6.dp)
        Column(Modifier.weight(1f)) {
            Text(label, style = sans(12.5f, if (current) 650 else 500, if (current) Wrt.TextPrimary else Wrt.TextSecondary))
            body?.let { Text(it, style = mono(9.5f, 500, Wrt.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
