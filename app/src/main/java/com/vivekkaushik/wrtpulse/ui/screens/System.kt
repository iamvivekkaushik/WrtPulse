package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.data.Telemetry
import com.vivekkaushik.wrtpulse.ops.BoardInfo
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WToggle
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

@Composable
fun SystemScreen(
    ticker: LiveTicker,
    live: Telemetry? = null,
    board: BoardInfo? = null,
    country: String? = null,
    sshKeyInstalled: Boolean? = null,
    biometricEnabled: Boolean? = null,
    onBiometricToggle: (Boolean) -> Unit = {},
    routerName: String,
    onRouterTap: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    var biometricDemo by remember { mutableStateOf(true) }
    val isLive = live != null
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = live?.latencyMs ?: ticker.latencyMs,
            onRouterTap = onRouterTap,
            trailing = { Icon(WrtIcons.MoreVert, "menu", Modifier.size(18.dp), tint = Wrt.TextTertiary) },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("MAINTENANCE", tracking = 0.14)
            SystemCard {
                SystemRow(
                    WrtIcons.Backup, "Backup & restore",
                    if (isLive) "Coming soon" else "Last backup 6 d ago",
                )
                if (isLive) {
                    SystemRow(WrtIcons.Firmware, "Firmware", board?.summary?.ifBlank { null } ?: "—")
                } else {
                    SystemRow(
                        WrtIcons.Firmware, "Firmware upgrade", "23.05.3 → 23.05.4 available",
                        subColor = Wrt.Amber,
                        extra = { Box(Modifier.size(6.dp).background(Wrt.Amber, CircleShape)) },
                    )
                }
                SystemRow(
                    WrtIcons.Services, "Services",
                    if (isLive) "Coming soon" else "31 running · 2 stopped",
                )
                SystemRow(WrtIcons.Packages, "Packages", null, sub = {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(74.dp).height(3.dp).background(Wrt.ProgressTrack, RoundedCornerShape(2.dp))) {
                            Box(
                                Modifier
                                    .fillMaxWidth(if (isLive) ((live!!.flashPct) / 100f).coerceIn(0.02f, 1f) else 0.62f)
                                    .height(3.dp)
                                    .background(Wrt.TextTertiary, RoundedCornerShape(2.dp))
                            )
                        }
                        Text(
                            if (isLive) (live!!.flashFree ?: "—") else "38.2 MB free",
                            style = mono(10f, 500, Wrt.TextDim),
                        )
                    }
                })
                SystemRow(WrtIcons.LiveLogs, "Live logs", "logread -f · streaming", onClick = onOpenLogs)
                SystemRow(
                    WrtIcons.Clock, "Scheduled tasks",
                    if (isLive) "Coming soon" else "3 cron jobs",
                    last = true,
                )
            }
            SectionLabel("ROUTER · APP", tracking = 0.14)
            SystemCard {
                ValueRow(
                    "Hostname · uptime", null,
                    if (isLive) {
                        listOfNotNull(
                            board?.hostname?.ifBlank { null } ?: routerName,
                            live!!.uptimeLabel.takeIf { it != "—" },
                        ).joinToString(" · ")
                    } else "home.gw · IST",
                )
                ValueRow(
                    "Country / regulatory domain",
                    "Sets which Wi-Fi channels & TX power are legal",
                    if (isLive) (country?.ifBlank { null } ?: "—") else "IN · India",
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Biometric lock", style = sans(13f, 600))
                        if (isLive) {
                            Text(
                                "Screen lock gates saved credentials on launch",
                                style = sans(10.5f, 400, Wrt.TextDim),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    val on = biometricEnabled ?: biometricDemo
                    WToggle(on) {
                        if (biometricEnabled != null) onBiometricToggle(!on) else biometricDemo = !on
                    }
                }
                Divider()
                ValueRow(
                    "SSH keys", null,
                    when (sshKeyInstalled) {
                        true -> "1 installed"
                        false -> "none — using password"
                        null -> "1 installed"
                    },
                    last = true,
                )
            }
            SectionLabel("DANGER ZONE", color = Wrt.Red, tracking = 0.14)
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                    .background(Wrt.Red.copy(alpha = 0.04f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 14.dp, vertical = 2.dp)
            ) {
                DangerRow(WrtIcons.Warning, "Factory reset", "Erases all settings · type RESET to confirm")
                Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.Red.copy(alpha = 0.15f)))
                DangerRow(WrtIcons.Lightning, "Reflash firmware", "Multi-step wizard · sysupgrade -n", last = true)
            }
        }
    }
}

@Composable
private fun SystemCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderHair))
}

@Composable
private fun SystemRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    subColor: Color = Wrt.TextDim,
    sub: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
    last: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Wrt.TextTertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600))
            if (sub != null) sub()
            else if (subtitle != null) Text(subtitle, style = sans(10.5f, 400, subColor), modifier = Modifier.padding(top = 2.dp))
        }
        extra?.invoke()
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
    if (!last) Divider()
}

@Composable
private fun ValueRow(title: String, subtitle: String?, value: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600))
            if (subtitle != null) Text(subtitle, style = sans(10.5f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 2.dp))
        }
        Text(value, style = mono(10.5f, 500, Wrt.TextTertiary))
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.TextDim)
    }
    if (!last) Divider()
}

@Composable
private fun DangerRow(icon: ImageVector, title: String, subtitle: String, last: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Wrt.Red)
        Column(Modifier.weight(1f)) {
            Text(title, style = sans(13f, 600, Wrt.Red))
            Text(subtitle, style = sans(10.5f, 400, Wrt.DangerSub), modifier = Modifier.padding(top = 2.dp))
        }
        Icon(WrtIcons.ChevronRight, null, Modifier.size(13.dp), tint = Wrt.DangerSub)
    }
}
