package com.vivekkaushik.wrtpulse.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnit.Companion.Unspecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.ui.theme.MonoFamily
import com.vivekkaushik.wrtpulse.ui.theme.SansFamily
import com.vivekkaushik.wrtpulse.ui.theme.Wrt

// ---------- text style helpers ----------

fun sans(
    size: Float,
    weight: Int = 400,
    color: Color = Wrt.TextPrimary,
    lineHeight: TextUnit = Unspecified,
    letterSpacing: TextUnit = Unspecified,
) = TextStyle(
    fontFamily = SansFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    color = color,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

fun mono(
    size: Float,
    weight: Int = 500,
    color: Color = Wrt.TextPrimary,
    lineHeight: TextUnit = Unspecified,
    letterSpacing: TextUnit = Unspecified,
) = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    color = color,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
)

/** Mono section label, e.g. "HOST" / "MAINTENANCE". */
@Composable
fun SectionLabel(text: String, color: Color = Wrt.TextDim, size: Float = 10f, tracking: Double = 0.12) {
    Text(text, style = mono(size, 600, color, letterSpacing = tracking.em))
}

// ---------- surfaces ----------

@Composable
fun WCard(
    modifier: Modifier = Modifier,
    border: Color = Wrt.BorderCard,
    background: Color = Wrt.BgCard,
    radius: Dp = 13.dp,
    body: @Composable () -> Unit,
) {
    Box(
        modifier
            .border(1.dp, border, RoundedCornerShape(radius))
            .background(background, RoundedCornerShape(radius))
    ) { body() }
}

// ---------- small atoms ----------

/** Status dot with the design's ring-pulse (2.4 s teal/green, 1.6 s amber). Static when [pulse] is false. */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, pulse: Boolean = false, periodMs: Int = 2400) {
    if (!pulse) {
        Box(Modifier.size(size).background(color, CircleShape))
        return
    }
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseT",
    )
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        // ring expands to +7dp over the first 70% of the period while fading out
        val phase = (t / 0.7f).coerceAtMost(1f)
        val ringR = r + 7.dp.toPx() * phase
        val alpha = (1f - phase) * 0.45f
        if (alpha > 0.01f) drawCircle(color.copy(alpha = alpha), radius = ringR)
        drawCircle(color, radius = r)
    }
}

/** 34x19 toggle from the design. */
@Composable
fun WToggle(checked: Boolean, onToggle: (() -> Unit)? = null) {
    val track = if (checked) Wrt.Accent.copy(alpha = 0.16f) else Wrt.TrackOff
    val borderC = if (checked) Wrt.Accent.copy(alpha = 0.5f) else Wrt.TrackOffBorder
    val knob = if (checked) Wrt.Accent else Wrt.KnobOff
    Box(
        Modifier
            .size(34.dp, 19.dp)
            .border(1.dp, borderC, RoundedCornerShape(10.dp))
            .background(track, RoundedCornerShape(10.dp))
            .let { if (onToggle != null) it.clickable { onToggle() } else it }
    ) {
        Box(
            Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(2.dp)
                .size(13.dp)
                .background(knob, CircleShape)
        )
    }
}

/** Rounded filter chip: filled accent when selected, hairline outline otherwise. */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    size: Float = 12f,
    padH: Dp = 13.dp,
    padV: Dp = 6.dp,
    mono: Boolean = false,
    selectedColor: Color = Wrt.Accent,
    onClick: (() -> Unit)? = null,
) {
    val style = if (mono) mono(size, if (selected) 600 else 500) else sans(size, if (selected) 600 else 500)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .let {
                if (selected) it.background(selectedColor, shape)
                else it.border(1.dp, Wrt.BorderCard, shape)
            }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = padH, vertical = padV)
    ) {
        Text(text, style = style.copy(color = if (selected) Wrt.OnAccent else Wrt.TextSecondary))
    }
}

/** Tiny bordered mono tag, e.g. "HOME", "WPA3-SAE", "Casa · 5G". */
@Composable
fun MonoTag(text: String, color: Color = Wrt.TextTertiary, border: Color = Wrt.BorderInput, size: Float = 9f) {
    Box(
        Modifier
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(text, style = mono(size, 600, color, letterSpacing = 0.08.em))
    }
}

/** Wireless signal bars (heights 5/8/11/14, 3dp wide). [bars] of 4 are lit. */
@Composable
fun SignalBars(bars: Int, color: Color = Wrt.Green) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(5, 8, 11, 14).forEachIndexed { i, h ->
            Box(
                Modifier
                    .size(3.dp, h.dp)
                    .background(if (i < bars) color else Wrt.SparkDim, RoundedCornerShape(1.dp))
            )
        }
    }
}

