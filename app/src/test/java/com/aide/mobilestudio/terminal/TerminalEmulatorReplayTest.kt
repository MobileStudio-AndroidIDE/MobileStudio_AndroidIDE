package com.aide.mobilestudio.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalEmulator regression tests.
 *
 *  - `java --version` -> Enter -> output -> `clear` replay: the cursor must
 *    always start on a fresh line so the reported overlap (`sionclear`)
 *    cannot be produced by the emulator itself.
 *  - Chunk-boundary fuzz: the PTY can split anywhere; the final state must
 *    not depend on the split points.
 *  - resize content preservation (the old implementation wiped the screen).
 *  - pendingEscape stale flush.
 */
class TerminalEmulatorReplayTest {

    private fun fresh(cols: Int = 60, rows: Int = 20) = TerminalEmulator(cols, rows)

    private fun rowText(em: TerminalEmulator, row: Int): String =
        em.getLine(row).joinToString("") { it.char.toString() }

    private fun screenText(em: TerminalEmulator): String =
        (0 until em.getRows()).joinToString("|") { rowText(em, it) }

    private fun state(em: TerminalEmulator): String = buildString {
        append("cursor=").append(em.getCursorRow()).append(',').append(em.getCursorCol())
        append(";scrollback=").append(em.getScrollBackSize())
        append(";screen=").append(screenText(em))
    }

    // ------------------------------------------------------------------
    // User repro: java --version then clear must not overlap
    // ------------------------------------------------------------------

    @Test
    fun javaVersionThenClearLandsOnFreshLine() {
        val em = fresh()

        // kernel echo of the typed text
        em.processString("java --version")
        // Enter echo (ONLCR)
        em.processString("\r\n")
        // command output (stderr merged by the PTY)
        em.processString("openjdk version \"17.0.21-internal\" 2026-10-20\r\n")
        em.processString("OpenJDK Runtime Environment (build 17.0.21-internal)\r\n")
        em.processString("OpenJDK 64-Bit Server VM (build 17.0.21-internal, mixed mode)\r\n")

        // the cursor must sit on an empty fresh line
        val afterOutput = rowText(em, em.getCursorRow()).trimEnd()
        assertEquals("", afterOutput)

        // follow-up input lands on that line only
        em.processString("clear")
        val typed = rowText(em, em.getCursorRow()).trimEnd()
        assertEquals("clear", typed)

        // the previous echo stays intact below, with no overlap
        val echoRow = (0 until em.getRows())
            .firstOrNull { rowText(em, it).startsWith("java --version") }
        assertTrue("java --version echo missing", echoRow != null)
        assertEquals("java --version", rowText(em, echoRow!!).trimEnd())
    }

    @Test
    fun splitEnterEchoStillAdvancesTheLine() {
        // Enter echo arriving as \r and \n in separate chunks.
        val em = fresh()
        em.processString("java --versio")
        em.processString("n\r")
        em.processString("\n")
        assertEquals(1, em.getCursorRow())
        assertEquals(0, em.getCursorCol())
    }

    // ------------------------------------------------------------------
    // Chunk-boundary fuzz
    // ------------------------------------------------------------------

    private val replayStream = buildString {
        append("java --version\r\n")
        append("openjdk version \"17.0.21-internal\" 2026-10-20\r\n")
        append(ESC + "[32mOpenJDK Runtime Environment" + ESC + "[0m (build 17.0.21-internal)\r\n")
        append("OpenJDK 64-Bit Server VM (build 17.0.21-internal, mixed mode)\r\n")
        append(ESC + "[H" + ESC + "[2J")
        append("clear")
    }

    @Test
    fun everyChunkBoundaryProducesTheSameState() {
        val whole = fresh()
        whole.processString(replayStream)
        val expected = state(whole)

        for (split in 1 until replayStream.length) {
            val em = fresh()
            em.processString(replayStream.substring(0, split))
            em.processString(replayStream.substring(split))
            assertEquals("state differs when split at $split", expected, state(em))
        }
    }

    // ------------------------------------------------------------------
    // resize: content preservation
    // ------------------------------------------------------------------

    @Test
    fun shrinkKeepsContentMovesTopRowsToScrollbackAndShiftsCursor() {
        val em = fresh(cols = 40, rows = 10)
        em.processString("L0\r\nL1\r\nL2\r\nL3\r\nL4\r\nL5\r\nL6\r\nL7\r\nL8")
        assertEquals(8, em.getCursorRow())
        assertEquals(0, em.getScrollBackSize())

        em.resize(40, 6)

        // the four rows leaving the top are preserved in scrollback
        assertEquals(4, em.getScrollBackSize())
        val sb0 = em.getScrollBackLine(0).joinToString("") { c -> c.char.toString() }.trimEnd()
        assertEquals("L0", sb0)

        // remaining content and cursor survive (L8 was row 8 -> row 4)
        assertEquals(4, em.getCursorRow())
        assertEquals("L8", rowText(em, em.getCursorRow()).trimEnd())
        assertEquals(40, em.getLine(0).size)
    }

    @Test
    fun growPadsBlankRowsOnTopAndKeepsCursorOffset() {
        val em = fresh(cols = 40, rows = 6)
        em.processString("A\r\nB\r\nC\r\nD\r\nE")
        assertEquals(4, em.getCursorRow())

        em.resize(40, 10)

        // shift +4: content stays bottom-anchored, cursor keeps its line
        assertEquals(8, em.getCursorRow())
        assertEquals("E", rowText(em, em.getCursorRow()).trimEnd())
        assertEquals("A", rowText(em, 4).trimEnd())
        assertEquals(0, em.getScrollBackSize())
    }

    @Test
    fun columnChangeClipsAndPadsRows() {
        val em = fresh(cols = 40, rows = 5)
        em.processString("0123456789ABCDEFGHIJ")

        em.resize(8, 5)
        assertEquals(8, em.getLine(0).size)
        assertEquals("01234567", rowText(em, 0).trimEnd())

        em.resize(16, 5)
        assertEquals(16, em.getLine(0).size)
        assertEquals("01234567", rowText(em, 0).trimEnd())
    }

    // ------------------------------------------------------------------
    // pendingEscape
    // ------------------------------------------------------------------

    @Test
    fun splitEscapeSequenceCompletesNormally() {
        val em = fresh()
        em.processString("ab" + ESC + "[3")
        em.processString("8mX")
        // CSI completes normally: only the colour changes, X is printed
        assertEquals("abX", rowText(em, 0).trimEnd())
    }

    @Test
    fun stalledEscapeSequenceIsFlushedAsLiteralText() {
        val em = fresh()
        em.processString("ab" + ESC + "[38")
        // the sender never completed the sequence — the next input must
        // not be swallowed by the escape parser.
        Thread.sleep(250)
        em.processString("clear")
        val text = rowText(em, 0)
        assertTrue("typed text was swallowed by a stalled escape: '$text'", text.contains("clear"))
    }

    companion object {
        /** ESC (0x1B) built at runtime — no raw control byte in this file. */
        private val ESC: String = Char(27).toString()
    }
}
