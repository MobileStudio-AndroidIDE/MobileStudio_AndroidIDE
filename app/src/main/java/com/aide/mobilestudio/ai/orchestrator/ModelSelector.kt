package com.aide.mobilestudio.ai.orchestrator

data class ModelSelection(
    val model: AgentModel,
    val score: Double,
    val reason: String,
)

/**
 * Picks a model from facts we actually have: declared capabilities, context,
 * local file size, configured pricing, live availability and observed
 * outcomes. Parameter count is a weak prior, never the deciding factor.
 */
class ModelSelector(private val config: OrchestratorConfig) {

    fun select(
        task: AgentTask,
        models: List<AgentModel>,
        excludedModelIds: Set<String> = emptySet(),
        activeByModel: Map<String, Int> = emptyMap(),
        availableMemoryBytes: Long? = config.availableMemoryBytes,
    ): ModelSelection? {
        val required = task.requiredCapabilities + ModelCapability.CHAT
        val candidates = models.filter { model ->
            model.available &&
                model.id !in excludedModelIds &&
                model.capabilities.containsAll(required) &&
                (model.contextLimit <= 0 || estimatedTokens(task) <= model.contextLimit * 9 / 10) &&
                (model.maxConcurrent <= 0 || (activeByModel[model.id] ?: 0) < model.maxConcurrent) &&
                fitsMemory(model, availableMemoryBytes) &&
                (model.provider != ModelProvider.API || config.networkAvailable)
        }
        if (candidates.isEmpty()) return null

        val scored = candidates.map { model -> model to score(task, model) }
            .sortedByDescending { it.second }
        val (best, baseScore) = scored.first()

        // An explicit model is a preference, not a bypass: it still has to be
        // available, capable and within the same resource limits.
        val preferred = task.preferredModelId?.let { id ->
            scored.firstOrNull { it.first.id == id }?.first
        }
        if (preferred != null) {
            return ModelSelection(preferred, baseScore + 1_000, "explicit model ${preferred.id}")
        }
        val reason = buildString {
            append(task.difficulty.name.lowercase()).append(" fit, ")
            append(best.provider.name.lowercase()).append(", ")
            append("capabilities=").append(best.capabilities.joinToString("+") { it.name.lowercase() })
            best.stats.successRate?.let { append(", success=").append(String.format(java.util.Locale.US, "%.0f%%", it * 100)) }
            best.parameterCount?.let { append(", ").append(it).append("B") }
        }
        return ModelSelection(best, baseScore, reason)
    }

    private fun estimatedTokens(task: AgentTask): Int =
        ((task.prompt.length + (task.inputContext?.length ?: 0)) / 4).coerceAtLeast(1)

    private fun fitsMemory(model: AgentModel, availableMemoryBytes: Long?): Boolean {
        if (model.provider != ModelProvider.LOCAL) return true
        val available = availableMemoryBytes ?: return true
        val weights = model.localWeightsBytes ?: return true
        // Weights plus a conservative runtime/KV allowance. This rejects an
        // obviously impossible parallel load; it does not claim to measure the
        // real resident set.
        val needed = weights + 512L * 1024 * 1024
        return needed <= (available * 0.85).toLong()
    }

    private fun score(task: AgentTask, model: AgentModel): Double {
        var score = 0.0
        val params = model.parameterCount
        when (task.difficulty) {
            TaskDifficulty.SIMPLE -> {
                if (model.provider == ModelProvider.LOCAL) score += 24
                if (params != null) score += when {
                    params <= 2.0 -> 35
                    params <= 4.0 -> 22
                    params <= 8.0 -> 8
                    else -> -4
                }
                if (model.contextLimit in 1..4096) score += 5
            }
            TaskDifficulty.NORMAL -> {
                if (model.provider == ModelProvider.LOCAL) score += 16 else score += 6
                if (params != null) score += when {
                    params in 2.5..8.5 -> 38
                    params < 2.5 -> -4
                    params <= 16.0 -> 22
                    else -> 4
                }
            }
            TaskDifficulty.HARD -> {
                if (model.provider == ModelProvider.API) score += 26 else score += 4
                if (params != null) score += when {
                    params in 6.0..32.0 -> 32
                    params > 32.0 -> 20
                    params >= 3.0 -> 8
                    else -> -12
                }
                if (ModelCapability.REASONING in model.capabilities) score += 18
            }
            TaskDifficulty.EXPERT -> {
                if (model.provider == ModelProvider.API) score += 38 else score += 8
                if (params != null) score += when {
                    params >= 20.0 -> 32
                    params >= 7.0 -> 16
                    else -> -12
                }
                if (ModelCapability.REASONING in model.capabilities) score += 24
                if (model.contextLimit >= 64_000) score += 8
            }
        }
        if (ModelCapability.CODING in model.capabilities) score += 12
        if (ModelCapability.TOOL_CALLING in model.capabilities) score += 8
        score += (model.stats.successRate ?: 0.5) * 45
        model.stats.averageLatencyMs?.let { latency ->
            score -= (latency / 1_000.0).coerceAtMost(30.0)
        }
        if (model.provider == ModelProvider.API) {
            val input = model.inputCostPerMillion
            val output = model.outputCostPerMillion
            if (input != null && output != null) {
                score -= ((input + output) * 0.15).coerceAtMost(15.0)
            }
            // Keep one API model from monopolizing a batch; the runner still
            // decides whether the provider rate-limits.
            score -= (model.id.length % 3).toDouble()
        }
        // Prefer headroom for hard work, but do not reward an enormous window
        // for a simple task.
        if (task.difficulty.rank >= TaskDifficulty.HARD.rank) {
            score += (model.contextLimit / 16_000).coerceAtMost(10)
        }
        return score
    }
}
