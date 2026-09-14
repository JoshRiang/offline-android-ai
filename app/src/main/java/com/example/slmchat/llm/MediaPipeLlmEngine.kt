package com.example.slmchat.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
                    // GPU delegate can fail on emulators / x86 / low-RAM devices
                    // while still returning a non-null LlmInference that then
                    // generates empty output. Retry on CPU before surfacing error.
                    inference = try {
                        LlmInference.createFromOptions(appContext, options)
                    } catch (e: Exception) {
                        if (!config.useGpu) throw e
                        val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelFile.absolutePath)
                            .setMaxTokens(config.maxTokens)
                            .build()
                        LlmInference.createFromOptions(appContext, cpuOptions)
                    }
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
            // Snapshot under lock so a concurrent load() can't swap inference mid-stream.
            // MediaPipe allows only one active generation per LlmInference.
            val llm: LlmInference
            val fullPrompt: String
            val config: LlmConfig
            mutex.withLock {
                llm = inference ?: run {
                    close(IllegalStateException("Engine not loaded"))
                    return@callbackFlow
                }
                config = activeConfig
                fullPrompt = buildPrompt(prompt, history, config.systemPrompt)
            }

            val job = launch(Dispatchers.IO) {
                var session: LlmInferenceSession? = null
                try {
                    val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTemperature(config.temperature)
                        .setTopK(config.topK)
                        .setTopP(config.topP)
                        .build()
                    session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
                    session.addQueryChunk(fullPrompt)

                    // NOTE (tasks-genai 0.10.27, verified against AOSP source):
                    // ProgressListener.run(partialResult, done) receives the
                    // *delta* for this callback, NOT the accumulated transcript.
                    // Do NOT diff against previous text — just emit it.
                    // The returned ListenableFuture must be awaited: on native
                    // failure the listener never gets done=true, so awaiting
                    // only a CompletableDeferred hangs silently -> empty flow.
                    val done = CompletableDeferred<Unit>()
                    val accumulated = StringBuilder()
                    val future = session.generateResponseAsync { partial, finished ->
                        val delta = partial ?: ""
                        if (delta.isNotEmpty()) {
                            accumulated.append(delta)
                            // trySend() drops under backpressure (callbackFlow
                            // default capacity is 64); blocking send never drops.
                            trySendBlocking(delta)
                        }
                        if (finished) {
                            if (!done.isCompleted) done.complete(Unit)
                        }
                    }
                    // Bridge Guava ListenableFuture into coroutines so native
                    // errors propagate instead of timing out silently.
                    future.addListener(
                        {
                            try {
                                future.get() // throws on native failure
                                if (!done.isCompleted) done.complete(Unit)
                            } catch (e: Exception) {
                                if (!done.isCompleted) {
                                    done.completeExceptionally(e)
                                }
                            }
                        },
                        { it.run() } // direct executor: listener is tiny
                    )
                    try {
                        withTimeout(15 * 60_000L) { done.await() }
                    } catch (e: Exception) {
                        runCatching { future.cancel(true) }
                        throw e
                    }
                    ensureActive()

                    // Async path can "succeed" with zero tokens when the prompt
                    // exceeds maxTokens or the template triggers instant EOS.
                    // Fall back to sync so the caller gets text or a real error.
                    if (accumulated.isEmpty()) {
                        val syncText = mutex.withLock {
                            runCatching { generateViaSessionLocked(llm, fullPrompt, config) }
                                .getOrElse { llm.generateResponse(fullPrompt) }
                        }
                        if (syncText.isNotEmpty()) trySendBlocking(syncText)
                        else throw IllegalStateException(
                            "Empty response (prompt likely exceeds maxTokens=${config.maxTokens} " +
                                "or template mismatch). Prompt chars=${fullPrompt.length}."
                        )
                    }
                    close()
                } catch (e: Exception) {
                    // Last-resort sync fallback before failing the flow, so a
                    // transient async-only failure still yields text.
                    val recovered = runCatching {
                        mutex.withLock {
                            runCatching { generateViaSessionLocked(llm, fullPrompt, config) }
                                .getOrElse { llm.generateResponse(fullPrompt) }
                        }
                    }.getOrNull()
                    if (!recovered.isNullOrEmpty()) {
                        trySendBlocking(recovered)
                        close()
                    } else {
                        close(e)
                    }
                } finally {
                    runCatching { session?.close() }
                }
            }
            awaitClose { job.cancel() }
        }.buffer(Channel.UNLIMITED)

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

    private fun generateViaSession(llm: LlmInference, fullPrompt: String): String =
        generateViaSessionLocked(llm, fullPrompt, activeConfig)

    /** Caller must hold [mutex] if concurrent load()/generate() is possible. */
    private fun generateViaSessionLocked(
        llm: LlmInference,
        fullPrompt: String,
        config: LlmConfig
    ): String {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(config.temperature)
            .setTopK(config.topK)
            .setTopP(config.topP)
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
         * TinyLlama-Chat template (from tokenizer_config.json chat_template):
         *   '<|system|>\n' + content + '</s>\n'
         *   '<|user|>\n' + content + '</s>\n'
         *   '<|assistant|>\n' + content + '</s>\n'
         *   ... + '<|assistant|>\n' as generation prompt.
         * The trailing </s> matters: without it the prefill merges turns into
         * one blob and multi-prefill .task builds often emit EOS immediately
         * (observed as "loads fine, empty streaming").
         */
        fun buildPrompt(
            prompt: String,
            history: List<Pair<String, String>>,
            systemPrompt: String
        ): String = buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|system|>\n").append(systemPrompt.trim()).append("</s>\n")
            }
            // Keep the context bounded: last 10 turns max.
            for ((user, assistant) in history.takeLast(10)) {
                append("<|user|>\n").append(user.trim()).append("</s>\n")
                if (assistant.isNotBlank()) {
                    append("<|assistant|>\n").append(assistant.trim()).append("</s>\n")
                }
            }
            append("<|user|>\n").append(prompt.trim()).append("</s>\n<|assistant|>\n")
        }
    }
}
