package com.example.slmchat.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaPipe LLM Inference implementation of [LlmEngine].
 *
 * Uses an [LlmInferenceSession] per generation so sampling params (temperature,
 * topK, topP) apply per request. All native calls are serialized with [mutex]
 * because the underlying engine is single-session at a time.
 *
 * Pinned against `com.google.mediapipe:tasks-genai:0.10.27`. Where a newer /
 * older AAR lacks an API (e.g. [LlmInference.Backend]), we fall back via
 * reflection-free try/catch to the always-present options.
 */
class MediaPipeLlmEngine(private val appContext: Context) : LlmEngine {

    private val mutex = Mutex()
    private var inference: LlmInference? = null
    private var activeConfig: LlmConfig = LlmConfig()

    override val isLoaded: Boolean get() = inference != null
    override var loadedModelId: String? = null
        private set

    override suspend fun load(modelId: String, modelFile: File, config: LlmConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    closeLocked()
                    require(modelFile.exists()) { "Model file not found: ${modelFile.absolutePath}" }
                    val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(config.maxTokens)
                    try {
                        optionsBuilder.setPreferredBackend(
                            if (config.useGpu) LlmInference.Backend.GPU
                            else LlmInference.Backend.CPU
                        )
                    } catch (_: Throwable) {
                        // Older AAR without Backend selection — default backend is fine.
                    }
                    val options = optionsBuilder.build()
                    inference = LlmInference.createFromOptions(appContext, options)
                    activeConfig = config
                    loadedModelId = modelId
                }
            }
        }

    override suspend fun generate(prompt: String, history: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val llm = inference ?: error("Engine not loaded")
                val fullPrompt = buildPrompt(prompt, history, activeConfig.systemPrompt)
                // Prefer session API (sampling params), fall back to direct call.
                runCatching { generateViaSession(llm, fullPrompt) }
                    .getOrElse { llm.generateResponse(fullPrompt) }
            }
        }

    override fun generateStreaming(prompt: String, history: List<Pair<String, String>>): Flow<String> =
        callbackFlow {
            val llm = inference ?: run {
                close(IllegalStateException("Engine not loaded"))
                return@callbackFlow
            }
            val fullPrompt = buildPrompt(prompt, history, activeConfig.systemPrompt)
            val config = activeConfig

            withContext(Dispatchers.IO) {
                // Session-based async streaming first; direct async as fallback.
                val sessionResult = runCatching {
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(config.temperature)
                        .setTopK(config.topK)
                        .setTopP(config.topP)
                        .build()
                    val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
                    try {
                        session.addQueryChunk(fullPrompt)
                        var previous = ""
                        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                        session.generateResponseAsync { partial, finished ->
                            // Listener form: (partialResult: String, done: Boolean)
                            val text = partial ?: ""
                            val delta = when {
                                text.startsWith(previous) -> text.substring(previous.length)
                                else -> text
                            }
                            previous = text
                            if (delta.isNotEmpty()) trySend(delta)
                            if (finished) done.complete(Unit)
                        }
                        // Some AAR variants deliver via ResultListener at construction;
                        // if generateResponseAsync returned without callback support the
                        // deferred still completes through the listener above.
                        runCatching {
                            kotlinx.coroutines.withTimeout(15 * 60_000L) { done.await() }
                        }
                    } finally {
                        runCatching { session.close() }
                    }
                }
                if (sessionResult.isFailure) {
                    // Fallback: single-shot sync call emitted as one chunk.
                    runCatching {
                        val text = mutex.withLock {
                            runCatching { generateViaSession(llm, fullPrompt) }
                                .getOrElse { llm.generateResponse(fullPrompt) }
                        }
                        if (text.isNotEmpty()) trySend(text)
                    }.onFailure { e ->
                        close(e)
                        return@withContext
                    }
                }
                close()
            }
            awaitClose { /* session closed above; nothing to retain */ }
        }

    override fun close() {
        // Best-effort synchronous close; native close is fast.
        runCatching { inference?.close() }
        inference = null
        loadedModelId = null
    }

    private fun closeLocked() {
        runCatching { inference?.close() }
        inference = null
        loadedModelId = null
    }

    private fun generateViaSession(llm: LlmInference, fullPrompt: String): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(activeConfig.temperature)
            .setTopK(activeConfig.topK)
            .setTopP(activeConfig.topP)
            .build()
        val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
        try {
            session.addQueryChunk(fullPrompt)
            return session.generateResponse()
        } finally {
            runCatching { session.close() }
        }
    }

    companion object {
        /**
         * Minimal chat template: system block + alternating turns. Individual
         * `.task` chat templates vary; this plain format works acceptably across
         * the catalog models without tokenizer-specific tokens.
         */
        fun buildPrompt(
            prompt: String,
            history: List<Pair<String, String>>,
            systemPrompt: String
        ): String = buildString {
            if (systemPrompt.isNotBlank()) {
                append("<system>\n").append(systemPrompt.trim()).append("\n</system>\n\n")
            }
            // Keep the context bounded: last 10 turns max.
            for ((user, assistant) in history.takeLast(10)) {
                append("<user>\n").append(user.trim()).append("\n</user>\n")
                if (assistant.isNotBlank()) {
                    append("<assistant>\n").append(assistant.trim()).append("\n</assistant>\n")
                }
            }
            append("<user>\n").append(prompt.trim()).append("\n</user>\n<assistant>\n")
        }
    }
}
