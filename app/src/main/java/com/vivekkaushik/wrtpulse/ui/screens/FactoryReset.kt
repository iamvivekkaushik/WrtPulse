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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.LossText
import com.vivekkaushik.wrtpulse.data.ResetStore
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import kotlinx.coroutines.launch

/**
 * Factory reset — design screen 43.
 *
 * The whole screen is the confirmation. There is no dry run and no rollback behind
 * `firstboot`, so what stands in for them is naming the loss precisely: the actual SSIDs,
 * the actual address it moves off, the actual counts, the actual package names. The hold is
 * the last three seconds of that, not a substitute for it.
 */
@Composable
fun FactoryResetScreen(store: ResetStore?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { store?.load() }

    Column(Modifier.fillMaxSize().background(Wrt.DangerBg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Wrt.DangerCode)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                WrtIcons.ChevronLeft, "back",
                Modifier.size(18.dp).clickable(onClick = onBack),
                tint = Wrt.DangerText,
            )
            Text("Factory reset", style = sans(15f, 650, Wrt.DangerText), modifier = Modifier.weight(1f))
            Text(
                "DANGER ZONE",
                style = mono(9f, 600, Wrt.Red),
                modifier = Modifier
                    .border(1.dp, Wrt.Red.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to reset it.", style = sans(12f, 500, Wrt.DangerDim))
            }
            return@Column
        }

        if (store.resetting) {
            ResettingPanel(store)
            return@Column
        }

        val name = store.board?.hostname?.ifBlank { null } ?: "this router"
        val release = store.board?.release
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(Wrt.Red, CircleShape))
                Text("CANNOT BE UNDONE", style = mono(10f, 600, Wrt.Red))
            }
            Text(
                "Reset $name to factory defaults?",
                style = sans(21f, 650, Wrt.DangerText, lineHeight = 26.sp),
            )
            Text(
                "Erases every setting in /overlay and reboots. " +
                    (release?.let { "Firmware $it stays; " } ?: "The firmware stays; ") +
                    "the router comes back as a fresh OpenWrt install.",
                style = sans(12f, 400, Wrt.DangerBody, lineHeight = 18.sp),
            )

            LossCard(store)
            BackupLine(store, result) {
                scope.launch { result = store.backUp() }
            }
            AmberNote(store)

            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.DangerCodeBorder, RoundedCornerShape(12.dp))
                    .background(Wrt.DangerCodeBg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Text("# runs on hold", style = mono(10.5f, 400, Wrt.DangerDim, lineHeight = 18.sp))
                Text(
                    "$ firstboot -y && reboot",
                    style = mono(10.5f, 400, Wrt.DangerLoss, lineHeight = 18.sp),
                )
            }

            store.error?.let { Text(it, style = sans(11f, 500, Wrt.Red, lineHeight = 16.sp)) }

            Spacer(Modifier.height(2.dp))
            HoldToConfirm("Hold to reset & reboot") {
                scope.launch { result = store.reset() }
            }
            Text(
                "Hold 3 s to confirm · release to cancel",
                style = sans(10.5f, 400, Wrt.DangerDim),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier.fillMaxWidth().height(38.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", style = sans(13f, 600, Wrt.DangerOutlineText))
            }
        }
    }
}

/** The design's "WHAT YOU LOSE" card: one red ✕ per thing the reset takes. */
@Composable
private fun LossCard(store: ResetStore) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Red.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
            .background(Wrt.DangerCode, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("WHAT YOU LOSE", style = mono(9f, 600, Wrt.DangerDim))
        Spacer(Modifier.height(9.dp))
        val losses = store.losses
        if (losses.isEmpty()) {
            Text(
                if (store.loading) "Reading the config…" else "Couldn't read the config — reset anyway only if you know what is on here.",
                style = sans(12f, 400, Wrt.DangerBody, lineHeight = 18.sp),
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            losses.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(
                        WrtIcons.Close, null,
                        Modifier.padding(top = 2.dp).size(13.dp),
                        tint = Wrt.Red,
                    )
                    Text(annotate(line), style = sans(12f, 400, Wrt.DangerLoss, lineHeight = 18.sp))
                }
            }
        }
    }
}

