package com.aide.mobilestudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aide.mobilestudio.highlight.LanguageHighlighter
import java.io.File

/**
 * Android Studio-style unified diff viewer.
 *
 * Launch with DiffViewerActivity.start(context, labelA, textA, labelB, textB)
 * or DiffViewerActivity.startFiles(context, fileA, fileB).
 */
class DiffViewerActivity : Activity() {

    companion object {
        fun start(
            context: android.content.Context,
            labelA: String,
            textA: String,
            labelB: String,
            textB: String,
            filePath: String? = null,
        ) {
            val intent = android.content.Intent(context, DiffViewerActivity::class.java)
                .putExtra("labelA", labelA)
                .putExtra("textA", textA)
                .putExtra("labelB", labelB)
                .putExtra("textB", textB)
            if (filePath != null) intent.putExtra("filePath", filePath)
            context.startActivity(intent)
        }

        fun startFiles(context: android.content.Context, fileA: File, fileB: File) {
            start(
                context,
                fileA.name,
                runCatching { fileA.readText() }.getOrDefault(""),
                fileB.name,
                runCatching { fileB.readText() }.getOrDefault(""),
                // The extension is what selects a syntax highlighter; labels
                // alone lose it after the " (before)" suffix is added.
                fileA.name,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.parseColor("#0B0F15")
        applyDiffLayout()
    }

    private fun applyDiffLayout() {
        val labelA = intent.getStringExtra("labelA") ?: "A"
        val textA = intent.getStringExtra("textA") ?: ""
        val labelB = intent.getStringExtra("labelB") ?: "B"
        val textB = intent.getStringExtra("textB") ?: ""
        // Highlight with the file's real language. The label carries a
        // " (before)"/" (after)" suffix, so an explicit path is used when the
        // caller has one; otherwise the label is stripped before detection.
        val filePath = intent.getStringExtra("filePath")
            ?: labelA.removeSuffix(" (before)").removeSuffix(" (after)").trim()
        val highlighter = runCatching { LanguageHighlighter.forFile(filePath) }.getOrNull()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F15"))
        }
        setContentView(root)

        // Header
        root.addView(TextView(this).apply {
            text = "Diff: $labelA \u21C6 $labelB"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#DCE3EE"))
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = StudioUi.rounded(this@DiffViewerActivity, fill = "#12161F", radiusDp = 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Legend
        root.addView(TextView(this).apply {
            text = "\u2716 removed      \u002B added      \u2022 context"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8E99AC"))
            setPadding(dp(16), 0, dp(16), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Stats
        val aLines = textA.lines()
        val bLines = textB.lines()
        val diffs = computeUnified(aLines, bLines)
        val removed = diffs.count { it.kind == Kind.REMOVED }
        val added = diffs.count { it.kind == Kind.ADDED }

        root.addView(TextView(this).apply {
            text = "${removed} removed, $added added, ${diffs.count { it.kind == Kind.CONTEXT }} unchanged"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(if (removed + added == 0) "#7EE787" else "#E5C07B"))
            setPadding(dp(16), 0, dp(16), dp(8))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (diffs.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Identical"
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#7EE787"))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            return
        }

        // Diff body (horizontal + vertical scroll, monospace line numbers)
        val hScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        val vScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        diffs.forEach { diff ->
            val marker = when (diff.kind) {
                Kind.REMOVED -> "\u2716"
                Kind.ADDED -> "\u002B"
                Kind.CONTEXT -> "\u2022"
            }
            val color = when (diff.kind) {
                Kind.REMOVED -> "#E06C75"
                Kind.ADDED -> "#98C379"
                Kind.CONTEXT -> "#5C677A"
            }
            val bg = when (diff.kind) {
                Kind.REMOVED -> "#2A1418"
                Kind.ADDED -> "#12251A"
                Kind.CONTEXT -> "#171C25"
            }
            val numbers = when (diff.kind) {
                Kind.REMOVED -> "%4s %4s".format(diff.oldNo, "-")
                Kind.ADDED -> "%4s %4s".format("-", diff.newNo)
                Kind.CONTEXT -> "%4s %4s".format(diff.oldNo, diff.newNo)
            }
            // The diff colour stays the fallback; language spans colour the
            // code on top of it. Without this a Kotlin change rendered as
            // flat green/red text and could not be read as code.
            val line = SpannableString("$marker  $numbers  ${diff.text}")
            val base = Color.parseColor(color)
            line.setSpan(
                ForegroundColorSpan(base),
                0,
                line.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val codeStart = line.length - diff.text.length
            highlighter?.let { h ->
                for (span in h.highlight(diff.text)) {
                    val start = codeStart + span.start
                    val end = codeStart + span.end
                    if (start < codeStart || start >= line.length || end > line.length || end <= start) continue
                    line.setSpan(
                        ForegroundColorSpan(span.color),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    if (span.bold) {
                        line.setSpan(
                            StyleSpan(Typeface.BOLD),
                            start,
                            end,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
            box.addView(TextView(this).apply {
                text = line
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(base)
                setPadding(dp(8), dp(2), dp(16), dp(2))
                background = StudioUi.rounded(this@DiffViewerActivity, fill = bg, radiusDp = 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        vScroll.addView(box)
        hScroll.addView(vScroll)
        root.addView(hScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private enum class Kind { REMOVED, ADDED, CONTEXT }
    private data class DiffLine(val kind: Kind, val text: String, val oldNo: Int, val newNo: Int)

    private fun computeUnified(a: List<String>, b: List<String>): List<DiffLine> {
        // Simple LCS-based diff for reasonable line counts.
        val n = a.size
        val m = b.size
        if (n * m > 4_000_000L) {
            // Fallback for huge inputs: naive
            return naiveDiff(a, b)
        }
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val out = mutableListOf<DiffLine>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                out += DiffLine(Kind.CONTEXT, a[i], i + 1, j + 1)
                i++; j++
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out += DiffLine(Kind.REMOVED, a[i], i + 1, -1)
                i++
            } else {
                out += DiffLine(Kind.ADDED, b[j], -1, j + 1)
                j++
            }
        }
        while (i < n) { out += DiffLine(Kind.REMOVED, a[i], i + 1, -1); i++ }
        while (j < m) { out += DiffLine(Kind.ADDED, b[j], -1, j + 1); j++ }
        return out
    }

    private fun naiveDiff(a: List<String>, b: List<String>): List<DiffLine> {
        val min = minOf(a.size, b.size)
        val out = mutableListOf<DiffLine>()
        for (k in 0 until min) {
            if (a[k] == b[k]) out += DiffLine(Kind.CONTEXT, a[k], k + 1, k + 1)
            else {
                out += DiffLine(Kind.REMOVED, a[k], k + 1, -1)
                out += DiffLine(Kind.ADDED, b[k], -1, k + 1)
            }
        }
        for (k in min until a.size) out += DiffLine(Kind.REMOVED, a[k], k + 1, -1)
        for (k in min until b.size) out += DiffLine(Kind.ADDED, b[k], -1, k + 1)
        return out
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}