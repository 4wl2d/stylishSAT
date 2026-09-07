package com.tomilov.stylishsat.ai

data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(id.matches(Regex("[a-z0-9_-]+")))
        require(fileName.matches(Regex("[A-Za-z0-9._-]+")))
        require(url.startsWith("https://"))
        require(sizeBytes > 0)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Immutable upstream revisions and LFS SHA-256 values checked on 2026-09-05. */
object ModelCatalog {
    val gemma = ModelSpec(
        "gemma4_e2b", "Gemma 4 E2B IT", "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        2_588_147_712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
    )
    val whisper = ModelSpec(
        "whisper_base_en", "Whisper base.en", "ggml-base.en.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en.bin",
        147_964_211L, "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
    )
    val all = listOf(gemma, whisper)
}
