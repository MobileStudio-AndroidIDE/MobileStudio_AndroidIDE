package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Threads Panel - displays thread list.
 */
class ThreadsPanel(
    private val context: Context,
    private val session: DebugSession
) {
    private val threads = mutableListOf<String>()

    fun refreshThreads() {
        threads.clear()
        session.threads.forEach { (id, info) ->
            threads.add("${info.name} (${info.state})")
        }
    }

    fun getThreads(): List<String> = threads

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, threads)
        listView.adapter = adapter
        return listView
    }
}
