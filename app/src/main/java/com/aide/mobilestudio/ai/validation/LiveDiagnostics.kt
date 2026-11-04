package com.aide.mobilestudio.ai.validation

import com.aide.mobilestudio.BuildProblem
import java.io.File

enum class LiveSeverity { ERROR, WARNING, INFO }

/**
 * A normalized diagnostic independent of any compiler or provider. Everything in
 * the live-validation pipeline speaks this type so UI, diffing and repair do not
 * depend on a specific tool's output format.
 */
data class LiveDiagnostic(
    val file: String,
    val line: Int,
    val column: Int,
    val severity: LiveSeverity,
    val message: String,
    val code: String? = null,
    val snippet: String? = null,
    val source: String? = null,
) {
    fun locationAvailable(): Boolean = file.isNotBlank() && line > 0
}

/** Converts the existing parser's output into the pipeline's diagnostic type. */
object LiveDiagnostics {
    fun fromProblems(problems: List<BuildProblem>, projectDir: File?): List<LiveDiagnostic> =
        problems.map { problem ->
            LiveDiagnostic(
                file = problem.displayPath(projectDir),
                line = problem.line,
                column = problem.column,
                severity = when (problem.severity) {
                    BuildProblem.Severity.ERROR -> LiveSeverity.ERROR
                    BuildProblem.Severity.WARNING -> LiveSeverity.WARNING
                    BuildProblem.Severity.INFO -> LiveSeverity.INFO
                },
                message = problem.message,
                code = null,
                snippet = problem.raw.takeIf { it.isNotBlank() },
            )
        }
}

/**
 * Normalizes diagnostic messages so the same underlying problem compares equal
 * even when volatile details differ (addresses, absolute paths, line numbers).
 */
object DiagnosticNormalizer {

    private val hex = Regex("0x[0-9a-fA-F]+")
    private val appData = Regex("/data/(?:user/\\d+|data)/[^/]+/")
    private val absPath = Regex("(?:/[^\\s:'\"]+)+/([^/\\s:'\"]+)")
    private val quoted = Regex("'([^']*)'")

    fun normalizeMessage(message: String): String {
        var m = message.trim()
        m = m.replace(hex, "0x…")
        m = m.replace(appData, "/app_data/")
        m = m.replace(absPath) { it.groupValues[1] }
        m = m.replace(Regex("\\s+"), " ")
        return m
    }

    /** Stable identity used for exact matching (file + code + normalized message). */
    fun identity(d: LiveDiagnostic): String {
        val code = d.code ?: ""
        return "${d.file}|$code|${normalizeMessage(d.message)}"
    }

    /** Token-overlap similarity in [0,1], used for fuzzy "moved / reworded" matching. */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return if (ta == tb) 1.0 else 0.0
        val inter = ta.intersect(tb).size.toDouble()
        val union = ta.union(tb).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    private fun tokens(s: String): Set<String> = normalizeMessage(s)
        .lowercase()
        .split(Regex("[^a-z0-9가-힣]+"))
        .filter { it.length > 1 }
        .toSet()

    /** True when two diagnostics point at the same file (exact or basename). */
    fun sameFile(a: LiveDiagnostic, b: LiveDiagnostic): Boolean {
        if (a.file == b.file) return true
        if (a.file.isBlank() || b.file.isBlank()) return false
        return File(a.file).name == File(b.file).name
    }
}

enum class ValidationStatus { COMPLETED, FAILED, SKIPPED, TIMED_OUT, CANCELLED, NO_BASELINE }

/** A fuzzy pairing the engine is not fully certain about; surfaced, not hidden. */
data class UncertainMatch(val current: LiveDiagnostic, val baseline: LiveDiagnostic, val reason: String)

/**
 * Compares diagnostics before and after an AI edit.
 *
 * Matching is multi-signal (priority): file + code + normalized message + line
 * proximity → file + code + normalized message (moved) → token similarity
 * (reworded). Only the first kind is treated as a confident match; the others are
 * recorded in [DiagnosticDiff.uncertain] and never presented as certain.
 */
data class DiagnosticDiff(
    val new: List<LiveDiagnostic> = emptyList(),
    val resolved: List<LiveDiagnostic> = emptyList(),
    val existing: List<LiveDiagnostic> = emptyList(),
    val remaining: List<LiveDiagnostic> = emptyList(),
    val warnings: List<LiveDiagnostic> = emptyList(),
    val uncertain: List<UncertainMatch> = emptyList(),
    val baselineAvailable: Boolean = true,
    val status: ValidationStatus = ValidationStatus.COMPLETED,
    val note: String? = null,
) {
    val newErrorCount: Int get() = new.count { it.severity == LiveSeverity.ERROR }
    val resolvedErrorCount: Int get() = resolved.count { it.severity == LiveSeverity.ERROR }
    val existingErrorCount: Int get() = existing.count { it.severity == LiveSeverity.ERROR }
    val remainingErrorCount: Int get() = remaining.count { it.severity == LiveSeverity.ERROR }
    val warningCount: Int get() = warnings.count { it.severity == LiveSeverity.WARNING }

    fun report(): String {
        if (status != ValidationStatus.COMPLETED) {
            val reason = note ?: when (status) {
                ValidationStatus.TIMED_OUT -> "compiler execution timed out"
                ValidationStatus.CANCELLED -> "validation was cancelled"
                ValidationStatus.SKIPPED -> "no applicable toolchain"
                ValidationStatus.FAILED -> "validation failed to run"
                ValidationStatus.NO_BASELINE -> "no baseline was collected"
                ValidationStatus.COMPLETED -> ""
            }
            return "Validation not completed: $reason"
        }
        if (!baselineAvailable) {
            return "Validation completed without a baseline: ${remainingErrorCount} error(s), " +
                "$warningCount warning(s). New vs existing could not be determined."
        }
        return buildString {
            append("Validation completed\n")
            append("New errors: $newErrorCount\n")
            append("Resolved errors: $resolvedErrorCount\n")
            append("Existing errors: $existingErrorCount\n")
            append("Warnings: $warningCount")
        }
    }
}

