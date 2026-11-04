package com.aide.mobilestudio.ai

import android.content.Context
import android.content.SharedPreferences
import com.aide.mobilestudio.SecurePrefs
import com.aide.mobilestudio.ai.agent.HardwareManager

class AiSettings(private val context: Context) {
    // API keys live in an encrypted store; existing plaintext values are migrated once.
    private val prefs: SharedPreferences = SecurePrefs.get(context, SECURE_PREFS).also {
        SecurePrefs.migrateAll(context, PREFS, it)
    }

    companion object {
        const val PREFS = "ai_settings"
        const val SECURE_PREFS = "ai_settings_secure"
        const val KEY_OC_PERMISSIONS = "oc_saved_permissions"
    }

    /** Raw JSON of saved opencode permission rules (read by OcEngine). */
    fun ocPermissionsJson(): String = prefs.getString(KEY_OC_PERMISSIONS, "[]") ?: "[]"

    fun setOcPermissionsJson(json: String) = prefs.edit().putString(KEY_OC_PERMISSIONS, json).apply()

    var apiKey: String?
        get() = prefs.getString("gemini_api_key", null)
        set(value) = prefs.edit().putString("gemini_api_key", value).apply()

    var lastModelId: String?
        get() = prefs.getString("last_model_id", "gemini")
        set(value) = prefs.edit().putString("last_model_id", value).apply()

    /**
     * The last model chosen in the AI workspace (OcEngine).
     *
     * It is a separate preference from [lastModelId] on purpose: that one
     * belongs to the bottom-panel agent, and the two keep entirely different
     * model lists whose ids do not mean the same thing. Sharing one key meant
     * opening either screen silently overwrote the other's choice, which is
     * how the workspace could reopen on a model you had not picked.
     */
    var lastWorkspaceModelId: String?
        get() = prefs.getString("last_workspace_model_id", null)
        set(value) = prefs.edit().putString("last_workspace_model_id", value).apply()

    /**
     * Reasoning depth picked in the AI workspace: "", "auto", "minimal",
     * "low", "medium", "high". "" and "auto" mean "send nothing and let the
     * provider decide" — the app never guesses a depth on its own.
     */
    var workspaceReasoningEffort: String
        get() = prefs.getString("workspace_reasoning_effort", "auto") ?: "auto"
        set(value) = prefs.edit().putString("workspace_reasoning_effort", value).apply()

    /**
     * "Think" mode in the AI workspace: the agent states its approach before
     * acting, and the reasoning a provider streams is shown as a full card.
     * Off by default - it costs prompt tokens on every request.
     */
    var thinkMode: Boolean
        get() = prefs.getBoolean("ai_think_mode", false)
        set(value) = prefs.edit().putBoolean("ai_think_mode", value).apply()

    // ---------------------------------------------------- delegation / agents

    /**
     * Initial parallel delegation is 2 and the user-facing ceiling is 5. The
     * orchestrator still reduces this dynamically when Android reports low
     * memory; this is an upper bound, not a promise to load five local models.
     */
    var orchestratorMaxParallel: Int
        get() = prefs.getInt("orchestrator_max_parallel", 2).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("orchestrator_max_parallel", value.coerceIn(1, 5)).apply()

