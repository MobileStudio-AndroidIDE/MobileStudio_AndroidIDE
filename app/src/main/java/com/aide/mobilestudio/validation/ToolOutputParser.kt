package com.aide.mobilestudio.validation

import java.io.File

/** One diagnostic line extracted from a tool's output. */
data class RawDiagnostic(
    val path: String?,
    val line: Int,
    val column: Int,
    val isError: Boolean,
    val message: String
)

/**
 * Parsers for the exact output formats the validation tools emit.
 * Formats verified against kotlinc 2.4 (K2JVMCompiler), javac 21 and aapt2 36.
 */
object ToolOutputParser {

    // kotlinc 2.x default renderer: "path:line:col: error: message"
    private val k2Pattern = Regex("""^(.+?):(\d+):(\d+): (error|warning): (.+)$""")

    // javac / aapt2 compile: "path:line: error: message" (no column)
    private val lineOnlyPattern = Regex("""^(.+?):(\d+): (error|warning): (.+)$""")

    // file-less aggregate: "error: failed linking file resources."
    private val aggregatePattern = Regex("""^(error|warning): (.+)$""")

    // caret line under a javac error: "        ^~~~~"
    private val caretPattern = Regex("""^[ ]*\^+~*$""")

    private val javacContinuation = Regex("""^\s+(symbol|location|required|found|actual|expected)\s*:.*$""")

    fun parseK2(output: String): List<RawDiagnostic> {
        val out = mutableListOf<RawDiagnostic>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val m = k2Pattern.matchEntire(line) ?: return@forEach
            out.add(
                RawDiagnostic(
                    path = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 0,
                    column = m.groupValues[3].toIntOrNull() ?: 0,
                    isError = m.groupValues[4] == "error",
                    message = m.groupValues[5].trim()
                )
            )
        }
        return out
    }

    fun parseJavac(output: String): List<RawDiagnostic> {
        val lines = output.lines()
        val out = mutableListOf<RawDiagnostic>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trimEnd('\r')
            val m = lineOnlyPattern.matchEntire(line)
            if (m == null || m.groupValues[3] != "error" && m.groupValues[3] != "warning") {
                i++
                continue
            }
            var column = 1
            // Look for source line + caret right after the header.
            if (i + 2 < lines.size &&
                caretPattern.matches(lines[i + 2].trimEnd('\r')) &&
                !lineOnlyPattern.matches(lines[i + 1].trimEnd('\r'))
            ) {
                val caret = lines[i + 2]
                val idx = caret.indexOf('^')
                if (idx >= 0) column = idx + 1
            }
            val message = StringBuilder(m.groupValues[4].trim())
            // Append javac's indented "symbol:/location:" detail lines.
            var j = i + 1
            var scanned = 0
            while (j < lines.size && scanned < 5) {
                val next = lines[j].trimEnd('\r')
                if (lineOnlyPattern.matches(next) || aggregatePattern.matches(next)) break
                if (javacContinuation.matches(next)) {
                    message.append(" | ").append(next.trim())
                }
                j++; scanned++
            }
            out.add(
                RawDiagnostic(
                    path = m.groupValues[1],
                    line = m.groupValues[2].toIntOrNull() ?: 0,
                    column = column,
                    isError = m.groupValues[3] == "error",
                    message = message.toString()
                )
            )
            i = j
        }
        return out
    }

    /**
     * aapt2 compile/link: per-file "path:line: error: msg" plus file-less
     * aggregates ("error: failed linking file resources.").
     * [isAggregateOk] filters aggregates when specific errors already exist.
     */
    fun parseAapt2(output: String): List<RawDiagnostic> {
        val perFile = mutableListOf<RawDiagnostic>()
        val aggregates = mutableListOf<RawDiagnostic>()
        output.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank()) return@forEach
            k2Pattern.matchEntire(line)?.let { m ->
                perFile.add(
                    RawDiagnostic(
                        path = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull() ?: 0,
                        column = 0,
                        isError = m.groupValues[4] == "error",
                        message = m.groupValues[5].trim()
                    )
                )
                return@forEach
            }
            lineOnlyPattern.matchEntire(line)?.let { m ->
                perFile.add(
                    RawDiagnostic(
                        path = m.groupValues[1],
                        line = m.groupValues[2].toIntOrNull() ?: 0,
                        column = 0,
                        isError = m.groupValues[3] == "error",
                        message = m.groupValues[4].trim()
                    )
                )
                return@forEach
            }
            aggregatePattern.matchEntire(line)?.let { m ->
                aggregates.add(
                    RawDiagnostic(
                        path = null,
                        line = 0,
                        column = 0,
                        isError = m.groupValues[1] == "error",
                        message = m.groupValues[2].trim()
                    )
                )
            }
        }
        return if (perFile.isNotEmpty()) {
            // Aggregates only carry value when nothing specific was reported.
            perFile
        } else {
            aggregates
        }
    }

    /** True when the tool died for infrastructure reasons rather than user code. */
    fun looksLikeToolFailure(output: String, parsedDiagnosticCount: Int): Boolean {
        if (parsedDiagnosticCount > 0) return false
        val markers = listOf(
            "Exception in thread",
            "NoClassDefFoundError",
            "ClassNotFoundException",
            "Unrecognized option",
            "Could not create the Java Virtual Machine",
            "Error: Could not find or load main class",
            "error: failed to start",
            "Unable to locate",
            "No such file or directory"
        )
        return markers.any { it in output }
    }

    fun firstLines(text: String, max: Int = 3): String =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .take(max).joinToString(" / ").take(400)

    fun resolvePath(path: String, projectRoot: File): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(projectRoot, path)
    }
}
