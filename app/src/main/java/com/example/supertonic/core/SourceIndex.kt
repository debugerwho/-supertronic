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
        val spanList = mutableListOf<IndexedChunkSpan>()
        for (chunk in chunks) {
            // Find paragraph containing chunk start offset
            val pIdx = paragraphs.indexOfFirst { p ->
                chunk.startOffset >= p.charStart && chunk.startOffset <= p.charEnd
            }.coerceAtLeast(0)

            // Initial estimate for sample length based on character length
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
        recalculateSampleOffsets(spanList)
        spans = spanList
    }

    private fun recalculateSampleOffsets(list: List<IndexedChunkSpan>) {
        var currentSample = 0L
        for (span in list) {
            span.sampleStart = currentSample
            currentSample += span.sampleLength
        }
    }

    /**
     * Updates span with exact synthesized duration/samples.
     */
    fun updateSynthesizedChunk(chunkIndex: Int, durationSec: Float) {
        val span = spans.getOrNull(chunkIndex) ?: return
        span.sampleLength = (durationSec * sampleRate).toLong()
        span.isSynthesized = true
        recalculateSampleOffsets(spans)
    }

    fun getSpan(chunkIndex: Int): IndexedChunkSpan? = spans.getOrNull(chunkIndex)

    fun findChunkIndexByParagraph(paragraphIndex: Int): Int {
        val span = spans.find { it.paragraphIndex == paragraphIndex }
        return span?.chunkIndex ?: 0
    }

    fun findChunkIndexBySample(sampleOffset: Long): Int {
        if (spans.isEmpty()) return 0
        if (sampleOffset <= 0) return 0
        for (span in spans) {
            if (sampleOffset >= span.sampleStart && sampleOffset < span.sampleStart + span.sampleLength) {
                return span.chunkIndex
            }
        }
        return spans.last().chunkIndex
    }

    fun getTotalEstimatedDurationSeconds(): Float {
        val totalSamples = spans.sumOf { it.sampleLength }
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
