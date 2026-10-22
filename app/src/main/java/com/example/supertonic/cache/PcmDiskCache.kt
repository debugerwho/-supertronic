package com.example.supertonic.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

class PcmDiskCache(private val context: Context, private val maxSizeBytes: Long = 100 * 1024 * 1024L) {
    private val tag = "PcmDiskCache"
    private val cacheDir = File(context.cacheDir, "pcm_cache").apply { mkdirs() }

    fun buildKey(text: String, lang: String, voiceId: String, steps: Int, speed: Float): String {
        val input = "$text|$lang|$voiceId|$steps|$speed"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(key: String): ByteArray? {
        val file = File(cacheDir, "$key.pcm")
        return if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            try {
                file.readBytes()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    @Synchronized
    fun put(key: String, pcmData: ByteArray) {
        try {
            ensureCapacity(pcmData.size.toLong())
            val tempFile = File(cacheDir, "$key.tmp")
            val targetFile = File(cacheDir, "$key.pcm")
            tempFile.writeBytes(pcmData)
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.w(tag, "Failed to cache PCM data", e)
        }
    }

    @Synchronized
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun getCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun ensureCapacity(neededBytes: Long) {
        val files = cacheDir.listFiles() ?: return
        var currentSize = files.sumOf { it.length() }
        if (currentSize + neededBytes <= maxSizeBytes) return

        // Sort by last modified ascending (oldest first)
        val sortedFiles = files.sortedBy { it.lastModified() }
        for (f in sortedFiles) {
            if (currentSize + neededBytes <= maxSizeBytes) break
            val size = f.length()
            if (f.delete()) {
                currentSize -= size
            }
        }
    }
}
