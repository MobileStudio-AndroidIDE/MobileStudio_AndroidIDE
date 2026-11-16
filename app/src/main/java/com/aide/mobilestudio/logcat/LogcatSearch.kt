package com.aide.mobilestudio.logcat

/**
 * Logcat Search - logcat search management.
 */
class LogcatSearch(
    private val context: android.content.Context
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
