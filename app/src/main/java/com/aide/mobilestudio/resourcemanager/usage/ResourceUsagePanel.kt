package com.aide.mobilestudio.resourcemanager.usage

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Usage Panel - resource usage panel.
 */
class ResourceUsagePanel(
    private val context: Context
) {
    private val usages = mutableListOf<String>()

    fun addUsage(usage: String) {
        usages.add(usage)
    }

    fun getUsages(): List<String> = usages

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, usages)
        listView.adapter = adapter
        return listView
    }
}
