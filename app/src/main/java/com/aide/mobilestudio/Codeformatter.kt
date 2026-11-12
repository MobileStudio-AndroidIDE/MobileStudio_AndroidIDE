package com.aide.mobilestudio

import java.io.File

/**
 * Code formatting for the Reformat Code and Optimize Imports tools.
 * Supports Kotlin/Java/Groovy/Gradle (brace languages), XML and JSON.
 * All functions are pure: they take a source string and return a formatted string.
 */
object CodeFormatter {

    private val REFORMAT_EXTS = setOf("xml", "json", "kt", "kts", "java", "groovy", "gradle")
    private val IMPORT_EXTS = setOf("kt", "kts", "java", "groovy", "gradle")

    // ---------- Public entry points ----------

    /**
     * Formats [src] according to [ext] (lowercase file extension without dot).
     * Returns [src] unchanged when no formatter exists for [ext].
     */
    fun format(src: String, ext: String): String {
        val e = ext.lowercase()
        return when {
            e == "xml" -> prettyPrintXml(src)
            e == "json" -> prettyPrintJson(src)
            e in IMPORT_EXTS -> formatBraceLanguage(src)
            else -> src
        }
    }

    /** Legacy single-arg delegate kept for compatibility. */
    fun format(src: String): String = format(src, "kt")

    /** Returns true if [ext] has a reformatter. */
    fun isFormattable(ext: String): Boolean = ext.lowercase() in REFORMAT_EXTS

    /** Returns true if [ext] supports Optimize Imports. */
    fun isImportOptimizable(ext: String): Boolean = ext.lowercase() in IMPORT_EXTS

    /**
     * Removes unused imports from [src] (Kotlin/Java/Groovy/Gradle).
     * Wildcard imports are always kept. Returns [src] unchanged if nothing was removed.
     */
    fun optimizeImports(src: String): String {
        val lines = src.lines().toMutableList()

        // Locate the leading import block (after optional package line / comments / blank lines).
        var blockStart = -1
        var blockEnd = -1
        var inBlock = false
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (!inBlock) {
                if (trimmed.startsWith("import ")) {
                    blockStart = i
                    blockEnd = i
                    inBlock = true
                } else if (trimmed.isNotEmpty() &&
                    !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                    !trimmed.startsWith("*") && !trimmed.startsWith("package")) {
                    break
                }
            } else {
                if (trimmed.startsWith("import ")) {
                    blockEnd = i
                } else if (trimmed.isNotBlank()) {
                    break
                }
            }
        }
        if (blockStart < 0) return src

        // Body = everything except the import-block lines, used for usage detection.
        val body = lines.filterIndexed { i, _ -> i < blockStart || i > blockEnd }
            .joinToString("\n")

        val kept = mutableListOf<String>()
        var removed = 0
        for (i in blockStart..blockEnd) {
            val imp = lines[i]
            val trimmed = imp.trim()
            if (!trimmed.startsWith("import ")) {
                kept.add(imp)
                continue
            }
            // Always keep wildcard and static imports.
            if (trimmed.contains(".*") || trimmed.contains(".*;") || trimmed.contains("static ")) {
                kept.add(imp)
                continue
            }
            val name = trimmed.removePrefix("import ").trimEnd(';').trim().substringAfterLast('.').trim()
            if (name.isEmpty()) { kept.add(imp); continue }
            val pattern = Regex("\\b${Regex.escape(name)}\\b")
            if (pattern.containsMatchIn(body)) kept.add(imp) else removed++
        }

        if (removed == 0) return src

