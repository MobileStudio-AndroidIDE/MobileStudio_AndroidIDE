package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GradleToolingConnector - Gradle Tooling API를 사용한 프로젝트 모델 수집
 * Android Studio와 동일하게 Gradle 데몬과 통신하여 정확한 프로젝트 정보를 가져옵니다.
 */
class GradleToolingConnector(private val context: Context) {

    companion object {
        private const val TAG = "GradleToolingConnector"
        private const val INIT_SCRIPT_NAME = "mobilestudio_tooling_init.gradle"
    }

    data class ToolingResult(
        val success: Boolean,
        val model: AndroidProjectModel?,
        val error: String? = null,
        val durationMs: Long = 0
    )

    /**
     * Gradle Tooling API를 사용하여 프로젝트 모델 수집
     */
    suspend fun fetchProjectModel(
        projectDir: File,
        gradleRunner: GradleRunner,
        onProgress: (String) -> Unit = {}
    ): ToolingResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            onProgress("Preparing Gradle Tooling API...")

            // 1. init script 생성
            val initScript = createInitScript(projectDir)
            onProgress("Generated Tooling API init script")

            // 2. Gradle 모델 수집 태스크 실행
            onProgress("Running Gradle model collection...")
            val modelOutput = collectGradleModel(projectDir, gradleRunner, initScript)

