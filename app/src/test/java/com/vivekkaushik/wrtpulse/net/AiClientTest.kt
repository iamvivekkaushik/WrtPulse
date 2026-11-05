package com.vivekkaushik.wrtpulse.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientTest {

    @Test
    fun `a data frame with a delta yields its text`() {
        val frame = """data: {"choices":[{"delta":{"content":"VERDICT:"}}]}"""
        assertEquals("VERDICT:", AiChat.parseSseLine(frame))
    }

    @Test
    fun `done frames blanks and comments yield nothing`() {
        assertNull(AiChat.parseSseLine("data: [DONE]"))
        assertNull(AiChat.parseSseLine(""))
        assertNull(AiChat.parseSseLine(": keep-alive"))
        assertNull(AiChat.parseSseLine("event: message"))
    }

    @Test
    fun `a frame without content yields nothing instead of throwing`() {
        assertNull(AiChat.parseSseLine("""data: {"choices":[{"delta":{}}]}"""))
        assertNull(AiChat.parseSseLine("""data: {"choices":[]}"""))
        assertNull(AiChat.parseSseLine("""data: not json at all"""))
        assertNull(AiChat.parseSseLine("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
    }

    @Test
    fun `an empty content chunk yields nothing so blanks never accumulate`() {
        assertNull(AiChat.parseSseLine("""data: {"choices":[{"delta":{"content":""}}]}"""))
    }

    @Test
    fun `the request body is a streaming low-temperature chat`() {
        val body = JSONObject(
            AiChat.buildRequestBody(
                AiSettings(model = "llama-3", apiKey = "k"),
                system = "be brief",
                user = "the router state",
            ),
        )
        assertEquals("llama-3", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(0.2, body.getDouble("temperature"), 1e-9)
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be brief", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("the router state", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `endpoint tolerates trailing slashes and bare hosts`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiSettings(baseUrl = "https://api.openai.com/v1/").endpoint,
        )
        assertEquals(
            "http://192.168.8.1:11434/v1/chat/completions",
            AiSettings(baseUrl = "http://192.168.8.1:11434/v1").endpoint,
        )
    }

    @Test
    fun `Gemini preset points at Google's OpenAI compatible endpoint`() {
        val settings = AiSettings(
            baseUrl = AiSettings.GEMINI_BASE_URL,
            model = AiSettings.GEMINI_MODEL,
            apiKey = "gemini-test-key",
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            settings.endpoint,
        )
        assertEquals("gemini-3.8-flash", settings.model)
        assertTrue(settings.configured)
    }

    @Test
    fun `configured needs all three fields`() {
        // Defaults fill the endpoint and model, so only a blank field can fail it.
        assertFalse(AiSettings().configured)
        assertFalse(AiSettings(baseUrl = "", model = "m", apiKey = "k").configured)
        assertFalse(AiSettings(baseUrl = "https://x", model = "", apiKey = "k").configured)
        assertFalse(AiSettings(baseUrl = "https://x", model = "m", apiKey = "").configured)
        assertFalse(AiSettings(baseUrl = "   ", model = "m", apiKey = "k").configured)
        assertTrue(AiSettings(baseUrl = "https://x", model = "m", apiKey = "k").configured)
        assertTrue(AiSettings(apiKey = "k").configured)
    }

    @Test
    fun `a local endpoint is configured without a key`() {
        val ollama = AiSettings(
            baseUrl = "http://192.168.1.100:11434/v1",
            model = "llama3.2",
            apiKey = "",
        )
        assertTrue(ollama.isLocal)
        assertTrue(ollama.configured)
        // The public endpoint still demands a key with the same field blank.
        assertFalse(AiSettings(baseUrl = "https://api.openai.com/v1", model = "m", apiKey = "").configured)
        assertFalse(AiSettings(baseUrl = "http://example.com:8080/v1", model = "m", apiKey = "").isLocal)
    }

    @Test
    fun `isLocalHost recognises the private ranges and home names`() {
        assertTrue(AiSettings.isLocalHost("127.0.0.1"))
        assertTrue(AiSettings.isLocalHost("localhost"))
        assertTrue(AiSettings.isLocalHost("::1"))
        assertTrue(AiSettings.isLocalHost("10.1.2.3"))
        assertTrue(AiSettings.isLocalHost("172.16.0.1"))
        assertTrue(AiSettings.isLocalHost("172.31.255.255"))
        assertTrue(AiSettings.isLocalHost("192.168.8.1"))
        assertTrue(AiSettings.isLocalHost("169.254.3.4"))
        assertTrue(AiSettings.isLocalHost("mybox.lan"))
        assertTrue(AiSettings.isLocalHost("nas.local"))
        assertFalse(AiSettings.isLocalHost("api.openai.com"))
        assertFalse(AiSettings.isLocalHost("8.8.8.8"))
        assertFalse(AiSettings.isLocalHost("172.32.0.1"))
        assertFalse(AiSettings.isLocalHost("192.169.1.1"))
        assertFalse(AiSettings.isLocalHost(""))
    }

    @Test
    fun `hostOf pulls the host out of a base url`() {
        assertEquals("192.168.1.100", AiSettings.hostOf("http://192.168.1.100:11434/v1"))
        assertEquals("api.openai.com", AiSettings.hostOf("https://api.openai.com/v1"))
        assertEquals("", AiSettings.hostOf("not a url"))
    }

    @Test
    fun `authorization is built only when a key exists`() {
        assertEquals("Bearer sk-x", AiSettings.authorization("sk-x"))
        assertEquals("Bearer sk-x", AiSettings.authorization("  sk-x  "))
        assertNull(AiSettings.authorization(""))
        assertNull(AiSettings.authorization("   "))
    }

    @Test
    fun `Ollama preset points at the local OpenAI compatible endpoint`() {
        val settings = AiSettings(baseUrl = AiSettings.OLLAMA_BASE_URL, model = AiSettings.OLLAMA_MODEL)
        assertEquals("http://192.168.1.100:11434/v1/chat/completions", settings.endpoint)
        assertTrue(settings.isLocal)
        assertTrue(settings.configured)
    }

    @Test
    fun `OpenRouter preset points at the compatibility endpoint`() {
        val settings = AiSettings(
            baseUrl = AiSettings.OPENROUTER_BASE_URL,
            model = AiSettings.OPENROUTER_MODEL,
            apiKey = "sk-or-v1-x",
        )
        assertEquals("https://openrouter.ai/api/v1/chat/completions", settings.endpoint)
        assertEquals("openrouter/auto", settings.model)
        assertFalse(settings.isLocal)
        assertTrue(settings.configured)
    }

    @Test
    fun `the models endpoint tolerates trailing slashes like the chat endpoint`() {
        assertEquals(
            "https://openrouter.ai/api/v1/models",
            AiSettings(baseUrl = "https://openrouter.ai/api/v1/").modelsEndpoint,
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            AiSettings(baseUrl = AiSettings.GEMINI_BASE_URL).modelsEndpoint,
        )
    }

    @Test
    fun `parseModelsBody reads the shape OpenAI OpenRouter Gemini and Ollama share`() {
        val body = """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model"},{"id":"gpt-4o"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), AiChat.parseModelsBody(body))
    }

    @Test
    fun `parseModelsBody reads a bare array and a models key`() {
        assertEquals(listOf("a", "b"), AiChat.parseModelsBody("""[{"id":"b"},{"id":"a"}]"""))
        assertEquals(listOf("m"), AiChat.parseModelsBody("""{"models":[{"id":"m"}]}"""))
    }

    @Test
    fun `parseModelsBody sorts distinct ids and drops entries without one`() {
        val body = """{"data":[{"id":"zeta"},{"id":null},{"id":"alpha"},{"id":"zeta"},""" +
            """{"id":""},{"name":"no id here"},"free",{"id":"mid"}]}"""
        assertEquals(listOf("alpha", "mid", "zeta"), AiChat.parseModelsBody(body))
    }

    @Test
    fun `parseModelsBody filters OpenRouter free models by suffix and pricing`() {
        val body = """
            {"data":[
              {"id":"paid/model","pricing":{"prompt":"0.000001","completion":"0.000002"}},
              {"id":"provider/model:free","pricing":{"prompt":"0.1","completion":"0.1"}},
              {"id":"zero-priced/model","pricing":{"prompt":"0","completion":"0"}}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("provider/model:free", "zero-priced/model"),
            AiChat.parseModelsBody(body, freeOnly = true),
        )
        assertEquals(3, AiChat.parseModelsBody(body).size)
    }

    @Test
    fun `parseModelsBody yields nothing on bodies it cannot read`() {
        assertTrue(AiChat.parseModelsBody("").isEmpty())
        assertTrue(AiChat.parseModelsBody("   ").isEmpty())
        assertTrue(AiChat.parseModelsBody("not json at all").isEmpty())
        assertTrue(AiChat.parseModelsBody("""{"data":"nope"}""").isEmpty())
        assertTrue(AiChat.parseModelsBody("""{"unrelated":true}""").isEmpty())
        assertTrue(AiChat.parseModelsBody("""{"data":[]}""").isEmpty())
    }
}
