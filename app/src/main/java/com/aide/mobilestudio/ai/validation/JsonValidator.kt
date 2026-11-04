package com.aide.mobilestudio.ai.validation

/** Severity of a structured-file issue. */
enum class JsonIssueSeverity { ERROR, WARNING }

/**
 * A single problem found while validating a JSON document.
 *
 * [line] / [column] are 1-based. When the parser cannot pinpoint a location the
 * caller is expected to show the issue without claiming an exact position.
 */
data class JsonIssue(
    val line: Int,
    val column: Int,
    val message: String,
    val severity: JsonIssueSeverity = JsonIssueSeverity.ERROR,
    val snippet: String? = null,
    val offset: Int = -1,
) {
    fun toShortString(): String = "$line:$column: ${severity.name.lowercase()}: $message"
}

data class JsonValidationResult(
    val valid: Boolean,
    val issues: List<JsonIssue>,
    val text: String,
) {
    fun summary(): String = when {
        valid -> "Valid JSON"
        issues.isEmpty() -> "Invalid JSON (no detail available)"
        else -> issues.joinToString("\n") { it.toShortString() }
    }
}

data class JsonRepairResult(
    val success: Boolean,
    val repaired: String,
    val fixes: List<String>,
    val remainingIssues: List<JsonIssue>,
) {
    fun summary(): String = when {
        success && fixes.isEmpty() -> "JSON is already valid."
        success -> "JSON repaired (${fixes.size} fix(es)): ${fixes.joinToString(", ")}"
        else -> "Could not fully repair JSON. ${remainingIssues.firstOrNull()?.toShortString() ?: ""}"
    }
}

/**
 * Strict JSON syntax validator.
 *
 * `org.json`'s tokener is permissive (single quotes, unquoted keys, trailing
 * commas, comments). That leniency is exactly what hides configuration bugs, so
 * this validator implements the real JSON grammar and reports the precise
 * line/column of the first error. It performs no I/O and has no Android
 * dependencies.
 */
object JsonValidator {

    fun validate(text: String): JsonValidationResult {
        if (text.isBlank()) {
            return JsonValidationResult(false, listOf(JsonIssue(1, 1, "Unexpected end of input")), text)
        }
        return try {
            StrictJsonParser(text).parse()
            JsonValidationResult(true, emptyList(), text)
        } catch (e: JsonParseException) {
            val (line, col) = offsetToLineColumn(text, e.offset)
            JsonValidationResult(
                false,
                listOf(JsonIssue(line, col, e.message ?: "JSON 파싱 오류", JsonIssueSeverity.ERROR, snippetAt(text, e.offset), e.offset)),
                text,
            )
        }
    }

    /** Converts a 0-based character [offset] into 1-based (line, column). */
    fun offsetToLineColumn(text: String, offset: Int): Pair<Int, Int> {
        val clamped = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until clamped) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (clamped - lineStart + 1)
    }

    private fun snippetAt(text: String, offset: Int): String? {
        if (offset < 0 || offset > text.length) return null
        val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        if (lineStart >= lineEnd) return null
        return text.substring(lineStart, minOf(lineEnd, lineStart + 200))
    }

    internal class JsonParseException(message: String, val offset: Int) : Exception(message)

    private class StrictJsonParser(private val s: String) {
        private var i = 0

        fun parse() {
            skipWs()
            if (i >= s.length) fail("Unexpected end of input")
            parseValue()
            skipWs()
            if (i < s.length) fail("Unexpected trailing content")
        }

        private fun parseValue() {
            if (i >= s.length) fail("Unexpected end of input")
            when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> expectLiteral("true")
                'f' -> expectLiteral("false")
                'n' -> expectLiteral("null")
                '-', in '0'..'9' -> parseNumber()
                '\'' -> fail("Single-quoted strings are not valid JSON")
                else -> fail("Unexpected character '$c'")
            }
        }

        private fun parseObject() {
            i++ // {
            skipWs()
            if (peek() == '}') { i++; return }
            while (true) {
                skipWs()
                val keyChar = peek()
                if (keyChar != '"') {
                    if (keyChar == '}') fail("Trailing comma is not allowed here")
                    if (keyChar == '\'') fail("Single-quoted strings are not valid JSON")
                    if (keyChar == null) fail("Unterminated object")
                    fail("Expected a double-quoted property name")
                }
                parseString()
                skipWs()
                expect(':')
                skipWs()
                parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == '}') fail("Trailing comma is not allowed here")
                    }
                    '}' -> { i++; return }
                    null -> fail("Unterminated object")
                    else -> fail("Expected ',' or '}' after property value")
                }
            }
        }

        private fun parseArray() {
            i++ // [
            skipWs()
            if (peek() == ']') { i++; return }
            while (true) {
                skipWs()
                if (peek() == ']') fail("Trailing comma is not allowed here")
                parseValue()
                skipWs()
                when (peek()) {
                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == ']') fail("Trailing comma is not allowed here")
                    }
                    ']' -> { i++; return }
                    null -> fail("Unterminated array")
                    else -> fail("Expected ',' or ']' after array element")
                }
            }
        }

        private fun parseString() {
            expect('"')
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) fail("Unterminated escape sequence")
                        when (val e = s[i]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                            'u' -> {
                                i++
                                repeat(4) {
                                    if (i >= s.length || !s[i].isHex()) fail("Invalid unicode escape")
                                    i++
                                }
                            }
                            else -> fail("Invalid escape sequence '\\$e'")
                        }
                    }
                    c.code < 0x20 -> fail("Control character in string must be escaped")
                    else -> i++
                }
            }
        }

        private fun parseNumber() {
            val start = i
            if (peek() == '-') i++
            if (peek() == '0') {
                i++
            } else if (peek()?.isDigit() == true) {
                while (peek()?.isDigit() == true) i++
            } else {
                fail("Invalid number")
            }
            if (peek() == '.') {
                i++
                if (peek()?.isDigit() != true) fail("Invalid number: expected digit after '.'")
                while (peek()?.isDigit() == true) i++
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek()?.isDigit() != true) fail("Invalid number: expected digit in exponent")
                while (peek()?.isDigit() == true) i++
            }
            if (i == start) fail("Invalid number")
        }

        private fun expectLiteral(literal: String) {
            if (i + literal.length > s.length || s.substring(i, i + literal.length) != literal) {
                fail("Invalid literal, expected '$literal'")
            }
            i += literal.length
        }

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) fail("Expected '$c'")
            i++
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun fail(message: String): Nothing = throw JsonParseException(message, i)
    }
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Deterministic, rule-based JSON repair. This intentionally runs *before* any AI
 * call: obvious syntax mistakes (comments, trailing commas, smart quotes, single
 * quotes, unquoted keys, missing closers) are fixed without network access. Every
 * candidate repair is re-validated with [JsonValidator]; unverified output is
 * never reported as success. Repairs are bounded.
 */
