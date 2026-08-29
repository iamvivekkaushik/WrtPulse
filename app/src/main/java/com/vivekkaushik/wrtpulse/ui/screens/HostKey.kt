package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.StatusDot
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

@Composable
fun HostKeyScreen(
    routerName: String,
    savedKey: String = Demo.SAVED_HOST_KEY,
    presentedKey: String = Demo.NEW_HOST_KEY,
    savedLabel: String = "SAVED · AUG 12",
    subtitle: String? = null,
    onDisconnect: () -> Unit,
    onTrust: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wrt.DangerBg)
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(Wrt.Red, 7.dp)
            Text("CONNECTION BLOCKED", style = mono(10f, 600, Wrt.Red, letterSpacing = 0.16.em))
        }
        Icon(WrtIcons.Shield, null, Modifier.padding(top = 30.dp).size(46.dp), tint = Wrt.Red)
        Text(
            "This router's identity changed",
            style = sans(23f, 650, Wrt.DangerText, lineHeight = 28.sp, letterSpacing = (-0.01).em),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            subtitle
                ?: "$routerName presented a different key than the one saved on Aug 12. Either the router was reset or reflashed — or something between you and it is intercepting the connection.",
            style = sans(13f, 400, Wrt.DangerBody, lineHeight = 21.sp),
            modifier = Modifier.padding(top = 10.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .border(1.dp, Wrt.Red.copy(alpha = 0.35f), RoundedCornerShape(13.dp))
                .background(Wrt.DangerCode, RoundedCornerShape(13.dp))
                .padding(horizontal = 15.dp, vertical = 13.dp)
        ) {
            Text(savedLabel, style = mono(9f, 600, Wrt.DangerDim, letterSpacing = 0.14.em))
            Text(savedKey, style = mono(10.5f, 500, Wrt.DangerMono, lineHeight = 16.sp), modifier = Modifier.padding(top = 5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 11.dp)
                    .height(1.dp)
                    .background(Wrt.Red.copy(alpha = 0.2f))
            )
            Text("PRESENTED · NOW", style = mono(9f, 600, Wrt.Red, letterSpacing = 0.14.em))
            Text(presentedKey, style = mono(10.5f, 500, Wrt.Red, lineHeight = 16.sp), modifier = Modifier.padding(top = 5.dp))
        }
        Text(
            "Did you reset, reflash, or replace this router recently? If not, don't trust it.",
            style = sans(12.5f, 400, Wrt.DangerBody, lineHeight = 19.sp),
            modifier = Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Wrt.Red, RoundedCornerShape(12.dp))
                .clickable(onClick = onDisconnect),
            contentAlignment = Alignment.Center,
        ) {
            Text("Disconnect", style = sans(14.5f, 650, Wrt.OnRed))
        }
        HoldToTrustButton(onTrust)
        Text(
            "Hold 3 s to confirm · WrtPulse never auto-trusts a changed key",
            style = sans(10.5f, 400, Wrt.DangerDim),
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** Destructive confirm per the motion spec: fills over a 3 s hold; releasing early cancels. */
@Composable
private fun HoldToTrustButton(onTrust: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var completed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Wrt.Red.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        val pressed = currentEvent.changes.any { it.pressed }
                        if (pressed && !completed && !progress.isRunning) {
                            scope.launch {
                                progress.animateTo(1f, tween(((1f - progress.value) * 3000).toInt(), easing = LinearEasing))
                                if (progress.value >= 1f) {
                                    completed = true
                                    onTrust()
                                }
                            }
                        } else if (!pressed && !completed) {
                            scope.launch {
                                progress.stop()
                                progress.animateTo(0f, tween(180))
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // radial-ish linear fill while holding
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(progress.value)
                .height(44.dp)
                .background(Wrt.Red.copy(alpha = 0.28f))
        )
        Text("I replaced it — trust the new key", style = sans(13f, 600, Wrt.DangerOutlineText))
    }
}
