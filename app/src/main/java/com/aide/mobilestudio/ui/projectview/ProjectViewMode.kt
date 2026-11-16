package com.aide.mobilestudio.ui.projectview

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ProjectViewMode(
    private val context: Context
) {
    private val modes = mutableListOf<String>()

    fun addMode(mode: String) {
        modes.add(mode)
    }

    fun getModes(): List<String> = modes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, modes)
        listView.adapter = adapter
        return listView
    }
}