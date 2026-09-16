package com.example.slmchat.llm

import android.content.Context
import androidx.work.WorkInfo
import com.example.slmchat.data.local.MessageRole
import com.example.slmchat.data.prefs.AppSettings
import com.example.slmchat.data.prefs.SettingsPreferences
import com.example.slmchat.data.repo.ChatRepository
import kotlinx.coroutines.CancellationException
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
     *
     * Suspends in the caller's coroutine so failures propagate directly.
     * (Previously this launched a child job in [scope] and returned the Job;
     * `Job.join()` only throws CancellationException, so every real failure
     * arrived as cancellation and ChatViewModel rethrew it without ever
     * setting the user-visible error — generate failed silently with an
     * empty bubble and no snackbar.)
     */
    suspend fun generateReply(
        conversationId: Long,
        prompt: String,
        assistantMessageId: Long,
        onToken: suspend (String) -> Unit = {}
    ) {
        require(prompt.isNotBlank()) { "Message must not be empty" }
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
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException(
                "Model file \"${settings.modelId}\" is missing (expected ${file.absolutePath}). " +
                    "Re-download it from Settings."
            )
        }
        if (!engine.isLoaded || engine.loadedModelId != settings.modelId) {
            _engineState.value = EngineState.Loading
            try {
                engine.load(settings.modelId, file, config).getOrThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _engineState.value = EngineState.Error(e.message ?: "Failed to load model")
                throw e
            }
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
        // A trailing user turn without an assistant reply yet is NORMAL here:
        // ChatViewModel persists the just-sent user message BEFORE calling us,
        // so pendingUser is almost always the current prompt itself. Do NOT
        // re-add it — buildPrompt() appends `prompt` already. Only keep it as
        // a pair if it differs (e.g. a genuinely unanswered earlier message).
        val pending = pendingUser
        if (pending != null && pending != prompt && pending.trim() != prompt.trim()) {
            turns += pending to ""
        }
        val full = StringBuilder()
        try {
            engine.generateStreaming(prompt, turns).collect { delta ->
                full.append(delta)
                // Sanitize BEFORE display so fabricated <|user|> turns and raw
                // </s> tokens never reach the bubble mid-stream. Recomputed from
                // the full buffer each time (markers can span chunk bounds).
                // onToken receives the FULL sanitized text (not a delta); the
                // caller replaces its display buffer with it.
                onToken(MediaPipeLlmEngine.sanitizeAssistantOutput(full.toString()))
            }
        } catch (e: CancellationException) {
            // Stop pressed: persist sanitized partial output, then propagate.
            val clean = MediaPipeLlmEngine.sanitizeAssistantOutput(full.toString(), final = true)
            if (clean.isNotEmpty()) {
                runCatching {
                    repository.updateMessageContent(assistantMessageId, clean)
                }
            }
            throw e
        } catch (e: Exception) {
            // Persist sanitized partial tokens so the failure is
            // visible in the transcript, then propagate to the caller
            // which surfaces a user-visible error.
            val clean = MediaPipeLlmEngine.sanitizeAssistantOutput(full.toString(), final = true)
            val text = clean.ifEmpty {
                "Generation failed: ${e.message ?: e::class.simpleName}"
            }
            runCatching {
                repository.updateMessageContent(assistantMessageId, text)
            }
            throw e
        }
        val finalText = MediaPipeLlmEngine.sanitizeAssistantOutput(full.toString(), final = true)
        if (finalText.isEmpty()) {
            throw IllegalStateException(
                "Empty response from model ${engine.loadedModelId ?: settings.modelId}. " +
                    "Try shortening the conversation or lowering Max tokens."
            )
        }
        repository.updateMessageContent(assistantMessageId, finalText)
    }

    /** Track a caller-owned generation coroutine so Stop can cancel it. */
    fun trackGeneration(job: Job) {
        generationJob?.cancel()
        generationJob = job
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
