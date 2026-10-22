package com.example.supertonic.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.supertonic.document.DocumentChapter
import com.example.supertonic.document.DocumentParser
import com.example.supertonic.engine.CalibrationReport
import com.example.supertonic.engine.CpuAffinityManager
import com.example.supertonic.engine.CpuCoreInfo
import com.example.supertonic.engine.CpuPreset
import com.example.supertonic.engine.ModelDownloadState
import com.example.supertonic.engine.ModelManager
import com.example.supertonic.engine.PerformanceCalibrator
import com.example.supertonic.language.LanguageDetector
import com.example.supertonic.playback.PlaybackState
import com.example.supertonic.playback.PlaybackStatus
import com.example.supertonic.playback.SupertonicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedAudioTrack(
    val file: File,
    val title: String,
    val format: String,
    val sizeBytes: Long,
    val dateModified: Long
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "MainViewModel"
    val modelManager = ModelManager(application)
    val calibrator = PerformanceCalibrator(application)
    val cpuAffinity = CpuAffinityManager(application)

    private var playbackService: SupertonicPlaybackService? = null
    private var isBound = false

    val downloadState: StateFlow<ModelDownloadState> = modelManager.downloadState

    // Editor & Speech Settings
    val inputText = MutableStateFlow("Привет! Это автономный синтезатор речи Supertonic 3. Выделите любой текст в другом приложении, импортируйте документ (TXT, PDF, EPUB) или вставьте текст сюда, чтобы начать быстрое и плавное чтение без интернета.")
    val selectedLang = MutableStateFlow("auto") // "auto" or language code
    val detectedLang = MutableStateFlow("ru")
    val selectedVoice = MutableStateFlow("F1")
    val synthesisSteps = MutableStateFlow(3) // 2, 3, 5, 8
    val speechSpeed = MutableStateFlow(1.0f) // 0.8, 1.0, 1.3, 1.5, 2.0
    val overlayEnabled = MutableStateFlow(true)

    // Document Import State
    val importedDocumentTitle = MutableStateFlow<String?>(null)
    val documentChapters = MutableStateFlow<List<DocumentChapter>>(emptyList())
    val isImportingDocument = MutableStateFlow(false)

    // CPU Affinity Cores State
    val availableCores = MutableStateFlow<List<CpuCoreInfo>>(cpuAffinity.getAvailableCores())

    // Calibration
    val calibrationReport = MutableStateFlow<CalibrationReport?>(null)
    val isCalibrating = MutableStateFlow(false)
    val calibrationProgress = MutableStateFlow("")

    // Export & Library
    val savedTracks = MutableStateFlow<List<SavedAudioTrack>>(emptyList())
    val isExporting = MutableStateFlow(false)
    val exportProgress = MutableStateFlow(0f)
    val exportStatusMessage = MutableStateFlow<String?>(null)

    // Service Playback State
    private val _servicePlaybackState = MutableStateFlow(PlaybackState())
    val servicePlaybackState: StateFlow<PlaybackState> = _servicePlaybackState.asStateFlow()

    // Independent preview player for saved library tracks (does not interrupt active speech inference)
    private var libraryMediaPlayer: MediaPlayer? = null
    val currentlyPlayingTrack = MutableStateFlow<SavedAudioTrack?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SupertonicPlaybackService.LocalBinder
            playbackService = localBinder?.getService()
            isBound = true

            playbackService?.let { s ->
                viewModelScope.launch {
                    s.playbackState.collect { state ->
                        _servicePlaybackState.value = state
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
        }
    }

    init {
        bindPlaybackService()
        refreshSavedTracks()
        updateLanguageDetection(inputText.value)
    }

    fun bindPlaybackService() {
        val intent = Intent(getApplication(), SupertonicPlaybackService::class.java)
        getApplication<Application>().startService(intent)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun onTextChange(newText: String) {
        inputText.value = newText
        updateLanguageDetection(newText)
    }

    private fun updateLanguageDetection(text: String) {
        val result = LanguageDetector.detectLanguage(text, priorityRussian = true)
        detectedLang.value = result.detectedLang
    }

    fun togglePlayPause() {
        val service = playbackService ?: return
        val currentStatus = servicePlaybackState.value.status

        if (currentStatus == PlaybackStatus.PLAYING) {
            service.pause()
        } else if (currentStatus == PlaybackStatus.PAUSED) {
            service.resume()
        } else {
            val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value
            val showOverlay = overlayEnabled.value && Settings.canDrawOverlays(getApplication())
            service.playText(
                text = inputText.value,
                lang = langToUse,
                voiceId = selectedVoice.value,
                steps = synthesisSteps.value,
                speed = speechSpeed.value,
                showOverlay = showOverlay
            )
        }
    }

    fun stopPlayback() {
        playbackService?.stop()
    }

    fun seekToChunk(targetIndex: Int) {
        playbackService?.seekToChunk(targetIndex)
    }

    fun skipNext() {
        playbackService?.skipNext()
    }

    fun skipPrevious() {
        playbackService?.skipPrevious()
    }

    fun jumpSeconds(deltaSec: Int) {
        playbackService?.jumpSeconds(deltaSec)
    }

    fun onSpeedChanged(newSpeed: Float) {
        speechSpeed.value = newSpeed
        playbackService?.setPlaybackSpeed(newSpeed)
    }

    // --- Document Import --- //

    fun importDocument(uri: Uri, fileName: String) {
        isImportingDocument.value = true
        viewModelScope.launch {
            val parsed = DocumentParser.parseUri(getApplication(), uri, fileName)
            if (parsed.fullText.isNotBlank()) {
                inputText.value = parsed.fullText
                importedDocumentTitle.value = parsed.title
                documentChapters.value = parsed.chapters
                updateLanguageDetection(parsed.fullText)
            }
            isImportingDocument.value = false
        }
    }

    fun selectChapter(chapter: DocumentChapter) {
        inputText.value = chapter.text
        updateLanguageDetection(chapter.text)
    }

    // --- CPU Core Affinity --- //

    fun toggleCore(coreId: Int, isEnabled: Boolean) {
        val currentMask = cpuAffinity.getSavedCoreMask()
        val newMask = if (isEnabled) {
            currentMask or (1 shl coreId)
        } else {
            currentMask and (1 shl coreId).inv()
        }
        cpuAffinity.setCoreMask(newMask)
        availableCores.value = cpuAffinity.getAvailableCores()
    }

    fun applyCpuPreset(preset: CpuPreset) {
        cpuAffinity.applyPreset(preset)
        availableCores.value = cpuAffinity.getAvailableCores()
    }

    // --- Calibration --- //

    fun startModelDownload() {
        viewModelScope.launch {
            modelManager.downloadModels()
        }
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload()
    }

    fun runCpuCalibration() {
        if (!modelManager.checkInstalledState()) return
        isCalibrating.value = true
        viewModelScope.launch {
            val report = calibrator.runCalibration(modelManager.modelDir) { currentThreads, total ->
                calibrationProgress.value = "Тест $currentThreads потоков из $total..."
            }
            calibrationReport.value = report
            isCalibrating.value = false
            calibrationProgress.value = ""
        }
    }

    // --- Decoupled Audio File Generation & External Player --- //

    /**
     * Independent Batch File Generation:
     * Synthesizes text directly to audio file at maximum CPU speed without real-time pacing,
     * and without interfering with any ongoing audio playback.
     */
    fun startBackgroundBatchExport(format: String = "m4a") {
        val service = playbackService ?: return
        val text = inputText.value.trim()
        if (text.isEmpty()) {
            exportStatusMessage.value = "Нет текста для создания файла"
            return
        }

        isExporting.value = true
        exportProgress.value = 0.05f
        exportStatusMessage.value = "Создание аудиофайла в фоне..."

        val libraryDir = File(getApplication<Application>().filesDir, "saved_recordings").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = (importedDocumentTitle.value ?: text.take(20)).replace(Regex("[^a-zA-Zа-яА-Я0-9]"), "_")
        val targetFile = File(libraryDir, "audio_${safeTitle}_$timeStamp.$format")

        val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value

        service.startIndependentBatchExport(
            text = text,
            lang = langToUse,
            voiceId = selectedVoice.value,
            steps = synthesisSteps.value,
            targetFile = targetFile,
            format = format,
            onProgress = { p ->
                exportProgress.value = p
            },
            onComplete = { success, error ->
                isExporting.value = false
                if (success) {
                    exportStatusMessage.value = "Готово: ${targetFile.name}"
                    refreshSavedTracks()
                } else {
                    exportStatusMessage.value = "Ошибка: ${error ?: "Сбой"}"
                }
            }
        )
    }

    fun exportAndOpenInVlc(context: Context, format: String = "m4a") {
        val service = playbackService ?: return
        val text = inputText.value.trim()
        if (text.isEmpty()) {
            exportStatusMessage.value = "Нет текста для открытия в VLC"
            return
        }

        isExporting.value = true
        exportProgress.value = 0.05f
        exportStatusMessage.value = "Озвучивание для VLC в фоне..."

        val libraryDir = File(getApplication<Application>().filesDir, "saved_recordings").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val safeTitle = (importedDocumentTitle.value ?: text.take(20)).replace(Regex("[^a-zA-Zа-яА-Я0-9]"), "_")
        val targetFile = File(libraryDir, "audio_${safeTitle}_$timeStamp.$format")
        val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value

        service.startIndependentBatchExport(
            text = text,
            lang = langToUse,
            voiceId = selectedVoice.value,
            steps = synthesisSteps.value,
            targetFile = targetFile,
            format = format,
            onProgress = { p ->
                exportProgress.value = p
            },
            onComplete = { success, error ->
                isExporting.value = false
                if (success) {
                    exportStatusMessage.value = "Открываем файл в VLC..."
                    refreshSavedTracks()
                    openInExternalPlayer(targetFile, context)
                } else {
                    exportStatusMessage.value = "Ошибка подготовки: ${error ?: "Сбой"}"
                }
            }
        )
    }

    fun openInExternalPlayer(trackFile: File, context: Context) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                trackFile
            )
            val mimeType = if (trackFile.extension.lowercase() == "m4a") "audio/mp4" else "audio/wav"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Открыть в VLC или аудиоплеере"))
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch external player", e)
        }
    }

    fun refreshSavedTracks() {
        val libraryDir = File(getApplication<Application>().filesDir, "saved_recordings").apply { mkdirs() }
        val files = libraryDir.listFiles { _, name -> name.endsWith(".m4a") || name.endsWith(".wav") }
        savedTracks.value = files?.map { f ->
            SavedAudioTrack(
                file = f,
                title = f.nameWithoutExtension.removePrefix("audio_"),
                format = f.extension.uppercase(),
                sizeBytes = f.length(),
                dateModified = f.lastModified()
            )
        }?.sortedByDescending { it.dateModified } ?: emptyList()
    }

    fun playTrack(track: SavedAudioTrack) {
        try {
            libraryMediaPlayer?.stop()
            libraryMediaPlayer?.release()

            if (currentlyPlayingTrack.value == track) {
                currentlyPlayingTrack.value = null
                return
            }

            libraryMediaPlayer = MediaPlayer().apply {
                setDataSource(track.file.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    currentlyPlayingTrack.value = null
                }
            }
            currentlyPlayingTrack.value = track
        } catch (e: Exception) {
            Log.e(tag, "Error playing audio file", e)
        }
    }

    fun stopTrackPlayback() {
        libraryMediaPlayer?.stop()
        libraryMediaPlayer?.release()
        libraryMediaPlayer = null
        currentlyPlayingTrack.value = null
    }

    fun deleteTrack(track: SavedAudioTrack) {
        if (currentlyPlayingTrack.value == track) {
            stopTrackPlayback()
        }
        track.file.delete()
        refreshSavedTracks()
    }

    fun clearCache() {
        playbackService?.cache?.clear()
    }

    override fun onCleared() {
        super.onCleared()
        libraryMediaPlayer?.release()
        if (isBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (ignored: Exception) {}
            isBound = false
        }
    }
}
