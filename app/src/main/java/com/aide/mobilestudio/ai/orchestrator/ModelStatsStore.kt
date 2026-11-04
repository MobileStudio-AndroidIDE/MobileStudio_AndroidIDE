package com.aide.mobilestudio.ai.orchestrator

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Observed outcomes keyed by model id. This is deliberately a small,
 * persisted history rather than a fabricated benchmark: one success on a
 * trivial task does not make a 1B model an expert.
 */
class ModelStatsStore(private val file: File? = null) {
    private val stats = ConcurrentHashMap<String, ModelStats>()

    init {
        load()
    }

    fun get(modelId: String): ModelStats = stats[modelId] ?: ModelStats()

    fun all(): Map<String, ModelStats> = stats.toMap()

    @Synchronized
    fun record(modelId: String, latencyMs: Long, success: Boolean, result: String?, error: String?) {
        val current = stats[modelId] ?: ModelStats()
        stats[modelId] = if (success) current.success(latencyMs, result) else current.failure(latencyMs, error)
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val root = JSONObject(f.readText())
            val models = root.optJSONObject("models") ?: return
            val keys = models.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val json = models.optJSONObject(id) ?: continue
                stats[id] = ModelStats(
                    successes = json.optInt("successes"),
                    failures = json.optInt("failures"),
                    totalLatencyMs = json.optLong("totalLatencyMs"),
                    lastResult = json.optString("lastResult").ifBlank { null },
                    lastError = json.optString("lastError").ifBlank { null },
                )
            }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val models = JSONObject()
            for ((id, s) in stats) {
                models.put(id, JSONObject().apply {
                    put("successes", s.successes)
                    put("failures", s.failures)
                    put("totalLatencyMs", s.totalLatencyMs)
                    if (s.lastResult != null) put("lastResult", s.lastResult)
                    if (s.lastError != null) put("lastError", s.lastError)
                })
            }
            f.writeText(JSONObject().put("version", 1).put("models", models).toString(2))
        }
    }
}
