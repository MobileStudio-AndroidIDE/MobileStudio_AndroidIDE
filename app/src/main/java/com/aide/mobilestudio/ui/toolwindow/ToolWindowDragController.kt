package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowDragController(
    private val context: Context
) {
    private val controllers = mutableListOf<String>()

    fun addController(controller: String) {
        controllers.add(controller)
    }

    fun getControllers(): List<String> = controllers

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, controllers)
        listView.adapter = adapter
        return listView
    }
}