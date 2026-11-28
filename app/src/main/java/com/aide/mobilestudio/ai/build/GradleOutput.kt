package com.aide.mobilestudio.ai.build

/**
 * Parses Gradle build output into the short summaries the Build screen and
 * the AI tools show. Reconstructed Nov 27, 2026: AiWorkspaceActivity and
 * OcAndroidTools both referenced this object, but the class was never
 * shipped upstream, so the build failed with "unresolved reference".
 */
object GradleOutput {

    private val taskFailedRegex = Regex("""^>\s*Task\s+(\S+)\s+FAILED\s*$""")
    private val executionFailedRegex = Regex("""Execution failed for task '([^']+)'""")
    private val kotlinErrorRegex = Regex("""^e:\s+.*""")
    private val compilerErrorRegex = Regex(""".*\.(kt|java|c|cpp|h):\d+.*[Ee]rror[:\s].*""")

    /**
     * Task paths (e.g. `:app:compileDebugKotlin`) that reported FAILED,
     * in first-seen order, deduplicated.
     */
    fun failedTasks(lines: List<String>): List<String> {
        val out = linkedSetOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            taskFailedRegex.find(line)?.let { out += it.groupValues[1] }
            executionFailedRegex.find(line)?.let { out += it.groupValues[1] }
        }
        return out.toList()
    }

    /**
     * The most useful error lines from a build log, in log order, capped at
     * [limit]. Picks Kotlin errors (`e: ...`), compiler errors
     * (`file.kt:123: ... error: ...`), the "What went wrong" block, the
     * "Execution failed for task" line, and FAILED task lines.
     */
    fun diagnostics(lines: List<String>, limit: Int): List<String> {
        val picked = ArrayList<String>()
        val seen = HashSet<String>()
        var inWhatWentWrong = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                inWhatWentWrong = false
                continue
            }
            val hit = when {
                line.startsWith("* What went wrong:") -> {
                    inWhatWentWrong = true
                    true
                }
                inWhatWentWrong && line.startsWith("* ") -> true
                kotlinErrorRegex.matches(line) -> true
                compilerErrorRegex.matches(line) -> true
                line.startsWith("Execution failed for task") -> true
                line.startsWith("FAILURE:") -> true
                taskFailedRegex.matches(line) -> true
                else -> false
            }
            if (hit && seen.add(line)) {
                picked += line.take(1000)
                if (picked.size >= limit) break
            }
        }
        return picked
    }
}
