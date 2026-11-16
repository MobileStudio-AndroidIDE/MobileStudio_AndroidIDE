package com.aide.mobilestudio.layoutinspector

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Layout Inspector Tool Window - layout inspector UI.
 * Shows layout hierarchy and view attributes.
 */
class LayoutInspectorToolWindow(
    private val host: Activity,
    private val projectDir: java.io.File,
    private val onClose: () -> Unit
) {

    companion object {
        const val ID = "LayoutInspector"
        const val DISPLAY_NAME = "Layout Inspector"
        const val ICON_RES = android.R.drawable.ic_menu_gallery
        const val SHORTCUT = "Alt+5"

        fun show(host: Activity, projectDir: File, onClose: () -> Unit) {
            val intent = Intent(host, LayoutInspectorToolWindowActivity::class.java)
            intent.putExtra("project_dir", projectDir.absolutePath)
            host.startActivity(intent)
        }
    }

    /**
     * Shows the Layout Inspector tool window content.
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
        titleTv.text = "Layout Inspector"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Info area
        infoTv = TextView(host).apply {
            text = "Layout Inspector ready.\nSelect a view in the layout editor to inspect its properties."
            textSize = 12f
            setPadding(16, 8, 16, 16)
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        }
        layout.addView(infoTv)

        // Button row
        val btnRow = LinearLayout(host)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(16, 8, 16, 8)

        val refreshBtn = Button(host)
        refreshBtn.text = "Refresh"
        refreshBtn.setOnClickListener {
            infoTv.text = "Layout Inspector refreshed.\nProject: $projectDir"
        }
        btnRow.addView(refreshBtn)

        layout.addView(btnRow)

        return layout
    }

    private lateinit var infoTv: TextView

    class LayoutInspectorToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = java.io.File(intent.getStringExtra("project_dir") ?: ".")
            val litw = LayoutInspectorToolWindow(this, projectDir) { finish() }
            setContentView(litw.createContent())
        }
    }
}
