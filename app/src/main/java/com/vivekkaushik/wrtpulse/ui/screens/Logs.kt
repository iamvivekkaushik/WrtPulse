package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import com.vivekkaushik.wrtpulse.data.LiveLogs
import com.vivekkaushik.wrtpulse.data.LiveTicker
import com.vivekkaushik.wrtpulse.ui.ConnectionTopBar
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun LogsScreen(ticker: LiveTicker, live: LiveLogs?, liveLatencyMs: Int? = null, routerName: String, onRouterTap: () -> Unit) {
    var sourceFilter by remember { mutableIntStateOf(if (live != null) -1 else 2) }
    var selected by remember { mutableStateOf<com.vivekkaushik.wrtpulse.data.LogLine?>(null) }
    val sources = listOf("kernel", "hostapd", "dnsmasq", "firewall")
    val allLogs = if (live != null) live.logs.toList() else ticker.logs
    val logs =
        if (live != null && sourceFilter >= 0) allLogs.filter { it.src.startsWith(sources[sourceFilter]) }
        else allLogs
    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        ConnectionTopBar(
            routerName = routerName,
            latencyMs = liveLatencyMs ?: ticker.latencyMs,
            onRouterTap = onRouterTap,
            trailing = { Icon(WrtIcons.ShareUp, "export", Modifier.size(17.dp), tint = Wrt.TextTertiary) },
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sources.forEachIndexed { i, s ->
                val selected = i == sourceFilter
                Box(
                    Modifier
                        .let {
                            if (selected) it.background(Wrt.Accent, RoundedCornerShape(13.dp))
                            else it.border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
                        }
                        .clickable { sourceFilter = if (live != null && sourceFilter == i) -1 else i }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(s, style = mono(10.5f, if (selected) 600 else 500, if (selected) Wrt.OnAccent else Wrt.TextSecondary))
                }
            }
            Spacer(Modifier.width(30.dp))
            Box(
                Modifier
                    .border(1.dp, Wrt.Amber.copy(alpha = 0.4f), RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("info+", style = mono(10.5f, 500, Wrt.Amber)) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
                .height(36.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(9.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(".*", style = mono(10f, 600, Wrt.Accent))
            Text(
                if (live != null) (if (sourceFilter >= 0) sources[sourceFilter] else "all sources") else "dhcp|dnsmasq",
                style = mono(11f, 500, Wrt.TextTertiary),
                modifier = Modifier.weight(1f),
            )
            Text(
                if (live != null) "${logs.size} lines" else "214 match",
                style = mono(9.5f, 500, Wrt.TextDim),
            )
        }
        // stream
        Box(Modifier.weight(1f).fillMaxWidth().background(Wrt.BgDeep)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.BorderRow))
            val listState = rememberLazyListState()
            var following by remember { mutableStateOf(true) }
            // any upward scroll by the user pauses tail-follow (motion spec)
            fun clearNew() {
                if (live != null) live.clearNewLines() else ticker.clearNewLines()
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
                    .distinctUntilChanged()
                    .collect { (scrolling, canForward) ->
                        if (scrolling && canForward) following = false
                        if (!canForward) { following = true; clearNew() }
                    }
            }
            LaunchedEffect(logs.size) {
                if (following && logs.isNotEmpty()) {
                    listState.scrollToItem(logs.size - 1)
                    clearNew()
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                items(logs) { ln ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = live != null) { selected = ln }
                            .padding(horizontal = 13.dp, vertical = 3.5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(ln.time, style = mono(9.5f, 500, Wrt.TextFaint, lineHeight = 15.sp))
                        Text(
                            ln.src,
                            style = mono(9.5f, 500, ln.color, lineHeight = 15.sp),
                            maxLines = 1,
                            modifier = Modifier.width(74.dp),
                        )
                        Text(
                            buildAnnotatedString {
                                append(ln.msg)
                                if (ln.tok.isNotEmpty()) {
                                    append(" ")
                                    withStyle(SpanStyle(color = Wrt.TextLogToken)) { append(ln.tok) }
                                }
                            },
                            style = mono(10.5f, 500, Wrt.TextSecondary, lineHeight = 15.sp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            // resume pill
            androidx.compose.animation.AnimatedVisibility(
                visible = !following,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                Box(
                    Modifier
                        .shadow(9.dp, RoundedCornerShape(16.dp))
                        .border(1.dp, Wrt.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .background(Wrt.BgBar, RoundedCornerShape(16.dp))
                        .clickable { following = true }
                        .padding(horizontal = 13.dp, vertical = 6.dp)
                ) {
                    Text("↓ ${live?.newLines ?: ticker.newLines} new lines", style = mono(10.5f, 600, Wrt.Accent))
                }
            }
        }
        // detail / explain card
        if (live != null && selected == null) return@Column
        val clipboard = LocalClipboardManager.current
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(12.dp))
                .background(Wrt.BgCard, RoundedCornerShape(12.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            if (live != null) {
                val ln = selected!!
                Text(ln.raw, style = mono(9.5f, 500, Wrt.TextDim), maxLines = 3)
                Text(
                    "${ln.src} · ${ln.time}",
                    style = sans(12.5f, 400, Wrt.TextPrimary, lineHeight = 19.sp),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Copy line",
                        style = sans(11.5f, 600, Wrt.Accent),
                        modifier = Modifier.clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(ln.raw))
                        },
                    )
                    Text(
                        "Dismiss",
                        style = sans(11.5f, 600, Wrt.TextDim),
                        modifier = Modifier.clickable { selected = null },
                    )
                }
                return@Column
            }
            Text(
                "dnsmasq-dhcp[3121]: DHCPACK(br-lan) 192.168.1.34 aa:5c:1e:88:04:2b",
                style = mono(9.5f, 500, Wrt.TextDim),
                maxLines = 1,
            )
            Text(
                buildAnnotatedString {
                    append("DHCPACK — the router granted ")
                    withStyle(SpanStyle(color = Wrt.Accent, fontFamily = com.vivekkaushik.wrtpulse.ui.theme.MonoFamily, fontSize = 11.5.sp)) {
                        append("192.168.1.34")
                    }
                    append(" to \"pixel-8\" for 12 hours.")
                },
                style = sans(12.5f, 400, Wrt.TextPrimary, lineHeight = 19.sp),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Copy line", style = sans(11.5f, 600, Wrt.Accent))
                Text("Share", style = sans(11.5f, 600, Wrt.Accent))
                Text("What's DHCP?", style = sans(11.5f, 600, Wrt.TextDim))
            }
        }
    }
}
