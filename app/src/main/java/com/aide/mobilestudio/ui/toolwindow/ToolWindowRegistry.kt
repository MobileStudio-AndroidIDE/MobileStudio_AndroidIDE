package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ToolWindowRegistry(
    private val context: Context
) {
    private val registrations = mutableListOf<String>()

    fun addRegistration(registration: String) {
        registrations.add(registration)
    }

    fun getRegistrations(): List<String> = registrations

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, registrations)
        listView.adapter = adapter
        return listView
    }
}