class DiagnosticDiffEngine(
    private val lineTolerance: Int = 8,
    private val similarityThreshold: Double = 0.6,
) {

    fun diff(
        baseline: List<LiveDiagnostic>,
        current: List<LiveDiagnostic>,
        status: ValidationStatus = ValidationStatus.COMPLETED,
    ): DiagnosticDiff {
        val warnings = current.filter { it.severity != LiveSeverity.ERROR }

        if (status != ValidationStatus.COMPLETED) {
            return DiagnosticDiff(
                baselineAvailable = true,
                status = status,
                warnings = warnings,
                note = "validation did not complete cleanly; comparison skipped",
            )
        }

        val baseErrors = baseline.filter { it.severity == LiveSeverity.ERROR }
        val curErrors = current.filter { it.severity == LiveSeverity.ERROR }
        val used = BooleanArray(baseErrors.size)
        val existing = mutableListOf<LiveDiagnostic>()
        val introduced = mutableListOf<LiveDiagnostic>()
        val uncertain = mutableListOf<UncertainMatch>()

        for (cur in curErrors) {
            // 1. Confident: same file/code/message and position within tolerance.
            val exact = baseErrors.indices.firstOrNull { k ->
                !used[k] &&
                    DiagnosticNormalizer.sameFile(baseErrors[k], cur) &&
                    baseErrors[k].code == cur.code &&
                    DiagnosticNormalizer.normalizeMessage(baseErrors[k].message) ==
                    DiagnosticNormalizer.normalizeMessage(cur.message) &&
                    withinTolerance(baseErrors[k], cur)
            }
            if (exact != null) {
                used[exact] = true
                existing.add(cur)
                continue
            }

            // 2. Moved within the file: same file/code/message, different line.
            val moved = baseErrors.indices.filter { k ->
                !used[k] &&
                    DiagnosticNormalizer.sameFile(baseErrors[k], cur) &&
                    baseErrors[k].code == cur.code &&
                    DiagnosticNormalizer.normalizeMessage(baseErrors[k].message) ==
                    DiagnosticNormalizer.normalizeMessage(cur.message)
            }
            if (moved.size == 1) {
                used[moved[0]] = true
                existing.add(cur)
                continue
            }

            // 3. Fuzzy: similar message in the same file. Flagged uncertain.
            val curTokens = DiagnosticNormalizer.normalizeMessage(cur.message)
            val scored = baseErrors.indices
                .filter { k -> !used[k] && DiagnosticNormalizer.sameFile(baseErrors[k], cur) }
                .map { k -> k to DiagnosticNormalizer.similarity(baseErrors[k].message, cur.message) }
                .filter { it.second >= similarityThreshold }
                .sortedByDescending { it.second }
            if (scored.isNotEmpty() && (scored.size == 1 || scored[0].second - scored[1].second >= 0.15)) {
                val best = scored[0].first
                used[best] = true
                existing.add(cur)
                uncertain.add(
                    UncertainMatch(cur, baseErrors[best], "matched by message similarity (reworded/moved)"),
                )
                continue
            }

            introduced.add(cur)
        }

        val resolved = baseErrors.filterIndexed { k, _ -> !used[k] }
        return DiagnosticDiff(
            new = introduced,
            resolved = resolved,
            existing = existing,
            remaining = curErrors,
            warnings = warnings,
            uncertain = uncertain,
            baselineAvailable = true,
            status = ValidationStatus.COMPLETED,
        )
    }

    /**
     * When no baseline was collected the engine must not pretend it can separate
     * new from existing errors. Everything is reported as undetermined.
     */
    fun diffWithoutBaseline(current: List<LiveDiagnostic>): DiagnosticDiff {
        val errors = current.filter { it.severity == LiveSeverity.ERROR }
        return DiagnosticDiff(
            new = emptyList(),
            resolved = emptyList(),
            existing = emptyList(),
            remaining = errors,
            warnings = current.filter { it.severity != LiveSeverity.ERROR },
            uncertain = errors.map { UncertainMatch(it, it, "no baseline available") },
            baselineAvailable = false,
            status = ValidationStatus.NO_BASELINE,
        )
    }

    private fun withinTolerance(a: LiveDiagnostic, b: LiveDiagnostic): Boolean =
        kotlin.math.abs(a.line - b.line) <= lineTolerance || !a.locationAvailable() || !b.locationAvailable()
}
