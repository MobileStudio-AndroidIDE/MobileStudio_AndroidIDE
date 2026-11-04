package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Android Studio-style TODO / FIXME / HACK list.
 *
 * Scans the current project for common marker comments and shows them
 * in a filterable dialog. Clicking an entry opens the file at that line.
 */
object TodoExplorer {

    private val markers = listOf(
        "TODO", "FIXME", "HACK", "XXX", "BUG", "WORKAROUND", "OPTIMIZE", "REVIEW"
    )

    data class TodoItem(
        val file: File,
        val line: Int,
        val marker: String,
        val text: String
    )

    /**
     * Scans [dir] (recursively) for marker comments.
     */
    fun scan(dir: File, maxFiles: Int = 400): List<TodoItem> {
        val result = mutableListOf<TodoItem>()
        if (!dir.isDirectory) return result
        dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in CODE_EXTENSIONS }
            .take(maxFiles)
            .forEach { file -> result += scanFile(file) }
        return result.sortedWith(compareBy({ it.marker }, { it.file.name }, { it.line }))
    }

    /**
     * Scans a single [file] for marker comments.
     */
    fun scanFile(file: File): List<TodoItem> {
        val out = mutableListOf<TodoItem>()
        val size = try { file.length() } catch (_: Exception) { 0L }
        if (size > 5 * 1024 * 1024) return out
        try {
            file.readLines().forEachIndexed { idx, raw ->
                markers.forEach { marker ->
                    // look for // TODO, /* TODO, # FIXME
                    val lower = raw.uppercase()
                    val at = lower.indexOf(marker)
                    if (at >= 0) {
                        val comment = raw.substring(at + marker.length).trim().trim('*', '/', '-', ' ')
                        if (comment.isNotEmpty() && !comment.startsWith("mobile-studio")) {
                            out += TodoItem(file, idx + 1, marker, comment.take(120))
                            return@forEach
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // binary or unreadable
        }
        return out
    }

    private val CODE_EXTENSIONS = setOf(
        "kt", "kts", "java", "xml", "gradle", "groovy", "kts", "c", "cpp", "h",
        "hpp", "py", "js", "ts", "json", "toml", "properties", "md", "sh", "rs",
        "go", "swift", "ini", "yml", "yaml", "css", "html"
    )

    fun show(
        context: Context,
        projectDir: File?,
        onOpen: (File, Int) -> Unit
    ) {
        val dir = projectDir
        if (dir == null) {
            android.app.AlertDialog.Builder(context)
                .setTitle("TODO / FIXME")
                .setMessage("Open a project first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        android.app.AlertDialog.Builder(context).apply {
            setTitle("Scanning...")
            setMessage("Looking for TODO / FIXME markers in\n${dir.name}")
            setNegativeButton("Cancel", null)
        }.show().also { dialog ->
            object : Thread() {
                override fun run() {
                    val items = scan(dir)
                    runOnUi(context) {
                        try { dialog.dismiss() } catch (_: Exception) {}
                        if (items.isEmpty()) {
                            android.app.AlertDialog.Builder(context)
                                .setTitle("TODO / FIXME")
                                .setMessage("Nothing found.")
                                .setPositiveButton("OK", null)
                                .show()
                            return@runOnUi
                        }
                        renderDialog(context, dir, items, onOpen)
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun renderDialog(
        context: Context,
        projectDir: File,
        items: List<TodoItem>,
        onOpen: (File, Int) -> Unit
    ) {
        val grouped = items.groupBy { it.marker }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        }

        val summary = TextView(context).apply {
            text = "${items.size} items: ${grouped.keys.joinToString(", ") { "$it:${grouped[it]!!.size}" }}"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.palette.muted)
            setPadding(dp(context, 2), 0, 0, dp(context, 6))
        }
        root.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val filterInput = android.widget.EditText(context).apply {
            hint = AppLanguage.literal(context, "Filter...")
            setSingleLine(true)
            textSize = 12f
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            background = StudioUi.inputBg(context)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(filterInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 6)
        })

        val list = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        root.addView(list, android.view.ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 420)
        ))
        list.addView(box)

        fun renderRows(query: String) {
            box.removeAllViews()
            val filtered = if (query.isBlank()) items else items.filter {
                (it.marker + " " + it.text + " " + it.file.name).contains(query, ignoreCase = true)
            }
            if (filtered.isEmpty()) {
                box.addView(TextView(context).apply {
                    text = AppLanguage.literal(context, "No matches")
                    setTextColor(StudioUi.palette.textSoft)
                    textSize = 12f
                    setPadding(0, dp(context, 24), 0, dp(context, 24))
                    gravity = android.view.Gravity.CENTER
                })
                return
            }
            filtered.forEach { item ->
                val rel = try {
                    projectDir.toPath().relativize(item.file.toPath()).toString()
                } catch (_: Exception) {
                    item.file.name
                }
                val color = when (item.marker) {
                    "FIXME", "BUG" -> StudioUi.palette.error
                    "HACK", "XXX", "WORKAROUND" -> StudioUi.palette.warning
                    "OPTIMIZE", "REVIEW" -> StudioUi.palette.info
                    else -> StudioUi.palette.success
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                    background = StudioUi.rounded(
                        context,
                        fill = StudioUi.palette.surface2,
                        stroke = StudioUi.palette.border,
                        radiusDp = 6
                    )
                    setOnClickListener { onOpen(item.file, item.line) }
                }
                row.addView(TextView(context).apply {
                    text = "${item.marker}  ${item.text}"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(color)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(TextView(context).apply {
                    text = "$rel:${item.line}"
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.palette.muted)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(context, 2)
                })
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(context, 4)
                })
            }
        }

        renderRows("")

        filterInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                renderRows(s?.toString() ?: "")
            }
        })

        android.app.AlertDialog.Builder(context)
            .setTitle("TODO / FIXME")
            .setView(root)
            .setNegativeButton(AppLanguage.literal(context, "Close"), null)
            .show()
    }

    private fun runOnUi(context: Context, block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post { block() }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}