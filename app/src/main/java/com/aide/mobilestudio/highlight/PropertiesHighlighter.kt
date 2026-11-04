package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class PropertiesHighlighter : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("^[#!][^\n]*", RegexOption.MULTILINE)
        val keyPattern = Regex("^([\\w._-]+)\\s*[=:]\\s*", RegexOption.MULTILINE)
        val valuePattern = Regex("([\\w._-]+)\\s*[=:]\\s*([^#\n]*)", RegexOption.MULTILINE)
        val escapePattern = Regex("\\\\[tnr\\\\\"'=: ]")
        val interpolationPattern = Regex("\\$\\{[^{}]*\\}|\\$[A-Za-z_]\\w*")

        val excludedRanges = mutableListOf<IntRange>()

        commentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        keyPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synTag)
            }
        }

        valuePattern.findAll(source).forEach { match ->
            val valueRange = match.groups[2]?.range ?: return@forEach
            if (!inExcluded(valueRange)) {
                val value = match.groups[2]?.value ?: ""
                val color = when {
                    value.trim().matches(Regex("true|false|yes|no|on|off", RegexOption.IGNORE_CASE)) -> StudioUi.palette.synBoolean
                    value.trim().matches(Regex("~|null")) -> StudioUi.palette.synBoolean
                    value.trim().matches(Regex("\\d+")) -> StudioUi.palette.synNum
                    value.trim().startsWith("\${") || value.trim().startsWith("$") -> StudioUi.palette.synAnnotation
                    else -> StudioUi.palette.synStr
                }
                spans += HighlightSpan(valueRange.first, valueRange.last + 1, color)
            }
        }

        escapePattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        return spans.sortedBy { it.start }
    }
}
