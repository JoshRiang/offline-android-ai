package com.example.slmchat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.slmchat.data.local.Conversation
import com.example.slmchat.data.local.Message
import com.example.slmchat.data.local.MessageRole
import com.example.slmchat.data.prefs.AppSettings
import com.example.slmchat.data.prefs.SettingsPreferences
import com.example.slmchat.data.repo.ChatRepository
import com.example.slmchat.llm.EngineState
import com.example.slmchat.llm.LlmManager
import com.example.slmchat.llm.ModelCatalog
import com.example.slmchat.llm.ModelDownloadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Chat screen state holder. Owns the active conversation, message streaming,
 * and model load/download orchestration (delegated to [LlmManager]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val prefs: SettingsPreferences,
    private val llmManager: LlmManager
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> =
        repository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val engineState: StateFlow<EngineState> = llmManager.engineState
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = llmManager.downloadStates

    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<Message>> =
        _activeConversationId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeMessages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    /** Live tokens of the in-flight reply (also persisted progressively). */
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var generateJob: Job? = null

    init {
        viewModelScope.launch {
            // Auto-select the most recent conversation once loaded.
            conversations.collect { list ->
                if (_activeConversationId.value == null && list.isNotEmpty()) {
                    _activeConversationId.value = list.first().id
                }
            }
        }
        viewModelScope.launch {
            // Load the engine whenever the selected model changes.
            var lastModel: String? = null
            settings.collect { s ->
                if (s.modelId != lastModel) {
                    lastModel = s.modelId
                    llmManager.refreshDownloadStates()
                    llmManager.loadActiveModel(s)
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _input.value = text
    }

    fun clearError() {
        _error.value = null
    }

    fun selectConversation(id: Long) {
        if (_isGenerating.value) return
        _activeConversationId.value = id
    }

    fun newConversation() {
        if (_isGenerating.value) return
        viewModelScope.launch {
            val id = repository.createConversation()
            _activeConversationId.value = id
        }
    }

    fun deleteConversation(id: Long) {
        if (_isGenerating.value) return
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) {
                _activeConversationId.value = repository.observeConversations().let { flow ->
                    // Pick next most-recent conversation after delete.
                    var next: Long? = null
                    conversations.value.firstOrNull { it.id != id }?.let { next = it.id }
                    next
                }
            }
        }
    }

    fun downloadActiveModel() {
        val modelId = settings.value.modelId
        llmManager.downloadModel(modelId, settings.value.customModelUrl)
    }

    fun cancelActiveDownload() = llmManager.cancelDownload(settings.value.modelId)

    fun stopGenerating() {
        generateJob?.cancel()
        llmManager.cancelGeneration()
        _isGenerating.value = false
    }

    fun send() {
        val text = _input.value.trim()
        if (text.isBlank() || _isGenerating.value) return
        val modelId = settings.value.modelId
        if (!llmManager.isDownloaded(modelId)) {
            _error.value = "Model “${ModelCatalog.requireById(modelId).name}” is not downloaded yet."
            return
        }
        _input.value = ""
        _error.value = null
        _isGenerating.value = true
        _streamingText.value = ""

        generateJob = viewModelScope.launch {
            // Register with LlmManager so Stop cancels the in-flight collect.
            llmManager.trackGeneration(currentCoroutineContext()[Job]!!)
            try {
                val conversationId = repository.ensureConversation(_activeConversationId.value)
                _activeConversationId.value = conversationId

                repository.addMessage(conversationId, MessageRole.USER, text)
                val assistantId =
                    repository.addMessage(conversationId, MessageRole.ASSISTANT, "")
                repository.maybeTitleFromFirstMessage(conversationId, text)

                var lastPersisted = 0
                val buffer = StringBuilder()
                // Suspends in this coroutine so engine/load/stream failures
                // throw here directly (old Job.join() swallowed them as
                // cancellation → silent empty bubble, no snackbar).
                llmManager.generateReply(
                    conversationId = conversationId,
                    prompt = text,
                    assistantMessageId = assistantId,
                    onToken = { delta ->
                        buffer.append(delta)
                        _streamingText.value = buffer.toString()
                        // Persist progressively, throttled to avoid DB churn.
                        if (buffer.length - lastPersisted >= 32) {
                            lastPersisted = buffer.length
                            repository.updateMessageContent(assistantId, buffer.toString())
                        }
                    }
                )
                // Final persist is done inside LlmManager; ensure UI reflects it.
                _streamingText.value = ""
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = e.message ?: "Generation failed"
            } finally {
                _isGenerating.value = false
                _streamingText.value = ""
            }
        }
    }

    override fun onCleared() {
        generateJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val repository: ChatRepository,
        private val prefs: SettingsPreferences,
        private val llmManager: LlmManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java))
            return ChatViewModel(repository, prefs, llmManager) as T
        }
    }
}
