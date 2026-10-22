package com.example.supertonic.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

data class CpuCoreInfo(
    val id: Int,
    val name: String,
    val isPerformanceCore: Boolean,
    var isEnabled: Boolean
)

class CpuAffinityManager(context: Context) {
    private val tag = "CpuAffinityManager"
    private val prefs: SharedPreferences = context.getSharedPreferences("supertonic_cpu_affinity", Context.MODE_PRIVATE)

    val totalCores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    companion object {
        private const val KEY_CORE_MASK = "core_mask"
    }

    /**
     * Returns list of cores with info.
     * On Helio G99 (8 cores): Cores 0-5 are Cortex-A55 (Little), Cores 6-7 are Cortex-A76 (Big).
     */
    fun getAvailableCores(): List<CpuCoreInfo> {
        val savedMask = getSavedCoreMask()
        val list = mutableListOf<CpuCoreInfo>()

        for (i in 0 until totalCores) {
            val isBig = if (totalCores >= 8) i >= 6 else (totalCores >= 6 && i >= 4)
            val isEnabled = ((savedMask shr i) and 1) == 1
            list.add(
                CpuCoreInfo(
                    id = i,
                    name = if (isBig) "Ядро $i (Big A76)" else "Ядро $i (Little A55)",
                    isPerformanceCore = isBig,
                    isEnabled = isEnabled
                )
            )
        }
        return list
    }

    /**
     * Default core mask: Cores 6 and 7 (the two Cortex-A76 performance cores)
     * as specifically designated for ultra-low latency real-time speech inference.
     */
    fun getDefaultCoreMask(): Int {
        return if (totalCores >= 8) {
            (1 shl 6) or (1 shl 7) // Cores 6 & 7 (0xC0 = 192)
        } else if (totalCores >= 2) {
            (1 shl (totalCores - 1)) or (1 shl (totalCores - 2))
        } else {
            (1 shl totalCores) - 1
        }
    }

    fun getSavedCoreMask(): Int {
        val defaultMask = getDefaultCoreMask()
        return prefs.getInt(KEY_CORE_MASK, defaultMask)
    }

    fun setCoreMask(mask: Int) {
        val validMask = if (mask <= 0) getDefaultCoreMask() else mask
        prefs.edit().putInt(KEY_CORE_MASK, validMask).apply()
        Log.i(tag, "CPU Affinity mask set to: ${Integer.toBinaryString(validMask)}")
    }

    fun getActiveThreadCount(): Int {
        val mask = getSavedCoreMask()
        val count = Integer.bitCount(mask)
        return count.coerceIn(1, totalCores)
    }

    fun applyAffinityToCurrentThread() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            Log.d(tag, "Could not set thread priority: ${e.message}")
        }
    }

    fun applyPreset(preset: CpuPreset) {
        val mask = when (preset) {
            CpuPreset.ALL_CORES -> (1 shl totalCores) - 1
            CpuPreset.PERFORMANCE_ONLY -> getDefaultCoreMask()
            CpuPreset.EFFICIENCY_ONLY -> {
                if (totalCores >= 8) (1 shl 6) - 1 // cores 0..5 (Little A55)
                else if (totalCores >= 6) (1 shl 4) - 1
                else 1
            }
        }
        setCoreMask(mask)
    }
}

enum class CpuPreset {
    ALL_CORES,
    PERFORMANCE_ONLY,
    EFFICIENCY_ONLY
}
