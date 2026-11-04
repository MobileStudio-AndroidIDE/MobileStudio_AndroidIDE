package com.aide.mobilestudio.validation

import android.app.Activity
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.util.Locale

/**
 * "Inspect Project" UI: a progress dialog with a per-check checklist while the
 * inspection runs on a background thread (Cancel supported), followed by a
 * results dialog whose issue rows navigate to file:line.
 */
object ProjectInspectionDialog {

    fun show(
        activity: Activity,
        projectRoot: File,
        onNavigate: (File, Int) -> Unit,
        onShowInProblems: (InspectionReport) -> Unit
    ) {
        val context = activity.applicationContext
        val toolchain = DeviceValidationToolchain.create(context)
        val runner = DeviceProcessRunner(context)
        val cacheDir = File(context.filesDir, ".cache/validator/cache")
        val tempDir = File(context.filesDir, ".cache/validator/tmp")
        runCatching { tempDir.deleteRecursively() }
        runCatching { tempDir.mkdirs() }
        val cache = runCatching { ValidationCache(cacheDir) }.getOrNull()

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        rootLayout.addView(TextView(activity).apply {
            text = projectRoot.name
            textSize = 12f
            setTextColor(StudioUi.palette.textSoft)
        })
        val stageText = TextView(activity).apply {
            text = "Preparing..."
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setPadding(0, dp(8), 0, dp(4))
        }
        rootLayout.addView(stageText)
        rootLayout.addView(ProgressBar(activity).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ).apply { topMargin = dp(4); bottomMargin = dp(8) }
        })

        val rowViews = LinkedHashMap<String, TextView>()
        for (check in ProjectInspector.defaultChecks()) {
            val row = TextView(activity).apply {
                text = "○  ${check.displayName}"
                textSize = 13f
                setTextColor(StudioUi.palette.textSoft)
                setPadding(0, dp(2), 0, dp(2))
            }
            rowViews[check.id] = row
            rootLayout.addView(row)
        }

        fun setRow(id: String, text: String, color: Int) {
            rowViews[id]?.let { row ->
                row.text = text
                row.setTextColor(color)
            }
        }

        val inspector = ProjectInspector(
            projectRoot = projectRoot,
            toolchain = toolchain,
            runner = runner,
            cache = cache,
            tempDir = tempDir,
            onStage = { stage ->
                activity.runOnUiThread {
                    stageText.text = stage
                    ProjectInspector.defaultChecks()
                        .firstOrNull { it.progressLabel == stage }
                        ?.let { check -> setRow(check.id, "◐  ${check.displayName}", StudioUi.palette.accent) }
                }
            },
            onCheckDone = { result ->
                activity.runOnUiThread {
                    val color = when (result.status) {
                        CheckStatus.FAILED -> StudioUi.palette.error
                        CheckStatus.ISSUES -> StudioUi.palette.warning
                        CheckStatus.OK -> StudioUi.palette.accent
                        else -> StudioUi.palette.textSoft
                    }
                    setRow(result.id, "${statusIcon(result.status)}  ${result.displayName}", color)
                }
            }
        )

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Project Inspection")
            .setView(rootLayout)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                inspector.cancel()
                stageText.text = "Cancelling..."
            }
            .show()

        Thread {
            val report = inspector.inspect()
            activity.runOnUiThread {
                runCatching { dialog.dismiss() }
                showResults(activity, report, rowViews, onNavigate, onShowInProblems)
            }
        }.apply {
            isDaemon = true
            name = "project-inspection"
            start()
        }
    }

    private fun showResults(
        activity: Activity,
        report: InspectionReport,
        rowViews: Map<String, TextView>,
        onNavigate: (File, Int) -> Unit,
        onShowInProblems: (InspectionReport) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        for (result in report.checks) {
            val color = when (result.status) {
                CheckStatus.FAILED -> StudioUi.palette.error
                CheckStatus.ISSUES ->
                    if (result.issues.any { it.severity == InspectionSeverity.ERROR })
                        StudioUi.palette.error else StudioUi.palette.warning
                CheckStatus.OK -> StudioUi.palette.accent
                else -> StudioUi.palette.textSoft
            }
            rowViews[result.id]?.apply {
                text = "${statusIcon(result.status)}  ${result.displayName}"
                setTextColor(color)
            }
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val summary = buildString {
            append("✖ ${report.errorCount} errors   ⚠ ${report.warningCount} warnings")
            append(String.format(Locale.US, "   %.1fs", report.durationMs / 1000.0))
            if (report.cancelled) append(" (cancelled)")
        }
        content.addView(TextView(activity).apply {
            text = summary
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setPadding(0, 0, 0, dp(8))
        })

        for (result in report.checks) {
            val suffix = if (result.issues.isNotEmpty()) " (${result.issues.size})" else ""
            val line = "${statusIcon(result.status)} ${result.displayName} — ${result.status.name.lowercase(Locale.US)}$suffix"
            content.addView(TextView(activity).apply {
                text = line
                textSize = 12f
                setTextColor(when (result.status) {
                    CheckStatus.FAILED -> StudioUi.palette.error
                    CheckStatus.ISSUES -> StudioUi.palette.warning
                    else -> StudioUi.palette.textSoft
                })
                setPadding(0, dp(1), 0, dp(1))
            })
        }

        if (report.issues.isNotEmpty()) {
            content.addView(TextView(activity).apply {
                text = ""
                textSize = 8f
            })
            for (issue in report.issues) {
                val severityColor = when (issue.severity) {
                    InspectionSeverity.ERROR -> StudioUi.palette.error
                    InspectionSeverity.WARNING -> StudioUi.palette.warning
                    else -> StudioUi.palette.textSoft
                }
                val location = issue.file?.let {
                    "${relativePath(report.projectRoot, it)}:${issue.line}:${issue.column}"
                } ?: "(project)"
                val row = TextView(activity).apply {
                    text = "${severitySymbol(issue.severity)} $location\n[${issue.errorCode}] ${issue.message}"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(severityColor)
                    setPadding(0, dp(6), 0, dp(6))
                    maxLines = 6
                }
                val targetFile = issue.file
                val targetLine = issue.line
                if (targetFile != null && targetFile.exists()) {
                    row.setOnClickListener { onNavigate(targetFile, targetLine) }
                }
                content.addView(row)
            }
        }

        val scroll = ScrollView(activity).apply { addView(content) }
        AlertDialog.Builder(activity)
            .setTitle(if (report.cancelled) "Project Inspection (cancelled)" else "Project Inspection")
            .setView(scroll)
            .setPositiveButton("Show in Problems") { _, _ -> onShowInProblems(report) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun statusIcon(status: CheckStatus): String = when (status) {
        CheckStatus.OK -> "✓"
        CheckStatus.SKIPPED -> "·"
        CheckStatus.ISSUES -> "⚠"
        CheckStatus.FAILED -> "✗"
        CheckStatus.CANCELLED -> "⊘"
    }

    private fun severitySymbol(severity: InspectionSeverity): String = when (severity) {
        InspectionSeverity.ERROR -> "✖"
        InspectionSeverity.WARNING -> "⚠"
        InspectionSeverity.INFO -> "ℹ"
        InspectionSeverity.SUCCESS -> "✓"
    }

    private fun relativePath(root: File, file: File): String = try {
        file.relativeTo(root).path
    } catch (_: Exception) {
        file.absolutePath
    }
}
