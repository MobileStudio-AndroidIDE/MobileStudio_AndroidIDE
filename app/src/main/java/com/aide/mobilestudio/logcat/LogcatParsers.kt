package com.aide.mobilestudio.logcat

/**
 * Logcat Parsers - logcat parsers.
 */
class LogcatParsers(
    private val context: android.content.Context
) {
    private val parsers = mutableListOf<String>()

    fun addParser(parser: String) {
        parsers.add(parser)
    }

    fun getParsers(): List<String> = parsers

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, parsers)
        listView.adapter = adapter
        return listView
    }
}
