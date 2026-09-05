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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.GuestConfig
import com.vivekkaushik.wrtpulse.data.GuestStore
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

/**
 * The Guest Wi-Fi sheet. Creates or manages an isolated guest network — the store does the
 * OpenWrt work; this is the SSID, the password, which bands, and the one-line honesty about
 * what a guest network is (internet, not your LAN).
 */
@Composable
fun GuestSheet(store: GuestStore?, hostname: String?, onDismiss: () -> Unit) {
    if (store == null) return
    LaunchedEffect(store) { if (!store.loaded) store.load() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(WrtIcons.GuestWifi, null, Modifier.size(18.dp), tint = Wrt.Accent)
            Spacer(Modifier.size(9.dp))
            Text("Guest Wi-Fi", style = sans(16f, 650))
        }
        when {
            !store.loaded && store.error == null ->
                Text("Reading the router…", style = sans(12f, 400, Wrt.TextDim), modifier = Modifier.padding(vertical = 20.dp))
            store.existing != null -> ManageGuest(store, onDismiss)
            else -> CreateGuest(store, hostname, onDismiss)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CreateGuest(store: GuestStore, hostname: String?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaults = remember(store.loaded) { store.defaults(hostname) }
    var ssid by remember { mutableStateOf(defaults.ssid) }
    var key by remember { mutableStateOf(defaults.key) }
    var open by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(true) }
    var isolate by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(defaults.devices) } }

    Text(
        "A separate SSID that reaches the internet but not your LAN — its own subnet, its own " +
            "DHCP, firewalled off from everything else.",
        style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp),
        modifier = Modifier.padding(top = 6.dp),
    )

    FieldLabel("NETWORK NAME")
    FormTextField(ssid, { ssid = it })

    Spacer(Modifier.height(11.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(if (open) "OPEN — NO PASSWORD" else "PASSWORD")
        FlexSpacer()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Open", style = sans(11f, 600, if (open) Wrt.Amber else Wrt.TextDim))
            WToggle(open) { open = !open }
        }
    }
    if (!open) {
        FormTextField(key, { key = it }, password = true, masked = !reveal) {
            Icon(
                WrtIcons.Eye, if (reveal) "hide" else "show",
                Modifier.size(17.dp).clickable { reveal = !reveal },
                tint = if (reveal) Wrt.Accent else Wrt.TextTertiary,
            )
            Icon(
                WrtIcons.ShareUp, "generate",
                Modifier.size(17.dp).clickable { key = GuestStore.passphrase(); reveal = true },
                tint = Wrt.TextTertiary,
            )
        }
        if (key.length < 8) {
            Text("A WPA password must be 8–63 characters.", style = sans(10.5f, 400, Wrt.Amber), modifier = Modifier.padding(top = 5.dp))
        }
    } else {
        Text(
            "Anyone in range can join. Fine for a café-style network; keep isolation on.",
            style = sans(10.5f, 400, Wrt.TextDim, lineHeight = 15.sp),
            modifier = Modifier.padding(top = 5.dp),
        )
    }

    if (store.radios.size > 1) {
        Spacer(Modifier.height(12.dp))
        FieldLabel("BANDS")
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            store.radios.forEach { radio ->
                val on = radio.section in selected
                FilterChip("${radio.band}", on, size = 11f, padH = 12.dp, padV = 5.dp) {
                    if (on) selected.remove(radio.section) else selected.add(radio.section)
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Isolate clients", style = sans(12.5f, 600))
            Text("Guests can't see each other — only the internet.", style = sans(10f, 400, Wrt.TextDim, lineHeight = 14.sp), modifier = Modifier.padding(top = 2.dp))
        }
        WToggle(isolate) { isolate = !isolate }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Guests get ${defaults.routerIp.substringBeforeLast('.')}.0/24 · internet only · applied across wireless, network, dhcp, firewall.",
        style = mono(9.5f, 500, Wrt.TextDim, lineHeight = 15.sp),
    )

    store.error?.let { Text(it, style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp), modifier = Modifier.padding(top = 10.dp)) }

    Spacer(Modifier.height(14.dp))
    val ready = ssid.isNotBlank() && selected.isNotEmpty() && (open || key.length in 8..63) && !store.applying
    val label = when {
        store.applying -> "Creating…"
        selected.isEmpty() -> "Pick a band"
        else -> "Create guest network"
    }
    if (ready) {
        PrimaryButton(label) {
            scope.launch {
                val ok = store.create(
                    GuestConfig(ssid.trim(), key, open, selected.toList(), isolate, defaults.routerIp),
                )
                if (ok) onDismiss()
            }
        }
    } else {
        DisabledButton(label)
    }
    Spacer(Modifier.height(6.dp))
    GhostButton("Cancel", onClick = onDismiss)
}

@Composable
private fun ManageGuest(store: GuestStore, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val net = store.existing ?: return
    val clipboard = LocalClipboardManager.current
    var reveal by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(net.ssid.ifBlank { "Guest network" }, style = sans(15f, 650))
            Text(
                (if (net.enabled) "on the air" else "switched off") +
                    (if (net.open) " · open" else " · WPA2"),
                style = sans(11f, 400, if (net.enabled) Wrt.Green else Wrt.TextDim),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        WToggle(net.enabled) { scope.launch { store.setEnabled(!net.enabled) } }
    }

    if (!net.open) {
        Spacer(Modifier.height(12.dp))
        FieldLabel("PASSWORD")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (reveal) net.key.ifBlank { "—" } else "•".repeat(net.key.length.coerceIn(6, 16)),
                style = mono(13f, 500), modifier = Modifier.weight(1f),
            )
            Icon(WrtIcons.Eye, if (reveal) "hide" else "show", Modifier.size(17.dp).clickable { reveal = !reveal }, tint = if (reveal) Wrt.Accent else Wrt.TextTertiary)
            Icon(
                WrtIcons.Copy, "copy",
                Modifier.size(16.dp).clickable { clipboard.setText(AnnotatedString(net.key)); copied = true },
                tint = if (copied) Wrt.Accent else Wrt.TextTertiary,
            )
        }
        if (copied) Text("Copied to the clipboard.", style = sans(10f, 400, Wrt.Accent), modifier = Modifier.padding(top = 5.dp))
    }

    store.notice?.let { Text(it, style = sans(11f, 400, Wrt.Accent, lineHeight = 16.sp), modifier = Modifier.padding(top = 12.dp)) }
    store.error?.let { Text(it, style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp), modifier = Modifier.padding(top = 10.dp)) }

    Spacer(Modifier.height(16.dp))
    if (confirmRemove) {
        PrimaryButton(if (store.applying) "Removing…" else "Tap again to remove", color = Wrt.Red, textColor = Wrt.OnRed) {
            if (!store.applying) scope.launch { if (store.remove()) onDismiss() }
        }
        Text(
            "Deletes the guest SSID, its subnet, DHCP pool and firewall zone.",
            style = sans(10f, 400, Wrt.TextDim, lineHeight = 14.sp),
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        GhostButton("Remove guest network", border = Wrt.Red.copy(alpha = 0.5f), textColor = Wrt.Red) { confirmRemove = true }
    }
    Spacer(Modifier.height(6.dp))
    GhostButton("Done", onClick = onDismiss)
}

@Composable
private fun DisabledButton(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Wrt.BgDeep, RoundedCornerShape(11.dp))
            .border(1.dp, Wrt.BorderInput, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = sans(13.5f, 650, Wrt.TextDim)) }
}
