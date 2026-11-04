package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class YamlHighlighter : LanguageHighlighter {

    private val booleans = setOf("true", "false", "yes", "no", "on", "off")
    private val nullValues = setOf("null", "~")

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("#[^\n]*")
        val keyPattern = Regex("^(\\s*)([\\w][\\w. -]*)(\\s*):", RegexOption.MULTILINE)
        val stringDoublePattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val stringSinglePattern = Regex("'(?:[^'\\\\]|\\\\.)*'")
        val numberPattern = Regex("[\\s,][-]?\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)\\b")
        val anchorPattern = Regex("&\\w+")
        val aliasPattern = Regex("\\*\\w+")
        val tagPattern = Regex("!\\w+")
        val dashPattern = Regex("^\\s*-\\s", RegexOption.MULTILINE)

        val excludedRanges = mutableListOf<IntRange>()

        commentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
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

        anchorPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        aliasPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        tagPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synType)
            }
        }

        keyPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synTag)
            }
        }

        dashPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synOperator)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val booleanPattern = Regex("\\b(${booleans.joinToString("|")})\\b")
        booleanPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synBoolean)
            }
        }

        val nullPattern = Regex("\\b(${nullValues.joinToString("|")})\\b")
        nullPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synBoolean)
            }
        }

        return spans.sortedBy { it.start }
    }
}
