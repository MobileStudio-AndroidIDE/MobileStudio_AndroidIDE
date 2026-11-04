package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class CStyleHighlighter(
    private val keywords: Set<String>,
    private val types: Set<String>
) : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val stringDoublePattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val charPattern = Regex("'(?:[^'\\\\]|\\\\.)'|'\\\\[xXuU][0-9A-Fa-f]+'|'\\\\.'")
        val preprocessorPattern = Regex("^\\s*#\\s*\\w+.*", RegexOption.MULTILINE)
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|0[oO][0-7]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)[uUlLfF]*\\b")
        val annotationPattern = Regex("__\\w+__|\\b(?:JNIEXPORT|JNICALL|JNI\\w+)\\b")

        val commentRanges = mutableListOf<IntRange>()
        commentBlockPattern.findAll(source).forEach {
            commentRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        commentLinePattern.findAll(source).forEach {
            commentRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inComment(range: IntRange): Boolean =
            commentRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringDoublePattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        charPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        preprocessorPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        annotationPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val typePattern = Regex("\\b(${types.joinToString("|")})\\b")
        typePattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synType)
            }
        }

        val keywordPattern = Regex("\\b(${keywords.joinToString("|")})\\b")
        keywordPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                val color = when (it.value) {
                    "true", "false", "NULL" -> StudioUi.palette.synBoolean
                    "const" -> StudioUi.palette.synConst
                    else -> StudioUi.palette.synKw
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
