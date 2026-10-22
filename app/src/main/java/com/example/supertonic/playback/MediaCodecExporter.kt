package com.example.supertonic.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Streaming encoders that write PCM chunk by chunk straight into the destination file.
 *
 * The previous implementation required `List<ByteArray>` with the whole audiobook in RAM
 * (≈ 317 MB per hour of 44.1 kHz mono PCM); a 3-hour book needed ~1 GB and could not be exported.
 * With these writers the peak RAM is one synthesized chunk plus the codec buffers, and no
 * intermediate spool file is ever created on disk.
 */
object MediaCodecExporter {
    private const val TAG = "MediaCodecExporter"

    interface AudioFileWriter {
        /** Appends [length] bytes of 16-bit little-endian PCM. */
        fun write(pcm: ByteArray, length: Int): Boolean

        /** Flushes the encoder and finalizes the container. */
        fun finish(): Boolean

        /** Releases resources and deletes the partially written file. */
        fun abort()
    }

    // ------------------------------------------------------------------ AAC in MP4 (.m4a)

    private class M4aWriter(
        private val destinationFile: File,
        private val sampleRate: Int,
        private val bitRate: Int
    ) : AudioFileWriter {
        private val bytesPerSecond = sampleRate.toLong() * 2L

        private var codec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private var presentationTimeUs = 0L
        private var released = false

        init {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                created = encoder
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                codec = encoder

                muxer = MediaMuxer(destinationFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                // Never leak a native encoder if the container cannot be opened.
                try {
                    created?.stop()
                } catch (ignored: Exception) {
                }
                try {
                    created?.release()
                } catch (ignored: Exception) {
                }
                codec = null
                Log.e(TAG, "Cannot initialise AAC writer", e)
                throw e
            }
        }

        override fun write(pcm: ByteArray, length: Int): Boolean {
            val enc = codec ?: return false
            if (length <= 0) return true
            return try {
                var offset = 0
                while (offset < length) {
                    val inputIndex = enc.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = enc.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val room = inputBuffer.remaining()
                        val toCopy = min(room, length - offset)
                        if (toCopy <= 0) {
                            drain(endOfStream = false)
                            continue
                        }
                        inputBuffer.put(pcm, offset, toCopy)
                        enc.queueInputBuffer(inputIndex, 0, toCopy, presentationTimeUs, 0)
                        presentationTimeUs += (toCopy.toLong() * 1_000_000L) / bytesPerSecond
                        offset += toCopy
                    }
                    drain(endOfStream = false)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "AAC streaming write failed", e)
                false
            }
        }

