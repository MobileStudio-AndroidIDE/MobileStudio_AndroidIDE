package com.aide.mobilestudio.ai.orchestrator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Executes one delegation batch. It is deliberately independent of Android:
 * the engine supplies a real [AgentRunner], tests supply fakes.
 *
 * A batch is scheduled in waves. Tasks in a wave are independent, have no
 * overlapping declared files, and fit the current concurrency/memory budget.
 * The next wave starts only after the previous one settles, which makes the
 * dependency and failure propagation deterministic.
 */
class AgentOrchestrator(
    private val catalog: () -> List<AgentModel>,
    private val runner: AgentRunner,
    private val config: OrchestratorConfig,
    private val budget: BudgetTracker,
    private val verifier: WorkVerifier? = null,
    private val availableSubagentTypes: () -> Set<String> = { setOf("general", "explore", "build", "plan") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val statsSink: (modelId: String, latencyMs: Long, success: Boolean, result: String?, error: String?) -> Unit =
        { _, _, _, _, _ -> },
    private val logSink: (String) -> Unit = {},
    private val onUpdate: (OrchestratorSnapshot) -> Unit = {},
) {
    private val lock = Any()
    private val selector = ModelSelector(config)
    private val cancelSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val unavailableModelIds = ConcurrentHashMap.newKeySet<String>()
    private val modelGates = ConcurrentHashMap<String, Semaphore>()
    private val globallyCancelled = AtomicBoolean(false)

    private var batchId = ""
    private var rootId = ""
    private var parentTaskId: String? = null
    private var createdAt = 0L
    private var finishedAt: Long? = null
    private var tasks: List<AgentTask> = emptyList()
    private val logs = mutableListOf<String>()
    private var finalSuccess: Boolean? = null
    private var finalSummary = ""
    private var finalVerification: String? = null

    suspend fun run(
        specs: List<TaskSpec>,
        batchId: String,
        rootId: String,
        parentTaskId: String?,
        parentSessionId: String,
        depth: Int,
    ): OrchestratorSnapshot {
        this.batchId = batchId
        this.rootId = rootId
        this.parentTaskId = parentTaskId
        this.createdAt = clock()
        val availableTypes = availableSubagentTypes()
        val built = TaskGraph.build(
            batchId = batchId,
            rootId = rootId,
            parentTaskId = parentTaskId,
            parentSessionId = parentSessionId,
            depth = depth,
            specs = specs,
            now = createdAt,
            maxTasks = config.maxTasksPerBatch,
            defaultTimeoutMs = config.defaultTimeoutMs,
            availableSubagentTypes = availableTypes,
        )
        when (built) {
            is BatchBuildResult.Rejected -> {
                log("rejected: ${built.reason}")
                synchronized(lock) {
                    finalSuccess = false
                    finalSummary = built.reason
                    finishedAt = clock()
                }
                return publish()
            }
            is BatchBuildResult.Success -> {
                synchronized(lock) { tasks = built.tasks }
            }
        }
        publish()

        var timedOut = false
        val loopCompleted = withTimeoutOrNull(config.totalTimeoutMs) {
            executeWaves()
            true
        } ?: false
        if (loopCompleted != true) {
            timedOut = true
            log("total delegation timeout after ${config.totalTimeoutMs}ms")
            synchronized(lock) {
                tasks = tasks.map { task ->
                    if (task.isFinished) task
                    else task.copy(
                        status = TaskStatus.TIMED_OUT,
                        error = task.error ?: "total delegation timeout",
                        finishedAt = clock(),
                    )
                }
            }
        }
        finalize(timedOut)
        return publish()
    }

    private suspend fun executeWaves() {
        while (!globallyCancelled.get()) {
            val wave = scheduleWave()
            if (wave.isEmpty()) {
                val waiting = synchronized(lock) {
                    tasks.filter { !it.isFinished }
                }
                if (waiting.isEmpty()) break
                // Nothing can be scheduled and nothing is running: the
                // remaining tasks are blocked by a dependency that will never
                // succeed. Refresh marks them failed, then the loop exits.
                synchronized(lock) { refreshDependencies() }
                val stillWaiting = synchronized(lock) { tasks.filter { !it.isFinished && it.status == TaskStatus.READY } }
                if (stillWaiting.isEmpty()) break
                continue
            }
            val results = supervisorScope {
                wave.map { (task, model) ->
                    async { executeTask(task, model) }
                }.awaitAll()
            }
            synchronized(lock) {
                for (result in results) {
                    if (cancelledIds.contains(result.id)) {
                        replaceTask(result.copy(
                            status = TaskStatus.CANCELLED,
                            finishedAt = result.finishedAt ?: clock(),
                            error = "cancelled by user",
                        ))
                    } else {
                        replaceTask(result)
                    }
                }
                refreshDependencies()
            }
            publish()
        }
        if (globallyCancelled.get()) {
            synchronized(lock) {
                tasks = tasks.map {
                    if (it.isFinished) it else it.copy(
                        status = TaskStatus.CANCELLED,
                        error = "cancelled by user",
                        finishedAt = clock(),
                    )
                }
            }
        }
    }

    /**
     * Assigns models before launching a wave. A model at its concurrency limit
     * is left for the next wave; a task with no capable model is failed with
     * an explanation instead of being quietly assigned to a weaker one.
     */
    private fun scheduleWave(): List<Pair<AgentTask, AgentModel>> {
        val now = clock()
        return synchronized(lock) {
            val ready = tasks.filter { it.status == TaskStatus.READY && !cancelledIds.contains(it.id) }
            if (ready.isEmpty()) return@synchronized emptyList()
            val models = catalog()
            val active = mutableMapOf<String, Int>()
            // ModelSelector applies its own 15% headroom; do not discount twice.
            var memoryLeft = config.availableMemoryBytes
            val claimedFiles = mutableSetOf<String>()
            val selected = mutableListOf<Pair<AgentTask, AgentModel>>()
            val failures = mutableListOf<Pair<AgentTask, String>>()
            val limit = config.memoryAdjustedParallel().coerceAtLeast(1)

            for (task in ready) {
                if (selected.size >= limit) break
                if (task.filesHint.any { normalizeFile(it) in claimedFiles }) continue
                val selection = selector.select(
                    task = task,
                    models = models,
                    excludedModelIds = unavailableModelIds,
                    activeByModel = active,
                    availableMemoryBytes = memoryLeft,
                )
                if (selection == null) {
                    val alone = selector.select(
                        task = task,
                        models = models,
                        excludedModelIds = unavailableModelIds,
                        activeByModel = emptyMap(),
                        availableMemoryBytes = config.availableMemoryBytes,
                    )
                    if (alone != null) continue // capacity/memory: try next wave
                    failures.add(task to noModelReason(task, models))
                    continue
                }
                selected.add(task to selection.model)
                active.merge(selection.model.id, 1, Int::plus)
                if (selection.model.provider == ModelProvider.LOCAL) {
                    val weights = selection.model.localWeightsBytes ?: 0
                    memoryLeft = (memoryLeft ?: Long.MAX_VALUE) - weights
                }
                claimedFiles.addAll(task.filesHint.map(::normalizeFile))
            }

            // If conflicts left the wave empty while ready work exists, run
            // one task alone so the batch cannot deadlock on file hints.
            if (selected.isEmpty() && failures.isEmpty() && ready.isNotEmpty()) {
                val first = ready.first()
                val selection = selector.select(
                    first, models, unavailableModelIds, emptyMap(), config.availableMemoryBytes,
                )
                if (selection != null) selected.add(first to selection.model)
                else failures.add(first to noModelReason(first, models))
            }

            val selectedIds = selected.map { it.first.id }.toSet()
            tasks = tasks.map { task ->
                when {
                    task.id in selectedIds -> {
                        val model = selected.first { it.first.id == task.id }.second
                        task.copy(
                            status = TaskStatus.RUNNING,
                            assignedModelId = model.id,
                            startedAt = now,
                        )
                    }
                    task.id in failures.map { it.first.id } -> task.copy(
                        status = TaskStatus.FAILED,
                        error = failures.first { it.first.id == task.id }.second,
                        finishedAt = now,
                    )
                    else -> task
                }
            }
            for ((task, reason) in failures) log("${task.id}: $reason")
            selected
        }.also { if (it.isNotEmpty()) { publish() } }
    }

    private suspend fun executeTask(initial: AgentTask, initialModel: AgentModel): AgentTask {
        var task = initial
        var model: AgentModel? = initialModel
        var sameModelRetries = 0
        var promotions = 0
        var lastKind = AgentErrorKind.UNKNOWN
        val excluded = mutableSetOf<String>()

        while (true) {
            if (globallyCancelled.get() || cancelledIds.contains(task.id)) {
                return task.copy(status = TaskStatus.CANCELLED, error = "cancelled by user", finishedAt = clock())
            }
            val selected = model ?: selector.select(
                task = task,
                models = catalog(),
                excludedModelIds = unavailableModelIds + excluded,
                activeByModel = emptyMap(),
                availableMemoryBytes = config.availableMemoryBytes,
            )?.model
            if (selected == null) {
                return task.copy(
                    status = TaskStatus.FAILED,
                    error = "no eligible model for this task after ${task.modelHistory.size} attempt(s)",
                    finishedAt = clock(),
                )
            }
            model = selected
            val budgetDecision = budget.canStart(selected.provider == ModelProvider.API)
            if (!budgetDecision.allowed) {
                return task.copy(
                    status = TaskStatus.FAILED,
                    error = "budget stopped the task: ${budgetDecision.reason}",
                    finishedAt = clock(),
                )
            }

            task = task.copy(assignedModelId = selected.id)
            val started = clock()
            if (selected.provider == ModelProvider.API) budget.recordApiCall()
            val result = try {
                withModelGate(selected) {
                    runAttempt(task, selected)
                }
            } catch (e: CancellationException) {
                if (globallyCancelled.get() || cancelledIds.contains(task.id)) {
                    return task.copy(status = TaskStatus.CANCELLED, error = "cancelled by user", finishedAt = clock())
                }
                throw e
            } catch (e: Exception) {
                AgentRunResult.failure(
                    e.message ?: e.javaClass.simpleName,
                    classifyException(e),
                )
            }
            val latency = clock() - started
            val tokensIn = result.tokensIn.takeIf { it > 0 }
                ?: ((task.prompt.length + (task.inputContext?.length ?: 0)) / 4).toLong()
            val tokensOut = result.tokensOut.takeIf { it > 0 }
                ?: (result.output.length / 4).toLong()
            val cost = when {
                selected.provider == ModelProvider.LOCAL -> 0.0
                result.costUsd != null -> result.costUsd
                selected.inputCostPerMillion != null && selected.outputCostPerMillion != null ->
                    tokensIn / 1_000_000.0 * selected.inputCostPerMillion +
                        tokensOut / 1_000_000.0 * selected.outputCostPerMillion
                else -> null
            }
            budget.recordUsage(tokensIn, tokensOut, cost)
            statsSink(
                selected.id,
                latency,
                result.success,
                if (result.success) result.summary else null,
                if (result.success) null else (result.errors.firstOrNull() ?: result.summary),
            )
            val attempt = ModelAttempt(
                modelId = selected.id,
                success = result.success,
                error = if (result.success) null else (result.errors.firstOrNull() ?: result.summary),
                latencyMs = latency,
                promoted = promotions > 0,
            )
            task = task.copy(
                retryCount = task.retryCount + 1,
                modelHistory = task.modelHistory + attempt,
                error = if (result.success) null else attempt.error,
            )
            log(
                "${task.id}: ${selected.id} ${if (result.success) "succeeded" else "failed"} " +
                    "in ${latency}ms (${result.errorKind.name.lowercase()})",
            )

            if (result.success) {
                val finalResult = TaskResult(
                    taskId = task.id,
                    success = true,
                    summary = result.summary.ifBlank { result.output.take(300) },
                    output = result.output,
                    changedFiles = result.changedFiles,
                    diff = result.diff,
                    errors = result.errors,
                    verification = result.verification,
                    needsFollowUp = result.needsFollowUp,
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                    tokensEstimated = result.tokensEstimated,
                    costUsd = cost,
                )
                return task.copy(status = TaskStatus.SUCCEEDED, result = finalResult, finishedAt = clock())
            }

            lastKind = result.errorKind
            when (result.errorKind) {
                AgentErrorKind.CANCELLED -> return task.copy(
                    status = TaskStatus.CANCELLED, error = "cancelled by user", finishedAt = clock(),
                )
                AgentErrorKind.AUTH,
                AgentErrorKind.QUOTA_EXHAUSTED,
                AgentErrorKind.MODEL_UNSUPPORTED,
                -> {
                    // These are properties of the model/provider, not of this
                    // one prompt: do not send every other task into the same
                    // broken credential or unsupported model.
                    unavailableModelIds.add(selected.id)
                    excluded.add(selected.id)
                }
                else -> {
                    if (result.retryable && sameModelRetries < config.maxRetriesPerTask) {
                        sameModelRetries++
                        task = task.copy(
                            inputContext = appendRetryContext(task, result, retryingSameModel = true),
                        )
                        delay(backoffMs(sameModelRetries, result.errorKind))
                        continue
                    }
                    excluded.add(selected.id)
                }
            }

            if (promotions >= config.maxPromotions) {
                return task.copy(
                    status = if (lastKind == AgentErrorKind.TIMEOUT) TaskStatus.TIMED_OUT else TaskStatus.FAILED,
                    error = buildString {
                        append("task failed after ${task.modelHistory.size} attempt(s): ")
                        append(task.modelHistory.lastOrNull()?.error ?: "unknown error")
                    },
                    finishedAt = clock(),
                )
            }
            promotions++
            sameModelRetries = 0
            model = null
            task = task.copy(
                difficulty = task.difficulty.next(),
                inputContext = appendRetryContext(task, result, retryingSameModel = false),
                preferredModelId = null,
            )
            // A promoted attempt gets a fresh, stronger model; the previous
            // errors and context travel in inputContext.
            log("${task.id}: promoting to ${if (task.difficulty.rank >= TaskDifficulty.EXPERT.rank) "expert" else task.difficulty.name.lowercase()} model")
        }
    }

    private suspend fun runAttempt(task: AgentTask, model: AgentModel): AgentRunResult {
        val signal = CompletableDeferred<Unit>()
        cancelSignals[task.id] = signal
        return supervisorScope {
            val work = async { runner.run(task, model, task.depth) }
            val canceller = launch {
                signal.await()
                work.cancel()
            }
            try {
                withTimeout(task.timeoutMs) { work.await() }
            } catch (e: TimeoutCancellationException) {
                work.cancel()
                AgentRunResult.timeout("task timed out after ${task.timeoutMs}ms")
            } catch (e: CancellationException) {
                if (globallyCancelled.get() || cancelledIds.contains(task.id)) {
                    AgentRunResult.failure("cancelled by user", AgentErrorKind.CANCELLED, retryable = false)
                } else {
                    throw e
                }
            } finally {
                canceller.cancel()
                cancelSignals.remove(task.id)
            }
        }
    }

    private suspend fun <T> withModelGate(model: AgentModel, block: suspend () -> T): T {
        val limit = model.maxConcurrent.coerceAtLeast(1)
        val gate = modelGates.computeIfAbsent(model.id) { Semaphore(limit) }
        return gate.withPermit { block() }
    }

    private fun refreshDependencies() {
        var changed = true
        while (changed) {
            changed = false
            for (task in tasks) {
                if (task.status != TaskStatus.WAITING_FOR_DEPENDENCY) continue
                val deps = tasks.filter { it.id in task.dependencies }
                if (deps.any { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT || it.status == TaskStatus.CANCELLED }) {
                    replaceTask(task.copy(
                        status = TaskStatus.FAILED,
                        error = "dependency failed: " + deps.filter {
                            it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT || it.status == TaskStatus.CANCELLED
                        }.joinToString(", ") { "${it.id}:${it.error ?: it.status.name.lowercase()}" },
                        finishedAt = clock(),
                    ))
                    changed = true
                } else if (deps.all { it.status == TaskStatus.SUCCEEDED }) {
                    replaceTask(task.copy(status = TaskStatus.READY))
                    changed = true
                }
            }
        }
    }

    private suspend fun finalize(timedOut: Boolean) {
        val verification = try {
            verifier?.verify(tasks, tasks.mapNotNull { it.result })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VerificationResult(true, false, "verification failed: ${e.message}")
        }
        val integration = ResultIntegrator.integrate(tasks, verification)
        synchronized(lock) {
            finishedAt = clock()
            finalSuccess = integration.success && !timedOut
            finalSummary = integration.summary + if (timedOut) " (total timeout)" else ""
            finalVerification = integration.verification
        }
        log(finalSummary)
        if (verification != null) log("verification: ${verification.detail}")
    }

    fun cancel(taskId: String? = null) {
        if (taskId == null) {
            globallyCancelled.set(true)
            synchronized(lock) {
                val now = clock()
                tasks = tasks.map {
                    if (it.isFinished) it else it.copy(
                        status = TaskStatus.CANCELLED,
                        error = "cancelled by user",
                        finishedAt = now,
                    )
                }
                tasks.forEach { cancelledIds.add(it.id) }
                // Make the cancellation durable even if the parent coroutine is
                // torn down before the normal finalize path runs.
                if (finishedAt == null) {
                    finalSuccess = false
                    finalSummary = "cancelled by user"
                    finishedAt = now
                }
            }
            cancelSignals.values.forEach { it.complete(Unit) }
        } else {
            cancelledIds.add(taskId)
            synchronized(lock) {
                tasks = tasks.map {
                    if (it.id == taskId && !it.isFinished) it.copy(
                        status = TaskStatus.CANCELLED,
                        error = "cancelled by user",
                        finishedAt = clock(),
                    ) else it
                }
            }
            cancelSignals[taskId]?.complete(Unit)
        }
        publish()
    }

    fun snapshot(): OrchestratorSnapshot = buildSnapshot()

    private fun publish(): OrchestratorSnapshot {
        val snapshot = buildSnapshot()
        logSink("batch ${snapshot.batchId}: ${snapshot.summary}")
        onUpdate(snapshot)
        return snapshot
    }

    private fun buildSnapshot(): OrchestratorSnapshot = synchronized(lock) {
        OrchestratorSnapshot(
            batchId = batchId,
            rootId = rootId,
            parentTaskId = parentTaskId,
            createdAt = createdAt,
            finishedAt = finishedAt,
            tasks = tasks.toList(),
            logs = logs.toList(),
            budget = budget.snapshot(),
            success = finalSuccess,
            summary = finalSummary,
            verification = finalVerification,
        )
    }

    private fun replaceTask(task: AgentTask) {
        tasks = tasks.map { if (it.id == task.id) task else it }
    }

    private fun log(message: String) {
        synchronized(lock) {
            logs.add("[${clock()}] $message")
            while (logs.size > 250) logs.removeAt(0)
        }
    }

    private fun appendRetryContext(task: AgentTask, result: AgentRunResult, retryingSameModel: Boolean): String {
        val previous = task.inputContext.orEmpty()
        val attempt = result.errors.firstOrNull() ?: result.summary
        return buildString {
            if (previous.isNotBlank()) append(previous).append("\n\n")
            append("--- ORCHESTRATOR RETRY CONTEXT ---\n")
            if (retryingSameModel) {
                append("The previous attempt by the same model failed. Do not repeat the same solution.\n")
            } else {
                append("This task was promoted to a stronger model. Use the previous work and errors below.\n")
            }
            append("Previous error: ").append(attempt).append('\n')
            val prior = task.modelHistory.takeLast(4)
            if (prior.isNotEmpty()) {
                append("Previous attempts:\n")
                for (a in prior) {
                    append("- ").append(a.modelId).append(": ")
                        .append(if (a.success) "succeeded" else (a.error ?: "failed")).append('\n')
                }
            }
        }
    }

    private fun backoffMs(attempt: Int, kind: AgentErrorKind): Long = when (kind) {
        AgentErrorKind.RATE_LIMIT -> 2_000L * attempt
        AgentErrorKind.NETWORK -> 500L * attempt
        AgentErrorKind.MEMORY -> 1_500L * attempt
        else -> 250L * attempt
    }

    private fun classifyException(e: Exception): AgentErrorKind {
        val name = e.javaClass.name.lowercase()
        return when {
            e is TimeoutCancellationException -> AgentErrorKind.TIMEOUT
            name.contains("timeout") -> AgentErrorKind.TIMEOUT
            name.contains("auth") || name.contains("permission") -> AgentErrorKind.AUTH
            name.contains("outofmemory") -> AgentErrorKind.MEMORY
            name.contains("ioexception") || name.contains("network") || name.contains("connect") ->
                AgentErrorKind.NETWORK
            else -> AgentErrorKind.UNKNOWN
        }
    }

    private fun noModelReason(task: AgentTask, models: List<AgentModel>): String {
        val available = models.filter { it.available }
        val missing = task.requiredCapabilities.filter { cap ->
            available.none { cap in it.capabilities }
        }
        return buildString {
            append("no eligible model for ").append(task.difficulty.name.lowercase()).append(" task")
            if (missing.isNotEmpty()) {
                append("; unsupported capabilities: ")
                append(missing.joinToString(", ") { it.name.lowercase() })
            }
            if (available.isEmpty()) {
                append("; no models are currently available (configure an API key or install a local GGUF)")
            } else {
                append("; available: ")
                append(available.joinToString(", ") { "${it.displayName} (${it.capabilities.joinToString("+") { c -> c.name.lowercase() }})" })
            }
            if (task.filesHint.isNotEmpty()) append("; files=").append(task.filesHint.joinToString(","))
        }
    }

    private fun normalizeFile(path: String): String =
        path.replace('\\', '/').trim().removePrefix("./").trimStart('/')
}
