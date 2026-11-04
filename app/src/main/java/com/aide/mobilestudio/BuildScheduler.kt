package com.aide.mobilestudio

import android.util.Log
import com.aide.mobilestudio.analysis.RealtimeCompiler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BuildScheduler(
    private val gradleRunner: GradleRunner,
    private val realtimeCompiler: RealtimeCompiler,
    private val onProblemsUpdated: (List<BuildProblem>) -> Unit,
    private val onBuildState: (State) -> Unit = {},
    private val onAutoCompileLine: ((String, LineLevel) -> Unit)? = null
) {
    enum class State { IDLE, BUILDING, QUEUED }

    companion object {
        private const val TAG = "BuildScheduler"
        private const val MAX_RETAINED_OUTPUT_CHARS = 900_000
        private const val MAX_LINE_CHARS = 4_000
        private const val MAX_CAPTURED_OUTPUT_CHARS = 1_200_000
        val INCREMENTAL_TASKS = listOf(
            "compileDebugKotlin",
            "compileDebugJavaWithJavac",
            "processDebugResources",
            "externalNativeBuildDebug"
        )
    }

    private val isBuilding = AtomicBoolean(false)
    @Volatile private var pendingRebuild = false
    @Volatile private var suspendedForManualBuild = false
    @Volatile private var suppressNextCompletionQueue = false
    @Volatile private var currentProjectDir: File? = null
    @Volatile private var lastFailureMs = 0L
    private val pendingChangedFiles = mutableSetOf<File>()

    // AGP 프로젝트 모델 (빌드 소스 결정에 사용)
    @Volatile var projectModel: AndroidProjectModel? = null

    fun attachTo(watcher: FileWatcher, projectDir: File) {
        currentProjectDir = projectDir
        Log.i(TAG, "attached to project: $projectDir")
    }

    fun schedule(changedFiles: Set<File>) {
        val projectDir = currentProjectDir ?: return
        val now = System.currentTimeMillis()
        if (now - lastFailureMs < 30_000L) {
            Log.i(TAG, "schedule ignored (cooldown after failure, ${now - lastFailureMs}ms ago)")
            return
        }
        val buildRelevantFiles = changedFiles
            .filterNot { shouldIgnoreBuildTrigger(projectDir, it) }
            .filter { it.extension.lowercase() in COMPILE_EXTENSIONS }
            .toSet()
        if (buildRelevantFiles.isEmpty()) {
            Log.i(TAG, "schedule ignored (${changedFiles.size} generated/config files)")
            return
        }
        if (buildRelevantFiles.all { it.extension.lowercase() in INLINE_CHECK_EXTENSIONS }) {
            Log.i(TAG, "schedule: kotlin/java change -> direct kotlinc/javac compile (Gradle only for resources/config)")
        }
        Log.i(TAG, "schedule(${buildRelevantFiles.size} files)")

        if (suspendedForManualBuild || isBuilding.get()) {
            pendingRebuild = true
            synchronized(pendingChangedFiles) {
                pendingChangedFiles += buildRelevantFiles
            }
            onBuildState(State.QUEUED)
            return
        }

        runIncrementalBuild(projectDir, buildRelevantFiles)
    }

    fun suspendForManualBuild() {
        suspendedForManualBuild = true
        if (isBuilding.get()) {
            pendingRebuild = false
            takePendingChangedFiles()
            gradleRunner.cancel()
        }
    }

    fun cancelAndClear() {
        suspendedForManualBuild = true
        suppressNextCompletionQueue = true
        pendingRebuild = false
        takePendingChangedFiles()
        gradleRunner.cancel()
        onBuildState(State.IDLE)
    }

    fun resumeAfterManualBuild(runPending: Boolean = true) {
        suspendedForManualBuild = false
        if (!runPending) {
            suppressNextCompletionQueue = false
            pendingRebuild = false
            takePendingChangedFiles()
            onBuildState(State.IDLE)
            return
        }
        if (pendingRebuild && !isBuilding.get()) {
            currentProjectDir?.let { projectDir ->
                runIncrementalBuild(projectDir, takePendingChangedFiles())
            }
        } else {
            onBuildState(State.IDLE)
        }
    }

    private fun shouldIgnoreBuildTrigger(projectDir: File, file: File): Boolean {
        val relativePath = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }
            .getOrDefault(file.name)
        return relativePath == "gradle.properties" ||
            relativePath == "local.properties" ||
            relativePath == "gradlew" ||
            relativePath.startsWith("gradle/wrapper/")
    }

    private val COMPILE_EXTENSIONS = setOf(
        "kt", "java", "xml", "gradle", "kts",
        "c", "cc", "cpp", "cxx", "h", "hpp", "hh", "hxx", "m", "mm"
    )

    /** Changed files whose diagnostics are already handled by inline AnalysisEngine. */
    private val INLINE_CHECK_EXTENSIONS = setOf("kt", "java")

    /** Language files compiled by RealtimeCompiler (direct tools) instead of Gradle. */
    private val DIRECT_CODE_EXTENSIONS = setOf(
        "kt", "java", "c", "cc", "cpp", "cxx", "m", "mm", "h", "hpp", "hh", "hxx"
    )

    fun forceRebuild() {
        val projectDir = currentProjectDir ?: return
        if (isBuilding.get()) {
            pendingRebuild = true
            return
        }
        runIncrementalBuild(projectDir, null)
    }

    private fun runIncrementalBuild(projectDir: File, changedFiles: Set<File>?) {
        isBuilding.set(true)
        onBuildState(State.BUILDING)
        pendingRebuild = false

        val outputBuffer = StringBuilder()
        val liveProblems = linkedMapOf<String, BuildProblem>()
        var lastProblemUpdateMs = 0L

        fun emitLine(line: String, level: LineLevel) {
            appendBounded(outputBuffer, line)
            val problem = BuildProblemParser.parseLine(line, projectDir)
            val effectiveLevel = when (problem?.severity) {
                BuildProblem.Severity.ERROR -> LineLevel.ERROR
                BuildProblem.Severity.WARNING -> LineLevel.WARNING
                else -> level
            }
            onAutoCompileLine?.invoke(line, effectiveLevel)
            if (problem != null &&
                (problem.severity != BuildProblem.Severity.INFO || problem.file != null)
            ) {
                val key = listOf(
                    problem.file?.absolutePath.orEmpty(),
                    problem.line,
                    problem.column,
                    problem.severity,
                    problem.message
                ).joinToString("|")
                liveProblems[key] = problem
                val now = System.currentTimeMillis()
                if (now - lastProblemUpdateMs >= 350L) {
                    lastProblemUpdateMs = now
                    onProblemsUpdated(liveProblems.values.toList())
                }
            }
        }

        fun finish(success: Boolean, durationMs: Long) {
            isBuilding.set(false)

            val problems = BuildProblemParser.parse(outputBuffer.toString(), projectDir)
            val errCount = problems.count { it.severity == BuildProblem.Severity.ERROR }
            val warnCount = problems.count { it.severity == BuildProblem.Severity.WARNING }
            Log.i(TAG, "compile done success=$success, errors=$errCount, warnings=$warnCount, ${durationMs}ms")
            onProblemsUpdated(problems)

            val summary = if (success) {
                "\u2714 Compile OK (${durationMs}ms${if (warnCount > 0) ", $warnCount warnings" else ""})"
            } else {
                "\u2718 Compile FAILED ($errCount error${if (errCount != 1) "s" else ""}, ${durationMs}ms)"
            }
            onAutoCompileLine?.invoke(summary, if (success) LineLevel.SUCCESS else LineLevel.ERROR)

            if (success && !suspendedForManualBuild && pendingRebuild && currentProjectDir != null) {
                Log.i(TAG, "pending file changes detected, starting another incremental build")
                runIncrementalBuild(currentProjectDir!!, takePendingChangedFiles())
            } else if (suspendedForManualBuild && !suppressNextCompletionQueue) {
                onBuildState(State.QUEUED)
            } else {
                suppressNextCompletionQueue = false
                if (!success) {
                    pendingRebuild = false
                    takePendingChangedFiles()
                    lastFailureMs = System.currentTimeMillis()
                }
                onBuildState(State.IDLE)
            }
        }

        // Partition: code files go to direct tools (kotlinc/javac/clang/ninja),
        // everything else (xml/gradle/manifest) still needs Gradle.
        val codeFiles = changedFiles
            ?.filter { it.extension.lowercase() in DIRECT_CODE_EXTENSIONS }
            ?.toSet()
            .orEmpty()
        val restFiles = changedFiles?.minus(codeFiles).orEmpty()
        val directNeeded = changedFiles == null || codeFiles.isNotEmpty()
        // Pure code change (restFiles empty) is fully handled by the direct
        // phase - an empty changed set must NOT expand to a full Gradle
        // compile in chooseTasks, otherwise every .kt save still pays for Gradle.
        var gradleTasks =
            if (changedFiles == null || restFiles.isEmpty()) emptyList()
            else chooseTasks(projectDir, restFiles)

        val phase = buildList {
            if (directNeeded) add("kotlinc/javac/clang-ninja direct")
            if (gradleTasks.isNotEmpty()) add(gradleTasks.joinToString(" "))
        }.joinToString(" + ")
        if (phase.isNotEmpty()) {
            onAutoCompileLine?.invoke("\u25B6 Auto-compile: $phase", LineLevel.INFO)
        }
        if (codeFiles.isNotEmpty()) {
            val names = codeFiles.take(5).joinToString(", ") { it.name }
            val suffix = if (codeFiles.size > 5) " (+${codeFiles.size - 5} more)" else ""
            onAutoCompileLine?.invoke("  Files: $names$suffix", LineLevel.DEBUG)
        }

        val startedAt = System.currentTimeMillis()
        Thread {
            var directOk = true
            try {
                if (directNeeded) {
                    val outcome = realtimeCompiler.compile(projectDir, changedFiles)
                    outcome.lines.forEach { emitLine(it, LineLevel.INFO) }
                    directOk = outcome.success
                    if (!outcome.available) {
                        gradleTasks = chooseTasks(projectDir, changedFiles)
                        Log.i(TAG, "direct tools unavailable, Gradle fallback: $gradleTasks")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "direct realtime compile failed", e)
                emitLine("Realtime compile failed: ${e.message}", LineLevel.ERROR)
                directOk = false
            }

            if (gradleTasks.isNotEmpty() && !suspendedForManualBuild) {
                gradleRunner.run(
                    projectDir = projectDir,
                    tasks = gradleTasks,
                    onLine = { line, level -> emitLine(line, level) },
                    onDone = { result ->
                        finish(directOk && result.success, System.currentTimeMillis() - startedAt)
                    }
                )
            } else {
                finish(directOk, System.currentTimeMillis() - startedAt)
            }
        }.apply { isDaemon = true; name = "RealtimeCompile" }.start()
    }

    private fun chooseTasks(projectDir: File, changedFiles: Set<File>?): List<String> {
        val hasCmakeProject = File(projectDir, "app/src/main/cpp/CMakeLists.txt").exists()
        val model = projectModel

        // Use model variant if available
        val variant = model?.activeVariant ?: "debug"
        
        // Correct task names for Android Gradle Plugin
        // format: compile<Flavor><Type>Kotlin
        val variantType = if (variant.lowercase().endsWith("release")) "Release" else "Debug"
        
        // Extract flavor (e.g. "freeDebug" -> "Free")
        val flavor = variant.replace("(?i)debug".toRegex(), "").replace("(?i)release".toRegex(), "")
        val flavorPrefix = if (flavor.isNotEmpty()) flavor.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } else ""
        
        val compileKotlinTask = "compile${flavorPrefix}${variantType}Kotlin"
        val compileJavaTask = "compile${flavorPrefix}${variantType}JavaWithJavac"
        val externalNativeTask = "externalNativeBuild${variantType}"
        val processResourcesTask = "process${flavorPrefix}${variantType}Resources"

        if (changedFiles.isNullOrEmpty()) {
            return buildList {
                add(compileKotlinTask)
                add(compileJavaTask)
                if (hasCmakeProject || model?.hasNativeBuild == true) {
                    add(externalNativeTask)
                }
            }
        }

        val extensions = changedFiles.map { it.extension.lowercase() }.toSet()
        val tasks = mutableListOf<String>()
        if (extensions.any { it in setOf("kt", "kts") }) {
            tasks += compileKotlinTask
        }
        if (extensions.any { it == "java" }) {
            tasks += compileJavaTask
        }
        if (changedFiles.any { isManifestFile(projectDir, it) }) {
            tasks += "process${flavorPrefix}${variantType}MainManifest"
            tasks += "process${flavorPrefix}${variantType}Manifest"
        }
        if (extensions.any { it == "xml" }) {
            tasks += processResourcesTask
        }
        if ((hasCmakeProject || model?.hasNativeBuild == true) && extensions.any { it in setOf("c", "cc", "cpp", "h", "hpp") }) {
            tasks += externalNativeTask
        }
        return tasks.distinct().ifEmpty {
            // Only Gradle script changes require a full Gradle run;
            // anything else with no mapped task (py, images, ...) does not.
            if (extensions.any { it == "gradle" || it == "kts" }) {
                if (hasCmakeProject || model?.hasNativeBuild == true) {
                    listOf(compileKotlinTask, compileJavaTask, processResourcesTask, externalNativeTask)
                } else {
                    listOf(compileKotlinTask, compileJavaTask, processResourcesTask)
                }
            } else {
                emptyList()
            }
        }
    }

    private fun isManifestFile(projectDir: File, file: File): Boolean {
        val relativePath = runCatching { file.relativeTo(projectDir).invariantSeparatorsPath }
            .getOrDefault(file.invariantSeparatorsPath)
        return relativePath.endsWith("AndroidManifest.xml")
    }

    private fun takePendingChangedFiles(): Set<File> =
        synchronized(pendingChangedFiles) {
            pendingChangedFiles.toSet().also { pendingChangedFiles.clear() }
        }

    private fun appendBounded(buffer: StringBuilder, line: String) {
        if (buffer.length > MAX_CAPTURED_OUTPUT_CHARS) {
            buffer.delete(0, buffer.length - MAX_RETAINED_OUTPUT_CHARS)
        }
        buffer.appendLine(line.take(MAX_LINE_CHARS))
    }
}
