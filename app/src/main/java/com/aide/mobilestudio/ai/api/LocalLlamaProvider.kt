package com.aide.mobilestudio.ai.api

import android.util.Log
import com.aide.mobilestudio.ai.GgufInfo
import com.aide.mobilestudio.ai.LlamaRuntimeManager
import com.aide.mobilestudio.ai.agent.ModelManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class LocalLlamaProvider(private val model: ModelManager.ModelInfo) : AIProvider {
    override val id: String = "local_llama_${model.id}"
    override val name: String = "Local: ${model.name}"

    private val llama = LlamaAndroid()
    private var activeBridge: LlamaRuntimeBridge? = null
    private var modelHandle: Long = 0L
    private val handleMutex = Mutex()
    private val isGenerating = AtomicBoolean(false)

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?
    ): Flow<AIProvider.StreamEvent> = callbackFlow {
        // Fail early only when NEITHER the downloaded runtime NOR the legacy lib is available
        val runtimeInstalled = LlamaRuntimeBridge.isBridgeAvailable() &&
            LlamaRuntimeManager.get().isInstalled()
        if (!runtimeInstalled && !LlamaAndroid.isNativeAvailable()) {
            Log.e("LocalLlamaProvider", "No AI runtime installed and legacy lib unavailable")
            trySend(AIProvider.StreamEvent.Error(
                "AI runtime is not installed.\nInstall it from AI Settings (llama.cpp runtime download)."
            ))
            close()
            return@callbackFlow
        }

        handleMutex.withLock {
            if (modelHandle == 0L) {
                // Every logical core: the old cap of 6 discarded up to 4 of
                // them and showed up directly in prompt-eval time.
                val fallbackThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
                val nThreads = options?.optInt("nThreads", fallbackThreads) ?: fallbackThreads
                // -1 = every layer; the bridge clamps it to the real count and
                // lowers it until the model fits in GPU memory.
                val nGpuRequested = options?.optInt("nGpuLayers", -1) ?: -1

                // Primary: downloaded llama.cpp runtime (CPU + Vulkan) via dlopen bridge
                val runtimeInstalled = LlamaRuntimeBridge.isBridgeAvailable() &&
                    LlamaRuntimeManager.get().isInstalled()
                if (runtimeInstalled) {
                    val bridge = LlamaRuntimeBridge()
                    // libllama.so needs libc++_shared.so — stage it where the
                    // JNI preload looks first (filesDir install dir)
                    val dep = LlamaRuntimeManager.get().ensureNativeDeps()
                    if (dep == null) Log.w("LocalLlamaProvider", "libc++_shared.so could not be staged")
                    if (!bridge.isRuntimeLoaded() &&
                        !bridge.loadRuntime(
                            LlamaRuntimeManager.get().runtimeLibDir().absolutePath,
                            LlamaRuntimeManager.get().appNativeLibDir()
                        )
                    ) {
                        val err = bridge.lastError() ?: "unknown"
                        Log.e("LocalLlamaProvider", "Runtime load failed: $err")
                        trySend(AIProvider.StreamEvent.Error("AI runtime load failed: $err\nReinstall the AI runtime from AI Settings."))
                        close()
                        return@callbackFlow
                    }
                    val vulkan = bridge.isVulkanAvailable()
                    val ctxLen = options?.optInt("context", 0)?.takeIf { it >= 512 } ?: model.contextLength
                    // flash_attn: 0=DISABLED, 1=AUTO, 2=ENABLED (llama.cpp enum mapping handled in bridge)
                    val flashAttn = options?.optInt("flash_attn", 1) ?: 0
                    // kv_cache_type: 0=F32, 1=F16, 8=Q8_0
                    val kvCacheType = options?.optInt("kv_cache_type", 1) ?: 1
                    Log.i("LocalLlamaProvider", "Loading model at ${model.path} (ctx=$ctxLen, nGpu=$nGpuRequested, threads=$nThreads, vulkan=$vulkan, gpu=${bridge.gpuName()}, flash_attn=$flashAttn, kv_cache=$kvCacheType)")
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val modelLayers = model.layerCount
                                ?: runCatching { GgufInfo.read(File(model.path))?.nLayer }.getOrNull()
                                ?: 0
                            bridge.loadModelWithFallback(
                                model.path,
                                ctxLen.coerceIn(512, 4096),
                                nGpuRequested,
                                nThreads,
                                vulkan,
                                flashAttnType = flashAttn,
                                kvCacheType = kvCacheType,
                                maxLayers = modelLayers,
                            )
                        }
                        activeBridge = bridge
                        modelHandle = result.handle
                        Log.i("LocalLlamaProvider", "Model loaded: handle=${result.handle}, backend=${result.backend}, requestedGpuLayers=${result.requestedGpuLayers}, flash_attn=$flashAttn, kv_cache=$kvCacheType")
                    } catch (e: Exception) {
                        Log.e("LocalLlamaProvider", "Model load failed (GPU offload fallback exhausted): ${e.message}", e)
                        modelHandle = 0L
                        activeBridge = null
                        trySend(AIProvider.StreamEvent.Error(
                            "Failed to load model: ${e.message}\nTry lowering GPU Layers in AI Settings (CPU fallback)."
                        ))
                        close()
                        return@callbackFlow
                    }
                } else {
                    // Fallback: legacy bundled llama (CPU-only, app-built llama.cpp)
                    if (!LlamaAndroid.isNativeAvailable()) {
                        val err = LlamaAndroid.getLoadError() ?: "unknown"
                        Log.e("LocalLlamaProvider", "No AI runtime installed and legacy lib unavailable: $err")
                        trySend(AIProvider.StreamEvent.Error(
                            "AI runtime is not installed.\nInstall it from AI Settings (llama.cpp runtime download)."
                        ))
                        close()
                        return@callbackFlow
                    }
                    val backendName = options?.optString("backend", "cpu") ?: "cpu"
                    Log.i("LocalLlamaProvider", "Loading model at ${model.path} (legacy, ctx=${model.contextLength}, nGpu=0, threads=$nThreads, backend=$backendName)")
                    try {
                        modelHandle = withContext(Dispatchers.IO) {
                            llama.loadModel(model.path, model.contextLength.coerceIn(512, 2048), 0, nThreads, backendName)
                        }
                    } catch (e: Exception) {
                        Log.e("LocalLlamaProvider", "loadModel crashed: ${e.message}", e)
                        modelHandle = 0L
                    }
                    if (modelHandle == 0L) {
                        Log.e("LocalLlamaProvider", "Model load returned 0L handle. Path: ${model.path}, backend: $backendName")
                        trySend(AIProvider.StreamEvent.Error("Failed to load model at ${model.path}. Check file exists and backend '$backendName' is supported on this device."))
                        close()
                        return@callbackFlow
                    }
                    Log.i("LocalLlamaProvider", "Model loaded successfully (legacy): handle=$modelHandle")
                }
            }
        }

        if (isGenerating.getAndSet(true)) {
            Log.w("LocalLlamaProvider", "Another generation is already running")
            trySend(AIProvider.StreamEvent.Error("Another generation is already running"))
            close()
            isGenerating.set(false)
            return@callbackFlow
        }

        try {
            // Local models have a much tighter practical context limit than
            // remote providers. Keep the newest turns and fit a conservative
            // character budget before native tokenization.
            val prompt = buildPrompt(compactHistory(history), tools)
            val temp = options?.optDouble("temperature", 0.7)?.toFloat() ?: 0.7f
            var tokenCount = 0

            Log.i("LocalLlamaProvider", "Starting generation: temp=$temp")

            var planBuffer = StringBuilder()
            var isInPlan = false
            var toolCallBuffer = StringBuilder()
            var isInToolCall = false

            val currentHandle = handleMutex.withLock { modelHandle }
            if (currentHandle == 0L) {
                Log.e("LocalLlamaProvider", "Model handle is 0 after load attempt")
                trySend(AIProvider.StreamEvent.Error("Model not loaded"))
                close()
                return@callbackFlow
            }

            val job = withContext(Dispatchers.IO) {
                try {
                    val bridgeForGen = activeBridge
                    val generateFn: (Long, String, Float, Float, Int, Float, Int, Array<String>, (String) -> Boolean) -> String =
                        if (bridgeForGen != null) bridgeForGen::generate else llama::generate
                    Log.i("LocalLlamaProvider", "Calling llama.generate (${if (bridgeForGen != null) "llama.cpp runtime" else "legacy"}) on IO dispatcher")
                    val result = generateFn(
                        currentHandle, prompt, temp, 0.9f, 40, 1.1f, -1,
                        arrayOf("</tool_call>", "</plan>")
                    ) { token ->
                        tokenCount++
                        var current = token

                        if (!isInPlan && current.contains("<plan>")) {
                            val split = current.split("<plan>", limit = 2)
                            if (split[0].isNotEmpty()) {
                                val sendResult = trySend(AIProvider.StreamEvent.Text(split[0]))
                                if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Text failed: ${sendResult.exceptionOrNull()?.message}")
                            }
                            isInPlan = true
                            current = split[1]
                        }

                        if (isInPlan) {
                            if (current.contains("</plan>")) {
                                val split = current.split("</plan>", limit = 2)
                                planBuffer.append(split[0])
                                val planResult = trySend(AIProvider.StreamEvent.Plan(planBuffer.toString()))
                                if (planResult.isFailure) Log.w("LocalLlamaProvider", "trySend Plan failed: ${planResult.exceptionOrNull()?.message}")
                                isInPlan = false
                                planBuffer = StringBuilder()
                                if (split[1].isNotEmpty()) {
                                    val sendResult = trySend(AIProvider.StreamEvent.Text(split[1]))
                                    if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Text failed: ${sendResult.exceptionOrNull()?.message}")
                                }
                            } else {
                                planBuffer.append(current)
                            }
                            return@generateFn true
                        }

                        if (!isInToolCall && current.contains("<tool_call>")) {
                            isInToolCall = true
                            current = current.substringAfter("<tool_call>")
                        }

                        if (isInToolCall) {
                            if (current.contains("</tool_call>")) {
                                toolCallBuffer.append(current.substringBefore("</tool_call>"))
                                runCatching {
                                    val json = JSONObject(toolCallBuffer.toString())
                                    val tcResult = trySend(AIProvider.StreamEvent.ToolCall(
                                        json.getString("name"),
                                        json.optJSONObject("arguments") ?: JSONObject()
                                    ))
                                    if (tcResult.isFailure) Log.w("LocalLlamaProvider", "trySend ToolCall failed: ${tcResult.exceptionOrNull()?.message}")
                                }.onFailure { e ->
                                    Log.e("LocalLlamaProvider", "Failed to parse tool call JSON: ${e.message}")
                                }
                                isInToolCall = false
                                toolCallBuffer = StringBuilder()
                            } else {
                                toolCallBuffer.append(current)
                            }
                            return@generateFn true
                        }

                        val sendResult = trySend(AIProvider.StreamEvent.Text(current))
                        if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Text failed: ${sendResult.exceptionOrNull()?.message}")
                        true
                    }

                    if (result != null && result.startsWith("Error")) {
                        Log.e("LocalLlamaProvider", "Generation failed: $result")
                        val sendResult = trySend(AIProvider.StreamEvent.Error(result))
                        if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Error failed: ${sendResult.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.e("LocalLlamaProvider", "Native generate crashed: ${e.message}", e)
                    val sendResult = trySend(AIProvider.StreamEvent.Error("AI generation failed: ${e.message}"))
                    if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Error failed: ${sendResult.exceptionOrNull()?.message}")
                }
            }

            Log.i("LocalLlamaProvider", "Generation finished: $tokenCount tokens produced")
            if (tokenCount == 0) {
                Log.e("LocalLlamaProvider", "Generation produced 0 tokens - model may be misconfigured or prompt is empty")
                val sendResult = trySend(AIProvider.StreamEvent.Error("AI generated 0 tokens. The model may not be compatible with the prompt format, or it may have refused to respond."))
                if (sendResult.isFailure) Log.w("LocalLlamaProvider", "trySend Error failed: ${sendResult.exceptionOrNull()?.message}")
            }
            val doneResult = trySend(AIProvider.StreamEvent.Done)
            if (doneResult.isFailure) Log.w("LocalLlamaProvider", "trySend Done failed: ${doneResult.exceptionOrNull()?.message}")
        } finally {
            isGenerating.set(false)
        }
        close()
        awaitClose { /* No-op */ }
    }

    private fun compactHistory(history: JSONArray): JSONArray {
        val compact = JSONArray()
        var budget = MAX_HISTORY_CHARS
        // Walk backwards so the current request is never discarded.
        for (i in history.length() - 1 downTo 0) {
            val message = history.optJSONObject(i) ?: continue
            val parts = message.optJSONArray("parts") ?: continue
            val text = parts.optJSONObject(0)?.optString("text", "") ?: ""
            if (text.isEmpty()) continue
            if (budget <= 0 && compact.length() > 0) continue
            val clipped = text.take(budget.coerceAtLeast(0))
            val copy = JSONObject(message.toString())
            copy.getJSONArray("parts").getJSONObject(0).put("text", clipped)
            compact.put(copy)
            budget -= clipped.length
        }
        // Restore chronological order without relying on JSONArray insertion.
        val ordered = JSONArray()
        for (i in compact.length() - 1 downTo 0) ordered.put(compact.get(i))
        return ordered
    }

    private fun buildPrompt(history: JSONArray, tools: JSONArray?): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\nYou are a helpful AI coding assistant.\n")
        if (tools != null && tools.length() > 0) {
            val names = mutableListOf<String>()
            for (j in 0 until tools.length()) {
                val t = tools.optJSONObject(j) ?: continue
                names.add(t.optString("name", ""))
            }
            sb.append("Tools: " + names.joinToString(", ") + "\n")
        }
        sb.append("<|im_end|>\n")
        
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.getString("role")
            val parts = msg.getJSONArray("parts")
            if (parts.length() == 0) continue
            val part = parts.getJSONObject(0)
            
            val content = if (part.has("text")) {
                part.getString("text")
            } else if (part.has("functionCall")) {
                val fc = part.getJSONObject("functionCall")
                "<tool_call>${fc.toString()}</tool_call>"
            } else if (part.has("functionResponse")) {
                val fr = part.getJSONObject("functionResponse")
                "Tool result: ${fr.getJSONObject("response").toString()}"
            } else ""
            
            sb.append("<|im_start|>$role\n$content<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("local", true)
    }

    fun unload() {
        if (isGenerating.get()) {
            Log.w("LocalLlamaProvider", "Cannot unload while generating")
            return
        }
        if (modelHandle != 0L) {
            try {
                val bridge = activeBridge
                if (bridge != null) bridge.unload() else llama.unloadModel(modelHandle)
            } catch (e: Exception) {
                Log.e("LocalLlamaProvider", "unloadModel crashed: ${e.message}", e)
            }
            modelHandle = 0L
            activeBridge = null
        }
    }

    /** Status for the AI screen: backend / GPU layers / model / context. */
    fun runtimeStatus(): String {
        val bridge = activeBridge
        val modelFile = File(model.path)
        val info = GgufInfo.read(modelFile)
        return buildString {
            append("Backend: ${bridge?.currentBackend ?: "not loaded"}\n")
            append("GPU: ${bridge?.gpuName() ?: "unknown"}\n")
            if (bridge != null && bridge.currentGpuLayers > 0) {
                val max = info?.nLayer
                append("GPU Layers: ${bridge.currentGpuLayers}${if (max != null) " / $max" else ""}\n")
            } else {
                append("GPU Layers: 0 (CPU)\n")
            }
            append("Model: ${info?.generalName ?: model.name}\n")
            append("Context: ${model.contextLength}\n")
        }
    }

    /**
     * Estimates the memory required to run this model with the given settings.
     * Returns a MemoryEstimate with breakdown, or null if GGUF info is unreadable.
     */
    fun estimateMemory(
        contextLength: Int = model.contextLength,
        nGpuLayers: Int = -1, // -1 = all layers
        kvCacheQuant: String = "Q8_0",
    ): GgufInfo.MemoryEstimate? {
        val modelFile = File(model.path)
        val info = GgufInfo.read(modelFile) ?: return null
        return info.estimateTotalMemory(
            contextLength = contextLength,
            nGpuLayers = if (nGpuLayers < 0) (info.nLayer ?: 0) else nGpuLayers,
            kvCacheQuant = kvCacheQuant,
        )
    }

    /**
     * Checks if the model can likely run on this device with the given settings.
     * Returns a pair of (canRun: Boolean, warning: String?).
     */
    fun canRunOnDevice(
        contextLength: Int = model.contextLength,
        nGpuLayers: Int = -1,
        kvCacheQuant: String = "Q8_0",
    ): Pair<Boolean, String?> {
        val estimate = estimateMemory(contextLength, nGpuLayers, kvCacheQuant)
            ?: return Pair(false, "Could not read model metadata (GGUF info unavailable)")

        // Get available memory
        val runtime = Runtime.getRuntime()
        val availableMem = runtime.maxMemory() // This is the JVM heap limit, not system RAM
        // Use a more realistic available memory estimate for the process
        val availableProcessMem = getAvailableProcessMemory()

        // Check if estimated total fits within available memory with some headroom
        val headroom = (availableProcessMem * 0.15).toLong() // 15% headroom
        val canRun = estimate.total <= (availableProcessMem - headroom)

        val warning = if (!canRun) {
            "Estimated memory (${estimate.formatBytes(estimate.total)}) exceeds available process memory (${estimate.formatBytes(availableProcessMem)}). " +
                "Try reducing context length, GPU layers, or use a smaller quantization."
        } else if (estimate.total > availableProcessMem * 0.85) {
            "Memory usage will be high (${estimate.formatBytes(estimate.total)} / ${estimate.formatBytes(availableProcessMem)}). " +
                "Consider reducing context length if you experience OOM crashes."
        } else {
            null
        }

        return Pair(canRun, warning)
    }

    /**
     * Gets an estimate of available memory for the process.
     * This is a rough approximation - actual available memory depends on the system.
     */
    private fun getAvailableProcessMemory(): Long {
        // Try to read from /proc/meminfo for system-wide available memory
        return try {
            val memInfo = File("/proc/meminfo").readText()
            val availableKb = memInfo.lineSequence()
                .find { it.startsWith("MemAvailable:") }
                ?.split("\\s+".toRegex())
                ?.getOrNull(1)
                ?.toLongOrNull()
            if (availableKb != null) {
                // Use 80% of system available memory as process budget
                (availableKb * 1024 * 0.8).toLong()
            } else {
                // Fallback: use JVM max memory as a conservative estimate
                Runtime.getRuntime().maxMemory()
            }
        } catch (e: Exception) {
            Runtime.getRuntime().maxMemory()
        }
    }

    private companion object {
        // Safe for 4k-context code models even for Korean and source-heavy turns.
        const val MAX_HISTORY_CHARS = 5_500
    }
}
