package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class ProblemsAdapter(
    private val onNavigateToFile: ((File, Int) -> Unit)? = null,
    private val onShowProblemActions: ((BuildProblem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    init {
        // Phase 7.1: Enable view recycling optimizations
        setHasStableIds(true)
    }

    private val displayedItems = mutableListOf<ProblemItem>()
    private var allProblems = emptyList<BuildProblem>()
    private val expandedFiles = mutableSetOf<String>()

    sealed class ProblemItem {
        data class FileHeader(val file: File?, val count: Int, val path: String) : ProblemItem()
        data class Problem(val problem: BuildProblem) : ProblemItem()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PROBLEM = 1
    }

    fun update(problems: List<BuildProblem>) {
        allProblems = problems
        rebuildItems()
    }

    fun updateBatched(problems: List<BuildProblem>, batchSize: Int = 50, intervalMs: Long = 1000L) {
        update(problems)
    }

    private fun rebuildItems() {
        displayedItems.clear()
        if (allProblems.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        val grouped = allProblems.groupBy { it.file?.absolutePath ?: "(no file)" }
        grouped.forEach { (filePath, fileProblems) ->
            val file = fileProblems.first().file
            // Auto-expand all files to show all problems
            expandedFiles.add(filePath)
            displayedItems.add(ProblemItem.FileHeader(file, fileProblems.size, filePath))
            fileProblems.forEach { problem ->
                displayedItems.add(ProblemItem.Problem(problem))
            }
        }
        notifyDataSetChanged()
    }

    fun setExpanded(filePath: String, expanded: Boolean) {
        if (expanded) expandedFiles.add(filePath) else expandedFiles.remove(filePath)
        rebuildItems()
    }

    private fun dp(view: View, v: Int): Int = (v * view.context.resources.displayMetrics.density).toInt()

    override fun getItemViewType(position: Int): Int {
        return when (displayedItems[position]) {
            is ProblemItem.FileHeader -> TYPE_HEADER
            is ProblemItem.Problem -> TYPE_PROBLEM
        }
    }

    override fun getItemId(position: Int): Long {
        return displayedItems[position].hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return if (viewType == TYPE_HEADER) {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(parent, 12), dp(parent, 8), dp(parent, 12), dp(parent, 8))
                setBackgroundColor(StudioUi.palette.surface)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            HeaderVH(root)
        } else {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(parent, 32), dp(parent, 4), dp(parent, 12), dp(parent, 4))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            ProblemVH(root)
        }
    }

    override fun getItemCount() = displayedItems.size

    fun getItem(position: Int): BuildProblem? {
        val item = displayedItems[position]
        return when (item) {
            is ProblemItem.Problem -> item.problem
            else -> null
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayedItems[position]) {
            is ProblemItem.FileHeader -> (holder as HeaderVH).bind(item)
            is ProblemItem.Problem -> (holder as ProblemVH).bind(item.problem)
        }
    }

    inner class HeaderVH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        fun bind(header: ProblemItem.FileHeader) {
            root.removeAllViews()
            val context = root.context
            
            // Icon
            root.addView(TextView(context).apply {
                text = if (expandedFiles.contains(header.path)) " \u25BE " else " \u25B8 "
                setTextColor(StudioUi.palette.textSoft)
                textSize = 12f
            })
            
            val fileIcon = TextView(context).apply {
                text = if (header.file?.extension?.lowercase() in setOf("c", "cpp", "h")) " \u24B8 " else " \u24C9 "
                setTextColor(StudioUi.palette.accent)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }
            root.addView(fileIcon)
            
            root.addView(TextView(context).apply {
                text = header.file?.name ?: "(no file)"
                setTextColor(StudioUi.palette.text)
                textSize = 13f
                setPadding(dp(root, 4), 0, dp(root, 8), 0)
            })
            
            root.addView(TextView(context).apply {
                text = "${header.count} problems"
                setTextColor(StudioUi.palette.textSoft)
                textSize = 11f
            })
            
            root.setOnClickListener { toggleFile(header.path) }
        }
    }

    inner class ProblemVH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        fun bind(problem: BuildProblem) {
            root.removeAllViews()
            val context = root.context
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(root, 32), dp(root, 4), dp(root, 12), dp(root, 8))
            
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            
            val icon = TextView(context).apply {
                text = if (problem.severity == BuildProblem.Severity.ERROR) " \u2716 " else " \u26A0 "
                setTextColor(if (problem.severity == BuildProblem.Severity.ERROR) StudioUi.palette.error else StudioUi.palette.warning)
                textSize = 12f
            }
            header.addView(icon)
            
            val severityLabel = TextView(context).apply {
                text = problem.severity.name
                setTextColor(if (problem.severity == BuildProblem.Severity.ERROR) StudioUi.palette.error else StudioUi.palette.warning)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(dp(root, 4), 0, dp(root, 8), 0)
            }
            header.addView(severityLabel)
            
            val lineCol = TextView(context).apply {
                text = " ${problem.file?.name ?: ""}:${problem.line}:${problem.column} "
                setTextColor(StudioUi.palette.textSoft)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(root, 4), 0, dp(root, 8), 0)
            }
            header.addView(lineCol)
            
            header.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            root.addView(header)
            
            // Full error message - multiline
            val msg = TextView(context).apply {
                text = problem.message
                setTextColor(StudioUi.palette.text)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(dp(root, 12), dp(root, 2), dp(root, 12), dp(root, 2))
                maxLines = 10 // Allow multiple lines
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(msg)
            
            // Raw output if available and different from message
            if (problem.raw.isNotBlank() && problem.raw != problem.message) {
                val rawView = TextView(context).apply {
                    text = problem.raw
                    setTextColor(StudioUi.palette.textSoft)
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(root, 12), 0, dp(root, 12), dp(root, 4))
                    maxLines = 5
                    ellipsize = TextUtils.TruncateAt.END
                }
                root.addView(rawView)
            }
            
            // Click to navigate
            root.setOnClickListener {
                if (problem.line > 0 && problem.file?.exists() == true) {
                    onNavigateToFile?.invoke(problem.file!!, problem.line)
                }
            }
            
            // Long click for actions
            root.setOnLongClickListener {
                showProblemActions(problem)
                true
            }
        }
    }

    // Navigation callbacks
    private fun openFile(file: File) {
        onNavigateToFile?.invoke(file, 0)
    }
    
    private fun navigateToLine(line: Int) {
        onNavigateToFile?.invoke(null as java.io.File, line)
    }
    
    private fun showProblemActions(problem: BuildProblem) {
        onShowProblemActions?.invoke(problem)
    }
    
    private fun toggleFile(filePath: String) {
        if (expandedFiles.contains(filePath)) {
            expandedFiles.remove(filePath)
        } else {
            expandedFiles.add(filePath)
        }
        rebuildItems()
    }
}
