package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Editor Tabs - editor tab management.
 */
class EditorTabs(
    private val context: Context
) {
    private val tabs = mutableListOf<EditorTab>()

    fun addTab(tab: EditorTab) {
        tabs.add(tab)
    }

    fun getTabs(): List<EditorTab> = tabs

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, tabs.map { it.fileName })
        listView.adapter = adapter
        return listView
    }
}
