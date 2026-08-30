package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.ServiceStore
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.RouterService
import com.vivekkaushik.wrtpulse.ops.ServiceAction
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

private enum class SvcTab { Running, Stopped, All }

/**
 * The router's init scripts. Reading is free and happens on entry; every action goes through
 * a confirmation that names the exact command, because half of these can end the session
 * that is running them.
 */
@Composable
fun ServicesScreen(
    store: ServiceStore?,
    latencyMs: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(SvcTab.Running) }
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<RouterService?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Services", onBack) {
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
                Text("Connect to a router to manage services.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            SummaryCard(store, onReload = { scope.launch { store.load(); toast = "Service list re-read" } })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip("Running ${store.runningCount}", tab == SvcTab.Running, size = 11.5f) {
                    tab = SvcTab.Running
                }
                FilterChip(
                    "Stopped ${store.stoppedCount}",
                    tab == SvcTab.Stopped,
                    size = 11.5f,
                    selectedColor = if (store.stoppedCount == 0) Wrt.Accent else Wrt.Amber,
                ) { tab = SvcTab.Stopped }
                FilterChip("All ${store.services.size}", tab == SvcTab.All, size = 11.5f) {
                    tab = SvcTab.All
                }
            }
            Spacer(Modifier.height(9.dp))
            Box {
                FormTextField(filter, { filter = it })
                if (filter.isEmpty()) {
                    Text(
                        "filter by name",
                        style = mono(12.5f, 500, Wrt.TextFaint),
                        modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                    )
                }
            }
            store.error?.let {
                Text(it, style = sans(11f, 500, Wrt.Red), modifier = Modifier.padding(top = 8.dp))
            }
            toast?.let {
                Text(
                    it,
                    style = mono(10.5f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                    modifier = Modifier.padding(top = 8.dp).clickable { toast = null },
                )
            }
        }

        val rows = store.services
            .filter { filter.isBlank() || it.name.contains(filter, true) }
            .filter {
                when (tab) {
                    SvcTab.Running -> it.running
                    // A boot script that has exited isn't stopped, so it belongs in neither
                    // of the first two tabs — only in All.
                    SvcTab.Stopped -> !it.running && it.procd
                    SvcTab.All -> true
                }
            }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(emptyLine(tab, store, filter), style = sans(11.5f, 500, Wrt.TextDim))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(rows, key = { it.name }) { service -> ServiceRow(service) { open = service } }
            }
        }
    }

    val current = store
    val selected = open
    if (current != null && selected != null) {
        ServiceDialog(
            store = current,
            // The list is re-read after every action, so the dialog follows it rather than
            // going on describing the state the row had at the moment it was tapped.
            service = current.services.firstOrNull { it.name == selected.name } ?: selected,
            onDismiss = { open = null },
            onResult = { toast = it; open = null },
        )
    }
}

private fun emptyLine(tab: SvcTab, store: ServiceStore, filter: String): String = when {
    store.loading -> "Reading /etc/init.d…"
    filter.isNotBlank() -> "No service matches “$filter”."
    tab == SvcTab.Running -> "Nothing is running — or procd didn't answer."
    tab == SvcTab.Stopped -> "Every service that should be running is running."
    else -> "No init scripts found."
}

/**
 * Stopped-but-enabled is the number worth leading with: a service set to start at boot that
 * isn't running now is the one thing in this list that is actually wrong.
 */
