package com.aide.mobilestudio.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aide.mobilestudio.StudioUi
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var session: TerminalSession? = null

    /*
     * ============================================================
     * Terminal appearance
     * ============================================================
     *
     * 13px is intentionally larger than the previous 10px default.
     *
     * Pinch zoom:
     *     8px  ~ minimum
     *     13px ~ default
     *     30px ~ maximum
     */
    private val textPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 13f
        isAntiAlias = true
        isSubpixelText = true
        color = com.aide.mobilestudio.StudioUi.palette.text
    }

    private val cursorPaint = Paint().apply {
        color = com.aide.mobilestudio.StudioUi.palette.text
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val selectionPaint = Paint().apply {
        style = Paint.Style.FILL
        color = com.aide.mobilestudio.StudioUi.palette.editorSelection
        // Drawn *behind* the text (see the row loop): translucent so the
        // glyphs underneath stay readable instead of being covered by an
        // opaque rectangle.
        alpha = SELECTION_ALPHA
    }

    private val scrollbarPaint = Paint().apply {
        style = Paint.Style.FILL
        color = com.aide.mobilestudio.StudioUi.palette.surface2
    }

    private val promptPaint = Paint().apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 13f
        isAntiAlias = true
        color = com.aide.mobilestudio.StudioUi.palette.accent
    }

    private val statusPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = 9.88f
        isAntiAlias = true
        color = com.aide.mobilestudio.StudioUi.palette.textSoft
    }

    private val statusBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = com.aide.mobilestudio.StudioUi.palette.surface
    }

    private val statusSuccessPaint = Paint(statusPaint).apply {
        color = com.aide.mobilestudio.StudioUi.palette.success
    }

    private val statusErrorPaint = Paint(statusPaint).apply {
        color = com.aide.mobilestudio.StudioUi.palette.error
    }

    private val statusRunningPaint = Paint(statusPaint).apply {
        color = com.aide.mobilestudio.StudioUi.palette.warning
    }

    private var charWidth = 0f
    private var charHeight = 0f
    private var baselineOffset = 0f

    private var paddingTop = 8f
    private var paddingLeft = 8f

    /*
     * ============================================================
     * Scrolling
     * ============================================================
     */

    private var scrollY = 0
    private var maxScrollY = 0
    private var isUserScrolling = false
    private var contentRefreshPending = false

    /*
     * Follow-up invalidation after the last output/input activity.  The
     * prompt drawing is gated on idle windows (PROMPT_IDLE_OUTPUT_MS /
     * PROMPT_IDLE_INPUT_MS), but nothing re-invalidates the view after the
     * final coalesced frame — without this pass the prompt would never
     * reappear on screen.
     */
    private val promptIdleRefresh = Runnable {
        invalidate()
    }

    /*
     * ============================================================
     * Touch / selection
     * ============================================================
     */

    private var lastTouchY = 0f
    private var lastTouchX = 0f
    private var touchStartY = 0f

    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    private var isSelecting = false

    /*
     * Standard Android floating selection toolbar (copy / paste / select all),
     * the same long-press action mode the code editor uses.  No custom handles.
     */
    private var selectionActionMode: ActionMode? = null

    /*
     * ============================================================
     * Completion
     * ============================================================
     */

    private var completionPopup: PopupWindow? = null
    private var completionItems: List<String> = emptyList()
    private var completionIndex = 0

    var onKeyInterception: ((KeyEvent) -> Boolean)? = null

    /*
     * ============================================================
     * Syntax highlight cache
     * ============================================================
     */

    private val highlightCache =
        mutableMapOf<Int, List<TerminalSyntaxHighlighter.ColoredSegment>>()

    private fun getHighlightedLine(
        lineIndex: Int,
        text: String,
        isInput: Boolean
    ): List<TerminalSyntaxHighlighter.ColoredSegment> {

        val cacheKey = lineIndex * 2 + if (isInput) 1 else 0

        return highlightCache.getOrPut(cacheKey) {
            if (isInput) {
                TerminalSyntaxHighlighter.highlightInput(text)
            } else {
                TerminalSyntaxHighlighter.highlightOutput(text)
            }
        }
    }

    /*
     * ============================================================
     * Pinch zoom
     * ============================================================
     */

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {

                    val currentSize = textPaint.textSize

                    /*
                     * Clamp the scale factor so a single gesture
                     * cannot suddenly make the font enormous/tiny.
                     */
                    val factor = detector.scaleFactor
                        .coerceIn(0.85f, 1.15f)

                    val newSize = (
                        currentSize * factor
                    ).coerceIn(
                        MIN_FONT_SIZE,
                        MAX_FONT_SIZE
                    )

                    if (newSize != currentSize) {
                        setFontSize(newSize)
                    }

                    return true
                }
            }
        )

    /*
     * ============================================================
     * Normal terminal gestures
     * ============================================================
     */

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onDoubleTap(
                    e: MotionEvent
                ): Boolean {
                    return true
                }

                override fun onLongPress(
                    e: MotionEvent
                ) {

                    /*
                     * Don't start text selection while pinch zoom
                     * is active.
                     */
                    if (scaleDetector.isInProgress) {
                        return
                    }

                    val row = touchToScrollLine(e.y)
                    val col = pixelToCol(e.x)

                    selectionStart = Pair(row, col)
                    selectionEnd = Pair(row, col)
                    isSelecting = true

                    invalidate()
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {

                    if (scaleDetector.isInProgress) {
                        return true
                    }

                    if (!isSelecting) {

                        scrollY = (
                            scrollY + distanceY.toInt()
                        ).coerceIn(
                            0,
                            maxScrollY
                        )

                        isUserScrolling =
                            scrollY < maxScrollY

                        invalidate()

                        return true
                    }

                    return false
                }

                override fun onSingleTapUp(
                    e: MotionEvent
                ): Boolean {

                    clearSelection()
                    showKeyboard()

                    return true
                }
            }
        )

    /*
     * ============================================================
     * Theme
     * ============================================================
     */

    private var lastPaletteVersion = -1

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)

        recalculateMetrics()
    }

    private fun refreshThemeIfNeeded() {

        val version =
            com.aide.mobilestudio.StudioUi.palette.hashCode()

        if (version == lastPaletteVersion) {
            return
        }

        lastPaletteVersion = version

        val p =
            com.aide.mobilestudio.StudioUi.palette

        textPaint.color = p.text
        cursorPaint.color = p.text
        selectionPaint.color = p.editorSelection
        selectionPaint.alpha = SELECTION_ALPHA
        scrollbarPaint.color = p.surface2
        promptPaint.color = p.accent

        statusPaint.color = p.textSoft
        statusBgPaint.color = p.surface
        statusSuccessPaint.color = p.success
        statusErrorPaint.color = p.error
        statusRunningPaint.color = p.warning
    }

    /*
     * ============================================================
     * IME
     * ============================================================
     */

    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    override fun onCreateInputConnection(
        outAttrs: EditorInfo
    ): InputConnection {

        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        outAttrs.imeOptions =
            EditorInfo.IME_ACTION_NONE

        return TerminalInputConnection()
    }

    /*
     * Some toolbar paste implementations can trigger both
     * clipboard insertion and IME commitText().
     */
    @Volatile
    private var isPasting = false

    /*
     * Text currently sent from the IME composing buffer.
     *
     * Example:
     *
     *     IME composing: "l"
     *     then "ls"
     *
     * We only send the delta:
     *
     *     l
     *     s
     *
     * rather than sending the whole composing string again.
     */
    private var composingSent = ""

    /**
     * The shell just consumed a line: its input buffer is empty again, so the
     * IME's idea of what it has already sent is void. Forgetting this made the
     * next line's composing delta start from the previous command's text and
     * emit backspace runs the shell could not honour.
     */
    private fun endInputLine() {
        composingSent = ""
    }

    /** One character left the shell's input line (backspace). */
    private fun dropComposingChar() {
        if (composingSent.isNotEmpty()) {
            composingSent = composingSent.dropLast(1)
        }
    }

    /**
     * How many characters the shell currently holds on the input line, as the
     * emulator sees them. The shell echoes everything it accepts, so this is
     * the ground truth the IME delta has to be clamped against.
     */
    private fun shellLineLength(): Int {
        val emulator = session?.emulator ?: return 0
        val row = emulator.getCursorRow()
        if (row < 0) return 0
        var n = 0
        for (cell in emulator.getLine(row)) {
            if (cell.char == '\u0000') break
            n++
        }
        // Moving the cursor left with arrows must never widen the erase range.
        return n.coerceAtMost(emulator.getCursorCol().coerceAtLeast(0))
    }

    inner class TerminalInputConnection :
        BaseInputConnection(
            this@TerminalView,
            true
        ) {

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int
        ): Boolean {

            if (isPasting) {
                return true
            }

            val t = text.toString()

            if (t.isEmpty()) {
                return true
            }

            val sent = composingSent


            if (sent.isNotEmpty()) {

                composingSent = ""

                if (t.startsWith(sent)) {

                    /*
                     * IME committed the existing composing text
                     * plus additional characters.
                     */
                    emit(
                        t.substring(sent.length)
                    )

                } else {

                    /*
                     * IME replaced its composing buffer.
                     *
                     * Only as many backspaces as the shell's line actually
                     * holds can be honoured -- after Enter or `clear` the line
                     * is empty, and blindly sending `sent.length` of them
                     * desyncs the shell's input buffer from the screen.
                     */
                    emit(
                        "\b".repeat(
                            boundedBackspaces(sent.length, shellLineLength())
                        ) + t
                    )
                }

            } else {

                emit(t)
            }

            return true
        }

        override fun setComposingText(
            text: CharSequence,
            newCursorPosition: Int
        ): Boolean {

            val t = text.toString()
            val old = composingSent

            if (t != old) {

                var common = 0

                val commonMax =
                    minOf(
                        t.length,
                        old.length
                    )

                while (
                    common < commonMax &&
                    t[common] == old[common]
                ) {
                    common++
                }

                /*
                 * Replace only the changed portion of the composing buffer,
                 * bounded by what the shell's line can actually erase.
                 */
                emit(
                    "\b".repeat(
                        boundedBackspaces(old.length - common, shellLineLength())
                    ) +
                        t.substring(common)
                )

                composingSent = t
            }

            return true
        }

        override fun finishComposingText(): Boolean {
            composingSent = ""
            return true
        }

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int
        ): Boolean {

            if (beforeLength > 0) {

                /*
                 * Backspace is not Ctrl mappable: it is sent normally
                 * and releases a single-shot Ctrl latch.
                 */
                releaseCtrlLatch()

                /*
                 * Do NOT localEcho here.
                 *
                 * The PTY/shell is responsible for echoing the
                 * resulting terminal state.
                 */
                session?.sendRaw(
                    "\b".repeat(beforeLength)
                )
            }


            /*
             * Terminal shells normally do not support deleting
             * characters after the cursor through this API.
             *
             * Keep afterLength ignored rather than attempting to
             * emulate it incorrectly.
             */

            return true
        }

        override fun sendKeyEvent(
            event: KeyEvent
        ): Boolean {

            if (
                event.action !=
                KeyEvent.ACTION_DOWN
            ) {
                return false
            }

            val currentSession =
                this@TerminalView.session
                    ?: return false

            /*
             * Non-mappable keys (Enter, Backspace, arrows, ...) are sent
             * normally and release a single-shot Ctrl latch.
             */
            if (event.keyCode in CTRL_RELEASE_KEYCODES) {
                releaseCtrlLatch()
            }

            when (event.keyCode) {


                KeyEvent.KEYCODE_ENTER -> {

                    dismissCompletion()
                    endInputLine()

                    /*
                     * No local echo.
                     *
                     * The shell/PTY produces the terminal output.
                     */
                    currentSession.sendRaw(ENTER_KEY)

                    return true
                }

                KeyEvent.KEYCODE_DEL -> {

                    currentSession.sendRaw("\b")
                    dropComposingChar()

                    return true
                }

                KeyEvent.KEYCODE_TAB -> {

                    handleTabCompletion()

                    return true
                }

                KeyEvent.KEYCODE_ESCAPE -> {

                    dismissCompletion()

                    currentSession.sendRaw(
                        "\u001B"
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {

                    currentSession.sendRaw(
                        "\u001B[A"
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {

                    currentSession.sendRaw(
                        "\u001B[B"
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {

                    currentSession.sendRaw(
                        "\u001B[C"
                    )

                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {

                    currentSession.sendRaw(
                        "\u001B[D"
                    )

                    return true
                }

                KeyEvent.KEYCODE_HOME -> {

                    currentSession.sendRaw(
                        "\u001B[H"
                    )

                    return true
                }

                KeyEvent.KEYCODE_MOVE_END -> {

                    currentSession.sendRaw(
                        "\u001B[F"
                    )

                    return true
                }

                KeyEvent.KEYCODE_PAGE_UP -> {

                    scrollY =
                        (scrollY - 10)
                            .coerceAtLeast(0)

                    isUserScrolling =
                        scrollY < maxScrollY

                    postInvalidate()

                    return true
                }

                KeyEvent.KEYCODE_PAGE_DOWN -> {

                    scrollY =
                        (scrollY + 10)
                            .coerceAtMost(maxScrollY)

                    isUserScrolling =
                        scrollY < maxScrollY

                    postInvalidate()

                    return true
                }
            }

            /*
             * Some physical keyboards / IMEs send printable
             * characters through KeyEvent instead of commitText().
             *
             * Forward them once.
             */
            if (
                !event.isCtrlPressed &&
                !event.isAltPressed
            ) {

                val code =
                    event.unicodeChar

                if (
                    code >= 0x20 &&
                    code != 0x7F &&
                    code <= 0x10FFFF
                ) {

                    emit(
                        String(
                            Character.toChars(code)
                        )
                    )

                    dismissCompletion()

                    return true
                }

            }

            return false
        }

        override fun performEditorAction(
            editorActionId: Int
        ): Boolean {

            /*
             * Enter has no Ctrl mapping: it is sent normally and
             * releases a single-shot Ctrl latch.
             */
            releaseCtrlLatch()

            endInputLine()

            session?.sendRaw(ENTER_KEY)

            dismissCompletion()

            return true
        }


        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest,
            flags: Int
        ): android.view.inputmethod.ExtractedText? {

            return android.view.inputmethod.ExtractedText().apply {

                text = ""

                startOffset = 0
                selectionStart = 0
                selectionEnd = 0
            }
        }

        override fun beginBatchEdit(): Boolean {
            return true
        }

        override fun endBatchEdit(): Boolean {
            return true
        }
    }

    /*
     * ============================================================
     * Hardware keyboard
     * ============================================================
     */

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent
    ): Boolean {

        val currentSession =
            this.session
                ?: return super.onKeyDown(
                    keyCode,
                    event
                )

        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        /*
         * Non-mappable keys (Enter, Backspace, arrows, ...) are sent
         * normally and release a single-shot Ctrl latch.
         */
        if (
            !ctrl &&
            !alt &&
            keyCode in CTRL_RELEASE_KEYCODES
        ) {
            releaseCtrlLatch()
        }

        if (ctrl) {


            /*
             * Ctrl+C
             *
             * While a process is running it must interrupt that process,
             * like in any real terminal.  Only when nothing is running and
             * a selection exists is it used for copying, so the common
             * case (stop the foreground job) always works.
             */
            if (
                keyCode == KeyEvent.KEYCODE_C &&
                !shift &&
                !alt
            ) {

                val processRunning =
                    currentSession
                        .processTracker
                        .isRunning()

                val text =
                    if (processRunning) "" else getSelectedText()

                if (text.isNotBlank()) {

                    val clipboard =
                        context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Terminal Selection",
                            text
                        )
                    )

                    Toast.makeText(
                        context,
                        "복사됨",
                        Toast.LENGTH_SHORT
                    ).show()

                    return true
                }

                /*
                 * No selection (or a process is running):
                 * fall through so the session receives the interrupt.
                 */
            }

            /*
             * Ctrl+V = paste.
             */
            if (
                keyCode == KeyEvent.KEYCODE_V &&
                !shift &&
                !alt
            ) {

                pasteFromClipboard()

                return true
            }

            /*
             * Ctrl+A = select all terminal contents.
             */
            if (
                keyCode == KeyEvent.KEYCODE_A &&
                !shift &&
                !alt
            ) {

                selectAll()

                return true
            }

            /*
             * Other Ctrl combinations are passed to the session.
             */
            if (
                currentSession.handleKeyDown(
                    keyCode,
                    shift,
                    ctrl,
                    alt
                )
            ) {
                return true
            }
        }

        when (keyCode) {

            KeyEvent.KEYCODE_TAB -> {

                handleTabCompletion()

                return true
            }

            KeyEvent.KEYCODE_ENTER -> {

                dismissCompletion()
                endInputLine()

                /*
                 * No localEcho().
                 */
                currentSession.sendRaw(ENTER_KEY)

                return true
            }

            KeyEvent.KEYCODE_DEL -> {

                /*
                 * No localEcho().
                 */
                currentSession.sendRaw("\b")
                dropComposingChar()

                return true
            }

            KeyEvent.KEYCODE_ESCAPE -> {

                dismissCompletion()

                currentSession.sendRaw(
                    "\u001B"
                )

                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {

                currentSession.sendRaw(
                    "\u001B[A"
                )

                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {

                currentSession.sendRaw(
                    "\u001B[B"
                )

                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {

                currentSession.sendRaw(
                    "\u001B[C"
                )

                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {

                currentSession.sendRaw(
                    "\u001B[D"
                )

                return true
            }

            KeyEvent.KEYCODE_HOME -> {

                currentSession.sendRaw(
                    "\u001B[H"
                )

                return true
            }

            KeyEvent.KEYCODE_MOVE_END -> {

                currentSession.sendRaw(
                    "\u001B[F"
                )

                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {

                scrollY =
                    (scrollY - 10)
                        .coerceAtLeast(0)

                isUserScrolling =
                    scrollY < maxScrollY

                postInvalidate()

                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {

                scrollY =
                    (scrollY + 10)
                        .coerceAtMost(maxScrollY)

                isUserScrolling =
                    scrollY < maxScrollY

                postInvalidate()

                return true
            }
        }

        /*
         * Printable characters normally arrive through
         * InputConnection.commitText().
         *
         * Injected and hardware key events (adb `input text`, hardware and
         * Bluetooth keyboards) never reach the InputConnection at all, so
         * they would be dropped without this fallback.  Software keyboards
         * do not deliver committed text as KeyEvents, so handling them here
         * cannot produce duplicated input (TextViews follow the same
         * pattern).
         */
        if (
            !ctrl &&
            !alt
        ) {
            val unicode = event.unicodeChar
            if (unicode >= ' '.code) {
                emit(unicode.toChar().toString())
                return true
            }
        }

        return super.onKeyDown(
            keyCode,
            event
        )
    }

    /*
     * ============================================================
     * Ctrl modifier (Termux-style latch / sticky)
     * ============================================================
     */

    private var ctrlLatched = false

    private var ctrlSticky = false

    private var ctrlTapAt = 0L

    var onCtrlStateChanged: ((latched: Boolean, sticky: Boolean) -> Unit)? = null

    fun isCtrlLatched(): Boolean = ctrlLatched

    fun isCtrlSticky(): Boolean = ctrlSticky

    /**
     * Ctrl key bar button tap.
     *
     * The first tap latches the modifier for the next key.  A second tap
     * inside [CTRL_DOUBLE_TAP_MS] makes it sticky, a later tap releases it.
     */
    fun ctrlTapped() {

        val now = SystemClock.uptimeMillis()

        when {

            // Sticky: a plain tap releases it.
            ctrlSticky -> setCtrlState(false, false)

            // Second tap within the window: latch becomes sticky.
            ctrlLatched &&
                now - ctrlTapAt <= CTRL_DOUBLE_TAP_MS ->
                setCtrlState(true, true)

            // Single-shot latch: a plain tap releases it.
            ctrlLatched -> setCtrlState(false, false)

            // Off: latch it for the next key.
            else -> setCtrlState(true, false)
        }

        ctrlTapAt = now
    }

    /**
     * Long press on the Ctrl key bar button toggles sticky mode.
     */
    fun ctrlLongPressed() {

        if (ctrlSticky) {
            setCtrlState(false, false)
        } else {
            setCtrlState(true, true)
        }

        ctrlTapAt = SystemClock.uptimeMillis()
    }

    private fun setCtrlState(latched: Boolean, sticky: Boolean) {

        if (ctrlLatched == latched && ctrlSticky == sticky) {
            return
        }

        ctrlLatched = latched
        ctrlSticky = sticky

        onCtrlStateChanged?.invoke(latched, sticky)
    }

    /**
     * Releases a single-shot latch after one key was handled.
     *
     * Sticky mode keeps the modifier on until Ctrl is tapped again.
     */
    private fun releaseCtrlLatch() {

        if (ctrlLatched && !ctrlSticky) {
            setCtrlState(false, false)
        }
    }

    /**
     * Consumes the latched Ctrl modifier for one typed character.
     *
     * Returns true when the character was sent as its control code.
     * A single-shot latch is always released here; a character without
     * a control mapping is left for the caller to send normally.
     */
    private fun consumeCtrlChar(ch: Char): Boolean {

        if (!ctrlLatched) {
            return false
        }

        val code = ctrlCharToCode(ch)

        releaseCtrlLatch()

        if (code == null) {
            return false
        }

        session?.sendControlChar(code)

        return true
    }

    /**
     * Control code for a Ctrl+<key> combination.
     *
     * Returns null when the character has no control mapping.
     */
    private fun ctrlCharToCode(ch: Char): Int? {

        val lower = ch.lowercaseChar()

        return when (lower) {

            in 'a'..'z' -> lower - 'a' + 1

            '@' -> 0x00
            ' ' -> 0x00
            '[' -> 0x1B
            '\\' -> 0x1C
            ']' -> 0x1D
            '^' -> 0x1E
            '_' -> 0x1F
            '-' -> 0x1F
            '?' -> 0x7F

            else -> null
        }
    }

    /*
     * ============================================================
     * Extra key bar
     * ============================================================
     *
     * None of these keys has a Ctrl mapping, so each one sends its
     * normal sequence and releases a single-shot Ctrl latch.
     */

    fun keyBarEsc() {

        dismissCompletion()

        releaseCtrlLatch()

        session?.sendRaw(
            "\u001B"
        )
    }

    fun keyBarTab() {

        dismissCompletion()

        releaseCtrlLatch()

        handleTabCompletion()
    }

    fun keyBarEnter() {

        dismissCompletion()

        releaseCtrlLatch()

        endInputLine()

        session?.sendRaw(ENTER_KEY)
    }

    fun keyBarBackspace() {

        releaseCtrlLatch()

        session?.sendRaw("\b")
    }

    fun keyBarArrow(keyCode: Int) {

        val sequence =
            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
                KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
                KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"

                else -> return
            }

        releaseCtrlLatch()

        session?.sendRaw(sequence)
    }

    /*
     * ============================================================
     * Input
     * ============================================================
     */


    /**
     * Sends terminal input to the PTY.
     *
     * IMPORTANT:
     *
     * This method intentionally does NOT call localEcho().
     *
     * The old implementation did:
     *
     *     localEcho(text)
     *     sendRaw(text)
     *
     * while the shell also echoed the input.
     *
     * That caused:
     *
     *     ls -> llss
     *
     * on screen while the actual shell received:
     *
     *     ls
     *
     * Removing localEcho() keeps the emulator synchronized with
     * the PTY output.
     */
    private fun emit(text: String) {

        if (text.isEmpty()) {
            return
        }

        var out = text

        /*
         * A Ctrl latch from the key bar applies to typed input from every
         * source (IME commitText, injected/hardware key events).  Leading
         * characters with a control mapping are sent as their control code;
         * a character without a mapping is left to be sent normally
         * (consumeCtrlChar releases a single-shot latch, sticky stays on).
         */
        while (ctrlLatched && out.isNotEmpty()) {
            if (!consumeCtrlChar(out[0])) {
                break
            }
            out = out.substring(1)
        }

        if (out.isNotEmpty()) {
            session?.sendRaw(out)
        }

        /*
         * Input updates lastInputAtMs (the prompt idle gate) and may produce
         * no echo at all — make sure the view re-evaluates the prompt.
         */
        scheduleContentRefresh()
    }

    /*
     * Kept for compatibility with code that may use this helper
     * for output that is genuinely generated locally.
     *
     * Normal keyboard input MUST NOT use this method.
     */
    private fun localEcho(
        session: TerminalSession?,
        text: String
    ) {

        session ?: return

        session.emulator.processString(text)

        highlightCache.clear()

        updateScrollLimits()

        if (!isUserScrolling) {
            scrollY = maxScrollY
        }

        postInvalidate()
    }

    /*
     * ============================================================
     * Font size
     * ============================================================
     */

    fun setFontSize(sizePx: Float) {

        val newSize =
            sizePx.coerceIn(
                MIN_FONT_SIZE,
                MAX_FONT_SIZE
            )

        if (
            newSize == textPaint.textSize
        ) {
            return
        }

        textPaint.textSize = newSize

        recalculateMetrics()

        resizeTerminalToView()

        invalidate()
    }

    fun getFontSize(): Float {
        return textPaint.textSize
    }

    private fun recalculateMetrics() {

        val fm =
            textPaint.fontMetrics

        charHeight =
            -fm.top + fm.bottom

        charWidth =
            textPaint.measureText("M")

        baselineOffset =
            -fm.top

        promptPaint.textSize =
            textPaint.textSize

        statusPaint.textSize =
            textPaint.textSize * 0.76f

        statusSuccessPaint.textSize =
            statusPaint.textSize

        statusErrorPaint.textSize =
            statusPaint.textSize

        statusRunningPaint.textSize =
            statusPaint.textSize

        updateScrollLimits()

        invalidate()
    }

    /*
     * ============================================================
     * Terminal size
     * ============================================================
     */

    private fun resizeTerminalToView() {

        val currentSession =
            session ?: return

        if (
            width <= 0 ||
            height <= 0 ||
            charWidth <= 0f ||
            charHeight <= 0f
        ) {
            return
        }

        val cols =
            (
                (width - paddingLeft) /
                    charWidth
                )
                .toInt()
                .coerceIn(
                    20,
                    300
                )

        val rows =
            (
                (height - paddingTop) /
                    charHeight
                )
                .toInt()
                .coerceIn(
                    5,
                    100
                )

        if (
            cols !=
                currentSession.emulator.getCols() ||
            rows !=
                currentSession.emulator.getRows()
        ) {

            currentSession.resize(
                cols,
                rows
            )
        }
    }

    /*
     * ============================================================
     * Session
     * ============================================================
     */

    /**
     * Coalesces content invalidations to at most one per animation frame.
     * During an output flood thousands of per-line callbacks collapse into a
     * single highlight-cache rebuild and invalidate, keeping the UI responsive.
     */
    private fun scheduleContentRefresh() {
        /*
         * Schedule the idle pass first: it must fire after the prompt idle
         * windows even when the coalesced frame below is already pending
         * (new activity resets the timer so the gate sees a fresh idle gap).
         */
        removeCallbacks(promptIdleRefresh)
        postDelayed(promptIdleRefresh, PROMPT_IDLE_REFRESH_MS)

        if (contentRefreshPending) return
        contentRefreshPending = true
        postOnAnimation {
            contentRefreshPending = false
            highlightCache.clear()
            updateScrollLimits()
            if (!isUserScrolling) {
                scrollY = maxScrollY
            }
            invalidate()
        }
    }

    fun setSession(
        session: TerminalSession
    ) {

        this.session = session

        session.onOutput = { scheduleContentRefresh() }

        /*
         * The emulator also mutates its buffer outside onOutput (resize,
         * clear, CSI 2J, scrolling inside processString). Those paths only
         * fire onScreenUpdate, so both callbacks funnel into one coalesced
         * refresh — otherwise a log flood queues a cache clear + invalidate
         * per output line and starves the frame budget.
         */
        session.emulator.onScreenUpdate = { scheduleContentRefresh() }

        recalculateMetrics()

        invalidate()
    }

    fun getSession(): TerminalSession? {
        return session
    }

    /*
     * ============================================================
     * Scroll limits
     * ============================================================
     */

    private fun updateScrollLimits() {

        val emulator =
            session?.emulator
                ?: return

        if (charHeight <= 0f) {
            return
        }

        val totalLines =
            emulator.getRows() +
                emulator.getScrollBackSize()

        val visibleLines =
            (height / charHeight)
                .toInt()
                .coerceAtLeast(1)

        maxScrollY =
            max(
                0,
                totalLines - visibleLines
            )

        if (!isUserScrolling) {
            scrollY = maxScrollY
        } else {
            scrollY =
                scrollY.coerceIn(
                    0,
                    maxScrollY
                )
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {

        super.onSizeChanged(
            w,
            h,
            oldw,
            oldh
        )

        if (
            w > 0 &&
            h > 0
        ) {

            recalculateMetrics()

            resizeTerminalToView()
        }
    }

    /*
     * ============================================================
     * Drawing
     * ============================================================
     */

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        refreshThemeIfNeeded()

        val emulator =
            session?.emulator
                ?: return

        val tracker =
            session?.processTracker

        /*
         * Layer 1: background
         */
        canvas.drawColor(
            TerminalEmulator.DEFAULT_BG
        )

        val cols =
            emulator.getCols()

        val rows =
            emulator.getRows()

        if (charHeight <= 0f) {
            return
        }

        val visibleRows =
            (height / charHeight)
                .toInt() + 1

        val scrollBackSize =
            emulator.getScrollBackSize()

        val cursorRow =
            emulator.getCursorRow()

        /*
         * Layer 2: terminal output
         */
        for (
            screenRow in
            0 until visibleRows
        ) {

            val scrollLineIndex =
                scrollY + screenRow

            val line:
                List<TerminalEmulator.Cell> =
                if (
                    scrollLineIndex <
                    scrollBackSize
                ) {

                    emulator.getScrollBackLine(
                        scrollLineIndex
                    )

                } else {

                    val bufferRow =
                        scrollLineIndex -
                            scrollBackSize

                    if (
                        bufferRow in
                        0 until rows
                    ) {

                        emulator.getLine(
                            bufferRow
                        )

                    } else {

                        emptyList()
                    }
                }

            val y =
                paddingTop +
                    screenRow *
                    charHeight

            val lineText =
                line.joinToString("") {
                    it.char.toString()
                }

            val isCursorLine =
                (
                    scrollLineIndex -
                        scrollBackSize
                    ) == cursorRow

            /*
             * Selection highlight — drawn before the text so the fill sits
             * *behind* the glyphs (an opaque rectangle painted over the
             * text made selected/pasted regions unreadable).
             */
            if (
                isSelecting ||
                (
                    selectionStart != null &&
                        selectionEnd != null
                    )
            ) {

                drawSelection(
                    canvas,
                    screenRow,
                    y
                )
            }

            /*
             * Text output.
             */
            if (lineText.isNotBlank()) {

                val segments =
                    getHighlightedLine(
                        scrollLineIndex,
                        lineText,
                        isCursorLine
                    )

                var x =
                    paddingLeft

                for (
                    segment in segments
                ) {

                    textPaint.color =
                        segment.color

                    canvas.drawText(
                        segment.text,
                        x,
                        y + baselineOffset,
                        textPaint
                    )

                    x +=
                        textPaint.measureText(
                            segment.text
                        )
                }

            } else {

                /*
                 * Empty line / background cells.
                 */
                for (
                    col in
                    0 until cols
                ) {

                    val cell =
                        if (
                            col < line.size
                        ) {

                            line[col]

                        } else {

                            TerminalEmulator.Cell()
                        }

                    val x =
                        paddingLeft +
                            col *
                            charWidth

                    val bgColor =
                        cell.effectiveBg

                    if (
                        bgColor !=
                        TerminalEmulator.DEFAULT_BG
                    ) {

                        bgPaint.color =
                            bgColor

                        canvas.drawRect(
                            x,
                            y,
                            x + charWidth,
                            y + charHeight,
                            bgPaint
                        )
                    }
                }
            }

        }

        /*
         * Layer 3: process status.
         */
        drawProcessStatus(canvas)

        /*
         * Layer 4: dynamic prompt.
         *
         * This only draws the prompt when the shell has not
         * already written content on the current line.
         */
        if (
            tracker?.currentProcess == null
        ) {

            drawDynamicPrompt(canvas)
        }

        /*
         * Cursor.
         */
        drawCursor(canvas)

        /*
         * Scrollbar.
         */
        drawScrollbar(canvas)
    }

    /*
     * ============================================================
     * Prompt
     * ============================================================
     */

    /**
     * The prompt is drawn on the monospace grid, so its width is measured
     * in columns (characters), never pixels. Reserve room after the prompt
     * for the user to type so the prompt and the typed text never collide.
     */
    private fun availablePromptColumns(emulator: TerminalEmulator): Int =
        (emulator.getCols() - PROMPT_TYPED_COLUMNS_RESERVED)
            .coerceAtLeast(0)

    private fun drawDynamicPrompt(
        canvas: Canvas
    ) {

        val currentSession =
            session ?: return

        /*
         * Idle gating: never flash the prompt while output is streaming,
         * and not right after Enter either — the cursor line is briefly
         * empty before the command's first output arrives.
         */
        val now = System.currentTimeMillis()

        if (
            now - currentSession.lastOutputAtMs < PROMPT_IDLE_OUTPUT_MS ||
            now - currentSession.lastInputAtMs < PROMPT_IDLE_INPUT_MS
        ) {
            return
        }

        val emulator =
            currentSession.emulator

        val cursorRow =
            emulator.getCursorRow()

        val scrollBackSize =
            emulator.getScrollBackSize()

        val screenRow =
            cursorRow +
                scrollBackSize -
                scrollY

        if (
            screenRow < 0 ||
            screenRow >=
                (height / charHeight)
                    .toInt()
        ) {
            return
        }

        val y =
            paddingTop +
                screenRow *
                charHeight

        val prompt =
            currentSession
                .promptManager
                .formatPrompt(
                    availablePromptColumns(emulator)
                )

        val line =
            emulator.getLine(cursorRow)

        /*
         * Only draw the custom prompt if the current line is
         * actually empty.
         */
        if (
            line.isEmpty() ||
            line.all {
                it.char == ' ' ||
                    it.char == '\u0000'
            }
        ) {

            canvas.drawText(
                prompt,
                paddingLeft,
                y + baselineOffset,
                promptPaint
            )
        }
    }

    /*
     * ============================================================
     * Process status
     * ============================================================
     */

    private fun drawProcessStatus(
        canvas: Canvas
    ) {

        val tracker =
            session?.processTracker
                ?: return

        val info =
            tracker.currentProcess
                ?: return

        val statusHeight =
            charHeight * 1.2f

        val y =
            paddingTop +
                (
                    height -
                        statusHeight -
                        16f
                    )

        bgPaint.color =
            statusBgPaint.color

        val rect =
            RectF(
                16f,
                y,
                width.toFloat() - 16f,
                y + statusHeight
            )

        canvas.drawRoundRect(
            rect,
            12f,
            12f,
            bgPaint
        )

        val paint =
            when (info.state) {

                ProcessStatusTracker
                    .ProcessInfo
                    .State.RUNNING ->
                    statusRunningPaint

                ProcessStatusTracker
                    .ProcessInfo
                    .State.SUCCESS ->
                    statusSuccessPaint

                ProcessStatusTracker
                    .ProcessInfo
                    .State.FAILED ->
                    statusErrorPaint
            }

        val statusText =
            info.formatStatusLine()

        canvas.drawText(
            statusText,
            32f,
            y + baselineOffset * 0.9f,
            paint
        )
    }

    /*
     * ============================================================
     * Selection
     * ============================================================
     */

    private fun drawSelection(
        canvas: Canvas,
        screenRow: Int,
        y: Float
    ) {

        val selStart =
            selectionStart
                ?: return

        val selEnd =
            selectionEnd
                ?: return

        val startRow =
            min(
                selStart.first,
                selEnd.first
            )

        val endRow =
            max(
                selStart.first,
                selEnd.first
            )

        val startCol =
            if (
                selStart.first <=
                selEnd.first
            ) {

                selStart.second

            } else {

                selEnd.second
            }

        val endCol =
            if (
                selStart.first <=
                selEnd.first
            ) {

                selEnd.second

            } else {

                selStart.second
            }

        val emulator =
            session?.emulator
                ?: return

        val scrollLine =
            scrollY +
                screenRow

        if (
            scrollLine !in
            startRow..endRow
        ) {
            return
        }

        val x1 =
            if (
                scrollLine == startRow
            ) {

                paddingLeft +
                    startCol.coerceAtLeast(0) *
                    charWidth

            } else {

                paddingLeft
            }

        val x2 =
            if (
                scrollLine == endRow
            ) {

                paddingLeft +
                    (endCol + 1)
                        .coerceAtLeast(0) *
                    charWidth

            } else {

                paddingLeft +
                    emulator.getCols() *
                    charWidth
            }

        canvas.drawRect(
            x1,
            y,
            x2,
            y + charHeight,
            selectionPaint
        )
    }

    /*
     * ============================================================
     * Cursor
     * ============================================================
     */

    private fun drawCursor(
        canvas: Canvas
    ) {

        val emulator =
            session?.emulator
                ?: return

        val cursorRow =
            emulator.getCursorRow()

        val cursorCol =
            emulator.getCursorCol()

        val scrollBackSize =
            emulator.getScrollBackSize()

        val screenRow =
            cursorRow +
                scrollBackSize -
                scrollY

        if (
            screenRow < 0 ||
            screenRow >=
                emulator.getRows()
        ) {
            return
        }

        val x =
            paddingLeft +
                cursorCol *
                charWidth

        val y =
            paddingTop +
                screenRow *
                charHeight

        /*
         * Draw cursor background.
         */
        canvas.drawRect(
            x,
            y,
            x + charWidth,
            y + charHeight,
            cursorPaint
        )

        if (
            cursorCol <
            emulator.getCols()
        ) {

            val cell =
                emulator
                    .getLine(cursorRow)
                    .getOrElse(cursorCol) {
                        TerminalEmulator.Cell()
                    }

            /*
             * Save the text color.
             *
             * The old implementation changed textPaint.color
             * to DEFAULT_BG and left it changed. That could affect
             * later rendering.
             */
            val oldColor =
                textPaint.color

            textPaint.color =
                TerminalEmulator.DEFAULT_BG

            val char =
                cell.char

            if (
                char != '\u0000' &&
                char != ' '
            ) {

                canvas.drawText(
                    char.toString(),
                    x,
                    y + baselineOffset,
                    textPaint
                )
            }

            textPaint.color =
                oldColor
        }
    }

    /*
     * ============================================================
     * Scrollbar
     * ============================================================
     */

    private fun drawScrollbar(
        canvas: Canvas
    ) {

        if (
            maxScrollY <= 0
        ) {
            return
        }

        val emulator =
            session?.emulator
                ?: return

        val totalLines =
            emulator.getRows() +
                emulator.getScrollBackSize()

        if (
            totalLines <= 0 ||
            charHeight <= 0f
        ) {
            return
        }

        val visibleHeight =
            height.toFloat()

        val scrollbarHeight =
            (
                visibleHeight *
                    visibleHeight /
                    (
                        totalLines *
                            charHeight
                        )
                )
                .coerceIn(
                    20f,
                    visibleHeight
                )

        val scrollbarY =
            (
                scrollY.toFloat() /
                    maxScrollY *
                    (
                        visibleHeight -
                            scrollbarHeight
                        )
                )
                .coerceIn(
                    0f,
                    visibleHeight -
                        scrollbarHeight
                )

        canvas.drawRoundRect(
            width - 8f,
            scrollbarY,
            width - 2f,
            scrollbarY +
                scrollbarHeight,
            3f,
            3f,
            scrollbarPaint
        )
    }

    /*
     * ============================================================
     * Completion
     * ============================================================
     */

    private fun handleTabCompletion() {

        val currentSession =
            session ?: return

        val emulator =
            currentSession.emulator

        val cursorRow =
            emulator.getCursorRow()

        val line =
            emulator.getLine(cursorRow)

        val inputText =
            line
                .joinToString("") {
                    it.char.toString()
                }
                .trimEnd()

        if (
            inputText.isEmpty()
        ) {
            return
        }

        /*
         * Complete only the word currently being typed.
         *
         * The rest of the line already exists in the shell, so candidates
         * must be computed for the last word and only its remaining suffix
         * gets sent back.
         */
        val wordStart =
            inputText.lastIndexOf(' ') + 1

        val typed =
            inputText.substring(wordStart)

        if (typed.isEmpty()) {
            return
        }

        val completions =
            currentSession.getCompletions(
                typed
            )

        when {

            completions.isEmpty() -> {

                showCompletionPopup(
                    listOf("(no matches)")
                )
            }

            completions.size == 1 -> {

                val completion =
                    completions[0]

                val text = if (completion.startsWith(typed)) {
                    completion.substring(typed.length)
                } else {
                    "\b".repeat(typed.length) + completion
                }

                currentSession.sendRaw(
                    text +
                        if (completion.endsWith("/")) "" else " "
                )

                dismissCompletion()
            }

            else -> {

                showCompletionPopup(
                    completions
                )
            }
        }
    }

    private fun showCompletionPopup(
        items: List<String>
    ) {

        val currentSession =
            session ?: return

        dismissCompletion()

        completionItems =
            items

        completionIndex = 0

        val layout =
            LinearLayout(context).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(8),
                    dp(4),
                    dp(4),
                    dp(4)
                )
            }

        val maxItems = 8

        items
            .take(maxItems)
            .forEach { item ->

                layout.addView(
                    TextView(context).apply {

                        text = item

                        textSize = 11f

                        typeface =
                            Typeface.MONOSPACE

                        setTextColor(
                            TerminalSyntaxHighlighter
                                .DEFAULT_COLOR
                        )

                        setPadding(
                            dp(4),
                            dp(2),
                            dp(4),
                            dp(2)
                        )
                    }
                )
            }

        if (
            items.size > maxItems
        ) {

            layout.addView(
                TextView(context).apply {

                    text =
                        "... ${items.size - maxItems} more"

                    textSize = 10f

                    setTextColor(
                        StudioUi.color(StudioUi.TextSoft)
                    )

                    setPadding(
                        dp(4),
                        dp(2),
                        dp(4),
                        dp(2)
                    )
                }
            )
        }

        val bg =
            GradientDrawable().apply {

                setColor(
                    StudioUi.color(StudioUi.Surface2)
                )

                cornerRadius =
                    dp(8).toFloat()

                setStroke(
                    1,
                    StudioUi.color(StudioUi.Border)
                )
            }

        completionPopup =
            PopupWindow(
                layout,
                dp(200),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                isOutsideTouchable =
                    true

                isFocusable =
                    false

                setBackgroundDrawable(bg)

                elevation =
                    8f
            }

        val cursorCol =
            currentSession
                .emulator
                .getCursorCol()

        val cursorRow =
            currentSession
                .emulator
                .getCursorRow()

        val x =
            (
                paddingLeft +
                    cursorCol *
                    charWidth
                )
                .toInt()

        val y =
            (
                paddingTop +
                    cursorRow *
                    charHeight +
                    charHeight
                )
                .toInt()

        completionPopup?.showAsDropDown(
            this,
            x,
            y
        )
    }

    private fun dismissCompletion() {

        completionPopup?.dismiss()

        completionPopup =
            null

        completionItems =
            emptyList()

        completionIndex = 0
    }

    /*
     * ============================================================
     * Touch
     * ============================================================
     */

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        /*
         * Feed the event to the pinch detector first.
         */
        scaleDetector.onTouchEvent(event)

        /*
         * During pinch zoom, do not let the normal terminal
         * scrolling/selection gesture interpret the same motion.
         */
        if (
            scaleDetector.isInProgress
        ) {

            return true
        }

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                lastTouchY =
                    event.y

                lastTouchX =
                    event.x

                touchStartY =
                    event.y

                /*
                 * Don't force "user scrolling" merely because the
                 * user touched the terminal.
                 */
                if (!hasFocus()) {
                    requestFocus()
                }

                showKeyboard()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (isSelecting) {

                    selectionEnd =
                        Pair(
                            touchToScrollLine(event.y),
                            pixelToCol(event.x)
                        )

                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                if (isSelecting) {

                    isSelecting = false

                    /*
                     * Instead of silently copying, surface the standard
                     * Android floating toolbar (copy / paste / select all)
                     * so the gesture matches the code editor's selection.
                     */
                    if (getSelectedText().isNotBlank()) {
                        startSelectionActionMode()
                    } else {
                        clearSelection()
                    }
                }

                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /** Touch Y -> absolute scroll line (covers scrollback + live buffer). */
    private fun touchToScrollLine(
        y: Float
    ): Int = scrollY + pixelToRow(y)


    private fun pixelToRow(
        y: Float
    ): Int {

        if (charHeight <= 0f) {
            return 0
        }

        return (
            (y - paddingTop) /
                charHeight
            )
            .toInt()
            .coerceAtLeast(0)
    }

    private fun pixelToCol(
        x: Float
    ): Int {

        if (charWidth <= 0f) {
            return 0
        }

        return (
            (x - paddingLeft) /
                charWidth
            )
            .toInt()
            .coerceAtLeast(0)
    }

    /*
     * ============================================================
     * Selection text
     * ============================================================
     */

    private fun getSelectedText(): String {

        val selStart =
            selectionStart
                ?: return ""

        val selEnd =
            selectionEnd
                ?: return ""

        val startRow =
            min(
                selStart.first,
                selEnd.first
            )

        val endRow =
            max(
                selStart.first,
                selEnd.first
            )

        val startCol =
            if (
                selStart.first <=
                selEnd.first
            ) {

                selStart.second

            } else {

                selEnd.second
            }

        val endCol =
            if (
                selStart.first <=
                selEnd.first
            ) {

                selEnd.second

            } else {

                selStart.second
            }

        val sb =
            StringBuilder()

        for (
            scrollLine in startRow..endRow
        ) {

            if (scrollLine < 0) {
                continue
            }

            val line =
                lineAtScrollLine(scrollLine)

            if (
                line.isNotEmpty()
            ) {

                val from =
                    if (
                        scrollLine == startRow
                    ) {

                        startCol.coerceIn(
                            0,
                            line.size
                        )

                    } else {

                        0
                    }

                val toExclusive =
                    if (
                        scrollLine == endRow
                    ) {

                        (endCol + 1)
                            .coerceIn(
                                0,
                                line.size
                            )

                    } else {

                        line.size
                    }

                if (
                    from < toExclusive
                ) {

                    for (
                        col in
                        from until toExclusive
                    ) {

                        sb.append(
                            line[col].char
                        )
                    }
                }
            }

            if (
                scrollLine < endRow
            ) {

                sb.append('\n')
            }
        }

        return sb.toString()
    }

    /**
     * Resolve an absolute scroll line (scrollback + live buffer) to its cells,
     * using the same mapping the renderer uses in onDraw.
     */
    private fun lineAtScrollLine(
        scrollLine: Int
    ): List<TerminalEmulator.Cell> {

        val emulator =
            session?.emulator
                ?: return emptyList()

        return if (scrollLine < emulator.getScrollBackSize()) {

            emulator.getScrollBackLine(scrollLine)

        } else {

            val bufferRow =
                scrollLine - emulator.getScrollBackSize()

            if (bufferRow in 0 until emulator.getRows()) {

                emulator.getLine(bufferRow)

            } else {

                emptyList()
            }
        }
    }

    /** Drop the current selection and dismiss the floating toolbar. */
    private fun clearSelection() {

        selectionStart = null
        selectionEnd = null
        isSelecting = false
        finishSelectionActionMode()

        invalidate()
    }

    /** Show the standard Android floating selection toolbar (idempotent). */
    private fun startSelectionActionMode() {

        if (selectionActionMode != null) return

        selectionActionMode =
            startActionMode(selectionActionModeCallback, ActionMode.TYPE_FLOATING)
    }

    private fun finishSelectionActionMode() {

        selectionActionMode?.finish()
        selectionActionMode = null
    }

    private val selectionActionModeCallback = object : ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, android.R.id.copy, 0, "복사")
            menu.add(0, android.R.id.paste, 1, "붙여넣기")
            menu.add(0, android.R.id.selectAll, 2, "모두 선택")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                android.R.id.copy -> {
                    copySelection()
                    clearSelection()
                    true
                }
                android.R.id.paste -> {
                    pasteFromClipboard()
                    clearSelection()
                    true
                }
                android.R.id.selectAll -> {
                    // Keep the toolbar up so the user can copy afterwards.
                    selectAll()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selectionActionMode = null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Never leave a floating selection toolbar bound to a view that is gone.
        finishSelectionActionMode()
    }


    /*
     * ============================================================
     * Keyboard
     * ============================================================
     */

    private fun showKeyboard() {

        if (!hasFocus()) {
            requestFocus()
        }

        val imm =
            context.getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as? InputMethodManager

        imm?.showSoftInput(
            this,
            InputMethodManager.SHOW_FORCED
        )
    }

    private fun hideKeyboard() {

        val imm =
            context.getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as? InputMethodManager

        imm?.hideSoftInputFromWindow(
            windowToken,
            0
        )
    }

    /*
     * ============================================================
     * Clipboard
     * ============================================================
     */

    private fun copyTextToClipboard(
        text: String,
        showToast: Boolean
    ) {

        if (text.isBlank()) {
            return
        }

        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Terminal Selection",
                text
            )
        )

        if (showToast) {

            Toast.makeText(
                context,
                "복사됨",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun copySelection() {

        val text =
            getSelectedText()

        copyTextToClipboard(
            text,
            showToast = true
        )
    }

    fun pasteFromClipboard() {

        val clipboard =
            context.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        val clip =
            clipboard.primaryClip

        if (
            clip == null ||
            clip.itemCount <= 0
        ) {
            return
        }

        val text =
            clip
                .getItemAt(0)
                .text
                ?.toString()

        if (
            text.isNullOrEmpty()
        ) {
            return
        }

        isPasting = true

        try {

            /*
             * When the shell has bracketed paste mode on (?2004h) the terminal
             * must wrap the paste in ESC[200~ ... ESC[201~.  Otherwise a
             * multi-line paste is treated as ordinary keystrokes and each line
             * executes immediately instead of pasting as one block.
             */
            val bracketed = session?.emulator?.isBracketedPaste() == true
            val payload = if (bracketed) {
                val clean = text
                    .replace("\u001B[200~", "")
                    .replace("\u001B[201~", "")
                "\u001B[200~" + clean + "\u001B[201~"
            } else {
                text
            }

            /*
             * Do not localEcho pasted text.
             *
             * PTY/shell output keeps the emulator synchronized.
             */
            session?.sendRaw(payload)

        } finally {

            isPasting = false
        }

        /*
         * Drop the selection that spawned the paste (long-press → 붙여넣기):
         * leaving it active kept the highlight rectangle on screen over the
         * freshly inserted text.
         */
        if (
            selectionStart != null ||
            selectionEnd != null ||
            isSelecting
        ) {
            selectionStart = null
            selectionEnd = null
            isSelecting = false
            finishSelectionActionMode()
        }

        invalidate()
    }

    /*
     * ============================================================
     * Scrolling / clearing
     * ============================================================
     */

    fun resetScroll() {

        scrollY =
            maxScrollY

        isUserScrolling =
            false

        invalidate()
    }

    fun scrollToBottom() {

        scrollY =
            maxScrollY

        isUserScrolling =
            false

        invalidate()
    }

    fun clear() {

        session?.emulator?.clear()

        /*
         * The view is now blank, but the shell's own input buffer is not: it
         * still holds whatever was half-typed. Until the two agree, the next
         * thing the user types is echoed onto the shell's leftover line -- the
         * reported "clearls" merge. Ctrl-U (kill line) empties it, but only
         * when nothing is running that could interpret it as its own input.
         */
        endInputLine()
        if (session?.processTracker?.currentProcess == null) {
            session?.sendRaw("\u0015")
        }

        highlightCache.clear()

        selectionStart =
            null

        selectionEnd =
            null

        isSelecting = false
        finishSelectionActionMode()

        scrollY = 0
        maxScrollY = 0
        isUserScrolling = false

        dismissCompletion()

        invalidate()
    }

    fun selectAll() {

        val emulator =
            session?.emulator
                ?: return

        val rows =
            emulator.getRows()

        val cols =
            emulator.getCols()

        if (
            rows <= 0 ||
            cols <= 0
        ) {
            return
        }

        // Absolute scroll lines cover scrollback + the live buffer.
        val lastLine =
            emulator.getScrollBackSize() + rows - 1

        selectionStart =
            Pair(
                0,
                0
            )

        selectionEnd =
            Pair(
                lastLine,
                cols - 1
            )

        startSelectionActionMode()

        invalidate()
    }

    /*
     * ============================================================
     * Utility
     * ============================================================
     */

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                context.resources
                    .displayMetrics
                    .density
            )
            .toInt()
    }

    companion object {

        /*
         * Default:
         *     13px
         *
         * Pinch range:
         *     8px ~ 30px
         */
        private const val DEFAULT_FONT_SIZE = 13f
        private const val MIN_FONT_SIZE = 8f
        private const val MAX_FONT_SIZE = 30f

        /** Two Ctrl taps inside this window make the latch sticky. */
        private const val CTRL_DOUBLE_TAP_MS = 400L

        /*
         * The dynamic prompt is drawn only when the terminal has been idle
         * for a moment: no output within this window and no fresh input
         * (covers the gap between Enter and a command's first output byte,
         * when the cursor line is briefly empty).
         */
        private const val PROMPT_IDLE_OUTPUT_MS = 400L
        private const val PROMPT_IDLE_INPUT_MS = 250L

        /** Delay of the follow-up pass that redraws the prompt after idle. */
        private const val PROMPT_IDLE_REFRESH_MS = PROMPT_IDLE_OUTPUT_MS + 60L

        /**
         * Columns kept free after the dynamic prompt so the prompt and the
         * text the user types never collide, even at the narrowest width.
         */
        private const val PROMPT_TYPED_COLUMNS_RESERVED = 8

        /**
         * What the Enter key puts on the PTY.
         *
         * CR, not LF. A terminal's Enter has always been carriage return, and
         * the kernel's ICRNL is what turned it into the newline the shell
         * sees. Any program that puts the tty in raw mode - which it does by
         * switching ICANON off - loses that translation and reads the byte as
         * it arrives, so it must be the CR it is written to expect.
         *
         * Sending LF here meant the shell was fine while every interactive
         * prompt was not: `gh auth login`, `less`, `vim` and any Y/n confirm
         * read the key, see something that is not their enter, and ignore it.
         * The keys around it - arrows, y, n - still worked, which is what made
         * it look like a broken shell rather than one wrong byte.
         */
        private const val ENTER_KEY = "\r"

        /** Key codes with no Ctrl mapping: they release the latch. */
        private val CTRL_RELEASE_KEYCODES = setOf(
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN
        )

        /** Selection fill drawn behind the text — kept translucent. */
        private const val SELECTION_ALPHA = 0xA0
    }

}


/**
 * Clamps an erase request to what the shell's input line can actually honour.
 *
 * The IME's model of the line and the shell's real line drift apart at every
 * line boundary -- Enter, `clear`, a fresh prompt. Sending more backspaces
 * than the line holds is never useful, and the resulting divergence is what
 * made typed text appear merged with the previous command.
 */
internal fun boundedBackspaces(requested: Int, shellLineLength: Int): Int =
    requested.coerceAtLeast(0).coerceAtMost(shellLineLength.coerceAtLeast(0))
