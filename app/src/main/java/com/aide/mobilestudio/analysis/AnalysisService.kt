package com.aide.mobilestudio.analysis

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

object AnalysisService {
    private var engine: AnalysisEngine? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        engine = AnalysisEngine(
            context = context.applicationContext,
            config = AnalysisConfig.defaultForDevice(context)
        )
        initialized = true
    }

    fun getEngine(): AnalysisEngine {
        return engine ?: throw IllegalStateException("AnalysisService not initialized. Call initialize() first.")
    }

    fun startProject(projectDir: File) {
        getEngine().startWatching(projectDir)
    }

    fun stopProject(projectDir: File) {
        getEngine().stopWatching(projectDir)
    }

    fun openFile(projectDir: File, file: File) {
        getEngine().openFile(projectDir, file)
    }

    fun closeFile(projectDir: File, file: File) {
        getEngine().closeFile(projectDir, file)
    }

    fun getDiagnostics(file: File): List<Diagnostic> {
        return getEngine().getDiagnosticsForFile(file)
    }

    fun getProjectSummary(projectDir: File): Triple<Int, Int, Int> {
        return getEngine().getSummaryForProject(projectDir)
    }

    fun shutdown() {
        engine?.stopAll()
        engine = null
        initialized = false
    }
}
