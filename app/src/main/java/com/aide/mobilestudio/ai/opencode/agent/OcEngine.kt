package com.aide.mobilestudio.ai.opencode.agent

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.ai.AiSettings
import com.aide.mobilestudio.ai.agent.CheckpointFileDelta
import com.aide.mobilestudio.ai.agent.CheckpointOutcome
import com.aide.mobilestudio.ai.agent.CheckpointStore
import com.aide.mobilestudio.ai.agent.FileMentions
import com.aide.mobilestudio.ai.agent.ModelManager
import com.aide.mobilestudio.ai.api.AIProvider
import com.aide.mobilestudio.ai.api.StreamRetry
import com.aide.mobilestudio.ai.opencode.command.OcCommandInfo
import com.aide.mobilestudio.ai.opencode.command.OcCommandRegistry
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.mcp.McpToolAdapter
import com.aide.mobilestudio.ai.opencode.mcp.OcMcpClient
import com.aide.mobilestudio.ai.opencode.orchestrator.OcAgentModelCatalog
import com.aide.mobilestudio.ai.opencode.permission.Permission
import com.aide.mobilestudio.ai.opencode.permission.PermissionRule
import com.aide.mobilestudio.ai.opencode.provider.OcModelInfo
import com.aide.mobilestudio.ai.opencode.provider.OcModelRef
import com.aide.mobilestudio.ai.opencode.provider.OcProviderRegistry
import com.aide.mobilestudio.ai.opencode.tool.DownloadResult
import com.aide.mobilestudio.ai.model.ApiModelFetcher
import com.aide.mobilestudio.ai.model.HuggingFaceSource
import com.aide.mobilestudio.ai.model.ModelCatalog
import com.aide.mobilestudio.ai.model.ModelDownloads
import com.aide.mobilestudio.ai.provider.AndroidHttpTransport
import com.aide.mobilestudio.ai.opencode.session.OcCompaction
import com.aide.mobilestudio.ai.opencode.session.OcContextBudget
import com.aide.mobilestudio.ai.opencode.session.OcInstructions
import com.aide.mobilestudio.ai.opencode.session.OcMessage
import com.aide.mobilestudio.ai.opencode.session.OcSessionStore
import com.aide.mobilestudio.ai.opencode.session.OcSnapshot
import com.aide.mobilestudio.ai.opencode.skill.OcSkill
import com.aide.mobilestudio.ai.opencode.tool.*
import com.aide.mobilestudio.ai.orchestrator.AgentErrorKind
import com.aide.mobilestudio.ai.orchestrator.AgentModel
import com.aide.mobilestudio.ai.orchestrator.AgentRunResult
import com.aide.mobilestudio.ai.orchestrator.AgentRunner
import com.aide.mobilestudio.ai.orchestrator.AgentTask
import com.aide.mobilestudio.ai.orchestrator.JsonOrchestratorStore
import com.aide.mobilestudio.ai.orchestrator.ModelStatsStore
import com.aide.mobilestudio.ai.orchestrator.OrchestratorHub
import com.aide.mobilestudio.ai.orchestrator.OrchestratorSnapshot
import com.aide.mobilestudio.ai.orchestrator.TaskResult
import com.aide.mobilestudio.ai.orchestrator.TaskStatus
import com.aide.mobilestudio.ai.ui.AiDiff
import com.aide.mobilestudio.ai.retry.ApiErrorClassifier
import com.aide.mobilestudio.ai.retry.ApiErrorInfo
import com.aide.mobilestudio.ai.retry.ApiErrorKind
import com.aide.mobilestudio.ai.retry.RequestRegistry
import com.aide.mobilestudio.ai.retry.RequestState
import com.aide.mobilestudio.ai.retry.RetryStatus
import com.aide.mobilestudio.ai.validation.DiagnosticDiffEngine
import com.aide.mobilestudio.ai.validation.LiveDiagnostic
import com.aide.mobilestudio.ai.validation.LiveDiagnostics
import com.aide.mobilestudio.ai.resource.AndroidMemoryProbe
import com.aide.mobilestudio.ai.resource.TaskScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Below this window the full session prompt and every tool schema do not fit
 * together, so both are trimmed. A frontier model has room for both; a local
 * one running with 4096 tokens does not, and was being handed over seven
 * thousand tokens of fixed overhead before a single message was added.
 */
private const val SMALL_CONTEXT_LIMIT = 8_192

/** Tool output handed to the chat surface; the full value stays in the session. */
private const val UI_TOOL_RESULT_LIMIT = 200_000

/**
 * Tools kept when the schema budget runs out, most useful first.
 *
 * Everything that reads, edits or runs code comes before the planning and web
 * tools, so a small window spends what it has on the work rather than on
 * describing the work. The order decides what survives.
 */
private val SMALL_CONTEXT_TOOL_ORDER = listOf(
    "read", "edit", "write", "bash", "grep", "glob", "patch", "tool_help",
    "gradle_build", "project_diagnostics", "llama_model_info", "device_info", "llama_runtime_logs", "android_logcat",
    "ls", "file_info", "diff", "git", "sqlite_query", "http_request", "zip_list", "zip",
    "network_info", "current_time", "app_info", "model_search", "model_download", "model_download_status",
    "todowrite", "question", "skill", "websearch", "webfetch", "task", "delegate",
    "copy_file", "move_file", "delete_file",
)

/**
 * Tools a local model gets in full even when the rest are lazy.
 *
 * A small model copies shapes, not prose: it can infer `filePath` from an
 * example, but not that `question` takes an array of objects with nested
 * options, or that `edit` needs both `oldString` and `newString`. These are
 * the calls that do the actual work (and the one that asks the user), so they
 * keep their complete schema. Planning, web and device-diagnostic tools stay
 * lazy: they are rarely the first step and `tool_help` is one call away.
 */
private val FULL_LOCAL_SCHEMA_TOOLS = setOf(
    "read", "write", "edit", "patch", "bash", "grep", "glob", "question", "tool_help",
    "delegate",
)

class OcEngine(private val context: Context) : EngineBridge {

    interface Listener {
        fun onMessage(text: String, isComplete: Boolean)
        fun onThinking(status: String)
        /** Live reasoning text of the current turn (accumulated so far). */
        fun onReasoning(text: String) {}
        /** Reasoning of the current turn ended (response text or a tool started). */
        fun onReasoningDone() {}

        /**
         * Approval for a file-changing tool, carrying the edit itself.
         *
         * A surface that can render a diff overrides this and shows what is
         * about to change; the default falls back to the plain tool dialog, so
         * nothing that worked before loses its approval step.
         */
        fun onDiffApproval(preview: DiffPreview, onApproval: (Boolean, Boolean) -> Unit) {
            onToolCall(preview.tool, preview.args, preview.risk, onApproval)
        }

        fun onToolCall(name: String, args: JSONObject, risk: RiskLevel, onApproval: (Boolean, Boolean) -> Unit)
        fun onToolStart(name: String, args: JSONObject)
        fun onToolEnd(name: String, result: String)
        /** Completion with the call arguments, so a surface can pair parallel calls. */
        fun onToolEnd(name: String, args: JSONObject, result: String, isError: Boolean) {
            onToolEnd(name, result)
        }
        fun onPlanUpdate(rawPlan: String)
        fun onStateChange(state: AgentState)
        fun onError(message: String)
        fun onTodos(todos: JSONArray) {}
        fun onAskQuestion(questions: JSONArray, onAnswer: (JSONArray?) -> Unit) {
            onAnswer(null)
        }
        fun onCommandOutput(commandId: String, line: String) {}
        /** Fired when a delegation batch changes state; surfaces redraw the agent panel. */
        fun onOrchestratorUpdate() {}
        /** Fired after a write/edit/patch tool completed, with the affected path. */
        fun onFileChanged(path: String) {}
        /**
         * Exact token throughput measured during the just-finished stream.
         *
         * [tokens] is the actual decoded output-token count reported by the
         * provider; [decodeElapsedMs] is decode time measured from the first
         * emitted token to the last one (not including prefill). Null means
         * the provider could not measure it and the caller may fall back to
         * an estimate - or show nothing, rather than fabricate a number.
         */
        fun onGenerationStats(tokens: Int, decodeElapsedMs: Long?) {}
        /**
         * Inline, non-error notice (unknown /command, unresolved @file, ...).
         * Surfaces render it as a system bubble; message flow is never blocked.
         */
        fun onNotice(text: String) {}

        /**
         * A transient failure is being retried automatically: which attempt,
         * why, and when the next one starts. Surfaces can show a countdown; no
         * user action is required and the retry must not be shown as an error.
         */
        fun onRetryStatus(status: RetryStatus) {}

        /**
         * Called when the agent wants to download a file. The surface should
         * show a confirmation dialog and return the result.
         */
        fun onDownloadFile(
            url: String,
            description: String,
            fileName: String?,
            onResult: (DownloadResult) -> Unit
        ) {
            onResult(DownloadResult(success = false, error = "Download not supported on this surface"))
        }
    }

    /**
     * A file change that is about to be applied.
     *
     * [before] is what the file holds now (empty when it is being created),
     * [after] is what the tool will leave behind. Both are the real contents,
     * so the diff is computed from the same bytes the tool will work on; a
     * preview synthesised from the arguments alone would be a guess.
     */
    data class DiffPreview(
        val tool: String,
        val args: JSONObject,
        val path: String,
        val before: String,
        val after: String,
        val risk: RiskLevel,
        /** Raw patch text for `patch`, whose effect is not simulated here. */
        val patch: String? = null,
    )

    enum class RiskLevel { READ_ONLY, BUILD, MODIFY, DANGEROUS }

    enum class AgentState {
        IDLE, THINKING, GENERATING, WAITING_TOOL_APPROVAL, EXECUTING_TOOL,
        PROCESSING_TOOL_RESULT, COMPLETED, ERROR, CANCELLED,
    }

    private val settings = AiSettings(context)
    private val store = OcSessionStore(context)
    private val snapshots = OcSnapshot(context)
    private val instructions = OcInstructions(context)
    private val modelManager = ModelManager(context)
    private val mcpClient = OcMcpClient()

    private var config: OpencodeConfig = OpencodeConfig.load(context, worktreeOf(context))
    private var agentRegistry = OcAgentRegistry(context, { config })
    private var toolRegistry = OcToolRegistry()
    private var skillManager = OcSkill(context) { config }
    private var commandRegistry = OcCommandRegistry(context, { config }, { skillManager }, { mcpServers }, mcpClient)
    private var providerRegistry = OcProviderRegistry(context, { config }, modelManager)

    /**
     * The same live Hugging Face catalogue the Models tab uses.
     *
     * Kept lazy so opening the chat does not construct a second provider
     * registry or touch the network. The completion listener registers an
     * AI-started download with the model manager; the UI has its own listener
     * for UI-started tasks, and both can coexist.
     */
    private val modelCatalog: ModelCatalog by lazy {
        ModelCatalog(
            context = context,
            modelManager = modelManager,
            worktreeProvider = { worktreeOf(context) },
            hfSource = HuggingFaceSource(AndroidHttpTransport()),
            apiFetcher = ApiModelFetcher(AndroidHttpTransport()),
        )
    }
    private val downloadCompletionTracker: Unit by lazy {
        ModelDownloads.get(context.applicationContext).addCompletionListener { task ->
            modelCatalog.registerCompleted(task)
        }
        Unit
    }

    /**
     * Delegation is a process-wide service: nested batches must share the
     * root budget and loop guard, not create independent orchestrators.
     * The catalog reads the current provider registry each time, so installing
     * a GGUF or adding an API provider makes it selectable without restarting.
     */
    private val orchestratorStats = ModelStatsStore(
        File(context.filesDir, "AI/orchestrator/model-stats.json"),
    )
    private val orchestratorHub: OrchestratorHub by lazy {
        OrchestratorHub(
            configProvider = {
                OcAgentModelCatalog(context, providerRegistry, settings) { id -> orchestratorStats.get(id) }.config()
            },
            catalogProvider = {
                OcAgentModelCatalog(context, providerRegistry, settings) { id -> orchestratorStats.get(id) }.models()
            },
            runner = object : AgentRunner {
                override suspend fun run(task: AgentTask, model: AgentModel, depth: Int): AgentRunResult =
                    runOrchestratedTask(task, model, depth)
            },
            store = JsonOrchestratorStore(File(context.filesDir, "AI/orchestrator/state.json")),
            stats = orchestratorStats,
            availableSubagentTypes = { agentRegistry.agents().keys },
            onUpdate = {
                // Listener implementations post to the UI thread themselves.
                listener?.onOrchestratorUpdate()
            },
        )
    }

