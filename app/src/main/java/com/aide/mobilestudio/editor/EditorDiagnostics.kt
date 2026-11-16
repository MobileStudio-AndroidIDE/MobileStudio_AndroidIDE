package com.aide.mobilestudio.editor

import java.io.File

/**
 * Editor Diagnostics - code diagnostics and validation results.
 */
class EditorDiagnostics {
    data class EditorDiagnostic(
        val file: File,
        val line: Int,
        val column: Int?,
        val message: String,
        val severity: Severity,
        val rawLine: String
    ) {
        enum class Severity { ERROR, WARNING, INFO }
    }

    private val diagnostics = mutableListOf<EditorDiagnostic>()

    fun addDiagnostic(diagnostic: EditorDiagnostic) {
        diagnostics.add(diagnostic)
    }

    fun addError(file: File, line: Int, message: String) {
        addDiagnostic(EditorDiagnostic(file, line, null, message, EditorDiagnostic.Severity.ERROR, ""))
    }

    fun addWarning(file: File, line: Int, message: String) {
        addDiagnostic(EditorDiagnostic(file, line, null, message, EditorDiagnostic.Severity.WARNING, ""))
    }

    fun addInfo(file: File, line: Int, message: String) {
        addDiagnostic(EditorDiagnostic(file, line, null, message, EditorDiagnostic.Severity.INFO, ""))
    }

    fun getDiagnostics(): List<EditorDiagnostic> = diagnostics.toList()

    fun getDiagnosticsForFile(file: File): List<EditorDiagnostic> =
        diagnostics.filter { it.file == file }

    fun clear() {
        diagnostics.clear()
    }

    fun clearFile(file: File) {
        diagnostics.removeAll { it.file == file }
    }

    val count: Int
        get() = diagnostics.size

    val errorCount: Int
        get() = diagnostics.count { it.severity == EditorDiagnostic.Severity.ERROR }

    val warningCount: Int
        get() = diagnostics.count { it.severity == EditorDiagnostic.Severity.WARNING }
}
