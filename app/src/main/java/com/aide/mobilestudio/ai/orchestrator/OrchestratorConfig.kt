package com.aide.mobilestudio.ai.orchestrator

/**
 * Limits are explicit so an API model cannot turn one request into an
 * unbounded tree of calls. Zero/negative token or call limits mean "do not
 * enforce that dimension"; a null cost limit means pricing is unknown or
 * unlimited, and the UI says so instead of inventing a number.
 */
data class OrchestratorConfig(
    val maxParallel: Int = 2,
    val hardMaxParallel: Int = 5,
    val maxDepth: Int = 3,
    val maxTasksPerBatch: Int = 8,
    val maxTotalTasksPerRoot: Int = 24,
    val maxRetriesPerTask: Int = 1,
    val maxPromotions: Int = 2,
    val maxApiCalls: Int = 24,
    val maxTokens: Long = 250_000,
    val maxCostUsd: Double? = null,
    val defaultTimeoutMs: Long = 5 * 60_000,
    val totalTimeoutMs: Long = 15 * 60_000,
    val networkAvailable: Boolean = true,
    val availableMemoryBytes: Long? = null,
) {
    fun effectiveParallel(): Int = maxParallel.coerceIn(1, hardMaxParallel.coerceAtLeast(1))

    /** Conservative adjustment for the device we are actually running on. */
    fun memoryAdjustedParallel(): Int {
        val requested = effectiveParallel()
        val available = availableMemoryBytes ?: return requested
        val gb = available.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb < 2.0 -> 1
            gb < 4.0 -> requested.coerceAtMost(2)
            else -> requested
        }
    }
}

data class BudgetDecision(val allowed: Boolean, val reason: String = "")

data class BudgetSnapshot(
    val apiCalls: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    val costUsd: Double?,
    val maxApiCalls: Int,
    val maxTokens: Long,
    val maxCostUsd: Double?,
) {
    val totalTokens: Long get() = tokensIn + tokensOut
}

/**
 * A single budget shared by every batch of one top-level request, including
 * nested delegation. API calls are counted before they start; usage is
 * recorded afterwards because providers only report it then.
 */
class BudgetTracker(private val config: OrchestratorConfig) {
    private var apiCalls = 0
    private var tokensIn = 0L
    private var tokensOut = 0L
    private var costUsd = 0.0
    private var costKnown = true

    @Synchronized
    fun canStart(api: Boolean): BudgetDecision {
        if (api && !config.networkAvailable) return BudgetDecision(false, "network is unavailable")
        if (api && config.maxApiCalls > 0 && apiCalls >= config.maxApiCalls) {
            return BudgetDecision(false, "API call limit reached (${config.maxApiCalls})")
        }
        if (config.maxTokens > 0 && tokensIn + tokensOut >= config.maxTokens) {
            return BudgetDecision(false, "token budget reached (${config.maxTokens})")
        }
        val cap = config.maxCostUsd
        if (api && cap != null && costKnown && costUsd >= cap) {
            return BudgetDecision(false, "cost budget reached ($${String.format(java.util.Locale.US, "%.4f", cap)})")
        }
        return BudgetDecision(true)
    }

    @Synchronized
    fun recordApiCall() {
        apiCalls++
    }

    @Synchronized
    fun recordUsage(input: Long, output: Long, cost: Double?) {
        tokensIn += input.coerceAtLeast(0)
        tokensOut += output.coerceAtLeast(0)
        if (cost != null) costUsd += cost.coerceAtLeast(0.0) else costKnown = false
    }

    @Synchronized
    fun snapshot(): BudgetSnapshot = BudgetSnapshot(
        apiCalls = apiCalls,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costUsd = if (costKnown) costUsd else null,
        maxApiCalls = config.maxApiCalls,
        maxTokens = config.maxTokens,
        maxCostUsd = config.maxCostUsd,
    )
}
