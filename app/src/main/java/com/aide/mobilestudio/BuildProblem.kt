package com.aide.mobilestudio

import java.io.File

data class BuildProblem(
    val file: File?,
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String,
    val raw: String
) {
    enum class Severity { ERROR, WARNING, INFO }

    fun displayPath(rootDir: File?): String {
        val f = file ?: return "(unknown)"
        return rootDir?.let {
            try {
                f.relativeTo(it).path
            } catch (_: Exception) {
                f.absolutePath
            }
        } ?: f.absolutePath
    }
}

object BuildProblemParser {
    fun parse(output: String, projectDir: File): List<BuildProblem> {
        if (isSuccessfulBuild(output)) return emptyList()

        val seen = HashSet<String>()
        val out = mutableListOf<BuildProblem>()

        output.lineSequence().forEach { line ->
            val problem = parseLine(line, projectDir) ?: return@forEach
            val key = "${problem.file?.absolutePath}:${problem.line}:${problem.column}:${problem.message}"
            if (seen.add(key)) out.add(problem)
        }

        return collapseClasspathStormIfNeeded(out, output).sortedWith(
            compareBy({ it.severity.ordinal }, { it.file?.absolutePath ?: "" }, { it.line }, { it.column })
        )
    }

    fun parseLine(line: String, projectDir: File): BuildProblem? {
        val trimmed = line.trimStart()

        kotlincParenPattern.matchEntire(trimmed)?.let { match ->
            val (severity, path, lineNumber, column, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                severity = kotlinSeverity(severity),
                message = message.trim(),
                raw = line
            )
        }

        kotlincColonPattern.matchEntire(trimmed)?.let { match ->
            val (severity, path, lineNumber, column, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                severity = kotlinSeverity(severity),
                message = message.trim().removePrefix(":").trim(),
                raw = line
            )
        }

        javacPattern.matchEntire(trimmed)?.let { match ->
            val (path, lineNumber, severity, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = 0,
                severity = severityToProblem(severity),
                message = message.trim(),
                raw = line
            )
        }

        androidXmlPattern.matchEntire(trimmed)?.let { match ->
            val (path, lineNumber, column, severity, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                severity = severityToProblem(severity),
                message = cleanupAndroidMessage(message),
                raw = line
            )
        }

        androidXmlNoColumnPattern.matchEntire(trimmed)?.let { match ->
            val (path, lineNumber, severity, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = 0,
                severity = severityToProblem(severity),
                message = cleanupAndroidMessage(message),
                raw = line
            )
        }

        manifestMergerPattern.matchEntire(trimmed)?.let { match ->
            val (path, lineNumber, column, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                severity = BuildProblem.Severity.ERROR,
                message = cleanupAndroidMessage(message),
                raw = line
            )
        }

        aaptMessagePattern.matchEntire(trimmed)?.let { match ->
            val (message) = match.destructured
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = cleanupAndroidMessage(message),
                raw = line
            )
        }

        cppPattern.matchEntire(trimmed)?.let { match ->
            val (path, lineNumber, column, severity, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = column.toIntOrNull() ?: 0,
                severity = severityToProblem(severity),
                message = message.trim(),
                raw = line
            )
        }

        cmakePattern.matchEntire(trimmed)?.let { match ->
            val (severity, path, lineNumber, message) = match.destructured
            return BuildProblem(
                file = resolveFile(path.removePrefix("file://"), projectDir),
                line = lineNumber.toIntOrNull() ?: 0,
                column = 0,
                severity = severityToProblem(severity),
                message = message.trim(),
                raw = line
            )
        }

