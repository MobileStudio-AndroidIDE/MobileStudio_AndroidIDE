package com.aide.mobilestudio.ui.toolwindow

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

enum class ToolWindowAnchor {
    LEFT, RIGHT, BOTTOM, TOP
}

class ToolWindowAnchorList(
    private val context: Context
) {
    private val anchors = mutableListOf<String>()

    fun addAnchor(anchor: String) {
        anchors.add(anchor)
    }

    fun getAnchors(): List<String> = anchors

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, anchors)
        listView.adapter = adapter
        return listView
    }
}