package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.BuildToolsConfig
import com.aide.mobilestudio.ExecHelper
import com.aide.mobilestudio.NativeExec
import com.aide.mobilestudio.NdkToolchain
import com.aide.mobilestudio.WindowsX64BuildBackend
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real-time auto-compile WITHOUT Gradle (BuildScheduler direct phase):
 *
 *   Kotlin  -> kotlin-compiler.jar (K2JVMCompiler) via NativeExec (memfd_fexecve, SELinux-safe)
 *   Java    -> javac via `java -m jdk.compiler/com.sun.tools.javac.Main` via NativeExec
 *   C/C++   -> ninja (incremental, when build.ninja exists)
 *              else clang -fsyntax-only (NDK clang, bundled mingw backend as last resort)
 *
 * Output lines are returned raw; BuildScheduler feeds them through
 * BuildProblemParser (kotlinc/javac/cpp/ninja formats are all supported).
 *
 * Permission model:
 *   - JVM tools never exec a script: NativeExec uses memfd_fexecve, so the
 *     app_data_file noexec/SELinux restriction can never produce EACCES.
 *   - Native tools (clang/ninja) run through ExecHelper, which loads app-data
 *     ARM64 ELFs via /system/bin/linker64, and libexec_redirect.so (LD_PRELOAD)
 *     rewrites execve calls to /system/bin/linker64 for child processes.
 */
class RealtimeCompiler(private val context: Context) {

    data class Outcome(
        val success: Boolean,
        val available: Boolean,
        val lines: List<String>,
        val durationMs: Long
    )

    private data class ToolResult(val exitCode: Int, val lines: List<String>)

    private var compileDaemon: KotlinDaemon? = null

