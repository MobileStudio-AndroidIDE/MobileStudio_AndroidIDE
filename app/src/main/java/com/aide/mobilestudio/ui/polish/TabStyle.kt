package com.aide.mobilestudio.ui.polish

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Tab Style - tab styling.
 * Simplified implementation for compilation.
 */
class TabStyle(
    private val context: Context
) {
    private val styles = mutableListOf<String>()

    fun addStyle(style: String) {
        styles.add(style)
    }

    fun getStyles(): List<String> = styles

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, styles)
        listView.adapter = adapter
        return listView
    }
}