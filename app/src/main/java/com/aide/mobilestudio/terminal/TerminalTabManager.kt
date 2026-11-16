package com.aide.mobilestudio.terminal

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import java.io.File

/**
 * Terminal Tab Manager - terminal tab management.
 * Restored API surface for MainActivity compatibility.
 */
class TerminalTabManager(
    private val context: Context,
    private val container: ViewGroup,
    private val tabBar: LinearLayout,
    private val cwd: File
) {
    private val tabs = mutableListOf<TerminalView>()
    private var activeTabIndex = 0

    fun addTab(tab: TerminalView) {
        tabs.add(tab)
        activeTabIndex = tabs.lastIndex
    }

    fun addTab(title: String) {
        addTab(TerminalView(context, title))
    }

    fun getTabs(): List<TerminalView> = tabs

    fun getActiveTabIndex(): Int = activeTabIndex

    fun addNewTabButton() {
        val btn = Button(context)
        btn.text = "+"
        tabBar.addView(btn)
    }

    fun createNewTab(title: String = "Terminal ${tabs.size + 1}") {
        addTab(title)
    }

    fun executeCommand(command: String) {
        // Stub
    }

    fun getActiveTerminalView(): TerminalView? {
        return if (tabs.isNotEmpty()) tabs[activeTabIndex] else null
    }

    fun scrollToBottom() {
        // Stub
    }

    fun clearActiveTab() {
        // Stub
    }

    fun applyFontSize(size: Float) {
        // Stub
    }

    fun setOnCtrlStateChanged(listener: (Boolean) -> Unit) {
        // Stub
    }

    var ctrlTapped: Boolean = false
        private set

    var ctrlLongPressed: Boolean = false
        private set

    var keyBarEsc: Boolean = false
        private set

    var keyBarTab: Boolean = false
        private set

    var keyBarArrowUp: Boolean = false
        private set

    var keyBarArrowDown: Boolean = false
        private set

    var keyBarArrowLeft: Boolean = false
        private set

    var keyBarArrowRight: Boolean = false
        private set

    var keyBarEnter: Boolean = false
        private set

    var keyBarBackspace: Boolean = false
        private set

    fun isCtrlLatched(): Boolean = false

    fun isCtrlSticky(): Boolean = false

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, tabs.map { it.title })
        listView.adapter = adapter
        return listView
    }
}