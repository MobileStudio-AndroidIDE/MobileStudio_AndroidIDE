package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Watches Panel - displays watched expressions.
 */
class WatchesPanel(
    private val context: Context,
    private val session: DebugSession
) {
    private val watches = mutableListOf<String>()

    fun addWatch(expression: String) {
        watches.add(expression)
    }

    fun removeWatch(expression: String) {
        watches.remove(expression)
    }

    fun getWatches(): List<String> = watches

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, watches)
        listView.adapter = adapter
        return listView
    }
}
