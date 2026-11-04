package com.aide.mobilestudio.analysis

import java.io.File
import java.util.Locale

class SyntaxChecker(private val config: AnalysisConfig) {

    private val braceBalance = BraceBalanceAnalyzer()
    private val importChecker = ImportSyntaxChecker()
    private val namingChecker = NamingConventionChecker()
    private val stringChecker = StringLiteralChecker()
    private val commonPatterns = CommonPatternRegistry()
    private val todoChecker = TodoCommentChecker()

    fun analyze(file: File, source: String): List<Diagnostic> {
        if (!config.enableQuickSyntaxCheck) return emptyList()
        val diagnostics = mutableListOf<Diagnostic>()
        val lines = source.lines()
        val path = file.absolutePath

        diagnostics += braceBalance.check(path, lines, source)
        diagnostics += importChecker.check(path, lines)
        diagnostics += namingChecker.check(path, lines)
        diagnostics += stringChecker.check(path, lines, source)
        diagnostics += commonPatterns.check(path, lines, source)
        diagnostics += todoChecker.check(path, lines)

        return diagnostics.take(config.maxDiagnosticsPerFile)
    }
}

private class BraceBalanceAnalyzer {
    private val bracePattern = Regex("[{}()\\[\\]]")

    fun check(path: String, lines: List<String>, source: String): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        var depth = 0
        var parenDepth = 0
        var bracketDepth = 0
        var lastOpenLine = -1
        var lastOpenCol = -1

        for ((index, line) in lines.withIndex()) {
            for ((col, ch) in line.withIndex()) {
                when (ch) {
                    '{' -> { depth++; lastOpenLine = index + 1; lastOpenCol = col + 1 }
                    '}' -> depth--
                    '(' -> { parenDepth++; lastOpenLine = index + 1; lastOpenCol = col + 1 }
                    ')' -> parenDepth--
                    '[' -> { bracketDepth++; lastOpenLine = index + 1; lastOpenCol = col + 1 }
                    ']' -> bracketDepth--
                }
            }
        }
        if (depth > 0 && lastOpenLine > 0) {
            result += Diagnostic(path, lastOpenLine, lastOpenCol, 1,
                "Unclosed brace `{` at line $lastOpenLine", DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
        }
        if (depth < 0) {
            result += Diagnostic(path, lines.size, 1, 1,
                "Unexpected closing brace `}`", DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
        }
        if (parenDepth > 0 && lastOpenLine > 0) {
            result += Diagnostic(path, lastOpenLine, lastOpenCol, 1,
                "Unclosed parenthesis `(` at line $lastOpenLine", DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
        }
        if (bracketDepth > 0 && lastOpenLine > 0) {
            result += Diagnostic(path, lastOpenLine, lastOpenCol, 1,
                "Unclosed bracket `[` at line $lastOpenLine", DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
        }
        return result
    }
}

private class ImportSyntaxChecker {
    private val importPattern = Regex("""^import\s+([a-zA-Z_][\w.]*(?:\.\*)?)\s*$""")
    private val staticImportPattern = Regex("""^import\s+(\w+(?:\.\w+)*\.[a-zA-Z_]\w*(?:\.\*)?)\s*$""")

    fun check(path: String, lines: List<String>): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) continue
            if (trimmed.startsWith("import ") && !importPattern.matches(trimmed) && !staticImportPattern.matches(trimmed)) {
                val detail = when {
                    trimmed.contains("..") -> "Double dot in import path"
                    trimmed.count { it == '.' } > 8 -> "Import path too long"
                    trimmed.lastOrNull()?.isLetterOrDigit() != true && !trimmed.endsWith(".*") ->
                        "Import should end with a package name or .*"
                    else -> "Invalid import syntax"
                }
                result += Diagnostic(path, index + 1, 1, trimmed.length,
                    detail, DiagnosticSeverity.ERROR, DiagnosticKind.IMPORT_ERROR)
            }
        }
        return result
    }
}

private class NamingConventionChecker {
    private val classPattern = Regex("""\b(?:class|interface|object|enum\s+class|data\s+class|sealed\s+class|value\s+class|annotation\s+class|abstract\s+class|open\s+class)\s+([A-Za-z_]\w*)""")
    private val funcPattern = Regex("""\bfun\s+([A-Za-z_]\w*)\s*\(""")
    private val valVarPattern = Regex("""\b(?:val|var)\s+([a-zA-Z_]\w*)\s*(?::\s*([A-Za-z_]\w*))?\s*=""")

