package com.aide.mobilestudio.ai.api

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers

class AnthropicClient(
    private val apiKey: String,
    private val modelName: String = "claude-sonnet-4-20250514"
) : AIProvider {
    override val id: String = "anthropic"
    override val name: String = when {
        modelName.contains("opus") -> "Claude Opus"
        modelName.contains("sonnet") -> "Claude Sonnet"
        modelName.contains("haiku") -> "Claude Haiku"
        else -> "Claude $modelName"
    }

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?
    ): Flow<AIProvider.StreamEvent> = flow {
        val (systemPrompt, messages) = convertHistory(history)

        val body = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", 8192)
            put("stream", true)
            if (systemPrompt.isNotEmpty()) {
                put("system", systemPrompt)
            }
            put("messages", messages)
            if (tools != null && tools.length() > 0) {
                put("tools", convertToolsToAnthropic(tools))
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                connect()
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "No error details"
                val errorMsg = when (responseCode) {
                    401 -> "Invalid API key"
                    403 -> "API access denied"
                    429 -> "Rate limited - too many requests"
                    500, 529 -> "Anthropic server error"
                    else -> "HTTP $responseCode"
                }
                Log.e("AnthropicClient", "HTTP $responseCode: $errorBody")
                emit(AIProvider.StreamEvent.Error("$errorMsg\n${errorBody.take(300)}"))
                emit(AIProvider.StreamEvent.Done)
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null
            var planBuffer = StringBuilder()
            var isInPlan = false
            var currentToolName = ""
            var currentToolArgs = StringBuilder()

            while (reader.readLine()?.also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.substring(6).trim()
                if (data == "[DONE]") break

                val json = try { JSONObject(data) } catch (e: Exception) { continue }
                val type = json.optString("type", "")

                when (type) {
                    "content_block_start" -> {
                        val block = json.optJSONObject("content_block")
                        if (block?.optString("type") == "tool_use") {
                            currentToolName = block.optString("name", "")
                            currentToolArgs = StringBuilder()
                        }
                    }
                    "content_block_delta" -> {
                        val delta = json.optJSONObject("delta") ?: continue
                        val deltaType = delta.optString("type", "")

                        if (deltaType == "text_delta") {
                            var text = delta.optString("text", "")
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
                        } else if (deltaType == "input_json_delta") {
                            currentToolArgs.append(delta.optString("partial_json", ""))
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolName.isNotEmpty()) {
                            val args = try { JSONObject(currentToolArgs.toString()) } catch (e: Exception) { JSONObject() }
                            emit(AIProvider.StreamEvent.ToolCall(currentToolName, args, null))
                            currentToolName = ""
                            currentToolArgs = StringBuilder()
                        }
                    }
                    "message_stop" -> {
                        // Done
                    }
                }
            }
            emit(AIProvider.StreamEvent.Done)
        } catch (e: Exception) {
            Log.e("AnthropicClient", "Stream error", e)
            emit(AIProvider.StreamEvent.Error(e.message ?: "Unknown error"))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun convertHistory(history: JSONArray): Pair<String, JSONArray> {
        var systemPrompt = ""
        val messages = JSONArray()

        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user")
            val parts = msg.optJSONArray("parts") ?: continue

            val textParts = mutableListOf<String>()
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.has("text")) textParts.add(part.getString("text"))
            }

            if (textParts.isEmpty()) continue
            val text = textParts.joinToString("\n")

            if (i == 0 && role == "user" && text.length > 200) {
                systemPrompt = text
                continue
            }

            if (role == "user") {
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            } else if (role == "model") {
                messages.put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
            } else if (role == "user" && text.startsWith("functionResponse:")) {
                // Tool result
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            }
        }
        return systemPrompt to messages
    }

    private fun convertToolsToAnthropic(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            result.put(JSONObject().apply {
                put("name", tool.optString("name"))
                put("description", tool.optString("description"))
                if (tool.has("parameters")) {
                    put("input_schema", tool.getJSONObject("parameters"))
                }
            })
        }
        return result
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("vision", true)
    }
}
