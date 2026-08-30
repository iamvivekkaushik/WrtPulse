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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.PackageStore
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.ops.InstallPlan
import com.vivekkaushik.wrtpulse.ops.RemovePlan
import com.vivekkaushik.wrtpulse.ops.RouterPackage
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

private enum class PkgTab { Installed, Updates, Search }

/**
 * The router's package manager. Reading is free and happens on entry; every write goes
 * through a dialog that first runs the manager's own dry run and shows what it said.
 */
@Composable
fun PackagesScreen(
    store: PackageStore?,
    live: Telemetry?,
    latencyMs: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(PkgTab.Installed) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<RouterPackage?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Packages", onBack) {
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
                Text("Connect to a router to manage packages.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            FlashCard(store, live, onRefresh = { scope.launch { toast = store.refreshFeed() } })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip("Installed ${store.installed.size}", tab == PkgTab.Installed, size = 11.5f) {
                    tab = PkgTab.Installed
                }
                FilterChip(
                    if (store.upgrades.isEmpty()) "Updates" else "Updates ${store.upgrades.size}",
                    tab == PkgTab.Updates,
                    size = 11.5f,
                    selectedColor = if (store.upgrades.isEmpty()) Wrt.Accent else Wrt.Amber,
                ) { tab = PkgTab.Updates }
                FilterChip("Search", tab == PkgTab.Search, size = 11.5f) { tab = PkgTab.Search }
            }
            Spacer(Modifier.height(9.dp))
            when (tab) {
                PkgTab.Installed -> SearchField(
                    filter, { filter = it }, "filter installed", busy = false, onSubmit = null,
                )
                PkgTab.Search -> SearchField(
                    query, { query = it }, "search the ${store.manager} feed",
                    busy = store.searching,
                    onSubmit = { scope.launch { store.search(query) } },
                )
                PkgTab.Updates -> Unit
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

        val rows = when (tab) {
            PkgTab.Installed -> store.installed.filter { filter.isBlank() || it.name.contains(filter, true) }
            PkgTab.Updates -> store.upgrades.toList()
            PkgTab.Search -> store.results.toList()
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    emptyLine(tab, store, filter, query),
                    style = sans(11.5f, 500, Wrt.TextDim),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(rows, key = { it.name }) { pkg -> PackageRow(pkg) { open = pkg } }
            }
        }
    }

    open?.let { pkg ->
        PackageDialog(
            store = store!!,
            pkg = pkg,
            onDismiss = { open = null },
            onResult = { toast = it; open = null },
        )
    }
}

private fun emptyLine(tab: PkgTab, store: PackageStore, filter: String, query: String): String = when {
    store.loading -> "Reading the package database…"
    tab == PkgTab.Installed && filter.isNotBlank() -> "No installed package matches “$filter”."
    tab == PkgTab.Installed -> "Nothing installed — or ${store.manager} didn't answer."
    tab == PkgTab.Updates -> "Everything is at the version the feed offers."
    store.searching -> "Searching the feed…"
    store.searchedTerm != null -> "No package matches “${store.searchedTerm}”. " +
        "If that seems wrong, refresh the package list."
    query.isNotBlank() -> "Tap the magnifier to search."
    else -> "Type at least two characters to search the feed."
}

