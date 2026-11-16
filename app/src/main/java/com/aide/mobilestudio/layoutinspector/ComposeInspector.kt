package com.aide.mobilestudio.layoutinspector

/**
 * Compose Inspector - Compose node tree inspection.
 */
class ComposeInspector(
    private val context: android.content.Context
) {
    private val nodes = mutableListOf<String>()

    fun addNode(node: String) {
        nodes.add(node)
    }

    fun getNodes(): List<String> = nodes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, nodes)
        listView.adapter = adapter
        return listView
    }
}
