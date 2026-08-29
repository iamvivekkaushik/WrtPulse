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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.Router
import com.vivekkaushik.wrtpulse.data.RouterStatus
import com.vivekkaushik.wrtpulse.db.RouterEntity
import com.vivekkaushik.wrtpulse.ui.FilterChip
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.RouterTile
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

/** "just now", "4 min ago", "3 h ago", "2 d ago" */
fun agoLabel(epoch: Long, nowEpoch: Long = System.currentTimeMillis() / 1000): String {
    val d = (nowEpoch - epoch).coerceAtLeast(0)
    return when {
        d < 90 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86_400 -> "${d / 3600} h ago"
        else -> "${d / 86_400} d ago"
    }
}

fun RouterEntity.asRouter(connectedHost: String?, connectingHost: String?): Router {
    val status = when (host) {
        connectingHost -> RouterStatus.Reconnecting
        connectedHost -> RouterStatus.Online
        else -> RouterStatus.Saved
    }
    return Router(
        name = name,
        model = model.ifEmpty { host },
        tag = host,
        status = status,
        wanIp = null,
        detail = if (status == RouterStatus.Online) "connected" else agoLabel(lastSeenEpoch),
        switcherDetail = listOf(host, summary.substringBefore(" · ")).filter { it.isNotBlank() }.joinToString(" · "),
        latencyMs = null,
    )
}

@Composable
fun RouterListScreen(
    saved: List<RouterEntity>?,
    connectedHost: String?,
    connectingHost: String?,
    error: String?,
    onOpenRouter: (Router) -> Unit,
    onOpenSaved: (RouterEntity) -> Unit,
    onAdd: () -> Unit,
) {
    var filter by remember { mutableIntStateOf(0) }
    val filters = listOf("All", "Home", "Office", "Parents")
    val count = saved?.size ?: Demo.routers.size
    Box(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Routers", style = sans(17f, 650))
                Text("$count", style = mono(11.5f, 500, Wrt.TextDim))
                FlexSpacer()
                Icon(WrtIcons.Search, "search", Modifier.size(19.dp), tint = Wrt.TextTertiary)
                Spacer(Modifier.size(6.dp))
                Icon(WrtIcons.MoreVert, "more", Modifier.size(19.dp), tint = Wrt.TextTertiary)
            }
            if (saved == null) {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    filters.forEachIndexed { i, f ->
                        FilterChip(f, selected = i == filter, onClick = { filter = i })
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
            }
            if (error != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
                        .background(Wrt.Red.copy(alpha = 0.07f), RoundedCornerShape(11.dp))
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                ) {
                    Text(error, style = sans(12f, 500, Wrt.Red))
                }
                Spacer(Modifier.height(10.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (saved != null) {
                    saved.forEach { e ->
                        RouterCard(
                            e.asRouter(connectedHost, connectingHost),
                            onClick = { onOpenSaved(e) },
                        )
                    }
                    if (saved.isEmpty()) {
                        Text(
                            "No routers saved yet — add one below.",
                            style = mono(11f, 500, Wrt.TextDim),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 16.dp),
                        )
                    }
                } else {
                    val visible = when (filter) {
                        1 -> Demo.routers.filter { it.tag == "HOME" }
                        2 -> Demo.routers.filter { it.tag == "OFFICE" }
                        3 -> Demo.routers.filter { it.tag == "PARENTS" }
                        else -> Demo.routers
                    }
                    visible.forEach { r -> RouterCard(r, onClick = { onOpenRouter(r) }) }
                }
                Spacer(Modifier.height(90.dp))
            }
        }
        // FAB
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 22.dp)
                .shadow(14.dp, RoundedCornerShape(17.dp), ambientColor = Wrt.Accent, spotColor = Wrt.Accent)
                .size(56.dp)
                .background(Wrt.Accent, RoundedCornerShape(17.dp))
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WrtIcons.Plus, "add router", Modifier.size(24.dp), tint = Wrt.OnAccent)
        }
    }
}

@Composable
private fun RouterCard(r: Router, onClick: () -> Unit) {
    val offline = r.status == RouterStatus.Offline
    val borderColor = when {
        r.status == RouterStatus.Online -> Wrt.Accent.copy(alpha = 0.4f)
        offline -> Wrt.BorderHair
        else -> Wrt.BorderCard
    }
    val (dotColor, statusLabel, pulse, periodMs) = when (r.status) {
        RouterStatus.Online -> Quad(Wrt.Green, "online", true, 2400)
        RouterStatus.Reconnecting -> Quad(Wrt.Amber, "connecting", true, 1600)
        RouterStatus.Offline -> Quad(Wrt.DotOff, "offline", false, 0)
        RouterStatus.Saved -> Quad(Wrt.TextTertiary, "saved", false, 0)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .background(if (offline) Wrt.BgCardDim else Wrt.BgCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
            .alpha(if (offline) 0.72f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RouterTile(
            ledColor = when (r.status) {
                RouterStatus.Online -> Wrt.Accent
                RouterStatus.Reconnecting -> Wrt.Amber
                RouterStatus.Offline -> Wrt.TextDim
                RouterStatus.Saved -> Wrt.TextTertiary
            },
            dim = offline,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.name, style = sans(14.5f, 650, if (offline) Wrt.TextSecondary else Wrt.TextPrimary))
                MonoTag(r.tag, color = if (offline) Wrt.TextDim else Wrt.TextTertiary, border = if (offline) Wrt.BorderFaint else Wrt.BorderInput, size = 8.5f)
            }
            Text(r.model, style = sans(11.5f, 400, Wrt.TextDim), modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(dotColor, 7.dp, pulse = pulse, periodMs = periodMs)
                Text(
                    statusLabel,
                    style = sans(11f, 600, when (r.status) {
                        RouterStatus.Online -> Wrt.Green
                        RouterStatus.Reconnecting -> Wrt.Amber
                        else -> Wrt.TextDim
                    }),
                )
            }
            if (r.wanIp != null) Text(r.wanIp, style = mono(11f, 500, Wrt.TextTertiary))
            Text(r.detail, style = sans(11f, 400, Wrt.TextDim))
        }
    }
}

private data class Quad(val c: Color, val s: String, val p: Boolean, val ms: Int)
