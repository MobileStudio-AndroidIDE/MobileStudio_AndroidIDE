package com.aide.mobilestudio.editor.missing

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Editor Features - editor feature utilities.
 */
class EditorFeatures(
    private val context: Context
) {
    private val features = mutableListOf<String>()

    fun addFeature(feature: String) {
        features.add(feature)
    }

    fun getFeatures(): List<String> = features

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, features)
        listView.adapter = adapter
        return listView
    }
}
