package com.aide.mobilestudio.ai.lsp

import com.aide.mobilestudio.ai.validation.LiveDiagnostic
import com.aide.mobilestudio.ai.validation.LiveSeverity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

/**
 * Minimal, dependency-free LSP wire protocol helpers: `Content-Length` framing
 * and conversion of `textDocument/publishDiagnostics` into the project's
 * [LiveDiagnostic] model. Pure JVM code — unit-testable without a language server.
 */
object LspProtocol {

    fun encode(message: String): ByteArray {
        val payload = message.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        return header + payload
    }

    /** Incremental decoder; feed it bytes as they arrive, get complete messages. */
    class Decoder {
        private var buffer: ByteArray = ByteArray(0)

        fun feed(chunk: ByteArray): List<String> {
            buffer = buffer + chunk
            val messages = mutableListOf<String>()
            while (true) {
                val sep = indexOfHeaderEnd(buffer) ?: break
                val header = String(buffer, 0, sep, Charsets.US_ASCII)
                val contentLength = header.lineSequence()
                    .firstNotNullOfOrNull { line ->
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            line.substringAfter(':').trim().toIntOrNull()
                        } else null
                    }
                if (contentLength == null || contentLength < 0) {
                    buffer = ByteArray(0)
                    break
                }
                val bodyStart = sep + 4
                if (buffer.size - bodyStart < contentLength) break
                messages.add(String(buffer, bodyStart, contentLength, Charsets.UTF_8))
                buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
            }
            return messages
        }

        private fun indexOfHeaderEnd(b: ByteArray): Int? {
            for (i in 0..b.size - 4) {
                if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
            }
            return null
        }
    }

    fun initializeRequest(id: Int, rootUri: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        put("params", JSONObject().apply {
            put("processId", JSONObject.NULL)
            put("rootUri", rootUri)
            put("capabilities", JSONObject().apply {
                put("textDocument", JSONObject().apply {
                    put("publishDiagnostics", JSONObject().apply { put("relatedInformation", true) })
                })
            })
        })
    }

    /**
     * Maps the `params` of a `textDocument/publishDiagnostics` notification to
     * diagnostics. LSP lines/characters are 0-based; the project model is 1-based.
     */
    fun parseDiagnostics(params: JSONObject, projectRoot: File? = null): List<LiveDiagnostic> {
        val uri = params.optString("uri", "")
        val file = uriToPath(uri, projectRoot)
        val list = params.optJSONArray("diagnostics") ?: JSONArray()
        val out = mutableListOf<LiveDiagnostic>()
        for (i in 0 until list.length()) {
            val d = list.optJSONObject(i) ?: continue
            val start = d.optJSONObject("range")?.optJSONObject("start")
            val line = (start?.optInt("line", 0) ?: 0) + 1
            val column = (start?.optInt("character", 0) ?: 0) + 1
            out.add(
                LiveDiagnostic(
                    file = file,
                    line = line,
                    column = column,
                    severity = severityOf(d.optInt("severity", 1)),
                    message = d.optString("message", ""),
                    code = d.opt("code")?.toString(),
                    snippet = null,
                    source = "lsp:" + d.optString("source", "unknown"),
                ),
            )
        }
        return out
    }

    fun severityOf(code: Int): LiveSeverity = when (code) {
        2 -> LiveSeverity.WARNING
        3, 4 -> LiveSeverity.INFO
        else -> LiveSeverity.ERROR
    }

    fun uriToPath(uri: String, projectRoot: File?): String {
        val path = runCatching { File(URI(uri)).absolutePath }
            .getOrElse { uri.removePrefix("file://") }
        val root = projectRoot ?: return path
        return runCatching { File(path).relativeTo(root).path }.getOrDefault(path)
    }

    fun pathToUri(file: File): String = file.absoluteFile.toURI().toString()
}
