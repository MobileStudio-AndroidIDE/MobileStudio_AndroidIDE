package com.aide.mobilestudio.profiler

/**
 * Profiler Package - profiler package.
 */
class ProfilerPackage(
    private val context: android.content.Context
) {
    private val profilers = mutableListOf<String>()

    fun addProfiler(profiler: String) {
        profilers.add(profiler)
    }

    fun getProfilers(): List<String> = profilers

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, profilers)
        listView.adapter = adapter
        return listView
    }
}
