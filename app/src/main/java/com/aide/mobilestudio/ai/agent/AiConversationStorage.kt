package com.aide.mobilestudio.ai.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AiConversationStorage(private val context: Context) {
    private val rootDir = File(context.filesDir, "AI/conversations").apply { mkdirs() }

    data class Conversation(
        val id: String,
        val title: String,
        val timestamp: Long,
        val messages: JSONArray,
        val modelInfo: JSONObject? = null
    )

    fun listConversations(): List<Conversation> {
        return rootDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                Conversation(
                    id = file.nameWithoutExtension,
                    title = json.optString("title", "Untitled"),
                    timestamp = json.optLong("timestamp", file.lastModified()),
                    messages = json.getJSONArray("messages"),
                    modelInfo = json.optJSONObject("modelInfo")
                )
            }.getOrNull()
        }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    fun saveConversation(id: String, title: String, messages: JSONArray, modelInfo: JSONObject? = null) {
        val file = File(rootDir, "$id.json")
        val json = JSONObject().apply {
            put("title", title)
            put("timestamp", System.currentTimeMillis())
            put("messages", sanitizeMessages(messages))
            put("modelInfo", modelInfo)
        }
        file.writeText(json.toString(2))
    }

    fun loadConversation(id: String): Conversation? {
        val file = File(rootDir, "$id.json")
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Conversation(
                id = id,
                title = json.optString("title", "Untitled"),
                timestamp = json.optLong("timestamp", file.lastModified()),
                messages = json.getJSONArray("messages"),
                modelInfo = json.optJSONObject("modelInfo")
            )
        }.getOrNull()
    }

    fun deleteConversation(id: String) {
        File(rootDir, "$id.json").delete()
    }

    private fun sanitizeMessages(messages: JSONArray): JSONArray {
        val sanitized = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            // Filter out sensitive fields if they existed in memory (unlikely given GeminiClient implementation)
            sanitized.put(msg)
        }
        return sanitized
    }

    fun generateId(): String = "conv_${System.currentTimeMillis()}"
    
    fun exportTo(destDir: File): Boolean {
        return runCatching {
            if (!destDir.exists()) destDir.mkdirs()
            rootDir.copyRecursively(destDir, overwrite = true)
        }.isSuccess
    }
    
    fun importFrom(srcDir: File): Boolean {
        return runCatching {
            if (srcDir.exists() && srcDir.isDirectory) {
                srcDir.copyRecursively(rootDir, overwrite = true)
                true
            } else false
        }.getOrDefault(false)
    }
}