    /**
     * Stream failures are session-scoped so parallel subagents do not read each
     * other's error (or the main turn's retry state).
     */
    private val sessionErrors = java.util.concurrent.ConcurrentHashMap<String, ApiErrorInfo>()
    private val sessionErrorMessages = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val requestRegistry = RequestRegistry()
    /** One approval dialog at a time, even with parallel subagents. */
    private val approvalGate = Mutex()
    /** Request id of the running turn; retries and de-duplication key off it. */
    private var activeRequestId: String? = null
    /** True when the last runLoop ended on a classified stream failure. */
    private var lastRunFailed = false

    /** Enough context to re-run the exact request behind "다시 시도". */
    private data class FailedRun(
        val sessionId: String,
        val agentName: String,
        val messageId: String,
        val error: ApiErrorInfo?,
    )
    private var lastFailure: FailedRun? = null

    private var mcpServers: List<OcMcpClient.ServerInfo> = emptyList()
    private var mcpReady = false

    var provider: AIProvider? = null

    // The last model you picked, restored on open. setModel() has always
    // written a preference, but the workspace never read one back - and the
    // key it wrote was shared with the bottom-panel agent, whose model ids do
    // not mean the same thing. So the engine started with no model at all and
    // currentModelRef() fell through to the config default, then to
    // gemini-1.5-flash, then to whichever local model was listed first. That
    // is why reopening the workspace landed on a different model every time
    // and tapping into AI twice looked like it had swapped in a better one.
    // A stale ref is not a problem: currentModelRef() only accepts it if the
    // provider registry still knows that model.
    var modelRef: String? = settings.lastWorkspaceModelId
    var agentName: String = agentRegistry.defaultAgent()
    /**
     * Reasoning depth the user picked ("auto", "low", …). Only ever attached
     * to requests of models whose provider teaches it the parameter; a model
     * that does not take one is never sent a guess.
     */
    var reasoningEffort: String = settings.workspaceReasoningEffort
    var listener: Listener? = null
    /**
     * Local-model runtime knobs, evaluated per request so a settings change
     * takes effect without recreating the engine.
     *
     * Auto mode asks for every GPU layer and the performance-core thread
     * count; llama.cpp lowers the offload if the model cannot fit. Explicit
     * values are still honoured for benchmarking or CPU-only use.
     */
    val runtimeOptions: JSONObject
        get() = JSONObject().apply {
            put("nGpuLayers", settings.effectiveGpuLayers())
            put("nThreads", settings.effectiveThreadCount())
            put("autoTune", settings.autoTuneLocal)
        }

    /**
     * "Think" mode for the workspace, persisted so it survives a restart.
     *
     * It asks the model to state its approach before acting, and the surface
     * shows the reasoning a provider streams as a real, expandable card rather
     * than a one-line ticker. It does not claim a model is reasoning when the
     * provider sends nothing: the card only exists when text arrived.
     */
    var thinkMode: Boolean = settings.thinkMode
        private set

    fun setThinkMode(enabled: Boolean) {
        thinkMode = enabled
        settings.thinkMode = enabled
    }

    private var currentSession: OcSessionStore.Session? = null
    private val readFiles = mutableSetOf<String>()
    private val toolCallCounts = mutableMapOf<String, Int>()
    private var activeMessageId: String = ""
    private var subagentDepth = 0

    // --- Checkpoints (/undo //redo): persisted per session under filesDir/ai/checkpoints ---
    private val checkpointStore = CheckpointStore(File(context.filesDir, "ai/checkpoints"))

    /** Groups every file mutation of one user request so /undo rolls back the whole turn. */
    @Volatile
    private var currentTurnId: String = ""

    // --- @file mentions: resolved once per queued message, attached at request time ---
    @Volatile
    private var pendingMentionContext: String? = null

    private data class PendingMessage(val text: String, val imageBase64: String? = null)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    private val queue = mutableListOf<PendingMessage>()
    private var isRunning = false

    private var currentFileContext: String? = null
    private var currentFilePath: String? = null

    fun setAgentMode(mode: String) {
        agentName = mode
    }

    /** Persists the reasoning depth for the workspace; "" or "auto" = provider default. */
    fun chooseReasoningEffort(level: String) {
        reasoningEffort = level
        settings.workspaceReasoningEffort = level
    }

    fun setCurrentFileContext(filePath: String?, content: String?, selection: String? = null) {
        currentFilePath = filePath
        if (filePath == null || content == null || content.isBlank()) {
            currentFileContext = null
            return
        }
        val sb = StringBuilder()
        sb.append("Current file: ").append(filePath).append("\n\n")
        if (selection != null && selection.isNotBlank()) {
            sb.append("Selected text:\n```\n").append(selection).append("\n```\n\n")
        }
        sb.append(content.take(6_000))
        currentFileContext = sb.toString()
    }

    /** Absolute path of the file set via [setCurrentFileContext], if any. */
    fun currentFileContextPath(): String? = currentFilePath

    /** Project root the agent operates on (used by @file mentions). */
    fun workspaceRoot(): File = projectDir ?: config.worktree

