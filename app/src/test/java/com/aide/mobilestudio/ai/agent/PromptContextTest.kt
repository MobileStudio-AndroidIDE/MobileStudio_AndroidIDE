package com.aide.mobilestudio.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContextTest {

    // ------------------------------------------------------------ StatusLine

    @Test
    fun `status line includes project, file, model and session`() {
        val text = StatusLine.format(
            project = "/home/user/MyApp",
            openFile = "/home/user/MyApp/app/Main.kt",
            model = "gemini/gemini-2.0-flash",
            sessionId = "ses_123",
            agent = "build",
        )
        assertTrue(text.contains("Project: /home/user/MyApp"))
        assertTrue(text.contains("Open file: /home/user/MyApp/app/Main.kt"))
        assertTrue(text.contains("Model: gemini/gemini-2.0-flash"))
        assertTrue(text.contains("Agent: build"))
        assertTrue(text.contains("Session: ses_123"))
    }

    @Test
    fun `status line omits missing parts and never throws`() {
        assertEquals("Project: none\nModel: none", StatusLine.format(null, null, null, null))
        assertEquals(
            "Project: MyMac\nModel: local/qwen",
            StatusLine.format("MyMac", "", "local/qwen", ""),
        )
    }

    // ------------------------------------------------------ PromptContextBadge

    @Test
    fun `badge is empty without input and file`() {
        assertEquals("", PromptContextBadge.format(null, ""))
    }

    @Test
    fun `badge shows the open file`() {
        val badge = PromptContextBadge.format("app/src/main/MainActivity.kt", "")
        assertTrue(badge.contains("MainActivity.kt"))
        assertTrue(badge.contains("📄"))
    }

    @Test
    fun `badge counts mentions and estimates tokens`() {
        val badge = PromptContextBadge.format(null, "@a/b.kt and @c/d.kt explain this")
        assertTrue(badge.contains("@2 files"))
        // "…explain this" → 30 chars → ceil(30/4) = 8
        assertTrue(badge.contains("~8 tok"))
    }

    @Test
    fun `badge combines all parts with separators`() {
        val badge = PromptContextBadge.format("Editor.kt", "@lib/Util.kt hi")
        assertEquals("📄 Editor.kt · @1 file · ~4 tok", badge)
    }

    @Test
    fun `badge singular file wording`() {
        val badge = PromptContextBadge.format(null, "@a.kt")
        assertTrue(badge.contains("@1 file"))
        assertFalseLike(badge, "@1 files")
    }

    private fun assertFalseLike(text: String, forbidden: String) {
        assertTrue("unexpected '$forbidden' in '$text'", !text.contains(forbidden))
    }
}
