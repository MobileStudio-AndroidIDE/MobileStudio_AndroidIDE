package com.aide.mobilestudio.ai.opencode.provider

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.ai.api.AIProvider
import com.aide.mobilestudio.ai.agent.ModelManager
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.session.OcContextBudget
import com.aide.mobilestudio.ai.opencode.session.OcTokenEstimate
import com.aide.mobilestudio.ai.opencode.session.OcToolOutput
import com.aide.mobilestudio.ai.resource.AndroidMemoryProbe
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private fun org.json.JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}

data class OcModelRef(val providerID: String, val modelID: String) {
    override fun toString(): String = "$providerID/$modelID"

    companion object {
        fun parse(ref: String): OcModelRef? {
            val idx = ref.indexOf('/')
            if (idx <= 0 || idx >= ref.length - 1) return null
            return OcModelRef(ref.substring(0, idx), ref.substring(idx + 1))
        }
    }
}

data class OcModelInfo(
    val ref: OcModelRef,
    val name: String,
    val contextLimit: Int,
    val outputLimit: Int?,
    val toolCall: Boolean,
    val reasoning: Boolean,
    val local: Boolean,
    val ggufPath: String? = null,
    /**
     * The largest window the model itself supports, when that is known.
     *
     * Separate from [contextLimit], which is the window this app will load.
     * Null means the model never declared one - not that it has no limit.
     */
    val maxContext: Int? = null,
)

/**
 * Context windows assumed when an OpenAI-compatible endpoint does not say.
 *
 * The hosted API really does offer 128k. A local server behind a custom base
 * URL usually does not, and guessing wrong in the wide direction is silent
 * right up until the server rejects the request.
 */
private const val OPENAI_CONTEXT_DEFAULT = 128_000
private const val REMOTE_CONTEXT_DEFAULT = 64_000
private const val LOCAL_CONTEXT_DEFAULT = 8_192

/**
 * Memory left untouched when sizing a local model's KV cache.
 *
 * The cache is not the only resident thing: the weights, the Android runtime
 * and the rest of the app all share the same budget, and claiming the last
 * available byte gets the process killed mid-answer.
 */
private const val LOCAL_MEMORY_HEADROOM_BYTES = 512L * 1024 * 1024
private val PRIVATE_V4_PATTERN = Regex("""^172\.(1[6-9]|2\d|3[01])\.""")

