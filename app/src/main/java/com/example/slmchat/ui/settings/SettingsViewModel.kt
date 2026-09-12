package com.example.slmchat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.slmchat.data.prefs.AppSettings
import com.example.slmchat.data.prefs.SettingsPreferences
import com.example.slmchat.data.repo.ChatRepository
import com.example.slmchat.llm.LlmManager
import com.example.slmchat.llm.ModelCatalog
import com.example.slmchat.llm.ModelDownloadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val prefs: SettingsPreferences,
    private val repository: ChatRepository,
    private val llmManager: LlmManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = llmManager.downloadStates

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            prefs.setModelId(modelId)
            llmManager.refreshDownloadStates()
            llmManager.loadActiveModel(settings.value.copy(modelId = modelId))
        }
    }

    fun downloadModel(modelId: String) =
        llmManager.downloadModel(modelId, settings.value.customModelUrl)

    fun cancelDownload(modelId: String) = llmManager.cancelDownload(modelId)

    fun deleteModel(modelId: String) = llmManager.deleteModel(modelId)

    fun isDownloaded(modelId: String): Boolean = llmManager.isDownloaded(modelId)

    fun setTemperature(v: Float) = viewModelScope.launch {
        prefs.setTemperature(v)
        reloadEngine()
    }

    fun setTopK(v: Int) = viewModelScope.launch {
        prefs.setTopK(v)
        reloadEngine()
    }

    fun setTopP(v: Float) = viewModelScope.launch {
        prefs.setTopP(v)
        reloadEngine()
    }

    fun setMaxTokens(v: Int) = viewModelScope.launch {
        prefs.setMaxTokens(v)
        reloadEngine()
    }

    fun setSystemPrompt(v: String) = viewModelScope.launch { prefs.setSystemPrompt(v) }

    fun setUseGpu(v: Boolean) = viewModelScope.launch {
        prefs.setUseGpu(v)
        reloadEngine()
    }

    fun setCustomModelUrl(v: String) = viewModelScope.launch { prefs.setCustomModelUrl(v) }

    fun clearAllChats(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.clearAll()
            onDone()
        }
    }

    private suspend fun reloadEngine() {
        // Debounce-free reload: LlmManager serializes native access internally.
        if (ModelCatalog.getById(settings.value.modelId) != null &&
            llmManager.isDownloaded(settings.value.modelId)
        ) {
            llmManager.loadActiveModel(settings.value)
        }
    }

    class Factory(
        private val prefs: SettingsPreferences,
        private val repository: ChatRepository,
        private val llmManager: LlmManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(prefs, repository, llmManager) as T
        }
    }
}
