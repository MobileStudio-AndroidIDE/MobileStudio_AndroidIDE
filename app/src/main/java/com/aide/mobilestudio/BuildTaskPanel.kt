package com.aide.mobilestudio

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList

class BuildTaskPanel(context: Context) : ScrollView(context) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val tasks = CopyOnWriteArrayList<TaskEntry>()
    private var onTaskClickListener: ((TaskEntry) -> Unit)? = null
    private var currentFilterTaskId: String? = null
    private val bufferingAnimators = mutableMapOf<String, ValueAnimator>()
    private val bufferingTexts = mutableMapOf<String, String>()

    data class TaskEntry(
        val id: String,
        val fullPath: String,
        var status: Status = Status.RUNNING,
        var startTimeMs: Long = System.currentTimeMillis(),
        var endTimeMs: Long = 0L,
        var durationMs: Long = 0L,
        var errorCount: Int = 0,
    ) {
        enum class Status { RUNNING, SUCCESS, FAILED, SKIPPED, UP_TO_DATE, FROM_CACHE, NO_SOURCE }
    }

    init {
        addView(container)
        isVerticalScrollBarEnabled = true
    }

    fun setOnTaskClickListener(listener: (TaskEntry) -> Unit) { onTaskClickListener = listener }

    fun beginTask(fullPath: String, line: String) {
        val id = fullPath
        // update existing if already present
        val existing = tasks.firstOrNull { it.id == id }
        if (existing != null) {
            existing.status = TaskEntry.Status.RUNNING
            existing.startTimeMs = System.currentTimeMillis()
            existing.endTimeMs = 0L
            existing.durationMs = 0L
            existing.errorCount = 0
            updateEntryView(existing)
            return
        }
        val entry = TaskEntry(id = id, fullPath = fullPath)
        tasks.add(entry)
        addEntryView(entry)
        post { fullScroll(View.FOCUS_DOWN) }
    }

    fun endTask(fullPath: String, success: Boolean, statusSuffix: String? = null) {
        val entry = tasks.lastOrNull { it.id == fullPath && it.status == TaskEntry.Status.RUNNING }
            ?: tasks.lastOrNull { it.id == fullPath }
            ?: return
        entry.endTimeMs = System.currentTimeMillis()
        entry.durationMs = entry.endTimeMs - entry.startTimeMs
        entry.status = when {
            !success -> TaskEntry.Status.FAILED
            statusSuffix?.contains("UP-TO-DATE") == true -> TaskEntry.Status.UP_TO_DATE
            statusSuffix?.contains("FROM-CACHE") == true -> TaskEntry.Status.FROM_CACHE
            statusSuffix?.contains("NO-SOURCE") == true -> TaskEntry.Status.NO_SOURCE
            statusSuffix?.contains("SKIPPED") == true -> TaskEntry.Status.SKIPPED
            else -> TaskEntry.Status.SUCCESS
        }
        updateEntryView(entry)
        removeBuffering(entry.id)
    }

    fun failTask(fullPath: String) {
        val existing = tasks.lastOrNull { it.id == fullPath }
            ?: return
        existing.status = TaskEntry.Status.FAILED
        if (existing.endTimeMs == 0L) {
            existing.endTimeMs = System.currentTimeMillis()
            existing.durationMs = existing.endTimeMs - existing.startTimeMs
        }
        existing.errorCount++
        updateEntryView(existing)
        removeBuffering(existing.id)
    }

    fun setFilter(taskId: String?) {
        currentFilterTaskId = taskId
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? LinearLayout ?: continue
            val tag = row.tag as? String ?: continue
            row.alpha = if (taskId == null || tag == taskId) 1f else 0.3f
        }
    }

    fun clearFilter() {
        currentFilterTaskId = null
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? LinearLayout)?.alpha = 1f
        }
    }

    fun currentFilter(): String? = currentFilterTaskId

    fun getTask(fullPath: String): TaskEntry? = tasks.firstOrNull { it.id == fullPath }

    fun getAllTasks(): List<TaskEntry> = tasks.toList()

    fun clear() {
        tasks.clear()
        bufferingAnimators.values.forEach { it.cancel() }
        bufferingAnimators.clear()
        bufferingTexts.clear()
        container.removeAllViews()
        currentFilterTaskId = null
    }

    @SuppressLint("SetTextI18n")
    private fun addEntryView(entry: TaskEntry) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            tag = entry.id
            setOnClickListener {
                val id = tag as? String ?: return@setOnClickListener
                val task = tasks.firstOrNull { it.id == id } ?: return@setOnClickListener
                onTaskClickListener?.invoke(task)
            }
        }

        val iconTv = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, dp(6), 0)
            tag = "icon"
        }
        row.addView(iconTv)

        val nameTv = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            tag = "name"
        }
        row.addView(nameTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val durationTv = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(dp(4), 0, 0, 0)
            tag = "duration"
        }
        row.addView(durationTv)

        container.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        updateEntryView(entry)
    }

    private fun startBuffering(taskId: String, nameTv: TextView, iconTv: TextView) {
        removeBuffering(taskId)
        val dots = arrayOf("", ".", "..", "...")
        val anim = ValueAnimator.ofInt(0, 3).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val idx = a.animatedValue as Int
                iconTv.text = dots[idx]
                iconTv.setTextColor(StudioUi.color(StudioUi.Blue))
            }
        }
        anim.start()
        bufferingAnimators[taskId] = anim
    }

    private fun removeBuffering(taskId: String) {
        bufferingAnimators[taskId]?.cancel()
        bufferingAnimators.remove(taskId)
        bufferingTexts.remove(taskId)
    }

    @SuppressLint("SetTextI18n")
    private fun updateEntryView(entry: TaskEntry) {
        val row = container.findViewWithTag<View>(entry.id) as? LinearLayout ?: return
        val iconTv = row.findViewWithTag<TextView>("icon")
        val nameTv = row.findViewWithTag<TextView>("name")
        val durationTv = row.findViewWithTag<TextView>("duration")

        val shortName = entry.fullPath.substringAfterLast(":")
        nameTv?.text = shortName
        nameTv?.setOnLongClickListener {
            // on long click show full path
            nameTv?.text = entry.fullPath
            Handler(Looper.getMainLooper()).postDelayed({
                nameTv?.text = shortName
            }, 2000)
            true
        }

        when (entry.status) {
            TaskEntry.Status.RUNNING -> {
                iconTv?.text = ""
                iconTv?.setTextColor(StudioUi.color(StudioUi.Blue))
                startBuffering(entry.id, nameTv!!, iconTv!!)
                durationTv?.text = formatDuration(System.currentTimeMillis() - entry.startTimeMs)
                nameTv?.setTextColor(StudioUi.color(StudioUi.Text))
            }
            TaskEntry.Status.SUCCESS -> {
                removeBuffering(entry.id)
                iconTv?.text = "\u2714"
                iconTv?.setTextColor(StudioUi.color(StudioUi.Green))
                durationTv?.text = formatDuration(entry.durationMs)
                nameTv?.setTextColor(StudioUi.color(StudioUi.TextSoft))
            }
            TaskEntry.Status.FAILED -> {
                removeBuffering(entry.id)
                iconTv?.text = "\u2716"
                iconTv?.setTextColor(StudioUi.color(StudioUi.Red))
                durationTv?.text = formatDuration(entry.durationMs)
                nameTv?.setTextColor(StudioUi.color(StudioUi.Red))
            }
            TaskEntry.Status.SKIPPED -> {
                removeBuffering(entry.id)
                iconTv?.text = "\u2500"
                iconTv?.setTextColor(StudioUi.color(StudioUi.Muted))
                durationTv?.text = ""
                nameTv?.setTextColor(StudioUi.color(StudioUi.Muted))
            }
            TaskEntry.Status.UP_TO_DATE -> {
                removeBuffering(entry.id)
                iconTv?.text = "\u2500"
                iconTv?.setTextColor(StudioUi.color(StudioUi.Muted))
                durationTv?.text = ""
                nameTv?.setTextColor(StudioUi.color(StudioUi.Muted))
            }
            TaskEntry.Status.FROM_CACHE, TaskEntry.Status.NO_SOURCE -> {
                removeBuffering(entry.id)
                iconTv?.text = "\u2500"
                iconTv?.setTextColor(StudioUi.color(StudioUi.Muted))
                durationTv?.text = ""
                nameTv?.setTextColor(StudioUi.color(StudioUi.Muted))
            }
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
