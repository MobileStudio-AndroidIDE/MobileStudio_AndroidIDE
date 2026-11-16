package com.aide.mobilestudio.profiler

/**
 * CPU Profiler - CPU profiling.
 */
class CpuProfiler(
    private val context: android.content.Context
) {
    private val samples = mutableListOf<Double>()

    fun addSample(value: Double) {
        samples.add(value)
    }

    fun getSamples(): List<Double> = samples

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, samples.map { it.toString() })
        listView.adapter = adapter
        return listView
    }
}
