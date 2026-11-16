package com.aide.mobilestudio.debugger

import android.content.Context
import android.content.SharedPreferences

class DebuggerSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("debugger_settings", Context.MODE_PRIVATE)

    var autoPauseOnException: Boolean
        get() = prefs.getBoolean("auto_pause_exception", false)
        set(value) = prefs.edit().putBoolean("auto_pause_exception", value).apply()

    var autoPauseOnEntry: Boolean
        get() = prefs.getBoolean("auto_pause_entry", false)
        set(value) = prefs.edit().putBoolean("auto_pause_entry", value).apply()

    var stepFilters: List<String>
        get() = prefs.getString("step_filters", "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit().putString("step_filters", value.joinToString(",")).apply()

    var sourcePathMapping: Map<String, String>
        get() {
            val result = mutableMapOf<String, String>()
            val json: String = prefs.getString("source_path_mapping", "{}") ?: "{}"
            val trimmed = json.trim().removeSurrounding("{", "}")
            if (trimmed.isNotEmpty()) {
                trimmed.split(",").forEach { pair ->
                    val kv = pair.split("=")
                    if (kv.size == 2) {
                        result[kv[0].trim().removeSurrounding("\"")] = kv[1].trim().removeSurrounding("\"")
                    }
                }
            }
            return result
        }
        set(value) {
            val json = value.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            prefs.edit().putString("source_path_mapping", "{$json}").apply()
        }
    }
