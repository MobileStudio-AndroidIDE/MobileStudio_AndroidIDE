package com.aide.mobilestudio.agpupgrade

import com.aide.mobilestudio.ToolchainManager
import com.aide.mobilestudio.ToolchainSyncManager
import java.io.File
import kotlin.text.Regex

/**
 * 개별 마이그레이션 단계 구현체들.
 * 각 단계는 독립적으로 실행 가능하며 롤백을 지원.
 */
object AgpMigrationSteps {

    /** 마이그레이션 실행 결과 */
    data class MigrationResult(
        val success: Boolean,
        val message: String,
        val changedFiles: List<File>,
        val backup: Map<File, String>,
        val details: List<String> = emptyList(),
    ) {
        companion object {
            fun success(message: String, changedFiles: List<File> = emptyList(), backup: Map<File, String> = emptyMap(), details: List<String> = emptyList()) =
                MigrationResult(true, message, changedFiles, backup, details)
            fun failure(message: String) = MigrationResult(false, message, emptyList(), emptyMap())
        }
    }

    /**
     * 1. Gradle Wrapper 업그레이드 (gradle-wrapper.properties)
     */
    fun upgradeGradleWrapper(projectDir: File, targetGradleVersion: String): MigrationResult {
        val wrapperFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        val backup = if (wrapperFile.exists()) wrapperFile.readText() else ""

        val newGradleVer = targetGradleVersion.removePrefix("gradle-")
        val newUrl = "https://services.gradle.org/distributions/gradle-$newGradleVer-all.zip"

        val content = if (wrapperFile.exists()) wrapperFile.readText() else """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=$newUrl
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent()

        val newContent = content.replace(
            Regex("distributionUrl=.*gradle-(.*)-(bin|all)\\.zip"),
            "distributionUrl=$newUrl"
        )

        wrapperFile.parentFile?.mkdirs()
        wrapperFile.writeText(newContent)

        return MigrationResult(
            success = true,
            message = "Gradle wrapper updated to $targetGradleVersion",
            changedFiles = listOf(wrapperFile),
            backup = mapOf(wrapperFile to backup),
        )
    }

    /**
     * 2. AGP 버전 업그레이드 (DSL 변경: compileSdk→namespace, buildToolsVersion 제거 등)
     */
    fun upgradeAgpVersion(
        projectDir: File,
        targetAgpVersion: String,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        // libs.versions.toml 업데이트
        scanResult.libsTomlPath?.let { file ->
            if (file.exists()) {
                backup[file] = file.readText()
                val content = file.readText()
                val newContent = content.replace(
                    Regex("(?m)^(\\s*agp\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$targetAgpVersion\\$2"
                )
                file.writeText(newContent)
                changedFiles.add(file)
            }
        }

        // 모듈 build.gradle.kts 업데이트
        for (file in scanResult.moduleBuildGradlePaths) {
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()

                // id("com.android.application") version "x.y.z" 패턴
                content = content.replace(
                    Regex("(id\\s*[(\"']com\\.android\\.(?:application|library)[\")']\\s*(?:version\\s*)?[(\")'][^\"']*([\")'])"),
                    "\\$1$targetAgpVersion\\$2"
                )

                // classpath "com.android.tools.build:gradle:x.y.z" 패턴
                content = content.replace(
                    Regex("(classpath\\s*\\(?\\s*[\"']com\\.android\\.tools\\.build:gradle:)[^\"']*([\"'])"),
                    "\\$1$targetAgpVersion\\$2"
                )

                file.writeText(content)
                changedFiles.add(file)
            }
        }

        // settings.gradle.kts 업데이트
        scanResult.settingsGradlePath?.let { file ->
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()
                content = content.replace(
                    Regex("(id\\s*[(\"']com\\.android\\.(?:application|library)[\")']\\s*(?:version\\s*)?[(\")'][^\"']*([\")'])"),
                    "\\$1$targetAgpVersion\\$2"
                )
                file.writeText(content)
                changedFiles.add(file)
            }
        }

        return MigrationResult(
            success = true,
            message = "AGP version updated to $targetAgpVersion",
            changedFiles = changedFiles,
            backup = backup,
        )
    }

    /**
     * 3. Kotlin 플러그인 버전 업그레이드
     */
    fun upgradeKotlinVersion(
        projectDir: File,
        targetKotlinVersion: String,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        // libs.versions.toml 업데이트
        scanResult.libsTomlPath?.let { file ->
            if (file.exists()) {
                backup[file] = file.readText()
                val content = file.readText()
                val newContent = content.replace(
                    Regex("(?m)^(\\s*kotlin\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$targetKotlinVersion\\$2"
                )
                file.writeText(newContent)
                changedFiles.add(file)
            }
        }

        // 모듈 build.gradle.kts 업데이트
        for (file in scanResult.moduleBuildGradlePaths) {
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()
                content = content.replace(
                    Regex("(id\\s*[(\"']org\\.jetbrains\\.kotlin\\.android[\")']\\s*(?:version\\s*)?[(\")'][^\"']*([\")'])"),
                    "\\$1$targetKotlinVersion\\$2"
                )
                file.writeText(content)
                changedFiles.add(file)
            }
        }

        return MigrationResult(
            success = true,
            message = "Kotlin version updated to $targetKotlinVersion",
            changedFiles = changedFiles,
            backup = backup,
        )
    }

    /**
     * 4. libs.versions.toml 동기화 (ToolchainSyncManager 활용)
     */
    fun syncLibsToml(
        projectDir: File,
        targetAgpVersion: String,
        targetGradleVersion: String,
        targetKotlinVersion: String,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        val compatibilityInfo = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion)

        scanResult.libsTomlPath?.let { file ->
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()

                // AGP 버전
                content = content.replace(
                    Regex("(?m)^(\\s*agp\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$targetAgpVersion\\$2"
                )

                // Kotlin 버전
                content = content.replace(
                    Regex("(?m)^(\\s*kotlin\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$targetKotlinVersion\\$2"
                )

                // Gradle 버전 (있는 경우)
                val gradleVer = targetGradleVersion.removePrefix("gradle-")
                content = content.replace(
                    Regex("(?m)^(\\s*gradle\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$gradleVer\\$2"
                )

                // NDK 버전 (권장 버전으로)
                val recommendedNdk = compatibilityInfo.recommendedNdkVersion
                content = content.replace(
                    Regex("(?m)^(\\s*ndk\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$recommendedNdk\\$2"
                )

                // CMake 버전
                val recommendedCmake = compatibilityInfo.recommendedCmakeVersion
                content = content.replace(
                    Regex("(?m)^(\\s*cmake\\s*=\\s*\")[^\"]*(\")"),
                    "\\$1$recommendedCmake\\$2"
                )

                file.writeText(content)
                changedFiles.add(file)
            }
        }

        // ToolchainSyncManager도 호출하여 일관성 보장
        val newAgp = ToolchainSyncManager.agpVersionForGradle(targetGradleVersion)
        ToolchainSyncManager.syncGradleWrapperVersion(projectDir, targetGradleVersion)
        ToolchainSyncManager.syncAgpVersion(projectDir, newAgp)
        ToolchainSyncManager.syncKotlinVersion(projectDir, ToolchainSyncManager.kotlinVersionForGradle(targetGradleVersion))
        ToolchainSyncManager.syncNdkVersion(projectDir, compatibilityInfo.recommendedNdkVersion)

        return MigrationResult(
            success = true,
            message = "libs.versions.toml synchronized with target versions",
            changedFiles = changedFiles,
            backup = backup,
        )
    }

    /**
     * 5. NDK/CMake 버전 호환성 확인 및 업데이트
     */
    fun upgradeNdkCmake(
        projectDir: File,
        targetAgpVersion: String,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        val compatibilityInfo = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion)
        val recommendedNdk = compatibilityInfo.recommendedNdkVersion
        val recommendedCmake = compatibilityInfo.recommendedCmakeVersion

        for (file in scanResult.moduleBuildGradlePaths) {
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()

                // NDK 버전
                content = content.replace(
                    Regex("ndkVersion\\s*=\\s*\"[^\"]+\""),
                    "ndkVersion = \"$recommendedNdk\""
                )
                content = content.replace(
                    Regex("ndkVersion\\s*\"[^\"]+\""),
                    "ndkVersion \"$recommendedNdk\""
                )

                // CMake 버전
                content = content.replace(
                    Regex("cmake\\s*\\{\\s*version\\s+\"[^\"]+\""),
                    "cmake { version = \"$recommendedCmake\""
                )
                content = content.replace(
                    Regex("cmakeVersion\\s*=\\s*\"[^\"]+\""),
                    "cmakeVersion = \"$recommendedCmake\""
                )

                file.writeText(content)
                changedFiles.add(file)
            }
        }

        // gradle.properties에도 NDK 버전 동기화
        val propsFile = File(projectDir, "gradle.properties")
        if (propsFile.exists()) {
            backup[propsFile] = propsFile.readText()
            var content = propsFile.readText()
            content = content.replace(
                Regex("android\\.ndkVersion\\s*=\\s*.+"),
                "android.ndkVersion=$recommendedNdk"
            )
            propsFile.writeText(content)
            changedFiles.add(propsFile)
        }

        return MigrationResult(
            success = true,
            message = "NDK updated to $recommendedNdk, CMake updated to $recommendedCmake",
            changedFiles = changedFiles,
            backup = backup,
        )
    }

    /**
     * 6. 지원 중단 API 교체
     */
    fun migrateDeprecatedApis(
        projectDir: File,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()
        val migrations = mutableListOf<String>()

        val allFiles = scanResult.moduleBuildGradlePaths +
            (scanResult.rootBuildGradlePath?.let { listOf(it) } ?: emptyList()) +
            (scanResult.settingsGradlePath?.let { listOf(it) } ?: emptyList()) +
            (scanResult.gradlePropertiesPath?.let { listOf(it) } ?: emptyList())

        for (file in allFiles) {
            if (!file.exists()) continue

            if (!backup.containsKey(file)) {
                backup[file] = file.readText()
            }

            var content = file.readText()
            var modified = false

            // variant.outputs.each { } → variant.outputs.all { }
            if (content.contains("variant.outputs.each")) {
                content = content.replace("variant.outputs.each", "variant.outputs.all")
                modified = true
                migrations.add("variant.outputs.each → variant.outputs.all in ${file.name}")
            }

            // variant.outputs { } → variant.outputs.all { }
            if (content.contains("variant.outputs {") || content.contains("variant.outputs[")) {
                content = content.replace("variant.outputs {", "variant.outputs.all {")
                content = content.replace("variant.outputs[", "variant.outputs.all[")
                modified = true
                migrations.add("variant.outputs → variant.outputs.all in ${file.name}")
            }

            // buildToolsVersion 제거
            if (content.contains("buildToolsVersion")) {
                content = content.replace(Regex("buildToolsVersion\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed buildToolsVersion in ${file.name}")
            }

            // dataBinding { enabled = true } → buildFeatures { dataBinding = true }
            if (content.contains("dataBinding") && content.contains("enabled")) {
                content = content.replace(
                    Regex("dataBinding\\s*\\{\\s*enabled\\s*=\"[^\"]+\"\\s*\\}"),
                    "buildFeatures { dataBinding = true }"
                )
                modified = true
                migrations.add("dataBinding.enabled → buildFeatures.dataBinding in ${file.name}")
            }

            // viewBinding { enabled = true } → buildFeatures { viewBinding = true }
            if (content.contains("viewBinding") && content.contains("enabled")) {
                content = content.replace(
                    Regex("viewBinding\\s*\\{\\s*enabled\\s*=\"[^\"]+\"\\s*\\}"),
                    "buildFeatures { viewBinding = true }"
                )
                modified = true
                migrations.add("viewBinding.enabled → buildFeatures.viewBinding in ${file.name}")
            }

            // android.enableR8 제거 (AGP 8+에서 기본값)
            if (content.contains("android.enableR8")) {
                content = content.replace(Regex("android\\.enableR8\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed android.enableR8 in ${file.name}")
            }

            // android.enableD8 제거
            if (content.contains("android.enableD8")) {
                content = content.replace(Regex("android\\.enableD8\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed android.enableD8 in ${file.name}")
            }

            // android.useAndroidX 제거 (기본값 true)
            if (content.contains("android.useAndroidX")) {
                content = content.replace(Regex("android\\.useAndroidX\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed android.useAndroidX in ${file.name}")
            }

            // android.enableJetifier 제거 (기본값 true)
            if (content.contains("android.enableJetifier")) {
                content = content.replace(Regex("android\\.enableJetifier\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed android.enableJetifier in ${file.name}")
            }

            // splits { } → bundle { } (주석으로 안내)
            if (content.contains("splits {")) {
                content = content.replace(
                    Regex("splits\\s*\\{[^}]+\\}"),
                    "// TODO: Migrate splits to bundle { }\\n// splits configuration removed - use bundle { } instead"
                )
                modified = true
                migrations.add("splits → bundle migration needed in ${file.name}")
            }

            // lintOptions → lint
            if (content.contains("lintOptions")) {
                content = content.replace("lintOptions {", "lint {")
                modified = true
                migrations.add("lintOptions → lint in ${file.name}")
            }

            // testOptions { } → testOptions { unitTests { } }
            if (content.contains("testOptions {") && !content.contains("unitTests {")) {
                content = content.replace(
                    Regex("testOptions\\s*\\{"),
                    "testOptions {\\n        unitTests {\\n            // unit test options here\\n        }"
                )
                modified = true
                migrations.add("testOptions → testOptions.unitTests in ${file.name}")
            }

            // renderScriptSupportMode 제거
            if (content.contains("renderscriptSupportMode")) {
                content = content.replace(Regex("renderscriptSupportMode\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = true
                migrations.add("Removed renderscriptSupportMode in ${file.name}")
            }

            // renderScriptTargetApi 제거
            if (content.contains("renderscriptTargetApi")) {
                content = content.replace(Regex("renderscriptTargetApi\\s*=\\d+\\s*\\n?"), "")
                modified = true
                migrations.add("Removed renderscriptTargetApi in ${file.name}")
            }

            // useProguard → isMinifyEnabled
            if (content.contains("useProguard")) {
                content = content.replace(
                    Regex("useProguard\\s*=\"[^\"]+\""),
                    "isMinifyEnabled = true"
                )
                modified = true
                migrations.add("useProguard → isMinifyEnabled in ${file.name}")
            }

            // runProguard → isMinifyEnabled
            if (content.contains("runProguard")) {
                content = content.replace(
                    Regex("runProguard\\s*=\"[^\"]+\""),
                    "isMinifyEnabled = true"
                )
                modified = true
                migrations.add("runProguard → isMinifyEnabled in ${file.name}")
            }

            // ndk { moduleName } → ndk { abiFilters }
            if (content.contains("ndk {") && content.contains("moduleName")) {
                content = content.replace(
                    Regex("ndk\\s*\\{\\s*moduleName\\s*=\"[^\"]+\"\\s*\\}"),
                    "ndk { abiFilters = [] } // TODO: Specify abiFilters"
                )
                modified = true
                migrations.add("ndk.moduleName → ndk.abiFilters in ${file.name}")
            }

            if (modified) {
                file.writeText(content)
                if (file !in changedFiles) changedFiles.add(file)
            }
        }

        return MigrationResult(
            success = true,
            message = "Applied ${migrations.size} deprecated API migrations",
            changedFiles = changedFiles,
            backup = backup,
            details = migrations,
        )
    }

    /**
     * 7. 빌드 캐시 정리 및 동기화
     */
    fun cleanBuildCache(projectDir: File): MigrationResult {
        val cleaned = mutableListOf<String>()

        // 프로젝트 .gradle 폴더
        val projectGradle = File(projectDir, ".gradle")
        if (projectGradle.exists()) {
            projectGradle.deleteRecursively()
            cleaned.add("Project .gradle cache")
        }

        // 모듈별 build 폴더
        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", ".git", "node_modules", ".cxx", "captures", "out") }
            .filter { it.name == "build" && it.isDirectory }
            .forEach { buildDir ->
                buildDir.deleteRecursively()
                cleaned.add("Module build: ${buildDir.parentFile.name}")
            }

        // Gradle wrapper 다운로드 캐시 (선택적)
        val wrapperDists = File(projectDir, "gradle/wrapper/dists")
        if (wrapperDists.exists()) {
            // 유지 - 다시 다운로드하지 않게 함
            cleaned.add("Kept wrapper dists cache")
        }

        return MigrationResult(
            success = true,
            message = "Build cache cleaned: ${cleaned.joinToString(", ")}",
            changedFiles = emptyList(),
            backup = emptyMap(),
            details = cleaned,
        )
    }

    /**
     * 8. Namespace 마이그레이션 (AGP 8.0+ 필수)
     */
    fun migrateNamespace(
        projectDir: File,
        targetAgpVersion: String,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        // AGP 8.0 미만이면 스킵
        val majorVersion = targetAgpVersion.split(".").first().toIntOrNull() ?: 0
        if (majorVersion < 8) {
            return MigrationResult(
                success = true,
                message = "Namespace migration not required for AGP $targetAgpVersion",
                changedFiles = emptyList(),
                backup = emptyMap(),
            )
        }

        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        // applicationId로부터 namespace 유추
        var namespace = scanResult.namespace
        if (namespace == null) {
            for (file in scanResult.moduleBuildGradlePaths) {
                val content = file.readText()
                val match = Regex("applicationId\\s*=\\s*\"([^\"]+)\"").find(content)
                if (match != null) {
                    namespace = match.groupValues[1]
                    break
                }
            }
        }

        namespace?.let { ns ->
            for (file in scanResult.moduleBuildGradlePaths) {
                if (file.exists()) {
                    backup[file] = file.readText()
                    var content = file.readText()

                    // android { } 블록 내에 namespace 추가 (이미 없으면)
                    if (!content.contains("namespace")) {
                        content = content.replace(
                            Regex("(android\\s*\\{)"),
                            "\\$1\\n    namespace = \"$ns\""
                        )
                        file.writeText(content)
                        changedFiles.add(file)
                    }
                }
            }
        }

        return MigrationResult(
            success = true,
            message = "Namespace added: ${namespace ?: "unknown (could not determine)"}",
            changedFiles = changedFiles,
            backup = backup,
        )
    }

    /**
     * 9. buildToolsVersion 제거 (AGP 8.0+에서 불필요)
     */
    fun removeBuildToolsVersion(
        projectDir: File,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult
    ): MigrationResult {
        val changedFiles = mutableListOf<File>()
        val backup = mutableMapOf<File, String>()

        for (file in scanResult.moduleBuildGradlePaths) {
            if (file.exists()) {
                backup[file] = file.readText()
                var content = file.readText()

                content = content.replace(Regex("buildToolsVersion\\s*=\"[^\"]+\"\\s*\\n?"), "")

                file.writeText(content)
                changedFiles.add(file)
            }
        }

        return MigrationResult(
            success = true,
            message = "buildToolsVersion removed from ${changedFiles.size} files",
            changedFiles = changedFiles,
            backup = backup,
        )
    }
}
