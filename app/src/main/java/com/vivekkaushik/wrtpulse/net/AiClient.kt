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
 * OpenRouter, Groq, or a self-hosted gateway. The key is the operator's, kept sealed at rest
 * like a router password, and sent nowhere but to this endpoint.
 */
data class AiSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
) {
    /** "{base}/chat/completions", tolerating a trailing slash on the configured base. */
    val endpoint: String get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    /** Enough to attempt a call: all three fields filled. */
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
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
    private const val READ_TIMEOUT_MS = 120_000

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
    ): String {
        val body = buildRequestBody(settings, system, user).toByteArray(StandardCharsets.UTF_8)
        val connection = try {
            (URL(settings.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
            }
        } catch (e: Exception) {
            throw AiError("That endpoint is not a URL this app can open: ${settings.endpoint}", e)
        }
        return try {
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
