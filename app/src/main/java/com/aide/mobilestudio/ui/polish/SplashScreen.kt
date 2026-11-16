package com.aide.mobilestudio.ui.polish

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Splash Screen - IDE splash screen.
 * Simplified implementation for compilation.
 */
class SplashScreen(
    private val context: Context
) {
    private val messages = mutableListOf<String>()

    fun addMessage(message: String) {
        messages.add(message)
    }

    fun getMessages(): List<String> = messages

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, messages)
        listView.adapter = adapter
        return listView
    }
}