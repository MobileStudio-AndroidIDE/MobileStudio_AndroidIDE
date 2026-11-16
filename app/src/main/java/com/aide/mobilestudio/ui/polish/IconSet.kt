package com.aide.mobilestudio.ui.polish

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Icon Set - icon set management.
 * Simplified implementation for compilation.
 */
class IconSet(
    private val context: Context
) {
    private val icons = mutableListOf<String>()

    fun addIcon(icon: String) {
        icons.add(icon)
    }

    fun getIcons(): List<String> = icons

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, icons)
        listView.adapter = adapter
        return listView
    }
}