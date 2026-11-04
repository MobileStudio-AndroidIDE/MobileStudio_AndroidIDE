package com.aide.mobilestudio

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

object ShellRunner {
    data class Result(
        val exitCode: Int,
        val outputLines: List<String>,
        val strategy: String,
        val timedOut: Boolean = false,
        val error: String = ""
    )

    // --- Direct execution (always uses /system/bin/sh for reliability) ---
    fun runDirect(
        context: Context,
        command: String,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long = -1L,
        onLine: (String) -> Unit,
    ): Result? {
        val actualTimeout = if (timeoutMs < 0) BuildToolsConfig.shellDefaultTimeoutMs(context) else timeoutMs
        // Always delegate to runSh() for reliability.
        // Direct exec has issues with: noexec mounts, PATH resolution, shell features.
        // /system/bin/sh handles all of these correctly.
        return runSh(context, command, cwd, env, actualTimeout, onLine)
    }

    /** Parses a simple command without shell operators. Returns null if operators present. */
    private fun parseSimpleCommand(command: String): List<String>? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return emptyList()
        
        // Check for shell operators that require sh
        if (SHELL_OPERATORS.matcher(trimmed).find()) return null
        
        // Parse respecting quotes
        return parseQuotedArgs(trimmed)
    }

    /** Public wrapper: parses a string into argv respecting quotes/escapes. */
    fun parseArgsForExecution(command: String): List<String> = parseQuotedArgs(command.trim())

    /** Parses arguments respecting single/double quotes and escapes. */
    private fun parseQuotedArgs(input: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false
        
        for (c in input) {
            when {
                escape -> { sb.append(c); escape = false }
                c == '\\' && !inSingle -> escape = true
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    private val SHELL_OPERATORS = Pattern.compile("[|&;<>()$`\\\\]") // Note: backslash is escape in regex

    fun runSh(
        context: Context,
        command: String,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long = -1L,
        onLine: (String) -> Unit,
    ): Result {
        val actualTimeout = if (timeoutMs < 0) BuildToolsConfig.shellDefaultTimeoutMs(context) else timeoutMs
        val processResult = runCatching {
            runWithArgs(
                args = listOf("/system/bin/sh", "-c", command),
                cwd = cwd,
                env = env,
                onLine = onLine,
                timeoutMs = actualTimeout,
                strategy = "exec-helper",
                context = context
            )
        }.getOrElse { throwable ->
            Result(
                exitCode = 127,
                outputLines = listOf("ExecHelper failed: ${throwable.message ?: throwable.javaClass.simpleName}"),
                strategy = "exec-helper",
                error = throwable.message ?: throwable.javaClass.simpleName
            )
        }
        return processResult
    }

    /**
     * Runs an explicit argv list through ExecHelper (auto linker64 for ARM64 ELF
     * in app data + auto LD_LIBRARY_PATH). Used by `studio` / StudioShell.
     */
    fun runArgs(
        context: Context,
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long = -1L,
        onLine: (String) -> Unit,
    ): Result {
        val actualTimeout = if (timeoutMs < 0) BuildToolsConfig.shellDefaultTimeoutMs(context) else timeoutMs
        if (args.isEmpty()) return Result(0, emptyList(), "studioshell")
        return runCatching {
            runWithArgs(
                args = args,
                cwd = cwd,
                env = env,
                onLine = onLine,
                timeoutMs = actualTimeout,
                strategy = "studioshell",
                context = context
            )
        }.getOrElse { throwable ->
            Result(
                exitCode = 127,
                outputLines = listOf("StudioShell failed: ${throwable.message ?: throwable.javaClass.simpleName}"),
                strategy = "studioshell",
                error = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    private fun runWithProcessBuilder(
        command: String,
        cwd: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        timeoutMs: Long
    ): Result {
        return runWithArgs(
            args = listOf("/system/bin/sh", "-c", command),
            cwd = cwd,
            env = env,
            onLine = onLine,
            timeoutMs = timeoutMs,
            strategy = "process-builder"
        )
    }

    private fun runWithProcessBuilder(
        pb: ProcessBuilder,
        cwd: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        timeoutMs: Long,
        strategy: String
    ): Result {
        val process = pb.redirectErrorStream(true).start()

        val lines = mutableListOf<String>()
        val readError = AtomicReference<String>("")
        val lineCounter = IntArray(1)
        val reader = Thread {
            runCatching {
                readStream(process.inputStream, lines, onLine, lineCounter)
            }.onFailure { throwable ->
                readError.set(throwable.message ?: throwable.javaClass.simpleName)
            }
        }.apply {
            name = "MobileStudioShellReader"
            isDaemon = true
            start()
        }

        val completed = if (timeoutMs > 0) {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            process.waitFor()
            true
        }
        if (!completed) {
            process.destroyForcibly()
            reader.join(1_000L)
            return Result(
                exitCode = -1,
                outputLines = lines + "Timed out after ${timeoutMs / 1000}s",
                strategy = strategy,
                timedOut = true,
                error = "timeout"
            )
        }
        reader.join(1_000L)
        val error = readError.get()
        return Result(
            exitCode = process.exitValue(),
            outputLines = if (error.isBlank()) lines else lines + "Output read error: $error",
            strategy = strategy,
            error = error
        )
    }

    private fun runWithArgs(
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        timeoutMs: Long,
        strategy: String,
        context: Context? = null
    ): Result {
        val pb = if (context != null) {
            ExecHelper.createProcess(context, args, cwd, env)
        } else {
            ProcessBuilder(args)
                .directory(cwd)
                .apply { environment().putAll(env) }
        }
        
        val process = pb.redirectErrorStream(true).start()

        val lines = mutableListOf<String>()
        val readError = AtomicReference<String>("")
        val lineCounter = IntArray(1)
        val reader = Thread {
            runCatching {
                readStream(process.inputStream, lines, onLine, lineCounter)
            }.onFailure { throwable ->
                readError.set(throwable.message ?: throwable.javaClass.simpleName)
            }
        }.apply {
            name = "MobileStudioShellReader"
            isDaemon = true
            start()
        }

        val completed = if (timeoutMs > 0) {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } else {
            process.waitFor()
            true
        }
        if (!completed) {
            process.destroyForcibly()
            reader.join(1_000L)
            return Result(
                exitCode = -1,
                outputLines = lines + "Timed out after ${timeoutMs / 1000}s",
                strategy = strategy,
                timedOut = true,
                error = "timeout"
            )
        }
        reader.join(1_000L)
        val error = readError.get()
        return Result(
            exitCode = process.exitValue(),
            outputLines = if (error.isBlank()) lines else lines + "Output read error: $error",
            strategy = strategy,
            error = error
        )
    }

    private fun resolveBinaryPath(binary: String, path: String): String? {
        for (dir in path.split(":")) {
            val file = File(dir, binary)
            if (file.isFile) return file.absolutePath
        }
        return null
    }

    private fun readStream(
        input: InputStream,
        lines: MutableList<String>,
        onLine: (String) -> Unit,
        lineCounter: IntArray
    ) {
        val buffer = ByteArray(4096)
        val lineBuffer = StringBuilder()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            appendChunk(String(buffer, 0, read), lines, lineBuffer, onLine, lineCounter)
        }
        flushLineBuffer(lines, lineBuffer, onLine, lineCounter)
    }

    private fun appendChunk(
        rawChunk: String,
        lines: MutableList<String>,
        lineBuffer: StringBuilder,
        onLine: (String) -> Unit,
        lineCounter: IntArray
    ) {
        lineBuffer.append(rawChunk.replace('\r', '\n'))
        while (true) {
            val index = lineBuffer.indexOf("\n")
            if (index < 0) break
            val line = lineBuffer.substring(0, index)
            lineBuffer.delete(0, index + 1)
            emitLine(line, lines, onLine, lineCounter)
        }
        if (lineBuffer.length > MAX_BUFFERED_LINE_CHARS) {
            flushLineBuffer(lines, lineBuffer, onLine, lineCounter)
        }
    }

    private fun flushLineBuffer(
        lines: MutableList<String>,
        lineBuffer: StringBuilder,
        onLine: (String) -> Unit,
        lineCounter: IntArray
    ) {
        if (lineBuffer.isEmpty()) return
        val line = lineBuffer.toString()
        lineBuffer.setLength(0)
        emitLine(line, lines, onLine, lineCounter)
    }

    private fun emitLine(
        rawLine: String,
        lines: MutableList<String>,
        onLine: (String) -> Unit,
        lineCounter: IntArray
    ) {
        val line = rawLine.trimEnd()
        if (line.isBlank()) return
        lines += line
        if (lines.size > MAX_OUTPUT_LINES) {
            lines.removeAt(0)
        }
        lineCounter[0]++
        // Throttle UI updates: only emit every 20th line to keep the UI responsive
        // during long builds while still keeping the log buffered in memory.
        if (lineCounter[0] % 20 == 0) {
            onLine(line)
        }
    }

    private const val MAX_OUTPUT_LINES = 8_000
    private const val MAX_BUFFERED_LINE_CHARS = 16_384
}
