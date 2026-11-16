package com.aide.mobilestudio.logcat

/**
 * Logcat Collector - logcat collection.
 */
class LogcatCollector(
    private val context: android.content.Context
) {
    private val logs = mutableListOf<String>()

    fun addLog(log: String) {
        logs.add(log)
    }

    fun clearLogs() {
        logs.clear()
    }

    fun getLogs(): List<String> = logs

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, logs)
        listView.adapter = adapter
        return listView
    }
}
