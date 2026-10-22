package com.example.supertonic.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.supertonic.data.DocumentChapterMetadata
import com.example.supertonic.data.DocumentMetadata
import com.example.supertonic.data.DocumentStore
import com.example.supertonic.data.ParagraphItem
import com.example.supertonic.data.ReadingSession
import com.example.supertonic.data.SessionStore
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val documentStore = DocumentStore(application)
    val sessionStore = SessionStore(application)

    private var playbackService: SupertonicPlaybackService? = null
    private var isBound = false

    val downloadState: StateFlow<ModelDownloadState> = modelManager.downloadState
    val currentEngineThreads: Int
        get() = com.example.supertonic.engine.EngineHost.currentThreads.value

    // Document and Paragraphs on-disk management
    val currentDocMetadata = MutableStateFlow<DocumentMetadata?>(null)
    val currentChapterIndex = MutableStateFlow(0)
    val currentChapterText = MutableStateFlow("")
    val currentParagraphs = MutableStateFlow<List<ParagraphItem>>(emptyList())
    val currentParagraphIndex = MutableStateFlow(0)
    val documentStats = MutableStateFlow("0 симв. · 0 слов")

    // Document Import State
    val isImportingDocument = MutableStateFlow(false)
    val importProgress = MutableStateFlow<String?>(null)

    // Speech Settings
    val selectedLang = MutableStateFlow("auto")
    val detectedLang = MutableStateFlow("ru")
    val selectedVoice = MutableStateFlow("F1")
    val synthesisSteps = MutableStateFlow(3)
    val speechSpeed = MutableStateFlow(1.0f)
    val overlayEnabled = MutableStateFlow(true)

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

    private var libraryMediaPlayer: MediaPlayer? = null
    val currentlyPlayingTrack = MutableStateFlow<SavedAudioTrack?>(null)

    private var langDetectJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SupertonicPlaybackService.LocalBinder
            playbackService = localBinder?.getService()
            isBound = true

            playbackService?.let { s ->
                viewModelScope.launch {
                    s.playbackState.collect { state ->
                        _servicePlaybackState.value = state
                        updateActiveParagraph(state.currentSentenceStart)
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

        // Initialize document from session or default intro
        viewModelScope.launch {
            initInitialDocument()
        }

        // Auto-save session position every 5 seconds
        viewModelScope.launch {
            while (isActive) {
                delay(5000)
                saveCurrentSession()
            }
        }
    }

    private suspend fun initInitialDocument() {
        val restoredSession = sessionStore.loadSession()
        if (restoredSession != null) {
            val meta = documentStore.loadMetadata(restoredSession.documentId)
            if (meta != null) {
                selectedVoice.value = restoredSession.voice
                synthesisSteps.value = restoredSession.steps
                speechSpeed.value = restoredSession.speed
                selectedLang.value = restoredSession.lang
                loadChapterInternal(meta, restoredSession.chapterIndex)
                return
            }
        }

        // Default introduction text stored to DocumentStore
        val defaultIntro = "Привет! Это автономный синтезатор речи Supertonic 3.\n\n" +
                "Выделите любой текст в другом приложении, импортируйте документ (TXT, PDF, EPUB) или откройте экран «Текст», чтобы начать быстрое и плавное чтение без интернета.\n\n" +
                "Все вычисления выполняются локально на вашем процессоре с использованием ONNX Runtime."
        val meta = documentStore.saveDocument(
            title = "Добро пожаловать",
            sourceUri = null,
            fullText = defaultIntro,
            chapters = listOf(DocumentChapterMetadata(title = "Введение", charStart = 0, charEnd = defaultIntro.length))
        )
        loadChapterInternal(meta, 0)
    }

    fun bindPlaybackService() {
        val intent = Intent(getApplication(), SupertonicPlaybackService::class.java)
        getApplication<Application>().startService(intent)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateActiveParagraph(sentenceStart: Int) {
        val paras = currentParagraphs.value
        if (paras.isEmpty()) return
        val found = paras.indexOfFirst { sentenceStart >= it.charStart && sentenceStart < it.charEnd }
        if (found != -1 && found != currentParagraphIndex.value) {
            currentParagraphIndex.value = found
        }
    }

    fun scheduleLanguageDetection(text: String) {
        langDetectJob?.cancel()
        langDetectJob = viewModelScope.launch(Dispatchers.Default) {
            delay(300)
            val sample = text.take(4000)
            if (sample.isNotBlank()) {
                val res = LanguageDetector.detectLanguage(sample, priorityRussian = true)
                detectedLang.value = res.detectedLang
            }
        }
    }

    fun loadChapter(chapterIndex: Int) {
        val meta = currentDocMetadata.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            loadChapterInternal(meta, chapterIndex)
        }
    }

    private suspend fun loadChapterInternal(meta: DocumentMetadata, chapterIndex: Int) = withContext(Dispatchers.IO) {
        val clampedIndex = chapterIndex.coerceIn(0, (meta.chapters.size - 1).coerceAtLeast(0))
        val chap = meta.chapters.getOrNull(clampedIndex) ?: meta.chapters.first()
        val text = documentStore.loadChapterText(meta.id, chap.charStart, chap.charEnd)
        val paragraphs = DocumentStore.splitIntoParagraphs(text, baseOffset = 0)

        val words = text.split("\\s+".toRegex()).count { it.isNotBlank() }
        val stats = "${text.length} симв. · $words слов"

        withContext(Dispatchers.Main) {
            currentDocMetadata.value = meta
            currentChapterIndex.value = clampedIndex
            currentChapterText.value = text
            currentParagraphs.value = paragraphs
            currentParagraphIndex.value = 0
            documentStats.value = stats
            scheduleLanguageDetection(text)
        }
    }

    fun importDocument(uri: Uri, fileName: String) {
        isImportingDocument.value = true
        importProgress.value = "Чтение файла книги..."

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parsed = DocumentParser.parseUri(getApplication(), uri, fileName)
                if (parsed.fullText.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        importProgress.value = "Сохранение документа на диск..."
                    }
                    var charOffset = 0
                    val metaChapters = parsed.chapters.map { c ->
                        val start = charOffset
                        val end = start + c.text.length
                        charOffset = end
                        DocumentChapterMetadata(
                            title = c.title,
                            charStart = start,
                            charEnd = end
                        )
                    }
                    val meta = documentStore.saveDocument(
                        title = parsed.title,
                        sourceUri = uri.toString(),
                        fullText = parsed.fullText,
                        chapters = metaChapters
                    )
                    loadChapterInternal(meta, 0)
                }
            } catch (e: Exception) {
                Log.e(tag, "Document import error", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isImportingDocument.value = false
                    importProgress.value = null
                }
            }
        }
    }

    fun saveManualText(title: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val meta = documentStore.saveDocument(
                title = title.ifBlank { "Заметка" },
                sourceUri = null,
                fullText = text,
                chapters = emptyList()
            )
            loadChapterInternal(meta, 0)
        }
    }

    fun getAvailableVoices(): List<String> {
        val installed = modelManager.getInstalledVoiceIds()
        return if (installed.isNotEmpty()) installed else listOf("F1", "F2", "M1", "M2")
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
                text = currentChapterText.value,
                lang = langToUse,
                voiceId = selectedVoice.value,
                steps = synthesisSteps.value,
                speed = speechSpeed.value,
                showOverlay = showOverlay,
                paragraphs = currentParagraphs.value
            )
        }
    }

    fun readFromParagraph(paragraphIndex: Int) {
        val service = playbackService ?: return
        val currentStatus = servicePlaybackState.value.status
        if (currentStatus == PlaybackStatus.IDLE || currentStatus == PlaybackStatus.COMPLETED || currentStatus == PlaybackStatus.ERROR) {
            val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value
            val showOverlay = overlayEnabled.value && Settings.canDrawOverlays(getApplication())
            // The paragraph is resolved to a chunk inside playText(), after segmentation built a fresh
            // index. Seeking right after playText() used the previous (or empty) index and therefore
            // always restarted reading from the beginning of the chapter.
            service.playText(
                text = currentChapterText.value,
                lang = langToUse,
                voiceId = selectedVoice.value,
                steps = synthesisSteps.value,
                speed = speechSpeed.value,
                startParagraphIndex = paragraphIndex,
                showOverlay = showOverlay,
                paragraphs = currentParagraphs.value
            )
        } else {
            service.seekToParagraph(paragraphIndex)
        }
    }

    fun stopPlayback() {
        playbackService?.stop()
    }

    fun retrySynthesis() {
        playbackService?.retrySynthesis()
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

    /** Absolute seek used by the timeline slider and MediaSession scrubbing. */
    fun seekToSeconds(seconds: Float) {
        playbackService?.seekToSeconds(seconds)
    }

    fun onSpeedChanged(newSpeed: Float) {
        speechSpeed.value = newSpeed
        playbackService?.setPlaybackSpeed(newSpeed)
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled.value = enabled
    }

    // --- CPU Settings & Calibration --- //

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

    fun startBackgroundBatchExport(format: String = "m4a") {
        val service = playbackService
        val textToExport = currentChapterText.value
        if (service == null || textToExport.isBlank()) {
            exportStatusMessage.value = "Нет текста для создания файла"
            return
        }

        val exportDir = File(getApplication<Application>().filesDir, "saved_recordings").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val targetFile = File(exportDir, "audio_${timeStamp}.$format")

        isExporting.value = true
        exportProgress.value = 0.05f
        exportStatusMessage.value = "Создание аудиофайла в памяти и кодирование в $format..."

        val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value

        service.startIndependentBatchExport(
            text = textToExport,
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
        val service = playbackService
        val textToExport = currentChapterText.value
        if (service == null || textToExport.isBlank()) {
            exportStatusMessage.value = "Нет текста для открытия в VLC"
            return
        }

        val exportDir = File(getApplication<Application>().filesDir, "saved_recordings").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val targetFile = File(exportDir, "vlc_${timeStamp}.$format")

        isExporting.value = true
        exportProgress.value = 0.05f
        exportStatusMessage.value = "Озвучивание для VLC в фоне..."

        val langToUse = if (selectedLang.value == "auto") detectedLang.value else selectedLang.value

        service.startIndependentBatchExport(
            text = textToExport,
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
                title = f.nameWithoutExtension.removePrefix("audio_").removePrefix("vlc_"),
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

            // Pause book playback when track starts per Task 4
            playbackService?.pause()

            val audioManager = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .build()
            audioManager.requestAudioFocus(focusRequest)

            libraryMediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
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

    fun onAppPause() {
        viewModelScope.launch {
            saveCurrentSession()
        }
    }

    private suspend fun saveCurrentSession() {
        val meta = currentDocMetadata.value ?: return
        val state = servicePlaybackState.value
        val session = ReadingSession(
            documentId = meta.id,
            chapterIndex = currentChapterIndex.value,
            chunkIndex = state.currentChunkIndex,
            sampleOffset = 0L,
            voice = selectedVoice.value,
            steps = synthesisSteps.value,
            speed = speechSpeed.value,
            lang = selectedLang.value
        )
        sessionStore.saveSession(session)
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
