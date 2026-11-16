package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowSystem(
    private val context: Context
) {
    private val systems = mutableListOf<String>()

    fun addSystem(system: String) {
        systems.add(system)
    }

    fun getSystems(): List<String> = systems

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, systems)
        listView.adapter = adapter
        return listView
    }
}