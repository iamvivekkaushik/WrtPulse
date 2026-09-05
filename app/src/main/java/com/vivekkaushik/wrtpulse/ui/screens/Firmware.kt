package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vivekkaushik.wrtpulse.data.FirmwareStore
import com.vivekkaushik.wrtpulse.data.NoticeKind
import com.vivekkaushik.wrtpulse.data.WatchEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch
import java.io.File

/**
 * The firmware upgrade path. Five gates, in the order they have to happen, each showing what
 * it would run before it runs it. The flash button does not exist until every gate is met —
 * see [FirmwareStore.flashBlock], which is also what the screen prints when it isn't.
 */
@Composable
fun FirmwareScreen(
    store: FirmwareStore?,
    latencyMs: Int,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keyed by gate number: a result shown 2000px below the button that caused it is a
    // result nobody sees.
    var result by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var urlOpen by remember { mutableStateOf(false) }
    // "asks once more" — the design's wording, so skipping the backup takes two taps.
    var waiveArmed by remember { mutableStateOf(false) }
    // Design 41 is its own screen, reached from gate 5 — the red zone is a step, not a card.
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }
    // Screen 42: once the flash is away, the screen watches for the router to come back.
    LaunchedEffect(store?.flashing) { if (store?.flashing == true) store.watchReboot() }

    // Design 40's "Flash a local file": the bytes are read off the main thread and pushed
    // over stdin; sysupgrade -T then judges them like any other image.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && store != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    readDocumentBytes(context, uri, MAX_LOCAL_IMAGE_BYTES + 1)
                }
                result = 3 to when {
                    picked == null -> "Failed: couldn't read that file."
                    picked.second.size > MAX_LOCAL_IMAGE_BYTES -> "Failed: over ${MAX_LOCAL_IMAGE_BYTES / 1024 / 1024} MB — not a sysupgrade image."
                    else -> store.uploadLocalImage(picked.first, picked.second)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        // The red zone is a takeover and carries its own bar, so this one stands down —
        // two stacked "Firmware upgrade" headers otherwise.
        if (!confirming) {
            FormTopBar("Firmware upgrade", onBack) {
                Text(
                    "$latencyMs ms",
                    style = mono(10.5f, 500, Wrt.TextTertiary),
                    modifier = Modifier
                        .border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to manage firmware.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        if (store.flashing) {
            FlashingPanel(store)
            return@Column
        }
        if (confirming) {
            FlashConfirmPanel(
                store = store,
                onCancel = { confirming = false },
                onResult = { result = 5 to it },
            )
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RunningCard(store)

            SectionLabel("FIVE GATES, ONE SCREEN", tracking = 0.14)

            // Designs 40a/40b: the gates are a sequence. Exactly one is actionable, and only
            // that one carries a button — the rest show what they WILL run, so the screen
            // reads as "here is the next thing to do", not five competing controls.
            val gate1 = when {
                store.busy && store.progress?.contains("archive", true) == true -> GateState.Running
                store.backupDone -> GateState.Done
                store.backupWaived -> GateState.Skipped
                else -> GateState.StartHere
            }
            val gate2 = when {
                store.busy && store.progress?.contains("server", true) == true -> GateState.Running
                store.check?.safe == false -> GateState.Blocked
                store.check != null -> GateState.Done
                gate1 == GateState.Done || gate1 == GateState.Skipped -> GateState.StartHere
                else -> GateState.Waiting
            }
            val rebuildOnly = store.check?.sameVersion == true
            val gate3 = when {
                store.busy && (store.progress?.contains("ownload", true) == true ||
                    store.progress?.contains("Sending", true) == true) -> GateState.Running
                store.image != null -> GateState.Done
                gate2 != GateState.Done -> GateState.Waiting
                // Nothing newer exists: rebuilding is a choice, not the next step.
                rebuildOnly -> GateState.Optional
                else -> GateState.StartHere
            }
            val gate4 = when {
                store.image?.testPassed == true -> GateState.Done
                store.image?.testPassed == false -> GateState.Blocked
                store.image != null -> GateState.StartHere
                else -> GateState.Waiting
            }

            GateCard(
                index = 1,
                title = "Back up the configuration",
                state = gate1,
                detail = when {
                    store.backupFile != null -> "${store.backupFile!!.name} · saved to this phone"
                    store.backupWaived -> "skipped — nothing to restore from"
                    else -> "pulled onto this phone, then removed from the router"
                },
                message = result?.takeIf { it.first == 1 }?.second,
            ) {
                if (store.backupDone) {
                    InlineAction("Share the backup", Wrt.Accent) { shareBackup(context, store.backupFile) }
                } else {
                    CodeLine("sysupgrade -b ${Commands.BACKUP_FILE}")
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        itemVerticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AccentChip(if (store.busy) "Working…" else "Back up now", busy = store.busy) {
                            if (!store.busy) {
                                scope.launch { result = 1 to store.backUp(File(context.filesDir, "backups")) }
                            }
                        }
                        Text("or", style = sans(10.5f, 400, Wrt.TextDim))
                        // "asks once more" in the design — so it does.
                        InlineAction(
                            if (waiveArmed) "Tap again to continue with no backup"
                            else "Continue without a backup — asks once more.",
                            if (waiveArmed) Wrt.Amber else Wrt.TextTertiary,
                        ) {
                            if (waiveArmed) {
                                store.waiveBackup()
                                waiveArmed = false
                                result = 1 to "Continuing without a backup"
                            } else {
                                waiveArmed = true
                            }
                        }
                    }
                }
            }

            GateCard(
                index = 2,
                title = "Ask the upgrade server",
                state = gate2,
                detail = when {
                    store.check != null -> "owut check"
                    store.status.tool == "owut" ->
                        "owut asks sysupgrade.openwrt.org what's available for " +
                            (store.board?.boardName?.ifBlank { null } ?: "this board")
                    store.status.hasTool -> "${store.status.tool} — not driven by this app yet"
                    else -> "no owut on this router; use a download URL below"
                },
                message = result?.takeIf { it.first == 2 }?.second,
            ) {
                store.check?.let { check ->
                    ServerAnswerBox(check, store.checkDetail)
                    // 40b: the same-version case says outright that there is nothing newer,
                    // instead of leaving two identical versions to be puzzled over.
                    if (check.sameVersion) {
                        Spacer(Modifier.height(9.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                                .background(Wrt.Accent.copy(alpha = 0.05f), RoundedCornerShape(11.dp))
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text(
                                "Latest release already installed. owut can rebuild " +
                                    "${check.versionTo?.substringBefore(" ") ?: "it"} with the newest packages.",
                                style = sans(11.5f, 650, Wrt.TextPrimary, lineHeight = 17.sp),
                            )
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                }
                if (store.userPackages.isNotEmpty()) {
                    Text(
                        "${store.userPackages.size} package${if (store.userPackages.size == 1) "" else "s"} " +
                            "you installed are not in the default image: " +
                            store.userPackages.joinToString(", ") +
                            ". owut carries them into the build; reinstall after a plain flash.",
                        style = sans(10.5f, 500, Wrt.AmberText, lineHeight = 16.sp),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$ ${Commands.UPGRADE_CHECK.substringBefore(" 2>")}", style = mono(9.5f, 500, Wrt.TextDim))
                    if (store.status.tool == "owut" && (gate2 == GateState.StartHere || store.check != null)) {
                        InlineAction(if (store.check == null) "run" else "check again", Wrt.Accent) {
                            scope.launch { result = 2 to store.runCheck() }
                        }
                    }
                }
            }

            GateCard(
                index = 3,
                title = "Build and download",
                state = gate3,
                detail = store.image?.let { img ->
                    listOfNotNull(
                        img.path.substringAfterLast('/'),
                        img.sizeBytes?.let { preciseBytes(it) },
                        img.sha256?.take(12),
                    ).joinToString(" · ")
                } ?: "built by the server with your installed packages",
                message = result?.takeIf { it.first == 3 }?.second,
            ) {
                if (store.busy && store.downloadLine != null) {
                    DownloadProgress(store)
                    Spacer(Modifier.height(9.dp))
                }
                store.status.tmpFreeKb?.let { free ->
                    Text(
                        "/tmp free ${preciseBytes(free * 1024)}" +
                            (store.image?.sizeBytes?.let { " · image needs ${preciseBytes(it)}" }
                                ?: " · read now, checked against image size later"),
                        style = mono(9.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                }
                // 40b: when there is nothing newer, the offer is a rebuild — named as one.
                if (gate3 == GateState.Optional) {
                    Text(
                        "Rebuild ${store.check?.versionTo?.substringBefore(" ") ?: "this version"} with " +
                            "current packages · same version, newer" +
                            (store.check?.outdatedPackages?.let { " ($it out of date)" } ?: ""),
                        style = sans(11f, 400, Wrt.TextSecondary, lineHeight = 16.sp),
                        modifier = Modifier.padding(bottom = 9.dp),
                    )
                }
                if (store.status.tool == "owut" && store.image == null &&
                    (gate3 == GateState.StartHere || gate3 == GateState.Optional || gate3 == GateState.Running)
                ) {
                    if (gate3 == GateState.Optional) {
                        OutlineChip(if (store.busy) "Rebuilding…" else "Rebuild") {
                            if (!store.busy) scope.launch { result = 3 to store.downloadWithTool() }
                        }
                    } else {
                        AccentChip(if (store.busy) "Building…" else "Build and download", busy = store.busy) {
                            if (!store.busy) scope.launch { result = 3 to store.downloadWithTool() }
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                }
                // These bypass owut entirely, so they stay available whatever gate 2 said.
                // FlowRow, not Row: four labels do not fit 360dp, and a Row makes the last
                // one spell itself vertically down the edge rather than wrapping.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (store.status.tool == "owut" && store.image != null) {
                        InlineAction("Download again") {
                            scope.launch { result = 3 to store.downloadWithTool() }
                        }
                    }
                    InlineAction(if (urlOpen) "Hide the URL field" else "Use a URL + sha256") {
                        urlOpen = !urlOpen
                    }
                    InlineAction("Flash a local file") { picker.launch(arrayOf("*/*")) }
                    if (store.image != null) {
                        InlineAction("Discard image", Wrt.Red) {
                            scope.launch { result = 3 to store.discardImage() }
                        }
                    }
                }
                if (urlOpen) {
                    Spacer(Modifier.height(9.dp))
                    ManualUrl(
                        url = url,
                        onUrl = { url = it },
                        sha = sha,
                        onSha = { sha = it },
                        onFetch = {
                            scope.launch {
                                result = 3 to store.downloadFromUrl(url.trim(), sha.trim().ifEmpty { null })
                            }
                        },
                    )
                }
            }

            GateCard(
                index = 4,
                title = "Let sysupgrade check the image",
                state = gate4,
                detail = when (store.image?.testPassed) {
                    true -> "accepted for this device"
                    false -> "refused — see below"
                    null -> "reads the image's metadata and refuses one built for another board"
                },
                message = result?.takeIf { it.first == 4 }?.second,
            ) {
                val img = store.image
                if (img == null) {
                    Text(
                        "$ sysupgrade -T ${Commands.MANUAL_IMAGE.substringBeforeLast('/')}/… · " +
                            if (rebuildOnly) "runs if you rebuild" else "runs once an image is in /tmp",
                        style = mono(9.5f, 500, Wrt.TextDim),
                    )
                } else {
                    CodeLine(Commands.imageTest(img.path))
                    if (img.testOutput.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutputBox(img.testOutput, img.testPassed == false)
                    }
                    Spacer(Modifier.height(9.dp))
                    if (img.testPassed == null) {
                        AccentChip("Check the image", busy = store.busy) {
                            if (!store.busy) scope.launch { result = 4 to store.dryRun() }
                        }
                    } else {
                        InlineAction("Check again") { scope.launch { result = 4 to store.dryRun() } }
                    }
                }
            }

            FlashGate(
                store = store,
                message = result?.takeIf { it.first == 5 }?.second,
                onOpenConfirm = { confirming = true },
            )

            store.progress?.let {
                Text(it, style = mono(10.5f, 500, Wrt.Accent), modifier = Modifier.padding(top = 2.dp))
            }
            store.error?.let {
                Text(it, style = sans(11f, 500, Wrt.Red))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RunningCard(store: FirmwareStore) {
    val check = store.check
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("RUNNING NOW — UBUS CALL SYSTEM BOARD", size = 9.5f, tracking = 0.12)
            FlexSpacer()
            MonoTag(
                store.status.tool.uppercase(),
                color = if (store.status.hasTool) Wrt.Accent else Wrt.TextDim,
                border = if (store.status.hasTool) Wrt.Accent.copy(alpha = 0.5f) else Wrt.BorderInput,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VersionBlock(
                version = store.board?.release?.substringAfter("OpenWrt ")?.ifBlank { null }
                    ?: store.board?.release?.ifBlank { null } ?: "—",
                revision = store.board?.revision.orEmpty(),
                accent = false,
            )
            Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
            // 40a: a "?" until the server has answered. Inventing a version before asking
            // would be the one number on this screen nobody could check.
            if (check == null) {
                VersionBlock(version = "?", revision = "", accent = false)
            } else {
                VersionBlock(
                    version = check.versionTo?.substringBefore(" ")?.ifBlank { null } ?: "—",
                    revision = check.versionTo?.substringAfter(" ", "")?.substringBefore(" ").orEmpty(),
                    accent = !check.sameVersion,
                )
            }
            FlexSpacer()
        }
        Text(
            listOfNotNull(
                check?.profile ?: store.board?.boardName?.ifBlank { null },
                store.status.tmpFreeKb?.let { "${preciseBytes(it * 1024)} free in /tmp" },
            ).joinToString(" · ").ifEmpty { "reading…" },
            style = mono(9.5f, 500, Wrt.TextDim),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One version with its revision under it — the design's before/after pair. */
@Composable
private fun VersionBlock(version: String, revision: String, accent: Boolean) {
    Column {
        Text(
            version,
            style = mono(14f, 600, if (accent) Wrt.Accent else Wrt.TextPrimary),
        )
        if (revision.isNotBlank()) {
            Text(revision, style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * What the upgrade server said, as design 40 puts it: a code box inside gate 2 rather than a
 * card of its own, with owut's verdict quoted underneath in its own words.
 */
@Composable
private fun ServerAnswerBox(check: com.vivekkaushik.wrtpulse.ops.UpgradeCheck, detail: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgCode, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        listOfNotNull(
            check.server?.let { "server" to it },
            check.target?.let { "target" to listOfNotNull(it, check.profile).joinToString(" · ") },
            "version" to if (check.sameVersion) {
                "${check.versionTo ?: "—"} — same version, rebuilt"
            } else {
                "${check.versionFrom ?: "—"} → ${check.versionTo ?: "—"}"
            },
        ).forEach { (key, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(key, style = mono(9.5f, 500, Wrt.TextDim), modifier = Modifier.width(46.dp))
                Text(
                    value,
                    style = mono(9.5f, 500, Wrt.TextSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // owut states its own verdict; the app quotes it rather than restating it.
    check.notes.forEach { note ->
        Text(
            if (check.safe && note.contains("safe", true)) "\"$note\"" else note,
            style = mono(10.5f, 500, if (check.safe) Wrt.TextSecondary else Wrt.Red),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (detail != null) {
        Spacer(Modifier.height(8.dp))
        OutputBox(detail, problem = true)
    }
}

/** Where a gate stands in the sequence — designs 40, 40a and 40b between them show all of these. */
internal enum class GateState { Done, Running, StartHere, Waiting, Optional, Locked, Blocked, Skipped }

private val GateState.badge: String
    get() = when (this) {
        GateState.Done -> "DONE"
        GateState.Running -> "RUNNING"
        GateState.StartHere -> "START HERE"
        GateState.Waiting -> "WAITING"
        GateState.Optional -> "OPTIONAL"
        GateState.Locked -> "LOCKED"
        GateState.Blocked -> "BLOCKED"
        GateState.Skipped -> "SKIPPED"
    }

/** One numbered gate: what it is, where it stands, and — only when it is the next thing — its control. */
@Composable
private fun GateCard(
    index: Int,
    title: String,
    state: GateState,
    detail: String,
    message: String? = null,
    content: @Composable () -> Unit,
) {
    val accent = when (state) {
        GateState.Blocked -> Wrt.Red
        GateState.Running -> Wrt.Amber
        GateState.Done -> Wrt.Accent
        GateState.StartHere, GateState.Optional -> Wrt.Accent
        GateState.Skipped -> Wrt.Amber
        GateState.Waiting, GateState.Locked -> Wrt.TextDim
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            // A ring for anything not yet done — bright on the one that is live, dim on the
            // rest — and a tick once it is behind you.
            Box(
                Modifier.size(14.dp).let {
                    when (state) {
                        GateState.Done -> it.background(accent.copy(alpha = 0.15f), CircleShape)
                        GateState.StartHere, GateState.Running, GateState.Optional ->
                            it.border(2.dp, accent, CircleShape)
                        else -> it.border(1.5.dp, Wrt.DotOff, CircleShape)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    GateState.Done -> Icon(WrtIcons.Check, null, Modifier.size(9.dp), tint = accent)
                    GateState.Blocked -> Icon(WrtIcons.Close, null, Modifier.size(8.dp), tint = accent)
                    else -> Unit
                }
            }
            Text("$index", style = mono(9.5f, 600, accent))
            Column(Modifier.weight(1f)) {
                Text(title, style = sans(12.5f, 650))
                Text(
                    detail,
                    style = mono(10.5f, 500, if (state == GateState.Waiting || state == GateState.Locked) Wrt.TextDim else accent),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MonoTag(state.badge, color = accent, border = accent.copy(alpha = 0.5f), size = 8.5f)
        }
        Spacer(Modifier.height(10.dp))
        content()
        message?.let {
            Text(
                it,
                style = mono(10f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** The design's filled action: a chip, not a full-width button. */
@Composable
private fun AccentChip(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (busy) Wrt.BgDeep else Wrt.Accent, RoundedCornerShape(8.dp))
            .border(1.dp, if (busy) Wrt.BorderInput else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (busy) com.vivekkaushik.wrtpulse.ui.StatusDot(Wrt.Accent, 8.dp, pulse = true)
        Text(text, style = sans(11.5f, 650, if (busy) Wrt.TextSecondary else Wrt.OnAccent))
    }
}

/** 40b's "Rebuild": offered, but outlined rather than filled — it is a choice, not the next step. */
@Composable
private fun OutlineChip(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = sans(11f, 600, Wrt.Accent),
        modifier = Modifier
            .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ManualUrl(
    url: String,
    onUrl: (String) -> Unit,
    sha: String,
    onSha: (String) -> Unit,
    onFetch: () -> Unit,
) {
    Column {
        FieldLabel("IMAGE URL")
        Box {
            FormTextField(url, onUrl)
            if (url.isEmpty()) {
                Text(
                    "https://downloads.openwrt.org/…-sysupgrade.bin",
                    style = mono(11f, 500, Wrt.TextFaint),
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FieldLabel("SHA256 (OPTIONAL, FROM THE sha256sums FILE)")
        Box {
            FormTextField(sha, onSha)
            if (sha.isEmpty()) {
                Text(
                    "64 hex characters",
                    style = mono(11f, 500, Wrt.TextFaint),
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                )
            }
        }
        if (sha.isBlank() && url.isNotBlank()) {
            Text(
                "Without a hash, nothing checks that the download is the file you meant — " +
                    "only that sysupgrade will accept it.",
                style = sans(10f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        GhostButton("Download from this URL", onClick = onFetch)
    }
}

/** Design 40's gate 5: quiet, locked until the four above it are green. */
@Composable
private fun FlashGate(store: FirmwareStore, message: String?, onOpenConfirm: () -> Unit) {
    val block = FirmwareStore.flashBlock(
        backupDone = store.backupDone,
        backupWaived = store.backupWaived,
        image = store.image,
        checkSafe = store.check?.safe,
    )
    GateCard(
        index = 5,
        title = "Flash",
        state = if (block != null) GateState.Locked else GateState.StartHere,
        detail = block?.let { "locked" } ?: "ready — every gate above is green",
        message = message,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Keep settings", style = sans(12f, 600))
                Text(
                    "Off = LAN resets to 192.168.1.1 (re-add the router there) and the SSH host " +
                        "key regenerates — expect a changed-key warning.",
                    style = sans(10f, 400, Wrt.TextDim, lineHeight = 15.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            WToggle(store.keepSettings) { store.keepSettings = !store.keepSettings }
        }
        Spacer(Modifier.height(10.dp))
        if (block != null) {
            Text(
                "$ sysupgrade ${if (store.keepSettings) "" else "-n "}… · " +
                    if (store.check?.sameVersion == true && store.image == null) {
                        "nothing to flash — appears only after a rebuild"
                    } else {
                        "appears when gates 1–4 are green"
                    },
                style = mono(9.5f, 500, Wrt.TextDim),
            )
            Spacer(Modifier.height(6.dp))
            Text(block, style = sans(10.5f, 500, Wrt.TextDim, lineHeight = 15.sp))
        } else {
            store.image?.let { CodeLine(Commands.flash(it.path, store.keepSettings)) }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(Wrt.Red.copy(alpha = 0.12f), RoundedCornerShape(11.dp))
                    .border(1.dp, Wrt.Red.copy(alpha = 0.5f), RoundedCornerShape(11.dp))
                    .clickable(onClick = onOpenConfirm),
                contentAlignment = Alignment.Center,
            ) {
                Text("Flash this image…", style = sans(13f, 650, Wrt.DangerOutlineText))
            }
        }
    }
}

/**
 * Screen 41 — the red zone. Its own screen, not a card: the consequences get the whole
 * display, and the hold is the only way past them.
 */
@Composable
private fun FlashConfirmPanel(store: FirmwareStore, onCancel: () -> Unit, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val version = store.check?.versionTo?.substringBefore(" ") ?: "this image"
    val router = store.board?.hostname?.ifBlank { null } ?: "this router"
    Column(Modifier.fillMaxSize().background(Wrt.DangerBg)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Wrt.DangerCode).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                WrtIcons.ChevronLeft, "back",
                Modifier.size(18.dp).clickable(onClick = onCancel),
                tint = Wrt.DangerText,
            )
            Text("Firmware upgrade", style = sans(15f, 650, Wrt.DangerText), modifier = Modifier.weight(1f))
            Text(
                "GATE 5",
                style = mono(9f, 600, Wrt.Red),
                modifier = Modifier
                    .border(1.dp, Wrt.Red.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(Wrt.Red, CircleShape))
                Text("POINT OF NO RETURN", style = mono(10f, 600, Wrt.Red))
            }
            Text(
                "Flash $version to $router?",
                style = sans(21f, 650, Wrt.DangerText, lineHeight = 26.sp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Red.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                    .background(Wrt.DangerCode, RoundedCornerShape(13.dp))
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                // Every line — including the two the design states outright — comes from the
                // one tested place, so the order and the icons cannot drift from the wording.
                FirmwareStore.flashWarnings(
                    keepSettings = store.keepSettings,
                    check = store.check,
                    lanAddress = store.host,
                    backupName = store.backupFile?.name,
                ).forEach { notice ->
                    val (glyph, tint) = when (notice.kind) {
                        NoticeKind.Downtime -> WrtIcons.Clock to Wrt.Red
                        NoticeKind.Power -> WrtIcons.Lightning to Wrt.Red
                        NoticeKind.Caution -> WrtIcons.Warning to Wrt.Amber
                        NoticeKind.Reassurance -> WrtIcons.Check to Wrt.Green
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(glyph, null, Modifier.padding(top = 2.dp).size(13.dp), tint = tint)
                        Text(notice.text, style = sans(12f, 400, Wrt.DangerBody, lineHeight = 18.sp))
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.DangerCodeBorder, RoundedCornerShape(12.dp))
                    .background(Wrt.DangerCodeBg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                Text("# runs on hold", style = mono(9.5f, 500, Wrt.DangerSub))
                store.image?.let {
                    Text(
                        Commands.flash(it.path, store.keepSettings).substringAfter("; ").substringBefore(")"),
                        style = mono(10f, 500, Wrt.DangerMono, lineHeight = 16.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            HoldToConfirm("Hold to flash firmware") {
                scope.launch { onResult(store.flash()) }
            }
            Text(
                "Hold 3 s to confirm · release to cancel",
                style = sans(10.5f, 500, Wrt.DangerSub),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .border(1.dp, Wrt.DangerSub.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", style = sans(13f, 600, Wrt.DangerBody))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** owut's own progress, with a bar only when it said enough to fill one. */
@Composable
private fun DownloadProgress(store: FirmwareStore) {
    val fraction = store.downloadFraction
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("owut download", style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.weight(1f))
            store.downloadBytes?.let { (done, total) ->
                Text(
                    "${preciseBytes(done)} / ${preciseBytes(total)}",
                    style = mono(10f, 600, Wrt.Accent),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
                .height(3.dp)
                .background(Wrt.ProgressTrack, RoundedCornerShape(2.dp)),
        ) {
            if (fraction != null) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Wrt.Accent, RoundedCornerShape(2.dp)),
                )
            }
        }
        store.downloadLine?.let {
            Text(
                it,
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The design's secondary action: a link in a row, not a full-width button. */
@Composable
private fun InlineAction(text: String, color: Color = Wrt.TextTertiary, onClick: () -> Unit) {
    Text(
        text,
        style = sans(10.5f, 600, color),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

/**
 * Screen 42 — the reboot watch. The command is away and the link is gone; the panel says so,
 * then shows every reconnection attempt until the router answers, and what it answered with.
 */
@Composable
private fun FlashingPanel(store: FirmwareStore) {
    val scope = rememberCoroutineScope()
    var reinstall by remember { mutableStateOf<String?>(null) }
    val end = store.watchEnd
    val version = store.check?.versionTo?.substringBefore(" ") ?: "the image"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(Wrt.Amber, CircleShape))
            Text("FLASHING — DON'T POWER OFF", style = mono(10f, 600, Wrt.Amber))
        }
        Text("Writing $version…", style = sans(22f, 650))
        Text(
            "The command is sent and the link is gone — that is expected. WrtPulse retries SSH " +
                "every ${FirmwareStore.WATCH_INTERVAL_MS / 1000} s until " +
                "${store.board?.hostname?.ifBlank { null } ?: "the router"} answers.",
            style = sans(12.5f, 400, Wrt.TextSecondary, lineHeight = 19.sp),
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            val image = store.image
            WatchStep(
                state = StepState.Done,
                text = "Image on the router",
                detail = image?.let { "${it.path} · ${it.sizeBytes?.let(::preciseBytes) ?: ""}" },
            )
            WatchStep(state = StepState.Done, text = "sysupgrade started · SSH session closed · that is normal")
            WatchStep(
                state = when (end) {
                    null -> StepState.Running
                    WatchEnd.GaveUp -> StepState.Failed
                    else -> StepState.Done
                },
                text = when (end) {
                    null -> "Waiting for the router — retrying every ${FirmwareStore.WATCH_INTERVAL_MS / 1000} s"
                    WatchEnd.GaveUp -> "No answer after ${FirmwareStore.WATCH_LIMIT_S / 60} minutes"
                    else -> "The router answered"
                },
            )
            val before = store.beforeBoard?.let {
                listOf(it.release, it.revision).filter { s -> s.isNotBlank() }.joinToString(" ")
            } ?: "?"
            val after = store.afterBoard?.let {
                listOf(it.release, it.revision).filter { s -> s.isNotBlank() }.joinToString(" ")
            }
            WatchStep(
                state = when (end) {
                    WatchEnd.Back -> StepState.Done
                    WatchEnd.Unchanged -> StepState.Failed
                    else -> StepState.Pending
                },
                text = "Back — re-read version:",
                detail = "$before → ${after ?: "?"}" +
                    if (end == WatchEnd.Unchanged) " · UNCHANGED" else "",
                detailProblem = end == WatchEnd.Unchanged,
            )
            if (store.userPackages.isNotEmpty()) {
                WatchStep(
                    state = if (store.reinstallOutput != null) StepState.Done else StepState.Pending,
                    text = "Reinstall ${store.userPackages.size} packages",
                    detail = "opkg install ${store.userPackages.joinToString(" ")}",
                )
            }
        }
        if (store.watchLog.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 11.dp),
            ) {
                store.watchLog.takeLast(4).forEach {
                    Text(
                        it,
                        style = mono(10f, 500, if (end == WatchEnd.GaveUp) Wrt.DangerMono else Wrt.TextSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (end == null) {
                    // The caret says the log is still being written to.
                    Box(Modifier.padding(top = 4.dp).size(width = 7.dp, height = 12.dp).background(Wrt.Accent))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
                .background(Wrt.BgCard, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Typical: 2–4 minutes · elapsed", style = sans(12f, 400, Wrt.TextSecondary), modifier = Modifier.weight(1f))
            Text(FirmwareStore.elapsedLabel(store.watchElapsedS), style = mono(11.5f, 600))
        }
        when (end) {
            WatchEnd.Back -> {
                NoteCard(
                    "Back on ${store.afterBoard?.release ?: "the new image"}." +
                        (store.backupFile?.let { " Your backup is on this phone as ${it.name}." } ?: "")
                )
                if (store.userPackages.isNotEmpty() && store.reinstallOutput == null) {
                    PrimaryButton(if (store.busy) "Reinstalling…" else "Reinstall ${store.userPackages.size} packages") {
                        if (!store.busy) scope.launch { reinstall = store.reinstallPackages() }
                    }
                }
                reinstall?.let {
                    Text(it, style = mono(10f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent))
                }
                store.reinstallOutput?.let { OutputBox(it, problem = reinstall?.startsWith("Failed") == true) }
            }
            WatchEnd.Unchanged -> ProblemCard(
                "The router answered with the same version it had before. The flash did not take — " +
                    "check the image and sysupgrade's output before trying again."
            )
            WatchEnd.NewKey -> NoteCard(
                "The router is back with an SSH key this phone does not know" +
                    (if (!store.keepSettings) " — settings were wiped, so it is at 192.168.1.1" else "") +
                    ". Reconnect from the router list; its fingerprint is shown for you to accept."
            )
            WatchEnd.GaveUp -> NoteCard(
                "No answer after ${FirmwareStore.WATCH_LIMIT_S / 60} minutes. The router may still be " +
                    "flashing — don't power it off. Check its lights, then reconnect from the router list."
            )
            null -> Text(
                "No answer after ${FirmwareStore.WATCH_LIMIT_S / 60} minutes? The router may still be " +
                    "flashing — don't power it off. Check its lights, then reconnect from the router list.",
                style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 16.sp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Where one step of the watch stands. */
private enum class StepState { Done, Running, Pending, Failed }

@Composable
private fun WatchStep(
    state: StepState,
    text: String,
    detail: String? = null,
    detailProblem: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Done is a tick, the one in progress is a bright ring, the rest are dim rings —
        // so the eye lands on where the watch actually is.
        val tone = when (state) {
            StepState.Failed -> Wrt.Red
            StepState.Done -> Wrt.Green
            StepState.Running -> Wrt.Accent
            StepState.Pending -> Wrt.DotOff
        }
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(14.dp)
                .border(if (state == StepState.Running) 2.dp else 1.5.dp, tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                StepState.Done -> Icon(WrtIcons.Check, null, Modifier.size(9.dp), tint = tone)
                StepState.Failed -> Icon(WrtIcons.Close, null, Modifier.size(8.dp), tint = tone)
                else -> Unit
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text,
                style = sans(
                    12.5f, 400,
                    when (state) {
                        StepState.Pending -> Wrt.TextDim
                        StepState.Failed -> Wrt.Red
                        else -> Wrt.TextPrimary
                    },
                    lineHeight = 18.sp,
                ),
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = mono(11f, 500, if (detailProblem) Wrt.Red else Wrt.TextDim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** An image a phone can hold in memory; anything bigger is not a sysupgrade image. */
private const val MAX_LOCAL_IMAGE_BYTES = 256L * 1024 * 1024

/** Name and bytes of a picked document, or null. Reads at most [limit] bytes. */
private fun readDocumentBytes(context: android.content.Context, uri: android.net.Uri, limit: Long): Pair<String, ByteArray>? =
    runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "image.bin"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= limit) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: return null
        name to bytes
    }.getOrNull()

@Composable
internal fun Card(border: Color = Wrt.BorderCard, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, border, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) { content() }
}

/**
 * The app's standing promise: the command is visible before it runs.
 *
 * The `$` is part of it — this is a shell line, and the design's screen 38 is named for the
 * habit. [onView] adds "· view command" for the cases where the single line shown is a
 * summary of a longer sequence.
 */
@Composable
internal fun CodeLine(command: String, onView: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgCode, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$ " + command,
            style = mono(9.5f, 500, Wrt.TextSecondary),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onView != null) {
            Text(
                "· view command",
                style = mono(9.5f, 600, Wrt.Accent),
                modifier = Modifier.clickable(onClick = onView),
            )
        }
    }
}

@Composable
internal fun OutputBox(text: String, problem: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .border(1.dp, if (problem) Wrt.Red.copy(alpha = 0.35f) else Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgCode, RoundedCornerShape(9.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(text, style = mono(9.5f, 500, if (problem) Wrt.DangerMono else Wrt.TextSecondary))
    }
}
