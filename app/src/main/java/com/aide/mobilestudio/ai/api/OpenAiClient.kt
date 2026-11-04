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

class OpenAiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val modelName: String = "gpt-4o"
) : AIProvider {
    override val id: String = "openai"
    override val name: String = when {
        modelName.startsWith("gpt-4o") -> "OpenAI GPT-4o"
        modelName.startsWith("gpt-4") -> "OpenAI GPT-4"
        modelName.startsWith("gpt-3") -> "OpenAI GPT-3.5"
        else -> "OpenAI $modelName"
    }

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?
    ): Flow<AIProvider.StreamEvent> = flow {
        val messages = convertHistory(history)

        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("stream", true)
            put("temperature", 0.7)
            put("max_tokens", options?.optInt("maxTokens", 8192) ?: 8192)
            if (tools != null && tools.length() > 0) {
                put("tools", convertToolsToOpenAi(tools))
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
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
                    500, 502, 503 -> "OpenAI server error"
                    else -> "HTTP $responseCode"
                }
                Log.e("OpenAiClient", "HTTP $responseCode: $errorBody")
                emit(AIProvider.StreamEvent.Error("$errorMsg\n${errorBody.take(300)}"))
                emit(AIProvider.StreamEvent.Done)
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null
            var planBuffer = StringBuilder()
            var isInPlan = false
            val toolCallsMap = mutableMapOf<Int, MutableList<String>>()

            while (reader.readLine()?.also { line = it } != null) {
                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.substring(6).trim()
                if (data == "[DONE]") break

                val json = try { JSONObject(data) } catch (e: Exception) { continue }
                val choices = json.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                // Text content
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    var remainingText = content
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

                // Tool calls (streaming)
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.getJSONObject(i)
                        val idx = tc.optInt("index", i)
                        val fn = tc.optJSONObject("function") ?: continue
                        val name = fn.optString("name", "")
                        val argsChunk = fn.optString("arguments", "")

                        if (name.isNotEmpty()) {
                            toolCallsMap.getOrPut(idx) { mutableListOf() }.add(0, name)
                        }
                        if (argsChunk.isNotEmpty()) {
                            toolCallsMap.getOrPut(idx) { mutableListOf() }.add(argsChunk)
                        }
                    }
                }

                // Check finish reason for tool calls
                val finishReason = choices.getJSONObject(0).optString("finish_reason", "")
                if (finishReason == "tool_calls" && toolCallsMap.isNotEmpty()) {
                    for ((idx, parts) in toolCallsMap) {
                        val name = parts.firstOrNull() ?: continue
                        val argsStr = parts.drop(1).joinToString("")
                        val args = try { JSONObject(argsStr) } catch (e: Exception) { JSONObject() }
                        emit(AIProvider.StreamEvent.ToolCall(name, args, idx.toString()))
                    }
                    toolCallsMap.clear()
                }
            }
            emit(AIProvider.StreamEvent.Done)
        } catch (e: Exception) {
            Log.e("OpenAiClient", "Stream error", e)
            emit(AIProvider.StreamEvent.Error(e.message ?: "Unknown error"))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun convertHistory(history: JSONArray): JSONArray {
        val messages = JSONArray()
        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user")
            val parts = msg.optJSONArray("parts") ?: continue

            val openAiRole = when (role) {
                "model" -> "assistant"
                else -> "user"
            }

            val textParts = mutableListOf<String>()
            var isToolResult = false
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.has("text")) textParts.add(part.getString("text"))
                if (part.has("functionResponse")) {
                    val fr = part.getJSONObject("functionResponse")
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", fr.optString("name", ""))
                        put("content", fr.optJSONObject("response")?.optString("content", "") ?: "")
                    })
                    isToolResult = true
                }
            }
            if (isToolResult) continue

            if (textParts.isNotEmpty()) {
                messages.put(JSONObject().apply {
                    put("role", openAiRole)
                    put("content", textParts.joinToString("\n"))
                })
            }
        }
        return messages
    }

    private fun convertToolsToOpenAi(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            result.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.optString("name"))
                    put("description", tool.optString("description"))
                    if (tool.has("parameters")) {
                        put("parameters", tool.getJSONObject("parameters"))
                    }
                })
            })
        }
        return result
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("vision", modelName.contains("4o"))
    }
}
