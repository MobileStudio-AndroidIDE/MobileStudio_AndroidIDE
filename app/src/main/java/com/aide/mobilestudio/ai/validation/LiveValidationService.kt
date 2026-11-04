package com.aide.mobilestudio.ai.validation

import com.aide.mobilestudio.BuildProblemParser
import java.io.File

/** Outcome of running a compiler/build for validation. */
data class CompilerRunResult(
    val ran: Boolean,
    val available: Boolean,
    val success: Boolean,
    val output: String,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val durationMs: Long = 0,
    val failureReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String) = CompilerRunResult(false, false, false, "", failureReason = reason)
        fun skipped(reason: String) = CompilerRunResult(false, true, false, "", failureReason = reason)
        fun cancelled() = CompilerRunResult(true, true, false, "", cancelled = true)
        fun timedOut(output: String, durationMs: Long) =
            CompilerRunResult(true, true, false, output, timedOut = true, durationMs = durationMs)
    }
}

/**
 * Abstraction over the existing compiler toolchain. The Android implementation
 * wraps `analysis.RealtimeCompiler`; unit tests inject a fake so the whole
 * validation pipeline is testable without a device.
 */
interface CompilerRunner {
    /** [changedFiles] = null means a full baseline run. */
    fun run(projectDir: File, changedFiles: Set<File>?): CompilerRunResult
}

/** Turns raw compiler output into normalized diagnostics. */
interface DiagnosticParser {
    fun parse(output: String, projectDir: File): List<LiveDiagnostic>
}

/** Reuses the project's existing `BuildProblemParser` (pure Kotlin). */
class BuildProblemDiagnosticParser : DiagnosticParser {
    override fun parse(output: String, projectDir: File): List<LiveDiagnostic> =
        LiveDiagnostics.fromProblems(
            runCatching { BuildProblemParser.parse(output, projectDir) }.getOrDefault(emptyList()),
            projectDir,
        )
}

/**
 * Records which files an AI edit touched and keeps the pre-edit content so a
 * failed change can be reverted. Only files changed by the current AI task are
 * recorded; earlier user edits are never captured or overwritten implicitly.
 */
class ChangeTracker {
    data class ChangeRecord(
        val path: String,
        val existedBefore: Boolean,
        val beforeContent: String?,
    )

    private val records = LinkedHashMap<String, ChangeRecord>()

    fun record(file: File) {
        val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (records.containsKey(key)) return
        val existed = file.isFile
        records[key] = ChangeRecord(
            path = file.absolutePath,
            existedBefore = existed,
            beforeContent = if (existed) runCatching { file.readText() }.getOrNull() else null,
        )
    }

    fun recordAll(files: Collection<File>) = files.forEach { record(it) }

    fun changedFiles(): Set<File> = records.values.map { File(it.path) }.toSet()

    fun records(): List<ChangeRecord> = records.values.toList()

    /** Restores every tracked file to its pre-change state. Returns restored paths. */
    fun restoreAll(): List<String> {
        val restored = mutableListOf<String>()
        for (record in records.values) {
            val file = File(record.path)
            val ok = runCatching {
                if (record.existedBefore) {
                    record.beforeContent?.let { file.writeText(it) } ?: return@runCatching false
                } else {
                    file.delete()
                }
                true
            }.getOrDefault(false)
            if (ok) restored.add(record.path)
        }
        return restored
    }

    fun clear() = records.clear()

    fun size(): Int = records.size
}

data class ValidationSummary(
    val diff: DiagnosticDiff,
    val run: CompilerRunResult?,
    val changedFileCount: Int,
    val baselineAvailable: Boolean,
) {
    fun report(): String {
        val head = "Changed files: $changedFileCount\n"
        return head + diff.report()
    }
}

data class BaselineResult(
    val available: Boolean,
    val run: CompilerRunResult,
    val diagnostics: List<LiveDiagnostic>,
)

/**
 * Orchestrates the "edit → compile → compare" loop on top of the existing
 * compiler. It never duplicates the compiler itself: callers supply a
 * [CompilerRunner] (on device this is the real-time Kotlin/Java/C++ compiler).
 */
class LiveValidationService(
    private val runner: CompilerRunner,
    private val parser: DiagnosticParser = BuildProblemDiagnosticParser(),
    private val diffEngine: DiagnosticDiffEngine = DiagnosticDiffEngine(),
) {

    val changeTracker = ChangeTracker()
    private var baseline: List<LiveDiagnostic>? = null
    private var baselineRun: CompilerRunResult? = null

    val hasBaseline: Boolean get() = baseline != null
    fun baselineDiagnostics(): List<LiveDiagnostic> = baseline.orEmpty()

    /** Collects the pre-edit baseline. Safe to call repeatedly; the last wins. */
    fun begin(projectDir: File): BaselineResult {
        val run = runner.run(projectDir, null)
        val usable = run.ran && run.available && !run.timedOut && !run.cancelled
        val diagnostics = if (usable) parser.parse(run.output, projectDir) else emptyList()
        baseline = if (usable) diagnostics else null
        baselineRun = run
        return BaselineResult(usable, run, diagnostics)
    }

    fun recordChanges(files: Collection<File>) = changeTracker.recordAll(files)

    /** Runs the post-edit validation against the recorded baseline. */
    fun validate(projectDir: File): ValidationSummary {
        val changed = changeTracker.changedFiles()
        val run = runner.run(projectDir, changed.ifEmpty { null })

        val status = when {
            run.cancelled -> ValidationStatus.CANCELLED
            run.timedOut -> ValidationStatus.TIMED_OUT
            !run.ran -> ValidationStatus.SKIPPED
            !run.available -> ValidationStatus.SKIPPED
            run.failureReason != null -> ValidationStatus.FAILED
            else -> ValidationStatus.COMPLETED
        }

        val diff = when {
            status != ValidationStatus.COMPLETED ->
                DiagnosticDiff(status = status, baselineAvailable = baseline != null, note = run.failureReason)
            baseline == null -> diffEngine.diffWithoutBaseline(parser.parse(run.output, projectDir))
            else -> diffEngine.diff(baseline.orEmpty(), parser.parse(run.output, projectDir))
        }

        return ValidationSummary(
            diff = diff,
            run = run,
            changedFileCount = changeTracker.size(),
            baselineAvailable = baseline != null,
        )
    }

    fun reset() {
        baseline = null
        baselineRun = null
        changeTracker.clear()
    }

    fun lastBaselineRun(): CompilerRunResult? = baselineRun
}
