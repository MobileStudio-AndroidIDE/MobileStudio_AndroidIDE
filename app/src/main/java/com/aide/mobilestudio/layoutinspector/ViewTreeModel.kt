package com.aide.mobilestudio.layoutinspector

/**
 * View Tree Model - view tree model.
 */
class ViewTreeModel(
    private val context: android.content.Context
) {
    private val models = mutableListOf<String>()

    fun addModel(model: String) {
        models.add(model)
    }

    fun getModels(): List<String> = models

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, models)
        listView.adapter = adapter
        return listView
    }
}
