package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Where the analysis goes: any OpenAI-compatible /chat/completions endpoint — OpenAI itself,
 * OpenRouter, Groq, Gemini, a self-hosted gateway, or an Ollama server on the operator's own
 * LAN. The key is the operator's, kept sealed at rest like a router password, and sent nowhere
 * but to this endpoint. A local endpoint needs no key at all: nothing leaves the network.
 */
data class AiSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
) {
    /** "{base}/chat/completions", tolerating a trailing slash on the configured base. */
    val endpoint: String get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** "{base}/models", tolerating the same trailing slash — the list endpoint the dropdown reads. */
    val modelsEndpoint: String get() = baseUrl.trim().trimEnd('/') + "/models"

    /**
     * Enough to attempt a call: base URL and model, plus a key — unless the endpoint is on
     * this network (Ollama, a self-hosted gateway), where "no key" is a valid configuration.
     */
    val configured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || isLocal)

    /** True when the endpoint host is this machine or a private/LAN address. */
    val isLocal: Boolean get() = isLocalHost(hostOf(baseUrl))

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        /** Google Gemini's official OpenAI-compatible Chat Completions endpoint. */
        const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
        const val GEMINI_MODEL = "gemini-3.8-flash"

        /**
         * OpenRouter's endpoint — one key, every provider behind it, the same OpenAI-compatible
         * shapes. The auto model lets OpenRouter route the request; the dropdown the screen now
         * builds from /models offers the 450+ concrete ids the same key can name instead.
         */
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val OPENROUTER_MODEL = "openrouter/auto"

        /**
         * Ollama's OpenAI-compatible endpoint, on the default port. The host is a placeholder —
         * the preset fills it in and the operator edits it to wherever Ollama actually runs,
         * because a router has neither the RAM nor the storage for a model of its own.
         */
        const val OLLAMA_BASE_URL = "http://192.168.1.100:11434/v1"
        const val OLLAMA_MODEL = "llama3.2"

        /** The Authorization header value, or null when the endpoint takes none. */
        fun authorization(apiKey: String): String? =
            apiKey.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" }

        /** The host part of a base URL, or "" — kept dumb on purpose: no DNS here. */
        fun hostOf(baseUrl: String): String = runCatching {
            URL(baseUrl.trim()).host
        }.getOrDefault("")

        /** Loopback, link-local and RFC1918 hosts, plus the names home networks give them. */
        fun isLocalHost(host: String): Boolean {
            val h = host.trim().lowercase().removeSuffix(".")
            if (h.isEmpty()) return false
            if (h == "localhost" || h == "::1" || h == "[::1]") return true
            if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal")) return true
            val m = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(h) ?: return false
            val (a, b) = m.destructured
            val first = a.toIntOrNull() ?: return false
            val second = b.toIntOrNull() ?: return false
            return first == 10 || first == 127 || (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) || (first == 169 && second == 254)
        }
    }
}

/** Every way the AI step can fail, phrased so the screen can show it as-is. */
class AiError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One streamed chat completion over HttpURLConnection — no extra dependency, and the same
 * org.json the SSH parsers already lean on. Pure builders and the SSE line parser are
 * separate functions so tests can drive them without a socket.
 */
object AiChat {

    private const val CONNECT_TIMEOUT_MS = 15_000
    /** Local models can think for a while before their first token; remote ones stream fast. */
    private const val READ_TIMEOUT_MS = 300_000
    /** The model list is small and answers fast; a slow one is a broken one. */
    private const val MODELS_READ_TIMEOUT_MS = 20_000

    /** The one transport rule every call shares: plain http only ever to a host on this network. */
    private fun requireSecureTransport(url: URL) {
        if (url.protocol.equals("http", true) && !AiSettings.isLocalHost(url.host)) {
            throw AiError(
                "Android blocks plain http to protect the API key in transit — and so does this " +
                    "app, even where Android would now allow it. Use an https endpoint, or an " +
                    "http one on your own network (Ollama).",
            )
        }
    }

