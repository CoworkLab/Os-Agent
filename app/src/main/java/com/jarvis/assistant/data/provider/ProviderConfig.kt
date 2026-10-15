package com.jarvis.assistant.data.provider

/** Fully resolved connection settings for one provider. */
data class ProviderConfig(
    val kind: AiProviderKind,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * True only when the user has vision switched on AND this exact model passed
     * the real-image probe. Callers must check this before sending any frame.
     */
    val visionUsable: Boolean
) {
    val isReady: Boolean
        get() = model.isNotBlank() && (!kind.requiresApiKey || apiKey.isNotBlank())

    val describe: String
        get() = buildString {
            append(kind.displayName)
            if (model.isNotBlank()) append(" • ").append(model)
            append(if (visionUsable) " • vision" else " • accessibility only")
        }
}
