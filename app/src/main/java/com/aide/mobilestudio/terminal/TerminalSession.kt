package com.aide.mobilestudio.terminal

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.PersistentShell
import com.aide.mobilestudio.ShellEnvironment
import kotlinx.coroutines.*
import java.io.File

/**
 * A terminal session wrapping PersistentShell with terminal emulation.
 *
 * Responsibilities:
 *
 *  - Shell lifecycle
 *  - Terminal emulator
 *  - Command buffering
 *  - Multi-line command handling
 *  - Prompt / cwd tracking
 *  - Process tracking
 *  - Raw terminal control input
 *  - Shell output processing
 *
 * TerminalCommandBuffer is responsible for deciding whether
 * a command is complete or needs more input.
 */
class TerminalSession(
    private val context: Context,
    private val id: String = "term-${System.currentTimeMillis()}",
    private val initialCwd: File? = null,
    private val title: String? = null
) {

    private val TAG = "TerminalSession"

    private val scope =
        CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var shell: PersistentShell? = null

    val emulator =
        TerminalEmulator(
            cols = 120,
            rows = 30
        )

    var state: State = State.CREATED
        private set

    var onOutput: ((String) -> Unit)? = null

    var onStateChange: ((State) -> Unit)? = null

    var onTitleChange: ((String) -> Unit)? = null

    val outputHistory =
        mutableListOf<String>()

    var currentTitle: String =
        title ?: "Shell"
        private set

    var exitCode: Int = 0
        private set

    // ---------------------------------------------------------------------
    // Terminal helpers
    // ---------------------------------------------------------------------

    val promptManager =
        TerminalPromptManager()

    val processTracker =
        ProcessStatusTracker()

    // ---------------------------------------------------------------------
    // Command editing state
    // ---------------------------------------------------------------------

    /**
     * Legacy line-editing buffer.
     *
     * This is kept because TerminalView may still use it indirectly.
     *
     * Multi-line command state is handled by TerminalCommandBuffer.
     */
    private val inputBuffer =
        StringBuilder()

    private var cursorPos = 0

    /**
     * Multi-line command buffer.
     *
     * This handles:
     *
     *   echo hello \
     *   world
     *
     * and:
     *
     *   echo "hello
     *   world"
     *
     * and:
     *
     *   if true; then
     *       echo hello
     *   fi
     *
     * as well as heredoc input.
     */
    private val commandBuffer =
        TerminalCommandBuffer()

    /**
     * Buffer for input typed before the shell is RUNNING.
     */
    private val pendingInput =
        StringBuilder()

    /**
     * Wall-clock of the last PTY output chunk and the last input send.
     * The view uses these to keep the dynamic prompt from flashing while a
     * command is still producing output (or between Enter and the first
     * byte of output, when the cursor line is briefly empty).
     */
    @Volatile
    var lastOutputAtMs: Long = 0L
        private set

    @Volatile
    var lastInputAtMs: Long = 0L
        private set

    enum class State {
        CREATED,
        STARTING,
        RUNNING,
        STOPPED,
        FAILED
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun start(): Boolean {

        if (
            state == State.RUNNING ||
            state == State.STARTING
        ) {
            return true
        }

        state = State.STARTING
        onStateChange?.invoke(state)

        scope.launch {

            try {

                val cwd =
                    initialCwd
                        ?: context.filesDir

                promptManager.setHomeDir(
                    context.filesDir.absolutePath
                )

                promptManager.setCwd(
                    cwd.absolutePath
                )

                val env =
                    withContext(Dispatchers.IO) {
                        ShellEnvironment.buildInitialEnvironment(
                            context,
                            cwd
                        )
                    }

                val newShell =
                    PersistentShell(
                        context,
                        env,
                        cwd,
                        promptString = ""
                    )

                val shellStarted =
                    withContext(Dispatchers.IO) {
                        newShell.start()
                    }

                if (!shellStarted) {

                    Log.e(
                        TAG,
                        "Shell process failed to start!"
                    )

                    withContext(Dispatchers.Main) {

                        state = State.FAILED

                        onStateChange?.invoke(state)
                    }

                    return@launch
                }

                this@TerminalSession.shell =
                    newShell

                // ---------------------------------------------------------
                // Output callback
                // ---------------------------------------------------------

                newShell.onOutputCallback = { text ->

                    /*
                     * Generic fallback for built-ins that report through this
                     * callback instead of the PTY — feed the emulator here as
                     * well, otherwise the text never reaches the screen. The
                     * `tools` builtin now joins the shared output channel
                     * instead, so its output flows through the same reader
                     * path (marker extraction included).
                     */
                    emulator.processString(text)

                    onOutput?.invoke(text)
                }

                /*
                 * The shell process died (exit, crash, kill).  Clear the
                 * status overlay; PersistentShell restarts the shell in the
                 * same directory unless the session itself was stopped.
                 */
                newShell.onShellExited = { _, _ ->
                    processTracker.clearStatus()
                }

                newShell.onShellFailed = { _, _ ->
                    scope.launch(Dispatchers.Main) {
                        if (state == State.RUNNING) {
                            state = State.FAILED
                            onStateChange?.invoke(state)
                        }
                    }
                }

                // ---------------------------------------------------------
                // Flush input typed during startup
                // ---------------------------------------------------------

                if (pendingInput.isNotEmpty()) {

                    newShell.sendRaw(
                        pendingInput.toString()
                    )

                    pendingInput.clear()
                }

                // ---------------------------------------------------------
                // Start output reader
                // ---------------------------------------------------------

                launch {
                    readOutputLoop()
                }

                // ---------------------------------------------------------
                // Shell configuration
                // ---------------------------------------------------------

                delay(100)

                sendRaw(
                    "export TERM=xterm-256color\n"
                )

                sendRaw(
                    "export LANG=en_US.UTF-8\n"
                )

                /*
                 * Do NOT disable monitor mode here.  With job control the
                 * shell puts every command in its own process group, so
                 * Ctrl+C (SIGINT from the line discipline) only reaches the
                 * foreground process and never the shell itself.
                 */

                /*
                 * Configure PS2.
                 *
                 * Bash/sh uses PS2 when it needs another
                 * line of input.
                 *
                 * TerminalCommandBuffer also knows about
                 * continuation state, so both sides use
                 * the same visible prompt.
                 */
                sendRaw(
                    "export PS2='> '\n"
                )

                // Only now report RUNNING: callers (sendCommand, the
                // self-test harness) race ahead of the configuration lines
                // above otherwise, so `export PS2=...` can interleave into
                // the middle of their first command.
                withContext(Dispatchers.Main) {

                    state = State.RUNNING

                    onStateChange?.invoke(state)
                }

                Log.i(
                    TAG,
                    "Session $id started at ${cwd.absolutePath}"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to start session $id",
                    e
                )

                withContext(Dispatchers.Main) {

                    state = State.FAILED

                    onStateChange?.invoke(state)
                }
            }
        }

        return true
    }

    // =====================================================================
    // Output
    // =====================================================================

    private suspend fun readOutputLoop() {

        val currentShell =
            shell ?: run {

                Log.e(
                    TAG,
                    "readOutputLoop: shell is null!"
                )

                return
            }

        Log.i(
            TAG,
            "readOutputLoop started for session $id"
        )

        /*
         * Consumes one PTY chunk: marker extraction, process-status update
         * and emulator feed.  Returns the cleaned text ("" for chunks that
         * contained only marker lines).
         */
        fun consume(chunk: String): String {
            val clean = extractMarkers(chunk)

            val marker = pendingMarker
            if (marker != null) {
                pendingMarker = null

                marker.exitCode?.let { commandExitCode ->

                    processTracker.endProcess(commandExitCode)

                    exitCode = commandExitCode
                }

                marker.cwd?.let { path ->
                    promptManager.setCwd(path)
                    currentShell.updateCwd(File(path))
                }
            }

            if (clean.isNotEmpty()) {
                emulator.processString(clean)
            }
            return clean
        }

        try {

            for (chunk in currentShell.output) {

                lastOutputAtMs = System.currentTimeMillis()

                if (
                    !currentCoroutineContext().isActive
                ) {
                    break
                }

                val delivered = StringBuilder()

                val first = consume(chunk)
                if (first.isNotEmpty()) delivered.append(first)

                /*
                 * Flood protection: drain everything already buffered so a
                 * burst of output costs a single main-thread hop and a
                 * single view refresh instead of one per line.  Every chunk
                 * must be accumulated — overwriting kept only the last one
                 * and silently dropped the rest (missing command output,
                 * lost marker side effects for listeners).
                 */
                while (true) {
                    val next = currentShell.output.tryReceive().getOrNull() ?: break

                    lastOutputAtMs = System.currentTimeMillis()

                    val cleaned = consume(next)
                    if (cleaned.isNotEmpty()) delivered.append(cleaned)
                }

                if (delivered.isNotEmpty()) {
                    val text = delivered.toString()
                    withContext(Dispatchers.Main) {

                        onOutput?.invoke(text)
                    }
                }
            }

        } catch (
            e: CancellationException
        ) {

            // Normal cancellation.

        } catch (
            e: Exception
        ) {

            if (
                state == State.RUNNING
            ) {

                Log.e(
                    TAG,
                    "Output read error in session $id",
                    e
                )
            }
        }
    }

    // =====================================================================
    // Internal marker handling
    // =====================================================================

    /**
     * One `STUDIO_CMD_DONE <code> STUDIO_PWD_<path>` line produced after
     * every command.  It is stripped from the display and reported here.
     */
    private data class CmdMarker(
        val exitCode: Int?,
        val cwd: String?
    )

    private val markerBuffer = StringBuilder()
    /** First unconsumed index in [markerBuffer]; one bulk delete per chunk instead of one per line. */
    private var markerScan = 0
    private var pendingMarker: CmdMarker? = null

    /**
     * Removes the internal marker lines from [chunk] and records their
     * values.  Handles markers split across two PTY reads.
     *
     * @return the text that is safe to feed to the emulator.
     */
    private fun extractMarkers(chunk: String): String {
        markerBuffer.append(chunk)

        val out = StringBuilder(chunk.length)
        while (true) {
            val nl = markerBuffer.indexOf("\n", markerScan)
            if (nl < 0) break

            val line = markerBuffer.substring(markerScan, nl)
            markerScan = nl + 1

            val markerIndex = line.indexOf(MARKER_START)
            if (markerIndex >= 0) {
                val parsed = parseMarkerLine(line.substring(markerIndex))
                if (parsed != null) {
                    pendingMarker = parsed
                    // Keep whatever the command printed before the marker.
                    val prefix = line.substring(0, markerIndex)
                    if (prefix.isNotEmpty()) out.append(prefix).append('\n')
                    continue
                }
            }
            out.append(line).append('\n')
        }

        // Incomplete tail: only hold it back when it may still grow into a
        // marker, otherwise streaming output would stall.
        val tail = markerBuffer.substring(markerScan)
        if (!couldBeMarker(tail)) {
            out.append(tail)
            markerBuffer.setLength(0)
            markerScan = 0
        } else if (markerScan > 0) {
            markerBuffer.delete(0, markerScan)
            markerScan = 0
        }

        return out.toString()
    }

    private fun parseMarkerLine(text: String): CmdMarker? {
        val m = MARKER_LINE.matchEntire(text) ?: return null
        val code = m.groupValues[1].toIntOrNull()
        val cwd = m.groupValues[2].trim().takeIf { it.isNotEmpty() }
        return if (code == null && cwd == null) null else CmdMarker(code, cwd)
    }

    /** True while [text] is still a possible prefix of a marker line. */
    private fun couldBeMarker(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.contains(MARKER_START)) return true
        val start = FULL_MARKER
        for (len in start.length - 1 downTo 5) {
            if (text.length >= len && text.endsWith(start.substring(0, len))) {
                val at = text.length - len
                if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            }
        }
        return false
    }

    // =====================================================================
    // Command handling
    // =====================================================================

    /**
     * Accept one complete terminal line.
     *
     * IMPORTANT:
     *
     * TerminalView should call this method when the user
     * presses Enter.
     *
     * It should NOT call this for Ctrl+C, arrows, Tab, etc.
     *
     * Examples:
     *
     *   ls
     *
     * executes immediately.
     *
     *   echo hello \
     *
     * waits for another line.
     *
     *   echo "hello
     *
     * waits for closing quote.
     */
    fun sendCommand(command: String) {

        if (
            state != State.RUNNING
        ) {

            Log.w(
                TAG,
                "sendCommand blocked: state=$state"
            )

            return
        }

        Log.d(
            TAG,
            "sendCommand input: " +
                command
                    .replace(
                        "\n",
                        "\\n"
                    )
                    .replace(
                        "\r",
                        "\\r"
                    )
        )

        /*
         * Pass the line to TerminalCommandBuffer.
         */
        val result =
            commandBuffer.accept(
                command
            )

        // -------------------------------------------------------------
        // Command is incomplete
        // -------------------------------------------------------------

        if (!result.isComplete) {

            Log.d(
                TAG,
                "Command continuation: " +
                    "reason=${result.reason}, " +
                    "lines=${result.lineCount}, " +
                    "heredoc=${result.isHeredoc}"
            )

            /*
             * Do NOT execute yet.
             *
             * We let TerminalCommandBuffer manage
             * the logical state.
             *
             * The actual shell may also emit PS2.
             *
             * We do not send the prompt into the PTY.
             * It is UI/output state only.
             */
            onContinuationChanged(
                result
            )

            return
        }

        // -------------------------------------------------------------
        // Complete command
        // -------------------------------------------------------------

        val finalCommand =
            commandBuffer.normalize(
                result.command ?: ""
            )

        if (
            finalCommand.isBlank()
        ) {

            clearInputState()

            return
        }

        Log.d(
            TAG,
            "Executing command: " +
                finalCommand
                    .replace(
                        "\n",
                        "\\n"
                    )
        )

        // -------------------------------------------------------------
        // Prompt / cwd tracking
        // -------------------------------------------------------------

        promptManager.updateFromCommand(
            finalCommand
        )

        // -------------------------------------------------------------
        // Process tracking
        // -------------------------------------------------------------

        processTracker.startProcess(
            finalCommand
        )

        // -------------------------------------------------------------
        // Send command to shell
        // -------------------------------------------------------------

        /*
         * Preserve newlines.
         *
         * This is important for:
         *
         *   if true; then
         *       echo hello
         *   fi
         *
         * and heredoc commands.
         */
        val commandWithMarker =
            buildCommandWithCompletionMarker(
                finalCommand
            )

        shell?.sendCommand(
            commandWithMarker,
            historyEntry = finalCommand
        )

        clearInputState()
    }

    /**
     * Adds the completion marker used by ProcessStatusTracker.
     */
    private fun buildCommandWithCompletionMarker(
        command: String
    ): String {

        /*
         * Wrap the command in a shell group.
         *
         * This allows multi-line shell syntax to remain
         * one logical command.
         *
         * Example:
         *
         * {
         *   echo hello
         * }
         *
         * Afterwards the exit code and the working directory are reported
         * on a single line that extractMarkers() removes again, so the
         * terminal never shows internal bookkeeping.
         */
        return buildString {

            append("{\n")

            append(command)

            append("\n}")

            append("; __st=\$?; ")

            append(
                "echo \"STUDIO_CMD_DONE \$__st STUDIO_PWD_\$(pwd)\""
            )
        }
    }

    /**
     * Called when TerminalCommandBuffer determines
     * that another line is required.
     */
    private fun onContinuationChanged(
        result: TerminalCommandBuffer.Result
    ) {

        /*
         * We intentionally don't write the prompt into
         * PersistentShell.
         *
         * The shell can also generate PS2 itself.
         *
         * This callback exists so TerminalView can display
         * a UI continuation prompt if desired.
         */
        Log.d(
            TAG,
            "Continuation prompt: " +
                result.prompt
        )

        /*
         * TerminalView can listen to onOutput if it wants
         * to display this.
         *
         * Do NOT send it to shell.
         */
    }

    /**
     * Cancel current multi-line input.
     *
     * Used by Ctrl+C / Escape.
     */
    fun cancelCommandBuffer() {

        if (
            commandBuffer.hasPendingInput()
        ) {

            val discarded =
                commandBuffer.cancel()

            Log.d(
                TAG,
                "Cancelled buffered command: " +
                    discarded
                        .replace(
                            "\n",
                            "\\n"
                        )
            )
        }

        clearInputState()
    }

    /**
     * Returns true if the current input is waiting
     * for another line.
     */
    fun isCommandContinuation(): Boolean {
        return commandBuffer.isContinuation
    }

    /**
     * Returns true if the current input is a heredoc.
     */
    fun isHeredocInput(): Boolean {
        return commandBuffer.isHeredoc
    }

    /**
     * Get current buffered command.
     */
    fun getBufferedCommand(): String {
        return commandBuffer.currentBuffer()
    }

    /**
     * Get command buffer state.
     */
    fun getCommandBufferState():
        TerminalCommandBuffer.State {

        return commandBuffer.getState()
    }

    /**
     * Debug command buffer.
     */
    fun getCommandBufferDebugState(): String {

        return commandBuffer.debugState()
    }

    /**
     * Reset line editing state.
     */
    private fun clearInputState() {

        inputBuffer.clear()

        cursorPos = 0
    }

    // =====================================================================
    // Raw terminal input
    // =====================================================================

    /**
     * Send raw terminal data.
     *
     * This bypasses TerminalCommandBuffer.
     *
     * Use this for:
     *
     *  - Ctrl+C
     *  - Ctrl+Z
     *  - Ctrl+D
     *  - Ctrl+U
     *  - Ctrl+W
     *  - Ctrl+A
     *  - Ctrl+E
     *  - Ctrl+R
     *  - arrows
     *  - terminal escape sequences
     *  - paste
     *
     * NOT for completed Enter commands.
     */
    fun sendRaw(text: String) {

        lastInputAtMs = System.currentTimeMillis()

        if (
            state == State.STARTING
        ) {

            pendingInput.append(
                text
            )

            return
        }

        if (
            state != State.RUNNING
        ) {

            Log.w(
                TAG,
                "sendRaw blocked: " +
                    "state=$state, " +
                    "text=${text.take(20)}"
            )

            return
        }

        if (
            pendingInput.isNotEmpty()
        ) {

            shell?.sendRaw(
                pendingInput.toString()
            )

            pendingInput.clear()
        }

        Log.d(
            TAG,
            "sendRaw: " +
                text
                    .take(40)
                    .replace(
                        "\n",
                        "\\n"
                    )
                    .replace(
                        "\r",
                        "\\r"
                    )
        )

        if (shell?.sendRaw(text) != true) {
            /*
             * The PTY stalled or closed mid-write. PersistentShell keeps the
             * unwritten remainder queued, so nothing is lost here -- but log
             * it, because a silently lost Enter is what made the shell keep
             * the previous command on its input line while the next command
             * was appended to it on screen.
             */
            Log.w(TAG, "sendRaw incomplete: ${text.take(20)}")
        }
    }

    // =====================================================================
    // EOF / Signals
    // =====================================================================

    fun sendEof() {

        /*
         * Ctrl+D while entering a multi-line command
         * should first cancel the logical buffer.
         */
        if (
            commandBuffer.hasPendingInput()
        ) {

            cancelCommandBuffer()

            return
        }

        shell?.sendEof()
    }

    fun sendInterrupt() {

        /*
         * If a multi-line command is being built,
         * cancel it first.
         *
         * Don't leave the buffer active after Ctrl+C.
         */
        if (
            commandBuffer.hasPendingInput()
        ) {

            commandBuffer.cancel()
        }

        shell?.sendInterrupt()

        processTracker.cancelProcess()

        clearInputState()

        onOutput?.invoke(
            "^C\n"
        )
    }

    fun sendSuspend() {

        shell?.sendSuspend()
    }

    fun sendQuit() {

        shell?.sendQuit()
    }

    fun sendLineKill() {

        /*
         * Clear logical multi-line input too.
         */
        if (
            commandBuffer.hasPendingInput()
        ) {

            commandBuffer.clear()
        }

        shell?.sendKill()

        clearInputState()

        onOutput?.invoke(
            "^K\n"
        )
    }

    // =====================================================================
    // Job control
    // =====================================================================

    fun getJobs():
        List<PersistentShell.BackgroundJob> {

        return shell?.getJobs()
            ?: emptyList()
    }

    fun fgJob(
        jobId: Int
    ): Boolean {

        return shell?.fgJob(
            jobId
        ) ?: false
    }

    fun bgJob(
        jobId: Int
    ): Boolean {

        return shell?.bgJob(
            jobId
        ) ?: false
    }

    fun killJob(
        jobId: Int
    ): Boolean {

        return shell?.killJob(
            jobId
        ) ?: false
    }

    fun jobsFormatted(): String {

        return shell?.jobsFormatted()
            ?: "No shell session."
    }

    // =====================================================================
    // Completion
    // =====================================================================

    fun getCompletions(
        partial: String
    ): List<String> {

        return shell?.getCompletions(
            partial
        ) ?: emptyList()
    }

    // =====================================================================
    // Input handling
    // =====================================================================

    fun handleKeyDown(
        keyCode: Int,
        shift: Boolean,
        ctrl: Boolean,
        alt: Boolean
    ): Boolean {

        when {

            // -------------------------------------------------------------
            // Ctrl+C
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_C -> {

                sendInterrupt()

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+Z
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_Z -> {

                sendSuspend()

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+D
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_D -> {

                sendEof()

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+K
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_K -> {

                sendLineKill()

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+L
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_L -> {

                /*
                 * The raw 0x0C must reach the shell so readline and
                 * friends can run their own clear-screen handling.
                 */
                sendRaw(
                    "\u000C"
                )

                emulator.clear()

                onOutput?.invoke(
                    "\u001B[H\u001B[2J"
                )

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+U
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_U -> {

                /*
                 * Ctrl+U is a shell line-editing command.
                 *
                 * Do not pass it through CommandBuffer.
                 */
                sendRaw(
                    "\u0015"
                )

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+W
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_W -> {

                sendRaw(
                    "\u0017"
                )

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+A
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_A -> {

                sendRaw(
                    "\u0001"
                )

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+E
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_E -> {

                sendRaw(
                    "\u0005"
                )

                return true
            }

            // -------------------------------------------------------------
            // Ctrl+R
            // -------------------------------------------------------------

            ctrl &&
                keyCode ==
                android.view.KeyEvent.KEYCODE_R -> {

                sendRaw(
                    "\u0012"
                )

                return true
            }

            // -------------------------------------------------------------
            // Tab
            // -------------------------------------------------------------

            keyCode ==
                android.view.KeyEvent.KEYCODE_TAB -> {

                sendHorizontalTab()

                return true
            }
        }

        return false
    }

    // =====================================================================
    // Terminal controls
    // =====================================================================

    /**
     * Sends a Ctrl+<key> control code to the shell.
     *
     * The well-known codes keep their dedicated path so local state
     * (command buffer, process tracker) stays consistent, but every code
     * reaches the PTY as the raw byte: 0x03, 0x04, 0x1A, 0x0C, 0x12, ...
     */
    fun sendControlChar(code: Int) {

        when (code) {

            0x03 -> sendInterrupt()
            0x04 -> sendEof()
            0x1A -> sendSuspend()
            0x0B -> sendLineKill()

            else -> sendRaw(
                code.toChar().toString()
            )
        }
    }

    fun sendHorizontalTab() {

        shell?.sendHorizontalTab()
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    fun stop() {

        if (
            state == State.STOPPED
        ) {
            return
        }

        /*
         * Clear command state before stopping.
         */
        commandBuffer.clear()

        clearInputState()

        state = State.STOPPED

        scope.cancel()

        shell?.stop()

        onStateChange?.invoke(
            state
        )

        Log.i(
            TAG,
            "Session $id stopped"
        )
    }

    fun destroy() {

        commandBuffer.clear()

        stop()

        shell?.destroy()
    }

    // =====================================================================
    // Resize
    // =====================================================================

    fun resize(
        cols: Int,
        rows: Int
    ) {

        if (cols <= 0 || rows <= 0) {
            return
        }

        emulator.resize(
            cols,
            rows
        )

        shell?.resize(
            cols,
            rows
        )
    }

    // =====================================================================
    // State / information
    // =====================================================================

    fun isAlive(): Boolean {

        return shell?.isAlive() == true
    }

    fun getHistory(): List<String> {

        return shell?.getHistory()
            ?: outputHistory
    }

    fun getId(): String {

        return id
    }

    fun updateTitle(
        newTitle: String
    ) {

        currentTitle = newTitle

        onTitleChange?.invoke(
            newTitle
        )
    }

    companion object {
        /** First token of the internal per-command marker line. */
        private const val MARKER_START = "STUDIO_CMD_DONE"

        /** Full example line: STUDIO_CMD_DONE 0 STUDIO_PWD_/data/data/x */
        private const val FULL_MARKER = "STUDIO_CMD_DONE 0 STUDIO_PWD_/data/data/x"

        /** Matches a complete marker line (cwd may contain spaces). */
        private val MARKER_LINE =
            Regex("^STUDIO_CMD_DONE\\s+(-?\\d+)(?:\\s+STUDIO_PWD_(.*))?\\s*$")
    }
}