    fun compile(projectDir: File, changedFiles: Set<File>?): Outcome {
        val start = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        var success = true
        var available = true

        val kotlinFiles = moduleSources(projectDir, changedFiles, KOTLIN_EXTS)
        val javaFiles = moduleSources(projectDir, changedFiles, JAVA_EXTS)
        val nativeFiles = if (changedFiles == null) {
            walkSources(projectDir, NATIVE_EXTS)
        } else {
            changedFiles.filter { it.extension.lowercase() in NATIVE_EXTS }
                .sortedBy { it.absolutePath }
        }

        Log.i(
            TAG,
            "compile: kotlin=${kotlinFiles.size} java=${javaFiles.size} " +
                "native=${nativeFiles.size} initial=${changedFiles == null}"
        )

        outDir("kotlinc-out")
        outDir("javac-out")
        val androidProject = isAndroidProject(projectDir)
        val hasGeneratedR = findGeneratedR(projectDir)

        // ---- Kotlin: kotlinc.jar direct ----
        if (kotlinFiles.isNotEmpty()) {
            val javaBin = KotlinCompiler.findJavaBin(context)
            val compilerJar = KotlinCompiler.findKotlinCompilerJar(context)
            if (javaBin == null || compilerJar == null) {
                available = false
                lines += "Realtime: kotlinc unavailable (java=${javaBin != null}, jar=${compilerJar != null}) - using Gradle fallback"
                Log.w(TAG, "kotlinc toolchain missing: javaBin=$javaBin jar=$compilerJar")
            } else {
                lines += "kotlinc: ${kotlinFiles.size} file(s), direct K2JVMCompiler (${compilerJar.name})"
                val out = outDir("kotlinc-out")
                // The embeddable compiler needs its stdlib/reflect/coroutines from -cp
                // and must not try (and fail) to locate kotlin-home jars.
                val runtimeJars = KotlinCompiler.compilerRuntimeJars(compilerJar)
                val cp = classpathFor(projectDir) + runtimeJars
                KotlinCompiler.ensureReadOnly(cp)
                Log.i(
                    TAG,
                    "kotlinc classpath: entries=${cp.size} androidProject=$androidProject " +
                        "androidJar=${cp.any { it.name == "android.jar" }} " +
                        "deps=${cp.count { it.parentFile?.name?.startsWith("files-2.1") == true }} " +
                        "cp=${cp.joinToString(File.pathSeparator).take(500)}"
                )
                val programArgs = buildList {
                    addAll(kotlinFiles.map { it.absolutePath })
                    add("-d"); add(out.absolutePath)
                    if (cp.isNotEmpty()) {
                        add("-cp"); add(cp.joinToString(File.pathSeparator))
                    }
                    add("-no-stdlib")
                }
                val jvmArgs = listOf("-Xmx${BuildToolsConfig.kotlinCompilerHeapMb(context)}m")
                val runtimeCp = (listOf(compilerJar) + runtimeJars)
                    .joinToString(File.pathSeparator) { it.absolutePath }
                val launcher = listOf("-cp", runtimeCp, KOTLIN_MAIN)
                val r = runKotlinTool(javaBin, jvmArgs, launcher, programArgs, projectDir, compilerJar)
                lines += "kotlinc exit=${r.exitCode}"
                val softened = softenGeneratedRefs(r.lines, androidProject, hasGeneratedR)
                lines += softened
                if (hasHardFailure(r.exitCode, softened)) success = false
            }
        }

        // ---- Java: javac direct (in-JVM module, no script exec) ----
        if (javaFiles.isNotEmpty()) {
            val javaBin = KotlinCompiler.findJavaBin(context)
            if (javaBin == null) {
                available = false
                lines += "Realtime: java runtime unavailable - using Gradle fallback"
                Log.w(TAG, "javac toolchain missing: no java bin")
            } else {
                lines += "javac: ${javaFiles.size} file(s), direct jdk.compiler module"
                val out = outDir("javac-out")
                val cp = classpathFor(projectDir)
                KotlinCompiler.ensureReadOnly(cp)
                val programArgs = buildList {
                    add("-d"); add(out.absolutePath)
                    add("-encoding"); add("UTF-8")
                    if (cp.isNotEmpty()) {
                        add("-cp"); add(cp.joinToString(File.pathSeparator))
                    }
                    addAll(javaFiles.map { it.absolutePath })
                }
                val jvmArgs = listOf("-Xmx${BuildToolsConfig.kotlinCompilerHeapMb(context)}m")
                val launcher = listOf("-m", JAVAC_MODULE)
                val r = runJvmTool(javaBin, jvmArgs, launcher, programArgs, projectDir)
                lines += "javac exit=${r.exitCode}"
                val softened = softenGeneratedRefs(r.lines, androidProject, hasGeneratedR)
                lines += softened
                if (hasHardFailure(r.exitCode, softened)) success = false
            }
        }

        // ---- C/C++: ninja or clang ----
        if (nativeFiles.isNotEmpty()) {
            val (ok, nativeLines) = runNative(projectDir, nativeFiles)
            lines += nativeLines
            if (!ok) success = false
        }

        return Outcome(
            success = success,
            available = available,
            lines = capLines(lines),
            durationMs = System.currentTimeMillis() - start
        )
    }

    // ------------------------------------------------------------------
    // Kotlin / Java tool invocation
    // ------------------------------------------------------------------

    /**
     * Kotlin compile through the persistent daemon first - a warm compiler JVM
     * turns a multi-second JVM fork + jar load per save into a sub-second
     * request. Falls back to the one-shot fork whenever the daemon cannot
     * serve the call (first save, cold start, crash).
     */
    private fun runKotlinTool(
        javaBin: File,
        jvmArgs: List<String>,
        launcher: List<String>,
        programArgs: List<String>,
        cwd: File,
        compilerJar: File
    ): ToolResult {
        val daemon = compileDaemon(javaBin, compilerJar)
        if (daemon != null) {
            val res = runCatching {
                runBlocking { daemon.compile(programArgs, JVM_TOOL_TIMEOUT_MS) }
            }.getOrNull()
            if (res != null) {
                Log.i(TAG, "kotlinc via daemon exit=${res.exitCode}, lines=${res.lines.size}")
                res.lines.take(40).forEach { Log.i(TAG, "kotlinc out: ${it.take(400)}") }
                return ToolResult(res.exitCode, res.lines)
            }
            Log.w(TAG, "daemon compile unavailable, using fork")
        }
        return runJvmTool(javaBin, jvmArgs, launcher, programArgs, cwd)
    }

