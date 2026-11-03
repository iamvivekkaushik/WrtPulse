package com.vivekkaushik.wrtpulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.vivekkaushik.wrtpulse.data.AiStore
import com.vivekkaushik.wrtpulse.net.AiSettings
import com.vivekkaushik.wrtpulse.ui.FlexSpacer
import com.vivekkaushik.wrtpulse.ui.GhostButton
import com.vivekkaushik.wrtpulse.ui.MonoTag
import com.vivekkaushik.wrtpulse.ui.PrimaryButton
import com.vivekkaushik.wrtpulse.ui.SectionLabel
import com.vivekkaushik.wrtpulse.ui.WrtIcons
import com.vivekkaushik.wrtpulse.ui.mono
import com.vivekkaushik.wrtpulse.ui.sans
import com.vivekkaushik.wrtpulse.ui.theme.Wrt
import kotlinx.coroutines.launch

/**
 * The AI analysis screen: read the router over SSH, show exactly what would be sent, then
 * send it — redacted — to the chat endpoint the operator configured and stream the answer.
 *
 * The app's four principles carry over literally: the command is shown (the payload is the
 * command's output), nothing runs without a press, nothing is irreversible, and the router
 * stays the source of truth — the provider's answer is advice, not state.
 */
@Composable
fun AiAnalysisScreen(
    store: AiStore?,
    settings: AiSettings,
    onBack: () -> Unit,
    onSaveSettings: (AiSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var showPayload by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    // One read on arrival, like every other screen: a snapshot the user has not asked to
    // refresh is exactly as stale as the last time they looked at it.
    LaunchedEffect(store) { if (store?.snapshot == null) store?.collect() }

    Column(Modifier.fillMaxSize().background(Wrt.BgScreen)) {
        FormTopBar("AI analysis", onBack)
        if (store == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Connect to a router to analyze it.", style = sans(12f, 500, Wrt.TextDim))
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            item { HowItWorksCard() }
            item {
                ProviderCard(
                    settings = settings,
                    savedNote = savedNote,
                    onSave = { saved ->
                        onSaveSettings(saved)
                        savedNote = "Saved — analyses will use ${saved.baseUrl.trim().trimEnd('/')}"
                    },
                )
            }
            item {
                DataCard(
                    store = store,
                    showPayload = showPayload,
                    onTogglePayload = { showPayload = !showPayload },
                    onReadAgain = { scope.launch { store.collect() } },
                )
            }
            item {
                AskCard(
                    store = store,
                    settings = settings,
                    question = question,
                    onQuestion = { question = it },
                    onAnalyze = { scope.launch { store.analyze(settings, question) } },
                )
            }
            store.answer?.let { text ->
                item { AnswerCard(store, text) }
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card {
        SectionLabel("HOW IT WORKS", size = 9.5f, tracking = 0.14)
        Text(
            "The router is read over SSH exactly like every other screen — one read-only " +
                "sweep. Wi-Fi keys and passwords are redacted on the phone before anything " +
                "leaves it, and the whole payload is shown below before it is sent. Nothing " +
                "goes to your AI provider until you press Analyze.",
            style = sans(11.5f, 450, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "The answer is advice, not state: the router stays the source of truth.",
            style = sans(10.5f, 500, Wrt.TextDim),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Endpoint, model and key. Any OpenAI-compatible /chat/completions provider works. */
@Composable
private fun ProviderCard(
    settings: AiSettings,
    savedNote: String?,
    onSave: (AiSettings) -> Unit,
) {
    // remember(settings): the fields follow the saved state — a save recomposes the screen
    // with the new value and the fields settle to it; while editing, the old value holds.
    var endpoint by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var key by remember(settings) { mutableStateOf(settings.apiKey) }
    var revealKey by remember { mutableStateOf(false) }

    Card {
        SectionLabel("AI PROVIDER", size = 9.5f, tracking = 0.14)
        Text(
            "Any OpenAI-compatible chat endpoint: OpenAI, Gemini, OpenRouter, Groq, or your own. " +
                "The key is sealed with the phone's keystore, like a router password.",
            style = sans(11f, 450, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 8.dp),
        )
        GhostButton("Use Gemini") {
            endpoint = AiSettings.GEMINI_BASE_URL
            model = AiSettings.GEMINI_MODEL
        }
        FieldLabel("BASE URL")
        FormTextField(endpoint, { endpoint = it })
        FieldLabel("MODEL")
        FormTextField(model, { model = it })
        FieldLabel("API KEY")
        FormTextField(
            key,
            { key = it },
            password = true,
            masked = !revealKey,
            trailing = {
                Icon(
                    WrtIcons.Eye,
                    if (revealKey) "hide the key" else "show the key",
                    Modifier.size(16.dp).clickable { revealKey = !revealKey },
                    tint = Wrt.TextDim,
                )
            },
        )
        if (savedNote != null) {
            Text(savedNote, style = mono(10f, 500, Wrt.Accent), modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            if (settings.configured) "Save provider" else "Save provider to enable analysis",
            color = if (endpoint.isBlank() || model.isBlank()) Wrt.TextDim else Wrt.Accent,
        ) {
            onSave(
                AiSettings(
                    baseUrl = endpoint.trim(),
                    model = model.trim(),
                    apiKey = key.trim(),
                ),
            )
        }
    }
}

/** The collected state: the facts as chips, the payload on demand. */
@Composable
private fun DataCard(
    store: AiStore,
    showPayload: Boolean,
    onTogglePayload: () -> Unit,
    onReadAgain: () -> Unit,
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("ROUTER STATE", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            if (store.collecting) {
                Box(Modifier.size(7.dp).background(Wrt.Accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("reading…", style = mono(9.5f, 600, Wrt.Accent))
            }
        }
        val snap = store.snapshot
        if (snap == null) {
            Text(
                if (store.error != null) store.error!! else "Nothing collected yet.",
                style = sans(11.5f, 450, if (store.error != null) Wrt.Red else Wrt.TextDim),
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                snap.ssids.joinToString(" · ").ifBlank { "no SSIDs configured" },
                style = sans(12f, 600),
                modifier = Modifier.padding(top = 8.dp),
            )
            // Chips scroll in one horizontal line — the payload preview below is where the
            // detail lives, and wrapped chips would push the Read button off small screens.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                snap.chips.forEachIndexed { i, chip ->
                    if (i > 0) Spacer(Modifier.width(6.dp))
                    MonoTag(
                        chip,
                        color = if (chip.startsWith("load") && snap.load1 > 2.0) Wrt.Amber else Wrt.TextSecondary,
                        border = Wrt.BorderInput,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clickable(onClick = onTogglePayload),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showPayload) "Hide what would be sent" else "See exactly what would be sent",
                    style = sans(11f, 600, Wrt.Accent),
                )
                FlexSpacer()
                Icon(
                    if (showPayload) WrtIcons.ChevronUp else WrtIcons.ChevronDown,
                    null,
                    Modifier.size(14.dp),
                    tint = Wrt.Accent,
                )
            }
            if (showPayload) {
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(240.dp)
                        .border(1.dp, Wrt.BorderHair, RoundedCornerShape(9.dp))
                        .background(Wrt.BgCode, RoundedCornerShape(9.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                ) {
                    Text(snap.payloadFor(""), style = mono(9f, 500, Wrt.TextSecondary))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GhostButton(if (store.collecting) "Reading the router…" else "Read the router again") {
            if (!store.collecting) onReadAgain()
        }
    }
}

@Composable
private fun AskCard(
    store: AiStore,
    settings: AiSettings,
    question: String,
    onQuestion: (String) -> Unit,
    onAnalyze: () -> Unit,
) {
    val ready = store.snapshot != null && settings.configured
    Card {
        SectionLabel("ASK", size = 9.5f, tracking = 0.14)
        Text(
            "Optional — a question focuses the analysis. Without one you get the full verdict.",
            style = sans(11f, 450, Wrt.TextSecondary),
            modifier = Modifier.padding(top = 8.dp),
        )
        Box {
            FormTextField(question, onQuestion)
            if (question.isEmpty()) {
                Text(
                    "e.g. why is Wi-Fi slow in the evening?",
                    style = mono(11f, 500, Wrt.TextFaint),
                    modifier = Modifier.padding(start = 12.dp, top = 21.dp),
                )
            }
        }
        if (!settings.configured) {
            Text(
                "Set the provider above first — base URL, model and API key.",
                style = sans(10.5f, 500, Wrt.Amber),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (store.error != null) {
            Text(
                store.error!!,
                style = sans(10.5f, 500, Wrt.Red),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            if (store.analyzing) "Analyzing…" else "Analyze",
            color = if (ready || store.analyzing) Wrt.Accent else Wrt.TextDim,
            textColor = if (ready || store.analyzing) Wrt.OnAccent else Wrt.BgScreen,
        ) {
            if (ready && !store.analyzing) onAnalyze()
        }
    }
}

@Composable
private fun AnswerCard(store: AiStore, text: String) {
    val clipboard = LocalClipboardManager.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("THE ANSWER", size = 9.5f, tracking = 0.14)
            FlexSpacer()
            if (store.analyzing) {
                Box(Modifier.size(7.dp).background(Wrt.Accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("streaming…", style = mono(9.5f, 600, Wrt.Accent))
            } else {
                Icon(
                    WrtIcons.Copy,
                    "copy the answer",
                    Modifier.size(15.dp).clickable { clipboard.setText(AnnotatedString(text)) },
                    tint = Wrt.TextDim,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AnswerText(text)
    }
}

/**
 * The provider's plain-text shape rendered: its own section names (VERDICT:, FINDINGS:,
 * ADVICE:, ANSWER:) become accent headings, dashes become bullets, and everything else is
 * body. No markdown engine — the system prompt asked for none.
 */
@Composable
private fun AnswerText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                HEADER.matches(line) -> Text(line, style = sans(11.5f, 700, Wrt.Accent))
                line.length > 2 && (line[0] == '-' || line[0] == '•' || line[0] == '*') && line[1] == ' ' -> Row {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(4.dp)
                            .background(Wrt.TextTertiary, CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(line.drop(2), style = sans(12f, 450, Wrt.TextSecondary))
                }
                else -> Text(line, style = sans(12f, 450, Wrt.TextSecondary))
            }
        }
    }
}

private val HEADER = Regex("""^[A-Z][A-Z0-9 &/'()-]{1,40}:$""")

/** The panel every block above sits in. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Wrt.BorderCard, RoundedCornerShape(13.dp))
            .background(Wrt.BgCard, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { content() }
}
