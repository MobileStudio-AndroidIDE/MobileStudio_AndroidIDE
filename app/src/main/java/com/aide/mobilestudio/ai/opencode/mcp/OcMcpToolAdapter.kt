package com.aide.mobilestudio.ai.opencode.mcp

import com.aide.mobilestudio.ai.opencode.tool.OcTool
import com.aide.mobilestudio.ai.opencode.tool.RiskHint
import com.aide.mobilestudio.ai.opencode.tool.ToolContext
import com.aide.mobilestudio.ai.opencode.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class McpToolAdapter(
    private val server: OcMcpClient.ServerInfo,
    private val tool: OcMcpClient.McpTool,
    private val client: OcMcpClient,
) : OcTool {

    override val name: String = "${server.name}_${tool.name}"

    override fun description(ctx: ToolContext): String =
        tool.description?.let { "[MCP server: ${server.name}] $it" }
            ?: "[MCP server: ${server.name}] Tool '${tool.name}'"

    override fun parameters(): JSONObject = tool.inputSchema ?: JSONObject().put("type", "object")

    override fun permissionKey(): String = "mcp"

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val result = client.callTool(server, tool.name, args, server.timeout * 10)
        val error = result.error
        if (error != null) {
            return@withContext ToolResult("Error: $error", isError = true)
        }
        ToolResult(result.output ?: "")
    }
}
