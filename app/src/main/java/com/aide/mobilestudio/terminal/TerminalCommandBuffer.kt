package com.aide.mobilestudio.terminal

/**
 * TerminalCommandBuffer
 *
 * Shell 입력을 실행하기 전에 관리하는 입력 버퍼.
 *
 * 이 클래스는 shell 명령을 "실행"하거나 완전한 shell parser 역할을 하지 않는다.
 * 사용자가 입력한 명령이 아직 계속 입력되어야 하는지 판단하고,
 * 여러 줄 입력을 하나의 command로 관리하는 역할을 한다.
 *
 * 지원:
 *
 *  1. '\' 줄 연속
 *  2. 여러 줄 command buffer
 *  3. single quote 상태
 *  4. double quote 상태
 *  5. escape 상태
 *  6. (), [], {} balance
 *  7. heredoc <<EOF
 *  8. heredoc <<-EOF
 *  9. heredoc delimiter 관리
 * 10. continuation prompt
 * 11. command cancel
 * 12. command clear
 * 13. 현재 buffer 조회
 * 14. 현재 line 조회
 * 15. command preview
 * 16. command normalization
 * 17. shell syntax 상태 분석
 * 18. debug state
 *
 * 실제 shell 문법 해석은 Bash/sh에게 맡긴다.
 */
class TerminalCommandBuffer {

    companion object {

        const val DEFAULT_CONTINUATION_PROMPT = "> "

        const val DEFAULT_HEREDOC_PROMPT = "heredoc> "

        private const val BACKSLASH = '\\'

        private const val SINGLE_QUOTE = '\''

        private const val DOUBLE_QUOTE = '"'

        private const val OPEN_PAREN = '('
        private const val CLOSE_PAREN = ')'

        private const val OPEN_BRACKET = '['
        private const val CLOSE_BRACKET = ']'

        private const val OPEN_BRACE = '{'
        private const val CLOSE_BRACE = '}'

        private const val HEREDOC_OPERATOR = "<<"
    }

    /**
     * Current command lines.
     */
    private val lines = mutableListOf<String>()

    /**
     * Current quote state.
     */
    private var singleQuoteOpen = false

    private var doubleQuoteOpen = false

    /**
     * Whether previous character was an escape.
     */
    private var escaped = false

    /**
     * Parentheses / brackets / braces depth.
     */
    private var parenDepth = 0

    private var bracketDepth = 0

    private var braceDepth = 0

    /**
     * Heredoc state.
     */
    private var heredocDelimiter: String? = null

    private var heredocStripTabs = false

    /**
     * Whether command explicitly ended with '\'.
     */
    private var explicitContinuation = false

    /**
     * Current continuation prompt.
     */
    var continuationPrompt: String = DEFAULT_CONTINUATION_PROMPT
        private set

    /**
     * Heredoc prompt.
     */
    var heredocPrompt: String = DEFAULT_HEREDOC_PROMPT
        private set

    /**
     * Whether the command is currently waiting for more input.
     */
    val isContinuation: Boolean
        get() = needsMoreInput()

    /**
     * Whether currently inside heredoc.
     */
    val isHeredoc: Boolean
        get() = heredocDelimiter != null

    /**
     * Current heredoc delimiter.
     */
    val currentHeredocDelimiter: String?
        get() = heredocDelimiter

    /**
     * Number of buffered lines.
     */
    val lineCount: Int
        get() = lines.size

    /**
     * Current nesting depth.
     */
    val nestingDepth: Int
        get() = parenDepth + bracketDepth + braceDepth

    /**
     * Result of accepting one line.
     */
    data class Result(
        val command: String?,
        val isComplete: Boolean,
        val isContinuation: Boolean,
        val isHeredoc: Boolean,
        val prompt: String,
        val reason: ContinuationReason,
        val lineCount: Int
    )

    /**
     * Why more input is required.
     */
    enum class ContinuationReason {
        NONE,
        BACKSLASH,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        PARENTHESIS,
        BRACKET,
        BRACE,
        HEREDOC,
        SHELL_SYNTAX
    }

