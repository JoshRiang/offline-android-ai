package com.example.slmchat.llm

/**
 * Catalog of free, openly-licensed small language models that can run on-device
 * via MediaPipe LLM Inference (`.task` format).
 *
 * Default URLs point at the `litert-community` Hugging Face organization, which
 * publishes official `.task` conversions (`multi-prefill-seq_q8_ekv2048`). These
 * URLs change over time — verify the file exists in the linked repo, or paste
 * your own direct link in Settings → Custom model URL.
 *
 * NOTE: Google Gemma weights are gated (you must accept the Gemma license on
 * Kaggle / Hugging Face). Download that file manually once, then place it in
 * the app's `files/models/` directory under [ModelInfo.fileName], or serve it
 * from your own direct URL.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    /** Approximate download size, for display only. */
    val sizeMb: Int,
    /** Direct https URL of the `.task` file. */
    val downloadUrl: String,
    /** Local file name under filesDir/models/. */
    val fileName: String,
    val license: String,
    val contextLength: Int,
    /** True if weights are access-gated and likely need a manual download. */
    val gated: Boolean = false
)

object ModelCatalog {

    const val DEFAULT_MODEL_ID = "qwen2-1.5b-instruct"

    private fun hfTask(repo: String, file: String) =
        "https://huggingface.co/$repo/resolve/main/$file?download=true"

    val models: List<ModelInfo> = listOf(
        ModelInfo(
            id = "qwen2-1.5b-instruct",
            name = "Qwen2 1.5B Instruct",
            description = "Alibaba Qwen2 1.5B, instruction-tuned. Best default: small, fast, Apache-2.0.",
            sizeMb = 1_450,
            downloadUrl = hfTask(
                "litert-community/Qwen2-1.5B-Instruct",
                "Qwen2-1.5B-Instruct_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "qwen2-1.5b-instruct.task",
            license = "Apache-2.0",
            contextLength = 4096
        ),
        ModelInfo(
            id = "smollm2-1.7b-instruct",
            name = "SmolLM2 1.7B Instruct",
            description = "HuggingFaceTB SmolLM2, strong quality-per-MB. Apache-2.0.",
            sizeMb = 1_650,
            downloadUrl = hfTask(
                "litert-community/SmolLM2-1.7B-Instruct",
                "SmolLM2-1.7B-Instruct_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "smollm2-1.7b-instruct.task",
            license = "Apache-2.0",
            contextLength = 4096
        ),
        ModelInfo(
            id = "gemma2-2b-it",
            name = "Gemma 2 2B IT",
            description = "Google Gemma 2 2B, instruction-tuned. Gated: accept the license first.",
            sizeMb = 1_600,
            downloadUrl = hfTask(
                "litert-community/Gemma2-2B-IT",
                "Gemma2-2B-IT_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "gemma2-2b-it.task",
            license = "Gemma",
            contextLength = 4096,
            gated = true
        ),
        ModelInfo(
            id = "phi-3-mini-4k",
            name = "Phi-3 Mini 4K",
            description = "Microsoft Phi-3 Mini 3.8B, 4K context. MIT licensed, needs a capable device.",
            sizeMb = 2_300,
            downloadUrl = hfTask(
                "litert-community/Phi-3-mini-4k-instruct",
                "Phi-3-mini-4k-instruct_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "phi-3-mini-4k.task",
            license = "MIT",
            contextLength = 4096
        ),
        ModelInfo(
            id = "stablelm-zephyr-3b",
            name = "StableLM Zephyr 3B",
            description = "Stability AI 3B chat model. Larger download, good instruction following.",
            sizeMb = 1_900,
            downloadUrl = hfTask(
                "litert-community/stablelm-zephyr-3b",
                "stablelm-zephyr-3b_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "stablelm-zephyr-3b.task",
            license = "StabilityAI NC",
            contextLength = 4096
        ),
        ModelInfo(
            id = "tinyllama-1.1b-chat",
            name = "TinyLlama 1.1B Chat",
            description = "Smallest fallback (~0.7 GB). Fastest on low-end devices, weakest quality.",
            sizeMb = 750,
            downloadUrl = hfTask(
                "litert-community/TinyLlama-1.1B-Chat-v1.0",
                "TinyLlama-1.1B-Chat-v1.0_multi-prefill-seq_q8_ekv2048.task"
            ),
            fileName = "tinyllama-1.1b-chat.task",
            license = "Apache-2.0",
            contextLength = 2048
        )
    )

    fun getById(id: String): ModelInfo? = models.find { it.id == id }

    fun requireById(id: String): ModelInfo = getById(id) ?: models.first { it.id == DEFAULT_MODEL_ID }
}