    private fun compileDaemon(javaBin: File, compilerJar: File): KotlinDaemon? {
        compileDaemon?.let { return it }
        return try {
            val config = AnalysisConfig(
                enableKotlinDaemon = true,
                enableKotlinCompiler = true,
                daemonHeapMb = BuildToolsConfig.kotlinCompilerHeapMb(context),
                daemonIdleTimeoutMs = COMPILE_DAEMON_IDLE_MS
            )
            val daemon = KotlinDaemon(context, config, tag = "rt")
            daemon.start(compilerJar.parentFile ?: compilerJar, javaBin, compilerJar)
            // First launch pays wrapper-javac + JVM boot; wait for the handshake
            // so the very first save reuses the daemon instead of racing into
            // a one-shot fork.
            if (!daemon.awaitReady(COMPILE_DAEMON_READY_MS)) {
                Log.w(TAG, "compile daemon not ready within ${COMPILE_DAEMON_READY_MS}ms, using fork")
            }
            compileDaemon = daemon
            startIdleMonitor(daemon)
            daemon
        } catch (e: Exception) {
            Log.e(TAG, "compile daemon init failed", e)
            null
        }
    }

    private fun startIdleMonitor(daemon: KotlinDaemon) {
        Thread {
            while (true) {
                try {
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (daemon.isIdle() && daemon.isAlive()) {
                    Log.i(TAG, "compile daemon idle > ${COMPILE_DAEMON_IDLE_MS}ms, stopping")
                    daemon.stop()
                }
            }
        }.apply { isDaemon = true; name = "RealtimeKotlinDaemonIdle" }.start()
    }

    private fun runJvmTool(
        javaBin: File,
        jvmArgs: List<String>,
        launcher: List<String>,
        programArgs: List<String>,
        cwd: File,
        timeoutMs: Long = JVM_TOOL_TIMEOUT_MS
    ): ToolResult {
        // Launch through ExecHelper (sh script path) exactly like the daemon:
        // NativeExec's fork+JLI strategy aborts in DexPathList when the JVM
        // classpath holds plain (non-dex) jars, while the shim launcher runs
        // the very same toolchain fine.
        return runProcess(
            listOf(javaBin.absolutePath) + jvmArgs + launcher + programArgs,
            cwd,
            timeoutMs
        )
    }

    // ------------------------------------------------------------------
    // Native (ninja / clang)
    // ------------------------------------------------------------------

    private fun runNative(projectDir: File, files: List<File>): Pair<Boolean, List<String>> {
        val lines = mutableListOf<String>()

        // 1) Incremental ninja build when a configured build.ninja exists
        val ninjaDirs = findNinjaDirs(projectDir)
        val ninjaBin = findNinjaBin()
        if (ninjaDirs.isNotEmpty() && ninjaBin != null) {
            lines += "ninja: incremental build (${ninjaDirs.size} build dir(s), ${ninjaBin.absolutePath})"
            var ok = true
            for (dir in ninjaDirs) {
                val r = runProcess(
                    listOf(ninjaBin.absolutePath, "-C", dir.absolutePath),
                    cwd = dir,
                    timeoutMs = NATIVE_TIMEOUT_MS
                )
                lines += "ninja -C ${dir.name}: exit=${r.exitCode}"
                lines += r.lines
                if (r.exitCode != 0) ok = false
            }
            return ok to lines
        }
        if (ninjaDirs.isNotEmpty()) {
            lines += "ninja: build.ninja found but ninja binary missing - falling back to clang syntax check"
        }

        // 2) clang -fsyntax-only per changed file (NDK clang preferred)
        val ndk = NdkToolchain.discoverInstalled(context).firstOrNull()
        val ndkClang = ndk?.let { NdkToolchain.clangPath(it) }?.takeIf { it.isFile }
        if (ndk != null && ndkClang != null) {
            lines += "clang: syntax check ${files.size} file(s) (NDK ${ndk.name})"
            var ok = true
            val api = BuildToolsConfig.targetApi(context)
            val std = BuildToolsConfig.cppStandard(context)
            for (f in files) {
                val args = buildList {
                    add(ndkClang.absolutePath)
                    add("--target=${NdkToolchain.targetFor(api)}")
                    add("--sysroot=${NdkToolchain.sysrootDir(ndk).absolutePath}")
                    add("-fsyntax-only")
                    add("-fno-color-diagnostics")
                    if (std.isNotBlank()) add("-std=$std")
                    add("-I${File(projectDir, "app/src/main/cpp").absolutePath}")
                    add("-I${projectDir.absolutePath}")
                    add(f.absolutePath)
                }
                val r = runProcess(args, cwd = projectDir, timeoutMs = BuildToolsConfig.clangTimeoutMs(context))
                lines += r.lines
                if (r.exitCode != 0) {
                    ok = false
                    if (r.lines.isEmpty()) {
                        lines += "${f.absolutePath}:1:1: error: clang exited with code ${r.exitCode} and no output"
                    }
                }
            }
            return ok to lines
        }

        // 3) Bundled (mingw) backend for EXE-style projects
        lines += "clang: syntax check ${files.size} file(s) (bundled backend)"
        var ok = true
        for (f in files) {
            val r = runBlocking {
                ClangCompiler(context).checkSyntax(file = f, source = null, projectDir = projectDir)
            }
            for (d in r.diagnostics) {
                val sev = when (d.severity) {
                    DiagnosticSeverity.ERROR -> "error"
                    DiagnosticSeverity.WARNING -> "warning"
                    else -> "note"
                }
                lines += "${d.filePath}:${d.line}:${d.column}: $sev: ${d.message}"
                if (d.severity == DiagnosticSeverity.ERROR) ok = false
            }
            if (r.diagnostics.isEmpty() && r.rawOutput.isNotBlank()) {
                r.rawOutput.lineSequence().filter { it.isNotBlank() }.take(20).forEach { lines += it }
                if (!r.success) ok = false
            }
        }
        return ok to lines
    }

    private fun runProcess(args: List<String>, cwd: File, timeoutMs: Long): ToolResult {
        return try {
            val env = mutableMapOf<String, String>()
            val execRedirect = File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so")
            if (execRedirect.exists()) env["LD_PRELOAD"] = execRedirect.absolutePath

            val resolved = wrapScriptIfNeeded(args)
            val pb = if (resolved[0].startsWith("/system/")) {
                ProcessBuilder(resolved).apply {
                    directory(cwd)
                    environment().putAll(env)
                }
            } else {
                ExecHelper.createProcess(context, resolved, cwd, env)
            }.redirectErrorStream(true)

            val process = pb.start()
            val out = java.util.Collections.synchronizedList(mutableListOf<String>())
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            out += line!!.take(MAX_LINE_CHARS)
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; name = "RealtimeToolReader"; start() }

            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                out += "Realtime: ${File(args[0]).name} timed out after ${timeoutMs / 1000}s"
            }
            readerThread.join(3000)
            ToolResult(if (exited) process.exitValue() else -1, out.toList())
        } catch (e: Exception) {
            ToolResult(-1, listOf("Realtime: exec failed for ${args[0]}: ${e.message}"))
        }
    }

    /** Scripts (#!/...) in app data cannot be execve'd directly (SELinux EACCES) - run via sh. */
    private fun wrapScriptIfNeeded(args: List<String>): List<String> {
        val tool = File(args[0])
        if (!tool.isFile || tool.length() < 2) return args
        val head = runCatching {
            java.io.RandomAccessFile(tool, "r").use { raf ->
                val b = ByteArray(2)
                raf.readFully(b)
                String(b)
            }
        }.getOrNull() ?: return args
        return if (head.startsWith("#!")) {
            listOf("/system/bin/sh", tool.absolutePath) + args.drop(1)
        } else {
            args
        }
    }

    // ------------------------------------------------------------------
    // ninja discovery
    // ------------------------------------------------------------------

    private fun findNinjaDirs(projectDir: File): List<File> {
        val roots = listOf(
            File(projectDir, ".cxx"),
            File(projectDir, "app/.cxx"),
            File(projectDir, "app/build/intermediates/cxx"),
            File(projectDir, "build/intermediates/cxx")
        )
        val dirs = mutableListOf<File>()
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(7)
                .filter { it.isFile && it.name == "build.ninja" }
                .forEach { dirs += it.parentFile }
        }
        return dirs.distinctBy { it.absolutePath }
            .sortedByDescending { it.absolutePath.contains("arm64") }
            .take(3)
    }

    private fun findNinjaBin(): File? {
        val candidates = mutableListOf<File>()
        val sdkRoots = listOf(
            File(context.filesDir, "toolchain/sdk/active"),
            File(context.filesDir, "toolchain/sdk"),
            System.getenv("ANDROID_HOME")?.let { File(it) },
            System.getenv("ANDROID_SDK_ROOT")?.let { File(it) }
        ).filterNotNull()
        for (root in sdkRoots) {
            val cmake = File(root, "cmake")
            cmake.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.forEach { candidates += File(it, "bin/ninja") }
        }
        // CmakeRuntimeManager layout: <files>/toolchain/cmake/<version>/bin/ninja
        File(context.filesDir, "toolchain/cmake").listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.forEach { candidates += File(it, "bin/ninja") }
        // Arm64ToolsDownloader layout: <files>/toolchain/arm64-tools/cmake-arm64/bin/ninja
        candidates += File(context.filesDir, "toolchain/arm64-tools/cmake-arm64/bin/ninja")
        return candidates.firstOrNull { it.isFile }
    }

    // ------------------------------------------------------------------
    // Sources / classpath
    // ------------------------------------------------------------------

    private fun moduleSources(projectDir: File, changedFiles: Set<File>?, exts: Set<String>): List<File> {
        if (changedFiles == null) return walkSources(projectDir, exts)
        val hits = changedFiles
            .filter { it.isFile && it.extension.lowercase() in exts && !isGradleScript(it) }
            .sortedBy { it.absolutePath }
            .take(MAX_SOURCES)
        if (hits.isEmpty()) return emptyList()
        // Cold start (no previous outputs): one full pass so cross-file references
        // resolve. After that only the changed files are recompiled - outputs of
        // earlier runs stay on the classpath - turning a full-project walk into
        // a single-file compile.
        val outName = if (exts == KOTLIN_EXTS) "kotlinc-out" else "javac-out"
        val outDir = File(context.cacheDir, "realtime/$outName")
        val warm = outDir.isDirectory && outDir.walkTopDown().any { it.isFile && it.extension == "class" }
        return if (warm) hits else walkSources(projectDir, exts)
    }

    private fun walkSources(projectDir: File, exts: Set<String>): List<File> =
        projectDir.walkTopDown()
            .onEnter { it.name !in EXCLUDED_DIRS }
            .filter { it.isFile && it.extension.lowercase() in exts && !isGradleScript(it) }
            .take(MAX_SOURCES)
            .toList()

    private fun isGradleScript(f: File): Boolean =
        f.name.startsWith("build.gradle") || f.name.startsWith("settings.gradle")

    private fun isAndroidProject(projectDir: File): Boolean =
        File(projectDir, "app/src/main/AndroidManifest.xml").isFile ||
            File(projectDir, "src/main/AndroidManifest.xml").isFile ||
            File(projectDir, "AndroidManifest.xml").isFile

    private fun findGeneratedR(projectDir: File): Boolean {
        val roots = listOf(File(projectDir, "app/build"), File(projectDir, "build"))
        for (root in roots) {
            if (!root.isDirectory) continue
            val found = root.walkTopDown()
                .maxDepth(6)
                .any { it.isFile && it.name == "R.jar" }
            if (found) return true
        }
        return false
    }

    private fun classpathFor(projectDir: File): List<File> {
        val list = mutableListOf<File>()
        listOf(
            "app/build/tmp/kotlin-classes/debug",
            "app/build/tmp/kotlin-classes/release",
            "app/build/intermediates/javac/debug/classes",
            "app/build/intermediates/javac/release/classes",
            "app/build/generated/source/buildConfig/debug",
            "app/build/generated/source/buildConfig/release",
            "build/tmp/kotlin-classes/debug",
            "build/intermediates/javac/debug/classes"
        ).forEach { rel -> File(projectDir, rel).takeIf { it.isDirectory }?.let(list::add) }

        // Generated symbol tables (R / BuildConfig) and AAR-extracted classes
        // (app/build/intermediates/**/classes.jar) from a previous Gradle build
        for (root in listOf(File(projectDir, "app/build"), File(projectDir, "build"))) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(7)
                .filter {
                    it.isFile && (it.name == "R.jar" || it.name == "BuildConfig.jar" ||
                        it.name == "classes.jar")
                }
                .take(30)
                .forEach { list += it }
        }

        File(context.cacheDir, "realtime/kotlinc-out").takeIf { it.isDirectory }?.let(list::add)
        File(context.cacheDir, "realtime/javac-out").takeIf { it.isDirectory }?.let(list::add)

        if (isAndroidProject(projectDir)) {
            findAndroidJar()?.let(list::add)
        }
        list += dependencyJars()
        return list.distinctBy { it.absolutePath }
    }

    private fun findAndroidJar(): File? = KotlinCompiler.findAndroidJar(context)

    private fun dependencyJars(): List<File> = KotlinCompiler.dependencyJars(context)

    /**
     * Fresh Android projects cannot resolve their library classpath yet:
     * no dependency cache (first Gradle build never ran) and/or no generated
     * R/BuildConfig. Downgrade those unresolved-reference diagnostics to
     * warnings with a hint instead of flooding the Problems panel - Gradle
     * itself would not report them once it has resolved dependencies.
     * Handles both kotlinc (`e: path:...`) and javac-style (`path:l:c: error:`).
     */
    private fun softenGeneratedRefs(
        lines: List<String>,
        androidProject: Boolean,
        hasGeneratedR: Boolean
    ): List<String> {
        if (!androidProject) return lines
        val depsMissing = dependencyJars().isEmpty()
        if (!depsMissing && hasGeneratedR) return lines
        return lines.map { line ->
            val classpathNoise = UNRESOLVED_ANY.containsMatchIn(line) ||
                INFER_TYPE_PARAM.containsMatchIn(line)
            if (!classpathNoise) return@map line
            when {
                !hasGeneratedR && UNRESOLVED_GENERATED.containsMatchIn(line) ->
                    demoteDiagnostic(line, "(generated classes missing - run Build once)")
                depsMissing ->
                    demoteDiagnostic(line, "(dependency classpath empty - run Build once)")
                else -> line
            }
        }
    }

    /** `e: ...` (kotlinc) or `path:line:col: error: ...` (javac-style). */
    private fun isErrorDiagnostic(line: String): Boolean = ERROR_LINE.containsMatchIn(line)

    private fun demoteDiagnostic(line: String, hint: String): String = when {
        line.startsWith("e:") -> "w: " + line.substring(2) + " " + hint
        ERROR_LINE.containsMatchIn(line) -> line.replaceFirst(": error:", ": warning:") + " " + hint
        else -> line
    }

    /**
     * Failure only when a real diagnostic survived softening or the tool
     * died (signal / no output) - exit code alone lies once known-missing
     * classpath noise has been demoted to warnings.
     */
    private fun hasHardFailure(exitCode: Int, lines: List<String>): Boolean {
        if (exitCode < 0 || exitCode > 128) return true
        if (lines.any { isErrorDiagnostic(it) }) return true
        return exitCode != 0 && lines.isEmpty()
    }

    private fun outDir(name: String): File =
        File(context.cacheDir, "realtime/$name").apply { mkdirs() }

    private fun capLines(lines: List<String>): List<String> =
        if (lines.size <= MAX_OUTPUT_LINES) lines
        else lines.take(MAX_OUTPUT_LINES) + "... (+${lines.size - MAX_OUTPUT_LINES} lines truncated)"

    companion object {
        private const val TAG = "RealtimeCompiler"
        private const val KOTLIN_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        private const val JAVAC_MODULE = "jdk.compiler/com.sun.tools.javac.Main"
        private const val MAX_SOURCES = 600
        private const val MAX_OUTPUT_LINES = 600
        private const val MAX_LINE_CHARS = 4_000
        private const val JVM_TOOL_TIMEOUT_MS = 300_000L
        private const val COMPILE_DAEMON_IDLE_MS = 120_000L
        private const val COMPILE_DAEMON_READY_MS = 15_000L
        private const val NATIVE_TIMEOUT_MS = 300_000L

        private val KOTLIN_EXTS = setOf("kt")
        private val JAVA_EXTS = setOf("java")
        private val NATIVE_EXTS = setOf("c", "cc", "cpp", "cxx", "m", "mm", "h", "hpp", "hh", "hxx")
        private val EXCLUDED_DIRS = setOf(
            ".git", ".gradle", "build", ".cxx", ".idea", "node_modules",
            ".buildozer", "out", "captures", ".kotlin"
        )
        private val UNRESOLVED_GENERATED = Regex("""unresolved reference '(R|BuildConfig)'""")
        private val UNRESOLVED_ANY = Regex("""unresolved reference '[^']+'""")
        private val INFER_TYPE_PARAM = Regex("""cannot infer type for type parameter""")
        private val ERROR_LINE = Regex("""^(e:|\S[^:\s]*:\d+:\d+: error:)""")
    }
}
