package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.content.SharedPreferences

class ToolWindowState(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tool_window_state", Context.MODE_PRIVATE)

    fun saveState(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun loadState(key: String): String? {
        return prefs.getString(key, null)
    }

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, listOf("Tool Window State"))
        listView.adapter = adapter
        return listView
    }
}