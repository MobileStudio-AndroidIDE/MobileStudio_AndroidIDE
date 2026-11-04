package com.aide.mobilestudio.ai.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandRegistryTest {

    private val registry = SlashCommandRegistry()

    // ------------------------------------------------------------ lookup

    @Test
    fun `minimum command set is present`() {
        val names = registry.all().map { it.name }
        for (required in listOf("help", "clear", "new", "sessions", "model", "plan", "undo", "redo", "files", "status")) {
            assertTrue("missing command /$required", required in names)
        }
    }

    @Test
    fun `lookup is case-insensitive`() {
        assertEquals(SlashCommandAction.HELP, registry.find("HELP")?.action)
        assertEquals(SlashCommandAction.UNDO, registry.find("Undo")?.action)
    }

    @Test
    fun `lookup strips leading slash and whitespace`() {
        assertNotNull(registry.find(" /clear "))
        assertNull(registry.find(""))
        assertNull(registry.find("nope"))
    }

    @Test
    fun `every command has a one-line description`() {
        for (command in registry.all()) {
            assertTrue("/${command.name}", command.description.isNotBlank())
            assertFalse("/${command.name}", command.description.contains('\n'))
        }
    }

    // ------------------------------------------------------------ parse

    @Test
    fun `plain text is not a command`() {
        assertEquals(SlashInvocation.NotACommand, registry.parse("hello world"))
        assertEquals(SlashInvocation.NotACommand, registry.parse("  hi "))
    }

    @Test
    fun `bare slash is not a command`() {
        assertEquals(SlashInvocation.NotACommand, registry.parse("/"))
        assertEquals(SlashInvocation.NotACommand, registry.parse("/ "))
    }

    @Test
    fun `known command parses without argument`() {
        val invocation = registry.parse("/help")
        assertTrue(invocation is SlashInvocation.Known)
        invocation as SlashInvocation.Known
        assertEquals(SlashCommandAction.HELP, invocation.command.action)
        assertEquals("", invocation.argument)
    }

    @Test
    fun `known command parses with argument and surrounding spaces`() {
        val invocation = registry.parse("  /status   now  ")
        assertTrue(invocation is SlashInvocation.Known)
        invocation as SlashInvocation.Known
        assertEquals("status", invocation.command.name)
        assertEquals("now", invocation.argument)
    }

    @Test
    fun `unknown command is reported, never crashing`() {
        val invocation = registry.parse("/frobnicate the widget")
        assertTrue(invocation is SlashInvocation.Unknown)
        assertEquals("frobnicate", (invocation as SlashInvocation.Unknown).name)
    }

    @Test
    fun `parse handles leading whitespace and uppercase`() {
        val invocation = registry.parse(" /UNDO ")
        assertTrue(invocation is SlashInvocation.Known)
        assertEquals(SlashCommandAction.UNDO, (invocation as SlashInvocation.Known).command.action)
    }

    // ------------------------------------------------------------ filter

    @Test
    fun `empty query returns all commands in order`() {
        assertEquals(registry.all(), registry.filter(""))
    }

    @Test
    fun `prefix matches rank before substring matches`() {
        val results = registry.filter("s")
        assertTrue(results.first().name.startsWith("s"))
        assertTrue(results.any { it.name == "sessions" })
    }

    @Test
    fun `filter matches description text`() {
        val results = registry.filter("read-only")
        assertTrue(results.any { it.name == "plan" })
    }

    @Test
    fun `filter with no hits is empty`() {
        assertTrue(registry.filter("zzzznope").isEmpty())
    }

    @Test
    fun `filter is case-insensitive`() {
        val results = registry.filter("UN")
        assertEquals(listOf("undo"), results.map { it.name })
    }

    // ------------------------------------------------------------ help

    @Test
    fun `help text lists every command with description`() {
        val help = SlashCommandRegistry.helpText()
        for (command in SlashCommandRegistry.defaultCommands()) {
            assertTrue(help.contains("/${command.name}"))
            assertTrue(help.contains(command.description))
        }
        assertFalse(help.contains('\r'))
    }
}
