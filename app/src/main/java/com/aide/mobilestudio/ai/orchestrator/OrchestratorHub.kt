package com.aide.mobilestudio.ai.orchestrator

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide coordinator for delegation batches.
 *
 * One instance owns the per-root budget, the loop guard, task history and the
 * cancellation handles. The engine supplies a runner; the UI reads snapshots.
 * Nested delegation creates another batch linked by [AgentTask.parentTaskId]
 * but shares the root's budget and seen-signature set, so an agent cannot
 * escape the limits by calling delegate recursively.
 */
class OrchestratorHub(
    private val configProvider: () -> OrchestratorConfig,
    private val catalogProvider: () -> List<AgentModel>,
    private val runner: AgentRunner,
    private val store: OrchestratorStore = InMemoryOrchestratorStore(),
    val stats: ModelStatsStore = ModelStatsStore(),
    private val verifier: WorkVerifier? = null,
    private val availableSubagentTypes: () -> Set<String> = { setOf("general", "explore", "build", "plan") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUpdate: (OrchestratorSnapshot) -> Unit = {},
) {
    private val live = ConcurrentHashMap<String, OrchestratorSnapshot>()
    private val budgets = ConcurrentHashMap<String, BudgetTracker>()
    private val taskCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val seenSignatures = ConcurrentHashMap<String, MutableSet<String>>()
    private val active = ConcurrentHashMap<String, AgentOrchestrator>()
    private val taskToOrchestrator = ConcurrentHashMap<String, AgentOrchestrator>()
    private val globalLogs = CopyOnWriteArrayList<String>()
    private val batchCounter = AtomicInteger(0)
    private val persistLock = Any()

    init {
        recoverInterrupted()
    }

    suspend fun run(
        request: JSONObject,
        parentSessionId: String,
        parentTaskId: String?,
        depth: Int,
        rootSessionId: String,
    ): OrchestratorSnapshot {
        val config = configProvider()
        val root = rootSessionId.ifBlank { parentSessionId }
        val nextDepth = depth + 1
        if (nextDepth > config.maxDepth) {
            return rejected(
                root = root,
                parentTaskId = parentTaskId,
                reason = "maximum delegation depth ${config.maxDepth} reached; do the work yourself or report the limitation",
            )
        }
        val parsed = parseRequest(request, config)
        if (parsed.error != null) {
            return rejected(root = root, parentTaskId = parentTaskId, reason = parsed.error)
        }
        val specs = parsed.specs
        val total = taskCounts.computeIfAbsent(root) { AtomicInteger(0) }
        if (total.get() + specs.size > config.maxTotalTasksPerRoot) {
            return rejected(
                root = root,
                parentTaskId = parentTaskId,
                reason = "total subtask limit reached for this request (${config.maxTotalTasksPerRoot})",
            )
        }
        val seen = seenSignatures.computeIfAbsent(root) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        val duplicates = specs.mapNotNull { spec ->
            val sig = signature(spec)
            if (!seen.add(sig)) spec.description else null
        }
        if (duplicates.isNotEmpty()) {
            return rejected(
                root = root,
                parentTaskId = parentTaskId,
                reason = "delegation loop detected: this task was already delegated in this request (${duplicates.first()})",
            )
        }
        total.addAndGet(specs.size)
        val batchId = "orch_${clock()}_${batchCounter.incrementAndGet()}"
        val budget = budgets.computeIfAbsent(root) { BudgetTracker(config) }
        lateinit var orchestrator: AgentOrchestrator
        orchestrator = AgentOrchestrator(
            catalog = catalogProvider,
            runner = runner,
            config = config,
            budget = budget,
            verifier = verifier,
            availableSubagentTypes = availableSubagentTypes,
            clock = clock,
            statsSink = { modelId, latency, success, result, error ->
                stats.record(modelId, latency, success, result, error)
            },
            logSink = { line ->
                globalLogs.add("[${clock()}] $line")
                while (globalLogs.size > 400) globalLogs.removeAt(0)
            },
            onUpdate = { snapshot ->
                live[snapshot.batchId] = snapshot
                for (task in snapshot.tasks) taskToOrchestrator[task.id] = orchestrator
                // Persist running snapshots too: after process death the UI
                // must show "interrupted", not silently lose the batch.
                persist()
                onUpdate(snapshot)
            },
        )
        active[batchId] = orchestrator
        try {
            val snapshot = orchestrator.run(specs, batchId, root, parentTaskId, parentSessionId, nextDepth)
            persist()
            return snapshot
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = OrchestratorSnapshot(
                batchId = batchId,
                rootId = root,
                parentTaskId = parentTaskId,
                createdAt = clock(),
                finishedAt = clock(),
                tasks = emptyList(),
                logs = listOf("orchestrator crashed: ${e.message}"),
                budget = budget.snapshot(),
                success = false,
                summary = "orchestration failed: ${e.message}",
                verification = "not run",
            )
            live[batchId] = failure
            persist()
            return failure
        } finally {
            active.remove(batchId)
        }
    }

    /**
     * Resets the per-request budget and loop history at the start of a new
     * top-level turn. Nested batches during that turn keep sharing the same
     * tracker; a later user message must not inherit an exhausted one.
     */
    fun beginRoot(rootId: String) {
        if (rootId.isBlank()) return
        budgets.remove(rootId)
        taskCounts.remove(rootId)
        seenSignatures.remove(rootId)
    }

    fun cancel(taskId: String? = null) {
        if (taskId == null) {
            active.values.distinct().forEach { it.cancel(null) }
        } else {
            taskToOrchestrator[taskId]?.cancel(taskId)
        }
    }

    fun cancelAll() {
        active.values.distinct().forEach { it.cancel(null) }
    }

    fun snapshots(): List<OrchestratorSnapshot> {
        val all = LinkedHashMap<String, OrchestratorSnapshot>()
        for (snapshot in store.load()) all[snapshot.batchId] = snapshot
        for (snapshot in live.values) all[snapshot.batchId] = snapshot
        return all.values.sortedByDescending { it.createdAt }
    }

    fun latest(rootId: String? = null): OrchestratorSnapshot? =
        snapshots().firstOrNull { rootId == null || it.rootId == rootId }

    fun logs(): List<String> = globalLogs.toList()

    fun modelStats(): Map<String, ModelStats> = stats.all()

    private fun parseRequest(request: JSONObject, config: OrchestratorConfig): ParsedRequest {
        val array = request.optJSONArray("tasks")
            ?: if (request.optString("prompt").isNotBlank()) JSONArray().put(request) else null
            ?: return ParsedRequest(emptyList(), "delegate requires a non-empty tasks array")
        if (array.length() == 0) return ParsedRequest(emptyList(), "delegate requires at least one task")
        if (array.length() > config.maxTasksPerBatch) {
            return ParsedRequest(emptyList(), "too many subtasks: ${array.length()} (limit ${config.maxTasksPerBatch})")
        }
        // The engine adds this out-of-band value; it is not part of the model-
        // visible schema. It keeps a nested subagent from asking the
        // orchestrator for tools its own run was never granted.
        val parentTools = mutableSetOf<String>()
        request.optJSONArray("_parent_allowed_tools")?.let { hidden ->
            for (i in 0 until hidden.length().coerceAtMost(100)) {
                val tool = hidden.optString(i).trim()
                if (tool.isNotEmpty()) parentTools.add(tool)
            }
        }
        val specs = mutableListOf<TaskSpec>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i)
                ?: return ParsedRequest(emptyList(), "task ${i + 1} is not an object")
            val description = json.optString("description").trim()
            val prompt = json.optString("prompt").trim()
            if (description.isEmpty() || prompt.isEmpty()) {
                return ParsedRequest(emptyList(), "task ${i + 1} needs description and prompt")
            }
            if (description.length > 240) {
                return ParsedRequest(emptyList(), "task ${i + 1} description is too long")
            }
            if (prompt.length > 120_000) {
                return ParsedRequest(emptyList(), "task ${i + 1} prompt is too long")
            }
            val difficulty = json.optString("difficulty").trim().takeIf { it.isNotEmpty() }?.let {
                runCatching { TaskDifficulty.valueOf(it.uppercase()) }.getOrNull()
            }
            val capabilities = mutableSetOf<ModelCapability>()
            val caps = json.optJSONArray("capabilities")
            if (caps != null) {
                for (c in 0 until caps.length()) {
                    val name = caps.optString(c).trim().uppercase().replace('-', '_')
                    runCatching { ModelCapability.valueOf(name) }.getOrNull()?.let(capabilities::add)
                }
            }
            val deps = mutableListOf<Int>()
            val depsJson = json.optJSONArray("depends_on")
            if (depsJson != null) {
                for (d in 0 until depsJson.length()) {
                    val n = depsJson.optInt(d, -1)
                    if (n < 1 || n > array.length()) {
                        return ParsedRequest(
                            emptyList(),
                            "task ${i + 1}: depends_on uses 1-based task numbers (got $n)",
                        )
                    }
                    if (n - 1 == i) {
                        return ParsedRequest(emptyList(), "task ${i + 1} cannot depend on itself")
                    }
                    deps.add(n - 1)
                }
            }
            val files = mutableListOf<String>()
            val filesJson = json.optJSONArray("files")
            if (filesJson != null) {
                for (f in 0 until filesJson.length().coerceAtMost(50)) {
                    val file = filesJson.optString(f).trim()
                    if (file.length in 1..500) files.add(file)
                }
            }
            val tools = mutableSetOf<String>()
            val toolsJson = json.optJSONArray("tools")
            if (toolsJson != null) {
                for (t in 0 until toolsJson.length().coerceAtMost(50)) {
                    val tool = toolsJson.optString(t).trim()
                    if (tool.isNotEmpty()) tools.add(tool)
                }
            }
            val timeoutSeconds = json.optLong("timeout_seconds", 0L)
            specs.add(
                TaskSpec(
                    description = description,
                    prompt = prompt,
                    difficulty = difficulty,
                    requiredCapabilities = capabilities,
                    modelId = json.optString("model").trim().ifBlank { null },
                    subagentType = json.optString("subagent_type", "general").trim().ifBlank { "general" },
                    dependencies = deps,
                    timeoutMs = timeoutSeconds.takeIf { it > 0 }?.times(1_000),
                    inputContext = json.optString("context").trim().takeIf { it.isNotEmpty() }
                        ?.take(60_000),
                    filesHint = files,
                    allowedTools = tools,
                    toolCeiling = parentTools,
                ),
            )
        }
        return ParsedRequest(specs, null)
    }

    private fun signature(spec: TaskSpec): String {
        val raw = spec.description.trim() + "\u0000" + spec.prompt.trim() + "\u0000" +
            spec.inputContext.orEmpty().trim()
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun rejected(root: String, parentTaskId: String?, reason: String): OrchestratorSnapshot {
        val now = clock()
        val snapshot = OrchestratorSnapshot(
            batchId = "orch_rejected_${now}_${batchCounter.incrementAndGet()}",
            rootId = root,
            parentTaskId = parentTaskId,
            createdAt = now,
            finishedAt = now,
            tasks = emptyList(),
            logs = listOf(reason),
            budget = budgets[root]?.snapshot() ?: BudgetSnapshot(0, 0, 0, null, 0, 0, null),
            success = false,
            summary = reason,
            verification = "not run",
        )
        live[snapshot.batchId] = snapshot
        persist()
        onUpdate(snapshot)
        return snapshot
    }

    private fun persist() {
        synchronized(persistLock) {
            val all = LinkedHashMap<String, OrchestratorSnapshot>()
            for (snapshot in store.load()) all[snapshot.batchId] = snapshot
            for (snapshot in live.values) all[snapshot.batchId] = snapshot
            val ordered = all.values.sortedByDescending { it.createdAt }.take(20)
            store.save(ordered)
        }
    }

    /**
     * There is no live provider process after process death. Showing a task as
     * RUNNING forever would be false, so unfinished work is marked interrupted
     * and the summary says exactly that.
     */
    private fun recoverInterrupted() {
        val loaded = runCatching { store.load() }.getOrDefault(emptyList())
        if (loaded.isEmpty()) return
        val now = clock()
        val recovered = loaded.map { snapshot ->
            if (snapshot.tasks.none { !it.isFinished }) snapshot
            else snapshot.copy(
                finishedAt = now,
                success = false,
                summary = "interrupted by app restart; unfinished tasks were not completed",
                verification = "not run",
                tasks = snapshot.tasks.map {
                    if (it.isFinished) it else it.copy(
                        status = TaskStatus.FAILED,
                        error = "app restarted before the task completed",
                        finishedAt = now,
                    )
                },
            )
        }
        runCatching { store.save(recovered.take(20)) }
    }

    private data class ParsedRequest(val specs: List<TaskSpec>, val error: String?)
}
