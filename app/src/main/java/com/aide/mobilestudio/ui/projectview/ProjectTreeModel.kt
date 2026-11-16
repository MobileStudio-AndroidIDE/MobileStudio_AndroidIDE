package com.aide.mobilestudio.ui.projectview

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class ProjectTreeModel(
    private val context: Context
) {
    private val nodes = mutableListOf<String>()

    fun addNode(node: String) {
        nodes.add(node)
    }

    fun getNodes(): List<String> = nodes

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, nodes)
        listView.adapter = adapter
        return listView
    }
}