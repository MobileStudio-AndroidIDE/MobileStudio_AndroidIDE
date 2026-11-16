package com.aide.mobilestudio.profiler

/**
 * Profiler Timeline - profiler timeline view.
 */
class ProfilerTimeline(
    private val context: android.content.Context
) {
    private val timelines = mutableListOf<String>()

    fun addTimeline(timeline: String) {
        timelines.add(timeline)
    }

    fun getTimelines(): List<String> = timelines

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, timelines)
        listView.adapter = adapter
        return listView
    }
}
