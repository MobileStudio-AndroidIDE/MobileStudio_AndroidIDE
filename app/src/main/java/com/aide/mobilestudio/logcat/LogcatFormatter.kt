package com.aide.mobilestudio.logcat

/**
 * Logcat Formatter - logcat formatter.
 */
class LogcatFormatter(
    private val context: android.content.Context
) {
    private val formats = mutableListOf<String>()

    fun addFormat(format: String) {
        formats.add(format)
    }

    fun getFormats(): List<String> = formats

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, formats)
        listView.adapter = adapter
        return listView
    }
}
