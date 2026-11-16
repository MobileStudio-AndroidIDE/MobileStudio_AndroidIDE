package com.aide.mobilestudio.terminal

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Terminal Search - terminal search functionality.
 * Simplified implementation for compilation.
 */
class TerminalSearch(
    private val context: Context
) {
    private val searches = mutableListOf<String>()

    fun addSearch(search: String) {
        searches.add(search)
    }

    fun getSearches(): List<String> = searches

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, searches)
        listView.adapter = adapter
        return listView
    }
}