class OcProviderRegistry(
    private val context: Context,
    private val configProvider: () -> OpencodeConfig,
    private val modelManager: ModelManager,
) {

    fun resolve(ref: String?): OcModelInfo? {
        val parsed = ref?.let { OcModelRef.parse(it) }
            ?: configProvider().model?.let { OcModelRef.parse(it) }
            ?: return null
        return getModel(parsed)
    }

    fun listProviders(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        result.add("gemini" to "Google Gemini (API)")
        result.add("openai" to "OpenAI (API)")
        result.add("anthropic" to "Anthropic (API)")
        result.add("local" to "Local Models (llama.cpp)")
        for ((name, provider) in configProvider().providers) {
            result.add(name to (provider.optString("name", name)))
        }
        return result
    }

    fun listModels(providerID: String): List<OcModelInfo> {
        return when (providerID) {
            "gemini" -> {
                val ids = linkedSetOf("gemini-1.5-flash", "gemini-2.0-flash")
                val configured = com.aide.mobilestudio.ai.AiSettings(context).geminiModel
                if (configured.isNotEmpty()) ids.add(configured)
                ids.map { id ->
                    OcModelInfo(
                        OcModelRef("gemini", id), "Gemini $id",
                        1_048_576, 8_192, true, false, false,
                    )
                }
            }
            "openai" -> {
                val settings = com.aide.mobilestudio.ai.AiSettings(context)
                val id = settings.openaiModel
                val context = settings.openaiContextLimit.takeIf { it > 0 }
                    ?: inferContextLimit(settings.openaiBaseUrl)
                // The output cap has to leave room inside the window or the
                // compaction check (estimate > context - output) sees a
                // threshold of zero and compacts every single turn - trading
                // one stall for another. Hosted OpenAI is unaffected: 8192 of
                // a 128k window already fits this rule.
                val output = minOf(settings.openaiMaxTokens, context / 4)
                listOf(
                    OcModelInfo(
                        OcModelRef("openai", id), "OpenAI $id",
                        context, output, true, false, false,
                    ),
                )
            }
            "anthropic" -> {
                val settings = com.aide.mobilestudio.ai.AiSettings(context)
                val id = settings.anthropicModel
                listOf(
                    OcModelInfo(
                        OcModelRef("anthropic", id), "Claude $id",
                        200_000, 8_192, true, false, false,
                        // Documented by the provider, so this is the model's
                        // real ceiling rather than an inferred window.
                        maxContext = 200_000,
                    ),
                )
            }
            "local" -> {
                // Read memory once for the whole list rather than per model.
                val available = runCatching { AndroidMemoryProbe.snapshot(context).availableBytes }.getOrNull()
                modelManager.listModels().map { model ->
                    // The window is chosen per model: what the file declares it
                    // can hold, what the app is configured to use, and what is
                    // left of memory for a KV cache whose cost per token is
                    // known only when the file gives up its geometry.
                    val gguf = modelManager.ggufInfo(model.path)
                    val headLength = OcContextBudget.headLength(
                        keyLength = gguf?.keyLength,
                        embeddingLength = gguf?.embeddingLength,
                        headCount = gguf?.headCount,
                    )
                    val perToken = OcContextBudget.kvBytesPerToken(
                        blockCount = gguf?.nLayer,
                        headCountKv = gguf?.headCountKv,
                        keyLength = headLength,
                        valueLength = gguf?.valueLength ?: headLength,
                    )
                    val choice = OcContextBudget.chooseLocalContext(
                        configured = model.contextLength,
                        modelMax = model.maxContext,
                        availableBytes = available,
                        kvBytesPerToken = perToken,
                        memoryHeadroomBytes = LOCAL_MEMORY_HEADROOM_BYTES,
                    )
                    OcModelInfo(
                        OcModelRef("local", model.id), model.name,
                        choice.tokens, null, true, false, true, model.path,
                        maxContext = model.maxContext,
                    )
                }
            }
            else -> {
                val provider = configProvider().providers[providerID] ?: return emptyList()
                val models = provider.optJSONObject("models") ?: return emptyList()
                val out = mutableListOf<OcModelInfo>()
                val keys = models.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val model = models.getJSONObject(id)
                    val declared = model.optJSONObject("limit")?.optInt("context", 128_000) ?: 128_000
                    out.add(
                        OcModelInfo(
                            OcModelRef(providerID, id),
                            model.optString("name", id),
                            declared,
                            model.optJSONObject("limit")?.let { if (it.has("output")) it.optInt("output") else null },
                            model.optBoolean("tool_call", true),
                            model.optBoolean("reasoning", false),
                            false,
                            // Declared by the config, so it doubles as the
                            // model's ceiling.
                            maxContext = declared,
                        ),
                    )
                }
                out
            }
        }
    }

    /**
     * Guesses the context window of an OpenAI-compatible endpoint from its
     * base URL, for when nothing explicit is configured.
     *
     * The hosted API's 128k is real, but the same client also points at
     * whatever the Base URL says, and a local server behind it - Ollama,
     * llama.cpp, LM Studio - typically runs with 4096 or 8192. Believing 128k
     * there meant the engine never compacted, shipped prompts far past what
     * the server could hold (minutes of prefill on a phone-sized model), and
     * was then refused with "Prompt exceeds context size".
     *
     * Private ranges get the local default, which errs small: a window that is
     * too narrow only compacts sooner, and a small conversation compacts
     * fast, whereas a window that is too wide is silent right up until the
     * server rejects the request.
     */
    private fun inferContextLimit(baseUrl: String): Int {
        val host = baseUrl.substringAfter("://", "")
            .substringBefore("/")
            .substringBefore(":")
            .substringBefore("@")
            .lowercase()
        if (host.isEmpty()) return REMOTE_CONTEXT_DEFAULT
        if (host == "api.openai.com") return OPENAI_CONTEXT_DEFAULT
        val isPrivate = host == "localhost" ||
            host == "127.0.0.1" ||
            host == "0.0.0.0" ||
            host.endsWith(".local") ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            PRIVATE_V4_PATTERN.containsMatchIn(host)
        return if (isPrivate) LOCAL_CONTEXT_DEFAULT else REMOTE_CONTEXT_DEFAULT
    }

    fun getModel(ref: OcModelRef): OcModelInfo? {
        for (model in listModels(ref.providerID)) {
            if (model.ref.modelID == ref.modelID) return model
        }
        return null
    }

    fun createProvider(ref: OcModelRef, runtimeOptions: JSONObject): AIProvider? {
        val model = getModel(ref) ?: return null
        return when (ref.providerID) {
            "gemini" -> {
                val apiKey = com.aide.mobilestudio.ai.AiSettings(context).apiKey ?: return null
                OcGeminiProvider(apiKey, ref.modelID, model)
            }
            "openai" -> {
                val settings = com.aide.mobilestudio.ai.AiSettings(context)
                val apiKey = settings.openaiApiKey
                if (apiKey.isNullOrEmpty()) return null
                OcOpenAiCompatibleProvider(settings.openaiBaseUrl, apiKey, ref.modelID, model)
            }
            "anthropic" -> {
                val settings = com.aide.mobilestudio.ai.AiSettings(context)
                val apiKey = settings.anthropicApiKey
                if (apiKey.isNullOrEmpty()) return null
                OcAnthropicProvider(apiKey, ref.modelID, model)
            }
            "local" -> {
                val gguf = model.ggufPath ?: return null
                val ggufInfo = modelManager.ggufInfo(gguf)
                val modelInfo = ModelManager.ModelInfo(
                    id = ref.modelID,
                    name = model.name,
                    path = gguf,
                    contextLength = model.contextLimit,
                    maxContext = model.maxContext,
                    layerCount = ggufInfo?.nLayer,
                )
                OcLocalLlamaProvider(modelInfo)
            }
            else -> {
                val provider = configProvider().providers[ref.providerID] ?: return null
                val options = provider.optJSONObject("options") ?: JSONObject()
                val baseURL = options.optString("baseURL", "")
                if (baseURL.isBlank()) return null
                val apiKey = options.optStringOrNull("apiKey")
                OcOpenAiCompatibleProvider(baseURL, apiKey, ref.modelID, model)
            }
        }
    }
}

