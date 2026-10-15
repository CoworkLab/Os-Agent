package com.jarvis.assistant.data.provider

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Proves whether a model can actually see by sending one real image and checking
 * the reply against facts that are only knowable by looking at it.
 *
 * Capability is never inferred from a model name: Groq, Ollama and most
 * OpenAI-compatible /models responses carry no vision metadata at all, and name
 * heuristics (e.g. "vl", "vision") are wrong often enough to matter.
 */
class VisionProbe(private val context: Context) {

    companion object {
        private const val TAG = "VisionProbe"
        private const val TEST_ASSET = "vision_test.png"

        // Must match the generator that produced assets/vision_test.png.
        const val EXPECTED_SHAPE = "triangle"
        const val EXPECTED_WORD = "JARVIS"
        const val EXPECTED_CIRCLES = 3

        private val PROMPT = """
            Look at the image carefully and answer with JSON only, no prose.
            Required keys:
            "shape": name of the single large coloured geometric shape,
            "word": the uppercase word written in the image,
            "circles": the number of solid blue circles as an integer.
            Example: {"shape":"square","word":"HELLO","circles":2}
        """.trimIndent()
    }

    private val base64Image: String? by lazy {
        runCatching {
            val bytes = context.assets.open(TEST_ASSET).use { it.readBytes() }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.onFailure { Log.e(TAG, "Vision test image missing: ${it.message}") }.getOrNull()
    }

    /**
     * Sends the bundled test image and scores the reply.
     *
     * A model that cannot accept images fails at the transport level (typically
     * HTTP 400 mentioning image content), which is reported as unsupported rather
     * than as an error the user has to debug.
     */
    suspend fun probe(
        client: AiProviderClient,
        baseUrl: String,
        apiKey: String,
        model: String
    ): VisionProbeResult {
        val image = base64Image
            ?: return VisionProbeResult(false, "Bundled test image $TEST_ASSET is missing from the app assets.")

        val response = client.complete(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = listOf(ChatMessage(role = "user", content = PROMPT)),
            image = ImageAttachment(base64 = image, mimeType = "image/png"),
            maxTokens = 256,
            temperature = 0.0
        )

        return when (response) {
            is ProviderResult.Failure -> {
                val msg = response.message
                if (looksLikeImageRejection(msg)) {
                    VisionProbeResult(false, "This model does not accept images: $msg")
                } else {
                    VisionProbeResult(false, "Vision test could not run: $msg")
                }
            }

            is ProviderResult.Success -> score(response.value)
        }
    }

    /**
     * Scores the reply. Two of the three facts must match: a genuine vision model
     * gets all three, while a text-only model that hallucinated, or a model
     * receiving a broken image, will not reliably hit two independent facts.
     */
    private fun score(raw: String): VisionProbeResult {
        val json = extractJson(raw)
        if (json == null) {
            return VisionProbeResult(
                false,
                "Model replied but not with readable JSON, so vision could not be confirmed.",
                raw
            )
        }

        val lower = raw.lowercase(Locale.ROOT)
        val shapeOk = json.optString("shape").lowercase(Locale.ROOT).contains(EXPECTED_SHAPE) ||
            lower.contains(EXPECTED_SHAPE)
        val wordOk = json.optString("word").uppercase(Locale.ROOT).contains(EXPECTED_WORD) ||
            raw.uppercase(Locale.ROOT).contains(EXPECTED_WORD)
        val circlesOk = json.optInt("circles", -1) == EXPECTED_CIRCLES ||
            Regex("\\b${EXPECTED_CIRCLES}\\b").containsMatchIn(raw)

        val hits = listOf(shapeOk, wordOk, circlesOk).count { it }
        val detail = "shape=$shapeOk word=$wordOk circles=$circlesOk"

        return if (hits >= 2) {
            VisionProbeResult(true, "Vision confirmed ($detail).", raw)
        } else {
            VisionProbeResult(
                false,
                "Model did not read the image correctly ($detail), so vision is treated as unsupported.",
                raw
            )
        }
    }

    /** Pulls the first JSON object out of a reply that may include prose or code fences. */
    private fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    /** Distinguishes "this model has no image support" from a network or auth problem. */
    private fun looksLikeImageRejection(message: String): Boolean {
        val m = message.lowercase(Locale.ROOT)
        return m.contains("image") || m.contains("vision") || m.contains("multimodal") ||
            m.contains("modality") || m.contains("content type") || m.contains("does not support")
    }
}
