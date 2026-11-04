package com.aide.mobilestudio.analysis

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT
}

enum class DiagnosticKind {
    SYNTAX_ERROR,
    UNRESOLVED_REFERENCE,
    TYPE_MISMATCH,
    NULLABILITY_ERROR,
    IMPORT_ERROR,
    OVERRIDE_ERROR,
    FUNCTION_SIGNATURE_ERROR,
    GENERIC_TYPE_ERROR,
    OTHER
}

data class Diagnostic(
    val filePath: String,
    val line: Int,
    val column: Int,
    val length: Int,
    val message: String,
    val severity: DiagnosticSeverity,
    val kind: DiagnosticKind,
    val rawText: String? = null
) {
    fun toShortString(): String = "${filePath}:${line}:${column}: ${severity.name.lowercase()}: $message"
}

data class AnalysisResult(
    val filePath: String,
    val diagnostics: List<Diagnostic>,
    val analysisTimeMs: Long,
    val isIncremental: Boolean,
    val sourceHash: Int
)

data class AnalysisScope(
    val projectDir: String,
    val openFiles: Set<String> = emptySet(),
    val includeProjectFiles: Boolean = false
)
