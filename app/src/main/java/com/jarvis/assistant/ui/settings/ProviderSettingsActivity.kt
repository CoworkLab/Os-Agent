package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.provider.AiProviderKind
import com.jarvis.assistant.data.provider.ProviderModel
import com.jarvis.assistant.data.provider.ProviderResult
import com.jarvis.assistant.data.provider.VisionProbe
import com.jarvis.assistant.databinding.ActivityProviderSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider picker: choose the reasoning brain, load its real model list, and
 * verify vision with an actual image before it is ever used for a task.
 */
class ProviderSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProviderSettingsBinding
    private val app by lazy { JarvisApp.instance }
    private val preferences by lazy { app.preferences }

    /** Models reported by the provider for the current credentials. */
    private var loadedModels: List<ProviderModel> = emptyList()
    private var selectedModelId: String = ""

    private var suppressSpinnerCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jarvis.assistant.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityProviderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.jarvis.assistant.util.ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        setupProviderSpinner()
        setupModelSpinner()
        setupListeners()
        loadProviderIntoUi(app.activeProviderKind)
    }

    private val providerLabels: List<String> by lazy {
        AiProviderKind.entries.map { "${it.displayName}${if (it.isRealtimeVoice) "  ★" else ""}" }
    }

    private fun setupProviderSpinner() {
        binding.spinnerProvider.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, providerLabels
        )
        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                val kind = AiProviderKind.entries[position]
                preferences.providerId = kind.id
                loadProviderIntoUi(kind)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupModelSpinner() {
        binding.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerCallbacks) return
                val model = loadedModels.getOrNull(position) ?: return
                selectModel(model)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun loadProviderIntoUi(kind: AiProviderKind) {
        suppressSpinnerCallbacks = true
        binding.spinnerProvider.setSelection(AiProviderKind.entries.indexOf(kind))
        suppressSpinnerCallbacks = false

        binding.tvProviderHint.text = buildString {
            append(if (kind.requiresApiKey) "API key required" else "No API key needed")
            if (!kind.isRealtimeVoice) {
                append(" • reasoning only; live voice stays on Gemini")
            }
        }

        binding.etApiKey.setText(preferences.providerApiKey(kind))
        binding.etBaseUrl.setText(preferences.providerBaseUrl(kind))
        binding.tilBaseUrl.visibility =
            if (kind == AiProviderKind.CUSTOM) View.VISIBLE else View.GONE

        // Restore the previously chosen model so the list is useful before reloading.
        selectedModelId = preferences.providerModel(kind)
        loadedModels = if (selectedModelId.isNotBlank()) {
            listOf(ProviderModel(id = selectedModelId, providerId = kind.id))
        } else {
            emptyList()
        }
        refreshModelSpinner()
        binding.tvModelsStatus.text = if (selectedModelId.isNotBlank()) {
            "Saved model: $selectedModelId. Tap Load Models to refresh the full list."
        } else {
            "No models loaded yet."
        }

        refreshVisionUi(kind)
        refreshStatus(kind)
    }

    private fun setupListeners() {
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                Toast.makeText(this, "Key pasted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener { binding.etApiKey.setText("") }

        binding.btnLoadModels.setOnClickListener { loadModels() }

        binding.switchVision.setOnCheckedChangeListener { _, checked ->
            preferences.isVisionUserEnabled = checked
            refreshVisionUi(currentKind())
        }

        binding.btnEnableVision.setOnClickListener { runVisionProbe() }

        binding.btnSaveProvider.setOnClickListener { saveAndApply() }
    }

    private fun currentKind(): AiProviderKind = app.activeProviderKind

    private fun refreshModelSpinner() {
        suppressSpinnerCallbacks = true
        val labels = loadedModels.map { it.id }.ifEmpty { listOf("No models loaded") }
        binding.spinnerModel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val index = loadedModels.indexOfFirst { it.id == selectedModelId }
        if (index >= 0) binding.spinnerModel.setSelection(index)
        suppressSpinnerCallbacks = false
    }

    private fun selectModel(model: ProviderModel) {
        selectedModelId = model.id
        val kind = currentKind()
        preferences.setProviderModel(kind, model.id)
        refreshVisionUi(kind)
        refreshStatus(kind)
    }

    private fun refreshVisionUi(kind: AiProviderKind) {
        binding.switchVision.isChecked = preferences.isVisionUserEnabled

        val verified = preferences.isVisionVerifiedFor(kind, selectedModelId)

        binding.btnEnableVision.isEnabled = selectedModelId.isNotBlank()

        val text = when {
            selectedModelId.isBlank() -> "Load models and pick one first."
            !preferences.isVisionUserEnabled -> "Vision is switched off. The agent will use the accessibility tree only."
            verified -> "Vision verified for $selectedModelId. The agent may look at the screen."
            else -> "Not verified for $selectedModelId. Tap Enable Vision to test with a real image."
        }
        binding.tvVisionStatus.text = text

        val color = when {
            selectedModelId.isBlank() -> R.color.text_muted
            !preferences.isVisionUserEnabled -> R.color.status_amber
            verified -> R.color.status_green
            else -> R.color.text_muted
        }
        binding.tvVisionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, color))
    }

    private fun refreshStatus(kind: AiProviderKind) {
        val key = preferences.providerApiKey(kind)
        val ready = selectedModelId.isNotBlank() && (!kind.requiresApiKey || key.isNotBlank())
        binding.tvStatus.text = buildString {
            append(kind.displayName)
            append(" • ")
            append(if (ready) "ready" else "incomplete")
            if (selectedModelId.isNotBlank()) append(" • ").append(selectedModelId)
        }
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (ready) "#22C55E" else "#EAB308")
        )
    }

    private fun loadModels() {
        val kind = currentKind()
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { kind.defaultBaseUrl }
        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()

        if (baseUrl.isBlank()) {
            Snackbar.make(binding.root, "Enter a base URL first.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (kind.requiresApiKey && apiKey.isBlank()) {
            Snackbar.make(binding.root, "Enter the API key first.", Snackbar.LENGTH_LONG).show()
            return
        }

        binding.btnLoadModels.isEnabled = false
        binding.tvModelsStatus.text = "Loading models from $baseUrl ..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.clientFor(kind).listModels(baseUrl, apiKey)
            }
            binding.btnLoadModels.isEnabled = true

            when (result) {
                is ProviderResult.Failure -> {
                    binding.tvModelsStatus.text = "Failed: ${result.message}"
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_LONG).show()
                }

                is ProviderResult.Success -> {
                    loadedModels = result.value
                    if (loadedModels.isEmpty()) {
                        binding.tvModelsStatus.text = "The provider returned no models for this key."
                        return@launch
                    }
                    // Keep the saved choice if it is still offered.
                    val saved = preferences.providerModel(kind)
                    if (loadedModels.none { it.id == saved }) selectedModelId = loadedModels.first().id
                    else selectedModelId = saved
                    refreshModelSpinner()
                    preferences.setProviderModel(kind, selectedModelId)
                    binding.tvModelsStatus.text = "Loaded ${loadedModels.size} models."
                    refreshVisionUi(kind)
                    refreshStatus(kind)
                }
            }
        }
    }

    private fun runVisionProbe() {
        val kind = currentKind()
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { kind.defaultBaseUrl }
        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val model = selectedModelId

        if (model.isBlank()) {
            Snackbar.make(binding.root, "Pick a model first.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (kind.requiresApiKey && apiKey.isBlank()) {
            Snackbar.make(binding.root, "Enter the API key first.", Snackbar.LENGTH_LONG).show()
            return
        }

        binding.btnEnableVision.isEnabled = false
        binding.tvVisionStatus.text = "Sending a real test image to $model ..."

        lifecycleScope.launch {
            val probe = VisionProbe(this@ProviderSettingsActivity)
            val result = withContext(Dispatchers.IO) {
                probe.probe(app.clientFor(kind), baseUrl, apiKey, model)
            }
            binding.btnEnableVision.isEnabled = true
            preferences.setVisionVerifiedFor(kind, model, result.supported)

            binding.tvVisionStatus.text = if (result.supported) {
                "Vision enabled. ${result.detail}"
            } else {
                "Not supported. ${result.detail}"
            }
            binding.tvVisionStatus.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this@ProviderSettingsActivity,
                    if (result.supported) R.color.status_green else R.color.status_red
                )
            )
            Snackbar.make(
                binding.root,
                if (result.supported) "Vision supported by $model" else "Vision not supported by $model",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun saveAndApply() {
        val kind = currentKind()
        val apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty()

        try {
            preferences.setProviderApiKey(kind, apiKey)
        } catch (e: IllegalStateException) {
            Snackbar.make(binding.root, e.message ?: "Secure storage unavailable.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (baseUrl.isNotBlank()) preferences.setProviderBaseUrl(kind, baseUrl)
        if (selectedModelId.isNotBlank()) preferences.setProviderModel(kind, selectedModelId)

        com.jarvis.assistant.background.JarvisConversationService.clearLiveError()
        com.jarvis.assistant.background.JarvisConversationService.restartSession()

        Toast.makeText(this, "Provider settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
