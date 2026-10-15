package com.jarvis.assistant.data.provider

/** A model reported by a provider, with capability information discovered at runtime. */
data class ProviderModel(
    val id: String,
    val displayName: String = id,
    val providerId: String,
    /**
     * True when a real image probe has confirmed the model accepts image input.
     * Only ever set from an actual API response, never guessed from the model name.
     */
    val visionVerified: Boolean = false,
    val contextLength: Int? = null
) {
    val shortName: String
        get() = displayName.substringAfterLast('/').ifBlank { displayName }
}

/** Result of a provider call that is either a value or an error message. */
sealed class ProviderResult<out T> {
    data class Success<T>(val value: T) : ProviderResult<T>()
    data class Failure(val message: String, val httpCode: Int? = null) : ProviderResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.value
    val errorMessage: String? get() = (this as? Failure)?.message
}

/** Capability probe outcome, kept separate so the UI can explain the reason. */
data class VisionProbeResult(
    val supported: Boolean,
    val detail: String,
    val rawResponse: String? = null
)
