package com.example.supertonic.playback

data class PcmChunk(
    val index: Int,
    val pcmBytes: ByteArray,
    val durationSec: Float,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val sampleRate: Int = 44100
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PcmChunk
        return index == other.index && startOffset == other.startOffset && endOffset == other.endOffset
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + startOffset
        result = 31 * result + endOffset
        return result
    }
}
