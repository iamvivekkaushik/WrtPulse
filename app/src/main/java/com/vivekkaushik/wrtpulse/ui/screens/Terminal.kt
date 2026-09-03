package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.Demo
import com.vivekkaushik.wrtpulse.data.TermEngine
import com.vivekkaushik.wrtpulse.data.TerminalSessions
import com.vivekkaushik.wrtpulse.ui.BlinkingCaret
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.dashedBorder
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ESC = "\u001b"

/**
 * What a key actually puts on the wire. Ctrl folds a letter to its control code and shift
 * upper-cases it; Alt is sent the way terminals have always sent Meta — an ESC in front of
 * whatever the key would have been on its own, so it composes with the other two.
 */
internal fun keyPayload(text: String, ctrl: Boolean, shift: Boolean, alt: Boolean): String {
    val base = when {
        ctrl && text.length == 1 && text[0].lowercaseChar() in 'a'..'z' ->
            (text[0].lowercaseChar() - 'a' + 1).toChar().toString()
        shift && text.length == 1 && text[0] in 'a'..'z' -> text.uppercase()
        else -> text
    }
    return if (alt) ESC + base else base
}

/** Hold-to-repeat timings, matched to the platform keyboard's feel. */
private const val REPEAT_START_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L

/** Repeats with no echo change before backspace counts as "nothing left to delete". */
private const val BOUNDARY_REPEATS = 4
private const val BOUNDARY_VOLUME = 0.3f

/**
 * A key press with the platform's keyboard tick. Repeating keys (backspace, arrows) keep
 * firing while held, buzzing every few repeats so a long delete feels like a ratchet rather
 * than one continuous hum. [onPressedChange] brackets the whole hold, so a key can light up
 * for as long as the finger is on it and let go on release or on a cancelled gesture.
 */
@Composable
private fun Modifier.keyPress(
    repeat: Boolean = false,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    onPressedChange: (Boolean) -> Unit = {},
    onTap: () -> Unit,
): Modifier {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    return this.pointerInput(onTap, repeat) {
        detectTapGestures(
            onPress = {
                onPressedChange(true)
                view.performHapticFeedback(haptic)
                onTap()
                val repeats = if (!repeat) null else scope.launch {
                    delay(REPEAT_START_MS)
                    var fired = 0
                    while (true) {
                        onTap()
                        if (fired++ % 4 == 0) view.performHapticFeedback(haptic)
                        delay(REPEAT_INTERVAL_MS)
                    }
                }
                try {
                    tryAwaitRelease()
                } finally {
                    repeats?.cancel()
                    onPressedChange(false)
                }
            },
        )
    }
}

/**
 * A struck key behaves like the rest of the screen does: it snaps to the same solid accent
 * block [BlinkingCaret] draws, with the glyph knocked out of it, then falls to a held level
 * and decays away on release the way [StatusDot]'s ring does. Nothing eases in and nothing
 * moves — the strike lands on the same frame as the touch.
 */
private const val STRIKE_FALL_MS = 90
private const val STRIKE_DECAY_MS = 260

/** What is left burning in while the finger stays down. */
private const val STRIKE_SUSTAIN = 0.45f

/** 1 at the instant of the strike, [STRIKE_SUSTAIN] while held, 0 once it has decayed. */
@Composable
private fun strikeGlow(pressed: Boolean): Float {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            glow.snapTo(1f)
            glow.animateTo(STRIKE_SUSTAIN, tween(STRIKE_FALL_MS, easing = LinearEasing))
        } else {
            glow.animateTo(0f, tween(STRIKE_DECAY_MS, easing = LinearEasing))
        }
    }
    return glow.value
}
private const val DEL = "\u007f"

private val promptPrefix: AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Wrt.Green)) { append("root@home") }
    withStyle(SpanStyle(color = Wrt.TextDim)) { append(":~# ") }
}

/** A rendered terminal line: either a command after the prompt, or program output. */
data class TermLine(val text: AnnotatedString, val isPrompt: Boolean)

