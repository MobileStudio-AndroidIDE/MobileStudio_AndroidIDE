package com.aide.mobilestudio.devicemanager.emulator

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Emulator Controller Panel - emulator control panel.
 */
class EmulatorControllerPanel(
    private val context: Context
) {
    private val controls = mutableListOf<String>()

    fun addControl(control: String) {
        controls.add(control)
    }

    fun getControls(): List<String> = controls

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, controls)
        listView.adapter = adapter
        return listView
    }
}
