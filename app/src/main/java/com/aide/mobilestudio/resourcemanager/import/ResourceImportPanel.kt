package com.aide.mobilestudio.resourcemanager.import

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Import Panel - resource import panel.
 */
class ResourceImportPanel(
    private val context: Context
) {
    private val imports = mutableListOf<String>()

    fun addImport(importStr: String) {
        imports.add(importStr)
    }

    fun getImports(): List<String> = imports

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, imports)
        listView.adapter = adapter
        return listView
    }
}
