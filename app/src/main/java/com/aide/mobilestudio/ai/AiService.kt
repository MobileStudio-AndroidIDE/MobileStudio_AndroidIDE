package com.aide.mobilestudio.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AiService — HTTP client for the full opencode Serve API
 * (https://opencode.ai/docs/server/).
 *
 * All calls are synchronous — run them off the main thread. Blocking calls use
 * generous read timeouts (a model turn can take minutes).
 *
 * [connectEvents] subscribes to `GET /event` (SSE) which carries every bus
 * event: message/part updates, permission requests, session changes. The
 * permission flow depends on it — the server blocks a running prompt until the
 * client answers `POST /session/:id/permissions/:permissionID`.
 */
class AiService(private val baseUrl: String = OpencodeEnvironment.BASE_URL) {

    companion object {
        private const val TAG = "AiService"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_TIMEOUT_MS = 30_000
        private const val LONG_TIMEOUT_MS = 300_000
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    /** Handle for the SSE subscription; call [close] to unsubscribe. */
    inner class EventStream internal constructor(
        private val connection: HttpURLConnection,
        private val thread: Thread
    ) {
        private val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { connection.disconnect() }
            runCatching { thread.interrupt() }
        }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private fun open(
        method: String,
        path: String,
        body: JSONObject?,
        timeoutMs: Int
    ): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            conn.doOutput = true
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        return conn
    }

    private fun execute(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Pair<Int, String> {
        val conn = open(method, path, body, timeoutMs)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, "HTTP $code $method $path: ${text.take(400)}")
            }
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun jsonOf(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): JSONObject? {
        val text = execute(method, path, body, timeoutMs).second
        if (text.isBlank()) return null
        return runCatching { JSONObject(text) }
            .onFailure { Log.w(TAG, "$method $path → not an object: ${text.take(200)}") }
            .getOrNull()
    }

    private fun arrayOf(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): JSONArray {
        val text = execute(method, path, body, timeoutMs).second
        if (text.isBlank()) return JSONArray()
        return runCatching { JSONArray(text) }.getOrElse { JSONArray() }
    }

    private fun ok(
        method: String,
        path: String,
        body: JSONObject? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Boolean = runCatching { execute(method, path, body, timeoutMs); true }
        .onFailure { Log.w(TAG, "$method $path failed: ${it.message}") }
        .getOrDefault(false)

    private fun query(params: Map<String, String?>): String {
        val parts = params.filterValues { !it.isNullOrBlank() }
            .map { (k, v) -> "$k=" + java.net.URLEncoder.encode(v!!, "UTF-8") }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    // ------------------------------------------------------------------
    // Global
    // ------------------------------------------------------------------

    /** GET /global/health → true when the server answers. */
    fun checkHealth(): Boolean = runCatching {
        val obj = jsonOf("GET", "/global/health") ?: return false
        obj.optBoolean("healthy", false)
    }.getOrDefault(false)

    fun health(): JSONObject? = jsonOf("GET", "/global/health")

    fun version(): String? = health()?.optString("version")?.takeIf { it.isNotEmpty() }

    fun projects(): JSONArray = arrayOf("GET", "/project")
    fun currentProject(): JSONObject? = jsonOf("GET", "/project/current")
    fun path(): String = jsonOf("GET", "/path")?.optString("cwd", "") ?: ""
    fun vcs(): JSONObject? = jsonOf("GET", "/vcs")
    fun dispose(): Boolean = ok("POST", "/instance/dispose")

    // ------------------------------------------------------------------
    // Events (SSE)
    // ------------------------------------------------------------------

    /**
     * Subscribes to `GET /event`. Every parsed event object is handed to
     * [listener] on a background thread. Returns a handle — call `close()`.
     */
    fun connectEvents(
        listener: (JSONObject) -> Unit,
        onError: (String) -> Unit = {}
    ): EventStream {
        val conn = URL(baseUrl.trimEnd('/') + "/event").openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = 0 // long-lived stream
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")

        val thread = Thread {
            runCatching {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val data = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: break
                    when {
                        current.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(current.removePrefix("data:").trimStart())
                        }
                        current.isBlank() -> {
                            if (data.isNotEmpty()) {
                                val payload = data.toString()
                                data.setLength(0)
                                dispatchEvent(payload, listener)
                            }
                        }
                        // "event:", "id:", ":" comments → ignore
                    }
                }
            }.onFailure {
                if (it !is java.io.IOException) {
                    Log.w(TAG, "event stream failed: ${it.message}")
                }
                onError(it.message ?: "event stream closed")
            }
            runCatching { conn.disconnect() }
        }.apply {
            isDaemon = true
            name = "opencode-events"
            start()
        }
        return EventStream(conn, thread)
    }

    private fun dispatchEvent(payload: String, listener: (JSONObject) -> Unit) {
        runCatching {
            val json = JSONObject(payload)
            listener(json)
        }.onFailure { Log.d(TAG, "non-JSON event: ${payload.take(200)}") }
    }

    // ------------------------------------------------------------------
    // Config / providers
    // ------------------------------------------------------------------

    fun config(): JSONObject? = jsonOf("GET", "/config")
    fun patchConfig(patch: JSONObject): JSONObject? = jsonOf("PATCH", "/config", patch)
    fun configProviders(): JSONObject? = jsonOf("GET", "/config/providers")
    fun providers(): JSONObject? = jsonOf("GET", "/provider")
    fun providerAuth(): JSONObject? = jsonOf("GET", "/provider/auth")
    fun setAuth(providerId: String, credentials: JSONObject): Boolean =
        ok("PUT", "/auth/$providerId", credentials)

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    fun sessions(): JSONArray = arrayOf("GET", "/session")
    fun sessionStatus(): JSONObject? = jsonOf("GET", "/session/status")

    fun createSession(title: String? = null, parentID: String? = null): JSONObject? {
        val body = JSONObject()
        if (title != null) body.put("title", title)
        if (parentID != null) body.put("parentID", parentID)
        return jsonOf("POST", "/session", body)
    }

    fun session(id: String): JSONObject? = jsonOf("GET", "/session/$id")
    fun deleteSession(id: String): Boolean = ok("DELETE", "/session/$id")

    fun patchSession(id: String, title: String): JSONObject? =
        jsonOf("PATCH", "/session/$id", JSONObject().put("title", title))

    fun children(id: String): JSONArray = arrayOf("GET", "/session/$id/children")
    fun todos(id: String): JSONArray = arrayOf("GET", "/session/$id/todo")
    fun abortSession(id: String): Boolean = ok("POST", "/session/$id/abort")
    fun forkSession(id: String, messageID: String? = null): JSONObject? {
        val body = JSONObject()
        if (messageID != null) body.put("messageID", messageID)
        return jsonOf("POST", "/session/$id/fork", body)
    }

    fun shareSession(id: String): JSONObject? = jsonOf("POST", "/session/$id/share")
    fun unshareSession(id: String): JSONObject? = jsonOf("DELETE", "/session/$id/share")
    fun sessionDiff(id: String, messageID: String? = null): JSONArray =
        arrayOf("GET", "/session/$id/diff" + query(mapOf("messageID" to messageID)))

    fun summarize(id: String, providerID: String, modelID: String): Boolean =
        ok("POST", "/session/$id/summarize", JSONObject().put("providerID", providerID).put("modelID", modelID))

    fun revert(id: String, messageID: String, partID: String? = null): Boolean {
        val body = JSONObject().put("messageID", messageID)
        if (partID != null) body.put("partID", partID)
        return ok("POST", "/session/$id/revert", body)
    }

    fun unrevert(id: String): Boolean = ok("POST", "/session/$id/unrevert")

    /**
     * Answers a permission request from the event stream.
     * @param response one of `once`/`always`/`reject` (see opencode permissions docs)
     */
    fun respondPermission(
        sessionId: String,
        permissionId: String,
        response: String,
        remember: Boolean = false
    ): Boolean = ok(
        "POST",
        "/session/$sessionId/permissions/$permissionId",
        JSONObject().put("response", response).put("remember", remember)
    )

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    fun messages(sessionId: String, limit: Int? = null): JSONArray =
        arrayOf("GET", "/session/$sessionId/message" + query(mapOf("limit" to limit?.toString())))

    fun message(sessionId: String, messageId: String): JSONObject? =
        jsonOf("GET", "/session/$sessionId/message/$messageId")

    private fun messageBody(
        text: String,
        messageID: String?,
        model: String?,
        agent: String?,
        system: String?,
        tools: JSONObject?
    ): JSONObject {
        val body = JSONObject()
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        body.put("parts", parts)
        if (messageID != null) body.put("messageID", messageID)
        if (model != null) body.put("model", model)
        if (agent != null) body.put("agent", agent)
        if (system != null) body.put("system", system)
        if (tools != null) body.put("tools", tools)
        return body
    }

    /** Blocking send — waits for the assistant turn to finish. */
    fun sendMessage(
        sessionId: String,
        text: String,
        model: String? = null,
        agent: String? = null,
        system: String? = null,
        tools: JSONObject? = null,
        messageID: String? = null
    ): JSONObject? = jsonOf(
        "POST",
        "/session/$sessionId/message",
        messageBody(text, messageID, model, agent, system, tools),
        LONG_TIMEOUT_MS
    )

    /** Fire-and-forget send — stream the result from `GET /event`. */
    fun promptAsync(
        sessionId: String,
        text: String,
        model: String? = null,
        agent: String? = null,
        system: String? = null,
        tools: JSONObject? = null,
        messageID: String? = null
    ): Boolean = ok(
        "POST",
        "/session/$sessionId/prompt_async",
        messageBody(text, messageID, model, agent, system, tools),
        LONG_TIMEOUT_MS
    )

    fun runCommand(
        sessionId: String,
        command: String,
        arguments: List<String> = emptyList(),
        agent: String? = null,
        model: String? = null
    ): JSONObject? {
        val body = JSONObject()
            .put("command", command)
            .put("arguments", JSONArray(arguments))
        if (agent != null) body.put("agent", agent)
        if (model != null) body.put("model", model)
        return jsonOf("POST", "/session/$sessionId/command", body, LONG_TIMEOUT_MS)
    }

    fun runShell(sessionId: String, agent: String, command: String, model: String? = null): JSONObject? {
        val body = JSONObject().put("agent", agent).put("command", command)
        if (model != null) body.put("model", model)
        return jsonOf("POST", "/session/$sessionId/shell", body, LONG_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------
    // Commands / files / search
    // ------------------------------------------------------------------

    fun commands(): JSONArray = arrayOf("GET", "/command")
    fun agents(): JSONArray = arrayOf("GET", "/agent")

    fun find(pattern: String): JSONArray =
        arrayOf("GET", "/find" + query(mapOf("pattern" to pattern)))

    fun findFile(query: String, type: String? = null, directory: String? = null, limit: Int? = null): JSONArray =
        arrayOf(
            "GET", "/find/file" + query(
                mapOf(
                    "query" to query,
                    "type" to type,
                    "directory" to directory,
                    "limit" to limit?.toString()
                )
            )
        )

    fun findSymbol(query: String): JSONArray =
        arrayOf("GET", "/find/symbol" + query(mapOf("query" to query)))

    fun files(path: String? = null): JSONArray =
        arrayOf("GET", "/file" + query(mapOf("path" to path)))

    fun fileContent(path: String): JSONObject? =
        jsonOf("GET", "/file/content" + query(mapOf("path" to path)))

    fun fileStatus(): JSONArray = arrayOf("GET", "/file/status")

    // ------------------------------------------------------------------
    // Tools / LSP / formatters / MCP
    // ------------------------------------------------------------------

    fun toolIds(): String = execute("GET", "/experimental/tool/ids").second
    fun tools(provider: String, model: String): String =
        execute("GET", "/experimental/tool" + query(mapOf("provider" to provider, "model" to model))).second

    fun lsp(): JSONArray = arrayOf("GET", "/lsp")
    fun formatters(): JSONArray = arrayOf("GET", "/formatter")
    fun mcp(): JSONObject? = jsonOf("GET", "/mcp")
    fun addMcp(name: String, config: JSONObject): JSONObject? =
        jsonOf("POST", "/mcp", JSONObject().put("name", name).put("config", config))

    fun log(service: String, level: String, message: String, extra: JSONObject? = null): Boolean {
        val body = JSONObject().put("service", service).put("level", level).put("message", message)
        if (extra != null) body.put("extra", extra)
        return ok("POST", "/log", body)
    }

    // ------------------------------------------------------------------
    // TUI control (drive an attached `opencode` TUI)
    // ------------------------------------------------------------------

    fun tuiAppendPrompt(text: String): Boolean =
        ok("POST", "/tui/append-prompt", JSONObject().put("append", text))

    fun tuiSubmitPrompt(): Boolean = ok("POST", "/tui/submit-prompt", JSONObject())
    fun tuiClearPrompt(): Boolean = ok("POST", "/tui/clear-prompt", JSONObject())
    fun tuiOpenHelp(): Boolean = ok("POST", "/tui/open-help", JSONObject())
    fun tuiOpenSessions(): Boolean = ok("POST", "/tui/open-sessions", JSONObject())
    fun tuiOpenThemes(): Boolean = ok("POST", "/tui/open-themes", JSONObject())
    fun tuiOpenModels(): Boolean = ok("POST", "/tui/open-models", JSONObject())

    fun tuiExecuteCommand(command: String): Boolean =
        ok("POST", "/tui/execute-command", JSONObject().put("command", command))

    fun tuiShowToast(message: String, title: String? = null, variant: String = "info"): Boolean {
        val body = JSONObject().put("message", message).put("variant", variant)
        if (title != null) body.put("title", title)
        return ok("POST", "/tui/show-toast", body)
    }
}