        ninjaPattern.matchEntire(trimmed)?.let { match ->
            val (message) = match.destructured
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = "ninja: ${message.trim()}",
                raw = line
            )
        }

        gradleTaskFailurePattern.matchEntire(trimmed)?.let { match ->
            val task = match.groupValues[1]
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = "Gradle task failed: $task",
                raw = line
            )
        }

        processStartPattern.matchEntire(trimmed)?.let { match ->
            val command = match.groupValues[1]
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = "Cannot start external tool: $command",
                raw = line
            )
        }

        cannotRunPattern.matchEntire(trimmed)?.let { match ->
            val command = match.groupValues[1]
            val reason = match.groupValues[2]
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = "Cannot run $command: $reason",
                raw = line
            )
        }

        permissionPattern.matchEntire(trimmed)?.let { match ->
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = "External tool permission error: error=${match.groupValues[1]}, ${match.groupValues[2]}",
                raw = line
            )
        }

        cxxBuildSystemPattern.matchEntire(trimmed)?.let { match ->
            val message = match.groupValues[1].trim()
            val cmakeFile = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
            return BuildProblem(
                file = cmakeFile?.let { resolveFile(it, projectDir) },
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = cleanupAndroidMessage(message),
                raw = line
            )
        }

        if (trimmed.startsWith("> Task") && trimmed.contains("FAILED")) {
            return BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.INFO,
                message = trimmed,
                raw = line
            )
        }

        return null
    }

    private fun kotlinSeverity(value: String): BuildProblem.Severity = when (value.lowercase()) {
        "e", "error" -> BuildProblem.Severity.ERROR
        "w", "warning" -> BuildProblem.Severity.WARNING
        else -> BuildProblem.Severity.INFO
    }

    private fun severityToProblem(value: String): BuildProblem.Severity = when (value.lowercase()) {
        "error", "fatal" -> BuildProblem.Severity.ERROR
        "warning", "warn" -> BuildProblem.Severity.WARNING
        else -> BuildProblem.Severity.INFO
    }

    private fun cleanupAndroidMessage(value: String): String =
        value.removePrefix("AAPT:")
            .removePrefix("error:")
            .removePrefix("Error:")
            .trim()

    private fun resolveFile(path: String, projectDir: File): File? {
        val normalized = path.removePrefix("file:")
        val direct = File(normalized)
        if (direct.isAbsolute) return direct.takeIf { it.exists() } ?: direct

        val projectRelative = File(projectDir, normalized)
        if (projectRelative.exists()) return projectRelative

        return projectDir.walkTopDown()
            .firstOrNull { it.isFile && it.invariantSeparatorsPath.endsWith(normalized.replace('\\', '/')) }
            ?: File(normalized)
    }

    private fun isSuccessfulBuild(output: String): Boolean {
        val hasSuccess = output.contains("BUILD SUCCESSFUL")
        val hasFailure = output.contains("BUILD FAILED") ||
            output.contains("FAILURE: Build failed with an exception")
        return hasSuccess && !hasFailure
    }

    private fun collapseClasspathStormIfNeeded(
        problems: List<BuildProblem>,
        output: String
    ): List<BuildProblem> {
        val unresolvedNames = Regex("""Unresolved reference ['`]([^'`]+)['`]?""")
            .findAll(output)
            .map { it.groupValues[1] }
            .toList()
        if (unresolvedNames.size < 40) return problems

        val classpathSignals = setOf(
            "AlertDialog",
            "AppCompatActivity",
            "CoroutineScope",
            "Dispatchers",
            "Job",
            "SupervisorJob",
            "BuildProblem",
            "buildString",
            "appendLine",
            "emptyList",
            "Regex",
            "Intent",
            "Context"
        )
        if (unresolvedNames.count { it in classpathSignals } < 4) return problems

        return listOf(
            BuildProblem(
                file = null,
                line = 0,
                column = 0,
                severity = BuildProblem.Severity.WARNING,
                message = "Problems analyzer classpath is incomplete; Gradle build may still be valid. Run Build/Sync to refresh dependencies.",
                raw = "Collapsed ${unresolvedNames.size} unresolved-reference diagnostics that look like missing Android/Kotlin classpath."
            )
        )
    }

    private val kotlincParenPattern = Regex(
        """^([ew]):\s*((?:file://)?.+?\.(?:kt|kts)):\s*\((\d+),\s*(\d+)\):\s*(.+)$"""
    )

    private val kotlincColonPattern = Regex(
        """^([ew]):\s*((?:file://)?.+?\.(?:kt|kts)):(\d+):(\d+)\s*:?\s*(.+)$"""
    )

    private val javacPattern = Regex(
        """^((?:file://)?.+?\.(?:java|kt|xml|gradle|kts)):(\d+)(?::\d+)?:\s*(error|warning|note|Error|Warning):\s*(.+)$"""
    )

    private val androidXmlPattern = Regex(
        """^(?:ERROR:\s*)?((?:file://)?(?:/|[A-Za-z]:\\|\.{1,2}/)?.+?\.(?:xml|gradle|kts)):(\d+):(\d+):\s*(error|warning|Error|Warning):\s*(.+)$"""
    )

    private val androidXmlNoColumnPattern = Regex(
        """^(?:ERROR:\s*)?((?:file://)?(?:/|[A-Za-z]:\\|\.{1,2}/)?.+?\.(?:xml|gradle|kts)):(\d+):\s*(error|warning|Error|Warning):\s*(.+)$"""
    )

    private val manifestMergerPattern = Regex(
        """^((?:file://)?.+?AndroidManifest\.xml):(\d+):(\d+)-\d+:\d+\s+(.+)$"""
    )

    private val aaptMessagePattern = Regex(
        """^(?:Android resource linking failed|Manifest merger failed|AAPT:\s*)\s*:?\s*(.+)$"""
    )

    private val cppPattern = Regex(
        """^((?:file://)?.+?\.(?:c|cpp|cc|cxx|h|hpp|hh)):(\d+):(\d+):\s*(fatal error|error|warning|note|fatal):\s*(.+)$"""
    )

    private val cmakePattern = Regex(
        """^CMake\s+(Error|Warning)\s+at\s+(.+?)(?::(\d+))?(?:\s+\(.+?\))?:\s*(.+)$"""
    )

    private val ninjaPattern = Regex(
        """^ninja:\s+error:\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    private val gradleTaskFailurePattern = Regex(
        """^(?:\*\s+What went wrong:\s*)?Execution failed for task '([^']+)'\.$"""
    )

    private val processStartPattern = Regex(
        """^>\s*A problem occurred starting process 'command '(.+?)''$"""
    )

    private val cannotRunPattern = Regex(
        """^(?:Caused by:\s*)?java\.io\.IOException:\s+Cannot run program "(.+?)".*?:\s*(.+)$"""
    )

    private val permissionPattern = Regex(
        """^(?:Caused by:\s*)?java\.io\.IOException:\s*error=(\d+),\s*(.+)$"""
    )

    private val cxxBuildSystemPattern = Regex(
        """^>\s*(\[CXX\d+\].*?)(?:\s+using\s+(.+?CMakeLists\.txt).*)?$"""
    )
}