@Composable
private fun SummaryCard(store: ServiceStore, onReload: () -> Unit) {
    val failed = store.failedCount
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("INIT SCRIPTS", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag("PROCD", color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f))
        }
        Row(Modifier.padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    "${store.runningCount} running",
                    "${store.stoppedCount} stopped",
                    "${store.enabledCount} at boot",
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (store.busy || store.loading) "working…" else "Re-read",
                style = sans(11.5f, 650, if (store.busy || store.loading) Wrt.TextDim else Wrt.Accent),
                modifier = Modifier.clickable(enabled = !store.busy && !store.loading, onClick = onReload),
            )
        }
        if (failed > 0) {
            Text(
                "$failed service${if (failed == 1) " is" else "s are"} set to start at boot " +
                    "but not running now — the log usually says why.",
                style = sans(10.5f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

/** Green runs, amber should be running and isn't, dim never intended to keep running. */
private fun dotColor(service: RouterService): Color = when {
    service.running -> Wrt.Green
    service.oneShot -> Wrt.DotOff
    service.enabled -> Wrt.Amber
    else -> Wrt.DotOff
}

@Composable
private fun ServiceRow(service: RouterService, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderRow, RoundedCornerShape(10.dp))
            .background(Wrt.BgCardDim, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(dotColor(service), size = 7.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    service.name,
                    style = sans(12.5f, 600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (service.enabled) MonoTag("BOOT", Wrt.TextTertiary)
                if (service.oneShot) MonoTag("ONE-SHOT", Wrt.TextDim)
            }
            Text(
                listOfNotNull(
                    service.statusLabel,
                    service.pid?.let { "pid $it" },
                    service.instances.takeIf { it > 1 }?.let { "$it instances" },
                    service.start?.let { "START $it" },
                ).joinToString(" · "),
                style = mono(10f, 500, if (service.running) Wrt.TextDim else dotColor(service)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * One dialog for the whole life of a service: what the init script says about itself, then —
 * once an action is chosen — the exact command that would run, whatever warning it earns,
 * and only then a button that runs it.
 */
@Composable
private fun ServiceDialog(
    store: ServiceStore,
    service: RouterService,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf<ServiceAction?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(service.name) { info = store.info(service.name) }

    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(dotColor(service), size = 8.dp)
                Text(service.name, style = sans(15f, 650), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(
                    service.statusLabel,
                    if (service.enabled) "starts at boot" else "not started at boot",
                    service.pid?.let { "pid $it" },
                    service.start?.let { "START $it" },
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
            )

            val picked = action
            if (picked == null) {
                CodeBox(info ?: "Reading the init script…", max = 180.dp)
            } else {
                val block = ServiceStore.actionBlock(service.name, picked)
                Column(Modifier.padding(top = 12.dp)) {
                    if (block != null) {
                        Text(block, style = sans(12f, 500, Wrt.Red))
                    } else {
                        SectionLabel("RUNS ON THE ROUTER", size = 9f, tracking = 0.14)
                        CodeBox(Commands.serviceAction(service.name, picked), max = 120.dp, top = 6.dp)
                        ServiceStore.actionWarning(service.name, picked)?.let {
                            Text(
                                it,
                                style = sans(10.5f, 500, Wrt.Amber),
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }

            failure?.let {
                Text(it, style = mono(10.5f, 500, Wrt.Red), modifier = Modifier.padding(top = 10.dp))
            }

            Spacer(Modifier.height(16.dp))
            if (picked == null) {
                // A blocked action is still offered, dimmed: tapping it is how the reason
                // it won't run becomes readable.
                ServiceStore.actionsFor(service).forEach { candidate ->
                    val pick = { failure = null; action = candidate }
                    when {
                        ServiceStore.actionBlock(service.name, candidate) != null ->
                            GhostButton(candidate.label, textColor = Wrt.TextDim, onClick = pick)
                        candidate == ServiceAction.Stop || candidate == ServiceAction.Disable ->
                            PrimaryButton(candidate.label, color = Wrt.Red, textColor = Wrt.OnRed, onClick = pick)
                        candidate == ServiceAction.Start ->
                            PrimaryButton(candidate.label, onClick = pick)
                        else -> GhostButton(candidate.label, onClick = pick)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                GhostButton("Close", onClick = onDismiss)
            } else {
                if (ServiceStore.actionBlock(service.name, picked) == null) {
                    val destructive = picked == ServiceAction.Stop || picked == ServiceAction.Disable
                    PrimaryButton(
                        if (running) "Working…" else "${picked.label} ${service.name}",
                        color = when {
                            running -> Wrt.BorderCard
                            destructive -> Wrt.Red
                            else -> Wrt.Accent
                        },
                        textColor = when {
                            running -> Wrt.TextDim
                            destructive -> Wrt.OnRed
                            else -> Wrt.OnAccent
                        },
                    ) {
                        if (!running) {
                            running = true
                            failure = null
                            scope.launch {
                                val message = store.act(service.name, picked)
                                running = false
                                if (message.startsWith("Failed")) failure = message else onResult(message)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                GhostButton("Back") { if (!running) { action = null; failure = null } }
            }
        }
    }
}

@Composable
private fun CodeBox(text: String, max: Dp, top: Dp = 12.dp) {
    Box(
        Modifier
            .padding(top = top)
            .fillMaxWidth()
            .heightIn(max = max)
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(11.dp))
            .background(Wrt.BgCode, RoundedCornerShape(11.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, style = mono(10f, 500, Wrt.TextSecondary))
    }
}
