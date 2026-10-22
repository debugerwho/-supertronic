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
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.supertonic.engine.CpuAffinityManager
import com.example.supertonic.engine.ModelManager
import com.example.supertonic.engine.SupertonicEngine
import com.example.supertonic.language.TextChunk
import com.example.supertonic.language.TextSegmenter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    val currentSentenceText: String = "",
    val currentSentenceStart: Int = 0,
    val currentSentenceEnd: Int = 0,
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
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): SupertonicPlaybackService = this@SupertonicPlaybackService
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var prepJob: Job? = null
    private var synthesisJob: Job? = null
    private var playbackJob: Job? = null
    private var batchExportJob: Job? = null

    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    val engine = SupertonicEngine()
    lateinit var modelManager: ModelManager
        private set
    lateinit var cpuAffinity: CpuAffinityManager
        private set
    private var audioTrackPlayer = AudioTrackPlayer()
    lateinit var overlayManager: FloatingOverlayManager
        private set

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val generationId = AtomicInteger(0)
    private var activeChunks: List<TextChunk> = emptyList()
    private val sessionChunkMap = ConcurrentHashMap<Int, PcmChunk>()

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

        // One-time startup cleanup of leftover old pcm files in cacheDir
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
        showOverlay: Boolean = false
    ) {
        val currentGen = generationId.incrementAndGet()
        prepJob?.cancel()
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioTrackPlayer.stop()

        activeLang = lang
        activeVoiceId = voiceId
        activeSteps = steps
        setPlaybackSpeed(speed)
        sessionChunkMap.clear()

        _playbackState.value = PlaybackState(
            status = PlaybackStatus.PREPARING,
            currentChunkIndex = startIndex,
            playbackSpeed = speed
        )

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка озвучивания...", false))

        prepJob = serviceScope.launch(Dispatchers.Default) {
            if (!engine.isLoaded) {
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
                val loaded = engine.load(modelManager.modelDir, numThreads = threads)
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

            // Segmentation in Dispatchers.Default
            val chunks = TextSegmenter.segment(text, defaultLang = lang, priorityRussian = true)
            if (chunks.isEmpty()) {
                withContext(Dispatchers.Main) {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.IDLE)
                    releaseWakeLock()
                }
                return@launch
            }
            activeChunks = chunks

            // Strict voice checking - no silent fallback
            val voice = engine.getVoiceStyle(voiceId)
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

                val initialChunk = chunks.getOrElse(startIndex) { chunks.first() }
                _playbackState.value = PlaybackState(
                    status = PlaybackStatus.BUFFERING,
                    currentChunkIndex = initialChunk.index,
                    totalChunks = chunks.size,
                    currentSentenceText = initialChunk.text,
                    currentSentenceStart = initialChunk.startOffset,
                    currentSentenceEnd = initialChunk.endOffset,
                    playbackSpeed = speed,
                    isChunkSynthesized = List(chunks.size) { false }
                )

                startSynthesisAndPlaybackPipes(currentGen, startIndex, voice)
            }
        }
    }

    fun retrySynthesis() {
        val currentChunk = _playbackState.value.currentChunkIndex
        val voice = engine.getVoiceStyle(activeVoiceId)
        if (voice == null) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "Голос '$activeVoiceId' не найден"
            )
            return
        }
        val currentGen = generationId.incrementAndGet()
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioTrackPlayer.flush()

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.BUFFERING,
            errorMessage = null
        )
        acquireWakeLock()
        startSynthesisAndPlaybackPipes(currentGen, currentChunk, voice)
    }

    fun seekToChunk(targetIndex: Int) {
        val total = activeChunks.size
        if (total == 0) return
        val clampedIndex = targetIndex.coerceIn(0, total - 1)

        val targetChunk = activeChunks[clampedIndex]
        val voice = engine.getVoiceStyle(activeVoiceId)
        if (voice == null) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "Голос '$activeVoiceId' не найден"
            )
            return
        }

        val cached = sessionChunkMap[clampedIndex]
        if (cached != null) {
            val currentGen = generationId.incrementAndGet()
            playbackJob?.cancel()
            audioTrackPlayer.flush()

            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.PLAYING,
                currentChunkIndex = clampedIndex,
                currentSentenceText = targetChunk.text,
                currentSentenceStart = targetChunk.startOffset,
                currentSentenceEnd = targetChunk.endOffset
            )
            acquireWakeLock()
            startPlaybackLoop(currentGen, clampedIndex)
        } else {
            val currentGen = generationId.incrementAndGet()
            synthesisJob?.cancel()
            playbackJob?.cancel()
            audioTrackPlayer.flush()

            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.BUFFERING,
                currentChunkIndex = clampedIndex,
                currentSentenceText = targetChunk.text,
                currentSentenceStart = targetChunk.startOffset,
                currentSentenceEnd = targetChunk.endOffset
            )
            acquireWakeLock()
            startSynthesisAndPlaybackPipes(currentGen, clampedIndex, voice)
        }
    }

    private fun startSynthesisAndPlaybackPipes(
        currentGen: Int,
        startIndex: Int,
        voice: com.example.supertonic.engine.VoiceStyle
    ) {
        synthesisJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val chunksOrder = mutableListOf<TextChunk>()
                for (i in startIndex until activeChunks.size) {
                    chunksOrder.add(activeChunks[i])
                }
                for (i in 0 until startIndex) {
                    chunksOrder.add(activeChunks[i])
                }

                for (chunk in chunksOrder) {
                    if (generationId.get() != currentGen) break
                    if (sessionChunkMap.containsKey(chunk.index)) continue

                    val synthResult = try {
                        engine.synthesizeChunk(
                            rawText = chunk.text,
                            lang = chunk.resolvedLang,
                            voiceStyle = voice,
                            totalSteps = activeSteps,
                            speed = 1.0f
                        )
                    } catch (e: Exception) {
                        Log.e(tag, "synthesizeChunk failed on phrase ${chunk.index + 1}", e)
                        null
                    }

                    if (synthResult == null) {
                        withContext(Dispatchers.Main) {
                            _playbackState.value = _playbackState.value.copy(
                                status = PlaybackStatus.ERROR,
                                errorMessage = "Ошибка синтеза фразы №${chunk.index + 1}"
                            )
                        }
                        return@launch
                    }

                    val pcmChunk = PcmChunk(
                        index = chunk.index,
                        pcmBytes = synthResult.pcmBytes,
                        durationSec = synthResult.durationSeconds,
                        text = chunk.text,
                        startOffset = chunk.startOffset,
                        endOffset = chunk.endOffset,
                        sampleRate = 44100
                    )
                    sessionChunkMap[chunk.index] = pcmChunk

                    withContext(Dispatchers.Main) {
                        val synthList = List(activeChunks.size) { idx -> sessionChunkMap.containsKey(idx) }
                        _playbackState.value = _playbackState.value.copy(
                            rtf = synthResult.rtf,
                            synthesizedChunksCount = sessionChunkMap.size,
                            isChunkSynthesized = synthList
                        )
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                Log.e(tag, "Synthesis loop error", e)
                withContext(Dispatchers.Main) {
                    _playbackState.value = _playbackState.value.copy(
                        status = PlaybackStatus.ERROR,
                        errorMessage = "Сбой синтеза: ${e.localizedMessage ?: "Неизвестная ошибка"}"
                    )
                }
            }
        }

        startPlaybackLoop(currentGen, startIndex)
    }

    private fun startPlaybackLoop(currentGen: Int, startIndex: Int) {
        playbackJob = serviceScope.launch(Dispatchers.IO) {
            try {
                audioTrackPlayer.play()
                var currentIndex = startIndex

                while (currentIndex < activeChunks.size) {
                    if (generationId.get() != currentGen) break

                    var chunk = sessionChunkMap[currentIndex]
                    while (chunk == null && generationId.get() == currentGen) {
                        if (_playbackState.value.status == PlaybackStatus.ERROR) {
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            if (_playbackState.value.status != PlaybackStatus.ERROR) {
                                _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.BUFFERING)
                            }
                        }
                        delay(50)
                        chunk = sessionChunkMap[currentIndex]
                    }

                    if (chunk == null || generationId.get() != currentGen) break

                    while (audioTrackPlayer.getBufferedDurationSeconds() > 1.2f && generationId.get() == currentGen) {
                        delay(60)
                    }

                    if (generationId.get() != currentGen) break

                    withContext(Dispatchers.Main) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.PLAYING,
                            currentChunkIndex = chunk.index,
                            currentSentenceText = chunk.text,
                            currentSentenceStart = chunk.startOffset,
                            currentSentenceEnd = chunk.endOffset,
                            bufferedSeconds = audioTrackPlayer.getBufferedDurationSeconds(),
                            underruns = audioTrackPlayer.getUnderruns()
                        )
                        updateNotificationThrottled(chunk.text, true)
                    }

                    audioTrackPlayer.write(chunk.pcmBytes)
                    currentIndex++
                }

                while (audioTrackPlayer.getBufferedDurationSeconds() > 0.05f && generationId.get() == currentGen && audioTrackPlayer.isPlaying) {
                    delay(50)
                }

                withContext(Dispatchers.Main) {
                    if (generationId.get() == currentGen) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.COMPLETED,
                            bufferedSeconds = 0f
                        )
                        updateNotificationThrottled("Чтение завершено", false, force = true)
                        releaseWakeLock()
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                Log.e(tag, "Playback loop error", e)
            }
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

                if (!engine.isLoaded) {
                    val threads = cpuAffinity.getActiveThreadCount()
                    engine.load(modelManager.modelDir, numThreads = threads)
                }

                val voice = engine.getVoiceStyle(voiceId)
                if (voice == null) {
                    withContext(Dispatchers.Main) { onComplete(false, "Голос '$voiceId' не найден") }
                    return@launch
                }

                val pcmChunks = mutableListOf<ByteArray>()

                for ((idx, chunk) in chunks.withIndex()) {
                    val synth = engine.synthesizeChunk(
                        rawText = chunk.text,
                        lang = chunk.resolvedLang,
                        voiceStyle = voice,
                        totalSteps = steps,
                        speed = 1.0f
                    )
                    if (synth == null) {
                        withContext(Dispatchers.Main) {
                            onComplete(false, "Ошибка синтеза чанка ${idx + 1}")
                        }
                        return@launch
                    }
                    pcmChunks.add(synth.pcmBytes)

                    val progress = ((idx + 1).toFloat() / chunks.size.toFloat()) * 0.85f
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }

                // Encode directly to destination file without temporary files on disk
                val success = if (format == "m4a") {
                    MediaCodecExporter.encodePcmChunksToM4a(pcmChunks, targetFile) { p ->
                        serviceScope.launch(Dispatchers.Main) {
                            onProgress(0.85f + (p * 0.15f))
                        }
                    }
                } else {
                    MediaCodecExporter.convertPcmChunksToWav(pcmChunks, targetFile)
                }

                withContext(Dispatchers.Main) {
                    onProgress(1.0f)
                    onComplete(success, if (success) null else "Сбой кодирования аудиофайла")
                }
            } catch (e: Exception) {
                Log.e(tag, "Batch export error", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage ?: "Сбой генерации")
                }
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        audioTrackPlayer.playbackSpeed = speed
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
    }

    fun pause() {
        playbackJob?.cancel()
        audioTrackPlayer.pause()
        releaseWakeLock()

        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        updateNotificationThrottled("Пауза", false, force = true)
    }

    fun resume() {
        if (_playbackState.value.status == PlaybackStatus.PAUSED) {
            val currentGen = generationId.incrementAndGet()
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
            acquireWakeLock()
            updateNotificationThrottled(_playbackState.value.currentSentenceText, true, force = true)
            startPlaybackLoop(currentGen, _playbackState.value.currentChunkIndex)
        }
    }

    fun stop() {
        prepJob?.cancel()
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioTrackPlayer.stop()
        releaseWakeLock()

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
        val deltaChunks = (deltaSec / 3).coerceAtLeast(1)
        val target = if (deltaSec > 0) _playbackState.value.currentChunkIndex + deltaChunks else _playbackState.value.currentChunkIndex - deltaChunks
        seekToChunk(target)
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

        val result = audioManager.requestAudioFocus(request)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "SupertonicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stop()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
            })
            isActive = true
        }
    }

    private fun updateMediaSession(isPlaying: Boolean, title: String) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_STOP or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
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
            updateMediaSession(isPlaying, text)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stop()
        unregisterReceiver(noisyReceiver)
        mediaSession?.release()
        audioTrackPlayer.release()
        engine.close()
        serviceScope.cancel()
    }
}
