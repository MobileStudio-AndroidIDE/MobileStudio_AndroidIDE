package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class PythonHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "finally", "for", "from",
        "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
        "or", "pass", "raise", "return", "try", "while", "with", "yield",
        "match", "case"
    )

    private val builtins = setOf(
        "True", "False", "None", "self", "cls",
        "print", "len", "range", "int", "float", "str", "list", "dict", "set", "tuple",
        "type", "super", "isinstance", "issubclass", "hasattr", "getattr", "setattr",
        "enumerate", "zip", "map", "filter", "sorted", "reversed", "any", "all",
        "open", "input", "format", "abs", "min", "max", "sum", "round",
        "property", "staticmethod", "classmethod", "__init__", "__str__", "__repr__",
        "__enter__", "__exit__", "__iter__", "__next__"
    )

    private val operators = setOf("==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "//=", "%=", "**=", "&=", "|=", "^=", "->", ":=")

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("#[^\n]*")
        val tripleQuotePattern = Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        val fStringPattern = Regex("[fFrRbBuU]?\"\"\"[\\s\\S]*?\"\"\"|[fFrRbBuU]?'''[\\s\\S]*?'''|[fFrRbBuU]?\"(?:[^\"\\\\]|\\\\.)*\"|[fFrRbBuU]?'(?:[^'\\\\]|\\\\.)*'")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*\\.\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?|\\.\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?|\\d[\\d_]*[eE][+-]?\\d[\\d_]*)[jJ]?\\b")
        val decoratorPattern = Regex("^\\s*@\\w+(?:\\.\\w+)*(?:\\([^)]*\\))?\\s*$", RegexOption.MULTILINE)
        val classPattern = Regex("\\bclass\\s+(\\w+)")
        val functionPattern = Regex("\\bdef\\s+(\\w+)\\s*\\(")
        val importPattern = Regex("^(?:from\\s+[\\w.]+\\s+)?import\\s+.+", RegexOption.MULTILINE)

        val excludedRanges = mutableListOf<IntRange>()

        commentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        tripleQuotePattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        fStringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                val color = if (it.value.first().lowercaseChar() == 'f') StudioUi.palette.synFunc else StudioUi.palette.synStr
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        decoratorPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        classPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
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
                    "True", "False", "None" -> StudioUi.palette.synBoolean
                    "self", "cls" -> StudioUi.palette.synOperator
                    else -> StudioUi.palette.synType
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
