package com.aide.mobilestudio.layoutinspector

/**
 * View Tree Renderer - view tree rendering.
 */
class ViewTreeRenderer(
    private val context: android.content.Context
) {
    private val trees = mutableListOf<String>()

    fun addTree(tree: String) {
        trees.add(tree)
    }

    fun getTrees(): List<String> = trees

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, trees)
        listView.adapter = adapter
        return listView
    }
}