/** Values in mono and the verdict in bold, as the design sets them. */
@Composable
private fun annotate(line: List<LossText>) = buildAnnotatedString {
    line.forEach { piece ->
        when (piece) {
            is LossText.Plain -> append(piece.text)
            is LossText.Mono -> withStyle(
                SpanStyle(
                    fontFamily = mono(10.5f, 500, Wrt.DangerMono).fontFamily,
                    fontSize = 10.5.sp,
                    color = Wrt.DangerMono,
                )
            ) { append(piece.text) }
            is LossText.Strong -> withStyle(
                SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight(650))
            ) { append(piece.text) }
        }
    }
}

/**
 * Whether there is a copy to come back to. Green when there is, amber with a way to take
 * one when there is not — the reset is still allowed either way, it is the user's router.
 */
@Composable
private fun BackupLine(store: ResetStore, result: String?, onBackUp: () -> Unit) {
    val file = store.backupFile
    val context = LocalContext.current
    if (file != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .background(Wrt.Accent.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(WrtIcons.Check, null, Modifier.size(13.dp), tint = Wrt.Green)
            Text(
                buildAnnotatedString {
                    append("Backup saved to this phone first · ")
                    withStyle(SpanStyle(fontSize = 10.sp, color = Wrt.Accent)) { append(file.name) }
                    store.backupFiles?.let { append(" · $it files") }
                },
                style = sans(11.5f, 400, Wrt.AccentBody, lineHeight = 17.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                "Share",
                style = sans(11f, 600, Wrt.Accent),
                modifier = Modifier.clickable { shareBackup(context, file) },
            )
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(WrtIcons.Warning, null, Modifier.size(13.dp), tint = Wrt.Amber)
        Text(
            store.progress ?: result?.takeIf { it.startsWith("Failed") }
                ?: "No backup on this phone yet — after the reset there is nothing to put back.",
            style = sans(11.5f, 400, Wrt.AmberText, lineHeight = 17.sp),
            modifier = Modifier.weight(1f),
        )
        Text(
            if (store.busy) "Working…" else "Back up now",
            style = sans(11f, 600, if (store.busy) Wrt.DangerDim else Wrt.Accent),
            modifier = Modifier.clickable(enabled = !store.busy, onClick = onBackUp),
        )
    }
}

/** The one thing a reset breaks that the user cannot see coming from the list above. */
@Composable
private fun AmberNote(store: ResetStore) {
    val summary = store.summary ?: return
    if (summary.ssids.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .background(Wrt.Amber.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(WrtIcons.Warning, null, Modifier.padding(top = 1.dp).size(13.dp), tint = Wrt.Amber)
        Text(
            buildAnnotatedString {
                append("If you reach this router over its Wi-Fi, there is no Wi-Fi after the reset — plug in by cable and re-add it at ")
                withStyle(SpanStyle(fontSize = 10.5.sp)) { append(ResetStore.DEFAULT_ADDRESS) }
                append(".")
            },
            style = sans(11f, 400, Wrt.AmberText, lineHeight = 17.sp),
        )
    }
}

/**
 * After the hold. There is nothing to watch for — the router that comes back has a new host
 * key at a new address, so it is a new entry in the router list, not this session resuming.
 */
@Composable
private fun ResettingPanel(store: ResetStore) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(Wrt.Amber, CircleShape))
            Text("RESETTING — DON'T POWER OFF", style = mono(10f, 600, Wrt.Amber))
        }
        Text("Erasing settings…", style = sans(22f, 650, Wrt.DangerText))
        Text(
            "firstboot is away and the link is gone — that is expected. " +
                "The router reboots as a fresh install at " +
                "${ResetStore.DEFAULT_ADDRESS}, with a new host key, so it comes back as a new " +
                "entry rather than this one.",
            style = sans(12.5f, 400, Wrt.DangerBody, lineHeight = 19.sp),
        )
        store.backupFile?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "Your settings are on this phone as ${it.name} — restore them from Backup once " +
                    "the router is added again.",
                style = sans(12f, 400, Wrt.AccentBody, lineHeight = 18.sp),
            )
        }
    }
}
