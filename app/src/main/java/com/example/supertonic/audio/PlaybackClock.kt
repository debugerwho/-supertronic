package com.example.supertonic.audio

/**
 * Clock tracking exact sample-based playback position, underruns, and timing.
 */
class PlaybackClock(
    val sink: AudioTrackSink,
    val sampleRate: Int = 44100
) {
    private var baseSampleOffset: Long = 0L

    val isPlaying: Boolean get() = sink.isPlaying

    val currentSample: Long
        get() = baseSampleOffset + sink.getPlayedFrames()

    val currentSeconds: Float
        get() = currentSample.toFloat() / sampleRate.toFloat()

    val underrunCount: Int
        get() = sink.getUnderruns()

    fun resetToSample(sampleOffset: Long) {
        sink.flush()
        baseSampleOffset = sampleOffset
    }

    fun reset() {
        sink.stop()
        baseSampleOffset = 0L
    }
}