fun initialTerminalLines(): List<TermLine> = listOf(
    TermLine(buildAnnotatedString { append(promptPrefix); append("uptime") }, true),
    TermLine(AnnotatedString(" 14:02:11 up 18 days, 4:12, load average: 0.42, 0.38, 0.31"), false),
    TermLine(buildAnnotatedString { append(promptPrefix); append("ubus call system board | jsonfilter -e '@.model'") }, true),
    TermLine(AnnotatedString("GL.iNet GL-MT6000"), false),
    TermLine(buildAnnotatedString { append(promptPrefix); append("logread -f") }, true),
    TermLine(buildAnnotatedString {
        withStyle(SpanStyle(color = Wrt.TextDim)) { append("Fri Aug 29 14:02:14 ") }
        withStyle(SpanStyle(color = Wrt.Blue)) { append("dnsmasq-dhcp") }
        append("[3121]: DHCPACK(br-lan) 192.168.1.34")
    }, false),
    TermLine(buildAnnotatedString {
        withStyle(SpanStyle(color = Wrt.TextDim)) { append("Fri Aug 29 14:02:17 ") }
        withStyle(SpanStyle(color = Wrt.Amber)) { append("hostapd") }
        append(": wlan1: STA aa:5c:1e:88:04:2b associated")
    }, false),
    TermLine(buildAnnotatedString {
        withStyle(SpanStyle(color = Wrt.TextDim)) { append("Fri Aug 29 14:02:21 ") }
        withStyle(SpanStyle(color = Wrt.Red)) { append("kernel") }
        append(": [162.44] DROP wan in: tcp dpt:23")
    }, false),
)

// Fixed-height chrome, at full size and at the floor it shrinks to. A 1440x3120 phone in
// landscape leaves ~307dp of content: the tab strip, extra-keys row and keyboard at full size
// come to 264dp on their own, and with the bottom nav's 63dp that left the output pane zero
// rows. The chrome gives up its height first, and stops once the output has MIN_OUTPUT.
private val TAB_STRIP = 42.dp to 34.dp
private val EXTRA_KEY = 32.dp to 24.dp
private val EXTRA_PAD = 6.dp to 3.dp
private val KEY = 36.dp to 26.dp
private val KEY_GAP = 5.dp to 3.dp
private val KB_TOP = 7.dp to 4.dp
private val KB_BOTTOM = 9.dp to 5.dp
private val OUTPUT_PAD = 12.dp to 6.dp

/** Roughly four rows of output plus its padding — below this the terminal stops being one. */
internal val MIN_OUTPUT = 100.dp

private const val KEY_ROWS = 4

/** [full] at squeeze 1, the floor at 0. */
private fun Pair<Dp, Dp>.at(squeeze: Float): Dp = lerp(second, first, squeeze)

/** Every height that changes with the window, so one measure drives the whole screen. */
@Immutable
internal data class TermMetrics(
    val squeeze: Float,
    val showExtraKeys: Boolean,
) {
    val tabStrip = TAB_STRIP.at(squeeze)
    val extraKey = EXTRA_KEY.at(squeeze)
    val extraPad = EXTRA_PAD.at(squeeze)
    val key = KEY.at(squeeze)
    val keyGap = KEY_GAP.at(squeeze)
    val kbTop = KB_TOP.at(squeeze)
    val kbBottom = KB_BOTTOM.at(squeeze)
    val outputPad = OUTPUT_PAD.at(squeeze)
}

/** Height of everything but the output pane. Affine in [squeeze], which is what lets us solve it. */
internal fun chromeHeight(squeeze: Float, extraKeys: Boolean): Dp {
    val strip = TAB_STRIP.at(squeeze) + 1.dp
    val extras =
        if (extraKeys) EXTRA_PAD.at(squeeze) * 2 + EXTRA_KEY.at(squeeze) + 2.dp else 0.dp
    val keyboard = KB_TOP.at(squeeze) + KB_BOTTOM.at(squeeze) +
        KEY.at(squeeze) * KEY_ROWS + KEY_GAP.at(squeeze) * (KEY_ROWS - 1)
    return strip + extras + keyboard
}

/** Content height the Terminal needs before anything has to shrink or hide. */
val TerminalRoomyHeight: Dp = chromeHeight(squeeze = 1f, extraKeys = true) + MIN_OUTPUT

/** The one style the grid is drawn in; the pty size is measured from it too. */
private val TERM_LINE_HEIGHT = 19.sp
private val TERM_TEXT = mono(11.5f, 400, Wrt.TermText, lineHeight = TERM_LINE_HEIGHT)

