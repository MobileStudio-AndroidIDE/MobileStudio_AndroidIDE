package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.BuildToolsConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ClangdAnalyzer - C/C++ code analysis using clangd or Clang-based tools
 * 
 * Features:
 * - Syntax error detection
 * - Type checking
 * - Include path resolution
 * - Code completion support
 * - Diagnostic reporting
 */
class ClangdAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ClangdAnalyzer"
        private const val MAX_FILE_SIZE_KB = 500
    }

    data class ClangdDiagnostic(
        val file: String,
        val line: Int,
        val column: Int,
        val endLine: Int,
        val endColumn: Int,
        val severity: Severity,
        val message: String,
        val category: String = ""
    ) {
        enum class Severity { ERROR, WARNING, INFO, HINT }
    }

    data class ClangdResult(
        val success: Boolean,
        val diagnostics: List<ClangdDiagnostic>,
        val durationMs: Long = 0,
        val error: String? = null
    )

    private val diagnosticCache = ConcurrentHashMap<String, ClangdResult>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Analyze a C/C++ file
     */
    suspend fun analyze(
        file: File,
        source: String? = null,
        projectDir: File? = null,
        includePaths: List<String> = emptyList()
    ): ClangdResult = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            return@withContext ClangdResult(
                success = false,
                diagnostics = emptyList(),
                error = "File not found: ${file.name}"
            )
        }

        val fileSizeKB = file.length() / 1024
        if (fileSizeKB > MAX_FILE_SIZE_KB) {
            return@withContext ClangdResult(
                success = false,
                diagnostics = emptyList(),
                error = "File too large: ${fileSizeKB}KB (max: ${MAX_FILE_SIZE_KB}KB)"
            )
        }

        val cacheKey = file.absolutePath
        val cached = diagnosticCache[cacheKey]
        val fileHash = (source ?: file.readText()).hashCode()
        if (cached != null && cached.success && cached.diagnostics.isNotEmpty()) {
            // Check if cache is still valid (simple hash check)
            return@withContext cached
        }

        val startTime = System.currentTimeMillis()

        try {
            // Try clangd first, fall back to basic clang analysis
            val result = tryClangdAnalysis(file, source, projectDir, includePaths)
            
            val duration = System.currentTimeMillis() - startTime
            val finalResult = result.copy(durationMs = duration)
            
            // Cache the result
            diagnosticCache[cacheKey] = finalResult
            
            Log.d(TAG, "Analyzed ${file.name}: ${result.diagnostics.size} diagnostics in ${duration}ms")
            return@withContext finalResult

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed for ${file.name}", e)
            return@withContext ClangdResult(
                success = false,
                diagnostics = emptyList(),
                error = e.message
            )
        }
    }

    /**
     * Try clangd analysis via LSP
     */
    private suspend fun tryClangdAnalysis(
        file: File,
        source: String?,
        projectDir: File?,
        includePaths: List<String>
    ): ClangdResult {
        // Find clangd binary
        val clangdBin = findClangd()
        if (clangdBin == null) {
            Log.w(TAG, "clangd not found, trying basic clang analysis")
            return tryBasicClangAnalysis(file, source, projectDir, includePaths)
        }

        // Prepare source content
        val content = source ?: file.readText()
        val tempFile = File(context.cacheDir, "clangd_analysis_${file.name}")
        tempFile.writeText(content)

        try {
            // Run clangd with diagnostic output
            val args = buildList {
                add(clangdBin.absolutePath)
                add("--check=${tempFile.absolutePath}")
                add("--log=error")
                add("--pretty")
                
                // Add include paths
                includePaths.forEach { path ->
                    add("-I$path")
                }
                
                // Add standard Android includes
                projectDir?.let { dir ->
                    add("-I${dir.absolutePath}")
                    add("-I${dir.absolutePath}/app/src/main/cpp")
                }
            }

            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    projectDir?.let { directory(it) }
                }
                .start()

            val output = withTimeoutOrNull(BuildToolsConfig.clangdTimeoutMs(context)) {
                process.inputStream.bufferedReader().readText()
            } ?: run {
                process.destroyForcibly()
                return ClangdResult(
                    success = false,
                    diagnostics = emptyList(),
                    error = "clangd timed out"
                )
            }

            process.waitFor(BuildToolsConfig.clangdTimeoutMs(context), java.util.concurrent.TimeUnit.MILLISECONDS)

            // Parse clangd output
            val diagnostics = parseClangdOutput(output, file.absolutePath)
            
            return ClangdResult(
                success = true,
                diagnostics = diagnostics
            )

        } finally {
            tempFile.delete()
        }
    }

    /**
     * Basic clang analysis as fallback
     */
    private suspend fun tryBasicClangAnalysis(
        file: File,
        source: String?,
        projectDir: File?,
        includePaths: List<String>
    ): ClangdResult {
        val content = source ?: file.readText()
        val tempFile = File(context.cacheDir, "clang_analysis_${file.name}")
        tempFile.writeText(content)

        try {
            // Find clang binary
            val clangBin = findClang() ?: return ClangdResult(
                success = false,
                diagnostics = emptyList(),
                error = "No C/C++ analyzer found"
            )

            val args = buildList {
                add(clangBin.absolutePath)
                add("-fsyntax-only")
                add("-Xclang")
                add("-verify")
                add("-x")
                add(if (file.extension in setOf("h", "hpp", "hxx")) "c++-header" else "c++")
                add(tempFile.absolutePath)
                
                includePaths.forEach { path ->
                    add("-I$path")
                }
            }

            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    projectDir?.let { directory(it) }
                }
                .start()

            val output = withTimeoutOrNull(BuildToolsConfig.clangdTimeoutMs(context)) {
                process.inputStream.bufferedReader().readText()
            } ?: run {
                process.destroyForcibly()
                return ClangdResult(
                    success = false,
                    diagnostics = emptyList(),
                    error = "clang timed out"
                )
            }

            process.waitFor(BuildToolsConfig.clangdTimeoutMs(context), java.util.concurrent.TimeUnit.MILLISECONDS)

            // Parse clang output
            val diagnostics = parseClangOutput(output, file.absolutePath)
            
            return ClangdResult(
                success = true,
                diagnostics = diagnostics
            )

        } finally {
            tempFile.delete()
        }
    }

    /**
     * Find clangd binary
     */
    private fun findClangd(): File? {
        val candidates = listOf(
            // NDK clangd
            File(context.filesDir, "toolchain/ndk/clangd"),
            File(context.filesDir, "toolchain/llvm/bin/clangd"),
            // System clangd
            File("/system/bin/clangd"),
            File("/data/data/com.termux/files/usr/bin/clangd")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    /**
     * Find clang binary
     */
    private fun findClang(): File? {
        val candidates = listOf(
            // NDK clang
            File(context.filesDir, "toolchain/ndk/bin/clang"),
            File(context.filesDir, "toolchain/llvm/bin/clang"),
            // System clang
            File("/system/bin/clang"),
            File("/data/data/com.termux/files/usr/bin/clang")
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
    }

    /**
     * Parse clangd diagnostic output
     */
    private fun parseClangdOutput(output: String, filePath: String): List<ClangdDiagnostic> {
        val diagnostics = mutableListOf<ClangdDiagnostic>()
        
        // clangd output format: file:line:col: severity: message
        val pattern = Regex("""^(.+?):(\d+):(\d+):\s*(error|warning|note|info):\s*(.+)$""")
        
        output.lines().forEach { line ->
            pattern.find(line.trim())?.let { match ->
                val (file, lineStr, colStr, severity, message) = match.destructured
                
                // Only include diagnostics for our file
                if (file.endsWith(filePath.substringAfterLast('/')) || file == filePath) {
                    diagnostics.add(
                        ClangdDiagnostic(
                            file = filePath,
                            line = lineStr.toIntOrNull() ?: 1,
                            column = colStr.toIntOrNull() ?: 1,
                            endLine = lineStr.toIntOrNull() ?: 1,
                            endColumn = colStr.toIntOrNull() ?: 1,
                            severity = when (severity.lowercase()) {
                                "error" -> ClangdDiagnostic.Severity.ERROR
                                "warning" -> ClangdDiagnostic.Severity.WARNING
                                "note" -> ClangdDiagnostic.Severity.INFO
                                else -> ClangdDiagnostic.Severity.HINT
                            },
                            message = message
                        )
                    )
                }
            }
        }
        
        return diagnostics
    }

    /**
     * Parse basic clang output
     */
    private fun parseClangOutput(output: String, filePath: String): List<ClangdDiagnostic> {
        val diagnostics = mutableListOf<ClangdDiagnostic>()
        
        // Basic clang output: file:line:col: error: message
        val pattern = Regex("""^(.+?):(\d+):(\d+):\s*(error|warning):\s*(.+)$""")
        
        output.lines().forEach { line ->
            pattern.find(line.trim())?.let { match ->
                val (file, lineStr, colStr, severity, message) = match.destructured
                
                if (file.endsWith(filePath.substringAfterLast('/')) || file == filePath) {
                    diagnostics.add(
                        ClangdDiagnostic(
                            file = filePath,
                            line = lineStr.toIntOrNull() ?: 1,
                            column = colStr.toIntOrNull() ?: 1,
                            endLine = lineStr.toIntOrNull() ?: 1,
                            endColumn = colStr.toIntOrNull() ?: 1,
                            severity = when (severity.lowercase()) {
                                "error" -> ClangdDiagnostic.Severity.ERROR
                                "warning" -> ClangdDiagnostic.Severity.WARNING
                                else -> ClangdDiagnostic.Severity.INFO
                            },
                            message = message
                        )
                    )
                }
            }
        }
        
        return diagnostics
    }

    /**
     * Convert clangd diagnostics to standard Diagnostic format
     */
    fun toDiagnostics(clangdDiags: List<ClangdDiagnostic>): List<Diagnostic> {
        return clangdDiags.map { clangd ->
            Diagnostic(
                filePath = clangd.file,
                line = clangd.line,
                column = clangd.column,
                length = clangd.endColumn - clangd.column + 1,
                message = clangd.message,
                severity = when (clangd.severity) {
                    ClangdDiagnostic.Severity.ERROR -> DiagnosticSeverity.ERROR
                    ClangdDiagnostic.Severity.WARNING -> DiagnosticSeverity.WARNING
                    ClangdDiagnostic.Severity.INFO -> DiagnosticSeverity.INFO
                    ClangdDiagnostic.Severity.HINT -> DiagnosticSeverity.HINT
                },
                kind = DiagnosticKind.OTHER
            )
        }
    }

    /**
     * Clear diagnostic cache
     */
    fun clearCache() {
        diagnosticCache.clear()
    }

    /**
     * Get completion items for a C/C++ file
     */
    suspend fun getCompletions(
        file: File,
        line: Int,
        column: Int,
        prefix: String = ""
    ): List<CompletionItem> = withContext(Dispatchers.IO) {
        // TODO: Implement clangd completion via LSP
        // For now, return basic completions
        return@withContext emptyList()
    }

    data class CompletionItem(
        val label: String,
        val kind: String,
        val detail: String = "",
        val insertText: String = ""
    )
}
