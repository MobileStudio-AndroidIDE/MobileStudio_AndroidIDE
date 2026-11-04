package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

/**
 * Dedicated GDScript highlighter (Godot-style) — Python-like indentation syntax
 * with GD-specific keywords, game API functions, signals and node paths.
 */
class GdHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "func", "var", "const", "extends", "if", "elif", "else", "for", "while",
        "return", "match", "break", "continue", "pass", "class_name", "signal",
        "enum", "export", "self", "and", "or", "not", "in", "is", "as", "await",
        "static", "yield", "assert", "preload", "breakpoint", "tool", "void",
        "when", "super", "namespace", "trait", "interface"
    )

    private val constants = setOf("true", "false", "null", "PI", "TAU", "TAU", "INF", "NAN")

    private val builtins = setOf(
        "print", "str", "int", "float", "bool", "abs", "min", "max", "clamp",
        "lerp", "range", "len", "sqrt", "pow", "sin", "cos", "tan", "randf",
        "randi", "rand_range", "seed", "typeof", "typeof", "is_instance_valid",
        "weakref", "funcref", "instance_from_id", "hash", "deg_to_rad", "rad_to_deg",
        "_ready", "_process", "_input", "_physics_process", "_init", "_enter_tree",
        "_exit_tree", "on_touch"
    )

    private val gameApi = setOf(
        "draw_rect", "draw_circle", "draw_text", "clear",
        "screen_width", "screen_height",
        "get_node", "get_parent", "get_child", "add_child", "remove_child",
        "queue_free", "emit_signal", "connect", "set_position", "get_position",
        "set_rotation", "set_scale", "move_and_slide", "look_at", "distance_to"
    )

    private val operators = setOf(
        "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "**=", "&=", "|=",
        "^=", "->", ":=", "~", "&&", "||", "<<", ">>"
    )

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()

        val commentPattern = Regex("#[^\n]*")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        val nodePathPattern = Regex("\\$[A-Za-z_][\\w/]*")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f_]+|0[bB][01_]+|\\d[\\d_]*\\.\\d[\\d_]*([eE][+-]?\\d[\\d_]*)?|\\.\\d[\\d_]*|\\d[\\d_]*[eE][+-]?\\d[\\d_]*)\\b")
        val functionPattern = Regex("\\bfunc\\s+(\\w+)\\s*\\(")
        val varPattern = Regex("\\b(?:var|const)\\s+(\\w+)\\b")
        val exportPattern = Regex("@export\\w*")

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

        // Node paths: $NodeName
        nodePathPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synLabel)
            }
        }

        // @export annotations
        exportPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        varPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synIdent)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val operatorPattern = Regex("(==|!=|<=|>=|\\+=|-=|\\*=|/=|%=|\\*\\*=|&=|\\|=|\\^=|:=|&&|\\|\\||<<|>>)")
        operatorPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synOperator)
            }
        }

        val gameApiPattern = Regex("\\b(${gameApi.joinToString("|")})\\b")
        gameApiPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation, bold = true)
            }
        }

        val builtinPattern = Regex("\\b(${builtins.joinToString("|")})\\b")
        builtinPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synType)
            }
        }

        val keywordPattern = Regex("\\b(${keywords.joinToString("|")})\\b")
        keywordPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synKw)
            }
        }

        val constPattern = Regex("\\b(${constants.joinToString("|")})\\b")
        constPattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synBoolean)
            }
        }

        return spans.sortedBy { it.start }
    }
}
