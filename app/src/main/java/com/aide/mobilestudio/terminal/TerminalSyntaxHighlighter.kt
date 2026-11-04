package com.aide.mobilestudio.terminal

import android.graphics.Color

/**
 * Extensible syntax highlighter for terminal input and output.
 * Tokenizes text and assigns colors based on content type.
 */
object TerminalSyntaxHighlighter {

    data class ColoredSegment(val text: String, val color: Int)

    // Colors (theme-aware via StudioUi.palette)
    val COMMAND_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termCommand       // Blue
    val OPTION_COLOR get() = com.aide.mobilestudio.StudioUi.palette.muted              // Gray
    val STRING_COLOR get() = com.aide.mobilestudio.StudioUi.palette.synStr             // Green
    val PATH_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termPath             // Cyan
    val NUMBER_COLOR get() = com.aide.mobilestudio.StudioUi.palette.synNum             // Purple
    val ERROR_COLOR get() = com.aide.mobilestudio.StudioUi.palette.error               // Red
    val SUCCESS_COLOR get() = com.aide.mobilestudio.StudioUi.palette.success           // Green
    val DEFAULT_COLOR get() = com.aide.mobilestudio.StudioUi.palette.text              // Light gray
    val GRADLE_TASK_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termGradleTask // Info blue
    val URL_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termUrl               // Info blue (with underline)
    val EXIT_CODE_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termExitCode     // Green/Red based on code
    val COMMENT_COLOR get() = com.aide.mobilestudio.StudioUi.palette.termComment       // Muted gray

    // Extensible command registry
    private val shellCommands = mutableSetOf(
        "ls", "cd", "grep", "find", "mkdir", "rm", "rmdir", "cat", "pwd",
        "which", "chmod", "cp", "mv", "echo", "touch", "ln", "head", "tail",
        "wc", "sort", "uniq", "diff", "sed", "awk", "tr", "cut", "xargs",
        "env", "export", "unset", "source", "bash", "sh", "zsh",
        "ps", "kill", "killall", "top", "free", "df", "du", "mount",
        "git", "curl", "wget", "tar", "unzip", "zip", "ssh", "scp",
        "node", "npm", "npx", "python", "python3", "pip", "pip3",
        "lua", "luac",
        "java", "javac", "jar", "javadoc",
        "clang", "clang++", "gcc", "g++", "ld", "ar", "objdump", "readelf", "nm",
        "cmake", "make", "ninja", "meson",
        "gradle", "gradlew",
        "adb", "fastboot", "aapt", "aapt2", "d8", "r8",
        "kotlin", "kotlinc",
        "rustc", "cargo",
        "go", "gofmt",
        "swift", "swiftc",
        "docker", "podman",
        "systemctl", "service",
        "chmod", "chown", "chgrp",
        "su", "sudo",
        "clear", "exit", "history",
        "true", "false", "test", "[",
        "if", "then", "else", "elif", "fi",
        "for", "while", "do", "done",
        "case", "esac",
        "function",
        "set", "unset", "shift",
        "return", "break", "continue",
        "eval", "exec",
        "read", "printf",
        "getopts", "shift",
        "trap", "wait",
        "bg", "fg", "jobs", "disown",
        "type", "builtin", "command",
        "hash", "alias", "unalias",
        "help", "man", "info",
        "tee", "nohup", "screen", "tmux",
        "strace", "ltrace", "ldd", "file",
        "dd", "mkfs", "fdisk", "mount", "umount",
        "ping", "ifconfig", "ip", "netstat", "ss", "nslookup", "dig",
        "iptables", "ip6tables",
        "crontab", "at",
        "date", "cal", "time", "timeout",
        "sleep", "usleep",
        "basename", "dirname", "realpath", "readlink",
        "stat", "lsblk", "lscpu", "lsusb", "lspci",
        "dmesg", "logcat",
        "pm", "am", "dumpsys", "input", "settings",
        "cmd", "cmdline",
        "dexdump", "profman",
        "sdkmanager", "avdmanager"
    )

    // Gradle task patterns
    private val gradleTaskPattern = "Task\\s+:\\w+:\\w+".toRegex()

    private val errorKeywords = setOf(
        "ERROR", "FAILED", "FATAL", "Exception", "Permission denied",
        "No such file or directory", "not found", "cannot find",
        "syntax error", "undefined reference", "unresolved reference",
        "compilation failed", "build failed", "exit=1", "exit=2",
        "signal 11", "SIGSEGV", "SIGABRT", "SIGILL",
        "OutOfMemoryError", "StackOverflowError", "NullPointerException"
    )

