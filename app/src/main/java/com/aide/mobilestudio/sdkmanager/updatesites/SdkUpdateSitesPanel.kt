package com.aide.mobilestudio.sdkmanager.updatesites

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * SDK Update Sites Panel - SDK update sites management panel.
 * Simplified implementation for compilation.
 */
class SdkUpdateSitesPanel(
    private val context: Context
) {
    private val sites = mutableListOf<String>()

    fun addSite(site: String) {
        sites.add(site)
    }

    fun getSites(): List<String> = sites

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, sites)
        listView.adapter = adapter
        return listView
    }
}