    /** The POST body for /chat/completions, streaming, low temperature — diagnostics, not prose. */
    fun buildRequestBody(settings: AiSettings, system: String, user: String): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return JSONObject()
            .put("model", settings.model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.2)
            .toString()
    }

    /**
     * One SSE line to the text it carries, or null: `data:` frames with a delta hold text,
     * `data: [DONE]` ends the stream, and anything else (comments, keep-alives, blanks) does
     * not. Malformed JSON answers null rather than throwing — one bad chunk must not kill an
     * answer that is otherwise arriving.
     */
    fun parseSseLine(line: String): String? {
        val frame = line.trim()
        if (!frame.startsWith("data:")) return null
        val payload = frame.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return runCatching {
            JSONObject(payload)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("delta")?.optString("content")
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * Streams one completion; [onDelta] fires per chunk as it arrives, and the full text is
     * returned once the stream closes. Cancellation between chunks unwinds promptly — the
     * read timeout bounds the case where no chunk ever comes.
     */
    suspend fun complete(
        settings: AiSettings,
        system: String,
        user: String,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(settings, system, user).toByteArray(StandardCharsets.UTF_8)
        // Plain http is fine to a host on this network (Ollama's default), and nowhere else:
        // the only credential this app ever puts on the wire is the provider key.
        val url = URL(settings.endpoint)
        requireSecureTransport(url)
        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                AiSettings.authorization(settings.apiKey)?.let { setRequestProperty("Authorization", it) }
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
        } catch (e: Exception) {
            throw AiError("That endpoint is not a URL this app can open: ${settings.endpoint}", e)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code != 200) throw AiError(readError(connection, code, settings))
            val full = StringBuilder()
            // Captured here rather than in the loop: useLines' lambda is not suspend, but
            // ensureActive on the captured context still unwinds a cancelled run at the next
            // chunk — and the read timeout bounds one that never comes.
            val runContext = currentCoroutineContext()
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    runContext.ensureActive()
                    parseSseLine(line)?.let { delta ->
                        full.append(delta)
                        onDelta(delta)
                    }
                }
            }
            if (full.isEmpty()) {
                throw AiError(
                    "The provider streamed no content. Usually the model name is one it does not know: ${settings.model}",
                )
            }
            full.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiError) {
            throw e
        } catch (e: IOException) {
            throw AiError(friendly(e, settings), e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The ids in a GET /models answer: OpenAI, OpenRouter, Gemini's compatibility layer and
     * Ollama all answer `{"data":[{"id":…}]}`; some self-hosted gateways answer a bare array
     * or a `models` key instead, so all three shapes are read. Ids are de-duplicated and
     * sorted, so the dropdown the operator sees is stable however the provider ordered it —
     * and an unrecognised body answers empty rather than throwing, because a model id can
     * always be typed by hand.
     */
    fun parseModelsBody(body: String, freeOnly: Boolean = false): List<String> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val array = when {
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> runCatching { JSONObject(trimmed) }.getOrNull()
                ?.let { it.optJSONArray("data") ?: it.optJSONArray("models") }
        } ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val model = array.optJSONObject(i) ?: continue
            val id = model.optString("id").trim()
            if (id.isNotEmpty() && id != "null" && (!freeOnly || isFreeModel(model, id))) ids += id
        }
        return ids.distinct().sorted()
    }

    /** OpenRouter marks free models with a :free id suffix and/or zero prompt/completion pricing. */
    private fun isFreeModel(model: JSONObject, id: String): Boolean {
        if (id.endsWith(":free", ignoreCase = true)) return true
        val pricing = model.optJSONObject("pricing") ?: return false
        val prompt = pricing.optString("prompt").trim()
        val completion = pricing.optString("completion").trim()
        return prompt.toBigDecimalOrNull() == java.math.BigDecimal.ZERO &&
            completion.toBigDecimalOrNull() == java.math.BigDecimal.ZERO
    }

    /**
     * The provider's own model list, fetched so the screen can offer it as a dropdown instead
     * of asking the operator to know an id by heart. Same transport, same key rules and the
     * same plain-http refusal as [complete]; a provider that implements no /models endpoint
     * answers 404 and the screen falls back to the hand-typed field it always was.
     */
    suspend fun listModels(settings: AiSettings, freeOnly: Boolean = false): List<String> = withContext(Dispatchers.IO) {
        val url = URL(settings.modelsEndpoint)
        requireSecureTransport(url)
        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = MODELS_READ_TIMEOUT_MS
                AiSettings.authorization(settings.apiKey)?.let { setRequestProperty("Authorization", it) }
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            throw AiError("That endpoint is not a URL this app can open: ${settings.modelsEndpoint}", e)
        }
        try {
            val code = connection.responseCode
            if (code != 200) throw AiError(modelsError(connection, code, settings))
            val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            parseModelsBody(body, freeOnly = freeOnly).ifEmpty {
                throw AiError("The provider listed no models at ${settings.modelsEndpoint}.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiError) {
            throw e
        } catch (e: IOException) {
            throw AiError(friendly(e, settings), e)
        } finally {
            connection.disconnect()
        }
    }

    /** What a failed GET /models said — same phrasing as the chat errors, minus the stream. */
    private fun modelsError(connection: HttpURLConnection, code: Int, settings: AiSettings): String {
        val provider = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }?.let { text ->
                JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        return when {
            code == 401 || code == 403 -> "The provider rejected the API key ($code)."
            code == 404 -> "There is no models endpoint at ${settings.modelsEndpoint} — type the model id by hand."
            code == 429 -> "The provider is rate-limiting this key (429)."
            provider != null -> "The provider answered $code: $provider"
            else -> "The provider answered $code."
        }
    }

    /** What a failed HTTP status said, in the provider's own words when it said any. */
    private fun readError(connection: HttpURLConnection, code: Int, settings: AiSettings): String {
        val provider = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }?.let { text ->
                JSONObject(text).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
        return when {
            code == 401 || code == 403 -> "The provider rejected the API key ($code)."
            code == 404 -> "There is no chat/completions endpoint at ${settings.endpoint}. Check the base URL."
            code == 429 -> "The provider is rate-limiting this key (429)."
            provider != null -> "The provider answered $code: $provider"
            else -> "The provider answered $code."
        }
    }

    /** IOExceptions the phone can do something about, said in terms of the thing to fix. */
    private fun friendly(e: IOException, settings: AiSettings): String {
        val detail = e.message ?: e.javaClass.simpleName
        return when {
            "Cleartext" in detail ->
                "Android blocks plain http to protect the API key in transit. Use an https endpoint."
            "timeout" in detail.lowercase() ->
                "The provider did not answer in time: $detail"
            "Unable to resolve" in detail || "UnknownHost" in detail ->
                "Could not resolve ${settings.baseUrl.trim().trimEnd('/')} — the phone is offline or the host is wrong."
            else -> detail
        }
    }
}
