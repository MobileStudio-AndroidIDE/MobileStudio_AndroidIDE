package com.aide.mobilestudio.editor.missing

/**
 * Code Folding Actions - code folding actions.
 */
class CodeFoldingActions(
    private val context: android.content.Context
) {
    private val folds = mutableListOf<String>()

    fun addFold(fold: String) {
        folds.add(fold)
    }

    fun getFolds(): List<String> = folds

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, folds)
        listView.adapter = adapter
        return listView
    }
}
