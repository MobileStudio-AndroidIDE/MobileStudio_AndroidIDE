package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowManager(
    private val context: Context
) {
    private val windows = mutableListOf<String>()

    fun addWindow(window: String) {
        windows.add(window)
    }

    fun getWindows(): List<String> = windows

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, windows)
        listView.adapter = adapter
        return listView
    }
}