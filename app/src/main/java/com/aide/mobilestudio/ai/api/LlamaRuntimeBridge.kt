package com.aide.mobilestudio.ai.api

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * LlamaRuntimeBridge — dlopen-based JNI bridge to the downloaded llama.cpp
 * Android runtime (arm64-v8a, CPU + Vulkan backends).
 *
 * Unlike [LlamaAndroid] (which is statically linked to an app-built, CPU-only
 * llama.cpp), this bridge loads the runtime's own libllama.so/libggml*.so via
 * dlopen from the on-device install directory, so:
 *   - the prebuilt runtime is used (llama.cpp is never recompiled at app build)
 *   - the Vulkan GPU backend is really used (libggml-vulkan.so)
 *   - CPU fallback works on devices without Vulkan (libggml-cpu.so)
 *
 * GPU offload ([loadModelWithFallback]):
 *   CPU <====|=============|===========|===> GPU
 *   n_gpu_layers=0 (CPU 위주) ←→ 가능한 최대 n_gpu_layers (GPU 최대 offload)
 *   모델이 GPU 메모리에 안 들어가면 자동으로 offload를 낮춰 재시도한다.
 *   무한 재시도하지 않는다 (최대 [MAX_FALLBACK_RETRIES]회 후 CPU로 폴백).
 */
class LlamaRuntimeBridge {
    companion object {
        private const val TAG = "LlamaRuntimeBridge"
        private const val MAX_FALLBACK_RETRIES = 5

        /**
         * Last offload that actually loaded, per model/context/thread target.
         *
         * The first load may spend several attempts finding the largest
         * offload that fits. Later requests start there instead of paying that
         * search again; a failure still steps down normally.
         */
        private val workingGpuLayers = ConcurrentHashMap<String, Int>()

        /** True when libllama_runtime_jni.so (the bridge, not the runtime) loaded. */
        val bridgeAvailable: Boolean = try {
            System.loadLibrary("llama_runtime_jni")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "libllama_runtime_jni.so load failed", e)
            false
        }