        val sorted = sortImports(kept)
        val prefix = lines.subList(0, blockStart)
        val suffix = lines.subList(blockEnd + 1, lines.size)
        var result = (prefix + sorted + suffix).joinToString("\n")
        if (!result.endsWith("\n")) result += "\n"
        return result
    }

    // ---------- Import sorting (kept from legacy CodeFormatter) ----------

    private fun sortImports(imports: List<String>): List<String> {
        val grouped = mutableListOf<String>()
        var lastGroup = ""
        imports.sortedWith(compareBy(
            { if (it.contains("android")) 0 else if (it.contains("java") || it.contains("kotlin")) 1 else 2 },
            { it }
        )).forEach { imp ->
            val group = imp.removePrefix("import ").substringBefore(".")
            if (group != lastGroup && grouped.isNotEmpty()) grouped.add("")
            grouped.add(imp)
            lastGroup = group
        }
        return grouped
    }

    // ---------- Brace-language (Kotlin/Java/Groovy/Gradle) reformatter ----------

    private fun formatBraceLanguage(src: String): String {
        val lines = src.lines()
        val out = ArrayList<String>()
        var indent = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.add("")
                continue
            }
            val first = trimmed.first()
            val decreases = first == '}' || first == ')' || first == ']' ||
                trimmed.startsWith("catch") || trimmed.startsWith("finally") || trimmed.startsWith("else")
            val displayIndent = if (decreases && indent > 0) indent - 1 else indent
            out.add("    ".repeat(displayIndent) + trimmed)

            val masked = stripStringsAndComments(trimmed)
            val opens = masked.count { it == '{' || it == '(' || it == '[' }
            val closes = masked.count { it == '}' || it == ')' || it == ']' }
            indent += (opens - closes)
            if (indent < 0) indent = 0
        }
        var result = out.joinToString("\n")
        result = Regex("\n{3,}").replace(result, "\n\n")
        result = result.lines().joinToString("\n") { it.trimEnd() }
        if (!result.endsWith("\n")) result += "\n"
        return result
    }

    /**
     * Returns a copy of [s] with string literals, raw strings and comments replaced by spaces,
     * so braces inside them are not counted for indentation.
     */
    private fun stripStringsAndComments(s: String): String {
        val out = StringBuilder()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' && i + 2 < n && s[i + 1] == '"' && s[i + 2] == '"' -> {
                    val end = s.indexOf("\"\"\"", i + 3)
                    if (end < 0) {
                        out.append(" ".repeat(n - i))
                        i = n
                    } else {
                        out.append(" ".repeat(end + 3 - i))
                        i = end + 3
                    }
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < n) {
                        if (s[j] == '\\') j += 2
                        else if (s[j] == '"') { j++; break }
                        else j++
                    }
                    out.append(" ".repeat(j - i))
                    i = j
                }
                c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                    val nl = s.indexOf('\n', i)
                    if (nl < 0) {
                        out.append(" ".repeat(n - i))
                        i = n
                    } else {
                        out.append(" ".repeat(nl - i))
                        i = nl
                    }
                }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) {
                        out.append(" ".repeat(n - i))
                        i = n
                    } else {
                        out.append(" ".repeat(end + 2 - i))
                        i = end + 2
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    // ---------- XML pretty printer ----------

    private fun prettyPrintXml(src: String): String {
        val out = StringBuilder()
        var depth = 0
        var i = 0
        val n = src.length
        fun indent() { out.append("    ".repeat(depth.coerceAtLeast(0))) }

        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    indent(); out.append(src, i, end).append('\n'); i = end
                }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i + 9).let { if (it < 0) n else it + 3 }
                    indent(); out.append(src, i, end).append('\n'); i = end
                }
                src.startsWith("<?", i) -> {
                    val end = src.indexOf("?>", i + 2).let { if (it < 0) n else it + 2 }
                    indent(); out.append(src.substring(i, end).trim()).append('\n'); i = end
                }
                c == '<' -> {
                    val j = src.indexOf('>', i)
                    if (j < 0) {
                        out.append(src.substring(i).trim()).append('\n')
                        i = n
                    } else {
                        val tag = src.substring(i, j + 1)
                        val isClose = tag.startsWith("</")
                        val isDecl = tag.startsWith("<?") || tag.startsWith("<!")
                        val isSelf = tag.endsWith("/>") || isDecl
                        if (isClose) depth = (depth - 1).coerceAtLeast(0)
                        indent(); out.append(tag)
                        if (isClose || isSelf) {
                            out.append('\n')
                        } else {
                            var k = j + 1
                            while (k < n && src[k].isWhitespace()) k++
                            if (k >= n || src[k] == '<') out.append('\n')
                            depth++
                        }
                        i = j + 1
                    }
                }
                else -> {
                    var j = i
                    while (j < n && src[j] != '<') j++
                    val text = src.substring(i, j).trim()
                    if (text.isNotEmpty()) {
                        indent(); out.append(text).append('\n')
                    }
                    i = j
                }
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    // ---------- JSON pretty printer ----------

    private fun prettyPrintJson(src: String): String {
        val out = StringBuilder()
        var indent = 0
        var inString = false
        var escaped = false
        var i = 0
        val n = src.length
        fun indentLine() { out.append("    ".repeat(indent.coerceAtLeast(0))) }

        while (i < n) {
            val ch = src[i]
            when {
                escaped -> {
                    out.append(ch)
                    escaped = false
                    i++
                }
                ch == '\\' && inString -> {
                    out.append(ch); escaped = true; i++
                }
                ch == '"' -> {
                    inString = !inString
                    out.append(ch); i++
                }
                inString -> {
                    out.append(ch); i++
                }
                ch == '{' || ch == '[' -> {
                    out.append(ch).append('\n')
                    indent++
                    indentLine()
                    i++
                }
                ch == '}' || ch == ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    out.append('\n')
                    indentLine()
                    out.append(ch)
                    i++
                }
                ch == ',' -> {
                    out.append(',').append('\n')
                    indentLine()
                    i++
                }
                ch == ':' -> {
                    out.append(": ")
                    i++
                }
                ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t' -> {
                    i++ // whitespace normalized
                }
                else -> {
                    out.append(ch); i++
                }
            }
        }
        return out.toString().trimEnd() + "\n"
    }
}
