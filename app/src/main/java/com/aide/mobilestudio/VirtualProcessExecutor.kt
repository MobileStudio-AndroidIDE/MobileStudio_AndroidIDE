package com.aide.mobilestudio

import android.util.Log
import kotlinx.coroutines.*
import java.io.Closeable

/**
 * Main API for the Virtual Runtime Engine.
 * Executes ARM64 ELF binaries on Android with automatic dependency resolution.
 *
 * Usage:
 *   val executor = VirtualProcessExecutor(runtimeManager)
 *   executor.initialize()
 *
 *   val result = executor.execute(
 *       executable = "/path/to/java",
 *       args = listOf("-version"),
 *       cwd = "/data/data/.../files/runtime"
 *   )
 *   result.lines.forEach { println(it) }
 *   println("Exit code: ${result.exitCode}")
 *
 *   executor.close()
 */
class VirtualProcessExecutor(
    private val runtimeManager: RuntimeManager,
    private val policy: VirtualRuntimePolicy
) : Closeable {

    companion object {
        private const val TAG = "VirtualProcessExecutor"
        private const val DEFAULT_READ_TIMEOUT_MS = 1000
    }

    private var nativeHandle: Long = 0L
    private val scanner = ExecutableSecurityScanner()
    private var isInitialized = false

    data class ProcessResult(
        val exitCode: Int,
        val stdout: List<String>,
        val stderr: List<String>,
        val timedOut: Boolean = false
    )

    data class ElfInfo(
        val path: String,
        val type: String,
        val architecture: String,
        val is64Bit: Boolean,
        val interpreter: String,
        val soname: String,
        val needed: List<String>,
        val buildId: String
    )

    interface ProcessListener {
        fun onStdout(line: String)
        fun onStderr(line: String)
        fun onExit(exitCode: Int)
    }

    fun initialize() {
        if (isInitialized) return

        runtimeManager.initialize()

        nativeHandle = NativeExec.nativeInitialize(
            runtimeManager.getRuntimeDirPath(),
            runtimeManager.getNativeLibDirPath(),
            runtimeManager.context.codeCacheDir.absolutePath
        )
        isInitialized = true
        Log.i(TAG, "Initialized with handle=$nativeHandle")
    }

    fun analyzeElf(executablePath: String): ElfInfo? {
        val json = NativeExec.nativeAnalyzeElf(executablePath) ?: return null
        return parseElfInfo(json, executablePath)
    }

    fun resolveDependencies(executablePath: String): String {
        ensureInitialized()
        return NativeExec.nativeResolveDependencies(nativeHandle, executablePath)
            ?: "No dependency information available"
    }

    fun findExecutable(name: String): String? {
        ensureInitialized()
        return NativeExec.nativeFindExecutable(nativeHandle, name)
    }

    fun addSearchPath(path: String) {
        ensureInitialized()
        NativeExec.nativeAddSearchPath(nativeHandle, path)
    }

    fun getSearchPaths(): String {
        ensureInitialized()
        return NativeExec.nativeGetSearchPaths(nativeHandle)
            ?: "(empty)"
    }

    fun isArm64Elf(path: String): Boolean {
        return NativeExec.nativeIsArm64Elf(path) == 1
    }

    fun scanExecutable(path: String) = scanner.scan(path, policy)

    fun addVfsMapping(virtualPath: String, realPath: String) {
        ensureInitialized()
        NativeExec.nativeVfsAddMapping(nativeHandle, virtualPath, realPath)
    }

    fun resolveVfsPath(path: String): String {
        ensureInitialized()
        return NativeExec.nativeVfsResolvePath(nativeHandle, path)
            ?: path
    }

    fun getLinkerInfo(executable: String): String {
        ensureInitialized()
        return NativeExec.nativeGetLinkerInfo(executable)
            ?: "Linker info unavailable"
    }

    fun checkPathSecurity(path: String, root: String? = null): String {
        ensureInitialized()
        return NativeExec.nativeCheckPathSecurity(nativeHandle, path, root)
            ?: "Security check unavailable"
    }

    /**
     * Execute with a pre-execution security scan. If the scan result should
     * be blocked by the malware policy, throws an exception; if a WARN_ONLY
     * policy is active, logs a warning but proceeds.
     */
    suspend fun executeWithSecurityCheck(
        executable: String,
        args: List<String> = emptyList(),
        envVars: Map<String, String> = emptyMap(),
        cwd: String? = null,
        timeoutMs: Int = policy.timeoutMs,
        malwarePolicy: ExecutableSecurityScanner.MalwarePolicy = ExecutableSecurityScanner.MalwarePolicy.BLOCK_DANGEROUS,
        listener: ProcessListener? = null
    ): ProcessResult {
        ensureInitialized()
        val resolvedPath = findExecutable(executable)
            ?: throw IllegalArgumentException("Executable not found: $executable")

        val scanResult = scanner.scan(resolvedPath, policy)
        Log.i(TAG, "Security scan: ${scanResult.summary()}")

        if (scanner.shouldBlock(scanResult, malwarePolicy)) {
            throw SecurityException(
                "Execution blocked by policy [${scanResult.trustLevel}]: ${scanResult.summary()}"
            )
        }

        if (scanResult.trustLevel == ExecutableSecurityScanner.TrustLevel.UNTRUSTED ||
            scanResult.trustLevel == ExecutableSecurityScanner.TrustLevel.DANGEROUS) {
            Log.w(TAG, "WARNING: Executing untrusted binary: ${scanResult.summary()}")
        }

        return execute(
            executable = executable,
            args = args,
            envVars = envVars,
            cwd = cwd,
            timeoutMs = timeoutMs,
            listener = listener
        )
    }

    /**
     * Execute an ARM64 ELF binary and return all output.
     */
    suspend fun execute(
        executable: String,
        args: List<String> = emptyList(),
        envVars: Map<String, String> = emptyMap(),
        cwd: String? = null,
        timeoutMs: Int = policy.timeoutMs,
        listener: ProcessListener? = null
    ): ProcessResult = withContext(Dispatchers.IO) {
        ensureInitialized()

        val resolvedPath = findExecutable(executable)
            ?: throw IllegalArgumentException("Executable not found: $executable")

        val envArray = if (policy.inheritEnvironment) {
            val inherited = System.getenv().map { "${it.key}=${it.value}" }.toMutableList()
            envVars.forEach { (k, v) -> inherited.add("$k=$v") }
            inherited.toTypedArray()
        } else {
            envVars.map { "${it.key}=${it.value}" }.toTypedArray()
        }

        val processHandle = NativeExec.nativeExecute(
            nativeHandle,
            resolvedPath,
            args.toTypedArray(),
            envArray,
            cwd ?: runtimeManager.getRuntimeDirPath(),
            timeoutMs,
            policy.createProcessGroup
        )

        if (processHandle < 0) {
            throw RuntimeException("Failed to start process: $executable (handle=$processHandle)")
        }

        try {
            val stdout = mutableListOf<String>()
            val stderr = mutableListOf<String>()
            var timedOut = false
            cancelled = false

            val readScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val stdoutJob = readScope.async {
                readStream("stdout", processHandle, stdout, listener)
            }
            val stderrJob = readScope.async {
                readStream("stderr", processHandle, stderr, listener)
            }

            val exitCode = withTimeoutOrNull(timeoutMs.toLong()) {
                async {
                    NativeExec.nativeWaitTimeout(processHandle, timeoutMs)
                }.await()
            } ?: run {
                timedOut = true
                NativeExec.nativeKillGroup(processHandle)
                137
            }

            stdoutJob.await()
            stderrJob.await()
            cancelled = true
            readScope.cancel()

            listener?.onExit(exitCode)

            ProcessResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                timedOut = timedOut
            )
        } finally {
            NativeExec.nativeFreeProcess(processHandle)
        }
    }

    /**
     * Execute and stream output via listener (non-suspending wrapper).
     */
    fun executeStreaming(
        executable: String,
        args: List<String> = emptyList(),
        envVars: Map<String, String> = emptyMap(),
        cwd: String? = null,
        timeoutMs: Int = policy.timeoutMs,
        listener: ProcessListener
    ): Int {
        ensureInitialized()

        val resolvedPath = findExecutable(executable)
            ?: throw IllegalArgumentException("Executable not found: $executable")

        val envArray = if (policy.inheritEnvironment) {
            val inherited = System.getenv().map { "${it.key}=${it.value}" }.toMutableList()
            envVars.forEach { (k, v) -> inherited.add("$k=$v") }
            inherited.toTypedArray()
        } else {
            envVars.map { "${it.key}=${it.value}" }.toTypedArray()
        }

        val processHandle = NativeExec.nativeExecute(
            nativeHandle,
            resolvedPath,
            args.toTypedArray(),
            envArray,
            cwd ?: runtimeManager.getRuntimeDirPath(),
            timeoutMs,
            policy.createProcessGroup
        )

        if (processHandle < 0) {
            throw RuntimeException("Failed to start process: $executable")
        }

        return try {
            val exitCode = readUntilDone(processHandle, listener)
            exitCode
        } finally {
            NativeExec.nativeFreeProcess(processHandle)
        }
    }

    private var cancelled = false

    private suspend fun readStream(
        name: String,
        processHandle: Long,
        output: MutableList<String>,
        listener: ProcessListener?
    ) {
        val readFn: (Int) -> String? = when (name) {
            "stdout" -> { timeout -> NativeExec.nativeReadStdout(processHandle, timeout) }
            "stderr" -> { timeout -> NativeExec.nativeReadStderr(processHandle, timeout) }
            else -> throw IllegalArgumentException("Unknown stream: $name")
        }

        while (!cancelled) {
            val line = readFn(DEFAULT_READ_TIMEOUT_MS) ?: break
            val trimmed = line.trimEnd('\n', '\r')
            synchronized(output) { output.add(trimmed) }
            when (name) {
                "stdout" -> listener?.onStdout(trimmed)
                "stderr" -> listener?.onStderr(trimmed)
            }
        }
    }

    private fun readUntilDone(processHandle: Long, listener: ProcessListener): Int {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        while (true) {
            val stdoutLine = NativeExec.nativeReadStdout(processHandle, DEFAULT_READ_TIMEOUT_MS)
            val stderrLine = NativeExec.nativeReadStderr(processHandle, DEFAULT_READ_TIMEOUT_MS)

            if (stdoutLine != null) {
                val trimmed = stdoutLine.trimEnd('\n', '\r')
                stdout.add(trimmed)
                listener.onStdout(trimmed)
            }
            if (stderrLine != null) {
                val trimmed = stderrLine.trimEnd('\n', '\r')
                stderr.add(trimmed)
                listener.onStderr(trimmed)
            }

            if (stdoutLine == null && stderrLine == null) break
        }

        val exitCode = NativeExec.nativeWaitTimeout(processHandle, 0)
        listener.onExit(exitCode)
        return exitCode
    }

    private fun parseElfInfo(json: String, path: String): ElfInfo {
        val type = extractJsonField(json, "Type:") ?: "unknown"
        val arch = extractJsonField(json, "Architecture:") ?: "unknown"
        val is64 = extractJsonField(json, "64-bit:") == "yes"
        val interp = extractJsonField(json, "Interpreter:") ?: ""
        val soname = extractJsonField(json, "SONAME:") ?: ""
        val buildId = extractJsonField(json, "Build ID:") ?: ""

        val needed = mutableListOf<String>()
        var inNeeded = false
        for (line in json.lines()) {
            if (line.contains("Needed:")) inNeeded = true
            if (inNeeded && line.trimStart().startsWith("[")) {
                val bracketEnd = line.indexOf(']')
                if (bracketEnd > 0) {
                    needed.add(line.substring(bracketEnd + 1).trim())
                }
            }
        }

        return ElfInfo(
            path = path,
            type = type,
            architecture = arch,
            is64Bit = is64,
            interpreter = interp,
            soname = soname,
            needed = needed,
            buildId = buildId
        )
    }

    private fun extractJsonField(text: String, fieldName: String): String? {
        val idx = text.indexOf(fieldName)
        if (idx < 0) return null
        val afterField = text.substring(idx + fieldName.length)
        val value = afterField.lines().firstOrNull()?.trim() ?: return null
        return value.ifEmpty { null }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            initialize()
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            cancelled = true
            NativeExec.nativeShutdown(nativeHandle)
            nativeHandle = 0L
            isInitialized = false
            Log.i(TAG, "Closed")
        }
    }
}
