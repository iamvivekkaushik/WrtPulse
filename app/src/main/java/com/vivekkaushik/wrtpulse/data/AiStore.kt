package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.db.AiAnalysisLog
import com.vivekkaushik.wrtpulse.db.AiAnalysisLogDao
import com.vivekkaushik.wrtpulse.net.AiChat
import com.vivekkaushik.wrtpulse.net.AiError
import com.vivekkaushik.wrtpulse.net.AiSettings
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.AiPrompt
import com.vivekkaushik.wrtpulse.ops.AiSampling
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Finding
import com.vivekkaushik.wrtpulse.ops.HealthScore
import com.vivekkaushik.wrtpulse.ops.LocalDiagnostics
import com.vivekkaushik.wrtpulse.ops.NetworkHealth
import com.vivekkaushik.wrtpulse.ops.Parsers
import com.vivekkaushik.wrtpulse.ops.TemporalStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one sweep of the router produced, after redaction. [sections] is the source of truth —
 * the payload shown to the operator and sent to the provider is built from it on demand, so
 * what they previewed is byte-for-byte what left the phone.
 */
data class AiSnapshot(
    val boardLabel: String,
    val uptimeLabel: String,
    val load1: Double,
    val memPct: Int,
    val flashFreeKb: Long?,
    val wifiClients: Int,
    val leases: Int,
    val warnings: Int,
    val ssids: List<String>,
    /** Redacted section bodies keyed as the router produced them. */
    val sections: Map<String, String>,
) {
    /** The one-line facts shown as chips above the payload. */
    val chips: List<String>
        get() = listOfNotNull(
            boardLabel.ifBlank { null },
            uptimeLabel,
            "load %.2f".format(load1),
            "RAM $memPct%",
            flashFreeKb?.let { "${"%.1f".format(it / 1024.0)} MB free" },
            "$wifiClients wifi client" + if (wifiClients == 1) "" else "s",
            "$leases lease" + if (leases == 1) "" else "s",
            "$warnings warning" + if (warnings == 1) "" else "s",
        )
}

/**
 * The AI analysis screen's state: collect the router's state over SSH, sample its counters
 * across a minute, diagnose locally on the phone, then stream a verdict from the provider the
 * operator configured. Collection and sampling are read-only; the send is explicit and
 * separate — nothing leaves the phone until analyze() is called.
 *
 * The health score and the local findings are recomputed from whatever the latest evidence
 * is: after the sweep, then again after the sample, because "tx failed is 21" only becomes
 * "tx failed grew by 21 this minute" once the second sample lands.
 *
 * The transport is injected as a function so tests can stream a scripted answer without a
 * socket; production hands it [AiChat.complete].
 */
