package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.BuildToolsConfig
import com.aide.mobilestudio.JreGradleInstaller
import com.aide.mobilestudio.NativeExec
import com.aide.mobilestudio.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

class KotlinCompiler(
    private val context: Context,
    private val config: AnalysisConfig,
    private val kotlinDaemon: KotlinDaemon? = null
) {
    data class CompilerResult(
        val diagnostics: List<Diagnostic>,
        val success: Boolean,
        val durationMs: Long,
        val rawOutput: String
    )

    private val kotlincCache = mutableMapOf<String, File>()

    suspend fun analyze(file: File, source: String, classpath: List<File> = emptyList()): CompilerResult {
        if (!config.enableKotlinCompiler) {
            return CompilerResult(emptyList(), true, 0, "Compiler disabled")
        }
        val start = System.currentTimeMillis()
        return try {
            withTimeout(config.compilerTimeoutMs) {
                withContext(Dispatchers.IO) {
                    // Try daemon first (persistent JVM ??no per-file startup overhead)
                    if (kotlinDaemon != null && classpath.isEmpty()) {
                        val daemonOutput = kotlinDaemon.analyze(file, source, config.compilerTimeoutMs)
                        if (daemonOutput != null) {
                            val diags = softenClasspathNoise(
                                parseCompilerOutput(daemonOutput, file.absolutePath),
                                file
                            )
                            return@withContext CompilerResult(
                                diagnostics = diags,
                                success = diags.none { it.severity == DiagnosticSeverity.ERROR },
                                durationMs = System.currentTimeMillis() - start,
                                rawOutput = daemonOutput
                            )
                        }
                    }
                    // Fallback: per-process kotlinc
                    runPerProcess(file, source, classpath)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Timeout (not job cancellation) still produces a normal result —
            // the caller wants a diagnostic for it.
            CompilerResult(
                diagnostics = listOf(
                    Diagnostic(
                        filePath = file.absolutePath,
                        line = 1,
                        column = 1,
                        length = 0,
                        message = "Compiler analysis timed out after ${config.compilerTimeoutMs} ms",
                        severity = DiagnosticSeverity.INFO,
                        kind = DiagnosticKind.OTHER
                    )
                ),
                success = false,
                durationMs = System.currentTimeMillis() - start,
                rawOutput = "Timeout: ${e.message}"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Job cancellation must propagate — swallowing it here turns a
            // cancelled analysis into a fake result that later gets emitted.
            throw e
        } catch (e: Exception) {
            CompilerResult(
                diagnostics = listOf(
                    Diagnostic(
                        filePath = file.absolutePath,
                        line = 1,
                        column = 1,
                        length = 0,
                        message = "Compiler analysis failed: ${e.message ?: e.javaClass.simpleName}",
                        severity = DiagnosticSeverity.INFO,
                        kind = DiagnosticKind.OTHER
                    )
                ),
                success = false,
                durationMs = System.currentTimeMillis() - start,
                rawOutput = "Exception: ${e.message}"
            )
        }
    }

    private fun runPerProcess(file: File, source: String, classpath: List<File>): CompilerResult {
        val start = System.currentTimeMillis()

        val tempDir = File(context.cacheDir, "kotlin_analysis_temp").apply { mkdirs() }
        val tempFile = File(tempDir, file.name)
        tempFile.writeText(source)
        tempFile.deleteOnExit()

        val outputDir = File(tempDir, "out").apply { deleteRecursively(); mkdirs() }

        // exec_redirect.so LD_PRELOAD for SELinux noexec bypass
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val execRedirect = File(nativeLibDir, "libexec_redirect.so")

        // [TRY 3] Java 직접 실행 via NativeExec (SELinux exec bypass)
        // SELinux가 app_data_file에서 shell 스크립트 실행을 차단하므로
        // memfd_fexecve 방식의 NativeExec로 Java를 직접 실행합니다.
        val javaBin = findJavaBin(context)
        val compilerJar = findKotlinCompilerJar(context)
        if (javaBin != null && compilerJar != null) {
            val args = mutableListOf(
                javaBin.absolutePath,
                "-Xmx${BuildToolsConfig.kotlinCompilerHeapMb(context)}m",
                "-jar", compilerJar.absolutePath,
                tempFile.absolutePath,
                "-d", outputDir.absolutePath,
                "-no-reflect", "-nowarn"
            )
            if (classpath.isNotEmpty()) {
                args += "-cp"
                args += classpath.joinToString(File.pathSeparator) { it.absolutePath }
            }
            Log.i("KotlinCompiler", "[TRY 3] NativeExec: ${javaBin.name} -jar ${compilerJar.name}")
            val handle = NativeExec.start(
                javaBin = javaBin.absolutePath,
                codeCacheDir = context.codeCacheDir.absolutePath,
                nativeLibDir = context.applicationInfo.nativeLibraryDir,
                args = args,
                envp = buildList {
                    val home = javaBin.parentFile?.parentFile?.absolutePath
                    if (!home.isNullOrBlank()) add("JAVA_HOME=$home")
                },
                cwd = tempDir.absolutePath
            )
            if (handle != 0L) {
                val output = StringBuilder()
                while (true) {
                    val chunk = NativeExec.readChunk(handle, 5000) ?: break
                    output.append(chunk)
                }
                NativeExec.waitFor(handle)
                NativeExec.free(handle)
                val rawOutput = output.toString()
                val diagnostics = parseCompilerOutput(rawOutput, file.absolutePath)
                return CompilerResult(
                    diagnostics = diagnostics,
                    success = diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
                    durationMs = System.currentTimeMillis() - start,
                    rawOutput = rawOutput
                )
            }
            Log.w("KotlinCompiler", "NativeExec start failed: ${NativeExec.getLastError()}")
        }

        // [Fallback] kotlinc wrapper ??크립트 ??도
        val kotlinc = findKotlinc() ?: return CompilerResult(
            diagnostics = listOf(
                Diagnostic(
                    filePath = file.absolutePath,
                    line = 1, column = 1, length = 0,
                    message = "Kotlin compiler not found. Java=${javaBin?.absolutePath} JAR=${compilerJar?.absolutePath}",
                    severity = DiagnosticSeverity.INFO,
                    kind = DiagnosticKind.OTHER
                )
            ),
            success = false,
            durationMs = System.currentTimeMillis() - start,
            rawOutput = "kotlinc not found"
        )

        val args = mutableListOf(
            kotlinc.absolutePath,
            tempFile.absolutePath,
            "-d", outputDir.absolutePath,
            "-no-reflect", "-nowarn"
        )
        if (classpath.isNotEmpty()) {
            args += "-cp"
            args += classpath.joinToString(File.pathSeparator) { it.absolutePath }
        }

        val pb = com.aide.mobilestudio.ExecHelper.createProcess(
            context = context,
            args = args,
            cwd = tempDir,
            env = buildMap {
                if (execRedirect.exists()) {
                    put("LD_PRELOAD", execRedirect.absolutePath)
                }
            }
        ).redirectErrorStream(true)
        val process = pb.start()

        val output = StringBuilder()
        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
            process.waitFor()
        } finally {
            process.destroy()
        }

        val rawOutput = output.toString()
        val diagnostics = parseCompilerOutput(rawOutput, file.absolutePath)
        return CompilerResult(
            diagnostics = diagnostics,
            success = diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
            durationMs = System.currentTimeMillis() - start,
            rawOutput = rawOutput
        )
    }

    /**
     * Fresh Android projects have no dependency cache yet; unresolved externals
     * are classpath noise (Gradle would resolve them after its first build), so
     * surface them as warnings instead of red editor markers.
     */
    private fun softenClasspathNoise(diags: List<Diagnostic>, file: File): List<Diagnostic> {
        if (diags.none { it.severity == DiagnosticSeverity.ERROR }) return diags
        if (!looksAndroidProject(file)) return diags
        if (hasDependencyClasspath(context)) return diags
        return diags.map { d ->
            val classpathNoise = d.message.contains("unresolved reference") ||
                d.message.contains("cannot infer type for type parameter")
            if (d.severity == DiagnosticSeverity.ERROR && classpathNoise) {
                d.copy(
                    severity = DiagnosticSeverity.WARNING,
                    message = d.message + " (dependency classpath empty - run Build once)"
                )
            } else d
        }
    }

    /** Walks up from [file] to see whether it lives inside an Android project. */
    private fun looksAndroidProject(file: File): Boolean {
        var d: File? = file.absoluteFile.parentFile
        repeat(6) {
            val dir = d ?: return false
            if (File(dir, "AndroidManifest.xml").isFile ||
                File(dir, "src/main/AndroidManifest.xml").isFile ||
                File(dir, "app/src/main/AndroidManifest.xml").isFile
            ) return true
            d = dir.parentFile
        }
        return false
    }

    private fun parseCompilerOutput(output: String, filePath: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val errorPattern = Regex("""^(?:e:|w:)\s*(.+?):\s*\((\d+),\s*(\d+)\):\s*(.+)$""", RegexOption.MULTILINE)
        val simplePattern = Regex("""^(?:e:|w:)\s*(.+?):\s*(\d+):\s*(\d+):\s*(.+)$""", RegexOption.MULTILINE)

        for (match in errorPattern.findAll(output)) {
            val msg = match.groupValues[4].trim()
            val line = match.groupValues[2].toIntOrNull() ?: 1
            val col = match.groupValues[3].toIntOrNull() ?: 1
            val isError = output.startsWith("e:", match.range.first)
            diagnostics += Diagnostic(
                filePath = filePath,
                line = line,
                column = col,
                length = 1,
                message = msg,
                severity = if (isError) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                kind = classifyError(msg)
            )
        }
        if (diagnostics.isEmpty()) {
            for (match in simplePattern.findAll(output)) {
                val msg = match.groupValues[4].trim()
                val line = match.groupValues[2].toIntOrNull() ?: 1
                val col = match.groupValues[3].toIntOrNull() ?: 1
                val isError = output.startsWith("e:", match.range.first)
                diagnostics += Diagnostic(
                    filePath = filePath,
                    line = line,
                    column = col,
                    length = 1,
                    message = msg,
                    severity = if (isError) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                    kind = classifyError(msg)
                )
            }
        }
        if (diagnostics.isEmpty()) {
            // Kotlin 2.x default renderer: "path:line:col: error: message" (no e: prefix)
            val k2Pattern = Regex(
                """^(.+?):(\d+):(\d+):\s*(error|warning):\s*(.+)$""",
                RegexOption.MULTILINE
            )
            for (match in k2Pattern.findAll(output)) {
                val msg = match.groupValues[5].trim()
                val line = match.groupValues[2].toIntOrNull() ?: 1
                val col = match.groupValues[3].toIntOrNull() ?: 1
                val isError = match.groupValues[4].equals("error", ignoreCase = true)
                diagnostics += Diagnostic(
                    filePath = filePath,
                    line = line,
                    column = col,
                    length = 1,
                    message = msg,
                    severity = if (isError) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                    kind = classifyError(msg)
                )
            }
        }
        return diagnostics
    }

    private fun classifyError(message: String): DiagnosticKind {
        val lower = message.lowercase()
        return when {
            "unresolved reference" in lower || "unresolved" in lower -> DiagnosticKind.UNRESOLVED_REFERENCE
            "type mismatch" in lower || "type inference" in lower -> DiagnosticKind.TYPE_MISMATCH
            "null" in lower && ("assert" in lower || "!" in lower) -> DiagnosticKind.NULLABILITY_ERROR
            "import" in lower -> DiagnosticKind.IMPORT_ERROR
            "override" in lower || "does not override" in lower -> DiagnosticKind.OVERRIDE_ERROR
            "generic" in lower || "type argument" in lower || "type parameter" in lower -> DiagnosticKind.GENERIC_TYPE_ERROR
            "function" in lower && ("signature" in lower || "parameter" in lower) -> DiagnosticKind.FUNCTION_SIGNATURE_ERROR
            "syntax" in lower || "expecting" in lower || "unexpected" in lower -> DiagnosticKind.SYNTAX_ERROR
            else -> DiagnosticKind.OTHER
        }
    }

    private fun findKotlinc(): File? {
        kotlincCache.values.removeIf { !it.exists() }
        if (kotlincCache.isNotEmpty()) return kotlincCache.values.first()

        val found = findKotlinc(context)
        if (found != null) kotlincCache["default"] = found
        return found
    }

    fun invalidateCache() {
        kotlincCache.clear()
    }

    companion object {
        /**
         * Runtime jars the embeddable compiler needs sitting next to [compilerJar]
         * (kotlin-compiler-embeddable does NOT bundle stdlib/reflect/coroutines).
         * Used for the daemon JVM classpath, the direct fork launcher and -no-stdlib
         * compiles - without them K2JVMCompiler dies with NoClassDefFoundError.
         *
         * Also enforces owner read-only mode: ART rejects any classpath dex/jar the
         * caller can write (`access(W_OK)`), which aborts the toolchain JVM at
         * classloader init with "Writable dex file ... is not allowed".
         */
        fun compilerRuntimeJars(compilerJar: File): List<File> {
            val dir = compilerJar.parentFile ?: return emptyList()
            val prefixes = listOf(
                "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime",
                "kotlin-scripting", "kotlinx-coroutines", "annotations-", "trove4j"
            )
            val jars = dir.listFiles()
                ?.filter { f -> f.isFile && f.extension == "jar" && prefixes.any { f.name.startsWith(it) } }
                ?.sortedBy { it.name }
                .orEmpty()
            ensureReadOnly(listOf(compilerJar) + jars)
            return jars
        }

        /** Strips owner write permission so ART will load the file as classpath dex. */
        fun ensureReadOnly(files: Iterable<File>) {
            for (f in files) {
                if (!f.isFile) continue
                if (!f.canWrite()) continue
                runCatching { f.setWritable(false, false) }
                runCatching { android.system.Os.chmod(f.absolutePath, 0b100_100_100) } // 0444
            }
        }

        // ---- shared dependency classpath (realtime compile + analysis daemon) ----

        private const val DEP_JAR_CACHE_TTL_MS = 60_000L
        private const val MAX_DEPENDENCY_JARS = 300
        private const val MAX_DEP_JAR_BYTES = 80_000_000L
        @Volatile private var depJarCache: List<File>? = null
        @Volatile private var depJarCacheMs = 0L

        /** Gradle-resolved library jars from the on-device dependency cache. */
        fun dependencyJars(context: Context): List<File> {
            val now = System.currentTimeMillis()
            depJarCache?.let { if (now - depJarCacheMs < DEP_JAR_CACHE_TTL_MS) return it }
            val gradleHome = File(context.filesDir, ".gradle/caches")
            val roots = mutableListOf(
                File(gradleHome, "modules-2/files-2.1"),
                File(gradleHome, "transforms-3"),
                File(gradleHome, "transforms")
            )
            // Versioned caches (Gradle 8+: caches/<version>/transforms ...)
            gradleHome.listFiles()
                ?.filter { it.isDirectory && it.name.firstOrNull()?.isDigit() == true }
                ?.forEach { v ->
                    roots += File(v, "transforms")
                    roots += File(v, "modules-2/files-2.1")
                }
            val jars = roots.filter { it.isDirectory }.flatMap { root ->
                root.walkTopDown()
                    .filter { f ->
                        f.isFile && f.extension == "jar" &&
                            f.length() in 1L..MAX_DEP_JAR_BYTES &&
                            !f.name.contains("-sources") &&
                            !f.name.contains("-javadoc")
                    }
                    .take(MAX_DEPENDENCY_JARS)
                    .toList()
            }
            depJarCache = jars
            depJarCacheMs = now
            return jars
        }

        /** False on a fresh project - unresolved externals are classpath noise, not code errors. */
        fun hasDependencyClasspath(context: Context): Boolean = dependencyJars(context).isNotEmpty()

        /** android.jar of the newest installed platform. */
        fun findAndroidJar(context: Context): File? {
            val roots = listOf(
                File(context.filesDir, "toolchain/sdk/active"),
                File(context.filesDir, "toolchain/sdk"),
                System.getenv("ANDROID_HOME")?.let { File(it) },
                System.getenv("ANDROID_SDK_ROOT")?.let { File(it) },
                File("/sdcard/Android/Sdk"),
                File("/storage/emulated/0/Android/Sdk")
            ).filterNotNull()
            for (root in roots) {
                val platforms = File(root, "platforms")
                if (!platforms.isDirectory) continue
                val best = platforms.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?.firstOrNull { File(it, "android.jar").isFile }
                if (best != null) return File(best, "android.jar")
            }
            return null
        }

        fun findKotlinc(context: Context): File? {
            val candidates = buildList {
                // Android SDK kotlinc
                val sdk = resolveAndroidSdkDir()
                if (sdk != null) {
                    addAll(sdk.walkTopDown().maxDepth(5).filter {
                        it.name == "kotlinc" && it.isFile && it.canExecute()
                    }.toList())
                }
                // Gradle bundled kotlinc
                val gradleHome = File(context.filesDir, ".gradle")
                addAll(gradleHome.walkTopDown().maxDepth(8).filter {
                    it.name == "kotlinc" && it.isFile && it.canExecute() &&
                        it.path.contains("kotlin-compiler")
                }.toList())
                // Local toolchain
                val pkg = context.packageName
                addAll(listOf(
                    File("/data/data/$pkg/files/toolchain/kotlinc/bin/kotlinc"),
                    File(context.filesDir, "toolchain/kotlinc/bin/kotlinc"),
                    File(context.filesDir, "kotlinc/bin/kotlinc"),
                    File(context.cacheDir, "kotlinc/bin/kotlinc"),
                    // Termux paths
                    File("/data/data/com.termux/files/usr/bin/kotlinc"),
                    File("/data/data/com.termux/files/usr/share/kotlin/bin/kotlinc")
                ).filter { it.isFile && it.canExecute() })
            }
            if (candidates.isNotEmpty()) return candidates.first()

            // Fallback: find kotlin-compiler-embeddable JAR and create wrapper script
            return findKotlincWrapper(context)
        }

        fun findKotlinCompilerJar(context: Context): File? {
            val pkg = context.packageName
            val candidates = mutableListOf<File>()
            
            // 1. Check toolchain/kotlin/lib (standard standalone layout)
            candidates.add(File(context.filesDir, "toolchain/kotlin/lib"))
            candidates.add(File(context.filesDir, "toolchain/kotlinc/lib"))
            
            // 2. Check Gradle dirs
            candidates.add(File(context.filesDir, "toolchain/gradle"))
            candidates.add(File(context.filesDir, ".gradle"))
            
            // 3. Check package data dirs
            candidates.add(File("/data/data/$pkg/files/toolchain/kotlin/lib"))
            candidates.add(File("/data/data/$pkg/files/toolchain/gradle"))
            
            // 4. Check external data (scoped storage)
            candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/toolchain/kotlin/lib"))
            candidates.add(File("/storage/emulated/0/Android/data/$pkg/files/toolchain/gradle"))

            for (dir in candidates) {
                if (!dir.isDirectory) continue
                val jar = dir.walkTopDown().maxDepth(6).firstOrNull {
                    (it.name.startsWith("kotlin-compiler") || it.name.startsWith("kotlin-compiler-embeddable")) &&
                    it.name.endsWith(".jar") &&
                    it.isFile
                }
                if (jar != null) return jar
            }
            
            // 5. Special check for specific versions in Gradle caches
            val gradleCache = File(context.filesDir, ".gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin")
            if (gradleCache.isDirectory) {
                val jar = gradleCache.walkTopDown().maxDepth(10).firstOrNull {
                    it.name.startsWith("kotlin-compiler-embeddable") && it.name.endsWith(".jar")
                }
                if (jar != null) return jar
            }

            return null
        }

        private fun findKotlincWrapper(context: Context): File? {
            val jar = findKotlinCompilerJar(context) ?: return null
            val javaBin = findJavaBin(context)
            if (javaBin == null || !javaBin.canExecute()) {
                Log.w("KotlinCompiler", "Java not found, cannot create kotlinc wrapper")
                return null
            }

            // Create wrapper script in codeCacheDir (SELinux allows execute there)
            val wrapperDir = File(context.codeCacheDir, "kotlinc_exec")
            wrapperDir.mkdirs()
            val wrapper = File(wrapperDir, "kotlinc")
            wrapper.writeText("#!/system/bin/sh\nexec \"${javaBin.absolutePath}\" -Xmx512m -jar \"${jar.absolutePath}\" \"\$@\"\n")
            // Set permissions via multiple methods
            wrapper.setReadable(true, false)
            wrapper.setExecutable(true, false)
            runCatching { android.system.Os.chmod(wrapper.absolutePath, 0b111_101_101) }
            runCatching {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", wrapper.absolutePath)).waitFor()
            }
            Log.i("KotlinCompiler", "Created kotlinc wrapper: ${wrapper.absolutePath} -> ${jar.name} (java=${javaBin.name})")
            return wrapper
        }

        fun findJavaBin(context: Context): File? {
            // Use ToolchainManager's active JDK version
            val toolchain = ToolchainManager(context)
            val activeHome = toolchain.activeJdkHome()
            if (activeHome != null) {
                val javaBin = File(activeHome, "bin/java")
                if (javaBin.isFile) {
                    if (!javaBin.canExecute()) {
                        runCatching { javaBin.setExecutable(true, false) }
                        runCatching { android.system.Os.chmod(javaBin.absolutePath, 0b111_101_101) }
                    }
                    // ensure javac keeps its exec bit too (needed by KotlinDaemon)
                    val javacBin = File(javaBin.parentFile, "javac")
                    if (javacBin.isFile && !javacBin.canExecute()) {
                        runCatching { javacBin.setExecutable(true, false) }
                        runCatching { android.system.Os.chmod(javacBin.absolutePath, 0b111_101_101) }
                    }
                    if (javaBin.canExecute()) return javaBin
                }
            }

            // Fallback: try JreGradleInstaller
            return try {
                val installer = JreGradleInstaller(context, { }, { })
                installer.effectiveJavaBin.takeIf { it.isFile }
            } catch (_: Throwable) { null }
        }


        private fun resolveAndroidSdkDir(): File? {
            val candidates = listOf(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "/sdcard/Android/Sdk",
                "/storage/emulated/0/Android/Sdk"
            )
            return candidates.firstNotNullOfOrNull { path ->
                if (path != null) File(path).takeIf { it.isDirectory } else null
            }
        }
    }
}
