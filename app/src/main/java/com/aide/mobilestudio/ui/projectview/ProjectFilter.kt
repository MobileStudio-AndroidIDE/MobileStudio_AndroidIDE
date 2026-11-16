package com.aide.mobilestudio.ui.projectview

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ProjectFilter(
    private val context: Context
) {
    private val filters = mutableListOf<String>()

    fun addFilter(filter: String) {
        filters.add(filter)
    }

    fun getFilters(): List<String> = filters

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, filters)
        listView.adapter = adapter
        return listView
    }
}