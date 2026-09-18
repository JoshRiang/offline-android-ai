package com.example.slmchat.llm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.Future

/**
 * MediaPipe LLM Inference implementation of [LlmEngine].
 *
 * Uses an [LlmInferenceSession] per generation so sampling params (temperature,
 * topK, topP) apply per request. All native calls are serialized with [mutex]
 * because the underlying engine is single-session at a time.
 *
 * Stability notes (Samsung S21 FE / Exynos 2100 / Mali-G78, TinyLlama 1.1B
 * .task 750MB, tasks-genai 0.10.27):
 * - The MediaPipe GPU delegate is unstable on Exynos/Mali and crashes natively
 *   (no Kotlin exception — the process dies). Such devices are forced to CPU.
 * - `maxTokens` in MediaPipe is the TOTAL context window (prompt + output), not
 *   output-only. An over-long prompt triggers empty output or a native crash,
 *   so prompts are fitted to the window before any native call.
 * - Native crashes cannot be caught in Kotlin; every Kotlin-side failure mode
 *   is logged via [TAG] and guarded so logcat shows what happened before any
 *   native issue. Use `adb logcat -s MediaPipeLlmEngine:V`.
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
                    val window = windowFor(modelId)
                    val effectiveMaxTokens = effectiveMaxTokens(config.maxTokens, window)
                    if (effectiveMaxTokens != config.maxTokens) {
                        Log.w(
                            TAG,
                            "load: clamping maxTokens ${config.maxTokens} -> $effectiveMaxTokens " +
                                "(bundle window=$window, allowed $MIN_MAX_TOKENS..$window)"
                        )
                    }
                    // Clamp sampler: topK must be <= the maxTopK load option or
                    // generation fails; temp/topP out of range misbehave too.
                    val safeConfig = clampSampler(config)
                    val forceCpu = shouldForceCpu()
                    val wantGpu = config.useGpu && !forceCpu
                    if (config.useGpu && forceCpu) {
                        Log.w(
                            TAG,
                            "load: GPU requested but ${deviceString()} is on the CPU-only " +
                                "blocklist (Exynos/Mali GPU delegate crashes natively). Forcing CPU."
                        )
                    }
                    Log.i(
                        TAG,
                        "load: model=$modelId file=${modelFile.name} size=${modelFile.length()} " +
                            "maxTokens=$effectiveMaxTokens backend=${if (wantGpu) "GPU" else "CPU"} " +
                            "device=${deviceString()}"
                    )
                    val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelFile.absolutePath)
                        .setMaxTokens(effectiveMaxTokens)
                        .setMaxTopK(safeConfig.topK)
                    try {
                        optionsBuilder.setPreferredBackend(
                            if (wantGpu) LlmInference.Backend.GPU
                            else LlmInference.Backend.CPU
                        )
                    } catch (t: Throwable) {
                        // Older AAR without Backend selection — default backend is fine.
                        Log.w(TAG, "load: setPreferredBackend unavailable, using default", t)
                    }
                    val options = optionsBuilder.build()
                    // GPU delegate can fail on emulators / x86 / low-RAM devices
                    // while still returning a non-null LlmInference that then
                    // generates empty output. Retry on CPU before surfacing error.
                    inference = try {
                        try {
                            LlmInference.createFromOptions(appContext, options)
                        } catch (t: Throwable) {
                            Log.e(TAG, "load: createFromOptions(${if (wantGpu) "GPU" else "CPU"}) failed", t)
                            throw t
                        }
                    } catch (e: Throwable) {
                        if (!wantGpu) throw e
                        Log.w(TAG, "load: GPU init failed, retrying CPU-only", e)
                        val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                            .setModelPath(modelFile.absolutePath)
                            .setMaxTokens(effectiveMaxTokens)
                            .setMaxTopK(safeConfig.topK)
                            .build()
                        try {
                            LlmInference.createFromOptions(appContext, cpuOptions)
                        } catch (t: Throwable) {
                            Log.e(TAG, "load: CPU fallback createFromOptions failed", t)
                            throw t
                        }
                    }
                    activeConfig = safeConfig.copy(maxTokens = effectiveMaxTokens)
                    loadedModelId = modelId
                    Log.i(TAG, "load: ready model=$modelId")
                    Unit
                }
            }
        }

    override suspend fun generate(prompt: String, history: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val llm = inference ?: error("Engine not loaded")
                val fullPrompt = fitPromptExactLocked(
                    llm, prompt, history, activeConfig.systemPrompt, activeConfig.maxTokens
                )
                Log.d(TAG, "generate: promptChars=${fullPrompt.length} maxTokens=${activeConfig.maxTokens}")
                // Prefer session API (sampling params), fall back to direct call.
                var text = runCatching {
                    try {
                        generateViaSessionLocked(llm, fullPrompt, activeConfig)
                    } catch (t: Throwable) {
                        Log.e(TAG, "generate: session path failed", t)
                        throw t
                    }
                }.getOrElse { sessionError ->
                    Log.w(TAG, "generate: falling back to direct generateResponse", sessionError)
                    try {
                        llm.generateResponse(fullPrompt)
                    } catch (t: Throwable) {
                        Log.e(TAG, "generate: direct generateResponse failed", t)
                        throw t
                    }
                }
                if (text.isBlank()) {
                    // Instant-EOS (often a template/prefill quirk on multi-prefill
                    // bundles): one keep-alive retry with a bare prompt instead
                    // of returning an empty string. Decode temperature is nudged
                    // up so greedy EOS-at-0 doesn't repeat deterministically.
                    Log.w(TAG, "generate: empty, trying keep-alive retry")
                    text = runCatching {
                        val retryConfig = configForRetry(activeConfig)
                        generateKeepAliveLocked(llm, prompt, retryConfig)
                    }.getOrDefault("")
                }
                text
            }
        }

    override fun generateStreaming(prompt: String, history: List<Pair<String, String>>): Flow<String> =
        callbackFlow {
            // Snapshot under lock so a concurrent load() can't swap inference mid-stream.
            // MediaPipe allows only one active generation per LlmInference.
            val llm: LlmInference
            val config: LlmConfig
            val fullPrompt: String
            mutex.withLock {
                llm = inference ?: run {
                    Log.e(TAG, "stream: engine not loaded")
                    close(IllegalStateException("Engine not loaded"))
                    return@callbackFlow
                }
                config = activeConfig
                fullPrompt = fitPromptExactLocked(
                    llm, prompt, history, config.systemPrompt, config.maxTokens
                )
            }
            Log.i(
                TAG,
                "stream: start model=$loadedModelId promptChars=${fullPrompt.length} " +
                    "maxTokens=${config.maxTokens} temp=${config.temperature} topK=${config.topK} topP=${config.topP}"
            )
            Log.d(TAG, "stream: promptHead=${fullPrompt.take(PROMPT_LOG_CHARS)}")

            // No inner launch(): the callbackFlow producer coroutine IS the worker.
            // awaitClose() only cancels the pending future — session.close()
            // happens exactly once in the finally below, on this coroutine.
            // This avoids the old race where awaitClose cancelled a child job
            // while its finally closed the session under a still-firing
            // native callback (use-after-free native crash).
            var session: LlmInferenceSession? = null
            var future: Future<*>? = null
            try {
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(config.temperature)
                    .setTopK(config.topK)
                    .setTopP(config.topP)
                    .build()
                try {
                    session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
                    Log.d(TAG, "stream: session created")
                } catch (t: Throwable) {
                    Log.e(TAG, "stream: createFromOptions failed", t)
                    throw t as? Exception ?: IllegalStateException("Session creation failed", t)
                }
                val sess = session
                if (sess == null) throw IllegalStateException("Session creation returned null")
                try {
                    sess.addQueryChunk(fullPrompt)
                    Log.d(TAG, "stream: addQueryChunk ok (${fullPrompt.length} chars)")
                } catch (t: Throwable) {
                    Log.e(TAG, "stream: addQueryChunk failed (promptChars=${fullPrompt.length})", t)
                    throw t as? Exception ?: IllegalStateException("addQueryChunk failed", t)
                }

                // NOTE (tasks-genai 0.10.27, verified against AOSP source):
                // ProgressListener.run(partialResult, done) receives the
                // *delta* for this callback, NOT the accumulated transcript.
                // Do NOT diff against previous text — just emit it.
                // The returned ListenableFuture must be awaited: on native
                // failure the listener never gets done=true, so awaiting
                // only a CompletableDeferred hangs silently -> empty flow.
                val done = CompletableDeferred<Unit>()
                val accumulated = StringBuilder()
                var firstTokenAt = 0L
                val genFuture = try {
                    sess.generateResponseAsync { partial, finished ->
                        // Runs on a MediaPipe native thread: never throw, never block.
                        try {
                            val delta = partial ?: ""
                            if (delta.isNotEmpty()) {
                                if (firstTokenAt == 0L) {
                                    firstTokenAt = System.currentTimeMillis()
                                    Log.d(TAG, "stream: first token (${delta.length} chars)")
                                }
                                accumulated.append(delta)
                                // trySend never blocks the native thread (channel is
                                // UNLIMITED via .buffer below); trySendBlocking could
                                // deadlock it and trip the native watchdog.
                                if (!trySend(delta).isSuccess) {
                                    Log.w(TAG, "stream: trySend failed, channel closed?")
                                }
                            }
                            if (finished) {
                                Log.i(TAG, "stream: finished totalChars=${accumulated.length}")
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "stream: listener failed", t)
                            if (!done.isCompleted) {
                                done.completeExceptionally(
                                    t as? Exception ?: IllegalStateException(t)
                                )
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "stream: generateResponseAsync threw", t)
                    throw t as? Exception ?: IllegalStateException("generateResponseAsync failed", t)
                }
                future = genFuture
                // Bridge Guava ListenableFuture into coroutines so native
                // errors propagate instead of timing out silently.
                try {
                    @Suppress("UNCHECKED_CAST")
                    (genFuture as? com.google.common.util.concurrent.ListenableFuture<String>)
                        ?.addListener(
                            {
                                try {
                                    genFuture.get() // throws on native failure
                                    if (!done.isCompleted) done.complete(Unit)
                                } catch (e: Exception) {
                                    Log.e(TAG, "stream: future.get() failed", e)
                                    if (!done.isCompleted) done.completeExceptionally(e)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "stream: future.get() failed", t)
                                    if (!done.isCompleted) {
                                        done.completeExceptionally(IllegalStateException(t))
                                    }
                                }
                            },
                            { it.run() } // direct executor: listener is tiny
                        )
                } catch (t: Throwable) {
                    Log.w(TAG, "stream: could not attach future listener", t)
                }
                try {
                    withTimeout(STREAM_TIMEOUT_MS) { done.await() }
                } catch (e: CancellationException) {
                    Log.i(TAG, "stream: cancelled, cancelling native future")
                    runCatching { genFuture.cancel(true) }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "stream: done.await failed/timed out", e)
                    runCatching { genFuture.cancel(true) }
                    throw e
                }
                ensureActive()

                // Async path can "succeed" with zero tokens when the prompt
                // exceeds maxTokens or the template triggers instant EOS.
                // Fall back to sync so the caller gets text or a real error.
                if (accumulated.isEmpty()) {
                    Log.w(
                        TAG,
                        "stream: async yielded 0 tokens (promptChars=${fullPrompt.length} " +
                            "maxTokens=${config.maxTokens}); trying sync fallback"
                    )
                    ensureActive()
                    val retryConfig = configForRetry(config)
                    val syncText = mutex.withLock {
                        // Re-fit with exact tokens first: the prompt may have
                        // grown past the window between snapshot and fallback.
                        val refit = runCatching {
                            fitPromptExactLocked(llm, prompt, history, retryConfig.systemPrompt, retryConfig.maxTokens)
                        }.getOrDefault(fullPrompt)
                        val viaSession = runCatching { generateViaSessionLocked(llm, refit, retryConfig) }
                            .getOrElse {
                                try {
                                    llm.generateResponse(refit)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "stream: sync direct fallback failed", t)
                                    throw t
                                }
                            }
                        if (viaSession.isNotBlank()) return@withLock viaSession
                        // Same instant-EOS as the async path hit: retry bare.
                        Log.w(TAG, "stream: sync empty too, trying keep-alive retry")
                        runCatching { generateKeepAliveLocked(llm, prompt, retryConfig) }
                            .getOrDefault("")
                    }
                    if (syncText.isNotEmpty()) {
                        Log.i(TAG, "stream: sync fallback yielded ${syncText.length} chars")
                        accumulated.append(syncText)
                        trySend(syncText)
                    } else {
                        throw IllegalStateException(
                            "Empty response (prompt likely exceeds maxTokens=${config.maxTokens} " +
                                "or template mismatch). Prompt chars=${fullPrompt.length}."
                        )
                    }
                }
                Log.i(TAG, "stream: done totalChars=${accumulated.length}")
                close()
            } catch (e: CancellationException) {
                Log.i(TAG, "stream: producer cancelled")
                runCatching { future?.cancel(true) }
                throw e // don't call close(e): cancellation propagates via the flow
            } catch (e: Exception) {
                Log.e(TAG, "stream: failed", e)
                // Last-resort sync fallback before failing the flow, so a
                // transient async-only failure still yields text. Skipped when
                // the producer was cancelled (native state is torn down).
                ensureActive()
                val recovered = runCatching {
                    mutex.withLock {
                        runCatching {
                            try {
                                generateViaSessionLocked(llm, fullPrompt, config)
                            } catch (t: Throwable) {
                                Log.e(TAG, "stream: recovery session path failed", t)
                                throw t
                            }
                        }.getOrElse {
                            try {
                                llm.generateResponse(fullPrompt)
                            } catch (t: Throwable) {
                                Log.e(TAG, "stream: recovery direct path failed", t)
                                throw t
                            }
                        }
                    }
                }.getOrNull()
                if (!recovered.isNullOrEmpty()) {
                    Log.i(TAG, "stream: recovered via sync (${recovered.length} chars)")
                    trySend(recovered)
                    close()
                } else {
                    close(e)
                }
            } finally {
                try {
                    session?.close()
                    Log.d(TAG, "stream: session closed")
                } catch (t: Throwable) {
                    Log.e(TAG, "stream: session.close() failed", t)
                }
            }
            // Only cancels the native future; session teardown stays in finally.
            awaitClose {
                Log.d(TAG, "stream: awaitClose, cancelling future")
                runCatching { future?.cancel(true) }
            }
        }.buffer(Channel.UNLIMITED)

    override fun close() {
        // Best-effort synchronous close; native close is fast.
        try {
            inference?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "close: inference.close() failed", t)
        }
        inference = null
        loadedModelId = null
        Log.d(TAG, "close: engine closed")
    }

    private fun closeLocked() {
        try {
            inference?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "closeLocked: inference.close() failed", t)
        }
        inference = null
        loadedModelId = null
    }

    private fun generateViaSession(llm: LlmInference, fullPrompt: String): String =
        generateViaSessionLocked(llm, fullPrompt, activeConfig)

    /**
     * Retry config after an instant-EOS empty response: nudge temperature up
     * from greedy-0 so the same deterministic EOS doesn't repeat. Keeps the
     * user's sampler otherwise (topK/topP still clamped at load).
     */
    private fun configForRetry(config: LlmConfig): LlmConfig =
        if (config.temperature <= 0.05f) config.copy(temperature = 0.7f) else config

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
        var session: LlmInferenceSession? = null
        try {
            try {
                session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
            } catch (t: Throwable) {
                Log.e(TAG, "generateViaSessionLocked: createFromOptions failed", t)
                throw t as? Exception ?: IllegalStateException("Session creation failed", t)
            }
            val sess = session ?: error("Session creation returned null")
            try {
                sess.addQueryChunk(fullPrompt)
            } catch (t: Throwable) {
                Log.e(TAG, "generateViaSessionLocked: addQueryChunk failed", t)
                throw t as? Exception ?: IllegalStateException("addQueryChunk failed", t)
            }
            try {
                return sess.generateResponse()
            } catch (t: Throwable) {
                Log.e(TAG, "generateViaSessionLocked: generateResponse failed", t)
                throw t as? Exception ?: IllegalStateException("generateResponse failed", t)
            }
        } finally {
            try {
                session?.close()
            } catch (t: Throwable) {
                Log.e(TAG, "generateViaSessionLocked: session.close() failed", t)
            }
        }
    }

    companion object {
        const val TAG = "MediaPipeLlmEngine"

        /** Safe default window: fits the TinyLlama ekv1280 bundle KV cache. */
        const val DEFAULT_MAX_TOKENS = 1024

        /** Hard bounds for the MediaPipe maxTokens option. */
        const val MIN_MAX_TOKENS = 512
        const val MAX_MAX_TOKENS = 4096

        /** Output tokens reserved when fitting the prompt; prevents instant-EOS/empty output. */
        const val RESERVED_OUTPUT_TOKENS = 256

        /** Rough chars-per-token estimate for English text. */
        const val CHARS_PER_TOKEN = 4

        const val STREAM_TIMEOUT_MS = 15 * 60_000L
        const val PROMPT_LOG_CHARS = 300

        /** TinyLlama-1.1B-Chat-v1.0 context length (see ModelCatalog). */
        const val TINYLLAMA_CONTEXT = 2048

        /**
         * True on SoCs where the MediaPipe GPU delegate crashes natively
         * (Exynos 2100/Mali-G78 family, e.g. Samsung S21 FE SM-G990E) and on
         * emulators without a real GPU. Callers must use CPU in that case —
         * a native crash kills the process and cannot be caught in Kotlin.
         */
        fun shouldForceCpu(): Boolean {
            val hw = runCatching { Build.HARDWARE ?: "" }.getOrDefault("").lowercase()
            val board = runCatching { Build.BOARD ?: "" }.getOrDefault("").lowercase()
            val model = runCatching { Build.MODEL ?: "" }.getOrDefault("").lowercase()
            val soc = runCatching {
                if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL ?: "" else ""
            }.getOrDefault("").lowercase()
            val fp = "$hw $board $soc $model"
            val blocked = fp.contains("exynos") ||
                fp.contains("universal") || // Samsung Exynos board family (universal2100 = E2100)
                fp.contains("s5e9925") || // Exynos 2100 codename
                fp.contains("s5e9935") ||
                fp.contains("s5e9840") ||
                fp.contains("ert9925") ||
                hw.contains("goldfish") || hw.contains("ranchu") || // emulators
                board.contains("goldfish") || board.contains("ranchu") ||
                model.contains("sdk_gphone")
            if (blocked) Log.w(TAG, "shouldForceCpu: CPU-only ($fp)")
            return blocked
        }

        fun deviceString(): String = runCatching {
            "model=${Build.MODEL} hw=${Build.HARDWARE} board=${Build.BOARD} " +
                "soc=${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "n/a"}"
        }.getOrDefault("unknown-device")

        fun effectiveMaxTokens(requested: Int, windowTokens: Int = MAX_MAX_TOKENS): Int =
            requested.coerceIn(MIN_MAX_TOKENS, windowTokens.coerceAtLeast(MIN_MAX_TOKENS))

        /** Usable prefill+decode window for [modelId] (bundle KV cap, not native ctx). */
        fun windowFor(modelId: String): Int =
            runCatching { ModelCatalog.requireById(modelId).windowTokens }
                .getOrDefault(MAX_MAX_TOKENS)

        /**
         * Clamp sampler params to ranges MediaPipe accepts. In particular
         * topK must never exceed the maxTopK load option (set to the same
         * clamped value at load); a topK above it fails generation.
         */
        fun clampSampler(config: LlmConfig): LlmConfig = config.copy(
            temperature = config.temperature.coerceIn(0f, 2f),
            topK = config.topK.coerceIn(1, 64),
            topP = config.topP.coerceIn(0.01f, 1f)
        )

        /**
         * TinyLlama-Chat template (from tokenizer_config.json chat_template of
         * TinyLlama-1.1B-Chat-v1.0, which the .task file bundles):
         *   '<|system|>\n' + content + '</s>\n'
         *   '<|user|>\n' + content + '</s>\n'
         *   '<|assistant|>\n' + content + '</s>\n'
         *   ... + '<|assistant|>\n' as generation prompt.
         * The trailing </s> matters: without it the prefill merges turns into
         * one blob and multi-prefill .task builds often emit EOS immediately
         * (observed as "loads fine, empty streaming").
         */
        /**
         * Clean a stored turn: strip template tokens leaked by earlier buggy
         * builds so poisoned histories self-heal instead of confusing the
         * model forever (nested `<|user|>` inside a turn → instant EOS).
         */
        fun cleanTurn(text: String): String =
            text.replace("<|system|>", "")
                .replace("<|user|>", "")
                .replace("<|assistant|>", "")
                .replace("<|im_start|>", "")
                .replace("<|im_end|>", "")
                .replace("<<SYS>>", "")
                .replace("<</SYS>>", "")
                .replace("[INST]", "")
                .replace("[/INST]", "")
                .replace("</s>", "")
                .replace("<s>", "")
                .trim()

        fun buildPrompt(
            prompt: String,
            history: List<Pair<String, String>>,
            systemPrompt: String
        ): String = buildString {
            val cleanSystem = cleanTurn(systemPrompt)
            if (cleanSystem.isNotBlank()) {
                append("<|system|>\n").append(cleanSystem).append("</s>\n")
            }
            // Keep the context bounded: last 10 turns max.
            for ((user, assistant) in history.takeLast(10)) {
                val cu = cleanTurn(user)
                if (cu.isBlank()) continue
                append("<|user|>\n").append(cu).append("</s>\n")
                val ca = cleanTurn(assistant)
                if (ca.isNotBlank()) {
                    append("<|assistant|>\n").append(ca).append("</s>\n")
                }
            }
            val cp = cleanTurn(prompt)
            append("<|user|>\n").append(cp.ifBlank { prompt.trim() }).append("</s>\n<|assistant|>\n")
        }

        /**
         * Fit the ChatML prompt into [maxTokens] (prompt + output share the
         * window in MediaPipe). Drops oldest history turns first, then hard
         * truncates the tail — keeping the trailing `<|assistant|>` generation
         * prompt intact. Prevents the over-long-prompt native crash / instant
         * empty response on small-context models (TinyLlama ctx 2048).
         */
        fun fitPrompt(
            prompt: String,
            history: List<Pair<String, String>>,
            systemPrompt: String,
            maxTokens: Int
        ): String {
            require(prompt.isNotBlank() || history.isNotEmpty()) {
                "Prompt must not be empty"
            }
            val window = effectiveMaxTokens(maxTokens)
            val maxPromptChars = (window - RESERVED_OUTPUT_TOKENS) * CHARS_PER_TOKEN
            var turns = 10
            var full = buildPrompt(prompt, history, systemPrompt)
            while (full.length > maxPromptChars && turns > 0) {
                turns -= 2
                full = buildPrompt(prompt, history.takeLast(turns.coerceAtLeast(0)), systemPrompt)
            }
            if (full.length > maxPromptChars) {
                // Hard truncate the head (oldest content), keep the tail with
                // the current user turn + generation prompt.
                Log.w(
                    TAG,
                    "fitPrompt: truncating ${full.length} -> $maxPromptChars chars " +
                        "(window=$window, historyTurns=$turns)"
                )
                val suffix = "\n<|assistant|>\n"
                val tail = full.takeLast(maxPromptChars - systemPrompt.length - suffix.length - 64)
                full = buildString {
                    if (systemPrompt.isNotBlank()) {
                        append("<|system|>\n").append(systemPrompt.trim()).append("</s>\n")
                    }
                    append("[...truncated...]\n")
                    append(tail.trimStart())
                    if (!tail.contains("<|assistant|>")) append(suffix)
                }.takeLast(maxPromptChars)
            } else if (turns < 10) {
                Log.w(TAG, "fitPrompt: dropped history to last $turns turns (window=$window)")
            }
            return full
        }

        /**
         * Exact-token variant of [fitPrompt] using the bundle's real tokenizer
         * via [LlmInference.sizeInTokens]. Char heuristics over/under-estimate
         * non-English text; this measures the actual prompt and trims history
         * turns until prompt + reserved output fits [maxTokens]. Falls back to
         * [fitPrompt] when the native call throws (e.g. mid-load teardown).
         * Callers must hold the engine mutex (reads native tokenizer state).
         */
        fun fitPromptExactLocked(
            llm: LlmInference,
            prompt: String,
            history: List<Pair<String, String>>,
            systemPrompt: String,
            maxTokens: Int
        ): String {
            require(prompt.isNotBlank() || history.isNotEmpty()) {
                "Prompt must not be empty"
            }
            val window = effectiveMaxTokens(maxTokens)
            val budget = (window - RESERVED_OUTPUT_TOKENS).coerceAtLeast(64)
            var turns = 10
            var full = buildPrompt(prompt, history.takeLast(turns), systemPrompt)
            var used: Int = runCatching { llm.sizeInTokens(full) }.getOrNull()
                ?: run {
                    Log.w(TAG, "fitPromptExact: sizeInTokens unavailable, using char heuristic")
                    return fitPrompt(prompt, history, systemPrompt, maxTokens)
                }
            while (used > budget && turns > 0) {
                turns -= 2
                full = buildPrompt(prompt, history.takeLast(turns.coerceAtLeast(0)), systemPrompt)
                used = runCatching { llm.sizeInTokens(full) }.getOrDefault(used)
            }
            if (used > budget) {
                Log.w(TAG, "fitPromptExact: truncating $used -> $budget tokens (window=$window)")
                return fitPrompt(prompt, history.takeLast(turns.coerceAtLeast(0)), systemPrompt, maxTokens)
            }
            if (turns < 10) {
                Log.w(TAG, "fitPromptExact: dropped history to last $turns turns ($used/$budget tokens)")
            }
            return full
        }

        /**
         * Keep-alive retry for instant-EOS empty responses (template/prefill
         * quirk on the multi-prefill TinyLlama bundle): issue a FRESH session
         * with a bare no-history prompt (system + current user turn only) and
         * read the sync response. Dropping history avoids re-triggering the
         * same instant-EOS the full prompt caused.
         * Callers must hold the engine mutex.
         */
        private fun generateKeepAliveLocked(
            llm: LlmInference,
            prompt: String,
            config: LlmConfig
        ): String {
            val bare = buildString {
                val cleanSystem = cleanTurn(config.systemPrompt)
                if (cleanSystem.isNotBlank()) {
                    append("<|system|>\n").append(cleanSystem).append("</s>\n")
                }
                val cp = cleanTurn(prompt)
                append("<|user|>\n").append(cp.ifBlank { prompt.trim() }).append("</s>\n<|assistant|>\n")
            }
            Log.w(TAG, "keepAlive: retrying with bare prompt (${bare.length} chars)")
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTemperature(config.temperature)
                .setTopK(config.topK)
                .setTopP(config.topP)
                .build()
            var session: LlmInferenceSession? = null
            try {
                session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
                session.addQueryChunk(bare)
                return session.generateResponse()
            } finally {
                runCatching { session?.close() }
            }
        }

        /**
         * Stop markers that end the assistant turn. MediaPipe's Android API
         * (tasks-genai 0.10.27) has NO stop-sequence option, so a small model
         * like TinyLlama happily role-plays a fake `<|user|>` follow-up when
         * the real prompt is short ("yo"). Truncation must be post-processing.
         * Extra families (ChatML <|im_*|>, Llama [INST], <<SYS>>) cover
         * non-TinyLlama bundles a user may sideload via custom URL, and plain
         * "User:"/"Assistant:" roleplay lines are caught too.
         */
        private val STOP_MARKERS = listOf(
            "<|user|>", "<|system|>", "</s>",
            "<|im_start|>", "<|im_end|>",
            "[INST]", "[/INST]", "<<SYS>>", "<</SYS>>"
        )

        /** Roleplay-line prefixes TinyLlama emits when it keeps talking ("User: …"). */
        private val ROLEPLAY_LINE_PREFIXES = listOf("user:", "system:")

        /** Strip a leading generation-prompt echo (`<|assistant|>`) some builds emit. */
        fun stripPromptEcho(raw: String): String {
            var out = raw.replaceFirst(Regex("^\\s*<\\|assistant\\|>"), "")
            out = out.replaceFirst(Regex("^\\s*(assistant|asistente)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            return out
        }

        /** Cut the text at the first stop marker (no trimming — streaming-safe). */
        fun truncateAtStopMarkers(noEcho: String): String {
            var cut = noEcho.length
            for (m in STOP_MARKERS) {
                val i = noEcho.indexOf(m)
                if (i >= 0) cut = minOf(cut, i)
            }
            // A non-leading <|assistant|> means a new fabricated turn started.
            val ai = noEcho.indexOf("<|assistant|>")
            if (ai > 0) cut = minOf(cut, ai)
            var out = noEcho.substring(0, cut)
            // Drop trailing roleplay lines ("User: ..." / "Assistant: ...")
            // the model appends after finishing its answer. Keep at most the
            // first such line boundary — scan line by line.
            val lines = out.lines()
            val kept = ArrayList<String>(lines.size)
            for (line in lines) {
                val t = line.trimStart().lowercase()
                val isRoleplay = ROLEPLAY_LINE_PREFIXES.any { t.startsWith(it) } ||
                    (t.startsWith("assistant:") || t.startsWith("asistente:"))
                if (isRoleplay && kept.isNotEmpty()) break
                kept.add(line)
            }
            if (kept.size < lines.size) out = kept.joinToString("\n")
            return out
        }

        /**
         * Sanitize assistant output: strip prompt echo, cut fabricated turns.
         * Pass final=true for persisted/final text (also trims whitespace).
         */
        fun sanitizeAssistantOutput(raw: String, final: Boolean = false): String {
            val clean = truncateAtStopMarkers(stripPromptEcho(raw))
            return if (final) clean.trim() else clean
        }
    }
}
