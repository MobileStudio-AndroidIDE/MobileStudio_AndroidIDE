package com.aide.mobilestudio.web

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aide.mobilestudio.GradleSyncManager
import com.aide.mobilestudio.StudioUi
import kotlinx.coroutines.*
import java.io.File

/**
 * Editor autocomplete for HTML / CSS / JavaScript / TypeScript.
 *
 * Drop-in companion to [com.aide.mobilestudio.KotlinAutoComplete] with the same public shape
 * (constructor `(Context, EditText)`, `setProjectDir`, `setCurrentFile`, `setGradleSyncManager`,
 * `syncProject`, `dismiss`), driven entirely by the stateless [WebCompletionEngine]:
 *
 * ```
 * autoComplete = WebAutoComplete(this, codeEditor)   // MainActivity.kt:7602
 * ```
 *
 * The popup is context-driven: HTML tag state suggests tags, `attr=` state suggests values,
 * CSS blocks suggest properties/values, JS/TS suggests keywords/globals/snippets. When the
 * caret sits in a context with no suggestions (plain text, comments, strings) the popup
 * stays closed, so attaching it never disturbs non-web files (its language is set per file
 * via [setCurrentFile]).
 */
class WebAutoComplete(
    private val context: Context,
    private val editText: EditText
) {

    private var language: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var completeJob: Job? = null
    private var lastChangeWasDeletion = false

    private val popup = PopupWindow(context).apply {
        width = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isFocusable = false
        isOutsideTouchable = true
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        elevation = 12f * context.resources.displayMetrics.density
    }

    private val listView = ListView(context).apply {
        background = roundedBg(StudioUi.palette.surface, StudioUi.palette.borderStrong, 8)
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(StudioUi.palette.selectedBg)
    }

    init {
        popup.contentView = listView
        attachWatcher()
    }

    // ── public API (mirrors KotlinAutoComplete) ─────────────────────────

    /** Not needed for web completions; accepted for API compatibility. */
    fun setProjectDir(dir: File?) { /* no project indexing for web languages */ }

    /** Switches the completion language for the file being edited. */
    fun setCurrentFile(file: File) {
        language = WebCompletionEngine.languageForExtension(file.extension)
        dismiss()
    }

    /** No-op: web completions need no Gradle symbol index. */
    fun setGradleSyncManager(manager: GradleSyncManager?) { /* ignored */ }

    /** Instant no-op so MainActivity can call it unconditionally. */
    fun syncProject(dir: File?, onComplete: (Int, String) -> Unit = { _, _ -> }) {
        onComplete(0, "Web completions are built in; nothing to index")
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    /** Stops watching the editor and releases the popup. */
    fun detach() {
        scope.cancel()
        dismiss()
    }

    // ── editor hookup ───────────────────────────────────────────────────

    private fun attachWatcher() {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                lastChangeWasDeletion = count > after
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s == null || language == null) {
                    dismiss()
                    return
                }
                if (lastChangeWasDeletion) dismiss()
                val wasDeletion = lastChangeWasDeletion

                completeJob?.cancel()
                completeJob = scope.launch {
                    delay(120)
                    val cursor = editText.selectionStart.coerceAtLeast(0)
                    val text = s.toString()
                    if (cursor > text.length) { dismiss(); return@launch }
                    val before = text.substring(0, cursor)
                    val result = withContext(Dispatchers.Default) {
                        WebCompletionEngine.queryDetailed(language!!, before)
                    }
                    // After a deletion an empty member/attr prefix means "erasing", not
                    // "asking" — keep the popup closed in that case.
                    if (result.isEmpty || (wasDeletion && result.prefix.isEmpty())) {
                        dismiss()
                        return@launch
                    }
                    showPopup(result)
                }
            }
        })
    }

    private fun showPopup(result: WebQueryResult) {
        val suggestions = result.items.take(12)
        listView.adapter = SuggestionAdapter(suggestions)
        listView.setOnItemClickListener { _, _, pos, _ ->
            applySuggestion(suggestions[pos], result.prefix)
            dismiss()
        }

        val density = context.resources.displayMetrics.density
        popup.width = (320 * density).toInt()
        popup.height = (suggestions.size * 40 * density).toInt().coerceAtMost((240 * density).toInt())

        val layout = editText.layout
        val caretLine = layout?.getLineForOffset(editText.selectionStart) ?: 0
        val caretX = if (layout != null) {
            (layout.getPrimaryHorizontal(editText.selectionStart) + editText.paddingLeft + editText.scrollX).toInt()
        } else 0
        val caretBaseline = if (layout != null) layout.getLineBottom(caretLine) + editText.paddingTop else 0
        val loc = IntArray(2)
        editText.getLocationInWindow(loc)
        var x = loc[0] + caretX
        var y = loc[1] + caretBaseline + (2 * density).toInt()
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val margin = (4 * density).toInt()
        x = x.coerceIn(margin, (screenW - popup.width - margin).coerceAtLeast(margin))
        if (y + popup.height > screenH) {
            val caretTop = if (layout != null) layout.getLineTop(caretLine) else 0
            y = (loc[1] + caretTop - popup.height).coerceAtLeast(margin)
        }
        if (!popup.isShowing) {
            popup.showAtLocation(editText.rootView, Gravity.TOP or Gravity.START, x, y)
        } else {
            popup.update(x, y, popup.width, popup.height)
        }
    }

    private fun applySuggestion(item: WebCompletion, prefix: String) {
        val cursor = editText.selectionStart.coerceAtLeast(0)
        val start = (cursor - prefix.length).coerceAtLeast(0)
        if (start > cursor) return
        editText.text.replace(start, cursor, item.insert)
        val target = start + item.insert.length - item.cursorOffsetFromEnd
        editText.setSelection(target.coerceIn(0, editText.text.length))
    }

    private fun Int.dpToPx() = (this * context.resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            setStroke((1 * context.resources.displayMetrics.density).toInt(), stroke)
        }

    // ── rows ────────────────────────────────────────────────────────────

    private inner class SuggestionAdapter(val items: List<WebCompletion>) :
        ArrayAdapter<WebCompletion>(context, 0, items) {

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[pos]
            val (badge, color) = badgeFor(item.kind)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 10, 14, 10)
                setBackgroundColor(Color.TRANSPARENT)
            }
            val icon = TextView(context).apply {
                text = badge
                setTextColor(color)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                width = 24.dpToPx()
            }
            val label = TextView(context).apply {
                text = item.label
                setTextColor(StudioUi.palette.text)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }
            row.addView(icon)
            row.addView(label)
            if (item.detail.isNotEmpty()) {
                val detail = TextView(context).apply {
                    text = item.detail
                    setTextColor(StudioUi.palette.textSoft)
                    textSize = 10f
                    setPadding(8, 1, 0, 0)
                }
                row.addView(detail)
            }
            return row
        }

        private fun badgeFor(kind: WebCompletion.Kind): Pair<String, Int> = when (kind) {
            WebCompletion.Kind.TAG -> "t" to StudioUi.palette.synTag
            WebCompletion.Kind.ATTRIBUTE -> "a" to StudioUi.palette.synAttr
            WebCompletion.Kind.VALUE -> "v" to StudioUi.palette.synStr
            WebCompletion.Kind.SNIPPET -> "s" to StudioUi.palette.synAnnotation
            WebCompletion.Kind.FUNCTION -> "f" to StudioUi.palette.synFunc
            WebCompletion.Kind.GLOBAL -> "g" to StudioUi.palette.synType
            WebCompletion.Kind.TYPE -> "T" to StudioUi.palette.synType
            WebCompletion.Kind.KEYWORD -> "k" to StudioUi.palette.synKw
        }
    }
}
