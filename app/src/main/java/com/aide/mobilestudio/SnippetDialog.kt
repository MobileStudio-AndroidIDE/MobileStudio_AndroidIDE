package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Android Studio-style code snippet insertion panel.
 *
 * Shows a categorized list of code templates for the active file type.
 * Clicking a snippet inserts it at the editor cursor (a $-marker is the
 * post-insertion caret position; AUTOSEL is replaced by the modern selection).
 */
object SnippetDialog {

    private data class Snippet(val label: String, val detail: String, val body: String)

    private val kotlinSnippets: List<Snippet> = listOf(
        Snippet("class", "Kotlin class", "class \$Name {\n\n\$CURSOR\n}\n"),
        Snippet("data class", "Kotlin data class", "data class \$Name(\n    \$PROP: \$TYPE\n)\n"),
        Snippet("function", "Kotlin function", "fun \$Name(\$ARGS): \$RET {\n    \$CURSOR\n}\n"),
        Snippet("private fun", "private function", "private fun \$Name(\$ARGS) {\n    \$CURSOR\n}\n"),
        Snippet("override fun", "override function", "override fun \$Name() {\n    super.\$Name()\n    \$CURSOR\n}\n"),
        Snippet("val", "immutable property", "val \$NAME: \$TYPE = \$CURSOR\n"),
        Snippet("var", "mutable property", "var \$NAME: \$TYPE = \$CURSOR\n"),
        Snippet("if", "if statement", "if (\$COND) {\n    \$CURSOR\n}\n"),
        Snippet("if..else", "if/else", "if (\$COND) {\n    \$CURSOR\n} else {\n\n}\n"),
        Snippet("when", "when statement", "when (\$EXPR) {\n    \$COND -> {\n        \$CURSOR\n    }\n}\n"),
        Snippet("for", "for loop", "for (\$ITEM in \$ITEMS) {\n    \$CURSOR\n}\n"),
        Snippet("while", "while loop", "while (\$COND) {\n    \$CURSOR\n}\n"),
        Snippet("try/catch", "try/catch", "try {\n    \$CURSOR\n} catch (e: \$Exception) {\n    e.printStackTrace()\n}\n"),
        Snippet("lambda", "lambda expression", "{\n    \$ARGS ->\n    \$CURSOR\n}\n"),
        Snippet("Composable", "@Composable", "@Composable\nfun \$Name(\$ARGS) {\n    \$CURSOR\n}\n"),
        Snippet("remember { }", "Compose state", "val \$NAME = remember { \$CURSOR }\n"),
        Snippet("LazyColumn", "Compose list", "LazyColumn {\n    items(\$ITEMS) { \$ITEM ->\n        \$CURSOR\n    }\n}\n"),
        Snippet("runOnUiThread", "run on UI thread", "runOnUiThread {\n    \$CURSOR\n}\n"),
        Snippet("vararg build", "Gradle task", "tasks.register(\"\$NAME\") {\n    doLast {\n        \$CURSOR\n    }\n}\n")
    )

    private val javaSnippets: List<Snippet> = listOf(
        Snippet("class", "Java class", "public class \$Name {\n    \$CURSOR\n}\n"),
        Snippet("method", "Java method", "public \$RET \$Name(\$ARGS) {\n    \$CURSOR\n}\n"),
        Snippet("main", "main method", "public static void main(String[] args) {\n    \$CURSOR\n}\n"),
        Snippet("if", "if statement", "if (\$COND) {\n    \$CURSOR\n}\n"),
        Snippet("for", "for loop", "for (int \$i = 0; \$i < \$N; \$i++) {\n    \$CURSOR\n}\n"),
        Snippet("try/catch", "try/catch", "try {\n    \$CURSOR\n} catch (Exception e) {\n    e.printStackTrace();\n}\n"),
        Snippet("loop", "enhanced for", "for (\$TYPE \$ITEM : \$ITEMS) {\n    \$CURSOR\n}\n"),
        Snippet("new", "object creation", "new \$Class(\$ARGS);\n"),
        Snippet("sout", "print line", "System.out.println(\$CURSOR);\n")
    )

    private val androidXmlSnippets: List<Snippet> = listOf(
        Snippet("TextView", "text view", "<TextView\n    android:layout_width=\"\$wrap\"\n    android:layout_height=\"\$wrap\"\n    android:text=\"\$TEXT\" />\n"),
        Snippet("Button", "button", "<Button\n    android:layout_width=\"\$wrap\"\n    android:layout_height=\"\$wrap\"\n    android:text=\"\$TEXT\" />\n"),
        Snippet("LinearLayout", "vertical", "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\">\n\n    \$CURSOR\n</LinearLayout>\n"),
        Snippet("ImageView", "image", "<ImageView\n    android:layout_width=\"\$wrap\"\n    android:layout_height=\"\$wrap\"\n    android:src=\"@drawable/\$DRAWABLE\" />\n"),
        Snippet("RecyclerView", "recycler", "<androidx.recyclerview.widget.RecyclerView\n    android:id=\"@+id/\${viewId}\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\" />\n"),
        Snippet("ConstraintLayout", "constraint", "<androidx.constraintlayout.widget.ConstraintLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n    \$CURSOR\n</androidx.constraintlayout.widget.ConstraintLayout>\n")
    )

