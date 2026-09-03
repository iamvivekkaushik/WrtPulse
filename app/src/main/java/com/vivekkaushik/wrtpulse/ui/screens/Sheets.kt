package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.Router
import com.vivekkaushik.wrtpulse.data.RouterStatus
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.dashedBorder
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

/**
 * Bottom-sheet host per the motion spec: scrim fades while the sheet springs up
 * (stiff spring, small overshoot).
 */
@Composable
fun SheetHost(visible: Boolean, onDismiss: () -> Unit, sheet: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xB8040706))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            ) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Wrt.BgSheet, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .border(1.dp, Wrt.BorderInput, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .navigationBarsPadding()
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp)
                        .size(36.dp, 4.dp)
                        .background(Wrt.DotOff, RoundedCornerShape(2.dp))
                )
                sheet()
            }
        }
    }
}

// ---------------- Router switcher ----------------

@Composable
fun SwitcherSheetContent(
    ticker: LiveTicker,
    currentRouter: String,
    saved: List<com.vivekkaushik.wrtpulse.db.RouterEntity>? = null,
    connectedHost: String? = null,
    liveLatencyMs: Int? = null,
    onPickSaved: (com.vivekkaushik.wrtpulse.db.RouterEntity) -> Unit = {},
    onPick: (Router) -> Unit,
    onManage: () -> Unit,
    /** The dashed "Add router" row. Distinct from [onManage]: it opens the add page, not the list. */
    onAdd: () -> Unit = onManage,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Switch router", style = sans(15.5f, 650))
            Spacer(Modifier.weight(1f))
            Text("Manage", style = sans(12f, 600, Wrt.Accent), modifier = Modifier.clickable(onClick = onManage))
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val entries: List<Pair<Router, com.vivekkaushik.wrtpulse.db.RouterEntity?>> =
                saved?.map { e -> e.asRouter(connectedHost, null) to e }
                    ?: Demo.routers.map { it to null }
            entries.forEach { (r, entity) ->
                val selected = if (saved != null) r.status == RouterStatus.Online else r.name == currentRouter
                val offline = r.status == RouterStatus.Offline
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            when {
                                selected -> Wrt.Accent.copy(alpha = 0.45f)
                                offline -> Wrt.BorderHair
                                else -> Wrt.BorderCard
                            },
                            RoundedCornerShape(12.dp),
                        )
                        .background(
                            when {
                                selected -> Wrt.Accent.copy(alpha = 0.05f)
                                offline -> Wrt.BgCardDim
                                else -> Wrt.BgCard
                            },
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = !offline) { if (entity != null) onPickSaved(entity) else onPick(r) }
                        .padding(horizontal = 13.dp, vertical = 12.dp)
                        .alpha(if (offline) 0.7f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    when (r.status) {
                        RouterStatus.Online -> StatusDot(Wrt.Green, 8.dp, pulse = selected)
                        RouterStatus.Reconnecting -> StatusDot(Wrt.Amber, 8.dp, pulse = true, periodMs = 1600)
                        RouterStatus.Offline -> StatusDot(Wrt.DotOff, 8.dp)
                        RouterStatus.Saved -> StatusDot(Wrt.TextTertiary, 8.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = sans(13.5f, 650, if (offline) Wrt.TextSecondary else Wrt.TextPrimary))
                        Text(r.switcherDetail, style = mono(10.5f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp))
                    }
                    when (r.status) {
                        RouterStatus.Online -> Text(
                            "${liveLatencyMs ?: if (selected) ticker.latencyMs else r.latencyMs ?: 18} ms",
                            style = mono(10.5f, 500, Wrt.TextTertiary),
                        )
                        RouterStatus.Reconnecting -> Text("retry 2/5", style = mono(10.5f, 500, Wrt.Amber))
                        RouterStatus.Saved -> Text("tap to connect", style = mono(10f, 500, Wrt.TextDim))
                        RouterStatus.Offline -> {}
                    }
                    if (selected) Icon(WrtIcons.Check, "selected", Modifier.size(16.dp), tint = Wrt.Accent)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .dashedBorder(Wrt.BorderInput, 12.dp)
                    .clickable(onClick = onAdd)
                    .padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(WrtIcons.Plus, null, Modifier.size(15.dp), tint = Wrt.TextTertiary)
                Text("Add router", style = sans(12.5f, 600, Wrt.TextTertiary))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------------- Diff before apply ----------------

@Composable
fun DiffSheetContent(
    store: com.vivekkaushik.wrtpulse.data.WifiStore?,
    routerName: String,
    clientCount: Int?,
    onApply: () -> Unit,
    onRevertAll: () -> Unit,
) {
    val opsCount = store?.opCount ?: 3
    val changeCount = store?.pendingCount ?: 3
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Review changes", style = sans(16f, 650))
            Spacer(Modifier.weight(1f))
            Text("uci batch · $opsCount ops", style = mono(10.5f, 500, Wrt.TextDim))
        }
        Text(
            "These commands run on $routerName when you apply.",
            style = sans(12f, 400, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .border(1.dp, Wrt.BorderHair, RoundedCornerShape(12.dp))
                .background(Wrt.BgCode, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 12.dp)
        ) {
            DiffLine("# wireless", Wrt.TextDim)
            if (store != null) {
                store.diffLines().forEach { (line, added) ->
                    DiffLine(line, if (added) Wrt.Green else Wrt.Red)
                }
                // Joining an upstream network reaches past wireless — show that too.
                val netOps = store.networkOps()
                if (netOps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DiffLine("# network", Wrt.TextDim)
                    netOps.forEach { DiffLine("+ ${it.removePrefix("set ")}", Wrt.Green) }
                }
                val fw = store.firewallLines()
                if (fw.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    DiffLine("# firewall", Wrt.TextDim)
                    fw.forEach { DiffLine(it, Wrt.Green) }
                }
            } else {
                DiffLine("- radio0.channel='6'", Wrt.Red)
                DiffLine("+ radio0.channel='11'", Wrt.Green)
                DiffLine("- @wifi-iface[0].key='••••••••'", Wrt.Red)
                DiffLine("+ @wifi-iface[0].key='tr0ub4dor&3'", Wrt.Green)
                DiffLine("+ @wifi-iface[2].disabled='0'", Wrt.Green)
            }
            Spacer(Modifier.height(8.dp))
            DiffLine(store?.commitLine() ?: "$ uci commit wireless && wifi reload", Wrt.TextDim)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Amber)
            Text(
                "Wi-Fi drops for ~15 s while radios reload. " +
                    (clientCount?.let { "$it clients will reconnect on their own." }
                        ?: "Clients will reconnect on their own.") +
                    " If this phone is on that Wi-Fi, the app reconnects too.",
                style = sans(12f, 400, Wrt.AmberText, lineHeight = 18.sp),
            )
        }
        // A change the router would reject must not reach the Apply button.
        val problems = store?.problems().orEmpty()
        problems.forEach { problem ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                    .background(Wrt.Red.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(16.dp), tint = Wrt.Red)
                Text(problem, style = sans(12f, 500, Wrt.Red, lineHeight = 18.sp))
            }
        }
        if (store?.error != null) {
            Text(
                store.error!!,
                style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        val label = when {
            store?.applying == true -> "Applying…"
            changeCount == 1 -> "Apply 1 change"
            else -> "Apply $changeCount changes"
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
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(42.dp)
                .clickable(onClick = onRevertAll),
            contentAlignment = Alignment.Center,
        ) {
            Text("Revert all", style = sans(13f, 600, Wrt.Red))
        }
        Text(
            "Nothing runs until you apply.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DiffLine(text: String, color: Color) {
    Text(text, style = mono(11f, 500, color, lineHeight = 19.sp))
}
