package com.aide.mobilestudio.ai.opencode.provider

import android.util.Base64
import android.util.Log
import com.aide.mobilestudio.ai.api.AIProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OcAnthropicProvider(
    private val apiKey: String,
    private val modelId: String,
    private val model: OcModelInfo,
) : AIProvider {
    override val id: String = "anthropic/$modelId"
    override val name: String = "Anthropic ($modelId)"

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?,
    ): Flow<AIProvider.StreamEvent> = flow {
        val systemText = extractSystem(history)
        val messages = convertMessages(history)
        // Reasoning models put the thinking budget inside the request; the
        // window must leave room for it or the request is rejected outright.
        val reasonEffort = options?.optString("reasoningEffort").orEmpty()
        val thinkingBudget = if (model.reasoning && reasonEffort.isNotEmpty() && reasonEffort != "auto") {
            when (reasonEffort) {
                "minimal" -> 512
                "low" -> 1024
                "medium" -> 4096
                "high" -> 8192
                else -> null
            }
        } else {
            null
        }
        var maxTokens = options?.optInt("maxOutputTokens")
            ?: options?.optInt("maxTokens")
            ?: 8192
        if (thinkingBudget != null && maxTokens <= thinkingBudget) {
            maxTokens = thinkingBudget + 2048
        }
        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", maxTokens)
            put("stream", true)
            if (thinkingBudget != null) {
                put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", thinkingBudget))
            }
            if (!systemText.isNullOrBlank()) put("system", systemText)
            put("messages", messages)
            if (tools != null && tools.length() > 0) put("tools", convertTools(tools))
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                com.aide.mobilestudio.ai.retry.HttpErrors.applyTimeouts(this)
                doOutput = true
                connect()
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            // 4xx/5xx must be classified before inputStream is touched, or the
            // status, Retry-After and body are all lost to an IOException.
            val failure = com.aide.mobilestudio.ai.retry.HttpErrors.failureOf(conn)
            if (failure != null) {
                Log.e("OcAnthropicProvider", "HTTP ${failure.status} (request failed)")
                emit(AIProvider.StreamEvent.Error(failure.detail(), failure))
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var currentEvent = ""
            var toolName = ""
            var toolId = ""
            val toolInput = StringBuilder()
            var inTool = false
            var line: String? = null
            // First-to-last text token timing; usage is reported on message_stop.
            var anthropicFirstNs: Long? = null
            var anthropicLastNs: Long? = null
            var anthropicOutputTokens = 0

            while (reader.readLine()?.also { line = it } != null) {
                val l = line ?: continue
                if (l.startsWith("event: ")) {
                    currentEvent = l.substring(7).trim()
                    continue
                }
                if (!l.startsWith("data: ")) continue
                val json = runCatching { JSONObject(l.substring(6)) }.getOrNull() ?: continue
                when (currentEvent) {
                    "content_block_start" -> {
                        val block = json.optJSONObject("content_block") ?: continue
                        if (block.optString("type") == "tool_use") {
                            inTool = true
                            toolId = block.optString("id")
                            toolName = block.optString("name")
                            toolInput.setLength(0)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = json.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val text = delta.optString("text")
                                if (text.isNotEmpty()) {
                                    if (anthropicFirstNs == null) anthropicFirstNs = System.nanoTime()
                                    anthropicLastNs = System.nanoTime()
                                    emit(AIProvider.StreamEvent.Text(text))
                                }
                            }
                            "input_json_delta" -> toolInput.append(delta.optString("partial_json"))
                            "thinking_delta" -> {
                                val thinking = delta.optString("thinking")
                                if (thinking.isNotEmpty()) emit(AIProvider.StreamEvent.Reasoning(thinking))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        if (inTool) {
                            inTool = false
                            val input = runCatching { JSONObject(toolInput.toString()) }
                                .getOrElse { JSONObject() }
                            emit(AIProvider.StreamEvent.ToolCall(toolName, input, toolId))
                            toolName = ""
                            toolId = ""
                            toolInput.setLength(0)
                        }
                    }
                    "error" -> {
                        val err = json.optJSONObject("error")
                        val text = err?.optString("message") ?: "Anthropic API error"
                        val info = com.aide.mobilestudio.ai.retry.ApiErrorClassifier.fromMessage(text)
                        emit(AIProvider.StreamEvent.Error(info.detail(), info))
                    }
                    "message_stop" -> {
                        val usage = json.optJSONObject("message") ?: json.optJSONObject("usage")
                        if (usage != null) {
                            anthropicOutputTokens = usage.optInt("output_tokens", 0)
                        }
                    }
                }
            }
            if (anthropicFirstNs != null && anthropicOutputTokens > 0) {
                emit(
                    AIProvider.StreamEvent.Usage(
                        anthropicOutputTokens,
                        (((anthropicLastNs ?: 0) - (anthropicFirstNs ?: 0)) / 1_000_000).coerceAtLeast(0),
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
            Log.e("OcAnthropicProvider", "Stream error: ${info.kind}")
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

    private fun convertMessages(history: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until history.length()) {
            val msg = history.getJSONObject(i)
            val role = msg.optString("role")
            if (role == "system") continue
            val parts = msg.optJSONArray("parts") ?: continue
            val content = JSONArray()
            val toolResults = JSONArray()

            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                when (part.optString("type")) {
                    "text" -> {
                        val text = part.optString("text")
                        if (text.isNotEmpty()) {
                            content.put(JSONObject().put("type", "text").put("text", text))
                        }
                    }
                    "file" -> {
                        val mime = part.optString("mime")
                        val path = part.optString("path")
                        if (mime.startsWith("image/") && path.isNotEmpty()) {
                            runCatching {
                                val bytes = java.io.File(path).readBytes()
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                content.put(
                                    JSONObject().apply {
                                        put("type", "image")
                                        put(
                                            "source",
                                            JSONObject().apply {
                                                put("type", "base64")
                                                put("media_type", mime)
                                                put("data", b64)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    "tool" -> {
                        val name = part.optString("tool")
                        val state = part.optJSONObject("state") ?: continue
                        if (name.isEmpty()) continue
                        val status = state.optString("status", "completed")
                        if (role == "assistant") {
                            val input = state.optJSONObject("input") ?: JSONObject()
                            content.put(
                                JSONObject().apply {
                                    put("type", "tool_use")
                                    put("id", part.optString("callId"))
                                    put("name", name)
                                    put("input", input)
                                },
                            )
                            if (status == "completed" || status == "error") {
                                val output = state.optString("output").ifEmpty {
                                    state.optString("error", "")
                                }
                                toolResults.put(
                                    JSONObject().apply {
                                        put("type", "tool_result")
                                        put("tool_use_id", part.optString("callId"))
                                        put("content", output)
                                        if (status == "error") put("is_error", true)
                                    },
                                )
                            }
                        } else {
                            val output = state.optString("output").ifEmpty {
                                state.optString("error", "")
                            }
                            toolResults.put(
                                JSONObject().apply {
                                    put("type", "tool_result")
                                    put("tool_use_id", part.optString("callId"))
                                    put("content", output)
                                    if (status == "error") put("is_error", true)
                                },
                            )
                        }
                    }
                }
            }

            if (content.length() == 0 && toolResults.length() == 0) continue
            appendRole(out, if (role == "assistant") "assistant" else "user", content)
            if (toolResults.length() > 0) {
                appendRole(out, "user", toolResults)
            }
        }
        return out
    }

    private fun appendRole(messages: JSONArray, role: String, content: JSONArray) {
        if (content.length() == 0) return
        val last = messages.optJSONObject(messages.length() - 1)
        if (last != null && last.optString("role") == role) {
            val lastContent = last.optJSONArray("content") ?: JSONArray().also {
                last.put("content", it)
            }
            for (i in 0 until content.length()) {
                lastContent.put(content.getJSONObject(i))
            }
        } else {
            messages.put(
                JSONObject().apply {
                    put("role", role)
                    put("content", content)
                },
            )
        }
    }

    private fun convertTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            out.put(
                JSONObject().apply {
                    put("name", tool.optString("name"))
                    put("description", tool.optString("description"))
                    put(
                        "input_schema",
                        tool.optJSONObject("parameters")
                            ?: JSONObject().put("type", "object"),
                    )
                },
            )
        }
        return out
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("vision", true)
        put("reasoning", model.reasoning)
    }
}
