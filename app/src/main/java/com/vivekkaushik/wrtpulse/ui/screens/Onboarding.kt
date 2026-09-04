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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.RouterGlyph
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

@Composable
private fun OnboardingScaffold(
    step: Int,
    showLogo: Boolean = false,
    /** Non-null when there is somewhere to go back to — the router list, for a returning user. */
    onBack: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wrt.BgScreen)
            .imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)
    ) {
        if (showLogo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (onBack != null) {
                    Icon(
                        WrtIcons.ChevronLeft,
                        "back",
                        Modifier.size(18.dp).clickable(onClick = onBack),
                        tint = Wrt.TextPrimary,
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .border(1.dp, Wrt.BorderIcon, RoundedCornerShape(8.dp))
                        .background(Wrt.BgCard, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { RouterGlyph(20.dp) }
                Text("WrtPulse", style = sans(13.5f, 650, letterSpacing = 0.01.em))
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("0$step", style = mono(11f, 600, Wrt.Accent))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(if (i < step) Wrt.Accent else Wrt.BorderCard, RoundedCornerShape(1.dp))
                    )
                }
            }
            Text("/ 03", style = mono(11f, 500, Wrt.TextDim))
        }
        content()
    }
}

@Composable
fun OnboardingConnectScreen(
    flow: OnboardingFlow,
    onFirstContact: () -> Unit,
    onConnected: () -> Unit,
    onKeyChanged: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    OnboardingScaffold(step = 1, showLogo = true, onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            Text("Connect to your router", style = sans(24f, 650, letterSpacing = (-0.01).em))
            Spacer(Modifier.height(8.dp))
            Text(
                "WrtPulse manages OpenWrt over SSH. Nothing is installed on the router — commands run remotely, results render here.",
                style = sans(13f, 400, Wrt.TextSecondary, lineHeight = 20.sp),
            )
            Spacer(Modifier.height(20.dp))
            val gw = flow.gateway
            if (gw != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .background(Wrt.Accent.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Icon(WrtIcons.RadioWaves, null, Modifier.size(20.dp), tint = Wrt.Accent)
                    Column(Modifier.weight(1f)) {
                        Text("Found a gateway — is this your router?", style = sans(12.5f, 600))
                        Text(gw, style = mono(12f, 500, Wrt.Accent), modifier = Modifier.padding(top = 2.dp))
                    }
                    Box(
                        Modifier
                            .background(Wrt.Accent, RoundedCornerShape(7.dp))
                            .clickable { flow.host = gw }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) { Text("Use", style = sans(11.5f, 600, Wrt.OnAccent)) }
                }
                Spacer(Modifier.height(20.dp))
            }
            InputBox(label = "HOST", value = flow.host, onValue = { flow.host = it }, placeholder = "192.168.1.1")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    InputBox(label = "PORT", value = flow.port, onValue = { flow.port = it }, keyboard = KeyboardType.Number)
                }
                Box(Modifier.weight(2f)) {
                    InputBox(label = "USERNAME", value = flow.username, onValue = { flow.username = it })
                }
            }
            Spacer(Modifier.height(14.dp))
            InputBox(
                label = "PASSWORD",
                value = flow.password,
                onValue = { flow.password = it },
                keyboard = KeyboardType.Password,
                isPassword = true,
            )
            Text(
                // A freshly flashed OpenWrt has no root password, which is precisely when
                // someone is adding it to this app.
                "Leave blank if root has no password yet — that is how OpenWrt ships.",
                style = sans(10.5f, 400, Wrt.TextDim),
                modifier = Modifier.padding(top = 7.dp, start = 2.dp),
            )
            if (flow.error != null) {
                Spacer(Modifier.height(14.dp))
                ErrorCard(flow.error!!)
            }
            Spacer(Modifier.height(20.dp))
        }
        PrimaryButton(
            if (flow.busy) "Connecting…" else "Connect",
            onClick = { flow.connect(onFirstContact, onConnected, onKeyChanged) },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Credentials stay on this device — sent only to the router.",
            style = sans(11f, 400, Wrt.TextDim),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .background(Wrt.Red.copy(alpha = 0.07f), RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(WrtIcons.Shield, null, Modifier.size(15.dp), tint = Wrt.Red)
        Text(message, style = sans(12f, 500, Wrt.Red, lineHeight = 17.sp))
    }
}

@Composable
private fun InputBox(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Ascii,
    isPassword: Boolean = false,
) {
    var reveal by remember { mutableStateOf(false) }
    Column {
        SectionLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(44.dp)
                .border(1.dp, Wrt.BorderInput, RoundedCornerShape(10.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = mono(13f, 500, Wrt.TextFaint))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    textStyle = mono(13f, 500),
                    singleLine = true,
                    cursorBrush = SolidColor(Wrt.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                    visualTransformation =
                        if (isPassword && !reveal) PasswordVisualTransformation('•')
                        else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isPassword) {
                Icon(
                    WrtIcons.Eye,
                    if (reveal) "hide password" else "show password",
                    Modifier.size(18.dp).clickable { reveal = !reveal },
                    tint = if (reveal) Wrt.Accent else Wrt.TextDim,
                )
            }
        }
    }
}

