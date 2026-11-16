package com.aide.mobilestudio.terminal

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Terminal Split - terminal split view management.
 * Simplified implementation for compilation.
 */
class TerminalSplit(
    private val context: Context
) {
    private val terminals = mutableListOf<String>()

    fun addTerminal(terminal: String) {
        terminals.add(terminal)
    }

    fun getTerminals(): List<String> = terminals

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, terminals)
        listView.adapter = adapter
        return listView
    }
}