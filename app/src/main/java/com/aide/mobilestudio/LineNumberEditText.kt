package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.widget.AppCompatEditText
import java.util.LinkedList

/**
 * ?번호 + Undo/Redo ?택???장???디?? * - ?번호 gutter (1..N)
 * - ?재 ??이?이?? * - Undo/Redo (최? 200 ?텝)
 * - ??괄호 ?동 ?기 (?? ???으??킵)
 */
class LineNumberEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyle) {

    init {
        // Load editor font settings from SharedPreferences
        val prefs = context.getSharedPreferences("mobilestudio_settings", android.content.Context.MODE_PRIVATE)
        val fontSize = prefs.getString("font_size", "14sp")?.let { s ->
            s.removeSuffix("sp").toFloatOrNull() ?: 14f
        } ?: 14f
        val fontFamily = prefs.getString("font_family", "Monospace")
        val theme = prefs.getString("theme", "Dark")

        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize)
        setTypeface(when (fontFamily) {
            "Sans-serif" -> Typeface.SANS_SERIF
            "Serif" -> Typeface.SERIF
            "System Default" -> Typeface.DEFAULT
            else -> Typeface.create("monospace", Typeface.NORMAL)
        })

        // Apply theme-based text color
        val textColor = when (theme) {
            "Light" -> android.graphics.Color.BLACK
            "Darcula" -> android.graphics.Color.parseColor("#F0F6FC")
            else -> android.graphics.Color.parseColor("#F0F6FC") // Dark
        }
        setTextColor(textColor)
        setHintTextColor(android.graphics.Color.argb(120, 255, 255, 255))
    }

    // Gutter and Editor styling (refreshed from theme on each draw)
    private val palette get() = StudioUi.palette
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.NORMAL)
    }
    private val currentLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gutterBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val indentGuidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val navHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val autocompleteCandidates = listOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while",
        "return", "try", "catch", "finally", "throw", "import", "package", "override", "suspend",
        "data", "sealed", "companion", "init", "constructor", "abstract",
        "print", "println", "readLine", "length", "size", "isEmpty", "notEmpty", "contains",
        "startsWith", "endsWith", "indexOf", "substring", "replace", "toUpperCase", "toLowerCase",
        "toInt", "toLong", "toDouble", "toFloat", "toBoolean", "toString", "map", "filter", "forEach",
        "listOf", "mapOf", "setOf", "arrayOf", "mutableListOf", "mutableMapOf", "mutableSetOf",
        "buildList", "buildMap", "buildSet", "run", "with", "let", "apply", "also"
    )

    private val bracketPairs = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        "'" to "'"
    )


    private fun refreshColors() {
        val p = palette
        gutterPaint.color = p.gutterBg
        numPaint.color = p.gutterText
        currentLinePaint.color = p.currentLine
        gutterBorderPaint.color = p.divider
        val a = p.accent
        bracketPaint.color = Color.argb(44, Color.red(a), Color.green(a), Color.blue(a))
        indentGuidePaint.color = p.editorIndentGuide
        navHighlightPaint.color = p.selection
        hintPaint.color = p.surface2
        hintTextPaint.color = p.textSoft
        val lc = p.textSoft
        lensPaint.color = Color.argb(0x50, Color.red(lc), Color.green(lc), Color.blue(lc))
        ghostTextPaint.color = Color.argb(0x90, Color.red(lc), Color.green(lc), Color.blue(lc))
        setBackgroundColor(p.editorBg)
        setTextColor(p.editorText)
        setHintTextColor(p.muted)
        setHighlightColor(p.editorSelection)
        post { applyCaretColor(p.caret) }
    }

    private var gutterWidth = 0
    private var lastLineCount = -1
    private var minGutterWidth = 42 // Default minimum for small screens
    private var bracketStart = -1
    private var bracketEnd = -1
    private var highlightedLine = -1

    data class InlayHint(val offset: Int, val text: String)
    data class CodeLens(val line: Int, val text: String)

    var onGutterLineTap: ((Int) -> Unit)? = null

    init {
        setOnTouchListener { v, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP && event.x <= gutterWidth) {
                val lyt = layout ?: return@setOnTouchListener false
                val y = event.y.toInt() - paddingTop + scrollY
                val line = lyt.getLineForVertical(y)
                if (line >= 0 && line < lyt.lineCount) {
                    val start = lyt.getLineStart(line)
                    val end = lyt.getLineEnd(line)
                    setSelection(start, end)
                    onGutterLineTap?.invoke(line)
                }
                true
            } else {
                false
            }
        }
    }

    private val inlayHints = mutableListOf<InlayHint>()
    private val codeLenses = mutableListOf<CodeLens>()

    fun setInlayHints(hints: List<InlayHint>) {
        inlayHints.clear()
        inlayHints.addAll(hints)
        postInvalidate()
    }

    fun setCodeLenses(lenses: List<CodeLens>) {
        codeLenses.clear()
        codeLenses.addAll(lenses)
        postInvalidate()
    }

    fun setGhostText(text: String?, offset: Int = -1) {
        ghostText = text
        ghostTextOffset = if (offset >= 0) offset else selectionStart.coerceIn(0, this.text?.length ?: 0)
        postInvalidate()
    }

    fun clearGhostText() {
        ghostText = null
        ghostTextOffset = -1
        postInvalidate()
    }

    // Configurable text size (10-14sp, default 12.5sp)
    fun setEditorTextSize(sp: Float) {
        textSize = sp.coerceIn(10f, 14f)
        postInvalidate()
    }

    fun getEditorTextSize(): Float = textSize

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StudioUi.palette.surface2
        style = Paint.Style.FILL
        textSize = 10f * context.resources.displayMetrics.density
    }

    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StudioUi.palette.textSoft; textSize = 10f * context.resources.displayMetrics.density
        typeface = Typeface.create("monospace", Typeface.NORMAL)
    }

    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = StudioUi.palette.textSoft
        color = Color.argb(0x50, Color.red(c), Color.green(c), Color.blue(c))
        textSize = 10f * context.resources.displayMetrics.density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    // Ghost text (inline AI completion)
    private var ghostText: String? = null
    private var ghostTextOffset: Int = -1
    private val ghostTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = StudioUi.palette.textSoft
        color = Color.argb(0x90, Color.red(c), Color.green(c), Color.blue(c))
        textSize = 14f * context.resources.displayMetrics.density
        typeface = Typeface.create("monospace", Typeface.NORMAL)
    }

    // Multi-cursor support: extra cursor offsets (absolute), main cursor excluded.
    private val extraCursors = mutableListOf<Int>()
    private var propagatingMultiEdit = false

    val multiCursorCount: Int get() = extraCursors.size + 1

    fun isMultiCursorActive(): Boolean = extraCursors.isNotEmpty()

    fun cursorOffsets(): List<Int> =
        (listOf(selectionStart.coerceAtLeast(0)) + extraCursors).distinct().sorted()

    /**
     * Adds all occurrences of [word] (case-sensitive) as extra cursors.
     * Returns the number of cursors created.
     */
    fun selectAllOccurrences(word: String): Int {
        extraCursors.clear()
        if (word.isEmpty()) return 0
        val src = text?.toString().orEmpty()
        var from = 0
        while (true) {
            val idx = src.indexOf(word, from)
            if (idx < 0) break
            extraCursors.add(idx)
            from = idx + word.length
        }
        if (extraCursors.isNotEmpty()) {
            setSelection(extraCursors[0])
        }
        return extraCursors.size
    }

    /**
     * Adds the next occurrence of the current selection/word after the active
     * cursor as an extra cursor and moves there. Returns the cursor count.
     */
    fun addNextOccurrence(word: String): Int {
        if (word.isEmpty()) return multiCursorCount
        val src = text?.toString().orEmpty()
        var searchFrom = selectionStart + word.length
        val existing = extraCursors.toSet()
        var guard = 0
        while (guard++ < src.length) {
            val idx = src.indexOf(word, searchFrom)
            if (idx < 0) {
                // wrap around
                if (searchFrom != 0) { searchFrom = 0; continue }
                break
            }
            if (idx !in existing) {
                extraCursors.add(idx)
                extraCursors.sort()
                setSelection(idx, idx + word.length)
                return multiCursorCount
            }
            searchFrom = idx + word.length
        }
        return multiCursorCount
    }

    fun clearMultiCursors() { extraCursors.clear() }

    /**
     * Replicates a text insertion that just happened at [at] to every extra
     * cursor, keeping cursor layout consistent. Called from the owner activity's
     * TextWatcher (onTextChanged) together with the inserted span.
     */
    fun propagateTypedText(at: Int, inserted: CharSequence) {
        if (propagatingMultiEdit || extraCursors.isEmpty() || inserted.isEmpty()) return
        propagatingMultiEdit = true
        try {
            val len = inserted.length
            val sorted = extraCursors.sortedDescending()
            val newOffsets = mutableListOf<Int>()
            var adjustments = 0
            for (off in sorted) {
                if (off == at) continue
                if (off > at && off <= at + len) continue // inside inserted region? skip
                val target = if (off > at) off + len else off
                // account for prior insertions above this cursor
                val real = target
                runCatching { text?.insert(real.coerceIn(0, text?.length ?: 0), inserted) } ?: continue
                newOffsets.add(real + len)
            }
            extraCursors.clear()
            extraCursors.addAll(newOffsets)
        } finally {
            propagatingMultiEdit = false
        }
    }

    /**
     * Replicates a deletion [delStart..delEnd) that just happened at the main
     * cursor to every extra cursor.
     */
    fun propagateDeletedText(delStart: Int, delEnd: Int) {
        if (propagatingMultiEdit || extraCursors.isEmpty()) return
        propagatingMultiEdit = true
        try {
            val len = delEnd - delStart
            val sorted = extraCursors.sortedDescending()
            val survivors = mutableListOf<Int>()
            for (off in sorted) {
                if (off >= delStart && off < delEnd) continue // swallowed by deletion
                // deleting content before/at this cursor shifts it left
                val target = if (off >= delStart) (off - len).coerceAtLeast(0) else off
                // delete same amount at this cursor
                val t2 = target.coerceAtMost(text?.length ?: 0)
                val end = minOf(t2 + len, text?.length ?: 0)
                if (t2 < end) {
                    runCatching { text?.delete(t2, end) }
                }
                survivors.add(t2)
            }
            extraCursors.clear()
            extraCursors.addAll(survivors.sorted())
        } finally {
            propagatingMultiEdit = false
        }
    }

    // Code folding: foldable ranges discovered by indent, currently collapsed
    private val foldStartLines = linkedSetOf<Int>()   // 0-based start lines currently collapsed
    private val collapsedLineRanges = mutableMapOf<Int, IntRange>() // startLine -> collapsed range (start..end)

    fun hasCollapsedBlocks(): Boolean = collapsedLineRanges.isNotEmpty()

    fun isLineCollapsed(line: Int): Boolean =
        collapsedLineRanges.values.any { line in it }

    fun clearFolding() {
        collapsedLineRanges.clear()
        foldStartLines.clear()
        invalidate()
    }

    /**
     * Toggles a fold at 0-based [line]. The block is computed by indentation:
     * consecutive following lines whose indent is deeper than [line]'s own.
     * Returns true if a block existed to fold.
     */
    fun toggleFoldAtLine(line: Int): Boolean {
        val editable = text ?: return false
        val lines = editable.toString().lines()
        if (line < 0 || line >= lines.size) return false
        // If this line is already a collapsed start -> expand
        if (collapsedLineRanges.containsKey(line)) {
            val range = collapsedLineRanges.remove(line)
            foldStartLines.remove(line)
            invalidate()
            return true
        }
        val indent = indentOf(lines[line])
        var end = line
        var foundDeeper = false
        for (i in line + 1 until lines.size) {
            val nextIndent = indentOf(lines[i])
            if (lines[i].trim().isEmpty()) continue
            if (nextIndent <= indent) break
            end = i
            foundDeeper = true
            if (i == line + 1 && nextIndent <= indent) break
        }
        if (!foundDeeper || end <= line + 0) {
            return false
        }
        collapsedLineRanges[line] = (line + 1)..end
        foldStartLines.add(line)
        ensureCursorOutOfCollapse(line, end + 1)
        invalidate()
        return true
    }

    /**
     * Collapses the block under the cursor (if the current line can host a fold).
     */
    fun foldCurrentBlock() {
        val lyt = layout ?: return
        val line = lyt.getLineForOffset(selectionStart.coerceIn(0, text?.length ?: 0))
        toggleFoldAtLine(line)
    }

    /**
     * Expands a collapsed block whose start line is at/before [line].
     */
    fun expandAtLine(line: Int) {
        val key = collapsedLineRanges.entries.firstOrNull { line in it.value }?.key ?: return
        collapsedLineRanges.remove(key)
        foldStartLines.remove(key)
        invalidate()
    }

    fun expandAll() { clearFolding() }

    /**
     * Collapses every foldable block in the file (indent-based), best-effort.
     */
    fun foldAllBlocks() {
        val editable = text ?: return
        val lines = editable.toString().lines()
        if (lines.size < 2) return
        collapsedLineRanges.clear()
        foldStartLines.clear()
        for (i in 0 until lines.size - 1) {
            val indent = indentOf(lines[i])
            if (lines[i].trim().isEmpty()) continue
            var deeper = false
            for (j in i + 1 until lines.size) {
                if (lines[j].trim().isEmpty()) continue
                deeper = indentOf(lines[j]) > indent
                break
            }
            if (!deeper) continue
            var end = i
            for (j in i + 1 until lines.size) {
                if (lines[j].trim().isEmpty()) continue
                if (indentOf(lines[j]) <= indent) break
                end = j
            }
            if (end > i) {
                collapsedLineRanges[i] = (i + 1)..end
                foldStartLines.add(i)
            }
        }
        ensureCursorOutOfAllCollapses()
        invalidate()
    }

    private fun ensureCursorOutOfAllCollapses() {
        val lyt = layout ?: return
        val textLen = text?.length ?: 0
        val cursor = selectionStart.coerceIn(0, textLen)
        val cursorLine = lyt.getLineForOffset(cursor)
        collapsedLineRanges.values.forEach { range ->
            if (cursorLine in range.first..range.last) {
                val offset = lyt.getLineStart(range.last + 1).coerceAtMost(textLen)
                setSelection(offset.coerceAtMost(textLen))
            }
        }
    }

    /**
     * Returns the number of collapsed lines above the given 0-based visible
     * line for scroll mapping (unused externally for now).
     */
    fun collapsedLinesAbove(line: Int): Int {
        var total = 0
        collapsedLineRanges.values.forEach { range ->
            if (range.first <= line && range.last >= line) {
                total += range.last - range.first + 1
            } else if (range.last < line) {
                total += range.last - range.first + 1
            }
        }
        return total
    }

    private fun indentOf(line: String): Int {
        var n = 0
        for (ch in line) {
            when (ch) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    private fun ensureCursorOutOfCollapse(startLine: Int, endExclusive: Int) {
        val lyt = layout ?: return
        val textLen = text?.length ?: 0
        val cursor = selectionStart.coerceIn(0, textLen)
        val cursorLine = lyt.getLineForOffset(cursor)
        if (cursorLine in startLine until endExclusive) {
            val offset = lyt.getLineStart(endExclusive.coerceAtMost(lyt.lineCount - 1))
            setSelection(offset.coerceAtMost(textLen))
        }
    }

    // ?? Undo / Redo ???????????????????????????????????????????????????????
    private data class EditSnapshot(val text: String, val cursor: Int)
    private val undoStack = LinkedList<EditSnapshot>()
    private val redoStack = LinkedList<EditSnapshot>()
    private var isUndoRedoAction = false
    private var lastSnapshotText = ""
    private val MAX_UNDO = 120
    private val MAX_UNDO_TEXT_BYTES = 300_000

    init {
        refreshColors()
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setHorizontallyScrolling(true)
        isHorizontalScrollBarEnabled = true

        addTextChangedListener(object : TextWatcher {
            private var before = ""
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                before = s?.toString() ?: ""
            }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUndoRedoAction) return
                val current = s?.toString() ?: return
                if (current == lastSnapshotText) return
                if (before.length > MAX_UNDO_TEXT_BYTES || current.length > MAX_UNDO_TEXT_BYTES) {
                    undoStack.clear()
                    redoStack.clear()
                    lastSnapshotText = current
                // Auto-complete and bracket matching
                val selStart = selectionStart.coerceAtLeast(0)
                if (current.length > before.length && selStart > 0) {
                    val lastChar = current[selStart - 1]
                    val closeChar = bracketPairs[lastChar]
                    if (closeChar != null && (selStart >= current.length || current[selStart] != closeChar)) {
                        s?.insert(selStart, closeChar.toString())
                        setSelection(selStart + 1)
                    }
                }
                // Simple autocomplete
                val delimiters = charArrayOf(' ', '(', ')', '{', '}', '[', ']', '.', ',', ':')
                val wordStart = current.lastIndexOfAny(delimiters)
                val wordEnd = selStart
                if (wordEnd > wordStart + 1) {
                    val word = current.substring(wordStart + 1, wordEnd)
                    val matches = autocompleteCandidates.filter { it.startsWith(word, ignoreCase = true) }
                    if (matches.size == 1 && matches[0].length > word.length && !current.substring(selStart).startsWith(matches[0].substring(word.length))) {
                        val rest = matches[0].substring(word.length)
                        s?.insert(selStart, rest)
                        setSelection(selStart + rest.length)
                    }
                }
                    return
                }
                // Push snapshot
                if (undoStack.size >= MAX_UNDO) undoStack.pollFirst()
                undoStack.push(EditSnapshot(before, selectionStart.coerceAtLeast(0)))
                redoStack.clear()
                lastSnapshotText = current
            }
        })
    }

    fun undo() {
        val snapshot = undoStack.pollFirst() ?: return
        redoStack.push(EditSnapshot(text?.toString() ?: "", selectionStart.coerceAtLeast(0)))
        isUndoRedoAction = true
        setText(snapshot.text)
        setSelection(snapshot.cursor.coerceIn(0, snapshot.text.length))
        lastSnapshotText = snapshot.text
        isUndoRedoAction = false
    }

    fun redo() {
        val snapshot = redoStack.pollFirst() ?: return
        undoStack.push(EditSnapshot(text?.toString() ?: "", selectionStart.coerceAtLeast(0)))
        isUndoRedoAction = true
        setText(snapshot.text)
        setSelection(snapshot.cursor.coerceIn(0, snapshot.text.length))
        lastSnapshotText = snapshot.text
        isUndoRedoAction = false
    }

    fun clearHistory() { undoStack.clear(); redoStack.clear() }

    /** Find / Replace dialog for the current editor content. */
    fun showFindReplaceDialog() {
        val ctx = context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val findInput = EditText(ctx).apply {
            hint = "Find"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val replaceInput = EditText(ctx).apply {
            hint = "Replace"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(ctx).apply {
                text = "Find / Replace"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })
            addView(findInput)
            addView(replaceInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Find / Replace")
            .setView(layout)
            .setPositiveButton("Replace All") { _, _ ->
                val findText = findInput.text.toString()
                val replaceText = replaceInput.text.toString()
                if (findText.isNotBlank()) {
                    val text = this.text.toString()
                    val newText = text.replace(findText, replaceText)
                    this.setText(newText)
                    Toast.makeText(ctx, "Replaced all occurrences", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun setBracketMatch(start: Int, end: Int) {
        if (bracketStart == start && bracketEnd == end) return
        bracketStart = start
        bracketEnd = end
        invalidate()
    }

    fun duplicateCurrentLine() {
        val editable = text ?: return
        val (start, end) = currentLineBounds()
        val line = editable.substring(start, end)
        editable.insert(end, "\n$line")
        setSelection((end + 1 + line.length).coerceAtMost(editable.length))
    }

    fun deleteCurrentLine() {
        val editable = text ?: return
        val (start, end) = currentLineBounds(includeLineBreak = true)
        editable.delete(start, end.coerceAtMost(editable.length))
        setSelection(start.coerceAtMost(editable.length))
    }

    fun moveCurrentLineUp() {
        val editable = text ?: return
        val (start, end) = currentLineBounds(includeLineBreak = true)
        if (start == 0) return
        val source = editable.toString()
        val relativeCursor = (selectionStart - start).coerceAtLeast(0)
        val previousStart = source.lastIndexOf('\n', start - 2).let { if (it < 0) 0 else it + 1 }
        val previous = editable.substring(previousStart, start)
        val current = editable.substring(start, end)
        editable.replace(previousStart, end, current + previous)
        setSelection((previousStart + relativeCursor).coerceAtMost(editable.length))
    }

    fun moveCurrentLineDown() {
        val editable = text ?: return
        val (start, end) = currentLineBounds(includeLineBreak = true)
        if (end >= editable.length) return
        val source = editable.toString()
        val relativeCursor = (selectionStart - start).coerceAtLeast(0)
        val nextEnd = source.indexOf('\n', end).let { if (it < 0) editable.length else it + 1 }
        val current = editable.substring(start, end)
        val next = editable.substring(end, nextEnd)
        editable.replace(start, nextEnd, next + current)
        setSelection((start + next.length + relativeCursor).coerceAtMost(editable.length))
    }

    fun toggleLineComment(prefix: String = "// ") {
        val editable = text ?: return
        val (start, end) = currentLineBounds()
        val line = editable.substring(start, end)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val content = line.drop(indent.length)
        val replacement = if (prefix.startsWith("<!--")) {
            if (content.startsWith("<!--") && content.endsWith("-->")) {
                indent + content.removePrefix("<!--").removeSuffix("-->").trim()
            } else {
                "$indent<!-- $content -->"
            }
        } else {
            if (content.startsWith(prefix)) indent + content.removePrefix(prefix) else indent + prefix + content
        }
        editable.replace(start, end, replacement)
        setSelection((start + replacement.length).coerceAtMost(editable.length))
    }

    private fun currentLineBounds(includeLineBreak: Boolean = false): Pair<Int, Int> {
        val editable = text ?: return 0 to 0
        val source = editable.toString()
        val cursor = selectionStart.coerceIn(0, source.length)
        val start = source.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (it < 0) 0 else it + 1
        }
        val rawEnd = source.indexOf('\n', cursor).let {
            if (it < 0) source.length else it
        }
        val end = if (includeLineBreak && rawEnd < source.length) rawEnd + 1 else rawEnd
        return start to end
    }

    // ?? Gutter drawing ????????????????????????????????????????????????????
    fun highlightLine(lineNum: Int) {
        highlightedLine = lineNum
        val text = text ?: return
        val layout = layout ?: return
        if (lineNum <= 0 || lineNum > layout.lineCount) return
        
        val start = layout.getLineStart(lineNum - 1)
        val end = layout.getLineEnd(lineNum - 1)
        
        val blue = StudioUi.palette.accent
        text.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(android.text.style.ForegroundColorSpan(blue), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        postInvalidate()
    }

    fun clearHighlight() {
        if (highlightedLine == -1) return
        highlightedLine = -1
        val text = text ?: return
        val styleSpans = text.getSpans(0, text.length, android.text.style.StyleSpan::class.java)
        for (span in styleSpans) {
            if (span.style == Typeface.BOLD) text.removeSpan(span)
        }
        val colorSpans = text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java)
        val blue = StudioUi.palette.accent
        for (span in colorSpans) {
            if (span.foregroundColor == blue) text.removeSpan(span)
        }
        postInvalidate()
    }

    private fun updateGutter() {
        val newCount = lineCount.coerceAtLeast(1)
        if (newCount == lastLineCount) return
        lastLineCount = newCount
        numPaint.textSize = textSize * 0.85f
        val digits = newCount.toString().length.coerceAtLeast(3)
        // ~2 extra characters of room so line numbers are never clipped at the screen edge
        val baseWidth = (numPaint.measureText("0".repeat(digits)) + 16).toInt() + (24 * resources.displayMetrics.density).toInt()
        // Dynamic gutter width: smaller on narrow screens
        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        minGutterWidth = if (screenWidthDp < 400) 56 else 66
        val newWidth = baseWidth.coerceAtLeast(minGutterWidth)
        if (gutterWidth != newWidth) {
            gutterWidth = newWidth
            setPadding(gutterWidth, paddingTop, paddingRight, paddingBottom)
        }
    }

    override fun onDraw(canvas: Canvas) {
        refreshColors()
        updateGutter()
        val lyt = layout ?: run { super.onDraw(canvas); return }

        // Current line highlight
        val cursorLine = lyt.getLineForOffset(selectionStart.coerceIn(0, text?.length ?: 0))
        val lineTop    = lyt.getLineTop(cursorLine).toFloat() + paddingTop
        val lineBot    = lyt.getLineBottom(cursorLine).toFloat() + paddingTop
        canvas.drawRect(scrollX.toFloat(), lineTop,
            (scrollX + width).toFloat(), lineBot, currentLinePaint)

        if (highlightedLine != -1 && highlightedLine <= lyt.lineCount) {
            val navTop = lyt.getLineTop(highlightedLine - 1).toFloat() + paddingTop
            val navBot = lyt.getLineBottom(highlightedLine - 1).toFloat() + paddingTop
            canvas.drawRect(scrollX.toFloat(), navTop, (scrollX + width).toFloat(), navBot, navHighlightPaint)
        }

        // Gutter background
        canvas.drawRect(scrollX.toFloat(), scrollY.toFloat(),
            (scrollX + gutterWidth).toFloat(), (scrollY + height).toFloat(), gutterPaint)

        // Gutter border
        canvas.drawLine((scrollX + gutterWidth).toFloat(), scrollY.toFloat(),
            (scrollX + gutterWidth).toFloat(), (scrollY + height).toFloat(), gutterBorderPaint)

        drawBracketHighlight(canvas, lyt)
        drawIndentGuides(canvas, lyt, cursorLine)

        // Line numbers
        val firstLine = lyt.getLineForVertical(scrollY)
        val lastLine  = lyt.getLineForVertical(scrollY + height)
        numPaint.textSize = textSize * 0.85f
        for (i in firstLine..lastLine) {
            val baseline = lyt.getLineBaseline(i).toFloat() + paddingTop
            val lineNum  = (i + 1).toString()
            // Right-align inside the gutter with a small margin; clamp to >= 6dp so
            // wide numbers are never clipped at the left screen edge
            val x = ((scrollX + gutterWidth) - numPaint.measureText(lineNum) - (8 * resources.displayMetrics.density))
                .coerceAtLeast(scrollX + (6 * resources.displayMetrics.density))
            numPaint.color = if (i == cursorLine) StudioUi.palette.textSoft else StudioUi.palette.gutterText
            canvas.drawText(lineNum, x, baseline, numPaint)
        }

        // Draw fold arrows in the gutter (visible for every expandable block start)
        drawFoldMarkers(canvas, lyt, firstLine, lastLine)

        drawCodeLenses(canvas, lyt, firstLine, lastLine)
        drawInlayHints(canvas, lyt, firstLine, lastLine)

        super.onDraw(canvas)

        // Draw ghost text (inline AI completion)
        drawGhostText(canvas, lyt)

        // Overlay collapsed regions so hidden lines appear as a single bar
        if (collapsedLineRanges.isNotEmpty()) {
            drawCollapsedOverlays(canvas, lyt, firstLine, lastLine)
        }
    }

    private fun drawFoldMarkers(canvas: Canvas, lyt: android.text.Layout, firstLine: Int, lastLine: Int) {
        if (text.isNullOrEmpty()) return
        val source = text.toString()
        val lines = source.lines()
        if (lines.size < 2) return
        // Foldable = every line that is followed by a deeper-indented content line
        for (i in firstLine.coerceAtLeast(0)..lastLine.coerceAtMost(lines.size - 1)) {
            val currentIndent = indentOf(lines[i])
            var deeper = false
            for (j in i + 1 until lines.size) {
                if (lines[j].trim().isEmpty()) continue
                if (indentOf(lines[j]) > indentOf(lines[i]) && lines[j].trim().isNotEmpty()) {
                    deeper = true
                }
                break
            }
            if (!deeper) continue
            val collapsed = collapsedLineRanges.containsKey(i)
            val baseline = lyt.getLineBaseline(i).toFloat() + paddingTop
            val markerPaint = if (collapsed) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = StudioUi.palette.synAnnotation
                    typeface = Typeface.MONOSPACE
                    textSize = textSize * 0.8f
                }
            } else {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = StudioUi.palette.gutterText
                    typeface = Typeface.MONOSPACE
                    textSize = textSize * 0.8f
                }
            }
            val mark = if (collapsed) "\u25B8" else "\u25BE"
            canvas.drawText(mark, scrollX + 6f, baseline, markerPaint)
        }
    }

    private fun drawCollapsedOverlays(canvas: Canvas, lyt: android.text.Layout, firstLine: Int, lastLine: Int) {
        val collapsedVisible = mutableListOf<Pair<Int, Int>>()
        collapsedLineRanges.values.forEach { range ->
            val clipStart = range.first.coerceAtLeast(firstLine)
            val clipEnd = range.last.coerceAtMost(lastLine)
            if (clipStart <= clipEnd) collapsedVisible.add(Pair(clipStart, clipEnd))
        }
        if (collapsedVisible.isEmpty()) return

        collapsedVisible.forEach { (start, end) ->
            val top = lyt.getLineTop(start).toFloat() + paddingTop
            val bottom = lyt.getLineBottom(end).toFloat() + paddingTop
            if (bottom <= top) return@forEach
            val barPaint = Paint().apply {
                color = StudioUi.palette.surface
                style = Paint.Style.FILL
            }
            // Cover the whole hidden block (text + gutter shimmer) so it reads as a bar
            canvas.drawRect(scrollX.toFloat(), top, (scrollX + width).toFloat(), bottom, barPaint)
            val foldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = StudioUi.palette.textSoft
                typeface = Typeface.MONOSPACE
                textSize = textSize * 0.8f
            }
            val collapsedCount = end - start + 1
            val label = "\u25B8  $collapsedCount lines \u2014 tap line ${start + 1} to expand"
            val textX = scrollX + gutterWidth + 8f
            val mid = (top + bottom) / 2f
            canvas.drawText(label, textX, mid + foldPaint.textSize / 3f, foldPaint)
        }
    }

    private fun drawIndentGuides(canvas: Canvas, lyt: android.text.Layout, cursorLine: Int) {
        val text = text?.toString().orEmpty()
        if (text.isEmpty()) return
        val charWidth = paint.measureText(" ").coerceAtLeast(1f)
        val tabWidth = charWidth * 4
        val firstVisible = lyt.getLineForVertical(scrollY)
        val lastVisible = lyt.getLineForVertical(scrollY + height)
        val indentChar = if (text.contains('\t')) '\t' else ' '
        val step = if (indentChar == '\t') 1 else 4
        // Draw guide lines for each visible line
        for (i in firstVisible..lastVisible) {
            val lineStart = lyt.getLineStart(i)
            val lineEnd = lyt.getLineEnd(i)
            if (lineStart >= text.length) continue
            val lineText = text.substring(lineStart, lineEnd.coerceAtMost(text.length))
            val indentLevel = lineText.takeWhile { it == ' ' || it == '\t' }.length
            if (indentLevel < step) continue
            val top = lyt.getLineTop(i).toFloat() + paddingTop
            val bottom = lyt.getLineBottom(i).toFloat() + paddingTop
            val guides = indentLevel / step
            for (g in 1..guides) {
                val x = totalPaddingLeft + (g * step * charWidth).toFloat() - charWidth / 2
                if (x > scrollX + gutterWidth && x < scrollX + width) {
                    canvas.drawLine(x, top, x, bottom, indentGuidePaint)
                }
            }
        }
    }

    private fun drawBracketHighlight(canvas: Canvas, lyt: android.text.Layout) {
        val sourceLength = text?.length ?: 0
        val charWidth = paint.measureText("M").coerceAtLeast(4f)
        listOf(bracketStart, bracketEnd).forEach { offset ->
            if (offset !in 0 until sourceLength) return@forEach
            val line = lyt.getLineForOffset(offset)
            val x = totalPaddingLeft + lyt.getPrimaryHorizontal(offset)
            val top = lyt.getLineTop(line).toFloat() + paddingTop
            val bottom = lyt.getLineBottom(line).toFloat() + paddingTop
            canvas.drawRect(x, top, x + charWidth, bottom, bracketPaint)
        }
    }

    private fun drawCodeLenses(canvas: Canvas, lyt: android.text.Layout, first: Int, last: Int) {
        if (codeLenses.isEmpty()) return
        codeLenses.forEach { lens ->
            if (lens.line in first..last && lens.line < lyt.lineCount) {
                val lineBottom = lyt.getLineBottom(lens.line).toFloat() + paddingTop
                val textWidth = lensPaint.measureText(lens.text)
                val x = (width - totalPaddingRight - textWidth - 8f).coerceAtLeast(totalPaddingLeft + 4f)
                canvas.drawText(lens.text, x, lineBottom - 3f, lensPaint)
            }
        }
    }

    private fun drawInlayHints(canvas: Canvas, lyt: android.text.Layout, first: Int, last: Int) {
        if (inlayHints.isEmpty()) return
        inlayHints.forEach { hint ->
            val offset = hint.offset.coerceIn(0, text?.length ?: 0)
            val line = lyt.getLineForOffset(offset)
            if (line in first..last) {
                val x = lyt.getPrimaryHorizontal(offset) + totalPaddingLeft
                val baseline = lyt.getLineBaseline(line).toFloat() + paddingTop
                val top = baseline + hintPaint.fontMetrics.ascent
                val bottom = baseline + hintPaint.fontMetrics.descent
                
                val textWidth = hintTextPaint.measureText(hint.text)
                val rectLeft = x
                val rectRight = x + textWidth + 12f
                
                // Draw rounded background for hint (bubble style like AS)
                val rect = android.graphics.RectF(rectLeft, top + 3f, rectRight, bottom - 1f)
                hintPaint.color = StudioUi.palette.surface2
                canvas.drawRoundRect(rect, 4.dp().toFloat(), 4.dp().toFloat(), hintPaint)
                
                canvas.drawText(hint.text, rectLeft + 6f, baseline - 1f, hintTextPaint)
            }
        }
    }
    
    private fun drawGhostText(canvas: Canvas, lyt: android.text.Layout) {
        if (ghostText == null || ghostTextOffset < 0) return
        
        val text = this.text ?: return
        val offset = ghostTextOffset.coerceIn(0, text.length)
        val line = lyt.getLineForOffset(offset)
        val lineTop = lyt.getLineTop(line).toFloat() + paddingTop
        val lineBaseline = lyt.getLineBaseline(line).toFloat() + paddingTop
        val lineLeft = lyt.getPrimaryHorizontal(offset).toFloat() + scrollX + gutterWidth
        
        val density = resources.displayMetrics.density
        val charWidth = ghostTextPaint.measureText(" ")
        val xStart = lineLeft
        
        // Draw ghost text background
        val textWidth = ghostTextPaint.measureText(ghostText!!)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = StudioUi.palette.surface2
            style = Paint.Style.FILL
        }
        val padding = (4 * density).toInt()
        val rect = android.graphics.RectF(
            xStart - padding, 
            lineTop + padding, 
            xStart + textWidth + padding, 
            lineTop + (ghostTextPaint.descent() - ghostTextPaint.ascent()) + padding
        )
        canvas.drawRoundRect(rect, 4f * density, 4f * density, bgPaint)
        
        // Draw ghost text
        canvas.drawText(ghostText!!, xStart, lineBaseline, ghostTextPaint)
    }

    private fun Int.dp(): Int = (this * context.resources.displayMetrics.density).toInt()

    /** Tints the IME caret (and linked drawable) to the accent color across API levels. */
    private fun applyCaretColor(color: Int) {
        runCatching {
            val editorField = TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }
            val editor = editorField.get(this)
            val editorClass = Class.forName("android.widget.Editor")
            val drawableField = editorClass.getDeclaredField("mCursorDrawable").apply { isAccessible = true }
            val drawables = drawableField.get(editor) as Array<Drawable>
            for (d in drawables) d.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }
}
