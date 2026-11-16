package com.aide.mobilestudio.agpupgrade

import com.aide.mobilestudio.ToolchainManager
import com.aide.mobilestudio.ToolchainSyncManager
import com.aide.mobilestudio.ThemeManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 프로젝트 전체 스캔하여 AGP 업그레이드에 필요한 모든 정보를 수집.
 * build.gradle.kts, libs.versions.toml, settings.gradle.kts, gradle.properties 분석.
 */
class AgpUpgradeAnalyzer(private val projectDir: File) {

    private val cache = ConcurrentHashMap<String, Any>()

    data class ProjectScanResult(
        val agpVersion: String,
        val gradleVersion: String,
        val kotlinVersion: String,
        val ndkVersion: String,
        val cmakeVersion: String,
        val jdkVersion: Int,
        val compileSdk: Int,
        val targetSdk: Int,
        val minSdk: Int,
        val namespace: String?,
        val buildToolsVersion: String?,
        val usesLibsToml: Boolean,
        val libsTomlPath: File?,
        val settingsGradlePath: File?,
        val rootBuildGradlePath: File?,
        val moduleBuildGradlePaths: List<File>,
        val gradlePropertiesPath: File?,
        val gradleWrapperPath: File?,
        val deprecatedApiUsages: List<DeprecatedApiUsage>,
        val productFlavors: List<String>,
        val buildTypes: List<String>,
        val dependencies: Map<String, String>,
        val plugins: Map<String, String>,
    )

    data class DeprecatedApiUsage(
        val file: File,
        val line: Int,
        val api: String,
        val replacement: String,
        val severity: AgpCompatibilityMatrix.Severity,
    )

    /**
     * 프로젝트 전체 스캔 수행.
     */
    fun scanProject(): ProjectScanResult {
        val agpVersion = detectAgpVersion()
        val gradleVersion = detectGradleVersion()
        val kotlinVersion = detectKotlinVersion()
        val ndkVersion = detectNdkVersion()
        val cmakeVersion = detectCmakeVersion()
        val jdkVersion = detectJdkVersion()
        val sdkConfig = detectSdkConfig()
        val compileSdk = sdkConfig.first
        val targetSdk = sdkConfig.second
        val minSdk = sdkConfig.third
        val namespace = detectNamespace()
        val buildToolsVersion = detectBuildToolsVersion()
        val usesLibsToml = File(projectDir, "gradle/libs.versions.toml").exists()
        val libsTomlPath = if (usesLibsToml) File(projectDir, "gradle/libs.versions.toml") else null
        val settingsGradlePath = findFile("settings.gradle.kts") ?: findFile("settings.gradle")
        val rootBuildGradlePath = findFile("build.gradle.kts") ?: findFile("build.gradle")
        val moduleBuildGradlePaths = findModuleBuildFiles()
        val gradlePropertiesPath = File(projectDir, "gradle.properties").takeIf { it.exists() }
        val gradleWrapperPath = File(projectDir, "gradle/wrapper/gradle-wrapper.properties").takeIf { it.exists() }
        val deprecatedApiUsages = scanDeprecatedApis()
        val productFlavors = extractProductFlavors()
        val buildTypes = extractBuildTypes()
        val dependencies = extractDependencies()
        val plugins = extractPlugins()

        return ProjectScanResult(
            agpVersion = agpVersion,
            gradleVersion = gradleVersion,
            kotlinVersion = kotlinVersion,
            ndkVersion = ndkVersion,
            cmakeVersion = cmakeVersion,
            jdkVersion = jdkVersion,
            compileSdk = compileSdk,
            targetSdk = targetSdk,
            minSdk = minSdk,
            namespace = namespace,
            buildToolsVersion = buildToolsVersion,
            usesLibsToml = usesLibsToml,
            libsTomlPath = libsTomlPath,
            settingsGradlePath = settingsGradlePath,
            rootBuildGradlePath = rootBuildGradlePath,
            moduleBuildGradlePaths = moduleBuildGradlePaths,
            gradlePropertiesPath = gradlePropertiesPath,
            gradleWrapperPath = gradleWrapperPath,
            deprecatedApiUsages = deprecatedApiUsages,
            productFlavors = productFlavors,
            buildTypes = buildTypes,
            dependencies = dependencies,
            plugins = plugins,
        )
    }

