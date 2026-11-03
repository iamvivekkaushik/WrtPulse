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
}
