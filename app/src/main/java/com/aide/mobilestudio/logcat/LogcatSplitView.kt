package com.aide.mobilestudio.logcat

/**
 * Logcat Split View - logcat split view management.
 */
class LogcatSplitView(
    private val context: android.content.Context
) {
    private val views = mutableListOf<String>()

    fun addView(view: String) {
        views.add(view)
    }

    fun getViews(): List<String> = views

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, views)
        listView.adapter = adapter
        return listView
    }
}
