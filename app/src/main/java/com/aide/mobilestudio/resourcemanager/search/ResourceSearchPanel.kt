package com.aide.mobilestudio.resourcemanager.search

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Search Panel - resource search panel.
 */
class ResourceSearchPanel(
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
