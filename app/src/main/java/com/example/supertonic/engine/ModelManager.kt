package com.example.supertonic.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

sealed class ModelDownloadState {
    object NotInstalled : ModelDownloadState()
    data class Downloading(
        val currentFileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercentage: Float,
        val speedBytesPerSec: Long,
        val etaSeconds: Long
    ) : ModelDownloadState()
    data class Ready(val modelDir: File, val totalSizeBytes: Long) : ModelDownloadState()
    data class Error(val message: String) : ModelDownloadState()
}

data class FileVerificationItem(
    val fileName: String,
    val isPresent: Boolean,
    val sizeBytes: Long,
    val expectedSizeBytes: Long
)

class ModelManager(private val context: Context) {
    private val tag = "ModelManager"
    val modelDir: File = File(context.filesDir, "supertonic_model")

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotInstalled)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    init {
        checkInstalledState()
    }

    fun getVoiceStylesDir(): File = File(modelDir, "voice_styles")

    fun checkInstalledState(): Boolean {
        if (!modelDir.exists()) {
            _downloadState.value = ModelDownloadState.NotInstalled
            return false
        }

        var allPresent = true
        var totalSize = 0L

        for (desc in SupertonicConstants.MODEL_FILES) {
            val file = File(modelDir, desc.relativePath)
            if (!file.exists() || file.length() < 100) {
                allPresent = false
                break
            }
            totalSize += file.length()
        }

        if (allPresent) {
            _downloadState.value = ModelDownloadState.Ready(modelDir, totalSize)
            return true
        } else {
            _downloadState.value = ModelDownloadState.NotInstalled
            return false
        }
    }

    fun getFilesVerificationList(): List<FileVerificationItem> {
        return SupertonicConstants.MODEL_FILES.map { desc ->
            val file = File(modelDir, desc.relativePath)
            FileVerificationItem(
                fileName = desc.relativePath,
                isPresent = file.exists() && file.length() > 0,
                sizeBytes = if (file.exists()) file.length() else 0L,
                expectedSizeBytes = desc.expectedSizeBytes
            )
        }
    }

    fun cancelDownload() {
        isCancelled = true
    }

    suspend fun downloadModels(): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        File(modelDir, "voice_styles").mkdirs()

        val totalExpectedBytes = SupertonicConstants.MODEL_FILES.sumOf { it.expectedSizeBytes }
        var totalBytesDownloaded = 0L

        // Count already downloaded bytes for resume
        for (desc in SupertonicConstants.MODEL_FILES) {
            val file = File(modelDir, desc.relativePath)
            if (file.exists()) {
                totalBytesDownloaded += file.length()
            }
        }

        var lastUpdateTime = System.currentTimeMillis()
        var lastBytesCounter = totalBytesDownloaded
        var currentSpeed = 0L

        for (desc in SupertonicConstants.MODEL_FILES) {
            if (isCancelled) {
                _downloadState.value = ModelDownloadState.Error("Загрузка отменена пользователем")
                return@withContext false
            }

            val targetFile = File(modelDir, desc.relativePath)
            if (targetFile.exists() && targetFile.length() > 0) {
                // File already downloaded
                continue
            }

            val partFile = File(modelDir, desc.relativePath + ".part")
            partFile.parentFile?.mkdirs()

            var existingPartSize = if (partFile.exists()) partFile.length() else 0L

            val requestBuilder = Request.Builder().url(desc.remoteUrl)
            if (existingPartSize > 0) {
                requestBuilder.header("Range", "bytes=$existingPartSize-")
            }

            try {
                val call = client.newCall(requestBuilder.build())
                val response = call.execute()

                if (!response.isSuccessful && response.code != 416) {
                    // If range not satisfiable (416) or fail, try fresh download
                    existingPartSize = 0L
                    partFile.delete()
                    val retryCall = client.newCall(Request.Builder().url(desc.remoteUrl).build())
                    val retryResp = retryCall.execute()
                    if (!retryResp.isSuccessful) {
                        _downloadState.value = ModelDownloadState.Error("Ошибка загрузки ${desc.relativePath}: HTTP ${retryResp.code}")
                        return@withContext false
                    }
                    saveStreamWithProgress(retryResp.body?.byteStream(), partFile, false, desc) { bytesJustRead ->
                        totalBytesDownloaded += bytesJustRead
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 300) {
                            val timeDeltaSec = (now - lastUpdateTime) / 1000.0
                            if (timeDeltaSec > 0) {
                                currentSpeed = ((totalBytesDownloaded - lastBytesCounter) / timeDeltaSec).toLong()
                            }
                            lastBytesCounter = totalBytesDownloaded
                            lastUpdateTime = now
                            val remainingBytes = (totalExpectedBytes - totalBytesDownloaded).coerceAtLeast(0)
                            val eta = if (currentSpeed > 0) remainingBytes / currentSpeed else 0L
                            val progress = (totalBytesDownloaded.toFloat() / totalExpectedBytes.toFloat()).coerceIn(0f, 1f)

                            _downloadState.value = ModelDownloadState.Downloading(
                                currentFileName = desc.relativePath,
                                downloadedBytes = totalBytesDownloaded,
                                totalBytes = totalExpectedBytes,
                                progressPercentage = progress,
                                speedBytesPerSec = currentSpeed,
                                etaSeconds = eta
                            )
                        }
                    }
                } else {
                    val append = existingPartSize > 0 && response.code == 206
                    saveStreamWithProgress(response.body?.byteStream(), partFile, append, desc) { bytesJustRead ->
                        totalBytesDownloaded += bytesJustRead
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= 300) {
                            val timeDeltaSec = (now - lastUpdateTime) / 1000.0
                            if (timeDeltaSec > 0) {
                                currentSpeed = ((totalBytesDownloaded - lastBytesCounter) / timeDeltaSec).toLong()
                            }
                            lastBytesCounter = totalBytesDownloaded
                            lastUpdateTime = now
                            val remainingBytes = (totalExpectedBytes - totalBytesDownloaded).coerceAtLeast(0)
                            val eta = if (currentSpeed > 0) remainingBytes / currentSpeed else 0L
                            val progress = (totalBytesDownloaded.toFloat() / totalExpectedBytes.toFloat()).coerceIn(0f, 1f)

                            _downloadState.value = ModelDownloadState.Downloading(
                                currentFileName = desc.relativePath,
                                downloadedBytes = totalBytesDownloaded,
                                totalBytes = totalExpectedBytes,
                                progressPercentage = progress,
                                speedBytesPerSec = currentSpeed,
                                etaSeconds = eta
                            )
                        }
                    }
                }

                if (isCancelled) {
                    _downloadState.value = ModelDownloadState.Error("Загрузка отменена")
                    return@withContext false
                }

                // Rename part file to actual file
                if (partFile.exists()) {
                    partFile.renameTo(targetFile)
                }

            } catch (e: Exception) {
                Log.e(tag, "Failed downloading ${desc.relativePath}", e)
                _downloadState.value = ModelDownloadState.Error("Ошибка: ${e.localizedMessage ?: "Сбой соединения"}")
                return@withContext false
            }
        }

        val success = checkInstalledState()
        return@withContext success
    }

    private inline fun saveStreamWithProgress(
        stream: InputStream?,
        outFile: File,
        append: Boolean,
        desc: ModelFileDescriptor,
        onBytesRead: (Int) -> Unit
    ) {
        if (stream == null) return
        val buffer = ByteArray(32768)
        FileOutputStream(outFile, append).use { fos ->
            stream.use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (isCancelled) break
                    fos.write(buffer, 0, read)
                    onBytesRead(read)
                }
            }
        }
    }

    fun deleteModels(): Boolean {
        return try {
            modelDir.deleteRecursively()
            checkInstalledState()
            true
        } catch (e: Exception) {
            false
        }
    }
}
