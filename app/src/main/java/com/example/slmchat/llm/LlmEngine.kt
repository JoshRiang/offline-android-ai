package com.example.slmchat.llm

import kotlinx.coroutines.flow.Flow
import java.io.File

/** Sampling / session config applied at load / session-creation time. */
data class LlmConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    // MediaPipe maxTokens = TOTAL context window (prompt + output), not
    // output-only. The bundled TinyLlama .task was converted with a 1280-token
    // KV cache (ekv1280 filename suffix), so the usable window is <= 1280 even
    // though the base model natively supports 2048. Requesting more than the
    // bundle KV capacity makes prefill overflow -> instant EOS -> EMPTY output
    // on every prompt, including fresh chats. Default 1024 leaves headroom;
    // MediaPipeLlmEngine.fitPrompt() reserves output space automatically.
    val maxTokens: Int = 1024,
    val useGpu: Boolean = true,
    val systemPrompt: String = ""
)

/** Lifecycle state of the inference engine. */
sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val modelId: String) : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Abstraction over the on-device inference backend so the rest of the app does
 * not depend directly on MediaPipe classes (and unit tests can fake it).
 */
interface LlmEngine {
    val isLoaded: Boolean
    val loadedModelId: String?

    /** Load (or reload) [modelFile] with [config]. Closes any previous session. */
    suspend fun load(modelId: String, modelFile: File, config: LlmConfig): Result<Unit>

    /** Blocking full generation — convenient for tests / one-shots. */
    suspend fun generate(prompt: String, history: List<Pair<String, String>> = emptyList()): String

    /**
     * Streaming generation. Emits incremental text deltas; the concatenated
     * emissions equal the full response.
     */
    fun generateStreaming(
        prompt: String,
        history: List<Pair<String, String>> = emptyList()
    ): Flow<String>

    fun close()
}
