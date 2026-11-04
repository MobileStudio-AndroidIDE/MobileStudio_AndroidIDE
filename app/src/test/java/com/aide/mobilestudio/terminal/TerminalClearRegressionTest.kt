package com.aide.mobilestudio.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reported input bug:
 *
 *   "clear을 실행하고 ls 치고 스페이스하면 clearls처럼 겹쳐져"
 *
 * Every scenario feeds the emulator exactly what a real PTY echoes, then
 * asserts that the line the cursor sits on holds only the freshly typed text.
 */
class TerminalClearRegressionTest {

    private val ESC = ""

    private fun fresh(cols: Int = 30, rows: Int = 6) = TerminalEmulator(cols, rows)

    private fun rowText(em: TerminalEmulator, row: Int): String =
        em.getLine(row).joinToString("") { it.char.toString() }

    private fun cursorLine(em: TerminalEmulator): String = rowText(em, em.getCursorRow()).trimEnd()

    // ------------------------------------------------------------- clear replay

    @Test
    fun `clear then ls then space renders only the new text`() {
        val em = fresh()
        em.processString("hello\r\n")
        em.processString("clear")
        em.processString("\r\n")
        em.processString(ESC + "[H" + ESC + "[2J")
        em.processString("ls")
        em.processString(" ")
        assertEquals("ls", cursorLine(em))
    }

    @Test
    fun `clear echo and its escape in one PTY chunk`() {
        val em = fresh()
        em.processString("hello\r\n")
        em.processString("clear\r\n" + ESC + "[H" + ESC + "[2J")
        em.processString("ls ")
        assertEquals("ls", cursorLine(em))
    }

    @Test
    fun `clear on a full screen does not resurrect the old command`() {
        val em = fresh()
        for (i in 0 until 10) em.processString("line$i\r\n")
        em.processString("clear\r\n" + ESC + "[H" + ESC + "[2J")
        em.processString("ls ")
        assertEquals("ls", cursorLine(em))
    }

    @Test
    fun `clear escape split across PTY chunks`() {
        val em = fresh()
        em.processString("hello\r\n")
        em.processString("clear\r\n" + ESC)
        em.processString("[H" + ESC)
        em.processString("[2J")
        em.processString("ls ")
        assertEquals("ls", cursorLine(em))
    }

    @Test
    fun `ESC 3J variant`() {
        val em = fresh()
        for (i in 0 until 10) em.processString("line$i\r\n")
        // Real `clear` (ncurses) always homes the cursor first: ESC[H before
        // the erases. ESC[2J/ESC[3J alone never move the cursor in a real
        // terminal, so eraseAll() must not home behind the program's back.
        em.processString("clear\r\n" + ESC + "[H" + ESC + "[3J")
        em.processString("ls ")
        assertEquals("ls", cursorLine(em))
    }

    // ------------------------------------------------- scrollback after a clear

    @Test
    fun `full screen erase drops the scrollback`() {
        val em = fresh()
        for (i in 0 until 10) em.processString("line$i\r\n")
        assertTrue(em.getScrollBackSize() > 0)
        em.processString("clear\r\n" + ESC + "[H" + ESC + "[2J")
        assertEquals(
            "`clear` must leave an empty screen, not a blank screen with the " +
                "previous command sitting right above the prompt",
            0,
            em.getScrollBackSize(),
        )
    }

    @Test
    fun `line erase does not touch the scrollback`() {
        val em = fresh()
        for (i in 0 until 10) em.processString("line$i\r\n")
        val before = em.getScrollBackSize()
        em.processString(ESC + "[2K")
        assertEquals(before, em.getScrollBackSize())
    }

    // ------------------------------------------------- IME delta clamping

    @Test
    fun `backspaces are clamped to what the shell line holds`() {
        // After Enter or clear the shell line is empty: no backspace can be
        // honoured, so a stale IME model must not emit any.
        assertEquals(0, boundedBackspaces(requested = 5, shellLineLength = 0))
        assertEquals(2, boundedBackspaces(requested = 5, shellLineLength = 2))
        assertEquals(5, boundedBackspaces(requested = 5, shellLineLength = 9))
        assertEquals(0, boundedBackspaces(requested = 0, shellLineLength = 0))
        // Defensive: a negative request erases nothing.
        assertEquals(0, boundedBackspaces(requested = -3, shellLineLength = 4))
    }

    // ------------------------------------------------- cursor after the Clear button
    //
    // "지우기 눌려도 커서가 맨앞으로 가면 안됨" — the app's Clear button wipes the
    // screen and then sends Ctrl-U (kill line). Ctrl-U empties the shell's
    // input buffer but keeps the cursor on the SAME row at column 0, so the
    // app-side clear() must do exactly that instead of homing to (0,0).

    @Test
    fun `app clear keeps the cursor row and resets the column`() {
        val em = fresh()
        for (i in 0 until 3) em.processString("line$i\r\n")
        em.processString("ls")
        assertEquals(3, em.getCursorRow())

        em.clear()

        assertEquals(
            "clear() must match Ctrl-U: same row, column 0",
            3,
            em.getCursorRow(),
        )
        assertEquals(0, em.getCursorCol())
    }

    @Test
    fun `clear on the bottom row keeps the cursor at the bottom row`() {
        val em = fresh(cols = 30, rows = 3)
        em.processString("a\r\nb\r\nls")
        assertEquals(2, em.getCursorRow())

        em.clear()

        // row preserved (clamp upper bound), column reset
        assertEquals(2, em.getCursorRow())
        assertEquals(0, em.getCursorCol())

        em.processString("ls")
        assertEquals("ls", cursorLine(em))
    }

    @Test
    fun `echo after app clear lands on the preserved row`() {
        val em = fresh()
        for (i in 0 until 3) em.processString("line$i\r\n")
        em.processString("ls")

        // Clear button: app-side wipe followed by the Ctrl-U kill line.
        em.clear()
        em.processString("ls ")

        assertEquals("ls", cursorLine(em))
        assertEquals(3, em.getCursorRow())
    }

    @Test
    fun `shell clear command still homes the cursor`() {
        val em = fresh()
        for (i in 0 until 3) em.processString("line$i\r\n")
        em.processString("ls")
        assertEquals(3, em.getCursorRow())

        // `clear` emits ESC[H before ESC[2J/ESC[3J — ESC[H homes the cursor.
        em.processString(ESC + "[H" + ESC + "[2J")
        assertEquals(0, em.getCursorRow())
        assertEquals(0, em.getCursorCol())

        em.processString(ESC + "[H" + ESC + "[3J")
        assertEquals(0, em.getCursorRow())
        assertEquals(0, em.getCursorCol())
    }

    @Test
    fun `bare ESC 2J erases the screen without homing the cursor`() {
        val em = fresh()
        for (i in 0 until 3) em.processString("line$i\r\n")
        em.processString("ls")
        assertEquals(3, em.getCursorRow())

        // ESC[2J alone never moves the cursor in a real terminal; programs
        // that want the cursor home send ESC[H first.
        em.processString(ESC + "[2J")
        assertEquals(3, em.getCursorRow())
        assertEquals(2, em.getCursorCol())
        for (row in 0 until em.getRows()) {
            assertEquals("", rowText(em, row).trimEnd())
        }
    }
}
