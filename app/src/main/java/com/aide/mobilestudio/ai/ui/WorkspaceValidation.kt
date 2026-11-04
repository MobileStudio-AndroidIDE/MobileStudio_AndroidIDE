package com.aide.mobilestudio.ai.ui

import android.content.Context
import com.aide.mobilestudio.ai.validation.LiveDiagnostic
import com.aide.mobilestudio.ai.validation.LiveValidationService
import com.aide.mobilestudio.ai.validation.RealtimeCompilerRunner
import com.aide.mobilestudio.ai.validation.ValidationSummary
import java.io.File

/**
 * Drives the workspace's Diagnostics tab using the existing real-time compiler
 * through the Live Validation pipeline. All methods are blocking and must be
 * called off the main thread.
 */
class WorkspaceValidation(context: Context) {

    private val service = LiveValidationService(RealtimeCompilerRunner(context.applicationContext))

    @Volatile private var summary: ValidationSummary? = null
    @Volatile private var baselineReady = false

    fun beginBaseline(projectDir: File) {
        runCatching { service.begin(projectDir) }
        baselineReady = service.hasBaseline
    }

    fun validate(projectDir: File) {
        summary = runCatching { service.validate(projectDir) }.getOrNull()
    }

    fun recordChanges(files: Collection<File>) = service.recordChanges(files)

    fun reset() {
        service.reset()
        summary = null
        baselineReady = false
    }

    fun lastSummary(): ValidationSummary? = summary
    fun hasBaseline(): Boolean = baselineReady

    /** Current errors + warnings from the last validation, de-duplicated. */
    fun diagnostics(): List<LiveDiagnostic> {
        val s = summary ?: return emptyList()
        return (s.diff.new + s.diff.existing + s.diff.warnings)
            .distinctBy { "${it.file}:${it.line}:${it.column}:${it.message}" }
    }

    fun resolved(): List<LiveDiagnostic> = summary?.diff?.resolved ?: emptyList()
}
