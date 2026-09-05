package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.DraftIface
import com.vivekkaushik.wrtpulse.data.InterfaceRow
import com.vivekkaushik.wrtpulse.data.WifiStore
import com.vivekkaushik.wrtpulse.ops.ChannelPlan
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.ScanCell
import com.vivekkaushik.wrtpulse.ops.WifiNetwork
import com.vivekkaushik.wrtpulse.ops.WifiRadio
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.InfoChip
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.SignalBars
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Screen 19 — every wireless interface, flat
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InterfacesScreen(
    store: WifiStore,
    onAdd: () -> Unit,
    onEdit: (InterfaceRow) -> Unit,
    onOpenRadio: (String) -> Unit,
    onShowUci: (String) -> Unit,
) {
    val rows = store.interfaceRows()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("Wireless interfaces", style = sans(16f, 650))
            Text("${rows.size}", style = mono(11.5f, 500, Wrt.Accent))
            FlexSpacer()
            Row(
                Modifier
                    .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(WrtIcons.Plus, null, Modifier.size(13.dp), tint = Wrt.Accent)
                Text("Add", style = sans(11.5f, 600, Wrt.Accent))
            }
        }
        Text(
            if (rows.any { it.deletable || it.deleting }) {
                "Tap to edit · long-press for UCI · swipe a network to delete"
            } else "Tap to edit · long-press for UCI config",
            style = sans(11f, 400, Wrt.TextDim),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (store.radios.isNotEmpty()) {
                SectionLabel("RADIOS", tracking = 0.14, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                store.radios.forEach { radio -> RadioRow(store, radio) { onOpenRadio(radio.section) } }
                SectionLabel(
                    "NETWORKS",
                    tracking = 0.14,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                )
            }
            if (rows.isEmpty()) {
                Text(
                    if (store.loaded) "This router has no wireless interfaces yet."
                    else store.error ?: "Reading wireless config…",
                    style = mono(11f, 500, if (store.error != null) Wrt.Red else Wrt.TextDim),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            rows.forEach { row -> InterfaceListRow(store, row, onEdit, onShowUci) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * A row that can be swiped aside to reveal Delete, per design screen 3e.
 *
 * Offered for every saved interface, on the air or not. The tap stages a `uci delete` rather
 * than running one: the row stays put, marked and undoable, and the review sheet names what
 * a live network costs before anything is applied. Unsaved drafts render without any of
 * this — the editor discards those.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InterfaceListRow(
    store: WifiStore,
    row: InterfaceRow,
    onEdit: (InterfaceRow) -> Unit,
    onShowUci: (String) -> Unit,
) {
    if (!row.deletable && !row.deleting) {
        InterfaceRowBody(store, row, onEdit, onShowUci)
        return
    }
    val undoing = row.deleting
    SwipeToReveal(
        actions = listOf(
            RevealAction(
                label = if (undoing) "Undo" else "Delete",
                icon = if (undoing) WrtIcons.Reboot else WrtIcons.Trash,
                tint = if (undoing) Wrt.Accent else Wrt.Red,
                onAction = {
                    row.section?.let { if (undoing) store.undoDelete(it) else store.stageDelete(it) }
                },
            )
        ),
        resetKey = row.key,
    ) { swipe ->
        InterfaceRowBody(store, row, onEdit, onShowUci, swipe)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InterfaceRowBody(
    store: WifiStore,
    row: InterfaceRow,
    onEdit: (InterfaceRow) -> Unit,
    onShowUci: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The uplink is the one interface the router itself depends on — it earns the accent.
    val border = when {
        row.deleting -> Wrt.Red.copy(alpha = 0.55f)
        !row.radioOn -> Wrt.Amber.copy(alpha = 0.4f)
        row.isUplink -> Wrt.Accent.copy(alpha = 0.4f)
        row.changed -> Wrt.Accent.copy(alpha = 0.35f)
        row.enabled -> Wrt.BorderCard
        else -> Wrt.BorderHair
    }
    // This row sits on top of the swipe actions, so it has to be opaque. The uplink's 4%
    // accent tint and the 72% alpha for an off-air row both let the red Delete panel behind
    // it show straight through — the uplink row rendered as if it were already swiped.
    val dim = if (row.onAir) 1f else 0.72f
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .background(Wrt.BgScreen, RoundedCornerShape(13.dp))
            .background(
                if (row.isUplink && row.radioOn) Wrt.Accent.copy(alpha = 0.04f)
                else if (row.onAir) Wrt.BgCard else Wrt.BgCardDim,
                RoundedCornerShape(13.dp),
            )
            .combinedClickable(
                onClick = { onEdit(row) },
                onLongClick = { row.section?.let(onShowUci) },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Dimming the CONTENT rather than the row keeps the background opaque.
        Column(Modifier.weight(1f).alpha(dim)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.ssid.ifBlank { "(no name)" },
                    style = sans(13.5f, 650, if (row.onAir) Wrt.TextPrimary else Wrt.TextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.isClient) {
                    Badge("CLIENT", Wrt.Blue)
                    if (row.isUplink) Badge("UPLINK", Wrt.Amber)
                } else {
                    Badge("AP", Wrt.Accent)
                }
                if (row.bands.isNotEmpty()) MonoTag(row.bands, size = 9f)
                if (row.isNew) Badge("NEW", Wrt.Green)
                if (row.deleting) Badge("WILL DELETE", Wrt.Red)
                if (!row.radioOn) Badge("RADIO OFF", Wrt.Amber)
            }
            Text(
                row.detail,
                style = mono(10.5f, 500, if (row.radioOn) Wrt.TextDim else Wrt.Amber),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(Modifier.alpha(dim)) {
            if (row.section != null) {
                val net = store.networks.first { it.section == row.section }
                val savedDisabled = if (net.disabled) "1" else "0"
                WToggle(row.enabled) {
                    store.stage(net.section, "disabled", savedDisabled, if (row.enabled) "1" else "0")
                }
            } else {
                WToggle(true)
            }
        }
        Icon(WrtIcons.ChevronRight, "edit", Modifier.size(13.dp).alpha(dim), tint = Wrt.TextDim)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(text, style = mono(8.5f, 600, color, letterSpacing = 0.7.sp))
    }
}

// ---------------------------------------------------------------------------
// Screen 15 — add interface, mode sheet
// ---------------------------------------------------------------------------

@Composable
fun AddModeSheetContent(
    radios: List<WifiRadio>,
    /** Radios currently switched off — worth knowing before you pick one. */
    offRadios: Set<String> = emptySet(),
    onCancel: () -> Unit,
    onContinue: (radio: String, mode: String) -> Unit,
) {
    var radio by remember { mutableStateOf(radios.firstOrNull()?.section.orEmpty()) }
    var client by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Text("Add wireless interface", style = sans(16f, 650), modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            radios.forEach { r ->
                val selected = r.section == radio
                Box(
                    Modifier
                        .border(
                            1.dp,
                            if (selected) Color.Transparent else Wrt.BorderCard,
                            RoundedCornerShape(14.dp),
                        )
                        .background(
                            if (selected) Wrt.Accent else Color.Transparent,
                            RoundedCornerShape(14.dp),
                        )
                        .clickable { radio = r.section }
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) {
                    Text(
                        "${r.section} · ${r.band}" + if (r.section in offRadios) " · off" else "",
                        style = mono(10.5f, 600, if (selected) Wrt.OnAccent else Wrt.TextSecondary),
                    )
                }
            }
        }
        ModeOption(
            title = "Access point",
            body = "Broadcast a new network from this router.",
            selected = !client,
            icon = WrtIcons.RadioWaves,
        ) { client = false }
        Spacer(Modifier.height(10.dp))
        ModeOption(
            title = "Client (join a network)",
            body = "Connect to another Wi-Fi — use it as internet uplink (wwan) or extend it.",
            selected = client,
            icon = WrtIcons.ShareUp,
        ) { client = true }
        Text(
            "$ uci set wireless.<name>=wifi-iface",
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        com.vivekkaushik.wrtpulse.ui.PrimaryButton("Continue") {
            onContinue(radio, if (client) "sta" else "ap")
        }
        Spacer(Modifier.height(6.dp))
        com.vivekkaushik.wrtpulse.ui.GhostButton("Cancel", onClick = onCancel)
    }
}

@Composable
private fun ModeOption(
    title: String,
    body: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) Wrt.Accent.copy(alpha = 0.55f) else Wrt.BorderCard,
                RoundedCornerShape(14.dp),
            )
            .background(
                if (selected) Wrt.Accent.copy(alpha = 0.06f) else Wrt.BgCard,
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(
            icon,
            null,
            Modifier.padding(top = 2.dp).size(22.dp),
            tint = if (selected) Wrt.Accent else Wrt.TextTertiary,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(14f, 650))
            Text(body, style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp), modifier = Modifier.padding(top = 4.dp))
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .border(1.5.dp, if (selected) Wrt.Accent else Wrt.DotOff, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).background(Wrt.Accent, CircleShape))
        }
    }
}

// ---------------------------------------------------------------------------
// Screen 16 — client: survey nearby networks
// ---------------------------------------------------------------------------

@Composable
fun ClientScanScreen(
    store: WifiStore,
    radio: WifiRadio,
    onBack: () -> Unit,
    onPick: (ScanCell) -> Unit,
    onHidden: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cells = store.scans[radio.section]
    val found = cells.orEmpty().filter { it.named }.distinctBy { it.ssid }.sortedByDescending { it.signalDbm }
    androidx.compose.runtime.LaunchedEffect(radio.section) {
        if (cells == null && !store.scanning) store.scan(radio.section)
    }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Join a network", onBack) {
            MonoTag("${radio.section} · ${radio.band}", size = 10.5f)
        }
        if (store.scanning) {
            ScanPulse(radio.band)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(if (store.scanning) "SCANNING" else "FOUND ${found.size}", tracking = 0.14)
            FlexSpacer()
            Text(
                "Rescan",
                style = sans(11.5f, 600, if (store.scanning) Wrt.TextDim else Wrt.Accent),
                modifier = Modifier
                    .clickable(enabled = !store.scanning) { scope.launch { store.scan(radio.section) } }
                    .padding(4.dp),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp),
        ) {
            store.error?.let {
                Text(it, style = sans(11.5f, 500, Wrt.Red), modifier = Modifier.padding(14.dp))
            }
            if (!store.scanning && found.isEmpty() && cells != null) {
                Text(
                    "Nothing heard on this radio.",
                    style = sans(12f, 400, Wrt.TextDim),
                    modifier = Modifier.padding(14.dp),
                )
            }
            found.forEachIndexed { index, cell ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cell) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    SignalBars(cell.bars, if (cell.bars >= 2) Wrt.Green else Wrt.Amber)
                    Column(Modifier.weight(1f)) {
                        Text(cell.ssid, style = sans(13.5f, 600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                "ch ${cell.channel}",
                                "${cell.signalDbm} dBm",
                                cell.bssid.takeIf { it.isNotEmpty() },
                            ).joinToString(" · "),
                            style = mono(10.5f, 500, Wrt.TextDim),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    MonoTag(
                        cell.encryption.ifEmpty { "—" },
                        color = if (cell.encryption == "OPEN") Wrt.Amber else Wrt.TextTertiary,
                        border = if (cell.encryption == "OPEN") Wrt.Amber.copy(alpha = 0.45f) else Wrt.BorderInput,
                    )
                }
                if (index != found.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderRow))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .height(44.dp)
                .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp))
                .clickable(onClick = onHidden),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(WrtIcons.Eye, null, Modifier.size(14.dp), tint = Wrt.TextSecondary)
            Spacer(Modifier.size(8.dp))
            Text("Join a hidden network", style = sans(12.5f, 600, Wrt.TextSecondary))
        }
    }
}

