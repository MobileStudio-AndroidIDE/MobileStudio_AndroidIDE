package com.aide.mobilestudio.ai.lsp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LspClientTest {

    private class FakeTransport : LspTransport {
        private val inbound = ArrayDeque<String>()
        val written = mutableListOf<String>()
        fun enqueue(message: JSONObject) { inbound.addLast(message.toString()) }
        override fun write(message: String) { written.add(message) }
        override fun read(): String? = inbound.removeFirstOrNull()
        override fun close() {}
    }

    private fun notification(uri: String, message: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("method", "textDocument/publishDiagnostics")
        put("params", JSONObject().apply {
            put("uri", uri)
            put(
                "diagnostics",
                JSONArray().put(
                    JSONObject().apply {
                        put("range", JSONObject().put("start", JSONObject().put("line", 2).put("character", 1)))
                        put("severity", 1)
                        put("message", message)
                    },
                ),
            )
        })
    }

    @Test
    fun `initialize correlates response and captures diagnostics notifications`() {
        val transport = FakeTransport()
        val uri = File("/tmp/proj/Foo.kt").toURI().toString()
        transport.enqueue(notification(uri, "boom"))
        transport.enqueue(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("result", JSONObject().put("capabilities", JSONObject()))
        })

        var callbackCount = 0
        val client = LspClient(transport, File("/tmp/proj"))
        client.onDiagnostics = { _, _ -> callbackCount++ }

        assertTrue(client.initialize("file:///tmp/proj"))
        assertEquals(1, client.allDiagnostics().size)
        assertEquals("boom", client.allDiagnostics()[0].message)
        assertEquals(1, callbackCount)
    }

    @Test
    fun `error response throws`() {
        val transport = FakeTransport()
        transport.enqueue(JSONObject().apply {
            put("id", 1)
            put("error", JSONObject().put("message", "server exploded"))
        })
        val client = LspClient(transport)
        try {
            client.request("initialize", JSONObject())
            fail("expected LspException")
        } catch (e: LspClient.LspException) {
            assertTrue(e.message!!.contains("server exploded"))
        }
    }

    @Test
    fun `didOpen writes a framed notification`() {
        val transport = FakeTransport()
        val client = LspClient(transport)
        client.didOpen(File("/tmp/proj/A.kt"), "kotlin", "fun main() {}")
        val decoded = JSONObject(transport.written.last())
        assertEquals("textDocument/didOpen", decoded.optString("method"))
        val td = decoded.optJSONObject("params").optJSONObject("textDocument")
        assertEquals("kotlin", td.optString("languageId"))
        assertTrue(td.optString("uri").endsWith("A.kt"))
    }

    @Test
    fun `request returns null at end of stream`() {
        val client = LspClient(FakeTransport())
        assertEquals(null, client.request("initialize", JSONObject()))
    }
}