class OcGeminiProvider(
    private val apiKey: String,
    private val modelId: String,
    private val model: OcModelInfo,
) : AIProvider {
    override val id: String = "gemini/$modelId"
    override val name: String = "Google Gemini ($modelId)"

    private val baseUrl
        get() = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse&key=$apiKey"

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?,
    ): kotlinx.coroutines.flow.Flow<AIProvider.StreamEvent> = kotlinx.coroutines.flow.flow {
        val systemText = extractSystem(history)
        val contents = JSONArray()
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.optString("role")
            if (role == "system") continue
            val parts = msg.optJSONArray("parts") ?: continue
            val gParts = JSONArray()
            val functionResponses = JSONArray()
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                when (part.optString("type")) {
                    "text" -> {
                        val text = part.optString("text")
                        if (text.isNotEmpty()) gParts.put(JSONObject().put("text", text))
                    }
                    "tool" -> {
                        val name = part.optString("tool")
                        val state = part.optJSONObject("state")
                        if (role == "assistant") {
                            val input = state?.optJSONObject("input") ?: JSONObject()
                            if (name.isNotEmpty()) {
                                gParts.put(
                                    JSONObject().put(
                                        "functionCall",
                                        JSONObject().put("name", name).put("args", input),
                                    ),
                                )
                            }
                            val status = state?.optString("status", "completed") ?: "completed"
                            val output = state?.optString("output")?.takeIf { it.isNotEmpty() }
                                ?: state?.optString("error", "")
                                ?: ""
                            if (name.isNotEmpty() && state != null) {
                                functionResponses.put(
                                    JSONObject().put(
                                        "functionResponse",
                                        JSONObject().put("name", name)
                                            .put(
                                                "response",
                                                JSONObject().put("content", output)
                                                    .put("status", status),
                                            ),
                                    ),
                                )
                            }
                        } else {
                            val output = state?.optString("output", state.optString("error", "")) ?: ""
                            if (name.isNotEmpty()) {
                                functionResponses.put(
                                    JSONObject().put(
                                        "functionResponse",
                                        JSONObject().put("name", name)
                                            .put("response", JSONObject().put("content", output)),
                                    ),
                                )
                            }
                        }
                    }
                    "file" -> {
                        val data = part.optString("data")
                        if (data.isNotEmpty()) {
                            gParts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject()
                                        .put("mimeType", part.optString("mime", "image/jpeg"))
                                        .put("data", data),
                                ),
                            )
                        } else {
                            gParts.put(
                                JSONObject().put(
                                    "text",
                                    "[Attached ${part.optString("mime")}: ${part.optString("filename", "file")}]",
                                ),
                            )
                        }
                    }
                    else -> {}
                }
            }
            if (gParts.length() == 0 && functionResponses.length() == 0) continue
            if (gParts.length() > 0) {
                contents.put(
                    JSONObject().put("role", if (role == "assistant") "model" else "user")
                        .put("parts", gParts),
                )
            }
            if (functionResponses.length() > 0) {
                contents.put(JSONObject().put("role", "user").put("parts", functionResponses))
            }
        }
        // Gemini 2.5+ takes a thinking budget; older models reject the field
        // with a 400, so it is only sent where it is understood.
        val reasonEffort = options?.optString("reasoningEffort").orEmpty()
        val thinkingBudget = if (reasonEffort.isNotEmpty() && reasonEffort != "auto" &&
            (modelId.contains("2.5") || modelId.contains("thinking"))
        ) {
            when (reasonEffort) {
                "minimal" -> 0
                "low" -> 1024
                "medium" -> 8192
                "high" -> 24576
                else -> null
            }
        } else {
            null
        }
        val body = JSONObject().apply {
            if (systemText != null) put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText))))
            put("contents", contents)
            if (tools != null && tools.length() > 0) {
                put("tools", JSONArray().put(JSONObject().put("function_declarations", tools)))
            }
            if (thinkingBudget != null) {
                put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingBudget", thinkingBudget)),
                )
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                com.aide.mobilestudio.ai.retry.HttpErrors.applyTimeouts(this)
                doOutput = true
                connect()
            }

            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            // Classify 4xx/5xx before touching inputStream: the key sits in this
            // URL, and letting the IOException carry it would leak it into logs.
            val failure = com.aide.mobilestudio.ai.retry.HttpErrors.failureOf(conn)
            if (failure != null) {
                Log.e("OcGeminiProvider", "HTTP ${failure.status} (request failed)")
                emit(AIProvider.StreamEvent.Error(failure.detail(), failure))
                return@flow
            }

            var line: String? = null
            var planBuffer = StringBuilder()
            var isInPlan = false

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            while (reader.readLine()?.also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.substring(6)
                    if (data == "[DONE]") break
                    val json = JSONObject(data)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val candidate = candidates.getJSONObject(0)
                        val content = candidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val text = part.optString("text")
                                if (text.isNotEmpty()) {
                                    var remainingText = text
                                    if (!isInPlan && remainingText.contains("<plan>")) {
                                        val split = remainingText.split("<plan>", limit = 2)
                                        if (split[0].isNotEmpty()) emit(AIProvider.StreamEvent.Text(split[0]))
                                        isInPlan = true
                                        remainingText = split[1]
                                    }
                                    if (isInPlan) {
                                        if (remainingText.contains("</plan>")) {
                                            val split = remainingText.split("</plan>", limit = 2)
                                            planBuffer.append(split[0])
                                            emit(AIProvider.StreamEvent.Plan(planBuffer.toString()))
                                            isInPlan = false
                                            planBuffer = StringBuilder()
                                            if (split[1].isNotEmpty()) emit(AIProvider.StreamEvent.Text(split[1]))
                                        } else {
                                            planBuffer.append(remainingText)
                                        }
                                    } else {
                                        emit(AIProvider.StreamEvent.Text(remainingText))
                                    }
                                }
                                if (part.optBoolean("thought", false) && text.isNotEmpty()) {
                                    emit(AIProvider.StreamEvent.Reasoning(text))
                                }
                                val toolCall = part.optJSONObject("functionCall")
                                if (toolCall != null) {
                                    emit(
                                        AIProvider.StreamEvent.ToolCall(
                                            toolCall.getString("name"),
                                            toolCall.optJSONObject("args") ?: JSONObject(),
                                            null,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Usage reported after generation completes; Gemini does not
            // return usage inside the SSE stream, so this stays empty here
            // unless a future API version adds it.
            emit(AIProvider.StreamEvent.Usage(0, null))
            emit(AIProvider.StreamEvent.Done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val info = com.aide.mobilestudio.ai.retry.ApiErrorClassifier.fromThrowable(e, requestSent = true)
            Log.e("OcGeminiProvider", "Stream error: ${info.kind}")
            emit(AIProvider.StreamEvent.Error(info.detail(), info))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    private fun extractSystem(history: JSONArray): String? {
        var system: String? = null
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            if (msg.optString("role") != "system") continue
            val parts = msg.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                if (part.optString("type") == "text") {
                    system = ((system ?: "") + part.optString("text")).ifEmpty { null }
                }
            }
        }
        return system
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
    }
}

class OcOpenAiCompatibleProvider(
    private val baseURL: String,
    private val apiKey: String?,
    private val modelId: String,
    private val model: OcModelInfo,
) : AIProvider {
    override val id: String = "openai-compatible/$modelId"
    override val name: String = "OpenAI Compatible ($modelId)"

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?,
    ): kotlinx.coroutines.flow.Flow<AIProvider.StreamEvent> = kotlinx.coroutines.flow.flow {
        val reasonEffort = options?.optString("reasoningEffort").orEmpty()
        val body = JSONObject().apply {
            put("model", modelId)
            put("messages", convertMessages(history))
            put("stream", true)
            // Only reasoning models take the parameter; sending it to the rest
            // is a 400 that reads like the app broke.
            if (model.reasoning && reasonEffort.isNotEmpty() && reasonEffort != "auto") {
                put("reasoning_effort", reasonEffort)
            }
            if (tools != null && tools.length() > 0) {
                put("tools", JSONArray().apply {
                    for (i in 0 until tools.length()) {
                        val tool = tools.getJSONObject(i)
                        put(JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tool.optString("name"))
                                put("description", tool.optString("description"))
                                put("parameters", tool.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                            })
                        })
                    }
                })
            }
        }

        var conn: HttpURLConnection? = null
        try {
            val urlStr = baseURL.trimEnd('/') + "/chat/completions"
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                if (apiKey != null) setRequestProperty("Authorization", "Bearer $apiKey")
                com.aide.mobilestudio.ai.retry.HttpErrors.applyTimeouts(this)
                doOutput = true
                connect()
            }

            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val failure = com.aide.mobilestudio.ai.retry.HttpErrors.failureOf(conn)
            if (failure != null) {
                Log.e("OcOpenAiCompatible", "HTTP ${failure.status} (request failed)")
                emit(AIProvider.StreamEvent.Error(failure.detail(), failure))
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))

            // First-to-last token timing and real usage, when the server reports it.
            var remoteFirstNs: Long? = null
            var remoteLastNs: Long? = null
            var remoteOutputTokens = 0

            val toolCalls = mutableMapOf<Int, Pair<String, StringBuilder>>()
            var line: String? = null
            while (reader.readLine()?.also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.substring(6).trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val choices = json.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                val usage = json.optJSONObject("usage")
                if (usage != null) {
                    // Some servers emit usage on each delta; the last one wins.
                    remoteOutputTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0))
                }
                val reasoning = delta.optStringOrNull("reasoning_content")
                    ?: delta.optStringOrNull("reasoning")
                if (!reasoning.isNullOrEmpty()) emit(AIProvider.StreamEvent.Reasoning(reasoning))
                val content = delta.optStringOrNull("content")
                if (!content.isNullOrEmpty()) {
                    if (remoteFirstNs == null) remoteFirstNs = System.nanoTime()
                    remoteLastNs = System.nanoTime()
                    emit(AIProvider.StreamEvent.Text(content))
                }
                val toolCallsArr = delta.optJSONArray("tool_calls")
                if (toolCallsArr != null) {
                    for (i in 0 until toolCallsArr.length()) {
                        val tc = toolCallsArr.getJSONObject(i)
                        val index = tc.optInt("index", 0)
                        val id = tc.optStringOrNull("id")
                        val fn = tc.optJSONObject("function") ?: continue
                        val name = fn.optStringOrNull("name") ?: ""
                        val args = fn.optStringOrNull("arguments") ?: ""
                        val existing = toolCalls[index]
                        if (existing == null) {
                            toolCalls[index] = Pair(name, StringBuilder(args))
                            if (id != null) toolCallIds[index] = id
                        } else {
                            val mergedName = if (name.isNotEmpty()) name else existing.first
                            toolCalls[index] = Pair(mergedName, existing.second.append(args))
                        }
                    }
                }
            }
            for ((index, tc) in toolCalls.toSortedMap()) {
                val argsJson = runCatching { JSONObject(tc.second.toString()) }.getOrDefault(JSONObject())
                emit(AIProvider.StreamEvent.ToolCall(tc.first, argsJson, toolCallIds[index]))
            }
            if (remoteFirstNs != null && remoteOutputTokens > 0) {
                emit(
                    AIProvider.StreamEvent.Usage(
                        remoteOutputTokens,
                        (((remoteLastNs ?: 0) - (remoteFirstNs ?: 0)) / 1_000_000).coerceAtLeast(0),
                    ),
                )
            } else {
                emit(AIProvider.StreamEvent.Usage(0, null))
            }
            emit(AIProvider.StreamEvent.Done)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val info = com.aide.mobilestudio.ai.retry.ApiErrorClassifier.fromThrowable(e, requestSent = true)
            Log.e("OcOpenAiCompatible", "Stream error: ${info.kind}")
            emit(AIProvider.StreamEvent.Error(info.detail(), info))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    /**
     * Turns an HTTP status + response body into a message the user can act on.
     * NVIDIA NIM (and most OpenAI-compatible servers) reply with
     * `{"error":{"message":...}}` or `{"detail":...}`, so surface that instead
     * of an opaque "Cannot read from the 401 stream".
     */
    private fun httpErrorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optJSONObject("error")?.optString("detail")
                ?: json.optString("detail")
                ?: json.optString("message")
                ?: json.optJSONArray("errors")?.optJSONObject(0)?.optString("message")
        }.getOrNull().orEmpty().ifBlank { body.trim().take(300) }

        val hint = when (code) {
            401 -> "Invalid or missing API key"
            403 -> "API access denied for this key/model"
            404 -> "Model or endpoint not found"
            422 -> "Request rejected (unsupported parameter or model)"
            429 -> "Rate limited - too many requests"
            in 500..599 -> "Provider server error"
            else -> "HTTP $code"
        }
        return if (detail.isNotBlank()) "$hint ($code)\n$detail" else "$hint"
    }

    private val toolCallIds = mutableMapOf<Int, String>()

    private fun convertMessages(history: JSONArray): JSONArray {
        val messages = JSONArray()
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.optString("role")
            val parts = msg.optJSONArray("parts") ?: continue
            if (parts.length() == 0) continue
            if (role == "system") {
                val systemContent = StringBuilder()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    if (part.optString("type") == "text") {
                        if (systemContent.isNotEmpty()) systemContent.append("\n")
                        systemContent.append(part.optString("text"))
                    }
                }
                if (systemContent.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "system").put("content", systemContent.toString()))
                }
                continue
            }
            if (role == "assistant") {
                val content = StringBuilder()
                val toolCallsArr = JSONArray()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    when (part.optString("type")) {
                        "text" -> if (!part.optBoolean("ignored") && part.optString("text").isNotEmpty()) {
                            if (content.isNotEmpty()) content.append("\n")
                            content.append(part.optString("text"))
                        }
                        "tool" -> {
                            val state = part.optJSONObject("state") ?: JSONObject()
                            val input = state.optJSONObject("input") ?: JSONObject()
                            toolCallsArr.put(JSONObject().apply {
                                put("id", part.optString("callId"))
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", part.optString("tool"))
                                    put("arguments", input.toString())
                                })
                            })
                        }
                    }
                }
                val msgOut = JSONObject().apply {
                    put("role", "assistant")
                    if (content.isNotEmpty()) put("content", content.toString())
                    if (toolCallsArr.length() > 0) put("tool_calls", toolCallsArr)
                }
                messages.put(msgOut)
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    if (part.optString("type") != "tool") continue
                    val state = part.optJSONObject("state") ?: JSONObject()
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", part.optString("callId"))
                        put("content", state.optString("output"))
                    })
                }
            } else {
                val content = StringBuilder()
                for (j in 0 until parts.length()) {
                    val part = parts.getJSONObject(j)
                    when (part.optString("type")) {
                        "text" -> if (!part.optBoolean("ignored") && part.optString("text").isNotEmpty()) {
                            if (content.isNotEmpty()) content.append("\n")
                            content.append(part.optString("text"))
                        }
                        "file" -> content.append("[Attached ").append(part.optString("mime")).append(": ").append(part.optString("filename", "file")).append("]")
                    }
                }
                if (content.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "user").put("content", content.toString()))
                }
            }
        }
        return messages
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", model.toolCall)
    }
}

