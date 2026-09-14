package com.vivekkaushik.wrtpulse.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlin.math.hypot

/** One router in the topology diagram, as much as the drawing needs to know. */
data class TopoNode(
    val name: String,
    val wired: Boolean,
    val online: Boolean,
    val signalDbm: Int?,
    val sync: TopoSync,
)

enum class TopoSync { Ok, Stale, Unknown }

/** True when the system has animations switched off: draw final states, let colour carry the status. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Arc colour for a mesh link: green from −60 dBm up, amber to −72, red below. */
fun signalColor(dbm: Int?): Color = when {
    dbm == null -> Wrt.TextFaint
    dbm >= -60 -> Wrt.Green
    dbm >= -72 -> Wrt.Amber
    else -> Wrt.Red
}

/**
 * The live map at the top of the Mesh page: the primary on the left, one glyph per node on
 * the right. A wired backhaul is a line with a short accent segment running along it; a
 * wireless one is two sets of arcs lit in sequence and coloured by signal. Offline nodes dim
 * and lose their motion. A badge on each node says whether its Wi-Fi copy is current; during
 * a push it turns into a small spinner. The diagram draws itself in once, staggered per node.
 *
 * With system animations off everything renders in its final state and colour alone carries
 * the status.
 */
@Composable
fun MeshTopology(
    primaryName: String,
    nodes: List<TopoNode>,
    pushing: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val measurer = rememberTextMeasurer()
    val loop = rememberInfiniteTransition(label = "topology")
    val dash by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "dash",
    )
    val arc by loop.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1550, easing = LinearEasing), RepeatMode.Restart), label = "arc",
    )
    val spin by loop.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    // Draw-in: 0 → 1 over the whole choreography, restarted when the set of nodes changes.
    val key = nodes.joinToString { it.name + it.wired }
    val enter = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!reduced) enter.animateTo(1f, tween(500 + 120 * nodes.size + 300, easing = FastOutSlowInEasing)) }
    val labelStyle = mono(10f, 500, Wrt.TextSecondary)
    val primaryLabel = remember(primaryName) { primaryName }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val u = size.width / 300f
        val hUnits = size.height / u
        val n = nodes.size
        val cy = hUnits / 2f - 10f
        val ys = if (n <= 1) listOf(cy) else nodes.indices.map { 34f + it * ((hUnits - 70f) / (n - 1)) }
        val stroke = 1.5.dp.toPx()
        val total = 0.5f + 0.12f * n + 0.3f
        fun phase(start: Float, length: Float): Float =
            if (reduced) 1f else ((enter.value * total - start) / length).coerceIn(0f, 1f)

        // Primary
        val pAlpha = phase(0f, 0.4f)
        glyph(Offset(24f * u, cy * u), 44f * u, 18f * u, Wrt.Green, pAlpha, stroke)
        label(measurer, primaryLabel, 46f * u, (cy + 26f) * u, labelStyle.copy(color = Wrt.TextSecondary.copy(alpha = pAlpha)))

        if (n == 0) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(3f * u, 5f * u))
            drawLine(Wrt.TextFaint, Offset(68f * u, cy * u), Offset(224f * u, cy * u), stroke, pathEffect = dashed)
            drawRoundRect(
                Wrt.TextFaint, Offset(224f * u, (cy - 7f) * u), Size(34f * u, 14f * u), CornerRadius(4f * u),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * u, 4f * u))),
            )
            label(measurer, "no nodes yet", 241f * u, (cy + 24f) * u, labelStyle.copy(color = Wrt.TextDim))
            return@Canvas
        }

        nodes.forEachIndexed { i, nd ->
            val ny = ys[i]
            val nx = 224f
            val live = nd.online
            val start = 0.15f + i * 0.12f
            val linkP = phase(start, 0.5f)
            val glyphA = phase(start + 0.3f, 0.4f)
            if (nd.wired) {
                val a = Offset(68f * u, cy * u)
                val b = Offset(nx * u, ny * u)
                val end = Offset(a.x + (b.x - a.x) * linkP, a.y + (b.y - a.y) * linkP)
                drawLine(if (live) Wrt.BorderInput else Wrt.TextFaint, a, end, stroke)
                if (live && !reduced && linkP >= 1f) {
                    val len = hypot(b.x - a.x, b.y - a.y)
                    val seg = 12f * u
                    val head = (len + seg) * dash - seg
                    val s0 = head.coerceIn(0f, len)
                    val s1 = (head + seg).coerceIn(0f, len)
                    if (s1 > s0) {
                        val dir = Offset((b.x - a.x) / len, (b.y - a.y) / len)
                        drawLine(Wrt.Accent, a + dir * s0, a + dir * s1, stroke, cap = StrokeCap.Round)
                    }
                }
            } else {
                val col = if (live) signalColor(nd.signalDbm) else Wrt.TextFaint
                listOf(8f, 14f, 20f).forEachIndexed { j, r ->
                    val alpha = when {
                        linkP < 1f -> linkP * 0.6f
                        !live || reduced -> if (live) 0.6f else 1f
                        else -> arcAlpha(arc, j * 0.15f / 1.55f)
                    }
                    val c = col.copy(alpha = alpha)
                    val rr = r * u
                    drawArc(c, -45f, 90f, false, Offset((72f - r) * u, (cy - r) * u), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(c, 135f, 90f, false, Offset((nx - 4f - r) * u, (ny - r) * u), Size(rr * 2, rr * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (linkP >= 1f) {
                    label(
                        measurer,
                        if (live) "${nd.signalDbm ?: "—"} dBm" else "link down",
                        150f * u, (ny - 6f) * u,
                        labelStyle.copy(color = if (live) col else Wrt.TextFaint),
                    )
                }
                if (!live) drawCircle(Wrt.Red, 2.5f * u, Offset((nx + 34f) * u, (ny + 8f) * u))
            }
            val nodeAlpha = glyphA * (if (live) 1f else 0.4f)
            glyph(Offset(nx * u, ny * u), 34f * u, 14f * u, if (live) Wrt.Green else Wrt.TextFaint, nodeAlpha, stroke)
            label(measurer, nd.name, (nx + 17f) * u, (ny + 22f) * u, labelStyle.copy(color = (if (live) Wrt.TextSecondary else Wrt.TextDim).copy(alpha = glyphA)))
            val badge = Offset((nx + 34f) * u, (ny - 7f) * u)
            when {
                pushing && live -> drawArc(
                    Wrt.Accent.copy(alpha = glyphA), if (reduced) 0f else spin, 250f, false,
                    Offset(badge.x - 3.5f * u, badge.y - 3.5f * u), Size(7f * u, 7f * u), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round),
                )
                nd.sync != TopoSync.Unknown -> {
                    drawCircle(Wrt.BgDeep, 3f * u + stroke / 2, badge)
                    drawCircle((if (nd.sync == TopoSync.Ok) Wrt.Green else Wrt.Amber).copy(alpha = glyphA), 3f * u, badge)
                }
            }
        }
    }
}

/** The design's arc keyframes: rests at 30 %, peaks at 12 % of the loop, back to rest by 38 %. */
private fun arcAlpha(t: Float, delay: Float): Float {
    val x = ((t - delay) % 1f + 1f) % 1f
    return when {
        x < 0.12f -> 0.3f + 0.7f * (x / 0.12f)
        x < 0.38f -> 1f - 0.7f * ((x - 0.12f) / 0.26f)
        else -> 0.3f
    }
}

private fun DrawScope.glyph(at: Offset, w: Float, h: Float, pip: Color, alpha: Float, stroke: Float) {
    if (alpha <= 0f) return
    drawRoundRect(
        Wrt.TextSecondary.copy(alpha = alpha), Offset(at.x, at.y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()),
        style = Stroke(stroke),
    )
    drawCircle(pip.copy(alpha = alpha), 2.dp.toPx(), Offset(at.x + w - 6.dp.toPx(), at.y - h / 2 + 5.dp.toPx()))
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, cx: Float, baseline: Float, style: androidx.compose.ui.text.TextStyle) {
    if (style.color.alpha <= 0f) return
    val laid = measurer.measure(AnnotatedString(text), style)
    drawText(laid, topLeft = Offset(cx - laid.size.width / 2f, baseline - laid.size.height * 0.8f))
}
