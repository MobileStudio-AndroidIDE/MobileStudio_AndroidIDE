package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import java.io.File

class PersistentShell(
    private val context: Context,
    private val initialEnv: Map<String, String>,
    private val initialCwd: File,
    private val promptString: String = "\u001B[38;5;214mMobileStudio\u001B[0m \u001B[1m>\u001B[0m ",
    private val continuationPrompt: String = "> "
) {
    private val TAG = "PersistentShell"
    private val prefs = context.getSharedPreferences("shell_history", Context.MODE_PRIVATE)
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
    private val outputChannel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val currentCwd = java.util.concurrent.atomic.AtomicReference<File>(initialCwd)
    private val lastExitCode = java.util.concurrent.atomic.AtomicReference<Int>(0)

    // Native PTY — real pseudo-terminal for proper line buffering.
    // Volatile: written by the reader/respawn thread, read by the UI thread.
    @Volatile
    private var pty: com.aide.mobilestudio.terminal.NativePty? = null

    // Output reader coroutine, so restart()/start() never runs two readers.
    @Volatile
    private var loopJob: kotlinx.coroutines.Job? = null

    /**
     * Invoked when the shell process terminates, before any restart attempt.
     * code >= 0 exit status (128+signal when killed), signal = fatal signal (0 = normal).
     */
    var onShellExited: ((code: Int, signal: Int) -> Unit)? = null

    /** Invoked when the shell could not be restarted after it exited. */
    var onShellFailed: ((code: Int, signal: Int) -> Unit)? = null

    // Tab-completion cache of executables found in PATH.
    @Volatile
    private var pathCommandCache: List<String> = emptyList()
    @Volatile
    private var pathCommandCacheAt = 0L


    // Output callback for built-in commands
    var onOutputCallback: ((String) -> Unit)? = null

    // Job control: track background jobs
    data class BackgroundJob(val id: Int, val pid: Int, val command: String, var state: JobState)
    enum class JobState { RUNNING, STOPPED, DONE }
    private val jobs = mutableListOf<BackgroundJob>()
    private var nextJobId = 1
    private val lock = Any()

    /*
     * Input typed while the shell is between two processes (restart after
     * exit/crash).  Dropping it silently would leave ProcessStatusTracker
     * waiting for a marker that never comes, so it is queued and flushed
     * to the new shell as soon as the PTY is up.
     */
    private val pendingWrites = ArrayDeque<String>()
    private val writeLock = Any()

    fun start(): Boolean {
        return try {
            stopRequested.set(false)

            if (!launchShell()) {
                Log.e(TAG, "Native PTY failed to start shell")
                return false
            }

            loadHistory()

            // Read output loop — reads directly from PTY master.
            loopJob?.cancel()
            loopJob = scope.launch { readOutputLoop() }

            Log.i(TAG, "Shell started with native PTY at ${currentCwd.get().absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent shell", e)
            false
        }
    }

    /**
     * Environment for a (re)started shell.  Everything the terminal needs
     * (TERM, PS1/PS2, PATH, ...) lives here so a restarted shell comes back
     * in the same usable state without extra commands on the PTY.
     */
    private fun buildEnvironment(): Map<String, String> {
        val envMap = mutableMapOf<String, String>()
        envMap.putAll(initialEnv)
        if (!envMap.containsKey("PATH")) {
            envMap["PATH"] = "/system/bin:/system/xbin:/data/data/com.aide.mobilestudio/files/toolchain/runtime-tools/bin"
        }
        val path = envMap["PATH"] ?: ""
        if (!path.contains("/system/bin")) {
            envMap["PATH"] = "$path:/system/bin:/system/xbin"
        }
        if (!envMap.containsKey("LD_LIBRARY_PATH")) {
            envMap["LD_LIBRARY_PATH"] = "/system/lib64:/system/lib"
        }
        if (!envMap.containsKey("HOME")) {
            envMap["HOME"] = context.filesDir.absolutePath
        }
        if (!envMap.containsKey("TERM")) {
            envMap["TERM"] = "xterm-256color"
        }
        if (!envMap.containsKey("SHELL")) {
            envMap["SHELL"] = "/system/bin/sh"
        }
        // Empty PS1: the UI draws the prompt itself (cwd aware).
        if (!envMap.containsKey("PS1")) {
            envMap["PS1"] = ""
        }
        // Continuation prompt for multi-line input.
        if (!envMap.containsKey("PS2")) {
            envMap["PS2"] = "> "
        }
        // Shell init script (sourced silently by the interactive shell): the
        // `tools` builtin lives app-side, so commands TYPED in the terminal
        // resolve through this shell function stub and report their arguments
        // back via the __TOOLS_HOOK__ line that readOutputLoop intercepts.
        // Sourcing (not typing) means the kernel echo never shows it.
        runCatching { envMap["ENV"] = prepareShellInitScript().absolutePath }
        envMap["LANG"] = "en_US.UTF-8"
        return envMap
    }

    /**
     * Start (or restart) the shell process in the current working directory.
     */
    private fun launchShell(): Boolean {
        return try {
            val envMap = buildEnvironment()

            // Prefer the tracked cwd, fall back to the initial one if it vanished.
            var cwd = currentCwd.get()
            if (cwd == null || !cwd.isDirectory) cwd = initialCwd

            val ptyInstance = com.aide.mobilestudio.terminal.NativePty()
            val started = ptyInstance.start("/system/bin/sh", cwd.absolutePath, envMap)
            if (!started) {
                Log.e(TAG, "Native PTY failed to start shell")
                return false
            }

            // stop() may have won the race while we were forking.
            if (stopRequested.get()) {
                ptyInstance.close()
                return false
            }

            synchronized(writeLock) {
                pty = ptyInstance
                isRunning.set(true)
                flushPendingWrites()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch shell", e)
            false
        }
    }

    // Hook lines from the shell-side `tools()` stub (__TOOLS_HOOK__ <args>)
    // are handled app-side and never reach the display; everything else flows
    // to the shared output channel untouched.
    private val hookBuffer = StringBuilder()
    /** First unconsumed index in [hookBuffer]; one bulk delete per chunk instead of one per line. */
    private var hookScan = 0

    /**
     * Line-aware PTY output processing. Lines starting with
     * [TOOLS_HOOK_PREFIX] are dispatched to the tools builtin and hidden;
     * all other text is forwarded to the shared output channel (line
     * splitting only — chunk boundaries preserved for partial lines).
     */
    private fun processPtyOutput(text: String) {
        Log.d(
            TAG,
            "pty chunk: " + text.take(400)
                .replace("\r", "\\r").replace("\n", "\\n")
        )
        hookBuffer.append(text)
        while (true) {
            val nl = hookBuffer.indexOf("\n", hookScan)
            if (nl < 0) break
            val line = hookBuffer.substring(hookScan, nl)
            hookScan = nl + 1
            val trimmed = line.trim()
            if (trimmed.startsWith(TOOLS_HOOK_PREFIX)) {
                val args = trimmed.removePrefix(TOOLS_HOOK_PREFIX).trim()
                handleToolsCommand(if (args.isEmpty()) "tools" else "tools $args")
                continue
            }
            outputChannel.trySend(line + "\n")
        }
        val tail = hookBuffer.substring(hookScan)
        if (!couldBeHookLine(tail)) {
            outputChannel.trySend(tail)
            hookBuffer.setLength(0)
            hookScan = 0
        } else if (hookScan > 0) {
            hookBuffer.delete(0, hookScan)
            hookScan = 0
        }
    }

    /** True while [tail] may still grow into a hook line (hold it back). */
    private fun couldBeHookLine(tail: String): Boolean {
        if (tail.isEmpty()) return false
        return tail.startsWith(TOOLS_HOOK_PREFIX) || TOOLS_HOOK_PREFIX.startsWith(tail)
    }

    private suspend fun readOutputLoop() {
        Log.i(TAG, "readOutputLoop started (native PTY)")

        try {
            while (isRunning.get() && !stopRequested.get()) {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) break

                val p = pty ?: break

                val text = try {
                    p.read()
                } catch (e: Exception) {
                    Log.e(TAG, "PTY read error", e)
                    null
                }

                if (text != null) {
                    processPtyOutput(text)
                    continue
                }

                /*
                 * No data.  Either a poll timeout, EOF/EIO from the PTY, or
                 * the shell really exited.  isAlive() reaps the child and
                 * caches its status, so getExitCode() afterwards returns the
                 * real exit code instead of -1.
                 */
                val alive = try {
                    p.isAlive()
                } catch (e: Exception) {
                    false
                }

                if (!alive) {
                    if (stopRequested.get() || !isRunning.get()) break
                    if (!handleShellExit(p)) break
                    continue
                }

                // Shell alive but silent — short nap, the native poll already
                // blocked for a few milliseconds.
                Thread.sleep(2)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (isRunning.get()) Log.e(TAG, "Output read error", e)
        }

        Log.i(TAG, "Output loop ended")
    }

    /**
     * A shell process died.  Report the real cause, then restart the shell
     * in the same directory so the tab/session keeps working.
     *
     * @return true when a new shell is up and the read loop may continue.
     */
    private fun handleShellExit(dead: com.aide.mobilestudio.terminal.NativePty): Boolean {
        val signal = try { dead.getExitSignal() } catch (e: Exception) { 0 }
        val code = try { dead.getExitCode() } catch (e: Exception) { -1 }
        lastExitCode.set(code)

        Log.i(TAG, "Shell process exited: code=$code signal=$signal")

        try {
            onShellExited?.invoke(code, signal)
        } catch (t: Throwable) {
            Log.e(TAG, "onShellExited callback failed", t)
        }

        emitExitMessage(code, signal)

        try {
            dead.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close dead PTY", t)
        }
        if (pty === dead) pty = null

        // Explicit stop()/destroy(): do not bring the shell back.
        if (stopRequested.get() || !isRunning.get()) return false

        return if (launchShell()) {
            Log.i(TAG, "Shell restarted in ${currentCwd.get().absolutePath}")
            true
        } else {
            try {
                onShellFailed?.invoke(code, signal)
            } catch (t: Throwable) {
                Log.e(TAG, "onShellFailed callback failed", t)
            }
            outputChannel.trySend("\r\n[Shell exited; restart failed]\r\n")
            isRunning.set(false)
            false
        }
    }

    /**
     * Human readable exit cause.
     *  - normal exit 0   -> silent (prompt simply returns)
     *  - normal exit !=0 -> "[Process exited with code N]"
     *  - killed by signal-> "[Process terminated by signal N (SIGNAME)]"
     */
    private fun emitExitMessage(code: Int, signal: Int) {
        if (signal > 0) {
            outputChannel.trySend(
                "\r\n[Process terminated by signal $signal (${signalName(signal)})]\r\n"
            )
        } else if (code != 0) {
            outputChannel.trySend("\r\n[Process exited with code $code]\r\n")
        }
    }

    private fun signalName(signal: Int): String = when (signal) {
        1 -> "SIGHUP"; 2 -> "SIGINT"; 3 -> "SIGQUIT"; 4 -> "SIGILL"; 5 -> "SIGTRAP"
        6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 10 -> "SIGUSR1"
        11 -> "SIGSEGV"; 12 -> "SIGUSR2"; 13 -> "SIGPIPE"; 14 -> "SIGALRM"; 15 -> "SIGTERM"
        16 -> "SIGSTKFLT"; 17 -> "SIGCHLD"; 18 -> "SIGCONT"; 19 -> "SIGSTOP"; 20 -> "SIGTSTP"
        21 -> "SIGTTIN"; 22 -> "SIGTTOU"; 24 -> "SIGXCPU"; 25 -> "SIGXFSZ"
        30 -> "SIGPWR"; 31 -> "SIGSYS"
        else -> "SIG$signal"
    }

    /**
     * Send one command line to the shell.
     * @param historyEntry text stored in history; defaults to [command].
     *   TerminalSession passes the clean command while [command] itself
     *   still carries the internal completion marker.
     */
    fun sendCommand(command: String, historyEntry: String? = null): Boolean {
        addToHistory(historyEntry ?: command)

        // Intercept built-in commands. TerminalSession wraps every command in
        // the internal completion group (`{ cmd\n}; __st=$?; echo ...`), so
        // unwrap it first — otherwise `tools` typed in the terminal is never
        // recognized here.
        val trimmed = command.trim()
        val candidate = unwrapCompletionGroup(trimmed) ?: trimmed
        if (candidate == "tools" || candidate.startsWith("tools ")) {
            handleToolsCommand(candidate)
            return true
        }

        return sendRaw("$command\n")
    }

    /**
     * `tools` builtin — dynamic StudioShellTools manager. Tools, versions and
     * checksums come from the GitHub Releases of StudioShellTools via
     * StudioToolsIndex (cache-first, 6h TTL); nothing is hardcoded. Sync
     * subcommands read the cached index only (no network on the caller's
     * thread); install/update/refresh do their network work on a background
     * thread. Every branch ends with [finishToolsCommand] so
     * ProcessStatusTracker resolves the status overlay.
     */
    private fun handleToolsCommand(command: String) {
        val parts = command.split("\\s+".toRegex())
        val subcommand = parts.getOrNull(1)?.lowercase() ?: "help"
        val arg = parts.getOrNull(2)

        when (subcommand) {
            "list" -> toolsList()
            "search" -> toolsSearch(arg)
            "info" -> toolsInfo(arg)
            "install" -> toolsInstall(arg)
            "remove" -> toolsRemove(arg)
            "installed" -> toolsInstalled()
            "update" -> toolsUpdate()
            "verify" -> toolsVerify(arg)
            "refresh" -> toolsRefresh()
            "help", "" -> toolsHelp()
            else -> {
                sendOutput("Unknown subcommand: $subcommand")
                sendOutput("Usage: tools [${TOOLS_SUBCOMMANDS.joinToString("|")}]")
                finishToolsCommand(1)
            }
        }
    }

    private fun toolsList() {
        val index = StudioToolsIndex.loadCached(context)
        if (index.isEmpty) {
            sendOutput("No tool index yet — run 'tools refresh' (needs network).")
            finishToolsCommand(1)
            return
        }
        sendOutput("StudioShellTools (GitHub releases) — ${index.tools.size} tools:")
        for (entry in index.tools) {
            val status = if (StudioShellTools.isInstalled(context, entry.name)) "[installed]" else "[available]"
            sendOutput("  ${entry.name} - ${entry.version.ifBlank { "?" }} $status")
        }
        if (index.stale) sendOutput(indexAgeNote(index))
        finishToolsCommand(0)
    }

    private fun toolsSearch(query: String?) {
        if (query.isNullOrBlank()) {
            sendOutput("Usage: tools search <keyword>")
            finishToolsCommand(1)
            return
        }
        val index = StudioToolsIndex.loadCached(context)
        if (index.isEmpty) {
            sendOutput("No tool index yet — run 'tools refresh' (needs network).")
            finishToolsCommand(1)
            return
        }
        val matches = index.tools.filter {
            it.name.contains(query, ignoreCase = true) || it.version.contains(query, ignoreCase = true)
        }
        if (matches.isEmpty()) {
            sendOutput("No tools matching '$query'.")
            finishToolsCommand(0)
            return
        }
        sendOutput("Search results for '$query':")
        for (entry in matches) {
            val status = if (StudioShellTools.isInstalled(context, entry.name)) "[installed]" else "[available]"
            sendOutput("  ${entry.name} - ${entry.version.ifBlank { "?" }} $status")
        }
        finishToolsCommand(0)
    }

    private fun toolsInfo(name: String?) {
        if (name.isNullOrBlank()) {
            sendOutput("Usage: tools info <toolname>")
            finishToolsCommand(1)
            return
        }
        val entry = StudioToolsIndex.loadCached(context).find(name)
        if (entry == null) {
            sendOutput("Unknown tool: $name (run 'tools refresh' to reload the GitHub index)")
            finishToolsCommand(1)
            return
        }
        val installed = StudioShellTools.isInstalled(context, entry.name)
        sendOutput("${entry.name} ${entry.version.ifBlank { "?" }} ${if (installed) "[installed]" else "[available]"}")
        sendOutput("  size: ${StudioShellTools.formatSize(entry.size)}")
        if (entry.sha256.isNotBlank()) sendOutput("  sha256: ${entry.sha256.take(16)}…")
        sendOutput("  release: ${entry.releaseTag.ifBlank { "?" }}")
        if (installed) {
            val installedVersion = StudioShellTools.getInstalledVersion(context, entry.name)
            if (installedVersion.isNotBlank()) sendOutput("  installed version: $installedVersion")
        }
        finishToolsCommand(0)
    }

    private fun toolsInstall(name: String?) {
        if (name.isNullOrBlank()) {
            sendOutput("Usage: tools install <toolname>")
            sendOutput("Run 'tools list' to see the tools published on GitHub.")
            finishToolsCommand(1)
            return
        }
        if (StudioShellTools.isInstalled(context, name)) {
            sendOutput("$name is already installed ('tools remove $name' to reinstall).")
            finishToolsCommand(0)
            return
        }
        sendOutput("Installing $name...")
        Thread {
            val entry = StudioToolsIndex.fetch(context).find(name)
            if (entry == null) {
                sendOutput("Unknown tool: $name (run 'tools refresh' to reload the GitHub index)")
                finishToolsCommand(1)
                return@Thread
            }
            try {
                StudioShellTools.installTarball(context, entry, onMessage = { msg -> sendOutput(msg) })
                // Publish the new binaries into the PATH/LD farm so the
                // running shell can execute them right away, and drop the
                // completion cache so tab-completion sees the new names.
                runCatching { StudioShellTools.refreshFarms(context) }
                pathCommandCacheAt = 0L
                sendOutput("$name installed successfully.")
                finishToolsCommand(0)
            } catch (t: Throwable) {
                Log.w(TAG, "tools install $name failed", t)
                sendOutput("Failed to install $name: ${t.message}")
                finishToolsCommand(1)
            }
        }.start()
    }

    private fun toolsRemove(name: String?) {
        if (name.isNullOrBlank()) {
            sendOutput("Usage: tools remove <toolname>")
            finishToolsCommand(1)
            return
        }
        if (StudioShellTools.remove(context, name)) {
            runCatching { StudioShellTools.refreshFarms(context) }
            pathCommandCacheAt = 0L
            sendOutput("$name removed.")
            finishToolsCommand(0)
        } else {
            sendOutput("$name is not installed.")
            finishToolsCommand(1)
        }
    }

    private fun toolsInstalled() {
        val installed = StudioShellTools.installedBinDirs(context)
            .mapNotNull { it.parentFile?.name }
            .sorted()
        if (installed.isEmpty()) {
            sendOutput("No tools installed. Run 'tools list' to see what's available.")
            finishToolsCommand(0)
            return
        }
        val index = StudioToolsIndex.loadCached(context)
        sendOutput("Installed tools:")
        for (name in installed) {
            val version = index.find(name)?.version
                ?: StudioShellTools.getInstalledVersion(context, name)
            sendOutput("  $name${if (version.isNotBlank()) " $version" else ""}")
        }
        finishToolsCommand(0)
    }

    private fun toolsUpdate() {
        sendOutput("Checking for updates...")
        Thread {
            val index = StudioToolsIndex.fetch(context, forceRefresh = true)
            if (index.isEmpty) {
                sendOutput("Tool index unavailable (offline?) — cannot check for updates.")
                finishToolsCommand(1)
                return@Thread
            }
            var checked = 0
            var updates = 0
            for (entry in index.tools) {
                if (!StudioShellTools.isInstalled(context, entry.name)) continue
                checked++
                val installedVersion = StudioShellTools.getInstalledVersion(context, entry.name)
                if (installedVersion.isNotBlank() && entry.version.isNotBlank() &&
                    installedVersion != entry.version
                ) {
                    updates++
                    sendOutput("  ${entry.name}: $installedVersion -> ${entry.version} ('tools install ${entry.name}' to update)")
                }
            }
            if (updates == 0) sendOutput("All $checked installed tools are up to date.")
            finishToolsCommand(0)
        }.start()
    }

    private fun toolsVerify(name: String?) {
        if (name.isNullOrBlank()) {
            sendOutput("Usage: tools verify <toolname>")
            finishToolsCommand(1)
            return
        }
        if (!StudioShellTools.isInstalled(context, name)) {
            sendOutput("$name is not installed.")
            finishToolsCommand(1)
            return
        }
        val exe = File(StudioShellTools.toolBinDir(context, name), name)
        val arch = StudioShellTools.checkElf(exe)
        sendOutput("$name: ${StudioShellTools.formatSize(exe.length())}, ${arch.archName}${if (arch.isElf) "" else " (not an ELF!)"}")
        finishToolsCommand(if (arch.isElf) 0 else 1)
    }

    private fun toolsRefresh() {
        sendOutput("Refreshing tool index from GitHub releases...")
        Thread {
            val index = StudioToolsIndex.fetch(context, forceRefresh = true)
            if (index.isEmpty) {
                sendOutput("Failed to refresh the tool index (network unavailable?).")
                finishToolsCommand(1)
            } else {
                val tags = index.tools.map { it.releaseTag }.distinct().joinToString(", ")
                sendOutput("Tool index refreshed: ${index.tools.size} tools from $tags.")
                finishToolsCommand(0)
            }
        }.start()
    }

    private fun toolsHelp() {
        sendOutput("StudioShell Tools Manager (tools come from GitHub releases — nothing hardcoded)")
        sendOutput("Usage:")
        sendOutput("  tools list              - List tools published on GitHub")
        sendOutput("  tools search <keyword>  - Search the tool index")
        sendOutput("  tools info <name>       - Show tool details")
        sendOutput("  tools install <name>    - Download + verify + install a tool")
        sendOutput("  tools update            - Check installed tools for updates")
        sendOutput("  tools remove <name>     - Remove an installed tool")
        sendOutput("  tools installed         - List installed tools")
        sendOutput("  tools verify <name>     - Verify an installed tool binary")
        sendOutput("  tools refresh           - Re-fetch the tool index from GitHub")
        sendOutput("  tools help              - Show this help")
        finishToolsCommand(0)
    }

    private fun indexAgeNote(index: StudioToolsIndex.ToolIndex): String {
        val age = System.currentTimeMillis() - index.fetchedAt
        val label = when {
            age < 60_000L -> "just now"
            age < 3_600_000L -> "${age / 60_000L} min ago"
            else -> "${age / 3_600_000L} h ago"
        }
        return "[index cached $label — 'tools refresh' to update]"
    }

    /**
     * Completion marker for ProcessStatusTracker. The tools builtin runs
     * in-app, not in the shell, so there is no STUDIO_PWD_ (the shell's cwd
     * is unchanged) — the marker alone resolves the status overlay and is
     * removed from the display by TerminalSession.extractMarkers like every
     * other marker line.
     */
    private fun finishToolsCommand(exitCode: Int) {
        outputChannel.trySend("STUDIO_CMD_DONE $exitCode\n")
    }

    private fun sendOutput(text: String) {
        // Built-in output joins the shared output channel so it flows through
        // the same reader path as PTY output (marker extraction + emulator +
        // UI) — otherwise marker lines printed by built-ins would be shown
        // on screen.
        outputChannel.trySend(text + "\n")
    }

    fun addToHistory(command: String) {
        if (command.isBlank()) return
        if (commandHistory.lastOrNull() != command) {
            commandHistory.add(command)
            if (commandHistory.size > 500) commandHistory.removeAt(0)
            saveHistory()
        }
        historyIndex = commandHistory.size
    }

    fun getPreviousCommand(): String? {
        if (commandHistory.isEmpty()) return null
        historyIndex = (historyIndex - 1).coerceAtLeast(0)
        return commandHistory.getOrNull(historyIndex)
    }

    fun getNextCommand(): String? {
        if (commandHistory.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size)
        return commandHistory.getOrNull(historyIndex)
    }

    fun getHistory(): List<String> = commandHistory.toList()

    private fun loadHistory() {
        commandHistory.clear()
        val saved = prefs.getString("history", null)
        if (saved != null) {
            commandHistory.addAll(saved.split("\n").filter { it.isNotEmpty() })
        }
        historyIndex = commandHistory.size
    }

    private fun saveHistory() {
        prefs.edit().putString("history", commandHistory.joinToString("\n")).apply()
    }

    fun sendRaw(text: String): Boolean {
        synchronized(writeLock) {
            val p = pty
            if (p == null) {
                // Shell is not up right now (starting or restarting).  Queue the
                // input instead of dropping it — it is delivered to the next shell.
                if (stopRequested.get()) return false
                pendingWrites.addLast(text)
                return true
            }
            return try {
                // The native layer may report a partial write (PTY buffer was
                // full).  Resume at the byte offset instead of treating it as
                // success — otherwise the remainder of the input is silently
                // dropped and multi-byte text gets truncated.
                val bytes = text.toByteArray(Charsets.UTF_8)
                var off = 0
                while (off < bytes.size) {
                    val n = p.writeBytes(bytes, off, bytes.size - off)
                    Log.d(TAG, "sendRaw len=${bytes.size} off=$off n=$n")
                    if (n <= 0) {
                        // Stalled mid-write. Park the unwritten remainder so it
                        // is delivered once the PTY drains instead of being
                        // dropped -- a lost Enter leaves the previous command
                        // sitting on the shell's input line, and the next
                        // command is then appended to it on screen.
                        if (off < bytes.size) {
                            pendingWrites.addLast(text.substring(off))
                        }
                        return false
                    }
                    off += n
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send raw: $text", e)
                false
            }
        }
    }

    /** Drain input queued while no shell process existed.  Caller holds writeLock. */
    private fun flushPendingWrites() {
        while (true) {
            val p = pty ?: return
            val item = pendingWrites.removeFirstOrNull() ?: return
            try {
                p.write(item)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush queued input", e)
            }
        }
    }

    fun sendEof(): Boolean {
        return sendRaw("\u0004") // Ctrl+D
    }

    val output: kotlinx.coroutines.channels.ReceiveChannel<String> = outputChannel

    fun getCurrentCwd(): File = currentCwd.get()

    fun updateCwd(newCwd: File) {
        currentCwd.set(newCwd)
    }

    fun getLastExitCode(): Int = lastExitCode.get()

    fun setLastExitCode(code: Int) {
        lastExitCode.set(code)
    }

    fun isAlive(): Boolean {
        return isRunning.get() && (pty?.isAlive() ?: false)
    }

    fun resize(cols: Int, rows: Int) {
        pty?.resize(cols, rows)
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    fun stop() {
        stopRequested.set(true)
        isRunning.set(false)
        synchronized(writeLock) { pendingWrites.clear() }
        val p = pty
        pty = null
        try {
            p?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PTY", e)
        }
    }

    fun destroy() {
        stop()
        scope.coroutineContext.cancel()
    }

    // --- Signal Handling ---

    fun sendInterrupt() {
        sendRaw("\u0003") // SIGINT (Ctrl+C)
    }

    fun sendSuspend() {
        sendRaw("\u001A") // SIGTSTP (Ctrl+Z)
    }

    fun sendEofSignal() {
        sendRaw("\u0004") // EOF (Ctrl+D)
    }

    fun sendQuit() {
        sendRaw("\u001C") // SIGQUIT (Ctrl+\)
    }

    fun sendKill() {
        sendRaw("\u000B") // SIGKILL (Ctrl+K) - line kill
    }

    fun sendHorizontalTab() {
        sendRaw("\u0009") // Tab character
    }

    // --- Job Control ---

    fun getJobs(): List<BackgroundJob> {
        synchronized(lock) {
            return jobs.toList()
        }
    }

    fun fgJob(jobId: Int): Boolean {
        synchronized(lock) {
            val job = jobs.find { it.id == jobId } ?: return false
            if (job.state == JobState.STOPPED) {
                sendRaw("fg %$jobId\n")
                job.state = JobState.RUNNING
            }
            return true
        }
    }

    fun bgJob(jobId: Int): Boolean {
        synchronized(lock) {
            val job = jobs.find { it.id == jobId } ?: return false
            if (job.state == JobState.STOPPED) {
                sendRaw("bg %$jobId\n")
                job.state = JobState.RUNNING
            }
            return true
        }
    }

    fun killJob(jobId: Int): Boolean {
        synchronized(lock) {
            val job = jobs.find { it.id == jobId } ?: return false
            sendRaw("kill %$jobId\n")
            jobs.remove(job)
            return true
        }
    }

    fun jobsFormatted(): String {
        synchronized(lock) {
            if (jobs.isEmpty()) return "No active jobs."
            return jobs.joinToString("\n") { job ->
                val stateStr = when (job.state) {
                    JobState.RUNNING -> "Running"
                    JobState.STOPPED -> "Stopped"
                    JobState.DONE -> "Done"
                }
                "[$${job.id}] $stateStr  ${job.command}"
            }
        }
    }

    // --- Completion ---

    /**
     * Local tab completion.
     *
     * The old implementation ran `compgen` through the shell and consumed
     * the shared output channel while doing so, which stole stdout chunks
     * from the UI reader (markers and command output could disappear).
     * Completion is now answered locally from the filesystem, PATH and the
     * command history — no PTY round trip at all.
     */
    fun getCompletions(partial: String): List<String> {
        if (partial.isEmpty()) return emptyList()
        val result = linkedSetOf<String>()
        val cwd = currentCwd.get()

        if (partial.contains('/')) {
            val dirPart = partial.substringBeforeLast('/')
            val namePart = partial.substringAfterLast('/')
            val dir = resolveCompletionDir(dirPart, cwd)
            dir?.listFiles()?.forEach { f ->
                if (f.name.startsWith(namePart)) {
                    result += if (f.isDirectory) "$dirPart/${f.name}/" else "$dirPart/${f.name}"
                }
            }
        } else {
            // `tools` builtin completion. Tool names come from the cached
            // release index (file read only — never network on the UI thread).
            if (partial == "tools" || partial.startsWith("tools ")) {
                val rest = partial.removePrefix("tools").trimStart()
                val sub = rest.substringBefore(' ')
                val arg = if (rest.contains(' ')) rest.substringAfter(' ') else ""
                if (arg.isEmpty()) {
                    TOOLS_SUBCOMMANDS.forEach { if (it.startsWith(sub)) result += "tools $it" }
                } else when (sub) {
                    "install" -> StudioToolsIndex.loadCached(context).tools.forEach { e ->
                        if (e.name.startsWith(arg)) result += "tools install ${e.name}"
                    }
                    "info", "verify" -> StudioToolsIndex.loadCached(context).tools.forEach { e ->
                        if (e.name.startsWith(arg)) result += "tools $sub ${e.name}"
                    }
                    "remove" -> StudioShellTools.installedBinDirs(context)
                        .mapNotNull { it.parentFile?.name }
                        .forEach { if (it.startsWith(arg)) result += "tools remove $it" }
                }
            }
            cwd.listFiles()?.forEach { f ->
                if (f.name.startsWith(partial)) result += f.name
            }
            pathCommands().forEach { if (it.startsWith(partial)) result += it }
            SHELL_BUILTINS.forEach { if (it.startsWith(partial)) result += it }
        }

        commandHistory.forEach { entry ->
            if (entry.startsWith(partial) && entry != partial) result += entry
        }

        return result.sorted()
    }

    private fun resolveCompletionDir(dirPart: String, cwd: File): File? {
        val base = runCatching {
            when {
                dirPart.isEmpty() || dirPart == "." -> cwd
                dirPart == ".." -> File(cwd, "..")
                dirPart == "~" -> context.filesDir
                dirPart.startsWith("~/") -> File(context.filesDir, dirPart.removePrefix("~"))
                dirPart.startsWith("/") -> File(dirPart)
                else -> File(cwd, dirPart)
            }.canonicalFile
        }.getOrNull()
        return base?.takeIf { it.isDirectory }
    }

    /** Executables found in PATH, refreshed at most once a minute. */
    private fun pathCommands(): List<String> {
        val now = System.currentTimeMillis()
        if (pathCommandCache.isNotEmpty() && now - pathCommandCacheAt < 60_000L) {
            return pathCommandCache
        }
        val envPath = initialEnv["PATH"] ?: return pathCommandCache
        val names = sortedSetOf<String>()
        for (dir in envPath.split(':')) {
            if (dir.isEmpty()) continue
            val files = File(dir).listFiles() ?: continue
            for (f in files) {
                if (f.isFile && f.canExecute()) {
                    names += f.name
                    if (names.size > 4000) break
                }
            }
            if (names.size > 4000) break
        }
        if (names.isNotEmpty()) {
            pathCommandCache = names.toList()
            pathCommandCacheAt = now
        }
        return pathCommandCache
    }

    /**
     * Shell init script sourced silently by the interactive shell ($ENV).
     * Typed `tools ...` commands resolve in the shell through this stub, so
     * the kernel echo works normally (the user sees what they type) while the
     * app-side builtin does the actual work. Sourcing — not typing — means
     * the stub itself is never shown on the terminal.
     */
    private fun prepareShellInitScript(): File {
        val script = File(context.codeCacheDir, "shell_env_init.sh")
        val text = "#!/system/bin/sh\n" +
            "# MobileStudio shell init (sourced via \$ENV). Do not edit:\n" +
            "# regenerated by PersistentShell on every shell (re)start.\n" +
            "# `tools` is handled app-side; this stub reports the arguments\n" +
            "# through the __TOOLS_HOOK__ line PersistentShell intercepts.\n" +
            "tools() { echo \"${TOOLS_HOOK_PREFIX}\$*\"; }\n"
        if (!script.exists() || script.readText() != text) {
            script.writeText(text)
        }
        script.setReadable(true, false)
        return script
    }

    companion object {
        const val DEFAULT_PROMPT = "\u001B[38;5;214mMobileStudio\u001B[0m \u001B[1m>\u001B[0m "

        /** Always-available names for tab completion. */
        private val SHELL_BUILTINS = listOf(
            "cd", "pwd", "echo", "exit", "export", "unset", "source", "type",
            "which", "true", "false", "test", "printf", "read", "set", "trap",
            "umask", "alias", "unalias", "history", "jobs", "fg", "bg", "kill",
            "clear", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "touch",
            "grep", "sed", "awk", "find", "sort", "head", "tail", "wc", "chmod",
            "git", "java", "javac", "gradle", "kotlin", "python", "python3",
            "lua", "luac",
            "sh", "bash", "busybox", "tools", "getprop", "setprop", "am", "pm"
        )

        /** Subcommands of the `tools` builtin. */
        private val TOOLS_SUBCOMMANDS = listOf(
            "list", "search", "info", "install", "update", "remove", "installed", "verify", "refresh", "help"
        )

        /**
         * Prefix of the hook line printed by the shell-side `tools()` stub
         * (shell_env_init.sh, sourced via $ENV). PersistentShell.readOutputLoop
         * intercepts these lines app-side — they are never displayed.
         */
        private const val TOOLS_HOOK_PREFIX = "__TOOLS_HOOK__ "

        /**
         * Strips the internal completion wrapper TerminalSession adds to
         * every command: `{ <command>\n}; __st=$?; echo "STUDIO_CMD_DONE ..."`.
         * Returns the inner command, or null when [command] is not that
         * wrapper (single-line inner only — multi-line scripts pass through
         * to the shell untouched).
         */
        internal fun unwrapCompletionGroup(command: String): String? {
            if (!command.startsWith("{")) return null
            val close = command.lastIndexOf("\n}")
            if (close <= 1) return null
            val tail = command.substring(close + 2)
            if (!tail.contains("__st=$?") || !tail.contains("STUDIO_CMD_DONE")) return null
            val inner = command.substring(1, close).trim()
            if (inner.isEmpty() || inner.contains('\n')) return null
            return inner
        }
    }
}

object ShellEnvironment {
    private val TAG = "ShellEnvironment"

    @Volatile
    private var cachedToolEnv: Map<String, String>? = null

    @Volatile
    private var cachedToolEnvAt = 0L

    /**
     * Full toolchain environment for non-PTY executors (AI bash tool etc.).
     *
     * Identical to [buildInitialEnvironment] — without it, a ProcessBuilder
     * shell only inherits the bare app process PATH and can never find
     * java/gradle/cmake/python/lua.  Cached for 60s (the same refresh cadence
     * as the shell's PATH completion cache) so frequent tool calls do not
     * rescan the toolchains every time.
     */
    fun buildToolEnvironment(context: Context, cwd: File): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedToolEnv?.let { if (now - cachedToolEnvAt < 60_000L) return it }
        val env = buildInitialEnvironment(context, cwd)
        cachedToolEnv = env
        cachedToolEnvAt = now
        return env
    }

    fun buildInitialEnvironment(context: Context, cwd: File): Map<String, String> {
        val toolchain = ToolchainManager(context)
        val jdk = toolchain.activeJdkHome()
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/jdk-21"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/jdk21-android"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/jdk-17"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/jdk17-android"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/lib/jvm/java-21-openjdk"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk/lib/jvm/java-17-openjdk"))
            ?: toolchain.resolveJdkHome(File(context.filesDir, "toolchain/jdk"))
        // Shared with the Build button (GradleRunner) via BuildToolchain so the
        // terminal sees the exact same SDK / NDK / Gradle that tapping Build uses.
        val sdk = BuildToolchain.sdkDir(context)
        val ndk = BuildToolchain.primaryNdkDir(context)
        val cmake = toolchain.activeDir(ToolchainManager.Kind.CMAKE)
            ?: BuildToolchain.cmakeDir(context, sdk)
        val gradle = BuildToolchain.gradleHome(context)
        val python = toolchain.activeDir(ToolchainManager.Kind.PYTHON)
        val aiDir = File(context.filesDir, "toolchain/ai")
        val opencodeBin = File(aiDir, "opencode/bin")
        val aiBin = File(aiDir, "bin")

        val nativeToolShell = prepareNativeToolShimScript(context)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val runtimeToolsBin = RuntimeToolsInstaller.binDir(context).takeIf { it.isDirectory }
        val runtimeToolsLib = RuntimeToolsInstaller.libDir(context).takeIf { it.isDirectory }

        // Bring the tools farm (symlinks to installed tool binaries/libs) in
        // sync before the environment is captured below.
        runCatching { StudioShellTools.refreshFarms(context) }

        val pathParts = buildList {
            // 1. runtime-tools first (busybox applets: ls, cat, cp, etc.)
            runtimeToolsBin?.let { add(it.absolutePath) }
            // 2. NDK LLVM tools (clang, lld, etc.)
            NdkToolchain.discoverInstalled(context).forEach { ndkDir ->
                add(NdkToolchain.llvmBinDir(ndkDir).absolutePath)
            }
            // 3. StudioShellTools — the farm is a stable PATH entry so tools
            // installed later resolve in already-running shells, followed by
            // the per-tool bin dirs for shells started after an install.
            add(StudioShellTools.binFarmDir(context).absolutePath)
            StudioShellTools.installedBinDirs(context).forEach { add(it.absolutePath) }
            // 4. JDK bin
            if (jdk != null) add(File(jdk, "bin").absolutePath)
            // 5. SDK tools
            sdk?.let {
                add(File(it, "platform-tools").absolutePath)
                add(File(it, "cmdline-tools/latest/bin").absolutePath)
                add(File(it, "cmdline-tools/bin").absolutePath)
            }
            // 6. Gradle (same install the Build button launches)
            add(File(gradle, "bin").absolutePath)
            // 7. CMake
            cmake?.let { add(File(it, "bin").absolutePath) }
            // 8. NDK cross-compile tools
            NdkToolchain.discoverInstalled(context).filter { !NdkToolchain.isArm64NdkDir(it) }
                .forEach { add(File(it, "toolchains/llvm/prebuilt/linux-x86_64/bin").absolutePath) }
            // 9. Python
            python?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "local/bin").absolutePath)
            }
            // 9b. Lua 5.4 interpreter + luac (APK-packaged, symlink farm)
            runCatching { LuaTools.sync(context) }
                .getOrNull()
                ?.let { add(it.absolutePath) }
            // 10. AI/OpenCode
            if (opencodeBin.isDirectory) add(opencodeBin.absolutePath)
            if (aiBin.isDirectory) add(aiBin.absolutePath)
            // 11. MobileStudio dir
            add("/data/data/com.aide.mobilestudio/files/MobileStudio")
            // 12. System paths last
            add("/system/bin")
            add("/system/xbin")
        }.distinct().joinToString(":")

        val ldParts = buildList {
            // JDK libs (libjli.so, libjvm.so, etc.)
            if (jdk != null) {
                add(File(jdk, "lib").absolutePath)
                add(File(jdk, "lib/server").absolutePath)
                add(File(jdk, "lib/jli").absolutePath)
            }
            // CMake
            cmake?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "lib").absolutePath)
            }
            // Native lib dir
            add(context.applicationInfo.nativeLibraryDir)
            // System libs
            add("/system/lib64")
            add("/system/lib")
            // Runtime tools
            runtimeToolsLib?.let { add(it.absolutePath) }
            // StudioShellTools (stable farm entry + per-tool lib dirs)
            add(StudioShellTools.libFarmDir(context).absolutePath)
            StudioShellTools.installedLibDirs(context).forEach { add(it.absolutePath) }
        }.distinct().joinToString(":")

        return buildMap {
            put("HOME", context.filesDir.absolutePath)
            put("SHELLSTUDIO_HOME", File(context.filesDir, "ShellStudio").absolutePath)
            put("TMPDIR", File(context.cacheDir, "android_shell_tmp").apply { mkdirs() }.absolutePath)
            put("PATH", pathParts)
            put("LD_LIBRARY_PATH", ldParts)
            put("MOBILESTUDIO_NATIVE_TOOL_SHELL", prepareNativeToolShimScript(context).absolutePath)
            put("MOBILESTUDIO_NATIVE_LIB_DIR", context.applicationInfo.nativeLibraryDir)
            put("MOBILESTUDIO_APK_PATH", context.applicationInfo.sourceDir)
            put("MOBILESTUDIO_CODE_CACHE", context.codeCacheDir.absolutePath)
            File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so").takeIf { it.exists() }?.let {
                put("LD_PRELOAD", it.absolutePath)
            }
            // JAVA_HOME
            if (jdk != null) put("JAVA_HOME", jdk.absolutePath)
            // SDK / NDK / Gradle: identical to the Build button (BuildToolchain).
            sdk?.let {
                put("ANDROID_HOME", it.absolutePath)
                put("ANDROID_SDK_ROOT", it.absolutePath)
            }
            // Same writable AGP prefs/keystore location the build uses; without
            // these a bare `gradle` in the shell fails applying AGP.
            put("ANDROID_USER_HOME", File(context.filesDir, ".android").apply { mkdirs() }.absolutePath)
            put("ANDROID_PREFS_ROOT", context.filesDir.absolutePath)
            // NDK is optional — only exported when one is installed.
            ndk?.let {
                put("ANDROID_NDK_HOME", it.absolutePath)
                put("ANDROID_NDK_ROOT", it.absolutePath)
            }
            put("MOBILESTUDIO_LLVM_SEARCH_ROOTS", NdkToolchain.discoverInstalled(context).joinToString(":") { it.absolutePath })
            if (gradle.isDirectory) put("GRADLE_HOME", gradle.absolutePath)
            toolchain.activeDir(ToolchainManager.Kind.PYTHON)?.let {
                put("PYTHONHOME", it.absolutePath)
                put("PYTHONUSERBASE", File(context.filesDir, "toolchain/python-user").apply { mkdirs() }.absolutePath)
                put("PIP_CACHE_DIR", File(context.cacheDir, "pip").apply { mkdirs() }.absolutePath)
            }
            // Shared with the build so caches / daemons / gradle.properties match.
            put("GRADLE_USER_HOME", BuildToolchain.gradleUserHome(context).absolutePath)
            // Do not force daemon OFF here; project/user gradle.properties governs.
            put("GRADLE_OPTS", "-Djava.io.tmpdir=${File(context.cacheDir, "gradle_tmp").apply { mkdirs() }.absolutePath}")
            put("TERM", "xterm-256color")
            put("SHELL", "/system/bin/sh")
            put("LANG", "en_US.UTF-8")
            put("PS1", "")
        }
    }

    private fun prepareNativeToolShimScript(context: Context): File {
        val out = File(context.filesDir, "toolchain/runtime-tools/bin/exec-redirect.sh")
        out.parentFile?.mkdirs()
        // Rewrite when the content is stale (app updates change this script,
        // and the runtime-tools installer may clobber it) — see the
        // MOBILESTUDIO_SHIM_ACTIVE guard in exec_redirect.c: without the
        // marker the shim's `exec "$@"` re-enters this hook in an infinite
        // loop that inflates PATH until exec fails with E2BIG.
        val content = "#!/system/bin/sh\n" +
            "# Wrapper to run runtime-tools binaries via linker64 with proper LD_LIBRARY_PATH\n" +
            "export LD_LIBRARY_PATH=/data/data/com.aide.mobilestudio/files/toolchain/runtime-tools/lib:\${LD_LIBRARY_PATH:-}\n" +
            "export PATH=/data/data/com.aide.mobilestudio/files/toolchain/runtime-tools/bin:\${PATH}\n" +
            "export MOBILESTUDIO_SHIM_ACTIVE=1\n" +
            "exec \"\$@\"\n"
        if (!out.exists() || out.readText() != content) {
            out.writeText(content)
            out.setExecutable(true, false)
        }
        return out
    }
}