/** Enough glyphs that rounding in a single advance cannot skew the average. */
private const val PROBE_CHARS = 64

/** The caret trails the last glyph on the cursor row, so a column is held back for it. */
private val CARET_RESERVE = 9.dp

internal fun termMetricsFor(available: Dp): TermMetrics {
    // Ctrl/Esc/arrows matter more in a shell than tall letter keys, so they are the last thing
    // dropped — only when even the fully squeezed chrome cannot leave a readable output pane.
    val extraKeys = chromeHeight(squeeze = 0f, extraKeys = true) + MIN_OUTPUT <= available
    val floor = chromeHeight(0f, extraKeys)
    val full = chromeHeight(1f, extraKeys)
    val squeeze = ((available - MIN_OUTPUT - floor) / (full - floor)).coerceIn(0f, 1f)
    return TermMetrics(squeeze, extraKeys)
}

/**
 * Columns and rows for the pty, taken from the pane the output is actually drawn in. The
 * advance is measured off the mono face rather than assumed — the old fixed 48 columns left
 * half a tablet empty and wrapped lines that had room to spare.
 */
@Composable
private fun rememberPtySize(width: Dp, height: Dp, metrics: TermMetrics): IntSize {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(width, height, metrics, measurer, density) {
        val advance =
            measurer.measure(AnnotatedString("M".repeat(PROBE_CHARS)), TERM_TEXT).size.width /
                PROBE_CHARS.toFloat()
        with(density) {
            val textWidth = (width - metrics.outputPad * 2 - CARET_RESERVE).toPx()
            val textHeight = (height - chromeHeight(metrics.squeeze, metrics.showExtraKeys) -
                metrics.outputPad * 2).toPx()
            IntSize(
                (textWidth / advance).toInt().coerceAtLeast(TermEngine.MIN_COLS),
                (textHeight / TERM_LINE_HEIGHT.toPx()).toInt().coerceAtLeast(TermEngine.MIN_ROWS),
            )
        }
    }
}

