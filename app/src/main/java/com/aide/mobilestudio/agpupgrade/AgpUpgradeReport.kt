package com.aide.mobilestudio.agpupgrade

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AGP 업그레이드 결과 리포트 (성공/실패/경고/수동 필요 항목).
 */
class AgpUpgradeReport {

    data class Report(
        val success: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val targetAgpVersion: String,
        val targetGradleVersion: String,
        val targetKotlinVersion: String,
        val completedSteps: List<StepReport>,
        val failedStep: StepReport?,
        val warnings: List<WarningItem>,
        val manualActions: List<ManualAction>,
        val summary: String,
    ) {
        val formattedDate: String
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    data class StepReport(
        val stepId: Int,
        val name: String,
        val type: AgpUpgradePlan.StepType,
        val status: StepStatus,
        val message: String,
        val changedFiles: List<File>,
        val durationMs: Long,
        val errorMessage: String? = null,
    ) {
        enum class StepStatus {
            COMPLETED, FAILED, SKIPPED, ROLLED_BACK
        }
    }

    data class WarningItem(
        val type: WarningType,
        val message: String,
        val file: File?,
        val line: Int?,
        val suggestion: String?,
    ) {
        enum class WarningType {
            DEPRECATED_API, COMPATIBILITY, PERFORMANCE, SECURITY, MANUAL_MIGRATION
        }
    }

    data class ManualAction(
        val title: String,
        val description: String,
        val file: File?,
        val actionType: ActionType,
        val priority: Priority,
    ) {
        enum class ActionType {
            UPDATE_CODE, UPDATE_CONFIG, REVIEW_CHANGES, RUN_TESTS, UPDATE_DOCS
        }
        enum class Priority {
            HIGH, MEDIUM, LOW
        }
    }

    /**
     * 실행 결과로부터 리포트 생성.
     */
    fun generateReport(
        planResult: AgpUpgradePlan.PlanResult,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult,
        compatibilityIssues: List<AgpCompatibilityMatrix.CompatibilityIssue>,
        targetAgpVersion: String,
        targetGradleVersion: String,
        targetKotlinVersion: String,
    ): Report {
        val completedSteps = planResult.completedSteps.map { step ->
            StepReport(
                stepId = step.id,
                name = step.name,
                type = step.type,
                status = when (step.status) {
                    AgpUpgradePlan.StepStatus.COMPLETED -> StepReport.StepStatus.COMPLETED
                    AgpUpgradePlan.StepStatus.FAILED -> StepReport.StepStatus.FAILED
                    AgpUpgradePlan.StepStatus.SKIPPED -> StepReport.StepStatus.SKIPPED
                    AgpUpgradePlan.StepStatus.ROLLED_BACK -> StepReport.StepStatus.ROLLED_BACK
                    else -> StepReport.StepStatus.FAILED
                },
                message = step.errorMessage ?: "Completed",
                changedFiles = emptyList(), // Would need to track from executor
                durationMs = 0, // Would need timing
                errorMessage = step.errorMessage,
            )
        }

        val failedStep = planResult.failedStep?.let { step ->
            StepReport(
                stepId = step.id,
                name = step.name,
                type = step.type,
                status = StepReport.StepStatus.FAILED,
                message = step.errorMessage ?: "Failed",
                changedFiles = emptyList(),
                durationMs = 0,
                errorMessage = step.errorMessage,
            )
        }

        // 호환성 이슈를 경고로 변환
        val warnings = compatibilityIssues.map { issue ->
            WarningItem(
                type = when (issue.type) {
                    AgpCompatibilityMatrix.IssueType.API_DEPRECATION,
                    AgpCompatibilityMatrix.IssueType.NAMESPACE_MIGRATION,
                    AgpCompatibilityMatrix.IssueType.BUILD_TOOLS_VERSION_REMOVAL -> WarningItem.WarningType.DEPRECATED_API
                    AgpCompatibilityMatrix.IssueType.AGP_MAJOR_UPGRADE,
                    AgpCompatibilityMatrix.IssueType.AGP_EOL -> WarningItem.WarningType.COMPATIBILITY
                    AgpCompatibilityMatrix.IssueType.GRADLE_VERSION,
                    AgpCompatibilityMatrix.IssueType.JDK_VERSION,
                    AgpCompatibilityMatrix.IssueType.KOTLIN_VERSION,
                    AgpCompatibilityMatrix.IssueType.NDK_VERSION,
                    AgpCompatibilityMatrix.IssueType.CMAKE_VERSION -> WarningItem.WarningType.COMPATIBILITY
                },
                message = issue.message,
                file = null,
                line = null,
                suggestion = if (issue.autoFixable) "Auto-fix available" else "Manual action required",
            )
        }

        // 수동 작업 필요 항목
        val manualActions = mutableListOf<ManualAction>()

        // AGP 메이저 업그레이드 시
        val currentMajor = scanResult.agpVersion.split(".").first().toIntOrNull() ?: 0
        val targetMajor = targetAgpVersion.split(".").first().toIntOrNull() ?: 0
        if (targetMajor > currentMajor) {
            manualActions.add(ManualAction(
                title = "Review Breaking Changes",
                description = "Major AGP upgrade from ${scanResult.agpVersion} to $targetAgpVersion. Review migration guide for breaking changes.",
                file = null,
                actionType = ManualAction.ActionType.REVIEW_CHANGES,
                priority = ManualAction.Priority.HIGH,
            ))
        }

        // Namespace 마이그레이션 필요
        if (targetMajor >= 8 && scanResult.namespace == null) {
            manualActions.add(ManualAction(
                title = "Verify Namespace",
                description = "AGP 8.0+ requires explicit namespace declaration. Verify it was added correctly.",
                file = scanResult.moduleBuildGradlePaths.firstOrNull(),
                actionType = ManualAction.ActionType.REVIEW_CHANGES,
                priority = ManualAction.Priority.HIGH,
            ))
        }

        // 지원 중단 API 수동 검토
        val deprecatedCount = scanResult.deprecatedApiUsages.count { it.severity == AgpCompatibilityMatrix.Severity.ERROR }
        if (deprecatedCount > 0) {
            manualActions.add(ManualAction(
                title = "Review Deprecated API Migrations",
                description = "$deprecatedCount deprecated APIs were auto-migrated. Verify the changes are correct.",
                file = scanResult.moduleBuildGradlePaths.firstOrNull(),
                actionType = ManualAction.ActionType.REVIEW_CHANGES,
                priority = ManualAction.Priority.HIGH,
            ))
        }

        // 빌드 테스트 필요
        manualActions.add(ManualAction(
            title = "Run Build Verification",
            description = "Execute ./gradlew assembleDebug to verify the upgrade works correctly.",
            file = File(scanResult.moduleBuildGradlePaths.firstOrNull()?.parentFile ?: projectDir, "gradlew"),
            actionType = ManualAction.ActionType.RUN_TESTS,
            priority = ManualAction.Priority.HIGH,
        ))

        // 테스트 실행
        manualActions.add(ManualAction(
            title = "Run Unit/Instrumentation Tests",
            description = "Execute ./gradlew test and ./gradlew connectedAndroidTest to ensure no regressions.",
            file = null,
            actionType = ManualAction.ActionType.RUN_TESTS,
            priority = ManualAction.Priority.MEDIUM,
        ))

        // 문서 업데이트
        manualActions.add(ManualAction(
            title = "Update Documentation",
            description = "Update project README, CHANGELOG, and developer docs with new AGP/Gradle/Kotlin versions.",
            file = File(projectDir, "README.md"),
            actionType = ManualAction.ActionType.UPDATE_DOCS,
            priority = ManualAction.Priority.LOW,
        ))

        val summary = buildSummary(planResult, scanResult, targetAgpVersion, targetGradleVersion, targetKotlinVersion)

        return Report(
            success = planResult.success,
            targetAgpVersion = targetAgpVersion,
            targetGradleVersion = targetGradleVersion,
            targetKotlinVersion = targetKotlinVersion,
            completedSteps = completedSteps,
            failedStep = failedStep,
            warnings = warnings,
            manualActions = manualActions,
            summary = summary,
        )
    }

    private fun buildSummary(
        planResult: AgpUpgradePlan.PlanResult,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult,
        targetAgp: String,
        targetGradle: String,
        targetKotlin: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("AGP Upgrade Report")
        sb.appendLine("==================")
        sb.appendLine("")
        sb.appendLine("Project: ${projectDir?.name ?: "unknown"}")
        sb.appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("")
        sb.appendLine("Upgrade Target:")
        sb.appendLine("  AGP: ${scanResult.agpVersion} → $targetAgp")
        sb.appendLine("  Gradle: ${scanResult.gradleVersion} → $targetGradle")
        sb.appendLine("  Kotlin: ${scanResult.kotlinVersion} → $targetKotlin")
        sb.appendLine("")
        sb.appendLine("Result: ${if (planResult.success) "SUCCESS" else "FAILED"}")
        sb.appendLine("Steps: ${planResult.completedSteps.size}/${planResult.totalSteps} completed")
        
        if (planResult.failedStep != null) {
            sb.appendLine("Failed at: ${planResult.failedStep?.name}")
            sb.appendLine("Error: ${planResult.failedStep?.errorMessage}")
        }
        
        sb.appendLine("")
        sb.appendLine("Completed Steps:")
        planResult.completedSteps.forEach { step ->
            sb.appendLine("  ✓ ${step.name}")
        }
        
        return sb.toString()
    }

    private var projectDir: File? = null
        @Suppress("UNUSED_PARAMETER")
        set(value) { field = value }

    /**
     * 리포트를 텍스트 파일로 저장.
     */
    fun saveReport(report: Report, outputDir: File): File {
        outputDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(report.timestamp))
        val file = File(outputDir, "agp_upgrade_report_$timestamp.txt")
        file.writeText(buildFullReportText(report))
        return file
    }