            if (modelOutput.isBlank()) {
                return@withContext ToolingResult(
                    success = false,
                    model = null,
                    error = "No output from Gradle model collection",
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            // 3. JSON 파싱
            onProgress("Parsing Gradle model...")
            val model = parseGradleModel(modelOutput, projectDir)

            // 4. init script 정리
            initScript.delete()

            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Gradle model collected in ${duration}ms: ${model?.applicationId}")

            return@withContext ToolingResult(
                success = true,
                model = model,
                durationMs = duration
            )

        } catch (e: Exception) {
            Log.e(TAG, "Tooling API failed", e)
            return@withContext ToolingResult(
                success = false,
                model = null,
                error = e.message,
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Gradle init script 생성 - 프로젝트 모델을 JSON으로 출력
     */
    private fun createInitScript(projectDir: File): File {
        val initScript = File(projectDir, ".gradle/$INIT_SCRIPT_NAME")
        initScript.parentFile?.mkdirs()

        initScript.writeText("""
// MobileStudio Gradle Tooling API init script v2
// Collects comprehensive project model for IDE integration

import groovy.json.JsonBuilder

class MobileStudioModelCollector {
    def rootInfo = [:]
    def modulesInfo = []
    def allDependencies = []
    def allPlugins = []
    def allBuildTypes = []
    def allSigningConfigs = []
    def allProductFlavors = []
    def allVariants = []
    def allRepositories = []
    def gradleInfo = [:]

    void collectRootProject(project) {
        rootInfo['name'] = project.name
        rootInfo['path'] = project.projectDir.absolutePath
        rootInfo['buildFile'] = project.buildFile?.absolutePath ?: ''
        rootInfo['rootDir'] = project.rootDir.absolutePath

        // Gradle version info
        try {
            gradleInfo['gradleVersion'] = project.gradle.gradleVersion
        } catch (e) { /* ignore */ }

        // Collect root plugins
        project.plugins.each { plugin ->
            def pluginId = plugin.class.name
            if (!allPlugins.any { it['id'] == pluginId }) {
                allPlugins << ['id': pluginId, 'name': plugin.class.simpleName]
            }
        }
    }

    void collectModule(project) {
        def moduleInfo = [:]
        moduleInfo['name'] = project.name
        moduleInfo['path'] = project.path
        moduleInfo['dir'] = project.projectDir.absolutePath
        moduleInfo['buildFile'] = project.buildFile?.absolutePath ?: ''
        moduleInfo['hasBuildFile'] = project.buildFile?.exists() ?: false

        // Detect module type
        def isAndroid = false
        def isApplication = false
        def isLibrary = false
        def isTest = project.path.contains('test') || project.path.contains('androidTest')

        // Check for android extension
        try {
            def androidExt = project.extensions.findByName('android')
            if (androidExt != null) {
                isAndroid = true
                collectAndroidConfig(moduleInfo, project, androidExt)
            }
        } catch (e) {
            moduleInfo['androidError'] = e.message
        }

        // Check for java-library or kotlin plugin
        try {
            def hasJavaLib = project.plugins.hasPlugin('java-library')
            def hasJava = project.plugins.hasPlugin('java')
            def hasKotlin = project.plugins.hasPlugin('org.jetbrains.kotlin.jvm')
            moduleInfo['isJavaLibrary'] = hasJavaLib
            moduleInfo['isPureJava'] = hasJava && !isAndroid
            moduleInfo['isPureKotlin'] = hasKotlin && !isAndroid
        } catch (e) { /* ignore */ }

        moduleInfo['isAndroid'] = isAndroid
        moduleInfo['isApplication'] = isApplication
        moduleInfo['isLibrary'] = isLibrary
        moduleInfo['isTest'] = isTest

        // Module plugins
        def modulePlugins = []
        project.plugins.each { plugin ->
            def pid = plugin.class.name
            modulePlugins << ['id': pid, 'name': plugin.class.simpleName]
            if (!allPlugins.any { it['id'] == pid }) {
                allPlugins << ['id': pid, 'name': plugin.class.simpleName]
            }
        }
        moduleInfo['plugins'] = modulePlugins

        // Collect module dependencies
        collectModuleDependencies(project, moduleInfo['name'])

        // Collect source sets for non-android modules
        if (!isAndroid) {
            try {
                def srcSets = [:]
                project.sourceSets?.each { sourceSet ->
                    def srcInfo = [:]
                    srcInfo['name'] = sourceSet.name
                    srcInfo['java'] = sourceSet.java.srcDirs.collect { it.absolutePath }
                    srcInfo['resources'] = sourceSet.resources.srcDirs.collect { it.absolutePath }
                    srcSets[sourceSet.name] = srcInfo
                }
                moduleInfo['sourceSets'] = srcSets
            } catch (e) { /* ignore */ }
        }

        // Project dependencies (submodule references)
        def projectDeps = []
        project.configurations.each { config ->
            config.dependencies.each { dep ->
                if (dep instanceof ProjectDependency) {
                    projectDeps << [
                        'configuration': config.name,
                        'projectPath': dep.dependencyProject.path
                    ]
                }
            }
        }
        moduleInfo['projectDependencies'] = projectDeps

        modulesInfo << moduleInfo
    }

    void collectAndroidConfig(moduleInfo, project, android) {
        try {
            moduleInfo['namespace'] = android.namespace ?: ''

            // Compile SDK
            try {
                moduleInfo['compileSdk'] = android.compileSdkVersion?.apiLevel ?: 0
                moduleInfo['compileSdkName'] = android.compileSdkVersion?.name ?: ''
            } catch (e) {
                moduleInfo['compileSdk'] = 0
            }

            // Default config
            try {
                def dc = android.defaultConfig
                moduleInfo['applicationId'] = dc?.applicationId ?: ''
                moduleInfo['minSdk'] = dc?.minSdk?.apiLevel ?: dc?.minSdkVersion ?: 0
                moduleInfo['targetSdk'] = dc?.targetSdk?.apiLevel ?: dc?.targetSdkVersion ?: 0
                moduleInfo['versionCode'] = dc?.versionCode ?: 0
                moduleInfo['versionName'] = dc?.versionName ?: ''
                moduleInfo['testInstrumentationRunner'] = dc?.testInstrumentationRunner ?: ''
            } catch (e) { /* ignore */ }

            // Build types
            try {
                android.buildTypes?.each { bt ->
                    def btInfo = [
                        'name': bt.name,
                        'debuggable': bt.debuggable,
                        'minifyEnabled': bt.isMinifyEnabled(),
                        'shrinkResources': bt.isShrinkResourcesEnabled(),
                        'proguardFiles': bt.proguardFiles.collect { it.absolutePath },
                        'signingConfig': bt.signingConfig?.name ?: ''
                    ]
                    allBuildTypes << btInfo
                }
            } catch (e) { /* ignore */ }

            // Product flavors
            try {
                android.productFlavors?.each { flavor ->
                    def flavorInfo = [
                        'name': flavor.name,
                        'dimension': flavor.dimension ?: '',
                        'applicationIdSuffix': flavor.applicationIdSuffix ?: '',
                        'versionNameSuffix': flavor.versionNameSuffix ?: '',
                        'minSdk': flavor.minSdk?.apiLevel ?: 0,
                        'targetSdk': flavor.targetSdk?.apiLevel ?: 0
                    ]
                    allProductFlavors << flavorInfo
                }
            } catch (e) { /* ignore */ }

            // Signing configs
            try {
                android.signingConfigs?.each { sc ->
                    allSigningConfigs << [
                        'name': sc.name,
                        'storeFile': sc.storeFile?.absolutePath ?: '',
                        'storeType': sc.storeType ?: 'jks',
                        'keyAlias': sc.keyAlias ?: ''
                    ]
                }
            } catch (e) { /* ignore */ }

            // Variants
            try {
                android.applicationVariants?.each { variant ->
                    def vInfo = [
                        'name': variant.name,
                        'buildType': variant.buildType.name,
                        'flavorName': variant.flavorName ?: '',
                        'isDebuggable': variant.buildType.debuggable,
                        'applicationId': variant.applicationId ?: '',
                        'versionCode': variant.versionCode ?: 0,
                        'versionName': variant.versionName ?: ''
                    ]
                    allVariants << vInfo
                }
            } catch (e) {
                // Try library variants
                try {
                    android.libraryVariants?.each { variant ->
                        def vInfo = [
                            'name': variant.name,
                            'buildType': variant.buildType.name,
                            'flavorName': variant.flavorName ?: '',
                            'isDebuggable': variant.buildType.debuggable
                        ]
                        allVariants << vInfo
                    }
                } catch (e2) { /* ignore */ }
            }

            // Source sets
            try {
                def srcSets = [:]
                android.sourceSets?.each { sourceSet ->
                    def srcInfo = [:]
                    srcInfo['name'] = sourceSet.name
                    srcInfo['java'] = sourceSet.java.srcDirs.collect { it.absolutePath }
                    srcInfo['kotlin'] = sourceSet.kotlin?.srcDirs?.collect { it.absolutePath } ?: []
                    srcInfo['res'] = sourceSet.res.srcDirs.collect { it.absolutePath }
                    srcInfo['assets'] = sourceSet.assets.srcDirs.collect { it.absolutePath }
                    srcInfo['jniLibs'] = sourceSet.jniLibs.srcDirs.collect { it.absolutePath }
                    srcInfo['manifest'] = sourceSet.manifest?.srcFile?.absolutePath ?: ''
                    srcSets[sourceSet.name] = srcInfo
                }
                moduleInfo['sourceSets'] = srcSets
            } catch (e) { /* ignore */ }

            // NDK version
            moduleInfo['ndkVersion'] = android.ndkVersion ?: ''

            // Build features
            try {
                def features = [:]
                features['viewBinding'] = android.buildFeatures?.viewBinding ?: false
                features['dataBinding'] = android.buildFeatures?.dataBinding ?: false
                features['compose'] = android.buildFeatures?.compose ?: false
                features['buildConfig'] = android.buildFeatures?.buildConfig ?: false
                moduleInfo['buildFeatures'] = features
            } catch (e) { /* ignore */ }

            // Compose
            try {
                def compose = android.composeOptions
                if (compose != null) {
                    moduleInfo['composeCompilerVersion'] = compose.kotlinCompilerExtensionVersion ?: ''
                }
            } catch (e) { /* ignore */ }

            // Native build
            try {
                if (android.hasProperty('externalNativeBuild')) {
                    def nativeBuild = [:]
                    android.externalNativeBuild?.cmake?.each { cmake ->
                        nativeBuild['cmakePath'] = cmake.path ?: ''
                    }
                    android.externalNativeBuild?.ndkBuild?.each { ndk ->
                        nativeBuild['ndkBuildPath'] = ndk.path ?: ''
                    }
                    moduleInfo['nativeBuild'] = nativeBuild
                }
            } catch (e) { /* ignore */ }

            // Determine app vs library
            moduleInfo['isApplication'] = project.plugins.hasPlugin('com.android.application')
            moduleInfo['isLibrary'] = project.plugins.hasPlugin('com.android.library')

        } catch (Exception e) {
            moduleInfo['androidConfigError'] = e.message
        }
    }

    void collectModuleDependencies(project, moduleName) {
        try {
            project.configurations.each { config ->
                if (config.name == 'annotationProcessor' || config.name == 'kapt') return
                try {
                    config.dependencies.each { dep ->
                        if (dep instanceof ProjectDependency) return
                        if (dep.hasProperty('group') && dep.hasProperty('name')) {
                            def depInfo = [
                                'module': moduleName,
                                'configuration': config.name,
                                'group': dep.group ?: '',
                                'name': dep.name ?: '',
                                'version': dep.version ?: 'latest'
                            ]
                            allDependencies << depInfo
                        }
                    }
                } catch (e) { /* ignore unresolvable configurations */ }
            }
        } catch (e) { /* ignore */ }
    }

    void collectRepositories(project) {
        try {
            project.buildscript?.repositories?.each { repo ->
                def repoInfo = [:]
                if (repo.hasProperty('url')) {
                    repoInfo['url'] = repo.url.toString()
                }
                repoInfo['type'] = repo.class.simpleName
                if (!allRepositories.any { it['url'] == repoInfo['url'] }) {
                    allRepositories << repoInfo
                }
            }
        } catch (e) { /* ignore */ }

        try {
            project.repositories?.each { repo ->
                def repoInfo = [:]
                if (repo.hasProperty('url')) {
                    repoInfo['url'] = repo.url.toString()
                }
                repoInfo['type'] = repo.class.simpleName
                if (!allRepositories.any { it['url'] == repoInfo['url'] }) {
                    allRepositories << repoInfo
                }
            }
        } catch (e) { /* ignore */ }
    }

    String toJson() {
        return new JsonBuilder([
            'rootProject': rootInfo,
            'modules': modulesInfo,
            'dependencies': allDependencies,
            'plugins': allPlugins,
            'buildTypes': allBuildTypes,
            'signingConfigs': allSigningConfigs,
            'productFlavors': allProductFlavors,
            'variants': allVariants,
            'repositories': allRepositories,
            'gradle': gradleInfo
        ]).toPrettyString()
    }
}

// Main collection
gradle.projectsLoaded {
    System.err.println("MobileStudio Tooling: Collecting project model...")

    def collector = new MobileStudioModelCollector()

    // Collect root project info
    collector.collectRootProject(rootProject)

    // Collect all modules
    rootProject.allprojects { project ->
        try {
            collector.collectModule(project)
        } catch (Exception e) {
            System.err.println("MobileStudio Tooling: Failed to collect module " + project.path + ": " + e.message)
        }
        collector.collectRepositories(project)
    }

    // Output model as JSON between markers
    System.err.println("MobileStudio Model Start")
    println collector.toJson()
    System.err.println("MobileStudio Model End")

    System.err.println("MobileStudio Tooling: Model collection complete (" + collector.modulesInfo.size() + " modules)")
}
        """.trimIndent())

        initScript.setReadable(true, false)
        return initScript
    }

    /**
     * Gradle 모델 수집 실행
     */
    private suspend fun collectGradleModel(
        projectDir: File,
        gradleRunner: GradleRunner,
        initScript: File
    ): String = suspendCancellableCoroutine { continuation ->
        val output = StringBuilder()
        var collecting = false
        var completed = false

        gradleRunner.run(
            projectDir = projectDir,
            tasks = listOf("--init-script", initScript.absolutePath, "help", "-q"),
            onLine = { line, _ ->
                if (completed) return@run
                // The markers are on stderr (--quiet suppresses them from stdout),
                // but the JSON itself is on stdout. Collect everything that looks like JSON.
                if (line.trim() == "{") {
                    collecting = true
                    output.setLength(0)
                    output.appendLine(line)
                    return@run
                }
                if (collecting) {
                    output.appendLine(line)
                    if (line.trim() == "}") {
                        collecting = false
                    }
                }
            },
            onDone = { result ->
                if (!completed) {
                    completed = true
                    continuation.resume(output.toString().trim()) {}
                }
            }
        )

        continuation.invokeOnCancellation {
            completed = true
        }
    }

    /**
     * Gradle 모델 JSON 파싱
     */
    private fun parseGradleModel(jsonOutput: String, projectDir: File): AndroidProjectModel? {
        return try {
            val jsonStart = jsonOutput.indexOf("{")
            val jsonEnd = jsonOutput.lastIndexOf("}") + 1
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No JSON found in Gradle output")
                return null
            }

            val jsonString = jsonOutput.substring(jsonStart, jsonEnd)
            val json = JSONObject(jsonString)

            val rootProject = json.optJSONObject("rootProject") ?: JSONObject()
            val modules = json.optJSONArray("modules") ?: JSONArray()
            val dependencies = json.optJSONArray("dependencies") ?: JSONArray()
            val allPlugins = json.optJSONArray("plugins") ?: JSONArray()
            val buildTypes = json.optJSONArray("buildTypes") ?: JSONArray()
            val signingConfigs = json.optJSONArray("signingConfigs") ?: JSONArray()
            val productFlavors = json.optJSONArray("productFlavors") ?: JSONArray()
            val variants = json.optJSONArray("variants") ?: JSONArray()
            val gradleInfo = json.optJSONObject("gradle") ?: JSONObject()

            // Find the app module (or first Android module)
            var appModule: JSONObject? = null
            for (i in 0 until modules.length()) {
                val mod = modules.getJSONObject(i)
                if (mod.optBoolean("isApplication", false)) {
                    appModule = mod
                    break
                }
            }
            if (appModule == null && modules.length() > 0) {
                // Fallback: use first Android module or first module
                for (i in 0 until modules.length()) {
                    val mod = modules.getJSONObject(i)
                    if (mod.optBoolean("isAndroid", false)) {
                        appModule = mod
                        break
                    }
                }
                if (appModule == null) appModule = modules.getJSONObject(0)
            }

            val project = appModule ?: rootProject

            // Parse variants
            val availableVariants = mutableListOf<AndroidProjectModel.BuildVariant>()
            for (i in 0 until variants.length()) {
                val v = variants.getJSONObject(i)
                availableVariants.add(
                    AndroidProjectModel.BuildVariant(
                        name = v.optString("name", ""),
                        type = v.optString("buildType", if (v.optString("name", "").contains("debug")) "debug" else "release"),
                        flavor = v.optString("flavorName", ""),
                        isDebug = v.optBoolean("isDebuggable", v.optString("name", "").contains("debug"))
                    )
                )
            }

            // Parse dependencies
            val depList = mutableListOf<AndroidProjectModel.ParsedDependency>()
            for (i in 0 until dependencies.length()) {
                val dep = dependencies.getJSONObject(i)
                depList.add(
                    AndroidProjectModel.ParsedDependency(
                        configuration = dep.optString("configuration", ""),
                        group = dep.optString("group", ""),
                        artifact = dep.optString("name", ""),
                        version = dep.optString("version", "")
                    )
                )
            }

            // Parse build types
            val buildTypeMap = mutableMapOf<String, AndroidProjectModel.BuildTypeInfo>()
            for (i in 0 until buildTypes.length()) {
                val bt = buildTypes.getJSONObject(i)
                val name = bt.optString("name", "")
                buildTypeMap[name] = AndroidProjectModel.BuildTypeInfo(
                    name = name,
                    isDebuggable = bt.optBoolean("debuggable", false),
                    isMinifyEnabled = bt.optBoolean("minifyEnabled", false),
                    isShrinkResources = bt.optBoolean("shrinkResources", false),
                    signingConfig = bt.optString("signingConfig", null)
                )
            }

            // Parse signing configs
            val signingConfigMap = mutableMapOf<String, AndroidProjectModel.SigningConfigInfo>()
            for (i in 0 until signingConfigs.length()) {
                val sc = signingConfigs.getJSONObject(i)
                val name = sc.optString("name", "")
                signingConfigMap[name] = AndroidProjectModel.SigningConfigInfo(
                    name = name,
                    storeFile = sc.optString("storeFile", ""),
                    keyAlias = sc.optString("keyAlias", "")
                )
            }

            // Parse product flavors
            val flavorList = mutableListOf<AndroidProjectModel.ProductFlavor>()
            for (i in 0 until productFlavors.length()) {
                val f = productFlavors.getJSONObject(i)
                flavorList.add(
                    AndroidProjectModel.ProductFlavor(
                        name = f.optString("name", ""),
                        dimension = f.optString("dimension", ""),
                        applicationIdSuffix = f.optString("applicationIdSuffix", null),
                        versionNameSuffix = f.optString("versionNameSuffix", null),
                        minSdk = f.optInt("minSdk", 0).takeIf { it > 0 },
                        targetSdk = f.optInt("targetSdk", 0).takeIf { it > 0 }
                    )
                )
            }

            // Parse source sets
            val sourceSetMap = mutableMapOf<String, AndroidProjectModel.SourceSetInfo>()
            val sourceSetsJson = project.optJSONObject("sourceSets")
            if (sourceSetsJson != null) {
                for (key in sourceSetsJson.keys()) {
                    val ss = sourceSetsJson.getJSONObject(key)
                    val javaDirs = mutableListOf<String>()
                    ss.optJSONArray("java")?.let { arr ->
                        for (j in 0 until arr.length()) javaDirs.add(arr.getString(j))
                    }
                    val resDirs = mutableListOf<String>()
                    ss.optJSONArray("res")?.let { arr ->
                        for (j in 0 until arr.length()) resDirs.add(arr.getString(j))
                    }
                    val assetsDirs = mutableListOf<String>()
                    ss.optJSONArray("assets")?.let { arr ->
                        for (j in 0 until arr.length()) assetsDirs.add(arr.getString(j))
                    }
                    val jniLibsDirs = mutableListOf<String>()
                    ss.optJSONArray("jniLibs")?.let { arr ->
                        for (j in 0 until arr.length()) jniLibsDirs.add(arr.getString(j))
                    }
                    sourceSetMap[key] = AndroidProjectModel.SourceSetInfo(
                        name = key,
                        javaDirs = javaDirs,
                        resDirs = resDirs,
                        assetsDirs = assetsDirs,
                        jniLibsDirs = jniLibsDirs,
                        manifest = ss.optString("manifest", null)
                    )
                }
            }

            // Parse plugins
            val pluginNames = mutableListOf<String>()
            for (i in 0 until allPlugins.length()) {
                val p = allPlugins.getJSONObject(i)
                pluginNames.add(p.optString("id", ""))
            }

            // Build features
            val buildFeatures = project.optJSONObject("buildFeatures")
            val isViewBinding = buildFeatures?.optBoolean("viewBinding", false) ?: false
            val isDataBinding = buildFeatures?.optBoolean("dataBinding", false) ?: false
            val isCompose = buildFeatures?.optBoolean("compose", false) ?: false

            // Aggregate module count info
            val moduleCount = modules.length()
            val androidModuleCount = (0 until modules.length()).count {
                modules.getJSONObject(it).optBoolean("isAndroid", false)
            }

            AndroidProjectModel(
                packageName = project.optString("namespace", ""),
                applicationId = project.optString("applicationId", ""),
                namespace = project.optString("namespace", ""),
                compileSdk = project.optInt("compileSdk", 0),
                minSdk = project.optInt("minSdk", 0),
                targetSdk = project.optInt("targetSdk", 0),
                versionCode = project.optInt("versionCode", 0),
                versionName = project.optString("versionName", ""),
                buildToolsVersion = "",
                ndkVersion = project.optString("ndkVersion", ""),
                AGPVersion = pluginNames.firstOrNull { it.contains("com.android") }?.substringAfterLast(".") ?: "",
                kotlinVersion = gradleInfo.optString("gradleVersion", ""),
                isKotlinApplied = pluginNames.any { it.contains("kotlin") },
                isComposeEnabled = isCompose || pluginNames.any { it.contains("compose") },
                isViewBindingEnabled = isViewBinding,
                isDataBindingEnabled = isDataBinding,
                hasNativeBuild = project.optJSONObject("nativeBuild") != null,
                hasTestRunner = project.optString("testInstrumentationRunner", "").isNotEmpty(),
                availableVariants = availableVariants,
                productFlavors = flavorList,
                sourceSets = sourceSetMap,
                plugins = pluginNames,
                signingConfigs = signingConfigMap,
                buildTypes = buildTypeMap,
                dependencies = depList,
                propertiesFromGradle = mapOf(
                    "moduleCount" to moduleCount.toString(),
                    "androidModuleCount" to androidModuleCount.toString(),
                    "gradleVersion" to gradleInfo.optString("gradleVersion", "")
                ),
                lastSyncTime = System.currentTimeMillis(),
                syncSuccess = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gradle model", e)
            null
        }
    }
}
