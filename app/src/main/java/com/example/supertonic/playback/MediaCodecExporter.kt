package com.example.supertonic.playback

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Vector

object MediaCodecExporter {
    private const val TAG = "MediaCodecExporter"

    /**
     * Encodes linear 16-bit PCM in RAM directly to AAC in an MPEG-4 container (.m4a)
     * without any intermediate PCM spool or cache files on disk.
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

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(destinationFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val chunkStreams = Vector(pcmChunks.map { ByteArrayInputStream(it) })
            val combinedInputStream: InputStream = SequenceInputStream(chunkStreams.elements())

            val chunkBuffer = ByteArray(4096)
            var bytesReadTotal = 0L
            var isInputEos = false
            var isOutputEos = false
            var presentationTimeUs = 0L

            combinedInputStream.use { stream ->
                while (!isOutputEos) {
                    if (!isInputEos) {
                        val inputBufIndex = codec.dequeueInputBuffer(10000)
                        if (inputBufIndex >= 0) {
                            val inputBuf = codec.getInputBuffer(inputBufIndex) ?: continue
                            inputBuf.clear()

                            val read = stream.read(chunkBuffer)
                            if (read <= 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                inputBuf.put(chunkBuffer, 0, read)
                                val durationUs = (read.toLong() * 1_000_000L) / (sampleRate * 2L)
                                codec.queueInputBuffer(inputBufIndex, 0, read, presentationTimeUs, 0)
                                presentationTimeUs += durationUs
                                bytesReadTotal += read
                                onProgress((bytesReadTotal.toFloat() / totalPcmBytes.toFloat()).coerceIn(0f, 0.95f))
                            }
                        }
                    }

                    val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw IllegalStateException("Format changed twice in MediaCodec")
                        }
                        val newFormat = codec.outputFormat
                        trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outputBufIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outputBufIndex)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }

                        codec.releaseOutputBuffer(outputBufIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEos = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            codec = null

            if (muxerStarted) {
                muxer.stop()
            }
            muxer.release()
            muxer = null

            onProgress(1.0f)
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "AAC export failed", e)
            destinationFile.delete()
            return@withContext false
        } finally {
            try {
                codec?.release()
            } catch (ignored: Exception) {}
            try {
                muxer?.release()
            } catch (ignored: Exception) {}
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
        val totalDataLen = pcmChunks.sumOf { it.size.toLong() }
        if (totalDataLen == 0L) return@withContext false

        destinationFile.parentFile?.mkdirs()
        try {
            val totalChunkLen = totalDataLen + 36

            val header = ByteArray(44)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF header
            buf.put("RIFF".toByteArray(Charsets.US_ASCII))
            buf.putInt(totalChunkLen.toInt())
            buf.put("WAVE".toByteArray(Charsets.US_ASCII))

            // 'fmt ' chunk
            buf.put("fmt ".toByteArray(Charsets.US_ASCII))
            buf.putInt(16) // Subchunk1Size
            buf.putShort(1.toShort()) // AudioFormat (1 = PCM)
            buf.putShort(1.toShort()) // NumChannels (1 = Mono)
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 2) // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
            buf.putShort(2.toShort()) // BlockAlign (NumChannels * BitsPerSample/8)
            buf.putShort(16.toShort()) // BitsPerSample

            // 'data' chunk
            buf.put("data".toByteArray(Charsets.US_ASCII))
            buf.putInt(totalDataLen.toInt())

            destinationFile.outputStream().use { os ->
                os.write(header)
                for (chunk in pcmChunks) {
                    os.write(chunk)
                }
                os.flush()
            }

            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "WAV export error", e)
            destinationFile.delete()
            return@withContext false
        }
    }
}
