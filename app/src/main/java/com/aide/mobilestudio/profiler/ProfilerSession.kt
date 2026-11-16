package com.aide.mobilestudio.profiler

/**
 * Profiler Session - profiler session management.
 */
class ProfilerSession(
    private val context: android.content.Context
) {
    private val sessions = mutableListOf<String>()

    fun addSession(session: String) {
        sessions.add(session)
    }

    fun getSessions(): List<String> = sessions

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, sessions)
        listView.adapter = adapter
        return listView
    }
}
