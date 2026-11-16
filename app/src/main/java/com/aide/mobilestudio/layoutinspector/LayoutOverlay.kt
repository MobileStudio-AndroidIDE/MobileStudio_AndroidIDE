package com.aide.mobilestudio.layoutinspector

/**
 * Layout Overlay - layout overlay for inspector.
 */
class LayoutOverlay(
    private val context: android.content.Context
) {
    private val overlays = mutableListOf<String>()

    fun addOverlay(overlay: String) {
        overlays.add(overlay)
    }

    fun getOverlays(): List<String> = overlays

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, overlays)
        listView.adapter = adapter
        return listView
    }
}
