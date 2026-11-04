package com.aide.mobilestudio.ai.opencode.command

import android.content.Context
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.mcp.OcMcpClient
import com.aide.mobilestudio.ai.opencode.skill.OcSkill

data class OcCommandInfo(
    val name: String,
    val description: String?,
    val agent: String?,
    val model: String?,
    val subtask: Boolean,
    val hints: List<String>,
    val source: String,
    val template: suspend () -> String,
)

class OcCommandRegistry(
    private val context: Context,
    private val configProvider: () -> OpencodeConfig,
    private val skillProvider: () -> OcSkill,
    private val mcpServersProvider: () -> List<OcMcpClient.ServerInfo>,
    private val mcpClient: OcMcpClient,
) {

    fun hints(template: String): List<String> {
        val result = mutableListOf<String>()
        val numbered = Regex("\\$\\d+").findAll(template).map { it.value }.toSortedSet()
        result.addAll(numbered)
        if (template.contains("\$ARGUMENTS")) result.add("\$ARGUMENTS")
        return result
    }

    fun list(): List<OcCommandInfo> {
        val config = configProvider()
        val commands = linkedMapOf<String, OcCommandInfo>()

        val initializeTemplate = com.aide.mobilestudio.ai.opencode.tool.OcAssets.read(
            context, "ai/prompt/command/initialize.txt",
        ) ?: ""
        commands["init"] = OcCommandInfo(
            name = "init",
            description = "guided AGENTS.md setup",
            agent = null,
            model = null,
            subtask = false,
            hints = hints(initializeTemplate),
            source = "command",
            template = { initializeTemplate.replace("\${path}", config.worktree.absolutePath) },
        )

        val reviewTemplate = com.aide.mobilestudio.ai.opencode.tool.OcAssets.read(
            context, "ai/prompt/command/review.txt",
        ) ?: ""
        commands["review"] = OcCommandInfo(
            name = "review",
            description = "review changes [commit|branch|pr], defaults to uncommitted",
            agent = null,
            model = null,
            subtask = true,
            hints = hints(reviewTemplate),
            source = "command",
            template = { reviewTemplate.replace("\${path}", config.worktree.absolutePath) },
        )

        for ((name, command) in config.commands) {
            val template = command.optString("template", "")
            if (template.isBlank()) continue
            commands[name] = OcCommandInfo(
                name = name,
                description = command.optStringOrNull("description"),
                agent = command.optStringOrNull("agent"),
                model = command.optStringOrNull("model"),
                subtask = command.optBoolean("subtask", false),
                hints = hints(template),
                source = "command",
                template = { template },
            )
        }

        for (server in mcpServersProvider()) {
            for ((promptName, description) in mcpClient.listPrompts(server)) {
                val commandName = "${server.name}_${promptName}"
                if (commands.containsKey(commandName)) continue
                commands[commandName] = OcCommandInfo(
                    name = commandName,
                    description = description,
                    agent = null,
                    model = null,
                    subtask = false,
                    hints = listOf("\$ARGUMENTS"),
                    source = "mcp",
                    template = {
                        mcpClient.getPrompt(server, promptName, emptyMap()) ?: ""
                    },
                )
            }
        }

        for (skill in skillProvider().list()) {
            if (commands.containsKey(skill.name)) continue
            val dir = java.io.File(skill.location).parentFile
            commands[skill.name] = OcCommandInfo(
                name = skill.name,
                description = skill.description,
                agent = null,
                model = null,
                subtask = false,
                hints = emptyList(),
                source = "skill",
                template = {
                    if (dir == null) {
                        skill.content
                    } else {
                        listOf(
                            skill.content,
                            "",
                            "Base directory for this skill: ${dir.absolutePath}",
                            "Relative paths in this skill (e.g., scripts/, references/) are relative to this base directory.",
                        ).joinToString("\n")
                    }
                },
            )
        }

        return commands.values.toList()
    }

    fun get(name: String): OcCommandInfo? = list().firstOrNull { it.name == name }

    fun interpolate(template: String, args: String): String {
        var result = template.replace("\$ARGUMENTS", args)
        val tokens = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (i in tokens.indices) {
            result = result.replace("\$${i + 1}", tokens[i])
        }
        return result
    }

    private fun org.json.JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}
