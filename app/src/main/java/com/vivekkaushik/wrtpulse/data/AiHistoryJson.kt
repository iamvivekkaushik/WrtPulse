package com.vivekkaushik.wrtpulse.data

import com.vivekkaushik.wrtpulse.db.AiAnalysisLog
import org.json.JSONArray
import org.json.JSONObject

/** Machine-readable export of completed analyses and the local diagnostic findings they included. */
object AiHistoryJson {
    const val SCHEMA_VERSION = 1

    fun encode(
        entries: List<AiAnalysisLog>,
        hideSensitive: Boolean,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val records = JSONArray()
        entries.forEach { entry ->
            val findings = runCatching { JSONArray(entry.findingsJson) }
                .getOrElse { JSONArray() }
            records.put(
                JSONObject()
                    .put("router", entry.routerHost)
                    .put("question", entry.question)
                    .put("answer", entry.answer)
                    .put("model", entry.model)
                    .put("createdAtEpochMs", entry.createdAtEpochMs)
                    .put("healthScore", entry.healthScore ?: JSONObject.NULL)
                    .put("findingCount", entry.findingCount)
                    .put("localFindings", findings),
            )
        }
        val json = JSONObject()
            .put("format", "wrtpulse-ai-analysis-history")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAtEpochMs", exportedAtEpochMs)
            .put(
                "privacy",
                JSONObject()
                    .put("rawRouterPayloadIncluded", false)
                    .put("providerApiKeyIncluded", false)
                    .put("networkIdentifierMaskingEnabled", hideSensitive),
            )
            .put("records", records)
            .toString(2)
        return if (hideSensitive) AiReport.sanitize(json) else json
    }
}
