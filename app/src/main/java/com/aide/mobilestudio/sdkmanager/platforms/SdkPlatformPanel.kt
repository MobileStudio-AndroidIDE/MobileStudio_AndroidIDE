package com.aide.mobilestudio.sdkmanager.platforms

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * SDK Platform Panel - SDK platform management panel.
 * Simplified implementation for compilation.
 */
class SdkPlatformPanel(
    private val context: Context
) {
    private val platforms = mutableListOf<String>()

    fun addPlatform(platform: String) {
        platforms.add(platform)
    }

    fun getPlatforms(): List<String> = platforms

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, platforms)
        listView.adapter = adapter
        return listView
    }
}