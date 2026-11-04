package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AnalysisEngine(
    private val context: Context,
    private val config: AnalysisConfig = AnalysisConfig.defaultForDevice(context),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private val sessions = ConcurrentHashMap<String, AnalysisSession>()
    private val fileWatcher = FileWatcher(scope, config.debounceMs)
    private val syntaxChecker = SyntaxChecker(config)

    private val kotlinDaemon = if (config.enableKotlinDaemon && config.enableKotlinCompiler) {
        KotlinDaemon(context, config)
    } else null

    private val kotlinCompiler = KotlinCompiler(context, config, kotlinDaemon)
    private val clangCompiler = ClangCompiler(context)
    private val targetedTaskAnalyzer = TargetedTaskAnalyzer(context)
    private val resultCache = ConcurrentHashMap<String, CachedResult>()
    private val editorAdapter = EditorDiagnosticsAdapter()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val analysisCount = AtomicInteger(0)
    private val analysisTimeStats = mutableListOf<Long>()
    private var totalAnalysisTimeMs = 0L

    private val checkerDispatcher = Dispatchers.Default.limitedParallelism(2)

    val editorDiagnostics: EditorDiagnosticsAdapter get() = editorAdapter

    fun startWatching(projectDir: File) {
        val session = getOrCreateSession(projectDir)
        scope.launch(Dispatchers.IO) {
            fileWatcher.watchDirectory(projectDir)
            scanProjectFiles(projectDir)

            // Start kotlinc daemon if enabled
            kotlinDaemon?.let { daemon ->
                val kotlincBin = KotlinCompiler.findKotlinc(context) ?: return@let
                val kotlincDir = kotlincBin.parentFile?.parentFile ?: return@let
                val javaBin = KotlinCompiler.findJavaBin(context) ?: return@let
                val explicitJar = KotlinCompiler.findKotlinCompilerJar(context)
                daemon.start(kotlincDir, javaBin, explicitJar)
            }
        }

        // Idle monitor ??stops daemon after inactivity to free ~64MB heap RAM
        kotlinDaemon?.let { daemon ->
            scope.launch {
                while (isActive) {
                    delay(30_000L)
                    if (daemon.isIdle() && daemon.isAlive()) {
                        Log.i(TAG, "daemon idle > ${config.daemonIdleTimeoutMs}ms, stopping")
                        daemon.stop()
                    }
                }
            }
        }

        scope.launch {
            fileWatcher.debouncedChanges().collect { event ->
                if (!session.isActive) return@collect
                session.markDirty(event.file)
                editorAdapter.onAnalysisStarted(event.file)
                
                if (targetedTaskAnalyzer.isHeaderFile(event.file)) {
                    // Header changed: re-analyze all open source files that might include it
                    session.getAllOpenFiles().filter { targetedTaskAnalyzer.isSourceFile(it) }.forEach {
                        scheduleAnalysis(it, session)
                    }
                } else {
                    scheduleAnalysis(event.file, session)
                }
            }
        }
    }

    fun stopWatching(projectDir: File) {
        fileWatcher.unwatchDirectory(projectDir)
        sessions[projectDir.absolutePath]?.closeAll()
        sessions.remove(projectDir.absolutePath)
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    fun stopAll() {
        fileWatcher.unwatchAll()
        sessions.values.forEach { it.closeAll() }
        sessions.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        resultCache.clear()
        kotlinDaemon?.stop()
    }

    fun openFile(projectDir: File, file: File, content: String? = null) {
        val session = getOrCreateSession(projectDir)
        session.openFile(file, content)
        val cached = resultCache[file.absolutePath]
        val hash = content?.hashCode() ?: if (file.isFile) file.readText().hashCode() else 0
        if (cached?.isValid(config.cacheTtlMs, hash) == true) {
            session.collector.add(file.absolutePath, cached.diagnostics)
            editorAdapter.onDiagnosticsUpdated(file, cached.diagnostics, content ?: file.readText(), 0)
        } else {
            session.markDirty(file)
            scheduleAnalysis(file, session, content)
        }
    }

    fun closeFile(projectDir: File, file: File) {
        val session = sessions[projectDir.absolutePath] ?: return
        session.closeFile(file)
        editorAdapter.onClearDiagnostics(file)
    }

    fun requestAnalysis(file: File, source: String? = null): Job? {
        val path = file.absolutePath
        for (session in sessions.values) {
            if (session.isFileOpen(file) || path.startsWith(session.projectDir.absolutePath)) {
                return scheduleAnalysis(file, session, source)
            }
        }
        return null
    }

    fun requestFullProjectAnalysis(projectDir: File): Job? {
        val session = sessions[projectDir.absolutePath] ?: return null
        return scope.launch {
            val files = session.getAllOpenFiles()
            for (file in files) {
                if (!isActive) break
                ensureActive()
                analyzeFile(file, session)
            }
            updateProjectSummary(session)
        }
    }

    private fun scheduleAnalysis(file: File, session: AnalysisSession, explicitSource: String? = null): Job {
        val key = file.absolutePath
        activeJobs[key]?.cancel()
        val job = scope.launch {
            delay(config.debounceMs)
            if (!session.isActive) return@launch
            if (explicitSource != null) {
                analyzeFile(file, session, explicitSource)
            } else {
                analyzeFile(file, session)
            }
        }
        activeJobs[key] = job
        job.invokeOnCompletion {
            activeJobs.remove(key, job)
        }
        return job
    }

    private suspend fun analyzeFile(file: File, session: AnalysisSession, explicitSource: String? = null) {
        if (!file.isFile && explicitSource == null) return
        val source = explicitSource ?: withContext(Dispatchers.IO) { file.readText() }
        if (source.length > config.maxFileSizeKb * 1024) return
        val hash = source.hashCode()

        val cached = resultCache[file.absolutePath]
        if (cached?.isValid(config.cacheTtlMs, hash) == true) {
            session.collector.add(file.absolutePath, cached.diagnostics)
            session.markClean(file)
            currentCoroutineContext().ensureActive() // cancelled job must not emit
            editorAdapter.onDiagnosticsUpdated(file, cached.diagnostics, source, 0)
            return
        }

        val start = System.currentTimeMillis()
        val path = file.absolutePath
        session.collector.clearFile(path)

        // Determine analysis type based on file extension
        val taskInfo = targetedTaskAnalyzer.getTasksForFile(file, session.projectDir)
        
        // Quick syntax check for all files. Emit only when it found something -
        // a bare empty emit would wipe the compiler diagnostics that are about
        // to be replaced, and a job cancelled mid-flight would leave the panel blank.
        val quickDiagnostics = syntaxChecker.analyze(file, source)
        session.collector.add("syntax_$path", quickDiagnostics)
        if (quickDiagnostics.isNotEmpty()) {
            currentCoroutineContext().ensureActive() // cancelled job must not emit
            editorAdapter.onDiagnosticsUpdated(file, session.collector.getForFile(path), source, System.currentTimeMillis() - start)
        }

        // Run appropriate analyzer based on file type
        when (taskInfo.analysisType) {
            TargetedTaskAnalyzer.AnalysisType.KOTLIN_COMPILER -> {
                val compilerResult = kotlinCompiler.analyze(file, source)
                if (compilerResult.diagnostics.isNotEmpty()) {
                    session.collector.add("compiler_$path", compilerResult.diagnostics)
                }
            }
            TargetedTaskAnalyzer.AnalysisType.JAVA_COMPILER -> {
                // Java files use Kotlin compiler for now (stub)
                val compilerResult = kotlinCompiler.analyze(file, source)
                if (compilerResult.diagnostics.isNotEmpty()) {
                    session.collector.add("compiler_$path", compilerResult.diagnostics)
                }
            }
            TargetedTaskAnalyzer.AnalysisType.CLANGD_ANALYZER -> {
                val clangResult = clangCompiler.checkSyntax(
                    file = file,
                    source = source,
                    projectDir = session.projectDir
                )
                if (clangResult.diagnostics.isNotEmpty()) {
                    session.collector.add("clang_$path", clangResult.diagnostics)
                }
            }
            TargetedTaskAnalyzer.AnalysisType.XML_VALIDATOR -> {
                // XML validation is handled by syntax checker
            }
            TargetedTaskAnalyzer.AnalysisType.GRADLE_VALIDATOR -> {
                // Gradle validation is handled by syntax checker
            }
        }

        val allDiagnostics = session.collector.getForFile(path)
        val duration = System.currentTimeMillis() - start

        resultCache[file.absolutePath] = CachedResult(
            sourceHash = hash,
            diagnostics = allDiagnostics,
            timestampMs = System.currentTimeMillis()
        )
        session.markClean(file)
        analysisCount.incrementAndGet()
        totalAnalysisTimeMs += duration
        analysisTimeStats.add(duration)
        if (analysisTimeStats.size > 100) analysisTimeStats.removeFirst()

        // A job cancelled mid-analysis must not publish stale diagnostics —
        // line/column info belongs to an editor snapshot the user has already
        // changed (MainActivity also guards source == current editor text).
        currentCoroutineContext().ensureActive()
        editorAdapter.onDiagnosticsUpdated(file, allDiagnostics, source, duration)
        editorAdapter.onAnalysisFinished(file)
        updateProjectSummary(session)
    }


    private fun scanProjectFiles(projectDir: File) {
        val session = getOrCreateSession(projectDir)
        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", "build", ".git", ".idea", "node_modules") }
            .filter { it.isFile && FileWatcher.isKotlinSource(it) }
            .take(500)
            .forEach { file ->
                session.openFile(file)
            }
    }

    private fun getOrCreateSession(projectDir: File): AnalysisSession {
        return sessions.getOrPut(projectDir.absolutePath) {
            AnalysisSession(projectDir)
        }
    }

    private fun updateProjectSummary(session: AnalysisSession) {
        val errors = session.collector.getErrors().size
        val warnings = session.collector.getWarnings().size
        val total = session.collector.count()
        editorAdapter.onProjectDiagnosticsUpdate(session.projectDir, errors, warnings, total - errors - warnings)
    }

    fun getDiagnosticsForFile(file: File): List<Diagnostic> {
        for (session in sessions.values) {
            val diags = session.collector.getForFile(file.absolutePath)
            if (diags.isNotEmpty()) return diags
        }
        return resultCache[file.absolutePath]?.diagnostics.orEmpty()
    }

    fun getSummaryForProject(projectDir: File): Triple<Int, Int, Int> {
        val session = sessions[projectDir.absolutePath] ?: return Triple(0, 0, 0)
        val errors = session.collector.getErrors().size
        val warnings = session.collector.getWarnings().size
        val infos = session.collector.count() - errors - warnings
        return Triple(errors, warnings, infos)
    }

    fun getPerformanceStats(): Map<String, Any> = mapOf(
        "totalAnalyses" to analysisCount.get(),
        "totalTimeMs" to totalAnalysisTimeMs,
        "avgTimeMs" to if (analysisTimeStats.isEmpty()) 0.0 else analysisTimeStats.average(),
        "cacheSize" to resultCache.size,
        "activeSessions" to sessions.size
    )

    fun invalidateCache() {
        resultCache.clear()
        kotlinCompiler.invalidateCache()
        clangCompiler.clearCache()
        targetedTaskAnalyzer.clearCache()
    }

    fun clearFileCache(file: File) {
        resultCache.remove(file.absolutePath)
    }

    val totalAnalyses: Int get() = analysisCount.get()

    companion object {
        private const val TAG = "AnalysisEngine"
    }
}
