package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.ArrayDeque

/**
 * Full-area build monitor: a horizontal task chip bar over the build log.
 *
 * - Tasks are detected from "> Task :xxx" lines.
 * - Duration is formatted per task (ms -> s -> m -> h).
 * - Finished tasks are removed from the bar; only tasks that produced
 *   errors or warnings stay pinned (red / amber).
 * - Tapping a chip filters the log to that task's lines only ("All" resets).
 */
class BuildMonitorView(context: Context) : LinearLayout(context) {

    private data class LogLine(val text: String, val level: LineLevel, val taskId: String?)

    private data class Task(
        val id: String,
        val label: String,
        val startMs: Long,
        var endMs: Long = 0L,
        var hasError: Boolean = false,
        var hasWarning: Boolean = false
    ) {
        val finished: Boolean get() = endMs > 0L
    }

    private val taskBarLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    private val taskBar = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(StudioUi.color(StudioUi.LayerPanelTabs))
        addView(taskBarLayout)
    }

    private val logView = TextView(context).apply {
        setTextColor(StudioUi.color(StudioUi.TextSoft))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setBackgroundColor(Color.TRANSPARENT)
        setTextIsSelectable(true)
    }

    private val logScroll = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(StudioUi.color(StudioUi.Ink))
        addView(logView)
    }

    private val tasks = ArrayDeque<Task>()
    private val lines = ArrayDeque<LogLine>()
    private val pendingLog = ArrayDeque<LogLine>()
    private var logFlushScheduled = false
    private var activeTaskId: String? = null
    private var filterTaskId: String? = null
    private val renderedTaskIds = mutableSetOf<String>()
    private var allChipRendered = false

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(StudioUi.color(StudioUi.Ink))
        addView(taskBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderChips(emptyList())
    }

    // ── API ─────────────────────────────────────────────────────────────

    fun clear() {
        tasks.clear()
        lines.clear()
        pendingLog.clear()
        logFlushScheduled = false
        activeTaskId = null
        filterTaskId = null
        logView.text = ""
        renderedTaskIds.clear()
        allChipRendered = false
        chipSignature = null
        renderChips(emptyList())
    }

    fun onLine(rawLine: String, level: LineLevel) {
        val line = rawLine.trimEnd()
        val taskMatch = TASK_RE.find(line)
        if (taskMatch != null) {
            closeActiveTask()
            val id = taskMatch.groupValues[1]
            val t = Task(id, id.substringAfterLast(':'), System.currentTimeMillis())
            tasks.addLast(t)
            activeTaskId = id
            if (line.contains("UP-TO-DATE") || line.contains("FROM-CACHE") || line.contains("NO-SOURCE")) {
                t.endMs = t.startMs
            }
            renderChips(tasks.toList())
        } else if (line.startsWith("BUILD SUCCESSFUL") || line.startsWith("BUILD FAILED") ||
            line.startsWith("Finished build")
        ) {
            closeActiveTask()
            renderChips(tasks.toList())
        } else if (level == LineLevel.ERROR) {
            tasks.lastOrNull()?.let { it.hasError = true }
            renderChips(tasks.toList())
        } else if (level == LineLevel.WARNING) {
            tasks.lastOrNull()?.let { it.hasWarning = true }
            renderChips(tasks.toList())
        }

        lines.addLast(LogLine(line, level, activeTaskId))
        while (lines.size > MAX_LINES) lines.removeFirst()

        // Batch view appends: a per-line logView.append + fullScroll post
        // saturates the main thread (append cost grows with buffer size)
        // and starves editor/shell input while build logs stream.
        pendingLog.addLast(LogLine(line, level, activeTaskId))
        while (pendingLog.size > MAX_PENDING_LOG) pendingLog.removeFirst()
        scheduleLogFlush()
    }

    private fun scheduleLogFlush() {
        if (logFlushScheduled) return
        logFlushScheduled = true
        postDelayed({ flushLog() }, LOG_FLUSH_MS)
    }

    private fun flushLog() {
        logFlushScheduled = false
        val batch = mutableListOf<LogLine>()
        while (pendingLog.isNotEmpty() && batch.size < MAX_LINES_PER_FLUSH) {
            batch.add(pendingLog.removeFirst())
        }
        if (batch.isNotEmpty()) {
            val sb = StringBuilder()
            for (l in batch) {
                if (filterTaskId != null && filterTaskId != l.taskId) continue
                when (l.level) {
                    LineLevel.ERROR -> sb.append("E ")
                    LineLevel.WARNING -> sb.append("W ")
                    else -> {}
                }
                sb.append(l.text).append('\n')
            }
            if (sb.isNotEmpty()) logView.append(sb)
            trimLogView()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        if (pendingLog.isNotEmpty()) scheduleLogFlush()
    }

    /** Keeps the rendered log bounded; the append cost grows with buffer size. */
    private fun trimLogView() {
        if (logView.text.length <= MAX_VIEW_CHARS) return
        val text = logView.text
        val cutOffset = text.length - MAX_VIEW_CHARS
        val cut = text.indexOf('\n', cutOffset).let { if (it in 1 until text.length) it + 1 else cutOffset }
        logView.text = text.subSequence(cut, text.length)
    }

    /** Rebuilds the log from history for the currently selected filter. */
    fun refreshLog() {
        val builder = SpannableStringBuilder()
        for (l in lines) {
            if (filterTaskId != null && l.taskId != filterTaskId) continue
            val prefix = when (l.level) {
                LineLevel.ERROR -> "[ERR] "
                LineLevel.WARNING -> "[WARN] "
                else -> ""
            }
            val start = builder.length
            builder.append(prefix).append(l.text).append('\n')
            when (l.level) {
                LineLevel.ERROR -> builder.setSpan(
                    ForegroundColorSpan(StudioUi.palette.error), start, builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                LineLevel.WARNING -> builder.setSpan(
                    ForegroundColorSpan(StudioUi.palette.warning), start, builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                LineLevel.SUCCESS -> builder.setSpan(
                    ForegroundColorSpan(StudioUi.palette.success), start, builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                else -> {}
            }
        }
        logView.text = builder
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun setFilter(taskId: String?) {
        if (filterTaskId == taskId && taskId != null) {
            filterTaskId = null
        } else {
            filterTaskId = taskId
        }
        refreshLog()
        renderChips(tasks.toList())
    }

    // ── internals ───────────────────────────────────────────────────────

    private fun closeActiveTask() {
        val active = tasks.lastOrNull()?.takeIf { !it.finished } ?: return
        active.endMs = System.currentTimeMillis()
        if (!active.hasError && !active.hasWarning) {
            tasks.removeLast()
        }
        activeTaskId = null
    }

    private var chipSignature: String? = null

    private fun renderChips(current: List<Task>) {
        val signature = buildString {
            append(filterTaskId)
            for (t in current) {
                append('|').append(t.id).append('/').append(t.label)
                    .append('/').append(t.finished).append(t.hasError)
                    .append(t.hasWarning).append(t.endMs)
            }
        }
        if (signature == chipSignature) return
        chipSignature = signature

        taskBarLayout.removeAllViews()

        // Track chips that are appearing for the first time so we only
        // animate those (existing chips update in place without flicker).
        val newViews = mutableListOf<View>()

        // "All" chip
        val allChip = chip("All", showAll = true) { setFilter(null) }
        taskBarLayout.addView(allChip)
        if (!allChipRendered) {
            newViews.add(allChip)
            allChipRendered = true
        }

        val currentIds = HashSet<String>(current.size)
        for (t in current) {
            currentIds.add(t.id)
            val statusColor = when {
                t.hasError -> StudioUi.palette.error
                t.hasWarning -> StudioUi.palette.warning
                t.finished -> StudioUi.palette.success
                else -> StudioUi.palette.info
            }
            val label = t.label + if (t.finished) " · ${format(t.endMs - t.startMs)}" else " …"
            val c = chip(label, showAll = false, tint = statusColor, selected = filterTaskId == t.id) {
                setFilter(t.id)
            }
            taskBarLayout.addView(c)
            if (t.id !in renderedTaskIds) {
                newViews.add(c)
            }
        }

        renderedTaskIds.clear()
        renderedTaskIds.addAll(currentIds)

        if (newViews.isNotEmpty()) {
            StudioUi.staggerIn(newViews, delayPerItem = 20)
        }
    }

    private fun chip(text: String, showAll: Boolean = false, tint: Int = StudioUi.palette.info, selected: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) StudioUi.palette.text else StudioUi.palette.textSoft)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (selected) StudioUi.palette.surface2 else StudioUi.palette.card)
                setStroke(dp(1), if (selected || showAll) StudioUi.palette.accent else tint)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun format(ms: Long): String {
        if (ms < 0) return "0ms"
        if (ms < 1000) return "${ms}ms"
        val s = ms / 1000.0
        if (s < 60) return String.format("%.1fs", s)
        if (s < 3600) {
            val m = s.toInt() / 60
            val ss = (s.toInt() % 60)
            return if (ss == 0) "${m}m" else "${m}m ${ss}s"
        }
        val h = s.toInt() / 3600
        val m = (s.toInt() % 3600) / 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val TASK_RE = Regex("^> Task (:\\S+)")
        private const val MAX_LINES = 4000
        private const val MAX_PENDING_LOG = 500
        private const val MAX_LINES_PER_FLUSH = 150
        private const val MAX_VIEW_CHARS = 60_000
        private const val LOG_FLUSH_MS = 120L
    }
}