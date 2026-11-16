package com.aide.mobilestudio.editor.missing

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Surround With - surround with code blocks.
 */
class SurroundWith(
    private val context: Context
) {
    private val templates = mutableListOf<String>()

    fun addTemplate(template: String) {
        templates.add(template)
    }

    fun getTemplates(): List<String> = templates

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, templates)
        listView.adapter = adapter
        return listView
    }
}