@Composable
fun OnboardingFingerprintScreen(flow: OnboardingFlow, onConfirm: () -> Unit, onBack: () -> Unit) {
    val target = flow.target
    val key = flow.probed
    OnboardingScaffold(step = 2) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            Text("We found this router", style = sans(24f, 650, letterSpacing = (-0.01).em))
            Spacer(Modifier.height(8.dp))
            Text("Confirm it's yours before WrtPulse trusts it.", style = sans(13f, 400, Wrt.TextSecondary, lineHeight = 20.sp))
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RouterGlyph(44.dp, leds = listOf(Wrt.Accent, Wrt.TextTertiary))
                Text(target.host, style = sans(17f, 650), modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Identity details load after you sign in",
                    style = mono(11f, 500, Wrt.TextDim),
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgCard, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SpecRow("HOST", target.host)
                SpecRow("PORT", target.port.toString())
                SpecRow("USERNAME", target.username)
                SpecRow("KEY TYPE", key?.type ?: "—", last = true)
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.BorderCard, RoundedCornerShape(14.dp))
                    .background(Wrt.BgDeep, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                SectionLabel("HOST KEY — FIRST CONTACT")
                Text(
                    key?.sha256Fingerprint ?: "—",
                    style = mono(11f, 500, Wrt.Accent, lineHeight = 16.5.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Read before sign-in — no password has been sent yet. Saved when you confirm. " +
                        "If it ever changes, WrtPulse blocks the connection and warns you first.",
                    style = sans(11.5f, 400, Wrt.TextSecondary, lineHeight = 17.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (flow.error != null) {
                Spacer(Modifier.height(12.dp))
                ErrorCard(flow.error!!)
            }
            Spacer(Modifier.height(16.dp))
        }
        PrimaryButton(if (flow.busy) "Signing in…" else "Yes, that's my router", onClick = onConfirm)
        Spacer(Modifier.height(8.dp))
        GhostButton("Not mine — go back", onClick = onBack)
    }
}

@Composable
private fun SpecRow(label: String, value: String, last: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = mono(10.5f, 500, Wrt.TextDim))
        FlexSpacer()
        Text(value, style = mono(12f, 500))
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))
}

@Composable
fun OnboardingSshKeyScreen(flow: OnboardingFlow, routerSummary: String?, onFinish: () -> Unit) {
    var installKey by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OnboardingScaffoldBody(routerSummary, installKey, onSelect = { installKey = it })
            if (flow.error != null) {
                Box(Modifier.padding(horizontal = 20.dp)) { ErrorCard(flow.error!!) }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            PrimaryButton(
                when {
                    flow.busy -> "Installing key…"
                    installKey -> "Install key & finish"
                    else -> "Finish"
                },
                onClick = {
                    if (installKey) flow.installAppKey { ok -> if (ok) onFinish() }
                    else onFinish()
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "You can remove the key any time in System → SSH keys.",
                style = sans(11f, 400, Wrt.TextDim),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OnboardingScaffoldBody(routerSummary: String?, installKey: Boolean, onSelect: (Boolean) -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("03", style = mono(11f, 600, Wrt.Accent))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(Modifier.weight(1f).height(2.dp).background(Wrt.Accent, RoundedCornerShape(1.dp)))
                }
            }
            Text("/ 03", style = mono(11f, 500, Wrt.TextDim))
        }
        if (routerSummary != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(11.dp))
                    .background(Wrt.Accent.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(Modifier.size(7.dp).background(Wrt.Accent, CircleShape))
                Text("Connected · $routerSummary", style = mono(10.5f, 500, Wrt.Accent), lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Skip the password next time", style = sans(24f, 650, letterSpacing = (-0.01).em))
        Spacer(Modifier.height(8.dp))
        Text(
            "WrtPulse can install its own SSH key on the router, then sign in with the key from now on.",
            style = sans(13f, 400, Wrt.TextSecondary, lineHeight = 20.sp),
        )
        Spacer(Modifier.height(22.dp))
        KeyOption(
            selected = installKey,
            title = "Install the app's key",
            tag = "RECOMMENDED",
            body = "Your password is used once to install the key, then discarded. Nothing is stored.",
            onClick = { onSelect(true) },
        )
        Spacer(Modifier.height(10.dp))
        KeyOption(
            selected = !installKey,
            title = "Keep using the password",
            tag = null,
            body = "Stored encrypted on this phone (Android Keystore), unlocked with your screen lock.",
            onClick = { onSelect(false) },
        )
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$", style = mono(11f, 500, Wrt.Green))
            Text("tee -a /etc/dropbear/authorized_keys", style = mono(11f, 500, Wrt.TextSecondary), modifier = Modifier.weight(1f))
            Text("view full command", style = sans(11f, 600, Wrt.Accent))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Every action in WrtPulse can show the exact shell command it runs.",
            style = sans(10.5f, 400, Wrt.TextDim),
            modifier = Modifier.padding(start = 2.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun KeyOption(selected: Boolean, title: String, tag: String?, body: String, onClick: () -> Unit) {
    val borderColor = if (selected) Wrt.Accent.copy(alpha = 0.55f) else Wrt.BorderCard
    val bg = if (selected) Wrt.Accent.copy(alpha = 0.06f) else Wrt.BgCard
    Row(
        Modifier
            .fillMaxWidth()
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(18.dp)
                .border(1.5.dp, if (selected) Wrt.Accent else Wrt.DotOff, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(9.dp).background(Wrt.Accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = sans(14f, 650))
                if (tag != null) MonoTag(tag, color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f), size = 8.5f)
            }
            Text(body, style = sans(12f, 400, Wrt.TextSecondary, lineHeight = 18.sp), modifier = Modifier.padding(top = 5.dp))
        }
    }
}