    /**
     * Complete snapshot of the current parser state.
     */
    data class State(
        val isContinuation: Boolean,
        val isHeredoc: Boolean,
        val heredocDelimiter: String?,
        val singleQuoteOpen: Boolean,
        val doubleQuoteOpen: Boolean,
        val escaped: Boolean,
        val parenDepth: Int,
        val bracketDepth: Int,
        val braceDepth: Int,
        val explicitContinuation: Boolean,
        val lineCount: Int,
        val reason: ContinuationReason
    )

    /**
     * Accept one complete terminal input line.
     *
     * The caller normally passes a line WITHOUT the final '\n'.
     */
    fun accept(input: String): Result {

        val line = normalizeInputLine(input)

        /*
         * Heredoc has special handling.
         */
        if (heredocDelimiter != null) {
            return acceptHeredocLine(line)
        }

        /*
         * Empty line when no command is pending.
         */
        if (line.isEmpty() && lines.isEmpty()) {
            return Result(
                command = "",
                isComplete = true,
                isContinuation = false,
                isHeredoc = false,
                prompt = "",
                reason = ContinuationReason.NONE,
                lineCount = 0
            )
        }

        /*
         * Add line to buffer.
         */
        lines += line

        /*
         * Analyze shell syntax.
         */
        analyzeLine(line)

        /*
         * Detect heredoc after parsing operators.
         */
        detectHeredoc(line)

        /*
         * Heredoc has priority.
         */
        if (heredocDelimiter != null) {
            return Result(
                command = null,
                isComplete = false,
                isContinuation = true,
                isHeredoc = true,
                prompt = heredocPrompt,
                reason = ContinuationReason.HEREDOC,
                lineCount = lines.size
            )
        }

        /*
         * Explicit '\' continuation.
         */
        if (explicitContinuation) {
            return Result(
                command = null,
                isComplete = false,
                isContinuation = true,
                isHeredoc = false,
                prompt = continuationPrompt,
                reason = ContinuationReason.BACKSLASH,
                lineCount = lines.size
            )
        }

        /*
         * Open quotes.
         */
        if (singleQuoteOpen) {
            return Result(
                command = null,
                isComplete = false,
                isContinuation = true,
                isHeredoc = false,
                prompt = continuationPrompt,
                reason = ContinuationReason.SINGLE_QUOTE,
                lineCount = lines.size
            )
        }

        if (doubleQuoteOpen) {
            return Result(
                command = null,
                isComplete = false,
                isContinuation = true,
                isHeredoc = false,
                prompt = continuationPrompt,
                reason = ContinuationReason.DOUBLE_QUOTE,
                lineCount = lines.size
            )
        }

        /*
         * Unclosed nesting.
         */
        if (parenDepth > 0) {
            return continuationResult(
                ContinuationReason.PARENTHESIS
            )
        }

        if (bracketDepth > 0) {
            return continuationResult(
                ContinuationReason.BRACKET
            )
        }

        if (braceDepth > 0) {
            return continuationResult(
                ContinuationReason.BRACE
            )
        }

        /*
         * Command is complete.
         */
        val command = buildCommand()

        clear()

        return Result(
            command = command,
            isComplete = true,
            isContinuation = false,
            isHeredoc = false,
            prompt = "",
            reason = ContinuationReason.NONE,
            lineCount = 0
        )
    }

    /**
     * Process a heredoc line.
     */
    private fun acceptHeredocLine(line: String): Result {

        lines += line

        val delimiter = heredocDelimiter ?: ""

        val comparisonLine =
            if (heredocStripTabs) {
                line.trimStart('\t')
            } else {
                line
            }

        /*
         * Heredoc ends when delimiter is found.
         */
        if (comparisonLine == delimiter) {

            heredocDelimiter = null
            heredocStripTabs = false

            /*
             * There may still be another heredoc
             * operator in the command.
             */
            detectAdditionalHeredoc()

            if (heredocDelimiter != null) {
                return Result(
                    command = null,
                    isComplete = false,
                    isContinuation = true,
                    isHeredoc = true,
                    prompt = heredocPrompt,
                    reason = ContinuationReason.HEREDOC,
                    lineCount = lines.size
                )
            }

            /*
             * Check normal shell syntax after heredoc.
             */
            if (singleQuoteOpen ||
                doubleQuoteOpen ||
                parenDepth > 0 ||
                bracketDepth > 0 ||
                braceDepth > 0
            ) {
                return Result(
                    command = null,
                    isComplete = false,
                    isContinuation = true,
                    isHeredoc = false,
                    prompt = continuationPrompt,
                    reason = currentContinuationReason(),
                    lineCount = lines.size
                )
            }

            val command = buildCommand()

            clear()

            return Result(
                command = command,
                isComplete = true,
                isContinuation = false,
                isHeredoc = false,
                prompt = "",
                reason = ContinuationReason.NONE,
                lineCount = 0
            )
        }

        return Result(
            command = null,
            isComplete = false,
            isContinuation = true,
            isHeredoc = true,
            prompt = heredocPrompt,
            reason = ContinuationReason.HEREDOC,
            lineCount = lines.size
        )
    }

