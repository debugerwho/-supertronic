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

    private var loadedDirPath: String? = null
    private var loadedThreads: Int = -1

    /** Direct engine access for legacy read-only callers. Inference must go through [withEngine]. */
    internal val rawEngine: SpeechEngine get() = onnxEngine

    fun getDiagnostics(): List<ChunkSynthesisDiagnostic> = onnxEngine.getDiagnostics()

    suspend fun load(modelDir: File, numThreads: Int = 2): Boolean = mutex.withLock {
        // Idempotent: re-entering reading must not reload the ~400 MB model with the same settings.
        if (onnxEngine.isLoaded &&
            loadedDirPath == modelDir.absolutePath &&
            loadedThreads == numThreads
        ) {
            _isLoaded.value = true
            return@withLock true
        }

        val result = onnxEngine.load(modelDir, numThreads)
        _isLoaded.value = onnxEngine.isLoaded
        _currentThreads.value = onnxEngine.currentThreads
        _voiceStyles.value = onnxEngine.getVoiceStyles()
        if (result) {
            loadedDirPath = modelDir.absolutePath
            loadedThreads = numThreads
        } else {
            loadedDirPath = null
            loadedThreads = -1
        }
        result
    }

    suspend fun <T> withEngine(block: suspend (SpeechEngine) -> T): T = mutex.withLock {
        block(onnxEngine)
    }

    fun getVoiceStyle(id: String): VoiceStyle? = onnxEngine.getVoiceStyle(id)

    fun getVoiceStyles(): List<VoiceStyle> = onnxEngine.getVoiceStyles()

    suspend fun close() = mutex.withLock {
        onnxEngine.close()
        loadedDirPath = null
        loadedThreads = -1
        _isLoaded.value = false
        _voiceStyles.value = emptyList()
    }
}
