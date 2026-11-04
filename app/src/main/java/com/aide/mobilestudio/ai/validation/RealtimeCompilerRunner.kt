package com.aide.mobilestudio.ai.validation

import android.content.Context
import com.aide.mobilestudio.analysis.RealtimeCompiler
import java.io.File

/**
 * Adapts the project's existing [RealtimeCompiler] (kotlinc / javac / clang /
 * ninja, no Gradle) to the validation pipeline. No compiler logic is duplicated
 * here — configuration, classpath and tool discovery all stay in RealtimeCompiler.
 */
class RealtimeCompilerRunner(private val context: Context) : CompilerRunner {

    private val compiler = RealtimeCompiler(context)

    override fun run(projectDir: File, changedFiles: Set<File>?): CompilerRunResult {
        val outcome = compiler.compile(projectDir, changedFiles)
        val timedOut = outcome.lines.any { it.contains("timed out", ignoreCase = true) }
        val failureReason = when {
            !outcome.available -> "no applicable compiler toolchain for the changed files"
            timedOut -> "compiler execution timed out"
            else -> null
        }
        return CompilerRunResult(
            ran = true,
            available = outcome.available,
            success = outcome.success,
            output = outcome.lines.joinToString("\n"),
            timedOut = timedOut,
            cancelled = false,
            durationMs = outcome.durationMs,
            failureReason = failureReason,
        )
    }
}
