package com.aide.mobilestudio.ai.api

import com.aide.mobilestudio.ai.retry.ApiErrorInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow

interface AIProvider {
    val id: String
    val name: String

    sealed class StreamEvent {
        data class Text(val text: String) : StreamEvent()
        data class Reasoning(val text: String) : StreamEvent()
        data class ToolCall(val name: String, val args: JSONObject, val id: String? = null) : StreamEvent()
        data class Plan(val rawPlan: String) : StreamEvent()
        /** Exact output-token count and decode time, when the provider can measure them. */
        data class Usage(val outputTokens: Int, val decodeElapsedMs: Long?) : StreamEvent()

        /**
         * @param info classified failure (status, kind, Retry-After, retryable).
         *   Null only for errors the provider could not classify; the retry
         *   engine falls back to reading [message] in that case.
         */
        data class Error(val message: String, val info: ApiErrorInfo? = null) : StreamEvent()
        object Done : StreamEvent()
    }

    fun generateStream(
        history: JSONArray,
        tools: JSONArray? = null,
        options: JSONObject? = null
    ): Flow<StreamEvent>

    fun getCapabilities(): JSONObject
}