@Composable
fun TerminalScreen(
    sessions: TerminalSessions?,
    routerName: String,
    lines: SnapshotStateList<TermLine>,
    pendingCommand: String,
    snippetsOpen: Boolean,
    onToggleSnippets: (Boolean) -> Unit,
    onInsertSnippet: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val engine = sessions?.current
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    // Watches whether the shell's echo actually shrank; a held backspace that changes
    // nothing means the cursor is already at the start of the input.
    var lastEchoLength by remember { mutableIntStateOf(-1) }
    var unchangedRepeats by remember { mutableIntStateOf(0) }

    fun sendKey(text: String) {
        if (engine == null) return
        val payload = keyPayload(text, ctrl, shift, alt)
        // Every modifier is one-shot: it applies to this key and lets go.
        ctrl = false
        shift = false
        alt = false
        scope.launch { engine.send(payload) }
    }

    // The height here is what is left after the system bars, the IME and the bottom nav, so
    // it is the honest budget the key rows have to fit inside.
    BoxWithConstraints(Modifier.fillMaxSize().background(Wrt.TermBg)) {
        val metrics = termMetricsFor(maxHeight)
        val pty = rememberPtySize(maxWidth, maxHeight, metrics)
        LaunchedEffect(sessions, pty) { sessions?.resize(pty.width, pty.height) }
        Column(Modifier.fillMaxSize()) {
            // tab strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(metrics.tabStrip)
                    .background(Wrt.TermBarBg)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (sessions == null) {
                        Row(
                            Modifier
                                .border(1.dp, Wrt.Accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .background(Wrt.TermTabActive, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(routerName, style = mono(11f, 400, Wrt.TextPrimary))
                            Text("×", style = mono(11f, 400, Wrt.TextDim))
                        }
                        Box(
                            Modifier
                                .border(1.dp, Wrt.TermTabBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("bpi-r3-lab", style = mono(11f, 400, Wrt.TextDim)) }
                    } else {
                        sessions.tabs.forEachIndexed { i, tab ->
                            val active = i == sessions.selected
                            Row(
                                Modifier
                                    .border(
                                        1.dp,
                                        if (active) Wrt.Accent.copy(alpha = 0.35f) else Wrt.TermTabBorder,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .let { if (active) it.background(Wrt.TermTabActive, RoundedCornerShape(8.dp)) else it }
                                    .clickable { sessions.select(i) }
                                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    if (i == 0) routerName else "$routerName ${i + 1}",
                                    style = mono(11f, 400, if (active) Wrt.TextPrimary else Wrt.TextDim),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                if (!tab.connected) {
                                    Text(
                                        if (tab.error != null) "!" else "…",
                                        style = mono(11f, 600, Wrt.Amber),
                                    )
                                }
                                Text(
                                    "×",
                                    style = mono(12f, 400, Wrt.TextDim),
                                    modifier = Modifier
                                        .keyPress { sessions.close(i) }
                                        .padding(horizontal = 4.dp),
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .border(1.dp, Wrt.TermTabBorder, RoundedCornerShape(8.dp))
                            .let { if (sessions != null) it.keyPress { sessions.open() } else it }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                    ) { Text("+", style = mono(11f, 400, Wrt.TextDim)) }
                }
                Spacer(Modifier.width(10.dp))
                if (engine != null) {
                    Icon(
                        WrtIcons.Paste, "paste",
                        Modifier
                            .size(17.dp)
                            .keyPress {
                                // Trailing newlines are dropped so a pasted command lands at
                                // the prompt for review instead of running itself.
                                val text = clipboard.getText()?.text?.trimEnd('\n', '\r')
                                if (!text.isNullOrEmpty()) scope.launch { engine.send(text) }
                            },
                        tint = Wrt.TextTertiary,
                    )
                    Spacer(Modifier.width(14.dp))
                }
                Icon(
                    WrtIcons.Lightning, "snippets",
                    Modifier.size(17.dp).clickable { onToggleSnippets(!snippetsOpen) },
                    tint = if (snippetsOpen) Wrt.Accent else Wrt.TextTertiary,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.TermBorder))
            // session
            val scroll = rememberScrollState()
            if (engine != null) {
                LaunchedEffect(engine.screen.size, engine.current) {
                    scroll.scrollTo(scroll.maxValue)
                }
            }
            // Long-press the output to select and copy it.
            SelectionContainer(Modifier.weight(1f)) {
            Column(
                Modifier
                    .verticalScroll(scroll)
                    .padding(metrics.outputPad)
            ) {
                if (engine != null) {
                    // The caret follows the cursor row, which the shell moves up to rewrite
                    // a command that wrapped across rows.
                    engine.screen.forEachIndexed { i, line ->
                        if (i == engine.cursorRow) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line, style = TERM_TEXT)
                                DisableSelection {
                                    Spacer(Modifier.width(1.dp))
                                    BlinkingCaret()
                                }
                            }
                        } else {
                            Text(line, style = TERM_TEXT)
                        }
                    }
                    if (engine.error != null) {
                        Text(
                            engine.error!!,
                            style = mono(10.5f, 500, Wrt.Red, lineHeight = 17.sp),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    lines.forEachIndexed { i, line ->
                        Text(
                            line.text,
                            style = TERM_TEXT,
                            modifier = Modifier.padding(top = if (line.isPrompt && i > 0) 6.dp else 0.dp),
                        )
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildAnnotatedString { append(promptPrefix); append(pendingCommand) },
                            style = TERM_TEXT,
                        )
                        Spacer(Modifier.width(1.dp))
                        BlinkingCaret()
                    }
                }
            }
            }
            if (metrics.showExtraKeys) {
                ExtraKeysRow(
                    metrics = metrics,
                    ctrl = ctrl,
                    alt = alt,
                    onCtrl = { ctrl = !ctrl },
                    onAlt = { alt = !alt },
                    onKey = ::sendKey,
                )
            }
            TerminalKeyboard(
                metrics = metrics,
                shift = shift,
                symbols = symbols,
                onShift = { shift = !shift },
                onSymbols = { symbols = !symbols },
                onKey = ::sendKey,
                onBackspace = {
                    val echoed = engine?.current?.length ?: -1
                    if (engine != null && echoed == lastEchoLength) {
                        // Allow for the round trip before calling it stuck, then chirp
                        // every few repeats rather than on every one.
                        if (unchangedRepeats >= BOUNDARY_REPEATS &&
                            (unchangedRepeats - BOUNDARY_REPEATS) % 3 == 0
                        ) {
                            audio?.playSoundEffect(AudioManager.FX_KEYPRESS_INVALID, BOUNDARY_VOLUME)
                        }
                        unchangedRepeats++
                    } else {
                        unchangedRepeats = 0
                    }
                    lastEchoLength = echoed
                    sendKey(DEL)
                },
                onEnter = { sendKey("\r") },
            )
        }
        // snippets overlay
        AnimatedVisibility(snippetsOpen, enter = fadeIn(tween(160)), exit = fadeOut(tween(140))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x8C000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onToggleSnippets(false) }
            )
        }
        AnimatedVisibility(
            snippetsOpen,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it },
            exit = slideOutHorizontally(tween(180)) { it },
        ) {
            SnippetsDrawer(onInsertSnippet)
        }
    }
}

