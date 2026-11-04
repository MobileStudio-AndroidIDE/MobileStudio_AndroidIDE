package com.aide.mobilestudio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object MobileStudioCrashGuard {
    private const val MAX_MAIN_LOG_BYTES = 1_000_000L
    private const val MAX_CRASH_TEXT_CHARS = 24_000

    @Volatile
    private var installed = false

    private const val MAX_RETRIES = 2

    fun install(application: Application) {
        if (installed) return
        installed = true

        // Detect how the PREVIOUS process ended and surface a one-line notice
        // when it ended abnormally. See [reportAbnormalPreviousRunIfNeeded].
        reportAbnormalPreviousRunIfNeeded(application)

        // Auto-retry: if app crashed recently (< 60s), try to restart activity
        val crashCount = recentCrashCount(application)
        if (crashCount < MAX_RETRIES && crashCount > 0) {
            val activity = currentActivityRef
            if (activity != null) {
                writeCrash(application, "auto-retry #$crashCount", RuntimeException("Previous crash"))
                Handler(Looper.getMainLooper()).post {
                    activity.recreate()
                }
                return
            }
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(application, "uncaught:${thread.name}", throwable)
            // An uncaught exception means the process is about to die (or has
            // already become unstable): invalidate the clean-exit marker so
            // the next launch reports the abnormal termination.
            runCatching { UiFreezeDetector.cleanExitMarkerFile(application).delete() }
            val activity = currentActivityRef
            if (activity != null && recentCrashCount(application) < MAX_RETRIES) {
                Handler(Looper.getMainLooper()).post {
                    activity.recreate()
                }
            } else {
                runCatching {
Handler(Looper.getMainLooper()).post {
                    val isKo = AppLanguage.isKorean(application)
                    ErrorLogDialog.show(
                        application,
                        if (isKo) "앱 복구됨" else "App Recovered",
                        "MobileStudio recovered from an error. Crash log saved."
                    )
                }
                }
            }
            if (throwable is VirtualMachineError) {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    @Volatile
    private var currentActivityRef: Activity? = null

    fun activityCreated(activity: Activity) { currentActivityRef = activity }

    fun activityDestroyed(activity: Activity) {
        if (currentActivityRef === activity) currentActivityRef = null
        // Normal onDestroy is a clean exit: record it so the next launch does
        // not report this process as "terminated unexpectedly".
        markCleanExit(activity)
    }

    /**
     * Writes the clean-exit marker ("this process ended / paused normally").
     * Called on normal onDestroy and, via [UiFreezeDetector.stopMonitoring],
     * on every background pause. The marker is cleared when the app returns
     * to the foreground or crashes, so it is MISSING at the next launch
     * exactly when the previous process died in the foreground.
     */
    fun markCleanExit(context: Context) {
        runCatching {
            val file = UiFreezeDetector.cleanExitMarkerFile(context)
            file.parentFile?.mkdirs()
            file.writeText(
                RunMarkerLogic.encode(
                    android.os.Process.myPid(),
                    android.os.SystemClock.uptimeMillis(),
                    System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Detects how the PREVIOUS process ended and surfaces a one-line notice
     * when it ended abnormally.
     *
     * Why no separate process: a kernel OOM kill (lmkd) terminates the process
     * instantly — nothing inside the victim process can run or observe it. The
     * only reliable way to catch that is a separate always-resident process
     * (e.g. a `:watchdog` service with its own process) that monitors the main
     * process from the outside. That cost (extra process, memory, IPC, its own
     * lifecycle) is deliberately avoided here: the marker file covers the
     * common cases — uncaught exceptions, ANR-then-kill, foreground OOM kill,
     * and (via [UiFreezeDetector]'s heartbeat marker) kills while backgrounded
     * — because those all leave a detectable fingerprint on disk: a missing or
     * stale clean-exit marker next to a heartbeat marker from a dead pid.
     */
    private fun reportAbnormalPreviousRunIfNeeded(application: Application) {
        runCatching {
            val hadCleanExit = UiFreezeDetector.cleanExitMarkerFile(application).isFile
            val heartbeat = UiFreezeDetector.heartbeatMarkerFile(application)
                .takeIf { it.isFile }
                ?.let { RunMarkerLogic.decode(it.readText()) }
            // A device reboot killed everything — not an app fault, stay quiet.
            val rebooted = heartbeat != null && RunMarkerLogic.evaluate(
                heartbeat,
                android.os.Process.myPid(),
                android.os.SystemClock.uptimeMillis(),
                hadCleanExit
            ) == RunMarkerLogic.Verdict.REBOOTED
            if (!hadCleanExit && !rebooted) {
                val evidenceOfPreviousRun = heartbeat != null || crashCount(application) > 0
                if (evidenceOfPreviousRun) {
                    val latestLog = getCrashLogs(application).firstOrNull()?.name
                    toast(application, RunMarkerLogic.abnormalExitNotice(AppLanguage.isKorean(application), latestLog))
                }
            }
        }
    }

    private fun recentCrashCount(context: Context): Int {
        val dir = File(context.filesDir, "logs/error")
        if (!dir.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - 60_000
        return dir.listFiles { f -> f.name.startsWith("error-") && f.lastModified() > cutoff }
            ?.size ?: 0
    }

    inline fun safe(context: Context, label: String, block: () -> Unit) {
        runCatching(block).onFailure { throwable ->
            writeCrash(context, label, throwable)
            toast(context, "Action failed safely: $label")
        }
    }

    inline fun <T> safeValue(context: Context, label: String, fallback: T, block: () -> T): T {
        return runCatching(block).getOrElse { throwable ->
            writeCrash(context, label, throwable)
            fallback
        }
    }

    fun finishSafely(activity: Activity) {
        safe(activity, "${activity.javaClass.simpleName}.finish") {
            activity.finish()
            activity.overridePendingTransition(0, 0)
        }
    }

    fun toast(context: Context, message: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun writeCrash(context: Context, label: String, throwable: Throwable) {
        runCatching {
            val dir = File(context.filesDir, "logs/error").apply { mkdirs() }
            // Keep max 5 error logs, delete oldest if exceeded
            val existing = dir.listFiles { f -> f.name.startsWith("error-") && f.name.endsWith(".log") }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            while (existing.size >= 5) {
                existing.firstOrNull()?.delete()
                existing.toMutableList().removeFirst()
            }

            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(java.util.Date())
            val logFile = File(dir, "error-$dateStr.log")

            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackLines = sw.toString().lines().take(300)

            val metadata = buildString {
                appendLine("=== MobileStudio Crash Report ===")
                appendLine("Timestamp: $dateStr")
                appendLine("Label: $label")
                appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
                val runtime = Runtime.getRuntime()
                appendLine("Memory: ${runtime.freeMemory() / 1024 / 1024}MB free / ${runtime.totalMemory() / 1024 / 1024}MB total / ${runtime.maxMemory() / 1024 / 1024}MB max")
                appendLine("Thread: ${Thread.currentThread().name}")
                appendLine()
                appendLine("=== Stack Trace ===")
                appendLine(stackLines.joinToString("\n"))
            }

            logFile.writeText(metadata)
        }
    }

    fun crashCount(context: Context): Int =
        errorDir(context).listFiles { file -> file.isFile && file.extension == "log" }?.size ?: 0

    fun latestCrashText(context: Context, maxChars: Int = MAX_CRASH_TEXT_CHARS): String {
        val files = errorDir(context).listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val latest = files.firstOrNull() ?: return "No crash logs saved."
        return runCatching {
            latest.readText().takeLast(maxChars)
        }.getOrElse { "Could not read crash log: ${it.message}" }
    }

    fun clearCrashLogs(context: Context): Boolean =
        runCatching {
            errorDir(context).listFiles()?.forEach { it.delete() }
            true
        }.getOrDefault(false)

    private fun errorDir(context: Context): File =
        File(context.filesDir, "logs/error").apply { mkdirs() }

    /** Returns all crash/error log files sorted newest-first. */
    fun getCrashLogs(context: Context): List<File> =
        errorDir(context).listFiles { file -> file.isFile && file.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    private fun stackTraceText(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    /**
     * Shows crash analysis dialog with parsed stack trace and source navigation.
     * Clicking a stack frame navigates to the source file at the specified line.
     */
    fun showCrashAnalysis(activity: Activity?, projectDir: File?) {
        if (activity == null) return
        val logs = getCrashLogs(activity)
        if (logs.isEmpty()) {
            android.app.AlertDialog.Builder(activity)
                .setTitle("No Crashes Found")
                .setMessage("No crash logs recorded.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        val ssb = SpannableStringBuilder()
        var clickableSpans = mutableListOf<Pair<String, Int>>() // (filePath, line)

        for ((idx, logFile) in logs.take(5).withIndex()) {
            val content = runCatching { logFile.readText() }.getOrNull() ?: continue
            if (idx > 0) ssb.appendLine()
            ssb.appendLine("--- Crash Log ${idx + 1}: ${logFile.name} ---")

            val stackStart = content.indexOf("Stack Trace")
            val traceSection = if (stackStart > 0) content.substring(stackStart) else content

            // Parse stack frames: com.example.Class.method(File.kt:123)
            val frameRegex = Regex("""at\s+([\w.$]+)\((\w+\.\w+):(\d+)\)""")
            val lines = traceSection.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("at ")) {
                    val match = frameRegex.find(trimmed)
                    if (match != null) {
                        val method = match.groupValues[1]
                        val fileName = match.groupValues[2]
                        val lineNum = match.groupValues[3].toIntOrNull() ?: 0
                        // Find source file in project
                        val sourceFile = projectDir?.let { findSourceFile(it, fileName) }
                        ssb.appendLine(trimmed)
                        if (sourceFile != null) {
                            val spannable = SpannableString("  -> Open $fileName:$lineNum")
                            spannable.setSpan(UnderlineSpan(), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            spannable.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.ActiveBlue)), 0, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            ssb.append(spannable)
                            ssb.appendLine()
                            clickableSpans.add(sourceFile.absolutePath to lineNum)
                        } else {
                            ssb.appendLine("  -> (source not found in project)")
                        }
                    } else {
                        ssb.appendLine(trimmed)
                    }
                } else if (trimmed.isNotBlank() && !trimmed.startsWith("=== ")) {
                    ssb.appendLine(trimmed)
                }
            }
        }

        val tv = TextView(activity).apply {
            text = ssb
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tv)

        val scroll = ScrollView(activity).apply {
            addView(root)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        AlertDialog.Builder(activity)
            .setTitle("Crash Analyzer")
            .setView(scroll)
            .setPositiveButton("Clear Logs") { _, _ ->
                clearCrashLogs(activity)
                android.app.AlertDialog.Builder(activity)
                    .setTitle("Cleared")
                    .setMessage("Crash logs deleted.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .setNeutralButton("Send to AI") { _, _ ->
                // Send crash log to AI agent
                val crashText = runCatching {
                    val logFile = logs.firstOrNull() ?: return@setNeutralButton
                    logFile.readText().take(15000)
                }.getOrNull() ?: "Crash log unavailable"

                val mainActivity = activity as? com.aide.mobilestudio.MainActivity
                mainActivity?.sendCrashToAI(crashText)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun findSourceFile(projectDir: File, fileName: String): File? {
        // Search common source directories
        val searchDirs = listOf(
            File(projectDir, "app/src"),
            File(projectDir, "src")
        )
        for (dir in searchDirs) {
            if (!dir.exists()) continue
            val result = findFileRecursive(dir, fileName)
            if (result != null) return result
        }
        // Also check build output for generated files
        val buildDir = File(projectDir, "app/build")
        if (buildDir.exists()) {
            val result = findFileRecursive(buildDir, fileName)
            if (result != null) return result
        }
        return null
    }

    private fun findFileRecursive(dir: File, fileName: String): File? {
        if (!dir.isDirectory) return null
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isFile && child.name == fileName) return child
            if (child.isDirectory) {
                val found = findFileRecursive(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun dp(v: Int): Int = (v * 2.0f).toInt()
}