    private fun buildFullReportText(report: Report): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("    AGP UPGRADE REPORT")
        sb.appendLine("========================================")
        sb.appendLine("")
        sb.appendLine("Generated: ${report.formattedDate}")
        sb.appendLine("Status: ${if (report.success) "SUCCESS ✓" else "FAILED ✗"}")
        sb.appendLine("")
        sb.appendLine("--- UPGRADE TARGET ---")
        sb.appendLine("AGP: ${report.targetAgpVersion}")
        sb.appendLine("Gradle: ${report.targetGradleVersion}")
        sb.appendLine("Kotlin: ${report.targetKotlinVersion}")
        sb.appendLine("")
        sb.appendLine("--- STEPS ---")
        report.completedSteps.forEach { step ->
            val statusIcon = when (step.status) {
                StepReport.StepStatus.COMPLETED -> "✓"
                StepReport.StepStatus.FAILED -> "✗"
                StepReport.StepStatus.SKIPPED -> "⊘"
                StepReport.StepStatus.ROLLED_BACK -> "↺"
            }
            sb.appendLine("$statusIcon [${step.type}] ${step.name}")
            if (step.errorMessage != null) {
                sb.appendLine("    Error: ${step.errorMessage}")
            }
        }
        
        if (report.failedStep != null) {
            sb.appendLine("")
            sb.appendLine("--- FAILED STEP ---")
            sb.appendLine("Step: ${report.failedStep?.name}")
            sb.appendLine("Error: ${report.failedStep?.errorMessage}")
        }
        
