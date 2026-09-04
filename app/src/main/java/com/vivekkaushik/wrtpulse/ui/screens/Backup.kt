package com.vivekkaushik.wrtpulse.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.FileProvider
import com.vivekkaushik.wrtpulse.data.BackupStore
import com.vivekkaushik.wrtpulse.data.LocalBackup
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.RevealAction
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.SwipeToReveal
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Backup & restore. The top half is reads: what a backup carries, take one, the archives
 * already on this phone. The bottom half is the restore, gated the same way the flash is —
 * see [BackupStore.restoreBlock], which is also what the screen prints when a gate is unmet.
 */
@Composable
fun BackupScreen(
    store: BackupStore?,
    latencyMs: Int,
    onBack: () -> Unit,
    /** The "snapshot before every Apply" switch — persisted by the app, not the store. */
    onAutoBackup: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Keyed by section: a result belongs next to the button that caused it.
    var result by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showFiles by remember { mutableStateOf(false) }
    var addingPath by remember { mutableStateOf(false) }
    var showCommand by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<LocalBackup?>(null) }

    // Re-read on every entry: the firmware gate writes into the same directory.
    LaunchedEffect(store) { store?.load() }

    // The picker hands back a content: URI. The bytes are read off the main thread and judged
    // by the store before the router hears about any of it.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && store != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    readDocument(context, uri, BackupStore.MAX_RESTORE_BYTES + 1)
                }
                result = 3 to (picked?.let { (name, bytes) -> store.stage(name, bytes) }
                    ?: "Failed: couldn't read that file.")
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val backup = saving
        saving = null
        if (uri != null && backup != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(backup.file.readBytes()) } != null
                    }.getOrDefault(false)
                }
                result = 2 to if (ok) "Saved a copy of ${backup.name}" else "Failed: couldn't write the copy."
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Backup & restore", onBack) {
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
                Text("Connect to a router to manage backups.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        if (store.restoring) {
            RestoringPanel(store)
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The design assumes one router; this app has several, so the identity gets a line
            // of its own above the card, which stays exactly as drawn.
            Text(
                listOfNotNull(
                    store.board?.hostname?.ifBlank { null } ?: store.host,
                    store.board?.release?.ifBlank { null },
                    store.lastBackup?.let { "last backup ${BackupStore.ageLabel(it.createdEpoch)}" },
                ).joinToString(" · "),
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Design 38's order: back up, what it holds, what is already here, the switch.
            Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Back up now", style = sans(13.5f, 650))
                        Text(
                            "Pulled to this phone over SSH, then removed from the router",
                            style = sans(10.5f, 400, Wrt.TextSecondary, lineHeight = 15.sp),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    AccentChip(
                        text = if (store.busy) "Working…" else "Create",
                        busy = store.busy,
                    ) { if (!store.busy) scope.launch { result = 1 to store.backUp() } }
                }
                Spacer(Modifier.height(10.dp))
                CodeLine("sysupgrade -b ${Commands.BACKUP_FILE}") { showCommand = true }
                ResultLine(result?.takeIf { it.first == 1 }?.second)
            }
            // Screen 38 corrected: a backup CONTAINS the host keys — it does not exclude them.
            NoteCard(
                "Contains this router's SSH host keys. Kept in app-private storage; leaves the " +
                    "phone only through a share sheet you tap."
            )

            SectionLabel("WHAT'S INCLUDED — SYSUPGRADE -L", tracking = 0.14)
            IncludeCard(
                store = store,
                showFiles = showFiles,
                onToggleFiles = { showFiles = !showFiles },
                onAdd = { addingPath = true },
                onRemove = { path -> scope.launch { result = 4 to store.removeIncludePath(path) } },
                message = result?.takeIf { it.first == 4 }?.second,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("ON THIS PHONE — ALL ROUTERS", tracking = 0.14)
                FlexSpacer()
                DashedChip("Import file") { picker.launch(arrayOf("*/*")) }
            }
            // The design wraps the whole list in one card: rows divided inside it, not a
            // card each and not floating on the page.
            Card {
                store.refusedImport?.let { (name, why) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(name, style = mono(11.5f, 500, Wrt.Red), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "refused — $why",
                            style = sans(10f, 400, Wrt.Red, lineHeight = 15.sp),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    if (store.local.isNotEmpty()) com.vivekkaushik.wrtpulse.ui.HorizontalHairline(Wrt.BorderRow)
                }
                if (store.local.isEmpty() && store.refusedImport == null) {
                    Text(
                        "No backups yet. The first one appears here as soon as it is taken.",
                        style = sans(10.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                store.local.forEachIndexed { index, backup ->
                    BackupRow(
                        backup = backup,
                        otherRouter = backup.host != store.safeHost,
                        confirmDelete = confirmDelete == backup.name,
                        onShare = { shareBackup(context, backup.file) },
                        onSave = { saving = backup; saver.launch(backup.name) },
                        onRestore = { result = 3 to store.stageLocal(backup) },
                        onDelete = {
                            if (confirmDelete == backup.name) {
                                result = 2 to store.delete(backup)
                                confirmDelete = null
                            } else {
                                confirmDelete = backup.name
                            }
                        },
                    )
                    if (index < store.local.lastIndex) {
                        com.vivekkaushik.wrtpulse.ui.HorizontalHairline(Wrt.BorderRow)
                    }
                }
            }
            ResultLine(result?.takeIf { it.first == 2 }?.second)

            ToggleCard {
                ToggleRow(
                    title = "Auto-backup before changes",
                    body = "Snapshot to this phone before every Apply · keeps the last " +
                        "${BackupStore.AUTO_KEEP}, oldest deleted",
                    checked = store.autoBackup,
                    divider = false,
                ) { onAutoBackup(!store.autoBackup) }
            }

            SectionLabel("RESTORE", color = Wrt.Red, tracking = 0.14)
            RestoreCard(
                store = store,
                message = result?.takeIf { it.first == 3 }?.second,
                onPick = { picker.launch(arrayOf("*/*")) },
                onResult = { result = 3 to it },
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

    // "view command": the one line on the card is a summary — this is the whole sequence.
    SheetHost(visible = showCommand, onDismiss = { showCommand = false }) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 22.dp)) {
            Text("What a backup runs", style = sans(16f, 650), modifier = Modifier.padding(top = 14.dp))
            Text(
                "Three commands on the router, in this order. The archive is read back over the " +
                    "same SSH channel — there is no second connection and nothing is installed.",
                style = sans(12f, 400, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutputBox(
                listOf(
                    "# 1. write the archive to the router's RAM",
                    Commands.BACKUP_CREATE,
                    "",
                    "# 2. encode and read it back (base64, else hexdump — whichever exists)",
                    Commands.BACKUP_READ,
                    "",
                    "# 3. take the router's copy away again",
                    Commands.BACKUP_CLEANUP,
                ).joinToString("\n"),
                problem = false,
            )
            Spacer(Modifier.height(14.dp))
            GhostButton("Close") { showCommand = false }
        }
    }

    if (addingPath && store != null) {
        TextEntryDialog(
            title = "Add a path to every backup",
            hint = "/root/scripts",
            validate = { Commands.safeBackupPath(it) },
            error = "An absolute path, no '..', nothing a shell would read.",
            onDismiss = { addingPath = false },
            onDone = { path ->
                addingPath = false
                scope.launch { result = 4 to store.addIncludePath(path) }
            },
        )
    }
}

/**
 * Design 38's "what's included": sysupgrade's own list, with the user's extra paths from
 * /etc/sysupgrade.conf editable in place. Default rows are read-only — they are what
 * sysupgrade carries regardless — and only custom rows get the ×.
 */
@Composable
private fun IncludeCard(
    store: BackupStore,
    showFiles: Boolean,
    onToggleFiles: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    message: String?,
) {
    Card {
        Text(
            if (store.files.isEmpty() && !store.loaded) "reading…"
            else "${store.files.size} files · /etc/config and everything sysupgrade.conf names",
            style = sans(11f, 600),
        )
        Row(
            Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("/etc/config/*", style = mono(10.5f, 500), modifier = Modifier.weight(1f))
            Text("default, read-only", style = mono(9.5f, 500, Wrt.TextDim))
        }
        store.includeList.forEach { path ->
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(path, style = mono(10.5f, 500), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("custom", style = mono(9.5f, 500, Wrt.TextDim))
                Icon(
                    WrtIcons.Close, "remove",
                    Modifier.size(12.dp).clickable { onRemove(path) },
                    tint = Wrt.TextDim,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DashedChip("Add path", onClick = onAdd)
            FlexSpacer()
            Text(
                "$ cat >> ${Commands.SYSUPGRADE_CONF}",
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
            )
        }
        if (store.files.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.clickable(onClick = onToggleFiles),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (showFiles) WrtIcons.ChevronUp else WrtIcons.ChevronDown,
                    null, Modifier.size(12.dp), tint = Wrt.TextTertiary,
                )
                Text(
                    if (showFiles) "Hide the full list" else "Show the full list",
                    style = sans(11f, 600, Wrt.TextTertiary),
                )
                FlexSpacer()
                Text(Commands.BACKUP_LIST.substringBefore(" 2>"), style = mono(9.5f, 500, Wrt.TextDim))
            }
            if (showFiles) {
                Spacer(Modifier.height(8.dp))
                OutputBox(store.files.joinToString("\n"), problem = false)
            }
        }
        ResultLine(message)
    }
}

/**
 * One archive on the phone, as design 38 draws it: a plain row with a divider, the name in
 * mono and one action on the right. Share, Save copy and Delete live behind the swipe — the
 * same place every other list in the app keeps its secondary actions.
 */
@Composable
private fun BackupRow(
    backup: LocalBackup,
    otherRouter: Boolean,
    confirmDelete: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeToReveal(
        actions = listOf(
            RevealAction("Share", WrtIcons.ShareUp, Wrt.TextTertiary, onShare),
            RevealAction("Save", WrtIcons.Backup, Wrt.TextTertiary, onSave),
            RevealAction("Delete", WrtIcons.Trash, Wrt.Red, onDelete),
        ),
        resetKey = backup.name,
        revealWidth = 64.dp,
        // The row is inside a card, so the ground it composites over is the card's.
        base = Wrt.BgCard,
    ) { swipe ->
        Row(
            swipe.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    backup.name,
                    style = mono(11.5f, 500),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        stamp(backup.createdEpoch),
                        preciseBytes(backup.bytes),
                        backup.release,
                        backup.hostname?.let { if (otherRouter) "from $it" else it },
                    ).joinToString(" · "),
                    style = sans(10f, 400, if (otherRouter) Wrt.Amber else Wrt.TextDim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (confirmDelete) {
                ActionChip("Tap again to delete", color = Wrt.Red, onClick = onDelete)
            } else {
                ActionChip("Restore", color = Wrt.Accent, onClick = onRestore)
            }
        }
    }
    com.vivekkaushik.wrtpulse.ui.HorizontalHairline(Wrt.BorderRow)
}

/** The design's filled action: a chip, not a full-width button. */
@Composable
private fun AccentChip(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (busy) Wrt.BgDeep else Wrt.Accent, RoundedCornerShape(9.dp))
            .border(1.dp, if (busy) Wrt.BorderInput else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (busy) com.vivekkaushik.wrtpulse.ui.StatusDot(Wrt.Accent, 8.dp, pulse = true)
        Text(text, style = sans(11.5f, 650, if (busy) Wrt.TextSecondary else Wrt.OnAccent))
    }
}

@Composable
private fun ActionChip(text: String, color: Color = Wrt.TextSecondary, onClick: () -> Unit) {
    Text(
        text,
        style = sans(10.5f, 600, color),
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The last card. Everything here is about making the consequences legible before the hold. */
@Composable
private fun RestoreCard(store: BackupStore, message: String?, onPick: () -> Unit, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val candidate = store.candidate
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
            .background(Wrt.Red.copy(alpha = 0.04f), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        if (candidate == null) {
            Text(
                "Restoring unpacks a backup over the router's current settings and reboots it. " +
                    "Pick one from the list above, or a .tar.gz from anywhere on this phone.",
                style = sans(11f, 500, Wrt.DangerBody),
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                "Choose a file on this phone…",
                border = Wrt.Red.copy(alpha = 0.4f),
                textColor = Wrt.DangerOutlineText,
                onClick = onPick,
            )
        } else {
            Text("Restore ${candidate.source}?", style = sans(13.5f, 650), maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Design 39: the three gates as a checklist that fills in. Each is a real step with
            // a real router answer, not a progress bar.
            Spacer(Modifier.height(10.dp))
            GateLine(
                done = true,
                text = "Phone read the archive · ${candidate.fileCount} files" +
                    (candidate.hostname?.let { " · $it" } ?: ""),
            )
            GateLine(
                done = candidate.onRouter,
                text = if (candidate.onRouter) "Router received it · sha256 ${candidate.sha256.take(4)}…${candidate.sha256.takeLast(4)} matches"
                else "Router received it · not yet sent",
            )
            GateLine(
                done = candidate.routerListing != null,
                failed = candidate.routerRefusal != null,
                text = when {
                    candidate.routerRefusal != null -> "Router listed it · tar refused: ${candidate.routerRefusal}"
                    candidate.routerListing != null ->
                        "Router listed it · tar -tzf · ${candidate.routerListing.firstOrNull() ?: ""}… · ${candidate.routerListing.size} paths"
                    else -> "Router listed it · waits for the upload"
                },
            )
            val inside = listOfNotNull(
                candidate.hostname?.let { "hostname $it" },
                candidate.lanAddress?.let { "lan $it" },
            )
            if (inside.isNotEmpty()) {
                Text(
                    "inside the archive: ${inside.joinToString(" · ")}",
                    style = mono(9.5f, 500, Wrt.DangerMono),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            BackupStore.restoreWarnings(candidate, store.board, store.host).forEach {
                Text(it, style = sans(10.5f, 500, Wrt.AmberText), modifier = Modifier.padding(top = 9.dp))
            }

            Spacer(Modifier.height(12.dp))
            if (!candidate.onRouter) {
                CodeLine(Commands.RESTORE_RECEIVE)
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    if (store.busy) "Working…" else "Send to the router",
                    border = Wrt.Red.copy(alpha = 0.4f),
                    textColor = Wrt.DangerOutlineText,
                ) {
                    if (!store.busy) scope.launch { onResult(store.upload()) }
                }
            } else {
                CodeLine(Commands.RESTORE_LIST)
                candidate.routerListing?.let {
                    Spacer(Modifier.height(8.dp))
                    OutputBox(it.joinToString("\n"), problem = false)
                }
                candidate.routerRefusal?.let {
                    Spacer(Modifier.height(8.dp))
                    OutputBox(it, problem = true)
                }
            }

            Spacer(Modifier.height(12.dp))
            val block = BackupStore.restoreBlock(candidate)
            if (block != null) {
                Text(block, style = sans(11.5f, 500, Wrt.DangerSub))
            } else {
                // Design 39: the current config goes to the phone before anything is overwritten.
                Text(
                    "Current config is saved to this phone first · " +
                        com.vivekkaushik.wrtpulse.data.ConfigArchive.fileName(store.host).substringBeforeLast('-') + "-<now>.tar.gz",
                    style = sans(10.5f, 500, Wrt.AmberText),
                )
                Spacer(Modifier.height(10.dp))
                CodeLine("${Commands.RESTORE_APPLY.substringBefore(" 2>")} && reboot")
                Spacer(Modifier.height(10.dp))
                HoldToConfirm("Hold to restore & reboot") {
                    scope.launch { onResult(store.restore()) }
                }
                Text(
                    "Hold 3 s to confirm · release to cancel",
                    style = sans(10f, 500, Wrt.DangerSub),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            store.restoreOutput?.let {
                Spacer(Modifier.height(8.dp))
                OutputBox(it, problem = true)
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Discard", textColor = Wrt.TextSecondary) {
                scope.launch { onResult(store.discard()) }
            }
        }
        message?.let {
            Text(
                it,
                style = mono(10f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Amber),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** After the reboot command is away there is nothing to do but say so plainly. */
@Composable
private fun RestoringPanel(store: BackupStore) {
    val candidate = store.candidate
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(WrtIcons.Reboot, null, Modifier.size(40.dp), tint = Wrt.Amber)
        Text(
            "Restored — rebooting",
            style = sans(16f, 700, Wrt.Amber),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "The archive is unpacked and the router is restarting with it. The connection has " +
                "already dropped — that is expected. It usually takes a minute.",
            style = sans(12f, 500, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 10.dp),
        )
        candidate?.lanAddress?.takeIf { it != store.host }?.let {
            Text(
                "The restored config puts the LAN on $it. If that differs from ${store.host}, add the " +
                    "router again at the new address from the router list.",
                style = sans(11f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        store.restoreSnapshot?.let {
            Text(
                "The config it replaced is on this phone as ${it.name}",
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

/** One gate of the restore checklist: a tick, a cross, or an empty ring. */
@Composable
private fun GateLine(done: Boolean, text: String, failed: Boolean = false) {
    Row(
        Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tone = when {
            failed -> Wrt.Red
            done -> Wrt.Green
            else -> Wrt.TextDim
        }
        Box(
            Modifier.size(14.dp).border(1.5.dp, tone, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (done && !failed) Icon(WrtIcons.Check, null, Modifier.size(9.dp), tint = tone)
            if (failed) Icon(WrtIcons.Close, null, Modifier.size(8.dp), tint = tone)
        }
        Text(text, style = mono(10f, 500, if (failed) Wrt.Red else if (done) Wrt.TextSecondary else Wrt.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ResultLine(text: String?) {
    text?.let {
        Text(
            it,
            style = mono(10f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent),
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")

private fun stamp(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(STAMP)

/**
 * Name and bytes of a picked document, or null. Reads at most [limit] bytes, so a wrong pick
 * cannot fill memory — the store then refuses anything over its own cap.
 */
private fun readDocument(context: Context, uri: Uri, limit: Long): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "backup.tar.gz"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() <= limit) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } ?: return null
    name to bytes
}.getOrNull()

/** Hands the archive to whatever the user wants to keep it in. */
internal fun shareBackup(context: Context, file: File?) {
    if (file == null || !file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Save the router backup"))
}
