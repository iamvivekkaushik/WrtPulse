package com.vivekkaushik.wrtpulse.net

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live spike for the model dropdown, JVM-side. Skipped unless a provider is supplied:
 *
 *   WRTPULSE_MODELS_URL=https://openrouter.ai/api/v1 ./gradlew testDebugUnitTest --tests '*AiModelsSpike*' -i
 *
 * Proves the one thing the offline tests cannot: that GET /models on a real provider answers
 * a body [AiChat.parseModelsBody] turns into the dropdown's list — transport, headers and
 * parsing, the same path the screen runs. OpenRouter lists its models without a key; a key
 * travels only when WRTPULSE_MODELS_KEY is set.
 */
class AiModelsSpike {

    @Test
    fun `the provider's model list parses into dropdown options`() = runBlocking {
        val url = System.getenv("WRTPULSE_MODELS_URL")
        assumeTrue("set WRTPULSE_MODELS_URL to run this spike", !url.isNullOrBlank())
        val key = System.getenv("WRTPULSE_MODELS_KEY").orEmpty()

        val models = AiChat.listModels(AiSettings(baseUrl = url!!, model = "probe", apiKey = key))

        println("=== ${models.size} models from $url ===")
        models.take(5).forEach { println(it) }

        check(models.isNotEmpty()) { "no models parsed" }
        check(models == models.distinct().sorted()) { "list not sorted or not distinct" }
    }
}
