package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Android Studio-style Structure / Outline browser.
 *
 * Parses the current editor text for class/function/property/interface
 * declarations and shows them in a searchable list. Clicking an entry
 * jumps the editor to that line.
 *
 * Usage:
 *   SymbolOutline.show(context, text, onJump = { line -> ... })
 */
object SymbolOutline {

    /** A single symbol entry found in the source text. */
    data class Symbol(
        val kind: Kind,
        val name: String,
        val line: Int,        // 1-based line
        val column: Int,      // 1-based column
        val indent: Int       // for nesting display
    )

    enum class Kind(val label: String, val color: String, val icon: String) {
        CLASS("Class", "#E5C07B", "\u25A0"),
        INTERFACE("Interface", "#61AFEF", "\u25A3"),
        ENUM("Enum", "#C678DD", "\u25A8"),
        OBJECT("Object", "#98C379", "\u25A1"),
        FUNCTION("Function", "#56B6C2", "\u0192"),
        PROPERTY("Property", "#C3CCD8", "\u2022"),
        XML_TAG("Tag", "#E06C75", "<>"),
        YAML_KEY("YAML", "#C3CCD8", ":"),
        MARKDOWN_HEADING("Heading", "#E5C07B", "#"),
        JSON_KEY("JSON", "#C3CCD8", "{")
    }

    private val KOTLIN_FUN = Regex(
        """^\s*(?:public|private|internal|protected)?\s*(?:suspend\s+|inline\s+|tailrec\s+|noinline\s+|crossinline\s+|external\s+|infix\s+|operator\s+|override\s+|open\s+|final\s+)*fun\s+([A-Za-z_$][\w$]*)\s*\("""
    )
    private val KOTLIN_CLASS = Regex(
        """^\s*(?:public|private|internal|protected)?\s*(?:data\s+|sealed\s+|abstract\s+|open\s+|final\s+|enum\s+)*(?:class|interface|object|enum class)\s+([A-Za-z_$][\w$]*)\s*(?:<|\{|:)""".replace("(?:class|interface|object|enum class)", "(?:(?:enum\\s+)?class|interface|object)")
    )
    private val KOTLIN_VAL = Regex(
        """^\s*(?:public|private|internal|protected)?\s*(?:override\s+|lateinit\s+|val|var)\s+([A-Za-z_$][\w$]*)\s*(?::|=)"""
    )
    private val KOTLIN_VAL_SIMPLE = Regex(
        """^\s*(?:val|var)\s+([A-Za-z_$][\w$]*)\s*(?::|=)"""
    )
    private val JAVA_CLASS = Regex(
        """^\s*(?:public|private|protected)?\s*(?:abstract\s+|final\s+|static\s+)*(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)"""
    )
    private val JAVA_METHOD = Regex(
        """^\s*(?:public|private|protected)?\s*(?:static\s+|final\s+|abstract\s+|synchronized\s+)*(?:[\w<>,.?\[\] ]+\s+)?([a-zA-Z_$][\w$]*)\s*\([^;{}]*\)\s*(?:\{|throws)"""
    )
    private val JAVA_FIELD = Regex(
        """^\s*(?:public|private|protected)?\s*(?:static\s+|final\s+)*(?:[\w<>,.\[\]?]+\s+)([a-zA-Z_$][\w$]*)\s*(?:=|;)"""
    )

    /**
     * Parses [text] and returns a flat list of symbols ordered by line,
     * best-effort across Kotlin / Java / XML / Markdown / JSON / YAML.
     */
    fun parse(text: String, fileName: String? = null): List<Symbol> {
        val out = mutableListOf<Symbol>()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        text.lines().forEachIndexed { idx, raw ->
            val lineNo = idx + 1
            val indent = raw.takeWhile { it == ' ' || it == '\t' }.length
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed

            val sym: Symbol? = when (ext) {
                "kt", "kts" -> parseKotlinLine(line, lineNo, indent)
                "java" -> parseJavaLine(line, lineNo, indent)
                "xml" -> parseXmlLine(line, lineNo, indent)
                "md" -> parseMarkdownLine(line, lineNo, indent)
                "json" -> parseJsonLine(line, lineNo, indent)
                else -> parseKotlinLine(line, lineNo, indent)
                    ?: parseJavaLine(line, lineNo, indent)
                    ?: parseXmlLine(line, lineNo, indent)
            }
            sym?.let { out += it }
        }
        return out
    }

    private fun parseKotlinLine(line: String, lineNo: Int, indent: Int): Symbol? {
        // Functions (incl. override fun, suspend fun)
        KOTLIN_FUN.find(line)?.let {
            val name = it.groupValues[1]
            if (looksLikeDeclarationName(name)) return Symbol(Kind.FUNCTION, name, lineNo, (it.value.indexOf(name) + 1).coerceAtLeast(1), indent)
        }
        // Classes / interfaces / objects / enums (multi-token regex)
        KOTLIN_CLASS.find(line)?.let {
            var name = it.groupValues[1]
            if (name.contains(" ")) {
                // Robust fallback: scan the declaration manually
                name = extractClassDeclName(line) ?: name
            }
            val kind = when {
                line.contains("enum class", ignoreCase = true) -> Kind.ENUM
                line.contains(" interface ", ignoreCase = true) || line.startsWith("interface ") -> Kind.INTERFACE
                line.contains(" object ", ignoreCase = true) || line.startsWith("object ") && !line.contains("companion", true) -> Kind.OBJECT
                else -> Kind.CLASS
            }
            val col = (it.value.indexOf(name) + 1).coerceAtLeast(1)
            return Symbol(kind, name, lineNo, col, indent)
        }
        // val/var: robust regex
        KOTLIN_VAL_SIMPLE.find(line)?.let { m ->
            // Ensure not already a function
            if (KOTLIN_FUN.containsMatchIn(line)) return@let
            val name = m.groupValues[1]
            return Symbol(Kind.PROPERTY, name, lineNo, (m.value.indexOf(name) + 1).coerceAtLeast(1), indent)
        }
        return null
    }

