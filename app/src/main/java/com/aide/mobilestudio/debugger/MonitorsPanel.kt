package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Monitors Panel - displays thread monitors/locks.
 */
class MonitorsPanel(
    private val context: Context
) {
    private val monitors = mutableListOf<String>()

    fun addMonitor(info: String) {
        monitors.add(info)
    }

    fun removeMonitor(info: String) {
        monitors.remove(info)
    }

    fun getMonitors(): List<String> = monitors

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, monitors)
        listView.adapter = adapter
        return listView
    }
}
