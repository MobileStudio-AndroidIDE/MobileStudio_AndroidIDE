package com.aide.mobilestudio.logcat

/**
 * Logcat Highlight - logcat highlighting.
 */
class LogcatHighlight(
    private val context: android.content.Context
) {
    private val highlights = mutableListOf<String>()

    fun addHighlight(highlight: String) {
        highlights.add(highlight)
    }

    fun getHighlights(): List<String> = highlights

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, highlights)
        listView.adapter = adapter
        return listView
    }
}
