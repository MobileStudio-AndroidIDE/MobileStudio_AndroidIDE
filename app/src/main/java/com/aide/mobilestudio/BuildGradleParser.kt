package com.aide.mobilestudio

import java.io.File

/**
 * build.gradle / build.gradle.kts ?일???싱?여 Android ?로?트 ?성 추출
 * AGP ?로그래?모델??기능???립?으??행
 */
object BuildGradleParser {

    data class ParseResult(
        val model: AndroidProjectModel,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    fun parseProject(projectDir: File): ParseResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val props = mutableMapOf<String, String>()
        val plugins = mutableListOf<String>()
        val dependencies = mutableListOf<AndroidProjectModel.ParsedDependency>()
        val buildTypes = mutableMapOf<String, AndroidProjectModel.BuildTypeInfo>()
        val productFlavors = mutableListOf<AndroidProjectModel.ProductFlavor>()
        val signingConfigs = mutableMapOf<String, AndroidProjectModel.SigningConfigInfo>()
        val sourceSets = mutableMapOf<String, AndroidProjectModel.SourceSetInfo>()

        // 1. Root build.gradle
        val rootBuildGradle = findBuildFile(projectDir, "")
        if (rootBuildGradle != null) {
            parseBuildFile(rootBuildGradle, props, plugins, errors)
        } else {
            warnings.add("Root build.gradle not found")
        }

        // 2. settings.gradle (plugin management, dependencyResolutionManagement)
        val settingsGradle = findSettingsFile(projectDir)
        if (settingsGradle != null) {
            parseSettingsFile(settingsGradle, props, errors)
        }

        // 3. libs.versions.toml (Version Catalog)
        val libsToml = File(projectDir, "gradle/libs.versions.toml")
        if (libsToml.exists()) {
            parseVersionCatalog(libsToml, props, errors)
        }

        // 4. app/build.gradle (main module)
        val appBuildGradle = findBuildFile(projectDir, "app")
        if (appBuildGradle != null) {
            parseBuildFile(appBuildGradle, props, plugins, errors)
            parseDependencies(appBuildGradle, dependencies, errors)
            parseBuildTypes(appBuildGradle, buildTypes, errors)
            parseProductFlavors(appBuildGradle, productFlavors, errors)
            parseSigningConfigs(appBuildGradle, signingConfigs, errors)
            parseSourceSets(appBuildGradle, sourceSets, errors)
        } else {
            // Try other modules
            projectDir.listFiles()?.filter { it.isDirectory }?.forEach { moduleDir ->
                if (moduleDir.name != ".gradle" && moduleDir.name != ".git") {
                    val moduleBuild = findBuildFile(projectDir, moduleDir.name)
                    if (moduleBuild != null && isAndroidModule(moduleBuild)) {
                        parseBuildFile(moduleBuild, props, plugins, errors)
                        parseDependencies(moduleBuild, dependencies, errors)
                    }
                }
            }
        }

        // 5. gradle.properties
        val gradleProps = File(projectDir, "gradle.properties")
        if (gradleProps.exists()) {
            parseGradleProperties(gradleProps, props, errors)
        }

        // 6. Detect variants from build types + flavors
        val variants = buildVariants(buildTypes, productFlavors)

        // 7. Build model
        val model = AndroidProjectModel(
            packageName = props["namespace"] ?: props["applicationId"] ?: "",
            applicationId = props["applicationId"] ?: "",
            namespace = props["namespace"] ?: "",
            compileSdk = props["compileSdkVersion"]?.toIntOrNull()
                ?: props["compileSdk"]?.toIntOrNull() ?: 0,
            minSdk = props["minSdkVersion"]?.toIntOrNull()
                ?: props["minSdk"]?.toIntOrNull() ?: 0,
            targetSdk = props["targetSdkVersion"]?.toIntOrNull()
                ?: props["targetSdk"]?.toIntOrNull() ?: 0,
            versionCode = props["versionCode"]?.toIntOrNull() ?: 0,
            versionName = props["versionName"]?.toString() ?: "",
            buildToolsVersion = props["buildToolsVersion"] ?: "",
            ndkVersion = props["ndkVersion"] ?: "",
            isKotlinApplied = plugins.any { it.contains("kotlin") } ||
                props["kotlin.jvm.target"] != null,
            isComposeEnabled = plugins.any { it.contains("compose") } ||
                props["kotlin.compose"] != null,
            isViewBindingEnabled = props["viewBinding"]?.trim()?.lowercase() == "true" ||
                props["android.viewBinding"]?.trim()?.lowercase() == "true",
            isDataBindingEnabled = props["dataBinding"]?.trim()?.lowercase() == "true" ||
                props["android.dataBinding"]?.trim()?.lowercase() == "true",
            hasNativeBuild = plugins.any { it.contains("cmake") || it.contains("ndk") } ||
                appBuildGradle?.readText()?.contains("externalNativeBuild") == true,
            hasTestRunner = props["testInstrumentationRunner"] != null,
            AGPVersion = props["agpVersion"] ?: props["android.tools.build.gradle.version"] ?: "",
            kotlinVersion = props["kotlinVersion"] ?: props["kotlin.version"] ?: "",
            availableVariants = variants,
            productFlavors = productFlavors,
            sourceSets = sourceSets,
            plugins = plugins.distinct(),
            signingConfigs = signingConfigs,
            buildTypes = buildTypes,
            dependencies = dependencies,
            lastSyncTime = System.currentTimeMillis()
        )

        return ParseResult(model, errors, warnings)
    }