/** Router front-panel glyph (the 2c icon): body outline, two antennas, LED row. */
@Composable
fun RouterGlyph(
    size: Dp,
    bodyColor: Color = Wrt.TextTertiary,
    antennaColor: Color = Wrt.DotOff,
    leds: List<Color> = listOf(Wrt.Green, Wrt.Accent),
    strokeDp: Float = 1.4f,
) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val stroke = Stroke(width = strokeDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // antennas: M7 13V6  M17 13V6
        drawLine(antennaColor, androidx.compose.ui.geometry.Offset(7f * s, 13f * s), androidx.compose.ui.geometry.Offset(7f * s, 6f * s), stroke.width, StrokeCap.Round)
        drawLine(antennaColor, androidx.compose.ui.geometry.Offset(17f * s, 13f * s), androidx.compose.ui.geometry.Offset(17f * s, 6f * s), stroke.width, StrokeCap.Round)
        // body: rect x3 y13 w18 h7 rx2
        drawRoundRect(
            bodyColor,
            topLeft = androidx.compose.ui.geometry.Offset(3f * s, 13f * s),
            size = androidx.compose.ui.geometry.Size(18f * s, 7f * s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * s),
            style = stroke,
        )
        // LEDs at (6.8,16.5) (10,16.5) ...
        leds.forEachIndexed { i, c ->
            drawCircle(c, radius = 0.9f * s, center = androidx.compose.ui.geometry.Offset((6.8f + i * 3.2f) * s, 16.5f * s))
        }
    }
}

/** Small square icon tile used for router avatars. */
@Composable
fun RouterTile(size: Dp = 42.dp, ledColor: Color = Wrt.Accent, dim: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(11.dp))
            .background(Wrt.BgDeep, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        RouterGlyph(
            size = size * 26f / 42f,
            bodyColor = if (dim) Wrt.TextDim else Wrt.TextTertiary,
            antennaColor = if (dim) Wrt.TextDim else Wrt.DotOff,
            leds = listOf(ledColor),
        )
    }
}

// ---------- charts ----------

/** Static sparkline from a fixed point list (design's CPU/RAM mini charts). */
@Composable
fun Sparkline(points: List<Float>, color: Color, modifier: Modifier = Modifier, maxY: Float = 18f) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = w / (points.size - 1)
        val p = Path()
        points.forEachIndexed { i, v ->
            val x = i * step
            val y = v / maxY * h
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, alpha = 0.75f, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Live WAN throughput chart: teal down series with area fill, blue up line,
 * three hairline gridlines. New points append on the right (values in 0..100).
 */
@Composable
fun ThroughputChart(down: List<Float>, up: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // gridlines at 25/50/75%
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(Color(0xFF16211D), androidx.compose.ui.geometry.Offset(0f, h * f), androidx.compose.ui.geometry.Offset(w, h * f), 1.dp.toPx())
        }
        fun linePath(arr: List<Float>): Path {
            val p = Path()
            val step = w / (arr.size - 1)
            arr.forEachIndexed { i, v ->
                val x = i * step
                val y = h - 4.dp.toPx() - (v / 100f) * (h - 10.dp.toPx())
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        val downPath = linePath(down)
        val area = Path().apply {
            addPath(downPath)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(area, Wrt.Accent.copy(alpha = 0.13f))
        drawPath(downPath, Wrt.Accent, style = Stroke(1.6.dp.toPx()))
        drawPath(linePath(up), Wrt.Blue, alpha = 0.85f, style = Stroke(1.2.dp.toPx()))
    }
}

// ---------- buttons ----------

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = Wrt.Accent, textColor: Color = Wrt.OnAccent, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(color, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sans(14.5f, 650, textColor))
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    border: Color = Wrt.BorderCard,
    textColor: Color = Wrt.TextSecondary,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sans(13.5f, 600, textColor))
    }
}

// ---------- app chrome ----------

/** Connected-router top bar: pulsing dot, router name, chevron, live latency chip, trailing icon. */
@Composable
fun ConnectionTopBar(
    routerName: String,
    latencyMs: Int,
    pulse: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onRouterTap: (() -> Unit)? = null,
    chevronUp: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Wrt.BgBar)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.let { if (onRouterTap != null) it.clickable(onClick = onRouterTap) else it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(Wrt.Accent, 8.dp, pulse = pulse)
            Text(routerName, style = sans(14.5f, 650))
            Icon(
                if (chevronUp) WrtIcons.ChevronUp else WrtIcons.ChevronDown,
                null,
                Modifier.size(13.dp),
                tint = Wrt.TextDim,
            )
        }
        Box(Modifier.weight(1f))
        Box(
            Modifier
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text("$latencyMs ms", style = mono(10.5f, 500, Wrt.TextTertiary))
        }
        trailing?.invoke()
    }
    HorizontalHairline()
}

@Composable
fun HorizontalHairline(color: Color = Wrt.BorderHair) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

enum class MainTab(val label: String) {
    Dashboard("Dashboard"), Network("Network"), Clients("Clients"), Terminal("Terminal"), System("System")
}

fun tabIcon(tab: MainTab): ImageVector = when (tab) {
    MainTab.Dashboard -> WrtIcons.Dashboard
    MainTab.Network -> WrtIcons.Network
    MainTab.Clients -> WrtIcons.Clients
    MainTab.Terminal -> WrtIcons.Terminal
    MainTab.System -> WrtIcons.System
}

@Composable
fun WrtBottomNav(current: MainTab, onSelect: (MainTab) -> Unit) {
    Column(Modifier.background(Wrt.BgBar)) {
        HorizontalHairline()
        Row(Modifier.fillMaxWidth().height(62.dp)) {
            MainTab.entries.forEach { tab ->
                val active = tab == current
                val c = if (active) Wrt.Accent else Wrt.TextDim
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(top = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(tabIcon(tab), tab.label, Modifier.size(21.dp), tint = c)
                    Text(tab.label, style = sans(9.5f, if (active) 600 else 500, c))
                }
            }
        }
    }
}

/** Convenience row spacer. */
@Composable
fun RowScope.FlexSpacer() = Box(Modifier.weight(1f))
