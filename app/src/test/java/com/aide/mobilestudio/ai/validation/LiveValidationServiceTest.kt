package com.aide.mobilestudio.ai.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiveValidationServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeRunner(var next: CompilerRunResult) : CompilerRunner {
        var lastChanged: Set<File>? = null
        override fun run(projectDir: File, changedFiles: Set<File>?): CompilerRunResult {
            lastChanged = changedFiles
            return next
        }
    }

    private fun ok(output: String) =
        CompilerRunResult(ran = true, available = true, success = output.isBlank(), output = output)

    private fun kotlinError(project: File, rel: String, line: Int, col: Int, message: String): String =
        "e: ${File(project, rel).absolutePath}:$line:$col: $message"

    @Test
    fun `resolves error after edit`() {
        val project = temp.newFolder()
        val runner = FakeRunner(ok(kotlinError(project, "Foo.kt", 10, 5, "unresolved reference: foo")))
        val service = LiveValidationService(runner)

        val baseline = service.begin(project)
        assertTrue(baseline.available)
        assertEquals(1, baseline.diagnostics.size)

        service.recordChanges(listOf(File(project, "Foo.kt")))
        runner.next = ok("")

        val summary = service.validate(project)
        assertEquals(1, summary.diff.resolvedErrorCount)
        assertEquals(0, summary.diff.newErrorCount)
        assertTrue(runner.lastChanged?.isNotEmpty() == true)
    }

    @Test
    fun `detects newly introduced error`() {
        val project = temp.newFolder()
        val runner = FakeRunner(ok(""))
        val service = LiveValidationService(runner)
        service.begin(project)
        service.recordChanges(listOf(File(project, "Foo.kt")))
        runner.next = ok(kotlinError(project, "Foo.kt", 3, 1, "unresolved reference: bar"))

        val summary = service.validate(project)
        assertEquals(1, summary.diff.newErrorCount)
    }

    @Test
    fun `skips when toolchain unavailable`() {
        val project = temp.newFolder()
        val runner = FakeRunner(CompilerRunResult.unavailable("no toolchain"))
        val service = LiveValidationService(runner)
        service.begin(project)
        runner.next = CompilerRunResult.skipped("still no toolchain")

        val summary = service.validate(project)
        assertEquals(ValidationStatus.SKIPPED, summary.diff.status)
        assertFalse(summary.baselineAvailable)
    }

    @Test
    fun `reports timeout`() {
        val project = temp.newFolder()
        val runner = FakeRunner(ok(kotlinError(project, "Foo.kt", 1, 1, "err")))
        val service = LiveValidationService(runner)
        service.begin(project)
        runner.next = CompilerRunResult.timedOut("Realtime: kotlinc timed out after 300s", 300_000)

        val summary = service.validate(project)
        assertEquals(ValidationStatus.TIMED_OUT, summary.diff.status)
    }

    @Test
    fun `no baseline cannot classify`() {
        val project = temp.newFolder()
        val runner = FakeRunner(CompilerRunResult.unavailable("no toolchain"))
        val service = LiveValidationService(runner)
        service.begin(project)
        runner.next = ok(kotlinError(project, "Foo.kt", 1, 1, "err"))

        val summary = service.validate(project)
        assertEquals(ValidationStatus.NO_BASELINE, summary.diff.status)
        assertEquals(1, summary.diff.uncertain.size)
    }

    @Test
    fun `change tracker restores modified file`() {
        val project = temp.newFolder()
        val file = File(project, "Foo.kt").apply { writeText("before") }
        val tracker = ChangeTracker()
        tracker.record(file)
        file.writeText("after")

        val restored = tracker.restoreAll()
        assertEquals(listOf(file.absolutePath), restored)
        assertEquals("before", file.readText())
    }

    @Test
    fun `change tracker deletes newly created file`() {
        val project = temp.newFolder()
        val file = File(project, "New.kt")
        val tracker = ChangeTracker()
        tracker.record(file) // does not exist yet
        file.writeText("created")

        tracker.restoreAll()
        assertFalse(file.exists())
    }
}
