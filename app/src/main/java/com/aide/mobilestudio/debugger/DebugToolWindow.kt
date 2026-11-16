package com.aide.mobilestudio.debugger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Debug Tool Window - Android Studio style debugger UI.
 * Shows debugger connection status, threads, and breakpoints.
 */
class DebugToolWindow(
    private val host: Activity,
    private val projectDir: File,
    private val onClose: () -> Unit
) {

    private val session = DebugSession(host, 0, 0)

    companion object {
        const val ID = "Debug"
        const val DISPLAY_NAME = "Debug"
        const val ICON_RES = android.R.drawable.ic_menu_info_details
        const val SHORTCUT = "Alt+9"
    }

    /**
     * Shows the Debug tool window content with real debugger integration.
     */
    fun createContent(): View {
        val layout = LinearLayout(host)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Header
        val titleTv = TextView(host)
        titleTv.text = "Debug Tool Window"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Connection status
        statusTv = TextView(host)
        statusTv.text = "Status: Disconnected"
        statusTv.textSize = 14f
        statusTv.setPadding(16, 0, 16, 8)
        layout.addView(statusTv)

        // Button row
        val btnRow = LinearLayout(host)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(16, 8, 16, 8)

        val connectBtn = Button(host)
        connectBtn.text = "Connect"
        connectBtn.setOnClickListener {
            val ok = session.connect()
            statusTv.text = "Status: ${if (ok) "Connected" else "Failed"}"
            statusTv.setTextColor(if (ok) android.graphics.Color.parseColor("#00C853") else android.graphics.Color.parseColor("#FF5252"))
            updateThreadInfo()
        }
        btnRow.addView(connectBtn)

        val disconnectBtn = Button(host)
        disconnectBtn.text = "Disconnect"
        disconnectBtn.setOnClickListener {
            session.disconnect()
            statusTv.text = "Status: Disconnected"
            statusTv.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            threadInfoTv.text = "Threads: --\nBreakpoints: --"
        }
        btnRow.addView(disconnectBtn)

        layout.addView(btnRow)

        // Info area
        threadInfoTv = TextView(host).apply {
            text = "Threads: --\nBreakpoints: --"
            textSize = 12f
            setPadding(16, 8, 16, 16)
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        }
        layout.addView(threadInfoTv)

        return layout
    }

    private fun updateThreadInfo() {
        val threads = session.threads
        val bps = session.breakpoints
        threadInfoTv.text = "Threads: ${threads.size}\nBreakpoints: ${bps.size}\n${bps.joinToString("\n") { "[${it.id}] ${it.file}:${it.line}" }}"
    }

    private lateinit var statusTv: TextView
    private lateinit var threadInfoTv: TextView

    /** Activity that hosts the Debug Tool Window. */
    class DebugToolWindowActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = File(intent.getStringExtra("project_dir") ?: ".")
            val dtw = DebugToolWindow(this, projectDir) { finish() }
            setContentView(dtw.createContent())
        }
    }
}
