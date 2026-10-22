package com.example.supertonic.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class CalibrationStepResult(
    val threadCount: Int,
    val durationSec: Float,
    val synthesisTimeMs: Long,
    val rtf: Float
)

data class CalibrationReport(
    val deviceModel: String,
    val availableCores: Int,
    val steps: List<CalibrationStepResult>,
    val recommendedThreads: Int,
    val bestRtf: Float
)

class PerformanceCalibrator(private val context: Context) {
    private val tag = "PerformanceCalibrator"
    private val prefs: SharedPreferences = context.getSharedPreferences("supertonic_calibration", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECOMMENDED_THREADS = "recommended_threads"
        private const val KEY_BEST_RTF = "best_rtf"
        private const val KEY_CALIBRATION_CORES = "calibration_cores"
        private const val KEY_BUILD_FINGERPRINT = "build_fingerprint"
    }

    fun getStoredRecommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val storedCores = prefs.getInt(KEY_CALIBRATION_CORES, -1)
        val storedFingerprint = prefs.getString(KEY_BUILD_FINGERPRINT, null)

        // Invalidate if device environment or CPU topology changed
        if (storedCores != cores || storedFingerprint != Build.FINGERPRINT) {
            return defaultSafeThreads(cores)
        }

        return prefs.getInt(KEY_RECOMMENDED_THREADS, defaultSafeThreads(cores))
    }

    fun getStoredBestRtf(): Float = prefs.getFloat(KEY_BEST_RTF, -1f)

    private fun defaultSafeThreads(cores: Int): Int {
        return when {
            cores <= 2 -> 2
            cores <= 4 -> 2
            cores <= 6 -> 3
            else -> 4 // Helio G99 and 8-core SoCs: 4 threads is the sweet spot for big cores
        }
    }

    fun getCandidateThreadCounts(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores <= 2 -> listOf(1, 2)
            cores <= 4 -> listOf(2, 3)
            cores <= 6 -> listOf(2, 3, 4)
            else -> listOf(2, 4, 6)
        }
    }

    suspend fun runCalibration(
        modelDir: File,
        onProgress: (currentThreads: Int, totalCandidates: Int) -> Unit
    ): CalibrationReport = withContext(Dispatchers.Default) {
        val candidates = getCandidateThreadCounts()
        val cores = Runtime.getRuntime().availableProcessors()
        val testPhrase = "Тестовая калибровка скорости синтеза речи."
        val results = mutableListOf<CalibrationStepResult>()

        for ((index, threads) in candidates.withIndex()) {
            onProgress(threads, candidates.size)
            val success = EngineHost.load(modelDir, numThreads = threads)
            if (!success) {
                Log.w(tag, "Engine failed to load with $threads threads")
                continue
            }

            val voiceStyle = EngineHost.getVoiceStyles().firstOrNull()
            if (voiceStyle != null) {
                EngineHost.withEngine { engine ->
                    // Warmup run
                    engine.synthesizeChunk(testPhrase, "ru", voiceStyle, totalSteps = 2, speed = 1.0f)

                    // Measured run with 3 steps
                    val measured = engine.synthesizeChunk(testPhrase, "ru", voiceStyle, totalSteps = 3, speed = 1.0f)
                    if (measured != null) {
                        results.add(
                            CalibrationStepResult(
                                threadCount = threads,
                                durationSec = measured.durationSeconds,
                                synthesisTimeMs = measured.synthesisTimeMs,
                                rtf = measured.rtf
                            )
                        )
                    }
                }
            }
        }

        // Pick optimal thread count: lowest RTF with slight bias against exhausting all cores
        val bestResult = results.minByOrNull { it.rtf }
        val recommended = bestResult?.threadCount ?: defaultSafeThreads(cores)
        val bestRtf = bestResult?.rtf ?: 1.0f

        // Save report
        prefs.edit()
            .putInt(KEY_RECOMMENDED_THREADS, recommended)
            .putFloat(KEY_BEST_RTF, bestRtf)
            .putInt(KEY_CALIBRATION_CORES, cores)
            .putString(KEY_BUILD_FINGERPRINT, Build.FINGERPRINT)
            .apply()

        CalibrationReport(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            availableCores = cores,
            steps = results,
            recommendedThreads = recommended,
            bestRtf = bestRtf
        )
    }
}
