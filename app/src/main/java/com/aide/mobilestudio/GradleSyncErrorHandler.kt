package com.aide.mobilestudio

import android.util.Log
import java.io.File

/**
 * GradleSyncErrorHandler - Provides detailed error analysis and suggestions
 * When sync fails, provides actionable diagnostics like Android Studio
 */
class GradleSyncErrorHandler {

    companion object {
        private const val TAG = "GradleSyncErrorHandler"
    }

    data class SyncError(
        val phase: GradleSyncProgress.SyncPhase,
        val message: String,
        val details: String = "",
        val cause: String = "",
        val module: String = "",
        val file: String = "",
        val suggestions: List<String> = emptyList(),
        val exitCode: Int = -1
    ) {
        fun toDisplayString(): String = buildString {
            appendLine("Gradle Sync Failed")
            appendLine()
            if (module.isNotEmpty()) appendLine("Module: $module")
            if (file.isNotEmpty()) appendLine("File: $file")
            appendLine("Phase: ${phase.displayName}")
            appendLine()
            appendLine("Cause:")
            appendLine("  $message")
            if (details.isNotEmpty()) {
                appendLine()
                appendLine("Details:")
                details.lines().forEach { appendLine("  $it") }
            }
            if (suggestions.isNotEmpty()) {
                appendLine()
                appendLine("Possible fixes:")
                suggestions.forEach { appendLine("  - $it") }
            }
        }
    }