    private val successKeywords = setOf(
        "SUCCESS", "BUILD SUCCESSFUL", "exit=0", "BUILD SUCCESSFUL IN",
        "UP-TO-DATE", "NO-SOURCE", "Task :app:compileDebugKotlin UP-TO-DATE"
    )

    private val warningKeywords = setOf(
        "WARNING", "WARN", "deprecated", "deprecation"
    )

    fun registerCommands(commands: Collection<String>) {
        shellCommands.addAll(commands)
    }

    fun registerCommand(command: String) {
        shellCommands.add(command)
    }

    /**
     * Tokenize a user-typed input line with syntax highlighting.
     * E.g., `grep -r "error" ./src` → [COMMAND, OPTION, STRING, PATH]
     */
    fun highlightInput(line: String): List<ColoredSegment> {
        if (line.isEmpty()) return emptyList()

        val segments = mutableListOf<ColoredSegment>()
        var i = 0

        while (i < line.length) {
            // Skip whitespace
            if (line[i].isWhitespace()) {
                val start = i
                while (i < line.length && line[i].isWhitespace()) i++
                segments.add(ColoredSegment(line.substring(start, i), DEFAULT_COLOR))
                continue
            }

            // Comments (shell-style # comment)
            if (line[i] == '#') {
                val start = i
                while (i < line.length) i++
                segments.add(ColoredSegment(line.substring(start, i), COMMENT_COLOR))
                continue
            }

            // Quoted strings
            if (line[i] == '"' || line[i] == '\'' || line[i] == '`') {
                val quote = line[i]
                val start = i
                i++ // skip opening quote
                while (i < line.length && line[i] != quote) {
                    if (line[i] == '\\' && i + 1 < line.length) i++ // skip escaped char
                    i++
                }
                if (i < line.length) i++ // skip closing quote
                segments.add(ColoredSegment(line.substring(start, i), STRING_COLOR))
                continue
            }

            // Options/flags
            if (line[i] == '-' && i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] == '-')) {
                val start = i
                i++
                while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '-' || line[i] == '_')) i++
                segments.add(ColoredSegment(line.substring(start, i), OPTION_COLOR))
                continue
            }

            // Paths (contain / or start with . or ~)
            if (line[i] == '/' || line[i] == '.' ||
                (line[i] == '~' && (i + 1 >= line.length || line[i + 1] == '/'))) {
                val start = i
                while (i < line.length && !line[i].isWhitespace() && line[i] != '"' && line[i] != '\'') i++
                segments.add(ColoredSegment(line.substring(start, i), PATH_COLOR))
                continue
            }

            // Numbers
            if (line[i].isDigit()) {
                val start = i
                while (i < line.length && (line[i].isDigit() || "xXabcdefABCDEF".contains(line[i]))) i++
                segments.add(ColoredSegment(line.substring(start, i), NUMBER_COLOR))
                continue
            }

            // Words (could be commands or arguments)
            if (line[i].isLetter() || line[i] == '_') {
                val start = i
                while (i < line.length && !line[i].isWhitespace() && line[i] != '"' && line[i] != '\'') i++
                val word = line.substring(start, i)

                // Check if this is the first non-whitespace token (command position)
                val isCommandPos = segments.none { it.text.trim().isNotEmpty() && !it.text.all { c -> c.isWhitespace() } }
                // Also check if previous non-whitespace token is a shell operator
                val prevNonWhitespace = segments.lastOrNull { it.text.trim().isNotEmpty() }
                val afterOperator = prevNonWhitespace != null && prevNonWhitespace.text.trim() in setOf(";", "&&", "||", "|", ">", ">>", "<")

                val color = if ((isCommandPos || afterOperator) && word in shellCommands) {
                    COMMAND_COLOR
                } else {
                    DEFAULT_COLOR
                }
                segments.add(ColoredSegment(word, color))
                continue
            }

            // Operators and other characters
            val start = i
            i++
            segments.add(ColoredSegment(line.substring(start, i), DEFAULT_COLOR))
        }

        return segments
    }

    /**
     * Tokenize an output line for syntax highlighting.
     * Detects error/success/warning patterns, paths, URLs, exit codes, Gradle tasks, and numbers.
     */
    fun highlightOutput(line: String): List<ColoredSegment> {
        if (line.isEmpty()) return emptyList()

        // Fast path for build status lines
        if (line.contains("BUILD SUCCESSFUL") || line.contains("SUCCESS")) {
            return listOf(ColoredSegment(line, SUCCESS_COLOR))
        }
        if (line.contains("BUILD FAILED") || line.contains("ERROR") || line.contains("FATAL")) {
            return listOf(ColoredSegment(line, ERROR_COLOR))
        }

        val segments = mutableListOf<ColoredSegment>()
        var i = 0

        while (i < line.length) {
            // Check for multi-word error/success keywords first
            var foundKeyword = false
            for (keyword in errorKeywords) {
                if (line.startsWith(keyword, i, ignoreCase = true)) {
                    segments.add(ColoredSegment(line.substring(i, i + keyword.length), ERROR_COLOR))
                    i += keyword.length
                    foundKeyword = true
                    break
                }
            }
            if (foundKeyword) continue

            for (keyword in successKeywords) {
                if (line.startsWith(keyword, i, ignoreCase = true)) {
                    segments.add(ColoredSegment(line.substring(i, i + keyword.length), SUCCESS_COLOR))
                    i += keyword.length
                    foundKeyword = true
                    break
                }
            }
            if (foundKeyword) continue

            for (keyword in warningKeywords) {
                if (line.startsWith(keyword, i, ignoreCase = true)) {
                    segments.add(ColoredSegment(line.substring(i, i + keyword.length), ERROR_COLOR))
                    i += keyword.length
                    foundKeyword = true
                    break
                }
            }
            if (foundKeyword) continue

            // URLs (http:// or https://)
            if (line.startsWith("http://", i) || line.startsWith("https://", i)) {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                segments.add(ColoredSegment(line.substring(start, i), URL_COLOR))
                continue
            }

            // Gradle tasks (Task :app:compileDebugKotlin)
            if (line.startsWith("Task ", i) || line.startsWith("> Task ", i)) {
                val start = i
                while (i < line.length && !line[i].isWhitespace() && line[i] != '(') i++
                // Include the task name
                while (i < line.length && line[i] != '(' && !line[i].isWhitespace()) i++
                segments.add(ColoredSegment(line.substring(start, i), GRADLE_TASK_COLOR))
                continue
            }

            // Exit codes (exit=0, exit=1, etc.)
            if (line.startsWith("exit=", i) || line.startsWith("Exit code:", i)) {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) i++
                val exitCode = line.substring(start, i)
                val isError = exitCode.contains("exit=1") || exitCode.contains("exit=2") || exitCode.contains("Exit code: 1") || exitCode.contains("Exit code: 2")
                segments.add(ColoredSegment(exitCode, if (isError) ERROR_COLOR else SUCCESS_COLOR))
                continue
            }

            // Paths in output (often seen in compiler output)
            if (line[i] == '/' || line[i] == '.') {
                val start = i
                while (i < line.length && !line[i].isWhitespace() && line[i] != ':' && line[i] != '(' && line[i] != ')') i++
                val path = line.substring(start, i)
                if (path.contains('/') || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".cpp") || path.endsWith(".h") || path.endsWith(".xml") || path.endsWith(".gradle") || path.endsWith(".json") || path.endsWith(".c") || path.endsWith(".hpp")) {
                    segments.add(ColoredSegment(path, PATH_COLOR))
                    continue
                } else {
                    i = start // Backtrack if not a likely path
                }
            }

            // Numbers
            if (line[i].isDigit()) {
                val start = i
                while (i < line.length && (line[i].isDigit() || "xXabcdefABCDEF".contains(line[i]))) i++
                segments.add(ColoredSegment(line.substring(start, i), NUMBER_COLOR))
                continue
            }

            // Regular words or other characters
            val start = i
            while (i < line.length && !line[i].isWhitespace() && !line[i].isDigit() && line[i] != '/' && line[i] != '.') i++
            if (i == start) i++ // Ensure progress
            segments.add(ColoredSegment(line.substring(start, i), DEFAULT_COLOR))

            // Whitespace
            if (i < line.length && line[i].isWhitespace()) {
                val wsStart = i
                while (i < line.length && line[i].isWhitespace()) i++
                segments.add(ColoredSegment(line.substring(wsStart, i), DEFAULT_COLOR))
            }
        }

        return segments
    }
}