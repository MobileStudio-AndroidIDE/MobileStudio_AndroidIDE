package com.aide.mobilestudio.ai.opencode.session

import org.json.JSONArray
import org.json.JSONObject

object OcMessage {

    fun nextId(prefix: String = "msg"): String = "${prefix}_${System.currentTimeMillis()}_${(0..9999).random()}"

    fun nextCallId(): String = "call_${System.currentTimeMillis()}_${(0..9999).random()}"

    fun userMessage(sessionId: String, agent: String?, model: JSONObject?): JSONObject = JSONObject().apply {
        put("id", nextId())
        put("role", "user")
        put("sessionID", sessionId)
        if (agent != null) put("agent", agent)
        if (model != null) put("model", model)
        put("time", JSONObject().put("created", System.currentTimeMillis()))
        put("parts", JSONArray())
    }

    fun assistantMessage(
        sessionId: String,
        agent: String,
        model: JSONObject,
        parentID: String? = null,
        summary: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("id", nextId())
        put("role", "assistant")
        put("sessionID", sessionId)
        put("agent", agent)
        put("model", model)
        if (parentID != null) put("parentID", parentID)
        if (summary) put("summary", true)
        put("time", JSONObject().put("created", System.currentTimeMillis()))
        put("tokens", JSONObject().apply {
            put("total", 0)
            put("input", 0)
            put("output", 0)
            put("reasoning", 0)
            put("cache", JSONObject().put("read", 0).put("write", 0))
        })
        put("parts", JSONArray())
    }

    fun textPart(text: String, synthetic: Boolean = false, metadata: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("type", "text")
            put("id", nextId("part"))
            put("text", text)
            if (synthetic) put("synthetic", true)
            if (metadata != null) put("metadata", metadata)
        }

    fun addPart(msg: JSONObject, part: JSONObject): JSONObject {
        val parts = msg.optJSONArray("parts") ?: JSONArray()
        parts.put(part)
        msg.put("parts", parts)
        return msg
    }

    fun reasoningPart(text: String): JSONObject = JSONObject().apply {
        put("type", "reasoning")
        put("id", nextId("part"))
        put("text", text)
    }

    fun toolPart(tool: String, callId: String, input: JSONObject): JSONObject = JSONObject().apply {
        put("type", "tool")
        put("id", nextId("part"))
        put("tool", tool)
        put("callId", callId)
        put("state", JSONObject().apply {
            put("status", "pending")
            put("input", input)
            put("output", "")
        })
    }

    fun filePart(mime: String, filename: String, path: String? = null): JSONObject = JSONObject().apply {
        put("type", "file")
        put("id", nextId("part"))
        put("mime", mime)
        put("filename", filename)
        if (path != null) put("path", path)
    }

    fun compactionPart(auto: Boolean, overflow: Boolean? = null): JSONObject = JSONObject().apply {
        put("type", "compaction")
        put("id", nextId("part"))
        put("auto", auto)
        if (overflow != null) put("overflow", overflow)
    }

    fun isUser(msg: JSONObject): Boolean = msg.optString("role") == "user"

    fun isAssistant(msg: JSONObject): Boolean = msg.optString("role") == "assistant"

    fun parts(msg: JSONObject): JSONArray = msg.optJSONArray("parts") ?: JSONArray()

    fun hasCompaction(msg: JSONObject): Boolean {
        val parts = parts(msg)
        for (i in 0 until parts.length()) {
            if (parts.getJSONObject(i).optString("type") == "compaction") return true
        }
        return false
    }

    fun compactionPart(msg: JSONObject): JSONObject? {
        val parts = parts(msg)
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "compaction") return part
        }
        return null
    }

    fun textOf(msg: JSONObject): String {
        val parts = parts(msg)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "text" && !part.optBoolean("ignored")) {
                val text = part.optString("text")
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(text)
                }
            }
        }
        return sb.toString()
    }

    fun summaryText(msg: JSONObject): String? {
        val parts = parts(msg)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "text") {
                val text = part.optString("text").trim()
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(text)
                }
            }
        }
        return sb.toString().ifEmpty { null }
    }

    fun toolParts(msg: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val parts = parts(msg)
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "tool") result.add(part)
        }
        return result
    }

    fun estimateTokens(value: String): Int = OcTokenEstimate.tokens(value)

    fun estimateTokens(value: Any): Int = OcTokenEstimate.tokens(value)
}
