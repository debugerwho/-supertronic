package com.example.supertonic.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision AudioTrack sink operating in MODE_STREAM with <= 100 ms write slices.
 */
class AudioTrackSink(
    val sampleRate: Int = 44100
) {
    private val tag = "AudioTrackSink"

    companion object {
        // Max 100 ms slice: 44100 * 0.1s = 4410 samples * 2 bytes = 8820 bytes
        const val MAX_SLICE_BYTES = 8820
    }

    private var audioTrack: AudioTrack? = null
    private var minBufferSize: Int = 0

    private val _totalWrittenFrames = AtomicLong(0)
    private var headOffsetFrames: Long = 0L
    private var lastObservedHead: Long = 0L

    @Volatile
    var isPlaying: Boolean = false
        private set

    var playbackSpeed: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.5f, 2.5f)
            applyPlaybackSpeed(field)
        }

    init {
        initAudioTrack()
    }

    @Synchronized
    private fun initAudioTrack() {
        try {
            audioTrack?.release()

            minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = max(minBufferSize * 4, 32768)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            applyPlaybackSpeed(playbackSpeed)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize AudioTrack", e)
        }
    }

    private fun applyPlaybackSpeed(speed: Float) {
        val track = audioTrack ?: return
        try {
            val params = track.playbackParams ?: PlaybackParams()
            params.speed = speed
            params.pitch = 1.0f
            track.playbackParams = params
        } catch (e: Exception) {
            Log.w(tag, "Failed to set playback speed", e)
        }
    }

    @Synchronized
    fun play() {
        val track = audioTrack ?: return
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            isPlaying = true
        } catch (e: Exception) {
            Log.e(tag, "Error starting playback", e)
        }
    }

    @Synchronized
    fun pause() {
        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            isPlaying = false
        } catch (e: Exception) {
            Log.e(tag, "Error pausing playback", e)
        }
    }

    @Synchronized
    fun flush() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            val currentHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            headOffsetFrames = _totalWrittenFrames.get() - currentHead
            lastObservedHead = currentHead
        } catch (e: Exception) {
            Log.e(tag, "Error flushing AudioTrack", e)
        }
    }

    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        try {
            track.stop()
            track.flush()
            isPlaying = false
            _totalWrittenFrames.set(0)
            headOffsetFrames = 0L
            lastObservedHead = 0L
        } catch (e: Exception) {
            Log.e(tag, "Error stopping AudioTrack", e)
        }
    }

    /**
     * Writes PCM data in <= 100 ms slices (<= 8820 bytes).
     * Returns total bytes successfully written.
     */
    fun writeSlice(pcmBytes: ByteArray, offset: Int, length: Int): Int {
        val track = audioTrack ?: return 0
        if (length <= 0) return 0

        val sliceSize = min(length, MAX_SLICE_BYTES)
        val written = track.write(pcmBytes, offset, sliceSize, AudioTrack.WRITE_BLOCKING)
        if (written > 0) {
            val writtenFrames = written / 2 // 16-bit mono = 2 bytes per frame
            _totalWrittenFrames.addAndGet(writtenFrames.toLong())
        }
        return written
    }

    fun getPlayedFrames(): Long {
        val track = audioTrack ?: return 0L
        val rawHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        lastObservedHead = rawHead
        val adjusted = headOffsetFrames + rawHead
        return max(0L, min(adjusted, _totalWrittenFrames.get()))
    }

    fun getWrittenFrames(): Long = _totalWrittenFrames.get()

    fun getBufferedDurationSeconds(): Float {
        val written = _totalWrittenFrames.get()
        val played = getPlayedFrames()
        val bufferedFrames = max(0L, written - played)
        return bufferedFrames.toFloat() / sampleRate.toFloat()
    }

    fun getUnderruns(): Int {
        val track = audioTrack ?: return 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            track.underrunCount
        } else {
            0
        }
    }

    @Synchronized
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }
}
