package com.aide.mobilestudio.analysis

import java.io.File

class DiagnosticCollector {

    private val diagnostics = mutableMapOf<String, MutableList<Diagnostic>>()

    fun add(key: String, result: List<Diagnostic>) {
        diagnostics[key] = result.toMutableList()
    }

    fun addDiagnostic(filePath: String, diagnostic: Diagnostic) {
        diagnostics.getOrPut(filePath) { mutableListOf() } += diagnostic
    }

    fun getForFile(filePath: String): List<Diagnostic> {
        return diagnostics.values.flatten().filter { it.filePath == filePath }
    }

    fun getAll(): Map<String, List<Diagnostic>> = diagnostics.toMap()

    fun getErrors(): List<Diagnostic> = diagnostics.values.flatten().filter { it.severity == DiagnosticSeverity.ERROR }

    fun getWarnings(): List<Diagnostic> = diagnostics.values.flatten().filter { it.severity == DiagnosticSeverity.WARNING }

    fun clearFile(filePath: String) {
        diagnostics.entries.removeAll { it.key == filePath }
        diagnostics.values.forEach { it.removeAll { d -> d.filePath == filePath } }
    }

    fun clearAll() = diagnostics.clear()

    fun removeStaleFiles(existingFiles: Set<String>) {
        val seen = existingFiles.toSet()
        diagnostics.entries.removeAll { (_, diags) ->
            diags.all { it.filePath !in seen }
        }
    }

    fun merge(collector: DiagnosticCollector) {
        collector.diagnostics.forEach { (key, diags) ->
            diagnostics.getOrPut(key) { mutableListOf() }.addAll(diags)
        }
    }

    fun count(): Int = diagnostics.values.sumOf { it.size }

    fun hasErrors(): Boolean = getErrors().isNotEmpty()
}
