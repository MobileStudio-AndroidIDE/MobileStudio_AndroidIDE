package com.aide.mobilestudio.analysis

import android.content.Context
import android.graphics.Color
import com.aide.mobilestudio.StudioUi
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aide.mobilestudio.GradleSyncManager
import java.io.File

data class QuickFix(
    val label: String,
    val description: String,
    val priority: Int = 0,
    val action: (Context, File, Int, Int) -> Unit
)

data class GoToDefinitionResult(
    val file: File,
    val line: Int,
    val column: Int,
    val preview: String,
    val symbol: String
)

object QuickFixProvider {

    private val rules = mutableListOf<FixRule>()
    private var gradleSyncManager: GradleSyncManager? = null

    init {
        registerBaseRules()
    }

    fun setGradleSyncManager(manager: GradleSyncManager) {
        gradleSyncManager = manager
    }

    private fun interface FixRule {
        fun match(diagnostic: Diagnostic): List<QuickFix>?
    }

    private fun registerBaseRules() {
        // Unresolved reference rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.UNRESOLVED_REFERENCE) return@FixRule null
            val name = extractName(diag.message, listOf(
                Regex("""Unresolved reference:\s*(\w+)"""),
                Regex("""Unresolved reference\s*'(\w+)'"""),
                Regex("""Cannot find name\s*'(\w+)'""")
            ))
            if (name.isEmpty()) return@FixRule null
            listOf(
                QuickFix("Create function '$name'", "Generate a stub function", 10) { ctx, file, line, _ -> createStubFunction(ctx, file, line, name) },
                QuickFix("Create variable '$name'", "Generate a val/var declaration", 9) { ctx, file, line, _ -> createStubVariable(ctx, file, line, name) },
                QuickFix("Import '$name'", "Try to find and add a matching import", 8) { ctx, file, _, _ -> suggestImport(ctx, file, name) },
                QuickFix("Rename to similar", "Search for similar names in scope", 5) { ctx, file, line, _ -> suggestSimilarName(ctx, file, line, name) }
            )
        })

        // Type mismatch rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.TYPE_MISMATCH) return@FixRule null
            val expected = extractTypeWord(diag.message, "expected")
            val actual = extractTypeWord(diag.message, "actual")
            val fixes = mutableListOf<QuickFix>()
            if (expected.isNotEmpty()) {
                fixes.add(QuickFix("Cast to $expected", "Add explicit type cast", 10) { ctx, file, line, col -> addTypeCast(ctx, file, line, expected) })
            }
            if (actual.isNotEmpty() && expected.isNotEmpty()) {
                fixes.add(QuickFix("Convert $actual to $expected", "Add conversion call", 9) { ctx, file, line, col -> addConversion(ctx, file, line, actual, expected) })
            }
            fixes.ifEmpty { null }
        })

        // Nullability error rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.NULLABILITY_ERROR) return@FixRule null
            val msg = diag.message.lowercase()
            val fixes = mutableListOf<QuickFix>()
            if (msg.contains("null")) {
                fixes.add(QuickFix("Add !! (non-null assertion)", "Assert value is not null", 10) { ctx, file, line, col -> addNonNullAssert(ctx, file, line) })
                fixes.add(QuickFix("Add ?. (safe call)", "Convert to safe call", 9) { ctx, file, line, col -> addSafeCall(ctx, file, line) })
                fixes.add(QuickFix("Add ?: return (early return)", "Return if null", 8) { ctx, file, line, col -> addEarlyReturn(ctx, file, line) })
                fixes.add(QuickFix("Wrap in let { }", "Use let block for non-null scope", 7) { ctx, file, line, col -> wrapInLet(ctx, file, line) })
            }
            fixes.ifEmpty { null }
        })

        // Import error rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.IMPORT_ERROR) return@FixRule null
            val name = extractName(diag.message, listOf(Regex("""'(\w+)'"""), Regex("""import\s+(\w+)""")))
            if (name.isEmpty()) return@FixRule null
            listOf(
                QuickFix("Remove import '$name'", "Delete the unused import", 10) { ctx, file, line, _ -> removeLine(ctx, file, line) },
                QuickFix("Replace with correct import", "Search for correct import path", 8) { ctx, file, line, _ -> suggestImport(ctx, file, name) }
            )
        })

        // Syntax error rules - brace matching
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.SYNTAX_ERROR) return@FixRule null
            val msg = diag.message.lowercase()
            val fixes = mutableListOf<QuickFix>()
            when {
                msg.contains("expecting") && msg.contains("{") -> fixes.add(QuickFix("Insert '{'", "Add missing opening brace", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, "{") })
                msg.contains("expecting") && msg.contains("}") -> fixes.add(QuickFix("Insert '}'", "Add missing closing brace", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, "}") })
                msg.contains("expecting") && msg.contains(";") -> fixes.add(QuickFix("Insert ';'", "Add missing semicolon", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, ";") })
                msg.contains("expecting") && msg.contains("(") -> fixes.add(QuickFix("Insert '('", "Add missing opening parenthesis", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, "(") })
                msg.contains("expecting") && msg.contains(")") -> fixes.add(QuickFix("Insert ')'", "Add missing closing parenthesis", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, ")") })
                msg.contains("unexpected") && msg.contains("eof") -> fixes.add(QuickFix("Insert '}'", "Close unclosed block", 10) { ctx, file, line, col -> insertAtEndOfFile(ctx, file, "}") })
                msg.contains("expecting") && msg.contains(",") -> fixes.add(QuickFix("Insert ','", "Add missing comma", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, ",") })
                msg.contains("expecting") && msg.contains("colon") -> fixes.add(QuickFix("Insert ':'", "Add missing colon", 10) { ctx, file, line, col -> insertChar(ctx, file, line, col, ":") })
            }
            fixes.ifEmpty { null }
        })

        // Override error rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.OVERRIDE_ERROR) return@FixRule null
            listOf(
                QuickFix("Remove 'override' modifier", "Remove unnecessary override", 10) { ctx, file, line, _ -> removeModifier(ctx, file, line, "override") },
                QuickFix("Add override keyword", "Mark as override", 8) { ctx, file, line, _ -> addModifier(ctx, file, line, "override") }
            )
        })

        // Function signature error rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.FUNCTION_SIGNATURE_ERROR) return@FixRule null
            val msg = diag.message.lowercase()
            val fixes = mutableListOf<QuickFix>()
            if (msg.contains("parameter")) {
                fixes.add(QuickFix("Add missing parameter", "Generate matching parameter", 10) { ctx, file, line, _ -> addPlaceholderParam(ctx, file, line) })
            }
            if (msg.contains("return type")) {
                fixes.add(QuickFix("Add return type", "Add explicit return type", 8) { ctx, file, line, _ -> addReturnType(ctx, file, line) })
            }
            fixes.ifEmpty { null }
        })

        // Generic type error rules
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.GENERIC_TYPE_ERROR) return@FixRule null
            listOf(
                QuickFix("Replace with star projection (*)", "Use wildcard type", 10) { ctx, file, line, col -> replaceStarProjection(ctx, file, line) },
                QuickFix("Add type parameter", "Specify concrete type", 8) { ctx, file, line, col -> addTypeParameter(ctx, file, line) }
            )
        })

        // Other/common patterns
        rules.add(FixRule { diag ->
            if (diag.kind != DiagnosticKind.OTHER) return@FixRule null
            val msg = diag.message.lowercase()
            val fixes = mutableListOf<QuickFix>()
            if (msg.contains("not used") || msg.contains("unused")) {
                fixes.add(QuickFix("Remove unused code", "Delete unused declaration", 10) { ctx, file, line, _ -> removeLine(ctx, file, line) })
                fixes.add(QuickFix("Suppress warning", "Add @Suppress annotation", 8) { ctx, file, line, _ -> addSuppress(ctx, file, line, diag.message) })
            }
            if (msg.contains("deprecated")) {
                fixes.add(QuickFix("Suppress deprecation", "Add @Suppress(\"DEPRECATION\")", 10) { ctx, file, line, _ -> addSuppress(ctx, file, line, "DEPRECATION") })
            }
            if (msg.contains("private") && msg.contains("exposes")) {
                fixes.add(QuickFix("Make public", "Change visibility to public", 10) { ctx, file, line, _ -> changeVisibility(ctx, file, line, "public") })
                fixes.add(QuickFix("Make internal", "Change visibility to internal", 9) { ctx, file, line, _ -> changeVisibility(ctx, file, line, "internal") })
            }
            fixes.ifEmpty { null }
        })
    }

    fun getFixes(diagnostic: Diagnostic): List<QuickFix> {
        val fixes = mutableListOf<QuickFix>()
        for (rule in rules) {
            rule.match(diagnostic)?.let { fixes.addAll(it) }
        }
        return fixes.sortedByDescending { it.priority }.take(8)
    }

    fun showFixDialog(context: Context, diagnostic: Diagnostic, file: File, onApplied: () -> Unit) {
        val fixes = getFixes(diagnostic)
        if (fixes.isEmpty()) {
            showNoFixesDialog(context, diagnostic)
            return
        }
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Quick Fix")
        if (fixes.size > 6) {
            val scrollView = ScrollView(context)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            fixes.forEach { fix ->
                val tv = TextView(context).apply {
                    text = "${fix.label}\n${fix.description}"
                    setTextColor(StudioUi.color(StudioUi.ActiveBlue))
                    textSize = 13f
                    setPadding(16, 12, 16, 12)
                    setOnClickListener {
                        fix.action(context, file, diagnostic.line, diagnostic.column)
                        onApplied()
                    }
                }
                container.addView(tv)
            }
            scrollView.addView(container)
            builder.setView(scrollView)
        } else {
            builder.setItems(fixes.map { "${it.label}\n  ${it.description}" }.toTypedArray()) { _, which ->
                fixes[which].action(context, file, diagnostic.line, diagnostic.column)
                onApplied()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    fun showGoToDefinitionDialog(context: Context, symbol: String, results: List<GoToDefinitionResult>, onSelect: (GoToDefinitionResult) -> Unit) {
        if (results.isEmpty()) return
        if (results.size == 1) { onSelect(results.first()); return }
        val builder = AlertDialog.Builder(context).setTitle("$symbol \u00B7 ${results.size} definitions")
        if (results.size > 5) {
            val scrollView = ScrollView(context)
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * context.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            results.forEach { result ->
                val tv = TextView(context).apply {
                    text = "${result.file.name}:${result.line}  ${result.preview.trim()}"
                    setTextColor(StudioUi.color(StudioUi.ActiveBlue))
                    textSize = 13f
                    setPadding(16, 12, 16, 12)
                    setOnClickListener { onSelect(result) }
                }
                container.addView(tv)
            }
            scrollView.addView(container)
            builder.setView(scrollView)
        } else {
            builder.setItems(results.map { "${it.file.name}:${it.line}  ${it.preview.trim()}" }.toTypedArray()) { _, which -> onSelect(results[which]) }
        }
        builder.setNegativeButton("Cancel", null).show()
    }

    private fun showNoFixesDialog(context: Context, diagnostic: Diagnostic) {
        AlertDialog.Builder(context)
            .setTitle("No Quick Fixes")
            .setMessage("${diagnostic.severity.name}: ${diagnostic.message}\n\nLine ${diagnostic.line}, Column ${diagnostic.column}")
            .setPositiveButton("OK", null).show()
    }

    // ???? Helper: extract name from message ????
    private fun extractName(message: String, patterns: List<Regex>): String {
        for (p in patterns) p.find(message)?.groupValues?.getOrNull(1)?.let { return it }
        return ""
    }

    private fun extractTypeWord(message: String, keyword: String): String {
        val idx = message.indexOf(keyword, ignoreCase = true)
        if (idx < 0) return ""
        val sub = message.substring(idx + keyword.length).trimStart(':', ' ', '\'')
        val end = sub.indexOfFirst { !it.isLetterOrDigit() && it != '_' && it != '.' && it != '<' && it != '>' }
        return if (end > 0) sub.substring(0, end) else sub.take(30)
    }

    // ???? Fix actions ????
    private fun createStubFunction(ctx: Context, file: File, line: Int, name: String) {
        val lines = file.readText().lines().toMutableList()
        val indent = lines.getOrNull(line - 1)?.takeWhile { it == ' ' || it == '\t' } ?: ""
        lines.add(line.coerceIn(0, lines.size), "${indent}fun $name() {\n${indent}    TODO(\"Implement $name\")\n${indent}}")
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Created stub: $name")
    }

    private fun createStubVariable(ctx: Context, file: File, line: Int, name: String) {
        val lines = file.readText().lines().toMutableList()
        val indent = lines.getOrNull(line - 1)?.takeWhile { it == ' ' || it == '\t' } ?: ""
        lines.add(line.coerceIn(0, lines.size), "${indent}val $name = TODO(\"Init $name\")")
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Created variable: $name")
    }

    private fun suggestImport(ctx: Context, file: File, name: String) {
        val syncManager = gradleSyncManager
        val fqn = if (syncManager != null) {
            val matches = syncManager.searchSymbols(name, maxResults = 10)
            matches.firstOrNull { it.simpleName == name }?.fullyQualifiedName
                ?: matches.firstOrNull()?.fullyQualifiedName
        } else null

        val importPath = fqn ?: name
        val lines = file.readText().lines().toMutableList()
        val existingImports = lines.filter { it.trimStart().startsWith("import ") }.map { it.trim() }
        if (existingImports.any { it == "import $importPath" }) {
            toast(ctx, "Import already exists: $importPath")
            return
        }
        val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
        if (lastImport >= 0) {
            lines.add(lastImport + 1, "import $importPath")
        } else {
            val pkgLine = lines.indexOfFirst { it.trimStart().startsWith("package ") }
            val insertAt = if (pkgLine >= 0) pkgLine + 1 else 0
            lines.add(insertAt, "")
            lines.add(insertAt + 1, "import $importPath")
        }
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Added import: $importPath")
    }

    private fun suggestSimilarName(ctx: Context, file: File, line: Int, name: String) {
        val source = file.readText()
        val words = Regex("""\b\w+\b""").findAll(source).map { it.value }.filter { it.length >= 3 }.distinct().toList()
        val similar = words.filter { levenshtein(it, name) <= 2 }.take(5)
        if (similar.isEmpty()) toast(ctx, "No similar names found")
        else toast(ctx, "Similar: ${similar.joinToString(", ")}")
    }

    private fun addTypeCast(ctx: Context, file: File, line: Int, type: String) {
        modifyLine(file, line) { "${it.trimEnd()} as $type" }
        toast(ctx, "Added cast: as $type")
    }

    private fun addConversion(ctx: Context, file: File, line: Int, from: String, to: String) {
        val conv = when (to) {
            "String" -> ".toString()"; "Int" -> ".toIntOrNull() ?: 0"; "Long" -> ".toLongOrNull() ?: 0L"
            "Double" -> ".toDoubleOrNull() ?: 0.0"; "Float" -> ".toFloatOrNull() ?: 0f"
            "Boolean" -> ".toBooleanStrictOrNull() ?: false"; "List" -> ".toList()"; "Set" -> ".toSet()"; "Map" -> ".toMap()"
            else -> " as? $to"
        }
        toast(ctx, "Add conversion: $conv")
    }

    private fun addNonNullAssert(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val qIdx = l.indexOf("?")
        if (qIdx >= 0) { lines[idx] = l.replaceRange(qIdx, qIdx + 1, "!!"); file.writeText(lines.joinToString("\n")); toast(ctx, "Added !!") }
        else toast(ctx, "No nullable expression found")
    }

    private fun addSafeCall(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val dIdx = l.indexOf(".")
        if (dIdx >= 0) { lines[idx] = l.replaceRange(dIdx, dIdx + 1, "?."); file.writeText(lines.joinToString("\n")); toast(ctx, "Added ?.") }
        else toast(ctx, "No dot call found")
    }

    private fun addEarlyReturn(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val eqIdx = l.indexOf(" = ")
        if (eqIdx >= 0) {
            val varName = l.substring(l.indexOfFirst { it != ' ' && it != '\t' }, eqIdx).trim()
            val rest = l.substring(eqIdx + 3)
            lines[idx] = "val $varName = $rest ?: return"
            file.writeText(lines.joinToString("\n")); toast(ctx, "Added ?: return")
        } else toast(ctx, "Could not detect assignment")
    }

    private fun wrapInLet(ctx: Context, file: File, line: Int) {
        modifyLine(file, line) { "${it.trimEnd()}.let { /* use it here */ }" }
        toast(ctx, "Wrapped in let { }")
    }

    private fun removeLine(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        lines.removeAt(idx)
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Removed line $line")
    }

    private fun removeModifier(ctx: Context, file: File, line: Int, modifier: String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        lines[idx] = lines[idx].replace(Regex("""\b$modifier\b\s*"""), "").trimStart()
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Removed '$modifier'")
    }

    private fun addModifier(ctx: Context, file: File, line: Int, modifier: String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val funIdx = l.indexOf("fun ")
        if (funIdx >= 0) { lines[idx] = l.substring(0, funIdx) + "$modifier " + l.substring(funIdx); file.writeText(lines.joinToString("\n")); toast(ctx, "Added '$modifier'") }
        else toast(ctx, "Could not find fun keyword")
    }

    private fun insertChar(ctx: Context, file: File, line: Int, col: Int, char: String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val insertAt = (col - 1).coerceIn(0, l.length)
        lines[idx] = l.substring(0, insertAt) + char + l.substring(insertAt)
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Inserted '$char'")
    }

    private fun insertAtEndOfFile(ctx: Context, file: File, char: String) {
        file.appendText(char)
        toast(ctx, "Inserted '$char' at end")
    }

    private fun replaceStarProjection(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val open = l.lastIndexOf('<'); val close = l.indexOf('>')
        if (open >= 0 && close > open) { lines[idx] = l.substring(0, open + 1) + "*" + l.substring(close); file.writeText(lines.joinToString("\n")); toast(ctx, "Replaced with *") }
        else toast(ctx, "No type parameter found")
    }

    private fun addTypeParameter(ctx: Context, file: File, line: Int) { toast(ctx, "Specify the type parameter manually") }

    private fun addPlaceholderParam(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val parenIdx = l.indexOf('(')
        if (parenIdx >= 0 && l.indexOf(')') > parenIdx) {
            lines[idx] = l.substring(0, parenIdx + 1) + "/* TODO params */" + l.substring(l.indexOf(')'))
            file.writeText(lines.joinToString("\n")); toast(ctx, "Added parameter placeholder")
        }
    }

    private fun addReturnType(ctx: Context, file: File, line: Int) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val closeParen = l.indexOf(')')
        if (closeParen >= 0) { lines[idx] = l.substring(0, closeParen + 1) + ": TODO = " + l.substring(closeParen + 1).trimStart(); file.writeText(lines.joinToString("\n")); toast(ctx, "Added return type") }
    }

    private fun addSuppress(ctx: Context, file: File, line: Int, warning: String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size)
        lines.add(idx, "@Suppress(\"${warning.take(40)}\")")
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Added @Suppress")
    }

    private fun changeVisibility(ctx: Context, file: File, line: Int, vis: String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        val l = lines[idx]
        val cleaned = l.replace(Regex("""\b(public|private|protected|internal)\b\s*"""), "")
        lines[idx] = "$vis $cleaned"
        file.writeText(lines.joinToString("\n"))
        toast(ctx, "Changed to $vis")
    }

    // ???? Utility ????
    private fun modifyLine(file: File, line: Int, transform: (String) -> String) {
        val lines = file.readText().lines().toMutableList()
        val idx = (line - 1).coerceIn(0, lines.size - 1)
        lines[idx] = transform(lines[idx])
        file.writeText(lines.joinToString("\n"))
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1] else minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1]) + 1
        }
        return dp[a.length][b.length]
    }

    private fun toast(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
