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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.vivekkaushik.wrtpulse.data.SshKeyStore
import com.vivekkaushik.wrtpulse.ops.AuthorizedKey
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

/**
 * The router's authorized_keys: who can log in, which entry is this app's, and whether a
 * password would still work. The one row that never offers a delete button is the one the
 * app is currently signed in with.
 */
@Composable
fun SshKeysScreen(store: SshKeyStore?, latencyMs: Int, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<AuthorizedKey?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { if (store != null && !store.loaded) store.load() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("SSH keys", onBack) {
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
                Text("Connect to a router to manage keys.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            item {
                Column {
                    AccessCard(store)
                    Spacer(Modifier.height(10.dp))
                    if (store.hasAppKey && !store.appKeyInstalled) {
                        PrimaryButton("Install the app's key") {
                            scope.launch { toast = store.installAppKey() }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    GhostButton(
                        if (adding) "Cancel" else "Add a key",
                        border = Wrt.TextTertiary,
                    ) { adding = !adding; paste = "" }
                    if (adding) {
                        Spacer(Modifier.height(10.dp))
                        PasteBox(paste, { paste = it }) {
                            scope.launch {
                                toast = store.add(paste)
                                if (toast?.startsWith("Failed") != true) { adding = false; paste = "" }
                            }
                        }
                    }
                    toast?.let {
                        Text(
                            it,
                            style = mono(10.5f, 500, if (it.startsWith("Failed")) Wrt.Red else Wrt.Accent),
                            modifier = Modifier.padding(top = 10.dp).clickable { toast = null },
                        )
                    }
                    store.error?.let {
                        Text(it, style = sans(11f, 500, Wrt.Red), modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionLabel(
                        if (store.keys.isEmpty()) "NO KEYS INSTALLED" else "AUTHORIZED KEYS",
                        tracking = 0.14,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(store.keys, key = { it.blob }) { key ->
                KeyRow(key, store) { confirm = key }
            }
            item {
                if (store.keys.isEmpty() && store.loaded) {
                    Text(
                        "Nothing can log in with a key. Only the password stands between " +
                            "this router and anyone on the network.",
                        style = sans(11.5f, 500, Wrt.TextDim),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                PasswordCard(store)
            }
        }
    }

    val target = confirm
    if (target != null && store != null) {
        RemoveKeyDialog(
            store = store,
            key = target,
            onDismiss = { confirm = null },
            onResult = { toast = it; confirm = null },
        )
    }
}

/** Who can get in, and how — the one-line answer this screen exists to give. */
@Composable
private fun AccessCard(store: SshKeyStore) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("WHO CAN LOG IN", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag(
                "${store.keys.size} KEY${if (store.keys.size == 1) "" else "S"}",
                color = if (store.keys.isEmpty()) Wrt.Amber else Wrt.Accent,
                border = (if (store.keys.isEmpty()) Wrt.Amber else Wrt.Accent).copy(alpha = 0.5f),
            )
        }
        Text(
            when {
                store.appKeyInstalled -> "WrtPulse signs in with its own key"
                store.hasAppKey -> "WrtPulse has a key, but it is not on this router"
                else -> "WrtPulse signs in with a password"
            },
            style = sans(12.5f, 600),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(
                Commands.AUTHORIZED_KEYS,
                store.fileMode,
            ).joinToString(" · "),
            style = mono(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PasteBox(value: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    Column {
        FieldLabel("PUBLIC KEY LINE")
        Box {
            FormTextField(value, onChange)
            if (value.isEmpty()) {
                Text(
                    "ssh-ed25519 AAAA… you@laptop",
                    style = mono(11f, 500, Wrt.TextFaint),
                    modifier = Modifier.padding(start = 12.dp, top = 20.dp),
                )
            }
        }
        Text(
            "The contents of a .pub file — never the private half.",
            style = sans(10f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Install this key", onClick = onAdd)
    }
}

@Composable
private fun KeyRow(key: AuthorizedKey, store: SshKeyStore, onRemove: () -> Unit) {
    val blocked = SshKeyStore.removalBlock(key, store.keys.size, store.auth) != null
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (key.isAppKey) Wrt.Accent.copy(alpha = 0.45f) else Wrt.BorderRow,
                RoundedCornerShape(10.dp),
            )
            .background(Wrt.BgCardDim, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(if (key.isAppKey) Wrt.Accent else Wrt.TextTertiary, size = 7.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    key.comment.ifBlank { "(no label)" },
                    style = sans(12.5f, 600),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                MonoTag(key.shortType.uppercase(), Wrt.TextTertiary)
                if (key.isAppKey) MonoTag("THIS APP", Wrt.Accent, Wrt.Accent.copy(alpha = 0.5f))
            }
            Text(
                key.fingerprint,
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (!blocked) {
            Text(
                "Remove",
                style = sans(11.5f, 650, Wrt.Red),
                modifier = Modifier.clickable(onClick = onRemove),
            )
        } else {
            Icon(WrtIcons.Shield, "protected", Modifier.size(14.dp), tint = Wrt.TextDim)
        }
    }
}

/**
 * Password auth is shown, not switched. Flipping it needs a dropbear restart, which ends the
 * session doing the flipping — and a mistake here locks the router permanently.
 */
@Composable
private fun PasswordCard(store: SshKeyStore) {
    val open = store.auth.passwordsAccepted
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (open) Wrt.Amber.copy(alpha = 0.4f) else Wrt.BorderCard,
                RoundedCornerShape(13.dp),
            )
            .background(if (open) Wrt.Amber.copy(alpha = 0.04f) else Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("PASSWORD LOGIN", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            MonoTag(
                if (open) "ACCEPTED" else "OFF",
                color = if (open) Wrt.Amber else Wrt.Accent,
                border = (if (open) Wrt.Amber else Wrt.Accent).copy(alpha = 0.5f),
            )
        }
        Text(
            if (open) {
                "A password still gets you in, so the keys above are a convenience rather " +
                    "than a lock."
            } else {
                "dropbear is refusing passwords. Only the keys above can log in."
            },
            style = sans(11.5f, 500, if (open) Wrt.AmberText else Wrt.TextSecondary),
            modifier = Modifier.padding(top = 8.dp),
        )
        if (open && store.appKeyInstalled) {
            Text(
                SshKeyStore.PASSWORD_AUTH_NOTE,
                style = sans(10.5f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 9.dp),
            )
            Box(
                Modifier
                    .padding(top = 9.dp)
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
                    .background(Wrt.BgCode, RoundedCornerShape(9.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp)
            ) {
                Text(
                    Commands.PASSWORD_AUTH_HELP.replace("; ", "\n"),
                    style = mono(9.5f, 500, Wrt.TextSecondary),
                )
            }
        }
    }
}

@Composable
private fun RemoveKeyDialog(
    store: SshKeyStore,
    key: AuthorizedKey,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val warning = SshKeyStore.removalWarning(store.keys.size, store.auth)
    Dialog(onDismissRequest = { if (!running) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(16.dp))
                .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                .padding(18.dp)
        ) {
            Text("Remove this key?", style = sans(15f, 650))
            Text(
                key.comment.ifBlank { "(no label)" },
                style = sans(12.5f, 600, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                key.fingerprint,
                style = mono(10f, 500, Wrt.TextDim),
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Whoever holds this key loses access to the router.",
                style = sans(11.5f, 500, Wrt.TextSecondary),
                modifier = Modifier.padding(top = 12.dp),
            )
            warning?.let {
                Text(it, style = sans(10.5f, 500, Wrt.Amber), modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                if (running) "Removing…" else "Remove",
                color = if (running) Wrt.BorderCard else Wrt.Red,
                textColor = if (running) Wrt.TextDim else Wrt.OnRed,
            ) {
                if (!running) {
                    running = true
                    scope.launch { onResult(store.remove(key)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            GhostButton("Cancel", border = Wrt.TextTertiary) { if (!running) onDismiss() }
        }
    }
}
