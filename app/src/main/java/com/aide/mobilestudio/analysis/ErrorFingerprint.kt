package com.aide.mobilestudio.analysis

import com.aide.mobilestudio.BuildProblem
import java.io.File

data class ErrorFingerprint(
    val file: String,
    val line: Int,
    val column: Int,
    val normalizedMessage: String,
    val severity: String
) {
    companion object {
        fun fromBuildProblem(problem: BuildProblem, projectDir: File?): ErrorFingerprint {
            return ErrorFingerprint(
                file = problem.file?.let {
                    try {
                        it.relativeTo(projectDir!!).path
                    } catch (_: Exception) {
                        it.absolutePath
                    }
                } ?: "(unknown)",
                line = problem.line,
                column = problem.column,
                normalizedMessage = normalizeMessage(problem.message),
                severity = problem.severity.name
            )
        }

        private fun normalizeMessage(message: String): String {
            // Remove specific details like memory addresses, hash codes, or absolute paths
            return message.replace(Regex("0x[0-9a-fA-F]+"), "0x...")
                .replace(Regex("/data/user/0/[^/]+/"), "/app_data/")
        }
    }
}
