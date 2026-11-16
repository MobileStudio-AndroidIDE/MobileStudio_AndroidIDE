package com.aide.mobilestudio.buildtoolwindow

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aide.mobilestudio.GradleRunner
import com.aide.mobilestudio.BuildToolsConfig
import java.io.File

/**
 * Build Tool Window - Android Studio equivalent Build tool window.
 * Displays Gradle sync status, build task tree, and build output.
 */
class BuildToolWindow(
    private val context: Context,
    private val gradleRunner: GradleRunner,
    private val gradleSyncManager: com.aide.mobilestudio.GradleSyncManager,
    private val installer: com.aide.mobilestudio.JreGradleInstaller,
    private val projectDir: File
) {

    private val executor = GradleExecutor(context, gradleRunner, projectDir)

    companion object {
        const val ID = "Build"
        const val DISPLAY_NAME = "Build"
        const val ICON_RES = android.R.drawable.ic_menu_manage
        const val SHORTCUT = "Alt+0"
    }

    /**
     * Shows the Build tool window content with real Gradle integration.
     */
    fun createContent(): View {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Header
        val titleTv = TextView(context)
        titleTv.text = "Build Tool Window"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Gradle status
        val statusTv = TextView(context)
        statusTv.text = "Gradle: ${if (installer.isGradleInstalled) "Installed" else "Not installed"}"
        statusTv.textSize = 14f
        statusTv.setPadding(16, 0, 16, 8)
        layout.addView(statusTv)

        // Task buttons row
        val btnRow = LinearLayout(context)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(16, 8, 16, 8)

        val assembleBtn = Button(context)
        assembleBtn.text = "assembleDebug"
        assembleBtn.setOnClickListener {
            val result = executor.execute("assembleDebug")
            outputTv.text = "Task: assembleDebug\nSuccess: ${result.success}\nDuration: ${result.durationMs}ms\n${if (result.errorMessage != null) "Error: ${result.errorMessage}" else ""}"
        }
        btnRow.addView(assembleBtn)

        val cleanBtn = Button(context)
        cleanBtn.text = "clean"
        cleanBtn.setOnClickListener {
            val result = executor.execute("clean")
            outputTv.text = "Task: clean\nSuccess: ${result.success}\nDuration: ${result.durationMs}ms\n${if (result.errorMessage != null) "Error: ${result.errorMessage}" else ""}"
        }
        btnRow.addView(cleanBtn)

        layout.addView(btnRow)

        // Output area
        outputTv = TextView(context).apply {
            text = "Ready. Select a task to run."
            textSize = 12f
            setPadding(16, 8, 16, 16)
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E"))
        }
        layout.addView(outputTv)

        return layout
    }

    private lateinit var outputTv: TextView
}