    /**
     * Analyze Gradle output and produce a structured SyncError
     */
    fun analyzeError(
        output: List<String>,
        exitCode: Int,
        phase: GradleSyncProgress.SyncPhase
    ): SyncError {
        val fullOutput = output.joinToString("\n")
        val lastLines = output.takeLast(50).joinToString("\n")

        // Detect specific error patterns
        val errorPatterns = listOf(
            ErrorPattern(
                Regex("""Could not resolve (.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Could not resolve: ${match.groupValues[1]}",
                    details = extractRelevantLines(output, match.value),
                    suggestions = listOf(
                        "Check your internet connection",
                        "Verify the dependency coordinates are correct",
                        "Check if the Maven repository is accessible",
                        "Try adding the dependency repository if missing"
                    )
                )
            },
            ErrorPattern(
                Regex("""Could not find (.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Could not find: ${match.groupValues[1]}",
                    suggestions = listOf(
                        "Check if the artifact exists in the configured repositories",
                        "Verify group, artifact, and version are correct",
                        "Check for typos in the dependency declaration"
                    )
                )
            },
            ErrorPattern(
                Regex("""SDK location not found.*Define location with (.+) in the local\.properties file""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Android SDK location not found",
                    suggestions = listOf(
                        "Install Android SDK via Tools > SDK Manager",
                        "Set ANDROID_HOME environment variable",
                        "Create local.properties with sdk.dir=/path/to/sdk"
                    )
                )
            },
            ErrorPattern(
                Regex("""Could not create service of type (.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Gradle service creation failed: ${match.groupValues[1]}",
                    suggestions = listOf(
                        "Try stopping Gradle daemon: ./gradlew --stop",
                        "Clear Gradle cache: rm -rf ~/.gradle/caches",
                        "Check available memory and increase Gradle heap"
                    )
                )
            },
            ErrorPattern(
                Regex("""Unsupported class file major version (\d+)""")
            ) { match ->
                val version = match.groupValues[1].toIntOrNull() ?: 0
                SyncError(
                    phase = phase,
                    message = "Unsupported Java class file version: $version",
                    suggestions = listOf(
                        "The project requires a newer JDK version",
                        "Current JDK may be too old for the AGP version",
                        "Try installing a newer JDK via Tools > JDK"
                    )
                )
            },
            ErrorPattern(
                Regex("""Gradle version (.+) is required.*current version is (.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Gradle version mismatch: project requires ${match.groupValues[1]}, current is ${match.groupValues[2]}",
                    suggestions = listOf(
                        "Update Gradle wrapper: ./gradlew wrapper --gradle-version ${match.groupValues[1]}",
                        "Or install the required Gradle version"
                    )
                )
            },
            ErrorPattern(
                Regex("""AGP version.+requires Gradle version (.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "AGP requires Gradle ${match.groupValues[1]} or higher",
                    suggestions = listOf(
                        "Update the Gradle wrapper version",
                        "Or downgrade the AGP version"
                    )
                )
            },
            ErrorPattern(
                Regex("""Build was configured to prefer settings (.+) over classpath""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Build configuration error",
                    suggestions = listOf(
                        "Check settings.gradle for incorrect configuration",
                        "Verify plugin management block"
                    )
                )
            },
            ErrorPattern(
                Regex("""Could not compile (.+\.gradle)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Compilation failed: ${match.groupValues[1]}",
                    file = match.groupValues[1],
                    details = extractRelevantLines(output, match.value),
                    suggestions = listOf(
                        "Check the build file for syntax errors",
                        "Verify all referenced plugins are applied",
                        "Check for missing dependencies"
                    )
                )
            },
            ErrorPattern(
                Regex("""Kotlin plugin version (.+) is incompatible""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Kotlin plugin version incompatibility: ${match.groupValues[1]}",
                    suggestions = listOf(
                        "Update Kotlin plugin version",
                        "Check AGP-Kotlin version compatibility matrix"
                    )
                )
            },
            ErrorPattern(
                Regex("""java\.lang\.OutOfMemoryError""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Out of memory during Gradle sync",
                    suggestions = listOf(
                        "Increase Gradle heap size in Settings > Build",
                        "Close other apps to free memory",
                        "Try using a smaller Gradle heap profile"
                    )
                )
            },
            ErrorPattern(
                Regex("""Timeout of (\d+) ms exceeded""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Gradle operation timed out after ${match.groupValues[1]}ms",
                    suggestions = listOf(
                        "Check internet connection speed",
                        "Try again - temporary network issue",
                        "Gradle daemon may be overloaded"
                    )
                )
            },
            ErrorPattern(
                Regex("""Could not download (.+)\.	templateUrl(.+)""")
            ) { match ->
                SyncError(
                    phase = phase,
                    message = "Failed to download: ${match.groupValues[1]}",
                    suggestions = listOf(
                        "Check internet connection",
                        "The artifact may not exist at the specified URL",
                        "Try using a mirror repository"
                    )
                )
            }
        )

        // Try to match error patterns
        for (pattern in errorPatterns) {
            val match = pattern.regex.find(lastLines)
            if (match != null) {
                val error = pattern.handler(match)
                Log.i(TAG, "Matched error pattern: ${error.message}")
                return error
            }
        }

        // Extract module from output
        val moduleMatch = Regex("""Project :(\w+)""").find(lastLines)
        val module = moduleMatch?.groupValues?.get(1) ?: ""

        // Extract file reference
        val fileMatch = Regex("""(build\.gradle(?:\.kts)?|settings\.gradle(?:\.kts)?)""").find(lastLines)
        val file = fileMatch?.groupValues?.get(1) ?: ""

        // Generic error
        return SyncError(
            phase = phase,
            message = extractErrorMessage(output),
            details = lastLines,
            module = module,
            file = file,
            exitCode = exitCode,
            suggestions = listOf(
                "Check the build output for detailed error information",
                "Try running './gradlew help --stacktrace' for more details",
                "Clean and rebuild: ./gradlew clean"
            )
        )
    }

    private fun extractErrorMessage(output: List<String>): String {
        // Find the most informative error line
        val errorLines = output.filter { line ->
            line.contains("error", ignoreCase = true) ||
            line.contains("failed", ignoreCase = true) ||
            line.contains("exception", ignoreCase = true) ||
            line.startsWith("e:")
        }
        return errorLines.lastOrNull()?.trim() ?: "Unknown error"
    }

    private fun extractRelevantLines(output: List<String>, trigger: String): String {
        val triggerIdx = output.indexOfFirst { it.contains(trigger) }
        if (triggerIdx < 0) return ""
        val start = (triggerIdx - 2).coerceAtLeast(0)
        val end = (triggerIdx + 10).coerceAtMost(output.size)
        return output.subList(start, end).joinToString("\n")
    }

    private data class ErrorPattern(
        val regex: Regex,
        val handler: (MatchResult) -> SyncError
    )
}
