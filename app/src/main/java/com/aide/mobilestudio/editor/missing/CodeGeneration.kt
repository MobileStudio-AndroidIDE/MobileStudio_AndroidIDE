package com.aide.mobilestudio.editor.missing

/**
 * Code Generation - code generation utilities.
 */
class CodeGeneration(
    private val context: android.content.Context
) {
    data class CodeItem(
        val id: String,
        val label: String,
        val description: String
    )

    private val items = mutableListOf<CodeItem>()

    fun addItem(item: CodeItem) {
        items.add(item)
    }

    fun getItems(): List<CodeItem> = items

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, items.map { it.label })
        listView.adapter = adapter
        return listView
    }
}
