package com.aide.mobilestudio.logcat

/**
 * Logcat Filter Engine - logcat filter engine.
 */
class LogcatFilterEngine(
    private val context: android.content.Context
) {
    private val filters = mutableListOf<String>()

    fun addFilter(filter: String) {
        filters.add(filter)
    }

    fun getFilters(): List<String> = filters

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, filters)
        listView.adapter = adapter
        return listView
    }
}
