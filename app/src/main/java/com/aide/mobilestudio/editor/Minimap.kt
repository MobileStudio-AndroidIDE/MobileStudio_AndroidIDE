package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Minimap - code minimap view.
 */
class Minimap(
    private val context: Context
) {
    private val lines = mutableListOf<String>()

    fun setLines(lines: List<String>) {
        this.lines.clear()
        this.lines.addAll(lines)
    }

    fun getLines(): List<String> = lines

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, lines)
        listView.adapter = adapter
        return listView
    }
}