/** The scanner: rings expanding out of the radio glyph while iwinfo is running. */
@Composable
private fun ScanPulse(band: String) {
    val transition = rememberInfiniteTransition(label = "scan")
    Box(
        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                listOf(0, 800, 1600).forEach { delayMs ->
                    val p by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(2400, delayMillis = delayMs, easing = LinearEasing),
                            RepeatMode.Restart,
                        ),
                        label = "ring$delayMs",
                    )
                    Box(
                        Modifier
                            .size(130.dp)
                            .scale(0.3f + p * 0.7f)
                            .alpha((1f - p) * 0.75f)
                            .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), CircleShape)
                    )
                }
                Box(
                    Modifier
                        .size(52.dp)
                        .background(Wrt.BgCard, CircleShape)
                        .border(1.dp, Wrt.BorderIcon, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(WrtIcons.RadioWaves, null, Modifier.size(24.dp), tint = Wrt.Accent)
                }
            }
            Text(
                "scanning $band",
                style = mono(11f, 500, Wrt.TextTertiary),
                modifier = Modifier.padding(top = 14.dp),
            )
            Text("iwinfo scan", style = sans(11f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Shared form furniture
// ---------------------------------------------------------------------------

@Composable
fun FormTopBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Wrt.BgBar)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            WrtIcons.ChevronLeft,
            "back",
            Modifier.size(18.dp).clickable(onClick = onBack),
            tint = Wrt.TextPrimary,
        )
        Text(title, style = sans(15f, 650), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun FieldLabel(text: String) = SectionLabel(text, size = 10f, tracking = 0.12)

@Composable
fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    /**
     * Shows bullets instead of the characters. The field still holds — and still takes —
     * the real value: masking by rewriting it into bullets makes every keystroke while
     * hidden either a no-op or corruption, which is not what an eye icon promises.
     */
    masked: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(44.dp)
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = mono(13f, 500),
                singleLine = true,
                cursorBrush = SolidColor(Wrt.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
                visualTransformation =
                    if (masked) PasswordVisualTransformation('\u2022') else VisualTransformation.None,
                // An empty field measures to its text without this, leaving most of the box
                // it sits in unable to take a tap.
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing()
    }
}

