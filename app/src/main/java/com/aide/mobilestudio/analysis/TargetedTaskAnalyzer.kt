package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TargetedTaskAnalyzer - Runs only necessary compilation tasks per open file type
 * 
 * File types and their required tasks:
 * - Kotlin (.kt): compileDebugKotlin
 * - Java (.java): compileDebugJavaWithJavac, kaptDebugKotlin, kspDebugKotlin
 * - C++ (.cpp, .cc, .cxx, .h, .hpp): clangd analysis
 * - XML (.xml): resource validation (no compilation)
 * - Gradle (.gradle, .gradle.kts): configuration validation
 */
class TargetedTaskAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "TargetedTaskAnalyzer"
        
        // File extensions
        private val KOTLIN_EXTENSIONS = setOf("kt")
        private val JAVA_EXTENSIONS = setOf("java")
        private val CPP_EXTENSIONS = setOf("cpp", "cc", "cxx", "c", "h", "hpp", "hxx")
        private val XML_EXTENSIONS = setOf("xml")
        private val GRADLE_EXTENSIONS = setOf("gradle", "gradle.kts")
    }

    data class TaskInfo(
        val tasks: List<String>,
        val analysisType: AnalysisType,
        val description: String
    )

    enum class AnalysisType {
        KOTLIN_COMPILER,
        JAVA_COMPILER,
        CLANGD_ANALYZER,
        XML_VALIDATOR,
        GRADLE_VALIDATOR
    }

    private val taskCache = ConcurrentHashMap<String, TaskInfo>()

    /**
     * Determine which tasks to run for a given file
     */
    fun getTasksForFile(file: File, projectDir: File): TaskInfo {
        val cacheKey = file.absolutePath
        taskCache[cacheKey]?.let { return it }

        val extension = file.extension.lowercase()
        val relativePath = runCatching { file.relativeTo(projectDir).path }.getOrDefault(file.name)

        val taskInfo = when {
            extension in KOTLIN_EXTENSIONS -> {
                TaskInfo(
                    tasks = listOf("compileDebugKotlin"),
                    analysisType = AnalysisType.KOTLIN_COMPILER,
                    description = "Kotlin compilation"
                )
            }
            extension in JAVA_EXTENSIONS -> {
                TaskInfo(
                    tasks = listOf(
                        "compileDebugJavaWithJavac",
                        "kaptDebugKotlin",
                        "kspDebugKotlin"
                    ),
                    analysisType = AnalysisType.JAVA_COMPILER,
                    description = "Java compilation with annotation processing"
                )
            }
            extension in CPP_EXTENSIONS -> {
                TaskInfo(
                    tasks = emptyList(), // clangd doesn't use Gradle tasks
                    analysisType = AnalysisType.CLANGD_ANALYZER,
                    description = "C++ analysis via clangd"
                )
            }
            extension in XML_EXTENSIONS -> {
                TaskInfo(
                    tasks = emptyList(), // XML validation is local
                    analysisType = AnalysisType.XML_VALIDATOR,
                    description = "XML validation"
                )
            }
            extension in GRADLE_EXTENSIONS -> {
                TaskInfo(
                    tasks = listOf("help"), // Basic Gradle validation
                    analysisType = AnalysisType.GRADLE_VALIDATOR,
                    description = "Gradle configuration validation"
                )
            }
            else -> {
                TaskInfo(
                    tasks = emptyList(),
                    analysisType = AnalysisType.KOTLIN_COMPILER,
                    description = "No analysis available"
                )
            }
        }

        taskCache[cacheKey] = taskInfo
        return taskInfo
    }

    /**
     * Get variant-specific task name
     */
    fun getVariantTask(baseTask: String, variant: String = "debug"): String {
        return baseTask.replace("Debug", variant.replaceFirstChar { it.uppercase() })
    }

    /**
     * Check if file is a source file that needs analysis
     */
    fun isSourceFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in KOTLIN_EXTENSIONS || 
               ext in JAVA_EXTENSIONS || 
               ext in CPP_EXTENSIONS
    }

    /**
     * Check if file is a header file (for C++)
     */
    fun isHeaderFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("h", "hpp", "hxx")
    }

    /**
     * Get all source files in a directory recursively
     */
    fun getSourceFiles(dir: File, maxDepth: Int = 10): List<File> {
        return dir.walkTopDown()
            .maxDepth(maxDepth)
            .onEnter { it.name !in setOf(".gradle", "build", ".git", ".idea", "node_modules") }
            .filter { it.isFile && isSourceFile(it) }
            .toList()
    }

    /**
     * Clear task cache (call when project structure changes)
     */
    fun clearCache() {
        taskCache.clear()
    }
}
