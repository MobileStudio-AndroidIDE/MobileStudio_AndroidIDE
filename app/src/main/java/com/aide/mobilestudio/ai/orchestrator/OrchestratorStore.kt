package com.aide.mobilestudio.ai.orchestrator

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface OrchestratorStore {
    fun load(): List<OrchestratorSnapshot>
    fun save(snapshots: List<OrchestratorSnapshot>)
}

class InMemoryOrchestratorStore : OrchestratorStore {
    private var snapshots: List<OrchestratorSnapshot> = emptyList()
    override fun load(): List<OrchestratorSnapshot> = snapshots
    override fun save(snapshots: List<OrchestratorSnapshot>) {
        this.snapshots = snapshots
    }
}

/**
 * Task history survives an app restart. Running work does not: there is no
 * live provider process to reattach to, so [OrchestratorHub] marks unfinished
 * tasks as failed with an explicit reason instead of showing them as running
 * forever.
 */
class JsonOrchestratorStore(private val file: File) : OrchestratorStore {

    override fun load(): List<OrchestratorSnapshot> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("batches") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::readSnapshot)
            }
        }.getOrDefault(emptyList())
    }

    override fun save(snapshots: List<OrchestratorSnapshot>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            snapshots.takeLast(MAX_BATCHES).forEach { array.put(writeSnapshot(it)) }
            file.writeText(JSONObject().put("version", 1).put("batches", array).toString(2))
        }
    }

    private fun writeSnapshot(snapshot: OrchestratorSnapshot): JSONObject = JSONObject().apply {
        put("batchId", snapshot.batchId)
        put("rootId", snapshot.rootId)
        if (snapshot.parentTaskId != null) put("parentTaskId", snapshot.parentTaskId)
        put("createdAt", snapshot.createdAt)
        if (snapshot.finishedAt != null) put("finishedAt", snapshot.finishedAt)
        put("success", snapshot.success ?: JSONObject.NULL)
        put("summary", snapshot.summary)
        if (snapshot.verification != null) put("verification", snapshot.verification)
        put("logs", JSONArray(snapshot.logs))
        put("budget", writeBudget(snapshot.budget))
        put("tasks", JSONArray().apply { snapshot.tasks.forEach { put(writeTask(it)) } })
    }

    private fun readSnapshot(json: JSONObject): OrchestratorSnapshot? {
        val batchId = json.optString("batchId").ifBlank { return null }
        val tasksArray = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = (0 until tasksArray.length()).mapNotNull { i ->
            tasksArray.optJSONObject(i)?.let(::readTask)
        }
        val logs = mutableListOf<String>()
        val logsArray = json.optJSONArray("logs") ?: JSONArray()
        for (i in 0 until logsArray.length()) logs.add(logsArray.optString(i))
        return OrchestratorSnapshot(
            batchId = batchId,
            rootId = json.optString("rootId", batchId),
            parentTaskId = json.optString("parentTaskId").ifBlank { null },
            createdAt = json.optLong("createdAt", 0),
            finishedAt = if (json.has("finishedAt") && !json.isNull("finishedAt")) json.optLong("finishedAt") else null,
            tasks = tasks,
            logs = logs,
            budget = readBudget(json.optJSONObject("budget")),
            success = if (json.has("success") && !json.isNull("success")) json.optBoolean("success") else null,
            summary = json.optString("summary", ""),
            verification = json.optString("verification").ifBlank { null },
        )
    }

    private fun writeTask(task: AgentTask): JSONObject = JSONObject().apply {
        put("id", task.id)
        put("batchId", task.batchId)
        if (task.parentTaskId != null) put("parentTaskId", task.parentTaskId)
        put("parentSessionId", task.parentSessionId)
        put("rootId", task.rootId)
        put("description", task.description)
        put("prompt", task.prompt)
        put("difficulty", task.difficulty.name)
        put("requiredCapabilities", JSONArray(task.requiredCapabilities.map { it.name }))
        put("dependencies", JSONArray(task.dependencies))
        if (task.inputContext != null) put("inputContext", task.inputContext)
        if (task.preferredModelId != null) put("preferredModelId", task.preferredModelId)
        if (task.assignedModelId != null) put("assignedModelId", task.assignedModelId)
        put("status", task.status.name)
        put("retryCount", task.retryCount)
        put("timeoutMs", task.timeoutMs)
        put("subagentType", task.subagentType)
        put("depth", task.depth)
        put("filesHint", JSONArray(task.filesHint))
        put("allowedTools", JSONArray(task.allowedTools.toList()))
        put("createdAt", task.createdAt)
        if (task.startedAt != null) put("startedAt", task.startedAt)
        if (task.finishedAt != null) put("finishedAt", task.finishedAt)
        if (task.error != null) put("error", task.error)
        if (task.result != null) put("result", writeResult(task.result))
        put("modelHistory", JSONArray().apply {
            task.modelHistory.forEach { attempt ->
                put(JSONObject().apply {
                    put("modelId", attempt.modelId)
                    put("success", attempt.success)
                    if (attempt.error != null) put("error", attempt.error)
                    put("latencyMs", attempt.latencyMs)
                    put("promoted", attempt.promoted)
                })
            }
        })
    }

    private fun readTask(json: JSONObject): AgentTask? {
        val id = json.optString("id").ifBlank { return null }
        val capabilities = mutableSetOf<ModelCapability>()
        val caps = json.optJSONArray("requiredCapabilities") ?: JSONArray()
        for (i in 0 until caps.length()) {
            runCatching { ModelCapability.valueOf(caps.optString(i)) }.getOrNull()?.let(capabilities::add)
        }
        val deps = mutableListOf<String>()
        val depsJson = json.optJSONArray("dependencies") ?: JSONArray()
        for (i in 0 until depsJson.length()) deps.add(depsJson.optString(i))
        val files = mutableListOf<String>()
        val filesJson = json.optJSONArray("filesHint") ?: JSONArray()
        for (i in 0 until filesJson.length()) files.add(filesJson.optString(i))
        val tools = mutableSetOf<String>()
        val toolsJson = json.optJSONArray("allowedTools") ?: JSONArray()
        for (i in 0 until toolsJson.length()) tools.add(toolsJson.optString(i))
        val history = mutableListOf<ModelAttempt>()
        val historyJson = json.optJSONArray("modelHistory") ?: JSONArray()
        for (i in 0 until historyJson.length()) {
            val a = historyJson.optJSONObject(i) ?: continue
            history.add(
                ModelAttempt(
                    modelId = a.optString("modelId"),
                    success = a.optBoolean("success"),
                    error = a.optString("error").ifBlank { null },
                    latencyMs = a.optLong("latencyMs"),
                    promoted = a.optBoolean("promoted"),
                ),
            )
        }
        return AgentTask(
            id = id,
            batchId = json.optString("batchId"),
            parentTaskId = json.optString("parentTaskId").ifBlank { null },
            parentSessionId = json.optString("parentSessionId"),
            rootId = json.optString("rootId"),
            description = json.optString("description"),
            prompt = json.optString("prompt"),
            difficulty = runCatching { TaskDifficulty.valueOf(json.optString("difficulty")) }
                .getOrDefault(TaskDifficulty.NORMAL),
            requiredCapabilities = capabilities,
            dependencies = deps,
            inputContext = json.optString("inputContext").ifBlank { null },
            preferredModelId = json.optString("preferredModelId").ifBlank { null },
            assignedModelId = json.optString("assignedModelId").ifBlank { null },
            status = runCatching { TaskStatus.valueOf(json.optString("status")) }
                .getOrDefault(TaskStatus.FAILED),
            retryCount = json.optInt("retryCount"),
            timeoutMs = json.optLong("timeoutMs", 5 * 60_000),
            subagentType = json.optString("subagentType", "general"),
            depth = json.optInt("depth"),
            filesHint = files,
            allowedTools = tools,
            result = json.optJSONObject("result")?.let(::readResult),
            error = json.optString("error").ifBlank { null },
            modelHistory = history,
            createdAt = json.optLong("createdAt"),
            startedAt = if (json.has("startedAt") && !json.isNull("startedAt")) json.optLong("startedAt") else null,
            finishedAt = if (json.has("finishedAt") && !json.isNull("finishedAt")) json.optLong("finishedAt") else null,
        )
    }

    private fun writeResult(result: TaskResult): JSONObject = JSONObject().apply {
        put("taskId", result.taskId)
        put("success", result.success)
        put("summary", result.summary)
        put("output", result.output)
        put("changedFiles", JSONArray(result.changedFiles))
        if (result.diff != null) put("diff", result.diff)
        put("errors", JSONArray(result.errors))
        if (result.verification != null) put("verification", result.verification)
        put("needsFollowUp", result.needsFollowUp)
        put("tokensIn", result.tokensIn)
        put("tokensOut", result.tokensOut)
        put("tokensEstimated", result.tokensEstimated)
        if (result.costUsd != null) put("costUsd", result.costUsd)
    }

    private fun readResult(json: JSONObject): TaskResult {
        val changed = mutableListOf<String>()
        val changedJson = json.optJSONArray("changedFiles") ?: JSONArray()
        for (i in 0 until changedJson.length()) changed.add(changedJson.optString(i))
        val errors = mutableListOf<String>()
        val errorsJson = json.optJSONArray("errors") ?: JSONArray()
        for (i in 0 until errorsJson.length()) errors.add(errorsJson.optString(i))
        return TaskResult(
            taskId = json.optString("taskId"),
            success = json.optBoolean("success"),
            summary = json.optString("summary"),
            output = json.optString("output"),
            changedFiles = changed,
            diff = json.optString("diff").ifBlank { null },
            errors = errors,
            verification = json.optString("verification").ifBlank { null },
            needsFollowUp = json.optBoolean("needsFollowUp"),
            tokensIn = json.optLong("tokensIn"),
            tokensOut = json.optLong("tokensOut"),
            tokensEstimated = json.optBoolean("tokensEstimated", true),
            costUsd = if (json.has("costUsd") && !json.isNull("costUsd")) json.optDouble("costUsd") else null,
        )
    }

    private fun writeBudget(budget: BudgetSnapshot): JSONObject = JSONObject().apply {
        put("apiCalls", budget.apiCalls)
        put("tokensIn", budget.tokensIn)
        put("tokensOut", budget.tokensOut)
        if (budget.costUsd != null) put("costUsd", budget.costUsd)
        put("maxApiCalls", budget.maxApiCalls)
        put("maxTokens", budget.maxTokens)
        if (budget.maxCostUsd != null) put("maxCostUsd", budget.maxCostUsd)
    }

    private fun readBudget(json: JSONObject?): BudgetSnapshot {
        if (json == null) {
            return BudgetSnapshot(0, 0, 0, null, 0, 0, null)
        }
        return BudgetSnapshot(
            apiCalls = json.optInt("apiCalls"),
            tokensIn = json.optLong("tokensIn"),
            tokensOut = json.optLong("tokensOut"),
            costUsd = if (json.has("costUsd") && !json.isNull("costUsd")) json.optDouble("costUsd") else null,
            maxApiCalls = json.optInt("maxApiCalls"),
            maxTokens = json.optLong("maxTokens"),
            maxCostUsd = if (json.has("maxCostUsd") && !json.isNull("maxCostUsd")) json.optDouble("maxCostUsd") else null,
        )
    }

    companion object {
        private const val MAX_BATCHES = 20
    }
}
