package com.aide.mobilestudio.devicemanager.pairing

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Device Pairing Panel - device pairing UI.
 */
class DevicePairingPanel(
    private val context: Context
) {
    private val pairings = mutableListOf<String>()

    fun addPairing(pairing: String) {
        pairings.add(pairing)
    }

    fun getPairings(): List<String> = pairings

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, pairings)
        listView.adapter = adapter
        return listView
    }
}
