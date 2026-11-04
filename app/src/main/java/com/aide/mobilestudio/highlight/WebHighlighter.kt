package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class WebHighlighter private constructor(
    private val mode: Mode
) : LanguageHighlighter {

    enum class Mode { HTML, CSS, JS, TS }

    companion object {
        fun html() = WebHighlighter(Mode.HTML)
        fun css() = WebHighlighter(Mode.CSS)
        fun js() = WebHighlighter(Mode.JS)
        fun ts() = WebHighlighter(Mode.TS)
    }

    private val jsKeywords = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "enum", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in", "instanceof",
        "let", "new", "null", "of", "return", "static", "super", "switch", "this",
        "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with",
        "yield", "from", "as", "implements", "interface", "package", "private",
        "protected", "public"
    )

    private val tsKeywords = jsKeywords + setOf(
        "type", "namespace", "declare", "abstract", "readonly", "keyof",
        "is", "asserts", "infer", "never", "unknown", "any", "void"
    )

    private val cssProperties = setOf(
        "color", "background", "background-color", "font-size", "font-family",
        "margin", "padding", "border", "display", "position", "width", "height",
        "top", "left", "right", "bottom", "z-index", "opacity", "overflow",
        "flex", "grid", "align-items", "justify-content", "text-align",
        "box-shadow", "border-radius", "transition", "transform", "animation"
    )

    private val cssValues = setOf(
        "inherit", "initial", "unset", "none", "auto", "transparent",
        "block", "inline", "flex", "grid", "absolute", "relative", "fixed", "sticky"
    )

    override fun highlight(source: String): List<HighlightSpan> {
        return when (mode) {
            Mode.HTML -> highlightHtml(source)
            Mode.CSS -> highlightCss(source)
            Mode.JS -> highlightJsTs(source, jsKeywords)
            Mode.TS -> highlightJsTs(source, tsKeywords)
        }
    }

    private fun highlightHtml(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("<!--[\\s\\S]*?-->")
        val cdataPattern = Regex("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>")
        val tagOpenPattern = Regex("</?([\\w:.-]+)")
        val attrPattern = Regex("\\b([\\w:.-]+)\\s*=")
        val stringPattern = Regex("\"[^\"]*\"|'[^']*'")
        val entityPattern = Regex("&[\\w#]+;")
        val doctypePattern = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
        val piPattern = Regex("<\\?[\\s\\S]*?\\?>")

        val excludedRanges = mutableListOf<IntRange>()

        commentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        cdataPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }
        doctypePattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
        }
        piPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        tagOpenPattern.findAll(source).forEach { match ->
            if (!inExcluded(match.range)) {
                val start = match.range.first + 1
                val end = match.range.last + 1
                spans += HighlightSpan(start, end, StudioUi.palette.synTag)
            }
        }

        attrPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synAttr)
            }
        }

        entityPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synConst)
            }
        }

        return spans.sortedBy { it.start }
    }

    private fun highlightCss(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        val propertyPattern = Regex("\\b([\\w-]+)\\s*:")
        val valuePattern = Regex(":\\s*([^;{}]+)")
        val selectorPattern = Regex("([.#]?[\\w][\\w.#-]*)\\s*\\{")
        val numberPattern = Regex("\\b\\d+(\\.\\d+)?(px|em|rem|%|vh|vw|deg|s|ms)?\\b")
        val colorPattern = Regex("#[0-9A-Fa-f]{3,8}\\b")
        val atRulePattern = Regex("@\\w+")

        val excludedRanges = mutableListOf<IntRange>()

        commentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        atRulePattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        selectorPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synType)
            }
        }

        propertyPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                val color = if (nameGroup.value in cssProperties) StudioUi.palette.synAttr else StudioUi.palette.synTag
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, color)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        colorPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val valuePattern2 = Regex(":\\s*([\\w-]+)")
        valuePattern2.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                if (nameGroup.value in cssValues) {
                    spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synBoolean)
                }
            }
        }

        return spans.sortedBy { it.start }
    }

    private fun highlightJsTs(source: String, keywords: Set<String>): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val templatePattern = Regex("`(?:[^`\\\\]|\\\\.)*`")
        val stringDoublePattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val stringSinglePattern = Regex("'(?:[^'\\\\]|\\\\.)*'")
        val regexPattern = Regex("/(?:[^/\\\\]|\\\\.)+/[gimsuy]*")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*\\.?\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?)\\b")
        val annotationPattern = Regex("@\\w+")
        val classPattern = Regex("\\b(class|extends|implements)\\s+(\\w+)")
        val functionPattern = Regex("\\b(?:function\\s+|(?<=\\.)\\s*)(\\w+)\\s*\\(")
        val arrowPattern = Regex("\\b(\\w+)\\s*(=>|=\\s*\\()")
        val importPattern = Regex("^\\s*import\\s+.+", RegexOption.MULTILINE)
        val fromPattern = Regex("^\\s*export\\s+.+", RegexOption.MULTILINE)
        val constPattern = Regex("\\b(?:const|let|var)\\s+(\\w+)\\b")

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
        templatePattern.findAll(source).forEach {
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
        stringSinglePattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        annotationPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        regexPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        classPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synType, bold = true)
            }
        }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                if (nameGroup.value !in keywords) {
                    spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
                }
            }
        }

        arrowPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        constPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synField)
            }
        }

        importPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synImport)
            }
        }

        fromPattern.findAll(source).forEach {
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
                val color = when (it.value) {
                    "true", "false", "null", "undefined", "NaN", "Infinity" -> StudioUi.palette.synBoolean
                    "this", "super" -> StudioUi.palette.synOperator
                    "console", "document", "window", "Math", "JSON", "Promise", "Array", "Object" -> StudioUi.palette.synType
                    else -> StudioUi.palette.synKw
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
