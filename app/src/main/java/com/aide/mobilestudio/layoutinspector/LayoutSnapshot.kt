package com.aide.mobilestudio.layoutinspector

/**
 * Layout Snapshot - layout snapshot management.
 */
class LayoutSnapshot(
    private val context: android.content.Context
) {
    private val snapshots = mutableListOf<String>()

    fun addSnapshot(snapshot: String) {
        snapshots.add(snapshot)
    }

    fun getSnapshots(): List<String> = snapshots

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, snapshots)
        listView.adapter = adapter
        return listView
    }
}
