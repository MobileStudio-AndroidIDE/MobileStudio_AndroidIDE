package com.aide.mobilestudio.ai.opencode.tool

import android.content.Context
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ToolResult(val output: String, val isError: Boolean = false)

interface EngineBridge {
    fun currentMessageId(): String
    suspend fun askApproval(tool: String, args: JSONObject, risk: RiskHint): Pair<Boolean, Boolean>
    suspend fun askQuestion(questions: JSONArray): JSONArray?
    suspend fun downloadFile(url: String, description: String, fileName: String?): DownloadResult
    fun onTodos(todos: JSONArray)
    fun onToolStart(name: String, args: JSONObject)
    fun onToolEnd(name: String, result: String)
    fun markFileRead(path: String)
    fun hasReadFile(path: String): Boolean
    fun snapshotFile(messageId: String, file: File)
    fun recordToolCall(tool: String, argsHash: String): Int
    suspend fun spawnSubagent(
        subagentType: String,
        prompt: String,
        description: String,
        taskId: String?,
        parentSessionId: String,
        background: Boolean,
        depth: Int,
    ): String

    /**
     * Routes a model-proposed delegation batch through the shared orchestrator.
     * The orchestrator owns model selection, depth/budget checks and execution;
     * the model never receives provider keys or calls another API directly.
     */
    suspend fun delegateTasks(
        request: JSONObject,
        parentSessionId: String,
        depth: Int,
        rootSessionId: String,
        parentTaskId: String?,
    ): String
}

data class DownloadResult(
    val success: Boolean,
    val filePath: String? = null,
    val error: String? = null
)

enum class RiskHint { READ_ONLY, BUILD, MODIFY, DANGEROUS }

class ToolContext(
    val androidContext: Context,
    val worktree: File,
    val sessionId: String,
    val config: OpencodeConfig,
    val bridge: EngineBridge,
    val tmpDir: File,
    /** Delegation depth of the run that owns this tool call. */
    val depth: Int = 0,
    /** The top-level session, shared by every nested delegation batch. */
    val rootSessionId: String = sessionId,
    /** Set when this run is executing an orchestrator task. */
    val orchestrationTaskId: String? = null,
    /** Empty means "all tools the agent is allowed to see by its permissions". */
    val allowedTools: Set<String> = emptySet(),
    /** Checkpoint turn owned by this run; parallel subagents must not share it. */
    val turnId: String = "",
) {
    /**
     * Live line callback for shell-running tools. The engine points it at the
     * engine listener before executing a tool and clears it afterwards, so a
     * long-running command streams output into the chat surface as it is
     * produced instead of freezing until the command completes.
     */
    internal var onOutputLine: ((String) -> Unit)? = null

    fun resolveInWorkspace(path: String): File {
        val f = File(path)
        return if (f.isAbsolute) f else File(worktree, path)
    }

    fun isInsideWorkspace(file: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
        val root = runCatching { worktree.canonicalFile }.getOrDefault(worktree)
        return canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
    }
}

interface OcTool {
    val name: String
    fun description(ctx: ToolContext): String
    fun parameters(): JSONObject
    suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult

    fun permissionKey(): String = name

    fun resourcePattern(args: JSONObject, ctx: ToolContext): String = "*"

    fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.READ_ONLY

    /**
     * A one-line version of [description] for small context windows.
     *
     * The full descriptions carry worked examples and edge cases meant for a
     * frontier model, and they are re-sent on every single request. A small
     * window cannot afford them, so it gets the opening sentence instead -
     * enough to choose the right tool - while the parameter schema stays
     * whole, because without it the call cannot be made at all.
     */
    fun shortDescription(ctx: ToolContext): String = firstSentence(description(ctx))
}

/** Length cap for [OcTool.shortDescription]; long enough to describe, short enough to afford. */
private const val SHORT_DESCRIPTION_MAX = 200

/**
 * Collapses a description to its opening sentence.
 *
 * Whitespace is normalised first so multi-line descriptions do not smuggle
 * their blank lines into the prompt, and the result is capped rather than
 * trusted: a tool whose first sentence runs long still has to fit.
 */
private fun firstSentence(text: String): String {
    val normalised = text.replace(Regex("\\s+"), " ").trim()
    val terminator = normalised.indexOf(". ")
    val sentence = if (terminator > 0) normalised.substring(0, terminator + 1) else normalised
    return if (sentence.length <= SHORT_DESCRIPTION_MAX) sentence
    else sentence.take(SHORT_DESCRIPTION_MAX).trimEnd() + "…"
}

class OcToolRegistry {

    private val tools = LinkedHashMap<String, OcTool>()

    fun register(tool: OcTool) {
        tools[tool.name] = tool
    }

    fun registerAll(tools: List<OcTool>) {
        for (tool in tools) register(tool)
    }

    fun get(name: String): OcTool? = tools[name]

    fun names(): List<String> = tools.keys.toList()

    fun all(): List<OcTool> = tools.values.toList()

    /**
     * Schemas for the tools the agent may call.
     *
     * [compact] swaps each description for its opening sentence.
     *
     * [lazy] replaces the full parameter schema with the argument names. It is
     * only for the local provider: that provider asks the model for a JSON
     * call in plain text, so the schema is prompt text rather than something
     * the wire format requires. A remote provider's function calling needs the
     * whole schema, and omitting it there would make every call unencodable -
     * so remote models never see lazily-described tools. [ToolHelpTool] fills
     * in whatever is left out, and the engine keeps it inside the budget.
     */
    fun definitions(
        visible: Set<String>,
        ctx: ToolContext,
        compact: Boolean = false,
        lazy: Boolean = false,
    ): JSONArray {
        val result = JSONArray()
        for (tool in tools.values) {
            if (!visible.contains(tool.name)) continue
            result.put(JSONObject().apply {
                put("name", tool.name)
                put("description", if (compact) tool.shortDescription(ctx) else tool.description(ctx))
                if (lazy) put("arguments", compactArgumentSummary(tool.parameters()))
                else put("parameters", tool.parameters())
            })
        }
        return result
    }
}

/**
 * `filePath:string*, limit:int?` - the names and types a call needs, with
 * `*` marking a required argument and `?` an optional one.
 *
 * A small model copies the shape of a call; the names are what it cannot
 * guess, while the prose about each argument matters less than fitting in
 * the window. `tool_help` carries the full text when it is wanted. Kept at
 * file scope so the format can be tested without an Android ToolContext.
 */
internal fun compactArgumentSummary(schema: JSONObject): String {
    val properties = schema.optJSONObject("properties") ?: return "none"
    val required = mutableSetOf<String>()
    val requiredArray = schema.optJSONArray("required")
    if (requiredArray != null) {
        for (i in 0 until requiredArray.length()) required.add(requiredArray.optString(i))
    }
    val parts = mutableListOf<String>()
    val keys = properties.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val type = properties.optJSONObject(key)?.optString("type").orEmpty().ifEmpty { "any" }
        parts.add(key + ":" + type + if (key in required) "" else "?")
    }
    return parts.joinToString(", ").ifEmpty { "none" }
}
