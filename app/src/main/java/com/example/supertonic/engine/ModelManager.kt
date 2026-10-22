package com.example.supertonic.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
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

    companion object {
        private val cachedContentLengths = ConcurrentHashMap<String, Long>()
        @Volatile private var lastHeadFetchTimestamp = 0L
        private const val HEAD_TTL_MS = 7L * 24 * 60 * 60 * 1000L // 7 days
        private const val PREFS_NAME = "supertonic_model_sizes"
        private const val KEY_LAST_FETCH = "last_head_fetch_ts"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.NotInstalled)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    private val headContentLengths = ConcurrentHashMap<String, Long>()
    private val _totalContentLengthBytes = MutableStateFlow<Long?>(null)
    val totalContentLengthBytes: StateFlow<Long?> = _totalContentLengthBytes.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isCancelled = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // 1. Load cached sizes from SharedPreferences
        loadCachedSizes()

        // 2. Perform offline verification first without blocking on network
        checkInstalledState()

        // 3. Launch background HEAD update only if TTL expired or never fetched
        val now = System.currentTimeMillis()
        val lastFetch = prefs.getLong(KEY_LAST_FETCH, 0L)
        lastHeadFetchTimestamp = lastFetch
        if (now - lastFetch > HEAD_TTL_MS || cachedContentLengths.isEmpty()) {
            scope.launch {
                fetchHeadSizes()
            }
        }
    }

    private fun loadCachedSizes() {
        for (desc in SupertonicConstants.MODEL_FILES) {
            val key = "size_rev1:${desc.relativePath}"
            val saved = prefs.getLong(key, -1L)
            if (saved > 0) {
                cachedContentLengths[desc.relativePath] = saved
                headContentLengths[desc.relativePath] = saved
            }
        }
        val sum = SupertonicConstants.MODEL_FILES.sumOf {
            headContentLengths[it.relativePath] ?: it.expectedSizeBytes
        }
        _totalContentLengthBytes.value = sum
    }

    private fun saveCachedSize(relativePath: String, size: Long) {
        cachedContentLengths[relativePath] = size
        headContentLengths[relativePath] = size
        prefs.edit().putLong("size_rev1:$relativePath", size).apply()
    }

    fun getVoiceStylesDir(): File = File(modelDir, "voice_styles")

    fun getInstalledVoiceIds(): List<String> {
        val stylesDir = File(modelDir, "voice_styles")
        if (!stylesDir.exists()) return emptyList()
        return stylesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }
            ?.sorted() ?: emptyList()
    }

    suspend fun fetchHeadSizes(): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastHeadFetchTimestamp < 60_000L && cachedContentLengths.size == SupertonicConstants.MODEL_FILES.size) {
            // Already fetched recently in this process
            return@withContext cachedContentLengths.values.sum()
        }

        var sum = 0L
        var successCount = 0
        for (desc in SupertonicConstants.MODEL_FILES) {
            try {
                val headReq = Request.Builder().url(desc.remoteUrl).head().build()
                client.newCall(headReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val cl = response.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 0) {
                            saveCachedSize(desc.relativePath, cl)
                            sum += cl
                            successCount++
                        } else {
                            val existing = cachedContentLengths[desc.relativePath] ?: desc.expectedSizeBytes
                            headContentLengths[desc.relativePath] = existing
                            sum += existing
                        }
                    } else {
                        val existing = cachedContentLengths[desc.relativePath] ?: desc.expectedSizeBytes
                        headContentLengths[desc.relativePath] = existing
                        sum += existing
                    }
                }
            } catch (e: Exception) {
                val existing = cachedContentLengths[desc.relativePath] ?: desc.expectedSizeBytes
                headContentLengths[desc.relativePath] = existing
                sum += existing
            }
        }
        if (successCount > 0) {
            lastHeadFetchTimestamp = now
            prefs.edit().putLong(KEY_LAST_FETCH, now).apply()
            _totalContentLengthBytes.value = sum
        } else {
            val manifestSum = SupertonicConstants.MODEL_FILES.sumOf {
                cachedContentLengths[it.relativePath] ?: it.expectedSizeBytes
            }
            _totalContentLengthBytes.value = manifestSum
            sum = manifestSum
        }
        sum
    }

    fun checkInstalledState(): Boolean {
        if (!modelDir.exists()) {
            _downloadState.value = ModelDownloadState.NotInstalled
            return false
        }

        var totalSize = 0L

        for (desc in SupertonicConstants.MODEL_FILES) {
            val file = File(modelDir, desc.relativePath)
            if (!file.exists()) {
                _downloadState.value = ModelDownloadState.NotInstalled
                return false
            }

            val actualLength = file.length()
            val cachedExpected = headContentLengths[desc.relativePath] ?: cachedContentLengths[desc.relativePath]

            // Neither a HEAD size nor the manifest length is byte-exact in practice: CDN re-packs and
            // LFS rewrites shift the size a little. Anything within 2% is accepted; beyond that the
            // file is considered broken. A stale cached HEAD size must never make a valid model
            // permanently "corrupted" (and must never force a re-download or break offline mode).
            val expected = cachedExpected ?: desc.expectedSizeBytes
            val tolerance = (expected * 0.02).toLong()
            val diff = Math.abs(actualLength - expected)

            val manifestTolerance = (desc.expectedSizeBytes * 0.02).toLong()
            val matchesManifest = Math.abs(actualLength - desc.expectedSizeBytes) <= manifestTolerance

            val isValid = when {
                diff <= tolerance -> true
                // Cached HEAD size disagrees, but the file still matches the manifest: forget the
                // cached value so the next HEAD fetch (or the next launch) can correct it.
                cachedExpected != null && matchesManifest -> {
                    headContentLengths.remove(desc.relativePath)
                    cachedContentLengths.remove(desc.relativePath)
                    prefs.edit().remove("size_rev1:${desc.relativePath}").apply()
                    Log.w(tag, "Cached size for ${desc.relativePath} looks stale, falling back to manifest")
                    true
                }
                else -> false
            }

            // Obvious corruption: <90% or >110% of manifest
            val isCorrupted = actualLength < (desc.expectedSizeBytes * 0.90) || actualLength > (desc.expectedSizeBytes * 1.10)

            if (!isValid || isCorrupted) {
                _downloadState.value = ModelDownloadState.Error("Файл ${desc.relativePath} поврежден, скачать заново")
                return false
            }
            totalSize += actualLength
        }

        _downloadState.value = ModelDownloadState.Ready(modelDir, totalSize)
        return true
    }

    fun getFilesVerificationList(): List<FileVerificationItem> {
        return SupertonicConstants.MODEL_FILES.map { desc ->
            val file = File(modelDir, desc.relativePath)
            val expected = headContentLengths[desc.relativePath] ?: cachedContentLengths[desc.relativePath] ?: desc.expectedSizeBytes
            val presentAndValid = if (file.exists()) {
                val diff = Math.abs(file.length() - expected)
                diff == 0L || (diff <= (expected * 0.02).toLong() && file.length() > expected * 0.90 && file.length() < expected * 1.10)
            } else false

            FileVerificationItem(
                fileName = desc.relativePath,
                isPresent = presentAndValid,
                sizeBytes = if (file.exists()) file.length() else 0L,
                expectedSizeBytes = expected
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

        // Fetch HEAD sizes before starting download
        val totalExpectedBytes = fetchHeadSizes()
        var totalBytesDownloaded = 0L

        // Count already downloaded complete files
        for (desc in SupertonicConstants.MODEL_FILES) {
            val targetFile = File(modelDir, desc.relativePath)
            val expectedSize = headContentLengths[desc.relativePath] ?: desc.expectedSizeBytes
            if (targetFile.exists() && targetFile.length() == expectedSize) {
                totalBytesDownloaded += targetFile.length()
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
            val expectedSize = headContentLengths[desc.relativePath] ?: desc.expectedSizeBytes

            if (targetFile.exists() && targetFile.length() == expectedSize) {
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

                if (response.code == 416) {
                    // HTTP 416 Range Not Satisfiable: delete .part file and redownload from scratch
                    response.close()
                    partFile.delete()
                    existingPartSize = 0L

                    val retryCall = client.newCall(Request.Builder().url(desc.remoteUrl).build())
                    val retryResp = retryCall.execute()
                    if (!retryResp.isSuccessful) {
                        _downloadState.value = ModelDownloadState.Error("Ошибка загрузки ${desc.relativePath}: HTTP ${retryResp.code}")
                        return@withContext false
                    }

                    saveStreamWithProgress(retryResp.body?.byteStream(), partFile, append = false) { bytesJustRead ->
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
                } else if (response.code == 200) {
                    // Response 200: Server sends full file from byte 0, do NOT append!
                    existingPartSize = 0L
                    saveStreamWithProgress(response.body?.byteStream(), partFile, append = false) { bytesJustRead ->
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
                } else if (response.code == 206) {
                    // Response 206: Partial Content, append to existing part file
                    saveStreamWithProgress(response.body?.byteStream(), partFile, append = true) { bytesJustRead ->
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
                    response.close()
                    _downloadState.value = ModelDownloadState.Error("Ошибка загрузки ${desc.relativePath}: HTTP ${response.code}")
                    return@withContext false
                }

                if (isCancelled) {
                    _downloadState.value = ModelDownloadState.Error("Загрузка отменена")
                    return@withContext false
                }

                // Verify completed file length against expected Content-Length
                if (partFile.length() != expectedSize && expectedSize > 0) {
                    partFile.delete()
                    _downloadState.value = ModelDownloadState.Error(
                        "Файл ${desc.relativePath} поврежден (размер ${partFile.length()} вместо $expectedSize), скачать заново"
                    )
                    return@withContext false
                }

                // Atomic rename to final destination file
                targetFile.delete()
                partFile.renameTo(targetFile)
                saveCachedSize(desc.relativePath, targetFile.length())

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
                fos.flush()
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
