package com.aide.mobilestudio.terminal

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.PersistentShell
import com.aide.mobilestudio.ShellEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device self test for the terminal stack: PTY isolation, real exit
 * codes, signal reporting, Ctrl+C, marker protocol, shell auto-restart and
 * multi-tab independence.
 *
 * Trigger:
 *   adb shell am start -n com.aide.mobilestudio/.MainActivity --ez terminal_self_test true
 *
 * Results:
 *   adb logcat -s TerminalTest
 *   adb shell run-as com.aide.mobilestudio cat files/terminal_selftest.log
 *
 * Every test returns null on success or a failure description.
 * [Result.ok] is false only for real failures — informational cases such as
 * "java is not installed" (exit 127) are reported as passes with detail.
 */
object TerminalPtySelfTest {

    private const val TAG = "TerminalTest"
    private const val DEFAULT_TIMEOUT = 12_000L
    private const val LOG_FILE = "terminal_selftest.log"

    data class Result(val name: String, val ok: Boolean, val detail: String)

    // ==================================================================
    // Entry points
    // ==================================================================

    /** Runs the suite on a worker thread, logs to logcat and to a file. */
    fun runAsync(context: Context) {
        val app = context.applicationContext
        Thread(
            {
                val lines = mutableListOf<String>()
                val results = runAll(app) { line ->
                    lines += line
                    Log.i(TAG, line)
                }
                val passed = results.count { it.ok }
                val summary = "DONE $passed/${results.size} passed"
                lines += "[TerminalTest] $summary"
                Log.i(TAG, summary)
                runCatching {
                    File(app.filesDir, LOG_FILE).writeText(lines.joinToString("\n") + "\n")
                }.onFailure { Log.e(TAG, "Failed to write $LOG_FILE", it) }
            },
            "terminal-selftest"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun runAll(context: Context, report: (String) -> Unit): List<Result> {
        val runner = Runner(context, report)
        runner.run()
        return runner.results
    }

    // ==================================================================
    // Harness
    // ==================================================================

    private class Runner(
        private val context: Context,
        private val report: (String) -> Unit
    ) {
        val results = mutableListOf<Result>()

        private val env: Map<String, String> by lazy {
            runCatching { ShellEnvironment.buildInitialEnvironment(context, context.filesDir) }
                .getOrElse {
                    mapOf(
                        "PATH" to "/system/bin:/system/xbin",
                        "HOME" to context.filesDir.absolutePath,
                        "TERM" to "xterm-256color",
                        "SHELL" to "/system/bin/sh",
                        "PS1" to ""
                    )
                }
        }

        fun test(name: String, body: () -> String?) {
            val t0 = System.currentTimeMillis()
            val failure = try {
                body()
            } catch (t: Throwable) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
            val ok = failure == null
            val ms = System.currentTimeMillis() - t0
            val detail = if (ok) "ok (${ms}ms)" else failure.orEmpty()
            results += Result(name, ok, detail)
            report("[TerminalTest] ${if (ok) "PASS" else "FAIL"} $name :: $detail")
        }

        fun run() {
            nativePtyEcho()
            nativePtyExitCode()
            nativePtySignalExit()
            nativePtyTwoInstances()
            nativePtyGroupMarker()

            shellMarkerExitCode()
            shellReportsExitCode3()
            shellExitZeroIsSilent()
            shellReportsFatalSignal()
            shellCtrlCKillsForegroundOnly()
            shellTwoInstancesIndependent()

            sessionEchoExitStatus()
            sessionPwdMkdirLs()
            sessionCdUpdatesCwd()
            sessionSurvivesExit()
            sessionSurvivesCtrlC()
            sessionJavaVersion()
            sessionLuaVersion()
            sessionLuaEvalPrintsTwo()
            sessionLuacVersion()
            sessionLsNoCannotLink()
            sessionClearNoCannotLink()
            sessionShimMarkerAbsentInShell()
            sessionTwoTabsIndependent()
        }

        // --------------------------------------------------------------
        // helpers
        // --------------------------------------------------------------

        private fun await(
            timeoutMs: Long = DEFAULT_TIMEOUT,
            cond: () -> Boolean
        ): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (cond()) return true
                Thread.sleep(25)
            }
            return cond()
        }

        private fun canonical(path: String): String =
            runCatching { File(path).canonicalPath }.getOrDefault(path)

        private fun samePath(a: String, b: String): Boolean = canonical(a) == canonical(b)

        /** Mirrors TerminalSession.buildCommandWithCompletionMarker: the group
         * body must be newline-terminated before `}` — a single-line
         * `{ cmd }; ...` is never submitted by mksh (it keeps asking for more
         * input with PS2, leaving the command permanently unfinished). */
        private fun withMarker(cmd: String): String =
            "{\n$cmd\n}; __st=\$?; echo \"STUDIO_CMD_DONE \$__st STUDIO_PWD_\$(pwd)\""

        private fun readPty(p: NativePty, sink: StringBuilder) {
            val chunk = p.read()
            if (chunk != null) synchronized(sink) { sink.append(chunk) }
        }

        private fun ptyText(sink: StringBuilder): String = synchronized(sink) { sink.toString() }

        /**
         * Starts a raw PersistentShell with a background channel consumer.
         * The caller must not consume shell.output itself.
         */
        private fun withShell(block: (PersistentShell, Probe) -> String?): String? {
            val shell = PersistentShell(context, env, context.filesDir)
            val probe = Probe(shell)
            try {
                if (!shell.start()) return "shell failed to start"
                if (!await(10_000) { shell.isAlive() }) return "shell not alive after start"
                // Wait for the shell's initial prompt before writing: input
                // that arrives while mksh is still initializing (typed-ahead)
                // gets its line editor redraw garbled and the line may never
                // be submitted.
                await(3_000) { probe.text().isNotBlank() }
                return block(shell, probe)
            } finally {
                runCatching { shell.destroy() }
                probe.close()
            }
        }

        /** Starts a TerminalSession and collects its (marker-stripped) output. */
        private fun withSession(block: (TerminalSession, StringBuilder) -> String?): String? {
            val session = TerminalSession(
                context,
                id = "selftest-${System.nanoTime()}",
                initialCwd = context.filesDir
            )
            val out = StringBuilder()
            session.onOutput = { chunk -> synchronized(out) { out.append(chunk) } }
            try {
                if (!session.start()) return "session start() returned false"
                if (!await(15_000) { session.state == TerminalSession.State.RUNNING }) {
                    return "session not RUNNING (state=${session.state})"
                }
                return block(session, out)
            } finally {
                runCatching { session.destroy() }
            }
        }

        /** Waits for the marker-driven process status and returns its exit code. */
        private fun awaitCommandExit(session: TerminalSession, timeoutMs: Long = DEFAULT_TIMEOUT): Int? {
            val found = await(timeoutMs) {
                val p = session.processTracker.currentProcess
                p != null &&
                    p.state != ProcessStatusTracker.ProcessInfo.State.RUNNING &&
                    p.exitCode != null
            }
            return if (found) session.processTracker.currentProcess?.exitCode else null
        }

        private fun sessionOutput(out: StringBuilder): String = synchronized(out) { out.toString() }

        /** Raw output with control characters visible, for failure reports. */
        private fun escaped(text: String): String =
            text.take(600)
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u001b", "\\e")

        private fun clearOutput(out: StringBuilder) = synchronized(out) { out.setLength(0) }

        /**
         * Keeps sending a unique echo until one of them is seen in the output.
         * Used to detect that a shell came back after it was killed/exited.
         */
        private fun awaitEcho(
            send: (String) -> Boolean,
            output: () -> String,
            prefix: String,
            timeoutMs: Long = 20_000L
        ): Boolean {
            val token = "${prefix}_${System.nanoTime() % 100_000_000}"
            var attempts = 0
            return await(timeoutMs) {
                attempts++
                if (attempts % 8 == 1) send(token) // ~every 200ms
                output().contains(token)
            }
        }

        // --------------------------------------------------------------
        // Native PTY level
        // --------------------------------------------------------------

        private fun nativePtyEcho() = test("native_pty_echo") {
            val p = NativePty()
            val sink = StringBuilder()
            try {
                if (!p.start("/system/bin/sh", context.filesDir.absolutePath, env)) {
                    return@test "native start failed"
                }
                if (p.write("echo NATIVE_OK\n") < 0) return@test "write failed"
                if (!await { readPty(p, sink); ptyText(sink).contains("NATIVE_OK") }) {
                    return@test "no output (got: ${ptyText(sink).take(200)})"
                }
                if (!p.isAlive()) return@test "shell died after echo"
                null
            } finally {
                p.close()
            }
        }

        private fun nativePtyExitCode() = test("native_pty_exit_code_5") {
            val p = NativePty()
            try {
                if (!p.start("/system/bin/sh", context.filesDir.absolutePath, env)) {
                    return@test "native start failed"
                }
                p.write("exit 5\n")
                if (!await(10_000) { !p.isAlive() }) return@test "shell still alive after 10s"
                val code = p.getExitCode()
                val sig = p.getExitSignal()
                if (code != 5) return@test "expected 5, got $code (signal=$sig)"
                if (sig != 0) return@test "unexpected signal $sig"
                null
            } finally {
                p.close()
            }
        }

        private fun nativePtySignalExit() = test("native_pty_signal_kill9") {
            val p = NativePty()
            try {
                if (!p.start("/system/bin/sh", context.filesDir.absolutePath, env)) {
                    return@test "native start failed"
                }
                p.write("kill -9 \$\$\n")
                if (!await(10_000) { !p.isAlive() }) return@test "shell survived SIGKILL"
                val sig = p.getExitSignal()
                val code = p.getExitCode()
                if (sig != 9) return@test "expected signal 9, got $sig (code=$code)"
                if (code != 137) return@test "expected 137, got $code"
                null
            } finally {
                p.close()
            }
        }

        private fun nativePtyTwoInstances() = test("native_pty_two_instances") {
            val a = NativePty()
            val b = NativePty()
            val sinkA = StringBuilder()
            val sinkB = StringBuilder()
            try {
                val cwd = context.filesDir.absolutePath
                if (!a.start("/system/bin/sh", cwd, env)) return@test "PTY A failed to start"
                if (!b.start("/system/bin/sh", cwd, env)) return@test "PTY B failed to start"
                a.write("echo TOKEN_AAA\n")
                b.write("echo TOKEN_BBB\n")
                val aOk = await { readPty(a, sinkA); ptyText(sinkA).contains("TOKEN_AAA") }
                val bOk = await { readPty(b, sinkB); ptyText(sinkB).contains("TOKEN_BBB") }
                if (!aOk) return@test "PTY A produced no output"
                if (!bOk) return@test "PTY B produced no output"
                if (ptyText(sinkA).contains("TOKEN_BBB")) return@test "PTY A saw PTY B output"
                if (ptyText(sinkB).contains("TOKEN_AAA")) return@test "PTY B saw PTY A output"
                if (!a.isAlive()) return@test "PTY A shell died"
                if (!b.isAlive()) return@test "PTY B shell died"
                null
            } finally {
                a.close()
                b.close()
            }
        }

        /**
         * Discriminates app-side output duplication from device/mksh behavior:
         * writes the exact TerminalSession completion-group line at the bare
         * NativePty level (no PersistentShell, no TerminalSession in between).
         */
        private fun nativePtyGroupMarker() = test("native_pty_group_marker") {
            val p = NativePty()
            val sink = StringBuilder()
            try {
                if (!p.start("/system/bin/sh", context.filesDir.absolutePath, env)) {
                    return@test "native start failed"
                }
                // Same typed-ahead guard as withShell: let mksh print its
                // initial prompt (mkshrc PS1) before writing the long line.
                await(3_000) { readPty(p, sink); ptyText(sink).contains("$") }
                val line = withMarker("sh -c 'exit 7'")
                if (p.write(line + "\n") < 0) return@test "write failed"
                if (!await(10_000) { readPty(p, sink); ptyText(sink).contains("STUDIO_CMD_DONE 7") }) {
                    return@test "marker missing (raw: ${escaped(ptyText(sink))})"
                }
                val echoes = Regex("\\Q__st=$?\\E").findAll(ptyText(sink)).count()
                if (echoes > 1) {
                    return@test "input echoed $echoes times (raw: ${escaped(ptyText(sink))})"
                }
                null
            } finally {
                p.close()
            }
        }

        // --------------------------------------------------------------
        // PersistentShell level
        // --------------------------------------------------------------

        private fun shellMarkerExitCode() = test("shell_marker_exit_code") {
            withShell { shell, probe ->
                probe.clear()
                if (!shell.sendRaw(withMarker("sh -c 'exit 7'") + "\n")) return@withShell "write failed"
                if (!await { probe.text().contains("STUDIO_CMD_DONE 7") }) {
                    return@withShell "marker missing (raw: ${escaped(probe.text())})"
                }
                // The echoed command text quotes the marker pattern with
                // `$__st` — only the parsed output form (digits) is the real
                // marker line.
                val markerLine = probe.text().lineSequence()
                    .firstOrNull { Regex("STUDIO_CMD_DONE \\d+ ").containsMatchIn(it) } ?: ""
                val pwd = markerLine.substringAfter("STUDIO_PWD_", "").trim()
                if (pwd.isEmpty()) return@withShell "marker has no PWD: $markerLine"
                if (!samePath(pwd, context.filesDir.absolutePath)) {
                    return@withShell "marker pwd=$pwd expected=${context.filesDir.absolutePath}"
                }
                null
            }
        }

        private fun shellReportsExitCode3() = test("shell_reports_exit_code_3") {
            withShell { shell, probe ->
                val exit = AtomicInteger(-1)
                val sig = AtomicInteger(-1)
                shell.onShellExited = { c, s -> exit.set(c); sig.set(s) }
                probe.clear()
                if (!shell.sendRaw("exit 3\n")) return@withShell "write failed"
                if (!await { exit.get() >= 0 }) return@withShell "shell never exited"
                if (exit.get() != 3) return@withShell "onShellExited code=${exit.get()} expected 3"
                if (sig.get() != 0) return@withShell "unexpected signal ${sig.get()}"
                if (!await { probe.text().contains("[Process exited with code 3]") }) {
                    return@withShell "exit message missing (got: ${probe.text().take(300)})"
                }
                if (probe.text().contains("[Process exited with code -1]")) {
                    return@withShell "old -1 exit code bug still present"
                }
                if (shell.getLastExitCode() != 3) {
                    return@withShell "lastExitCode=${shell.getLastExitCode()} expected 3"
                }
                probe.clear()
                if (!awaitEcho({ shell.sendRaw("echo $it\n") }, { probe.text() }, "RST")) {
                    return@withShell "shell did not restart after exit 3"
                }
                if (!shell.isAlive()) return@withShell "shell not alive after restart"
                null
            }
        }

        private fun shellExitZeroIsSilent() = test("shell_exit_zero_is_silent") {
            withShell { shell, probe ->
                val exit = AtomicInteger(-1)
                shell.onShellExited = { c, _ -> exit.set(c) }
                probe.clear()
                if (!shell.sendRaw("exit\n")) return@withShell "write failed"
                if (!await { exit.get() >= 0 }) return@withShell "shell never exited"
                if (exit.get() != 0) return@withShell "expected code 0, got ${exit.get()}"
                // Give the exit message (if wrongly emitted) time to arrive.
                Thread.sleep(500)
                val text = probe.text()
                if (text.contains("[Process exited")) {
                    return@withShell "exit 0 must be silent, got: ${text.take(200)}"
                }
                if (text.contains("[Shell exited; restart failed]")) {
                    return@withShell "restart failed"
                }
                probe.clear()
                if (!awaitEcho({ shell.sendRaw("echo $it\n") }, { probe.text() }, "Z0")) {
                    return@withShell "shell did not restart after plain exit"
                }
                null
            }
        }

        private fun shellReportsFatalSignal() = test("shell_reports_fatal_signal") {
            withShell { shell, probe ->
                val exit = AtomicInteger(-1)
                val sig = AtomicInteger(-1)
                shell.onShellExited = { c, s -> exit.set(c); sig.set(s) }
                probe.clear()
                if (!shell.sendRaw("kill -11 \$\$\n")) return@withShell "write failed"
                if (!await { sig.get() > 0 }) return@withShell "shell was never signalled"
                if (sig.get() != 11) return@withShell "expected signal 11, got ${sig.get()}"
                if (exit.get() != 139) return@withShell "expected code 139, got ${exit.get()}"
                if (!await {
                        probe.text().contains("[Process terminated by signal 11 (SIGSEGV)]")
                    }
                ) {
                    return@withShell "signal message missing (got: ${probe.text().take(300)})"
                }
                if (probe.text().contains("[Process exited with code -1]")) {
                    return@withShell "old -1 exit code bug still present"
                }
                probe.clear()
                if (!awaitEcho({ shell.sendRaw("echo $it\n") }, { probe.text() }, "SIG")) {
                    return@withShell "shell did not restart after fatal signal"
                }
                null
            }
        }

        private fun shellCtrlCKillsForegroundOnly() =
            test("shell_ctrl_c_kills_foreground_only") {
                withShell { shell, probe ->
                    probe.clear()
                    if (!shell.sendRaw(withMarker("sleep 30") + "\n")) return@withShell "write failed"
                    Thread.sleep(700)
                    if (!shell.isAlive()) return@withShell "shell died before Ctrl+C"
                    shell.sendInterrupt()
                    val started = System.currentTimeMillis()
                    /*
                     * The Ctrl+C kills the wrapped group's subshell before its
                     * marker line can run — no STUDIO_CMD_DONE ever arrives.
                     * Detect that the foreground sleep was interrupted by
                     * whether the shell accepts a follow-up command quickly
                     * (a live sleep would delay the echo by the full 30s).
                     */
                    probe.clear()
                    if (!awaitEcho({ shell.sendRaw("echo $it\n") }, { probe.text() }, "AFT", timeoutMs = 5_000)) {
                        return@withShell "foreground sleep not interrupted (${System.currentTimeMillis() - started}ms after Ctrl+C, raw: ${escaped(probe.text())})"
                    }
                    if (System.currentTimeMillis() - started > 4_000) {
                        return@withShell "Ctrl+C took ${System.currentTimeMillis() - started}ms — sleep was not interrupted"
                    }
                    if (!shell.isAlive()) return@withShell "shell died from Ctrl+C"
                    null
                }
            }

        private fun shellTwoInstancesIndependent() =
            test("shell_two_instances_independent") {
                val envCopy = env
                val s1 = PersistentShell(context, envCopy, context.filesDir)
                val s2 = PersistentShell(context, envCopy, context.cacheDir)
                val p1 = Probe(s1)
                val p2 = Probe(s2)
                try {
                    if (!s1.start() || !s2.start()) return@test "both shells must start"
                    if (!await { s1.isAlive() && s2.isAlive() }) return@test "shells not alive"
                    p1.clear(); p2.clear()
                    s1.sendRaw("echo SHELL_ONE_XYZ\n")
                    s2.sendRaw("echo SHELL_TWO_XYZ\n")
                    val ok1 = await { p1.text().contains("SHELL_ONE_XYZ") }
                    val ok2 = await { p2.text().contains("SHELL_TWO_XYZ") }
                    if (!ok1) return@test "shell 1 produced no output"
                    if (!ok2) return@test "shell 2 produced no output"
                    if (p1.text().contains("SHELL_TWO_XYZ")) return@test "output crossed shells"
                    if (p2.text().contains("SHELL_ONE_XYZ")) return@test "output crossed shells"
                    if (!s1.isAlive() || !s2.isAlive()) return@test "a shell died"
                    null
                } finally {
                    runCatching { s1.destroy() }
                    runCatching { s2.destroy() }
                    p1.close()
                    p2.close()
                }
            }

        // --------------------------------------------------------------
        // TerminalSession level (marker stripping + status + prompts)
        // --------------------------------------------------------------

        private fun sessionEchoExitStatus() = test("session_echo_exit_status") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("echo SESSION_HELLO")
                val code = awaitCommandExit(session)
                if (code == null) return@withSession "command never finished"
                if (code != 0) return@withSession "exit code $code (expected 0)"
                val text = sessionOutput(out)
                if (!text.contains("SESSION_HELLO")) {
                    return@withSession "output missing (got: ${text.take(200)})"
                }
                // The command's own text (echoed by the tty) quotes the marker
                // pattern with `$__st` — only the parsed output form counts
                // as a leak.
                if (Regex("STUDIO_CMD_DONE \\d+ ").containsMatchIn(text)) {
                    return@withSession "internal marker leaked to screen"
                }
                null
            }
        }

        private fun sessionPwdMkdirLs() = test("session_pwd_mkdir_ls") {
            withSession { session, out ->
                val target = File(context.cacheDir, "selftest_${System.nanoTime() % 100000}")
                session.sendCommand("mkdir -p ${target.absolutePath}")
                val mcode = awaitCommandExit(session)
                if (mcode != 0) return@withSession "mkdir exit=$mcode (raw: ${escaped(sessionOutput(out))})"

                out.setLength(0)
                session.sendCommand("pwd")
                if (awaitCommandExit(session) != 0) return@withSession "pwd failed"
                val pwdOut = sessionOutput(out).trim()
                if (!pwdOut.contains(context.filesDir.name)) {
                    return@withSession "pwd output unexpected: ${pwdOut.take(200)}"
                }

                session.sendCommand("ls")
                if (awaitCommandExit(session) == null) return@withSession "ls never finished"
                null
            }
        }

        private fun sessionCdUpdatesCwd() = test("session_cd_updates_cwd") {
            withSession { session, _ ->
                val target = File(context.cacheDir, "cd_selftest").apply { mkdirs() }
                session.sendCommand("cd ${target.absolutePath}")
                val code = awaitCommandExit(session)
                if (code != 0) return@withSession "cd failed (code=$code)"
                val cwd = session.promptManager.getCwd()
                if (!samePath(cwd, target.absolutePath)) {
                    return@withSession "prompt cwd=$cwd expected=${target.absolutePath}"
                }
                null
            }
        }

        private fun sessionSurvivesExit() = test("session_survives_shell_exit") {
            withSession { session, out ->
                session.sendCommand("exit")
                out.setLength(0)
                // The shell-exit handler clears the process status
                // asynchronously on the reader thread.  Wait for it before
                // queueing the post-restart echo — otherwise clearStatus can
                // clobber the new command's ProcessInfo (startProcess vs
                // clearStatus race) and its marker never resolves.
                await(5_000) { session.processTracker.currentProcess == null }
                val restarted = awaitEcho(
                    send = { token ->
                        if (session.state == TerminalSession.State.RUNNING) {
                            session.sendCommand("echo $token")
                            true
                        } else false
                    },
                    output = { sessionOutput(out) },
                    prefix = "SURV"
                )
                if (!restarted) {
                    return@withSession "shell did not restart (state=${session.state})"
                }
                if (session.state != TerminalSession.State.RUNNING) {
                    return@withSession "session state=${session.state}"
                }
                val finalCode = awaitCommandExit(session)
                if (finalCode != 0) {
                    return@withSession "post-restart command exit code=$finalCode"
                }
                null
            }
        }

        private fun sessionSurvivesCtrlC() = test("session_ctrl_c_kills_foreground") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("sleep 20")
                Thread.sleep(700)
                if (session.processTracker.currentProcess == null) {
                    return@withSession "command not tracked"
                }
                val t0 = System.currentTimeMillis()
                session.sendInterrupt()
                /*
                 * sendInterrupt resolves the tracked status immediately via
                 * cancelProcess — the wrapped group's subshell dies before its
                 * marker line can run, so no exit code is ever reported.  The
                 * kill itself is verified by the follow-up command below (a
                 * live sleep would delay it by the full 20s).
                 */
                await(3_000) { !session.processTracker.isRunning() }
                if (session.processTracker.isRunning()) {
                    return@withSession "Ctrl+C never completed the command"
                }
                if (System.currentTimeMillis() - t0 > 4_000) {
                    return@withSession "Ctrl+C too slow"
                }
                if (session.state != TerminalSession.State.RUNNING) {
                    return@withSession "session state=${session.state} after Ctrl+C"
                }
                out.setLength(0)
                session.sendCommand("echo AFTER_CTRLC")
                val after = awaitCommandExit(session)
                if (after != 0) return@withSession "command after Ctrl+C failed (code=$after)"
                if (!sessionOutput(out).contains("AFTER_CTRLC")) {
                    return@withSession "no output after Ctrl+C"
                }
                null
            }
        }

        private fun sessionJavaVersion() = test("session_java_version") {
            withSession { session, _ ->
                session.sendCommand("java -version")
                val code = awaitCommandExit(session, timeoutMs = 30_000)
                if (code == null) return@withSession "java -version never finished"
                if (code == -1) return@withSession "exit code reported as -1 (old bug)"
                if (session.state != TerminalSession.State.RUNNING) {
                    return@withSession "shell died while running java (state=${session.state})"
                }
                // 0 = JDK available, 127/126 = not installed/not executable: both OK.
                if (code != 0 && code != 127 && code != 126) {
                    return@withSession "unexpected exit code $code"
                }
                null
            }
        }

        private fun sessionLuaVersion() = test("session_lua_version") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("lua -v")
                val code = awaitCommandExit(session, timeoutMs = 30_000)
                if (code == null) return@withSession "lua -v never finished (shim loop?)"
                if (code != 0) {
                    return@withSession "lua -v exit=$code (raw: ${escaped(sessionOutput(out))})"
                }
                val text = sessionOutput(out)
                if (!text.contains("Lua 5.4")) {
                    return@withSession "expected Lua 5.4 (got: ${text.take(300)})"
                }
                null
            }
        }

        private fun sessionLuaEvalPrintsTwo() = test("session_lua_eval_prints_two") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("lua -e \"print(1+1)\"")
                val code = awaitCommandExit(session, timeoutMs = 30_000)
                if (code != 0) {
                    return@withSession "lua -e exit=$code (raw: ${escaped(sessionOutput(out))})"
                }
                val printed = sessionOutput(out).lineSequence().any { it.trim() == "2" }
                if (!printed) return@withSession "print(1+1) did not yield 2 (raw: ${escaped(sessionOutput(out))})"
                null
            }
        }

        private fun sessionLuacVersion() = test("session_luac_version") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("luac -v")
                val code = awaitCommandExit(session, timeoutMs = 30_000)
                if (code == null) return@withSession "luac -v never finished (shim loop?)"
                if (code != 0) {
                    return@withSession "luac -v exit=$code output=${sessionOutput(out).take(300)}"
                }
                val text = sessionOutput(out)
                if (!text.contains("Lua 5.4")) {
                    return@withSession "expected Lua 5.4 (got: ${text.take(300)})"
                }
                null
            }
        }

        private fun sessionLsNoCannotLink() = test("session_ls_no_cannot_link") {
            withSession { session, out ->
                out.setLength(0)
                // Neither `/` nor `/system/bin` is readable under Android app
                // data isolation (Permission denied) — the cwd is.
                session.sendCommand("ls")
                val code = awaitCommandExit(session, timeoutMs = 20_000)
                val text = sessionOutput(out)
                if (text.contains("CANNOT LINK")) {
                    return@withSession "linker failure: ${escaped(text)}"
                }
                if (code == null) return@withSession "ls never finished"
                if (code != 0) return@withSession "ls exit=$code (raw: ${escaped(text)})"
                null
            }
        }

        private fun sessionClearNoCannotLink() = test("session_clear_no_cannot_link") {
            withSession { session, out ->
                out.setLength(0)
                session.sendCommand("clear")
                val code = awaitCommandExit(session, timeoutMs = 20_000)
                val text = sessionOutput(out)
                if (text.contains("CANNOT LINK")) {
                    return@withSession "linker failure: ${escaped(text)}"
                }
                if (code == null) return@withSession "clear never finished"
                if (code != 0) return@withSession "clear exit=$code (raw: ${escaped(text)})"
                null
            }
        }

        private fun sessionShimMarkerAbsentInShell() = test("session_shim_marker_absent_in_shell") {
            withSession { session, _ ->
                // The shim exports MOBILESTUDIO_SHIM_ACTIVE only into the tool
                // process; if it ever leaks into the shell's own environment
                // every native tool would bypass the shim entirely.
                session.sendCommand("[ -z \"\$MOBILESTUDIO_SHIM_ACTIVE\" ]")
                val code = awaitCommandExit(session, timeoutMs = 10_000)
                if (code == null) return@withSession "test never finished"
                if (code != 0) return@withSession "marker leaked into shell env (exit=$code)"
                null
            }
        }

        private fun sessionTwoTabsIndependent() = test("session_two_tabs_independent") {
            val s1 = TerminalSession(context, id = "tabA-${System.nanoTime()}", initialCwd = context.filesDir)
            val s2 = TerminalSession(context, id = "tabB-${System.nanoTime()}", initialCwd = context.filesDir)
            val o1 = StringBuilder()
            val o2 = StringBuilder()
            s1.onOutput = { c -> synchronized(o1) { o1.append(c) } }
            s2.onOutput = { c -> synchronized(o2) { o2.append(c) } }
            try {
                if (!s1.start() || !s2.start()) return@test "tab start failed"
                if (!await(15_000) {
                        s1.state == TerminalSession.State.RUNNING &&
                            s2.state == TerminalSession.State.RUNNING
                    }
                ) {
                    return@test "tabs not RUNNING (A=${s1.state} B=${s2.state})"
                }
                s1.sendCommand("echo TAB_A_TOKEN")
                if (awaitCommandExit(s1) != 0) return@test "tab A command failed"
                s2.sendCommand("echo TAB_B_TOKEN")
                if (awaitCommandExit(s2) != 0) return@test "tab B command failed"
                val t1 = sessionOutput(o1)
                val t2 = sessionOutput(o2)
                if (!t1.contains("TAB_A_TOKEN")) return@test "tab A missing its output"
                if (!t2.contains("TAB_B_TOKEN")) return@test "tab B missing its output"
                if (t1.contains("TAB_B_TOKEN")) return@test "tab A saw tab B output"
                if (t2.contains("TAB_A_TOKEN")) return@test "tab B saw tab A output"
                null
            } finally {
                runCatching { s1.destroy() }
                runCatching { s2.destroy() }
            }
        }
    }

    /** Background consumer for a raw PersistentShell output channel. */
    private class Probe(shell: PersistentShell) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val buf = StringBuilder()

        init {
            scope.launch {
                try {
                    for (chunk in shell.output) {
                        synchronized(buf) { buf.append(chunk) }
                    }
                } catch (_: Throwable) {
                    // channel closed / scope cancelled
                }
            }
        }

        fun text(): String = synchronized(buf) { buf.toString() }

        fun clear() = synchronized(buf) { buf.setLength(0) }

        fun close() {
            scope.cancel()
        }
    }
}
