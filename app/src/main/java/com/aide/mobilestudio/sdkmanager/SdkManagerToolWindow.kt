package com.aide.mobilestudio.sdkmanager

import android.app.Activity
import android.content.Intent
import android.widget.TextView

/**
 * SDK Manager Tool Window - Android Studio style SDK Manager UI.
 * Simplified implementation for compilation.
 */
class SdkManagerToolWindow(
    private val host: Activity,
    private val projectDir: java.io.File,
    private val onClose: () -> Unit
) {
    companion object {
        fun show(host: Activity, projectDir: java.io.File, onClose: () -> Unit) {
            val intent = android.content.Intent(host, SdkManagerToolWindowActivity::class.java)
            intent.putExtra("project_dir", projectDir.absolutePath)
            host.startActivity(intent)
        }
    }

    class SdkManagerToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = java.io.File(intent.getStringExtra("project_dir") ?: ".")
            val tv = android.widget.TextView(this)
            tv.text = "SDK Manager\n\nProject: ${projectDir.absolutePath}\n\nSDK Manager ready."
            tv.textSize = 18f
            tv.setPadding(24, 24, 24, 24)
            setContentView(tv)
        }
    }
}