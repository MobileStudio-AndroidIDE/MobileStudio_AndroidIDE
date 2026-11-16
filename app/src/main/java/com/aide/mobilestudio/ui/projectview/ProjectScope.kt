package com.aide.mobilestudio.ui.projectview

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ProjectScope(
    private val context: Context
) {
    private val scopes = mutableListOf<String>()

    fun addScope(scope: String) {
        scopes.add(scope)
    }

    fun getScopes(): List<String> = scopes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, scopes)
        listView.adapter = adapter
        return listView
    }
}