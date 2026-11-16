package com.aide.mobilestudio.terminal

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Terminal Renderer - terminal rendering.
 * Simplified implementation for compilation.
 */
class TerminalRenderer(
    private val context: Context
) {
    private val lines = mutableListOf<String>()

    fun addLine(line: String) {
        lines.add(line)
    }

    fun getLines(): List<String> = lines

    fun clear() {
        lines.clear()
    }

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, lines)
        listView.adapter = adapter
        return listView
    }
}