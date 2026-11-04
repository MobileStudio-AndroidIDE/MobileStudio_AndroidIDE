package com.aide.mobilestudio.analysis

import java.io.File

interface EditorDiagnostics {
    fun onDiagnosticsUpdated(
        file: File,
        diagnostics: List<Diagnostic>,
        source: String,
        analysisTimeMs: Long
    )

    fun onAnalysisStarted(file: File)
    fun onAnalysisFinished(file: File)
    fun onClearDiagnostics(file: File)

    fun onProjectDiagnosticsUpdate(
        projectDir: File,
        errors: Int,
        warnings: Int,
        infos: Int
    )
}

class EditorDiagnosticsAdapter : EditorDiagnostics {
    private val listeners = mutableListOf<EditorDiagnostics>()

    fun addListener(listener: EditorDiagnostics) {
        listeners.add(listener)
    }

    fun removeListener(listener: EditorDiagnostics) {
        listeners.remove(listener)
    }

    override fun onDiagnosticsUpdated(file: File, diagnostics: List<Diagnostic>, source: String, analysisTimeMs: Long) {
        listeners.forEach { it.onDiagnosticsUpdated(file, diagnostics, source, analysisTimeMs) }
    }

    override fun onAnalysisStarted(file: File) {
        listeners.forEach { it.onAnalysisStarted(file) }
    }

    override fun onAnalysisFinished(file: File) {
        listeners.forEach { it.onAnalysisFinished(file) }
    }

    override fun onClearDiagnostics(file: File) {
        listeners.forEach { it.onClearDiagnostics(file) }
    }

    override fun onProjectDiagnosticsUpdate(projectDir: File, errors: Int, warnings: Int, infos: Int) {
        listeners.forEach { it.onProjectDiagnosticsUpdate(projectDir, errors, warnings, infos) }
    }
}
