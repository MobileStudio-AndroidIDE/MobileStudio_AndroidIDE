package com.aide.mobilestudio.devicemanager.actions

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Device Actions Panel - device action buttons.
 */
class DeviceActionsPanel(
    private val context: Context
) {
    private val actions = mutableListOf<String>()

    fun addAction(action: String) {
        actions.add(action)
    }

    fun getActions(): List<String> = actions

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, actions)
        listView.adapter = adapter
        return listView
    }
}