    /**
     * Analyze one shell line.
     *
     * This is deliberately not a full shell parser.
     */
    private fun analyzeLine(line: String) {

        var index = 0

        var localEscaped = false

        while (index < line.length) {

            val c = line[index]

            /*
             * Escape handling.
             */
            if (localEscaped) {
                localEscaped = false
                index++
                continue
            }

            if (c == BACKSLASH) {

                /*
                 * Inside single quotes Bash does not treat
                 * backslash as a normal escape character.
                 */
                if (!singleQuoteOpen) {
                    localEscaped = true
                }

                index++
                continue
            }

            /*
             * Single quote.
             */
            if (c == SINGLE_QUOTE && !doubleQuoteOpen) {
                singleQuoteOpen = !singleQuoteOpen
                index++
                continue
            }

            /*
             * Double quote.
             */
            if (c == DOUBLE_QUOTE && !singleQuoteOpen) {
                doubleQuoteOpen = !doubleQuoteOpen
                index++
                continue
            }

            /*
             * Inside quotes, do not process brackets.
             */
            if (singleQuoteOpen || doubleQuoteOpen) {
                index++
                continue
            }

            /*
             * Parentheses.
             */
            when (c) {

                OPEN_PAREN -> {
                    parenDepth++
                }

                CLOSE_PAREN -> {
                    if (parenDepth > 0) {
                        parenDepth--
                    }
                }

                OPEN_BRACKET -> {
                    bracketDepth++
                }

                CLOSE_BRACKET -> {
                    if (bracketDepth > 0) {
                        bracketDepth--
                    }
                }

                OPEN_BRACE -> {
                    braceDepth++
                }

                CLOSE_BRACE -> {
                    if (braceDepth > 0) {
                        braceDepth--
                    }
                }
            }

            index++
        }

        escaped = localEscaped

        /*
         * A trailing backslash outside single quotes
         * means explicit continuation.
         */
        explicitContinuation =
            hasTrailingContinuationMarker(line)

        /*
         * If the line does not end in '\',
         * an escape state caused by an ordinary
         * backslash inside the line should not force
         * a continuation by itself.
         */
        if (!explicitContinuation) {
            escaped = false
        }
    }

    /**
     * Detect heredoc operators.
     *
     * Supported examples:
     *
     *   <<EOF
     *   << EOF
     *   <<-EOF
     *   << 'EOF'
     *   << "EOF"
     */
    private fun detectHeredoc(line: String) {

        if (singleQuoteOpen || doubleQuoteOpen) {
            return
        }

        val heredoc = findHeredocOperator(line) ?: return

        heredocDelimiter = heredoc.delimiter

        heredocStripTabs = heredoc.stripTabs
    }

