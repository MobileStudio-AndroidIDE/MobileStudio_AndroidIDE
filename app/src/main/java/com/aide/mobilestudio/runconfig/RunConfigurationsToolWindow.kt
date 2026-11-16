package com.aide.mobilestudio.runconfig

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Run Configuration - base data class for run/debug configurations.
 */
data class RunConfiguration(
    val id: String,
    val name: String,
    val type: String = "ANDROID_APP"
)

/**
 * Deploy Target enum.
 */
enum class DeployTarget(val label: String) {
    DEBUG("Debug"),
    RELEASE("Release"),
    CHOOSE_AT_RUNTIME("Choose at Runtime")
}

/**
 * Run Configurations Tool Window - shows and manages run/debug configurations.
 */
class RunConfigurationsToolWindow(
    private val configurations: List<RunConfiguration> = emptyList()
) {

    companion object {
        const val ID = "RunConfigurations"
        const val DISPLAY_NAME = "Run Configurations"
        const val ICON_RES = android.R.drawable.ic_menu_info_details
        const val SHORTCUT = "Alt+Shift+F10"
    }

    /**
     * Shows the Run Configurations tool window content.
     */
    fun createContent(context: android.content.Context): View {
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Header
        val titleTv = TextView(context)
        titleTv.text = "Run Configurations"
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Config list
        val configs = if (configurations.isEmpty()) {
            listOf(RunConfiguration("default", "Default Android App", "ANDROID_APP"))
        } else {
            configurations
        }

        configs.forEach { config ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(16, 4, 16, 4)

            val nameTv = TextView(context)
            nameTv.text = config.name
            nameTv.textSize = 14f
            nameTv.setPadding(8, 8, 8, 8)
            row.addView(nameTv)

            val typeTv = TextView(context)
            typeTv.text = config.type
            typeTv.textSize = 12f
            typeTv.setPadding(8, 8, 8, 8)
            row.addView(typeTv)

            layout.addView(row)
        }

        // Add button
        val addBtn = Button(context)
        addBtn.text = "Add Configuration"
        addBtn.setPadding(16, 8, 16, 8)
        layout.addView(addBtn)

        return layout
    }
}
