package com.example.supertonic.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.util.Log

class AudioTrackPlayer(val sampleRate: Int = 44100) {
    private val tag = "AudioTrackPlayer"

    private var audioTrack: AudioTrack? = null
    private var totalBytesWritten = 0L

    @Volatile
    var isPlaying = false
        private set

    @Volatile
    var isPaused = false
        private set

    var playbackSpeed = 1.0f
        set(value) {
            field = value
            applyPlaybackSpeed(value)
        }

    init {
        createAudioTrack()
    }

    private fun createAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // High capacity buffer (minimum 1.5 - 2.0 seconds) to prevent any underruns or swallowed
        // audio when running at accelerated speeds (1.3x, 1.5x, 2.0x) on big.LITTLE CPUs like Helio G99
        val bufferSize = maxOf(minBufferSize * 16, sampleRate * 2 * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        applyPlaybackSpeed(playbackSpeed)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    val params = try { track.playbackParams } catch (e: Exception) { PlaybackParams() }
                    params.speed = speed
                    params.pitch = 1.0f // Preserve natural speaker pitch while time-stretching
                    track.playbackParams = params
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to set playback speed $speed", e)
        }
    }

    fun getPlaybackHeadFrames(): Long {
        val track = audioTrack ?: return 0L
        return track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    fun getTotalFramesWritten(): Long = totalBytesWritten / 2L

    fun play() {
        try {
            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                createAudioTrack()
            }
            audioTrack?.play()
            isPlaying = true
            isPaused = false
        } catch (e: Exception) {
            Log.e(tag, "Error starting AudioTrack playback", e)
        }
    }

    fun write(pcmData: ByteArray): Int {
        val track = audioTrack ?: return -1
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING && !isPaused) {
            track.play()
            isPlaying = true
        }

        var offset = 0
        val size = pcmData.size

        while (offset < size) {
            val written = track.write(pcmData, offset, size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(tag, "AudioTrack write error: $written")
                return written
            }
            offset += written
            totalBytesWritten += written
        }

        return size
    }

    fun pause() {
        try {
            audioTrack?.pause()
            isPaused = true
            isPlaying = false
        } catch (e: Exception) {
            Log.w(tag, "Error pausing AudioTrack", e)
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
            isPaused = false
            isPlaying = true
        } catch (e: Exception) {
            Log.w(tag, "Error resuming AudioTrack", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.flush()
            totalBytesWritten = 0L
            isPlaying = false
            isPaused = false
        } catch (e: Exception) {
            Log.w(tag, "Error stopping AudioTrack", e)
        }
    }

    fun flush() {
        try {
            audioTrack?.flush()
            totalBytesWritten = 0L
        } catch (e: Exception) {
            Log.w(tag, "Error flushing AudioTrack", e)
        }
    }

    fun getBufferedDurationSeconds(): Float {
        val track = audioTrack ?: return 0f
        val playbackHead = track.playbackHeadPosition.toLong()
        val totalFramesWritten = totalBytesWritten / 2L
        val unplayedFrames = (totalFramesWritten - playbackHead).coerceAtLeast(0L)
        return unplayedFrames.toFloat() / sampleRate.toFloat()
    }

    fun getUnderruns(): Int {
        return try {
            audioTrack?.underrunCount ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AudioTrack", e)
        } finally {
            audioTrack = null
            isPlaying = false
            isPaused = false
        }
    }
}
