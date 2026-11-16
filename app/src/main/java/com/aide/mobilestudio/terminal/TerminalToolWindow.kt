package com.aide.mobilestudio.terminal

import android.app.Activity
import android.content.Intent
import android.widget.TextView

/**
 * Terminal Tool Window - terminal tool window UI.
 * Simplified implementation for compilation.
 */
class TerminalToolWindow(
    private val host: Activity,
    private val projectDir: java.io.File,
    private val onClose: () -> Unit
) {
    companion object {
        fun show(host: Activity, projectDir: java.io.File, onClose: () -> Unit) {
            val intent = android.content.Intent(host, TerminalToolWindowActivity::class.java)
            intent.putExtra("project_dir", projectDir.absolutePath)
            host.startActivity(intent)
        }
    }

    class TerminalToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = java.io.File(intent.getStringExtra("project_dir") ?: ".")
            val tv = android.widget.TextView(this)
            tv.text = "Terminal Tool Window\n\nProject: ${projectDir.absolutePath}\n\nTerminal ready."
            tv.textSize = 18f
            tv.setPadding(24, 24, 24, 24)
            setContentView(tv)
        }
    }
}