@Composable
private fun SnippetsDrawer(onInsert: (String) -> Unit) {
    Column(
        Modifier
            .width(272.dp)
            .fillMaxHeight()
            .background(Wrt.BgBar)
            .border(1.dp, Wrt.BorderCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snippets", style = sans(15f, 650))
            MonoTag("OPENWRT PACK", color = Wrt.Accent, border = Wrt.Accent.copy(alpha = 0.5f), size = 8f)
        }
        Text("Tap inserts at the prompt", style = sans(10.5f, 400, Wrt.TextDim))
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .border(1.dp, Wrt.BorderCard, RoundedCornerShape(9.dp))
                .background(Wrt.BgDeep, RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(WrtIcons.Search, null, Modifier.size(13.dp), tint = Wrt.TextDim)
            Text("Search snippets", style = sans(11f, 400, Wrt.TextDim))
        }
        Demo.snippets.forEach { s ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (s.highlighted) Wrt.Accent.copy(alpha = 0.4f) else Wrt.BorderCard,
                        RoundedCornerShape(11.dp),
                    )
                    .background(
                        if (s.highlighted) Wrt.Accent.copy(alpha = 0.05f) else Wrt.BgCard,
                        RoundedCornerShape(11.dp),
                    )
                    .clickable { onInsert(s.command) }
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(s.title, style = sans(12.5f, 650))
                    Spacer(Modifier.weight(1f))
                    if (s.highlighted) Icon(WrtIcons.PlayFilled, "run", Modifier.size(13.dp), tint = Wrt.Accent)
                }
                Text(s.command, style = mono(10f, 500, Wrt.TextDim), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .dashedBorder(Wrt.BorderInput, 11.dp)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(WrtIcons.Plus, null, Modifier.size(13.dp), tint = Wrt.TextTertiary)
            Text("New snippet", style = sans(12f, 600, Wrt.TextTertiary))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExtraKeysRow(
    metrics: TermMetrics,
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (String) -> Unit,
) {
    Column(Modifier.background(Wrt.TermRowBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.TermRowBorder))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = metrics.extraPad),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val h = metrics.extraKey
            ExtraKey("Esc", 1.3f, h) { onKey(ESC) }
            ExtraKey("Tab", 1.3f, h) { onKey("\t") }
            ExtraKey("Ctrl", 1.4f, h, active = ctrl, onTap = onCtrl)
            ExtraKey("Alt", 1.2f, h, active = alt, onTap = onAlt)
            ExtraKey("|", 1f, h) { onKey("|") }
            ExtraKey("/", 1f, h) { onKey("/") }
            ExtraKey("-", 1f, h) { onKey("-") }
            ExtraKey("↑", 1f, h, repeat = true) { onKey(ESC + "[A") }
            ExtraKey("↓", 1f, h, repeat = true) { onKey(ESC + "[B") }
            ExtraKey("←", 1f, h, repeat = true) { onKey(ESC + "[D") }
            ExtraKey("→", 1f, h, repeat = true) { onKey(ESC + "[C") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.TermRowBorder))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExtraKey(
    label: String,
    weight: Float,
    height: Dp,
    active: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val glow = strikeGlow(pressed)
    val rest = if (active) Wrt.Accent.copy(alpha = 0.1f) else Wrt.TermExtraKeyBg
    val restBorder = if (active) Wrt.Accent.copy(alpha = 0.5f) else Wrt.TermExtraKeyBorder
    val restText = if (active) Wrt.Accent else Wrt.TermExtraKeyText
    Box(
        Modifier
            .weight(weight)
            .height(height)
            .border(1.dp, lerp(restBorder, Wrt.Accent, glow), RoundedCornerShape(6.dp))
            .background(lerp(rest, Wrt.Accent, glow), RoundedCornerShape(6.dp))
            .keyPress(repeat = repeat, onPressedChange = { pressed = it }, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = mono(10.5f, 400, lerp(restText, Wrt.OnAccent, glow)))
    }
}

@Composable
private fun TerminalKeyboard(
    metrics: TermMetrics,
    shift: Boolean,
    symbols: Boolean,
    onShift: () -> Unit,
    onSymbols: () -> Unit,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Wrt.TermBarBg)
            .padding(start = 6.dp, end = 6.dp, top = metrics.kbTop, bottom = metrics.kbBottom),
        verticalArrangement = Arrangement.spacedBy(metrics.keyGap),
    ) {
        val h = metrics.key
        val row1 = if (symbols) "1234567890" else "qwertyuiop"
        val row2 = if (symbols) "-/:;()$&@\"" else "asdfghjkl"
        val row3 = if (symbols) "#+='*<>!?~" else "zxcvbnm"
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row1.forEach { c -> TermKey(shown(c, shift), 1f, h) { onKey(c.toString()) } }
        }
        Row(Modifier.padding(horizontal = if (symbols) 0.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { c -> TermKey(shown(c, shift), 1f, h) { onKey(c.toString()) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TermKey("⇧", 1.4f, h, alt = true, activeAlt = shift, onTap = onShift)
            row3.forEach { c -> TermKey(shown(c, shift), 1f, h) { onKey(c.toString()) } }
            TermKey("⌫", 1.4f, h, alt = true, repeat = true, onTap = onBackspace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TermKey(if (symbols) "abc" else "?123", 1.6f, h, alt = true, small = true, onTap = onSymbols)
            TermKey("", 5.5f, h) { onKey(" ") }
            TermKey(".", 1f, h) { onKey(".") }
            TermKeyEnter(1.6f, h, onEnter)
        }
    }
}

private fun shown(c: Char, shift: Boolean) = if (shift && c in 'a'..'z') c.uppercaseChar().toString() else c.toString()

@Composable
private fun androidx.compose.foundation.layout.RowScope.TermKey(
    label: String,
    weight: Float,
    height: Dp,
    alt: Boolean = false,
    small: Boolean = false,
    activeAlt: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val glow = strikeGlow(pressed)
    val rest = when {
        activeAlt -> Wrt.Accent.copy(alpha = 0.25f)
        alt -> Wrt.TermKeyAltBg
        else -> Wrt.TermKeyBg
    }
    val restText = if (activeAlt) Wrt.Accent else Wrt.TermKeyText
    Box(
        Modifier
            .weight(weight)
            .height(height)
            .background(lerp(rest, Wrt.Accent, glow), RoundedCornerShape(5.dp))
            .keyPress(repeat = repeat, onPressedChange = { pressed = it }, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = mono(if (small) 10f else 11.5f, 400, lerp(restText, Wrt.OnAccent, glow)),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TermKeyEnter(
    weight: Float,
    height: Dp,
    onTap: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val glow = strikeGlow(pressed)
    Box(
        Modifier
            .weight(weight)
            .height(height)
            .border(1.dp, lerp(Wrt.Accent.copy(alpha = 0.4f), Wrt.Accent, glow), RoundedCornerShape(5.dp))
            .background(
                lerp(Wrt.Accent.copy(alpha = 0.15f), Wrt.Accent, glow),
                RoundedCornerShape(5.dp),
            )
            // Enter commits a command — a firmer tick than a plain keypress.
            .keyPress(
                haptic = HapticFeedbackConstants.VIRTUAL_KEY,
                onPressedChange = { pressed = it },
                onTap = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("⏎", style = mono(11f, 400, lerp(Wrt.Accent, Wrt.OnAccent, glow)))
    }
}
