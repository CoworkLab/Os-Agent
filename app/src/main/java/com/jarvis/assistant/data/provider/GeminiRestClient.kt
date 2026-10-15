package com.jarvis.assistant.data.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gemini REST access for model listing and one-shot completions.
 *
 * Live voice streaming stays in [com.jarvis.assistant.network.GeminiLiveWebSocket];
 * this class only covers the plain REST surface used by the settings screen and
 * the capability probe.
 */
class GeminiRestClient(
    override val kind: AiProviderKind = AiProviderKind.GEMINI,
    private val httpClient: OkHttpClient = defaultClient()
) : AiProviderClient {

    companion object {
        private const val TAG = "GeminiRestClient"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String
    ): ProviderResult<List<ProviderModel>> = withContext(Dispatchers.IO) {
        val root = BASE.removeSuffix("/v1beta").ifBlank { BASE }
        val url = "$BASE/models?pageSize=200&key=$apiKey"
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ProviderResult.Failure(
                        describeFailure(response.code, body), response.code
                    )
                }
                val models = JSONArray()
                runCatching { JSONObject(body).optJSONArray("models") ?: JSONArray() }
                    .getOrDefault(models)
                val out = mutableListOf<ProviderModel>()
                for (i in 0 until models.length()) {
                    val item = models.optJSONObject(i) ?: continue
                    val name = item.optString("name").removePrefix("models/")
                    if (name.isBlank()) continue
                    val methods = item.optJSONArray("supportedGenerationMethods")
                    val methodsText = buildString {
                        if (methods != null) {
                            for (m in 0 until methods.length()) append(methods.optString(m)).append(' ')
                        }
                    }
                    // Only surface models that can actually generate content.
                    if (!methodsText.contains("generateContent")) continue
                    out += ProviderModel(
                        id = name,
                        displayName = item.optString("displayName").ifBlank { name },
                        providerId = kind.id,
                        contextLength = item.optInt("inputTokenLimit", 0).takeIf { it > 0 }
                    )
                }
                ProviderResult.Success(out.distinctBy { it.id }.sortedBy { it.id })
            }
        } catch (e: Exception) {
            Log.e(TAG, "listModels failed: ${e.message}")
            ProviderResult.Failure("Could not reach Gemini: ${e.message}")
        }
    }

    override suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        image: ImageAttachment?,
        maxTokens: Int,
        temperature: Double
    ): ProviderResult<String> = withContext(Dispatchers.IO) {
        val modelId = if (model.startsWith("models/")) model else "models/$model"
        val url = "$BASE/$modelId:generateContent?key=$apiKey"
        try {
            val contents = JSONArray()
            messages.forEach { msg ->
                contents.put(
                    JSONObject()
                        .put("role", if (msg.role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
                )
            }
            if (image != null) {
                // Attach the image to the final user turn.
                val last = contents.optJSONObject(contents.length() - 1)
                val parts = last?.optJSONArray("parts") ?: JSONArray()
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", image.mimeType)
                            .put("data", image.base64)
                    )
                )
                last?.put("parts", parts)
            }

            val payload = JSONObject()
                .put("contents", contents)
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
                )

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ProviderResult.Failure(
                        describeFailure(response.code, body), response.code
                    )
                }
                val text = runCatching {
                    JSONObject(body).optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.let { parts ->
                            buildString {
                                for (i in 0 until parts.length()) {
                                    append(parts.optJSONObject(i)?.optString("text").orEmpty())
                                }
                            }
                        }.orEmpty()
                }.getOrDefault("")
                if (text.isBlank()) {
                    ProviderResult.Failure("Gemini returned no text (the model may have blocked the image)")
                } else {
                    ProviderResult.Success(text.trim())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed: ${e.message}")
            ProviderResult.Failure("Gemini request failed: ${e.message}")
        }
    }

    private fun describeFailure(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val base = when (code) {
            400 -> "Gemini rejected the request (HTTP 400)."
            401, 403 -> "Gemini authentication failed (HTTP $code). Check the API key."
            429 -> "Gemini rate limited (HTTP 429)."
            else -> "Gemini request failed (HTTP $code)."
        }
        return if (detail != null) "$base $detail" else base
    }
}
