package com.aide.mobilestudio.validation

import java.io.File

/**
 * build.gradle / build.gradle.kts checks: script syntax (real Kotlin parser
 * for .kts, brace balance for Groovy) plus structural/semantic rules that
 * predict AGP configuration failures (namespace, SDK levels, module paths,
 * dependency notations, plugin presence, JVM target consistency).
 */
class BuildScriptCheck : ProjectCheck() {
    override val id = "buildscript"
    override val displayName = "Build Scripts"
    override val progressLabel = "Checking Gradle configuration..."

    /** Best-effort table: dependency → ordered (minVersion, required compileSdk). */
    private val minCompileSdk = mapOf(
        "androidx.core:core" to listOf("1.13.0" to 34, "1.15.0" to 35),
        "androidx.core:core-ktx" to listOf("1.13.0" to 34, "1.15.0" to 35),
        "androidx.activity:activity" to listOf("1.9.0" to 34),
        "androidx.activity:activity-ktx" to listOf("1.9.0" to 34),
        "androidx.fragment:fragment" to listOf("1.8.0" to 34),
        "androidx.appcompat:appcompat" to listOf("1.7.0" to 34),
        "androidx.lifecycle:lifecycle-runtime" to listOf("2.8.0" to 34),
        "androidx.lifecycle:lifecycle-runtime-ktx" to listOf("2.8.0" to 34),
        "androidx.compose.ui:ui" to listOf("1.7.0" to 34),
        "androidx.compose.material3:material3" to listOf("1.3.0" to 34)
    )

    override fun run(ctx: ValidationContext): CheckOutcome {
        if (ctx.model.modules.isEmpty()) return CheckOutcome(skipped = true)
        // A .kt file belongs to its nearest module; a root script must not be
        // blamed for Kotlin sources that live in an Android submodule.
        val modulesWithKotlin = CheckSupport.collectFiles(ctx.projectRoot) { it.extension == "kt" }
            .mapNotNull { ctx.model.moduleFor(it)?.dir }
            .toSet()

        val issues = mutableListOf<InspectionIssue>()
        for (module in ctx.model.modules.sortedBy { it.dir.absolutePath }) {
            if (ctx.isCancelled()) break
            val text = runCatching { module.buildFile.readText() }.getOrNull() ?: continue

            // Syntax (tool for .kts, brace balance for Groovy).
            issues += if (module.isKts) {
                KotlinScriptSyntax.check(
                    ctx, module.buildFile,
                    InspectionCategory.BUILD_GRADLE, "BUILD_SYNTAX"
                )
            } else {
                CheckSupport.balanceIssues(module.buildFile, text, "BUILD_SYNTAX")
            }
            if (ctx.isCancelled()) return CheckOutcome(issues)
            issues += semantic(ctx, module, text, modulesWithKotlin)
        }
        return CheckOutcome(issues)
    }

