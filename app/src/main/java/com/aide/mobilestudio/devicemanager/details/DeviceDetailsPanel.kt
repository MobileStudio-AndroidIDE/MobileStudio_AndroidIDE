package com.aide.mobilestudio.devicemanager.details

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Device Details Panel - displays device details.
 */
class DeviceDetailsPanel(
    private val context: Context
) {
    private val details = mutableListOf<String>()

    fun addDetail(label: String, value: String) {
        details.add("$label: $value")
    }

    fun getDetails(): List<String> = details

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, details)
        listView.adapter = adapter
        return listView
    }
}