class OcLocalLlamaProvider(private val model: ModelManager.ModelInfo) : AIProvider {
    override val id: String = "local/" + model.id
    override val name: String = "Local: " + model.name

    private val llama = com.aide.mobilestudio.ai.api.LlamaAndroid()
    private var modelHandle: Long = 0L
    private var activeBridge: com.aide.mobilestudio.ai.api.LlamaRuntimeBridge? = null

    companion object {
        private val TOOL_OPEN = "\uD83E\uDD16"
        private val TOOL_CLOSE = "\uE003"
    }

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?,
    ): kotlinx.coroutines.flow.Flow<AIProvider.StreamEvent> = kotlinx.coroutines.flow.callbackFlow {
        if (modelHandle == 0L) {
            // Negative = every layer the model has (llama.h); the runtime clamps
            // it to the real count. 0 = CPU only.
            val nGpu = options?.optInt("nGpuLayers", -1) ?: -1
            val fallbackThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val nThreads = options?.optInt("nThreads", fallbackThreads) ?: fallbackThreads
            val backendName = options?.optString("backend", "AUTO") ?: "AUTO"
            val runtimeManager = com.aide.mobilestudio.ai.LlamaRuntimeManager.get()
            val runtimeAvailable = com.aide.mobilestudio.ai.api.LlamaRuntimeBridge.isBridgeAvailable() &&
                runtimeManager.isInstalled()
            if (!runtimeAvailable && !com.aide.mobilestudio.ai.api.LlamaAndroid.isNativeAvailable()) {
                Log.e("OcLocalLlamaProvider", "No AI runtime installed and legacy lib unavailable")
                trySend(
                    AIProvider.StreamEvent.Error(
                        "AI runtime is not installed.\nInstall it from AI Settings (llama.cpp runtime download).",
                    ),
                )
                close()
                return@callbackFlow
            }
            Log.i("OcLocalLlamaProvider", "Loading with $nGpu layers, $nThreads threads, runtime=$runtimeAvailable")
            val loaded = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (runtimeAvailable) {
                        val bridge = com.aide.mobilestudio.ai.api.LlamaRuntimeBridge()
                        if (runtimeManager.ensureNativeDeps() == null) {
                            Log.w("OcLocalLlamaProvider", "libc++_shared.so could not be staged")
                        }
                        if (!bridge.isRuntimeLoaded() &&
                            !bridge.loadRuntime(
                                runtimeManager.runtimeLibDir().absolutePath,
                                runtimeManager.appNativeLibDir(),
                            )
                        ) {
                            throw IllegalStateException(bridge.lastError() ?: "runtime load failed")
                        }
                        val vulkan = bridge.isVulkanAvailable()
                        val ctx = (model.contextLimit ?: model.contextLength).coerceIn(512, OcContextBudget.LOCAL_CONTEXT_CEILING)
                        Log.i(
                            "OcLocalLlamaProvider",
                            "Loading model at ${model.path} (ctx=$ctx, requestedGpuLayers=" +
                                "${if (nGpu < 0) "all" else nGpu}, " +
                                "modelLayers=${model.layerCount ?: "unknown"}, threads=$nThreads, vulkan=$vulkan)",
                        )
                        val result = bridge.loadModelWithFallback(
                            model.path, ctx, nGpu, nThreads, vulkan, model.layerCount ?: 0,
                        )
                        activeBridge = bridge
                        // The layers llama.cpp actually placed on the GPU are
                        // logged by the runtime itself ("offloaded N/M layers to
                        // GPU"). Repeating the request here as if it were the
                        // outcome is how a CPU-only load looked like a GPU one.
                        Log.i(
                            "OcLocalLlamaProvider",
                            "Model loaded: handle=${result.handle}, backend=${result.backend}, " +
                                "requestedGpuLayers=${result.requestedGpuLayers}",
                        )
                        result.handle
                    } else {
                        if (!com.aide.mobilestudio.ai.api.LlamaAndroid.isNativeAvailable()) {
                            throw UnsatisfiedLinkError(
                                com.aide.mobilestudio.ai.api.LlamaAndroid.getLoadError() ?: "libllama_android.so unavailable",
                            )
                        }
                        Log.i("OcLocalLlamaProvider", "Loading model at ${model.path} (legacy, ctx=${model.contextLimit}, threads=$nThreads, backend=$backendName)")
                        llama.loadModel(model.path, (model.contextLimit ?: model.contextLength).coerceIn(512, 2048), 0, nThreads, backendName)
                    }
                }
            }
            modelHandle = loaded.getOrElse { e ->
                Log.e("OcLocalLlamaProvider", "Model load failed: ${e.message}", e)
                activeBridge = null
                trySend(
                    AIProvider.StreamEvent.Error(
                        "Failed to load model: ${e.message}\nInstall or reinstall the AI runtime from AI Settings.",
                    ),
                )
                close()
                return@callbackFlow
            }
            if (modelHandle == 0L) {
                trySend(AIProvider.StreamEvent.Error("Failed to load model at " + model.path))
                close()
                return@callbackFlow
            }
        }

        val prompt = buildPrompt(history, tools)
        // Tool calls are structured output: the 0.7 chat default made a 1.5B
        // diverge from the JSON shape often enough that it looked like it
        // "could not use tools". Lower entropy first, conversation second.
        val temp = options?.optDouble("temperature", 0.2)?.toFloat() ?: 0.2f

        var planBuffer = StringBuilder()
        var isInPlan = false
        var toolCallBuffer = StringBuilder()
        var isInToolCall = false
        // What the reply said in prose, and whether any call came out of it.
        // A model that answers with the call alone - no wire markers - is
        // recovered from these once the stream ends.
        var emittedToolCall = false
        val streamedText = StringBuilder()
        // Exact tokens and decode time (first token → last token, not prefill).
        var nativeTokenCount = 0
        var firstTokenNs: Long? = null
        var lastTokenNs: Long? = null

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = generate(prompt, temp) { token ->
                if (firstTokenNs == null) firstTokenNs = System.nanoTime()
                nativeTokenCount++
                var current = token
                if (!isInPlan && current.contains("<plan>")) {
                    val split = current.split("<plan>", limit = 2)
                    if (split[0].isNotEmpty()) trySend(AIProvider.StreamEvent.Text(split[0]))
                    isInPlan = true
                    current = split[1]
                }
                if (isInPlan) {
                    if (current.contains("</plan>")) {
                        val split = current.split("</plan>", limit = 2)
                        planBuffer.append(split[0])
                        trySend(AIProvider.StreamEvent.Plan(planBuffer.toString()))
                        isInPlan = false
                        planBuffer = StringBuilder()
                        if (split[1].isNotEmpty()) trySend(AIProvider.StreamEvent.Text(split[1]))
                    } else {
                        planBuffer.append(current)
                    }
                    return@generate true
                }
                if (!isInToolCall && current.contains(TOOL_OPEN)) {
                    isInToolCall = true
                    current = current.substringAfter(TOOL_OPEN)
                }
                if (isInToolCall) {
                    if (current.contains(TOOL_CLOSE)) {
                        toolCallBuffer.append(current.substringBefore(TOOL_CLOSE))
                        val json = parseToolCallJson(toolCallBuffer.toString())
                        if (json != null) {
                            emittedToolCall = true
                            trySend(
                                AIProvider.StreamEvent.ToolCall(
                                    json.optString("name"),
                                    json.optJSONObject("arguments") ?: JSONObject(),
                                ),
                            )
                        }
                        isInToolCall = false
                        toolCallBuffer = StringBuilder()
                    } else {
                        toolCallBuffer.append(current)
                    }
                    return@generate true
                }
                streamedText.append(current)
                trySend(AIProvider.StreamEvent.Text(current))
                true
            }
            if (isInToolCall && toolCallBuffer.isNotEmpty()) {
                // Small models often stop right after the JSON without the
                // closing marker. The buffer used to be dropped here: the
                // turn recorded an empty message and the user saw nothing.
                val raw = toolCallBuffer.toString()
                val json = parseToolCallJson(raw)
                if (json != null) {
                    emittedToolCall = true
                    Log.w("OcLocalLlamaProvider", "Recovered tool call with no closing marker: " + raw.take(200))
                    trySend(
                        AIProvider.StreamEvent.ToolCall(
                            json.optString("name"),
                            json.optJSONObject("arguments") ?: JSONObject(),
                        ),
                    )
                } else {
                    // Started like a call but is not one: show it instead of
                    // silently dropping the whole turn.
                    trySend(AIProvider.StreamEvent.Text(TOOL_OPEN + raw))
                }
                isInToolCall = false
                toolCallBuffer = StringBuilder()
            }
            if (!emittedToolCall) {
                // Some local models never use the wire markers: they answer
                // with the call as a fenced ```json block, or as the bare
                // object. That reply used to stay on screen as text while
                // nothing ran - the user asked for `ls` and got "bash ls".
                val markerless = parseMarkerlessToolCall(streamedText.toString())
                if (markerless != null) {
                    Log.w(
                        "OcLocalLlamaProvider",
                        "Recovered markerless tool call: " + markerless.toString().take(200),
                    )
                    trySend(
                        AIProvider.StreamEvent.ToolCall(
                            markerless.optString("name"),
                            markerless.optJSONObject("arguments") ?: JSONObject(),
                        ),
                    )
                }
            }
            if (result != null && result.startsWith("Error")) {
                trySend(AIProvider.StreamEvent.Error(result))
            }
        }
        lastTokenNs = System.nanoTime()
        if (nativeTokenCount > 0) {
            trySend(
                AIProvider.StreamEvent.Usage(
                    nativeTokenCount,
                    (((lastTokenNs ?: 0) - (firstTokenNs ?: 0)) / 1_000_000).coerceAtLeast(0),
                ),
            )
        }
        trySend(AIProvider.StreamEvent.Done)
        close()
        // Free the native session the moment this request's stream ends. The
        // engine builds a fresh provider per call, and nothing else ever called
        // unload(), so every request left a loaded model - over a gigabyte of
        // native memory - behind: the app's memory only ever grew while the
        // user chatted. The model is reloaded on the next call, which is what
        // already happened anyway.
        awaitClose { unload() }
    }

    private fun buildPrompt(history: JSONArray, tools: JSONArray?): String {
        val window = model.contextLength
            .coerceIn(OcContextBudget.MIN_CONTEXT, OcContextBudget.LOCAL_CONTEXT_CEILING)
        val reserve = OcContextBudget.outputReserve(window, null, null)
        val toolResultChars = OcContextBudget.toolResultChars(window, reserve)

        val head = StringBuilder()
        head.append("<|im_start|>system\n")
        var first = true
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            if (msg.optString("role") == "system" && first) {
                val parts = msg.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    head.append(parts.getJSONObject(0).optString("text"))
                    first = false
                }
            }
        }
        if (first) head.append("You are a professional AI Coding Agent. You have access to tools.\n")
        if (tools != null && tools.length() > 0) {
            // Compact, not indented. These schemas are fixed overhead that
            // every single request pays for, and pretty-printing them roughly
            // doubles what a 4096-token window has to spend.
            head.append("\nAvailable tools: ").append(tools.toString()).append("\n")
            // Lazily-described tools carry an `arguments` summary instead of a
            // full schema; the full one is one tool_help call away. Without
            // this line a small model sees names it cannot spell the arguments
            // for and invents them.
            val lazySchemas = (0 until tools.length()).any { i ->
                tools.optJSONObject(i)?.has("arguments") == true
            }
            if (lazySchemas) {
                head.append("Some tools list argument names only. For a tool's full parameters reply with ")
                    .append(TOOL_OPEN)
                    .append("{\"name\": \"tool_help\", \"arguments\": {\"tool\": \"<tool name>\"}}")
                    .append(TOOL_CLOSE).append("\n")
            }
            head.append("To call a tool, use: ").append(TOOL_OPEN)
                .append("{\"name\": \"...\", \"arguments\": {...}}").append(TOOL_CLOSE).append("\n")
            // Policy text alone was not enough for small local models: asked
            // to create or run something they answered with a tutorial.
            // Concrete calls in the exact wire shape give them something to
            // copy, which is the cheapest lever on a 1B model.
            head.append("When the user asks for a change, a command or a check, reply with a tool call only.\n")
            head.append("Example - user says 'look at app.js': reply ").append(TOOL_OPEN)
                .append("{\"name\": \"read\", \"arguments\": {\"filePath\": \"app.js\"}}")
                .append(TOOL_CLOSE).append("\n")
            head.append("Example - user says 'create hello.js that prints hi': reply ").append(TOOL_OPEN)
                .append("{\"name\": \"write\", \"arguments\": {\"filePath\": \"hello.js\", \"content\": \"console.log('hi')\"}}")
                .append(TOOL_CLOSE).append("\n")
            head.append("Example - user says 'run the project tests': reply ").append(TOOL_OPEN)
                .append("{\"name\": \"bash\", \"arguments\": {\"command\": \"npm test\"}}")
                .append(TOOL_CLOSE).append("\n")
            // The question tool is the one call a user explicitly asks for
            // ("ask me which ..."), and a small model would answer in prose
            // instead - so no options ever appeared. The nested shape is shown
            // once, in full, because that is exactly what it cannot guess.
            head.append("Example - user says 'ask me which framework to use': reply ").append(TOOL_OPEN)
                .append("{\"name\": \"question\", \"arguments\": {\"questions\": [{\"question\": \"Which framework should I use?\", \"header\": \"Framework\", \"options\": [{\"label\": \"Compose\"}, {\"label\": \"XML views\"}]}]}}")
                .append(TOOL_CLOSE).append("\n")
        }
        head.append("<|im_end|>\n")

        val turns = ArrayList<String>()
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.optString("role")
            if (role == "system") continue
            val parts = msg.optJSONArray("parts") ?: continue
            if (parts.length() == 0) continue
            val content = StringBuilder()
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                when (part.optString("type")) {
                    "text" -> if (!part.optBoolean("ignored")) {
                        if (content.isNotEmpty()) content.append("\n")
                        content.append(part.optString("text"))
                    }
                    "tool" -> {
                        val state = part.optJSONObject("state") ?: JSONObject()
                        val status = state.optString("status")
                        val input = state.optJSONObject("input") ?: JSONObject()
                        content.append(TOOL_OPEN)
                            .append("{\"name\": \"").append(part.optString("tool"))
                            .append("\", \"arguments\": ").append(input.toString()).append("}").append(TOOL_CLOSE)
                        if (status == "completed") {
                            // A build or a search can return thousands of
                            // lines. The stored transcript keeps all of it -
                            // this only bounds what the next request carries.
                            content.append("\nTool result: ")
                                .append(OcToolOutput.trim(state.optString("output"), toolResultChars))
                        } else if (status == "error") {
                            content.append("\nTool error: ").append(state.optString("error"))
                        }
                    }
                }
            }
            if (content.isEmpty()) continue
            val displayRole = if (role == "assistant") "assistant" else "user"
            turns.add("<|im_start|>" + displayRole + "\n" + content + "<|im_end|>\n")
        }

        // Keep the newest turns and drop the oldest whole ones, so the model
        // never sees a tool call without its result. The whole history used to
        // go in regardless of size, which is what pushed the prompt past the
        // window before the model could answer a single message. Costs are
        // estimated per turn, not counted in characters, because Korean spends
        // several times the tokens English does for the same length.
        val usableInput = (window - reserve).coerceAtLeast(0)
        val budget = usableInput - OcTokenEstimate.tokens(head)
        val kept = ArrayList<String>()
        var used = 0
        for (i in turns.indices.reversed()) {
            val turn = turns[i]
            val cost = OcTokenEstimate.tokens(turn)
            if (used + cost > budget) {
                if (kept.isEmpty() && budget > 0) kept.add(clipToTokenBudget(turn, budget))
                break
            }
            kept.add(turn)
            used += cost
        }

        val sb = StringBuilder(head)
        for (i in kept.indices.reversed()) sb.append(kept[i])
        sb.append("<|im_start|>assistant\n")
        val prompt = sb.toString()
        Log.i(
            "OcLocalLlamaProvider",
            "Prompt " + OcContextBudget.of(
                contextTokens = window,
                modelMaxTokens = model.maxContext,
                declaredOutput = null,
                configuredReserve = null,
                systemText = head.toString(),
                toolsJson = tools?.toString() ?: "",
                historyText = kept.joinToString(""),
            ).describe() + " chars=${prompt.length}",
        )
        return prompt
    }

    /**
     * Keeps the end of a turn that will not fit as a whole. The request the
     * user just made sits at the end, so that is the part worth keeping.
     */
    private fun clipToTokenBudget(text: String, tokens: Int): String {
        if (tokens <= 0) return ""
        var chars = tokens * 4
        if (chars >= text.length) return text
        var tail = text.takeLast(chars)
        while (tail.isNotEmpty() && OcTokenEstimate.tokens(tail) > tokens) {
            chars = chars * 3 / 4
            if (chars <= 0) return ""
            tail = text.takeLast(chars)
        }
        return tail
    }

    /**
     * A small model can wrap its call in a fence, leave trailing text, or
     * truncate the JSON. Try the text as-is and then the outermost {...} it
     * contains, and log what could not be read: a dropped call used to look
     * exactly like the model refusing to act, with nothing in the log.
     */
    private fun parseToolCallJson(raw: String): JSONObject? {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val candidates = mutableListOf(trimmed)
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) candidates.add(trimmed.substring(start, end + 1))
        for (candidate in candidates) {
            val json = runCatching { JSONObject(candidate) }.getOrNull()
            if (json != null && json.optString("name").isNotEmpty()) return json
        }
        Log.w("OcLocalLlamaProvider", "Unreadable tool call: " + raw.take(200))
        return null
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("local", true)
    }

    private fun generate(prompt: String, temp: Float, onToken: (String) -> Boolean): String {
        return try {
            val bridge = activeBridge
            val stops = arrayOf("</s>", "</plan>")
            if (bridge != null) {
                bridge.generate(modelHandle, prompt, temp, 0.9f, 40, 1.1f, -1, stops, onToken)
            } else {
                llama.generate(modelHandle, prompt, temp, 0.9f, 40, 1.1f, -1, stops, onToken)
            }
        } catch (e: Throwable) {
            Log.e("OcLocalLlamaProvider", "AI generation failed: ${e.message}", e)
            "Error: AI generation failed: ${e.message}"
        }
    }

    fun unload() {
        if (modelHandle != 0L) {
            val bridge = activeBridge
            try {
                if (bridge != null) bridge.unload() else llama.unloadModel(modelHandle)
            } catch (e: Throwable) {
                Log.e("OcLocalLlamaProvider", "unload failed: ${e.message}")
            }
            modelHandle = 0L
            activeBridge = null
        }
    }
}

