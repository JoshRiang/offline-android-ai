package com.example.slmchat.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.slmchat.llm.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Snapshot of all user-tunable generation settings. */
data class AppSettings(
    val modelId: String = ModelCatalog.DEFAULT_MODEL_ID,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    // TOTAL context window (prompt + output) for MediaPipe. TinyLlama ctx 2048.
    val maxTokens: Int = 2048,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val useGpu: Boolean = true,
    /** Optional user-supplied direct download URL overriding the catalog URL. */
    val customModelUrl: String = ""
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, concise assistant running fully on-device. " +
                "Answer clearly and keep responses focused."
    }
}

class SettingsPreferences(private val context: Context) {

    private object Keys {
        val MODEL_ID = stringPreferencesKey("model_id")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = floatPreferencesKey("top_p")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val USE_GPU = booleanPreferencesKey("use_gpu")
        val CUSTOM_MODEL_URL = stringPreferencesKey("custom_model_url")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            modelId = prefs[Keys.MODEL_ID] ?: ModelCatalog.DEFAULT_MODEL_ID,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            topK = prefs[Keys.TOP_K] ?: 40,
            topP = prefs[Keys.TOP_P] ?: 0.9f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 2048,
            systemPrompt = prefs[Keys.SYSTEM_PROMPT]
                ?: AppSettings.DEFAULT_SYSTEM_PROMPT,
            useGpu = prefs[Keys.USE_GPU] ?: true,
            customModelUrl = prefs[Keys.CUSTOM_MODEL_URL] ?: ""
        )
    }

    suspend fun setModelId(modelId: String) {
        context.settingsStore.edit { it[Keys.MODEL_ID] = modelId }
    }

    suspend fun setTemperature(value: Float) {
        context.settingsStore.edit { it[Keys.TEMPERATURE] = value.coerceIn(0f, 2f) }
    }

    suspend fun setTopK(value: Int) {
        context.settingsStore.edit { it[Keys.TOP_K] = value.coerceIn(1, 100) }
    }

    suspend fun setTopP(value: Float) {
        context.settingsStore.edit { it[Keys.TOP_P] = value.coerceIn(0.01f, 1f) }
    }

    suspend fun setMaxTokens(value: Int) {
        context.settingsStore.edit { it[Keys.MAX_TOKENS] = value.coerceIn(128, 4096) }
    }

    suspend fun setSystemPrompt(value: String) {
        context.settingsStore.edit { it[Keys.SYSTEM_PROMPT] = value }
    }

    suspend fun setUseGpu(value: Boolean) {
        context.settingsStore.edit { it[Keys.USE_GPU] = value }
    }

    suspend fun setCustomModelUrl(value: String) {
        context.settingsStore.edit { it[Keys.CUSTOM_MODEL_URL] = value.trim() }
    }
}
