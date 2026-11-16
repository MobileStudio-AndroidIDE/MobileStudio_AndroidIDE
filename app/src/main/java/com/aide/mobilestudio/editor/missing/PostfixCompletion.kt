package com.aide.mobilestudio.editor.missing

/**
 * Postfix Completion - postfix completion suggestions.
 */
class PostfixCompletion(
    private val context: android.content.Context
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
