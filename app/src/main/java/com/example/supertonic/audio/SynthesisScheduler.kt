package com.example.supertonic.audio

import android.util.Log
import com.example.supertonic.engine.EngineHost
import com.example.supertonic.engine.SpeechEngine
import com.example.supertonic.engine.VoiceStyle
import com.example.supertonic.language.TextChunk
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

enum class ChunkPriority(val level: Int) {
    P0_CURRENT_BLOCKING(0),
    P1_IMMEDIATE_LOOKAHEAD(1),
    P2_WINDOW_FORWARD(2),
    P3_USER_SEEK(3),
    P4_BACKGROUND_REMAINING(4)
}

/**
 * Priority scheduler for speech synthesis tasks (P0..P4).
 */
class SynthesisScheduler(
    private val pcmRing: PcmRing,
    private val scope: CoroutineScope
) {
    private val tag = "SynthesisScheduler"
    private val generationId = AtomicInteger(0)
    private var workerJob: Job? = null
    private val queueMutex = Mutex()

    private val scheduledIndices = mutableListOf<Pair<Int, ChunkPriority>>()

    @Volatile
    private var activeChunks: List<TextChunk> = emptyList()
    @Volatile
    private var activeVoice: VoiceStyle? = null
    @Volatile
    private var activeSteps: Int = 3

    var onChunkSynthesized: ((chunkIndex: Int, durationSec: Float, rtf: Float) -> Unit)? = null
    var onSynthesisError: ((chunkIndex: Int, message: String) -> Unit)? = null

    fun updateContext(
        chunks: List<TextChunk>,
        voice: VoiceStyle,
        steps: Int = 3
    ) {
        activeChunks = chunks
        activeVoice = voice
        activeSteps = steps
    }

    /**
     * Schedules synthesis with P0..P4 prioritization starting around currentIndex.
     */
    fun scheduleAround(currentIndex: Int, userSeekTarget: Int? = null) {
        val currentGen = generationId.incrementAndGet()
        workerJob?.cancel()

        val chunks = activeChunks
        if (chunks.isEmpty()) return

        val queue = mutableListOf<Pair<Int, ChunkPriority>>()

        // P0: Current chunk if not synthesized yet
        if (currentIndex in chunks.indices && !pcmRing.isReady(currentIndex)) {
            queue.add(currentIndex to ChunkPriority.P0_CURRENT_BLOCKING)
        }

        // P3: User seek target if requested
        if (userSeekTarget != null && userSeekTarget in chunks.indices && userSeekTarget != currentIndex && !pcmRing.isReady(userSeekTarget)) {
            queue.add(userSeekTarget to ChunkPriority.P3_USER_SEEK)
        }

        // P1: Next 3 chunks
        for (i in 1..3) {
            val idx = currentIndex + i
            if (idx in chunks.indices && !pcmRing.isReady(idx) && queue.none { it.first == idx }) {
                queue.add(idx to ChunkPriority.P1_IMMEDIATE_LOOKAHEAD)
            }
        }

        // P2: Window up to 10 minutes forward (around 40 chunks)
        val window = pcmRing.calculateWindowIndices(currentIndex, chunks)
        for (idx in window.sorted()) {
            if (idx > currentIndex + 3 && !pcmRing.isReady(idx) && queue.none { it.first == idx }) {
                queue.add(idx to ChunkPriority.P2_WINDOW_FORWARD)
            }
        }

        // P4: Background remaining
        for (idx in currentIndex until chunks.size) {
            if (!pcmRing.isReady(idx) && queue.none { it.first == idx }) {
                queue.add(idx to ChunkPriority.P4_BACKGROUND_REMAINING)
            }
        }

        workerJob = scope.launch(Dispatchers.Default) {
            runWorker(currentGen, queue)
        }
    }

    private suspend fun runWorker(
        expectedGen: Int,
        tasks: List<Pair<Int, ChunkPriority>>
    ) {
        val voice = activeVoice ?: return

        for ((chunkIdx, priority) in tasks) {
            if (generationId.get() != expectedGen) break
            if (pcmRing.isReady(chunkIdx)) continue

            val chunk = activeChunks.getOrNull(chunkIdx) ?: continue

            val result = try {
                EngineHost.withEngine { eng: SpeechEngine ->
                    eng.synthesizeChunk(
                        rawText = chunk.text,
                        lang = chunk.resolvedLang,
                        voiceStyle = voice,
                        totalSteps = activeSteps,
                        speed = 1.0f
                    )
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(tag, "Synthesis error for chunk $chunkIdx", e)
                null
            }

            if (generationId.get() != expectedGen) break

            if (result == null) {
                onSynthesisError?.invoke(chunkIdx, "Ошибка синтеза фразы №${chunkIdx + 1}")
                if (priority == ChunkPriority.P0_CURRENT_BLOCKING || priority == ChunkPriority.P1_IMMEDIATE_LOOKAHEAD) {
                    // Critical failure for currently playing / upcoming chunk
                    break
                }
                continue
            }

            pcmRing.putChunk(
                chunkIndex = chunkIdx,
                pcm = result.pcmBytes,
                durationSec = result.durationSeconds,
                sampleRate = result.sampleRate
            )

            onChunkSynthesized?.invoke(chunkIdx, result.durationSeconds, result.rtf)
        }
    }

    fun cancelAll() {
        generationId.incrementAndGet()
        workerJob?.cancel()
    }
}
