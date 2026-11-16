package com.aide.mobilestudio.resourcemanager

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.widget.TextView

/**
 * Resource Manager Tool Window - resource manager tool window UI.
 */
class ResourceManagerToolWindow(
    private val host: Activity,
    private val projectDir: java.io.File,
    private val onClose: () -> Unit
) {
    fun createContent(): android.view.View {
        val layout = android.widget.LinearLayout(host)
        layout.orientation = android.widget.LinearLayout.VERTICAL

        val titleTv = android.widget.TextView(host)
        titleTv.text = "Resource Manager"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 16)
        layout.addView(titleTv)

        val statusTv = android.widget.TextView(host)
        statusTv.text = "Resource Manager ready"
        statusTv.textSize = 14f
        layout.addView(statusTv)

        return layout
    }

    companion object {
        fun show(host: Activity, projectDir: java.io.File, onClose: () -> Unit) {
            val intent = android.content.Intent(host, ResourceManagerToolWindowActivity::class.java)
            intent.putExtra("project_dir", projectDir.absolutePath)
            host.startActivity(intent)
        }
    }

    class ResourceManagerToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = java.io.File(intent.getStringExtra("project_dir") ?: ".")
            val tv = android.widget.TextView(this)
            tv.text = "Resource Manager\n\nProject: ${projectDir.absolutePath}\n\nResource Manager ready."
            tv.textSize = 18f
            tv.setPadding(24, 24, 24, 24)
            setContentView(tv)
        }
    }
}
