package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowComponent(
    private val context: Context
) {
    private val components = mutableListOf<String>()

    fun addComponent(component: String) {
        components.add(component)
    }

    fun getComponents(): List<String> = components

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, components)
        listView.adapter = adapter
        return listView
    }
}