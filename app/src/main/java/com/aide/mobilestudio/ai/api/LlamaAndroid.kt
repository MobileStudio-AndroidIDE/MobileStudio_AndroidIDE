package com.aide.mobilestudio.ai.api

import android.util.Log

class LlamaAndroid {
    companion object {
        private var isAvailable = false
        private var loadError: String? = null

        init {
            try {
                System.loadLibrary("llama_android")
                isAvailable = true
                Log.i("LlamaAndroid", "libllama_android.so loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                loadError = e.message
                Log.w("LlamaAndroid", "libllama_android.so load failed: ${e.message}")
                isAvailable = false
            } catch (e: Exception) {
                loadError = e.message
                Log.e("LlamaAndroid", "Unexpected error loading native lib: ${e.message}", e)
                isAvailable = false
            }
        }

        fun isNativeAvailable(): Boolean = isAvailable
        fun getLoadError(): String? = loadError
    }

    private var handle: Long = 0L

    fun loadModelSafe(modelPath: String, nContext: Int = 2048, nGpuLayers: Int = 0, nThreads: Int = 4, backend: String = "cpu"): Long {
        if (!isAvailable) throw UnsatisfiedLinkError("libllama_android.so is not installed")
        return loadModel(modelPath, nContext, nGpuLayers, nThreads, backend)
    }

    external fun loadModel(modelPath: String, nContext: Int, nGpuLayers: Int, nThreads: Int, backend: String): Long
    external fun unloadModel(modelHandle: Long)
    external fun generate(
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

    data class ModelStats(
        val loadTimeMs: Long,
        val sampleTimeMs: Long,
        val promptEvalTimeMs: Long,
        val evalTimeMs: Long,
        val totalTokens: Int
    )

    external fun getStats(modelHandle: Long): ModelStats?
}
