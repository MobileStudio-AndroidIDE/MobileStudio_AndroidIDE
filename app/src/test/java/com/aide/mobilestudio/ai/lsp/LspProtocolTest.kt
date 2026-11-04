package com.aide.mobilestudio.ai.lsp

import com.aide.mobilestudio.ai.lang.FixedToolProbe
import com.aide.mobilestudio.ai.validation.LiveSeverity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LspProtocolTest {

    @Test
    fun `encode then decode round trips`() {
        val message = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        val decoded = LspProtocol.Decoder().feed(LspProtocol.encode(message))
        assertEquals(listOf(message), decoded)
    }

    @Test
    fun `decoder handles two messages in one chunk`() {
        val a = """{"id":1}"""
        val b = """{"id":2}"""
        val bytes = LspProtocol.encode(a) + LspProtocol.encode(b)
        assertEquals(listOf(a, b), LspProtocol.Decoder().feed(bytes))
    }

    @Test
    fun `decoder handles a message split across feeds`() {
        val message = """{"jsonrpc":"2.0","method":"x","params":{"a":1}}"""
        val bytes = LspProtocol.encode(message)
        val decoder = LspProtocol.Decoder()
        assertTrue(decoder.feed(bytes.copyOfRange(0, 10)).isEmpty())
        assertTrue(decoder.feed(bytes.copyOfRange(10, 20)).isEmpty())
        assertEquals(listOf(message), decoder.feed(bytes.copyOfRange(20, bytes.size)))
    }

    @Test
    fun `parseDiagnostics maps severity and 1-based location`() {
        val root = File("/tmp/proj")
        val params = JSONObject().apply {
            put("uri", File("/tmp/proj/src/Main.kt").toURI().toString())
            put("diagnostics", JSONArray().put(JSONObject().apply {
                put("range", JSONObject().put("start", JSONObject().put("line", 9).put("character", 4)))
                put("severity", 1)
                put("message", "unresolved reference: foo")
                put("source", "kotlin")
            }).put(JSONObject().apply {
                put("range", JSONObject().put("start", JSONObject().put("line", 0).put("character", 0)))
                put("severity", 2)
                put("message", "unused variable")
            }))
        }
        val diagnostics = LspProtocol.parseDiagnostics(params, root)
        assertEquals(2, diagnostics.size)
        assertEquals("src/Main.kt", diagnostics[0].file)
        assertEquals(10, diagnostics[0].line)
        assertEquals(5, diagnostics[0].column)
        assertEquals(LiveSeverity.ERROR, diagnostics[0].severity)
        assertEquals(LiveSeverity.WARNING, diagnostics[1].severity)
        assertTrue(diagnostics[0].source!!.startsWith("lsp:"))
    }

    @Test
    fun `severity mapping`() {
        assertEquals(LiveSeverity.ERROR, LspProtocol.severityOf(1))
        assertEquals(LiveSeverity.WARNING, LspProtocol.severityOf(2))
        assertEquals(LiveSeverity.INFO, LspProtocol.severityOf(3))
        assertEquals(LiveSeverity.INFO, LspProtocol.severityOf(4))
    }

    @Test
    fun `registry resolves known languages`() {
        assertEquals("kotlin-language-server", LspServerRegistry.forLanguage("kotlin")?.id)
        assertEquals("clangd", LspServerRegistry.forLanguage("cpp")?.id)
        assertNull(LspServerRegistry.forLanguage("cobol"))
    }

    @Test
    fun `registry availability probes the environment`() {
        val probe = FixedToolProbe(setOf("clangd"))
        val map = LspServerRegistry.availability(probe).toMap()
        val clangd = LspServerRegistry.forLanguage("c")!!
        assertTrue(map[clangd] == true)
        val kotlinLsp = LspServerRegistry.forLanguage("kotlin")!!
        assertFalse(map[kotlinLsp] == true)
    }
}
