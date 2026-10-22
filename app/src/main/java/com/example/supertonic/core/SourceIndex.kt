package com.example.supertonic.core

import com.example.supertonic.data.ParagraphItem
import com.example.supertonic.language.TextChunk
import java.util.Locale
import kotlin.math.max

/**
 * Mapping item connecting text character offsets, paragraphs, and audio sample offsets.
 */
data class IndexedChunkSpan(
    val chunkIndex: Int,
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val paragraphIndex: Int,
    var sampleStart: Long = 0L,
    var sampleLength: Long = 0L,
    var isSynthesized: Boolean = false
)

/**
 * Sample-accurate index connecting document paragraphs, text chunks, and audio timeline.
 *
 * Lookups are O(log n) and both index construction and duration updates are linear in the number
 * of chunks (no per-chunk paragraph scans, no full re-layout for every synthesized chunk).
 */
class SourceIndex(
    val chunks: List<TextChunk>,
    val paragraphs: List<ParagraphItem>,
    val sampleRate: Int = 44100
) {
    companion object {
        const val ESTIMATED_CHARS_PER_SEC = 13.0f
    }

    private val spans: List<IndexedChunkSpan>

    init {
        val spanList = ArrayList<IndexedChunkSpan>(chunks.size)

        // Paragraphs and chunks are both ordered by character offset, so a single moving cursor is
        // enough: the previous implementation scanned every paragraph for every chunk (O(n·m)).
        var paragraphCursor = 0

        for (chunk in chunks) {
            if (paragraphs.isNotEmpty()) {
                while (paragraphCursor + 1 < paragraphs.size &&
                    chunk.startOffset > paragraphs[paragraphCursor].charEnd
                ) {
                    paragraphCursor++
                }
            }

            val pIdx = if (paragraphs.isEmpty()) {
                0
            } else {
                val candidate = paragraphs[paragraphCursor]
                if (chunk.startOffset in candidate.charStart..candidate.charEnd) {
                    paragraphCursor
                } else {
                    // Offset falls into a gap between paragraphs: keep the one that starts after it,
                    // as long as it is not the last one; otherwise stay on the current paragraph.
                    max(0, paragraphCursor)
                }
            }

            val estDurationSec = max(0.4f, chunk.text.length / ESTIMATED_CHARS_PER_SEC)
            val estSamples = (estDurationSec * sampleRate).toLong()

            spanList.add(
                IndexedChunkSpan(
                    chunkIndex = chunk.index,
                    text = chunk.text,
                    startCharOffset = chunk.startOffset,
                    endCharOffset = chunk.endOffset,
                    paragraphIndex = pIdx,
                    sampleLength = estSamples,
                    isSynthesized = false
                )
            )
        }

        recalculateOffsets(spanList, 0)
        spans = spanList
    }

    /**
     * Recomputes sample starts from [fromIndex] to the end. Only spans at or after the changed chunk
     * can move, so a duration update costs O(remaining chunks) instead of a full re-layout.
     */
    private fun recalculateOffsets(list: List<IndexedChunkSpan>, fromIndex: Int) {
        if (list.isEmpty()) return
        val start = fromIndex.coerceIn(0, list.size - 1)
        var currentSample = if (start == 0) {
            0L
        } else {
            list[start - 1].sampleStart + list[start - 1].sampleLength
        }
        for (i in start until list.size) {
            list[i].sampleStart = currentSample
            currentSample += list[i].sampleLength
        }
    }

    /**
     * Updates span with exact synthesized duration/samples.
     */
    fun updateSynthesizedChunk(chunkIndex: Int, durationSec: Float) {
        val span = spans.getOrNull(chunkIndex) ?: return
        span.sampleLength = (durationSec * sampleRate).toLong()
        span.isSynthesized = true
        recalculateOffsets(spans, chunkIndex)
    }

    fun getSpan(chunkIndex: Int): IndexedChunkSpan? = spans.getOrNull(chunkIndex)

    fun sampleStartOf(chunkIndex: Int): Long = spans.getOrNull(chunkIndex)?.sampleStart ?: 0L

    /** Duration of the chunk in seconds (exact after synthesis, estimate before). */
    fun durationSecondsOf(chunkIndex: Int): Float {
        val span = spans.getOrNull(chunkIndex) ?: return 0f
        return span.sampleLength.toFloat() / sampleRate.toFloat()
    }

    fun findChunkIndexByParagraph(paragraphIndex: Int): Int {
        // Paragraphs are ordered: binary search on the first span of the paragraph.
        var low = 0
        var high = spans.size - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            val p = spans[mid].paragraphIndex
            when {
                p < paragraphIndex -> low = mid + 1
                p > paragraphIndex -> high = mid - 1
                else -> {
                    result = mid
                    high = mid - 1
                }
            }
        }
        return if (result in spans.indices) spans[result].chunkIndex else 0
    }

    fun findChunkIndexBySample(sampleOffset: Long): Int {
        if (spans.isEmpty()) return 0
        if (sampleOffset <= 0) return 0

        var low = 0
        var high = spans.size - 1
        var candidate = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (spans[mid].sampleStart <= sampleOffset) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return spans[candidate].chunkIndex
    }

    fun getTotalEstimatedDurationSeconds(): Float {
        if (spans.isEmpty()) return 0f
        val last = spans.last()
        val totalSamples = last.sampleStart + last.sampleLength
        return totalSamples.toFloat() / sampleRate.toFloat()
    }

    fun isFullySynthesized(): Boolean = spans.isNotEmpty() && spans.all { it.isSynthesized }

    /**
     * Formats current position and total duration:
     * e.g. "01:23 / ≈05:40" (if partially synthesized) or "01:23 / 05:40" (if fully synthesized).
     */
    fun formatTimeline(currentSeconds: Float): String {
        val curSec = max(0, currentSeconds.toInt())
        val curMin = curSec / 60
        val curRemSec = curSec % 60
        val curStr = String.format(Locale.US, "%02d:%02d", curMin, curRemSec)

        val totalSec = max(0, getTotalEstimatedDurationSeconds().toInt())
        val totMin = totalSec / 60
        val totRemSec = totalSec % 60
        val prefix = if (isFullySynthesized()) "" else "≈"
        val totStr = String.format(Locale.US, "%s%02d:%02d", prefix, totMin, totRemSec)

        return "$curStr / $totStr"
    }
}
