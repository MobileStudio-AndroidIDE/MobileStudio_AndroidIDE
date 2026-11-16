package com.aide.mobilestudio.debugger

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

/**
 * Stack Frames Panel - displays call stack frames.
 */
class StackFramesPanel(
    private val context: Context
) {
    private val frames = mutableListOf<String>()

    fun addFrame(frame: String) {
        frames.add(frame)
    }

    fun removeFrame(frame: String) {
        frames.remove(frame)
    }

    fun getFrames(): List<String> = frames

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, frames)
        listView.adapter = adapter
        return listView
    }
}
