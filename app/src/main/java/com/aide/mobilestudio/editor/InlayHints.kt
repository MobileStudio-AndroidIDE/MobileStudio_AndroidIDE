package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Inlay Hints - parameter name hints in editor.
 */
class InlayHints(
    private val context: Context
) {
    private val hints = mutableListOf<String>()

    fun addHint(hint: String) {
        hints.add(hint)
    }

    fun getHints(): List<String> = hints

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, hints)
        listView.adapter = adapter
        return listView
    }
}