    fun check(path: String, lines: List<String>): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("@")) continue

            val classMatch = classPattern.find(trimmed)
            if (classMatch != null) {
                val name = classMatch.groupValues[1]
                if (name[0].isLowerCase()) {
                    result += Diagnostic(path, index + 1,
                        trimmed.indexOf(name) + 1, name.length,
                        "Class name '$name' should start with an uppercase letter",
                        DiagnosticSeverity.WARNING, DiagnosticKind.OTHER)
                }
            }

            val funcMatch = funcPattern.findAll(trimmed).firstOrNull()
            if (funcMatch != null && !trimmed.startsWith("override ")) {
                val name = funcMatch.groupValues[1]
                if (name[0].isUpperCase()) {
                    result += Diagnostic(path, index + 1,
                        trimmed.indexOf(name) + 1, name.length,
                        "Function name '$name' should start with a lowercase letter",
                        DiagnosticSeverity.WARNING, DiagnosticKind.OTHER)
                }
            }

            val valVarMatch = valVarPattern.find(trimmed)
            if (valVarMatch != null) {
                val name = valVarMatch.groupValues[1]
                if (name.startsWith("_") && name.length > 1) {
                    result += Diagnostic(path, index + 1,
                        trimmed.indexOf(name) + 1, name.length,
                        "Property name '$name' should not start with underscore",
                        DiagnosticSeverity.HINT, DiagnosticKind.OTHER)
                }
            }

            if (trimmed.contains("= ") && trimmed.contains("null") && trimmed.contains("?")) {
                val equalsIdx = trimmed.indexOf("= ")
                if (equalsIdx > 0) {
                    val beforeEquals = trimmed.substring(0, equalsIdx)
                    if (beforeEquals.contains("var ") || beforeEquals.contains("val ")) {
                        val type = beforeEquals.substringAfter(":").trim().substringBefore("=").trim()
                        if (type.isNotBlank() && !type.contains("?") && !type.startsWith("(") && !type.contains("->")) {
                            result += Diagnostic(path, index + 1, equalsIdx + 1, 1,
                                "Property with nullable default should have nullable type (add ?)",
                                DiagnosticSeverity.HINT, DiagnosticKind.NULLABILITY_ERROR)
                        }
                    }
                }
            }
        }
        return result
    }
}

private class StringLiteralChecker {
    fun check(path: String, lines: List<String>, source: String): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        var inString = false
        var stringStartLine = -1
        var stringStartCol = -1
        var inRawString = false
        var rawQuoteCount = 0
        var inCharLiteral = false

        for ((index, line) in lines.withIndex()) {
            var col = 0
            while (col < line.length) {
                val ch = line[col]
                if (inRawString) {
                    if (ch == '"' && col + 2 < line.length && line[col + 1] == '"' && line[col + 2] == '"') {
                        rawQuoteCount++
                        if (rawQuoteCount == 2) {
                            inRawString = false; rawQuoteCount = 0
                        }
                        col += 3; continue
                    }
                    col++; continue
                }
                if (inCharLiteral) {
                    if (ch == '\'') inCharLiteral = false
                    col++; continue
                }
                if (inString) {
                    if (ch == '\\' && col + 1 < line.length) { col += 2; continue }
                    if (ch == '"' && !(col + 2 < line.length && line[col + 1] == '"' && line[col + 2] == '"')) {
                        inString = false
                    }
                    col++; continue
                }
                when {
                    ch == '\'' && col + 2 < line.length && line[col + 1] != '\'' -> inCharLiteral = true
                    ch == '"' && col + 2 < line.length && line[col + 1] == '"' && line[col + 2] == '"' -> {
                        inRawString = true; rawQuoteCount = 1; col += 3; continue
                    }
                    ch == '"' -> { inString = true; stringStartLine = index + 1; stringStartCol = col + 1 }
                }
                col++
            }
            if (inString && !inRawString) {
                result += Diagnostic(path, stringStartLine, stringStartCol, 1,
                    "Unclosed string literal starting at line $stringStartLine",
                    DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
                inString = false
            }
        }
        if (inRawString) {
            result += Diagnostic(path, lines.size, 1, 1,
                "Unclosed raw string literal (triple-quote)",
                DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
        }
        return result
    }
}

private class CommonPatternRegistry {
    private val todoPattern = Regex("""(TODO|FIXME|HACK|XXX|BUG|WORKAROUND)""", RegexOption.IGNORE_CASE)

    fun check(path: String, lines: List<String>, source: String): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            val packageMatch = Regex("""^package\s+([a-zA-Z_]\w*(?:\.\w+)*)\s*$""").find(trimmed)
            if (trimmed.startsWith("package ") && packageMatch == null) {
                result += Diagnostic(path, index + 1, 1, trimmed.length,
                    "Invalid package declaration", DiagnosticSeverity.ERROR, DiagnosticKind.SYNTAX_ERROR)
            }

            val duplicateImport = Regex("""import\s+(\S+)""").findAll(source)
                .groupBy { it.groupValues[1] }
                .filter { it.value.size > 1 }
            if (duplicateImport.isNotEmpty()) {
                duplicateImport.forEach { (pkg, matches) ->
                    matches.drop(1).forEach { match ->
                        val lineIdx = source.substring(0, match.range.first).count { it == '\n' }
                        result += Diagnostic(path, lineIdx + 1, match.range.first + 1, match.value.length,
                            "Duplicate import: '$pkg'", DiagnosticSeverity.WARNING, DiagnosticKind.IMPORT_ERROR)
                    }
                }
            }
        }

        return result
    }
}

private class TodoCommentChecker {
    private val todoPattern = Regex("""(TODO|FIXME|HACK|XXX|BUG|WORKAROUND|OPTIMIZE|REVIEW)""", RegexOption.IGNORE_CASE)

    fun check(path: String, lines: List<String>): List<Diagnostic> {
        val result = mutableListOf<Diagnostic>()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                val match = todoPattern.find(trimmed)
                if (match != null) {
                    val context = trimmed.substringAfter(match.value).trim().take(120)
                    result += Diagnostic(path, index + 1, trimmed.indexOf(match.value) + 1, match.value.length,
                        "${match.value}: $context", DiagnosticSeverity.INFO, DiagnosticKind.OTHER)
                }
            }
        }
        return result
    }
}