    private fun semantic(
        ctx: ValidationContext,
        module: ModuleBuildInfo,
        text: String,
        modulesWithKotlin: Set<File>
    ): List<InspectionIssue> {
        val issues = mutableListOf<InspectionIssue>()
        val isRoot = module.dir == ctx.model.root
        val appliedAndroidPlugin = module.pluginIds.any { it.startsWith("com.android.") }
        val file = module.buildFile

        if (module.hasAndroidBlock && !appliedAndroidPlugin && !module.usesAliasPlugins) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "android {"), 1,
                "android block found but the Android Gradle plugin is not applied",
                "BUILD_PLUGIN_MISSING"
            )
        }
        if (appliedAndroidPlugin && !module.hasAndroidBlock && !isRoot) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                file, 1, 1,
                "Android Gradle plugin applied but there is no android { } block",
                "BUILD_ANDROID_BLOCK_MISSING"
            )
        }
        if (!module.hasAndroidBlock) {
            // Non-Android module: only dependency / kotlin rules apply.
            issues += dependencyIssues(ctx, module, text)
            issues += kotlinAndJvmIssues(ctx, module, text, modulesWithKotlin)
            return issues
        }

        val manifestFile = listOf(File(module.dir, "src/main/AndroidManifest.xml"))
            .plus(CheckSupport.collectFiles(module.dir, maxDepth = 6) { it.name == "AndroidManifest.xml" })
            .firstOrNull { it.isFile }
        val manifestPkg = manifestFile?.let { m ->
            Regex("""package\s*=\s*"([^"]+)"""").find(runCatching { m.readText() }.getOrDefault(""))
                ?.groupValues?.get(1)
        }
        val namespace = module.strProp("namespace")
        when {
            namespace == null && manifestPkg == null ->
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                    file, 1, 1,
                    "android.namespace is not set and the manifest has no package attribute",
                    "BUILD_NAMESPACE_MISSING"
                )
            namespace == null && manifestPkg != null ->
                issues += InspectionIssue(
                    InspectionSeverity.INFO, InspectionCategory.BUILD_GRADLE,
                    file, CheckSupport.findLine(text, "android {"), 1,
                    "No android.namespace; relying on the deprecated manifest package attribute",
                    "BUILD_NAMESPACE_FALLBACK"
                )
            namespace != null && !Regex("""^[A-Za-z_][\w]*(\.[A-Za-z_][\w]*)*$""").matches(namespace) ->
                issues += InspectionIssue(
                    InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                    file, CheckSupport.findLine(text, "namespace"), 1,
                    "Invalid namespace '$namespace'", "BUILD_NAMESPACE_INVALID"
                )
        }

        val compileSdk = module.intProp("compileSdk") ?: module.intProp("compileSdkVersion")
        val minSdk = module.intProp("minSdk") ?: module.intProp("minSdkVersion")
        val targetSdk = module.intProp("targetSdk") ?: module.intProp("targetSdkVersion")

        if (compileSdk == null) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "android {"), 1,
                "compileSdk is not set in the android block", "BUILD_COMPILE_SDK_MISSING"
            )
        }
        if (minSdk == null) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "android {"), 1,
                "minSdk is not set (defaultConfig.minSdk)", "BUILD_MIN_SDK_MISSING"
            )
        }
        if (targetSdk == null) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "android {"), 1,
                "targetSdk is not set (defaultConfig.targetSdk)", "BUILD_TARGET_SDK_MISSING"
            )
        } else if (targetSdk < 34) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "targetSdk"), 1,
                "targetSdk $targetSdk is lower than the recommended 34",
                "BUILD_TARGET_SDK_OLD"
            )
        }
        if (minSdk != null && targetSdk != null && minSdk > targetSdk) {
            issues += InspectionIssue(
                InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "minSdk"), 1,
                "minSdk ($minSdk) is greater than targetSdk ($targetSdk)",
                "BUILD_SDK_MIN_GT_TARGET"
            )
        }

        // compileSdk vs dependency minCompileSdk requirements.
        if (compileSdk != null) {
            for (dep in module.dependencies) {
                if (dep.kind != ParsedDependency.Kind.COORDINATE || dep.coordinate.size < 2) continue
                val key = "${dep.coordinate[0]}:${dep.coordinate[1]}"
                val required = requiredCompileSdk(key, dep.coordinate.getOrNull(2)) ?: continue
                if (compileSdk < required) {
                    issues += InspectionIssue(
                        InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                        file, dep.line.coerceAtLeast(1), 1,
                        "$key requires compileSdk $required+ but compileSdk is $compileSdk",
                        "BUILD_MIN_COMPILE_SDK"
                    )
                }
            }
        }

        // Declared source set directories must exist.
        for (srcDir in module.sourceSetSrcDirs) {
            val resolved = File(module.dir, srcDir)
            if (!resolved.isDirectory) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                    file, CheckSupport.findLine(text, srcDir), 1,
                    "sourceSets path '$srcDir' does not exist", "BUILD_SRC_DIR_MISSING"
                )
            }
        }

        issues += dependencyIssues(ctx, module, text)
        issues += kotlinAndJvmIssues(ctx, module, text, modulesWithKotlin)

        if (module.composeEnabled && !module.usesAliasPlugins &&
            module.pluginIds.none { it.contains("compose") }
        ) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                file, CheckSupport.findLine(text, "compose"), 1,
                "buildFeatures.compose is enabled but no Compose compiler plugin is applied",
                "BUILD_COMPOSE_PLUGIN_MISSING"
            )
        }
        return issues
    }

    private fun dependencyIssues(
        ctx: ValidationContext,
        module: ModuleBuildInfo,
        text: String
    ): List<InspectionIssue> {
        val issues = mutableListOf<InspectionIssue>()
        val includedPaths = ctx.model.settings?.includes?.map { normalizeProjectPath(it.path) }.orEmpty()
        for (dep in module.dependencies) {
            when (dep.kind) {
                ParsedDependency.Kind.PROJECT -> {
                    val path = normalizeProjectPath(dep.projectPath ?: continue)
                    val notIncluded = includedPaths.isNotEmpty() && path !in includedPaths
                    val moduleDir = File(
                        ctx.model.root,
                        path.removePrefix(":").replace(':', '/')
                    )
                    val dirMissing = !moduleDir.isDirectory
                    if (notIncluded) {
                        issues += InspectionIssue(
                            InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                            module.buildFile, dep.line, 1,
                            "project(\"${dep.projectPath}\") is not included in settings.gradle",
                            "BUILD_PROJECT_NOT_INCLUDED"
                        )
                    } else if (dirMissing) {
                        issues += InspectionIssue(
                            InspectionSeverity.ERROR, InspectionCategory.BUILD_GRADLE,
                            module.buildFile, dep.line, 1,
                            "project(\"${dep.projectPath}\") directory does not exist",
                            "BUILD_PROJECT_NOT_INCLUDED"
                        )
                    }
                }
                ParsedDependency.Kind.FILES, ParsedDependency.Kind.FILE_TREE -> {
                    for (p in dep.filePaths) {
                        val resolved = File(module.dir, p)
                        if (!resolved.exists()) {
                            issues += InspectionIssue(
                                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                                module.buildFile, dep.line, 1,
                                "Dependency path '$p' does not exist", "BUILD_FILE_DEP_MISSING"
                            )
                        }
                    }
                }
                ParsedDependency.Kind.COORDINATE -> {
                    if (dep.coordinate.size < 3) {
                        issues += InspectionIssue(
                            InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                            module.buildFile, dep.line, 1,
                            "Dependency '${dep.raw}' has no version",
                            "BUILD_COORD_NO_VERSION"
                        )
                    }
                }
            }
        }
        return issues
    }

    private fun kotlinAndJvmIssues(
        ctx: ValidationContext,
        module: ModuleBuildInfo,
        text: String,
        modulesWithKotlin: Set<File>
    ): List<InspectionIssue> {
        val issues = mutableListOf<InspectionIssue>()
        if (module.dir in modulesWithKotlin && !module.kotlinPluginText) {
            issues += InspectionIssue(
                InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                module.buildFile, 1, 1,
                "Module contains .kt files but no Kotlin Gradle plugin is applied",
                "BUILD_KOTLIN_PLUGIN_MISSING"
            )
        }
        module.jvmTarget?.let { target ->
            if (target !in setOf("1.8", "8", "11", "17", "21")) {
                issues += InspectionIssue(
                    InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                    module.buildFile, CheckSupport.findLine(text, "jvmTarget"), 1,
                    "Unusual jvmTarget '$target'", "BUILD_JVM_TARGET_INVALID"
                )
            }
            module.javaCompat?.let { compat ->
                if (normalizeJavaTarget(compat) != normalizeJavaTarget(target)) {
                    issues += InspectionIssue(
                        InspectionSeverity.WARNING, InspectionCategory.BUILD_GRADLE,
                        module.buildFile, CheckSupport.findLine(text, "jvmTarget"), 1,
                        "jvmTarget '$target' does not match sourceCompatibility '$compat'",
                        "BUILD_JVM_TARGET_MISMATCH"
                    )
                }
            }
        }
        return issues
    }

    /** "1.8" -> "8", "JavaVersion.VERSION_17" -> "17", "11" -> "11". */
    private fun normalizeJavaTarget(v: String): String {
        val nums = Regex("""\d+""").findAll(v).map { it.value }.toList()
        if (nums.isEmpty()) return v
        // "1.8" must become "8" (take the last component), same for VERSION_1_8.
        return nums.last()
    }

    private fun normalizeProjectPath(path: String): String =
        ":" + path.trim().removePrefix(":").removeSuffix(":").replace('/', ':')

    private fun requiredCompileSdk(key: String, version: String?): Int? {
        val rules = minCompileSdk[key] ?: return null
        val ver = version ?: return null
        var required: Int? = null
        for ((minVersion, sdk) in rules) {
            if (versionAtLeast(ver, minVersion)) required = sdk
        }
        return required
    }

    private fun versionAtLeast(actual: String, min: String): Boolean {
        val a = actual.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val b = min.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return true
    }
}
