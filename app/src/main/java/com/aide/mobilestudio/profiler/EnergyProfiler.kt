package com.aide.mobilestudio.profiler

/**
 * Energy Profiler - energy profiling.
 */
class EnergyProfiler(
    private val context: android.content.Context
) {
    private val readings = mutableListOf<Double>()

    fun addReading(value: Double) {
        readings.add(value)
    }

    fun getReadings(): List<Double> = readings

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, readings.map { it.toString() })
        listView.adapter = adapter
        return listView
    }
}
