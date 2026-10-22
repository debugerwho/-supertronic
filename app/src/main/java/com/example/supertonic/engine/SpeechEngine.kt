package com.example.supertonic.engine

import java.io.File

/**
 * Synthesis result for a single text chunk
 */
data class PcmSynthesisResult(
    val pcmBytes: ByteArray,
    val durationSeconds: Float,
    val synthesisTimeMs: Long,
    val sampleRate: Int,
    val rtf: Float
)

/**
 * Common abstraction for the Supertonic text-to-speech engine.
 */
interface SpeechEngine {
    val isLoaded: Boolean
    val currentThreads: Int

    fun load(modelDir: File, numThreads: Int = 2): Boolean
    fun close()
    fun getVoiceStyles(): List<VoiceStyle>
    fun getVoiceStyle(id: String): VoiceStyle?
    fun synthesizeChunk(
        rawText: String,
        lang: String,
        voiceStyle: VoiceStyle,
        totalSteps: Int = 3,
        speed: Float = 1.0f
    ): PcmSynthesisResult?
}
