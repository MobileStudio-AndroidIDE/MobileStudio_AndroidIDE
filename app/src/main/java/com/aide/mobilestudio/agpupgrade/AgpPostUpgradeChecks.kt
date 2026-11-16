package com.aide.mobilestudio.agpupgrade

import com.aide.mobilestudio.BuildToolchain
import com.aide.mobilestudio.GradleRunner
import com.aide.mobilestudio.JreGradleInstaller
import com.aide.mobilestudio.ShellRunner
import com.aide.mobilestudio.ToolchainManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 업그레이드 후 검증 (./gradlew assembleDebug 자동 실행, 테스트).
 */
class AgpPostUpgradeChecks(
    private val projectDir: File,
    private val context: android.content.Context,
) {

    data class CheckResult(
        val success: Boolean,
        val task: String,
        val output: String,
        val durationMs: Long,
        val errorMessage: String? = null,
    ) {
        companion object {
            fun success(task: String, output: String, durationMs: Long) = CheckResult(true, task, output, durationMs)
            fun failure(task: String, errorMessage: String, durationMs: Long) = CheckResult(false, task, "", durationMs, errorMessage)
        }
    }

    data class VerificationReport(
        val overallSuccess: Boolean,
        val checks: List<CheckResult>,
        val totalDurationMs: Long,
        val summary: String,
    )

    /**
     * 전체 검증 실행.
     */
    fun runAllChecks(onProgress: (String, Boolean) -> Unit): VerificationReport {
        val checks = mutableListOf<CheckResult>()
        val startTime = System.currentTimeMillis()

        // 1. Gradle wrapper 검증
        onProgress("Verifying Gradle wrapper...", false)
        checks.add(verifyGradleWrapper())

        // 2. 프로젝트 동기화 (gradle sync)
        onProgress("Syncing Gradle project...", false)
        checks.add(syncGradleProject())

        // 3. 의존성 해결 확인
        onProgress("Resolving dependencies...", false)
        checks.add(resolveDependencies())

        // 4. 컴파일 체크 (assembleDebug)
        onProgress("Building debug APK (assembleDebug)...", false)
        checks.add(runAssembleDebug())

        // 5. 린트 체크 (선택적)
        onProgress("Running lint checks...", false)
        checks.add(runLint())

        // 6. 단위 테스트 (선택적)
        onProgress("Running unit tests...", false)
        checks.add(runUnitTests())

        // 7. 컴파일 체크 (compileDebugKotlin)
        onProgress("Compiling Kotlin sources...", false)
        checks.add(compileKotlin())

        val totalDuration = System.currentTimeMillis() - startTime
        val overallSuccess = checks.all { it.success }
        
        val summary = buildSummary(checks, overallSuccess, totalDuration)

        onProgress("Verification complete", overallSuccess)

        return VerificationReport(
            overallSuccess = overallSuccess,
            checks = checks,
            totalDurationMs = totalDuration,
            summary = summary,
        )
    }

    private fun verifyGradleWrapper(): CheckResult {
        val start = System.currentTimeMillis()
        val wrapperFile = File(projectDir, "gradlew")
        val wrapperJar = File(projectDir, "gradle/wrapper/gradle-wrapper.jar")
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")

        val issues = mutableListOf<String>()
        
        if (!wrapperFile.exists()) issues.add("gradlew script missing")
        if (!wrapperJar.exists()) issues.add("gradle-wrapper.jar missing")
        if (!wrapperProps.exists()) issues.add("gradle-wrapper.properties missing")
        else {
            val content = wrapperProps.readText()
            if (!content.contains("distributionUrl")) issues.add("distributionUrl not found in wrapper properties")
        }

        return if (issues.isEmpty()) {
            CheckResult.success("Gradle Wrapper", "All wrapper files present", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("Gradle Wrapper", issues.joinToString(", "), System.currentTimeMillis() - start)
        }
    }

    private fun syncGradleProject(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("help"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("Gradle Sync", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("Gradle Sync", "Project synced successfully", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("Gradle Sync", errorMsg ?: "Sync failed", System.currentTimeMillis() - start)
        }
    }

    private fun resolveDependencies(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("dependencies", "--configuration", "debugCompileClasspath"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("Dependency Resolution", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("Dependency Resolution", "Dependencies resolved", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("Dependency Resolution", errorMsg ?: "Resolution failed", System.currentTimeMillis() - start)
        }
    }

    private fun runAssembleDebug(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("assembleDebug"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("assembleDebug", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            val apkDir = File(projectDir, "app/build/outputs/apk/debug")
            val apks = apkDir.listFiles { _, name -> name.endsWith(".apk") } ?: emptyArray()
            if (apks.isNotEmpty()) {
                CheckResult.success("assembleDebug", "APK generated: ${apks.first().name} (${apks.first().length() / 1024} KB)", System.currentTimeMillis() - start)
            } else {
                CheckResult.success("assembleDebug", "Build successful but no APK found", System.currentTimeMillis() - start)
            }
        } else {
            CheckResult.failure("assembleDebug", errorMsg ?: "Build failed", System.currentTimeMillis() - start)
        }
    }

    private fun runLint(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("lint"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("lint", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("lint", "Lint passed", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("lint", errorMsg ?: "Lint failed", System.currentTimeMillis() - start)
        }
    }

    private fun runLintDebug(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("lintDebug"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("Lint Check", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("Lint Check", "Lint passed", System.currentTimeMillis() - start)
        } else {
            CheckResult.success("Lint Check", "Lint completed with warnings", System.currentTimeMillis() - start)
        }
    }

    private fun runUnitTests(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("testDebugUnitTest"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("Unit Tests", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("Unit Tests", "All tests passed", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("Unit Tests", errorMsg ?: "Tests failed", System.currentTimeMillis() - start)
        }
    }

    private fun compileKotlin(): CheckResult {
        val start = System.currentTimeMillis()
        val latch = java.util.concurrent.CountDownLatch(1)
        var success = false
        var errorMsg: String? = null
        
        try {
            val installer = JreGradleInstaller(context, { }, { })
            val runner = GradleRunner(context, installer)
            runner.run(
                projectDir = projectDir,
                tasks = listOf("compileDebugKotlin"),
                onLine = { _, _ -> },
                onDone = { result ->
                    success = result.success
                    errorMsg = result.errorLines.firstOrNull()
                    latch.countDown()
                },
            )
            latch.await()
        } catch (e: Exception) {
            return CheckResult.failure("Kotlin Compile", "Exception: ${e.message}", System.currentTimeMillis() - start)
        }
        
        return if (success) {
            CheckResult.success("Kotlin Compile", "Kotlin sources compiled successfully", System.currentTimeMillis() - start)
        } else {
            CheckResult.failure("Kotlin Compile", errorMsg ?: "Compilation failed", System.currentTimeMillis() - start)
        }
    }

    /**
     * 빠른 검증 (assembleDebug만).
     */
    fun runQuickCheck(onProgress: (String, Boolean) -> Unit): CheckResult {
        onProgress("Building debug APK...", false)
        return runAssembleDebug()
    }

    /**
     * 쉘을 통한 직접 실행 (GradleRunner 없이).
     */
    fun runGradleCommand(tasks: List<String>, timeoutMinutes: Int = 10): ShellRunner.Result {
        val gradleCmd = File(projectDir, "gradlew").absolutePath
        val args = mutableListOf(gradleCmd)
        args.addAll(tasks)
        
        // JVM 옵션 설정
        val env = mapOf(
            "GRADLE_OPTS" to "-Xmx2g -Dorg.gradle.jvmargs=-Xmx2g",
            "GRADLE_USER_HOME" to BuildToolchain.gradleUserHome(context).absolutePath,
        )
        
        return ShellRunner.runArgs(
            context = context,
            args = args,
            cwd = projectDir,
            env = env,
            timeoutMs = timeoutMinutes * 60 * 1000L,
            onLine = { },
        )
    }

    private fun buildSummary(checks: List<CheckResult>, overallSuccess: Boolean, totalDuration: Long): String {
        val sb = StringBuilder()
        sb.appendLine("Post-Upgrade Verification Report")
        sb.appendLine("================================")
        sb.appendLine("Overall: ${if (overallSuccess) "PASSED" else "FAILED"}")
        sb.appendLine("Total Duration: ${formatDuration(totalDuration)}")
        sb.appendLine("")
        
        checks.forEach { check ->
            val status = if (check.success) "✓" else "✗"
            sb.appendLine("$status ${check.task} (${formatDuration(check.durationMs)})")
            if (!check.success) {
                sb.appendLine("    Error: ${check.errorMessage}")
            } else if (check.output.isNotBlank()) {
                sb.appendLine("    Output: ${check.output.take(100)}")
            }
        }
        
        return sb.toString()
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${seconds}s"
    }
}