package com.aide.mobilestudio.editor

import java.io.File

/**
 * Editor Diagnostics - code diagnostics and validation.
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

    fun getDiagnostics(): List<EditorDiagnostic> = diagnostics

    fun clear() {
        diagnostics.clear()
    }
}
