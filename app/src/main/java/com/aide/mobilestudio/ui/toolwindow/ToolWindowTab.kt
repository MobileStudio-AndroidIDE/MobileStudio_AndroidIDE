package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowTab(
    private val context: Context
) {
    private val tabs = mutableListOf<String>()

    fun addTab(tab: String) {
        tabs.add(tab)
    }

    fun getTabs(): List<String> = tabs

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, tabs)
        listView.adapter = adapter
        return listView
    }
}