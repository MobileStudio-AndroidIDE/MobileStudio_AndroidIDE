package com.aide.mobilestudio.terminal

import android.content.Context

/**
 * Terminal Integration - terminal integration with the IDE.
 * Simplified implementation for compilation.
 */
class TerminalIntegration(
    private val context: Context
) {
    private val terminals = mutableListOf<String>()

    fun addTerminal(terminalId: String) {
        terminals.add(terminalId)
    }

    fun removeTerminal(terminalId: String) {
        terminals.remove(terminalId)
    }

    fun getTerminals(): List<String> = terminals

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, terminals)
        listView.adapter = adapter
        return listView
    }
}