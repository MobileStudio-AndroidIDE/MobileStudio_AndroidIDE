package com.aide.mobilestudio.validation

import java.io.File

/**
 * Runs every project check in order, reports per-stage progress, supports
 * cooperative cancellation between and inside checks, de-duplicates issues and
 * produces the final [InspectionReport]. Never modifies project files: all
 * tool output goes to [tempDir].
 */
class ProjectInspector(
    private val projectRoot: File,
    private val toolchain: ValidationToolchain,
    private val runner: ProcessRunner,
    private val cache: ValidationCache?,
    private val tempDir: File,
    private val onStage: (String) -> Unit = {},
    private val onCheckDone: (CheckResult) -> Unit = {}
) {
    private val cancel = CancellationSignal()

    val checks: List<ProjectCheck> = defaultChecks()

    companion object {
        fun defaultChecks(): List<ProjectCheck> = listOf(
            ProjectStructureCheck(),
            KotlinSourceCheck(),
            JavaSourceCheck(),
            ManifestCheck(),
            XmlResourceCheck(),
            ResourceLinkCheck(),
            SettingsScriptCheck(),
            GradlePropertiesCheck(),
            BuildScriptCheck()
        )
    }

    fun cancel() {
        cancel.cancel()
    }

    fun inspect(): InspectionReport {
        val startedAt = System.currentTimeMillis()
        runCatching { tempDir.mkdirs() }

        val model = try {
            GradleProjectScanner.scan(projectRoot)
        } catch (e: Exception) {
            GradleProjectModel(projectRoot, null, emptyList(), null)
        }
        val resDirs = try {
            CheckSupport.findResDirs(projectRoot)
        } catch (_: Exception) {
            emptyList()
        }

        val ctx = ValidationContext(
            projectRoot = projectRoot,
            toolchain = toolchain,
            runner = runner,
            cache = cache,
            tempDir = tempDir,
            cancel = cancel,
            model = model,
            resDirs = resDirs,
            onStage = onStage
        )

        val results = mutableListOf<CheckResult>()
        val allIssues = mutableListOf<InspectionIssue>()
        for (check in checks) {
            if (cancel.isCancelled()) {
                results += CheckResult(check.id, check.displayName, check.progressLabel, CheckStatus.CANCELLED, emptyList(), 0)
                continue
            }
            onStage(check.progressLabel)
            val checkStart = System.currentTimeMillis()
            val outcome = try {
                check.run(ctx)
            } catch (e: Exception) {
                CheckOutcome(
                    listOf(
                        ctx.toolIssue(
                            "Check '${check.displayName}' failed: ${e.message ?: e.javaClass.simpleName}",
                            "TOOL_CHECK_CRASH"
                        )
                    )
                )
            }
            val duration = System.currentTimeMillis() - checkStart
            val status = when {
                cancel.isCancelled() && outcome.issues.isEmpty() -> CheckStatus.CANCELLED
                outcome.issues.any { it.category == InspectionCategory.TOOL } -> CheckStatus.FAILED
                outcome.skipped && outcome.issues.isEmpty() -> CheckStatus.SKIPPED
                outcome.issues.isNotEmpty() -> CheckStatus.ISSUES
                else -> CheckStatus.OK
            }
            val result = CheckResult(check.id, check.displayName, check.progressLabel, status, outcome.issues, duration)
            results += result
            allIssues += outcome.issues
            onCheckDone(result)
        }

        val deduped = allIssues
            .distinctBy { it.dedupeKey() }
            .sortedWith(
                compareBy(
                    { it.severity.ordinal },
                    { it.file?.absolutePath ?: "" },
                    { it.line },
                    { it.column },
                    { it.errorCode }
                )
            )

        return InspectionReport(
            projectRoot = projectRoot,
            checks = results,
            issues = deduped,
            durationMs = System.currentTimeMillis() - startedAt,
            cancelled = cancel.isCancelled()
        )
    }
}
