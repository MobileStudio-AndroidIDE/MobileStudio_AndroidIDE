package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BuildOutputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14, 10, 14, 10)
    }

    private val textView = TextView(context).apply {
        setTextColor(StudioUi.color(StudioUi.TextSoft))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setBackgroundColor(Color.TRANSPARENT)
        setTextIsSelectable(true)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = StudioUi.color(StudioUi.Blue)
        setPadding(0, 4, 0, 4)
    }

    private val ssb = SpannableStringBuilder()
    private val pendingLines = ArrayDeque<PendingLine>()
    private val pendingLock = ReentrantLock()
    @Volatile private var flushScheduled = false
    private var lastScrollMs = 0L
    private var droppedLines = 0
    private var totalLineCount = 0

    var followMode: Boolean = true
        private set

    var onFollowChanged: ((Boolean) -> Unit)? = null

    private var onErrorClickListener: ((filePath: String, line: Int, column: Int) -> Unit)? = null
    private var onActionClickListener: ((actionId: String) -> Unit)? = null

    fun scrollToTopNow() {
        smoothScrollTo(0, 0)
    }

    fun scrollToBottomNow() {
        post { fullScroll(View.FOCUS_DOWN) }
    }

    fun setFollowMode(enabled: Boolean) {
        if (followMode == enabled) return
        followMode = enabled
        onFollowChanged?.invoke(enabled)
        if (enabled) scrollToBottomNow()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t > oldt) return
        val contentHeight = container.height
        val viewportHeight = height
        val atBottom = contentHeight - scrollY - viewportHeight <= 20
        if (followMode && !atBottom) {
            followMode = false
            onFollowChanged?.invoke(false)
        } else if (!followMode && atBottom) {
            followMode = true
            onFollowChanged?.invoke(true)
        }
    }

    fun setOnErrorClickListener(listener: (filePath: String, line: Int, column: Int) -> Unit) {
        onErrorClickListener = listener
    }

    fun setOnActionClickListener(listener: (actionId: String) -> Unit) {
        onActionClickListener = listener
    }

    private data class PendingLine(
        val line: String,
        val level: LineLevel,
        val actionId: String? = null,
        val actionLabel: String? = null
    )

    init {
        background = StudioUi.rounded(context, fill = StudioUi.Ink, stroke = "#00000000", radiusDp = 0)
        container.addView(textView)
        addView(container)
    }

    fun clear() {
        pendingLock.withLock {
            ssb.clear()
            pendingLines.clear()
            droppedLines = 0
            totalLineCount = 0
        }
        textView.text = ""
        flushScheduled = false
    }

    fun appendLine(line: String, level: LineLevel) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Log here (worker thread) rather than on the main path: logging
            // every build line on the UI thread causes visible jank under
            // heavy log output.
            Log.i("MobileStudioBuild", line)
            pendingLock.withLock {
                pendingLines.addLast(PendingLine(line.take(MAX_LINE_CHARS), level))
                while (pendingLines.size > MAX_PENDING_LINES) {
                    pendingLines.removeFirst()
                    droppedLines++
                }
                totalLineCount++
            }
            scheduleFlush()
            return
        }
        pendingLines.addLast(PendingLine(line.take(MAX_LINE_CHARS), level))
        while (pendingLines.size > MAX_PENDING_LINES) {
            pendingLines.removeFirst()
            droppedLines++
        }
        totalLineCount++
        scheduleFlush()
    }

    fun appendActionLine(line: String, level: LineLevel, handler: () -> Unit) {
        appendActionLineInternal(line, level, null, handler)
    }

    fun appendErrorLinkLine(
        line: String,
        level: LineLevel,
        filePath: String,
        fileLine: Int,
        fileColumn: Int
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { appendErrorLinkLine(line, level, filePath, fileLine, fileColumn) }
            return
        }
        flushPending(Int.MAX_VALUE)
        val prefix = levelPrefix(level)
        Log.i("MobileStudioBuild", line)
        val start = ssb.length
        ssb.append(prefix).append(line.take(MAX_LINE_CHARS)).append('\n')
        ssb.setSpan(ForegroundColorSpan(levelColor(level)), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val pathStart = start + prefix.length + line.indexOf(filePath).coerceAtLeast(0)
        val pathEnd = pathStart + filePath.length
        if (pathStart >= start + prefix.length && pathEnd <= ssb.length) {
            ssb.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        onErrorClickListener?.invoke(filePath, fileLine, fileColumn)
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = StudioUi.color(StudioUi.Blue)
                        ds.isUnderlineText = true
                    }
                },
                pathStart, pathEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val lineBgStart = start
        val lineBgEnd = ssb.length
        val bgColor = when (level) {
            LineLevel.ERROR -> Color.parseColor(StudioUi.palette.errBg.let { String.format("#%06X", 0xFFFFFF and it) })
            LineLevel.WARNING -> Color.parseColor(StudioUi.palette.warnBg.let { String.format("#%06X", 0xFFFFFF and it) })
            else -> Color.TRANSPARENT
        }
        if (bgColor != Color.TRANSPARENT) {
            ssb.setSpan(BackgroundColorSpan(bgColor), lineBgStart, lineBgEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        trimBuffer()
        flushNow()
    }

    fun appendExpandableGroup(
        summary: String,
        hiddenCount: Int,
        expandedLines: List<String>,
        level: LineLevel
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { appendExpandableGroup(summary, hiddenCount, expandedLines, level) }
            return
        }
        flushPending(Int.MAX_VALUE)
        val groupId = "group_${ssb.length}"
        val start = ssb.length
        val prefix = levelPrefix(level)
        val label = "$prefix$groupId: $summary ($hiddenCount lines hidden - tap to expand)"
        ssb.append(label).append('\n')
        ssb.setSpan(ForegroundColorSpan(levelColor(level)), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        ssb.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onActionClickListener?.invoke("expand:$groupId")
                    insertExpandedLines(groupId, expandedLines, level)
                }
                override fun updateDrawState(ds: TextPaint) {
                    ds.color = StudioUi.color(StudioUi.ActiveBlue)
                    ds.isUnderlineText = true
                }
            },
            start + prefix.length, ssb.length - 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        trimBuffer()
        flushNow()
    }

    private fun insertExpandedLines(groupId: String, lines: List<String>, level: LineLevel) {
        val idx = ssb.indexOf(groupId)
        if (idx < 0) return
        val lineStart = ssb.toString().lastIndexOf('\n', idx) + 1
        val lineEnd = ssb.indexOf('\n', idx) + 1
        val insertion = buildString {
            lines.forEach { line ->
                val prefix = levelPrefix(level)
                append(prefix).append(line).append('\n')
            }
        }
        ssb.replace(lineStart, lineEnd, insertion)
        textView.text = ssb
        post { if (followMode) fullScroll(View.FOCUS_DOWN) }
    }

    fun appendBuildTaskHeader(taskName: String, durationMs: Long? = null, failed: Boolean = false, status: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { appendBuildTaskHeader(taskName, durationMs, failed, status) }
            return
        }
        flushPending(Int.MAX_VALUE)
        val start = ssb.length
        val duration = durationMs?.let { " (${formatDuration(it)})" }.orEmpty()
        val statusStr = when {
            status?.contains("UP-TO-DATE") == true -> " UP-TO-DATE"
            status?.contains("FROM-CACHE") == true -> " FROM-CACHE"
            status?.contains("NO-SOURCE") == true -> " NO-SOURCE"
            status?.contains("SKIPPED") == true -> " SKIPPED"
            else -> ""
        }
        val icon = when {
            failed -> "\u2716"
            statusStr.isNotEmpty() -> "\u2500"
            else -> "\u25B6"
        }
        val color: Int = when {
            failed -> StudioUi.color(StudioUi.Red)
            statusStr.isNotEmpty() -> StudioUi.color(StudioUi.Muted)
            else -> StudioUi.palette.termGradleTask
        }
        ssb.append("  $icon $taskName$duration$statusStr\n")
        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (failed) {
            ssb.setSpan(
                BackgroundColorSpan(StudioUi.color(StudioUi.Red)),
                start, ssb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        trimBuffer()
        flushNow()
    }

    fun appendBuildSummary(errors: Int, warnings: Int, durationMs: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { appendBuildSummary(errors, warnings, durationMs) }
            return
        }
        flushPending(Int.MAX_VALUE)
        val start = ssb.length
        val success = errors == 0
        val icon = if (success) "Build finished" else "Build finished with errors"
        val color = if (success) StudioUi.Green else StudioUi.Red
        ssb.append(icon)
        if (errors > 0) ssb.append(" · $errors error(s)")
        if (warnings > 0) ssb.append(" · $warnings warning(s)")
        ssb.append('\n')
        ssb.setSpan(
            StyleSpan(Typeface.BOLD),
            start, ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        ssb.setSpan(
            ForegroundColorSpan(StudioUi.color(color)),
            start, ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        trimBuffer()
        flushNow()
    }

    fun currentText(): String {
        if (Looper.myLooper() == Looper.getMainLooper()) flushPending(Int.MAX_VALUE)
        return ssb.toString()
    }

    fun totalLines(): Int = totalLineCount

    private fun appendActionLineInternal(
        line: String,
        level: LineLevel,
        actionId: String?,
        handler: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { appendActionLineInternal(line, level, actionId, handler) }
            return
        }
        flushPending(Int.MAX_VALUE)
        val prefix = levelPrefix(level)
        Log.i("MobileStudioBuild", line)
        val start = ssb.length
        val actionStart = start + prefix.length
        ssb.append(prefix).append(line.take(MAX_LINE_CHARS)).append('\n')
        ssb.setSpan(
            ForegroundColorSpan(levelColor(level)),
            start,
            ssb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        ssb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                handler()
                actionId?.let { onActionClickListener?.invoke(it) }
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = StudioUi.color(StudioUi.Blue)
                ds.isUnderlineText = true
            }
        }, actionStart, ssb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        trimBuffer()
        flushNow()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        // Pace flushes: a back-to-back post chain runs textView.text = ssb
        // (full 50k-char relayout) for every handful of lines, saturating the
        // main thread and starving keyboard/shell input while logs stream.
        postDelayed({ flushNow() }, FLUSH_DELAY_MS)
    }

    private fun flushNow() {
        flushScheduled = false
        flushPending(MAX_LINES_PER_FLUSH)
        pendingLock.withLock {
            if (pendingLines.isNotEmpty()) scheduleFlush()
        }
    }

    private fun flushPending(maxLines: Int) {
        val toFlush = pendingLock.withLock {
            if (droppedLines > 0) {
                val warn = "Skipped $droppedLines log lines to protect the UI."
                val start = ssb.length
                ssb.append(levelPrefix(LineLevel.WARNING)).append(warn).append('\n')
                ssb.setSpan(ForegroundColorSpan(levelColor(LineLevel.WARNING)), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                droppedLines = 0
            }
            buildList {
                while (pendingLines.isNotEmpty() && size < maxLines) {
                    add(pendingLines.removeFirst())
                }
            }
        }
        toFlush.forEach { appendStyledLine(it.line, it.level) }
        trimBuffer()
        textView.text = ssb
        if (!followMode) return
        val now = System.currentTimeMillis()
        if (now - lastScrollMs > 600L) {
            lastScrollMs = now
            post { fullScroll(View.FOCUS_DOWN) }
        }
    }

private fun appendStyledLine(line: String, level: LineLevel) {
        val color = levelColor(level)
        val start = ssb.length
        val prefix = levelPrefix(level)
        ssb.append(prefix).append(line).append('\n')

        ssb.setSpan(ForegroundColorSpan(color), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        val bgColor = when (level) {
            LineLevel.ERROR -> StudioUi.palette.errBg
            LineLevel.WARNING -> StudioUi.palette.warnBg
            else -> Color.TRANSPARENT
        }
        if (bgColor != Color.TRANSPARENT) {
            ssb.setSpan(BackgroundColorSpan(bgColor), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Highlight URLs
        URL_PATTERN.findAll(line).forEach { match ->
            val matchStart = start + prefix.length + match.range.first
            val matchEnd = start + prefix.length + match.range.last + 1
            if (matchStart >= start && matchEnd <= ssb.length) {
                ssb.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val url = line.substring(match.range)
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                widget.context.startActivity(intent)
                            }
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = StudioUi.palette.termUrl
                            ds.isUnderlineText = true
                        }
                    },
                    matchStart, matchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // Highlight exit codes
        EXIT_CODE_PATTERN.findAll(line).forEach { match ->
            val matchStart = start + prefix.length + match.range.first
            val matchEnd = start + prefix.length + match.range.last + 1
            if (matchStart >= start && matchEnd <= ssb.length) {
                val code = match.groupValues[1].toIntOrNull() ?: 0
                val isError = code != 0
                ssb.setSpan(
                    ForegroundColorSpan(if (isError) StudioUi.color(StudioUi.Red) else StudioUi.color(StudioUi.Green)),
                    matchStart, matchEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (level == LineLevel.ERROR || level == LineLevel.WARNING || level == LineLevel.INFO) {
            val matches = ERROR_LINK_PATTERN.findAll(line)
            matches.forEach { match ->
                var filePath = match.groupValues[1]
                if (filePath.startsWith("file:///")) filePath = filePath.substring(7)
                else if (filePath.startsWith("file://")) filePath = filePath.substring(7)

                val fileLine = match.groupValues[2].toIntOrNull() ?: 0
                val fileCol = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0

                val lineStartOffset = ssb.length - line.length - 1
                val clickStart = lineStartOffset + match.range.first
                val clickEnd = lineStartOffset + match.groupValues[1].length + match.range.first // Only underline the path part

                ssb.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onErrorClickListener?.invoke(filePath, fileLine, fileCol)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = StudioUi.color(StudioUi.Blue)
                            ds.isUnderlineText = true
                        }
                    },
                    clickStart, clickEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }


    private fun trimBuffer() {
        val overflow = ssb.length - MAX_CHARS
        if (overflow <= 0) return
        val trimTo = ssb.indexOf('\n', overflow).takeIf { it > 0 } ?: overflow
        ssb.delete(0, trimTo.coerceAtMost(ssb.length))
    }

    private fun levelPrefix(level: LineLevel) = when (level) {
        LineLevel.ERROR -> "E "
        LineLevel.WARNING -> "W "
        LineLevel.SUCCESS -> "OK "
        LineLevel.DEBUG -> "D "
        LineLevel.INFO -> "  "
    }

    private fun levelColor(level: LineLevel): Int = when (level) {
        LineLevel.ERROR -> StudioUi.color(StudioUi.Red)
        LineLevel.WARNING -> StudioUi.color(StudioUi.Amber)
        LineLevel.SUCCESS -> StudioUi.color(StudioUi.Green)
        LineLevel.DEBUG -> StudioUi.color(StudioUi.Muted)
        LineLevel.INFO -> StudioUi.color(StudioUi.TextSoft)
    }

    companion object {
        private const val MAX_CHARS = 50_000
        private const val MAX_LINE_CHARS = 2_000
        private const val MAX_PENDING_LINES = 500
        private const val MAX_LINES_PER_FLUSH = 100
        private const val FLUSH_DELAY_MS = 120L
        private val ERROR_LINK_PATTERN = Regex("""(?<![\w:/.])((?:file:///|/)?[\w./\\-]+\.(?:kt|java|xml|kts|c|cpp|h|hpp|gradle|pro|aidl)):(\d+)(?::(\d+))?""", RegexOption.IGNORE_CASE)
        // Gradle task pattern for highlighting
        private val GRADLE_TASK_PATTERN = Regex("""(>?\s*Task\s+):([\w:]+)""")
        // URL pattern
        private val URL_PATTERN = Regex("""https?://[^\s]+""")
        // Exit code pattern
        private val EXIT_CODE_PATTERN = Regex("""exit[=\s](\d+)""", RegexOption.IGNORE_CASE)

        private fun formatDuration(ms: Long): String = when {
            ms < 1000 -> "${ms}ms"
            ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
            else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
        }
    }
}
