package com.aide.mobilestudio.ai.opencode.orchestrator

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.aide.mobilestudio.ai.AiSettings
import com.aide.mobilestudio.ai.orchestrator.AgentModel
import com.aide.mobilestudio.ai.orchestrator.ModelCapability
import com.aide.mobilestudio.ai.orchestrator.ModelProvider
import com.aide.mobilestudio.ai.orchestrator.ModelStats
import com.aide.mobilestudio.ai.orchestrator.OrchestratorConfig
import com.aide.mobilestudio.ai.opencode.provider.OcModelInfo
import com.aide.mobilestudio.ai.opencode.provider.OcProviderRegistry
import com.aide.mobilestudio.ai.resource.AndroidMemoryProbe
import java.io.File

/**
 * Bridges the existing provider registry and settings into the orchestrator's
 * provider-neutral model metadata. It does not duplicate model selection:
 * the model picker and this catalog read the same registry.
 */
class OcAgentModelCatalog(
    private val context: Context,
    private val providers: OcProviderRegistry,
    private val settings: AiSettings = AiSettings(context),
    private val statsProvider: (String) -> ModelStats = { ModelStats() },
) {

    fun models(): List<AgentModel> {
        val out = mutableListOf<AgentModel>()
        for ((providerId, _) in providers.listProviders()) {
            val models = runCatching { providers.listModels(providerId) }.getOrDefault(emptyList())
            for (model in models) {
                out.add(toAgentModel(model))
            }
        }
        return out.distinctBy { it.id }
    }

    private fun toAgentModel(model: OcModelInfo): AgentModel {
        val id = model.ref.toString()
        val lower = (model.ref.modelID + " " + model.name).lowercase()
        val capabilities = linkedSetOf(ModelCapability.CHAT)
        if (model.toolCall) capabilities.add(ModelCapability.TOOL_CALLING)
        if (codingFamily(lower)) capabilities.add(ModelCapability.CODING)
        if (model.reasoning || reasoningFamily(lower)) capabilities.add(ModelCapability.REASONING)
        val local = model.local
        val weights = if (local) {
            model.ggufPath?.let { path -> runCatching { File(path).length() }.getOrNull()?.takeIf { it > 0 } }
        } else {
            null
        }
        return AgentModel(
            id = id,
            providerId = model.ref.providerID,
            displayName = model.name,
            provider = if (local) ModelProvider.LOCAL else ModelProvider.API,
            capabilities = capabilities,
            parameterCount = parseParameterCount(lower),
            contextLimit = model.contextLimit,
            available = available(model),
            localWeightsBytes = weights,
            maxConcurrent = if (local) 1 else 2,
            stats = statsProvider(id),
        )
    }

    private fun available(model: OcModelInfo): Boolean {
        if (model.local) {
            val path = model.ggufPath ?: return false
            return runCatching { File(path).isFile && File(path).length() > 0 }.getOrDefault(false)
        }
        return when (model.ref.providerID) {
            "gemini" -> !settings.apiKey.isNullOrBlank()
            "openai" -> !settings.openaiApiKey.isNullOrBlank()
            "anthropic" -> !settings.anthropicApiKey.isNullOrBlank()
            // A custom OpenAI-compatible endpoint may be keyless on a LAN.
            else -> true
        }
    }

    fun config(): OrchestratorConfig {
        val budgetUsd = settings.orchestratorCostBudgetUsd.toDouble().takeIf { it > 0.0 }
        return OrchestratorConfig(
            maxParallel = settings.orchestratorMaxParallel,
            hardMaxParallel = 5,
            maxDepth = settings.orchestratorMaxDepth,
            maxTasksPerBatch = settings.orchestratorMaxTasksPerBatch,
            maxTotalTasksPerRoot = 24,
            maxRetriesPerTask = 1,
            maxPromotions = 2,
            maxApiCalls = settings.orchestratorApiCallBudget,
            maxTokens = settings.orchestratorTokenBudget.toLong(),
            maxCostUsd = budgetUsd,
            defaultTimeoutMs = 5 * 60_000L,
            totalTimeoutMs = 15 * 60_000L,
            networkAvailable = isOnline(),
            availableMemoryBytes = availableMemoryBytes(),
        )
    }

    fun availableMemoryBytes(): Long? =
        runCatching { AndroidMemoryProbe.snapshot(context).availableBytes }.getOrNull()

    private fun isOnline(): Boolean {
        return runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private fun codingFamily(lower: String): Boolean =
        Regex("(coder|coding|code|codestral|starcoder|qwen|deepseek|gpt|claude|gemini|llama|mistral)")
            .containsMatchIn(lower)

    private fun reasoningFamily(lower: String): Boolean =
        Regex("(^|[-_/ ])(r1|qwq|o1|o3|o4)([-_/ .]|$)|think|reason")
            .containsMatchIn(lower)

    private fun parseParameterCount(lower: String): Double? {
        // The quantisation suffix (Q4_K_M) does not match because it has no
        // "b" immediately after the number. 2.5-coder-3B matches only 3B.
        val match = Regex("(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)\\s*b(?![a-z0-9])")
            .find(lower) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.1..2000.0 }
    }
}
