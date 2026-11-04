package com.aide.mobilestudio.ai.ui

/**
 * Line diff for the change previews.
 *
 * The preview is built on the UI thread of a phone that may be looking at a
 * ten-thousand-line file, so the work is bounded: identical leading and
 * trailing lines are trimmed first, the middle is compared with a table only
 * while it is small, and a middle too large for the table falls back to
 * "everything changed". That fallback is coarse, but it is honest and cheap -
 * a preview that stalls the workspace is worse than one that shows less.
 */
object AiDiff {

    /** One rendered line: '+' added, '-' removed, ' ' unchanged. */
    data class Line(val kind: Char, val text: String)

    data class Stats(val added: Int, val removed: Int)

    /** Largest middle the LCS table may cover (400x400 entries, ~640 KB). */
    private const val MAX_TABLE = 400

    /** Cap on the rendered line list, so a huge edit cannot exhaust memory. */
    private const val MAX_OUTPUT = 4000

    fun diff(before: String, after: String): List<Line> {
        if (before == after) return emptyList()
        val a = before.split('\n')
        val b = after.split('\n')
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        val out = ArrayList<Line>()
        for (i in 0 until start) out.add(Line(' ', a[i]))
        if (midA.size.toLong() * midB.size.toLong() > MAX_TABLE.toLong() * MAX_TABLE) {
            for (line in midA) out.add(Line('-', line))
            for (line in midB) out.add(Line('+', line))
        } else {
            out.addAll(lcs(midA, midB))
        }
        for (i in endA until a.size) out.add(Line(' ', a[i]))
        return if (out.size > MAX_OUTPUT) {
            out.subList(0, MAX_OUTPUT).toList() + Line(' ', "… diff truncated (${out.size - MAX_OUTPUT} more lines)")
        } else {
            out
        }
    }

    fun stats(before: String, after: String): Stats {
        var added = 0
        var removed = 0
        for (line in diff(before, after)) {
            when (line.kind) {
                '+' -> added++
                '-' -> removed++
            }
        }
        return Stats(added, removed)
    }

    /**
     * The diff with unchanged stretches collapsed to [context] lines around
     * each change. The approval dialog and the inline card both use this: the
     * interesting lines are never pushed out by a long unchanged body.
     */
    fun preview(before: String, after: String, maxLines: Int = 300, context: Int = 3): List<Line> {
        val all = diff(before, after)
        if (all.isEmpty()) return emptyList()
        val keep = BooleanArray(all.size)
        for (i in all.indices) {
            if (all[i].kind == ' ') continue
            for (k in (i - context).coerceAtLeast(0)..(i + context).coerceAtMost(all.lastIndex)) keep[k] = true
        }
        val out = ArrayList<Line>()
        var skipped = 0
        for (i in all.indices) {
            if (!keep[i]) {
                skipped++
                continue
            }
            if (skipped > 0) {
                out.add(Line(' ', "… $skipped unchanged line(s)"))
                skipped = 0
            }
            out.add(all[i])
            if (out.size >= maxLines) {
                out.add(Line(' ', "… preview truncated"))
                break
            }
        }
        return out
    }

    private fun lcs(a: List<String>, b: List<String>): List<Line> {
        val n = a.size
        val m = b.size
        // Built backwards so the walk below can read dp[i + 1][j] directly.
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<Line>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Line(' ', a[i]))
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Line('-', a[i]))
                    i++
                }
                else -> {
                    out.add(Line('+', b[j]))
                    j++
                }
            }
        }
        while (i < n) {
            out.add(Line('-', a[i]))
            i++
        }
        while (j < m) {
            out.add(Line('+', b[j]))
            j++
        }
        return out
    }
}
