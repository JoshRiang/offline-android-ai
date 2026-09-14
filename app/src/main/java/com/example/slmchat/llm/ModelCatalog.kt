package com.example.slmchat.llm

/**
 * Catalog of free, openly-licensed small language models that can run on-device
 * via MediaPipe LLM Inference (`.task` format).
 *
 * NOTE: The litert-community repos now use `.litertlm` format instead of `.task`.
 * These models require manual download from Hugging Face, then placing in the
 * app's `files/models/` directory under [ModelInfo.fileName].
 * Or serve your own direct URL via Settings → Custom model URL.
 *
 * For now, only TinyLlama has a working direct .task download.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    /** Approximate download size, for display only. */
    val sizeMb: Int,
    /** Direct https URL of the `.task` file. Empty = manual download needed. */
    val downloadUrl: String,
    /** Local file name under filesDir/models/. */
    val fileName: String,
    val license: String,
    val contextLength: Int,
    /** True if weights are access-gated and likely need a manual download. */
    val gated: Boolean = false
)

object ModelCatalog {

    const val DEFAULT_MODEL_ID = "tinyllama-1.1b-chat"

    private fun hfTask(repo: String, file: String) =
        "https://huggingface.co/$repo/resolve/main/$file?download=true"

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "tinyllama-1.1b-chat",
            name = "TinyLlama 1.1B Chat (auto-download)",
            description = "Smallest fallback (~0.7 GB). Fastest on low-end devices, weakest quality. Only model with working auto-download.",
            sizeMb = 750,
            downloadUrl = hfTask(
                "litert-community/TinyLlama-1.1B-Chat-v1.0",
                "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv1280.task"
            ),
            fileName = "tinyllama-1.1b-chat.task",
            license = "Apache-2.0",
            contextLength = 2048
        ),
        ModelInfo(
            id = "qwen2-1.5b-instruct",
            name = "Qwen2 1.5B Instruct (manual)",
            description = "Alibaba Qwen2 1.5B, instruction-tuned. Best default: small, fast, Apache-2.0. Manual download required (.litertlm format).",
            sizeMb = 1_450,
            downloadUrl = "", // Manual: https://huggingface.co/litert-community/Qwen2-1.5B-Instruct/tree/main
            fileName = "qwen2-1.5b-instruct.task",
            license = "Apache-2.0",
            contextLength = 4096,
            gated = false
        ),
        ModelInfo(
            id = "smollm2-1.7b-instruct",
            name = "SmolLM2 1.7B Instruct (manual)",
            description = "HuggingFaceTB SmolLM2, strong quality-per-MB. Apache-2.0. Manual download required.",
            sizeMb = 1_650,
            downloadUrl = "", // Manual
            fileName = "smollm2-1.7b-instruct.task",
            license = "Apache-2.0",
            contextLength = 4096,
            gated = false
        ),
        ModelInfo(
            id = "gemma2-2b-it",
            name = "Gemma 2 2B IT (manual, gated)",
            description = "Google Gemma 2 2B, instruction-tuned. Gated: accept the license first on Hugging Face.",
            sizeMb = 1_600,
            downloadUrl = "", // Gated + manual
            fileName = "gemma2-2b-it.task",
            license = "Gemma",
            contextLength = 4096,
            gated = true
        ),
        ModelInfo(
            id = "phi-3-mini-4k",
            name = "Phi-3 Mini 4K (manual)",
            description = "Microsoft Phi-3 Mini 3.8B, 4K context. MIT licensed, needs a capable device. Manual download required.",
            sizeMb = 2_300,
            downloadUrl = "", // Manual
            fileName = "phi-3-mini-4k.task",
            license = "MIT",
            contextLength = 4096,
            gated = false
        ),
        ModelInfo(
            id = "stablelm-zephyr-3b",
            name = "StableLM Zephyr 3B (manual)",
            description = "Stability AI 3B chat model. Larger download, good instruction following. Manual download required.",
            sizeMb = 1_900,
            downloadUrl = "", // Manual
            fileName = "stablelm-zephyr-3b.task",
            license = "StabilityAI NC",
            contextLength = 4096,
            gated = false
        )
    )

    fun getById(id: String): ModelInfo? = models.find { it.id == id }

    fun requireById(id: String): ModelInfo = getById(id) ?: models.first { it.id == DEFAULT_MODEL_ID }
}
