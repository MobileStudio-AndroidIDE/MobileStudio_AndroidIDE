package com.aide.mobilestudio.ui.polish

import android.content.Context
import android.content.SharedPreferences

class UIDensity(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ui_density", Context.MODE_PRIVATE)

    var density: Float
        get() = prefs.getFloat("density", 1.0f)
        set(value) = prefs.edit().putFloat("density", value).apply()

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, listOf("Density: $density"))
        listView.adapter = adapter
        return listView
    }
}