    private val markdownSnippets: List<Snippet> = listOf(
        Snippet("h1", "heading", "# \$CURSOR\n"),
        Snippet("code", "code block", "```\$LANG\n\$CURSOR\n```\n"),
        Snippet("link", "link", "[\$TEXT](\$URL)\n"),
        Snippet("bullet", "list", "- \$CURSOR\n"),
        Snippet("quote", "blockquote", "> \$CURSOR\n")
    )

    private val genericSnippets: List<Snippet> = listOf(
        Snippet("if", "if statement", "if (\$COND) {\n    \$CURSOR\n}\n"),
        Snippet("for", "for loop", "for (\$ITEM in \$ITEMS) {\n    \$CURSOR\n}\n"),
        Snippet("try", "try/catch", "try {\n    \$CURSOR\n} catch (e: Exception) {\n    e.printStackTrace()\n}\n")
    )

    /**
     * Collect the snippets for the given file extension.
     */
    fun snippetsForExtension(extension: String?): List<Pair<String, String>> {
        val list = when (extension?.lowercase()) {
            "kt", "kts" -> kotlinSnippets
            "java" -> javaSnippets
            "xml" -> androidXmlSnippets
            "md" -> markdownSnippets
            else -> genericSnippets
        }
        return list.map { it.label to it.body }
    }

    /**
     * Renders the snippet picker as a modal dialog.
     *
     * @param onInsert receives the raw snippet body; callers expand [] markers.
     */
    fun show(
        context: Context,
        extension: String?,
        onInsert: (String) -> Unit
    ) {
        val snippets = when (extension?.lowercase()) {
            "kt", "kts" -> kotlinSnippets
            "java" -> javaSnippets
            "xml" -> androidXmlSnippets
            "md" -> markdownSnippets
            else -> genericSnippets
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        }
        val search = EditText(context).apply {
            hint = "Filter snippets..."
            textSize = 13f
            setSingleLine(true)
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            background = StudioUi.inputBg(context)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(context, 10)
        })

        fun render(q: String) {
            box.removeAllViews()
            val filtered = snippets
                .filter { q.isBlank() || it.label.contains(q, true) || it.detail.contains(q, true) }
            if (filtered.isEmpty()) {
                box.addView(TextView(context).apply {
                    text = AppLanguage.literal(context, "No matches")
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(context, 24), 0, dp(context, 24))
                })
                return
            }
            filtered.forEach { snip ->
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                        background = StudioUi.rounded(context, fill = "#171C25", radiusDp = 6)
                        setOnClickListener { onInsert(snip.body) }
                    }
                    val title = TextView(context).apply {
                        text = snip.label
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                        setTextColor(StudioUi.color(StudioUi.ActiveBlue))
                    }
                    row.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val detail = TextView(context).apply {
                        text = snip.detail
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setTextColor(StudioUi.color(StudioUi.TextSoft))
                    }
                    row.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(context, 2)
                    })
                    box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(context, 4)
                    })
                }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString() ?: "") }
        })
        render("")

        android.app.AlertDialog.Builder(context)
            .setTitle("Insert Snippet")
            .setView(root)
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Expands a snippet body by replacing the [] markers with meaningful text.
     * Returns the text to insert and the caret offset (absolute) within it.
     * If a marker can't be auto-filled it is replaced with an editable placeholder
     * and the caret is placed at the first unresolved [] position.
     */
    fun expand(body: String): Pair<String, Int> {
        var caret = body.indexOf("\$CURSOR").let { if (it >= 0) it else body.indexOf("[]") }
        var text = body.replace("\$CURSOR", "")
        text = text.replace("\$Name", "MyClass")
        text = text.replace("\$PROP", "value")
        text = text.replace("\$TYPE", "String")
        text = text.replace("\$RET", "Unit")
        text = text.replace("\$ARGS", "")
        text = text.replace("\$COND", "true")
        text = text.replace("\$EXPR", "value")
        text = text.replace("\$ITEM", "item")
        text = text.replace("\$ITEMS", "items")
        text = text.replace("\$N", "10")
        text = text.replace("\$i", "i")
        text = text.replace("\$Exception", "Exception")
        text = text.replace("\$TEXT", "text")
        text = text.replace("\$URL", "https://example.com")
        text = text.replace("\$LANG", "kotlin")
        text = text.replace("\$DRAWABLE", "icon")
        text = text.replace("\$IMG", "image")
        text = text.replace("\$Class", "MyClass")
        text = text.replace("\$wrap", "wrap_content")
        text = text.replace("\${viewId}", "list")
        if (caret < 0 || caret > text.length) caret = text.length
        return text to caret
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}