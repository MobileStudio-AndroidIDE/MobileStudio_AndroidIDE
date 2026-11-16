package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowLayout(
    private val context: Context
) {
    private val layouts = mutableListOf<String>()

    fun addLayout(layout: String) {
        layouts.add(layout)
    }

    fun getLayouts(): List<String> = layouts

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, layouts)
        listView.adapter = adapter
        return listView
    }
}