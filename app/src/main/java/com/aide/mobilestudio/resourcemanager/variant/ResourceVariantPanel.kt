package com.aide.mobilestudio.resourcemanager.variant

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Resource Variant Panel - resource variant panel.
 */
class ResourceVariantPanel(
    private val context: Context
) {
    private val variants = mutableListOf<String>()

    fun addVariant(variant: String) {
        variants.add(variant)
    }

    fun getVariants(): List<String> = variants

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, variants)
        listView.adapter = adapter
        return listView
    }
}
