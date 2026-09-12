package com.example.slmchat.llm

import android.content.Context
import androidx.work.WorkInfo
import com.example.slmchat.data.local.MessageRole
import com.example.slmchat.data.prefs.AppSettings
import com.example.slmchat.data.prefs.SettingsPreferences
import com.example.slmchat.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.File

/** Download state of a single catalog model. */
sealed interface ModelDownloadState {
    data object NotDownloaded : ModelDownloadState
    data class Downloading(val progress: Int) : ModelDownloadState
    data object Downloaded : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Coordinates model files (download/delete), engine loading, and generation.
 * Owned by [com.example.slmchat.SlmChatApp] as a process-wide singleton.
 */
class LlmManager(
    private val appContext: Context,
    private val prefs: SettingsPreferences,
    private val repository: ChatRepository,
    val engine: LlmEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _downloadStates =
        MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    private var generationJob: Job? = null

    init {
        // Track download WorkManager states for every catalog model.
        for (model in ModelCatalog.models) {
            scope.launch {
                ModelDownloadWorker.stateFlow(appContext, model.id).collect { state ->
                    val fileExists = modelFile(model.id).exists()
                    val next: ModelDownloadState = when {
                        fileExists && (state == null || state == WorkInfo.State.SUCCEEDED) ->
                            ModelDownloadState.Downloaded
                        state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED ->
                            ModelDownloadState.Downloading(currentProgress(model.id))
                        state == WorkInfo.State.FAILED ->
                            if (fileExists) ModelDownloadState.Downloaded
                            else ModelDownloadState.Failed("Download failed")
                        state == WorkInfo.State.CANCELLED ->
                            if (fileExists) ModelDownloadState.Downloaded
                            else ModelDownloadState.NotDownloaded
                        fileExists -> ModelDownloadState.Downloaded
                        else -> ModelDownloadState.NotDownloaded
                    }
                    _downloadStates.value = _downloadStates.value + (model.id to next)
                }
            }
            scope.launch {
                ModelDownloadWorker.progressFlow(appContext, model.id).collect { pct ->
                    val current = _downloadStates.value[model.id]
                    if (current is ModelDownloadState.Downloading || current == null ||
                        current is ModelDownloadState.NotDownloaded
                    ) {
                        _downloadStates.value =
                            _downloadStates.value + (model.id to ModelDownloadState.Downloading(pct))
                    }
                }
            }
        }
        refreshDownloadStates()
    }

    fun modelsDir(): File = File(appContext.filesDir, "models").apply { mkdirs() }

    fun modelFile(modelId: String): File {
        val info = ModelCatalog.requireById(modelId)
        return File(modelsDir(), info.fileName)
    }

    fun isDownloaded(modelId: String): Boolean {
        val f = modelFile(modelId)
        return f.exists() && f.length() > 0
    }

    fun refreshDownloadStates() {
        scope.launch(Dispatchers.IO) {
            val map = ModelCatalog.models.associate { model ->
                val state = if (isDownloaded(model.id)) ModelDownloadState.Downloaded
                else ModelDownloadState.NotDownloaded
                model.id to state
            }
            // Don't clobber live Downloading states.
            val merged = map.mapValues { (id, state) ->
                val cur = _downloadStates.value[id]
                if (cur is ModelDownloadState.Downloading && state is ModelDownloadState.NotDownloaded) cur
                else state
            }
            _downloadStates.value = merged
        }
    }

    private suspend fun currentProgress(modelId: String): Int =
        runCatching {
            ModelDownloadWorker.progressFlow(appContext, modelId).first()
        }.getOrDefault(0)

    fun downloadModel(modelId: String, customUrl: String = "") {
        val model = ModelCatalog.requireById(modelId)
        _downloadStates.value += (modelId to ModelDownloadState.Downloading(0))
        ModelDownloadWorker.enqueue(appContext, model, customUrl)
    }

    fun cancelDownload(modelId: String) {
        ModelDownloadWorker.cancel(appContext, modelId)
        refreshDownloadStates()
    }

    fun deleteModel(modelId: String) {
        scope.launch(Dispatchers.IO) {
            ModelDownloadWorker.cancel(appContext, modelId)
            runCatching { modelFile(modelId).delete() }
            if (engine.loadedModelId == modelId) {
                engine.close()
                _engineState.value = EngineState.Unloaded
            }
            refreshDownloadStates()
        }
    }

    /** Load the model selected in settings (if downloaded). */
    fun loadActiveModel(settings: AppSettings? = null) {
        scope.launch(Dispatchers.IO) {
            val s = settings ?: prefs.settings.first()
            val modelId = s.modelId
            val file = modelFile(modelId)
            if (!isDownloaded(modelId)) {
                if (engine.loadedModelId != null) {
                    engine.close()
                    _engineState.value = EngineState.Unloaded
                }
                return@launch
            }
            if (engine.loadedModelId == modelId && engine.isLoaded) {
                _engineState.value = EngineState.Ready(modelId)
                return@launch
            }
            _engineState.value = EngineState.Loading
            val config = LlmConfig(
                temperature = s.temperature,
                topK = s.topK,
                topP = s.topP,
                maxTokens = s.maxTokens,
                useGpu = s.useGpu,
                systemPrompt = s.systemPrompt
            )
            engine.load(modelId, file, config)
                .onSuccess { _engineState.value = EngineState.Ready(modelId) }
                .onFailure { e ->
                    _engineState.value =
                        EngineState.Error(e.message ?: "Failed to load model")
                }
        }
    }

    fun unload() {
        generationJob?.cancel()
        generationJob = null
        scope.launch(Dispatchers.IO) {
            engine.close()
            _engineState.value = EngineState.Unloaded
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
    }

    /**
     * Stream the assistant reply for [prompt] in [conversationId]'s context.
     * Persists the final text to the placeholder [assistantMessageId].
     */
    fun generateReply(
        conversationId: Long,
        prompt: String,
        assistantMessageId: Long,
        onToken: suspend (String) -> Unit = {}
    ): Job {
        generationJob?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            val settings = prefs.settings.first()
            // Ensure the right model is loaded with current sampling settings.
            val config = LlmConfig(
                temperature = settings.temperature,
                topK = settings.topK,
                topP = settings.topP,
                maxTokens = settings.maxTokens,
                useGpu = settings.useGpu,
                systemPrompt = settings.systemPrompt
            )
            val file = modelFile(settings.modelId)
            if (!file.exists()) {
                throw IllegalStateException("Model not downloaded")
            }
            if (!engine.isLoaded || engine.loadedModelId != settings.modelId) {
                _engineState.value = EngineState.Loading
                engine.load(settings.modelId, file, config).getOrThrow()
                _engineState.value = EngineState.Ready(settings.modelId)
            }
            val historyRows = repository.recentMessages(conversationId, 20)
                .filter { it.id != assistantMessageId && it.content.isNotBlank() }
            // Pair consecutive user -> assistant turns.
            val turns = mutableListOf<Pair<String, String>>()
            var pendingUser: String? = null
            for (row in historyRows) {
                when (row.role) {
                    MessageRole.USER -> {
                        if (pendingUser == null) pendingUser = row.content
                    }
                    MessageRole.ASSISTANT -> {
                        val u = pendingUser
                        if (u != null) {
                            turns += u to row.content
                            pendingUser = null
                        }
                    }
                }
            }
            val full = StringBuilder()
            engine.generateStreaming(prompt, turns).collect { delta ->
                full.append(delta)
                onToken(delta)
            }
            if (full.isEmpty()) full.append("(empty response)")
            repository.updateMessageContent(assistantMessageId, full.toString())
        }
        generationJob = job
        return job
    }

    /** Cold flow variant for callers that want tokens directly. */
    fun streamTokens(prompt: String, history: List<Pair<String, String>> = emptyList()): Flow<String> =
        flow {
            val settings = prefs.settings.first()
            val file = modelFile(settings.modelId)
            check(file.exists()) { "Model not downloaded" }
            val config = LlmConfig(
                temperature = settings.temperature,
                topK = settings.topK,
                topP = settings.topP,
                maxTokens = settings.maxTokens,
                useGpu = settings.useGpu,
                systemPrompt = settings.systemPrompt
            )
            if (!engine.isLoaded || engine.loadedModelId != settings.modelId) {
                engine.load(settings.modelId, file, config).getOrThrow()
                _engineState.value = EngineState.Ready(settings.modelId)
            }
            engine.generateStreaming(prompt, history).collect { emit(it) }
        }
}
