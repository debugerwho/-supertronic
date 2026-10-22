package com.example.supertonic.domain

import android.util.Log
import com.example.supertonic.audio.AudioTrackSink
import com.example.supertonic.audio.PcmRing
import com.example.supertonic.audio.PlaybackClock
import com.example.supertonic.audio.SynthesisScheduler
import com.example.supertonic.core.SourceIndex
import com.example.supertonic.data.ParagraphItem
import com.example.supertonic.engine.VoiceStyle
import com.example.supertonic.language.TextChunk
import com.example.supertonic.language.TextSegmenter
import com.example.supertonic.playback.PlaybackStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-level domain state of reading session
 */
data class ReadSessionState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val currentParagraphIndex: Int = 0,
    val currentSentenceText: String = "",
    val timelineDisplay: String = "00:00 / 00:00",
    val playbackProgress: Float = 0f,
    val currentSeconds: Float = 0f,
    val totalEstimatedSeconds: Float = 0f,
    val rtf: Float = 1.0f,
    val playbackSpeed: Float = 1.0f,
    val synthesizedChunksCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Domain orchestrator coordinating text segmentation, index mapping, off-heap audio,
 * priority synthesis scheduler, and AudioTrack streaming sink.
 */
class ReadSession(
    val pcmRing: PcmRing = PcmRing(),
    val sink: AudioTrackSink = AudioTrackSink(),
    private val scope: CoroutineScope
) {
    private val tag = "ReadSession"

    val clock = PlaybackClock(sink)
    val scheduler = SynthesisScheduler(pcmRing, scope)

    private val _sessionState = MutableStateFlow(ReadSessionState())
    val sessionState: StateFlow<ReadSessionState> = _sessionState.asStateFlow()

    private var sourceIndex: SourceIndex? = null
    private var activeChunks: List<TextChunk> = emptyList()
    private val currentGen = AtomicInteger(0)
    private var playbackLoopJob: Job? = null

    init {
        scheduler.onChunkSynthesized = { chunkIndex, durationSec, rtf ->
            sourceIndex?.updateSynthesizedChunk(chunkIndex, durationSec)
            val total = activeChunks.size
            val synthCount = activeChunks.count { pcmRing.isReady(it.index) }
            val curSec = clock.currentSeconds
            val timeline = sourceIndex?.formatTimeline(curSec) ?: "00:00 / 00:00"

            _sessionState.value = _sessionState.value.copy(
                rtf = rtf,
                synthesizedChunksCount = synthCount,
                timelineDisplay = timeline,
                totalEstimatedSeconds = sourceIndex?.getTotalEstimatedDurationSeconds() ?: 0f
            )
        }

        scheduler.onSynthesisError = { chunkIndex, message ->
            Log.e(tag, "Synthesis error for chunk $chunkIndex: $message")
            _sessionState.value = _sessionState.value.copy(
                status = PlaybackStatus.ERROR,
                errorMessage = message
            )
        }
    }

    fun prepareDocument(
        text: String,
        paragraphs: List<ParagraphItem>,
        defaultLang: String = "ru",
        voiceStyle: VoiceStyle,
        steps: Int = 3,
        startIndex: Int = 0
    ) {
        playbackLoopJob?.cancel()
        scheduler.cancelAll()
        sink.stop()
        pcmRing.clear()

        _sessionState.value = ReadSessionState(status = PlaybackStatus.PREPARING)

        val chunks = TextSegmenter.segment(text, defaultLang = defaultLang, priorityRussian = true)
        if (chunks.isEmpty()) {
            _sessionState.value = ReadSessionState(status = PlaybackStatus.IDLE)
            return
        }

        activeChunks = chunks
        val index = SourceIndex(chunks, paragraphs)
        sourceIndex = index

        scheduler.updateContext(chunks, voiceStyle, steps)

        val initialChunk = chunks.getOrElse(startIndex) { chunks.first() }
        val span = index.getSpan(initialChunk.index)

        _sessionState.value = ReadSessionState(
            status = PlaybackStatus.BUFFERING,
            currentChunkIndex = initialChunk.index,
            totalChunks = chunks.size,
            currentParagraphIndex = span?.paragraphIndex ?: 0,
            currentSentenceText = initialChunk.text,
            timelineDisplay = index.formatTimeline(0f),
            totalEstimatedSeconds = index.getTotalEstimatedDurationSeconds()
        )

        // Kick off scheduler
        scheduler.scheduleAround(initialChunk.index)
        startPlaybackStream(currentGen.incrementAndGet(), initialChunk.index)
    }

    fun play() {
        if (_sessionState.value.status == PlaybackStatus.PAUSED) {
            val gen = currentGen.incrementAndGet()
            sink.play()
            _sessionState.value = _sessionState.value.copy(status = PlaybackStatus.PLAYING)
            startPlaybackStream(gen, _sessionState.value.currentChunkIndex)
        }
    }

    fun pause() {
        playbackLoopJob?.cancel()
        sink.pause()
        _sessionState.value = _sessionState.value.copy(status = PlaybackStatus.PAUSED)
    }

    fun seekToParagraph(paragraphIndex: Int) {
        val chunkIdx = sourceIndex?.findChunkIndexByParagraph(paragraphIndex) ?: 0
        seekToChunk(chunkIdx)
    }

    fun seekToChunk(targetIndex: Int) {
        val total = activeChunks.size
        if (total == 0) return
        val clamped = targetIndex.coerceIn(0, total - 1)

        val gen = currentGen.incrementAndGet()
        playbackLoopJob?.cancel()
        sink.flush()

        val chunk = activeChunks[clamped]
        val span = sourceIndex?.getSpan(clamped)
        span?.let { clock.resetToSample(it.sampleStart) }

        _sessionState.value = _sessionState.value.copy(
            status = if (pcmRing.isReady(clamped)) PlaybackStatus.PLAYING else PlaybackStatus.BUFFERING,
            currentChunkIndex = clamped,
            currentParagraphIndex = span?.paragraphIndex ?: 0,
            currentSentenceText = chunk.text
        )

        scheduler.scheduleAround(clamped, userSeekTarget = clamped)
        startPlaybackStream(gen, clamped)
    }

    fun setSpeed(speed: Float) {
        sink.playbackSpeed = speed
        _sessionState.value = _sessionState.value.copy(playbackSpeed = speed)
    }

    private fun startPlaybackStream(gen: Int, startIndex: Int) {
        playbackLoopJob?.cancel()
        playbackLoopJob = scope.launch(Dispatchers.IO) {
            var currentIndex = startIndex
            sink.play()

            while (currentIndex < activeChunks.size && currentGen.get() == gen) {
                // Wait for chunk if not synthesized yet (buffering)
                var pcmBytes = pcmRing.getChunk(currentIndex)
                while (pcmBytes == null && currentGen.get() == gen) {
                    if (_sessionState.value.status == PlaybackStatus.ERROR) return@launch
                    withContext(Dispatchers.Main) {
                        if (_sessionState.value.status != PlaybackStatus.ERROR) {
                            _sessionState.value = _sessionState.value.copy(status = PlaybackStatus.BUFFERING)
                        }
                    }
                    delay(40)
                    pcmBytes = pcmRing.getChunk(currentIndex)
                }

                if (pcmBytes == null || currentGen.get() != gen) break

                val chunk = activeChunks[currentIndex]
                val span = sourceIndex?.getSpan(currentIndex)

                withContext(Dispatchers.Main) {
                    pcmRing.advance(currentIndex, activeChunks)
                    scheduler.scheduleAround(currentIndex)

                    val curSec = clock.currentSeconds
                    val totalSec = sourceIndex?.getTotalEstimatedDurationSeconds() ?: 1f
                    val progress = (curSec / totalSec).coerceIn(0f, 1f)

                    _sessionState.value = _sessionState.value.copy(
                        status = PlaybackStatus.PLAYING,
                        currentChunkIndex = chunk.index,
                        currentParagraphIndex = span?.paragraphIndex ?: 0,
                        currentSentenceText = chunk.text,
                        currentSeconds = curSec,
                        playbackProgress = progress,
                        timelineDisplay = sourceIndex?.formatTimeline(curSec) ?: "00:00 / 00:00"
                    )
                }

                // Write chunk in <= 100 ms slices
                var writeOffset = 0
                while (writeOffset < pcmBytes.size && currentGen.get() == gen) {
                    val remaining = pcmBytes.size - writeOffset
                    val written = sink.writeSlice(pcmBytes, writeOffset, remaining)
                    if (written > 0) {
                        writeOffset += written
                    } else {
                        delay(20)
                    }
                }

                currentIndex++
            }

            // Await buffer drain at completion
            while (sink.getBufferedDurationSeconds() > 0.05f && currentGen.get() == gen && sink.isPlaying) {
                delay(40)
            }

            if (currentGen.get() == gen && currentIndex >= activeChunks.size) {
                withContext(Dispatchers.Main) {
                    _sessionState.value = _sessionState.value.copy(
                        status = PlaybackStatus.COMPLETED,
                        playbackProgress = 1.0f
                    )
                }
            }
        }
    }

    fun release() {
        currentGen.incrementAndGet()
        playbackLoopJob?.cancel()
        scheduler.cancelAll()
        sink.release()
        pcmRing.clear()
        _sessionState.value = ReadSessionState(status = PlaybackStatus.IDLE)
    }
}
