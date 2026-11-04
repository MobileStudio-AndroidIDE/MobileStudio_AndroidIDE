package com.aide.mobilestudio.ai.orchestrator

enum class TaskStatus {
    PENDING,
    READY,
    RUNNING,
    WAITING_FOR_DEPENDENCY,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    val finished: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT
}

enum class AgentErrorKind {
    NONE,
    NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    AUTH,
    QUOTA_EXHAUSTED,
    MODEL_UNSUPPORTED,
    BAD_REQUEST,
    SERVER,
    CONTEXT_OVERFLOW,
    MEMORY,
    CANCELLED,
    UNKNOWN;

    /** Errors where replaying the exact same request cannot help. */
    val retryable: Boolean
        get() = when (this) {
            NETWORK, TIMEOUT, RATE_LIMIT, SERVER, MEMORY -> true
            else -> false
        }
}

/**
 * An untrusted proposal from a model. Every field is validated before it
 * becomes an [AgentTask]; dependencies are indexes into the same batch.
 */
data class TaskSpec(
    val description: String,
    val prompt: String,
    val difficulty: TaskDifficulty? = null,
    val requiredCapabilities: Set<ModelCapability> = emptySet(),
    val modelId: String? = null,
    val subagentType: String = "general",
    val dependencies: List<Int> = emptyList(),
    val timeoutMs: Long? = null,
    val inputContext: String? = null,
    val filesHint: List<String> = emptyList(),
    val allowedTools: Set<String> = emptySet(),
    /**
     * Tools the proposing agent is itself allowed to use. A nested proposal
     * cannot expand beyond this ceiling; an empty set means the top-level
     * agent (which owns the session) imposed no ceiling.
     */
    val toolCeiling: Set<String> = emptySet(),
)

data class ModelAttempt(
    val modelId: String,
    val success: Boolean,
    val error: String?,
    val latencyMs: Long,
    val promoted: Boolean = false,
)

data class AgentTask(
    val id: String,
    val batchId: String,
    val parentTaskId: String?,
    val parentSessionId: String,
    val rootId: String,
    val description: String,
    val prompt: String,
    val difficulty: TaskDifficulty,
    val requiredCapabilities: Set<ModelCapability>,
    val dependencies: List<String>,
    val inputContext: String?,
    val preferredModelId: String?,
    val assignedModelId: String?,
    val status: TaskStatus,
    val retryCount: Int,
    val timeoutMs: Long,
    val subagentType: String,
    val depth: Int,
    val filesHint: List<String>,
    val allowedTools: Set<String>,
    val result: TaskResult?,
    val error: String?,
    val modelHistory: List<ModelAttempt>,
    val createdAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val toolCeiling: Set<String> = emptySet(),
) {
    val durationMs: Long? get() = if (startedAt == null || finishedAt == null) null else finishedAt - startedAt
    val isFinished: Boolean get() = status.finished
}

data class TaskResult(
    val taskId: String,
    val success: Boolean,
    val summary: String,
    val output: String,
    val changedFiles: List<String> = emptyList(),
    val diff: String? = null,
    val errors: List<String> = emptyList(),
    val verification: String? = null,
    val needsFollowUp: Boolean = false,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tokensEstimated: Boolean = true,
    val costUsd: Double? = null,
)

/** What an [AgentRunner] returns. The orchestrator turns it into a task state. */
data class AgentRunResult(
    val success: Boolean,
    val output: String,
    val summary: String = output.take(300),
    val changedFiles: List<String> = emptyList(),
    val diff: String? = null,
    val errors: List<String> = emptyList(),
    val verification: String? = null,
    val needsFollowUp: Boolean = false,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tokensEstimated: Boolean = true,
    val costUsd: Double? = null,
    val errorKind: AgentErrorKind = if (success) AgentErrorKind.NONE else AgentErrorKind.UNKNOWN,
    val retryable: Boolean = errorKind.retryable,
) {
    companion object {
        fun failure(
            message: String,
            kind: AgentErrorKind = AgentErrorKind.UNKNOWN,
            retryable: Boolean = kind.retryable,
        ) = AgentRunResult(
            success = false,
            output = "",
            summary = message,
            errors = listOf(message),
            errorKind = kind,
            retryable = retryable,
        )

        fun timeout(message: String = "task timed out") =
            failure(message, AgentErrorKind.TIMEOUT, retryable = true)
    }
}

/**
 * The transport-neutral execution seam. The Android engine implements this by
 * running a real subagent on a real provider; tests implement it with fakes.
 * The orchestrator never sees a provider key or a transport.
 */
interface AgentRunner {
    suspend fun run(task: AgentTask, model: AgentModel, depth: Int): AgentRunResult
}

/**
 * Optional last step. It is deliberately injectable: the Android engine does
 * not run a project build without the user's existing build approval, so its
 * verifier is absent and results say "not run" rather than "passed".
 */
interface WorkVerifier {
    suspend fun verify(tasks: List<AgentTask>, results: List<TaskResult>): VerificationResult
}

data class VerificationResult(
    val ran: Boolean,
    val success: Boolean?,
    val detail: String,
)

data class OrchestratorSnapshot(
    val batchId: String,
    val rootId: String,
    val parentTaskId: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    val tasks: List<AgentTask>,
    val logs: List<String>,
    val budget: BudgetSnapshot,
    val success: Boolean?,
    val summary: String,
    val verification: String? = null,
)

data class IntegrationResult(
    val success: Boolean,
    val summary: String,
    val changedFiles: List<String>,
    val conflicts: List<String>,
    val failedTaskIds: List<String>,
    val cancelledTaskIds: List<String>,
    val needsFollowUp: Boolean,
    val verification: String?,
)
