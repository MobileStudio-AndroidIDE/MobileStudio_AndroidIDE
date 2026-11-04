package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

/**
 * Dedicated Lua highlighter — Lua-specific syntax:
 * `--` line comments, `--[[ ]]` block comments, `[[ ]]` / `[=[ ]]` long strings,
 * Lua stdlib modules and idioms with a distinct color scheme.
 */
class LuaHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "and", "break", "do", "else", "elseif", "end", "for", "function", "goto",
        "if", "in", "local", "not", "or", "repeat", "return", "then", "until", "while"
    )

    private val constants = setOf("nil", "true", "false", "self")

    private val stdlib = setOf(
        "print", "type", "tostring", "tonumber", "pairs", "ipairs", "next", "select",
        "pcall", "xpcall", "error", "assert", "require", "rawget", "rawset", "rawequal",
        "rawlen", "setmetatable", "getmetatable", "collectgarbage", "dofile", "load",
        "loadstring", "unpack", "version", "string", "table", "math", "io", "os",
        "coroutine", "debug", "utf8", "package", "bit32", "arg"
    )

    private val gameApi = setOf(
        "drawRect", "drawCircle", "drawText", "clear", "screenWidth", "screenHeight",
        "onTouch", "update", "begin3D", "camera", "drawCube", "drawPlane", "end3D",
        "getDeltaTime", "getTime", "isKeyDown", "isTouchDown"
    )

    /** love.* engine modules — distinct semantic category (LÖVE compatibility). */
    private val loveModules = setOf(
        "love", "love.graphics", "love.audio", "love.filesystem", "love.font",
        "love.image", "love.joystick", "love.keyboard", "love.math", "love.mouse",
        "love.physics", "love.sound", "love.system", "love.thread", "love.timer",
        "love.touch", "love.window", "love.data", "love.event",
        "display", "Runtime", "vmath", "stage", "application",
        "MOAISim", "MOAIViewport", "engine", "ecs"
    )

    private val operators = setOf(
        "==", "~=", "<=", ">=", "..", "+=", "-=", "*=", "/=", "%=", "^=", "//", "::"
    )

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()

        // Comments: -- line, --[[ ]] block
        val commentBlockPattern = Regex("--\\[\\[\\[\\s\\S]*?\\]\\]\\]|--\\[\\[\\s\\S]*?\\]\\]|--[^\n]*")
        val longStringPattern = Regex("\\[=\\[\\s\\S]*?\\]=]|\\[\\[\\s\\S]*?\\]\\]")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)\\b")
        val functionPattern = Regex("\\bfunction\\s+([\\w.:]+)")
        val fieldPattern = Regex("\\blocal\\s+(\\w+)\\b")

        val excludedRanges = mutableListOf<IntRange>()

        commentBlockPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inExcluded(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        longStringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        stringPattern.findAll(source).forEach {
            if (!inExcluded(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inExcluded(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        fieldPattern.findAll(source).forEach { match ->
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

        val operatorPattern = Regex("(==|~=|<=|>=|\\.\\.|\\+=|-=|\\*=|/=|%=|\\^=|//|::)")
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

        // love.* modules + other framework globals — distinct semantic color
        val lovePattern = Regex("\\b(love(?:\\.[a-z]+)?|display|Runtime|vmath|stage|application|MOAISim|MOAIViewport|engine)\\b")
        lovePattern.findAll(source).forEach {
            if (!inExcluded(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synConst, bold = true)
            }
        }

        val stdlibPattern = Regex("\\b(${stdlib.joinToString("|")})\\b")
        stdlibPattern.findAll(source).forEach {
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