    var orchestratorMaxDepth: Int
        get() = prefs.getInt("orchestrator_max_depth", 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("orchestrator_max_depth", value.coerceIn(1, 5)).apply()

    var orchestratorMaxTasksPerBatch: Int
        get() = prefs.getInt("orchestrator_max_tasks", 8).coerceIn(1, 24)
        set(value) = prefs.edit().putInt("orchestrator_max_tasks", value.coerceIn(1, 24)).apply()

    var orchestratorTokenBudget: Int
        get() = prefs.getInt("orchestrator_token_budget", 250_000).coerceAtLeast(0)
        set(value) = prefs.edit().putInt("orchestrator_token_budget", value.coerceAtLeast(0)).apply()

    var orchestratorApiCallBudget: Int
        get() = prefs.getInt("orchestrator_api_call_budget", 24).coerceAtLeast(0)
        set(value) = prefs.edit().putInt("orchestrator_api_call_budget", value.coerceAtLeast(0)).apply()

    /** 0 means "no dollar cap configured"; pricing is often unknown, so this is optional. */
    var orchestratorCostBudgetUsd: Float
        get() = prefs.getFloat("orchestrator_cost_budget_usd", 0f).coerceAtLeast(0f)
        set(value) = prefs.edit().putFloat("orchestrator_cost_budget_usd", value.coerceAtLeast(0f)).apply()

    var activeProvider: String
        get() = prefs.getString("active_provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("active_provider", value).apply()

    var openaiApiKey: String?
        get() = prefs.getString("openai_api_key", null)
        set(value) = prefs.edit().putString("openai_api_key", value).apply()

    var openaiBaseUrl: String
        get() = prefs.getString("openai_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("openai_base_url", value).apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("openai_model", value).apply()

    var anthropicApiKey: String?
        get() = prefs.getString("anthropic_api_key", null)
        set(value) = prefs.edit().putString("anthropic_api_key", value).apply()

    var anthropicModel: String
        get() = prefs.getString("anthropic_model", "claude-sonnet-4-20250514") ?: "claude-sonnet-4-20250514"
        set(value) = prefs.edit().putString("anthropic_model", value).apply()

    var geminiModel: String
        get() = prefs.getString("gemini_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(value) = prefs.edit().putString("gemini_model", value).apply()

    /** Max output tokens for OpenAI-compatible providers (per-model setting). */
    var openaiMaxTokens: Int
        get() = prefs.getInt("openai_max_tokens", 8192)
        set(value) = prefs.edit().putInt("openai_max_tokens", value.coerceIn(256, 131072)).apply()

    /**
     * Context window of the OpenAI-compatible endpoint, in tokens.
     *
     * Zero means "work it out from the base URL" - see the provider registry.
     * The hosted API's 128k is real, but this client also points at whatever
     * the Base URL says, and a local Ollama or llama.cpp server behind a
     * custom URL typically runs with 4k or 8k. Trusting 128k there meant the
     * engine never compacted, shipped prompts far past what the server could
     * hold - minutes of prefill - and then got refused with "Prompt exceeds
     * context size".
     */
    var openaiContextLimit: Int
        get() = prefs.getInt("openai_context_limit", 0)
        set(value) = prefs.edit().putInt("openai_context_limit", value.coerceIn(0, 2_000_000)).apply()

    fun clearApiKey() = prefs.edit().remove("gemini_api_key").apply()

    fun isCommandAlwaysAllowed(command: String): Boolean {
        val whitelist = prefs.getStringSet("allowed_commands", emptySet()) ?: emptySet()
        return whitelist.contains(command)
    }

    fun allowCommandAlways(command: String) {
        val whitelist = (prefs.getStringSet("allowed_commands", emptySet()) ?: emptySet()).toMutableSet()
        whitelist.add(command)
        prefs.edit().putStringSet("allowed_commands", whitelist).apply()
    }

    var autoApplyChanges: Boolean
        get() = prefs.getBoolean("auto_apply_changes", false)
        set(value) = prefs.edit().putBoolean("auto_apply_changes", value).apply()

    var webSearchEnabled: Boolean
        get() = prefs.getBoolean("web_search_enabled", true)
        set(value) = prefs.edit().putBoolean("web_search_enabled", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    // ── Local AI (llama.cpp runtime) ─────────────────────────────────

    /**
     * Let the app pick CPU threads and GPU layers for the current SoC.
     *
     * The runtime still lowers GPU offload when the model does not fit, so
     * this is a performance target, not an unsafe "always all layers" promise.
     */
    var autoTuneLocal: Boolean
        get() = prefs.getBoolean("llama_auto_tune", true)
        set(value) = prefs.edit().putBoolean("llama_auto_tune", value).apply()

    /** GPU layer offload: default 99 (-ngl 99 = all layers on GPU for any model), 0 = CPU, -1 = max. */
    var nGpuLayers: Int
        get() = prefs.getInt("llama_n_gpu_layers", 99)
        set(value) = prefs.edit().putInt("llama_n_gpu_layers", value).apply()

    var nThreads: Int
        get() = prefs.getInt(
            "llama_n_threads",
            HardwareManager(context).getOptimalThreadCount(),
        )
        set(value) = prefs.edit().putInt("llama_n_threads", value.coerceIn(1, 8)).apply()

    /** Threads the next local request should use. */
    fun effectiveThreadCount(): Int =
        if (autoTuneLocal) HardwareManager(context).getOptimalThreadCount() else nThreads

    /**
     * -1 means "every layer"; the bridge clamps it to the model's real layer
     * count and lowers it until the model fits in GPU memory.
     */
    fun effectiveGpuLayers(): Int = if (autoTuneLocal) -1 else nGpuLayers

    var contextLength: Int
        get() = prefs.getInt("llama_context", 4096)
        set(value) = prefs.edit().putInt("llama_context", value.coerceIn(512, 16384)).apply()
}
