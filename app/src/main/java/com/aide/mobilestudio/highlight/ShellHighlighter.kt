package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class ShellHighlighter : LanguageHighlighter {

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentPattern = Regex("#[^\\n]*")
        val stringDoublePattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val stringSinglePattern = Regex("'(?:[^'\\\\]|\\\\.)*'")
        val variablePattern = Regex("\\$\\{[^{}]*\\}|\\$[A-Za-z_]\\w*|\\$\\d+|\\$\\{\\!\\w+:?.*\\}")
        val operatorPattern = Regex("\\|\\||&&|\\|>&?|&>|>>?|<<?|2>&1|\\|")
        val numberPattern = Regex("\\b\\d+\\b")
        val keywordPattern = Regex("\\b(if|then|else|elif|fi|for|while|do|done|case|esac|in|function|select|until|return|exit|export|source|alias|unalias|set|unset|local|declare|readonly|typeset|eval|exec|shift|trap|wait|kill|true|false|test)\\b")

        val commentRanges = mutableListOf<IntRange>()
        commentPattern.findAll(source).forEach {
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
        stringSinglePattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        variablePattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synField)
            }
        }

        keywordPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synKw)
            }
        }

        operatorPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synOperator)
            }
        }

        return spans.sortedBy { it.start }
    }
}