    private fun detectAgpVersion(): String {
        return cache.computeIfAbsent("agpVersion") {
            // 1. libs.versions.toml 확인
            val libsToml = File(projectDir, "gradle/libs.versions.toml")
            if (libsToml.exists()) {
                val content = libsToml.readText()
                val match = Regex("""(?m)^\s*agp\s*=\s*"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }

            // 2. settings.gradle.kts / settings.gradle 확인
            for (name in listOf("settings.gradle.kts", "settings.gradle")) {
                val file = File(projectDir, name)
                if (file.exists()) {
                    val content = file.readText()
                    var match = Regex("""id\(\s*"com\.android\.(?:application|library)"\s*\)\s*version\s*"([^"]+)"""").find(content)
                    if (match != null) return@computeIfAbsent match.groupValues[1]
                    match = Regex("""classpath\s*\(?\s*["']com\.android\.tools\.build:gradle:([^"']+)["']""").find(content)
                    if (match != null) return@computeIfAbsent match.groupValues[1]
                }
            }

            // 3. 모듈 build.gradle.kts 확인
            for (moduleFile in findModuleBuildFiles()) {
                val content = moduleFile.readText()
                val match = Regex("""id\(\s*"com\.android\.(?:application|library)"\s*\)\s*version\s*"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }

            ""
        } as String
    }

    private fun detectGradleVersion(): String {
        return cache.computeIfAbsent("gradleVersion") {
            val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
            if (wrapperProps.exists()) {
                val content = wrapperProps.readText()
                val match = Regex("""gradle-([0-9][0-9A-Za-z.\-_]*)-(?:bin|all)\.zip""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }
            ""
        } as String
    }

    private fun detectKotlinVersion(): String {
        return cache.computeIfAbsent("kotlinVersion") {
            // 1. libs.versions.toml 확인
            val libsToml = File(projectDir, "gradle/libs.versions.toml")
            if (libsToml.exists()) {
                val content = libsToml.readText()
                val match = Regex("""(?m)^\s*kotlin\s*=\s*"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }

            // 2. build.gradle.kts / build.gradle 확인
            val rootBuildGradle = findFile("build.gradle.kts") ?: findFile("build.gradle")
            for (file in findModuleBuildFiles() + (rootBuildGradle?.let { listOf(it) } ?: emptyList())) {
                val content = file.readText()
                val match = Regex("""id\s*[("']org\.jetbrains\.kotlin\.android[")']\s*(?:version\s*)?[("']([^"']+)[")']""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }
            ""
        } as String
    }

    private fun detectNdkVersion(): String {
        return cache.computeIfAbsent("ndkVersion") {
            for (file in findModuleBuildFiles()) {
                val content = file.readText()
                var match = Regex("""ndkVersion\s*=\s*"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
                match = Regex("""ndkVersion\s*"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
            }
            // gradle.properties 확인
            val propsFile = File(projectDir, "gradle.properties")
            if (propsFile.exists()) {
                val content = propsFile.readText()
                val match = Regex("""android\.ndkVersion\s*=\s*(.+)""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1].trim()
            }
            ""
        } as String
    }

    private fun detectCmakeVersion(): String {
        return cache.computeIfAbsent("cmakeVersion") {
            for (file in findModuleBuildFiles()) {
                val content = file.readText()
                val match = Regex("""cmake\s*\{\s*version\s+"([^"]+)"""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1]
                val match2 = Regex("""cmakeVersion\s*=\s*"([^"]+)"""").find(content)
                if (match2 != null) return@computeIfAbsent match2.groupValues[1]
            }
            "3.22.1"
        } as String
    }

    private fun detectJdkVersion(): Int {
        return cache.computeIfAbsent("jdkVersion") {
            // gradle.properties에서 javaHome 또는 org.gradle.java.home 확인
            val propsFile = File(projectDir, "gradle.properties")
            if (propsFile.exists()) {
                val content = propsFile.readText()
                val match = Regex("""org\.gradle\.java\.home\s*=\s*.*?jdk[_-]?(\d+)""").find(content)
                if (match != null) return@computeIfAbsent match.groupValues[1].toIntOrNull() ?: 17
            }
            // ToolchainManager의 기본 JDK 사용
            ToolchainManager.DEFAULT_JDK.removePrefix("jdk-").toIntOrNull() ?: 17
        } as Int
    }

    private fun detectSdkConfig(): Triple<Int, Int, Int> {
        return cache.computeIfAbsent("sdkConfig") {
            var compileSdk = 34
            var targetSdk = 34
            var minSdk = 24

            for (file in findModuleBuildFiles()) {
                val content = file.readText()

                // compileSdk
                var match = Regex("""compileSdk\s*=\s*(\d+)""").find(content)
                if (match != null) compileSdk = match.groupValues[1].toIntOrNull() ?: compileSdk

                // targetSdk (defaultConfig)
                match = Regex("""targetSdk\s*=\s*(\d+)""").find(content)
                if (match != null) targetSdk = match.groupValues[1].toIntOrNull() ?: targetSdk

                // minSdk
                match = Regex("""minSdk\s*=\s*(\d+)""").find(content)
                if (match != null) minSdk = match.groupValues[1].toIntOrNull() ?: minSdk
            }

            Triple(compileSdk, targetSdk, minSdk)
        } as Triple<Int, Int, Int>
    }

    private fun detectNamespace(): String? {
        for (file in findModuleBuildFiles()) {
            val content = file.readText()
            val match = Regex("""namespace\s*=\s*"([^"]+)"""").find(content)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun detectBuildToolsVersion(): String? {
        for (file in findModuleBuildFiles()) {
            val content = file.readText()
            val match = Regex("""buildToolsVersion\s*=\s*"([^"]+)"""").find(content)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun findModuleBuildFiles(): List<File> {
        val files = mutableListOf<File>()
        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", ".git", "node_modules", ".cxx", "captures", "out") }
            .forEach { file ->
                if (file.name == "build.gradle.kts" || file.name == "build.gradle") {
                    files.add(file)
                }
            }
        return files
    }

    private fun findFile(name: String): File? {
        return File(projectDir, name).takeIf { it.exists() }
    }

    private fun scanDeprecatedApis(): List<DeprecatedApiUsage> {
        val usages = mutableListOf<DeprecatedApiUsage>()
        val deprecatedPatterns = mapOf(
            // variant.outputs -> variant.outputs.all
            Regex("""\.outputs\s*(?:\{|\[)""") to "variant.outputs.all { }",
            // android.testOptions -> android.testOptions.unitTests
            Regex("""android\s*\{\s*testOptions\s*\{""") to "android { testOptions { unitTests { } } }",
            // buildToolsVersion 제거
            Regex("""buildToolsVersion\s*="[^"]+" """) to "Remove buildToolsVersion (no longer needed)",
            // compileSdk -> namespace 마이그레이션 필요
            Regex("""compileSdk\s*=\s*\d+""") to "Ensure namespace is declared",
            // variant.outputs.each -> variant.outputs.all
            Regex("""variant\.outputs\.each\s*\{""") to "variant.outputs.all { }",
            // applicationIdSuffix -> applicationIdSuffix (still valid but check)
            Regex("""applicationIdSuffix\s*=""") to "Check applicationIdSuffix usage",
            // manifestPlaceholders -> manifestPlaceholders (still valid)
            Regex("""manifestPlaceholders\s*=\s*\[""") to "Check manifestPlaceholders usage",
            // packagingOptions -> packagingOptions (still valid)
            Regex("""packagingOptions\s*\{""") to "Check packagingOptions usage",
            // lintOptions -> lint { }
            Regex("""lintOptions\s*\{""") to "lint { }",
            // useProguard -> isMinifyEnabled
            Regex("""useProguard\s*="[^"]+""") to "isMinifyEnabled = true/false",
            // runProguard -> isMinifyEnabled
            Regex("""runProguard\s*="[^"]+""") to "isMinifyEnabled = true/false",
            // ndk { moduleName } -> ndk { abiFilters }
            Regex("""ndk\s*\{\s*moduleName\s*=""") to "ndk { abiFilters }",
            // renderscriptSupportMode -> remove
            Regex("""renderscriptSupportMode\s*="[^"]+""") to "Remove (RenderScript deprecated)",
            // renderscriptTargetApi -> remove
            Regex("""renderscriptTargetApi\s*=\d+""") to "Remove (RenderScript deprecated)",
            // android.enableR8 -> removed in AGP 8+
            Regex("""android\.enableR8\s*="[^"]+""") to "Remove (R8 is default)",
            // android.enableD8 -> removed in AGP 8+
            Regex("""android\.enableD8\s*="[^"]+""") to "Remove (D8 is default)",
            // android.useAndroidX -> default true
            Regex("""android\.useAndroidX\s*="[^"]+""") to "Remove (AndroidX is default)",
            // android.enableJetifier -> default true
            Regex("""android\.enableJetifier\s*="[^"]+""") to "Remove (Jetifier is default)",
            // bundle { } -> bundle { } (still valid but check)
            Regex("""bundle\s*\{""") to "Check bundle configuration",
            // splits { } -> bundle { }
            Regex("""splits\s*\{""") to "Migrate to bundle { }",
            // productFlavors { } -> productFlavors { } (still valid)
            Regex("""productFlavors\s*\{""") to "Check productFlavors configuration",
            // signingConfigs { } -> signingConfigs { } (still valid)
            Regex("""signingConfigs\s*\{""") to "Check signingConfigs configuration",
            // buildTypes { release { } } -> buildTypes { release { } } (still valid)
            Regex("""buildTypes\s*\{""") to "Check buildTypes configuration",
            // dataBinding { enabled = true } -> buildFeatures { dataBinding = true }
            Regex("""dataBinding\s*\{\s*enabled\s*="[^"]+""") to "buildFeatures { dataBinding = true }",
            // viewBinding { enabled = true } -> buildFeatures { viewBinding = true }
            Regex("""viewBinding\s*\{\s*enabled\s*="[^"]+""") to "buildFeatures { viewBinding = true }",
            // composeOptions { kotlinCompilerExtensionVersion } -> composeOptions { kotlinCompilerExtensionVersion }
            Regex("""composeOptions\s*\{""") to "Check composeOptions",
            // kotlinOptions { jvmTarget } -> kotlinOptions { jvmTarget }
            Regex("""kotlinOptions\s*\{""") to "Check kotlinOptions",
        )

        for (file in findModuleBuildFiles() + (findFile("build.gradle.kts")?.let { listOf(it) } ?: emptyList()) + (findFile("settings.gradle.kts")?.let { listOf(it) } ?: emptyList())) {
            val content = file.readText()
            val lines = content.lines().toList()
            for ((index, line) in lines.withIndex()) {
                for ((pattern, replacement) in deprecatedPatterns) {
                    if (pattern.containsMatchIn(line)) {
                        val severity = when {
                            pattern.pattern.contains("buildToolsVersion") || pattern.pattern.contains("renderscript") || pattern.pattern.contains("enableR8") || pattern.pattern.contains("enableD8") || pattern.pattern.contains("useAndroidX") || pattern.pattern.contains("enableJetifier") -> AgpCompatibilityMatrix.Severity.ERROR
                            pattern.pattern.contains("outputs") || pattern.pattern.contains("testOptions") || pattern.pattern.contains("splits") || pattern.pattern.contains("dataBinding") || pattern.pattern.contains("viewBinding") -> AgpCompatibilityMatrix.Severity.WARNING
                            else -> AgpCompatibilityMatrix.Severity.INFO
                        }
                        usages.add(DeprecatedApiUsage(
                            file = file,
                            line = index + 1,
                            api = line.trim(),
                            replacement = replacement,
                            severity = severity,
                        ))
                    }
                }
            }
        }
        return usages
    }

    private fun extractProductFlavors(): List<String> {
        val flavors = mutableListOf<String>()
        for (file in findModuleBuildFiles()) {
            val content = file.readText()
            val match = Regex("""productFlavors\s*\{([^}]+)\}""").find(content)
            if (match != null) {
                val block = match.groupValues[1]
                Regex("""(\w+)\s*\{""").findAll(block).forEach { m ->
                    flavors.add(m.groupValues[1])
                }
            }
        }
        return flavors
    }

    private fun extractBuildTypes(): List<String> {
        val types = mutableListOf<String>()
        for (file in findModuleBuildFiles()) {
            val content = file.readText()
            val match = Regex("""buildTypes\s*\{([^}]+)\}""").find(content)
            if (match != null) {
                val block = match.groupValues[1]
                Regex("""(\w+)\s*\{""").findAll(block).forEach { m ->
                    types.add(m.groupValues[1])
                }
            }
        }
        return types
    }

    private fun extractDependencies(): Map<String, String> {
        val deps = mutableMapOf<String, String>()
        for (file in findModuleBuildFiles()) {
            val content = file.readText()
            // implementation, api, compileOnly, runtimeOnly, testImplementation, androidTestImplementation
            Regex("""(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|ksp|kapt)\s*[("']([^"')]+)["']""").findAll(content).forEach { m ->
                deps[m.groupValues[1]] = m.groupValues[2]
            }
        }
        return deps
    }

    private fun extractPlugins(): Map<String, String> {
        val plugins = mutableMapOf<String, String>()
        // settings.gradle.kts
        findFile("settings.gradle.kts")?.let { file ->
            val content = file.readText()
            Regex("""id\s*[("']([^"']+)[")']\s*version\s*[("']([^"']+)[")']""").findAll(content).forEach { m ->
                plugins[m.groupValues[1]] = m.groupValues[2]
            }
        }
        // build.gradle.kts (root)
        findFile("build.gradle.kts")?.let { file ->
            val content = file.readText()
            Regex("""id\s*[("']([^"']+)[")']\s*version\s*[("']([^"']+)[")']""").findAll(content).forEach { m ->
                plugins[m.groupValues[1]] = m.groupValues[2]
            }
        }
        return plugins
    }
}