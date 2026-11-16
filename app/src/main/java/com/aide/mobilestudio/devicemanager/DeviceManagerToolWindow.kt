package com.aide.mobilestudio.devicemanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aide.mobilestudio.AdbDeviceManager
import java.io.File

/**
 * Device Manager Tool Window - Android Studio style device manager UI.
 * Shows connected ADB devices and device information.
 */
class DeviceManagerToolWindow(
    private val host: Activity,
    private val projectDir: File,
    private val onClose: () -> Unit
) {

    companion object {
        const val ID = "DeviceManager"
        const val DISPLAY_NAME = "Device Manager"
        const val ICON_RES = android.R.drawable.ic_menu_info_details
        const val SHORTCUT = "Alt+8"
    }

    /**
     * Shows the Device Manager tool window content with real ADB integration.
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
        titleTv.text = "Device Manager"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Device info area
        deviceInfoTv = TextView(host).apply {
            text = "Scanning devices..."
            textSize = 12f
            setPadding(16, 8, 16, 16)
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        }
        layout.addView(deviceInfoTv)

        // Button row
        val btnRow = LinearLayout(host)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(16, 8, 16, 8)

        val refreshBtn = Button(host)
        refreshBtn.text = "Refresh"
        refreshBtn.setOnClickListener {
            updateDeviceInfo()
        }
        btnRow.addView(refreshBtn)

        layout.addView(btnRow)

        updateDeviceInfo()
        return layout
    }

    private fun updateDeviceInfo() {
        val adbInfo = AdbDeviceManager.getCurrentAdbInfo()
        val devices = AdbDeviceManager.getDevices()
        val sb = StringBuilder()
        sb.append("ADB: ${if (adbInfo != null && adbInfo.isAvailable) "Available" else "Not available"}\n")
        sb.append("Host ABI: ${adbInfo?.hostAbi ?: "unknown"}\n")
        sb.append("Devices: ${devices.size}\n\n")
        devices.forEach { d ->
            sb.append("- ${d.serial} (${d.state.label})\n")
            sb.append("  Model: ${d.model}, API: ${d.apiLevel}, ABI: ${d.abi}\n")
        }
        if (devices.isEmpty()) {
            sb.append("No devices connected.\n")
        }
        deviceInfoTv.text = sb.toString()
    }

    private lateinit var deviceInfoTv: TextView

    class DeviceManagerToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = File(intent.getStringExtra("project_dir") ?: ".")
            val dmtw = DeviceManagerToolWindow(this, projectDir) { finish() }
            setContentView(dmtw.createContent())
        }
    }
}
