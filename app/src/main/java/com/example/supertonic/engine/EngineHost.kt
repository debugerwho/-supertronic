package com.example.supertonic.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Singleton holder ensuring exactly ONE instance of SpeechEngine per process.
 * Synchronizes engine initialization, configuration, and inference.
 */
object EngineHost {
    private val mutex = Mutex()
    private val onnxEngine: OnnxSpeechEngine = OnnxSpeechEngine()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _currentThreads = MutableStateFlow(2)
    val currentThreads: StateFlow<Int> = _currentThreads.asStateFlow()

    private val _voiceStyles = MutableStateFlow<List<VoiceStyle>>(emptyList())
    val voiceStyles: StateFlow<List<VoiceStyle>> = _voiceStyles.asStateFlow()

    val rawEngine: SpeechEngine get() = onnxEngine

    fun getDiagnostics(): List<ChunkSynthesisDiagnostic> = onnxEngine.getDiagnostics()

    suspend fun load(modelDir: File, numThreads: Int = 2): Boolean = mutex.withLock {
        val result = onnxEngine.load(modelDir, numThreads)
        _isLoaded.value = onnxEngine.isLoaded
        _currentThreads.value = onnxEngine.currentThreads
        _voiceStyles.value = onnxEngine.getVoiceStyles()
        result
    }

    suspend fun <T> withEngine(block: suspend (SpeechEngine) -> T): T = mutex.withLock {
        block(onnxEngine)
    }

    fun getVoiceStyle(id: String): VoiceStyle? = onnxEngine.getVoiceStyle(id)

    fun getVoiceStyles(): List<VoiceStyle> = onnxEngine.getVoiceStyles()

    suspend fun close() = mutex.withLock {
        onnxEngine.close()
        _isLoaded.value = false
        _voiceStyles.value = emptyList()
    }
}
