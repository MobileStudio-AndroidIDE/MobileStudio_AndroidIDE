package com.aide.mobilestudio.ui.theme

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class SyntaxColorEditor(
    private val context: Context
) {
    private val colors = mutableListOf<String>()

    fun addColor(color: String) {
        colors.add(color)
    }

    fun getColors(): List<String> = colors

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, colors)
        listView.adapter = adapter
        return listView
    }
}