package com.jarvis.assistant.data.provider

/**
 * A selectable AI backend.
 *
 * [isRealtimeVoice] marks providers that can drive the live, barge-in capable
 * voice loop. Only Gemini exposes a bidirectional audio streaming API, so every
 * other provider acts as the reasoning/vision brain while voice stays on Gemini.
 */
enum class AiProviderKind(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val supportsModelListing: Boolean,
    val isRealtimeVoice: Boolean,
    val requiresApiKey: Boolean,
    val keyHint: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini (Live Voice)",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        supportsModelListing = true,
        isRealtimeVoice = true,
        requiresApiKey = true,
        keyHint = "AIza..."
    ),
    GROQ(
        id = "groq",
        displayName = "Groq Cloud",
        defaultBaseUrl = "https://api.groq.com/openai/v1",
        supportsModelListing = true,
        isRealtimeVoice = false,
        requiresApiKey = true,
        keyHint = "gsk_..."
    ),
    OLLAMA_CLOUD(
        id = "ollama_cloud",
        displayName = "Ollama Cloud",
        defaultBaseUrl = "https://ollama.com/v1",
        supportsModelListing = true,
        isRealtimeVoice = false,
        requiresApiKey = true,
        keyHint = "Ollama API key"
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        supportsModelListing = true,
        isRealtimeVoice = false,
        requiresApiKey = true,
        keyHint = "sk-..."
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        supportsModelListing = true,
        isRealtimeVoice = false,
        requiresApiKey = true,
        keyHint = "sk-or-..."
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom (OpenAI-compatible)",
        defaultBaseUrl = "",
        supportsModelListing = true,
        isRealtimeVoice = false,
        requiresApiKey = false,
        keyHint = "Optional for local servers"
    );

    val usesOpenAiCompatibleApi: Boolean
        get() = this != GEMINI

    companion object {
        fun fromId(id: String?): AiProviderKind =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}
