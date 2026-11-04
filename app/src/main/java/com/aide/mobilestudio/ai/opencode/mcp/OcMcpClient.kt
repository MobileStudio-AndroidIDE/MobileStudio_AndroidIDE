package com.aide.mobilestudio.ai.opencode.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private fun org.json.JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}

class OcMcpClient {

    data class ServerInfo(
        val name: String,
        val type: String,
        val command: List<String> = emptyList(),
        val cwd: String? = null,
        val environment: Map<String, String> = emptyMap(),
        val url: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val enabled: Boolean = true,
        val timeout: Long = 5000L,
    ) {
        companion object {
            fun fromConfig(name: String, config: JSONObject): ServerInfo? {
                if (!config.optBoolean("enabled", true)) return null
                val type = config.optString("type", "")
                return if (type == "local") {
                    val command = mutableListOf<String>()
                    val cmdArr = config.optJSONArray("command")
                    if (cmdArr != null) {
                        for (i in 0 until cmdArr.length()) command.add(cmdArr.optString(i))
                    }
                    if (command.isEmpty()) return null
                    val env = mutableMapOf<String, String>()
                    config.optJSONObject("environment")?.let { envObj ->
                        val keys = envObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            env[key] = envObj.optString(key)
                        }
                    }
                    ServerInfo(
                        name = name,
                        type = "local",
                        command = command,
                        cwd = config.optStringOrNull("cwd"),
                        environment = env,
                        timeout = config.optLong("timeout", 5000L),
                    )
                } else if (type == "remote") {
                    ServerInfo(
                        name = name,
                        type = "remote",
                        url = config.optString("url", ""),
                        headers = config.optJSONObject("headers")?.let { headersObj ->
                            val env = mutableMapOf<String, String>()
                            val keys = headersObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                env[key] = headersObj.optString(key)
                            }
                            env
                        } ?: emptyMap(),
                        timeout = config.optLong("timeout", 5000L),
                    )
                } else {
                    null
                }
            }
        }
    }

    data class McpTool(val name: String, val description: String?, val inputSchema: JSONObject?)

    private val processes = mutableMapOf<String, Process>()
    private val processIo = mutableMapOf<String, Pair<BufferedReader, OutputStreamWriter>>()
    private val nextRequestId = java.util.concurrent.atomic.AtomicInteger(1)

    fun parseServers(configs: Map<String, JSONObject>): List<ServerInfo> {
        return configs.mapNotNull { (name, config) -> ServerInfo.fromConfig(name, config) }
    }

    fun listTools(server: ServerInfo): List<McpTool> {
        val result = request(server, "tools/list", JSONObject()) ?: return emptyList()
        val tools = result.optJSONObject("result")?.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<McpTool>()
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            out.add(
                McpTool(
                    name = tool.optString("name"),
                    description = tool.optStringOrNull("description"),
                    inputSchema = tool.optJSONObject("inputSchema"),
                ),
            )
        }
        return out
    }

    fun callTool(server: ServerInfo, toolName: String, arguments: JSONObject, timeoutMs: Long? = null): ToolCallResult {
        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", arguments)
        }
        val timeout = timeoutMs ?: server.timeout
        val result = request(server, "tools/call", params, timeout) ?: return ToolCallResult(null, "MCP request failed: ${server.name}")
        if (result.has("error")) {
            return ToolCallResult(null, "MCP error: ${result.optJSONObject("error")?.optString("message", "unknown")}")
        }
        val content = result.optJSONObject("result")?.optJSONArray("content") ?: return ToolCallResult("", null)
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.getJSONObject(i)
            when (item.optString("type")) {
                "text" -> sb.append(item.optString("text"))
                "resource" -> {
                    val resource = item.optJSONObject("resource") ?: JSONObject()
                    sb.append("[Resource ${resource.optString("uri")}: ${resource.optStringOrNull("text") ?: "(binary)"}]")
                }
            }
        }
        return ToolCallResult(sb.toString(), null)
    }

    data class ToolCallResult(val output: String?, val error: String?)

    fun listPrompts(server: ServerInfo): List<Pair<String, String?>> {
        val result = request(server, "prompts/list", JSONObject()) ?: return emptyList()
        val prompts = result.optJSONObject("result")?.optJSONArray("prompts") ?: return emptyList()
        val out = mutableListOf<Pair<String, String?>>()
        for (i in 0 until prompts.length()) {
            val prompt = prompts.getJSONObject(i)
            out.add(prompt.optString("name") to prompt.optStringOrNull("description"))
        }
        return out
    }

    fun getPrompt(server: ServerInfo, promptName: String, args: Map<String, String>): String? {
        val params = JSONObject().apply {
            put("name", promptName)
            if (args.isNotEmpty()) put("arguments", JSONObject(args))
        }
        val result = request(server, "prompts/get", params) ?: return null
        val messages = result.optJSONObject("result")?.optJSONArray("messages") ?: return null
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val content = message.optJSONObject("content") ?: continue
            if (content.optString("type") == "text") sb.append(content.optString("text")).append("\n")
        }
        return sb.toString().ifEmpty { null }
    }

    private fun request(server: ServerInfo, method: String, params: JSONObject, timeoutMs: Long = server.timeout): JSONObject? {
        val id = nextRequestId.getAndIncrement()
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params.length() > 0) put("params", params)
        }
        return try {
            if (server.type == "remote") requestRemote(server, body, id, timeoutMs)
            else requestLocal(server, body, id, timeoutMs)
        } catch (e: Exception) {
            Log.w("OcMcpClient", "MCP request failed: $method ${server.name}", e)
            null
        }
    }

    private fun requestRemote(server: ServerInfo, body: JSONObject, id: Int, timeoutMs: Long): JSONObject? {
        val url = server.url ?: return null
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt()
            readTimeout = timeoutMs.toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            for ((key, value) in server.headers) setRequestProperty(key, value)
            sessionIds[server.name]?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val sessionId = conn.getHeaderField("Mcp-Session-Id")
            if (sessionId != null && body.optString("method") == "initialize") {
                sessionIds[server.name] = sessionId
            }
            val contentType = conn.contentType ?: ""
            val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            if (contentType.contains("text/event-stream")) {
                return parseSse(text, id)
            }
            if (text.isBlank()) return null
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private val sessionIds = mutableMapOf<String, String>()

    private fun parseSse(text: String, id: Int): JSONObject? {
        val dataLines = text.split("\n").filter { it.startsWith("data:") }
        for (line in dataLines) {
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
            if (json.optInt("id", -1) == id || json.optJSONObject("result") != null) return json
        }
        return null
    }

    @Synchronized
    private fun requestLocal(server: ServerInfo, body: JSONObject, id: Int, timeoutMs: Long): JSONObject? {
        val process = processes[server.name] ?: run {
            val pb = ProcessBuilder(server.command)
            server.cwd?.let { pb.directory(java.io.File(it)) }
            pb.redirectErrorStream(false)
            val proc = runCatching { pb.start() }.getOrElse { return null }
            processes[server.name] = proc
            processIo[server.name] = Pair(
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)),
                OutputStreamWriter(proc.outputStream, Charsets.UTF_8),
            )
            val initBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 0)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().put("name", "mobilestudio-opencode").put("version", "1.0"))
                })
            }
            val (_, initWriter) = processIo[server.name]!!
            initWriter.write(initBody.toString() + "\n")
            initWriter.flush()
        }
        val (reader, writer) = processIo[server.name]!!
        writer.write(body.toString() + "\n")
        writer.flush()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = reader.ready()
            if (ready) {
                val line = reader.readLine() ?: return null
                if (line.isBlank()) continue
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (json.optInt("id", -1) == id) return json
                if (json.has("result") || json.has("error")) return json
            } else {
                Thread.sleep(20)
            }
        }
        return null
    }

    fun shutdown() {
        for ((_, process) in processes) {
            runCatching {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        processes.clear()
        processIo.clear()
        sessionIds.clear()
    }
}
