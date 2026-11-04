package com.aide.mobilestudio.ai.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDiffEngineTest {

    private val engine = DiagnosticDiffEngine()

    private fun err(file: String, line: Int, message: String, code: String? = null) =
        LiveDiagnostic(file, line, 1, LiveSeverity.ERROR, message, code)

    private fun warn(file: String, line: Int, message: String) =
        LiveDiagnostic(file, line, 1, LiveSeverity.WARNING, message)

    // 1. No existing errors and no new errors.
    @Test
    fun `clean to clean`() {
        val diff = engine.diff(emptyList(), emptyList())
        assertEquals(0, diff.newErrorCount)
        assertEquals(0, diff.resolvedErrorCount)
        assertEquals(0, diff.existingErrorCount)
        assertEquals(ValidationStatus.COMPLETED, diff.status)
    }

    // 2. Existing error remains unchanged.
    @Test
    fun `existing error stays`() {
        val e = err("A.kt", 10, "unresolved reference: foo")
        val diff = engine.diff(listOf(e), listOf(e))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(0, diff.newErrorCount)
        assertEquals(0, diff.resolvedErrorCount)
    }

    // 3. Existing error is fixed.
    @Test
    fun `existing error resolved`() {
        val e = err("A.kt", 10, "unresolved reference: foo")
        val diff = engine.diff(listOf(e), emptyList())
        assertEquals(1, diff.resolvedErrorCount)
        assertEquals(0, diff.newErrorCount)
    }

    // 4. A new error appears.
    @Test
    fun `new error introduced`() {
        val e = err("A.kt", 10, "unresolved reference: bar")
        val diff = engine.diff(emptyList(), listOf(e))
        assertEquals(1, diff.newErrorCount)
        assertEquals(0, diff.resolvedErrorCount)
    }

    // 5. Existing and new errors coexist.
    @Test
    fun `existing plus new`() {
        val old = err("A.kt", 10, "unresolved reference: foo")
        val introduced = err("A.kt", 20, "type mismatch")
        val diff = engine.diff(listOf(old), listOf(old, introduced))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(1, diff.newErrorCount)
        assertEquals(2, diff.remainingErrorCount)
    }

    // 6. Only some of several errors are resolved.
    @Test
    fun `partial resolution`() {
        val a = err("A.kt", 10, "unresolved reference: foo")
        val b = err("A.kt", 20, "unresolved reference: baz")
        val diff = engine.diff(listOf(a, b), listOf(a))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(1, diff.resolvedErrorCount)
    }

    // 7. Line number shifts within tolerance -> still the same error.
    @Test
    fun `line shifts within tolerance`() {
        val before = err("A.kt", 10, "unresolved reference: foo")
        val after = err("A.kt", 13, "unresolved reference: foo")
        val diff = engine.diff(listOf(before), listOf(after))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(0, diff.newErrorCount)
        assertTrue(diff.uncertain.isEmpty())
    }

    // 8. Related code moved far -> matched by identity, not similarity.
    @Test
    fun `large move matched by identity`() {
        val before = err("A.kt", 10, "unresolved reference: foo")
        val after = err("A.kt", 400, "unresolved reference: foo")
        val diff = engine.diff(listOf(before), listOf(after))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(0, diff.resolvedErrorCount)
        assertTrue(diff.uncertain.isEmpty())
    }

    // 9. Message reworded -> matched, but flagged uncertain.
    @Test
    fun `reworded message flagged uncertain`() {
        val before = err("A.kt", 10, "cannot find symbol: method foo()")
        val after = err("A.kt", 10, "cannot find symbol: method foo(int)")
        val diff = engine.diff(listOf(before), listOf(after))
        assertEquals(1, diff.existingErrorCount)
        assertEquals(1, diff.uncertain.size)
    }

    // 10. Compiler timed out -> comparison not attempted.
    @Test
    fun `timeout yields timed out status`() {
        val diff = engine.diff(listOf(err("A.kt", 1, "x")), emptyList(), ValidationStatus.TIMED_OUT)
        assertEquals(ValidationStatus.TIMED_OUT, diff.status)
        assertEquals(0, diff.newErrorCount)
    }

    // 11. No baseline -> cannot classify new vs existing.
    @Test
    fun `no baseline is undetermined`() {
        val diff = engine.diffWithoutBaseline(listOf(err("A.kt", 1, "x"), err("A.kt", 2, "y")))
        assertEquals(ValidationStatus.NO_BASELINE, diff.status)
        assertEquals(0, diff.newErrorCount)
        assertEquals(0, diff.resolvedErrorCount)
        assertEquals(2, diff.uncertain.size)
    }

    // 12. Cancellation.
    @Test
    fun `cancelled status`() {
        val diff = engine.diff(emptyList(), emptyList(), ValidationStatus.CANCELLED)
        assertEquals(ValidationStatus.CANCELLED, diff.status)
    }

    // 13. Missing location info must not break matching.
    @Test
    fun `missing location still matches`() {
        val before = err("A.kt", 0, "gradle sync failed")
        val after = err("A.kt", 0, "gradle sync failed")
        val diff = engine.diff(listOf(before), listOf(after))
        assertEquals(1, diff.existingErrorCount)
    }

    // 14. Warnings are tracked separately from errors.
    @Test
    fun `warnings counted separately`() {
        val diff = engine.diff(emptyList(), listOf(warn("A.kt", 5, "unused variable")))
        assertEquals(1, diff.warningCount)
        assertEquals(0, diff.newErrorCount)
    }

    // 15. Same message in different files must not be conflated.
    @Test
    fun `different files not conflated`() {
        val before = err("A.kt", 10, "unresolved reference: foo")
        val after = err("B.kt", 10, "unresolved reference: foo")
        val diff = engine.diff(listOf(before), listOf(after))
        assertEquals(1, diff.newErrorCount)
        assertEquals(1, diff.resolvedErrorCount)
    }

    @Test
    fun `report mentions counts`() {
        val diff = engine.diff(
            listOf(err("A.kt", 1, "e1")),
            listOf(err("A.kt", 2, "e2")),
        )
        val report = diff.report()
        assertTrue(report.contains("New errors"))
        assertTrue(report.contains("Resolved errors"))
    }

    @Test
    fun `normalizer collapses volatile details`() {
        val a = DiagnosticNormalizer.normalizeMessage("Error at 0x1a2b3c in /data/user/0/pkg/files/x.kt")
        val b = DiagnosticNormalizer.normalizeMessage("Error at 0x9f8e7d in /data/user/0/other/x.kt")
        assertEquals(a, b)
    }
}
