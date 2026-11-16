package com.aide.mobilestudio.ui.theme

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ThemeEditorToolWindow(
    private val context: Context
) {
    private val items = mutableListOf<String>()

    fun addItem(item: String) {
        items.add(item)
    }

    fun getItems(): List<String> = items

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, items)
        listView.adapter = adapter
        return listView
    }
}