package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Variables Panel - displays debugger variables.
 */
class VariablesPanel(
    private val context: Context,
    private val session: DebugSession
) {
    private val variables = mutableListOf<String>()

    fun refreshVariables() {
        variables.clear()
        // In a real implementation, this would get variables from the debug session
    }

    fun getVariables(): List<String> = variables

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, variables)
        listView.adapter = adapter
        return listView
    }
}
