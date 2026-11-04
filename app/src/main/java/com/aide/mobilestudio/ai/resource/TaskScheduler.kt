package com.aide.mobilestudio.ai.resource

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ScheduleOutcome<T>(
    val results: List<T>,
    val parallelism: Int,
    val reason: String,
)

/**
 * Runs a list of independent tasks with a parallelism chosen by [ResourceManager].
 * Order of results matches the input order. When the policy allows only one task
 * the batch runs sequentially, which is the safe default under memory pressure.
 */
class TaskScheduler(private val resourceManager: ResourceManager = ResourceManager()) {

    suspend fun <T> runAll(
        tasks: List<suspend () -> T>,
        snapshot: MemorySnapshot,
        userMax: Int = 4,
        loadedLocalModels: Int = 0,
        buildRunning: Boolean = false,
    ): ScheduleOutcome<T> = coroutineScope {
        val decision = resourceManager.decide(snapshot, tasks.size, loadedLocalModels, buildRunning, userMax)
        when {
            tasks.isEmpty() -> ScheduleOutcome(emptyList(), 0, decision.reason)
            decision.count <= 1 -> ScheduleOutcome(tasks.map { it() }, 1, decision.reason)
            else -> {
                val semaphore = Semaphore(decision.count)
                val results = tasks
                    .map { task -> async { semaphore.withPermit { task() } } }
                    .awaitAll()
                ScheduleOutcome(results, decision.count, decision.reason)
            }
        }
    }
}