        if (report.warnings.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("--- WARNINGS (${report.warnings.size}) ---")
            report.warnings.forEach { warning ->
                sb.appendLine("[${warning.type}] ${warning.message}")
                warning.file?.let { sb.appendLine("    File: ${it.name}") }
                warning.line?.let { sb.appendLine("    Line: $it") }
                warning.suggestion?.let { sb.appendLine("    Suggestion: $it") }
            }
        }
        
        if (report.manualActions.isNotEmpty()) {
            sb.appendLine("")
            sb.appendLine("--- MANUAL ACTIONS REQUIRED (${report.manualActions.size}) ---")
            report.manualActions.sortedBy { it.priority.ordinal }.forEach { action ->
                val priorityIcon = when (action.priority) {
                    ManualAction.Priority.HIGH -> "🔴"
                    ManualAction.Priority.MEDIUM -> "🟡"
                    ManualAction.Priority.LOW -> "🟢"
                }
                sb.appendLine("$priorityIcon [${action.priority}] ${action.title}")
                sb.appendLine("    ${action.description}")
                action.file?.let { sb.appendLine("    File: ${it.name}") }
            }
        }
        
        sb.appendLine("")
        sb.appendLine("--- SUMMARY ---")
        sb.appendLine(report.summary)
        
        return sb.toString()
    }

    /**
     * 리포트 액티비티로 표시.
     */
    class ReportActivity : AppCompatActivity() {

        companion object {
            fun start(context: Context, report: Report) {
                val intent = Intent(context, ReportActivity::class.java)
                // 보고서 직렬화하여 전달 (실제로는 Parcelable 또는 JSON 사용)
                intent.putExtra("report_summary", report.summary)
                intent.putExtra("report_success", report.success)
                intent.putExtra("report_target_agp", report.targetAgpVersion)
                intent.putExtra("report_target_gradle", report.targetGradleVersion)
                intent.putExtra("report_target_kotlin", report.targetKotlinVersion)
                intent.putExtra("report_warnings_count", report.warnings.size)
                intent.putExtra("report_manual_count", report.manualActions.size)
                context.startActivity(intent)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            
            val summary = intent.getStringExtra("report_summary") ?: "No report available"
            val success = intent.getBooleanExtra("report_success", false)
            val targetAgp = intent.getStringExtra("report_target_agp") ?: "Unknown"
            val targetGradle = intent.getStringExtra("report_target_gradle") ?: "Unknown"
            val targetKotlin = intent.getStringExtra("report_target_kotlin") ?: "Unknown"
            val warningsCount = intent.getIntExtra("report_warnings_count", 0)
            val manualCount = intent.getIntExtra("report_manual_count", 0)

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(StudioUi.color(StudioUi.Ink))
            }

            val scrollView = ScrollView(this)
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            scrollView.addView(content)
            root.addView(scrollView, LinearLayout.LayoutParams(-1, -1, 1f))
            setContentView(root)

            // Header
            content.addView(TextView(this).apply {
                text = "AGP Upgrade Report"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(StudioUi.Text))
                setPadding(0, 0, 0, dp(8))
            })

            // Status badge
            content.addView(TextView(this).apply {
                text = if (success) "✓ UPGRADE SUCCESSFUL" else "✗ UPGRADE FAILED"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(if (success) StudioUi.Green else StudioUi.Red))
                setPadding(0, 0, 0, dp(16))
            })

            // Target versions
            val versions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(16))
            }
            versions.addView(createVersionChip("AGP", targetAgp))
            versions.addView(createVersionChip("Gradle", targetGradle))
            versions.addView(createVersionChip("Kotlin", targetKotlin))
            content.addView(versions)

            // Stats
            val stats = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(16))
            }
            stats.addView(createStatChip("Warnings", warningsCount.toString(), StudioUi.Amber))
            stats.addView(createStatChip("Manual Actions", manualCount.toString(), StudioUi.Blue))
            content.addView(stats)

            // Divider
            content.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(1))
                setBackgroundColor(StudioUi.color(StudioUi.Border))
            })

            // Summary
            content.addView(TextView(this).apply {
                text = summary
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(8), 0, 0)
            })

            // Action buttons
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
            }
            actions.addView(createButton("Close", StudioUi.Surface2, StudioUi.TextSoft) { finish() })
            actions.addView(createButton("Export Report", StudioUi.Blue, StudioUi.Text) {
                // Export logic
                toast("Report exported to Downloads")
            })
            content.addView(actions)
        }

        private fun createVersionChip(label: String, value: String): TextView {
            return TextView(this).apply {
                text = "$label: $value"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.Blue))
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
            }
        }

        private fun createStatChip(label: String, value: String, color: String): TextView {
            return TextView(this).apply {
                text = "$label: $value"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(color))
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
            }
        }

        private fun createButton(text: String, bgColor: String, textColor: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(textColor))
                setBackgroundColor(StudioUi.color(bgColor))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(8) }
            }
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        private fun toast(msg: String) {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}