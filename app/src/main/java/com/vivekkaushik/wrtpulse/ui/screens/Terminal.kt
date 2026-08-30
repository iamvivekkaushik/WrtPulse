package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivekkaushik.wrtpulse.data.Demo
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

/** Hold-to-repeat timings, matched to the platform keyboard's feel. */
private const val REPEAT_START_MS = 400L
private const val REPEAT_INTERVAL_MS = 55L

/** Repeats with no echo change before backspace counts as "nothing left to delete". */
private const val BOUNDARY_REPEATS = 4
private const val BOUNDARY_VOLUME = 0.3f

/**
 * A key press with the platform's keyboard tick. Repeating keys (backspace, arrows) keep
 * firing while held, buzzing every few repeats so a long delete feels like a ratchet rather
 * than one continuous hum.
 */
@Composable
private fun Modifier.keyPress(
    repeat: Boolean = false,
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    onTap: () -> Unit,
): Modifier {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    return this.pointerInput(onTap, repeat) {
        detectTapGestures(
            onPress = {
                view.performHapticFeedback(haptic)
                onTap()
                if (repeat) {
                    val job = scope.launch {
                        delay(REPEAT_START_MS)
                        var fired = 0
                        while (true) {
                            onTap()
                            if (fired++ % 4 == 0) view.performHapticFeedback(haptic)
                            delay(REPEAT_INTERVAL_MS)
                        }
                    }
                    tryAwaitRelease()
                    job.cancel()
                } else {
                    tryAwaitRelease()
                }
            },
        )
    }
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

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    // Watches whether the shell's echo actually shrank; a held backspace that changes
    // nothing means the cursor is already at the start of the input.
    var lastEchoLength by remember { mutableIntStateOf(-1) }
    var unchangedRepeats by remember { mutableIntStateOf(0) }

    fun sendKey(text: String) {
        if (engine == null) return
        val payload = when {
            ctrl && text.length == 1 && text[0].lowercaseChar() in 'a'..'z' ->
                (text[0].lowercaseChar() - 'a' + 1).toChar().toString()
            shift && text.length == 1 && text[0] in 'a'..'z' -> text.uppercase()
            else -> text
        }
        if (ctrl) ctrl = false
        if (shift) shift = false
        scope.launch { engine.send(payload) }
    }

    Box(Modifier.fillMaxSize().background(Wrt.TermBg)) {
        Column(Modifier.fillMaxSize()) {
            // tab strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.dp)
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
                    .padding(12.dp)
            ) {
                if (engine != null) {
                    // The caret follows the cursor row, which the shell moves up to rewrite
                    // a command that wrapped across rows.
                    engine.screen.forEachIndexed { i, line ->
                        if (i == engine.cursorRow) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line, style = mono(11.5f, 400, Wrt.TermText, lineHeight = 19.sp))
                                DisableSelection {
                                    Spacer(Modifier.width(1.dp))
                                    BlinkingCaret()
                                }
                            }
                        } else {
                            Text(line, style = mono(11.5f, 400, Wrt.TermText, lineHeight = 19.sp))
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
                            style = mono(11.5f, 400, Wrt.TermText, lineHeight = 19.sp),
                            modifier = Modifier.padding(top = if (line.isPrompt && i > 0) 6.dp else 0.dp),
                        )
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildAnnotatedString { append(promptPrefix); append(pendingCommand) },
                            style = mono(11.5f, 400, Wrt.TermText, lineHeight = 19.sp),
                        )
                        Spacer(Modifier.width(1.dp))
                        BlinkingCaret()
                    }
                }
            }
            }
            ExtraKeysRow(ctrl, onCtrl = { ctrl = !ctrl }, onKey = ::sendKey)
            TerminalKeyboard(
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
private fun ExtraKeysRow(ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit) {
    Column(Modifier.background(Wrt.TermRowBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.TermRowBorder))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ExtraKey("Esc", 1.3f) { onKey(ESC) }
            ExtraKey("Tab", 1.3f) { onKey("\t") }
            ExtraKey("Ctrl", 1.4f, active = ctrl, onTap = onCtrl)
            ExtraKey("Alt", 1.2f) { }
            ExtraKey("|", 1f) { onKey("|") }
            ExtraKey("/", 1f) { onKey("/") }
            ExtraKey("-", 1f) { onKey("-") }
            ExtraKey("↑", 1f, repeat = true) { onKey(ESC + "[A") }
            ExtraKey("↓", 1f, repeat = true) { onKey(ESC + "[B") }
            ExtraKey("←", 1f, repeat = true) { onKey(ESC + "[D") }
            ExtraKey("→", 1f, repeat = true) { onKey(ESC + "[C") }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wrt.TermRowBorder))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExtraKey(
    label: String,
    weight: Float,
    active: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .height(32.dp)
            .border(1.dp, if (active) Wrt.Accent.copy(alpha = 0.5f) else Wrt.TermExtraKeyBorder, RoundedCornerShape(6.dp))
            .background(if (active) Wrt.Accent.copy(alpha = 0.1f) else Wrt.TermExtraKeyBg, RoundedCornerShape(6.dp))
            .keyPress(repeat = repeat, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = mono(10.5f, 400, if (active) Wrt.Accent else Wrt.TermExtraKeyText))
    }
}

