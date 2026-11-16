package com.aide.mobilestudio.logcat

/**
 * Logcat Export - logcat export functionality.
 */
class LogcatExport(
    private val context: android.content.Context
) {
    private val exports = mutableListOf<String>()

    fun addExport(export: String) {
        exports.add(export)
    }

    fun getExports(): List<String> = exports

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, exports)
        listView.adapter = adapter
        return listView
    }
}
