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

class GeminiClient(
    private val apiKey: String,
    private val modelName: String = "gemini-2.0-flash"
) : AIProvider {
    override val id: String = "gemini"
    override val name: String = "Google Gemini"

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:streamGenerateContent?alt=sse&key=$apiKey"

    override fun generateStream(
        history: JSONArray,
        tools: JSONArray?,
        options: JSONObject?
    ): Flow<AIProvider.StreamEvent> = flow {
        val body = JSONObject().apply {
            put("contents", convertHistory(history))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 8192)
            })
            if (tools != null && tools.length() > 0) {
                put("tools", JSONArray().put(JSONObject().apply {
                    put("functionDeclarations", convertToolsToGemini(tools))
                }))
            }
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
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
                    400 -> "Invalid request: ${errorBody.take(200)}"
                    403 -> "API key invalid or quota exceeded"
                    429 -> "Rate limited - too many requests"
                    500 -> "Gemini server error"
                    else -> "HTTP $responseCode"
                }
                Log.e("GeminiClient", "HTTP $responseCode: $errorBody")
                emit(AIProvider.StreamEvent.Error(errorMsg))
                emit(AIProvider.StreamEvent.Done)
                return@flow
            }

            var planBuffer = StringBuilder()
            var isInPlan = false

            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var line: String? = null
            while (reader.readLine()?.also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.isBlank()) continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.substring(6)
                if (data == "[DONE]") break

                val json = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    Log.w("GeminiClient", "Skipping invalid JSON: ${data.take(100)}")
                    continue
                }

                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            val text = part.optString("text", "")
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
                            val toolCall = part.optJSONObject("functionCall")
                            if (toolCall != null) {
                                emit(AIProvider.StreamEvent.ToolCall(
                                    toolCall.getString("name"),
                                    toolCall.optJSONObject("args") ?: JSONObject(),
                                    null
                                ))
                            }
                        }
                    }
                    val finishReason = candidate.optString("finishReason", "")
                    if (finishReason == "SAFETY" || finishReason == "RECITATION") {
                        emit(AIProvider.StreamEvent.Error("Response blocked by safety filter ($finishReason)"))
                    }
                }
            }
            emit(AIProvider.StreamEvent.Done)
        } catch (e: Exception) {
            Log.e("GeminiClient", "Stream error", e)
            emit(AIProvider.StreamEvent.Error(e.message ?: "Unknown error"))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun convertHistory(history: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until history.length()) {
            val msg = history.optJSONObject(i) ?: continue
            val role = msg.optString("role", "user")
            val parts = msg.optJSONArray("parts") ?: continue

            val geminiRole = when (role) {
                "model" -> "model"
                else -> "user"
            }

            val geminiParts = JSONArray()
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                if (part.has("text")) {
                    geminiParts.put(JSONObject().put("text", part.getString("text")))
                }
                if (part.has("inlineData")) {
                    geminiParts.put(part)
                }
                if (part.has("functionResponse")) {
                    val fr = part.getJSONObject("functionResponse")
                    geminiParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", fr.getString("name"))
                            put("response", fr.optJSONObject("response") ?: JSONObject())
                        })
                    })
                }
            }

            if (geminiParts.length() > 0) {
                result.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", geminiParts)
                })
            }
        }
        return result
    }

    private fun convertToolsToGemini(tools: JSONArray): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val name = tool.optString("name", "")
            val description = tool.optString("description", "")
            val params = tool.optJSONObject("parameters")

            result.put(JSONObject().apply {
                put("name", name)
                put("description", description)
                if (params != null) {
                    put("parameters", convertSchemaToGemini(params))
                }
            })
        }
        return result
    }

    private fun convertSchemaToGemini(schema: JSONObject): JSONObject {
        val result = JSONObject()
        result.put("type", when (schema.optString("type", "").uppercase()) {
            "OBJECT" -> "OBJECT"
            "ARRAY" -> "ARRAY"
            "STRING" -> "STRING"
            "NUMBER" -> "NUMBER"
            "INTEGER" -> "INTEGER"
            "BOOLEAN" -> "BOOLEAN"
            else -> schema.optString("type", "OBJECT").uppercase()
        })

        if (schema.has("description")) {
            result.put("description", schema.getString("description"))
        }

        val properties = schema.optJSONObject("properties")
        if (properties != null) {
            val geminiProps = JSONObject()
            for (key in properties.keys()) {
                val prop = properties.optJSONObject(key) ?: continue
                geminiProps.put(key, convertSchemaToGemini(prop))
            }
            result.put("properties", geminiProps)
        }

        val required = schema.optJSONArray("required")
        if (required != null) {
            result.put("required", required)
        }

        val items = schema.optJSONObject("items")
        if (items != null) {
            result.put("items", convertSchemaToGemini(items))
        }

        return result
    }

    override fun getCapabilities(): JSONObject = JSONObject().apply {
        put("streaming", true)
        put("tools", true)
        put("vision", true)
    }
}
