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
        FormTopBar("Firmware upgrade", onBack) {
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
                Text("Connect to a router to manage firmware.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }
        if (store.flashing) {
            FlashingPanel(store)
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
            store.check?.let { CheckCard(it, store.checkDetail) }

            SectionLabel("FIVE GATES, ONE SCREEN", tracking = 0.14)

            GateCard(
                index = 1,
                title = "Back up the configuration",
                done = store.backupDone,
                running = store.busy && store.progress?.contains("archive", true) == true,
                detail = when {
                    store.backupFile != null -> store.backupFile!!.name
                    store.backupWaived -> "skipped — nothing to restore from"
                    else -> "sysupgrade -b, pulled onto this phone"
                },
                warn = store.backupWaived && !store.backupDone,
                message = result?.takeIf { it.first == 1 }?.second,
            ) {
                if (store.backupDone) {
                    GhostButton("Share the backup") { shareBackup(context, store.backupFile) }
                } else {
                    PrimaryButton("Back up now") {
                        scope.launch { result = 1 to store.backUp(File(context.filesDir, "backups")) }
                    }
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Skip the backup", textColor = Wrt.TextSecondary) {
                        store.waiveBackup()
                        result = 1 to "Continuing without a backup"
                    }
                }
            }

            GateCard(
                index = 2,
                title = "Ask the upgrade server",
                done = store.check != null,
                running = store.busy && store.progress?.contains("server", true) == true,
                detail = when {
                    store.status.tool == "owut" -> "owut check"
                    store.status.hasTool -> "${store.status.tool} — not driven by this app yet"
                    else -> "no owut on this router; use a download URL below"
                },
                warn = store.check?.safe == false,
                message = result?.takeIf { it.first == 2 }?.second,
            ) {
                // Design 40: the packages a plain flash would lose, by name.
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
                if (store.status.tool == "owut") {
                    GhostButton(if (store.check == null) "Check" else "Check again") {
                        scope.launch { result = 2 to store.runCheck() }
                    }
                }
            }

            GateCard(
                index = 3,
                title = "Build and download",
                done = store.image != null,
                running = store.busy && (store.progress?.contains("ownload", true) == true ||
                    store.progress?.contains("Sending", true) == true),
                detail = store.image?.let { img ->
                    listOfNotNull(
                        img.path.substringAfterLast('/'),
                        img.sizeBytes?.let { preciseBytes(it) },
                        img.sha256?.take(12),
                    ).joinToString(" · ")
                } ?: "built by the server with your installed packages",
                message = result?.takeIf { it.first == 3 }?.second,
            ) {
                // Design 40: RAM is the gate, and the numbers are the router's.
                store.status.tmpFreeKb?.let { free ->
                    Text(
                        "/tmp free ${preciseBytes(free * 1024)}" +
                            (store.image?.sizeBytes?.let { " · image needs ${preciseBytes(it)}" } ?: ""),
                        style = mono(9.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (store.status.tool == "owut") {
                    PrimaryButton(if (store.image == null) "Build and download" else "Download again") {
                        scope.launch { result = 3 to store.downloadWithTool() }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                GhostButton("Flash a local file", textColor = Wrt.TextSecondary) {
                    picker.launch(arrayOf("*/*"))
                }
                Spacer(Modifier.height(6.dp))
                if (store.image != null) {
                    GhostButton("Discard image", textColor = Wrt.TextSecondary) {
                        scope.launch { result = 3 to store.discardImage() }
                    }
                    Spacer(Modifier.height(6.dp))
                }
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

            GateCard(
                index = 4,
                title = "Let sysupgrade check the image",
                done = store.image?.testPassed == true,
                running = store.busy && store.progress?.contains("sysupgrade", true) == true,
                locked = store.image == null,
                detail = when (store.image?.testPassed) {
                    true -> "accepted for this device"
                    false -> "refused — see below"
                    null -> if (store.image == null) "runs when the image lands" else "sysupgrade -T reads the image's metadata"
                },
                warn = store.image?.testPassed == false,
                message = result?.takeIf { it.first == 4 }?.second,
            ) {
                store.image?.let { img ->
                    CodeLine(Commands.imageTest(img.path))
                    if (img.testOutput.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutputBox(img.testOutput, img.testPassed == false)
                    }
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Check the image") { scope.launch { result = 4 to store.dryRun() } }
                }
            }

            SectionLabel("GATE 5 · POINT OF NO RETURN", color = Wrt.Red, tracking = 0.14)
            FlashCard(store, message = result?.takeIf { it.first == 5 }?.second) {
                result = 5 to it
            }

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
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("RUNNING NOW", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag(
                store.status.tool.uppercase(),
                color = if (store.status.hasTool) Wrt.Accent else Wrt.TextDim,
                border = if (store.status.hasTool) Wrt.Accent.copy(alpha = 0.5f) else Wrt.BorderInput,
            )
        }
        Text(
            store.board?.summary?.ifBlank { null } ?: "reading…",
            style = sans(12.5f, 600),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(
                store.check?.target,
                store.check?.profile,
                // /tmp is RAM and it is where the image has to live, so it leads here rather
                // than the overlay space the packages screen cares about.
                store.status.tmpFreeKb?.let { "${preciseBytes(it * 1024)} free in /tmp" },
            ).joinToString(" · ").ifEmpty { "—" },
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CheckCard(check: com.vivekkaushik.wrtpulse.ops.UpgradeCheck, detail: String?) {
    Card(border = if (check.safe) Wrt.BorderCard else Wrt.Red.copy(alpha = 0.4f)) {
        SectionLabel("THE SERVER SAYS", size = 9.5f, tracking = 0.14)
        Text(
            // The same version with fresher packages is the normal owut case, and calling
            // that "up to date" would hide the only reason to run an attended sysupgrade.
            if (check.sameVersion) {
                "${check.versionTo ?: "—"} — same version, rebuilt"
            } else {
                "${check.versionFrom ?: "—"} → ${check.versionTo ?: "—"}"
            },
            style = sans(13f, 650, if (check.sameVersion) Wrt.TextPrimary else Wrt.Accent),
            modifier = Modifier.padding(top = 8.dp),
        )
        check.notes.forEach { note ->
            Text(
                note,
                style = sans(10.5f, 500, if (check.safe) Wrt.TextDim else Wrt.Red),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            OutputBox(detail, problem = true)
        }
    }
}

/** One numbered gate: what it is, whether it's met, and the control that meets it. */
@Composable
private fun GateCard(
    index: Int,
    title: String,
    done: Boolean,
    detail: String,
    warn: Boolean = false,
    running: Boolean = false,
    locked: Boolean = false,
    message: String? = null,
    content: @Composable () -> Unit,
) {
    val accent = when {
        warn -> Wrt.Red
        running -> Wrt.Amber
        done -> Wrt.Accent
        else -> Wrt.TextDim
    }
    // Design 40's state badge: every gate visible at once, each saying where it is.
    val badge = when {
        warn -> "BLOCKED"
        running -> "RUNNING"
        done -> "DONE"
        locked -> "LOCKED"
        else -> "WAITING"
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(20.dp).background(accent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done && !warn) {
                    Icon(WrtIcons.Check, null, Modifier.size(11.dp), tint = accent)
                } else {
                    Text("$index", style = mono(9.5f, 700, accent))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = sans(12.5f, 600))
                Text(
                    detail,
                    style = mono(9.5f, 500, accent),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MonoTag(badge, color = accent, border = accent.copy(alpha = 0.5f), size = 8f)
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

@Composable
private fun ManualUrl(
    url: String,
    onUrl: (String) -> Unit,
    sha: String,
    onSha: (String) -> Unit,
    onFetch: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        GhostButton("Use a download URL instead", textColor = Wrt.TextSecondary) { open = true }
        return
    }
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

/** The last card. Everything here is about making the consequences legible before the hold. */
@Composable
private fun FlashCard(store: FirmwareStore, message: String?, onResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val block = FirmwareStore.flashBlock(
        backupDone = store.backupDone,
        backupWaived = store.backupWaived,
        image = store.image,
        checkSafe = store.check?.safe,
    )
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
            .background(Wrt.Red.copy(alpha = 0.04f), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep settings", style = sans(13f, 600))
                Text(
                    if (store.keepSettings) "config carried across (sysupgrade)"
                    else "config discarded (sysupgrade -n)",
                    style = mono(9.5f, 500, if (store.keepSettings) Wrt.TextDim else Wrt.Red),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            WToggle(store.keepSettings) { store.keepSettings = !store.keepSettings }
        }

        FirmwareStore.flashWarnings(
            keepSettings = store.keepSettings,
            check = store.check,
            lanAddress = null,
        ).forEach {
            Text(it, style = sans(10.5f, 500, Wrt.AmberText), modifier = Modifier.padding(top = 9.dp))
        }

        Spacer(Modifier.height(12.dp))
        if (block != null) {
            MonoTag("LOCKED", color = Wrt.DangerSub, border = Wrt.DangerSub.copy(alpha = 0.5f), size = 8f)
            Text(block, style = sans(11.5f, 500, Wrt.DangerSub), modifier = Modifier.padding(top = 8.dp))
        } else {
            // Design 41: the consequences, then the command, then the hold.
            Text(
                "Flash ${store.check?.versionTo ?: "this image"} to ${store.board?.hostname?.ifBlank { null } ?: "this router"}?",
                style = sans(13.5f, 650),
            )
            listOf(
                "Router offline about 4 minutes — every client drops.",
                "Power loss while flashing can brick the device. Don't unplug it.",
                if (store.keepSettings) {
                    "Settings kept" + (store.backupFile?.let { " · backup saved to this phone as ${it.name}" } ?: "")
                } else {
                    "Settings wiped · router comes back at 192.168.1.1 with a new SSH key."
                },
            ).forEach {
                Text(it, style = sans(11f, 500, Wrt.DangerBody), modifier = Modifier.padding(top = 7.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("# runs on hold", style = mono(9.5f, 500, Wrt.DangerSub))
            val image = store.image
            if (image != null) CodeLine(Commands.flash(image.path, store.keepSettings))
            Spacer(Modifier.height(10.dp))
            HoldToConfirm("Hold to flash firmware") {
                scope.launch { onResult(store.flash()) }
            }
            Text(
                "Hold 3 s to confirm · release to cancel",
                style = sans(10f, 500, Wrt.DangerSub),
                modifier = Modifier.padding(top = 8.dp),
            )
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

/**
 * Screen 42 — the reboot watch. The command is away and the link is gone; the panel says so,
 * then shows every reconnection attempt until the router answers, and what it answered with.
 */
@Composable
private fun FlashingPanel(store: FirmwareStore) {
    val scope = rememberCoroutineScope()
    var reinstall by remember { mutableStateOf<String?>(null) }
    val end = store.watchEnd
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(WrtIcons.Firmware, null, Modifier.size(26.dp), tint = Wrt.Amber)
            Column {
                Text("FLASHING — DON'T POWER OFF", style = mono(11f, 700, Wrt.Amber))
                Text(
                    "Writing ${store.check?.versionTo ?: "the image"}…",
                    style = sans(15f, 650),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            "The command is sent and the link is gone — that is expected. WrtPulse retries SSH " +
                "every ${FirmwareStore.WATCH_INTERVAL_MS / 1000} s until " +
                "${store.board?.hostname?.ifBlank { null } ?: "the router"} answers.",
            style = sans(12f, 500, Wrt.TextSecondary, lineHeight = 18.sp),
        )
        Card {
            val image = store.image
            WatchStep(true, "Image on the router" + (image?.let { " · ${it.path} · ${it.sizeBytes?.let(::preciseBytes) ?: ""}" } ?: ""))
            WatchStep(true, "sysupgrade started · SSH session closed · that is normal")
            WatchStep(
                done = end != null && end != WatchEnd.GaveUp,
                text = when (end) {
                    null -> "Waiting for the router — retrying every ${FirmwareStore.WATCH_INTERVAL_MS / 1000} s"
                    WatchEnd.GaveUp -> "No answer after ${FirmwareStore.WATCH_LIMIT_S / 60} minutes"
                    else -> "The router answered"
                },
                failed = end == WatchEnd.GaveUp,
            )
            val before = store.beforeBoard?.let { listOf(it.release, it.revision).filter { s -> s.isNotBlank() }.joinToString(" ") } ?: "?"
            val after = store.afterBoard?.let { listOf(it.release, it.revision).filter { s -> s.isNotBlank() }.joinToString(" ") }
            WatchStep(
                done = end == WatchEnd.Back,
                failed = end == WatchEnd.Unchanged,
                text = "Back — re-read version: $before → ${after ?: "?"}" +
                    if (end == WatchEnd.Unchanged) " · UNCHANGED — the flash did not take" else "",
            )
            if (store.userPackages.isNotEmpty()) {
                WatchStep(
                    done = store.reinstallOutput != null,
                    text = "Reinstall ${store.userPackages.size} packages · ${Commands.reinstall(store.userPackages).substringAfter("then ").substringBefore(" 2>&1")}",
                )
            }
        }
        if (store.watchLog.isNotEmpty()) {
            OutputBox(store.watchLog.joinToString("\n"), problem = end == WatchEnd.GaveUp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Typical: 2–4 minutes", style = mono(10f, 500, Wrt.TextDim))
            FlexSpacer()
            Text("elapsed ${FirmwareStore.elapsedLabel(store.watchElapsedS)}", style = mono(10f, 600, Wrt.TextSecondary))
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
                reinstall?.let { Text(it, style = mono(10f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent)) }
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
            null -> if (!store.keepSettings) {
                NoteCard(
                    "Settings were discarded, so the router comes back on 192.168.1.1 with a new " +
                        "SSH host key. The watch will stop when it sees that key."
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WatchStep(done: Boolean, text: String, failed: Boolean = false) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tone = when {
            failed -> Wrt.Red
            done -> Wrt.Green
            else -> Wrt.Amber
        }
        Box(
            Modifier.size(14.dp).border(1.5.dp, tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done && !failed) Icon(WrtIcons.Check, null, Modifier.size(9.dp), tint = tone)
            if (failed) Icon(WrtIcons.Close, null, Modifier.size(8.dp), tint = tone)
        }
        Text(
            text,
            style = mono(10f, 500, if (failed) Wrt.Red else if (done) Wrt.TextSecondary else Wrt.Amber),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
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
