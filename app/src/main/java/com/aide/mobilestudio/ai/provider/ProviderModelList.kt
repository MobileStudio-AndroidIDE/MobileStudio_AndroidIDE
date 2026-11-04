package com.aide.mobilestudio.ai.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses model-listing responses from the common provider shapes. Pure and
 * dependency free so it is unit-testable without network access.
 */
object ProviderModelList {

    fun parse(providerId: String, body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull()
        val array = when {
            json == null -> runCatching { JSONArray(body) }.getOrNull()
            providerId == "gemini" -> json.optJSONArray("models")
            else -> json.optJSONArray("data") ?: json.optJSONArray("models")
        } ?: return emptyList()

        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = when {
                obj.has("id") -> obj.optString("id")
                obj.has("name") -> obj.optString("name").removePrefix("models/")
                else -> ""
            }
            if (id.isNotBlank()) out.add(id)
        }
        return out.toList()
    }
}
