package com.aide.mobilestudio.resourcemanager.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Editor Panel - resource editor panel.
 */
class ResourceEditorPanel(
    private val context: Context
) {
    private val edits = mutableListOf<String>()

    fun addEdit(edit: String) {
        edits.add(edit)
    }

    fun getEdits(): List<String> = edits

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, edits)
        listView.adapter = adapter
        return listView
    }
}
