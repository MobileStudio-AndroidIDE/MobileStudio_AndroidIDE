package com.aide.mobilestudio.buildtoolwindow

import java.io.File
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Build Output Parser - parses Gradle build output and extracts errors/warnings.
 */
class BuildOutputParser {

    data class ParsedIssue(
        val file: File?,
        val line: Int,
        val column: Int?,
        val message: String,
        val severity: Severity,
        val rawLine: String
    ) {
        enum class Severity { ERROR, WARNING, INFO }
    }

    private val errorPatterns = listOf(
        Pattern.compile("^(.*?):(\\d+):(\\d+):\\s*error:\\s*(.*)$"),
        Pattern.compile("^(.*?):(\\d+):\\s*error:\\s*(.*)$"),
        Pattern.compile("error:\\s*(.*)")
    )

    private val warningPatterns = listOf(
        Pattern.compile("^(.*?):(\\d+):(\\d+):\\s*warning:\\s*(.*)$"),
        Pattern.compile("^(.*?):(\\d+):\\s*warning:\\s*(.*)$"),
        Pattern.compile("warning:\\s*(.*)")
    )

    /**
     * Parses build output text and extracts issues.
     */
    fun parse(output: String): List<ParsedIssue> {
        val issues = mutableListOf<ParsedIssue>()
        val lines = output.lines()

        for (line in lines) {
            // Check error patterns
            for (pattern in errorPatterns) {
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    val file = matcher.group(1)
                    val lineNum = matcher.group(2).toIntOrNull() ?: 0
                    val message = if (matcher.groupCount() >= 4) matcher.group(4) else matcher.group(3)
                    if (fileExists(file)) {
                        issues.add(ParsedIssue(
                            file = resolvePath(file),
                            line = lineNum,
                            column = null,
                            message = message,
                            severity = ParsedIssue.Severity.ERROR,
                            rawLine = line
                        ))
                    }
                }
            }

            // Check warning patterns
            for (pattern in warningPatterns) {
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    val file = matcher.group(1)
                    val lineNum = matcher.group(2).toIntOrNull() ?: 0
                    val message = if (matcher.groupCount() >= 4) matcher.group(4) else matcher.group(3)
                    if (fileExists(file)) {
                        issues.add(ParsedIssue(
                            file = resolvePath(file),
                            line = lineNum,
                            column = null,
                            message = message,
                            severity = ParsedIssue.Severity.WARNING,
                            rawLine = line
                        ))
                    }
                }
            }
        }

        return issues
    }

    private fun fileExists(path: String): Boolean {
        return File(path).exists()
    }

    private fun resolvePath(path: String): File? {
        val file = File(path)
        return if (file.exists()) file else null
    }
}