    fun estimateTokens(): Int {
        val session = currentSession ?: return 0
        var total = 0
        for (msg in store.messages(session)) {
            val parts = OcMessage.parts(msg)
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                total += part.optString("text", "").length
            }
        }
        return total / 4
    }

    fun reloadConfig() {
        config = OpencodeConfig.load(context, worktreeOf(context))
        agentRegistry = OcAgentRegistry(context, { config })
        skillManager = OcSkill(context) { config }
        commandRegistry = OcCommandRegistry(context, { config }, { skillManager }, { mcpServers }, mcpClient)
        providerRegistry = OcProviderRegistry(context, { config }, modelManager)
        agentName = agentRegistry.defaultAgent()
        mcpReady = false
        initTools()
    }

    private fun worktreeOf(context: Context): File {
        projectDir?.let { return it }
        val project = (context as? com.aide.mobilestudio.MainActivity)?.currentProjectDir
        return project ?: File(context.filesDir, "workspace").apply { mkdirs() }
    }

    /** Project root override for hosts that are not [com.aide.mobilestudio.MainActivity]. */
    var projectDir: File? = null

    private val toolContext: ToolContext
        get() = toolContextFor(currentSession?.id ?: "")

    private fun toolContextFor(
        sessionId: String,
        depth: Int = 0,
        orchestrationTaskId: String? = null,
        rootSessionId: String = currentSession?.id ?: sessionId,
        allowedTools: Set<String> = emptySet(),
        turnId: String = "",
    ): ToolContext = ToolContext(
        androidContext = context,
        worktree = config.worktree,
        sessionId = sessionId,
        config = config,
        bridge = this,
        tmpDir = File(context.filesDir, "tmp").apply { mkdirs() },
        depth = depth,
        rootSessionId = rootSessionId,
        orchestrationTaskId = orchestrationTaskId,
        allowedTools = allowedTools,
        turnId = turnId,
    )

    fun init() {
        initTools()
    }

    private fun initTools() {
        // Attach one listener per engine before the model tools can start
        // anything. The model manager ignores duplicate registrations by path,
        // so a UI listener and this one can both observe the same completion.
        runCatching { downloadCompletionTracker }
        toolRegistry = OcToolRegistry()
        toolRegistry.registerAll(
            listOf(
                BashTool(),
                ReadTool(),
                WriteTool(),
                EditTool(),
                GlobTool(),
                GrepTool(),
                PatchTool(),
                CopyTool(),
                MoveTool(),
                DeleteTool(),
                // Structured everyday tools: easier to call correctly than
                // assembling the same operation through bash, and reviewable
                // by a person because each has a narrow shape.
                ListDirectoryTool(),
                FileInfoTool(),
                DiffFilesTool(),
                HttpRequestTool(),
                GitTool(),
                SqliteQueryTool(),
                ZipListTool(),
                ZipTool(),
                NetworkInfoTool(),
                CurrentTimeTool(),
                AppInfoTool(),
                ModelSearchTool(modelCatalog),
                ModelDownloadTool(modelCatalog),
                ModelDownloadStatusTool(),
                // Lazy schemas: the local window ships argument names and
                // fetches the one full schema it is about to use.
                ToolHelpTool { toolRegistry },
                // The work this app exists for: building on the device and
                // reading what the build and the local runtime actually did.
                GradleBuildTool(),
                ProjectDiagnosticsTool(),
                LlamaModelInfoTool(),
                DeviceInfoTool(),
                LlamaRuntimeLogsTool(),
                AndroidLogcatTool(),
                TodoWriteTool(),
                TaskTool(),
                DelegateTool(),
                QuestionTool(),
                SkillTool { skillManager },
                WebFetchTool(),
                WebSearchTool(),
            ),
        )
        mcpServers = runCatching { mcpClient.parseServers(config.mcp) }.getOrDefault(emptyList())
    }

    private suspend fun ensureMcp() {
        if (mcpReady || mcpServers.isEmpty()) {
            mcpReady = true
            return
        }
        withContext(Dispatchers.IO) {
            for (server in mcpServers) {
                runCatching {
                    for (mcpTool in mcpClient.listTools(server)) {
                        toolRegistry.register(McpToolAdapter(server, mcpTool, mcpClient))
                    }
                }
            }
        }
        mcpReady = true
    }

    fun currentSessionId(): String? = currentSession?.id

    fun newSession() {
        val session = store.create(
            agent = agentName,
            model = currentModelRef()?.toString()?.let { JSONObject().put("ref", it) },
        )
        currentSession = session
        activeMessageId = session.messages.optJSONObject(session.messages.length() - 1)?.optString("id") ?: ""
        readFiles.clear()
        toolCallCounts.clear()
        currentTurnId = ""
        pendingMentionContext = null
        snapshots.clear(session.id)
        listener?.onStateChange(AgentState.IDLE)
    }

    fun loadSession(sessionId: String) {
        val session = store.load(sessionId) ?: return
        currentSession = session
        agentName = session.agent.ifEmpty { agentRegistry.defaultAgent() }
        activeMessageId = ""
        readFiles.clear()
        toolCallCounts.clear()
        listener?.onStateChange(AgentState.IDLE)
    }

    fun listSessions(includeArchived: Boolean = false): List<OcSessionStore.Session> = store.list(includeArchived)

    fun sessionTitle(sessionId: String): String = store.load(sessionId)?.title ?: "Untitled"

    fun sessionMessages(sessionId: String): List<JSONObject> {
        val session = store.load(sessionId) ?: return emptyList()
        return store.messages(session)
    }

    fun renameSession(sessionId: String, title: String) {
        store.load(sessionId)?.let { store.setTitle(it, title) }
    }

    fun forkSession(sessionId: String, messageID: String? = null): String? {
        val forked = store.fork(sessionId, messageID) ?: return null
        return forked.id
    }

    fun archiveSession(sessionId: String, archived: Boolean): Boolean {
        return if (archived) store.archive(sessionId) else store.unarchive(sessionId)
    }

    fun deleteSession(sessionId: String): Boolean = store.delete(sessionId)

    fun sessionMessages(): List<JSONObject> {
        val session = currentSession ?: return emptyList()
        return store.messages(session)
    }

    fun revert(): List<String> {
        val session = currentSession ?: return emptyList()
        val restored = snapshots.restore(session.id)
        if (restored.isNotEmpty()) listener?.onMessage("Reverted ${restored.size} file(s).", false)
        return restored
    }

    fun listCommands(): List<OcCommandInfo> = runCatching { commandRegistry.list() }.getOrDefault(emptyList())

    /** Every registered tool name — used by the workspace's tool list. */
    fun listToolNames(): List<String> = runCatching { toolRegistry.names() }.getOrDefault(emptyList())

    /**
     * True when "다시 시도" can re-run the last failed request: something
     * failed, nothing else is running, and the message has no live request.
     */
    fun canRetryLastFailed(): Boolean =
        lastFailure != null && !isRunning && !requestRegistry.isDuplicate(lastFailure!!.messageId)

    fun listProviders(): List<Pair<String, String>> = providerRegistry.listProviders()

    fun listProviderModels(providerId: String): List<OcModelInfo> = providerRegistry.listModels(providerId)

    fun currentModelLabel(): String = currentModelRef()?.toString() ?: "none"

    fun currentTodos(): JSONArray = currentSession?.let { runCatching { store.load(it.id)?.todos }.getOrNull() }
        ?: JSONArray()

    /** Replaces the current session's todo list (used by structural plan editing). */
    fun updateTodos(todos: JSONArray) {
        val session = currentSession ?: return
        store.setTodos(session, todos)
        listener?.onTodos(todos)
    }

    fun latestBackupForCurrentSession(path: String): File? =
        currentSession?.let { runCatching { snapshots.latestBackup(it.id, path) }.getOrNull() }

    fun mcpServerNames(): List<String> = mcpServers.map { it.name }

    /** Names of tools currently registered with the engine. */
    fun toolNames(): List<String> = toolRegistry.names()

    /**
     * Documentation for the Tools screen, rendered by the same code the model
     * sees through `tool_help` - the app never explains a tool differently
     * from the way it asks the model to call it.
     */
    fun toolDetail(name: String): String? {
        val tool = toolRegistry.get(name) ?: return null
        return ToolHelpTool.describe(tool, toolContextFor(currentSession?.id ?: ""))
    }

    /** Adds a local MCP server to the global config and reloads. */
    fun addMcpServer(name: String, command: List<String>) {
        OpencodeConfig.upsertMcpServer(context, name, command)
        reloadConfig()
    }

    fun removeMcpServer(name: String): Boolean {
        val removed = OpencodeConfig.removeMcpServer(context, name)
        if (removed) reloadConfig()
        return removed
    }

    /** Probes an MCP server's `tools/list`. Runs on IO; returns a human-readable status. */
    suspend fun testMcpServer(name: String): String {
        val server = mcpServers.firstOrNull { it.name == name }
            ?: return "Unknown MCP server: $name"
        return withContext(Dispatchers.IO) {
            val tools = runCatching { mcpClient.listTools(server) }.getOrNull()
            if (tools == null) {
                "MCP '$name': no response (server not running or not reachable)"
            } else {
                "MCP '$name': ${tools.size} tool(s)" +
                    if (tools.isNotEmpty()) " — ${tools.take(6).joinToString(", ") { it.name }}" else ""
            }
        }
    }

    fun listAgents(): List<OcAgent> = agentRegistry.listVisible()

    fun currentAgentName(): String = agentName

    // ---------------------------------------------------- orchestrator view

    fun orchestratorSnapshots(): List<OrchestratorSnapshot> = orchestratorHub.snapshots()

    fun latestOrchestratorSnapshot(): OrchestratorSnapshot? = orchestratorHub.latest()

    fun cancelOrchestratorTask(taskId: String? = null) {
        orchestratorHub.cancel(taskId)
        listener?.onOrchestratorUpdate()
    }

    fun orchestratorLogs(): List<String> = orchestratorHub.logs()

    fun orchestratorModelStats(): Map<String, com.aide.mobilestudio.ai.orchestrator.ModelStats> =
        orchestratorHub.modelStats()

    /**
     * Runs conversation compaction for the active session on demand (OpenCode's
     * `/compact`). Returns false when there is no session or no usable model.
     */
    suspend fun compactNow(): Boolean {
        val session = currentSession ?: return false
        val agent = agentRegistry.get(agentName)
        val model = resolveModelFor(agent) ?: return false
        runCompaction(session, model, agent)
        return true
    }

    fun currentAgent(): OcAgent = agentRegistry.get(agentName)

    fun setAgent(name: String) {
        if (agentRegistry.agents().containsKey(name)) agentName = name
    }

    fun currentModelRef(): OcModelRef? {
        modelRef?.let { OcModelRef.parse(it) }?.let { if (providerRegistry.getModel(it) != null) return it }
        config.model?.let { OcModelRef.parse(it) }?.let { if (providerRegistry.getModel(it) != null) return it }
        val apiKey = settings.apiKey
        if (apiKey != null) return OcModelRef("gemini", "gemini-1.5-flash")
        val local = providerRegistry.listModels("local").firstOrNull() ?: return null
        return local.ref
    }

    fun setModel(ref: String) {
        modelRef = ref
        settings.lastWorkspaceModelId = ref
        provider = null
    }

    fun resolveModelInfo(): OcModelInfo? = currentModelRef()?.let { providerRegistry.getModel(it) }

    fun stop() {
        orchestratorHub.cancelAll()
        currentJob?.cancel()
        currentJob = null
        queue.clear()
        listener?.onStateChange(AgentState.CANCELLED)
        listener?.onOrchestratorUpdate()
    }

    fun sendMessage(text: String, imageBase64: String? = null) {
        if (text.isBlank() && imageBase64 == null) return
        if (currentSession == null) newSession()
        if (text.trimStart().startsWith("/")) {
            if (executeCommand(text.trim())) return
        }
        queue.add(PendingMessage(text, imageBase64))
        pumpQueue()
    }

    /**
     * Re-runs the request behind the last stream failure without duplicating
     * the user message. Refused while something is running or while the same
     * message still has a live request, so a double tap cannot send twice.
     */
    fun retryLastFailed(): Boolean {
        val failed = lastFailure ?: return false
        if (isRunning) return false
        if (requestRegistry.isDuplicate(failed.messageId)) return false
        val session = store.load(failed.sessionId) ?: return false
        val ref = currentModelRef()
        val record = requestRegistry.begin(
            messageId = failed.messageId,
            providerId = ref?.providerID ?: "unknown",
            modelId = ref?.modelID ?: "unknown",
        ) ?: return false
        currentSession = session
        agentName = failed.agentName
        activeRequestId = record.requestId
        lastFailure = null
        lastRunFailed = false
        isRunning = true
        orchestratorHub.beginRoot(session.id)
        currentJob = scope.launch {
            try {
                runLoop(session, agentRegistry.get(agentName), 0)
                if (!lastRunFailed) {
                    requestRegistry.finish(record.requestId, RequestState.COMPLETED)
                    activeRequestId = null
                    lastFailure = null
                }
                listener?.onStateChange(AgentState.COMPLETED)
            } catch (e: CancellationException) {
                requestRegistry.finish(record.requestId, RequestState.CANCELLED)
                activeRequestId = null
                listener?.onStateChange(AgentState.CANCELLED)
            } catch (e: Exception) {
                requestRegistry.finish(record.requestId, RequestState.FAILED)
                activeRequestId = null
                listener?.onError(e.message ?: "Unknown error during agent loop")
                listener?.onStateChange(AgentState.ERROR)
            } finally {
                isRunning = false
                if (queue.isNotEmpty() && isActive) {
                    pumpQueue()
                } else {
                    listener?.onStateChange(AgentState.IDLE)
                }
            }
        }
        return true
    }

    private fun executeCommand(text: String): Boolean {
        val body = text.removePrefix("/").trim()
        val name = body.substringBefore(" ").trim()
        val args = body.substringAfter(" ", "").trim()
        val cmd = runCatching { commandRegistry.get(name) }.getOrNull() ?: return false
        scope.launch {
            try {
                val template = cmd.template()
                if (template.isBlank()) {
                    listener?.onError("Command /$name has an empty template")
                    return@launch
                }
                val rendered = commandRegistry.interpolate(template, args)
                if (cmd.model != null) setModel(cmd.model)
                if (cmd.subtask) {
                    listener?.onThinking("Running /$name as subagent...")
                    val subagentType = cmd.agent ?: "general"
                    val result = spawnSubagent(subagentType, rendered, name, null, currentSession?.id ?: "", false, 0)
                    listener?.onMessage(result, true)
                } else {
                    if (cmd.agent != null) setAgent(cmd.agent)
                    queue.add(PendingMessage(rendered))
                    pumpQueue()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener?.onError("Command /$name failed: ${e.message}")
            }
        }
        return true
    }

    private fun pumpQueue() {
        if (isRunning) return
        val text = if (queue.isEmpty()) {
            listener?.onStateChange(AgentState.IDLE)
            return
        } else {
            queue.removeAt(0)
        }
        val session = currentSession ?: return
        isRunning = true
        // A new user turn gets a fresh per-root budget and loop guard; nested
        // delegation during the turn shares both.
        orchestratorHub.beginRoot(session.id)
        currentJob = scope.launch {
            try {
                // Resolve @file mentions off the main thread, once per message:
                // the block is attached to every request of this agent turn.
                val resolution = withContext(Dispatchers.IO) { resolveMentions(text.text) }
                pendingMentionContext = resolution.block
                if (resolution.unknown.isNotEmpty()) {
                    listener?.onNotice(
                        "[@file] not found: " +
                            resolution.unknown.joinToString(", ") { "@$it" } +
                            " — message sent without it.",
                    )
                }
                currentTurnId = OcMessage.nextId("turn")
                addUserMessage(text.text, text.imageBase64)
                lastRunFailed = false
                lastFailure = null
                val ref = currentModelRef()
                val record = requestRegistry.begin(
                    messageId = activeMessageId.ifEmpty { "msg-${System.currentTimeMillis()}" },
                    providerId = ref?.providerID ?: "unknown",
                    modelId = ref?.modelID ?: "unknown",
                )
                activeRequestId = record?.requestId
                runLoop(session, agentRegistry.get(agentName), 0)
                if (!lastRunFailed) {
                    activeRequestId?.let { requestRegistry.finish(it, RequestState.COMPLETED) }
                    activeRequestId = null
                }
                listener?.onStateChange(AgentState.COMPLETED)
            } catch (e: CancellationException) {
                activeRequestId?.let { requestRegistry.finish(it, RequestState.CANCELLED) }
                activeRequestId = null
                listener?.onStateChange(AgentState.CANCELLED)
            } catch (e: Exception) {
                activeRequestId?.let { requestRegistry.finish(it, RequestState.FAILED) }
                activeRequestId = null
                listener?.onError(e.message ?: "Unknown error during agent loop")
                listener?.onStateChange(AgentState.ERROR)
            } finally {
                pendingMentionContext = null
                isRunning = false
                if (queue.isNotEmpty() && isActive) {
                    pumpQueue()
                } else {
                    listener?.onStateChange(AgentState.IDLE)
                }
            }
        }
    }

    private fun addUserMessage(text: String, imageBase64: String? = null) {
        val session = currentSession ?: return
        val modelJson = currentModelRef()?.let { JSONObject().put("ref", it.toString()) }
        val msg = OcMessage.userMessage(session.id, agentName, modelJson)
        OcMessage.addPart(msg, OcMessage.textPart(text))
        if (imageBase64 != null) {
            OcMessage.addPart(
                msg,
                JSONObject().apply {
                    put("type", "file")
                    put("id", OcMessage.nextId("part"))
                    put("mime", "image/jpeg")
                    put("filename", "pasted-image.jpg")
                    put("data", imageBase64)
                },
            )
        }
        store.updateMessage(session, msg)
        activeMessageId = msg.optString("id")
    }

    private suspend fun runLoop(
        session: OcSessionStore.Session,
        agent: OcAgent,
        depth: Int,
        orchestrationTaskId: String? = null,
        allowedTools: Set<String> = emptySet(),
    ): String {
        var iterations = 0
        val maxSteps = agent.steps ?: 80
        // An orchestrated subagent reports through its structured result; its
        // stream must not be interleaved into the parent chat. Approval dialogs
        // are emitted by executeToolCall and remain active.
        val reportEvents = orchestrationTaskId == null
        var lastText = ""
        var didCompact = false
        sessionErrors.remove(session.id)
        sessionErrorMessages.remove(session.id)
        val runTurnId = if (depth == 0) {
            currentTurnId.ifEmpty { OcMessage.nextId("turn").also { currentTurnId = it } }
        } else {
            // Parallel subagents edit files in the same project; sharing the
            // parent's undo turn would mix their checkpoints.
            OcMessage.nextId("turn")
        }
        val ctx = toolContextFor(
            sessionId = session.id,
            depth = depth,
            orchestrationTaskId = orchestrationTaskId,
            rootSessionId = currentSession?.id ?: session.id,
            allowedTools = allowedTools,
            turnId = runTurnId,
        )
        while (true) {
            ensureMcp()
            val model = resolveModelFor(agent)
            if (model == null) {
                if (reportEvents) listener?.onError("No model configured. Set an API key or select a local model.")
                return lastText
            }
            val messages = store.messages(session)
            // A delegated task may carry a tool allowlist. It is applied to
            // both the schemas and the execution path: a model cannot expand
            // its own permissions by calling a hidden tool.
            val visible = visibleTools(agent).let { all ->
                if (allowedTools.isEmpty()) all else all intersect allowedTools
            }
            val systemText = buildSystemPrompt(agent, model, session, visible)
            val toolDefs = toolDefinitionsFor(model, visible, ctx)
            if (!didCompact && config.compactionAuto() && messages.count { OcMessage.isUser(it) } >= 2) {
                // Auto-compaction only when the conversation genuinely cannot
                // fit and is near the hard limit - small conversations stay
                // uncompressed until they really need it.
                if (OcCompaction.autoShouldCompact(
                        messages, systemText, toolDefs.toString(), config.compaction,
                        model.contextLimit, null, model.outputLimit, config.compactionReserved(),
                    )
                ) {
                    didCompact = true
                    OcCompaction.prune(store, session.id, config.compactionPrune())
                    runCompaction(session, model, agent)
                    continue
                }
            }
            val history = buildHistory(session, systemText, messages)
            // Where the prompt budget actually goes. These are estimates, and
            // are labelled as such: the only exact count lives inside the
            // provider's tokenizer and never comes back out.
            val budget = OcContextBudget.of(
                contextTokens = model.contextLimit,
                modelMaxTokens = model.maxContext,
                declaredOutput = model.outputLimit,
                configuredReserve = config.compactionReserved(),
                systemText = systemText,
                toolsJson = toolDefs.toString(),
                historyText = history.toString(),
            )
            Log.i("OcPromptBudget", budget.describe() + " tools=${toolDefs.length()} msgs=${messages.size}")
            if (!budget.fits) {
                // Recoverable: the provider trims the history to fit before it
                // sends. Logged so an overflow can be diagnosed from a report
                // instead of only reproduced.
                Log.w("OcPromptBudget", "Request over the window by ~${budget.overByTokens} tokens")
            }
            // Reasoning depth is a request field, not a provider setting: the
            // provider is created fresh per iteration, so the choice applies
            // from the next request on.
            val requestOptions = JSONObject(runtimeOptions.toString()).apply {
                if (reasoningEffort.isNotBlank() && reasoningEffort != "auto") {
                    put("reasoningEffort", reasoningEffort)
                }
            }
            val provider = providerRegistry.createProvider(model.ref, requestOptions)
            if (provider == null) {
                if (reportEvents) listener?.onError("Unable to create provider for ${model.ref}")
                return lastText
            }

            if (reportEvents) listener?.onStateChange(AgentState.GENERATING)
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val toolCalls = mutableListOf<AIProvider.StreamEvent.ToolCall>()
            var streamError: String? = null
            var streamErrorInfo: ApiErrorInfo? = null
            /** Output tokens measured by this turn's provider (0 if unavailable). */
            var lastUsage: Int = 0
            /** Decode milliseconds measured by this turn's provider (null if unavailable). */
            var lastDecodeElapsedMs: Long? = null

            // Top-level retries reuse the request id so the retry registry can
            // correlate attempts. A delegated subagent is a separate request:
            // sharing the parent's id would make parallel tasks overwrite one
            // another's error/attempt state.
            val requestId = if (depth == 0) {
                activeRequestId ?: requestRegistry.newId().also { activeRequestId = it }
            } else {
                requestRegistry.newId()
            }
            // Local llama.cpp has no remote to retry; remote providers get the
            // full schedule. Only pre-content failures are replayed inside.
            val policy = if (model.local) com.aide.mobilestudio.ai.retry.RetryPolicy.NONE
            else com.aide.mobilestudio.ai.retry.RetryPolicy.DEFAULT
            StreamRetry.wrap(
                stream = provider.generateStream(history, toolDefs, requestOptions),
                policy = policy,
                requestId = requestId,
                onStatus = { status ->
                    if (reportEvents) listener?.onRetryStatus(status)
                    requestRegistry.updateAttempt(requestId, status.attempt, status.error)
                },
            ).collect { event ->
                when (event) {
                    is AIProvider.StreamEvent.Text -> {
                        if (textBuf.isEmpty() && reportEvents) listener?.onReasoningDone()
                        textBuf.append(event.text)
                        if (reportEvents) listener?.onMessage(textBuf.toString(), false)
                    }
                    is AIProvider.StreamEvent.Reasoning -> {
                        reasoningBuf.append(event.text)
                        if (reportEvents) listener?.onReasoning(reasoningBuf.toString())
                        val snippet = reasoningBuf.toString().takeLast(120).replace("\n", " ")
                        if (reportEvents) listener?.onThinking(snippet)
                    }
                    is AIProvider.StreamEvent.Plan -> if (reportEvents) listener?.onPlanUpdate(event.rawPlan)
                    is AIProvider.StreamEvent.ToolCall -> toolCalls.add(event)
                    is AIProvider.StreamEvent.Error -> {
                        streamError = event.message
                        streamErrorInfo = event.info ?: ApiErrorClassifier.fromMessage(event.message)
                    }
                    AIProvider.StreamEvent.Done -> {}
                    is AIProvider.StreamEvent.Usage -> {
                        lastUsage = event.outputTokens
                        lastDecodeElapsedMs = event.decodeElapsedMs
                        if (reportEvents) listener?.onGenerationStats(event.outputTokens, event.decodeElapsedMs)
                    }
                }
            }

            if (streamError != null) {
                // Session-scoped first: parallel subagents must never read the
                // main turn's error later.
                sessionErrorMessages[session.id] = streamError
                if (streamErrorInfo != null) sessionErrors[session.id] = streamErrorInfo
                // A failed attempt is remembered so "다시 시도" can re-run the
                // exact request; the user message is kept, never duplicated.
                // Only the interactive top-level turn owns retry UI state.
                if (depth == 0) {
                    lastRunFailed = true
                    lastFailure = FailedRun(session.id, agentName, activeMessageId, streamErrorInfo)
                }
                requestRegistry.finish(requestId, RequestState.FAILED, streamErrorInfo)
                if (depth == 0) activeRequestId = null
                if (reportEvents) {
                    listener?.onError(streamError)
                    listener?.onStateChange(AgentState.ERROR)
                }
                return lastText
            }

            val messagesNow = store.messages(session)
            val lastUser = messagesNow.lastOrNull { OcMessage.isUser(it) }
            val assistant = OcMessage.assistantMessage(
                session.id, agent.name, modelJsonOf(model), parentID = lastUser?.optString("id"),
            )
            val parts = assistant.optJSONArray("parts") ?: JSONArray()
            if (reasoningBuf.isNotEmpty()) parts.put(OcMessage.reasoningPart(reasoningBuf.toString()))
            if (textBuf.isNotEmpty()) parts.put(OcMessage.textPart(textBuf.toString()))
            val pendingTools = mutableListOf<Pair<JSONObject, AIProvider.StreamEvent.ToolCall>>()
            for (call in toolCalls) {
                val callId = call.id ?: OcMessage.nextCallId()
                val part = OcMessage.toolPart(call.name, callId, call.args)
                parts.put(part)
                pendingTools.add(part to call)
            }
            store.updateMessage(session, assistant)
            activeMessageId = assistant.optString("id")
            lastText = textBuf.toString()
            if (textBuf.isNotEmpty() && reportEvents) listener?.onMessage(textBuf.toString(), true)

            if (pendingTools.isEmpty()) {
                if (reportEvents) listener?.onStateChange(AgentState.COMPLETED)
                // Delegated work must not spend API calls generating chat
                // titles as a side effect.
                if (depth == 0) maybeGenerateTitle(session, lastText)
                return lastText
            }

            val canParallelize = depth == 0 &&
                pendingTools.size >= 2 &&
                pendingTools.all { it.second.name in READ_ONLY_TOOLS } &&
                pendingTools.all { (_, call) ->
                    val tool = toolRegistry.get(call.name) ?: return@all false
                    val hash = call.args?.toString() ?: "{}"
                    permissionActionFor(tool, call, agent, ctx, currentToolCallCount(call.name, hash)) == "allow"
                }

            if (canParallelize) {
                executeReadOnlyToolsInParallel(session, assistant, pendingTools, ctx)
                if (!isActiveLoop()) {
                    if (reportEvents) listener?.onStateChange(AgentState.CANCELLED)
                    return lastText
                }
            } else {
            for ((part, call) in pendingTools) {
                if (!isActiveLoop()) {
                    if (reportEvents) listener?.onStateChange(AgentState.CANCELLED)
                    return lastText
                }
                val beforeDiagnostics = if (depth == 0) getGlobalDiagnostics() else emptyList()
                if (reportEvents) listener?.onStateChange(AgentState.PROCESSING_TOOL_RESULT)
                val state = executeToolCall(call, agent, ctx)
                part.put("state", state)
                store.updatePart(session, assistant.optString("id"), part)
                val output = state.optString("output", state.optString("error", ""))
                val toolFailed = state.optString("status") == "error"
                if (reportEvents) {
                    listener?.onToolEnd(
                        call.name,
                        call.args ?: JSONObject(),
                        output.take(UI_TOOL_RESULT_LIMIT),
                        toolFailed,
                    )
                }
                if (depth == 0 && call.name in setOf("write", "edit", "patch") &&
                    state.optString("status") == "completed"
                ) {
                    handlePostWriteAnalysis(beforeDiagnostics, session)
                }
                if (call.name in setOf("write", "edit", "patch") && state.optString("status") == "completed") {
                    val changed = call.args.optString("filePath", "")
                        .ifEmpty { call.args.optString("path", "") }
                    if (changed.isNotEmpty() && reportEvents) listener?.onFileChanged(changed)
                }
            }
            }

            iterations++
            if (iterations >= maxSteps) {
                val notice = "Maximum number of agentic iterations ($maxSteps) reached. Respond with text only."
                val assistant2 = OcMessage.assistantMessage(
                    session.id, agent.name, modelJsonOf(model), parentID = assistant.optString("id"),
                )
                OcMessage.addPart(assistant2, OcMessage.textPart(notice))
                store.updateMessage(session, assistant2)
                if (reportEvents) listener?.onMessage(notice, true)
                sessionErrorMessages[session.id] = notice
                return notice
            }
        }
    }

    private fun isActiveLoop(): Boolean = currentJob?.isActive != false || isRunning

    private fun resolveModelFor(agent: OcAgent): OcModelInfo? {
        val ref = agent.model?.let { OcModelRef.parse(it) }
            ?: currentModelRef()
            ?: return null
        providerRegistry.getModel(ref)?.let { return it }
        return providerRegistry.resolve(config.model)
    }

    private fun modelJsonOf(model: OcModelInfo): JSONObject = JSONObject().apply {
        put("ref", model.ref.toString())
        put("limit", JSONObject().apply {
            put("context", model.contextLimit)
            if (model.outputLimit != null) put("output", model.outputLimit)
        })
    }

    private class MentionResolution(val block: String?, val unknown: List<String>)

    /** Resolves @path tokens in [text] into a context block (runs on IO). */
    private fun resolveMentions(text: String): MentionResolution {
        val mentions = FileMentions.extract(text)
        if (mentions.isEmpty()) return MentionResolution(null, emptyList())
        val resolved = runCatching { FileMentions.resolve(workspaceRoot(), mentions) }.getOrNull()
            ?: return MentionResolution(null, mentions)
        val block = FileMentions.formatContextBlock(resolved)
        val unknown = resolved.filter { it.path == null }.map { it.mention }
        return MentionResolution(block.ifEmpty { null }, unknown)
    }

    private fun buildHistory(
        session: OcSessionStore.Session?,
        systemText: String,
        messages: List<JSONObject>,
    ): JSONArray {
        val arr = JSONArray()
        arr.put(
            JSONObject().apply {
                put("role", "system")
                put("parts", JSONArray().put(OcMessage.textPart(systemText)))
            },
        )
        // Request-time context (@file mentions + the open editor file) is
        // appended to a COPY of the last user message: every request of the
        // turn carries it, while the stored transcript stays clean.
        val isTopLevel = session != null && session.id == currentSession?.id
        val contextParts = mutableListOf<String>()
        if (isTopLevel) {
            pendingMentionContext?.let { contextParts.add(it) }
            currentFileContext?.let { contextParts.add("---EDITOR CONTEXT---\n$it") }
        }
        val contextText = contextParts.joinToString("\n\n")
        var lastUserIndex = -1
        if (contextText.isNotEmpty()) {
            for (i in messages.indices) if (OcMessage.isUser(messages[i])) lastUserIndex = i
        }
        for ((i, msg) in messages.withIndex()) {
            if (i == lastUserIndex) {
                val copy = JSONObject(msg.toString())
                val part = OcMessage.textPart(contextText, synthetic = true)
                part.put("ignored", true)
                OcMessage.addPart(copy, part)
                arr.put(copy)
            } else {
                arr.put(msg)
            }
        }
        return arr
    }

    /**
     * Tool schemas that fit inside the model's window.
     *
     * The definitions ship with every request and compaction cannot shrink
     * them, so on a small-window model they are fixed overhead that can eat
     * the whole context before a single message is added - measured at over
     * twenty thousand bytes for the full set, against a 4096-token window.
     * When that happens the peripheral tools give way to the ones that read,
     * edit and run code, taken in that order until the budget is spent. A
     * window large enough for everything keeps everything.
     */
    private fun toolDefinitionsFor(model: OcModelInfo, visible: Set<String>, ctx: ToolContext): JSONArray {
        // Below the small-window threshold the full descriptions cost more than
        // the schemas they explain, so each tool ships as its opening sentence.
        val compact = model.contextLimit <= SMALL_CONTEXT_LIMIT
        // The local provider asks for a JSON call in text; it never reads the
        // schema, so a local request can ship argument names and fetch the one
        // full schema it needs with tool_help. Remote function calling needs
        // the whole schema on the wire, so those requests keep it.
        val lazy = model.local
        val all = toolRegistry.definitions(visible, ctx, compact, lazy)
        if (lazy) {
            // Keep the workhorse schemas whole. Lazy descriptions save the
            // window, but a 1-2B model cannot reconstruct a nested array or
            // two required string arguments from a name alone - it either
            // invents a shape or gives up and answers in prose. The fixed
            // overhead of these nine is small next to a failed turn.
            for (i in 0 until all.length()) {
                val def = all.optJSONObject(i) ?: continue
                val name = def.optString("name")
                if (name !in FULL_LOCAL_SCHEMA_TOOLS) continue
                val tool = toolRegistry.get(name) ?: continue
                def.remove("arguments")
                def.put("parameters", tool.parameters())
            }
        }
        val reserve = OcContextBudget.outputReserve(
            model.contextLimit, model.outputLimit, config.compactionReserved(),
        )
        // Schemas ride along on every request and compaction cannot shrink them,
        // so they get half the input budget; the rest has to hold the system
        // prompt and the conversation itself.
        val budgetChars = ((model.contextLimit - reserve).coerceAtLeast(0) / 2) *
            OcContextBudget.CHARS_PER_TOKEN
        if (all.toString().length <= budgetChars) return all
        val byName = mutableMapOf<String, JSONObject>()
        for (i in 0 until all.length()) {
            val def = all.optJSONObject(i) ?: continue
            byName[def.optString("name")] = def
        }
        val kept = JSONArray()
        var used = 0
        for (name in SMALL_CONTEXT_TOOL_ORDER) {
            val def = byName[name] ?: continue
            val size = def.toString().length
            if (used + size > budgetChars) break
            kept.put(def)
            used += size
        }
        return if (kept.length() > 0) kept else all
    }

    private fun visibleTools(agent: OcAgent): Set<String> {
        val all = toolRegistry.names().toSet()
        val result = all.toMutableSet()
        result.removeAll(Permission.disabledTools(all.toList(), agent.permission))
        val configTools = config.tools
        if (configTools != null) {
            for (name in all) {
                if (configTools.has(name) && !configTools.optBoolean(name, true)) result.remove(name)
            }
        }
        val primaryOnly = config.experimental?.optJSONArray("primary_tools")
        if (primaryOnly != null && agent.mode == "subagent") {
            for (i in 0 until primaryOnly.length()) result.remove(primaryOnly.optString(i))
        }
        return result
    }

    private suspend fun executeToolCall(
        call: AIProvider.StreamEvent.ToolCall,
        agent: OcAgent,
        ctx: ToolContext,
    ): JSONObject {
        val state = JSONObject().put("input", call.args)
        // Orchestrated subagents report through the Agents tab; their tool
        // chatter must not leak into the parent conversation. Approval calls
        // below are deliberately not guarded.
        val reportEvents = ctx.orchestrationTaskId == null
        if (ctx.allowedTools.isNotEmpty() && call.name !in ctx.allowedTools) {
            return state.put("status", "error").put(
                "output",
                "Tool '${call.name}' is not allowed for this delegated task. Allowed: " +
                    ctx.allowedTools.sorted().joinToString(", "),
            )
        }
        val tool = toolRegistry.get(call.name)
        if (tool == null) {
            return state.put("status", "error")
                .put("output", "Unknown tool: ${call.name}. Available tools: ${toolRegistry.names().sorted().joinToString(", ")}")
        }
        val argsJson = call.args?.toString() ?: "{}"
        val callCount = recordToolCall(call.name, argsJson)
        if (reportEvents) listener?.onToolStart(call.name, call.args ?: JSONObject())
        state.put("status", "running")
        if (reportEvents) listener?.onStateChange(AgentState.WAITING_TOOL_APPROVAL)

        val permissionKey = tool.permissionKey()
        val pattern = tool.resourcePattern(call.args, ctx)
        val risk = tool.riskHint(call.args, ctx)
        val rules = agent.permission + savedPermissions()
        var action = permissionActionFor(tool, call, agent, ctx, callCount)
        val external = externalResource(tool, call.args, ctx)

        if (action == "deny") {
            if (reportEvents) listener?.onStateChange(AgentState.PROCESSING_TOOL_RESULT)
            return state.put("status", "error")
                .put("output", "Permission denied by policy for tool '$permissionKey' (pattern: $pattern).")
        }
        if (action == "ask") {
            // A file change is approved against what it will actually do, not
            // against the arguments as JSON. Write/edit show a computed diff;
            // patch shows the patch text verbatim.
            val preview = diffPreviewFor(call, risk, ctx)
            val decision = if (preview != null) askDiffApproval(preview)
            else askApproval(call.name, call.args ?: JSONObject(), risk)
            if (!decision.first) {
                if (reportEvents) listener?.onStateChange(AgentState.PROCESSING_TOOL_RESULT)
                return state.put("status", "error")
                    .put("output", "User rejected the tool call '${call.name}'. Continue and adjust.")
            }
            if (decision.second) {
                val rule = PermissionRule(permissionKey, "allow", pattern)
                addSavedPermission(rule)
                if (external != null) addSavedPermission(PermissionRule("external_directory", "allow", external))
            }
        }

        if (reportEvents) listener?.onStateChange(AgentState.EXECUTING_TOOL)
        // Single capture hook for /undo //redo: before ANY file-mutating tool
        // runs, checkpoint the affected file's pre-edit content.
        if (call.name in CHECKPOINT_TOOLS) captureCheckpoint(call.args, ctx, call.name)
        // Shell-running tools stream output line by line through the context;
        // the engine listener turns each line into a live chat update, so the
        // user sees the log arrive while the command still runs.
        ctx.onOutputLine = { line ->
            listener?.onCommandOutput("tool_${call.name}", line)
        }
        val result = try {
            tool.execute(call.args ?: JSONObject(), ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("${call.name} failed: ${e.message}", isError = true)
        } finally {
            ctx.onOutputLine = null
        }
        state.put("status", if (result.isError) "error" else "completed")
        state.put("output", result.output)
        return state
    }

    /**
     * The change a write/edit will make, or the patch text for `patch`.
     *
     * Write and edit are previewed from the real before/after contents, so the
     * dialog shows exactly what the tool will produce. A patch is shown
     * verbatim instead of being simulated: the approved text is then the same
     * text the tool consumes. An edit whose oldString is absent is left alone -
     * the tool will report the mismatch, and guessing at it would hide the
     * error.
     */
    private suspend fun diffPreviewFor(
        call: AIProvider.StreamEvent.ToolCall,
        risk: RiskHint,
        ctx: ToolContext,
    ): DiffPreview? = withContext(Dispatchers.IO) {
        val args = call.args ?: return@withContext null
        if (call.name == "patch") {
            // The patch is the change; it is shown verbatim rather than
            // simulated, so what the user approves is exactly what will run.
            val text = args.optString("patchText", "")
            if (text.isBlank()) return@withContext null
            val firstFile = text.lineSequence()
                .map { it.trim() }
                .firstOrNull {
                    it.startsWith("*** Add File: ") ||
                        it.startsWith("*** Update File: ") ||
                        it.startsWith("*** Delete File: ")
                }
                ?.substringAfter(": ")
                ?.trim()
            return@withContext DiffPreview(
                tool = call.name,
                args = args,
                path = firstFile?.ifEmpty { null } ?: "(patch)",
                before = "",
                after = "",
                risk = risk.toRiskLevel(),
                patch = text,
            )
        }
        if (call.name != "write" && call.name != "edit") return@withContext null
        val path = args.optString("filePath", "").ifEmpty { args.optString("path", "") }
        if (path.isEmpty()) return@withContext null
        val file = ctx.resolveInWorkspace(path)
        if (!ctx.isInsideWorkspace(file)) return@withContext null
        val before = if (file.isFile) {
            runCatching { file.readText() }.getOrNull() ?: return@withContext null
        } else {
            ""
        }
        val after = when (call.name) {
            "write" -> args.optString("content", "")
            else -> {
                val old = args.optString("oldString", "")
                val new = args.optString("newString", "")
                if (old.isEmpty() || !before.contains(old)) return@withContext null
                if (args.optBoolean("replaceAll", false)) before.replace(old, new)
                else before.replaceFirst(old, new)
            }
        }
        if (before == after) return@withContext null
        DiffPreview(
            tool = call.name,
            args = args,
            path = path,
            before = before,
            after = after,
            risk = risk.toRiskLevel(),
        )
    }

    /** The contract of [askApproval], carrying the change that is being approved. */
    private suspend fun askDiffApproval(preview: DiffPreview): Pair<Boolean, Boolean> =
        approvalGate.withLock {
            val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
            withContext(Dispatchers.Main) {
                val surface = listener
                if (surface == null) {
                    deferred.complete(false to false)
                } else {
                    surface.onDiffApproval(preview) { allow, always -> deferred.complete(allow to always) }
                }
            }
            deferred.await()
        }

    /** Tools whose successful run mutates a project file. */
    private val CHECKPOINT_TOOLS = setOf("write", "edit", "patch")

    /**
     * Checkpoints the pre-edit content of the file a mutating tool is about to
     * touch. Content is read on IO; missing files are recorded as "created"
     * (undo deletes them). One call site: [executeToolCall].
     */
    private suspend fun captureCheckpoint(args: JSONObject?, ctx: ToolContext, toolName: String) {
        val paths = mutableListOf<String>()
        if (toolName == "patch") {
            args?.optString("patchText", "").orEmpty().lineSequence().forEach { line ->
                val trimmed = line.trim()
                for (prefix in listOf(
                    "*** Add File: ", "*** Update File: ",
                    "*** Delete File: ", "*** Move to: ",
                )) {
                    if (trimmed.startsWith(prefix)) {
                        val path = trimmed.removePrefix(prefix).trim()
                        if (path.isNotEmpty()) paths.add(path)
                    }
                }
            }
        } else {
            val path = args?.optString("filePath", "")?.ifEmpty { args.optString("path", "") }
            if (!path.isNullOrEmpty()) paths.add(path)
        }
        if (paths.isEmpty()) return
        val turn = ctx.turnId.ifEmpty { currentTurnId.ifEmpty { OcMessage.nextId("turn") } }
        withContext(Dispatchers.IO) {
            for (path in paths.distinct()) {
                runCatching { checkpointStore.capture(ctx.sessionId, turn, ctx.resolveInWorkspace(path)) }
            }
        }
    }

    /** Files the next /undo would restore, with line-count deltas (IO). */
    fun pendingUndoDeltas(): List<CheckpointFileDelta> {
        val sessionId = currentSession?.id ?: return emptyList()
        return runCatching { checkpointStore.describeUndo(sessionId) }.getOrDefault(emptyList())
    }

    /** Rolls back all files of the newest checkpointed turn. Blocking IO. */
    fun undoLastTurn(): CheckpointOutcome {
        val sessionId = currentSession?.id ?: return CheckpointOutcome.NothingToUndo
        val outcome = runCatching { checkpointStore.undo(sessionId) }.getOrElse {
            return CheckpointOutcome.Failed(it.message ?: "undo failed", emptyList())
        }
        if (outcome is CheckpointOutcome.Undone) {
            for (path in outcome.files) listener?.onFileChanged(path)
        }
        return outcome
    }

    /** Re-applies the most recently undone turn. Blocking IO. */
    fun redoLastTurn(): CheckpointOutcome {
        val sessionId = currentSession?.id ?: return CheckpointOutcome.NothingToRedo
        val outcome = runCatching { checkpointStore.redo(sessionId) }.getOrElse {
            return CheckpointOutcome.Failed(it.message ?: "redo failed", emptyList())
        }
        if (outcome is CheckpointOutcome.Redone) {
            for (path in outcome.files) listener?.onFileChanged(path)
        }
        return outcome
    }

    /** Shared permission decision (used by both sequential and parallel execution). */
    private fun permissionActionFor(
        tool: OcTool,
        call: AIProvider.StreamEvent.ToolCall,
        agent: OcAgent,
        ctx: ToolContext,
        callCount: Int,
    ): String {
        val rules = agent.permission + savedPermissions()
        var action = Permission.evaluate(tool.permissionKey(), tool.resourcePattern(call.args, ctx), rules).action
        val external = externalResource(tool, call.args, ctx)
        if (external != null) {
            val extAction = Permission.evaluate("external_directory", external, rules).action
            if (extAction == "deny") action = "deny"
            else if (extAction == "ask" && action != "deny") action = "ask"
        }
        val doom = Permission.evaluate("doom_loop", "*", rules).action
        if (doom == "deny") action = "deny"
        else if (doom == "ask" && callCount >= 3) action = "ask"
        return action
    }

    private fun currentToolCallCount(tool: String, argsHash: String): Int =
        toolCallCounts["$tool|$argsHash"] ?: 0

    /**
     * Runs several auto-allowed read-only tools concurrently with a parallelism
     * chosen by the resource policy. Only reached when every call is read-only
     * and already permitted (no approval prompt is bypassed).
     */
    private suspend fun executeReadOnlyToolsInParallel(
        session: OcSessionStore.Session,
        assistant: JSONObject,
        pendingTools: List<Pair<JSONObject, AIProvider.StreamEvent.ToolCall>>,
        ctx: ToolContext,
    ) {
        listener?.onStateChange(AgentState.EXECUTING_TOOL)
        val snapshot = AndroidMemoryProbe.snapshot(context)
        val tasks = pendingTools.map { (part, call) ->
            suspend {
                listener?.onToolStart(call.name, call.args)
                val tool = toolRegistry.get(call.name)
                val result = if (tool == null) {
                    ToolResult("Unknown tool: ${call.name}", isError = true)
                } else {
                    try {
                        tool.execute(call.args, ctx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolResult("${call.name} failed: ${e.message}", isError = true)
                    }
                }
                Triple(part, call, result)
            }
        }
        val outcome = taskScheduler.runAll(tasks, snapshot, loadedLocalModels = 0, buildRunning = false)
        for ((part, call, result) in outcome.results) {
            val state = JSONObject().put("input", call.args)
                .put("status", if (result.isError) "error" else "completed")
                .put("output", result.output)
            part.put("state", state)
            store.updatePart(session, assistant.optString("id"), part)
            listener?.onToolEnd(
                call.name,
                call.args ?: JSONObject(),
                result.output.take(UI_TOOL_RESULT_LIMIT),
                result.isError,
            )
        }
    }

    private fun externalResource(tool: OcTool, args: JSONObject?, ctx: ToolContext): String? {
        val path = when (tool.name) {
            "read", "write", "edit", "patch" -> args?.optString("path")?.takeIf { it.isNotEmpty() }
            else -> null
        } ?: return null
        val resolved = ctx.resolveInWorkspace(path)
        return if (ctx.isInsideWorkspace(resolved)) null else resolved.absolutePath
    }

    override suspend fun askApproval(
        tool: String,
        args: JSONObject,
        risk: RiskHint,
    ): Pair<Boolean, Boolean> = approvalGate.withLock {
        val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
        withContext(Dispatchers.Main) {
            listener?.onToolCall(tool, args, risk.toRiskLevel()) { allow, always ->
                deferred.complete(allow to always)
            }
        }
        deferred.await()
    }

    override suspend fun downloadFile(
        url: String,
        description: String,
        fileName: String?
    ): DownloadResult {
        // Show download confirmation dialog
        val deferred = CompletableDeferred<DownloadResult>()
        withContext(Dispatchers.Main) {
            listener?.onDownloadFile(url, description, fileName) { result ->
                deferred.complete(result)
            }
        }
        return deferred.await()
    }

    private fun savedPermissions(): List<PermissionRule> = runCatching {
        val json = JSONArray(AiSettings(context).ocPermissionsJson())
        val out = mutableListOf<PermissionRule>()
        for (i in 0 until json.length()) {
            val o = json.optJSONObject(i) ?: continue
            out.add(
                PermissionRule(
                    o.optString("permission"), o.optString("action", "allow"), o.optString("pattern"),
                ),
            )
        }
        out
    }.getOrDefault(emptyList())

    private fun addSavedPermission(rule: PermissionRule) {
        val settings = AiSettings(context)
        val arr = runCatching { JSONArray(settings.ocPermissionsJson()) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("permission") == rule.permission && o.optString("pattern") == rule.pattern) {
                o.put("action", rule.action)
                settings.setOcPermissionsJson(arr.toString())
                return
            }
        }
        arr.put(JSONObject().put("permission", rule.permission).put("action", rule.action).put("pattern", rule.pattern))
        settings.setOcPermissionsJson(arr.toString())
    }

    override suspend fun spawnSubagent(
        subagentType: String,
        prompt: String,
        description: String,
        taskId: String?,
        parentSessionId: String,
        background: Boolean,
        depth: Int,
    ): String {
        val agents = agentRegistry.agents()
        val agent = agents[subagentType]
            ?: return "Error: unknown subagent '$subagentType'. Available: ${agents.keys.sorted().joinToString(", ")}"
        if (maxOf(subagentDepth, depth) >= config.subagentDepth) {
            return "Error: Maximum subagent nesting depth (${config.subagentDepth}) reached. Continue the task yourself instead of spawning more subagents."
        }
        if (background) {
            scope.launch { runSubagent(agent, prompt, taskId, background = true) }
            return "The task is running in the background. You will be notified when it finishes. task_id: ${taskId ?: "pending"}"
        }
        return runSubagent(agent, prompt, taskId, background = false)
    }

    private suspend fun runSubagent(
        agent: OcAgent,
        prompt: String,
        taskId: String?,
        background: Boolean,
    ): String {
        val session = if (taskId != null) {
            store.load(taskId) ?: return "Error: failed to load subagent session '$taskId'"
        } else {
            val modelJson = agent.model?.let { JSONObject().put("ref", it) }
                ?: currentModelRef()?.let { JSONObject().put("ref", it.toString()) }
            store.create(agent.name, modelJson)
        }
        subagentDepth++
        try {
            val modelJson = agent.model?.let { JSONObject().put("ref", it) }
            val msg = OcMessage.userMessage(session.id, agent.name, modelJson)
            OcMessage.addPart(msg, OcMessage.textPart(prompt))
            store.updateMessage(session, msg)
            val result = runLoop(session, agent, subagentDepth)
            if (background) {
                withContext(Dispatchers.Main) {
                    listener?.onMessage(
                        "[background task ${session.id} finished]\n$result", true,
                    )
                }
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "Error: subagent failed: ${e.message}"
        } finally {
            subagentDepth--
        }
    }

    // ------------------------------------------------------- orchestration

    override suspend fun delegateTasks(
        request: JSONObject,
        parentSessionId: String,
        depth: Int,
        rootSessionId: String,
        parentTaskId: String?,
    ): String {
        val snapshot = orchestratorHub.run(
            request = request,
            parentSessionId = parentSessionId,
            parentTaskId = parentTaskId,
            depth = depth,
            rootSessionId = rootSessionId,
        )
        return formatOrchestration(snapshot)
    }

    /**
     * Runs one orchestrator task on the already-selected model. The model id is
     * validated against the live registry again: a selection snapshot may be a
     * few hundred milliseconds old and a local file or API key can disappear.
     */
    /**
     * Explicit task tools are a request; an empty list gets a conservative
     * allowlist derived from the task wording. The model never receives the
     * whole tool surface just because it was delegated to.
     */
    private fun effectiveAllowedTools(task: AgentTask): Set<String> {
        if (task.allowedTools.isNotEmpty()) {
            val capped = if (task.toolCeiling.isEmpty()) task.allowedTools
            else task.allowedTools intersect task.toolCeiling
            // Empty means "all tools" in ToolContext; never let attenuation
            // turn into escalation when the intersection is empty.
            return if (task.toolCeiling.isNotEmpty() && capped.isEmpty()) setOf("__no_tools__") else capped
        }
        val text = buildString {
            append(task.description).append('\n').append(task.prompt)
            if (!task.inputContext.isNullOrBlank()) append('\n').append(task.inputContext)
        }.lowercase()
        val tools = linkedSetOf("read", "glob", "grep")
        val edit = task.filesHint.isNotEmpty() ||
            listOf(
                "edit", "write", "patch", "fix", "implement", "refactor", "rename",
                "modify", "change", "update", "add ", "remove", "delete", "create",
                "수정", "작성", "구현", "리팩터", "변경", "추가", "삭제", "고쳐",
            ).any(text::contains)
        if (edit) {
            tools.addAll(listOf("write", "edit", "patch", "project_diagnostics"))
        }
        if (listOf("build", "gradle", "compile", "test", "run ", "execute", "빌드", "컴파일", "테스트", "실행")
                .any(text::contains)
        ) {
            tools.addAll(listOf("bash", "gradle_build", "project_diagnostics"))
        }
        if (listOf("android", "logcat", "device", "기기", "안드로이드").any(text::contains)) {
            tools.addAll(listOf("android_logcat", "device_info"))
        }
        if (listOf("web", "url", "http", "search the internet", "인터넷", "웹").any(text::contains)) {
            tools.addAll(listOf("webfetch", "websearch"))
        }
        // Chained delegation stays available; the hub enforces the configured
        // depth, total-task limit and loop guard for every nested batch.
        tools.add("delegate")
        if (task.toolCeiling.isNotEmpty()) {
            val capped = tools intersect task.toolCeiling
            return if (capped.isEmpty()) setOf("__no_tools__") else capped
        }
        return tools
    }

    private suspend fun runOrchestratedTask(
        task: AgentTask,
        model: AgentModel,
        depth: Int,
    ): AgentRunResult {
        val ref = OcModelRef.parse(model.id)
            ?: return AgentRunResult.failure("invalid model reference '${model.id}'", AgentErrorKind.MODEL_UNSUPPORTED)
        val info = providerRegistry.getModel(ref)
            ?: return AgentRunResult.failure(
                "model ${model.id} is no longer available",
                AgentErrorKind.MODEL_UNSUPPORTED,
            )
        val agents = agentRegistry.agents()
        val base = agents[task.subagentType] ?: agents["general"]
            ?: return AgentRunResult.failure(
                "unknown subagent type '${task.subagentType}'",
                AgentErrorKind.BAD_REQUEST,
            )
        // The model is an explicit override; the agent's normal prompt,
        // permissions and step limit still apply.
        val agent = base.copy(model = model.id)
        val effectiveTools = effectiveAllowedTools(task)
        val session = store.create(
            agent = agent.name,
            model = modelJsonOf(info),
            parentID = task.parentSessionId.ifBlank { null },
            title = task.description,
        )
        val prompt = buildString {
            append("You are a delegated subagent. Complete only this task and report exactly what you did.\n")
            append("Task: ").append(task.description).append("\n\n")
            if (!task.inputContext.isNullOrBlank()) {
                append("Context and prior attempts:\n").append(task.inputContext).append("\n\n")
            }
            append(task.prompt)
            if (effectiveTools.isNotEmpty()) {
                append("\n\nAllowed tools for this task: ")
                append(effectiveTools.sorted().joinToString(", "))
            }
        }
        val message = OcMessage.userMessage(session.id, agent.name, modelJsonOf(info))
        OcMessage.addPart(message, OcMessage.textPart(prompt))
        store.updateMessage(session, message)
        sessionErrors.remove(session.id)
        sessionErrorMessages.remove(session.id)

        val text = try {
            runLoop(
                session = session,
                agent = agent,
                depth = depth,
                orchestrationTaskId = task.id,
                allowedTools = effectiveTools,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AgentRunResult.failure(
                "subagent failed: ${e.message ?: e.javaClass.simpleName}",
                classifyEngineException(e),
            )
        }

        val streamError = sessionErrorMessages.remove(session.id)
        val streamInfo = sessionErrors.remove(session.id)
        val messages = store.messages(session)
        val changed = linkedSetOf<String>()
        val toolErrors = mutableListOf<String>()
        var completedTools = 0
        for (msg in messages) {
            for (part in OcMessage.toolParts(msg)) {
                val state = part.optJSONObject("state") ?: continue
                val tool = part.optString("tool")
                when (state.optString("status")) {
                    "error" -> toolErrors.add(
                        "$tool: ${state.optString("output").take(600).ifBlank { "failed" }}",
                    )
                    "completed" -> {
                        completedTools++
                        if (tool in setOf("write", "edit", "patch")) {
                            val input = state.optJSONObject("input") ?: JSONObject()
                            if (tool == "patch") {
                                val patch = input.optString("patchText")
                                patch.lineSequence().forEach { line ->
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("*** Add File: ") ||
                                        trimmed.startsWith("*** Update File: ") ||
                                        trimmed.startsWith("*** Delete File: ")
                                    ) {
                                        val path = trimmed.substringAfter(": ").trim()
                                        if (path.isNotEmpty()) changed.add(displayPath(path))
                                    }
                                }
                            } else {
                                val path = input.optString("filePath")
                                    .ifEmpty { input.optString("path") }
                                if (path.isNotBlank()) changed.add(displayPath(path))
                            }
                        }
                    }
                }
            }
        }
        val diff = checkpointDiff(session.id)
        val tokensIn = (prompt.length / 4).toLong().coerceAtLeast(1)
        val tokensOut = (text.length / 4).toLong()
        val kind = if (streamError != null) mapApiErrorKind(streamInfo, streamError) else AgentErrorKind.NONE
        val hasOutput = text.isNotBlank() || changed.isNotEmpty() || completedTools > 0
        val success = streamError == null && hasOutput
        val errors = buildList {
            if (streamError != null) add(streamError)
            addAll(toolErrors)
        }
        val verification = "not run by orchestrator; the main agent must run the relevant tests/build"
        val summary = when {
            success && text.isNotBlank() -> text.take(600)
            success -> "Subagent completed ${completedTools} tool call(s)"
            streamError != null -> streamError
            else -> "Subagent returned no output"
        }
        return AgentRunResult(
            success = success,
            output = text,
            summary = summary,
            changedFiles = changed.toList(),
            diff = diff,
            errors = errors,
            verification = verification,
            needsFollowUp = toolErrors.isNotEmpty(),
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            tokensEstimated = true,
            costUsd = null,
            errorKind = if (success) AgentErrorKind.NONE else kind,
            retryable = !success && kind.retryable,
        )
    }

    private fun formatOrchestration(snapshot: OrchestratorSnapshot): String {
        val status = when {
            snapshot.tasks.isEmpty() -> "REJECTED"
            snapshot.success == true -> "SUCCEEDED"
            else -> "FAILED"
        }
        val sb = StringBuilder()
        sb.append("DELEGATION ").append(status).append(" (").append(snapshot.batchId).append(")\n")
        sb.append(snapshot.summary.ifBlank { "no summary" }).append('\n')
        sb.append("verification: ")
            .append(snapshot.verification ?: "not run - the main agent must verify")
            .append('\n')
        val budget = snapshot.budget
        sb.append("budget: API calls ").append(budget.apiCalls)
            .append('/').append(budget.maxApiCalls)
            .append(", tokens ~").append(budget.totalTokens)
            .append('/').append(budget.maxTokens)
        sb.append(", cost ").append(
            budget.costUsd?.let { "~$" + String.format(java.util.Locale.US, "%.4f", it) }
                ?: "unknown (pricing not configured)",
        ).append('\n')
        if (snapshot.tasks.isEmpty()) {
            sb.append("reason: ").append(snapshot.summary.ifBlank { "delegation was rejected" }).append('\n')
            return sb.toString()
        }
        sb.append("tasks:\n")
        val perTask = (14_000 / snapshot.tasks.size.coerceAtLeast(1)).coerceIn(900, 3_000)
        for ((index, task) in snapshot.tasks.withIndex()) {
            val result = task.result
            val lastModel = task.modelHistory.lastOrNull()?.modelId ?: task.assignedModelId ?: "(not assigned)"
            sb.append(index + 1).append(". [").append(task.status.name).append("] ")
                .append(task.description).append('\n')
            sb.append("   model=").append(lastModel)
                .append(" difficulty=").append(task.difficulty.name)
                .append(" attempts=").append(task.modelHistory.size)
            task.durationMs?.let { sb.append(" duration=").append(it).append("ms") }
            sb.append('\n')
            if (task.modelHistory.size > 1) {
                sb.append("   attempts: ")
                sb.append(task.modelHistory.joinToString(" -> ") {
                    "${it.modelId}${if (it.success) ":ok" else ":fail"}"
                }).append('\n')
            }
            if (task.error != null) sb.append("   error: ").append(task.error).append('\n')
            if (result != null) {
                sb.append("   summary: ").append(result.summary.take(600)).append('\n')
                if (result.changedFiles.isNotEmpty()) {
                    sb.append("   changed: ").append(result.changedFiles.joinToString(", ").take(1_000)).append('\n')
                }
                if (result.errors.isNotEmpty()) {
                    sb.append("   errors: ").append(result.errors.joinToString(" | ").take(1_200)).append('\n')
                }
                if (result.needsFollowUp) sb.append("   needs follow-up: yes\n")
                if (result.costUsd != null) {
                    sb.append("   cost: ~$")
                        .append(String.format(java.util.Locale.US, "%.4f", result.costUsd)).append('\n')
                }
                val output = result.output.trim()
                if (output.isNotEmpty()) {
                    sb.append("   output:\n").append(output.take(perTask)).append('\n')
                }
                val diff = result.diff
                if (!diff.isNullOrBlank()) {
                    sb.append("   diff (truncated):\n").append(diff.take(1_500)).append('\n')
                }
            }
        }
        sb.append("Next: review changed files, resolve overlaps, and run the project's tests/build before claiming success.\n")
        return sb.toString().take(20_000)
    }

    private fun displayPath(path: String): String {
        val file = File(path).let { if (it.isAbsolute) it else File(config.worktree, path) }
        val relative = runCatching { file.relativeTo(config.worktree).path }.getOrElse { file.path }
        return relative.replace('\\', '/').removePrefix("./")
    }

    /**
     * A real diff for files the subagent touched, reconstructed from the
     * per-subagent checkpoint turn. Never a fabricated summary.
     */
    private fun checkpointDiff(sessionId: String): String? {
        val turn = runCatching { checkpointStore.pendingUndoTurn(sessionId) }.getOrNull() ?: return null
        val out = StringBuilder()
        for (entry in turn.entries.take(20)) {
            val file = File(entry.path)
            val after = runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
            val before = entry.content ?: ""
            if (before == after) continue
            val relative = displayPath(file.path)
            out.append("--- a/").append(relative).append('\n')
            out.append("+++ b/").append(relative).append('\n')
            for (line in AiDiff.preview(before, after, maxLines = 240, context = 3)) {
                out.append(line.kind).append(line.text).append('\n')
            }
            if (out.length >= 24_000) {
                out.append("... diff truncated\n")
                break
            }
        }
        return out.toString().ifBlank { null }
    }

    private fun mapApiErrorKind(info: ApiErrorInfo?, message: String): AgentErrorKind {
        val lower = message.lowercase()
        if (("context" in lower && ("exceed" in lower || "too long" in lower)) ||
            ("maximum context" in lower) || ("token limit" in lower)
        ) {
            return AgentErrorKind.CONTEXT_OVERFLOW
        }
        if ("out of memory" in lower || "oom" in lower || "failed to allocate" in lower) {
            return AgentErrorKind.MEMORY
        }
        return when (info?.kind) {
            ApiErrorKind.RATE_LIMIT, ApiErrorKind.MODEL_OVERLOADED -> AgentErrorKind.RATE_LIMIT
            ApiErrorKind.SERVER_ERROR -> AgentErrorKind.SERVER
            ApiErrorKind.NETWORK -> AgentErrorKind.NETWORK
            ApiErrorKind.TIMEOUT -> AgentErrorKind.TIMEOUT
            ApiErrorKind.AUTH, ApiErrorKind.PERMISSION -> AgentErrorKind.AUTH
            ApiErrorKind.QUOTA_EXHAUSTED -> AgentErrorKind.QUOTA_EXHAUSTED
            ApiErrorKind.MODEL_NOT_FOUND -> AgentErrorKind.MODEL_UNSUPPORTED
            ApiErrorKind.BAD_REQUEST -> AgentErrorKind.BAD_REQUEST
            ApiErrorKind.CANCELLED -> AgentErrorKind.CANCELLED
            ApiErrorKind.UNKNOWN, null -> AgentErrorKind.UNKNOWN
        }
    }

    private fun classifyEngineException(e: Exception): AgentErrorKind {
        val name = e.javaClass.name.lowercase()
        val message = (e.message ?: "").lowercase()
        return when {
            "out of memory" in message || "oom" in message -> AgentErrorKind.MEMORY
            "timeout" in name || "timed out" in message -> AgentErrorKind.TIMEOUT
            "auth" in name || "permission" in name -> AgentErrorKind.AUTH
            "ioexception" in name || "connect" in name || "network" in name -> AgentErrorKind.NETWORK
            else -> AgentErrorKind.UNKNOWN
        }
    }

    private suspend fun runCompaction(session: OcSessionStore.Session, model: OcModelInfo, agent: OcAgent) {
        listener?.onThinking("Compacting conversation...")
        listener?.onStateChange(AgentState.GENERATING)
        val smallRef = config.smallModel?.let { OcModelRef.parse(it) } ?: model.ref
        val llm = object : OcCompaction.Llm {
            override suspend fun generate(prompt: String, outputTokens: Int): String? {
                val prov = providerRegistry.createProvider(smallRef, runtimeOptions) ?: return null
                val history = JSONArray().put(
                    JSONObject().apply {
                        put("role", "system")
                        put("parts", JSONArray().put(OcMessage.textPart(prompt)))
                    },
                )
                val opts = JSONObject(runtimeOptions.toString())
                    .put("maxOutputTokens", outputTokens).put("temperature", 0.5)
                val sb = StringBuilder()
                prov.generateStream(history, JSONArray(), opts).collect { event ->
                    if (event is AIProvider.StreamEvent.Text) sb.append(event.text)
                    if (event is AIProvider.StreamEvent.Error) return@collect
                }
                return sb.toString().ifBlank { null }
            }
        }
        OcCompaction.compact(
            session.id, session, store, modelJsonOf(model), false, llm,
            agent.model?.let { JSONObject().put("ref", it) },
        )
        listener?.onMessage("[conversation compacted]", true)
    }

    private suspend fun maybeGenerateTitle(session: OcSessionStore.Session, firstResponse: String) {
        if (session.id.startsWith("subagent-")) return
        val current = store.load(session.id) ?: return
        if (current.title.isNotBlank() && current.title != "New session") return
        val messages = store.messages(current)
        val firstUser = messages.firstOrNull { OcMessage.isUser(it) } ?: return
        val text = OcMessage.textOf(firstUser)?.take(400) ?: return
        val smallRef = config.smallModel?.let { OcModelRef.parse(it) } ?: currentModelRef() ?: return
        val titlePrompt = OcAssets.read(context, "ai/prompt/agent/title.txt") ?: "Generate a title."
        val generated = generateText(
            system = titlePrompt,
            user = "Generate a title (3-5 words) for this conversation:\n\n$text",
            modelRef = smallRef,
            temperature = 0.5f,
        ) ?: return
        val title = generated.trim().lineSequence().firstOrNull()
            ?.trim('"', ' ', '\n')?.take(80)
        if (!title.isNullOrBlank()) store.setTitle(current, title)
    }

    suspend fun generateText(
        system: String,
        user: String,
        modelRef: OcModelRef,
        temperature: Float? = null,
    ): String? {
        val prov = providerRegistry.createProvider(modelRef, runtimeOptions) ?: return null
        val history = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("parts", JSONArray().put(OcMessage.textPart(system)))
            })
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(OcMessage.textPart(user)))
            })
        }
        val opts = JSONObject(runtimeOptions.toString())
        if (temperature != null) opts.put("temperature", temperature.toDouble())
        val sb = StringBuilder()
        try {
            prov.generateStream(history, JSONArray(), opts).collect { event ->
                when (event) {
                    is AIProvider.StreamEvent.Text -> sb.append(event.text)
                    is AIProvider.StreamEvent.Error -> throw IllegalStateException(event.message)
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return sb.toString().ifBlank { null }
    }

    private fun buildSystemPrompt(
        agent: OcAgent,
        model: OcModelInfo,
        session: OcSessionStore.Session,
        visibleOverride: Set<String>? = null,
    ): String {
        val visible = visibleOverride ?: visibleTools(agent)
        val sb = StringBuilder()
        sb.append(agent.prompt ?: modelSystemPrompt(model)).append("\n\n")
        sb.append(envBlock(model))
        if ("skill" in visible) {
            val skills = runCatching { skillManager.list() }.getOrDefault(emptyList())
            val denied = skills.filter { Permission.evaluate("skill", it.name, agent.permission).action == "deny" }
            val fmt = runCatching {
                skillManager.fmt(skillManager.availableFor(skills, denied.map { it.name }.toSet()), verbose = true)
            }.getOrDefault("")
            if (fmt.isNotBlank()) {
                sb.append("\n\n").append(OcAssets.read(context, "ai/prompt/tool/skill.txt")?.substringBefore("<skill>"))
                    .append(fmt)
            }
        }
        val instructions = runCatching { instructions.collect(config) }.getOrDefault(emptyList())
        if (instructions.isNotEmpty()) sb.append("\n\n").append(instructions.joinToString("\n\n"))
        // Users reported the agent pasting code into the reply instead of
        // applying it. The tool schemas are already in the request; this makes
        // the expectation explicit for models that treat chat as the output.
        if (agent.name != "plan") {
            // A plain-text inventory of the enabled tools. The schemas are
            // already in the request body, but small models (and providers that
            // ignore tools) only react to the prompt.
            val available = visible.sorted()
            if (available.isNotEmpty()) {
                sb.append("\n\n# Available tools\n").append(available.joinToString(", "))
            }
            if ("delegate" in visible) {
                sb.append(
                    "\n\n# Delegation\n" +
                        "Use `delegate` when a task splits into independent parts or needs a model better suited " +
                        "to a specialist step. You propose tasks; the orchestrator validates them, chooses the " +
                        "actual local or API model, enforces budgets and runs them. Do not invoke another provider " +
                        "or ask for an API key. Do not delegate the same failed task again to bypass retries: the " +
                        "orchestrator retries and promotes models itself. After results return, you are responsible " +
                        "for integrating them and for running the project's tests/build before claiming success.",
                )
            }
            sb.append(
                "\n\n# Apply changes with tools\n" +
                    "When the user asks for a code change, make it with the write, edit or patch tool " +
                    "and then explain briefly. Do not answer with a code block as a substitute for " +
                    "editing the file. Paste a snippet only when explaining something the tools cannot do.",
            )
        }
        if (thinkMode) {
            sb.append(
                "\n\n# Think before acting\n" +
                    "Before calling a tool or starting a multi-step change, state in one or two sentences " +
                    "what you are about to do and why. Then do it in the same turn - stop at the plan only " +
                    "when the user asked for a plan.",
            )
        }
        if (agent.name == "plan") sb.append("\n\n").append(planModeReminder(session))
        return sb.toString().trim() + "\n"
    }

    private fun modelSystemPrompt(model: OcModelInfo): String {
        val id = model.ref.modelID.lowercase()
        val file = when {
            // The full session prompt is written for a frontier window and
            // costs over two thousand tokens before a single message is
            // added - half of a local model's 4096. Small windows get the
            // condensed prompt, which keeps the behavioural rules and drops
            // the worked examples.
            model.contextLimit <= SMALL_CONTEXT_LIMIT -> "ai/prompt/session/compact.txt"
            id.contains("claude") -> "ai/prompt/session/anthropic.txt"
            id.contains("gemini") -> "ai/prompt/session/gemini.txt"
            id.contains("gpt") || id.contains("o1") || id.contains("o3") -> "ai/prompt/session/gpt.txt"
            else -> "ai/prompt/session/default.txt"
        }
        return OcAssets.read(context, file) ?: OcAssets.read(context, "ai/prompt/session/default.txt")
            ?: "You are an AI coding assistant."
    }

    private fun envBlock(model: OcModelInfo): String {
        val root = config.worktree
        val git = File(root, ".git").exists()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        return "You are powered by the model named ${model.ref.modelID}. " +
            "The exact model ID is ${model.ref}\nHere is some useful information " +
            "about the environment you are running in:\n<env>\n" +
            "  Working directory: ${root.absolutePath}\n" +
            "  Workspace root folder: ${root.absolutePath}\n" +
            "  Is directory a git repo: ${if (git) "yes" else "no"}\n" +
            "  Platform: android (arm64-v8a)\n" +
            "  Today's date: $today\n" +
            "</env>"
    }

    private fun planModeReminder(session: OcSessionStore.Session): String {
        val plansDir = agentRegistry.plansDir()
        plansDir.mkdirs()
        val slug = (session.title.ifBlank { session.id })
            .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)
        val planFile = File(plansDir, "$slug.md")
        val info = if (planFile.exists()) {
            "Plan file: ${planFile.absolutePath}"
        } else {
            "No plan file exists yet. You should create your plan at ${planFile.absolutePath} " +
                "using the write tool. (You have write access only to this path.)"
        }
        val template = OcAssets.read(context, "ai/prompt/session/plan-mode.txt") ?: return ""
        return template.replace("\${planInfo}", info)
    }

    private val fixAttempts = mutableMapOf<String, Int>()
    private val validationDiff = DiagnosticDiffEngine()
    private val taskScheduler = TaskScheduler()

    /** Tools that only read state and can safely run concurrently. */
    private val READ_ONLY_TOOLS = setOf(
        "read", "glob", "grep", "webfetch", "websearch",
        "ls", "file_info", "diff", "git", "sqlite_query", "zip_list",
        "network_info", "current_time", "app_info", "model_search", "model_download_status",
    )

    private suspend fun handlePostWriteAnalysis(
        beforeDiagnostics: List<LiveDiagnostic>,
        session: OcSessionStore.Session,
    ) {
        listener?.onThinking("Analyzing changes...")
        val activity = context as? com.aide.mobilestudio.MainActivity
        activity?.triggerLiveDiagnosticsAndWait()?.join()
        delay(1000)
        val after = getGlobalDiagnostics()
        val diff = validationDiff.diff(beforeDiagnostics, after)
        val introduced = diff.newErrorCount
        val fixed = diff.resolvedErrorCount
        val remaining = diff.remainingErrorCount

        if (introduced > 0) {
            listener?.onMessage("[compiler] ${diff.report().replace("\n", " · ")}", false)
            val newDetails = diff.new.joinToString("\n") { d ->
                "${d.file}:${d.line}:${d.column}: ${d.message}"
            }
            val fb = OcMessage.userMessage(session.id, "compiler_feedback", null)
            OcMessage.addPart(
                fb,
                OcMessage.textPart(
                    "[compiler_feedback] The previous write introduced $introduced new error(s):\n" +
                        "$newDetails\nPlease fix them.",
                ),
            )
            store.updateMessage(session, fb)
            activeMessageId = fb.optString("id")
            val key = diff.new.joinToString(",") { "${it.file}:${it.line}:${it.message}" }
            val attempts = fixAttempts.getOrDefault(key, 0)
            if (attempts >= 5) {
                listener?.onMessage("[compiler] Auto-fix attempts (5) exceeded. Manual intervention required.", false)
            } else {
                fixAttempts[key] = attempts + 1
            }
        } else if (fixed > 0) {
            listener?.onMessage("[compiler] This change fixed $fixed error(s).", false)
            if (remaining == 0) listener?.onMessage("[compiler] All errors resolved.", false)
        } else if (remaining == 0 && activity != null) {
            listener?.onMessage("[compiler] No errors introduced.", false)
        }
        if (diff.uncertain.isNotEmpty()) {
            listener?.onMessage(
                "[compiler] ${diff.uncertain.size} diagnostic(s) matched only by similarity; classification is uncertain.",
                false,
            )
        }
    }

    private fun getGlobalDiagnostics(): List<LiveDiagnostic> {
        val activity = context as? com.aide.mobilestudio.MainActivity ?: return emptyList()
        return LiveDiagnostics.fromProblems(activity.getAllProblems(), activity.currentProjectDir)
    }

    private fun RiskHint.toRiskLevel(): RiskLevel = when (this) {
        RiskHint.READ_ONLY -> RiskLevel.READ_ONLY
        RiskHint.BUILD -> RiskLevel.BUILD
        RiskHint.MODIFY -> RiskLevel.MODIFY
        RiskHint.DANGEROUS -> RiskLevel.DANGEROUS
    }

    override fun currentMessageId(): String = activeMessageId

    override suspend fun askQuestion(questions: JSONArray): JSONArray? {
        val deferred = CompletableDeferred<JSONArray?>()
        withContext(Dispatchers.Main) {
            listener?.onAskQuestion(questions) { answers -> deferred.complete(answers) }
        }
        return deferred.await()
    }

    override fun onTodos(todos: JSONArray) {
        currentSession?.let { store.setTodos(it, todos) }
        listener?.onTodos(todos)
    }

    override fun onToolStart(name: String, args: JSONObject) {
        listener?.onToolStart(name, args)
    }

    override fun onToolEnd(name: String, result: String) {
        listener?.onToolEnd(name, result)
    }

    override fun markFileRead(path: String) {
        readFiles.add(path)
    }

    override fun hasReadFile(path: String): Boolean = path in readFiles

    override fun snapshotFile(messageId: String, file: File) {
        val sid = currentSession?.id ?: return
        runCatching { snapshots.snapshot(sid, messageId, file) }
    }

    override fun recordToolCall(tool: String, argsHash: String): Int {
        val key = "$tool|$argsHash"
        val count = (toolCallCounts[key] ?: 0) + 1
        toolCallCounts[key] = count
        return count
    }
}
