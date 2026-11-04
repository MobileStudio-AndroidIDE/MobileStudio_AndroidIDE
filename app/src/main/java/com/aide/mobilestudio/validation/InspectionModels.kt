package com.aide.mobilestudio.validation

import com.aide.mobilestudio.BuildProblem
import java.io.File

enum class InspectionSeverity { ERROR, WARNING, INFO, SUCCESS }

enum class InspectionCategory {
    PROJECT_STRUCTURE,
    KOTLIN,
    JAVA,
    MANIFEST,
    XML_RESOURCE,
    RESOURCE_LINK,
    GRADLE_PROPERTIES,
    SETTINGS_GRADLE,
    BUILD_GRADLE,
    TOOL
}

data class InspectionIssue(
    val severity: InspectionSeverity,
    val category: InspectionCategory,
    val file: File?,
    val line: Int,
    val column: Int,
    val message: String,
    val errorCode: String
) {
    /** Stable identity used for de-duplication across checks. */
    fun dedupeKey(): String =
        "${file?.absolutePath ?: ""}:$line:$column:${category.name}:$errorCode:$message"

    fun toBuildProblem(): BuildProblem = BuildProblem(
        file = file,
        line = line,
        column = column,
        severity = when (severity) {
            InspectionSeverity.ERROR -> BuildProblem.Severity.ERROR
            InspectionSeverity.WARNING -> BuildProblem.Severity.WARNING
            else -> BuildProblem.Severity.INFO
        },
        message = "[$errorCode] $message",
        raw = message
    )
}

enum class CheckStatus { OK, ISSUES, FAILED, SKIPPED, CANCELLED }

data class CheckResult(
    val id: String,
    val displayName: String,
    val progressLabel: String,
    val status: CheckStatus,
    val issues: List<InspectionIssue>,
    val durationMs: Long
)

data class InspectionReport(
    val projectRoot: File,
    val checks: List<CheckResult>,
    val issues: List<InspectionIssue>,
    val durationMs: Long,
    val cancelled: Boolean
) {
    val errorCount: Int get() = issues.count { it.severity == InspectionSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == InspectionSeverity.WARNING }
    val infoCount: Int get() = issues.count { it.severity == InspectionSeverity.INFO }

    fun toBuildProblems(): List<BuildProblem> = issues.map { it.toBuildProblem() }

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"project\":").append(jsonString(projectRoot.absolutePath)).append(',')
        sb.append("\"durationMs\":").append(durationMs).append(',')
        sb.append("\"cancelled\":").append(cancelled).append(',')
        sb.append("\"counts\":{\"error\":").append(errorCount)
            .append(",\"warning\":").append(warningCount)
            .append(",\"info\":").append(infoCount).append("},")
        sb.append("\"checks\":[")
        checks.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(jsonString(c.id))
                .append(",\"status\":").append(jsonString(c.status.name))
                .append(",\"issues\":").append(c.issues.size)
                .append(",\"durationMs\":").append(c.durationMs).append('}')
        }
        sb.append("],")
        sb.append("\"issues\":[")
        issues.forEachIndexed { i, issue ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"severity\":").append(jsonString(issue.severity.name)).append(',')
            sb.append("\"category\":").append(jsonString(issue.category.name)).append(',')
            sb.append("\"file\":").append(issue.file?.let { jsonString(it.absolutePath) } ?: "null").append(',')
            sb.append("\"line\":").append(issue.line).append(',')
            sb.append("\"column\":").append(issue.column).append(',')
            sb.append("\"errorCode\":").append(jsonString(issue.errorCode)).append(',')
            sb.append("\"message\":").append(jsonString(issue.message))
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    companion object {
        fun jsonString(value: String): String {
            val sb = StringBuilder(value.length + 8)
            sb.append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    else sb.append(ch)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
