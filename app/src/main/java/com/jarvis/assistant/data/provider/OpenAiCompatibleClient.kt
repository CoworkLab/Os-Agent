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
 * Client for any endpoint that speaks the OpenAI chat-completions protocol.
 * Covers Groq, Ollama Cloud, OpenAI, OpenRouter and user-supplied base URLs.
 *
 * The /models response only advertises ids and (sometimes) context length. It never
 * states whether a model accepts images, so vision support is established by
 * [VisionProbe] instead of being inferred here.
 */
class OpenAiCompatibleClient(
    override val kind: AiProviderKind,
    private val httpClient: OkHttpClient = defaultClient()
) : AiProviderClient {

    companion object {
        private const val TAG = "OpenAiCompatClient"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun listModels(
        baseUrl: String,
        apiKey: String
    ): ProviderResult<List<ProviderModel>> = withContext(Dispatchers.IO) {
        val url = "${normalizeBase(baseUrl)}/models"
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ProviderResult.Failure(
                        describeHttpFailure(response.code, body),
                        response.code
                    )
                }
                ProviderResult.Success(parseModels(body))
            }
        } catch (e: Exception) {
            Log.e(TAG, "listModels failed for $url: ${e.message}")
            ProviderResult.Failure("Could not reach $url: ${e.message}")
        }
    }

    /**
     * Handles both the OpenAI envelope {"data":[{"id":...}]} and a bare array,
     * since self-hosted servers sometimes return either.
     */
    private fun parseModels(body: String): List<ProviderModel> {
        val out = mutableListOf<ProviderModel>()
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return out

        val array: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("data")
                ?: JSONObject(trimmed).optJSONArray("models")
                ?: JSONArray()
        }

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank {
                item.optString("name").ifBlank { item.optString("model") }
            }
            if (id.isBlank()) continue
            out += ProviderModel(
                id = id,
                displayName = item.optString("name").ifBlank { id },
                providerId = kind.id,
                contextLength = item.optInt("context_length", 0).takeIf { it > 0 }
                    ?: item.optInt("context_window", 0).takeIf { it > 0 }
            )
        }
        return out.distinctBy { it.id }.sortedBy { it.id }
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
        val url = "${normalizeBase(baseUrl)}/chat/completions"
        try {
            val payload = buildPayload(model, messages, image, maxTokens, temperature)
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ProviderResult.Failure(
                        describeHttpFailure(response.code, body),
                        response.code
                    )
                }
                val text = extractAssistantText(body)
                if (text.isBlank()) {
                    ProviderResult.Failure("Provider returned an empty completion")
                } else {
                    ProviderResult.Success(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "complete failed for $url: ${e.message}")
            ProviderResult.Failure("Request to $url failed: ${e.message}")
        }
    }

    private fun buildPayload(
        model: String,
        messages: List<ChatMessage>,
        image: ImageAttachment?,
        maxTokens: Int,
        temperature: Double
    ): JSONObject {
        val msgs = JSONArray()
        messages.forEachIndexed { index, message ->
            val isLastUser = image != null &&
                index == messages.lastIndex &&
                message.role == "user"
            val obj = JSONObject().put("role", message.role)
            if (isLastUser) {
                // Multimodal content array: text first, then the image part.
                val parts = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", message.content))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:${image.mimeType};base64,${image.base64}")
                        )
                    )
                obj.put("content", parts)
            } else {
                obj.put("content", message.content)
            }
            msgs.put(obj)
        }

        return JSONObject()
            .put("model", model)
            .put("messages", msgs)
            .put("max_completion_tokens", maxTokens)
            .put("temperature", temperature)
    }

    /** Reads choices[0].message.content, tolerating array-form content parts. */
    private fun extractAssistantText(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val choices = json.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: first.optJSONObject("delta") ?: return ""

        val content = message.opt("content")
        if (content is String) return content.trim()
        if (content is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                sb.append(part.optString("text"))
            }
            return sb.toString().trim()
        }
        return message.optString("reasoning_content").trim()
    }

    private fun normalizeBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * Turns a provider error body into something actionable. Providers wrap the
     * real reason in an "error.message" field, and the HTTP code alone is not
     * enough to tell "wrong key" from "model does not accept images".
     */
    private fun describeHttpFailure(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?: JSONObject(body).optString("error")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

        val base = when (code) {
            401, 403 -> "Authentication failed (HTTP $code). Check the API key for this provider."
            404 -> "Endpoint not found (HTTP 404). Check the base URL."
            429 -> "Rate limited or quota exceeded (HTTP 429)."
            in 500..599 -> "Provider server error (HTTP $code)."
            else -> "Request failed (HTTP $code)."
        }
        return if (detail != null) "$base $detail" else base
    }
}
