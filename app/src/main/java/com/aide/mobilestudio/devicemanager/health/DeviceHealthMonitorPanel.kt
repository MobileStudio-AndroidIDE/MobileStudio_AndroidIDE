package com.aide.mobilestudio.devicemanager.health

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Device Health Monitor Panel - device health monitoring.
 */
class DeviceHealthMonitorPanel(
    private val context: Context
) {
    private val metrics = mutableListOf<String>()

    fun addMetric(metric: String) {
        metrics.add(metric)
    }

    fun getMetrics(): List<String> = metrics

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, metrics)
        listView.adapter = adapter
        return listView
    }
}
