package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class GoHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer",
        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
        "interface", "map", "package", "range", "return", "select", "struct",
        "switch", "type", "var"
    )

    private val builtins = setOf(
        "true", "false", "iota", "nil",
        "bool", "byte", "complex64", "complex128", "error", "float32", "float64",
        "int", "int8", "int16", "int32", "int64", "rune", "string",
        "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        "append", "cap", "close", "complex", "copy", "delete", "imag",
        "len", "make", "new", "panic", "print", "println", "real", "recover"
    )

    private val types = setOf(
        "string", "int", "int8", "int16", "int32", "int64",
        "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        "float32", "float64", "complex64", "complex128",
        "bool", "byte", "rune", "error", "interface{}", "struct{}",
        "map", "slice", "chan"
    )

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val stringDoublePattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val stringBacktickPattern = Regex("`[^`]*`")
        val rawStringPattern = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*\\.?\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?)[iu]*\\b")
        val annotationPattern = Regex("\\b(?:fmt\\.Print|log\\.Print)\\w*")
        val classPattern = Regex("\\b(type|struct|interface)\\s+(\\w+)")
        val functionPattern = Regex("\\bfunc\\s+(?:\\(\\w+\\s+\\*?\\w+\\)\\s+)?(\\w+)\\s*\\(")
        val importPattern = Regex("^\\s*import\\s+\\(?[\\s\\S]*?\\)|^\\s*import\\s+\"[^\"]*\"", RegexOption.MULTILINE)
        val constPattern = Regex("\\bconst\\s+(\\w+)\\b")
        val varPattern = Regex("\\bvar\\s+(\\w+)\\b")

        val excludedRanges = mutableListOf<IntRange>()

        commentBlockPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        commentLinePattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        rawStringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        stringBacktickPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        stringDoublePattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        classPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synType, bold = true)
            }
        }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        importPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synImport)
            }
        }

        constPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synConst, bold = true)
            }
        }

        varPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synField)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val keywordPattern = Regex("\\b(${keywords.joinToString("|")})\\b")
        keywordPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synKw)
            }
        }

        val builtinPattern = Regex("\\b(${builtins.joinToString("|")})\\b")
        builtinPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                val color = when (it.value) {
                    "true", "false", "iota", "nil" -> StudioUi.palette.synBoolean
                    "append", "cap", "close", "complex", "copy", "delete", "imag",
                    "len", "make", "new", "panic", "print", "println", "real", "recover" -> StudioUi.palette.synFunc
                    else -> StudioUi.palette.synType
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
