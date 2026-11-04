package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class JsonHighlighter : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/")
        val stringKeyPattern = Regex("(\"[^\"]*\")\\s*:")
        val stringValuePattern = Regex(":\\s*(\"[^\"]*\")")
        val stringStandalonePattern = Regex("(?<![:\\w])\\s*(\"[^\"]*\")")
        val numberPattern = Regex("(?<![\"])\\b-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")
        val boolNullPattern = Regex("\\b(true|false|null)\\b")
        val bracketPattern = Regex("[{}\\[\\]]")

        val commentRanges = mutableListOf<IntRange>()
        commentPattern.findAll(source).forEach {
            commentRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inComment(range: IntRange): Boolean =
            commentRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringKeyPattern.findAll(source).forEach { match ->
            val key = match.groups[1] ?: return@forEach
            if (!inComment(key.range)) {
                stringRanges += key.range
                spans += HighlightSpan(key.range.first, key.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        stringValuePattern.findAll(source).forEach { match ->
            val value = match.groups[1] ?: return@forEach
            if (!inComment(value.range)) {
                stringRanges += value.range
                spans += HighlightSpan(value.range.first, value.range.last + 1, StudioUi.palette.synStr)
            }
        }

        boolNullPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                val color = when (it.value) {
                    "null" -> StudioUi.palette.synBoolean
                    else -> StudioUi.palette.synBoolean
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        return spans.sortedBy { it.start }
    }
}
