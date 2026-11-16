package com.aide.mobilestudio.resourcemanager.refactoring

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Refactoring Panel - resource refactoring panel.
 */
class ResourceRefactoringPanel(
    private val context: Context
) {
    private val refactors = mutableListOf<String>()

    fun addRefactor(refactor: String) {
        refactors.add(refactor)
    }

    fun getRefactors(): List<String> = refactors

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, refactors)
        listView.adapter = adapter
        return listView
    }
}
