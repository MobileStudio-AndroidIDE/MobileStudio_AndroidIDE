package com.aide.mobilestudio.terminal

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * VT100/xterm terminal emulator with screen buffer, cursor, and ANSI color support.
 * Handles escape sequences for proper terminal behavior.
 */
class TerminalEmulator(
    private var cols: Int = 80,
    private var rows: Int = 24
) {
    data class Cell(
        val char: Char = ' ',
        var fg: Int = DEFAULT_FG,
        var bg: Int = DEFAULT_BG,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var reverse: Boolean = false
    ) {
        val effectiveFg: Int get() = if (reverse) bg else fg
        val effectiveBg: Int get() = if (reverse) fg else bg
    }

    private val lines = mutableListOf<MutableList<Cell>>()
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var currentFg = DEFAULT_FG
    private var currentBg = DEFAULT_BG
    private var currentBold = false
    private var currentUnderline = false
    private var currentReverse = false
    private var insertMode = false
    private var originMode = false
    private var bracketedPaste = false
    private var wraparound = true
    private var autoWrap = true
    private var wrapPending = false
    private var scrollBackBuffer = ArrayDeque<MutableList<Cell>>()
    private val maxScrollBack = 5000

    // Callback for screen updates
    var onScreenUpdate: (() -> Unit)? = null
    var onTitleChange: ((String) -> Unit)? = null
    var onBell: (() -> Unit)? = null

    init {
        for (r in 0 until rows) {
            lines.add(newRow())
        }
    }

    private fun newRow(): MutableList<Cell> {
        return MutableList(cols) { Cell() }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return

        val oldLines = lines.toList()
        val oldRows = rows
        val shift = newRows - oldRows

        val kept = ArrayList<MutableList<Cell>>(newRows)
        if (shift >= 0) {
            // Grow: blank rows go on top so the content and the cursor keep
            // their distance from the bottom (the prompt stays visible).
            repeat(shift) { kept.add(newRowWith(newCols)) }
            for (row in oldLines) kept.add(resizeRow(row, newCols))
        } else {
            // Shrink: rows leaving the top of the screen are newer than
            // anything already in scrollback — append them there so the
            // history survives the resize.
            val drop = -shift
            for (i in 0 until drop) {
                scrollBackBuffer.add(oldLines[i])
            }
            while (scrollBackBuffer.size > maxScrollBack) {
                scrollBackBuffer.removeAt(0)
            }
            for (i in drop until oldRows) {
                kept.add(resizeRow(oldLines[i], newCols))
            }
        }

        lines.clear()
        lines.addAll(kept)

        cols = newCols
        rows = newRows
        cursorRow = (cursorRow + shift).coerceIn(0, newRows - 1)
        savedCursorRow = (savedCursorRow + shift).coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newCols - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, newCols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
        onScreenUpdate?.invoke()
    }

    private fun newRowWith(width: Int): MutableList<Cell> =
        MutableList(width) { Cell() }

    private fun resizeRow(src: List<Cell>, newCols: Int): MutableList<Cell> {
        val row = ArrayList<Cell>(newCols)
        val n = minOf(src.size, newCols)
        for (i in 0 until n) row.add(src[i])
        while (row.size < newCols) row.add(Cell())
        return row
    }

    // Incomplete escape sequence carried over to the next chunk.  The PTY
    // can split anywhere, so an ESC at the end of one read may complete in
    // the next one; without this buffer the remainder would be printed as
    // plain text.
    private val pendingEscape = StringBuilder()
    private var pendingEscapeAtMs = 0L

    fun processString(text: String) {
        var s = text
        if (pendingEscape.isNotEmpty()) {
            if (
                System.currentTimeMillis() - pendingEscapeAtMs >
                PENDING_ESCAPE_FLUSH_MS
            ) {
                // The sequence stalled (program died mid-escape, or the
                // tail was never a real sequence). Render the stranded
                // bytes literally so they cannot swallow the user's next
                // keystrokes; the bare ESC itself is invisible, drop it.
                s = pendingEscape.substring(1) + text
            } else {
                s = pendingEscape.toString() + text
            }
            pendingEscape.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\u001B') {
                val end = scanEscapeEnd(s, i)
                if (end < 0) {
                    pendingEscape.append(s, i, s.length)
                    pendingEscapeAtMs = System.currentTimeMillis()
                    break
                }
                i = processEscape(s, i + 1)
                continue
            } else if (c == '\r') {
                cursorCol = 0
                wrapPending = false
            } else if (c == '\n') {
                lineFeed()
            } else if (c == '\t') {
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = nextTab.coerceAtMost(cols - 1)
                wrapPending = false
            } else if (c == '\b') {
                if (cursorCol > 0) cursorCol--
                wrapPending = false
            } else if (c == '\u0007') {
                onBell?.invoke()
            } else {
                if (wrapPending) {
                    doWrap()
                }
                putChar(c)
            }
            i++
        }
        onScreenUpdate?.invoke()
    }

    private fun processEscape(text: String, start: Int): Int {
        if (start >= text.length) return start
        var i = start
        val c = text[i]

        when (c) {
            '[' -> return processCSI(text, i + 1)
            ']' -> return processOSC(text, i + 1)
            '(' -> return i + 2 // Charset designation, skip
            ')' -> return i + 2
            'M' -> { reverseIndex(); return i + 1 }
            'D' -> { lineFeed(); return i + 1 }
            'E' -> { nextLine(); return i + 1 }
            '7' -> { saveCursor(); return i + 1 }
            '8' -> { restoreCursor(); return i + 1 }
            'c' -> { reset(); return i + 1 }
            'H' -> { setTabStop(); return i + 1 }
            '>' -> { return i + 1 } // Keypad mode
            '=' -> { return i + 1 } // Application keypad
            else -> return i + 1
        }
    }

    /**
     * Length check for an escape sequence before it is executed.
     *
     * @param start index of the ESC character
     * @return index just past the sequence, or -1 when the sequence is cut
     *         off at the end of [text] and has to wait for the next chunk.
     */
    private fun scanEscapeEnd(text: String, start: Int): Int {
        if (start + 1 >= text.length) return -1
        val c = text[start + 1]
        return when (c) {
            '[' -> {
                var i = start + 2
                while (i < text.length && (
                        text[i] in '0'..'9' || text[i] == ';' || text[i] == '?' ||
                            text[i] == '>' || text[i] == '!' || text[i] == '=' ||
                            text[i] == '<' || text[i] in ' '..'/'
                        )
                ) {
                    i++
                }
                if (i >= text.length) -1 else i + 1
            }

            ']' -> {
                var i = start + 2
                var terminated = false
                while (i < text.length && text[i] != '\u0007') {
                    if (i > start + 2 && text[i] == '\\' && text[i - 1] == '\u001B') {
                        terminated = true
                        break
                    }
                    i++
                }
                when {
                    i < text.length && text[i] == '\u0007' -> i + 1
                    terminated -> i + 1
                    else -> -1
                }
            }

            // DCS / SOS / PM / APC are terminated by ST (ESC \)
            'P', 'X', '^', '_' -> {
                var i = start + 2
                while (i + 1 < text.length && !(text[i] == '\u001B' && text[i + 1] == '\\')) {
                    i++
                }
                if (i + 1 < text.length) i + 2 else -1
            }

            else -> start + 2
        }
    }

    private fun processCSI(text: String, start: Int): Int {
        var i = start
        if (i >= text.length) return i

        // Collect parameters
        val params = StringBuilder()
        var intermediate = '\u0000'
        while (i < text.length) {
            val c = text[i]
            when {
                c in '0'..'9' || c == '?' || c == '>' || c == '!' || c == '=' || c == '<' -> {
                    params.append(c)
                    i++
                }
                c == ';' -> {
                    params.append(c)
                    i++
                }
                c in ' '..'/' -> {
                    intermediate = c
                    i++
                }
                else -> break
            }
        }
        if (i >= text.length) return i
        val finalChar = text[i]
        i++

        val paramStr = params.toString()
        val paramList = if (paramStr.isEmpty()) intArrayOf(0)
        else paramStr.split(';').map { it.toIntOrNull() ?: 0 }.toIntArray()

        executeCSI(paramList, intermediate, finalChar)
        return i
    }

    private fun executeCSI(params: IntArray, intermediate: Char, final: Char): Int {
        when (final) {
            'A' -> { // Cursor Up
                val n = params.getOrElse(0) { 1 }
                cursorRow = (cursorRow - n).coerceAtLeast(scrollTop)
                wrapPending = false
            }
            'B' -> { // Cursor Down
                val n = params.getOrElse(0) { 1 }
                cursorRow = (cursorRow + n).coerceAtMost(scrollBottom)
                wrapPending = false
            }
            'C' -> { // Cursor Forward
                val n = params.getOrElse(0) { 1 }
                cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
                wrapPending = false
            }
            'D' -> { // Cursor Backward
                val n = params.getOrElse(0) { 1 }
                cursorCol = (cursorCol - n).coerceAtLeast(0)
                wrapPending = false
            }
            'E' -> { // Cursor Next Line
                val n = params.getOrElse(0) { 1 }
                cursorRow = (cursorRow + n).coerceAtMost(scrollBottom)
                cursorCol = 0
                wrapPending = false
            }
            'F' -> { // Cursor Previous Line
                val n = params.getOrElse(0) { 1 }
                cursorRow = (cursorRow - n).coerceAtLeast(scrollTop)
                cursorCol = 0
                wrapPending = false
            }
            'G' -> { // Cursor Horizontal Absolute
                val n = params.getOrElse(0) { 1 }
                cursorCol = (n - 1).coerceIn(0, cols - 1)
                wrapPending = false
            }
            'H', 'f' -> { // Cursor Position
                val row = params.getOrElse(0) { 1 } - 1
                val col = params.getOrElse(1) { 1 } - 1
                cursorRow = row.coerceIn(scrollTop, scrollBottom)
                cursorCol = col.coerceIn(0, cols - 1)
                wrapPending = false
            }
            'J' -> { // Erase in Display
                val mode = params.getOrElse(0) { 0 }
                when (mode) {
                    0 -> eraseFromCursorToEnd()
                    1 -> eraseFromStartToCursor()
                    // Full-screen erases (what `clear` emits) also drop the
                    // scrollback. Desktop terminals keep it, but on a phone the
                    // leftover command line sitting directly above the freshly
                    // drawn prompt reads as merged text ("clearls") -- the exact
                    // report this fixes. Mode 2 and 3 both mean "empty screen".
                    2 -> { eraseAll(); scrollBackBuffer.clear() }
                    3 -> { eraseAll(); scrollBackBuffer.clear() }
                }
            }
            'K' -> { // Erase in Line
                val mode = params.getOrElse(0) { 0 }
                when (mode) {
                    0 -> eraseFromCursorToEOL()
                    1 -> eraseFromBOLToCursor()
                    2 -> eraseLine()
                }
            }
            'L' -> { // Insert Lines
                val n = params.getOrElse(0) { 1 }
                insertLines(n)
            }
            'M' -> { // Delete Lines
                val n = params.getOrElse(0) { 1 }
                deleteLines(n)
            }
            'P' -> { // Delete Characters
                val n = params.getOrElse(0) { 1 }
                deleteChars(n)
            }
            '@' -> { // Insert Characters
                val n = params.getOrElse(0) { 1 }
                insertChars(n)
            }
            'S' -> { // Scroll Up
                val n = params.getOrElse(0) { 1 }
                repeat(n) { scrollUp() }
            }
            'T' -> { // Scroll Down
                val n = params.getOrElse(0) { 1 }
                repeat(n) { scrollDown() }
            }
            'X' -> { // Erase Characters
                val n = params.getOrElse(0) { 1 }
                eraseChars(n)
            }
            'd' -> { // Line Position Absolute
                val row = params.getOrElse(0) { 1 } - 1
                cursorRow = row.coerceIn(0, rows - 1)
                wrapPending = false
            }
            'm' -> { // SGR - Select Graphic Rendition
                processSGR(params)
            }
            'r' -> { // Set Scrolling Region
                val top = (params.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                val bottom = (params.getOrElse(1) { rows } - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = 0
                    cursorCol = 0
                }
            }
            'n' -> { // Device Status Report - handled externally
            }
            'h' -> { // Set Mode
                if (intermediate == '?') {
                    for (p in params) {
                        when (p) {
                            1 -> {} // Application cursor keys
                            6 -> { originMode = true; cursorRow = scrollTop }
                            7 -> { wraparound = true }
                            25 -> {} // Show cursor
                            1049 -> { saveCursor(); eraseAll() } // Alt screen buffer
                            2004 -> { bracketedPaste = true } // Bracketed paste mode
                        }
                    }
                }
            }
            'l' -> { // Reset Mode
                if (intermediate == '?') {
                    for (p in params) {
                        when (p) {
                            1 -> {} // Normal cursor keys
                            6 -> { originMode = false }
                            7 -> { wraparound = false }
                            25 -> {} // Hide cursor
                            1049 -> { eraseAll(); restoreCursor() } // Normal screen buffer
                            2004 -> { bracketedPaste = false } // Disable bracketed paste
                        }
                    }
                }
            }
            'c' -> {} // Device Attributes - ignore
        }
        return 0
    }

    private fun processOSC(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != '\u0007') {
            // ST (ESC \) also terminates the string
            if (text[i] == '\\' && i > start && text[i - 1] == '\u001B') return i + 1
            i++
        }
        if (i < text.length && text[i] == '\u0007') return i + 1
        return i
    }

    private fun processSGR(params: IntArray) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> resetAttributes()
                1 -> currentBold = true
                2 -> {} // Dim
                3 -> {} // Italic
                4 -> currentUnderline = true
                5, 6 -> {} // Blink
                7 -> currentReverse = true
                8 -> {} // Hidden
                9 -> {} // Strikethrough
                22 -> currentBold = false
                23 -> {} // Not italic
                24 -> currentUnderline = false
                25 -> {} // Not blink
                27 -> currentReverse = false
                28 -> {} // Not hidden
                29 -> {} // Not strikethrough
                in 30..37 -> currentFg = ansiToColor(params[i] - 30)
                38 -> {
                    if (i + 1 < params.size && params[i + 1] == 5 && i + 2 < params.size) {
                        currentFg = xterm256ToColor(params[i + 2])
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2 && i + 4 < params.size) {
                        currentFg = Color.rgb(params[i + 2], params[i + 3], params[i + 4])
                        i += 4
                    }
                }
                39 -> currentFg = DEFAULT_FG
                in 40..47 -> currentBg = ansiToColor(params[i] - 40)
                48 -> {
                    if (i + 1 < params.size && params[i + 1] == 5 && i + 2 < params.size) {
                        currentBg = xterm256ToColor(params[i + 2])
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2 && i + 4 < params.size) {
                        currentBg = Color.rgb(params[i + 2], params[i + 3], params[i + 4])
                        i += 4
                    }
                }
                49 -> currentBg = DEFAULT_BG
                in 90..97 -> currentFg = brightAnsiToColor(params[i] - 90)
                in 100..107 -> currentBg = brightAnsiToColor(params[i] - 100)
            }
            i++
        }
    }

    private fun resetAttributes() {
        currentFg = DEFAULT_FG
        currentBg = DEFAULT_BG
        currentBold = false
        currentUnderline = false
        currentReverse = false
    }

    private fun putChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = cols - 1
        }
        val row = lines[cursorRow]
        if (insertMode) {
            row.add(cursorCol, Cell(c, currentFg, currentBg, currentBold, currentUnderline, currentReverse))
            if (row.size > cols) row.removeAt(cols)
        } else {
            row[cursorCol] = Cell(c, currentFg, currentBg, currentBold, currentUnderline, currentReverse)
        }
        cursorCol++
        if (cursorCol >= cols) {
            autoWrap = true
            wrapPending = true
            cursorCol = cols - 1
        }
    }

    private fun doWrap() {
        cursorCol = 0
        lineFeed()
        wrapPending = false
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp()
        } else {
            cursorRow++
        }
    }

    private fun nextLine() {
        lineFeed()
        cursorCol = 0
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown()
        } else {
            cursorRow--
        }
    }

    private fun scrollUp() {
        val removed = lines.removeAt(scrollTop)
        scrollBackBuffer.add(removed)
        if (scrollBackBuffer.size > maxScrollBack) {
            scrollBackBuffer.removeAt(0)
        }
        lines.add(scrollTop + (scrollBottom - scrollTop), newRow())
    }

    private fun scrollDown() {
        if (cursorRow < scrollBottom) {
            lines.removeAt(scrollBottom)
            lines.add(cursorRow, newRow())
        }
    }

    private fun insertLines(n: Int) {
        repeat(n) {
            if (cursorRow in scrollTop..scrollBottom) {
                lines.removeAt(scrollBottom)
                lines.add(cursorRow, newRow())
            }
        }
    }

    private fun deleteLines(n: Int) {
        repeat(n) {
            if (cursorRow in scrollTop..scrollBottom) {
                lines.removeAt(cursorRow)
                lines.add(scrollBottom, newRow())
            }
        }
    }

    private fun insertChars(n: Int) {
        val row = lines[cursorRow]
        repeat(n) {
            if (cursorCol < cols) {
                row.add(cursorCol, Cell())
                if (row.size > cols) row.removeAt(cols)
            }
        }
    }

    private fun deleteChars(n: Int) {
        val row = lines[cursorRow]
        repeat(n) {
            if (cursorCol < row.size) {
                row.removeAt(cursorCol)
                row.add(Cell())
            }
        }
    }

    private fun eraseChars(n: Int) {
        val row = lines[cursorRow]
        for (col in cursorCol until (cursorCol + n).coerceAtMost(cols)) {
            if (col < row.size) row[col] = Cell()
        }
    }

    private fun eraseFromCursorToEnd() {
        eraseFromCursorToEOL()
        for (row in (cursorRow + 1) until rows) {
            lines[row] = newRow()
        }
    }

    private fun eraseFromStartToCursor() {
        for (row in 0 until cursorRow) {
            lines[row] = newRow()
        }
        eraseFromBOLToCursor()
    }

    private fun eraseAll() {
        for (row in 0 until rows) {
            lines[row] = newRow()
        }
        // Cursor position is left alone on purpose: ESC[2J/ESC[3J do not move
        // the cursor in a real terminal (the shell's `clear` homes it with a
        // preceding ESC[H), and the app-side clear() wants the cursor to
        // stay on the shell's line. Callers decide whether to home.
    }

    private fun eraseFromCursorToEOL() {
        val row = lines[cursorRow]
        for (col in cursorCol until cols) {
            row[col] = Cell()
        }
    }

    private fun eraseFromBOLToCursor() {
        val row = lines[cursorRow]
        for (col in 0..cursorCol.coerceAtMost(cols - 1)) {
            if (col < row.size) row[col] = Cell()
        }
    }

    private fun eraseLine() {
        lines[cursorRow] = newRow()
    }

    private fun setTabStop() {
        // Tab stops at 8-column intervals (simplified)
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
    }

    private fun reset() {
        lines.clear()
        for (r in 0 until rows) {
            lines.add(newRow())
        }
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        resetAttributes()
        insertMode = false
        originMode = false
        wraparound = true
    }

    fun clear() {
        eraseAll()
        /*
         * Match the shell exactly: this runs right before a Ctrl-U kill line,
         * which empties the input buffer but keeps the cursor on the SAME row
         * at column 0. Homing to (0,0) here would draw the cursor and the
         * dynamic prompt at the top-left while the shell is still further
         * down ("지우기 눌려도 커서가 맨앞으로 가면 안됨").
         */
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = 0
        scrollBackBuffer.clear()
        onScreenUpdate?.invoke()
    }

    fun getLine(row: Int): List<Cell> {
        return if (row in 0 until rows) lines[row] else newRow()
    }

    fun getScrollBackLine(index: Int): List<Cell> {
        return if (index in scrollBackBuffer.indices) scrollBackBuffer[index] else newRow()
    }

    fun getScrollBackSize(): Int = scrollBackBuffer.size
    fun isBracketedPaste(): Boolean = bracketedPaste

    fun getCursorRow(): Int = cursorRow
    fun getCursorCol(): Int = cursorCol
    fun getCols(): Int = cols
    fun getRows(): Int = rows

    // Color mapping
    private fun ansiToColor(index: Int): Int = when (index) {
        0 -> Color.rgb(0, 0, 0)         // Black
        1 -> Color.rgb(170, 0, 0)       // Red
        2 -> Color.rgb(0, 170, 0)       // Green
        3 -> Color.rgb(170, 85, 0)      // Yellow
        4 -> Color.rgb(0, 0, 170)       // Blue
        5 -> Color.rgb(170, 0, 170)     // Magenta
        6 -> Color.rgb(0, 170, 170)     // Cyan
        7 -> Color.rgb(170, 170, 170)   // White
        else -> DEFAULT_FG
    }

    private fun brightAnsiToColor(index: Int): Int = when (index) {
        0 -> Color.rgb(85, 85, 85)       // Bright Black (Gray)
        1 -> Color.rgb(255, 85, 85)      // Bright Red
        2 -> Color.rgb(85, 255, 85)      // Bright Green
        3 -> Color.rgb(255, 255, 85)     // Bright Yellow
        4 -> Color.rgb(85, 85, 255)      // Bright Blue
        5 -> Color.rgb(255, 85, 255)     // Bright Magenta
        6 -> Color.rgb(85, 255, 255)     // Bright Cyan
        7 -> Color.rgb(255, 255, 255)    // Bright White
        else -> DEFAULT_FG
    }

    private fun xterm256ToColor(index: Int): Int {
        if (index < 0 || index > 255) return DEFAULT_FG
        if (index < 16) {
            return when (index) {
                0 -> Color.rgb(0, 0, 0)
                1 -> Color.rgb(128, 0, 0)
                2 -> Color.rgb(0, 128, 0)
                3 -> Color.rgb(128, 128, 0)
                4 -> Color.rgb(0, 0, 128)
                5 -> Color.rgb(128, 0, 128)
                6 -> Color.rgb(0, 128, 128)
                7 -> Color.rgb(192, 192, 192)
                8 -> Color.rgb(128, 128, 128)
                9 -> Color.rgb(255, 0, 0)
                10 -> Color.rgb(0, 255, 0)
                11 -> Color.rgb(255, 255, 0)
                12 -> Color.rgb(0, 0, 255)
                13 -> Color.rgb(255, 0, 255)
                14 -> Color.rgb(0, 255, 255)
                15 -> Color.rgb(255, 255, 255)
                else -> DEFAULT_FG
            }
        }
        if (index < 232) {
            val idx = index - 16
            val r = idx / 36
            val g = (idx % 36) / 6
            val b = idx % 6
            return Color.rgb(
                if (r == 0) 0 else 55 + r * 40,
                if (g == 0) 0 else 55 + g * 40,
                if (b == 0) 0 else 55 + b * 40
            )
        }
        val gray = 8 + (index - 232) * 10
        return Color.rgb(gray, gray, gray)
    }

    companion object {
        val DEFAULT_FG: Int get() = com.aide.mobilestudio.StudioUi.palette.text
        val DEFAULT_BG: Int get() = if (com.aide.mobilestudio.StudioUi.palette.isDark) Color.rgb(13, 17, 23) else Color.rgb(250, 250, 250)

        /**
         * A partial escape sequence older than this is rendered as literal
         * text — real PTY chunks arrive within milliseconds, so anything
         * this old was never completed by its sender.
         */
        private const val PENDING_ESCAPE_FLUSH_MS = 200L

        // Paint presets for common rendering
        fun createPaint(typeface: Typeface, fontSize: Float): Paint {
            return Paint().apply {
                this.typeface = typeface
                textSize = fontSize
                isAntiAlias = true
                isSubpixelText = true
            }
        }
    }
}
