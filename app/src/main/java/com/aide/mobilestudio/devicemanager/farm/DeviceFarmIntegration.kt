package com.aide.mobilestudio.devicemanager.farm

import android.content.Context

/**
 * Device Farm Integration - Firebase Test Lab integration stub.
 */
class DeviceFarmIntegration(
    private val context: Context
) {
    private val tests = mutableListOf<String>()

    fun addTest(test: String) {
        tests.add(test)
    }

    fun getTests(): List<String> = tests

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, tests)
        listView.adapter = adapter
        return listView
    }
}
