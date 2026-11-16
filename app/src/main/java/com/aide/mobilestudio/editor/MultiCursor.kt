package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Multi Cursor - multiple cursor editing.
 */
class MultiCursor(
    private val context: Context
) {
    private val positions = mutableListOf<Int>()

    fun addPosition(position: Int) {
        positions.add(position)
    }

    fun getPositions(): List<Int> = positions

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, positions.map { it.toString() })
        listView.adapter = adapter
        return listView
    }
}