class AiStore(
    private val session: RouterSession,
    private val complete: suspend (AiSettings, String, String, (String) -> Unit) -> String = AiChat::complete,
    private val historyDao: AiAnalysisLogDao? = null,
) {
    var snapshot by mutableStateOf<AiSnapshot?>(null); private set
    /** Phone-clock times are deliberately separate: a sweep can be fresh while a sample is old. */
    var lastSnapshotAtMs by mutableStateOf<Long?>(null); private set
    var lastSampleAtMs by mutableStateOf<Long?>(null); private set
    var unavailableSections by mutableStateOf<List<String>>(emptyList()); private set
    var collecting by mutableStateOf(false); private set
    var analyzing by mutableStateOf(false); private set

    /** The answer so far — appended to live while the stream is open. */
    var answer by mutableStateOf<String?>(null); private set

    var error by mutableStateOf<String?>(null); private set
    var history by mutableStateOf<List<AiAnalysisLog>>(emptyList()); private set
    var historyError by mutableStateOf<String?>(null); private set
    private val historyIdentity: String get() = session.target.pinScope

    /** The 60-second run. [sampleStatus] is the live line the card shows while it runs. */
    var sampling by mutableStateOf(false); private set
    var sampleStatus by mutableStateOf<String?>(null); private set
    var temporal by mutableStateOf<TemporalStats?>(null); private set

    /** Computed on the phone, before and without any provider. */
    var findings by mutableStateOf<List<Finding>>(emptyList()); private set
    var health by mutableStateOf<HealthScore?>(null); private set

    /** Stable labels for the progress strip; no router command is implied by a label. */
    val phase: String
        get() = when {
            collecting -> "reading_router"
            sampling -> "sampling_performance"
            analyzing -> "preparing_solutions"
            snapshot != null -> "ready"
            else -> "idle"
        }

    /** Reads the router once. The data goes stale exactly like the person reading it does. */
    suspend fun collect() {
        if (collecting) return
        collecting = true
        error = null
        try {
            val out = session.exec(Commands.AI_SNAPSHOT, timeoutMs = 45_000)
            ingest(Parsers.sections(out.stdout))
            lastSnapshotAtMs = System.currentTimeMillis()
            if (snapshot == null) error = "The router returned nothing to analyze."
        } catch (e: SshException) {
            error = "Couldn't read the router: ${e.message}"
        } finally {
            collecting = false
        }
    }

    /**
     * The 60-second performance sample: three ticks, [AiSampling.GAP_MS] apart, each one read
     * in its own round trip so the status line is live and a dead link surfaces as a failed
     * tick, not as sixty seconds of silence. Cancelling mid-run keeps what two samples can
     * already prove — the operator watched them arrive.
     */
    suspend fun sample() {
        if (sampling || collecting) return
        sampling = true
        error = null
        val ticks = mutableListOf<AiTick>()
        try {
            repeat(AiSampling.SAMPLE_COUNT) { i ->
                sampleStatus = "reading sample ${i + 1} of ${AiSampling.SAMPLE_COUNT}…"
                val out = session.exec(Commands.AI_TICK, timeoutMs = 20_000)
                ticks += AiTick(Parsers.sections(out.stdout), System.currentTimeMillis())
                lastSampleAtMs = ticks.last().atMs
                ingestTicks(ticks.map { it.sections }, ticks.map { it.atMs })
                if (i < AiSampling.SAMPLE_COUNT - 1) {
                    // A one-second heartbeat keeps the countdown honest and the cancel prompt.
                    repeat((AiSampling.GAP_MS / 1000).toInt()) { s ->
                        sampleStatus = "sample ${i + 1}/${AiSampling.SAMPLE_COUNT} taken — next in ${AiSampling.GAP_MS / 1000 - s}s"
                        delay(1_000)
                    }
                }
            }
            sampleStatus = null
        } catch (e: CancellationException) {
            sampleStatus = "sampling stopped — keeping the ${ticks.size} sample(s) taken"
            throw e
        } catch (e: SshException) {
            error = "Couldn't sample the router: ${e.message}"
            sampleStatus = null
        } finally {
            sampling = false
        }
    }

    /** Test seam: the ticks' sections, in order, with the phone-clock timestamps to match. */
    fun ingestTicks(parts: List<Map<String, String>>, atMs: List<Long> = emptyList()) {
        val samples = parts.mapIndexed { i, p ->
            AiSampling.parseTick(p, atMs.getOrNull(i) ?: (i * AiSampling.GAP_MS))
        }
        temporal = AiSampling.analyze(samples)
        refreshLocal()
    }

    /** Test seam: the sweep's sections, keyed by marker, already redacted into [snapshot]. */
    fun ingest(parts: Map<String, String>) {
        if (parts.isEmpty()) return
        val redacted = LinkedHashMap<String, String>()
        parts.forEach { (name, body) -> redacted[name] = AiPrompt.redact(body) }

        val board = runCatching {
            val o = JSONObject(parts["board"].orEmpty())
            listOf(
                o.optString("model").takeIf { it.isNotBlank() },
                o.optJSONObject("release")?.optString("version")?.takeIf { it.isNotBlank() },
            ).filterNotNull().joinToString(" · ")
        }.getOrNull().orEmpty()

        val info = runCatching { Parsers.systemInfo(parts["info"].orEmpty()) }.getOrNull()
        val ssids = redacted["wireless"].orEmpty().lineSequence()
            .filter { it.contains(".ssid=") }
            .map { it.substringAfter(".ssid=").trim('\'', '"') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        snapshot = AiSnapshot(
            boardLabel = board,
            uptimeLabel = info?.uptimeLabel ?: "—",
            load1 = info?.load1 ?: 0.0,
            memPct = info?.memUsedPercent ?: 0,
            flashFreeKb = redacted["overlay"]?.let { Parsers.overlayAvailKb(it) },
            wifiClients = countMacs(redacted["assoc"].orEmpty()),
            leases = redacted["dhcp"].orEmpty().lineSequence().count { it.isNotBlank() },
            warnings = redacted["log"].orEmpty().lineSequence().count { it.isNotBlank() },
            ssids = ssids,
            sections = redacted,
        )
        val expected = listOf(
            "board", "info", "overlay", "wireless", "assoc", "dhcp", "log",
            "release", "links", "network_status", "radio_caps", "survey", "wifi_scan",
            "wan_diag", "dhcp_diag", "mesh",
        )
        unavailableSections = expected.filter { redacted[it].isNullOrBlank() }
        refreshLocal()
    }

    /**
     * Recomputes the phone-side verdict from the latest evidence. Runs after every sweep and
     * after every sample, so the score and the findings are never a screen behind the data.
     */
    private fun refreshLocal() {
        val snap = snapshot ?: return
        findings = LocalDiagnostics.run(snap.sections, temporal)
        health = NetworkHealth.compute(snap.sections, temporal)
    }

    /**
     * The payload for a run: the sweep's sections plus the phone's own conclusions, built the
     * same way for the preview and the send — what the operator approved is what left.
     */
    fun buildPayload(question: String): String {
        val snap = snapshot ?: return ""
        return AiPrompt.buildUserContent(
            snap.sections,
            question,
            findings.map { "${AiReport.severityTag(it.severity)} — ${it.title} (${it.evidence})" },
            temporal?.summaryLines() ?: emptyList(),
            health?.let { "Network health ${it.overall}/100 (Wi-Fi ${it.wifi}, " +
                "Mesh ${it.mesh ?: "n/a"}, Performance ${it.performance}, Security ${it.security})" },
        )
    }

    /** Everything the exported report needs, gathered at the moment of the export. */
    fun reportParts(question: String, generatedAt: String): ReportParts = ReportParts(
        routerLabel = listOfNotNull(
            snapshot?.boardLabel?.takeIf { it.isNotBlank() },
            session.target.host,
        ).joinToString(" · ").ifBlank { "unknown" },
        generatedAt = generatedAt,
        health = health,
        temporal = temporal,
        findings = findings,
        question = question,
        answer = answer,
    )

    /**
     * Sends the payload and streams the answer into [answer]. A fresh run replaces whatever
     * the previous one left mid-screen; a cancel keeps the partial text — the operator watched
     * it arrive, so deleting it on the way out would gaslight them.
     */
    suspend fun analyze(settings: AiSettings, question: String) {
        val snap = snapshot ?: run {
            error = "Read the router first — there is nothing to analyze yet."
            return
        }
        if (analyzing) return
        analyzing = true
        error = null
        answer = ""
        try {
            val payload = buildPayload(question)
            answer = complete(settings, AiPrompt.SYSTEM, payload) { delta -> answer += delta }
            persistAnswer(settings, question)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiError) {
            error = e.message
        } finally {
            analyzing = false
        }
    }

    /** Loads this router's saved analyses; provider credentials and raw payloads are never stored. */
    suspend fun loadHistory() {
        val dao = historyDao ?: return
        historyError = null
        runCatching { dao.forRouter(historyIdentity).first() }
            .onSuccess { history = it }
            .onFailure { historyError = "Couldn't load saved analyses: ${it.message}" }
    }

    suspend fun deleteHistory(id: Long) {
        val dao = historyDao ?: return
        runCatching {
            dao.delete(id, historyIdentity)
            loadHistory()
        }.onFailure { historyError = "Couldn't delete this analysis: ${it.message}" }
    }

    suspend fun clearHistory() {
        val dao = historyDao ?: return
        runCatching {
            dao.deleteForRouter(historyIdentity)
            loadHistory()
        }.onFailure { historyError = "Couldn't clear analysis history: ${it.message}" }
    }

    /** Displays an archived answer in the normal answer card without contacting the provider. */
    fun showSavedAnswer(savedAnswer: String) {
        answer = savedAnswer
        error = null
    }

    private suspend fun persistAnswer(settings: AiSettings, question: String) {
        val dao = historyDao ?: return
        val result = answer?.takeIf { it.isNotBlank() } ?: return
        historyError = null
        runCatching {
            dao.insert(
                AiAnalysisLog(
                    routerIdentity = historyIdentity,
                    routerHost = session.target.host,
                    question = question.trim(),
                    answer = result,
                    model = settings.model,
                    createdAtEpochMs = System.currentTimeMillis(),
                    healthScore = health?.overall,
                    findingCount = findings.size,
                    findingsJson = JSONArray().apply {
                        findings.forEach { finding ->
                            put(
                                JSONObject()
                                    .put("severity", finding.severity.name)
                                    .put("domain", finding.domain.name)
                                    .put("title", finding.title)
                                    .put("evidence", finding.evidence)
                                    .put("verifyCommand", finding.command),
                            )
                        }
                    }.toString(),
                ),
            )
            dao.trim(historyIdentity, HISTORY_LIMIT)
            loadHistory()
        }.onFailure {
            // A local storage failure must not discard the completed result from the screen.
            historyError = "Analysis completed, but couldn't save it locally: ${it.message}"
        }
    }

    /** One raw tick, kept only long enough to be parsed into a sample. */
    private data class AiTick(val sections: Map<String, String>, val atMs: Long)

    /** Distinct hardware addresses across both station-dump shapes: `iw` lists each station
     * on its own header line, `iwinfo assoclist` one per row. */
    private fun countMacs(text: String): Int =
        text.lineSequence()
            .mapNotNull { MAC.find(it)?.value }
            .distinct()
            .count()

    private companion object {
        const val HISTORY_LIMIT = 50
        val MAC = Regex("""\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")
    }
}
