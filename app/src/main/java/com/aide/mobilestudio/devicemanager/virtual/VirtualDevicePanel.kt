package com.aide.mobilestudio.devicemanager.virtual

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Virtual Device Panel - virtual device management.
 */
class VirtualDevicePanel(
    private val context: Context
) {
    private val devices = mutableListOf<String>()

    fun addDevice(device: String) {
        devices.add(device)
    }

    fun getDevices(): List<String> = devices

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, devices)
        listView.adapter = adapter
        return listView
    }
}
