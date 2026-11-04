package com.aide.mobilestudio.validation

/**
 * Comment/string-aware scanning utilities for build.gradle / build.gradle.kts /
 * settings.gradle scripts. Masking replaces comments (and optionally string
 * literals) with spaces while preserving length, so every index/line/column
 * computed on the masked text is valid for the original file.
 */
object GradleCodeScanner {

    /** Text with line/block comments blanked out; string literals kept. */
    fun maskComments(text: String): String = mask(text, maskLiterals = false)

    /** Text with comments and string/char literals blanked out (brace matching). */
    fun maskAll(text: String): String = mask(text, maskLiterals = true)

    private const val NORMAL = 0
    private const val LINE_COMMENT = 1
    private const val BLOCK_COMMENT = 2
    private const val DOUBLE_QUOTE = 3
    private const val SINGLE_QUOTE = 4
    private const val TRIPLE_DOUBLE = 5
    private const val TRIPLE_SINGLE = 6

    private fun mask(text: String, maskLiterals: Boolean): String {
        val out = CharArray(text.length)
        var state = NORMAL
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when (state) {
                NORMAL -> when {
                    c == '/' && next == '/' -> {
                        out[i] = ' '; out[i + 1] = ' '; state = LINE_COMMENT; i += 2; continue
                    }
                    c == '/' && next == '*' -> {
                        out[i] = ' '; out[i + 1] = ' '; state = BLOCK_COMMENT; i += 2; continue
                    }
                    c == '"' && text.startsWith("\"\"\"", i) -> {
                        repeat(3) { out[i + it] = if (maskLiterals) ' ' else '"' }
                        state = TRIPLE_DOUBLE; i += 3; continue
                    }
                    c == '\'' && text.startsWith("'''", i) -> {
                        repeat(3) { out[i + it] = if (maskLiterals) ' ' else '\'' }
                        state = TRIPLE_SINGLE; i += 3; continue
                    }
                    c == '"' -> {
                        out[i] = if (maskLiterals) ' ' else '"'
                        state = DOUBLE_QUOTE; i++; continue
                    }
                    c == '\'' -> {
                        out[i] = if (maskLiterals) ' ' else '\''
                        state = SINGLE_QUOTE; i++; continue
                    }
                    else -> out[i] = c
                }
                LINE_COMMENT -> {
                    out[i] = if (c == '\n') '\n' else ' '
                    if (c == '\n') state = NORMAL
                }
                BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        out[i] = ' '; out[i + 1] = ' '; state = NORMAL; i += 2; continue
                    }
                    out[i] = if (c == '\n') '\n' else ' '
                }
                DOUBLE_QUOTE -> {
                    when {
                        c == '\\' -> {
                            out[i] = if (maskLiterals) ' ' else '\\'
                            if (i + 1 < text.length) {
                                val n = text[i + 1]
                                out[i + 1] = if (n == '\n') '\n' else if (maskLiterals) ' ' else n
                            }
                            i += 2; continue
                        }
                        c == '"' -> {
                            out[i] = if (maskLiterals) ' ' else '"'
                            state = NORMAL
                        }
                        else -> out[i] = if (maskLiterals) ' ' else c
                    }
                }
                SINGLE_QUOTE -> {
                    when {
                        c == '\\' -> {
                            out[i] = if (maskLiterals) ' ' else '\\'
                            if (i + 1 < text.length) {
                                out[i + 1] = if (maskLiterals) ' ' else text[i + 1]
                            }
                            i += 2; continue
                        }
                        c == '\'' -> {
                            out[i] = if (maskLiterals) ' ' else '\''
                            state = NORMAL
                        }
                        else -> out[i] = if (maskLiterals) ' ' else c
                    }
                }
                TRIPLE_DOUBLE -> {
                    if (c == '"' && text.startsWith("\"\"\"", i)) {
                        repeat(3) { out[i + it] = if (maskLiterals) ' ' else '"' }
                        state = NORMAL; i += 3; continue
                    }
                    out[i] = if (c == '\n') '\n' else if (maskLiterals) ' ' else c
                }
                TRIPLE_SINGLE -> {
                    if (c == '\'' && text.startsWith("'''", i)) {
                        repeat(3) { out[i + it] = if (maskLiterals) ' ' else '\'' }
                        state = NORMAL; i += 3; continue
                    }
                    out[i] = if (c == '\n') '\n' else if (maskLiterals) ' ' else c
                }
            }
            i++
        }
        return String(out)
    }

    /**
     * Finds every `name {` / `name(` block in [maskedAll] (fully masked text)
     * and returns body ranges (exclusive of delimiters) valid on original text.
     */
    fun findBlocks(maskedAll: String, name: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        val pattern = Regex("""(?<![\w.])${Regex.escape(name)}\s*[{(]""")
        for (match in pattern.findAll(maskedAll)) {
            val openIdx = match.range.last
            val open = maskedAll[openIdx]
            val close = when (open) {
                '{' -> '}'
                '(' -> ')'
                else -> continue
            }
            val stack = ArrayDeque<Char>()
            stack.addLast(open)
            var i = openIdx + 1
            var end = -1
            while (i < maskedAll.length) {
                val c = maskedAll[i]
                if (c == '{' || c == '(') {
                    stack.addLast(c)
                } else if (c == '}' || c == ')') {
                    if (stack.isEmpty()) break
                    val top = stack.removeLast()
                    if ((top == '{' && c != '}') || (top == '(' && c != ')')) {
                        end = i
                        break
                    }
                    if (stack.isEmpty()) {
                        end = i
                        break
                    }
                }
                i++
            }
            if (end < 0) continue
            out.add((openIdx + 1) until end)
        }
        return out
    }

    fun lineOfIndex(text: String, index: Int): Int {
        var line = 1
        val upto = index.coerceAtMost(text.length)
        for (i in 0 until upto) if (text[i] == '\n') line++
        return line
    }
}
