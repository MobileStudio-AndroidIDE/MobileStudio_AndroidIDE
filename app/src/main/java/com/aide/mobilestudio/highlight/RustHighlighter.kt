package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class RustHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
        "else", "enum", "extern", "false", "fn", "for", "if", "impl",
        "in", "let", "loop", "match", "mod", "move", "mut", "pub",
        "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while",
        "yield", "try", "macro_rules"
    )

    private val builtins = setOf(
        "String", "str", "Vec", "Box", "Rc", "Arc", "Option", "Result",
        "Some", "None", "Ok", "Err", "i8", "i16", "i32", "i64", "i128", "isize",
        "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "byte", "Copy", "Clone", "Debug",
        "Display", "Default", "Iterator", "IntoIterator", "From", "Into",
        "println", "print", "eprintln", "eprint", "format", "vec", "panic", "assert", "assert_eq", "assert_ne",
        "todo", "unimplemented", "unreachable"
    )

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val charPattern = Regex("'(?:[^'\\\\]|\\\\.)'")
        val lifetimePattern = Regex("'[a-zA-Z_]\\w*")
        val macroPattern = Regex("\\b(\\w+)!")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*\\.?\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?)[iuuf](?:8|16|32|64|128|size)?\\b")
        val annotationPattern = Regex("#\\[[\\s\\S]*?\\]")
        val classPattern = Regex("\\b(struct|enum|trait|type|impl)\\s+(\\w+)")
        val functionPattern = Regex("\\bfn\\s+(\\w+)\\s*\\(")
        val importPattern = Regex("^\\s*use\\s+[\\w:]+.*;", RegexOption.MULTILINE)

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
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        charPattern.findAll(source).forEach {
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

        lifetimePattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synGeneric)
            }
        }

        macroPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
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
                val color = when (it.value) {
                    "true", "false" -> StudioUi.palette.synBoolean
                    "self", "Self" -> StudioUi.palette.synOperator
                    "super", "crate", "mod" -> StudioUi.palette.synImport
                    else -> StudioUi.palette.synKw
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        val builtinPattern = Regex("\\b(${builtins.joinToString("|")})\\b")
        builtinPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                val color = when (it.value) {
                    "Some", "None", "Ok", "Err" -> StudioUi.palette.synBoolean
                    "println", "print", "eprintln", "eprint", "format", "vec", "panic" -> StudioUi.palette.synFunc
                    "assert", "assert_eq", "assert_ne" -> StudioUi.palette.synFunc
                    else -> StudioUi.palette.synType
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
