package com.aide.mobilestudio.layoutinspector

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * View Attributes Panel - view attributes panel.
 */
class ViewAttributesPanel(
    private val context: Context
) {
    private val attributes = mutableListOf<String>()

    fun addAttribute(attribute: String) {
        attributes.add(attribute)
    }

    fun getAttributes(): List<String> = attributes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, attributes)
        listView.adapter = adapter
        return listView
    }
}
