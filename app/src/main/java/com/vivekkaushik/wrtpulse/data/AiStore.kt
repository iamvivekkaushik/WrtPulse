package com.vivekkaushik.wrtpulse.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vivekkaushik.wrtpulse.net.AiChat
import com.vivekkaushik.wrtpulse.net.AiError
import com.vivekkaushik.wrtpulse.net.AiSettings
import com.vivekkaushik.wrtpulse.net.RouterSession
import com.vivekkaushik.wrtpulse.net.SshException
import com.vivekkaushik.wrtpulse.ops.AiPrompt
import com.vivekkaushik.wrtpulse.ops.Commands
import com.vivekkaushik.wrtpulse.ops.Parsers
import kotlinx.coroutines.CancellationException
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

    /** The user message for a run, question included. */
    fun payloadFor(question: String): String = AiPrompt.buildUserContent(sections, question)
}

/**
 * The AI analysis screen's state: collect the router's state over SSH, then stream a
 * diagnosis from the provider the operator configured. Collection is read-only; the send is
 * explicit and separate — nothing leaves the phone until analyze() is called.
 *
 * The transport is injected as a function so tests can stream a scripted answer without a
 * socket; production hands it [AiChat.complete].
 */
class AiStore(
    private val session: RouterSession,
    private val complete: suspend (AiSettings, String, String, (String) -> Unit) -> String = AiChat::complete,
) {
    var snapshot by mutableStateOf<AiSnapshot?>(null); private set
    var collecting by mutableStateOf(false); private set
    var analyzing by mutableStateOf(false); private set

    /** The answer so far — appended to live while the stream is open. */
    var answer by mutableStateOf<String?>(null); private set

    var error by mutableStateOf<String?>(null); private set

    /** Reads the router once. The data goes stale exactly like the person reading it does. */
    suspend fun collect() {
        if (collecting) return
        collecting = true
        error = null
        try {
            val out = session.exec(Commands.AI_SNAPSHOT, timeoutMs = 45_000)
            ingest(Parsers.sections(out.stdout))
            if (snapshot == null) error = "The router returned nothing to analyze."
        } catch (e: SshException) {
            error = "Couldn't read the router: ${e.message}"
        } finally {
            collecting = false
        }
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
    }

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
            val payload = snap.payloadFor(question)
            answer = complete(settings, AiPrompt.SYSTEM, payload) { delta -> answer += delta }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiError) {
            error = e.message
        } finally {
            analyzing = false
        }
    }

    /** Distinct hardware addresses across both station-dump shapes: `iw` lists each station
     * on its own header line, `iwinfo assoclist` one per row. */
    private fun countMacs(text: String): Int =
        text.lineSequence()
            .mapNotNull { MAC.find(it)?.value }
            .distinct()
            .count()

    private companion object {
        val MAC = Regex("""\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b""")
    }
}
