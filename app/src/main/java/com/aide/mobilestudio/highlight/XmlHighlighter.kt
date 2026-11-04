package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class XmlHighlighter : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("<!--[\\s\\S]*?-->")
        val cdataPattern = Regex("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>")
        val tagOpenPattern = Regex("</?([\\w:.-]+)")
        val attrPattern = Regex("\\b([\\w:.-]+)\\s*=")
        val stringPattern = Regex("\"[^\"]*\"|'[^']*'")
        val entityPattern = Regex("&[\\w#]+;")
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
        piPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        val stringRanges = mutableListOf<IntRange>()
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) stringRanges += it.range
        }
        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        tagOpenPattern.findAll(source).forEach { match ->
            if (!inExcluded(match.range)) {
                val start = if (match.value.startsWith("</")) match.range.first + 1 else match.range.first + 1
                val nameEnd = match.range.last + 1
                spans += HighlightSpan(start, nameEnd, StudioUi.palette.synTag)
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
}
