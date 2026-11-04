package com.aide.mobilestudio

import android.os.Build
import java.io.File

object NnapiHelper {
    data class AcceleratorInfo(
        val name: String,
        val isNpu: Boolean,
        val isGpu: Boolean,
        val isDsp: Boolean,
        val apiLevel: Int,
        val featureLevel: Int
    )

    data class NnapiStatus(
        val available: Boolean,
        val accelerators: List<AcceleratorInfo>,
        val primaryAccelerator: AcceleratorInfo?,
        val hasNpu: Boolean,
        val hasGpuNna: Boolean,
        val hasDsp: Boolean
    )

    fun detect(): NnapiStatus {
        if (Build.VERSION.SDK_INT < 27) {
            return NnapiStatus(false, emptyList(), null, false, false, false)
        }
        val accelerators = mutableListOf<AcceleratorInfo>()
        try {
            val nnClass = Class.forName("android.neuralnetworks.NeuralNetworks")
            val deviceList: List<*>? = when {
                Build.VERSION.SDK_INT >= 29 -> {
                    val getDevices = nnClass.getMethod("getDevices")
                    (getDevices.invoke(null) as? List<*>)
                }
                Build.VERSION.SDK_INT >= 27 -> {
                    val getDeviceList = nnClass.getMethod("getDeviceList")
                    (getDeviceList.invoke(null) as? List<*>)
                }
                else -> null
            }
            if (deviceList != null) {
                for (device in deviceList) {
                    if (device == null) continue
                    val deviceClass = device.javaClass
                    val name = try {
                        deviceClass.getMethod("getName").invoke(device) as? String ?: "unknown"
                    } catch (_: Exception) { "unknown" }

                    val apiLevel = try {
                        deviceClass.getMethod("getVersion").invoke(device) as? Int ?: 1
                    } catch (_: Exception) { 1 }

                    var featureLevel = apiLevel
                    val type = try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val getType = deviceClass.getMethod("getType")
                            getType.invoke(device) as? Int ?: 0
                        } else 0
                    } catch (_: Exception) { 0 }

                    val isNpu = type == 1 || name.let {
                        val n = it.lowercase()
                        n.contains("npu") || n.contains("apu") || n.contains("dla") ||
                        n.contains("neural") || n.contains("nna") ||
                        n.contains("hexagon") || n.contains("exynos") ||
                        n.contains("mediatek") || n.contains("tensor")
                    }

                    val isGpu = type == 2 || name.lowercase().contains("gpu")

                    val isDsp = type == 3 || name.lowercase().contains("dsp")

                    featureLevel = try {
                        if (Build.VERSION.SDK_INT >= 31) {
                            val capabilities = deviceClass.getMethod("getCapabilities").invoke(device)
                            val capsClass = capabilities?.javaClass
                            val perfInfo = capsClass?.getMethod("getPerformanceInfo", Int::class.javaPrimitiveType)
                            if (perfInfo != null) {
                                capsClass.getMethod("getFeatureLevel").invoke(capabilities) as? Int ?: apiLevel
                            } else apiLevel
                        } else apiLevel
                    } catch (_: Exception) { apiLevel }

                    accelerators += AcceleratorInfo(
                        name = name,
                        isNpu = isNpu,
                        isGpu = isGpu,
                        isDsp = isDsp,
                        apiLevel = apiLevel,
                        featureLevel = featureLevel
                    )
                }
            }
        } catch (_: Exception) {
        }

        if (accelerators.isEmpty()) {
            val systemNpu = detectSystemNpu()
            if (systemNpu != null) {
                accelerators += systemNpu
            }
        }

        val hasNpu = accelerators.any { it.isNpu }
        val hasGpuNna = accelerators.any { it.isGpu }
        val hasDsp = accelerators.any { it.isDsp }

        val primary = when {
            hasNpu -> accelerators.firstOrNull { it.isNpu }
            hasGpuNna -> accelerators.firstOrNull { it.isGpu }
            hasDsp -> accelerators.firstOrNull { it.isDsp }
            accelerators.isNotEmpty() -> accelerators.first()
            else -> null
        }

        return NnapiStatus(
            available = accelerators.isNotEmpty(),
            accelerators = accelerators,
            primaryAccelerator = primary,
            hasNpu = hasNpu,
            hasGpuNna = hasGpuNna,
            hasDsp = hasDsp
        )
    }

    fun npuAvailable(): Boolean = detect().hasNpu

    fun bestBackend(): String = when {
        Build.VERSION.SDK_INT < 27 -> "cpu"
        else -> {
            val status = detect()
            when {
                status.hasNpu -> "nnapi"
                status.hasGpuNna -> "nnapi"
                else -> "cpu"
            }
        }
    }

    fun acceleratorSummary(): String {
        val status = detect()
        if (!status.available) return "No hardware accelerator (CPU only)"
        val parts = mutableListOf<String>()
        if (status.hasNpu) parts += "NPU"
        if (status.hasGpuNna) parts += "GPU(NNA)"
        if (status.hasDsp) parts += "DSP"
        val names = status.accelerators.joinToString(", ") { it.name }
        return "${parts.joinToString("+")} available: $names"
    }

    private data class NpuProbe(val name: String, val paths: List<String>)

    private fun detectSystemNpu(): AcceleratorInfo? {
        val probes = listOf(
            NpuProbe("Qualcomm Hexagon NPU", listOf(
                "/vendor/lib/libhexagon_nn_stub.so",
                "/vendor/lib64/libhexagon_nn_stub.so",
                "/vendor/lib/libqti_performance.so",
                "/system/lib/libqti_performance.so"
            )),
            NpuProbe("MediaTek APU", listOf(
                "/vendor/lib/libmtk_drvb.so",
                "/vendor/lib64/libmtk_drvb.so",
                "/vendor/lib/libapu.so",
                "/vendor/lib64/libapu.so"
            )),
            NpuProbe("Samsung Exynos NPU", listOf(
                "/vendor/lib/libedma.so",
                "/vendor/lib64/libedma.so",
                "/vendor/lib/libexynos_npu.so",
                "/vendor/lib64/libexynos_npu.so"
            )),
            NpuProbe("Google Tensor NPU", listOf(
                "/vendor/lib/libgoogle_npu.so",
                "/vendor/lib64/libgoogle_npu.so",
                "/vendor/lib/libedgetpu.so",
                "/vendor/lib64/libedgetpu.so"
            ))
        )
        for (probe in probes) {
            if (probe.paths.any { File(it).exists() }) {
                return AcceleratorInfo(
                    name = probe.name,
                    isNpu = true,
                    isGpu = false,
                    isDsp = false,
                    apiLevel = Build.VERSION.SDK_INT,
                    featureLevel = Build.VERSION.SDK_INT - 26
                )
            }
        }
        return null
    }
}
