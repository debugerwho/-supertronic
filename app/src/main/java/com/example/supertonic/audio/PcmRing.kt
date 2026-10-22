package com.example.supertonic.audio

import com.example.supertonic.language.TextChunk
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Sliding window manager for playback audio:
 * Keeps 45 seconds backwards and up to 10 minutes forward in RAM via PcmStore.
 */
class PcmRing(
    val pcmStore: PcmStore = PcmStore()
) {
    companion object {
        const val WINDOW_BACK_SEC = 45.0f
        const val WINDOW_FORWARD_SEC = 600.0f // 10 minutes
        const val ESTIMATED_CHARS_PER_SEC = 13.0f
    }

    private val readyIndices = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun isReady(chunkIndex: Int): Boolean = pcmStore.has(chunkIndex)

    fun getChunk(chunkIndex: Int): ByteArray? = pcmStore.get(chunkIndex)

    fun putChunk(chunkIndex: Int, pcm: ByteArray, durationSec: Float, sampleRate: Int = 44100) {
        pcmStore.put(chunkIndex, pcm, durationSec, sampleRate)
        readyIndices.add(chunkIndex)
    }

    /**
     * Calculates the window of chunk indices to retain around the current index:
     * 45 seconds behind, up to 600 seconds ahead.
     */
    fun calculateWindowIndices(currentIndex: Int, chunks: List<TextChunk>): Set<Int> {
        if (chunks.isEmpty()) return emptySet()

        val retained = mutableSetOf<Int>()
        retained.add(currentIndex)

        // 1. Look backwards up to 45 seconds
        var backDuration = 0f
        var bIdx = currentIndex - 1
        while (bIdx >= 0 && backDuration < WINDOW_BACK_SEC) {
            retained.add(bIdx)
            val chunkLen = chunks[bIdx].text.length
            backDuration += (chunkLen / ESTIMATED_CHARS_PER_SEC).coerceAtLeast(0.5f)
            bIdx--
        }

        // 2. Look forward up to 10 minutes (600 seconds)
        var forwardDuration = 0f
        var fIdx = currentIndex + 1
        while (fIdx < chunks.size && forwardDuration < WINDOW_FORWARD_SEC) {
            retained.add(fIdx)
            val chunkLen = chunks[fIdx].text.length
            forwardDuration += (chunkLen / ESTIMATED_CHARS_PER_SEC).coerceAtLeast(0.5f)
            fIdx++
        }

        return retained
    }

    /**
     * Advances playback position and trims out-of-window chunks from memory.
     */
    fun advance(currentIndex: Int, chunks: List<TextChunk>) {
        val window = calculateWindowIndices(currentIndex, chunks)
        readyIndices.retainAll(window)
        pcmStore.evictExcept(window)
    }

    fun clear() {
        readyIndices.clear()
        pcmStore.clear()
    }
}
