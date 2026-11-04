package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BackgroundTaskManager - Shows running/completed background Gradle builds.
 * Accessible from status bar or Build menu.
 */
class BackgroundTaskManager(private val context: Context) {

    data class TaskRecord(
        val tasks: List<String>,
        val projectDir: File,
        val startTime: Long,
        var endTime: Long = 0,
        var success: Boolean = false,
        var output: String = ""
    )

    private val history = mutableListOf<TaskRecord>()
    private var currentTask: TaskRecord? = null

    fun onTaskStarted(tasks: List<String>, projectDir: File) {
        currentTask = TaskRecord(tasks, projectDir, System.currentTimeMillis())
    }

    fun onTaskCompleted(success: Boolean, output: String = "") {
        currentTask?.let { record ->
            record.endTime = System.currentTimeMillis()
            record.success = success
            record.output = output
            history.add(record)
            if (history.size > 20) history.removeFirst()
        }
        currentTask = null
    }

    fun getStatusText(): String {
        val running = currentTask
        return if (running != null) {
            val elapsed = (System.currentTimeMillis() - running.startTime) / 1000
            "Running: ${running.tasks.joinToString(" ")} (${elapsed}s)"
        } else {
            val last = history.lastOrNull()
            if (last != null) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                val time = sdf.format(Date(last.startTime))
                val status = if (last.success) "OK" else "FAIL"
                "Last: $time $status"
            } else ""
        }
    }

    fun isRunning(): Boolean = currentTask != null

    fun showHistoryDialog() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        if (currentTask != null) {
            val running = currentTask!!
            val elapsed = (System.currentTimeMillis() - running.startTime) / 1000
            root.addView(sectionLabel("Currently Running"))
            root.addView(taskRow("Tasks", running.tasks.joinToString(" ")))
            root.addView(taskRow("Project", running.projectDir.name))
            root.addView(taskRow("Elapsed", "${elapsed}s"))
            root.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, 16, 0, 8) }
                setBackgroundColor(StudioUi.color(StudioUi.TextSoft))
            })
        }

        if (history.isNotEmpty()) {
            root.addView(sectionLabel("Build History"))
            for (record in history.reversed()) {
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val time = sdf.format(Date(record.startTime))
                val duration = if (record.endTime > 0) "${(record.endTime - record.startTime) / 1000}s" else "running"
                val status = if (record.success) "\u2714 OK" else "\u2716 FAIL"
                val statusColor = if (record.success) "#46C07D" else "#F05C5C"

                val entry = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                entry.addView(TextView(context).apply {
                    text = "$status  ${record.tasks.joinToString(" ")}"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor(statusColor))
                })
                entry.addView(TextView(context).apply {
                    text = "  $time | $duration | ${record.projectDir.name}"
                    textSize = 10f
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                })
                root.addView(entry)
                root.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                })
            }
        } else if (currentTask == null) {
            root.addView(TextView(context).apply {
                text = "No build history yet."
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(16), 0, 0)
            })
        }

        val scroll = ScrollView(context).apply {
            addView(root)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        AlertDialog.Builder(context)
            .setTitle("Background Builds")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .apply {
                if (history.isNotEmpty()) {
                    setNegativeButton("Clear History") { _, _ ->
                        history.clear()
                    }
                }
            }
            .show()
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(StudioUi.color(StudioUi.Text))
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun taskRow(label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(context).apply {
                text = "$label: "
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
            })
            addView(TextView(context).apply {
                text = value
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.Text))
            })
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
