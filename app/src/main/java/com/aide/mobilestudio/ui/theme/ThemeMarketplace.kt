package com.aide.mobilestudio.ui.theme

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ThemeMarketplace(
    private val context: Context
) {
    private val themes = mutableListOf<String>()

    fun addTheme(theme: String) {
        themes.add(theme)
    }

    fun getThemes(): List<String> = themes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, themes)
        listView.adapter = adapter
        return listView
    }
}