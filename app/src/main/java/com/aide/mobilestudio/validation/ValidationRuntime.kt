package com.aide.mobilestudio.validation

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation shared by the inspector, checks and process runs. */
class CancellationSignal {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()
}

data class ToolExecution(
    val command: List<String>,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val failedToStart: String?
) {
    val started: Boolean get() = failedToStart == null
}

interface ProcessRunner {
    /**
     * Runs [command] to completion, [timeoutMs] expiry or cancellation.
     * stdout+stderr are merged into [ToolExecution.output].
     */
    fun run(
        command: List<String>,
        cwd: File?,
        timeoutMs: Long,
        cancel: CancellationSignal
    ): ToolExecution
}

/**
 * Shared implementation for process runners: builds the ProcessBuilder through a
 * subclass hook (so the Android variant can apply ExecHelper/noexec handling),
 * drains the merged output on a reader thread (avoids pipe-buffer deadlock) and
 * enforces the timeout/cancellation deadline on the main loop.
 */
abstract class StreamProcessRunner : ProcessRunner {

    protected abstract fun createProcessBuilder(command: List<String>, cwd: File?): ProcessBuilder

    override fun run(
        command: List<String>,
        cwd: File?,
        timeoutMs: Long,
        cancel: CancellationSignal
    ): ToolExecution {
        if (command.isEmpty()) {
            return ToolExecution(command, -1, "", false, false, "empty command")
        }
        if (cancel.isCancelled()) {
            return ToolExecution(command, -1, "", false, true, null)
        }
        val process = try {
            createProcessBuilder(command, cwd).start()
        } catch (e: Exception) {
            return ToolExecution(command, -1, "", false, false, e.message ?: e.javaClass.simpleName)
        }

        val output = StringBuilder()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(output) { output.append(buf, 0, n) }
                    }
                }
            } catch (_: Exception) {
                // Stream closed by destroy(); keep whatever we captured.
            }
        }.apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        var cancelledRun = false
        var exitCode = -1
        while (true) {
            if (cancel.isCancelled()) {
                cancelledRun = true
                process.destroy()
                break
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                timedOut = true
                process.destroy()
                break
            }
            try {
                if (process.waitFor(minOf(200L, remaining), TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue()
                    break
                }
            } catch (e: InterruptedException) {
                cancelledRun = true
                process.destroy()
                break
            }
        }
        if (timedOut || cancelledRun) {
            // Give it a moment to die, then force.
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            } catch (_: Exception) {
                process.destroyForcibly()
            }
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        }
        try {
            reader.join(2000L)
        } catch (_: Exception) {
        }
        val text = synchronized(output) { output.toString() }
        return ToolExecution(command, exitCode, text, timedOut, cancelledRun, null)
    }
}

/** Host/test runner: plain ProcessBuilder. Also serves as device fallback. */
class SystemProcessRunner : StreamProcessRunner() {
    override fun createProcessBuilder(command: List<String>, cwd: File?): ProcessBuilder =
        ProcessBuilder(command).apply {
            redirectErrorStream(true)
            if (cwd != null) directory(cwd)
        }
}
