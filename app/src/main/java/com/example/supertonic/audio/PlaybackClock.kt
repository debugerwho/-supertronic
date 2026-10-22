package com.example.supertonic.audio

import kotlin.math.max

/**
 * Clock tracking exact sample-based playback position, underruns, and timing.
 *
 * The absolute position is `baseSampleOffset + frames played since the last sink flush`.
 * [AudioTrackSink.flush] resets its own frame counters, so a seek only has to move the base.
 */
class PlaybackClock(
    val sink: AudioTrackSink,
    val sampleRate: Int = 44100
) {
    private var baseSampleOffset: Long = 0L

    val isPlaying: Boolean get() = sink.isPlaying

    /** Sample offset of the point the sink currently plays (set by the last seek). */
    val baseSample: Long get() = baseSampleOffset

    val currentSample: Long
        get() = baseSampleOffset + sink.getPlayedFrames()

    val currentSeconds: Float
        get() = currentSample.toFloat() / sampleRate.toFloat()

    val underrunCount: Int
        get() = sink.getUnderruns()

    /**
     * Flushes the sink and anchors the clock at [sampleOffset].
     *
     * The caller is responsible for starting playback of the chunk that begins at that sample.
     */
    fun resetToSample(sampleOffset: Long) {
        sink.flush()
        baseSampleOffset = max(0L, sampleOffset)
    }

    /**
     * Moves the anchor without touching the sink.
     *
     * Used when the timeline index is recalculated after a chunk got its exact duration: the
     * mapping between chunk index and sample offset changes, so the anchor has to follow it
     * instead of letting the displayed position drift.
     */
    fun shiftBaseBy(samples: Long) {
        if (samples == 0L) return
        baseSampleOffset = max(0L, baseSampleOffset + samples)
    }

    fun reset() {
        sink.stop()
        baseSampleOffset = 0L
    }
}
