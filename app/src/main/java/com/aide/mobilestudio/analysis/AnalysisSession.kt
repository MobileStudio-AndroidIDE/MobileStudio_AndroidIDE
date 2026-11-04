package com.aide.mobilestudio.analysis

import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AnalysisSession(
    val projectDir: File
) {
    data class FileState(
        val path: String,
        val contentHash: Int,
        val lastDiagnostics: List<Diagnostic> = emptyList(),
        val isDirty: Boolean = true
    )

    private val openFiles = ConcurrentHashMap<String, FileState>()
    val collector = DiagnosticCollector()
    var isActive: Boolean = true

    fun openFile(file: File, content: String? = null) {
        val path = file.absolutePath
        if (!openFiles.containsKey(path)) {
            val hash = content?.hashCode() ?: if (file.isFile) file.readText().hashCode() else 0
            openFiles[path] = FileState(path = path, contentHash = hash, isDirty = true)
        }
    }

    fun closeFile(file: File) {
        val path = file.absolutePath
        openFiles.remove(path)
        collector.clearFile(path)
    }

    fun markDirty(file: File) {
        val path = file.absolutePath
        openFiles.computeIfPresent(path) { _, state ->
            state.copy(
                contentHash = if (file.isFile) file.readText().hashCode() else 0,
                isDirty = true
            )
        }
    }

    fun markClean(file: File) {
        val path = file.absolutePath
        openFiles.computeIfPresent(path) { _, state ->
            state.copy(isDirty = false)
        }
    }

    fun getDirtyFiles(): List<FileState> = openFiles.values.filter { it.isDirty }.toList()

    fun getAllOpenFiles(): List<File> = openFiles.keys.map { File(it) }

    fun isFileOpen(file: File): Boolean = openFiles.containsKey(file.absolutePath)

    fun getFileState(file: File): FileState? = openFiles[file.absolutePath]

    fun refreshHash(file: File) {
        val path = file.absolutePath
        if (file.isFile) {
            openFiles.computeIfPresent(path) { _, state ->
                state.copy(contentHash = file.readText().hashCode())
            }
        }
    }

    fun closeAll() {
        openFiles.clear()
        collector.clearAll()
        isActive = false
    }
}