@Composable
fun FormSelect(value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(42.dp)
                .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = mono(12.5f, 500))
            FlexSpacer()
            Icon(WrtIcons.ChevronDown, null, Modifier.size(12.dp), tint = Wrt.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(option, style = mono(12.5f, 500, if (option == value) Wrt.Accent else Wrt.TextPrimary))
                    },
                    onClick = { open = false; onPick(option) },
                )
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, divider: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = sans(13f, 600))
                body?.let {
                    Text(it, style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp), modifier = Modifier.padding(top = 2.dp))
                }
            }
            WToggle(checked, onToggle)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))
    }
}

@Composable
fun ToggleCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp)
    ) { content() }
}

/** The bottom bar the add/edit forms share: pending count, cancel, review. */
@Composable
fun FormActionBar(
    pendingCount: Int,
    countLabel: String,
    saveLabel: String,
    saveEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.Accent.copy(alpha = 0.35f)))
        Row(
            Modifier.fillMaxWidth().background(Wrt.BgPendingBar).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (pendingCount > 0) {
                Text("±$pendingCount", style = mono(11f, 600, Wrt.Accent))
                Text(countLabel, style = sans(12.5f, 600))
            }
            FlexSpacer()
            Text(
                "Cancel",
                style = sans(12f, 600, Wrt.TextSecondary),
                modifier = Modifier.clickable(onClick = onCancel).padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Box(
                Modifier
                    .background(if (saveEnabled) Wrt.Accent else Wrt.BgDeep, RoundedCornerShape(9.dp))
                    .border(
                        1.dp,
                        if (saveEnabled) Color.Transparent else Wrt.BorderInput,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable(enabled = saveEnabled, onClick = onSave)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(saveLabel, style = sans(12.5f, 650, if (saveEnabled) Wrt.OnAccent else Wrt.TextDim))
            }
        }
    }
}

val SECURITY_CHOICES = listOf(
    "WPA2-PSK" to "psk2",
    "WPA2/WPA3" to "sae-mixed",
    "WPA3-SAE" to "sae",
    "Open" to "none",
)

fun securityLabelOf(value: String) =
    SECURITY_CHOICES.firstOrNull { it.second == value }?.first ?: Parsers.encryptionLabel(value)

/** Readable and long enough for WPA — four words beats a wall of base64 nobody can retype. */
fun generatePassphrase(): String {
    val words = listOf(
        "amber", "basalt", "cedar", "delta", "ember", "fjord", "granite", "harbor",
        "indigo", "juniper", "kestrel", "lumen", "meadow", "nimbus", "onyx", "pewter",
        "quarry", "rowan", "slate", "thistle", "umber", "verdant", "willow", "zephyr",
    )
    val random = java.security.SecureRandom()
    return (1..4).joinToString("-") { words[random.nextInt(words.size)] }
}

// ---------------------------------------------------------------------------
// Screen 18 — access point, new or existing
// ---------------------------------------------------------------------------

