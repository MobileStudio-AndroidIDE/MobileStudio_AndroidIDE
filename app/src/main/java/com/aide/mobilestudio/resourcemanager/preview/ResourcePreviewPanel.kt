package com.aide.mobilestudio.resourcemanager.preview

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Preview Panel - resource preview panel.
 */
class ResourcePreviewPanel(
    private val context: Context
) {
    private val previews = mutableListOf<String>()

    fun addPreview(preview: String) {
        previews.add(preview)
    }

    fun getPreviews(): List<String> = previews

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, previews)
        listView.adapter = adapter
        return listView
    }
}