        fun isBridgeAvailable(): Boolean = bridgeAvailable
    }

    data class ModelStats(
        val loadTimeMs: Long,
        val sampleTimeMs: Long,
        val promptEvalTimeMs: Long,
        val evalTimeMs: Long,
        val totalTokens: Int
    )

    data class LoadResult(
        val handle: Long,
        /**
         * Layers the loader was asked to place on the GPU.
         *
         * Deliberately not called "used": llama.cpp clamps this to the model's
         * own layer count and logs what it actually offloaded
         * ("offloaded N/M layers to GPU"). Reporting the request as the outcome
         * is how a CPU-only load was mistaken for a GPU one.
         */
        val requestedGpuLayers: Int,
        val backend: String
    )

    private var handle: Long = 0L
    private var gpuLayersUsed = 0
    private var backendUsed = "cpu"

    // ------------------------------------------------------------------
    // Runtime lifecycle (dlopen of the downloaded runtime)
    // ------------------------------------------------------------------

    /**
     * dlopen()s the runtime libs from [runtimeDir] and resolves the llama.cpp API.
     * [appNativeLibDir] (the app's native lib dir) is used to preload the app's
     * own libc++_shared.so first so DT_NEEDED lookups resolve.
     */
    fun loadRuntime(runtimeDir: String, appNativeLibDir: String? = null): Boolean {
        if (!bridgeAvailable) {
            Log.e(TAG, "bridge not available (libllama_runtime_jni.so)")
            return false
        }
        val ok = nativeLoadRuntime(runtimeDir, appNativeLibDir ?: "")
        if (!ok) {
            Log.e(TAG, "runtime load failed: ${nativeLastError()}")
        }
        return ok
    }

    fun isRuntimeLoaded(): Boolean = bridgeAvailable && nativeRuntimeLoaded()
    fun lastError(): String? = if (bridgeAvailable) nativeLastError().takeIf { it != "runtime not loaded" } else "bridge not available"
    fun isVulkanAvailable(): Boolean = bridgeAvailable && nativeIsVulkanAvailable()
    fun gpuName(): String = if (bridgeAvailable) nativeGetGpuName() else "unknown"

    // ------------------------------------------------------------------
    // Model management
    // ------------------------------------------------------------------

    /**
     * Loads a model with automatic GPU offload fallback:
     * load 실패 시 n_gpu_layers를 낮춰 재시도하고, 최대 [MAX_FALLBACK_RETRIES]회
     * 후 CPU(0 layers)로 폴백한다. 무한 재시도하지 않는다.
     */
    fun loadModelWithFallback(
        modelPath: String,
        nContext: Int,
        requestedGpuLayers: Int,
        nThreads: Int,
        vulkanAvailable: Boolean,
        flashAttnType: Int = 0,   // 0=DISABLED, 1=AUTO(-1), 2=ENABLED
        kvCacheType: Int = 1,     // 0=F32, 1=F16, 8=Q8_0
        maxLayers: Int = 0,
        onAttempt: ((attempt: Int, nGpuLayers: Int) -> Unit)? = null
    ): LoadResult {
        unload()

        // Vulkan unavailable, or the caller asked for CPU: load on the CPU and
        // say so, instead of reporting an offload that never happened.
        val resolved = when {
            !vulkanAvailable -> 0
            requestedGpuLayers == 0 -> 0
            // Negative means "every layer" (llama.h). Resolve it to the model's
            // own layer count when that is known so the fallback ladder below
            // steps down in real fractions instead of blind decrements; when the
            // count is unknown the negative value is passed through and llama.cpp
            // still resolves it to all layers.
            requestedGpuLayers < 0 -> if (maxLayers > 0) maxLayers else requestedGpuLayers
            maxLayers > 0 -> requestedGpuLayers.coerceAtMost(maxLayers)
            else -> requestedGpuLayers
        }
        when {
            !vulkanAvailable -> Log.i(TAG, "Vulkan backend unavailable: loading CPU only")
            resolved < 0 -> Log.i(TAG, "Requested all layers (model layer count unknown to the app)")
            maxLayers > 0 -> Log.i(TAG, "Requested $resolved of the model's $maxLayers layers on the GPU")
            resolved == 0 -> Log.i(TAG, "Requested CPU only (n_gpu_layers=0)")
        }
        Log.i(TAG, "KV cache type: type_k=$kvCacheType type_v=$kvCacheType, flash_attn=$flashAttnType")
        // Map Kotlin flash_attn to llama.h enum:
        //   Kotlin 0=DISABLED → llama.cpp 0 (DISABLED)
        //   Kotlin 1=AUTO     → llama.cpp -1 (AUTO)
        //   Kotlin 2=ENABLED  → llama.cpp 1 (ENABLED)
        val flashAttnNative = when (flashAttnType) {
            1 -> -1  // AUTO
            2 -> 1   // ENABLED
            else -> 0 // DISABLED (0 or any unknown)
        }
        val profileKey = "$modelPath|ctx=$nContext|threads=$nThreads|max=$maxLayers|requested=$requestedGpuLayers|flash=$flashAttnType|kv=$kvCacheType"
        val cached = if (vulkanAvailable && requestedGpuLayers != 0) workingGpuLayers[profileKey] else null
        var nGpu = cached ?: resolved
        if (cached != null) Log.i(TAG, "Using cached GPU offload: n_gpu_layers=$cached")

        var attempt = 0
        while (true) {
            attempt++
            onAttempt?.invoke(attempt, nGpu)
            Log.i(TAG, "Load attempt #$attempt: n_gpu_layers=$nGpu (vulkan=$vulkanAvailable)")
            val h = try {
                nativeLoadModel(modelPath, nContext, nGpu, nThreads, flashAttnNative, kvCacheType, kvCacheType)
            } catch (e: Throwable) {
                Log.e(TAG, "nativeLoadModel crashed: ${e.message}", e)
                0L
            }
            if (h != 0L) {
                handle = h
                gpuLayersUsed = nGpu
                backendUsed = if (nGpu == 0) "CPU" else "Vulkan"
                workingGpuLayers[profileKey] = nGpu
                return LoadResult(h, nGpu, backendUsed)
            }
            if (nGpu == 0) {
                // CPU was the last rung of the ladder; nothing is left to lower.
                val err = nativeLastError() ?: "unknown"
                Log.e(TAG, "Model load failed on CPU too, after $attempt attempts: $err")
                throw RuntimeException("Model load failed: $err")
            }
            // Lower the offload and, once the retries are spent, fall back to
            // the CPU: the ladder exists to keep the app alive on a device that
            // cannot hold the model, not to retry forever. The failure text is
            // kept for the report - an out-of-memory load and a corrupt file
            // must not read the same.
            val err = nativeLastError() ?: "unknown"
            val step = when {
                maxLayers > 0 -> maxOf(1, maxLayers / 5)
                nGpu > 8 -> maxOf(2, nGpu / 4)
                else -> 1
            }
            nGpu = if (attempt >= MAX_FALLBACK_RETRIES) {
                0
            } else {
                (nGpu - step).coerceAtLeast(0)
            }
            Log.w(TAG, "Load attempt #$attempt failed ($err); retrying with n_gpu_layers=$nGpu")
        }
    }

    fun unload() {
        if (handle != 0L) {
            try { nativeUnloadModel(handle) } catch (e: Throwable) { Log.w(TAG, "unload failed: ${e.message}") }
            handle = 0L
        }
    }

    val currentHandle: Long get() = handle
    val currentGpuLayers: Int get() = gpuLayersUsed
    val currentBackend: String get() = backendUsed

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    fun generate(
        modelHandle: Long,
        prompt: String,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        seed: Int = -1,
        stopSequences: Array<String> = emptyArray(),
        onToken: (String) -> Boolean
    ): String {
        if (modelHandle == 0L) throw IllegalStateException("model is not loaded")
        return nativeGenerate(modelHandle, prompt, temperature, topP, topK, repeatPenalty, seed, stopSequences, onToken)
    }

    // ------------------------------------------------------------------
    // Native methods (implemented in llama_runtime_jni.cpp)
    // ------------------------------------------------------------------

    external fun nativeLoadRuntime(runtimeDir: String, appNativeLibDir: String): Boolean
    external fun nativeRuntimeLoaded(): Boolean
    external fun nativeLastError(): String
    external fun nativeIsVulkanAvailable(): Boolean
    external fun nativeGetGpuName(): String
    external fun nativeLoadModel(
        modelPath: String,
        nContext: Int,
        nGpuLayers: Int,
        nThreads: Int,
        flashAttnType: Int,
        typeK: Int,
        typeV: Int
    ): Long
    external fun nativeUnloadModel(handle: Long)
    external fun nativeGenerate(
        modelHandle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
        stopSequences: Array<String>,
        onToken: (String) -> Boolean
    ): String
    external fun getStats(modelHandle: Long): ModelStats?
}
