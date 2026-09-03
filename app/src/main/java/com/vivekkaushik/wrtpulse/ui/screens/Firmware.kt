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
import com.vivekkaushik.wrtpulse.data.FirmwareStore
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

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("Firmware", onBack) {
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

            SectionLabel("BEFORE FLASHING", tracking = 0.14)

            GateCard(
                index = 1,
                title = "Back up the configuration",
                done = store.backupDone,
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
                detail = when {
                    store.status.tool == "owut" -> "owut check"
                    store.status.hasTool -> "${store.status.tool} — not driven by this app yet"
                    else -> "no owut on this router; use a download URL below"
                },
                warn = store.check?.safe == false,
                message = result?.takeIf { it.first == 2 }?.second,
            ) {
                if (store.status.tool == "owut") {
                    GhostButton(if (store.check == null) "Check" else "Check again") {
                        scope.launch { result = 2 to store.runCheck() }
                    }
                }
            }

            GateCard(
                index = 3,
                title = "Download the image",
                done = store.image != null,
                detail = store.image?.let { img ->
                    listOfNotNull(
                        img.path.substringAfterLast('/'),
                        img.sizeBytes?.let { preciseBytes(it) },
                        img.sha256?.take(12),
                    ).joinToString(" · ")
                } ?: "built by the server with your installed packages",
                message = result?.takeIf { it.first == 3 }?.second,
            ) {
                if (store.status.tool == "owut") {
                    PrimaryButton(if (store.image == null) "Build and download" else "Download again") {
                        scope.launch { result = 3 to store.downloadWithTool() }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (store.image != null) {
                    GhostButton("Discard it and free the RAM", textColor = Wrt.TextSecondary) {
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
                detail = when (store.image?.testPassed) {
                    true -> "accepted for this device"
                    false -> "refused — see below"
                    null -> "sysupgrade -T reads the image's metadata"
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

            SectionLabel("FLASH", color = Wrt.Red, tracking = 0.14)
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
    message: String? = null,
    content: @Composable () -> Unit,
) {
    val accent = when {
        warn -> Wrt.Red
        done -> Wrt.Accent
        else -> Wrt.TextDim
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
            Text(block, style = sans(11.5f, 500, Wrt.DangerSub))
        } else {
            val image = store.image
            if (image != null) CodeLine(Commands.flash(image.path, store.keepSettings))
            Spacer(Modifier.height(10.dp))
            HoldToConfirm("Hold to flash") {
                scope.launch { onResult(store.flash()) }
            }
            Text(
                "Hold 3 s to confirm",
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

/** After the command is away there is nothing to do but say so plainly. */
@Composable
private fun FlashingPanel(store: FirmwareStore) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(WrtIcons.Firmware, null, Modifier.size(40.dp), tint = Wrt.Amber)
        Text(
            "Do not power the router off",
            style = sans(16f, 700, Wrt.Amber),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "sysupgrade is writing the image and will reboot on its own. This usually takes a " +
                "few minutes. The connection has already dropped — that is expected.",
            style = sans(12f, 500, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 10.dp),
        )
        store.backupFile?.let {
            Text(
                "Your backup is on this phone as ${it.name}",
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        if (!store.keepSettings) {
            Text(
                "Settings were discarded, so the router comes back on 192.168.1.1 with a new " +
                    "SSH host key. Add it again from the router list.",
                style = sans(11f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

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

/** The app's standing promise: the command is visible before it runs. */
@Composable
internal fun CodeLine(command: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
            .background(Wrt.BgCode, RoundedCornerShape(9.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(command, style = mono(9.5f, 500, Wrt.TextSecondary))
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
