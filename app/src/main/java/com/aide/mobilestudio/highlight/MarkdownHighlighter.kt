package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class MarkdownHighlighter : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val codeBlockPattern = Regex("```[\\s\\S]*?```")
        val inlineCodePattern = Regex("`[^`]+`")
        val headerPattern = Regex("^#{1,6}\\s+.+", RegexOption.MULTILINE)
        val boldPattern = Regex("\\*\\*[^*]+\\*\\*|__[^_]+__")
        val italicPattern = Regex("(?<!\\*)\\*(?!\\*)[^*]+\\*(?!\\*)|(?<!_)_(?!_)[^_]+_(?!_)")
        val linkPattern = Regex("\\[([^]]+)]\\(([^)]+)\\)")
        val imagePattern = Regex("!\\[([^]]*?)]\\(([^)]+)\\)")
        val blockquotePattern = Regex("^>\\s+.+", RegexOption.MULTILINE)
        val hrPattern = Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE)
        val listPattern = Regex("^\\s*[-*+]\\s+|^[ \\t]*\\d+\\.\\s+", RegexOption.MULTILINE)

        codeBlockPattern.findAll(source).forEach {
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }
        inlineCodePattern.findAll(source).forEach {
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }

        val codeRanges = mutableListOf<IntRange>()
        codeBlockPattern.findAll(source).forEach { codeRanges += it.range }
        inlineCodePattern.findAll(source).forEach { codeRanges += it.range }

        fun inCode(range: IntRange): Boolean =
            codeRanges.any { it.first <= range.first && range.last <= it.last }

        headerPattern.findAll(source).forEach {
            if (!inCode(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synFunc, bold = true)
            }
        }

        boldPattern.findAll(source).forEach {
            if (!inCode(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synIdent, bold = true)
            }
        }

        italicPattern.findAll(source).forEach {
            if (!inCode(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synIdent)
            }
        }

        imagePattern.findAll(source).forEach { match ->
            if (!inCode(match.range)) {
                spans += HighlightSpan(match.range.first, match.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }
        linkPattern.findAll(source).forEach { match ->
            if (!inCode(match.range)) {
                val textGroup = match.groups[1] ?: return@forEach
                val urlGroup = match.groups[2] ?: return@forEach
                spans += HighlightSpan(textGroup.range.first, textGroup.range.last + 1, StudioUi.palette.synFunc)
                spans += HighlightSpan(urlGroup.range.first, urlGroup.range.last + 1, StudioUi.palette.synStr)
            }
        }

        blockquotePattern.findAll(source).forEach {
            if (!inCode(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
            }
        }

        listPattern.findAll(source).forEach {
            if (!inCode(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synOperator)
            }
        }

        return spans.sortedBy { it.start }
    }
}
