package com.aide.mobilestudio.runconfig

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Run Configuration Templates - default templates for run configurations.
 * Simplified implementation for compilation.
 */
class RunConfigurationTemplates(
    private val context: Context
) {
    private val templates = mutableListOf<String>()

    init {
        templates.add("Android App")
        templates.add("Gradle Task")
        templates.add("Shell Script")
        templates.add("Remote Debug")
        templates.add("Kotlin Script")
    }

    fun getTemplates(): List<String> = templates

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, templates)
        listView.adapter = adapter
        return listView
    }
}