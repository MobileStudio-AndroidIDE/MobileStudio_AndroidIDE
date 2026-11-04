package com.aide.mobilestudio.ai.resource

/**
 * A conservative snapshot of memory pressure. Measured values (Android's
 * available/total and the JVM heap) are kept separate from estimates; the manager
 * never treats an estimate as a measurement.
 */
data class MemorySnapshot(
    val availableBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean,
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
) {
    val availableMb: Long get() = availableBytes / (1024 * 1024)
    val heapUsedFraction: Double
        get() = if (heapMaxBytes <= 0) 0.0 else heapUsedBytes.toDouble() / heapMaxBytes.toDouble()

    companion object {
        /** Java-heap-only estimate, used when Android memory info is unavailable. */
        fun fromJvm(): MemorySnapshot {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            return MemorySnapshot(
                availableBytes = rt.freeMemory(),
                totalBytes = rt.maxMemory(),
                lowMemory = false,
                heapUsedBytes = used,
                heapMaxBytes = rt.maxMemory(),
            )
        }
    }
}

/** Decides how many independent tasks may run at once, and why. */
data class ParallelismDecision(val count: Int, val reason: String)

/**
 * Adaptive resource policy. Starts conservative and only widens when memory
 * reports headroom. It deliberately distinguishes tool-level parallelism from
 * model-level inference: loading local models reduces the task budget because a
 * loaded model consumes memory that is not reflected by free heap alone.
 */
class ResourceManager(private val policy: Policy = Policy()) {

    data class Policy(
        val minParallelism: Int = 1,
        val maxParallelism: Int = 5,
        val lowMemoryAvailableBytes: Long = 900L * 1024 * 1024,
        val criticalAvailableBytes: Long = 400L * 1024 * 1024,
        /** Estimated resident memory a single loaded local model needs. */
        val modelReserveBytes: Long = 1_500L * 1024 * 1024,
        val heapPressureFraction: Double = 0.85,
    )

    fun decide(
        snapshot: MemorySnapshot,
        pendingTasks: Int,
        loadedLocalModels: Int,
        buildRunning: Boolean,
        userMax: Int,
    ): ParallelismDecision {
        if (pendingTasks <= 0) return ParallelismDecision(0, "no pending tasks")

        val cap = minOf(policy.maxParallelism, userMax.coerceAtLeast(policy.minParallelism))
        val reasons = mutableListOf<String>()
        var limit = cap

        when {
            snapshot.lowMemory -> { limit = minOf(limit, 1); reasons += "system reports low memory" }
            snapshot.availableBytes < policy.criticalAvailableBytes -> {
                limit = minOf(limit, 1); reasons += "critical free memory (${snapshot.availableMb}MB)"
            }
            snapshot.availableBytes < policy.lowMemoryAvailableBytes -> {
                limit = minOf(limit, 2); reasons += "low free memory (${snapshot.availableMb}MB)"
            }
        }

        if (snapshot.heapUsedFraction > policy.heapPressureFraction) {
            limit = minOf(limit, 2)
            reasons += "high heap usage (${(snapshot.heapUsedFraction * 100).toInt()}%)"
        }

        if (loadedLocalModels > 0) {
            limit = minOf(limit, 2)
            reasons += "$loadedLocalModels local model(s) resident"
        }

        if (buildRunning) {
            limit = minOf(limit, 2)
            reasons += "build in progress"
        }

        val count = limit.coerceIn(policy.minParallelism, cap).coerceAtMost(pendingTasks)
        val reason = if (reasons.isEmpty()) "resources sufficient" else reasons.joinToString("; ")
        return ParallelismDecision(count, reason)
    }

    /**
     * Whether another local model may be loaded. Uses an explicit estimate, so the
     * result states it is based on an estimate rather than a measurement.
     */
    fun canLoadModel(snapshot: MemorySnapshot, alreadyLoaded: Int): Pair<Boolean, String> {
        if (snapshot.lowMemory) return false to "system reports low memory"
        val reserve = policy.modelReserveBytes * (alreadyLoaded + 1)
        if (snapshot.availableBytes < reserve) {
            return false to "insufficient free memory: need ~${reserve / (1024 * 1024)}MB " +
                "(estimated), have ${snapshot.availableMb}MB"
        }
        return true to "estimated headroom available"
    }

    /**
     * Whether a build and local inference should run together. Concurrent native
     * inference and a compiler compete for CPU/memory, so this defaults to false
     * unless there is generous headroom.
     */
    fun canRunBuildWithInference(snapshot: MemorySnapshot, inferenceActive: Boolean): Boolean {
        if (!inferenceActive) return true
        if (snapshot.lowMemory) return false
        return snapshot.availableBytes > 2 * policy.lowMemoryAvailableBytes
    }
}