@Composable
private fun TerminalKeyboard(
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
            .padding(start = 6.dp, end = 6.dp, top = 7.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val row1 = if (symbols) "1234567890" else "qwertyuiop"
        val row2 = if (symbols) "-/:;()$&@\"" else "asdfghjkl"
        val row3 = if (symbols) "#+='*<>!?~" else "zxcvbnm"
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row1.forEach { c -> TermKey(shown(c, shift), 1f) { onKey(c.toString()) } }
        }
        Row(Modifier.padding(horizontal = if (symbols) 0.dp else 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { c -> TermKey(shown(c, shift), 1f) { onKey(c.toString()) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TermKey("⇧", 1.4f, alt = true, activeAlt = shift, onTap = onShift)
            row3.forEach { c -> TermKey(shown(c, shift), 1f) { onKey(c.toString()) } }
            TermKey("⌫", 1.4f, alt = true, repeat = true, onTap = onBackspace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TermKey(if (symbols) "abc" else "?123", 1.6f, alt = true, small = true, onTap = onSymbols)
            TermKey("", 5.5f) { onKey(" ") }
            TermKey(".", 1f) { onKey(".") }
            TermKeyEnter(1.6f, onEnter)
        }
    }
}

private fun shown(c: Char, shift: Boolean) = if (shift && c in 'a'..'z') c.uppercaseChar().toString() else c.toString()

@Composable
private fun androidx.compose.foundation.layout.RowScope.TermKey(
    label: String,
    weight: Float,
    alt: Boolean = false,
    small: Boolean = false,
    activeAlt: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .height(36.dp)
            .background(
                when {
                    activeAlt -> Wrt.Accent.copy(alpha = 0.25f)
                    alt -> Wrt.TermKeyAltBg
                    else -> Wrt.TermKeyBg
                },
                RoundedCornerShape(5.dp),
            )
            .keyPress(repeat = repeat, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (label.isNotEmpty()) {
            Text(label, style = mono(if (small) 10f else 11.5f, 400, if (activeAlt) Wrt.Accent else Wrt.TermKeyText))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TermKeyEnter(weight: Float, onTap: () -> Unit) {
    Box(
        Modifier
            .weight(weight)
            .height(36.dp)
            .border(1.dp, Wrt.Accent.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
            .background(Wrt.Accent.copy(alpha = 0.15f), RoundedCornerShape(5.dp))
            // Enter commits a command — a firmer tick than a plain keypress.
            .keyPress(haptic = HapticFeedbackConstants.VIRTUAL_KEY, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text("⏎", style = mono(11f, 400, Wrt.Accent))
    }
}
