package com.aide.mobilestudio.agpupgrade

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AGP 업그레이드 안전한 적용 (백업, 트랜잭션, 롤백).
 * .agp-upgrade-backup/ 디렉토리에 타임스탬프별 백업 저장.
 */
class AgpUpgradeExecutor(
    private val projectDir: File,
    private val context: Context,
) {

    private val backupRoot = File(projectDir, ".agp-upgrade-backup")
    private val currentBackupDir: File? = null
    private val executedSteps = mutableListOf<ExecutedStep>()
    private val fileBackups = ConcurrentHashMap<File, String>()

    data class ExecutedStep(
        val stepId: Int,
        val stepName: String,
        val backupFiles: Map<File, String>,
        val changedFiles: List<File>,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class ExecutionResult(
        val success: Boolean,
        val message: String,
        val backupDir: File?,
        val executedSteps: List<ExecutedStep>,
        val failedStep: ExecutedStep?,
    ) {
        companion object {
            fun success(message: String, backupDir: File, executedSteps: List<ExecutedStep>) =
                ExecutionResult(true, message, backupDir, executedSteps, null)
            fun failure(message: String, backupDir: File?, executedSteps: List<ExecutedStep>, failedStep: ExecutedStep?) =
                ExecutionResult(false, message, backupDir, executedSteps, failedStep)
        }
    }

    init {
        backupRoot.mkdirs()
        // 오래된 백업 정리 (30일 이상)
        cleanOldBackups()
    }

    /**
     * 전체 업그레이드 계획 실행 (트랜잭션 방식).
     */
    fun executePlan(
        plan: AgpUpgradePlan,
        onStepProgress: (AgpUpgradePlan.UpgradeStep, AgpUpgradePlan.StepResult) -> Unit,
    ): ExecutionResult {
        val timestamp = System.currentTimeMillis()
        val backupDir = File(backupRoot, SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp)))
        backupDir.mkdirs()

        val planResult = plan.executeAll { step, result ->
            // 백업 저장
            if (result.success && result.backupFiles.isNotEmpty()) {
                val executedStep = ExecutedStep(
                    stepId = step.id,
                    stepName = step.name,
                    backupFiles = result.backupFiles,
                    changedFiles = result.changedFiles,
                )
                executedSteps.add(executedStep)
                
                // 백업 파일을 백업 디렉토리에 저장
                for ((file, content) in result.backupFiles) {
                    saveBackupToDir(backupDir, file, content)
                    fileBackups[file] = content
                }
            }
            onStepProgress(step, result)
        }

        if (planResult.success) {
            // 성공 시 백업 메타데이터 저장
            saveBackupMetadata(backupDir, planResult.completedSteps)
            return ExecutionResult.success(
                "Upgrade completed successfully. Backup saved to ${backupDir.name}",
                backupDir,
                executedSteps,
            )
        } else {
            // 실패 시 자동 롤백
            rollbackAll(backupDir)
            val failedExecutedStep = planResult.failedStep?.let { step ->
                ExecutedStep(
                    stepId = step.id,
                    stepName = step.name,
                    backupFiles = emptyMap(),
                    changedFiles = emptyList(),
                    timestamp = System.currentTimeMillis(),
                )
            }
            return ExecutionResult.failure(
                "Upgrade failed at step ${planResult.failedStep?.name}: ${planResult.failedStep?.errorMessage}",
                backupDir,
                executedSteps,
                failedExecutedStep,
            )
        }
    }

    /**
     * 특정 단계부터 롤백.
     */
    fun rollbackFrom(stepId: Int, backupDir: File, onProgress: (AgpUpgradePlan.UpgradeStep, AgpUpgradePlan.StepResult) -> Unit): List<AgpUpgradePlan.StepResult> {
        val results = mutableListOf<AgpUpgradePlan.StepResult>()
        val toRollback = executedSteps.filter { it.stepId >= stepId }.reversed()

        for (executedStep in toRollback) {
            // 백업에서 복원
            for ((file, content) in executedStep.backupFiles) {
                if (file.exists()) {
                    file.writeText(content)
                }
            }
            
            executedStep.changedFiles.forEach { file ->
                if (!executedStep.backupFiles.containsKey(file)) {
                    // 백업이 없는 경우 (새로 생성된 파일) 삭제
                    if (file.exists()) file.delete()
                }
            }

            val result = AgpUpgradePlan.StepResult.success("Rolled back: ${executedStep.stepName}")
            results.add(result)
            onProgress(
                AgpUpgradePlan.UpgradeStep(
                    id = executedStep.stepId,
                    name = executedStep.stepName,
                    description = "",
                    type = AgpUpgradePlan.StepType.GRADLE_WRAPPER,
                    execute = { AgpUpgradePlan.StepResult.success("") },
                    rollback = { AgpUpgradePlan.StepResult.success("") },
                ),
                result
            )
        }

        return results
    }

    /**
     * 전체 롤백.
     */
    fun rollbackAll(backupDir: File? = null): Boolean {
        val targetBackupDir = backupDir ?: findLatestBackup()
        if (targetBackupDir == null) {
            Log.e("AgpUpgradeExecutor", "No backup found for rollback")
            return false
        }

        // 메타데이터에서 복원할 파일 목록 읽기
        val metadataFile = File(targetBackupDir, "backup_metadata.json")
        if (metadataFile.exists()) {
            // JSON 파싱하여 복원 (간단한 구현)
            restoreFromBackupDir(targetBackupDir)
        } else {
            // 실행된 단계 역순으로 롤백
            for (executedStep in executedSteps.reversed()) {
                for ((file, content) in executedStep.backupFiles) {
                    if (file.exists()) {
                        file.writeText(content)
                    }
                }
                executedStep.changedFiles.forEach { file ->
                    if (!executedStep.backupFiles.containsKey(file) && file.exists()) {
                        file.delete()
                    }
                }
            }
        }

        executedSteps.clear()
        return true
    }

    /**
     * 백업 디렉토리에서 복원.
     */
    private fun restoreFromBackupDir(backupDir: File) {
        val originalProject = File(backupDir, "project")
        if (originalProject.exists()) {
            // 백업된 프로젝트 파일들을 원래 위치로 복사
            copyRecursively(originalProject, projectDir)
        }
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    /**
     * 백업 디렉토리에 파일 저장.
     */
    private fun saveBackupToDir(backupDir: File, file: File, content: String) {
        val relativePath = projectDir.toPath().relativize(file.toPath()).toString()
        val backupFile = File(backupDir, "project/$relativePath")
        backupFile.parentFile?.mkdirs()
        backupFile.writeText(content)
    }

    /**
     * 백업 메타데이터 저장.
     */
    private fun saveBackupMetadata(backupDir: File, completedSteps: List<AgpUpgradePlan.UpgradeStep>) {
        val metadata = StringBuilder()
        metadata.appendLine("{")
        metadata.appendLine("  \"timestamp\": ${System.currentTimeMillis()},")
        metadata.appendLine("  \"projectDir\": \"${projectDir.absolutePath}\",")
        metadata.appendLine("  \"steps\": [")
        completedSteps.forEachIndexed { index, step ->
            metadata.appendLine("    {")
            metadata.appendLine("      \"id\": ${step.id},")
            metadata.appendLine("      \"name\": \"${step.name}\",")
            metadata.appendLine("      \"type\": \"${step.type}\",")
            metadata.appendLine("      \"status\": \"${step.status}\"")
            metadata.appendLine("    }${if (index < completedSteps.lastIndex) "," else ""}")
        }
        metadata.appendLine("  ]")
        metadata.appendLine("}")
        
        File(backupDir, "backup_metadata.json").writeText(metadata.toString())
    }

    /**
     * 30일 이상 된 백업 정리.
     */
    private fun cleanOldBackups() {
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        backupRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val dirTime = dir.lastModified()
            if (dirTime < cutoffTime) {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * 가장 최근 백업 찾기.
     */
    private fun findLatestBackup(): File? {
        return backupRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * 백업 목록 조회.
     */
    fun getAvailableBackups(): List<BackupInfo> {
        return backupRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.map { dir ->
                val metadataFile = File(dir, "backup_metadata.json")
                val metadata = if (metadataFile.exists()) metadataFile.readText() else "{}"
                BackupInfo(
                    dir = dir,
                    timestamp = dir.lastModified(),
                    metadata = metadata,
                )
            } ?: emptyList()
    }

    data class BackupInfo(
        val dir: File,
        val timestamp: Long,
        val metadata: String,
    ) {
        val formattedDate: String
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * 특정 백업으로 복원.
     */
    fun restoreBackup(backupInfo: BackupInfo): Boolean {
        return try {
            restoreFromBackupDir(backupInfo.dir)
            true
        } catch (e: Exception) {
            Log.e("AgpUpgradeExecutor", "Failed to restore backup", e)
            false
        }
    }

    /**
     * 백업 삭제.
     */
    fun deleteBackup(backupInfo: BackupInfo): Boolean {
        return backupInfo.dir.deleteRecursively()
    }
}