object JsonRepairer {

    fun repair(original: String, maxPasses: Int = 6): JsonRepairResult {
        var text = original
        val fixes = mutableListOf<String>()
        var result = JsonValidator.validate(text)
        if (result.valid) return JsonRepairResult(true, text, emptyList(), emptyList())

        val transforms: List<Pair<String, (String) -> String>> = listOf(
            "removed UTF-8 BOM" to ::stripBom,
            "normalized smart quotes" to ::normalizeSmartQuotes,
            "removed comments" to ::removeComments,
            "removed trailing commas" to ::removeTrailingCommas,
            "converted single-quoted strings" to ::singleToDoubleQuotes,
            "quoted object keys" to ::quoteUnquotedKeys,
            "appended missing closing brackets" to ::balanceBrackets,
        )

        for (pass in 0 until maxPasses) {
            var changed = false
            for ((label, transform) in transforms) {
                val next = transform(text)
                if (next != text) {
                    text = next
                    fixes.add(label)
                    changed = true
                    result = JsonValidator.validate(text)
                    if (result.valid) return JsonRepairResult(true, text, fixes, emptyList())
                }
            }
            if (!changed) break
        }
        return JsonRepairResult(false, text, fixes, result.issues)
    }

    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.removePrefix("\uFEFF") else text

    private fun normalizeSmartQuotes(text: String): String = text
        .replace('\u201C', '"').replace('\u201D', '"')
        .replace('\u2018', '\'').replace('\u2019', '\'')

    private fun removeComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun removeTrailingCommas(text: String): String {
        val sb = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == ',') {
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j < text.length && (text[j] == '}' || text[j] == ']')) {
                    i++ // drop the trailing comma
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun singleToDoubleQuotes(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inDouble = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inDouble) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inDouble = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> { inDouble = true; sb.append(c); i++ }
                '\'' -> {
                    // Convert this single-quoted string to a double-quoted one.
                    sb.append('"')
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        when (d) {
                            '\\' -> {
                                if (i + 1 < text.length) { sb.append(d).append(text[i + 1]); i += 2 } else { i++ }
                            }
                            '\'' -> { sb.append('"'); i++; break }
                            '"' -> { sb.append("\\\""); i++ }
                            else -> { sb.append(d); i++ }
                        }
                    }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun quoteUnquotedKeys(text: String): String {
        val sb = StringBuilder(text.length + 16)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var expectKey = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '{' -> { stack.addLast('o'); expectKey = true; sb.append(c); i++ }
                c == '[' -> { stack.addLast('a'); expectKey = false; sb.append(c); i++ }
                c == '}' || c == ']' -> { if (stack.isNotEmpty()) stack.removeLast(); expectKey = false; sb.append(c); i++ }
                c == ',' -> { expectKey = stack.lastOrNull() == 'o'; sb.append(c); i++ }
                c == ':' -> { expectKey = false; sb.append(c); i++ }
                expectKey && (c.isLetterOrDigit() || c == '_' || c == '$' || c == '-') -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '-' || text[i] == '.')) i++
                    val ident = text.substring(start, i)
                    var j = i
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && text[j] == ':') {
                        sb.append('"').append(ident).append('"')
                    } else {
                        sb.append(ident)
                    }
                    expectKey = false
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun balanceBrackets(text: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.lastOrNull() == '{') stack.removeLast()
                ']' -> if (stack.lastOrNull() == '[') stack.removeLast()
            }
        }
        if (stack.isEmpty()) return text
        val sb = StringBuilder(text)
        while (stack.isNotEmpty()) {
            val open = stack.removeLast()
            sb.append(if (open == '{') '}' else ']')
        }
        return sb.toString()
    }
}
