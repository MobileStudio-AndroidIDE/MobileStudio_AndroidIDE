package com.aide.mobilestudio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class ComposePreviewActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_FILE = "extra_file_path"

        /** How far a preview that delegates to another composable may be followed. */
        private const val MAX_DELEGATION_DEPTH = 4

        fun start(context: Context, file: File) { context.startActivity(Intent(context, ComposePreviewActivity::class.java).putExtra(EXTRA_FILE, file.absolutePath)) }
    }

    private var sourceFile: File? = null
    private var previews = mutableListOf<PreviewFunc>()

    data class PreviewFunc(val name: String, val startLine: Int, val endLine: Int, val widthDp: Int = 320, val heightDp: Int = 640)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE)
        val file = filePath?.let { File(it) }
        if (file == null || !file.exists()) { finish(); return }
        sourceFile = file
        setContentView(buildLayout())
        parsePreviews()
        renderPreviews()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(StudioUi.color(StudioUi.Surface)) }
        root.addView(LinearLayout(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.Surface)); setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(this@ComposePreviewActivity).apply { setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } })
            addView(TextView(this@ComposePreviewActivity).apply { text = "Compose Preview - ${sourceFile?.name ?: ""}"; setTextColor(Color.WHITE); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), 0, 0, 0) })
        })
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f); setBackgroundColor(StudioUi.color(StudioUi.Ink)) }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); tag = "preview_container" }
        scroll.addView(container); root.addView(scroll)
        root.addView(TextView(this).apply { text = "Detecting @Preview functions..."; setTextColor(StudioUi.color(StudioUi.TextSoft)); textSize = 11f; setPadding(dp(12), dp(6), dp(12), dp(6)); setBackgroundColor(StudioUi.color(StudioUi.Surface)); tag = "status_bar" })
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun parsePreviews() {
        previews.clear()
        val source = runCatching { sourceFile?.readText() }.getOrNull().orEmpty() ?: ""
        val pat = java.util.regex.Pattern.compile("""@Preview\s*\(([^)]*)\)\s*\n\s*(?:private\s+)?fun\s+(\w+)""")
        val m = pat.matcher(source)
        while (m.find()) {
            val name = m.group(2) ?: continue
            val startLine = source.substring(0, m.start()).count { it == '\n' } + 1
            previews.add(PreviewFunc(name = name, startLine = startLine, endLine = startLine + 20))
        }
        val pat2 = java.util.regex.Pattern.compile("""@Preview\s*\n\s*(?:private\s+)?fun\s+(\w+)""")
        val m2 = pat2.matcher(source)
        while (m2.find()) {
            val name = m2.group(1) ?: continue
            if (previews.any { it.name == name }) continue
            val startLine = source.substring(0, m2.start()).count { it == '\n' } + 1
            previews.add(PreviewFunc(name = name, startLine = startLine, endLine = startLine + 20))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderPreviews() {
        val container = findViewWithTagHelper("preview_container") as? LinearLayout ?: return
        val status = findViewWithTagHelper("status_bar") as? TextView
        container.removeAllViews()
        if (previews.isEmpty()) {
            container.addView(TextView(this).apply { text = "No @Preview functions found.\n\nAdd @Preview annotation to a Composable:\n\n@Composable\n@Preview\nfun MyPreview() {\n    Text(\"Hello\")\n}"; setTextColor(StudioUi.color(StudioUi.TextSoft)); textSize = 13f; setPadding(dp(8), dp(16), dp(8), dp(16)) })
            status?.text = "No @Preview functions found"; return
        }
        status?.text = "Found ${previews.size} @Preview function(s)"
        val source = runCatching { sourceFile?.readText() }.getOrNull().orEmpty()
        val renderer = ComposeStructuralRenderer(this)
        previews.forEach { preview ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(StudioUi.color(StudioUi.Surface)); cornerRadius = dp(12f); setStroke(1, StudioUi.color(StudioUi.Border)) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.setMargins(0, 0, 0, dp(12)) }
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(8))
                addView(TextView(this@ComposePreviewActivity).apply { text = preview.name; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD })
                addView(Space(this@ComposePreviewActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                addView(TextView(this@ComposePreviewActivity).apply { text = "Line ${preview.startLine}"; setTextColor(StudioUi.color(StudioUi.TextSoft)); textSize = 10f })
            })
            val phoneFrame = FrameLayout(this).apply {
                val w = (preview.widthDp * resources.displayMetrics.density).toInt()
                val h = (preview.heightDp * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(w.coerceAtMost(resources.displayMetrics.widthPixels - dp(48)), h.coerceAtMost((resources.displayMetrics.heightPixels * 0.6).toInt())).also { it.gravity = Gravity.CENTER_HORIZONTAL }
                background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16f) }
                elevation = dp(4f)
                clipToOutline = true
            }
            when (val result = renderComposable(source, preview.name)) {
                is ComposeStructuralParser.Result.Ok -> {
                    val content = renderer.render(result.root)
                    content.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    phoneFrame.addView(content)
                    if (result.truncated) {
                        phoneFrame.addView(simulationNote("Preview is too large to draw completely; showing the top of the tree."))
                    }
                }
                is ComposeStructuralParser.Result.Failed -> {
                    phoneFrame.addView(simulationNote("Could not parse ${preview.name}:\n${result.error.message} (line ${result.error.line})"))
                }
            }
            card.addView(phoneFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.gravity = Gravity.CENTER_HORIZONTAL })
            val lines = source.lines(); val start = (preview.startLine - 1).coerceAtLeast(0); val end = preview.endLine.coerceAtMost(lines.size)
            card.addView(TextView(this).apply { text = lines.subList(start, end).joinToString("\n"); setTextColor(StudioUi.color(StudioUi.Text)); typeface = Typeface.MONOSPACE; textSize = 10f; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(StudioUi.color(StudioUi.Ink)) })
            container.addView(card)
        }
    }

    /**
     * Parses a preview function, following a body that only delegates to
     * another composable (`fun Preview() { App() }`) so the real screen is
     * drawn rather than the one-line wrapper.
     */
    private fun renderComposable(source: String, name: String, depth: Int = 0): ComposeStructuralParser.Result {
        val body = ComposeStructuralParser.functionBody(source, name)
            ?: return ComposeStructuralParser.Result.Failed(
                ComposeStructuralParser.ParseError("could not read the body of $name", 0),
            )
        val result = ComposeStructuralParser.parseBody(body)
        if (result is ComposeStructuralParser.Result.Ok && depth < MAX_DELEGATION_DEPTH) {
            val delegated = result.root
            val callee = delegated.label
            if (delegated.kind == ComposeStructuralParser.Kind.PLACEHOLDER && !callee.isNullOrEmpty()) {
                return renderComposable(source, callee, depth + 1)
            }
        }
        return result
    }

    /**
     * Honest note shown in place of anything the renderer could not simulate,
     * so a partially drawn preview is never mistaken for a complete one.
     */
    private fun simulationNote(text: String): View =
        TextView(this).apply {
            this.text = text
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun findViewWithTagHelper(tag: String): View? {
        val contentView = findViewById<View>(android.R.id.content) ?: return null
        return contentView.findViewWithTag(tag)
    }
}