    // ?? File Discovery ????????????????????????????????????????????????????

    private fun findBuildFile(projectDir: File, module: String): File? {
        val dir = if (module.isEmpty()) projectDir else File(projectDir, module)
        return listOf("build.gradle.kts", "build.gradle")
            .mapNotNull { File(dir, it).takeIf { f -> f.exists() } }
            .firstOrNull()
    }

    private fun findSettingsFile(projectDir: File): File? =
        listOf("settings.gradle.kts", "settings.gradle")
            .mapNotNull { File(projectDir, it).takeIf { f -> f.exists() } }
            .firstOrNull()

    // ?? Build File Parsing ????????????????????????????????????????????????

    private fun parseBuildFile(
        file: File,
        props: MutableMap<String, String>,
        plugins: MutableList<String>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val isKts = file.name.endsWith(".kts")

            // Extract plugins
            extractPlugins(text, isKts, props, plugins)

            // Extract android block properties
            extractAndroidBlock(text, isKts, props)

            // Extract dependencies block
            extractDependenciesBlock(text, isKts, props)
        } catch (e: Exception) {
            errors.add("Failed to parse ${file.name}: ${e.message}")
        }
    }

    private fun extractPlugins(text: String, isKts: Boolean, props: MutableMap<String, String>, plugins: MutableList<String>) {
        // KTS: id("com.android.application") version "8.1.0" apply true
        // Groovy: id 'com.android.application' version '8.1.0' apply true
        val pluginPattern = if (isKts) {
            Regex("""id\s*\(\s*["']([^"']+)["']\s*\)""")
        } else {
            Regex("""id\s+['"]([^"']+)['"]""")
        }
        pluginPattern.findAll(text).forEach { match ->
            plugins.add(match.groupValues[1])
        }

        // Also check buildscript classpath for AGP version
        val classpathPattern = Regex("""com\.android\.tools\.build:gradle:([^\s"'"]+)""")
        classpathPattern.find(text)?.let { match ->
            props["agpVersion"] = match.groupValues[1]
        }

        // Kotlin plugin version
        val kotlinPluginPattern = Regex("""org\.jetbrains\.kotlin\.android:([^\s"'"]+):([^\s"'"]+)""")
        kotlinPluginPattern.find(text)?.let { match ->
            props["kotlin.version"] = match.groupValues[2]
        }
    }

    private fun extractAndroidBlock(text: String, isKts: Boolean, props: MutableMap<String, String>) {
        // Find android { ... } block content
        val androidBlock = extractBlock(text, "android") ?: return

        // compileSdk
        extractProperty(androidBlock, "compileSdk", props)
        extractProperty(androidBlock, "compileSdkVersion", props)
        extractProperty(androidBlock, "minSdk", props)
        extractProperty(androidBlock, "minSdkVersion", props)
        extractProperty(androidBlock, "targetSdk", props)
        extractProperty(androidBlock, "targetSdkVersion", props)
        extractProperty(androidBlock, "buildToolsVersion", props)
        extractProperty(androidBlock, "ndkVersion", props)
        extractProperty(androidBlock, "testInstrumentationRunner", props)
        extractProperty(androidBlock, "viewBinding", props)
        extractProperty(androidBlock, "dataBinding", props)
        extractProperty(androidBlock, "namespace", props)

        // defaultConfig block
        val defaultConfig = extractBlock(androidBlock, "defaultConfig") ?: androidBlock
        extractProperty(defaultConfig, "applicationId", props)
        extractProperty(defaultConfig, "versionCode", props)
        extractProperty(defaultConfig, "versionName", props)
        extractProperty(defaultConfig, "minSdk", props)
        extractProperty(defaultConfig, "targetSdk", props)
        extractProperty(defaultConfig, "testInstrumentationRunner", props)

        // compose block
        val composeBlock = extractBlock(androidBlock, "composeOptions") ?: extractBlock(androidBlock, "compose")
        if (composeBlock != null) {
            props["kotlin.compose"] = "true"
            extractProperty(composeBlock, "kotlinCompilerExtensionVersion", props)
            extractProperty(composeBlock, "compilerExtensionVersion", props)
        }

        // buildFeatures block
        val buildFeatures = extractBlock(androidBlock, "buildFeatures")
        if (buildFeatures != null) {
            extractProperty(buildFeatures, "viewBinding", props)
            extractProperty(buildFeatures, "dataBinding", props)
            extractProperty(buildFeatures, "compose", props)
            extractProperty(buildFeatures, "buildConfig", props)
            extractProperty(buildFeatures, "aidl", props)
            extractProperty(buildFeatures, "prefab", props)
        }

        // externalNativeBuild
        val nativeBuild = extractBlock(androidBlock, "externalNativeBuild")
        if (nativeBuild != null) {
            props["android.externalNativeBuild"] = "true"
            extractProperty(nativeBuild, "cmakeVersion", props)
        }
    }

    private fun extractDependenciesBlock(text: String, isKts: Boolean, props: MutableMap<String, String>) {
        val depsBlock = extractBlock(text, "dependencies") ?: return
        // Store raw dependencies block for later parsing
        props["dependenciesBlock"] = depsBlock
    }

    private fun extractBlock(text: String, blockName: String): String? {
        // Find blockName { ... } with brace matching
        val pattern = Regex("""$blockName\s*\{""")
        val match = pattern.find(text) ?: return null

        val start = match.range.last + 1
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return if (depth == 0) text.substring(start, i - 1) else null
    }

    private fun extractProperty(block: String, key: String, props: MutableMap<String, String>) {
        if (props.containsKey(key)) return

        // KTS: key = "value" or key = value or key = 34
        // Groovy: key "value" or key = "value" or key value
        val patterns = listOf(
            Regex("""$key\s*=\s*["']([^"']+)["']"""),     // key = "value" or key = 'value'
            Regex("""$key\s*=\s*(\d+)"""),                  // key = 34
            Regex("""$key\s*=\s*(true|false)""", RegexOption.IGNORE_CASE), // key = true/false
            Regex("""$key\s+["']([^"']+)["']"""),           // key "value"
            Regex("""$key\s+(\d+)"""),                       // key 34
        )

        for (pattern in patterns) {
            pattern.find(block)?.let { match ->
                val value = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                props[key] = value
                return
            }
        }
    }

    // ?? Dependency Parsing ????????????????????????????????????????????????

    private fun parseDependencies(
        file: File,
        dependencies: MutableList<AndroidProjectModel.ParsedDependency>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val depsBlock = extractBlock(text, "dependencies") ?: return

            // Match: implementation "group:artifact:version"
            // Match: implementation("group:artifact:version")
            // Match: implementation group:artifact:version
            val depPatterns = listOf(
                // Kotlin style: implementation("group:artifact:version")
                Regex("""(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\s*\(\s*["']([^"']+)["']\s*\)"""),
                // Groovy style: implementation 'group:artifact:version'
                Regex("""(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|kapt|ksp)\s+['"]([^"']+)['"]"""),
                // Platform/BOM: implementation platform("group:artifact:version")
                Regex("""(implementation|api|compileOnly|runtimeOnly)\s+(?:platform|testFixtures)\s*\(\s*["']([^"']+)["']\s*\)"""),
            )

            for (pattern in depPatterns) {
                pattern.findAll(depsBlock).forEach { match ->
                    val config = match.groupValues[1]
                    val coordinates = match.groupValues[2]
                    val parts = coordinates.split(":")
                    if (parts.size >= 3) {
                        dependencies.add(
                            AndroidProjectModel.ParsedDependency(
                                configuration = config,
                                group = parts[0],
                                artifact = parts[1],
                                version = parts[2],
                                isBom = coordinates.contains("platform") || coordinates.contains("enforcedPlatform"),
                                isPlatform = coordinates.contains("platform")
                            )
                        )
                    }
                }
            }

            // Project dependencies: implementation project(":module")
            val projectDepPattern = Regex("""(implementation|api|compileOnly|runtimeOnly)\s*(?:\(\s*)?project\s*\(\s*["']:([^"']+)["']\s*\)""")
            projectDepPattern.findAll(depsBlock).forEach { match ->
                dependencies.add(
                    AndroidProjectModel.ParsedDependency(
                        configuration = match.groupValues[1],
                        group = "project",
                        artifact = ":${match.groupValues[2]}",
                        version = ""
                    )
                )
            }
        } catch (e: Exception) {
            errors.add("Failed to parse dependencies from ${file.name}: ${e.message}")
        }
    }

    // ?? Build Types ???????????????????????????????????????????????????????

    private fun parseBuildTypes(
        file: File,
        buildTypes: MutableMap<String, AndroidProjectModel.BuildTypeInfo>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val androidBlock = extractBlock(text, "android") ?: return

            // Always add debug and release as defaults
            buildTypes["debug"] = AndroidProjectModel.BuildTypeInfo("debug", isDebuggable = true)
            buildTypes["release"] = AndroidProjectModel.BuildTypeInfo("release", isMinifyEnabled = false)

            // Parse custom build types
            val buildTypesBlock = extractBlock(androidBlock, "buildTypes") ?: return

            // Match: debug { ... } or release { ... }
            val buildTypePattern = Regex("""(\w+)\s*\{""")
            for (match in buildTypePattern.findAll(buildTypesBlock)) {
                val name = match.groupValues[1]
                if (name in listOf("debug", "release", "debuggable", "minifyEnabled")) continue

                val start = match.range.last + 1
                var depth = 1
                var i = start
                while (i < buildTypesBlock.length && depth > 0) {
                    when (buildTypesBlock[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                if (depth == 0) {
                    val blockContent = buildTypesBlock.substring(start, i - 1)
                    val isDebuggable = blockContent.contains("isDebuggable", ignoreCase = true) &&
                        blockContent.substringAfter("isDebuggable").substringBefore("\n")
                            .contains("true", ignoreCase = true)
                    val isMinify = blockContent.contains("isMinifyEnabled", ignoreCase = true) &&
                        blockContent.substringAfter("isMinifyEnabled").substringBefore("\n")
                            .contains("true", ignoreCase = true)

                    buildTypes[name] = AndroidProjectModel.BuildTypeInfo(
                        name = name,
                        isDebuggable = isDebuggable,
                        isMinifyEnabled = isMinify
                    )
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse build types: ${e.message}")
        }
    }

    // ?? Product Flavors ???????????????????????????????????????????????????

    private fun parseProductFlavors(
        file: File,
        productFlavors: MutableList<AndroidProjectModel.ProductFlavor>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val androidBlock = extractBlock(text, "android") ?: return

            val flavorDimensions = mutableListOf<String>()
            val dimensionsBlock = extractBlock(androidBlock, "flavorDimensions")
            if (dimensionsBlock != null) {
                Regex("""["']([^"']+)["']""").findAll(dimensionsBlock).forEach {
                    flavorDimensions.add(it.groupValues[1])
                }
            }

            val productFlavorsBlock = extractBlock(androidBlock, "productFlavors") ?: return

            val flavorPattern = Regex("""(\w+)\s*\{""")
            for (match in flavorPattern.findAll(productFlavorsBlock)) {
                val name = match.groupValues[1]
                if (name in listOf("dimension", "applicationIdSuffix", "versionNameSuffix")) continue

                val start = match.range.last + 1
                var depth = 1
                var i = start
                while (i < productFlavorsBlock.length && depth > 0) {
                    when (productFlavorsBlock[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                if (depth == 0) {
                    val blockContent = productFlavorsBlock.substring(start, i - 1)
                    val dimension = flavorDimensions.firstOrNull() ?: ""

                    productFlavors.add(
                        AndroidProjectModel.ProductFlavor(
                            name = name,
                            dimension = dimension
                        )
                    )
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse product flavors: ${e.message}")
        }
    }

    // ?? Signing Configs ???????????????????????????????????????????????????

    private fun parseSigningConfigs(
        file: File,
        signingConfigs: MutableMap<String, AndroidProjectModel.SigningConfigInfo>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val androidBlock = extractBlock(text, "android") ?: return
            val signingBlock = extractBlock(androidBlock, "signingConfigs") ?: return

            val configPattern = Regex("""(\w+)\s*\{""")
            for (match in configPattern.findAll(signingBlock)) {
                val name = match.groupValues[1]
                val start = match.range.last + 1
                var depth = 1
                var i = start
                while (i < signingBlock.length && depth > 0) {
                    when (signingBlock[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                if (depth == 0) {
                    val block = signingBlock.substring(start, i - 1)
                    signingConfigs[name] = AndroidProjectModel.SigningConfigInfo(
                        name = name,
                        storeFile = extractQuotedValue(block, "storeFile"),
                        storePassword = extractQuotedValue(block, "storePassword"),
                        keyAlias = extractQuotedValue(block, "keyAlias"),
                        keyPassword = extractQuotedValue(block, "keyPassword")
                    )
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse signing configs: ${e.message}")
        }
    }

    private fun extractQuotedValue(block: String, key: String): String? {
        val pattern = Regex("""$key\s*(?:=\s*)?["']([^"']+)["']""")
        return pattern.find(block)?.groupValues?.get(1)
    }

    // ?? Source Sets ???????????????????????????????????????????????????????

    private fun parseSourceSets(
        file: File,
        sourceSets: MutableMap<String, AndroidProjectModel.SourceSetInfo>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val androidBlock = extractBlock(text, "android") ?: return
            val sourceSetsBlock = extractBlock(androidBlock, "sourceSets") ?: return

            val sourceSetPattern = Regex("""(\w+)\s*\{""")
            for (match in sourceSetPattern.findAll(sourceSetsBlock)) {
                val name = match.groupValues[1]
                val start = match.range.last + 1
                var depth = 1
                var i = start
                while (i < sourceSetsBlock.length && depth > 0) {
                    when (sourceSetsBlock[i]) {
                        '{' -> depth++
                        '}' -> depth--
                    }
                    i++
                }
                if (depth == 0) {
                    val block = sourceSetsBlock.substring(start, i - 1)
                    sourceSets[name] = AndroidProjectModel.SourceSetInfo(
                        name = name,
                        javaDirs = extractListValue(block, "java"),
                        resDirs = extractListValue(block, "res"),
                        assetsDirs = extractListValue(block, "assets"),
                        jniLibsDirs = extractListValue(block, "jniLibs")
                    )
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse source sets: ${e.message}")
        }
    }

    private fun extractListValue(block: String, key: String): List<String> {
        val values = mutableListOf<String>()
        // Match: srcDir("path") or srcDirs("path1", "path2")
        val pattern = Regex("""$key(?:Dirs|Dir)\s*(?:\(\s*)?["']([^"']+)["']""")
        pattern.findAll(block).forEach {
            values.add(it.groupValues[1])
        }
        return values
    }

    // ?? Variant Building ??????????????????????????????????????????????????

    private fun buildVariants(
        buildTypes: Map<String, AndroidProjectModel.BuildTypeInfo>,
        flavors: List<AndroidProjectModel.ProductFlavor>
    ): List<AndroidProjectModel.BuildVariant> {
        val variants = mutableListOf<AndroidProjectModel.BuildVariant>()

        if (flavors.isEmpty()) {
            // No flavors: debug, release
            buildTypes.forEach { (name, info) ->
                variants.add(
                    AndroidProjectModel.BuildVariant(
                        name = name,
                        type = name,
                        isActive = name == "debug",
                        isDebug = name.contains("debug", ignoreCase = true)
                    )
                )
            }
        } else {
            // With flavors: flavorDebug, flavorRelease, etc.
            val flavorNames = flavors.map { it.name }.distinct()
            for (flavor in flavorNames) {
                for ((typeName, _) in buildTypes) {
                    val variantName = "${flavor}${typeName.replaceFirstChar { it.uppercase() }}"
                    variants.add(
                        AndroidProjectModel.BuildVariant(
                            name = variantName,
                            type = typeName,
                            flavor = flavor,
                            isActive = typeName == "debug",
                            isDebug = typeName.contains("debug", ignoreCase = true)
                        )
                    )
                }
            }
        }

        return variants
    }

    // ?? Settings File Parsing ?????????????????????????????????????????????

    private fun parseSettingsFile(
        file: File,
        props: MutableMap<String, String>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()
            val isKts = file.name.endsWith(".kts")

            // pluginManagement { ... }
            val pluginMgmt = extractBlock(text, "pluginManagement")
            if (pluginMgmt != null) {
                val versionPattern = if (isKts) {
                    Regex("""id\s*\(\s*["']([^"']+)["']\s*\)\s*version\s*["']([^"']+)["']""")
                } else {
                    Regex("""id\s+['"]([^"']+)['"]\s+version\s+['"]([^"']+)['"]""")
                }
                versionPattern.findAll(pluginMgmt).forEach { match ->
                    val pluginId = match.groupValues[1]
                    val version = match.groupValues[2]
                    when {
                        pluginId.contains("android") -> props["agpVersion"] = version
                        pluginId.contains("kotlin") -> props["kotlin.version"] = version
                    }
                }
            }

            // dependencyResolutionManagement { ... }
            val depMgmt = extractBlock(text, "dependencyResolutionManagement")
            if (depMgmt != null) {
                // Extract version catalog references
                val catalogPattern = Regex("""(gradlePluginPortal|google|mavenCentral)\(\)""")
                // Not much to extract here for properties
            }

            // rootProject.name
            val rootNamePattern = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""")
            rootNamePattern.find(text)?.let { match ->
                props["rootProject.name"] = match.groupValues[1]
            }
        } catch (e: Exception) {
            errors.add("Failed to parse settings.gradle: ${e.message}")
        }
    }

    // ?? Version Catalog ???????????????????????????????????????????????????

    private fun parseVersionCatalog(
        file: File,
        props: MutableMap<String, String>,
        errors: MutableList<String>
    ) {
        try {
            val text = file.readText()

            // [versions] section
            val versionsSection = extractTomlSection(text, "versions")
            if (versionsSection != null) {
                val versionPattern = Regex("""(\w+)\s*=\s*["']([^"']+)["']""")
                versionPattern.findAll(versionsSection).forEach { match ->
                    val key = match.groupValues[1]
                    val value = match.groupValues[2]
                    when {
                        key.contains("agp") || key.contains("android") -> props["agpVersion"] = value
                        key.contains("kotlin") -> props["kotlin.version"] = value
                    }
                    props["version.$key"] = value
                }
            }

            // [libraries] section - extract dependency coordinates
            val libsSection = extractTomlSection(text, "libraries")
            if (libsSection != null) {
                val libPattern = Regex("""(\w+)\s*=\s*\{[^}]*module\s*=\s*["']([^"']+)["']""")
                libPattern.findAll(libsSection).forEach { match ->
                    val alias = match.groupValues[1]
                    val module = match.groupValues[2]
                    props["lib.$alias"] = module
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse libs.versions.toml: ${e.message}")
        }
    }

    private fun extractTomlSection(text: String, sectionName: String): String? {
        val pattern = Regex("""\[$sectionName\]""")
        val match = pattern.find(text) ?: return null
        val start = match.range.last + 1
        // Find next section or end
        val nextSection = Regex("""\[[^\]]+\]""").find(text, start)
        val end = nextSection?.range?.first ?: text.length
        return text.substring(start, end)
    }

    // ?? Gradle Properties ?????????????????????????????????????????????????

    private fun parseGradleProperties(
        file: File,
        props: MutableMap<String, String>,
        errors: MutableList<String>
    ) {
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || trimmed.isEmpty()) return@forEach
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val key = trimmed.substring(0, eqIndex).trim()
                    val value = trimmed.substring(eqIndex + 1).trim()
                    if (value.isNotEmpty() && value != "null") {
                        props[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Failed to parse gradle.properties: ${e.message}")
        }
    }

    // ?? Helpers ???????????????????????????????????????????????????????????

    private fun isAndroidModule(file: File): Boolean {
        return try {
            val text = file.readText()
            text.contains("com.android.application") ||
                text.contains("com.android.library") ||
                text.contains("com.android.dynamic-feature")
        } catch (e: Exception) {
            false
        }
    }
}
