package com.aide.mobilestudio.sdkmanager.tools

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * SDK Tool Panel - SDK tools management panel.
 * Simplified implementation for compilation.
 */
class SdkToolPanel(
    private val context: Context
) {
    private val tools = mutableListOf<String>()

    fun addTool(tool: String) {
        tools.add(tool)
    }

    fun getTools(): List<String> = tools

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, tools)
        listView.adapter = adapter
        return listView
    }
}