        private fun drain(endOfStream: Boolean) {
            val enc = codec ?: return
            val mux = muxer ?: return
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val outputIndex = enc.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw IllegalStateException("Format changed twice in MediaCodec")
                        }
                        trackIndex = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encodedData = enc.getOutputBuffer(outputIndex)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        enc.releaseOutputBuffer(outputIndex, false)
                        if (isEos) return
                    }
                    else -> return
                }
            }
        }

        override fun finish(): Boolean {
            val enc = codec ?: return false
            val mux = muxer ?: return false
            return try {
                val inputIndex = enc.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    enc.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
                drain(endOfStream = true)

                enc.stop()
                enc.release()
                this.codec = null

                if (muxerStarted) mux.stop()
                mux.release()
                this.muxer = null
                released = true
                presentationTimeUs > 0L
            } catch (e: Exception) {
                Log.e(TAG, "AAC finalize failed", e)
                abort()
                false
            }
        }

        override fun abort() {
            if (released) return
            released = true
            try {
                codec?.stop()
            } catch (ignored: Exception) {
            }
            try {
                codec?.release()
            } catch (ignored: Exception) {
            }
            codec = null
            try {
                muxer?.release()
            } catch (ignored: Exception) {
            }
            muxer = null
            if (destinationFile.exists()) destinationFile.delete()
        }
    }

    // ------------------------------------------------------------------ RIFF WAV

    private class WavWriter(
        private val destinationFile: File,
        private val sampleRate: Int
    ) : AudioFileWriter {
        private var stream: FileOutputStream? = null
        private var dataBytes = 0L
        private var released = false

        init {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()
            val out = FileOutputStream(destinationFile)
            out.write(ByteArray(44)) // placeholder, rewritten in finish()
            stream = out
        }

        override fun write(pcm: ByteArray, length: Int): Boolean {
            val out = stream ?: return false
            if (length <= 0) return true
            return try {
                out.write(pcm, 0, length)
                dataBytes += length
                true
            } catch (e: Exception) {
                Log.e(TAG, "WAV streaming write failed", e)
                false
            }
        }

        override fun finish(): Boolean {
            val out = stream ?: return false
            return try {
                out.flush()
                out.close()
                stream = null

                if (dataBytes == 0L) {
                    destinationFile.delete()
                    released = true
                    return false
                }

                val header = buildHeader(dataBytes)
                RandomAccessFile(destinationFile, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(header)
                }
                released = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "WAV finalize failed", e)
                abort()
                false
            }
        }

        private fun buildHeader(totalDataLen: Long): ByteArray {
            val header = ByteArray(44)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray(Charsets.US_ASCII))
            buf.putInt((totalDataLen + 36).toInt())
            buf.put("WAVE".toByteArray(Charsets.US_ASCII))
            buf.put("fmt ".toByteArray(Charsets.US_ASCII))
            buf.putInt(16)
            buf.putShort(1.toShort()) // PCM
            buf.putShort(1.toShort()) // mono
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2)
            buf.putShort(2.toShort()) // block align
            buf.putShort(16.toShort()) // bits per sample
            buf.put("data".toByteArray(Charsets.US_ASCII))
            buf.putInt(totalDataLen.toInt())
            return header
        }

        override fun abort() {
            if (released) return
            released = true
            try {
                stream?.close()
            } catch (ignored: Exception) {
            }
            stream = null
            if (destinationFile.exists()) destinationFile.delete()
        }
    }

    /** Opens a streaming AAC/MP4 writer, or null if the encoder cannot be created. */
    fun openM4aWriter(
        destinationFile: File,
        sampleRate: Int = 44100,
        bitRate: Int = 128000
    ): AudioFileWriter? {
        return try {
            M4aWriter(destinationFile, sampleRate, bitRate)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open AAC writer", e)
            if (destinationFile.exists()) destinationFile.delete()
            null
        }
    }

    /** Opens a streaming WAV writer, or null if the file cannot be created. */
    fun openWavWriter(destinationFile: File, sampleRate: Int = 44100): AudioFileWriter? {
        return try {
            WavWriter(destinationFile, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open WAV writer", e)
            if (destinationFile.exists()) destinationFile.delete()
            null
        }
    }

    // ------------------------------------------------------------------ legacy list helpers

    /**
     * Encodes linear 16-bit PCM in RAM directly to AAC in an MPEG-4 container (.m4a).
     * Kept for callers that already hold all PCM; the export path uses [openM4aWriter].
     */
    suspend fun encodePcmChunksToM4a(
        pcmChunks: List<ByteArray>,
        destinationFile: File,
        sampleRate: Int = 44100,
        bitRate: Int = 128000,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val totalPcmBytes = pcmChunks.sumOf { it.size.toLong() }
        if (totalPcmBytes == 0L) {
            Log.e(TAG, "PCM chunks empty")
            return@withContext false
        }

        val writer = openM4aWriter(destinationFile, sampleRate, bitRate) ?: return@withContext false
        var written = 0L
        try {
            for (chunk in pcmChunks) {
                if (!writer.write(chunk, chunk.size)) {
                    writer.abort()
                    return@withContext false
                }
                written += chunk.size
                onProgress((written.toFloat() / totalPcmBytes.toFloat()).coerceIn(0f, 0.95f))
            }
            val ok = writer.finish()
            if (ok) onProgress(1.0f)
            ok
        } catch (e: Exception) {
            Log.e(TAG, "AAC export failed", e)
            writer.abort()
            false
        }
    }

    /**
     * Converts in-memory PCM chunks to a standard 16-bit PCM RIFF WAV file with a 44-byte header.
     * Writes only to the destinationFile without any temporary spool or intermediate files.
     */
    suspend fun convertPcmChunksToWav(
        pcmChunks: List<ByteArray>,
        destinationFile: File,
        sampleRate: Int = 44100
    ): Boolean = withContext(Dispatchers.IO) {
        if (pcmChunks.all { it.isEmpty() }) return@withContext false

        val writer = openWavWriter(destinationFile, sampleRate) ?: return@withContext false
        try {
            for (chunk in pcmChunks) {
                if (!writer.write(chunk, chunk.size)) {
                    writer.abort()
                    return@withContext false
                }
            }
            writer.finish()
        } catch (e: Exception) {
            Log.e(TAG, "WAV export error", e)
            writer.abort()
            false
        }
    }
}
