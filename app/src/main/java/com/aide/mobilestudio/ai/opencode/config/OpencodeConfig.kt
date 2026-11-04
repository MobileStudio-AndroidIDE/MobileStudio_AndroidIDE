package com.aide.mobilestudio.ai.opencode.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OpencodeConfig private constructor(val raw: JSONObject, val worktree: File) {

    val model: String? get() = raw.optStringOrNull("model")
    val smallModel: String? get() = raw.optStringOrNull("small_model")
    val defaultAgent: String? get() = raw.optStringOrNull("default_agent")
    val subagentDepth: Int get() = raw.optInt("subagent_depth", 1)
    val shell: String? get() = raw.optStringOrNull("shell")
    val snapshotEnabled: Boolean get() = raw.optBoolean("snapshot", true)
    val permission: JSONObject? get() = raw.optJSONObjectOrNull("permission")
    val tools: JSONObject? get() = raw.optJSONObjectOrNull("tools")
    val compaction: JSONObject? get() = raw.optJSONObjectOrNull("compaction")
    val toolOutput: JSONObject? get() = raw.optJSONObjectOrNull("tool_output")
    val experimental: JSONObject? get() = raw.optJSONObjectOrNull("experimental")

    val agents: Map<String, JSONObject>
        get() = raw.optJSONObjectOrNull("agent")?.toMap() ?: emptyMap()

    val commands: Map<String, JSONObject>
        get() = raw.optJSONObjectOrNull("command")?.toMap() ?: emptyMap()

    val providers: Map<String, JSONObject>
        get() = raw.optJSONObjectOrNull("provider")?.toMap() ?: emptyMap()

    val mcp: Map<String, JSONObject>
        get() = raw.optJSONObjectOrNull("mcp")?.toMap() ?: emptyMap()

    val instructions: List<String>
        get() {
            val arr = raw.optJSONArray("instructions") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }

    val skillPaths: List<String>
        get() {
            val obj = raw.optJSONObjectOrNull("skills") ?: return emptyList()
            val arr = obj.optJSONArray("paths") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }

    val skillUrls: List<String>
        get() {
            val obj = raw.optJSONObjectOrNull("skills") ?: return emptyList()
            val arr = obj.optJSONArray("urls") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        }

    fun agentConfig(name: String): JSONObject? = agents[name]

    fun mcpConfig(name: String): JSONObject? = mcp[name]

    fun maxToolOutputLines(): Int = toolOutput?.optInt("max_lines", 2000) ?: 2000

    fun maxToolOutputBytes(): Int = toolOutput?.optInt("max_bytes", 51200) ?: 51200

    fun compactionAuto(): Boolean = compaction?.optBoolean("auto", true) ?: true

    fun compactionPrune(): Boolean = compaction?.optBoolean("prune", false) ?: false

    fun compactionReserved(): Int? = compaction?.let { if (it.has("reserved")) it.optInt("reserved") else null }

    fun compactionTailTurns(): Int? = compaction?.let { if (it.has("tail_turns")) it.optInt("tail_turns") else null }

    fun compactionPreserveRecentTokens(): Int? =
        compaction?.let { if (it.has("preserve_recent_tokens")) it.optInt("preserve_recent_tokens") else null }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key)) return null
        val v = optString(key, "")
        return v.ifEmpty { null }
    }

    private fun JSONObject.optJSONObjectOrNull(key: String): JSONObject? =
        if (has(key) && get(key) is JSONObject) getJSONObject(key) else null

    private fun JSONObject.toMap(): Map<String, JSONObject> {
        val result = mutableMapOf<String, JSONObject>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = get(key)
            if (value is JSONObject) result[key] = value
        }
        return result
    }

    companion object {
        /** App-private global config merged under any project config. */
        fun globalConfigFile(context: Context): File =
            File(File(context.filesDir, "opencode/config").apply { mkdirs() }, "opencode.json")

        /** Adds or replaces a local MCP server in the global config. */
        @Synchronized
        fun upsertMcpServer(context: Context, name: String, command: List<String>, enabled: Boolean = true) {
            val file = globalConfigFile(context)
            val root = if (file.isFile) parse(file.readText()) ?: JSONObject() else JSONObject()
            val mcp = root.optJSONObject("mcp") ?: JSONObject().also { root.put("mcp", it) }
            mcp.put(
                name,
                JSONObject().apply {
                    put("type", "local")
                    put("command", JSONArray(command))
                    put("enabled", enabled)
                },
            )
            file.writeText(root.toString(2))
        }

        @Synchronized
        fun removeMcpServer(context: Context, name: String): Boolean {
            val file = globalConfigFile(context)
            if (!file.isFile) return false
            val root = parse(file.readText()) ?: return false
            val mcp = root.optJSONObject("mcp") ?: return false
            if (!mcp.has(name)) return false
            mcp.remove(name)
            file.writeText(root.toString(2))
            return true
        }

        @Synchronized
        fun load(context: Context, worktree: File?): OpencodeConfig {
            val root = worktree ?: File(context.filesDir, "workspace").apply { mkdirs() }
            val globalFile = File(File(context.filesDir, "opencode/config"), "opencode.json")
            val candidates = listOf(
                File(root, "opencode.json"),
                File(root, "opencode.jsonc"),
                File(root, ".opencode/opencode.json"),
                File(root, ".opencode/opencode.jsonc"),
                File(root, ".opencode.json"),
            )
            val projectFile = candidates.firstOrNull { it.isFile }

            val merged = JSONObject()
            if (globalFile.isFile) {
                val parsed = parse(globalFile.readText())
                if (parsed != null) deepMerge(merged, parsed)
            }
            if (projectFile != null) {
                val parsed = parse(projectFile.readText())
                if (parsed != null) deepMerge(merged, parsed)
            }
            return OpencodeConfig(merged, root)
        }

        fun parse(text: String): JSONObject? = runCatching {
            JSONObject(stripJsonc(text))
        }.getOrNull()

        fun stripJsonc(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            var inString = false
            var escaped = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    sb.append(c)
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                    i++
                    continue
                }
                if (c == '"') {
                    inString = true
                    sb.append(c)
                    i++
                    continue
                }
                if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        fun deepMerge(target: JSONObject, source: JSONObject) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = source.get(key)
                if (key == "instructions") {
                    val existing = target.optJSONArray("instructions") ?: JSONArray()
                    val seen = mutableSetOf<String>()
                    for (i in 0 until existing.length()) seen.add(existing.optString(i))
                    if (value is JSONArray) {
                        for (i in 0 until value.length()) {
                            val item = value.optString(i)
                            if (!seen.contains(item)) {
                                seen.add(item)
                                existing.put(item)
                            }
                        }
                    }
                    target.put("instructions", existing)
                    continue
                }
                if (value is JSONObject && target.opt(key) is JSONObject) {
                    deepMerge(target.getJSONObject(key), value)
                } else {
                    target.put(key, value)
                }
            }
        }
    }
}
