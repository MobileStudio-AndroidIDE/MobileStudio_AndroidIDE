package com.aide.mobilestudio.ai.orchestrator

/**
 * A model that the orchestrator may assign work to.
 *
 * Local and API models deliberately share this shape: [provider] is metadata
 * used for policy, not a second code path. Keys and transport stay inside the
 * existing provider implementations; nothing here can carry a secret into a
 * prompt.
 */
data class AgentModel(
    val id: String,
    val providerId: String,
    val displayName: String,
    val provider: ModelProvider,
    val capabilities: Set<ModelCapability>,
    /** Parameter count in billions when it is known; null is not zero. */
    val parameterCount: Double? = null,
    val contextLimit: Int = 0,
    val available: Boolean = true,
    /**
     * Size of the GGUF weights on disk. This is a lower bound on runtime
     * memory, not a measured resident set; the UI labels it that way.
     */
    val localWeightsBytes: Long? = null,
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    /** Provider/model concurrency ceiling. Local llama.cpp is effectively 1. */
    val maxConcurrent: Int = 1,
    val stats: ModelStats = ModelStats(),
) {
    val supportsTools: Boolean get() = ModelCapability.TOOL_CALLING in capabilities
}

enum class ModelProvider { LOCAL, API }

enum class ModelCapability {
    CHAT,
    CODING,
    REASONING,
    TOOL_CALLING,
    CODE_REVIEW,
    SUMMARIZATION,
}

enum class TaskDifficulty(val rank: Int) {
    SIMPLE(0),
    NORMAL(1),
    HARD(2),
    EXPERT(3);

    fun next(): TaskDifficulty = entries.getOrElse(rank + 1) { EXPERT }
}

/**
 * Observed outcomes, not a claim about a model's intelligence. A model with no
 * history has a null success rate so the selector can avoid pretending 0% or
 * 100% before the first run.
 */
data class ModelStats(
    val successes: Int = 0,
    val failures: Int = 0,
    val totalLatencyMs: Long = 0,
    val lastResult: String? = null,
    val lastError: String? = null,
) {
    val runs: Int get() = successes + failures
    val successRate: Double? get() = if (runs == 0) null else successes.toDouble() / runs
    val averageLatencyMs: Long? get() = if (runs == 0) null else totalLatencyMs / runs

    fun success(latencyMs: Long, result: String?): ModelStats = copy(
        successes = successes + 1,
        totalLatencyMs = totalLatencyMs + latencyMs.coerceAtLeast(0),
        lastResult = result,
        lastError = null,
    )

    fun failure(latencyMs: Long, error: String?): ModelStats = copy(
        failures = failures + 1,
        totalLatencyMs = totalLatencyMs + latencyMs.coerceAtLeast(0),
        lastError = error,
    )
}
