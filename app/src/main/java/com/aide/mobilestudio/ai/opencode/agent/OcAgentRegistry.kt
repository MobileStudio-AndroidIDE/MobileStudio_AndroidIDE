package com.aide.mobilestudio.ai.opencode.agent

import android.content.Context
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.permission.Permission
import com.aide.mobilestudio.ai.opencode.permission.PermissionRule
import com.aide.mobilestudio.ai.opencode.tool.OcAssets
import java.io.File

data class OcAgent(
    val name: String,
    val description: String?,
    val mode: String,
    val hidden: Boolean,
    val temperature: Float?,
    val topP: Float?,
    val steps: Int?,
    val prompt: String?,
    val permission: List<PermissionRule>,
    val model: String?,
    val native: Boolean,
)

class OcAgentRegistry(
    private val context: Context,
    private val configProvider: () -> OpencodeConfig,
) {

    fun tmpDir(): File = File(context.filesDir, "tmp").apply { mkdirs() }

    fun plansDir(): File = File(configProvider().worktree, ".opencode/plans").apply { mkdirs() }

    private fun whitelistedDirs(): List<String> {
        val config = configProvider()
        val dirs = mutableListOf(File(context.filesDir, "tmp"))
        for (path in config.skillPaths) {
            dirs.add(if (File(path).isAbsolute) File(path) else File(config.worktree, path))
        }
        return dirs.map { it.absolutePath.replace("\\", "/") }
    }

    private fun defaultRuleset(): List<PermissionRule> {
        val config = configProvider()
        val tmpGlob = tmpDir().absolutePath.replace("\\", "/") + "/*"
        val externalDirectory = org.json.JSONObject()
        externalDirectory.put("*", "ask")
        for (dir in whitelistedDirs()) externalDirectory.put(dir + "/*", "allow")
        val defaults = org.json.JSONObject()
        defaults.put("*", "allow")
        defaults.put("doom_loop", "ask")
        defaults.put("external_directory", externalDirectory)
        defaults.put("question", "deny")
        defaults.put("plan_enter", "deny")
        defaults.put("plan_exit", "deny")
        val read = org.json.JSONObject()
        read.put("*", "allow")
        read.put("*.env", "ask")
        read.put("*.env.*", "ask")
        read.put("*.env.example", "allow")
        defaults.put("read", read)
        return Permission.fromConfig(defaults)
    }

    private fun fromConfigObject(obj: org.json.JSONObject?): List<PermissionRule> =
        Permission.fromConfig(obj)

    fun agents(): Map<String, OcAgent> {
        val config = configProvider()
        val defaults = defaultRuleset()
        val user = fromConfigObject(config.permission)

        val plansDirGlob = plansDir().absolutePath.replace("\\", "/") + "/*"
        val relativePlans = ".opencode/plans/*.md"

        val planEdit = org.json.JSONObject()
        planEdit.put("*", "deny")
        planEdit.put(relativePlans, "allow")
        val planExternal = org.json.JSONObject()
        planExternal.put(plansDirGlob, "allow")
        val planTask = org.json.JSONObject()
        planTask.put("general", "deny")

        val readonlyExternal = org.json.JSONObject()
        readonlyExternal.put("*", "ask")
        for (dir in whitelistedDirs()) readonlyExternal.put(dir + "/*", "allow")

        val explorePermission = org.json.JSONObject()
        explorePermission.put("*", "deny")
        explorePermission.put("grep", "allow")
        explorePermission.put("glob", "allow")
        explorePermission.put("list", "allow")
        explorePermission.put("bash", "allow")
        explorePermission.put("webfetch", "allow")
        explorePermission.put("websearch", "allow")
        explorePermission.put("read", "allow")
        explorePermission.put("external_directory", readonlyExternal)

        val allDeny = org.json.JSONObject()
        allDeny.put("*", "deny")

        val agents = linkedMapOf(
            "build" to OcAgent(
                name = "build",
                description = "The default agent. Executes tools based on configured permissions.",
                mode = "primary",
                hidden = false,
                temperature = null,
                topP = null,
                steps = null,
                prompt = null,
                permission = Permission.merge(
                    defaults,
                    fromConfigObject(
                        org.json.JSONObject().apply {
                            put("question", "allow")
                            put("plan_enter", "allow")
                        },
                    ),
                    user,
                ),
                model = null,
                native = true,
            ),
            "plan" to OcAgent(
                name = "plan",
                description = "Plan mode. Disallows all edit tools.",
                mode = "primary",
                hidden = false,
                temperature = null,
                topP = null,
                steps = null,
                prompt = null,
                permission = Permission.merge(
                    defaults,
                    fromConfigObject(
                        org.json.JSONObject().apply {
                            put("question", "allow")
                            put("plan_exit", "allow")
                            put("task", planTask)
                            put("external_directory", planExternal)
                            put("edit", planEdit)
                        },
                    ),
                    user,
                ),
                model = null,
                native = true,
            ),
            "general" to OcAgent(
                name = "general",
                description = "General-purpose agent for researching complex questions and executing multi-step tasks. Use this agent to execute multiple units of work in parallel.",
                mode = "subagent",
                hidden = false,
                temperature = null,
                topP = null,
                steps = null,
                prompt = null,
                permission = Permission.merge(
                    defaults,
                    fromConfigObject(org.json.JSONObject().apply { put("todowrite", "deny") }),
                    user,
                ),
                model = null,
                native = true,
            ),
            "explore" to OcAgent(
                name = "explore",
                description = "Fast agent specialized for exploring codebases. Use this when you need to quickly find files by patterns (eg. \"src/components/**/*.tsx\"), search code for keywords (eg. \"API endpoints\"), or answer questions about the codebase (eg. \"how do API endpoints work?\"). When calling this agent, specify the desired thoroughness level: \"quick\" for basic searches, \"medium\" for moderate exploration, or \"very thorough\" for comprehensive analysis across multiple locations and naming conventions.",
                mode = "subagent",
                hidden = false,
                temperature = null,
                topP = null,
                steps = null,
                prompt = OcAssets.read(context, "ai/prompt/agent/explore.txt"),
                permission = Permission.merge(defaults, fromConfigObject(explorePermission), user),
                model = null,
                native = true,
            ),
            "compaction" to OcAgent(
                name = "compaction",
                description = null,
                mode = "primary",
                hidden = true,
                temperature = null,
                topP = null,
                steps = null,
                prompt = OcAssets.read(context, "ai/prompt/agent/compaction.txt"),
                permission = Permission.merge(defaults, fromConfigObject(allDeny), user),
                model = null,
                native = true,
            ),
            "title" to OcAgent(
                name = "title",
                description = null,
                mode = "primary",
                hidden = true,
                temperature = 0.5f,
                topP = null,
                steps = null,
                prompt = OcAssets.read(context, "ai/prompt/agent/title.txt"),
                permission = Permission.merge(defaults, fromConfigObject(allDeny), user),
                model = null,
                native = true,
            ),
            "summary" to OcAgent(
                name = "summary",
                description = null,
                mode = "primary",
                hidden = true,
                temperature = null,
                topP = null,
                steps = null,
                prompt = OcAssets.read(context, "ai/prompt/agent/summary.txt"),
                permission = Permission.merge(defaults, fromConfigObject(allDeny), user),
                model = null,
                native = true,
            ),
        )

        for ((key, value) in config.agents) {
            if (value.optBoolean("disable")) {
                agents.remove(key)
                continue
            }
            val existing = agents[key]
            val base = existing ?: OcAgent(
                name = key,
                description = null,
                mode = "all",
                hidden = false,
                temperature = null,
                topP = null,
                steps = null,
                prompt = null,
                permission = Permission.merge(defaults, user),
                model = null,
                native = false,
            )
            val promptAsset = value.optStringOrNull("prompt")
            agents[key] = base.copy(
                model = value.optStringOrNull("model") ?: base.model,
                prompt = promptAsset ?: base.prompt,
                description = value.optStringOrNull("description") ?: base.description,
                temperature = (if (value.has("temperature")) value.optDouble("temperature").toFloat() else null) ?: base.temperature,
                topP = (if (value.has("top_p")) value.optDouble("top_p").toFloat() else null) ?: base.topP,
                mode = value.optStringOrNull("mode") ?: base.mode,
                hidden = if (value.has("hidden")) value.optBoolean("hidden") else base.hidden,
                steps = (if (value.has("steps")) value.optInt("steps") else null) ?: base.steps,
                permission = Permission.merge(base.permission, fromConfigObject(value.optJSONObject("permission"))),
            )
        }
        return agents
    }

    fun get(name: String): OcAgent = agents()[name] ?: agents()["build"]!!

    fun defaultAgent(): String {
        val config = configProvider()
        val configured = config.defaultAgent
        if (configured != null) {
            val agent = agents()[configured]
            if (agent != null && (agent.mode == "primary" || agent.mode == "all")) return configured
        }
        return "build"
    }

    fun listVisible(): List<OcAgent> = agents().values.filter { !it.hidden }

    fun primaryAgents(): List<OcAgent> = agents().values.filter { it.mode == "primary" && !it.hidden }

    fun subagents(): List<OcAgent> = agents().values.filter { (it.mode == "subagent" || it.mode == "all") && !it.hidden }

    private fun org.json.JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}
