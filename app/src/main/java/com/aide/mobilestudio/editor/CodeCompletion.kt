package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Code Completion - code completion suggestions.
 */
class CodeCompletion(
    private val context: Context
) {
    private val suggestions = mutableListOf<String>()

    fun addSuggestion(suggestion: String) {
        suggestions.add(suggestion)
    }

    fun getSuggestions(): List<String> = suggestions

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, suggestions)
        listView.adapter = adapter
        return listView
    }
}
