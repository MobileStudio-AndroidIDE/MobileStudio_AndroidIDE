package com.aide.mobilestudio.analysis

import android.graphics.Color
import com.aide.mobilestudio.StudioUi
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import java.io.File

class InlineDiagnosticProvider {

    data class EditorDiagnosticSpan(
        val line: Int,
        val column: Int,
        val length: Int,
        val severity: DiagnosticSeverity,
        val message: String,
        val kind: DiagnosticKind
    )

    fun computeSpans(file: File, diagnostics: List<Diagnostic>): List<EditorDiagnosticSpan> {
        val filePath = file.absolutePath
        return diagnostics
            .filter { it.filePath == filePath }
            .sortedWith(compareBy({ it.line }, { it.column }))
            .map { diag ->
                EditorDiagnosticSpan(
                    line = diag.line,
                    column = diag.column,
                    length = diag.length.coerceAtLeast(1),
                    severity = diag.severity,
                    message = diag.message,
                    kind = diag.kind
                )
            }
    }

    fun applyToTextView(textView: TextView, source: String, spans: List<EditorDiagnosticSpan>) {
        val lines = source.lines()
        val builder = SpannableStringBuilder(source)

        val errors = spans.filter { it.severity == DiagnosticSeverity.ERROR && it.line <= lines.size }
        val warnings = spans.filter {
            (it.severity == DiagnosticSeverity.WARNING || it.severity == DiagnosticSeverity.HINT) && it.line <= lines.size
        }

        errors.forEach { span ->
            val lineStart = lines.take(span.line - 1).sumOf { it.length + 1 }
            val start = (lineStart + span.column - 1).coerceIn(0, builder.length)
            val end = (start + span.length).coerceIn(0, builder.length)
            if (start < end) {
                builder.setSpan(
                    BackgroundColorSpan(StudioUi.color("#33FF0000")),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(StudioUi.color(StudioUi.Red)),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        warnings.forEach { span ->
            val lineStart = lines.take(span.line - 1).sumOf { it.length + 1 }
            val start = (lineStart + span.column - 1).coerceIn(0, builder.length)
            val end = (start + span.length).coerceIn(0, builder.length)
            if (start < end) {
                builder.setSpan(
                    BackgroundColorSpan(StudioUi.color("#33E5C07B")),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(StudioUi.color(StudioUi.Amber)),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        if (errors.isNotEmpty() || warnings.isNotEmpty()) {
            textView.setText(builder, TextView.BufferType.SPANNABLE)
        }
    }

    fun buildErrorSummary(diagnostics: List<Diagnostic>): String {
        val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val warnings = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }
        val hints = diagnostics.count { it.severity == DiagnosticSeverity.HINT }

        return buildString {
            if (errors > 0) append("$errors error(s)")
            if (warnings > 0) append(if (isNotEmpty()) ", " else "").append("$warnings warning(s)")
            if (hints > 0) append(if (isNotEmpty()) ", " else "").append("$hints hint(s)")
            if (isEmpty()) append("No issues")
        }
    }
}