/** Flash is the constraint on a router, so it leads — every install eats into this bar. */
@Composable
private fun FlashCard(store: PackageStore, live: Telemetry?, onRefresh: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("OVERLAY FLASH", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag(store.manager.uppercase(), color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f))
        }
        val used = (live?.flashPct ?: 0).coerceIn(0, 100)
        Box(
            Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .height(4.dp)
                .background(Wrt.ProgressTrack, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth((used / 100f).coerceIn(0.02f, 1f))
                    .height(4.dp)
                    .background(if (used >= 90) Wrt.Red else Wrt.Accent, RoundedCornerShape(2.dp))
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    store.availKb?.let { preciseBytes(it * 1024) + " free" } ?: live?.flashFree,
                    "${store.installed.size} installed",
                    PackageStore.feedAgeLabel(store.feedAgeSeconds),
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (store.busy) "working…" else "Refresh list",
                style = sans(11.5f, 650, if (store.busy) Wrt.TextDim else Wrt.Accent),
                modifier = Modifier.clickable(enabled = !store.busy, onClick = onRefresh),
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    busy: Boolean,
    onSubmit: (() -> Unit)?,
) {
    Box {
        FormTextField(value, onChange) {
            if (onSubmit != null) {
                Icon(
                    WrtIcons.Search,
                    "search",
                    Modifier.size(16.dp).clickable(enabled = !busy, onClick = onSubmit),
                    tint = if (busy) Wrt.TextDim else Wrt.Accent,
                )
            }
        }
        if (value.isEmpty()) {
            Text(
                hint,
                style = mono(12.5f, 500, Wrt.TextFaint),
                modifier = Modifier.padding(start = 12.dp, top = 20.dp),
            )
        }
    }
}

@Composable
private fun PackageRow(pkg: RouterPackage, onClick: () -> Unit) {
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
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    pkg.name,
                    style = sans(12.5f, 600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (pkg.upgradeTo != null) MonoTag("UPDATE", Wrt.Amber, Wrt.Amber.copy(alpha = 0.5f))
                else if (pkg.installed && pkg.auto) MonoTag("DEP", Wrt.TextDim)
                else if (!pkg.installed) MonoTag("AVAILABLE", Wrt.TextDim)
            }
            Text(
                buildString {
                    if (pkg.upgradeTo != null) {
                        append(pkg.version.ifBlank { "installed" }).append(" → ").append(pkg.upgradeTo)
                    } else {
                        append(pkg.version.ifBlank { "—" })
                    }
                    if (pkg.description.isNotBlank()) append(" · ").append(pkg.description)
                },
                style = mono(10f, 500, if (pkg.upgradeTo != null) Wrt.Amber else Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            pkg.sizeBytes?.let { preciseBytes(it) } ?: "",
            style = mono(10.5f, 500, Wrt.TextTertiary),
        )
        Icon(WrtIcons.ChevronRight, null, Modifier.size(12.dp), tint = Wrt.TextDim)
    }
}

private enum class PkgAction { None, Install, Remove, Upgrade }

/**
 * One dialog for the whole life of a package: what the manager knows about it, then — once
 * an action is chosen — that action's dry run, and only then a button that writes.
 */
@Composable
private fun PackageDialog(
    store: PackageStore,
    pkg: RouterPackage,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf(PkgAction.None) }
    var info by remember { mutableStateOf<String?>(null) }
    var installPlan by remember { mutableStateOf<InstallPlan?>(null) }
    var removePlan by remember { mutableStateOf<RemovePlan?>(null) }
    var running by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pkg.name) { info = store.info(pkg.name) }
    LaunchedEffect(action) {
        when (action) {
            PkgAction.Install -> installPlan = store.planInstall(pkg.name)
            PkgAction.Remove -> removePlan = store.planRemove(pkg.name)
            else -> Unit
        }
    }

    val block = PackageStore.removalBlock(pkg.name)
    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text(pkg.name, style = sans(15f, 650), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    pkg.version.ifBlank { null },
                    pkg.sizeBytes?.let { preciseBytes(it) },
                    if (pkg.installed) "installed" else "not installed",
                    pkg.upgradeTo?.let { "update to $it" },
                ).joinToString(" · "),
                style = mono(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
            )

            when (action) {
                PkgAction.None -> InfoBox(info)
                PkgAction.Install -> PlanBox(installPlan)
                PkgAction.Remove -> RemoveBox(removePlan, pkg.name)
                PkgAction.Upgrade -> Column(Modifier.padding(top = 12.dp)) {
                    Note(PackageStore.UPGRADE_CAUTION, Wrt.Amber)
                    Text(
                        "${pkg.version.ifBlank { "installed" }} → ${pkg.upgradeTo}",
                        style = mono(12f, 600, Wrt.Accent),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            failure?.let {
                Text(it, style = mono(10.5f, 500, Wrt.Red), modifier = Modifier.padding(top = 10.dp))
            }
            if (action == PkgAction.None && block != null && pkg.installed) {
                Note(block, Wrt.TextDim)
            }

            Spacer(Modifier.height(16.dp))
            when (action) {
                PkgAction.None -> {
                    if (pkg.upgradeTo != null) {
                        PrimaryButton("Upgrade", color = Wrt.Amber, textColor = Wrt.OnAccent) {
                            failure = null; action = PkgAction.Upgrade
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (!pkg.installed) {
                        PrimaryButton("Install") { failure = null; action = PkgAction.Install }
                        Spacer(Modifier.height(6.dp))
                    } else if (block == null) {
                        GhostButton(
                            "Remove",
                            border = Wrt.Red.copy(alpha = 0.45f),
                            textColor = Wrt.Red,
                        ) { failure = null; action = PkgAction.Remove }
                        Spacer(Modifier.height(6.dp))
                    }
                    GhostButton("Close", onClick = onDismiss)
                }
                else -> {
                    val ready = when (action) {
                        PkgAction.Install -> installPlan?.problem == null && installPlan != null
                        PkgAction.Remove -> removePlan?.problem == null && removePlan != null
                        else -> true
                    }
                    if (ready) {
                        val (label, color, textColor) = when (action) {
                            PkgAction.Remove -> Triple("Remove", Wrt.Red, Wrt.OnRed)
                            PkgAction.Upgrade -> Triple("Upgrade", Wrt.Amber, Wrt.OnAccent)
                            else -> Triple("Install", Wrt.Accent, Wrt.OnAccent)
                        }
                        PrimaryButton(
                            if (running) "Working…" else label,
                            color = if (running) Wrt.BorderCard else color,
                            textColor = if (running) Wrt.TextDim else textColor,
                        ) {
                            if (!running) {
                                running = true
                                failure = null
                                scope.launch {
                                    val message = when (action) {
                                        PkgAction.Install -> store.install(pkg.name)
                                        PkgAction.Remove -> store.remove(pkg.name)
                                        else -> store.upgrade(pkg.name)
                                    }
                                    running = false
                                    if (message.startsWith("Failed")) failure = message else onResult(message)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    GhostButton("Back") { if (!running) { action = PkgAction.None; failure = null } }
                }
            }
        }
    }
}

@Composable
private fun InfoBox(text: String?) {
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(11.dp))
            .background(Wrt.BgCode, RoundedCornerShape(11.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text ?: "Asking the router…", style = mono(10f, 500, Wrt.TextSecondary))
    }
}

@Composable
private fun PlanBox(plan: InstallPlan?) {
    Column(Modifier.padding(top = 12.dp)) {
        when {
            plan == null -> Text("Resolving the install…", style = mono(11f, 500, Wrt.TextDim))
            plan.problem != null -> Text(plan.problem!!, style = sans(12f, 500, Wrt.Red))
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderHair, RoundedCornerShape(11.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            ) {
                val total = plan.totalBytes
                val free = plan.availKb?.let { it * 1024 }
                PlanRow(
                    "Install size",
                    total?.let {
                        preciseBytes(it) + if (plan.packages.size > 1) " · ${plan.packages.size} packages" else ""
                    } ?: "unknown (${plan.packageManager})",
                )
                PlanRow("Free space now", free?.let { preciseBytes(it) } ?: "—")
                PlanRow(
                    "After install",
                    if (total != null && free != null) "≈ ${preciseBytes(free - total)}" else "—",
                    highlight = true,
                )
                if (plan.packages.size > 1) {
                    Text(
                        "Pulls in: " + plan.packages.joinToString(", ") { it.first },
                        style = sans(9.5f, 400, Wrt.TextDim),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    "Its service is started too, if the package ships one.",
                    style = sans(9.5f, 400, Wrt.TextDim),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RemoveBox(plan: RemovePlan?, name: String) {
    Column(Modifier.padding(top = 12.dp)) {
        when {
            plan == null -> Text("Asking what would go…", style = mono(11f, 500, Wrt.TextDim))
            plan.problem != null -> Text(plan.problem!!, style = sans(12f, 500, Wrt.Red))
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Red.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                    .background(Wrt.Red.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp)
            ) {
                PlanRow("Removes", "${plan.packages.size} package${if (plan.packages.size == 1) "" else "s"}")
                PlanRow("Reclaims", plan.totalBytes?.let { preciseBytes(it) } ?: "unknown")
                PlanRow(
                    "Free after",
                    (plan.availKb?.let { it * 1024 })?.let { free ->
                        plan.totalBytes?.let { "≈ ${preciseBytes(free + it)}" } ?: preciseBytes(free)
                    } ?: "—",
                    highlight = true,
                )
                if (plan.packages.size > 1) {
                    Text(
                        plan.packages.joinToString(", ") { it.first },
                        style = sans(9.5f, 400, Wrt.DangerSub),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        // The plan usually takes orphaned dependencies with it, so the caution is about
        // everything that would go, not only the row that was tapped.
        val going = (plan?.packages?.map { it.first } ?: listOf(name)).ifEmpty { listOf(name) }
        going.mapNotNull { PackageStore.removalWarning(it) }.distinct()
            .forEach { Note(it, Wrt.Amber) }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, style = sans(10.5f, 500, color), modifier = Modifier.padding(top = 10.dp))
}
