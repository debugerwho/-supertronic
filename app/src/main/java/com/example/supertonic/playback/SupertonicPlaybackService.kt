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
import com.example.supertonic.cache.PcmDiskCache
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
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class PlaybackStatus {
    IDLE,
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
    lateinit var cache: PcmDiskCache
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
    private var activeSpoolFile: File? = null
    private var activeSpoolStream: FileOutputStream? = null

    @Volatile
    private var activeVoiceId: String = "F1"
    @Volatile
    private var activeSteps: Int = 3
    @Volatile
    private var activeLang: String = "ru"

    // Audio Focus listener: pause audio output without aborting ongoing text synthesis
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pause() // Pause playback only; keeps background synthesis alive!
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}
            AudioManager.AUDIOFOCUS_GAIN -> resume()
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
        cache = PcmDiskCache(this)
        cpuAffinity = CpuAffinityManager(this)
        overlayManager = FloatingOverlayManager(this, this)

        createNotificationChannel()
        setupMediaSession()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
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

    fun setPlaybackSpeed(speed: Float) {
        audioTrackPlayer.playbackSpeed = speed
        _playbackState.value = _playbackState.value.copy(playbackSpeed = speed)
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
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioTrackPlayer.stop()

        activeLang = lang
        activeVoiceId = voiceId
        activeSteps = steps
        setPlaybackSpeed(speed)

        sessionChunkMap.clear()
        closeActiveSpool()
        activeSpoolFile = File(cacheDir, "session_spool_$currentGen.pcm")
        activeSpoolStream = FileOutputStream(activeSpoolFile)

        if (!engine.isLoaded) {
            if (!modelManager.checkInstalledState()) {
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "Модель не загружена. Требуется загрузка весов."
                )
                return
            }
            val threads = cpuAffinity.getActiveThreadCount()
            val loaded = engine.load(modelManager.modelDir, numThreads = threads)
            if (!loaded) {
                _playbackState.value = _playbackState.value.copy(
                    status = PlaybackStatus.ERROR,
                    errorMessage = "Ошибка инициализации движка ONNX"
                )
                return
            }
        }

        // Segmentation
        val chunks = TextSegmenter.segment(text, defaultLang = lang, priorityRussian = true)
        if (chunks.isEmpty()) {
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.IDLE)
            return
        }
        activeChunks = chunks

        val voice = engine.getVoiceStyle(voiceId) ?: engine.getVoiceStyles().firstOrNull()
        if (voice == null) {
            _playbackState.value = _playbackState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = "Голос не найден"
            )
            return
        }

        requestAudioFocus()
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка озвучивания...", false))

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

    /**
     * Non-linear seek: jumps to target chunk index.
     * If already synthesized, audio player seeks instantly.
     * If not yet synthesized, synthesis worker restarts prioritized from targetIndex.
     */
    fun seekToChunk(targetIndex: Int) {
        val total = activeChunks.size
        if (total == 0) return
        val clampedIndex = targetIndex.coerceIn(0, total - 1)

        val targetChunk = activeChunks[clampedIndex]
        val voice = engine.getVoiceStyle(activeVoiceId) ?: engine.getVoiceStyles().firstOrNull() ?: return

        val cached = sessionChunkMap[clampedIndex]
        if (cached != null) {
            // Instant seek from session memory/spool!
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

            // Continue playback from clampedIndex
            startPlaybackLoop(currentGen, clampedIndex)
        } else {
            // Forward seek: prioritize synthesis starting from clampedIndex onward!
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

            startSynthesisAndPlaybackPipes(currentGen, clampedIndex, voice)
        }
    }

    private fun startSynthesisAndPlaybackPipes(
        currentGen: Int,
        startIndex: Int,
        voice: com.example.supertonic.engine.VoiceStyle
    ) {
        // 1. Synthesis Worker Coroutine starting at startIndex
        synthesisJob = serviceScope.launch(Dispatchers.Default) {
            try {
                val chunksOrder = mutableListOf<TextChunk>()
                // First: startIndex until end
                for (i in startIndex until activeChunks.size) {
                    chunksOrder.add(activeChunks[i])
                }
                // Second: 0 until startIndex (so previous chunks are also available in memory)
                for (i in 0 until startIndex) {
                    chunksOrder.add(activeChunks[i])
                }

                for (chunk in chunksOrder) {
                    if (generationId.get() != currentGen) break
                    if (sessionChunkMap.containsKey(chunk.index)) continue

                    val cacheKey = cache.buildKey(chunk.text, chunk.resolvedLang, voice.id, activeSteps, 1.0f)
                    val cachedPcm = cache.get(cacheKey)

                    val pcmBytes: ByteArray
                    val durationSec: Float

                    if (cachedPcm != null) {
                        pcmBytes = cachedPcm
                        durationSec = (pcmBytes.size / 2) / 44100.0f
                    } else {
                        // Neural synthesis always at pristine 1.0f natural speed without clipping
                        val synth = engine.synthesizeChunk(
                            rawText = chunk.text,
                            lang = chunk.resolvedLang,
                            voiceStyle = voice,
                            totalSteps = activeSteps,
                            speed = 1.0f
                        )
                        if (synth != null) {
                            pcmBytes = synth.pcmBytes
                            durationSec = synth.durationSeconds
                            cache.put(cacheKey, pcmBytes)
                            withContext(Dispatchers.Main) {
                                _playbackState.value = _playbackState.value.copy(rtf = synth.rtf)
                            }
                        } else {
                            // Ensure chunk index is never omitted so playback loop never stalls
                            pcmBytes = ByteArray(44100 * 2 / 4) // 250ms silence
                            durationSec = 0.25f
                        }
                    }

                    val pcmChunk = PcmChunk(
                        index = chunk.index,
                        pcmBytes = pcmBytes,
                        durationSec = durationSec,
                        text = chunk.text,
                        startOffset = chunk.startOffset,
                        endOffset = chunk.endOffset,
                        sampleRate = 44100
                    )
                    sessionChunkMap[chunk.index] = pcmChunk

                    // Append to spool
                    try {
                        activeSpoolStream?.write(pcmBytes)
                        activeSpoolStream?.flush()
                    } catch (e: Exception) {
                        Log.w(tag, "Spool write error", e)
                    }

                    // Update synthesized chunks list
                    withContext(Dispatchers.Main) {
                        val synthList = List(activeChunks.size) { idx -> sessionChunkMap.containsKey(idx) }
                        _playbackState.value = _playbackState.value.copy(
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
                        errorMessage = "Сбой синтеза: ${e.localizedMessage}"
                    )
                }
            }
        }

        // 2. Playback Loop starting at startIndex
        startPlaybackLoop(currentGen, startIndex)
    }

    private fun startPlaybackLoop(currentGen: Int, startIndex: Int) {
        playbackJob = serviceScope.launch(Dispatchers.IO) {
            try {
                audioTrackPlayer.play()
                var currentIndex = startIndex

                while (currentIndex < activeChunks.size) {
                    if (generationId.get() != currentGen) break

                    // Wait for current chunk to be synthesized if still pending
                    var chunk = sessionChunkMap[currentIndex]
                    while (chunk == null && generationId.get() == currentGen) {
                        withContext(Dispatchers.Main) {
                            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.BUFFERING)
                        }
                        delay(50)
                        chunk = sessionChunkMap[currentIndex]
                    }

                    if (chunk == null || generationId.get() != currentGen) break

                    // Throttle writes if buffer is already well-stocked (> 1.2s),
                    // ensuring UI highlight closely tracks the actual acoustic output
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
                        updateMediaSession(true, chunk.text)
                        updateNotification(chunk.text, true)
                    }

                    audioTrackPlayer.write(chunk.pcmBytes)
                    currentIndex++
                }

                // Wait for all remaining buffered samples to drain through the device speakers
                while (audioTrackPlayer.getBufferedDurationSeconds() > 0.05f && generationId.get() == currentGen && audioTrackPlayer.isPlaying) {
                    delay(50)
                }

                // Completed
                withContext(Dispatchers.Main) {
                    if (generationId.get() == currentGen) {
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.COMPLETED,
                            bufferedSeconds = 0f
                        )
                        updateMediaSession(false, "Чтение завершено")
                        updateNotification("Чтение завершено", false)
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

    /**
     * Decoupled Batch Audio Generation: runs in background at maximum CPU speed
     * without playing sound or interfering with real-time playback!
     */
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

                val voice = engine.getVoiceStyle(voiceId) ?: engine.getVoiceStyles().firstOrNull()
                if (voice == null) {
                    withContext(Dispatchers.Main) { onComplete(false, "Голос не найден") }
                    return@launch
                }

                val tempPcmFile = File(cacheDir, "batch_export_${System.currentTimeMillis()}.pcm")
                val fos = FileOutputStream(tempPcmFile)

                for ((idx, chunk) in chunks.withIndex()) {
                    val cacheKey = cache.buildKey(chunk.text, chunk.resolvedLang, voice.id, steps, 1.0f)
                    var pcmBytes = cache.get(cacheKey)

                    if (pcmBytes == null) {
                        val synth = engine.synthesizeChunk(
                            rawText = chunk.text,
                            lang = chunk.resolvedLang,
                            voiceStyle = voice,
                            totalSteps = steps,
                            speed = 1.0f
                        )
                        if (synth != null) {
                            pcmBytes = synth.pcmBytes
                            cache.put(cacheKey, pcmBytes)
                        }
                    }

                    if (pcmBytes != null) {
                        fos.write(pcmBytes)
                    }

                    val progress = ((idx + 1).toFloat() / chunks.size.toFloat()) * 0.85f
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                fos.close()

                // Encode to destination format
                val success = if (format == "m4a") {
                    MediaCodecExporter.encodePcmToM4a(tempPcmFile, targetFile) { p ->
                        serviceScope.launch(Dispatchers.Main) {
                            onProgress(0.85f + (p * 0.15f))
                        }
                    }
                } else {
                    MediaCodecExporter.convertPcmToWav(tempPcmFile, targetFile)
                }

                tempPcmFile.delete()
                withContext(Dispatchers.Main) {
                    onProgress(1.0f)
                    onComplete(success, if (success) null else "Сбой кодирования аудиофайла")
                }
            } catch (e: Exception) {
                Log.e(tag, "Batch export error", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage)
                }
            }
        }
    }

    fun pause() {
        audioTrackPlayer.pause()
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        updateMediaSession(false, _playbackState.value.currentSentenceText)
        updateNotification(_playbackState.value.currentSentenceText, false)
        releaseWakeLock()
    }

    fun resume() {
        audioTrackPlayer.resume()
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
        updateMediaSession(true, _playbackState.value.currentSentenceText)
        updateNotification(_playbackState.value.currentSentenceText, true)
        acquireWakeLock()
    }

    fun stop() {
        generationId.incrementAndGet()
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioTrackPlayer.stop()
        closeActiveSpool()
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
        // Approximate jump: each chunk is ~3-4 seconds of speech
        val deltaChunks = (deltaSec / 3).coerceAtLeast(1)
        val target = if (deltaSec > 0) _playbackState.value.currentChunkIndex + deltaChunks else _playbackState.value.currentChunkIndex - deltaChunks
        seekToChunk(target)
    }

    fun getActiveSpoolFile(): File? = activeSpoolFile

    private fun closeActiveSpool() {
        try {
            activeSpoolStream?.close()
        } catch (ignored: Exception) {}
        activeSpoolStream = null
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock")
        }
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (ignored: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
    }

    private fun requestAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        audioManager.requestAudioFocus(request)
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

    private fun updateNotification(text: String, isPlaying: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text, isPlaying))
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
