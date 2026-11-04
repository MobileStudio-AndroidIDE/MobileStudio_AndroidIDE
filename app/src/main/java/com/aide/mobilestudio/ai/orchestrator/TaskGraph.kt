package com.aide.mobilestudio.ai.orchestrator

sealed interface BatchBuildResult {
    data class Success(val tasks: List<AgentTask>) : BatchBuildResult
    data class Rejected(val reason: String) : BatchBuildResult
}

object TaskGraph {

    fun build(
        batchId: String,
        rootId: String,
        parentTaskId: String?,
        parentSessionId: String,
        depth: Int,
        specs: List<TaskSpec>,
        now: Long,
        maxTasks: Int,
        defaultTimeoutMs: Long,
        availableSubagentTypes: Set<String>,
    ): BatchBuildResult {
        if (specs.isEmpty()) return BatchBuildResult.Rejected("delegate requires at least one task")
        if (specs.size > maxTasks) {
            return BatchBuildResult.Rejected("too many subtasks: ${specs.size} (limit $maxTasks)")
        }
        if (depth < 0) return BatchBuildResult.Rejected("invalid delegation depth")

        val ids = specs.indices.map { "task-${batchId}-${it + 1}" }
        val colors = IntArray(specs.size) // 0 new, 1 visiting, 2 done
        fun hasCycle(index: Int): Boolean {
            if (colors[index] == 1) return true
            if (colors[index] == 2) return false
            colors[index] = 1
            for (dep in specs[index].dependencies) {
                if (dep !in specs.indices) return true
                if (hasCycle(dep)) return true
            }
            colors[index] = 2
            return false
        }
        for (i in specs.indices) if (hasCycle(i)) {
            return BatchBuildResult.Rejected("cyclic task dependency at index ${i + 1}")
        }

        val tasks = specs.mapIndexed { index, spec ->
            val description = spec.description.trim()
            val prompt = spec.prompt.trim()
            if (description.isEmpty()) {
                return BatchBuildResult.Rejected("task ${index + 1}: description is required")
            }
            if (prompt.isEmpty()) {
                return BatchBuildResult.Rejected("task ${index + 1}: prompt is required")
            }
            if (description.length > 240) {
                return BatchBuildResult.Rejected("task ${index + 1}: description is too long (240 max)")
            }
            if (prompt.length > 120_000) {
                return BatchBuildResult.Rejected("task ${index + 1}: prompt is too long (120000 max)")
            }
            if (spec.dependencies.distinct().size != spec.dependencies.size) {
                return BatchBuildResult.Rejected("task ${index + 1}: duplicate dependency")
            }
            val subagent = spec.subagentType.trim().ifEmpty { "general" }
            if (subagent !in availableSubagentTypes) {
                return BatchBuildResult.Rejected(
                    "task ${index + 1}: unknown subagent '$subagent' (available: ${availableSubagentTypes.sorted().joinToString(", ")})",
                )
            }
            val analysis = TaskClassifier.analyze(spec)
            val timeout = (spec.timeoutMs ?: defaultTimeoutMs).coerceIn(5_000L, 15 * 60_000L)
            val deps = spec.dependencies.distinct().map { ids[it] }
            AgentTask(
                id = ids[index],
                batchId = batchId,
                parentTaskId = parentTaskId,
                parentSessionId = parentSessionId,
                rootId = rootId,
                description = description,
                prompt = prompt,
                difficulty = analysis.difficulty,
                requiredCapabilities = analysis.requiredCapabilities,
                dependencies = deps,
                inputContext = spec.inputContext?.trim()?.ifBlank { null },
                preferredModelId = spec.modelId?.trim()?.ifBlank { null },
                assignedModelId = null,
                status = if (deps.isEmpty()) TaskStatus.READY else TaskStatus.WAITING_FOR_DEPENDENCY,
                retryCount = 0,
                timeoutMs = timeout,
                subagentType = subagent,
                depth = depth,
                filesHint = spec.filesHint.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                allowedTools = spec.allowedTools.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                result = null,
                error = null,
                modelHistory = emptyList(),
                createdAt = now,
                startedAt = null,
                finishedAt = null,
                toolCeiling = spec.toolCeiling.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            )
        }
        return BatchBuildResult.Success(tasks)
    }
}

/**
 * Formats and conflict checks. Tests can inject a verifier; the Android engine
 * leaves it null so the result says verification was not run.
 */
object ResultIntegrator {

    fun integrate(
        tasks: List<AgentTask>,
        verification: VerificationResult? = null,
    ): IntegrationResult {
        val failed = tasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT }
        val cancelled = tasks.filter { it.status == TaskStatus.CANCELLED }
        val successful = tasks.filter { it.status == TaskStatus.SUCCEEDED }
        val changed = successful.flatMap { it.result?.changedFiles.orEmpty() }
            .map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

        val byFile = mutableMapOf<String, MutableList<String>>()
        for (task in successful) {
            for (file in task.result?.changedFiles.orEmpty()) {
                byFile.getOrPut(normalize(file)) { mutableListOf() }.add(task.id)
            }
        }
        val conflicts = byFile.filterValues { it.size > 1 }.keys.sorted()
        val declaredFailure = successful.any { it.result?.success == false || it.result?.needsFollowUp == true }
        val needsFollowUp = successful.any { it.result?.needsFollowUp == true }
        val verifierRan = verification?.ran == true
        val verifierFailed = verifierRan && verification?.success == false
        val success = failed.isEmpty() && cancelled.isEmpty() && conflicts.isEmpty() &&
            !declaredFailure && !verifierFailed

        val summary = buildString {
            append(if (success) "Delegation completed" else "Delegation needs attention")
            append(": ${successful.size}/${tasks.size} succeeded")
            if (failed.isNotEmpty()) append(", ${failed.size} failed")
            if (cancelled.isNotEmpty()) append(", ${cancelled.size} cancelled")
            if (conflicts.isNotEmpty()) append(", file conflicts: ${conflicts.joinToString(", ")}")
            if (needsFollowUp) append(", follow-up requested")
        }
        val verificationText = when {
            verifierRan && verification != null -> verification.detail
            else -> "not run by the orchestrator - the main agent must run the relevant tests/build"
        }
        return IntegrationResult(
            success = success,
            summary = summary,
            changedFiles = changed,
            conflicts = conflicts,
            failedTaskIds = failed.map { it.id },
            cancelledTaskIds = cancelled.map { it.id },
            needsFollowUp = needsFollowUp,
            verification = verificationText,
        )
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim().removePrefix("./").trimStart('/')
}
