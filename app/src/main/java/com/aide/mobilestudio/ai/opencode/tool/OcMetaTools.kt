package com.aide.mobilestudio.ai.opencode.tool

import org.json.JSONObject

/**
 * The full documentation of any tool, fetched only when it is needed.
 *
 * A 4K local window cannot hold a schema for every tool on every request: the
 * schemas are fixed overhead that is paid again for each reply, and paying it
 * leaves less room for the conversation. When the engine ships abbreviated
 * definitions (name + argument summary), this tool stays in the budget so the
 * model can ask for the one schema it is about to use and nothing else.
 *
 * The result is the same text the Tools screen shows a human, so what the
 * model is told and what the app explains cannot drift apart.
 */
class ToolHelpTool(private val registry: () -> OcToolRegistry) : OcTool {

    override val name = "tool_help"

    override fun description(ctx: ToolContext): String =
        "Returns the full description and parameter schema of one or more tools. " +
            "Call it before using a tool whose arguments are not listed in this request."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put(
            "properties",
            JSONObject().apply {
                put(
                    "tool",
                    JSONObject().put("type", "string").put(
                        "description",
                        "A tool name, or several separated by commas. Omit to list every tool with a one-line description.",
                    ),
                )
            },
        )
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val reg = registry()
        val requested = args.optString("tool", "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (requested.isEmpty()) {
            val sb = StringBuilder("Available tools (call tool_help with a name for its full parameters):\n")
            for (tool in reg.all().sortedBy { it.name }) {
                sb.append("- ").append(tool.name).append(": ").append(tool.shortDescription(ctx)).append('\n')
            }
            return ToolResult(sb.toString().trim())
        }
        val sb = StringBuilder()
        for (name in requested) {
            val tool = reg.get(name)
            if (tool == null) {
                sb.append("Unknown tool: ").append(name)
                    .append(". Available: ").append(reg.names().sorted().joinToString(", ")).append("\n\n")
                continue
            }
            sb.append(describe(tool, ctx)).append("\n\n")
        }
        return ToolResult(sb.toString().trim())
    }

    companion object {
        /** Shared with the Tools screen; never invents fields a tool does not have. */
        fun describe(tool: OcTool, ctx: ToolContext): String = buildString {
            append("# ").append(tool.name).append('\n')
            append(tool.description(ctx).trim()).append("\n\n")
            append("Parameters (JSON Schema):\n")
            append(tool.parameters().toString(2))
            append("\n\nPermission key: ").append(tool.permissionKey())
        }
    }
}
