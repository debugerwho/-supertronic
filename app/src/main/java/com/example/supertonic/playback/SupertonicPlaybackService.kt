package com.example.supertonic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.supertonic.audio.AudioTrackSink
import com.example.supertonic.audio.PcmRing
import com.example.supertonic.audio.PlaybackClock
import com.example.supertonic.audio.SynthesisScheduler
import com.example.supertonic.core.SourceIndex
import com.example.supertonic.data.ParagraphItem
import com.example.supertonic.engine.CpuAffinityManager
import com.example.supertonic.engine.EngineHost
import com.example.supertonic.engine.ModelManager
import com.example.supertonic.engine.SpeechEngine
import com.example.supertonic.language.TextChunk
import com.example.supertonic.language.TextSegmenter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

enum class PlaybackStatus {
    IDLE,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    COMPLETED,
    ERROR
}

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val currentParagraphIndex: Int = 0,
    val currentSentenceText: String = "",
    val currentSentenceStart: Int = 0,
    val currentSentenceEnd: Int = 0,
    val timelineDisplay: String = "00:00 / 00:00",
    val playbackProgress: Float = 0f,
    val currentSeconds: Float = 0f,
    val totalEstimatedSeconds: Float = 0f,
    val bufferedSeconds: Float = 0f,
    val rtf: Float = 1.0f,
    val underruns: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val synthesizedChunksCount: Int = 0,
    val isChunkSynthesized: List<Boolean> = emptyList(),
    val errorMessage: String? = null
)

class SupertonicPlaybackService : Service() {
    private val tag = "PlaybackService"

    companion object {
        const val CHANNEL_ID = "supertonic_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.supertonic.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.supertonic.ACTION_PAUSE"
        const val ACTION_STOP = "com.example.supertonic.ACTION_STOP"
        const val ACTION_SKIP_NEXT = "com.example.supertonic.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREV = "com.example.supertonic.ACTION_SKIP_PREV"

        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_LANG = "extra_lang"
        const val EXTRA_VOICE = "extra_voice"
        const val EXTRA_SHOW_OVERLAY = "extra_show_overlay"

        /** How often the playback position (timeline / MediaSession) is published, in ms. */
        private const val UI_TICK_MS = 250L
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): SupertonicPlaybackService = this@SupertonicPlaybackService
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var prepJob: Job? = null
    private var playbackJob: Job? = null
    private var batchExportJob: Job? = null

    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentFocusRequest: AudioFocusRequest? = null

    lateinit var modelManager: ModelManager
        private set
    lateinit var cpuAffinity: CpuAffinityManager
        private set
    lateinit var overlayManager: FloatingOverlayManager
        private set

    val pcmRing = PcmRing()
    val sink = AudioTrackSink(sampleRate = 44100)
    val clock = PlaybackClock(sink, 44100)
    private lateinit var scheduler: SynthesisScheduler

    private var sourceIndex: SourceIndex? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val generationId = AtomicInteger(0)
    private var activeChunks: List<TextChunk> = emptyList()

    // Position bookkeeping inside the current chunk: needed so that pause/resume continues at the
    // exact frame instead of replaying the whole phrase, and so that a seek can hand the sink a
    // byte offset. All absolute positions still live in PlaybackClock.
    @Volatile private var currentPlaybackChunk: Int = -1
    @Volatile private var currentChunkWriteOffset: Int = 0
    @Volatile private var pausedChunkIndex: Int = -1
    @Volatile private var pausedChunkByteOffset: Int = 0

    /** True when the clock base refers to the current timeline (set on play/seek, cleared on stop). */
    @Volatile private var timelineAnchored: Boolean = false

    /** Reused chunk buffer: the playback loop no longer allocates a ByteArray per chunk. */
    private var pcmScratch: ByteArray = ByteArray(0)

    @Volatile
    private var activeVoiceId: String = "F1"
    @Volatile
    private var activeSteps: Int = 3
    @Volatile
    private var activeLang: String = "ru"

