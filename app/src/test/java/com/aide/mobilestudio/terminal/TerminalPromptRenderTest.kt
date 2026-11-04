package com.aide.mobilestudio.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the dynamic prompt renderer:
 *
 *  - the prompt must show the REAL working directory: "~" when the cwd is
 *    home or under home, otherwise the absolute path (no middle
 *    abbreviation) — "/sdcard/Download >" stays "/sdcard/Download >".
 *  - when the prompt is wider than the terminal it must be truncated at the
 *    FRONT with "..." keeping the tail, and it must NEVER exceed the
 *    available column budget (the grid is monospace: chars == columns).
 */
class TerminalPromptRenderTest {

    private val home = "/data/data/com.aide.mobilestudio/files"

    private fun manager(cwd: String): TerminalPromptManager =
        TerminalPromptManager().apply {
            setHomeDir(home)
            setCwd(cwd)
        }

    /** Not under home — reported example path (76 chars). */
    private val deepAbsPath =
        "/home/limsemin/Projects/MobileStudio/app/src/main/java/com/aide/mobilestudio"

    // ------------------------------------------------------------- real location

    @Test
    fun `absolute path is shown when the cwd is not under home`() {
        val pm = manager("/sdcard/Download")
        assertEquals("/sdcard/Download >", pm.formatPrompt())
        assertEquals("/sdcard/Download >", pm.formatPrompt(80))
    }

    @Test
    fun `deep absolute path is not middle-abbreviated`() {
        val pm = manager(deepAbsPath)
        assertEquals("$deepAbsPath >", pm.formatPrompt())
    }

    @Test
    fun `tilde is shown at home`() {
        val pm = manager(home)
        assertEquals("~ >", pm.formatPrompt())
        assertEquals("~ >", pm.formatPrompt(80))
    }

    @Test
    fun `tilde is shown under home`() {
        val pm = manager("$home/KotlinApp")
        assertEquals("~/KotlinApp >", pm.formatPrompt())
        assertEquals("~/KotlinApp >", pm.formatPrompt(80))
    }

    @Test
    fun `trailing slash is normalized`() {
        val pm = manager("$home/KotlinApp/")
        assertEquals("~/KotlinApp >", pm.formatPrompt())
    }

    // ------------------------------------------------------------- front truncation

    @Test
    fun `wide prompt is truncated at the front keeping the tail`() {
        val pm = manager(deepAbsPath)
        // 3 (ellipsis) + 27 ("/java/com/aide/mobilestudio") + 2 (" >") = 32 columns.
        assertEquals(".../java/com/aide/mobilestudio >", pm.formatPrompt(32))
    }

    @Test
    fun `truncation never exceeds the column budget`() {
        val pm = manager(deepAbsPath)
        val full = pm.formatPrompt()
        assertEquals(78, full.length)
        for (budget in 1..full.length) {
            val prompt = pm.formatPrompt(budget)
            assertTrue(
                "budget=$budget produced a ${prompt.length}-column prompt: '$prompt'",
                prompt.length <= budget,
            )
        }
    }

    @Test
    fun `truncated prompt keeps the ellipsis marker and the suffix`() {
        val pm = manager(deepAbsPath)
        val full = pm.formatPrompt()
        for (budget in 8 until full.length) {
            val prompt = pm.formatPrompt(budget)
            assertTrue("budget=$budget: '$prompt'", prompt.startsWith("..."))
            assertTrue("budget=$budget: '$prompt'", prompt.endsWith(" >"))
            // the tail of the real path must survive the cut
            assertTrue(
                "budget=$budget: tail lost from '$prompt'",
                full.endsWith(prompt.substring(3)),
            )
        }
    }

    @Test
    fun `prompt fits exactly at its natural width`() {
        val pm = manager(deepAbsPath)
        val full = pm.formatPrompt()
        assertEquals(full, pm.formatPrompt(full.length))
        assertEquals(full, pm.formatPrompt(full.length + 10))
    }

    @Test
    fun `degenerate budgets never overflow`() {
        val pm = manager(deepAbsPath)
        assertEquals("", pm.formatPrompt(0))
        assertEquals("", pm.formatPrompt(-3))
        for (budget in 1..5) {
            val prompt = pm.formatPrompt(budget)
            assertTrue(prompt.length <= budget)
        }
    }

    // ------------------------------------------------- abbreviatePath stays available

    @Test
    fun `abbreviatePath keeps its middle-abbreviation behaviour`() {
        val pm = manager("$home/directory-one-is-long/directory-two-long/dir3/dir4/dir5")
        assertEquals(
            "~/.../dir4/dir5",
            pm.abbreviatePath(pm.getCwd()),
        )
    }

    @Test
    fun `prompt itself never uses the middle abbreviation`() {
        val pm = manager("$home/directory-one-is-long/directory-two-long/dir3/dir4/dir5")
        assertEquals(
            "~/directory-one-is-long/directory-two-long/dir3/dir4/dir5 >",
            pm.formatPrompt(),
        )
    }
}