    private fun parseJavaLine(line: String, lineNo: Int, indent: Int): Symbol? {
        if (line.startsWith("import ")) return null
        JAVA_METHOD.find(line)?.let {
            var name = it.groupValues[1]
            if (name == "if" || name == "for" || name == "while" || name == "switch" || name == "catch" ||
                name == "synchronized" || name == "return" || name == "class" || name == "new") return@let
            // Might be a field followed by () — unlikely; skip pseudo names
            return Symbol(Kind.FUNCTION, name, lineNo, (it.value.indexOf(name) + 1).coerceAtLeast(1), indent)
        }
        JAVA_CLASS.find(line)?.let {
            val name = it.groupValues[1]
            val kind = when {
                line.contains("interface ", true) -> Kind.INTERFACE
                line.contains("enum ", true) -> Kind.ENUM
                else -> Kind.CLASS
            }
            return Symbol(kind, name, lineNo, (it.value.indexOf(name) + 1).coerceAtLeast(1), indent)
        }
        JAVA_FIELD.find(line)?.let {
            val name = it.groupValues[1]
            if (name == "if") return@let
            return Symbol(Kind.PROPERTY, name, lineNo, (it.value.indexOf(name) + 1).coerceAtLeast(1), indent)
        }
        return null
    }

    private fun parseXmlLine(line: String, lineNo: Int, indent: Int): Symbol? {
        // <tag> or </tag> or <tag ...>
        val tag = Regex("""<([A-Za-z_][\w.-]*)""").find(line) ?: return null
        val name = tag.groupValues[1]
        if (name == "?xml" || name.startsWith("/") || line.trim().startsWith("</")) return null
        return Symbol(Kind.XML_TAG, name, lineNo, (line.indexOf(name) + 1).coerceAtLeast(1), indent)
    }

    private fun parseMarkdownLine(line: String, lineNo: Int, indent: Int): Symbol? {
        val m = Regex("""^(#{1,6})\s+(.*)$""").find(line) ?: return null
        return Symbol(Kind.MARKDOWN_HEADING, m.groupValues[2], lineNo, (line.indexOf(m.groupValues[2]) + 1).coerceAtLeast(1), indent)
    }

    private fun parseJsonLine(line: String, lineNo: Int, indent: Int): Symbol? {
        val m = Regex(""""([^"]+)"\s*:""").find(line) ?: return null
        return Symbol(Kind.JSON_KEY, m.groupValues[1], lineNo, indent + 1, indent)
    }

    private fun looksLikeDeclarationName(name: String): Boolean =
        name.isNotEmpty() && (name.first().isLetter() || name.first() == '_')

    private fun extractClassDeclName(line: String): String? {
        val idx = line.indexOfFirst { it == '(' || it == '{' }
        val head = if (idx >= 0) line.substring(0, idx) else line
        val tokens = head.split(Regex("\\s+"))
        // class Name ... : (extends) — take token right after 'class'
        for (i in 0 until tokens.size - 1) {
            val t = tokens[i]
            if (t in setOf("class", "interface", "object", "enum", "data", "sealed", "open", "abstract")) {
                val next = tokens[i + 1]
                if (next.isNotEmpty() && next.first().isUpperCase()) return next
            }
        }
        return null
    }

    /**
     * Renders the structure browser as a modal dialog.
     */
    fun show(
        context: Context,
        text: String,
        fileName: String?,
        onJump: (Int) -> Unit
    ) {
        val symbols = parse(text, fileName)
        if (symbols.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle("Structure")
                .setMessage("No symbols found in this file.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
        }
        val search = EditText(context).apply {
            hint = "Filter symbols..."
            textSize = 13f
            setSingleLine(true)
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            background = StudioUi.inputBg(context)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val list = ScrollView(context).apply {
            // Fill remaining dialog space (max height)
            isVerticalScrollBarEnabled = true
        }
        val listBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        list.addView(listBox)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(context, 10)
        })

        fun renderAllVisible(query: String) {
            listBox.removeAllViews()
            val q = query.trim()
            var shown = 0
            symbols.forEach { sym ->                if (q.isNotEmpty() && !sym.name.contains(q, ignoreCase = true)) return@forEach
                shown++
                val row = TextView(context).apply {
                    this.text = buildString {
                        repeat(sym.indent / 2) { append("  ") }
                        append(sym.kind.icon)
                        append("  ")
                        append(sym.name)
                        append("   : ")
                        append(sym.line)
                    }
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(android.graphics.Color.parseColor(sym.kind.color))
                    setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                    background = StudioUi.rounded(context, fill = "#171C25", radiusDp = 6)
                    setOnClickListener {
                        onJump(sym.line)
                    }
                }
                listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(context, 3)
                })
            }
            if (shown == 0) {
                listBox.addView(TextView(context).apply {
                    this.text = AppLanguage.literal(context, if (q.isEmpty()) "No symbols" else "No matches")
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(context, 24), 0, dp(context, 24))
                })
            }
        }

        try { search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderAllVisible(s?.toString() ?: "") }
        }) } catch (_: Exception) {}
        renderAllVisible("")

        android.app.AlertDialog.Builder(context)
            .setTitle("Structure - $fileName")
            .setView(root)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}