package com.jarvis.assistant.data.provider

/** A single text turn sent to a chat-completions style provider. */
data class ChatMessage(
    val role: String,
    val content: String
)

/** Optional image attachment for a chat turn (base64, no data-URI prefix). */
data class ImageAttachment(
    val base64: String,
    val mimeType: String = "image/png"
)

/**
 * One provider endpoint. Implementations are stateless; credentials and base URL
 * are passed per call so a single instance can serve several configured profiles.
 */
interface AiProviderClient {

    val kind: AiProviderKind

    /** GET {base}/models. Returns the raw model ids plus any advertised metadata. */
    suspend fun listModels(baseUrl: String, apiKey: String): ProviderResult<List<ProviderModel>>

    /**
     * Sends one prompt, optionally with an image, and returns the assistant text.
     * Used both for the vision capability probe and for agent reasoning.
     */
    suspend fun complete(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        image: ImageAttachment? = null,
        maxTokens: Int = 1024,
        temperature: Double = 0.6
    ): ProviderResult<String>
}
