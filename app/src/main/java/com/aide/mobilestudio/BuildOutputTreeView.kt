package com.aide.mobilestudio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.CopyOnWriteArrayList

class BuildOutputTreeView(context: Context) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val entries = CopyOnWriteArrayList<BuildEntry>()
    private var currentTask: BuildEntry? = null

    data class BuildEntry(
        val id: String,
        val name: String,
        val type: Type,
        val startTimeMs: Long = System.currentTimeMillis(),
        var endTimeMs: Long = 0L,
        var status: Status = Status.RUNNING,
        val indentation: Int = 0,
        val children: MutableList<BuildEntry> = mutableListOf()
    ) {
        enum class Type { TASK, STEP, INFO, ERROR, WARNING }
        enum class Status { RUNNING, SUCCESS, FAILED, SKIPPED }
    }

    init {
        addView(container)
        isVerticalScrollBarEnabled = true
    }

    fun beginBuild(projectName: String) {
        entries.clear()
        container.removeAllViews()
        val entry = BuildEntry("build", "Build $projectName", BuildEntry.Type.INFO)
        entries.add(entry)
        addEntryView(entry)
    }

    fun beginTask(taskPath: String) {
        val entry = BuildEntry(taskPath, taskPath.substringAfterLast(":"), BuildEntry.Type.TASK)
        entries.add(entry)
        currentTask = entry
        addEntryView(entry)
    }

    fun addStep(message: String) {
        val entry = BuildEntry("step_${System.currentTimeMillis()}", message, BuildEntry.Type.STEP, indentation = 1)
        entries.add(entry)
        addEntryView(entry)
    }

    fun addInfo(message: String) {
        val entry = BuildEntry("info_${System.currentTimeMillis()}", message, BuildEntry.Type.INFO)
        entries.add(entry)
        addEntryView(entry)
    }

    fun addError(message: String) {
        val entry = BuildEntry("err_${System.currentTimeMillis()}", message, BuildEntry.Type.ERROR)
        entries.add(entry)
        addEntryView(entry)
    }

    fun addWarning(message: String) {
        val entry = BuildEntry("warn_${System.currentTimeMillis()}", message, BuildEntry.Type.WARNING)
        entries.add(entry)
        addEntryView(entry)
    }

    fun endTask(taskPath: String, success: Boolean) {
        val entry = entries.lastOrNull { it.id == taskPath && it.type == BuildEntry.Type.TASK }
        if (entry != null) {
            entry.endTimeMs = System.currentTimeMillis()
            entry.status = if (success) BuildEntry.Status.SUCCESS else BuildEntry.Status.FAILED
            updateEntryView(entry)
        }
        currentTask = null
    }

    fun endBuild(success: Boolean, durationMs: Long) {
        val buildEntry = entries.firstOrNull { it.id == "build" }
        if (buildEntry != null) {
            buildEntry.endTimeMs = System.currentTimeMillis()
            buildEntry.status = if (success) BuildEntry.Status.SUCCESS else BuildEntry.Status.FAILED
            updateEntryView(buildEntry)
        }
        // Summary
        val summary = if (success) "Build finished" else "Build finished with errors"
        val summaryEntry = BuildEntry("summary", summary, BuildEntry.Type.INFO)
        addEntryView(summaryEntry)
    }

    fun clear() {
        entries.clear()
        container.removeAllViews()
        currentTask = null
    }

    @SuppressLint("SetTextI18n")
    private fun addEntryView(entry: BuildEntry) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4 * entry.indentation), dp(2), dp(4), dp(2))
            tag = entry.id
        }

        // Icon
        val icon = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(4), 0)
            when (entry.type) {
                BuildEntry.Type.TASK -> { text = "\u25B6"; setTextColor(c("#46C07D")) }
                BuildEntry.Type.STEP -> { text = "  \u2518"; setTextColor(c("#8E99AC")) }
                BuildEntry.Type.INFO -> { text = "\u2139"; setTextColor(c("#4F8EF7")) }
                BuildEntry.Type.ERROR -> { text = "\u2716"; setTextColor(c("#F05C5C")) }
                BuildEntry.Type.WARNING -> { text = "\u26A0"; setTextColor(c("#E3B963")) }
            }
        }
        row.addView(icon)

        // Name
        val nameTv = TextView(context).apply {
            text = entry.name
            textSize = 11f
            typeface = Typeface.MONOSPACE
            maxLines = 2
            when (entry.type) {
                BuildEntry.Type.TASK -> setTextColor(c("#C3CCD8"))
                BuildEntry.Type.ERROR -> setTextColor(c("#F05C5C"))
                BuildEntry.Type.WARNING -> setTextColor(c("#E3B963"))
                else -> setTextColor(c("#8E99AC"))
            }
        }
        row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Duration (for tasks)
        if (entry.type == BuildEntry.Type.TASK) {
            val durationTv = TextView(context).apply {
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(c("#8E99AC"))
                text = "running..."
                tag = "duration"
            }
            row.addView(durationTv)
        }

        // Status indicator
        val statusTv = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), 0, 0, 0)
            tag = "status"
            when (entry.status) {
                BuildEntry.Status.RUNNING -> { text = ""; setTextColor(c("#8E99AC")) }
                BuildEntry.Status.SUCCESS -> { text = "\u2714"; setTextColor(c("#46C07D")) }
                BuildEntry.Status.FAILED -> { text = "\u2716"; setTextColor(c("#F05C5C")) }
                BuildEntry.Status.SKIPPED -> { text = "\u2500"; setTextColor(c("#8E99AC")) }
            }
        }
        row.addView(statusTv)

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    @SuppressLint("SetTextI18n")
    private fun updateEntryView(entry: BuildEntry) {
        val row = container.findViewWithTag<View>(entry.id) as? LinearLayout ?: return
        val durationTv = row.findViewWithTag<TextView>("duration")
        val statusTv = row.findViewWithTag<TextView>("status")

        if (entry.endTimeMs > 0) {
            val dur = entry.endTimeMs - entry.startTimeMs
            durationTv?.text = formatDuration(dur)
        }

        when (entry.status) {
            BuildEntry.Status.SUCCESS -> { statusTv?.text = "\u2714"; statusTv?.setTextColor(c("#46C07D")) }
            BuildEntry.Status.FAILED -> { statusTv?.text = "\u2716"; statusTv?.setTextColor(c("#F05C5C")) }
            BuildEntry.Status.SKIPPED -> { statusTv?.text = "\u2500"; statusTv?.setTextColor(c("#8E99AC")) }
            else -> {}
        }
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
            else -> "${ms / 60_000}m ${ms % 60_000 / 1000}s"
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun c(hex: String): Int = StudioUi.color(hex)
}