    /**
     * Search a line for the first heredoc operator.
     */
    private fun findHeredocOperator(
        line: String
    ): HeredocInfo? {

        var index = 0

        var single = false
        var double = false
        var escapedLocal = false

        while (index < line.length - 1) {

            val c = line[index]

            if (escapedLocal) {
                escapedLocal = false
                index++
                continue
            }

            if (c == BACKSLASH && !single) {
                escapedLocal = true
                index++
                continue
            }

            if (c == SINGLE_QUOTE && !double) {
                single = !single
                index++
                continue
            }

            if (c == DOUBLE_QUOTE && !single) {
                double = !double
                index++
                continue
            }

            if (!single && !double) {

                if (line[index] == '<' &&
                    line[index + 1] == '<'
                ) {

                    var cursor = index + 2

                    var stripTabs = false

                    if (cursor < line.length &&
                        line[cursor] == '-'
                    ) {
                        stripTabs = true
                        cursor++
                    }

                    /*
                     * Skip spaces.
                     */
                    while (
                        cursor < line.length &&
                        line[cursor].isWhitespace()
                    ) {
                        cursor++
                    }

                    if (cursor >= line.length) {
                        return null
                    }

                    val delimiterStart = cursor

                    /*
                     * Quoted delimiter.
                     */
                    if (
                        line[cursor] == '\'' ||
                        line[cursor] == '"'
                    ) {

                        val quote = line[cursor]

                        cursor++

                        val quotedStart = cursor

                        while (
                            cursor < line.length &&
                            line[cursor] != quote
                        ) {
                            cursor++
                        }

                        if (cursor <= quotedStart) {
                            return null
                        }

                        val delimiter =
                            line.substring(
                                quotedStart,
                                cursor
                            )

                        return HeredocInfo(
                            delimiter = delimiter,
                            stripTabs = stripTabs
                        )
                    }

                    /*
                     * Normal delimiter.
                     *
                     * Shell delimiters normally stop
                     * at whitespace.
                     */
                    while (
                        cursor < line.length &&
                        !line[cursor].isWhitespace() &&
                        line[cursor] != ';'
                    ) {
                        cursor++
                    }

                    if (cursor > delimiterStart) {

                        val delimiter =
                            line.substring(
                                delimiterStart,
                                cursor
                            )

                        return HeredocInfo(
                            delimiter = delimiter,
                            stripTabs = stripTabs
                        )
                    }
                }
            }

            index++
        }

        return null
    }

    /**
     * Find another heredoc after one has ended.
     */
    private fun detectAdditionalHeredoc() {

        /*
         * Only inspect command lines.
         *
         * The actual shell will handle ordering.
         * We only need to know whether more input
         * should be collected.
         */
        for (line in lines) {

            val info = findHeredocOperator(line)

            if (info != null) {

                /*
                 * If this delimiter is still represented
                 * by an unmatched heredoc section, keep it.
                 *
                 * This is intentionally conservative.
                 */
                if (info.delimiter != heredocDelimiter) {
                    heredocDelimiter = info.delimiter
                    heredocStripTabs = info.stripTabs
                    return
                }
            }
        }
    }

    /**
     * Detect a trailing '\' continuation.
     */
    private fun hasTrailingContinuationMarker(
        line: String
    ): Boolean {

        if (line.isEmpty()) return false

        /*
         * Ignore trailing whitespace when determining
         * whether '\' is the final meaningful character.
         */
        var index = line.length - 1

        while (
            index >= 0 &&
            line[index].isWhitespace()
        ) {
            index--
        }

        if (index < 0) return false

        var slashCount = 0

        while (
            index >= 0 &&
            line[index] == BACKSLASH
        ) {
            slashCount++
            index--
        }

        /*
         * Odd number of backslashes:
         *
         *   \     -> continuation
         *   \\    -> literal slash
         *   \\\   -> continuation
         */
        return slashCount % 2 == 1 &&
                !singleQuoteOpen
    }

    /**
     * Create a continuation result.
     */
    private fun continuationResult(
        reason: ContinuationReason
    ): Result {

        return Result(
            command = null,
            isComplete = false,
            isContinuation = true,
            isHeredoc = false,
            prompt = continuationPrompt,
            reason = reason,
            lineCount = lines.size
        )
    }

    /**
     * Determine the current continuation reason.
     */
    private fun currentContinuationReason():
        ContinuationReason {

        if (heredocDelimiter != null) {
            return ContinuationReason.HEREDOC
        }

        if (explicitContinuation) {
            return ContinuationReason.BACKSLASH
        }

        if (singleQuoteOpen) {
            return ContinuationReason.SINGLE_QUOTE
        }

        if (doubleQuoteOpen) {
            return ContinuationReason.DOUBLE_QUOTE
        }

        if (parenDepth > 0) {
            return ContinuationReason.PARENTHESIS
        }

        if (bracketDepth > 0) {
            return ContinuationReason.BRACKET
        }

        if (braceDepth > 0) {
            return ContinuationReason.BRACE
        }

        return ContinuationReason.SHELL_SYNTAX
    }

