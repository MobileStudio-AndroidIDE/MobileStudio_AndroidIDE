package com.aide.mobilestudio.ai.lsp

import com.aide.mobilestudio.ai.validation.LiveDiagnostic
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Bidirectional framed-message transport. Abstract so tests can fake a server. */
interface LspTransport {
    fun write(message: String)
    /** Blocking read of the next complete message, or null at end of stream. */
    fun read(): String?
    fun close()
}

/** Framed transport over a pair of streams (process stdin/stdout). */
class StreamLspTransport(private val input: InputStream, private val output: OutputStream) : LspTransport {
    private val decoder = LspProtocol.Decoder()
    private val pending = ArrayDeque<String>()
    private val buffer = ByteArray(4096)

    override fun write(message: String) {
        output.write(LspProtocol.encode(message))
        output.flush()
    }

    override fun read(): String? {
        while (pending.isEmpty()) {
            val n = input.read(buffer)
            if (n < 0) return null
            pending.addAll(decoder.feed(buffer.copyOf(n)))
        }
        return pending.removeFirst()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/**
 * Minimal LSP client: correlates requests/responses by id, handles
 * `textDocument/publishDiagnostics` notifications, and provides the small
 * surface the IDE needs (initialize / didOpen / shutdown).
 */
class LspClient(
    private val transport: LspTransport,
    private val projectRoot: File? = null,
) {
    private val nextId = AtomicInteger(1)
    private val diagnosticsByUri = LinkedHashMap<String, List<LiveDiagnostic>>()

    /** Notified whenever a server publishes diagnostics. */
    var onDiagnostics: ((uri: String, diagnostics: List<LiveDiagnostic>) -> Unit)? = null

    fun request(method: String, params: JSONObject?): JSONObject? {
        val id = nextId.getAndIncrement()
        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        transport.write(message.toString())
        while (true) {
            val raw = transport.read() ?: return null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val hasId = json.has("id")
            val hasMethod = json.has("method")
            if (hasMethod && !hasId) {
                handleNotification(json)
                continue
            }
            if (hasId && json.optInt("id", Int.MIN_VALUE) == id) {
                if (json.has("error")) {
                    val err = json.optJSONObject("error")?.optString("message") ?: "unknown error"
                    throw LspException(err)
                }
                return json.optJSONObject("result") ?: JSONObject()
            }
            // Response to a previous id or a server->client request: ignore.
        }
    }

    fun notify(method: String, params: JSONObject?) {
        val message = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        transport.write(message.toString())
    }

    fun initialize(rootUri: String = projectRoot?.absoluteFile?.toURI()?.toString() ?: ""): Boolean {
        val params = JSONObject().apply {
            put("processId", JSONObject.NULL)
            put("rootUri", rootUri)
            put("capabilities", JSONObject().apply {
                put("textDocument", JSONObject().apply {
                    put("publishDiagnostics", JSONObject().apply { put("relatedInformation", true) })
                })
            })
        }
        request("initialize", params) ?: return false
        notify("initialized", JSONObject())
        return true
    }

    fun didOpen(file: File, languageId: String, text: String) {
        notify(
            "textDocument/didOpen",
            JSONObject().put(
                "textDocument",
                JSONObject().apply {
                    put("uri", LspProtocol.pathToUri(file))
                    put("languageId", languageId)
                    put("version", 1)
                    put("text", text)
                },
            ),
        )
    }

    fun didClose(file: File) {
        notify("textDocument/didClose", JSONObject().put("textDocument", JSONObject().put("uri", LspProtocol.pathToUri(file))))
    }

    fun shutdown() {
        runCatching { request("shutdown", JSONObject()) }
        runCatching { notify("exit", null) }
        runCatching { transport.close() }
    }

    fun allDiagnostics(): List<LiveDiagnostic> = diagnosticsByUri.values.flatten()

    fun diagnosticsFor(uri: String): List<LiveDiagnostic> = diagnosticsByUri[uri].orEmpty()

    private fun handleNotification(json: JSONObject) {
        if (json.optString("method") != "textDocument/publishDiagnostics") return
        val params = json.optJSONObject("params") ?: return
        val uri = params.optString("uri")
        val parsed = LspProtocol.parseDiagnostics(params, projectRoot)
        if (uri.isBlank()) return
        diagnosticsByUri[uri] = parsed
        onDiagnostics?.invoke(uri, parsed)
    }

    class LspException(message: String) : Exception(message)
}

/**
 * Best-effort launcher for a language server process. On Android, exec of
 * non-app binaries is often blocked, so failures are surfaced rather than
 * hidden. Returns null and a reason when the server cannot be started.
 */
class LspServerProcess(private val spec: LspServerSpec, private val workdir: File) {
    private var process: Process? = null
    var client: LspClient? = null
        private set

    fun start(projectRoot: File? = null): Result<LspClient> = runCatching {
        val pb = ProcessBuilder(listOf(spec.command) + spec.args)
        pb.directory(workdir)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        process = proc
        val transport = StreamLspTransport(proc.inputStream, proc.outputStream)
        val lsp = LspClient(transport, projectRoot)
        client = lsp
        lsp
    }

    fun stop() {
        runCatching { client?.shutdown() }
        runCatching {
            process?.destroy()
            if (process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) == false) process?.destroyForcibly()
        }
        process = null
        client = null
    }

    fun isAlive(): Boolean = process?.isAlive == true
}
