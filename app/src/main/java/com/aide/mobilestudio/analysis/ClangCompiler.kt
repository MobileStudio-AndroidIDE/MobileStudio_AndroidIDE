package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.BuildToolsConfig
import com.aide.mobilestudio.WindowsX64BuildBackend
import com.aide.mobilestudio.ExecHelper
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ClangCompiler - C/C++ code analysis using Clang
 * 
 * Responsibilities:
 * - Syntax error detection using -fsyntax-only
 * - Reuse configuration from WindowsX64BuildBackend
 * - Diagnostic parsing for editor feedback
 */
class ClangCompiler(private val context: Context) {

    companion object {
        private const val TAG = "ClangCompiler"
        private const val MAX_FILE_SIZE_KB = 1024
    }

    private val backend = WindowsX64BuildBackend(context)
    private val diagnosticCache = ConcurrentHashMap<String, CompilerResult>()

    data class CompilerResult(
        val success: Boolean,
        val diagnostics: List<Diagnostic>,
        val durationMs: Long = 0,
        val rawOutput: String = ""
    )

    /**
     * Run syntax check on a C/C++ file
     */
    suspend fun checkSyntax(
        file: File,
        source: String? = null,
        projectDir: File? = null
    ): CompilerResult = withContext(Dispatchers.IO) {
        if (!file.exists() && source == null) {
            return@withContext CompilerResult(false, emptyList(), error = "File not found")
        }

        val fileSizeKB = file.length() / 1024
        if (fileSizeKB > MAX_FILE_SIZE_KB) {
            return@withContext CompilerResult(false, emptyList(), error = "File too large")
        }

        val cacheKey = file.absolutePath
        val content = source ?: file.readText()

        val startTime = System.currentTimeMillis()
        
        try {
            val result = runClangSyntaxCheck(file, content, projectDir)
            val duration = System.currentTimeMillis() - startTime
            val finalResult = result.copy(durationMs = duration)
            
            diagnosticCache[cacheKey] = finalResult
            return@withContext finalResult
        } catch (e: Exception) {
            Log.e(TAG, "Clang analysis failed for ${file.name}", e)
            return@withContext CompilerResult(false, emptyList(), rawOutput = e.message ?: "Unknown error")
        }
    }

    private suspend fun runClangSyntaxCheck(
        file: File,
        content: String,
        projectDir: File?
    ): CompilerResult {
        val clangBin = backend.clangPath() ?: return CompilerResult(false, emptyList(), rawOutput = "Clang not found")
        
        // Use temp file for unsaved content
        val tempFile = File(context.cacheDir, "clang_syntax_check_${file.name}")
        tempFile.writeText(content)

        try {
            val isCpp = file.extension.lowercase() in setOf("cpp", "cxx", "cc", "c++", "hpp", "hxx", "h")
            val triple = "x86_64-w64-mingw32"
            val mingwRoot = backend.mingwRoot
            val tripleDir = backend.mingwTriple
            val sysroot = if (File(tripleDir, "include").isDirectory) tripleDir else mingwRoot

            val args = mutableListOf(
                clangBin.absolutePath,
                "--target=$triple",
                "--sysroot=${sysroot.absolutePath}",
                "-fsyntax-only",
                "-fno-caret-diagnostics",
                "-fdiagnostics-print-source-range-info",
                "-fno-color-diagnostics"
            )

            // Resource dir is crucial for internal headers
            backend.resourceDir()?.let { args.add("-resource-dir=${it.absolutePath}") }
            args.add("-B${clangBin.parentFile!!.absolutePath}")

            // Add standard includes
            if (File(tripleDir, "include").isDirectory) {
                args.add("-isystem"); args.add(File(tripleDir, "include").absolutePath)
            }
            if (File(mingwRoot, "include").isDirectory) {
                args.add("-isystem"); args.add(File(mingwRoot, "include").absolutePath)
            }

            // Project local includes
            projectDir?.let { dir ->
                args.add("-I${dir.absolutePath}")
                args.add("-I${File(dir, "app/src/main/cpp").absolutePath}")
            }

            args.add(if (isCpp) "-xc++" else "-xc")
            args.add(tempFile.absolutePath)

            val procEnv = mutableMapOf<String, String>()
            val execRedirect = File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so")
            if (execRedirect.exists()) {
                procEnv["LD_PRELOAD"] = execRedirect.absolutePath
            }

            val process = ExecHelper.createProcess(context, args, projectDir, procEnv).redirectErrorStream(true).start()
            
            val output = StringBuilder()
            val readerJob = CoroutineScope(Dispatchers.IO).launch {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.appendLine(line)
                    }
                }
            }

            val exited = process.waitFor(BuildToolsConfig.clangTimeoutMs(context), TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                readerJob.cancel()
                return CompilerResult(false, emptyList(), rawOutput = "Clang timed out")
            }
            readerJob.join()

            val rawOutput = output.toString()
            val diagnostics = parseClangOutput(rawOutput, tempFile.absolutePath, file.absolutePath)
            
            return CompilerResult(
                success = process.exitValue() == 0,
                diagnostics = diagnostics,
                rawOutput = rawOutput
            )

        } finally {
            tempFile.delete()
        }
    }

    /**
     * Parse clang output in format: file:line:col: severity: message
     */
    private fun parseClangOutput(output: String, tempPath: String, originalPath: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        
        // Clang output pattern: <path>:<line>:<col>: <severity>: <message>
        val pattern = Regex("""^(.+?):(\d+):(\d+):\s*(error|warning|note|fatal error):\s*(.+)$""")
        
        output.lines().forEach { line ->
            pattern.find(line.trim())?.let { match ->
                val (path, lineStr, colStr, severity, message) = match.destructured
                
                // Check if the diagnostic is for the file we are checking
                if (path == tempPath || path == originalPath || path.endsWith(originalPath.substringAfterLast('/'))) {
                    val severityType = when (severity.lowercase()) {
                        "error", "fatal error" -> DiagnosticSeverity.ERROR
                        "warning" -> DiagnosticSeverity.WARNING
                        "note" -> DiagnosticSeverity.INFO
                        else -> DiagnosticSeverity.HINT
                    }

                    diagnostics.add(
                        Diagnostic(
                            filePath = originalPath,
                            line = lineStr.toIntOrNull() ?: 1,
                            column = colStr.toIntOrNull() ?: 1,
                            length = 1, // Basic length, Clang range info can be used for better precision
                            message = message.trim(),
                            severity = severityType,
                            kind = DiagnosticKind.OTHER
                        )
                    )
                }
            }
        }
        
        return diagnostics
    }

    fun clearCache() {
        diagnosticCache.clear()
    }
}

// Extension to facilitate error handling in suspend functions
private fun CompilerResult(success: Boolean, diagnostics: List<Diagnostic>, error: String): ClangCompiler.CompilerResult {
    return ClangCompiler.CompilerResult(success, diagnostics, rawOutput = error)
}