    /**
     * Build complete command.
     *
     * Newlines are intentionally preserved.
     *
     * This lets Bash interpret:
     *
     *   if true; then
     *       echo hello
     *   fi
     *
     * correctly.
     */
    private fun buildCommand(): String {

        if (lines.isEmpty()) {
            return ""
        }

        return lines.joinToString("\n")
    }

    /**
     * Get current buffer without clearing it.
     */
    fun currentBuffer(): String {
        return lines.joinToString("\n")
    }

    /**
     * Get immutable copy of current lines.
     */
    fun currentLines(): List<String> {
        return lines.toList()
    }

    /**
     * Get the last entered line.
     */
    fun currentLine(): String? {
        return lines.lastOrNull()
    }

    /**
     * Get a preview suitable for logging/UI.
     */
    fun preview(maxLength: Int = 200): String {

        val value = currentBuffer()

        if (value.length <= maxLength) {
            return value
        }

        return value.take(maxLength) + "…"
    }

    /**
     * Cancel current command.
     *
     * Returns discarded text.
     */
    fun cancel(): String {

        val discarded = currentBuffer()

        clear()

        return discarded
    }

    /**
     * Clear all state.
     */
    fun clear() {

        lines.clear()

        singleQuoteOpen = false
        doubleQuoteOpen = false
        escaped = false

        parenDepth = 0
        bracketDepth = 0
        braceDepth = 0

        heredocDelimiter = null
        heredocStripTabs = false

        explicitContinuation = false
    }

    /**
     * Whether there is pending input.
     */
    fun hasPendingInput(): Boolean {
        return lines.isNotEmpty()
    }

    /**
     * Whether more input is required.
     */
    fun needsMoreInput(): Boolean {

        return explicitContinuation ||
                singleQuoteOpen ||
                doubleQuoteOpen ||
                parenDepth > 0 ||
                bracketDepth > 0 ||
                braceDepth > 0 ||
                heredocDelimiter != null
    }

    /**
     * Change continuation prompt.
     */
    fun setContinuationPrompt(prompt: String) {
        continuationPrompt = prompt
    }

    /**
     * Change heredoc prompt.
     */
    fun setHeredocPrompt(prompt: String) {
        heredocPrompt = prompt
    }

    /**
     * Normalize line endings.
     */
    fun normalizeInputLine(input: String): String {

        return input
            .replace("\r\n", "")
            .replace("\r", "")
            .removeSuffix("\n")
    }

    /**
     * Normalize a complete command.
     *
     * We intentionally don't collapse spaces or
     * remove newlines because shell syntax depends
     * on them.
     */
    fun normalize(command: String): String {

        return command
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }

    /**
     * Return parser state.
     */
    fun getState(): State {

        return State(
            isContinuation = needsMoreInput(),
            isHeredoc = heredocDelimiter != null,
            heredocDelimiter = heredocDelimiter,
            singleQuoteOpen = singleQuoteOpen,
            doubleQuoteOpen = doubleQuoteOpen,
            escaped = escaped,
            parenDepth = parenDepth,
            bracketDepth = bracketDepth,
            braceDepth = braceDepth,
            explicitContinuation = explicitContinuation,
            lineCount = lines.size,
            reason = currentContinuationReason()
        )
    }

    /**
     * Debug representation.
     */
    fun debugState(): String {

        val state = getState()

        return buildString {

            append("TerminalCommandBuffer(")

            append("lines=")
            append(state.lineCount)

            append(", continuation=")
            append(state.isContinuation)

            append(", reason=")
            append(state.reason)

            append(", heredoc=")
            append(state.isHeredoc)

            if (state.heredocDelimiter != null) {
                append(":")
                append(state.heredocDelimiter)
            }

            append(", singleQuote=")
            append(state.singleQuoteOpen)

            append(", doubleQuote=")
            append(state.doubleQuoteOpen)

            append(", paren=")
            append(state.parenDepth)

            append(", bracket=")
            append(state.bracketDepth)

            append(", brace=")
            append(state.braceDepth)

            append(", buffer=\"")
            append(
                preview()
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
            )
            append("\"")

            append(")")
        }
    }

    /**
     * Internal heredoc information.
     */
    private data class HeredocInfo(
        val delimiter: String,
        val stripTabs: Boolean
    )
}