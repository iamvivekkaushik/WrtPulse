package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.db.AiAnalysisLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiHistoryJsonTest {
    private fun entry(answer: String = "weak client aa:bb:cc:dd:ee:ff at 8.8.8.8") =
        AiAnalysisLog(
            id = 7,
            routerIdentity = "private-router-id",
            routerHost = "router.example.net",
            question = "Why is 8.8.8.8 unreachable?",
            answer = answer,
            model = "model-x",
            createdAtEpochMs = 1_735_000_000_000,
            healthScore = 72,
            findingCount = 1,
            findingsJson = """[{"severity":"HIGH","domain":"WIFI","title":"Weak client","evidence":"-82 dBm","verifyCommand":"iwinfo"}]""",
        )

    @Test
    fun `export is valid structured json with diagnosis details and privacy metadata`() {
        val root = JSONObject(AiHistoryJson.encode(listOf(entry()), hideSensitive = false, exportedAtEpochMs = 99))
        assertEquals("wrtpulse-ai-analysis-history", root.getString("format"))
        assertEquals(AiHistoryJson.SCHEMA_VERSION, root.getInt("schemaVersion"))
        assertEquals(99L, root.getLong("exportedAtEpochMs"))
        assertFalse(root.getJSONObject("privacy").getBoolean("rawRouterPayloadIncluded"))
        assertFalse(root.getJSONObject("privacy").getBoolean("providerApiKeyIncluded"))
        val record = root.getJSONArray("records").getJSONObject(0)
        assertEquals("Why is 8.8.8.8 unreachable?", record.getString("question"))
        assertEquals(72, record.getInt("healthScore"))
        assertEquals("Weak client", record.getJSONArray("localFindings").getJSONObject(0).getString("title"))
        assertFalse(record.has("routerIdentity"))
    }

    @Test
    fun `share sanitization masks public addresses and macs while preserving valid json`() {
        val encoded = AiHistoryJson.encode(listOf(entry()), hideSensitive = true)
        val root = JSONObject(encoded)
        assertTrue(root.getJSONObject("privacy").getBoolean("networkIdentifierMaskingEnabled"))
        val record = root.getJSONArray("records").getJSONObject(0)
        assertFalse(record.getString("answer").contains("8.8.8.8"))
        assertFalse(record.getString("answer").contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(record.getString("answer").contains("x.x.x.x"))
        assertTrue(record.getString("answer").contains("aa:bb:cc:XX:XX:XX"))
    }

    @Test
    fun `bad stored findings json falls back to an empty diagnostics array`() {
        val encoded = AiHistoryJson.encode(listOf(entry().copy(findingsJson = "not-json")), hideSensitive = false)
        assertEquals(0, JSONObject(encoded).getJSONArray("records").getJSONObject(0).getJSONArray("localFindings").length())
    }
}
