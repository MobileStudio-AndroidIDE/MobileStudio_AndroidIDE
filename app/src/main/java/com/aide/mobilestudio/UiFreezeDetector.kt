package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * UI Freeze Detector - Android Studio-style ANR detection
 *
 * Monitors the main/UI thread for responsiveness. When the main thread
 * is blocked for >= 3 seconds, shows a diagnostic overlay.
 *
 * The detector itself NEVER blocks the main thread. All diagnostics
 * run on background threads. The freeze-cause work continues running
 * and when it completes, the UI recovers automatically.
 */
class UiFreezeDetector(private val context: Context) {

    companion object {
        private const val TAG = "UiFreezeDetector"
        private const val WARNING_THRESHOLD_MS = 1000L
        private const val CRITICAL_THRESHOLD_MS = 3000L
        private const val HEARTBEAT_INTERVAL_MS = 200L
        private const val HEARTBEAT_TIMEOUT_MS = 500L

        /** Pure duration formatting (JVM-testable): 999 -> "999ms", 1500 -> "1.5s", 125000 -> "2m 5s". */
        fun formatDuration(ms: Long): String = when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> String.format("%.1fs", ms / 1000.0)
            else -> String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000)
        }

        private fun diagnosticsDir(context: Context): File =
            File(context.filesDir, RunMarkerLogic.DIAGNOSTICS_DIR)

        fun heartbeatMarkerFile(context: Context): File =
            File(diagnosticsDir(context), RunMarkerLogic.HEARTBEAT_MARKER_NAME)

        fun cleanExitMarkerFile(context: Context): File =
            File(diagnosticsDir(context), RunMarkerLogic.CLEAN_EXIT_MARKER_NAME)
    }

    interface FreezeListener {
        fun onFreezeDetected(durationMs: Long)
        fun onFreezeResolved(durationMs: Long)
        fun onDumpCaptured(dumpFile: File)
    }

    @Volatile private var listener: FreezeListener? = null
    @Volatile private var isMonitoring = false
    @Volatile private var isFrozen = false
    @Volatile private var freezeStartTime = 0L
    @Volatile private var overlayView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogThread = HandlerThread("UiFreezeWatchdog").apply { start() }
    private val watchdogHandler = Handler(watchdogThread.looper)
    private val heartbeatToken = AtomicBoolean(false)
    private val heartbeatPending = AtomicBoolean(false)
    private val dumpInProgress = AtomicBoolean(false)
    private val heartbeatCount = AtomicLong(0)
    private val consecutiveFailures = AtomicLong(0)

    private var warningShown = false
    private var activityRef: WeakReference<Activity>? = null

    fun setListener(l: FreezeListener?) { listener = l }

    fun startMonitoring(activity: Activity) {
        if (isMonitoring) return
        // A stopMonitoring() -> startMonitoring() cycle happens on every
        // onPause()/onResume() around app switches. A heartbeat that was in
        // flight when the activity paused never ran its timeout callback
        // (stopMonitoring cancels it), which used to leave heartbeatPending
        // stuck at true; on resume every sendHeartbeat then took the early
        // "pending" branch and incremented consecutiveFailures every 200ms
        // WITHOUT ever pinging the main thread — crossing the 3s critical
        // threshold ~3s after returning to the app even though nothing froze.
        // Cancel any stale pending timeout first, then reset ALL state.
        mainHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
        heartbeatPending.set(false)
        heartbeatToken.set(false)
        consecutiveFailures.set(0)
        isFrozen = false
        warningShown = false
        freezeStartTime = 0L
        isMonitoring = true
        activityRef = WeakReference(activity)
        reportPreviousRunIfNeeded()
        Log.i(TAG, "Freeze detector started")
        scheduleHeartbeat()
    }

    fun stopMonitoring() {
        isMonitoring = false
        mainHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
        writeRunMarkers()
        hideOverlayOnMainThread()
        Log.i(TAG, "Freeze detector stopped")
    }

    /**
     * onPause/onDestroy checkpoint: record "last known alive" (uptime + wall)
     * plus a clean-exit marker so the next launch can tell how the previous
     * process ended instead of silently losing the information.
     */
    private fun writeRunMarkers() {
        runCatching {
            val marker = RunMarkerLogic.encode(
                android.os.Process.myPid(),
                SystemClock.uptimeMillis(),
                System.currentTimeMillis()
            )
            val dir = File(context.filesDir, RunMarkerLogic.DIAGNOSTICS_DIR)
            dir.mkdirs()
            heartbeatMarkerFile(context).writeText(marker)
            // Clean checkpoint: the process paused/finished normally, so an
            // abnormal death after this point happened in the background.
            cleanExitMarkerFile(context).writeText(marker)
        }
    }

    /**
     * onResume: interpret the markers written by the previous process.
     * DIED_IN_BACKGROUND is reported here (log + durable record); a
     * foreground death is surfaced by MobileStudioCrashGuard at install time.
     * Returning to the foreground then invalidates the clean-exit marker, so
     * an abnormal death from here on is correctly reported as a foreground
     * end on the next launch.
     */
    private fun reportPreviousRunIfNeeded() {
        runCatching {
            val marker = heartbeatMarkerFile(context).takeIf { it.isFile }
                ?.let { RunMarkerLogic.decode(it.readText()) }
            val cleanExitPresent = cleanExitMarkerFile(context).isFile
            val verdict = RunMarkerLogic.evaluate(
                marker,
                android.os.Process.myPid(),
                SystemClock.uptimeMillis(),
                cleanExitPresent
            )
            when (verdict) {
                RunMarkerLogic.Verdict.DIED_IN_BACKGROUND -> {
                    Log.e(TAG, "Previous app process was terminated while backgrounded " +
                        "(checkpoint uptime=${marker?.uptimeMs}ms, wall=${marker?.wallMs})")
                    recordBackgroundKill(marker)
                }
                RunMarkerLogic.Verdict.DIED_IN_FOREGROUND ->
                    Log.e(TAG, "Previous app process terminated while in the foreground " +
                        "(crash guard surfaces the user notice)")
                RunMarkerLogic.Verdict.REBOOTED ->
                    Log.i(TAG, "Device rebooted since the previous session")
                else -> {}
            }
            cleanExitMarkerFile(context).delete()
        }
    }

    private fun recordBackgroundKill(marker: RunMarkerLogic.HeartbeatMarker?) {
        runCatching {
            val dir = File(context.filesDir, RunMarkerLogic.DIAGNOSTICS_DIR)
            dir.mkdirs()
            File(dir, "last_background_kill.txt").writeText(
                "Previous process (pid=${marker?.pid}) was terminated while backgrounded; " +
                    "checkpoint uptime=${marker?.uptimeMs}ms wall=${marker?.wallMs}; " +
                    "detected at uptime=${SystemClock.uptimeMillis()}ms wall=${System.currentTimeMillis()}\n"
            )
        }
    }

    private fun scheduleHeartbeat() {
        if (!isMonitoring) return
        watchdogHandler.postDelayed({
            if (!isMonitoring) return@postDelayed
            sendHeartbeat()
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun sendHeartbeat() {
        if (!isMonitoring) return
        if (heartbeatPending.getAndSet(true)) {
            val failures = consecutiveFailures.incrementAndGet()
            val estimatedBlockMs = failures * HEARTBEAT_INTERVAL_MS

            if (estimatedBlockMs >= WARNING_THRESHOLD_MS && !warningShown) {
                warningShown = true
                Log.w(TAG, "Main thread responsive delay detected: ~${estimatedBlockMs}ms")
            }

            if (estimatedBlockMs >= CRITICAL_THRESHOLD_MS && !isFrozen) {
                isFrozen = true
                freezeStartTime = SystemClock.uptimeMillis() - estimatedBlockMs
                Log.e(TAG, "UI FREEZE detected: ~${estimatedBlockMs}ms")
                listener?.onFreezeDetected(estimatedBlockMs)
                showOverlayOnMainThread(estimatedBlockMs)
            }

            if (isFrozen) {
                val duration = SystemClock.uptimeMillis() - freezeStartTime
                updateOverlayDuration(duration)
            }

            watchdogHandler.postDelayed({
                if (isMonitoring) sendHeartbeat()
            }, HEARTBEAT_INTERVAL_MS)
            return
        }

        heartbeatToken.set(false)

        mainHandler.post {
            heartbeatToken.set(true)
        }

        watchdogHandler.postDelayed({
            if (!isMonitoring) return@postDelayed
            heartbeatPending.set(false)

            if (!heartbeatToken.get()) {
                val failures = consecutiveFailures.incrementAndGet()
                val estimatedBlockMs = failures * HEARTBEAT_INTERVAL_MS

                if (estimatedBlockMs >= WARNING_THRESHOLD_MS && !warningShown) {
                    warningShown = true
                }

                if (estimatedBlockMs >= CRITICAL_THRESHOLD_MS && !isFrozen) {
                    isFrozen = true
                    freezeStartTime = SystemClock.uptimeMillis() - estimatedBlockMs
                    Log.e(TAG, "UI FREEZE detected: ~${estimatedBlockMs}ms")
                    listener?.onFreezeDetected(estimatedBlockMs)
                    showOverlayOnMainThread(estimatedBlockMs)
                }

                if (isFrozen) {
                    val duration = SystemClock.uptimeMillis() - freezeStartTime
                    updateOverlayDuration(duration)
                }
            } else {
                consecutiveFailures.set(0)
                if (isFrozen) {
                    val duration = SystemClock.uptimeMillis() - freezeStartTime
                    isFrozen = false
                    warningShown = false
                    Log.i(TAG, "UI freeze resolved after ${duration}ms")
                    listener?.onFreezeResolved(duration)
                    hideOverlayOnMainThread()
                }
            }

            if (isMonitoring) scheduleHeartbeat()
        }, HEARTBEAT_TIMEOUT_MS)
    }

    private fun showOverlayOnMainThread(durationMs: Long) {
        mainHandler.post {
            try {
                val activity = activityRef?.get() ?: return@post
                if (activity.isFinishing || activity.isDestroyed) return@post
                if (overlayView != null) return@post

                val overlay = createFreezeOverlay(activity, durationMs)
                overlayView = overlay

                val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                rootView.addView(overlay, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show freeze overlay", e)
            }
        }
    }

    private fun hideOverlayOnMainThread() {
        mainHandler.post {
            try {
                overlayView?.let { view ->
                    val parent = view.parent as? ViewGroup
                    parent?.removeView(view)
                }
                overlayView = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide freeze overlay", e)
            }
        }
    }

    private fun updateOverlayDuration(durationMs: Long) {
        mainHandler.post {
            try {
                overlayView?.findViewById<TextView>(R.id.freeze_duration_text)?.text =
                    "UI not responding for ${formatDuration(durationMs)}"
            } catch (_: Exception) {}
        }
    }

    private fun createFreezeOverlay(activity: Activity, durationMs: Long): View {
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }

        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(0x80, 13, 17, 23))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedRect(StudioUi.color(StudioUi.Red), dp(16).toFloat())
            setPadding(dp(24), dp(20), dp(24), dp(20))
            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            cardParams.gravity = Gravity.CENTER
            cardParams.leftMargin = dp(16)
            cardParams.rightMargin = dp(16)
            layoutParams = cardParams
        }

        card.addView(TextView(activity).apply {
            text = "UI Not Responding"
            setTextColor(StudioUi.color(StudioUi.Red))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        card.addView(TextView(activity).apply {
            id = R.id.freeze_duration_text
            text = "UI not responding for ${formatDuration(durationMs)}"
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        })

        card.addView(TextView(activity).apply {
            text = "The main thread is blocked. This is usually caused by a long-running\n" +
                    "operation on the UI thread (network, disk I/O, heavy computation).\n" +
                    "The app will recover once the blocking operation completes."
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        })

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        buttonRow.addView(createButton(activity, "Dump Threads", StudioUi.color(StudioUi.ActiveBlue)) {
            captureThreadDump(activity)
        })

        buttonRow.addView(createButton(activity, "Fix", StudioUi.color(StudioUi.Green)) {
            attemptRecovery(activity)
        })

        buttonRow.addView(createButton(activity, "Dismiss", StudioUi.color(StudioUi.TextSoft)) {
            hideOverlayOnMainThread()
        })

        card.addView(buttonRow)
        container.addView(card)

        return container
    }

    private fun createButton(context: Context, text: String, color: Int, onClick: () -> Unit): Button {
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }
        return Button(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(color)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.leftMargin = dp(8)
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun captureThreadDump(activity: Activity) {
        if (dumpInProgress.getAndSet(true)) return

        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dumpDir = File(activity.filesDir, "freeze_dumps")
                dumpDir.mkdirs()
                val dumpFile = File(dumpDir, "freeze_dump_$timestamp.txt")

                val dump = buildThreadDump()
                dumpFile.writeText(dump)

                Log.i(TAG, "Thread dump saved to: ${dumpFile.absolutePath}")
                listener?.onDumpCaptured(dumpFile)

                mainHandler.post {
                    Toast.makeText(activity, "Dump saved: ${dumpFile.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture thread dump", e)
            } finally {
                dumpInProgress.set(false)
            }
        }.start()
    }

    private fun attemptRecovery(activity: Activity) {
        Thread {
            try {
                // Try to interrupt blocked threads
                val allThreads = Thread.getAllStackTraces()
                var interrupted = 0
                for ((thread, _) in allThreads) {
                    if (thread.state == Thread.State.BLOCKED || thread.state == Thread.State.WAITING) {
                        try {
                            thread.interrupt()
                            interrupted++
                        } catch (_: Exception) {}
                    }
                }

                // Force GC to free memory
                System.gc()

                // Try to restart shell if it's the cause
                if (activity is MainActivity) {
                    mainHandler.post {
                        try {
                            // Hide the overlay first
                            hideOverlayOnMainThread()
                            Toast.makeText(activity, "Attempting recovery... interrupted $interrupted threads", Toast.LENGTH_SHORT).show()

                            // Try to hide bottom panel if visible
                            val bottomPanel = activity.findViewById<View>(R.id.bottom_panel)
                            if (bottomPanel?.visibility == View.VISIBLE) {
                                CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                                    try {
                                        kotlinx.coroutines.delay(300)
                                        bottomPanel.visibility = View.GONE
                                        kotlinx.coroutines.delay(200)
                                        Toast.makeText(activity, "Bottom panel hidden. Try reopening.", Toast.LENGTH_SHORT).show()
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Recovery attempt failed", e)
                        }
                    }
                } else {
                    mainHandler.post {
                        hideOverlayOnMainThread()
                        Toast.makeText(activity, "Interrupted $interrupted threads. App may recover.", Toast.LENGTH_SHORT).show()
                    }
                }

                Log.i(TAG, "Recovery attempt: interrupted $interrupted threads")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attempt recovery", e)
                mainHandler.post {
                    hideOverlayOnMainThread()
                }
            }
        }.start()
    }

    private fun buildThreadDump(): String {
        return buildString {
            appendLine("=== MobileStudio UI Freeze Thread Dump ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("Freeze duration: ${formatDuration(SystemClock.uptimeMillis() - freezeStartTime)}")
            appendLine()

            val allThreads = Thread.getAllStackTraces()
            appendLine("=== All Threads (${allThreads.size}) ===")
            appendLine()

            val mainThread = Thread.currentThread()
            val mainTrace = allThreads[mainThread]
            appendLine("--- MAIN THREAD (ID=${mainThread.id}, State=${mainThread.state}) ---")
            mainTrace?.forEach { element ->
                appendLine("  at $element")
            }
            appendLine()

            val gradleThreads = mutableMapOf<Thread, Array<StackTraceElement>>()
            val indexingThreads = mutableMapOf<Thread, Array<StackTraceElement>>()
            val ioThreads = mutableMapOf<Thread, Array<StackTraceElement>>()
            val otherThreads = mutableMapOf<Thread, Array<StackTraceElement>>()

            for ((thread, trace) in allThreads) {
                if (thread == mainThread) continue
                val name = thread.name.lowercase()
                when {
                    name.contains("gradle") || name.contains("daemon") -> gradleThreads[thread] = trace
                    name.contains("index") || name.contains("symbol") -> indexingThreads[thread] = trace
                    name.contains("io") || name.contains("read") || name.contains("write") ||
                    name.contains("network") || name.contains("http") -> ioThreads[thread] = trace
                    else -> otherThreads[thread] = trace
                }
            }

            if (gradleThreads.isNotEmpty()) {
                appendLine("--- GRADLE THREADS (${gradleThreads.size}) ---")
                for ((thread, trace) in gradleThreads) {
                    appendLine("  Thread: ${thread.name} (ID=${thread.id}, State=${thread.state})")
                    trace?.take(20)?.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }

            if (indexingThreads.isNotEmpty()) {
                appendLine("--- INDEXING THREADS (${indexingThreads.size}) ---")
                for ((thread, trace) in indexingThreads) {
                    appendLine("  Thread: ${thread.name} (ID=${thread.id}, State=${thread.state})")
                    trace?.take(20)?.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }

            if (ioThreads.isNotEmpty()) {
                appendLine("--- I/O THREADS (${ioThreads.size}) ---")
                for ((thread, trace) in ioThreads) {
                    appendLine("  Thread: ${thread.name} (ID=${thread.id}, State=${thread.state})")
                    trace?.take(10)?.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }

            if (otherThreads.isNotEmpty()) {
                appendLine("--- OTHER THREADS (${otherThreads.size}) ---")
                for ((thread, trace) in otherThreads) {
                    appendLine("  Thread: ${thread.name} (ID=${thread.id}, State=${thread.state})")
                    trace?.take(10)?.forEach { appendLine("    at $it") }
                    appendLine()
                }
            }

            appendLine("=== LOCK/BLOCK ANALYSIS ===")
            val blockedThreads = allThreads.filter { it.key.state == Thread.State.BLOCKED }
            if (blockedThreads.isNotEmpty()) {
                for ((thread, trace) in blockedThreads) {
                    appendLine("  BLOCKED: ${thread.name} (ID=${thread.id})")
                    trace?.take(5)?.forEach { appendLine("    at $it") }
                    appendLine()
                }
            } else {
                appendLine("  No blocked threads detected")
            }

            appendLine("=== MEMORY ===")
            val runtime = Runtime.getRuntime()
            appendLine("  Max memory: ${runtime.maxMemory() / 1024 / 1024}MB")
            appendLine("  Total memory: ${runtime.totalMemory() / 1024 / 1024}MB")
            appendLine("  Free memory: ${runtime.freeMemory() / 1024 / 1024}MB")
            appendLine("  Used memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024}MB")

            appendLine()
            appendLine("=== SUMMARY ===")
            appendLine("  Total threads: ${allThreads.size}")
            appendLine("  Main thread state: ${mainThread.state}")
            appendLine("  Blocked threads: ${blockedThreads.size}")
            appendLine("  Waiting threads: ${allThreads.count { it.key.state == Thread.State.WAITING }}")
            appendLine("  Timed waiting threads: ${allThreads.count { it.key.state == Thread.State.TIMED_WAITING }}")
            appendLine("  Runnable threads: ${allThreads.count { it.key.state == Thread.State.RUNNABLE }}")
        }
    }

    private fun createRoundedRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    fun destroy() {
        stopMonitoring()
        watchdogThread.quitSafely()
        try {
            watchdogThread.join(1000)
        } catch (_: Exception) {}
    }
}

/**
 * Pure, JVM-testable logic for the run-state markers shared by
 * [UiFreezeDetector] and [MobileStudioCrashGuard].
 *
 * Two tiny marker files under filesDir/diagnostics record how the previous
 * process ended:
 *  - ui_heartbeat.marker: written whenever monitoring stops (onPause/onDestroy),
 *    with the current uptime AND wall clock.
 *  - clean-exit.marker: a clean checkpoint, written on the same events and
 *    cleared when the app returns to the foreground or crashes.
 *
 * At the next launch, a heartbeat marker from a dead pid plus a PRESENT
 * clean-exit marker means the previous process died in the background
 * (LMK/OOM while cached — reported by the freeze detector). A MISSING
 * clean-exit marker means the previous process died in the foreground
 * (uncaught exception, ANR-then-kill, OOM kill — surfaced to the user by the
 * crash guard). A heartbeat marker whose uptime is in the future means the
 * device rebooted, which is not the app's fault.
 */
object RunMarkerLogic {
    const val DIAGNOSTICS_DIR = "diagnostics"
    const val HEARTBEAT_MARKER_NAME = "ui_heartbeat.marker"
    const val CLEAN_EXIT_MARKER_NAME = "clean-exit.marker"

    /** Slack for the reboot check: uptime must have gone backwards to count. */
    const val REBOOT_SLACK_MS = 2_000L

    data class HeartbeatMarker(val pid: Int, val uptimeMs: Long, val wallMs: Long) {
        fun encode(): String = "pid=$pid;uptime=$uptimeMs;wall=$wallMs"
    }

    enum class Verdict {
        /** No marker recorded yet (first run). */
        NO_MARKER,
        /** Marker belongs to this process: ordinary pause/resume cycle. */
        SAME_PROCESS,
        /** Marker uptime is in the future: the device rebooted since. */
        REBOOTED,
        /** Previous process's last lifecycle event was a clean checkpoint. */
        DIED_IN_BACKGROUND,
        /** Previous process's last lifecycle event was a foreground resume. */
        DIED_IN_FOREGROUND
    }

    fun encode(pid: Int, uptimeMs: Long, wallMs: Long): String =
        HeartbeatMarker(pid, uptimeMs, wallMs).encode()

    fun decode(text: String): HeartbeatMarker? {
        val fields = text.trim()
            .split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .toMap()
        val pid = fields["pid"]?.toIntOrNull() ?: return null
        val uptime = fields["uptime"]?.toLongOrNull() ?: return null
        val wall = fields["wall"]?.toLongOrNull() ?: return null
        return HeartbeatMarker(pid, uptime, wall)
    }

    fun heartbeatMarkerFile(filesDir: File): File =
        File(File(filesDir, DIAGNOSTICS_DIR), HEARTBEAT_MARKER_NAME)

    fun cleanExitMarkerFile(filesDir: File): File =
        File(File(filesDir, DIAGNOSTICS_DIR), CLEAN_EXIT_MARKER_NAME)

    /**
     * Decide what happened to the process that wrote [marker].
     *
     * @param currentPid pid of the process evaluating the marker
     * @param nowUptimeMs current [android.os.SystemClock.uptimeMillis]; uptime
     *   stops during deep sleep and resets on reboot, so "marker uptime in the
     *   future" can only mean a reboot, while "wall far ahead of uptime" is
     *   just deep sleep (never a reboot, never a freeze).
     * @param cleanExitMarkerPresent whether the previous process's last known
     *   lifecycle event was a clean checkpoint (background/onDestroy)
     */
    fun evaluate(
        marker: HeartbeatMarker?,
        currentPid: Int,
        nowUptimeMs: Long,
        cleanExitMarkerPresent: Boolean
    ): Verdict = when {
        marker == null -> Verdict.NO_MARKER
        marker.uptimeMs > nowUptimeMs + REBOOT_SLACK_MS -> Verdict.REBOOTED
        marker.pid == currentPid -> Verdict.SAME_PROCESS
        cleanExitMarkerPresent -> Verdict.DIED_IN_BACKGROUND
        else -> Verdict.DIED_IN_FOREGROUND
    }

    /**
     * One-line user notice for an abnormal previous run. Pure so it can be
     * unit-tested.
     */
    fun abnormalExitNotice(isKorean: Boolean, latestCrashLogName: String?): String =
        if (isKorean) {
            if (latestCrashLogName != null) "이전 실행이 비정상 종료되었습니다. 로그: $latestCrashLogName"
            else "이전 실행이 비정상 종료되었습니다. 기록된 크래시 로그가 없습니다."
        } else {
            if (latestCrashLogName != null) "Previous run terminated unexpectedly — crash log: $latestCrashLogName"
            else "Previous run terminated unexpectedly. No crash log was recorded."
        }
}
