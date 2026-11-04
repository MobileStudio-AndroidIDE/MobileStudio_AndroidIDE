package com.aide.mobilestudio.terminal

import android.content.Context
import com.aide.mobilestudio.StudioUi
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * Manages multiple terminal sessions with a tabbed UI (Windows Terminal style).
 * Keyboard input is handled directly by TerminalView.
 */
class TerminalTabManager(
    private val context: Context,
    private val container: FrameLayout,
    private val tabContainer: LinearLayout,
    private val initialCwd: File? = null
) {
    private val sessions = mutableListOf<TerminalSessionData>()
    private var activeIndex = -1
    private var onActiveSessionChange: ((TerminalSession?) -> Unit)? = null
    private var onCtrlStateChanged: (() -> Unit)? = null
    private var nextTabId = 1

    data class TerminalSessionData(
        val session: TerminalSession,
        val view: TerminalView,
        val tabButton: TextView
    )

    fun createNewTab(title: String? = null): TerminalSession {
        val tabTitle = title ?: "Shell $nextTabId"
        val tabId = "tab-$nextTabId"
        nextTabId++

        // Create terminal view (handles keyboard input directly)
        val terminalView = TerminalView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setFontSize(loadTerminalFontSize())
            // TerminalView handles all keyboard input via onKeyDown
        }

        // Create tab button
        val tabButton = createTabButton(tabTitle, tabId)

        // Create session
        val session = TerminalSession(
            context = context,
            id = tabId,
            initialCwd = initialCwd,
            title = tabTitle
        )

        val data = TerminalSessionData(session, terminalView, tabButton)
        sessions.add(data)

        // Add tab button to tab strip (before the + button if it exists)
        val plusIndex = findPlusButtonIndex()
        if (plusIndex >= 0) {
            tabContainer.addView(tabButton, plusIndex, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        } else {
            tabContainer.addView(tabButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // Start session
        session.start()
        terminalView.setSession(session)

        // Forward Ctrl modifier changes so the key bar button can update.
        terminalView.onCtrlStateChanged = { _, _ ->
            this@TerminalTabManager.onCtrlStateChanged?.invoke()
        }

        // Focus the terminal view for keyboard input
        terminalView.requestFocus()

        // Switch to new tab
        switchToTab(sessions.indexOf(data))

        return session
    }

    private fun findPlusButtonIndex(): Int {
        for (i in 0 until tabContainer.childCount) {
            val child = tabContainer.getChildAt(i)
            if (child is TextView && child.text.toString().contains("+")) {
                return i
            }
        }
        return -1
    }

    private fun createTabButton(title: String, id: String): TextView {
        return TextView(context).apply {
            text = " $title "
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            gravity = Gravity.CENTER
            isSingleLine = true

            // Tab background
            background = GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.Surface2))
                cornerRadius = dp(6).toFloat()
            }

            setOnClickListener {
                val idx = sessions.indexOfFirst { it.session.getId() == id }
                if (idx >= 0) switchToTab(idx)
            }

            // Long press to rename or close
            setOnLongClickListener {
                showTabContextMenu(id)
                true
            }
        }
    }

    private fun showTabContextMenu(tabId: String) {
        val data = sessions.find { it.session.getId() == tabId } ?: return
        val idx = sessions.indexOf(data)

        val items = arrayOf("Rename", "Close Tab", "Close Others")
        android.app.AlertDialog.Builder(context)
            .setTitle(data.session.currentTitle)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(data)
                    1 -> closeTab(idx)
                    2 -> closeOtherTabs(idx)
                }
            }
            .show()
    }

    private fun showRenameDialog(data: TerminalSessionData) {
        val input = EditText(context).apply {
            hint = "Tab name"
            setText(data.session.currentTitle)
            setSelection(data.session.currentTitle.length)
            setSingleLine(true)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.TextSoft))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("Rename tab")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    data.session.updateTitle(newName)
                    data.tabButton.text = " $newName "
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.post {
            input.requestFocus()
            input.selectAll()
        }
    }

    fun switchToTab(index: Int) {
        if (index < 0 || index >= sessions.size) return

        // Hide all
        sessions.forEach { data ->
            container.removeView(data.view)
            data.tabButton.setTextColor(StudioUi.color(StudioUi.TextSoft))
            data.tabButton.background?.setTint(StudioUi.color(StudioUi.Surface2))
        }

        // Show active
        val data = sessions[index]
        container.addView(data.view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Highlight active tab
        data.tabButton.setTextColor(StudioUi.color(StudioUi.ActivePurple))
        data.tabButton.background?.setTint(StudioUi.color(StudioUi.ButtonNormal))

        activeIndex = index

        // Focus the terminal view for keyboard input
        data.view.requestFocus()

        onActiveSessionChange?.invoke(data.session)

        // The Ctrl key bar button must reflect the newly active view.
        onCtrlStateChanged?.invoke()
    }

    fun closeTab(index: Int) {
        if (index < 0 || index >= sessions.size) return
        val data = sessions.removeAt(index)
        data.session.destroy()
        tabContainer.removeView(data.tabButton)
        container.removeView(data.view)

        if (sessions.isEmpty()) {
            createNewTab()
        } else {
            switchToTab(index.coerceAtMost(sessions.size - 1))
        }
    }

    fun closeOtherTabs(keepIndex: Int) {
        if (keepIndex < 0 || keepIndex >= sessions.size) return
        val keep = sessions[keepIndex]
        sessions.filter { it != keep }.forEach { data ->
            data.session.destroy()
            tabContainer.removeView(data.tabButton)
            container.removeView(data.view)
        }
        sessions.clear()
        sessions.add(keep)
        switchToTab(0)
    }

    fun closeAllTabs() {
        sessions.forEach { it.session.destroy() }
        sessions.clear()
        tabContainer.removeAllViews()
        container.removeAllViews()
    }

    fun getActiveSession(): TerminalSession? {
        return sessions.getOrNull(activeIndex)?.session
    }

    fun getActiveTerminalView(): TerminalView? {
        return sessions.getOrNull(activeIndex)?.view
    }

    fun getCurrentSession(): TerminalSessionData? {
        return sessions.getOrNull(activeIndex)
    }

    fun getSessionCount(): Int = sessions.size

    fun getActiveIndex(): Int = activeIndex

    fun applyFontSize(sizePx: Float) {
        sessions.forEach { it.view.setFontSize(sizePx) }
    }

    private fun loadTerminalFontSize(): Float {
        return context.getSharedPreferences("terminal_options", Context.MODE_PRIVATE)
            .getFloat("font_size", 12f)
    }

    fun executeCommand(command: String) {
        val session = getActiveSession() ?: return
        session.sendCommand(command)
        // Focus the terminal view after command
        sessions.getOrNull(activeIndex)?.view?.requestFocus()
    }

    fun setOnActiveSessionChange(listener: (TerminalSession?) -> Unit) {
        onActiveSessionChange = listener
    }

    fun setOnCtrlStateChanged(listener: () -> Unit) {
        onCtrlStateChanged = listener
    }

    fun scrollToBottom() {
        sessions.getOrNull(activeIndex)?.view?.scrollToBottom()
    }

    fun clearActiveTab() {
        sessions.getOrNull(activeIndex)?.view?.clear()
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    /**
     * Adds a "+" button at the end of the tab strip for creating new tabs.
     */
    fun addNewTabButton() {
        val plusBtn = TextView(context).apply {
            text = " + "
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(6).toFloat()
                setStroke(1, StudioUi.color(StudioUi.Border))
            }
            setOnClickListener { createNewTab() }
        }
        tabContainer.addView(plusBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
}
