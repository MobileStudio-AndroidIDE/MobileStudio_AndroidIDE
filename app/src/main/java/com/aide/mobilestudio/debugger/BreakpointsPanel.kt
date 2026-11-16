package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Breakpoints Panel - displays breakpoints.
 */
class BreakpointsPanel(
    private val context: Context
) {
    private val breakpoints = mutableListOf<String>()

    /**
     * Adds a breakpoint.
     */
    fun addBreakpoint(location: String) {
        breakpoints.add(location)
    }

    /**
     * Removes a breakpoint.
     */
    fun removeBreakpoint(location: String) {
        breakpoints.remove(location)
    }

    /**
     * Returns current breakpoints.
     */
    fun getBreakpoints(): List<String> = breakpoints

    /**
     * Creates a simple ListView for displaying breakpoints.
     */
    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, breakpoints)
        listView.adapter = adapter
        return listView
    }
}
