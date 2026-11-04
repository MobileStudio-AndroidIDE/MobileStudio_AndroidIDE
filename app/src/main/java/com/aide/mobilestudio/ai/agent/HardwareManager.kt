package com.aide.mobilestudio.ai.agent

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware detection used to keep llama.cpp busy on both the CPU and the GPU.
 *
 * The CPU thread count is not "all cores": on arm64 phones the little cores
 * finish their share later and hold the decode step, so the default is the
 * number of performance-class cores. The GPU layer count is left to the
 * runtime, which lowers it until the model fits and then caches the answer.
 */
class HardwareManager(private val context: Context) {
    enum class Backend(val label: String) {
        CPU("CPU (Default)"),
        GPU_VULKAN("GPU (Vulkan)"),
        NPU_TPU("NPU/TPU (NNAPI)"),
        AUTO("Auto Optimize")
    }

    data class CpuTopology(
        /** Maximum clock of each readable core, in kHz. Empty when sysfs is hidden. */
        val maxFreqKhz: List<Long>,
        val cores: Int,
        val performanceThreads: Int,
    )

    fun getAvailableBackends(): List<Backend> {
        val list = mutableListOf(Backend.CPU)

        // Vulkan is generally available on Android 7+
        if (checkVulkanSupport()) {
            list.add(Backend.GPU_VULKAN)
        }

        // NNAPI is for TPU/NPU acceleration (Pixel, Qualcomm, etc.)
        if (checkNnapiSupport()) {
            list.add(Backend.NPU_TPU)
        }

        list.add(Backend.AUTO)
        return list
    }

    private fun checkVulkanSupport(): Boolean {
        // Simple check for Vulkan loader
        return File("/system/lib64/libvulkan.so").exists() || File("/system/lib/libvulkan.so").exists()
    }

    private fun checkNnapiSupport(): Boolean {
        // NNAPI is available on Android 8.1+
        return android.os.Build.VERSION.SDK_INT >= 27
    }

    fun recommendBackend(): Backend {
        // Use GPU by default if available, as it's often more stable for LLMs than NNAPI
        if (checkVulkanSupport()) return Backend.GPU_VULKAN
        return Backend.CPU
    }

    fun getCpuTopology(): CpuTopology {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = ArrayList<Long>(cores)
        for (i in 0 until cores) {
            val freq = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (freq != null && freq > 0) freqs.add(freq)
        }
        val threads = choosePerformanceThreads(freqs, cores)
        return CpuTopology(freqs, cores, threads)
    }

    fun getOptimalThreadCount(): Int {
        val topology = getCpuTopology()
        if (topology.maxFreqKhz.isNotEmpty()) {
            Log.i(
                "HardwareManager",
                "CPU cores=${topology.cores}, maxFreqKHz=${topology.maxFreqKhz}, " +
                    "performanceThreads=${topology.performanceThreads}",
            )
        }
        return topology.performanceThreads
    }

    companion object {
        /**
         * Picks the performance cores without relying on a vendor name.
         *
         * Cores whose maximum clock is at least 55% of the fastest core are
         * treated as performance-class. This keeps the two little Cortex-A520
         * cores out on an S24-class SoC while still using the six cores that can
         * actually contribute to a decode step.
         */
        internal fun choosePerformanceThreads(maxFreqKhz: List<Long>, cores: Int): Int {
            if (cores <= 0) return 1
            if (maxFreqKhz.isEmpty()) return min(6, cores)
            val fastest = maxFreqKhz.maxOrNull() ?: return min(6, cores)
            if (fastest <= 0) return min(6, cores)
            val cutoff = (fastest * 0.55).toLong()
            val performance = maxFreqKhz.count { it >= cutoff }
            val wanted = max(2, min(8, performance))
            return min(cores, wanted)
        }

        private fun readLong(path: String): Long? =
            runCatching { File(path).readText().trim().toLong() }.getOrNull()
    }
}