/**
 * Recognizes a call that arrives without the wire markers.
 *
 * Observed from the local model: asked to run `ls` it answered with a
 * ```json fenced object, or with the bare {"name": ..., "arguments": ...} -
 * never the 🤖 form. That reply stayed on screen as prose while nothing
 * ran. Only a reply that is *nothing but* the call is accepted: a tutorial
 * quoting a call in passing has a second fence (or prose around the
 * object) and stays prose, because obeying every JSON snippet a model
 * explains would run work nobody asked for.
 *
 * Top-level and Android-free so the shapes a small model actually emits can
 * be tested without loading a native runtime.
 */
internal fun parseMarkerlessToolCall(text: String): JSONObject? {
    var trimmed = text.trim()
    // Hermes/Qwen templates wrap calls in <tool_call>…</tool_call>. A
    // small model reaches for the shape it saw in training even when the
    // prompt shows another, so the tags are accepted as a wrapper here.
    val tagged = Regex("^<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>$", RegexOption.IGNORE_CASE)
        .matchEntire(trimmed)
    if (tagged != null) trimmed = tagged.groupValues[1].trim()
    if (trimmed.isEmpty()) return null
    val body = if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
        // Exactly one fenced block: more backticks means the reply embedded
        // the call in a longer answer.
        if (trimmed.count { it == '`' } != 6) return null
        val inner = trimmed.removePrefix("```").removeSuffix("```").trim()
        // Drop a language tag ("json") before the object when present.
        if (inner.startsWith("{")) inner else inner.substringAfter('\n', "").trim()
    } else {
        trimmed
    }
    if (!body.startsWith("{") || !body.endsWith("}")) return null
    val json = runCatching { JSONObject(body) }.getOrNull()
        ?: repairLooseCall(body)
        ?: return null
    // OpenAI/Hermes variants nest the call under "function" and sometimes
    // send arguments as a JSON string. Accept those spellings instead of
    // dropping a call the model clearly intended.
    json.optJSONObject("function")?.let { function ->
        if (json.optString("name").isEmpty()) {
            val name = function.optString("name")
            if (name.isNotEmpty()) json.put("name", name)
        }
        if (!json.has("arguments")) function.opt("arguments")?.let { json.put("arguments", it) }
    }
    if (!json.has("arguments")) {
        (json.optJSONObject("parameters") ?: json.optJSONObject("input"))
            ?.let { json.put("arguments", it) }
    }
    val rawArgs = json.opt("arguments")
    if (rawArgs is String) {
        runCatching { JSONObject(rawArgs) }.getOrNull()?.let { json.put("arguments", it) }
    }
    if (json.optString("name").isEmpty() || json.optJSONObject("arguments") == null) return null
    return json
}

/**
 * One more shape the local model produces: {:name="write", "arguments":
 * {...}} - a dropped quote, and '=' where ':' belongs. Seen on device right
 * after a correct ```json answer, so it is a formatting slip rather than a
 * different intent. Only that pattern is repaired, and only inside a reply
 * that already looked like a lone call.
 */
private fun repairLooseCall(body: String): JSONObject? {
    if (!body.contains('=')) return null
    val repaired = body
        .replaceFirst(Regex("""\{\s*:?\s*"?name"?\s*[:=]\s*"""), "{\"name\": ")
        .replaceFirst(Regex("""([,{])\s*"?arguments"?\s*[:=]\s*"""), "${'$'}1\"arguments\": ")
    if (repaired == body) return null
    return runCatching { JSONObject(repaired) }.getOrNull()
}
