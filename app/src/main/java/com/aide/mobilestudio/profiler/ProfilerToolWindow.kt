package com.aide.mobilestudio.profiler

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.concurrent.thread

/**
 * Profiler Tool Window - profiler tool window.
 * Shows CPU, memory, and thread information.
 */
class ProfilerToolWindow(
    private val host: Activity,
    private val projectDir: File,
    private val onClose: () -> Unit
) {

    companion object {
        const val ID = "Profiler"
        const val DISPLAY_NAME = "Profiler"
        const val ICON_RES = android.R.drawable.ic_menu_info_details
        const val SHORTCUT = "Alt+7"
    }

    /**
     * Shows the Profiler tool window content with real system info.
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
        titleTv.text = "Profiler Tool Window"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Info area
        infoTv = TextView(host).apply {
            text = "Gathering system info..."
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
            updateInfo()
        }
        btnRow.addView(refreshBtn)

        layout.addView(btnRow)

        updateInfo()
        return layout
    }

    private fun updateInfo() {
        thread(isDaemon = true) {
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val maxMem = runtime.maxMemory() / 1024 / 1024
            val usedMem = totalMem - freeMem
            val cpuCores = runtime.availableProcessors()

            val sb = StringBuilder()
            sb.append("=== System Information ===\n")
            sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            sb.append("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
            sb.append("CPU Cores: $cpuCores\n")
            sb.append("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}\n\n")
            sb.append("=== Memory ===\n")
            sb.append("Total: $totalMem MB\n")
            sb.append("Used: $usedMem MB\n")
            sb.append("Free: $freeMem MB\n")
            sb.append("Max: $maxMem MB\n\n")
            sb.append("=== Threads ===\n")
            sb.append("Active: ${Thread.activeCount()}\n")

            host.runOnUiThread {
                infoTv.text = sb.toString()
            }
        }
    }

    private lateinit var infoTv: TextView

    class ProfilerToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = File(intent.getStringExtra("project_dir") ?: ".")
            val ptw = ProfilerToolWindow(this, projectDir) { finish() }
            setContentView(ptw.createContent())
        }
    }
}
