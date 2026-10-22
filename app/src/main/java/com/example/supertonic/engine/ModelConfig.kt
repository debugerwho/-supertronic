package com.example.supertonic.engine

import org.json.JSONObject
import java.io.File

/**
 * Supertonic 3 configuration parsed from tts.json
 */
data class SupertonicConfig(
    val sampleRate: Int = 44100,
    val baseChunkSize: Int = 512,
    val chunkCompressFactor: Int = 6,
    val latentDim: Int = 24
) {
    val chunkSize: Int get() = baseChunkSize * chunkCompressFactor // 3072
    val totalLatentDim: Int get() = latentDim * chunkCompressFactor // 144

    companion object {
        fun fromJson(jsonStr: String): SupertonicConfig {
            val root = JSONObject(jsonStr)
            val ae = root.optJSONObject("ae")
            val ttl = root.optJSONObject("ttl")
            return SupertonicConfig(
                sampleRate = ae?.optInt("sample_rate", 44100) ?: 44100,
                baseChunkSize = ae?.optInt("base_chunk_size", 512) ?: 512,
                chunkCompressFactor = ttl?.optInt("chunk_compress_factor", 6) ?: 6,
                latentDim = ttl?.optInt("latent_dim", 24) ?: 24
            )
        }
    }
}

/**
 * Voice Style container holding pre-parsed TTL and DP style tensors
 */
data class VoiceStyle(
    val id: String,
    val displayName: String,
    val ttlData: FloatArray, // 1 * 50 * 256 = 12800 floats
    val dpData: FloatArray,  // 1 * 8 * 16 = 128 floats
    val ttlDims: LongArray = longArrayOf(1, 50, 256),
    val dpDims: LongArray = longArrayOf(1, 8, 16)
) {
    companion object {
        fun fromJsonFile(file: File, id: String, displayName: String): VoiceStyle {
            val jsonStr = file.readText()
            val root = JSONObject(jsonStr)

            // Extract style_ttl data
            val ttlObj = root.getJSONObject("style_ttl")
            val ttlBatch = ttlObj.getJSONArray("data").getJSONArray(0)
            val ttlFloats = FloatArray(50 * 256)
            var ttlIdx = 0
            for (r in 0 until ttlBatch.length()) {
                val row = ttlBatch.getJSONArray(r)
                for (c in 0 until row.length()) {
                    ttlFloats[ttlIdx++] = row.getDouble(c).toFloat()
                }
            }

            // Extract style_dp data
            val dpObj = root.getJSONObject("style_dp")
            val dpBatch = dpObj.getJSONArray("data").getJSONArray(0)
            val dpFloats = FloatArray(8 * 16)
            var dpIdx = 0
            for (r in 0 until dpBatch.length()) {
                val row = dpBatch.getJSONArray(r)
                for (c in 0 until row.length()) {
                    dpFloats[dpIdx++] = row.getDouble(c).toFloat()
                }
            }

            return VoiceStyle(
                id = id,
                displayName = displayName,
                ttlData = ttlFloats,
                dpData = dpFloats
            )
        }
    }
}

/**
 * Supertonic model file descriptors
 */
data class ModelFileDescriptor(
    val relativePath: String,
    val remoteUrl: String,
    val expectedSizeBytes: Long,
    val isMandatory: Boolean = true
)

object SupertonicConstants {
    const val HF_REPO_BASE = "https://huggingface.co/supertone-oss-archive/supertonic-3/resolve/main"

    // Supported Supertonic 3 languages
    val SUPPORTED_LANGUAGES = listOf(
        "ru" to "Русский",
        "en" to "English",
        "uk" to "Українська",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pl" to "Polski",
        "pt" to "Português",
        "ro" to "Română",
        "bg" to "Български",
        "cs" to "Čeština",
        "sk" to "Slovenčina",
        "sl" to "Slovenščina",
        "hr" to "Hrvatski",
        "sr" to "Српски",
        "el" to "Ελληνικά",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "hi" to "हिन्दी",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "vi" to "Tiếng Việt",
        "id" to "Bahasa Indonesia",
        "nl" to "Nederlands",
        "sv" to "Svenska",
        "da" to "Dansk",
        "fi" to "Suomi",
        "et" to "Eesti",
        "lv" to "Latviešu",
        "lt" to "Lietuvių",
        "hu" to "Magyar"
    )

    val MODEL_FILES = listOf(
        ModelFileDescriptor(
            relativePath = "tts.json",
            remoteUrl = "$HF_REPO_BASE/onnx/tts.json",
            expectedSizeBytes = 6144L
        ),
        ModelFileDescriptor(
            relativePath = "unicode_indexer.json",
            remoteUrl = "$HF_REPO_BASE/onnx/unicode_indexer.json",
            expectedSizeBytes = 265000L
        ),
        ModelFileDescriptor(
            relativePath = "duration_predictor.onnx",
            remoteUrl = "$HF_REPO_BASE/onnx/duration_predictor.onnx",
            expectedSizeBytes = 1400000L
        ),
        ModelFileDescriptor(
            relativePath = "text_encoder.onnx",
            remoteUrl = "$HF_REPO_BASE/onnx/text_encoder.onnx",
            expectedSizeBytes = 71600000L
        ),
        ModelFileDescriptor(
            relativePath = "vector_estimator.onnx",
            remoteUrl = "$HF_REPO_BASE/onnx/vector_estimator.onnx",
            expectedSizeBytes = 211000000L
        ),
        ModelFileDescriptor(
            relativePath = "vocoder.onnx",
            remoteUrl = "$HF_REPO_BASE/onnx/vocoder.onnx",
            expectedSizeBytes = 113000000L
        ),
        ModelFileDescriptor(
            relativePath = "voice_styles/F1.json",
            remoteUrl = "$HF_REPO_BASE/voice_styles/F1.json",
            expectedSizeBytes = 155000L
        ),
        ModelFileDescriptor(
            relativePath = "voice_styles/F2.json",
            remoteUrl = "$HF_REPO_BASE/voice_styles/F2.json",
            expectedSizeBytes = 155000L
        ),
        ModelFileDescriptor(
            relativePath = "voice_styles/M1.json",
            remoteUrl = "$HF_REPO_BASE/voice_styles/M1.json",
            expectedSizeBytes = 155000L
        ),
        ModelFileDescriptor(
            relativePath = "voice_styles/M2.json",
            remoteUrl = "$HF_REPO_BASE/voice_styles/M2.json",
            expectedSizeBytes = 155000L
        )
    )
}