@Composable
fun ApFormScreen(
    store: WifiStore,
    radios: List<WifiRadio>,
    /** The section being edited, or null when this is a new interface. */
    existing: WifiNetwork?,
    /** The draft being edited, or null. */
    draft: DraftIface?,
    startRadio: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val editing = existing != null || draft != null
    var ssid by remember { mutableStateOf(existing?.ssid ?: draft?.ssid ?: "") }
    val devices = remember {
        mutableStateListOf<String>().apply {
            addAll(
                when {
                    existing != null -> listOf(existing.device)
                    draft != null -> draft.devices
                    else -> listOf(startRadio)
                }
            )
        }
    }
    var security by remember { mutableStateOf(existing?.encryption ?: draft?.encryption ?: "psk2") }
    var password by remember { mutableStateOf(existing?.key ?: draft?.key ?: "") }
    var network by remember { mutableStateOf(existing?.network?.ifEmpty { "lan" } ?: draft?.network ?: "lan") }
    var hidden by remember { mutableStateOf(existing?.hidden ?: draft?.hidden ?: false) }
    var isolate by remember { mutableStateOf(existing?.isolate ?: draft?.isolate ?: false) }
    var reveal by remember { mutableStateOf(false) }
    // Creating a network on a dead radio is almost never what anyone means, so the fix is
    // pre-selected — but it is staged like everything else and shows up in the diff.
    var enableRadios by remember { mutableStateOf(existing == null) }

    // Channel and width live on the radio, not the interface, so they are only offered when
    // exactly one radio is in play — there is no single answer for two.
    val soleRadio = devices.singleOrNull()?.let { section -> radios.firstOrNull { it.section == section } }
    var channel by remember(soleRadio?.section) {
        mutableStateOf(soleRadio?.let { store.value(it.section, "channel", it.channel) } ?: "auto")
    }
    var width by remember(soleRadio?.section) { mutableStateOf(soleRadio?.htmode.orEmpty()) }

    val open = security == "none"
    val problem = when {
        ssid.isBlank() -> "Give the network a name."
        ssid.toByteArray().size > 32 -> "An SSID can be at most 32 bytes."
        devices.isEmpty() -> "Pick at least one radio to broadcast on."
        !open && password.length !in 8..63 -> "A WPA password must be 8–63 characters."
        else -> null
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar(if (editing) "Edit access point" else "New access point", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Column {
                FieldLabel("SSID")
                FormTextField(ssid, { ssid = it })
            }
            Column {
                FieldLabel("BROADCAST ON")
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    radios.forEach { r ->
                        val on = r.section in devices
                        Row(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .border(
                                    1.dp,
                                    if (on) Wrt.Accent.copy(alpha = 0.5f) else Wrt.BorderInput,
                                    RoundedCornerShape(10.dp),
                                )
                                .background(
                                    if (on) Wrt.Accent.copy(alpha = 0.07f) else Wrt.BgDeep,
                                    RoundedCornerShape(10.dp),
                                )
                                // An existing section belongs to one radio; moving it is a
                                // different operation from editing it.
                                .clickable(enabled = existing == null) {
                                    if (on) devices.remove(r.section) else devices.add(r.section)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (on) {
                                Icon(WrtIcons.Check, null, Modifier.size(13.dp), tint = Wrt.Accent)
                                Spacer(Modifier.size(7.dp))
                            }
                            Text(
                                "${r.section} · ${r.band}",
                                style = mono(11f, 600, if (on) Wrt.Accent else Wrt.TextSecondary),
                            )
                        }
                    }
                }
            }
            if (soleRadio != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("CHANNEL")
                        FormSelect(channel, listOf("auto") + ChannelPlan.candidates(soleRadio.band).map { "$it" }) {
                            channel = it
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("WIDTH")
                        FormSelect(
                            ChannelPlan.widthLabel(width),
                            ChannelPlan.widths(soleRadio.htmode, soleRadio.band).map { ChannelPlan.widthLabel(it) },
                        ) { picked ->
                            width = ChannelPlan.widths(soleRadio.htmode, soleRadio.band)
                                .firstOrNull { ChannelPlan.widthLabel(it) == picked } ?: width
                        }
                    }
                }
                ChannelAdviceStrip(
                    store = store,
                    radio = soleRadio,
                    onUseBest = { channel = "$it" },
                    onScan = { scope.launch { store.scan(soleRadio.section) } },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("SECURITY")
                    FormSelect(securityLabelOf(security), SECURITY_CHOICES.map { it.first }) { label ->
                        security = SECURITY_CHOICES.first { it.first == label }.second
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("NETWORK")
                    FormSelect(network, store.interfaces.sorted().ifEmpty { listOf("lan") }) { network = it }
                }
            }
            if (!open) {
                Column {
                    FieldLabel("PASSWORD")
                    FormTextField(
                        value = password,
                        onChange = { password = it },
                        password = true,
                        masked = !reveal,
                    ) {
                        Icon(
                            WrtIcons.Eye,
                            if (reveal) "hide" else "show",
                            Modifier.size(17.dp).clickable { reveal = !reveal },
                            tint = if (reveal) Wrt.Accent else Wrt.TextTertiary,
                        )
                        Icon(
                            WrtIcons.ShareUp,
                            "generate",
                            Modifier.size(17.dp).clickable { password = generatePassphrase(); reveal = true },
                            tint = Wrt.TextTertiary,
                        )
                    }
                    Text(
                        when {
                            password.isEmpty() -> "Tap the arrow to generate one."
                            password.length < 8 -> "Too short — WPA needs 8 characters."
                            password.length >= 16 -> "Strong — ${password.length} chars."
                            else -> "${password.length} chars."
                        },
                        style = sans(10.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    if (!reveal && password.isNotEmpty()) {
                        Text(
                            "Reveal to edit.",
                            style = sans(10.5f, 400, Wrt.TextDim),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            RadioOffNotice(
                radios = devices.filter { !store.radioEnabled(it) },
                enable = enableRadios,
                editing = existing != null,
            ) { enableRadios = !enableRadios }
            ToggleCard {
                ToggleRow("Hidden SSID", null, hidden, divider = true) { hidden = !hidden }
                ToggleRow(
                    "Client isolation",
                    "Devices on this SSID can't see each other",
                    isolate,
                    divider = false,
                ) { isolate = !isolate }
            }
            Text(
                if (editing) "$ uci set wireless.${existing?.section ?: "<new>"}.…"
                else "$ uci set wireless.<name>=wifi-iface · mode='ap'",
                style = mono(10f, 500, Wrt.TextDim),
            )
            problem?.let {
                Text(it, style = sans(11.5f, 500, Wrt.Amber), modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
        FormActionBar(
            pendingCount = store.pendingCount,
            countLabel = if (store.pendingCount == 1) "1 pending change" else "${store.pendingCount} pending changes",
            saveLabel = if (editing) "Save" else "Add",
            saveEnabled = problem == null,
            onCancel = onBack,
            onSave = {
                if (enableRadios) {
                    devices.filter { !store.radioEnabled(it) }
                        .forEach { store.stage(it, "disabled", "1", "0") }
                }
                if (soleRadio != null) {
                    store.stage(soleRadio.section, "channel", soleRadio.channel, channel)
                    if (width.isNotEmpty()) store.stage(soleRadio.section, "htmode", soleRadio.htmode, width)
                }
                when {
                    existing != null -> {
                        store.stage(existing.section, "ssid", existing.ssid, ssid)
                        store.stage(existing.section, "encryption", existing.encryption, security)
                        store.stage(existing.section, "key", existing.key, if (open) "" else password)
                        store.stage(existing.section, "network", existing.network, network)
                        store.stage(existing.section, "hidden", if (existing.hidden) "1" else "0", if (hidden) "1" else "0")
                        store.stage(existing.section, "isolate", if (existing.isolate) "1" else "0", if (isolate) "1" else "0")
                    }
                    else -> {
                        draft?.let { store.removeDraft(it.id) }
                        store.addDraft(
                            devices = devices.toList(),
                            mode = "ap",
                            ssid = ssid,
                            encryption = security,
                            key = password,
                            hidden = hidden,
                            isolate = isolate,
                            network = network,
                        )
                    }
                }
                onDone()
            },
        )
    }
}

/**
 * A radio that is switched off takes every network on it down with it — hostapd never starts,
 * no netdev appears, and the SSID simply is not there. Creating one without saying so is how
 * you end up with a network that looks configured and cannot be found.
 */
@Composable
fun RadioOffNotice(radios: List<String>, enable: Boolean, editing: Boolean, onToggle: () -> Unit) {
    if (radios.isEmpty()) return
    val names = radios.joinToString(" and ")
    val verb = if (radios.size == 1) "is" else "are"
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(15.dp), tint = Wrt.Amber)
            Text(
                "$names $verb switched off. A network on ${if (radios.size == 1) "it" else "them"} " +
                    "is saved but never broadcasts — this is what LuCI calls \"Wireless is disabled\".",
                style = sans(11.5f, 400, Wrt.AmberText, lineHeight = 17.sp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Turn $names on", style = sans(13f, 600))
                Text(
                    if (editing) "Adds it to this change" else "Included in this change",
                    style = sans(10.5f, 400, Wrt.TextDim),
                )
            }
            WToggle(enable, onToggle)
        }
    }
}

/** "ch 11 clearest — 2 neighbors, no overlap", straight off the last survey. */
@Composable
private fun ChannelAdviceStrip(
    store: WifiStore,
    radio: WifiRadio,
    onUseBest: (Int) -> Unit,
    onScan: () -> Unit,
) {
    val cells = store.scans[radio.section]
    val advice = cells?.let { ChannelPlan.advise(radio.band, it) }
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
            .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(if (store.scanning) Wrt.Amber else Wrt.Accent, 6.dp, pulse = store.scanning)
        Text(
            when {
                store.scanning -> "Surveying ${radio.band}…"
                advice != null -> advice.headline
                cells != null -> "Nothing heard — any channel is clear."
                else -> "Survey ${radio.band} to find the clearest channel."
            },
            style = sans(11.5f, 400, Wrt.TextPrimary.copy(alpha = 0.85f), lineHeight = 16.sp),
            modifier = Modifier.weight(1f),
        )
        if (advice != null) {
            Box(
                Modifier
                    .background(Wrt.Accent, RoundedCornerShape(7.dp))
                    .clickable { onUseBest(advice.channel) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("Use best", style = sans(11f, 650, Wrt.OnAccent))
            }
        }
        Text(
            if (cells == null) "Scan" else "Rescan",
            style = sans(11f, 600, if (store.scanning) Wrt.TextDim else Wrt.Accent),
            modifier = Modifier.clickable(enabled = !store.scanning, onClick = onScan),
        )
    }
}

// ---------------------------------------------------------------------------
// Screen 17 — client: join config
// ---------------------------------------------------------------------------

@Composable
fun ClientJoinScreen(
    store: WifiStore,
    radio: WifiRadio,
    /** The network picked from the scan, when this is a new uplink. */
    picked: ScanCell?,
    existing: WifiNetwork?,
    draft: DraftIface?,
    onBack: () -> Unit,
    onChangeNetwork: () -> Unit,
    onDone: () -> Unit,
) {
    val editing = existing != null || draft != null
    var ssid by remember(picked?.ssid) {
        mutableStateOf(existing?.ssid ?: draft?.ssid ?: picked?.ssid.orEmpty())
    }
    var security by remember(picked?.ssid) {
        mutableStateOf(
            existing?.encryption ?: draft?.encryption ?: when (picked?.encryption) {
                "OPEN" -> "none"
                "WPA3" -> "sae"
                else -> "psk2"
            }
        )
    }
    var password by remember { mutableStateOf(existing?.key ?: draft?.key ?: "") }
    // Defaulting to a bare "wwan" would rewrite the uplink interface a router that already
    // has one is reaching the internet through.
    var ifaceName by remember {
        mutableStateOf(existing?.network?.ifEmpty { "wwan" } ?: draft?.network ?: store.freeUplinkName())
    }
    val zoneNames = store.zones.map { it.name }.filter { it.isNotEmpty() }.distinct()
    // For an existing uplink this reports where it actually sits — claiming "wan" for a
    // network no zone covers would be a comfortable lie about the router's firewall.
    var zone by remember {
        mutableStateOf(
            existing?.let { store.zoneFor(it.network) }
                ?: draft?.zone?.ifEmpty { "wan" } ?: "wan"
        )
    }
    var uplink by remember { mutableStateOf(draft?.zone?.isNotEmpty() ?: true) }
    var repeater by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var enableRadio by remember { mutableStateOf(existing == null) }

    val open = security == "none"
    val problem = when {
        ssid.isBlank() -> "Pick a network to join."
        !open && password.length !in 8..63 -> "A WPA password must be 8–63 characters."
        ifaceName.isBlank() -> "The uplink needs an interface name."
        else -> null
    }
    val previewOps = remember(ssid, security, password, ifaceName, uplink, zone) {
        buildList {
            add("+ wireless.$ifaceName=wifi-iface")
            add("+ wireless.$ifaceName.mode='sta'")
            add("+ wireless.$ifaceName.ssid='$ssid'")
            add("+ wireless.$ifaceName.encryption='$security'")
            if (!open) add("+ wireless.$ifaceName.key='••••'")
            add("+ network.$ifaceName.proto='dhcp'")
            if (uplink) add("+ firewall.@zone[$zone].network += '$ifaceName'")
        }
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar(if (editing) "Edit ${ssid.ifBlank { "client" }}" else "Join ${ssid.ifBlank { "a network" }}", onBack) {
            MonoTag("${radio.section} · ${radio.band}", size = 10.5f)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                picked?.let { SignalBars(it.bars, if (it.bars >= 2) Wrt.Green else Wrt.Amber) }
                Column(Modifier.weight(1f)) {
                    Text(ssid.ifBlank { "No network chosen" }, style = sans(14f, 650))
                    Text(
                        picked?.let { "ch ${it.channel} · ${it.signalDbm} dBm · ${securityLabelOf(security)}" }
                            ?: securityLabelOf(security),
                        style = mono(10.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (existing == null) {
                    Text(
                        "Change",
                        style = sans(11.5f, 600, Wrt.Accent),
                        modifier = Modifier.clickable(onClick = onChangeNetwork),
                    )
                }
            }
            if (!open) {
                Column {
                    FieldLabel("PASSWORD")
                    FormTextField(
                        value = password,
                        onChange = { password = it },
                        password = true,
                        masked = !reveal,
                    ) {
                        Icon(
                            WrtIcons.Eye,
                            if (reveal) "hide" else "show",
                            Modifier.size(17.dp).clickable { reveal = !reveal },
                            tint = if (reveal) Wrt.Accent else Wrt.TextTertiary,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("INTERFACE NAME")
                    if (existing == null) {
                        FormTextField(ifaceName, { ifaceName = it.filter { c -> c.isLetterOrDigit() || c == '_' } })
                    } else {
                        ReadOnlyBox(ifaceName)
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("FIREWALL ZONE")
                    if (existing == null) {
                        FormSelect(zone, zoneNames.ifEmpty { listOf("wan") }) { zone = it }
                    } else {
                        ReadOnlyBox(zone.ifEmpty { "none" })
                    }
                }
            }
            if (existing == null && store.networkExists(ifaceName)) {
                Text(
                    "$ifaceName already exists — applying this rewrites that interface.",
                    style = sans(11.5f, 500, Wrt.Amber),
                )
            }
            if (existing != null) {
                Text(
                    "The interface and its zone are set up already — changing where an active " +
                        "uplink is bridged is a move, not an edit, so it isn't offered here.",
                    style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
                )
            }
            if (existing == null) {
                ToggleCard {
                    ToggleRow(
                        "Use as internet uplink",
                        "Route WAN traffic through this network",
                        uplink,
                        divider = true,
                    ) { uplink = !uplink }
                    ToggleRow(
                        "Rebroadcast as repeater",
                        "Also serve this SSID from ${radio.section}",
                        repeater,
                        divider = false,
                    ) { repeater = !repeater }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                        .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                ) {
                    Text("# this will run", style = mono(10.5f, 500, Wrt.TextDim, lineHeight = 18.sp))
                    previewOps.take(3).forEach {
                        Text(it, style = mono(10.5f, 500, Wrt.Green, lineHeight = 18.sp))
                    }
                    if (previewOps.size > 3) {
                        Text(
                            "… ${previewOps.size - 3} more · shown in full when you review",
                            style = mono(10.5f, 500, Wrt.TextDim, lineHeight = 18.sp),
                        )
                    }
                }
                // The design promised an automatic fallback here. OpenWrt has no such thing,
                // so this says what actually happens instead.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(15.dp), tint = Wrt.Amber)
                    Text(
                        "${radio.section} follows this network's channel, so any AP on it moves too. " +
                            "If ${ssid.ifBlank { "it" }} can't be reached the station just stays down — " +
                            "the router won't undo this on its own.",
                        style = sans(11.5f, 400, Wrt.AmberText, lineHeight = 17.sp),
                    )
                }
            }
            RadioOffNotice(
                radios = listOf(radio.section).filter { !store.radioEnabled(it) },
                enable = enableRadio,
                editing = existing != null,
            ) { enableRadio = !enableRadio }
            problem?.let { Text(it, style = sans(11.5f, 500, Wrt.Amber)) }
            Spacer(Modifier.height(4.dp))
        }
        FormActionBar(
            pendingCount = store.pendingCount,
            countLabel = if (store.pendingCount == 1) "1 pending change" else "${store.pendingCount} pending changes",
            saveLabel = if (editing) "Save" else "Add",
            saveEnabled = problem == null,
            onCancel = onBack,
            onSave = {
                if (enableRadio && !store.radioEnabled(radio.section)) {
                    store.stage(radio.section, "disabled", "1", "0")
                }
                if (existing != null) {
                    store.stage(existing.section, "ssid", existing.ssid, ssid)
                    store.stage(existing.section, "encryption", existing.encryption, security)
                    store.stage(existing.section, "key", existing.key, if (open) "" else password)
                } else {
                    draft?.let { store.removeDraft(it.id) }
                    store.addDraft(
                        devices = listOf(radio.section),
                        mode = "sta",
                        ssid = ssid,
                        encryption = security,
                        key = password,
                        network = ifaceName,
                        zone = if (uplink) zone else "",   // opting out is explicit
                    )
                    if (repeater) {
                        store.addDraft(
                            devices = listOf(radio.section),
                            mode = "ap",
                            ssid = ssid,
                            encryption = security,
                            key = password,
                            network = "lan",
                        )
                    }
                }
                onDone()
            },
        )
    }
}

@Composable
private fun ReadOnlyBox(value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(42.dp)
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(value, style = mono(12.5f, 500, Wrt.TextSecondary))
    }
}

/**
 * What a long-press reveals: the section exactly as uci has it. Passphrases are hidden until
 * asked for — everywhere else in the app a key sits behind an eye, and a config dump is a
 * worse place than most to print one unprompted.
 */
@Composable
fun UciSheetContent(section: String, text: String?, onDismiss: () -> Unit) {
    var reveal by remember(section) { mutableStateOf(false) }
    val hasSecret = text.orEmpty().lineSequence().any { it.isSecret() }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("UCI config", style = sans(16f, 650))
            FlexSpacer()
            Text("wireless.$section", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp)
        ) {
            if (text == null) {
                Text("reading…", style = mono(11f, 500, Wrt.TextDim))
            } else {
                text.trim().lines().forEach { line ->
                    Text(
                        if (reveal) line else line.masked(),
                        style = mono(11f, 500, Wrt.TextPrimary, lineHeight = 19.sp),
                    )
                }
            }
        }
        if (hasSecret) {
            Row(
                Modifier.padding(top = 10.dp).clickable { reveal = !reveal },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    WrtIcons.Eye,
                    null,
                    Modifier.size(15.dp),
                    tint = if (reveal) Wrt.Accent else Wrt.TextTertiary,
                )
                Text(
                    if (reveal) "Hide passphrase" else "Show passphrase",
                    style = sans(12f, 600, if (reveal) Wrt.Accent else Wrt.TextSecondary),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        com.vivekkaushik.wrtpulse.ui.GhostButton("Close", onClick = onDismiss)
    }
}

private fun String.isSecret() = Regex("\\.(key|password|passphrase)=").containsMatchIn(this)

private fun String.masked(): String {
    if (!isSecret()) return this
    val quote = indexOf('\'')
    return if (quote < 0) this else substring(0, quote + 1) + "••••••••" + "'"
}

/**
 * The Network tab. Everything wireless moved to its own page, so this is a list of what the
 * tab covers rather than a screen in its own right.
 */
@Composable
fun NetworkHomeScreen(
    ticker: com.vivekkaushik.wrtpulse.data.LiveTicker,
    store: WifiStore?,
    lan: com.vivekkaushik.wrtpulse.data.LanStore?,
    wan: com.vivekkaushik.wrtpulse.data.WanStore?,
    live: com.vivekkaushik.wrtpulse.data.Telemetry?,
    liveLatencyMs: Int?,
    routerName: String,
    onRouterTap: () -> Unit,
    onOpenLan: () -> Unit,
    onOpenWan: () -> Unit,
    onOpenWireless: () -> Unit,
    onOpenFirewall: () -> Unit = {},
    firewall: com.vivekkaushik.wrtpulse.data.FirewallStore? = null,
) {
    // The LAN card's chips are read state, so the tab's landing page is what pays for the
    // round trip — by the time the LAN screen opens, its data is already there.
    // Kept live, not read once: the WAN chip here is the first thing that lies when an
    // uplink drops, and it used to keep lying until the app was restarted.
    com.vivekkaushik.wrtpulse.ui.LiveRefresh(wan, WAN_REFRESH_MS)
    com.vivekkaushik.wrtpulse.ui.LiveRefresh(lan, LAN_REFRESH_MS)
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        com.vivekkaushik.wrtpulse.ui.ConnectionTopBar(
            routerName = routerName,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
            onRouterTap = onRouterTap,
        )
        Column(
            Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenLan)
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .border(1.dp, Wrt.BorderIcon, RoundedCornerShape(10.dp))
                            .background(Wrt.BgDeep, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(WrtIcons.Lan, null, Modifier.size(20.dp), tint = Wrt.Accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("LAN & local network", style = sans(14.5f, 650))
                        Text(
                            "Subnet · DHCP · leases · VLANs",
                            style = sans(11f, 400, Wrt.TextDim),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (lan != null && lan.pendingCount > 0) StatusDot(Wrt.Accent, 6.dp)
                    Icon(WrtIcons.ChevronRight, null, Modifier.size(14.dp), tint = Wrt.TextDim)
                }
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val subnet = lan?.let { store ->
                        store.net?.ipaddr?.takeIf { it.isNotEmpty() }?.let { "$it/${store.prefix}" }
                    }
                    InfoChip(subnet ?: "reading…")
                    if (lan != null && lan.loaded) {
                        val healthy = lan.dhcpOn && lan.dnsmasqRunning
                        val tone = if (healthy) Wrt.Green else Wrt.Amber
                        InfoChip(
                            text = when {
                                !lan.dhcpOn -> "DHCP off"
                                !lan.dnsmasqRunning -> "dnsmasq stopped"
                                else -> "DHCP active"
                            },
                            color = tone,
                            border = tone.copy(alpha = 0.4f),
                            dot = tone,
                        )
                        InfoChip("${lan.leases.size} leases")
                    }
                }
            }
            val wanRows = wan?.wanRows().orEmpty()
            val upstream = wanRows.firstOrNull { it.up } ?: wanRows.firstOrNull()
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenWan)
                    .padding(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .border(1.dp, Wrt.BorderIcon, RoundedCornerShape(10.dp))
                            .background(Wrt.BgDeep, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(WrtIcons.Network, null, Modifier.size(20.dp), tint = Wrt.Accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Internet & WAN gateways", style = sans(14.5f, 650))
                        Text(
                            wanSummary(wan, wanRows.size),
                            style = sans(11f, 400, Wrt.TextDim),
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (wan != null && wan.pendingCount > 0) StatusDot(Wrt.Accent, 6.dp)
                    Icon(WrtIcons.ChevronRight, null, Modifier.size(14.dp), tint = Wrt.TextDim)
                }
                if (upstream != null) {
                    Row(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val tone = if (upstream.up) Wrt.Green else Wrt.Amber
                        InfoChip(
                            text = if (upstream.up) "Connected" else "Down",
                            color = tone,
                            border = tone.copy(alpha = 0.4f),
                            dot = tone,
                        )
                        upstream.address.takeIf { it.isNotEmpty() }?.let { InfoChip(it) }
                        upstream.v6Prefix.takeIf { it.isNotEmpty() }?.let {
                            InfoChip("PD ${it.substringAfter("::")}".ifBlank { "PD" })
                        }
                    }
                    live?.rates?.get(upstream.device)?.let { (down, up) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            com.vivekkaushik.wrtpulse.ui.Sparkline(
                                live.down.toList(),
                                Wrt.Accent,
                                Modifier.weight(1f).height(20.dp),
                                // Mbps against the fixed 18 default clipped the moment the
                                // line went faster than that; the shape is what this card is
                                // for, so it scales to its own peak.
                                maxY = 0f,
                            )
                            Text("↓ ${"%.1f".format(down)} Mbps", style = mono(11f, 600, Wrt.Accent))
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenWireless)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(WrtIcons.RadioWaves, null, Modifier.size(19.dp), tint = Wrt.Accent)
                Column(Modifier.weight(1f)) {
                    Text("Wireless", style = sans(14.5f, 650))
                    Text(
                        wirelessSummary(store),
                        style = mono(10.5f, 500, if (store?.radios?.any { !store.radioEnabled(it.section) } == true) Wrt.Amber else Wrt.TextDim),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                if (store != null && store.pendingCount > 0) StatusDot(Wrt.Accent, 6.dp)
                Icon(WrtIcons.ChevronRight, null, Modifier.size(14.dp), tint = Wrt.TextDim)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenFirewall)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(WrtIcons.Shield, null, Modifier.size(19.dp), tint = Wrt.Accent)
                Column(Modifier.weight(1f)) {
                    Text("Firewall & security", style = sans(14.5f, 650))
                    Text(
                        firewallSummary(firewall),
                        style = mono(10.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(top = 3.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (firewall != null && firewall.pendingCount > 0) StatusDot(Wrt.Accent, 6.dp)
                Icon(WrtIcons.ChevronRight, null, Modifier.size(14.dp), tint = Wrt.TextDim)
            }
        }
    }
}

/** "fw4 · 3 forwards · 12 rules" — the section's shape before going in. */
private fun firewallSummary(fw: com.vivekkaushik.wrtpulse.data.FirewallStore?): String {
    if (fw == null) return "not connected"
    if (!fw.loaded) return fw.error ?: "opens to read"
    val f = fw.forwardRows().count { it.enabled }
    val r = fw.ruleRows().size
    return listOfNotNull(
        fw.engine?.engine,
        "$f forward${if (f == 1) "" else "s"}",
        "$r rule${if (r == 1) "" else "s"}",
        if (fw.dmz().enabled) "DMZ on" else null,
    ).joinToString(" · ")
}

/** "wan pppoe · wan6 dhcpv6" — which uplinks exist, without opening the screen. */
private fun wanSummary(wan: com.vivekkaushik.wrtpulse.data.WanStore?, count: Int): String {
    if (wan == null) return "not connected"
    if (!wan.loaded) return wan.error ?: "reading…"
    val rows = wan.wanRows()
    if (rows.isEmpty()) return "no uplink of its own"
    return rows.joinToString(" · ") { row ->
        row.section + " " + com.vivekkaushik.wrtpulse.data.WanStore.protoLabel(row.proto).lowercase() +
            if (row.primary && count > 1) " (primary)" else ""
    }
}

/** "3 interfaces · 2 radios · radio0 off" — enough to know whether to go in. */
private fun wirelessSummary(store: WifiStore?): String {
    if (store == null) return "not connected"
    if (!store.loaded) return store.error ?: "reading…"
    val ifaces = store.networks.size + store.drafts.size
    val off = store.radios.filter { !store.radioEnabled(it.section) }.map { it.section }
    return listOfNotNull(
        "$ifaces interface${if (ifaces == 1) "" else "s"}",
        "${store.radios.size} radio${if (store.radios.size == 1) "" else "s"}",
        if (off.isEmpty()) null else "${off.joinToString(", ")} off",
    ).joinToString(" · ")
}

/** One radio as a row on the Wireless page: state at a glance, tap for its settings. */
@Composable
private fun RadioRow(store: WifiStore, radio: WifiRadio, onClick: () -> Unit) {
    val on = store.radioEnabled(radio.section)
    val channel = store.value(radio.section, "channel", radio.channel)
    val nets = store.networks.count { it.device == radio.section }
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (on) Wrt.BorderCard else Wrt.Amber.copy(alpha = 0.4f),
                RoundedCornerShape(13.dp),
            )
            .background(if (on) Wrt.BgCard else Wrt.BgCardDim, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(radio.section, style = sans(13.5f, 650, if (on) Wrt.TextPrimary else Wrt.TextSecondary))
                MonoTag(radio.band, size = 9f)
                if (!on) Badge("OFF", Wrt.Amber)
            }
            Text(
                if (on) "ch $channel · ${ChannelPlan.widthLabel(radio.htmode)} · $nets network${if (nets == 1) "" else "s"}"
                else "switched off · $nets network${if (nets == 1) "" else "s"} silent",
                style = mono(10.5f, 500, if (on) Wrt.TextDim else Wrt.Amber),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // The toggle that was missing when an SSID sat on a dead radio looking healthy.
        WToggle(on) {
            store.stage(radio.section, "disabled", if (radio.disabled) "1" else "0", if (on) "1" else "0")
        }
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
}

// ---------------------------------------------------------------------------
// The Network tab: radio view, interface list, and the add / edit flow
// ---------------------------------------------------------------------------

/** Link state is what people watch: an unplugged WAN should read as down within seconds. */
const val WAN_REFRESH_MS = 5_000L

/** Leases and neighbours move slowly, and the LAN read is the heavier batch. */
const val LAN_REFRESH_MS = 10_000L

/** On-air status and client counts, so a radio going quiet shows up. */
const val WIFI_REFRESH_MS = 8_000L

private sealed interface WifiRoute {
    /** The Network tab itself — a list of what the tab covers, nothing wireless inline. */
    data object Home : WifiRoute
    data object Lan : WifiRoute
    data object Wan : WifiRoute
    data object Firewall : WifiRoute
    data object Interfaces : WifiRoute
    data class Radio(val section: String) : WifiRoute
    data class ClientScan(val radio: String) : WifiRoute
    data class ClientJoin(val radio: String, val cell: ScanCell?, val section: String?, val draftId: Int?) : WifiRoute
    data class ApForm(val radio: String, val section: String?, val draftId: Int?) : WifiRoute
}

@Composable
fun WifiSection(
    ticker: com.vivekkaushik.wrtpulse.data.LiveTicker,
    store: WifiStore?,
    lan: com.vivekkaushik.wrtpulse.data.LanStore?,
    wan: com.vivekkaushik.wrtpulse.data.WanStore?,
    firewall: com.vivekkaushik.wrtpulse.data.FirewallStore? = null,
    live: com.vivekkaushik.wrtpulse.data.Telemetry?,
    liveLatencyMs: Int?,
    routerName: String,
    pendingCount: Int,
    onRouterTap: () -> Unit,
    onReviewApply: () -> Unit,
    onRevert: () -> Unit,
    /** The router changed its own address — the saved entry has to follow it. */
    onLanMoved: (String) -> Unit,
    /** Full-screen steps hide the tab bar, the way the design draws them. */
    onFullScreen: (Boolean) -> Unit,
) {
    // A real stack: back inside the Network tab has to step through the flow, not leave it.
    val stack = remember { mutableStateListOf<WifiRoute>(WifiRoute.Home) }
    val route = stack.last()
    fun push(next: WifiRoute) { stack.add(next) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun resetTo(next: WifiRoute) { stack.clear(); stack.add(next) }
    var addingOn by remember { mutableStateOf<String?>(null) }
    var uciSection by remember { mutableStateOf<String?>(null) }
    var uciText by remember { mutableStateOf<String?>(null) }
    val radios = store?.radios?.toList().orEmpty()

    fun radioOf(section: String) = radios.firstOrNull { it.section == section } ?: radios.first()

    // The Wireless and LAN pages are still tab-level screens; only the steps past them
    // take over the whole display.
    val fullScreen = route !is WifiRoute.Home && route !is WifiRoute.Interfaces &&
        route !is WifiRoute.Lan && route !is WifiRoute.Wan && route !is WifiRoute.Firewall
    androidx.compose.runtime.LaunchedEffect(fullScreen) { onFullScreen(fullScreen) }

    // A router swap resets the flow — the sections it referred to are gone.
    androidx.compose.runtime.LaunchedEffect(store) { resetTo(WifiRoute.Home) }

    androidx.activity.compose.BackHandler(
        enabled = uciSection != null || addingOn != null || stack.size > 1,
    ) {
        when {
            uciSection != null -> uciSection = null
            addingOn != null -> addingOn = null
            else -> pop()
        }
    }

    androidx.compose.runtime.LaunchedEffect(uciSection) {
        val section = uciSection
        uciText = null
        if (section != null && store != null) uciText = store.showSection(section)
    }

    fun openEdit(section: String?, draftId: Int?, radioHint: String?) {
        if (store == null) return
        val net = section?.let { s -> store.networks.firstOrNull { it.section == s } }
        val draft = draftId?.let { id -> store.drafts.firstOrNull { it.id == id } }
        val radio = net?.device ?: draft?.devices?.firstOrNull() ?: radioHint ?: radios.firstOrNull()?.section
        if (radio == null) return
        push(
            if (net?.isClient ?: draft?.isClient ?: false) {
                WifiRoute.ClientJoin(radio, null, section, draftId)
            } else {
                WifiRoute.ApForm(radio, section, draftId)
            }
        )
    }

    when (val r = route) {
        is WifiRoute.Home -> NetworkHomeScreen(
            ticker = ticker,
            store = store,
            lan = lan,
            wan = wan,
            firewall = firewall,
            live = live,
            liveLatencyMs = liveLatencyMs,
            routerName = routerName,
            onRouterTap = onRouterTap,
            onOpenLan = { push(WifiRoute.Lan) },
            onOpenWan = { push(WifiRoute.Wan) },
            onOpenWireless = { push(WifiRoute.Interfaces) },
            onOpenFirewall = { push(WifiRoute.Firewall) },
        )
        is WifiRoute.Firewall -> FirewallSection(
            store = firewall,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
            onBack = { pop() },
            onFullScreen = onFullScreen,
        )
        is WifiRoute.Wan -> WanSection(
            store = wan,
            live = live,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
            onBack = { pop() },
            // The WAN editor pages take the whole screen; the hub does not.
            onFullScreen = onFullScreen,
        )
        is WifiRoute.Lan -> LanScreen(
            store = lan,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
            onBack = { pop() },
            onMoved = onLanMoved,
        )
        is WifiRoute.Radio -> if (store != null) {
            val radio = store.radios.firstOrNull { it.section == r.section }
            if (radio != null) RadioScreen(store, radio) { pop() } else pop()
        }
        is WifiRoute.Interfaces -> Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
            // On-air status and client counts go stale the same way the WAN chip did.
            com.vivekkaushik.wrtpulse.ui.LiveRefresh(store, WIFI_REFRESH_MS)
            FormTopBar("Wireless", { pop() }) { MonoTag(routerName, size = 10.5f) }
            if (store != null) {
                Box(Modifier.weight(1f)) {
                    InterfacesScreen(
                        store = store,
                        onAdd = { addingOn = radios.firstOrNull()?.section },
                        onEdit = { row -> openEdit(row.section, row.draftId, null) },
                        onOpenRadio = { push(WifiRoute.Radio(it)) },
                        onShowUci = { uciSection = it },
                    )
                }
                if (store.pendingCount > 0) {
                    FormActionBar(
                        pendingCount = store.pendingCount,
                        countLabel = if (store.pendingCount == 1) "1 pending change" else "${store.pendingCount} pending changes",
                        saveLabel = "Review & Apply",
                        saveEnabled = true,
                        onCancel = onRevert,
                        onSave = onReviewApply,
                    )
                }
            }
        }
        is WifiRoute.ClientScan -> if (store != null) ClientScanScreen(
            store = store,
            radio = radioOf(r.radio),
            onBack = { pop() },
            onPick = { cell -> push(WifiRoute.ClientJoin(r.radio, cell, null, null)) },
            onHidden = { push(WifiRoute.ClientJoin(r.radio, null, null, null)) },
        )
        is WifiRoute.ClientJoin -> if (store != null) ClientJoinScreen(
            store = store,
            radio = radioOf(r.radio),
            picked = r.cell,
            existing = r.section?.let { s -> store.networks.firstOrNull { it.section == s } },
            draft = r.draftId?.let { id -> store.drafts.firstOrNull { it.id == id } },
            onBack = { pop() },
            // "Change" goes back to the survey it came from, or opens one when the form was
            // reached another way (editing a draft, or joining a hidden network by hand).
            onChangeNetwork = {
                if (stack.getOrNull(stack.lastIndex - 1) is WifiRoute.ClientScan) pop()
                else push(WifiRoute.ClientScan(r.radio))
            },
            onDone = { resetTo(WifiRoute.Interfaces) },
        )
        is WifiRoute.ApForm -> if (store != null) ApFormScreen(
            store = store,
            radios = radios,
            existing = r.section?.let { s -> store.networks.firstOrNull { it.section == s } },
            draft = r.draftId?.let { id -> store.drafts.firstOrNull { it.id == id } },
            startRadio = r.radio,
            onBack = { pop() },
            onDone = { resetTo(WifiRoute.Interfaces) },
        )
    }

    SheetHost(visible = addingOn != null, onDismiss = { addingOn = null }) {
        AddModeSheetContent(
            radios = radios,
            offRadios = radios.filter { store?.radioEnabled(it.section) == false }.map { it.section }.toSet(),
            onCancel = { addingOn = null },
            onContinue = { radio, mode ->
                addingOn = null
                push(if (mode == "sta") WifiRoute.ClientScan(radio) else WifiRoute.ApForm(radio, null, null))
            },
        )
    }
    SheetHost(visible = uciSection != null, onDismiss = { uciSection = null }) {
        UciSheetContent(uciSection.orEmpty(), uciText) { uciSection = null }
    }
}