    private var lastNotificationText: String? = null
    private var lastNotificationTime: Long = 0L

    // Audio Focus listener: pause audio output without auto-resuming on AUDIOFOCUS_GAIN
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Do NOT call resume() automatically on focus gain per requirements
            }
        }
    }

    // Noisy audio (headphones unplugged)
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        modelManager = ModelManager(this)
        cpuAffinity = CpuAffinityManager(this)
        overlayManager = FloatingOverlayManager(this, this)

        scheduler = SynthesisScheduler(pcmRing, serviceScope).apply {
            onChunkSynthesized = { chunkIndex, durationSec, rtf ->
                // Exact durations move every later span. Without shifting the clock base by the same
                // delta the displayed position (and progress) would drift while chunks are refined.
                val anchor = _playbackState.value.currentChunkIndex
                val anchorBefore = sourceIndex?.sampleStartOf(anchor) ?: 0L
                sourceIndex?.updateSynthesizedChunk(chunkIndex, durationSec)
                val anchorAfter = sourceIndex?.sampleStartOf(anchor) ?: 0L
                if (timelineAnchored && anchorAfter != anchorBefore) {
                    clock.shiftBaseBy(anchorAfter - anchorBefore)
                }
                val total = activeChunks.size
                val synthList = List(total) { idx -> pcmRing.isReady(idx) }
                val curSec = clock.currentSeconds
                val timeline = sourceIndex?.formatTimeline(curSec) ?: "00:00 / 00:00"

                _playbackState.value = _playbackState.value.copy(
                    rtf = rtf,
                    synthesizedChunksCount = synthList.count { it },
                    isChunkSynthesized = synthList,
                    timelineDisplay = timeline,
                    totalEstimatedSeconds = sourceIndex?.getTotalEstimatedDurationSeconds() ?: 0f
                )
            }
            onSynthesisError = { chunkIndex, errorMsg ->
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = errorMsg
                )
            }
        }

        cleanLegacyPcmFiles()
        createNotificationChannel()
        setupMediaSession()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
    }

    private fun cleanLegacyPcmFiles() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                if ((name.startsWith("session_spool_") || name.startsWith("batch_export_")) && name.endsWith(".pcm")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Clean legacy pcm files error", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> stop()
            ACTION_SKIP_NEXT -> skipNext()
            ACTION_SKIP_PREV -> skipPrevious()
            else -> {
                val text = intent?.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    val lang = intent.getStringExtra(EXTRA_LANG) ?: "ru"
                    val voice = intent.getStringExtra(EXTRA_VOICE) ?: "F1"
                    val showOverlay = intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, false)
                    playText(text, lang, voice, showOverlay = showOverlay)
                }
            }
        }
        return START_NOT_STICKY
    }

    fun playText(
        text: String,
        lang: String = "ru",
        voiceId: String = "F1",
        steps: Int = 3,
        speed: Float = 1.0f,
        startIndex: Int = 0,
        // Start at the chunk that contains this paragraph (-1 = ignore).
        startParagraphIndex: Int = -1,
        showOverlay: Boolean = false,
        paragraphs: List<ParagraphItem> = emptyList()
    ) {
        val currentGen = generationId.incrementAndGet()
        prepJob?.cancel()
        playbackJob?.cancel()
        scheduler.cancelAll()
        sink.stop()
        pcmRing.clear()

        currentPlaybackChunk = -1
        currentChunkWriteOffset = 0
        pausedChunkIndex = -1
        pausedChunkByteOffset = 0
        timelineAnchored = false

        activeLang = lang
        activeVoiceId = voiceId
        activeSteps = steps
        setPlaybackSpeed(speed)

        _playbackState.value = PlaybackState(
            status = PlaybackStatus.PREPARING,
            currentChunkIndex = startIndex,
            playbackSpeed = speed
        )

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка озвучивания...", false))

        prepJob = serviceScope.launch(Dispatchers.Default) {
            if (!EngineHost.isLoaded.value) {
                if (!modelManager.checkInstalledState()) {
                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = "Модель не загружена. Требуется загрузка весов."
                        )
                        releaseWakeLock()
                    }
                    return@launch
                }
                val threads = cpuAffinity.getActiveThreadCount()
                val loaded = EngineHost.load(modelManager.modelDir, numThreads = threads)
                if (!loaded) {
                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.ERROR,
                            errorMessage = "Ошибка инициализации движка ONNX"
                        )
                        releaseWakeLock()
                    }
                    return@launch
                }
            }

            val chunks = TextSegmenter.segment(text, defaultLang = lang, priorityRussian = true)
            if (chunks.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.IDLE)
                    releaseWakeLock()
                }
                return@launch
            }
            activeChunks = chunks

            val index = SourceIndex(chunks, paragraphs)
            sourceIndex = index

            val voice = EngineHost.getVoiceStyle(voiceId)
            if (voice == null) {
                withContext(Dispatchers.Main) {
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Голос '$voiceId' не загружен. Проверьте модель в настройках."
                    )
                    releaseWakeLock()
                }
                return@launch
            }

            scheduler.updateContext(chunks, voice, steps)

            withContext(Dispatchers.Main) {
                if (!requestAudioFocus()) {
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Не удалось получить аудиофокус"
                    )
                    releaseWakeLock()
                    return@withContext
                }

                if (showOverlay && overlayManager.canShowOverlay()) {
                    overlayManager.show()
                }

                // Paragraph tap: resolve the chunk only now, when segmentation and the index exist.
                val requestedChunk = if (startParagraphIndex >= 0) {
                    index.findChunkIndexByParagraph(startParagraphIndex)
                } else {
                    startIndex
                }
                val initialIndex = requestedChunk.coerceIn(0, chunks.lastIndex)
                val initialChunk = chunks[initialIndex]
                val span = index.getSpan(initialIndex)
                val startSample = index.sampleStartOf(initialIndex)

                clock.resetToSample(startSample)
                timelineAnchored = true
                pausedChunkIndex = -1
                pausedChunkByteOffset = 0
                currentChunkWriteOffset = 0

                _playbackState.value = PlaybackState(
                    status = PlaybackStatus.BUFFERING,
                    currentChunkIndex = initialIndex,
                    totalChunks = chunks.size,
                    currentParagraphIndex = span?.paragraphIndex ?: 0,
                    currentSentenceText = initialChunk.text,
                    currentSentenceStart = initialChunk.startOffset,
                    currentSentenceEnd = initialChunk.endOffset,
                    timelineDisplay = index.formatTimeline(startSample.toFloat() / 44100f),
                    totalEstimatedSeconds = index.getTotalEstimatedDurationSeconds(),
                    playbackSpeed = speed,
                    isChunkSynthesized = List(chunks.size) { false }
                )

                scheduler.scheduleAround(initialIndex)
                startPlaybackLoop(currentGen, initialIndex)
            }
        }
    }

    fun retrySynthesis() {
        val currentChunk = _playbackState.value.currentChunkIndex
        val voice = EngineHost.getVoiceStyle(activeVoiceId)
        if (voice == null) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "Голос '$activeVoiceId' не найден"
            )
            return
        }
        val currentGen = generationId.incrementAndGet()
        playbackJob?.cancel()

        val span = sourceIndex?.getSpan(currentChunk)
        if (span != null) {
            clock.resetToSample(span.sampleStart)
        } else {
            sink.flush()
        }
        currentChunkWriteOffset = 0
        pausedChunkIndex = -1
        pausedChunkByteOffset = 0

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.BUFFERING,
            errorMessage = null
        )
        acquireWakeLock()
        scheduler.scheduleAround(currentChunk, userSeekTarget = currentChunk)
        startPlaybackLoop(currentGen, currentChunk)
    }

    fun seekToParagraph(paragraphIndex: Int) {
        val targetChunk = sourceIndex?.findChunkIndexByParagraph(paragraphIndex) ?: 0
        seekToChunk(targetChunk)
    }

    fun seekToChunk(targetIndex: Int) {
        val total = activeChunks.size
        if (total == 0) return
        val clampedIndex = targetIndex.coerceIn(0, total - 1)

        val targetChunk = activeChunks[clampedIndex]
        val voice = EngineHost.getVoiceStyle(activeVoiceId)
        if (voice == null) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "Голос '$activeVoiceId' не найден"
            )
            return
        }

        val currentGen = generationId.incrementAndGet()
        playbackJob?.cancel()

        // resetToSample() flushes the sink itself: a second manual flush reset the counters twice.
        val span = sourceIndex?.getSpan(clampedIndex)
        if (span != null) {
            clock.resetToSample(span.sampleStart)
        } else {
            sink.flush()
        }
        timelineAnchored = true
        currentChunkWriteOffset = 0
        pausedChunkIndex = -1
        pausedChunkByteOffset = 0

        val isReady = pcmRing.isReady(clampedIndex)
        _playbackState.value = _playbackState.value.copy(
            status = if (isReady) PlaybackStatus.PLAYING else PlaybackStatus.BUFFERING,
            currentChunkIndex = clampedIndex,
            currentParagraphIndex = span?.paragraphIndex ?: 0,
            currentSentenceText = targetChunk.text,
            currentSentenceStart = targetChunk.startOffset,
            currentSentenceEnd = targetChunk.endOffset
        )
        acquireWakeLock()

        scheduler.scheduleAround(clampedIndex, userSeekTarget = clampedIndex)
        startPlaybackLoop(currentGen, clampedIndex)
    }

    private fun startPlaybackLoop(currentGen: Int, startIndex: Int, startByteOffset: Int = 0) {
        playbackJob?.cancel()
        playbackJob = serviceScope.launch(Dispatchers.IO) {
            try {
                sink.play()
                var currentIndex = startIndex
                var nextChunkByteOffset = startByteOffset.coerceAtLeast(0)

                while (currentIndex < activeChunks.size && generationId.get() == currentGen) {
                    // Wait for the scheduler; the PCM is copied into a reused buffer (no allocation
                    // per chunk, which used to churn the heap on every phrase).
                    var chunkSize = -1
                    while (generationId.get() == currentGen) {
                        if (_playbackState.value.status == PlaybackStatus.ERROR) return@launch
                        chunkSize = pcmRing.sizeOf(currentIndex) ?: -1
                        if (chunkSize > 0) break
                        withContext(Dispatchers.Main) {
                            if (_playbackState.value.status != PlaybackStatus.ERROR) {
                                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.BUFFERING)
                            }
                        }
                        delay(40)
                    }

                    if (chunkSize <= 0 || generationId.get() != currentGen) break

                    val chunkPcm = scratchBuffer(chunkSize)
                    if (pcmRing.copyChunkInto(currentIndex, chunkPcm, 0) != chunkSize) {
                        delay(20)
                        continue
                    }

                    val chunk = activeChunks[currentIndex]
                    val span = sourceIndex?.getSpan(currentIndex)

                    currentPlaybackChunk = currentIndex

                    withContext(Dispatchers.Main) {
                        pcmRing.advance(currentIndex, activeChunks)
                        scheduler.scheduleAround(currentIndex)

                        val curSec = clock.currentSeconds
                        val totalSec = sourceIndex?.getTotalEstimatedDurationSeconds() ?: 1f
                        val progress = (curSec / totalSec).coerceIn(0f, 1f)
                        val timeline = sourceIndex?.formatTimeline(curSec) ?: "00:00 / 00:00"

                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.PLAYING,
                            currentChunkIndex = chunk.index,
                            currentParagraphIndex = span?.paragraphIndex ?: 0,
                            currentSentenceText = chunk.text,
                            currentSentenceStart = chunk.startOffset,
                            currentSentenceEnd = chunk.endOffset,
                            timelineDisplay = timeline,
                            currentSeconds = curSec,
                            playbackProgress = progress,
                            bufferedSeconds = sink.getBufferedDurationSeconds(),
                            underruns = sink.getUnderruns()
                        )
                        updateNotificationThrottled(chunk.text, true)
                        updateMediaSession(isPlaying = true)
                    }

                    // Resume from an exact byte offset after pause; start from the chunk head otherwise.
                    var writeOffset = if (currentIndex == startIndex) {
                        nextChunkByteOffset.coerceIn(0, chunkSize)
                    } else {
                        0
                    }
                    nextChunkByteOffset = 0
                    currentChunkWriteOffset = writeOffset

                    // Write audio in <= 100 ms slices, publishing the live position every UI_TICK_MS.
                    var lastTick = SystemClock.elapsedRealtime()
                    while (writeOffset < chunkSize && generationId.get() == currentGen) {
                        val written = sink.writeSlice(chunkPcm, writeOffset, chunkSize - writeOffset)
                        if (written > 0) {
                            writeOffset += written
                            currentChunkWriteOffset = writeOffset
                        } else {
                            delay(20)
                        }

                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTick >= UI_TICK_MS) {
                            lastTick = now
                            publishPosition()
                        }
                    }

                    currentIndex++
                }

                // Buffer drain
                while (sink.getBufferedDurationSeconds() > 0.05f && generationId.get() == currentGen && sink.isPlaying) {
                    delay(40)
                }

                withContext(Dispatchers.Main) {
                    if (generationId.get() == currentGen && currentIndex >= activeChunks.size) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.COMPLETED,
                            bufferedSeconds = 0f,
                            playbackProgress = 1.0f
                        )
                        updateNotificationThrottled("Чтение завершено", false, force = true)
                        releaseWakeLock()
                        abandonAudioFocus()
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                Log.e(tag, "Playback loop error", e)
            }
        }
    }

    /** Grows (never shrinks) the reused PCM buffer to the requested chunk size. */
    private fun scratchBuffer(size: Int): ByteArray {
        if (pcmScratch.size < size) {
            pcmScratch = ByteArray(size)
        }
        return pcmScratch
    }

    /** Publishes the live position: timeline, progress, buffered audio and MediaSession. */
    private suspend fun publishPosition() {
        withContext(Dispatchers.Main) {
            val curSec = clock.currentSeconds
            val totalSec = sourceIndex?.getTotalEstimatedDurationSeconds() ?: 1f
            _playbackState.value = _playbackState.value.copy(
                currentSeconds = curSec,
                playbackProgress = if (totalSec > 0f) (curSec / totalSec).coerceIn(0f, 1f) else 0f,
                timelineDisplay = sourceIndex?.formatTimeline(curSec) ?: "00:00 / 00:00",
                bufferedSeconds = sink.getBufferedDurationSeconds(),
                underruns = sink.getUnderruns()
            )
            updateMediaSession(isPlaying = _playbackState.value.status == PlaybackStatus.PLAYING)
        }
    }

    fun startIndependentBatchExport(
        text: String,
        lang: String,
        voiceId: String,
        steps: Int,
        targetFile: File,
        format: String = "m4a",
        onProgress: (Float) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ) {
        batchExportJob?.cancel()
        batchExportJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val chunks = TextSegmenter.segment(text, defaultLang = lang, priorityRussian = true)
                if (chunks.isEmpty()) {
                    withContext(Dispatchers.Main) { onComplete(false, "Текст пуст") }
                    return@launch
                }

                if (!EngineHost.isLoaded.value) {
                    val threads = cpuAffinity.getActiveThreadCount()
                    EngineHost.load(modelManager.modelDir, numThreads = threads)
                }

                val voice = EngineHost.getVoiceStyle(voiceId)
                if (voice == null) {
                    withContext(Dispatchers.Main) { onComplete(false, "Голос '$voiceId' не найден") }
                    return@launch
                }

                // Streaming export: each synthesized chunk is encoded and written immediately, so the
                // whole book never sits in RAM (the old List<ByteArray> needed ≈1 GB for 3 hours and
                // had to refuse long exports). No temporary spool files are created either.
                val writer = if (format == "m4a") {
                    MediaCodecExporter.openM4aWriter(targetFile)
                } else {
                    MediaCodecExporter.openWavWriter(targetFile)
                }
                if (writer == null) {
                    withContext(Dispatchers.Main) { onComplete(false, "Не удалось открыть аудиофайл") }
                    return@launch
                }

                var chunksWritten = 0
                var success = false
                try {
                    for ((idx, chunk) in chunks.withIndex()) {
                        val synth = EngineHost.withEngine { eng: SpeechEngine ->
                            eng.synthesizeChunk(
                                rawText = chunk.text,
                                lang = chunk.resolvedLang,
                                voiceStyle = voice,
                                totalSteps = steps,
                                speed = 1.0f
                            )
                        }
                        if (synth == null) {
                            withContext(Dispatchers.Main) {
                                onComplete(false, "Ошибка синтеза чанка ${idx + 1}")
                            }
                            return@launch
                        }
                        if (!writer.write(synth.pcmBytes, synth.pcmBytes.size)) {
                            withContext(Dispatchers.Main) {
                                onComplete(false, "Ошибка записи аудио (чанк ${idx + 1})")
                            }
                            return@launch
                        }
                        chunksWritten++

                        val progress = ((idx + 1).toFloat() / chunks.size.toFloat()) * 0.95f
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }

                    success = writer.finish()
                } finally {
                    if (!success) {
                        writer.abort()
                    }
                }

                if (!success && chunksWritten > 0 && targetFile.exists()) {
                    targetFile.delete()
                }

                withContext(Dispatchers.Main) {
                    onProgress(1.0f)
                    onComplete(success, if (success) null else "Сбой кодирования аудиофайла")
                }
            } catch (e: CancellationException) {
                if (targetFile.exists()) targetFile.delete()
                withContext(Dispatchers.Main) {
                    onComplete(false, "Экспорт отменен")
                }
            } catch (e: Exception) {
                if (targetFile.exists()) targetFile.delete()
                Log.e(tag, "Batch export error", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage ?: "Сбой генерации")
                }
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        sink.playbackSpeed = speed
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    fun pause() {
        // Stop the producer first. Cancelling the coroutine alone is not enough: a blocking
        // AudioTrack.write() keeps running until it returns, and only the generation counter is
        // checked between writes.
        generationId.incrementAndGet()
        playbackJob?.cancel()
        sink.pause()

        // No flush() here: keeping the buffered frames means resume neither repeats the phrase
        // (the old behaviour re-wrote the chunk into a live buffer) nor drops its tail.
        pausedChunkIndex = if (currentPlaybackChunk >= 0) {
            currentPlaybackChunk
        } else {
            _playbackState.value.currentChunkIndex
        }
        pausedChunkByteOffset = currentChunkWriteOffset

        releaseWakeLock()

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.PAUSED,
            currentSeconds = clock.currentSeconds
        )
        updateNotificationThrottled("Пауза", false, force = true)
        updateMediaSession(isPlaying = false)
    }

    fun resume() {
        if (_playbackState.value.status == PlaybackStatus.PAUSED) {
            val currentGen = generationId.incrementAndGet()
            val startIdx = if (pausedChunkIndex >= 0) {
                pausedChunkIndex.coerceIn(0, (activeChunks.size - 1).coerceAtLeast(0))
            } else {
                _playbackState.value.currentChunkIndex
            }
            // The producer may have queued one more slice after the cancel request, so take the
            // furthest of the two offsets; otherwise up to 100 ms of audio would be duplicated.
            val startOffset = if (startIdx == pausedChunkIndex) {
                maxOf(pausedChunkByteOffset, currentChunkWriteOffset)
            } else {
                0
            }
            pausedChunkIndex = -1
            pausedChunkByteOffset = 0

            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.PLAYING,
                currentChunkIndex = startIdx,
                bufferedSeconds = 0f
            )
            acquireWakeLock()
            updateNotificationThrottled(_playbackState.value.currentSentenceText, true, force = true)
            startPlaybackLoop(currentGen, startIdx, startOffset)
        }
    }

    fun stop() {
        generationId.incrementAndGet()
        prepJob?.cancel()
        playbackJob?.cancel()
        scheduler.cancelAll()
        sink.stop()
        clock.reset()
        pcmRing.clear()
        currentPlaybackChunk = -1
        currentChunkWriteOffset = 0
        pausedChunkIndex = -1
        pausedChunkByteOffset = 0
        timelineAnchored = false
        releaseWakeLock()
        abandonAudioFocus()

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.IDLE,
            bufferedSeconds = 0f
        )
        overlayManager.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun skipNext() {
        seekToChunk(_playbackState.value.currentChunkIndex + 1)
    }

    fun skipPrevious() {
        seekToChunk(_playbackState.value.currentChunkIndex - 1)
    }

    fun jumpSeconds(deltaSec: Int) {
        // Prefer the live clock; fall back to the published state when nothing is anchored yet.
        val curSec = if (timelineAnchored) clock.currentSeconds else _playbackState.value.currentSeconds
        seekToSeconds(curSec + deltaSec)
    }

    /** Seeks to an absolute timeline position in seconds (used by the UI and MediaSession). */
    fun seekToSeconds(targetSeconds: Float) {
        if (activeChunks.isEmpty()) return
        val safeSeconds = targetSeconds.coerceAtLeast(0f)
        val targetSample = (safeSeconds * 44100f).toLong()
        val targetChunk = sourceIndex?.findChunkIndexBySample(targetSample) ?: 0
        seekToChunk(targetChunk)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "WakeLock acquire error", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "WakeLock release error", e)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        currentFocusRequest = request

        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        try {
            currentFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                currentFocusRequest = null
            }
        } catch (e: Exception) {
            Log.w(tag, "abandonAudioFocus error", e)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SupertonicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()

                // Lock screen / headset / Android Auto scrubbing: two-way seek support.
                override fun onSeekTo(pos: Long) = seekToSeconds(pos / 1000f)
                override fun onFastForward() = jumpSeconds(15)
                override fun onRewind() = jumpSeconds(-15)
            })
            isActive = true
        }
    }

    private fun updateMediaSession(isPlaying: Boolean) {
        val state = when {
            isPlaying -> PlaybackStateCompat.STATE_PLAYING
            _playbackState.value.status == PlaybackStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            _playbackState.value.status == PlaybackStatus.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            _playbackState.value.status == PlaybackStatus.COMPLETED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val positionMs = (clock.currentSeconds * 1000f).toLong().coerceAtLeast(0L)
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_FAST_FORWARD or
                            PlaybackStateCompat.ACTION_REWIND
                )
                .setState(state, positionMs, _playbackState.value.playbackSpeed)
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.playback_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, isPlaying: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Пауза",
                PendingIntent.getService(this, 1, Intent(this, SupertonicPlaybackService::class.java).apply { action = ACTION_PAUSE }, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Воспроизведение",
                PendingIntent.getService(this, 2, Intent(this, SupertonicPlaybackService::class.java).apply { action = ACTION_PLAY }, PendingIntent.FLAG_IMMUTABLE)
            )
        }

        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Стоп",
            PendingIntent.getService(this, 3, Intent(this, SupertonicPlaybackService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Supertonic Reader")
            .setContentText(text)
            .setSmallIcon(R.drawable.supertonic_launcher_fg_1792105555345)
            .setContentIntent(contentIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .setOngoing(isPlaying)
            .build()
    }

    private fun updateNotificationThrottled(text: String, isPlaying: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || (text != lastNotificationText && now - lastNotificationTime >= 1000L)) {
            lastNotificationText = text
            lastNotificationTime = now
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(text, isPlaying))
            updateMediaSession(isPlaying)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stop()
        abandonAudioFocus()
        unregisterReceiver(noisyReceiver)
        mediaSession?.release()
        sink.release()
        serviceScope.cancel()
    }
}
