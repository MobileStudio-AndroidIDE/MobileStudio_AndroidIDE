package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * GradleCacheManager - Caches Gradle project model to avoid redundant syncs
 *
 * Detects changes in:
 * - build.gradle / build.gradle.kts
 * - settings.gradle / settings.gradle.kts
 * - gradle.properties
 * - libs.versions.toml
 * - Gradle/AGP/Kotlin versions
 * - SDK configuration
 *
 * When no changes detected, returns cached model instantly.
 */
class GradleCacheManager(private val context: Context) {

    companion object {
        private const val TAG = "GradleCacheManager"
        private const val CACHE_DIR = "gradle_sync_cache"
        private const val MODEL_CACHE_FILE = "project_model.json"
        private const val FINGERPRINT_FILE = "fingerprint.json"
        private const val MAX_CACHE_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    private val cacheDir = File(context.filesDir, CACHE_DIR)

    init {
        cacheDir.mkdirs()
    }

    data class CacheFingerprint(
        val buildGradleHash: String = "",
        val settingsGradleHash: String = "",
        val gradlePropertiesHash: String = "",
        val versionCatalogHash: String = "",
        val gradleVersion: String = "",
        val agpVersion: String = "",
        val kotlinVersion: String = "",
        val sdkVersion: String = "",
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("buildGradleHash", buildGradleHash)
            put("settingsGradleHash", settingsGradleHash)
            put("gradlePropertiesHash", gradlePropertiesHash)
            put("versionCatalogHash", versionCatalogHash)
            put("gradleVersion", gradleVersion)
            put("agpVersion", agpVersion)
            put("kotlinVersion", kotlinVersion)
            put("sdkVersion", sdkVersion)
            put("timestamp", timestamp)
        }

        companion object {
            fun fromJson(json: JSONObject): CacheFingerprint = CacheFingerprint(
                buildGradleHash = json.optString("buildGradleHash", ""),
                settingsGradleHash = json.optString("settingsGradleHash", ""),
                gradlePropertiesHash = json.optString("gradlePropertiesHash", ""),
                versionCatalogHash = json.optString("versionCatalogHash", ""),
                gradleVersion = json.optString("gradleVersion", ""),
                agpVersion = json.optString("agpVersion", ""),
                kotlinVersion = json.optString("kotlinVersion", ""),
                sdkVersion = json.optString("sdkVersion", ""),
                timestamp = json.optLong("timestamp", 0)
            )
        }
    }

    /**
     * Check if sync is needed by comparing fingerprints
     */
    fun isSyncNeeded(projectDir: File, currentFingerprint: CacheFingerprint): Boolean {
        val cached = loadFingerprint(projectDir) ?: return true

        if (System.currentTimeMillis() - cached.timestamp > MAX_CACHE_AGE_MS) {
            Log.d(TAG, "Cache expired")
            return true
        }

        val changed = cached.buildGradleHash != currentFingerprint.buildGradleHash ||
                cached.settingsGradleHash != currentFingerprint.settingsGradleHash ||
                cached.gradlePropertiesHash != currentFingerprint.gradlePropertiesHash ||
                cached.versionCatalogHash != currentFingerprint.versionCatalogHash ||
                cached.gradleVersion != currentFingerprint.gradleVersion ||
                cached.agpVersion != currentFingerprint.agpVersion ||
                cached.kotlinVersion != currentFingerprint.kotlinVersion ||
                cached.sdkVersion != currentFingerprint.sdkVersion

        if (changed) {
            Log.d(TAG, "Project configuration changed, sync needed")
        } else {
            Log.d(TAG, "No changes detected, using cached model")
        }

        return changed
    }

    /**
     * Generate fingerprint from current project state
     */
    fun generateFingerprint(projectDir: File, gradleVersion: String = "", agpVersion: String = "", kotlinVersion: String = ""): CacheFingerprint {
        return CacheFingerprint(
            buildGradleHash = hashFile(File(projectDir, "build.gradle")) +
                    hashFile(File(projectDir, "build.gradle.kts")) +
                    hashFile(File(projectDir, "app/build.gradle")) +
                    hashFile(File(projectDir, "app/build.gradle.kts")),
            settingsGradleHash = hashFile(File(projectDir, "settings.gradle")) +
                    hashFile(File(projectDir, "settings.gradle.kts")),
            gradlePropertiesHash = hashFile(File(projectDir, "gradle.properties")),
            versionCatalogHash = hashFile(File(projectDir, "gradle/libs.versions.toml")),
            gradleVersion = gradleVersion,
            agpVersion = agpVersion,
            kotlinVersion = kotlinVersion,
            sdkVersion = resolveSdkVersion(projectDir),
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Save project model to cache
     */
    fun saveModel(projectDir: File, model: AndroidProjectModel, fingerprint: CacheFingerprint) {
        try {
            val projectCacheDir = getProjectCacheDir(projectDir)
            projectCacheDir.mkdirs()

            // Save model as JSON
            val modelFile = File(projectCacheDir, MODEL_CACHE_FILE)
            modelFile.writeText(modelToJson(model).toString(2))

            // Save fingerprint
            val fpFile = File(projectCacheDir, FINGERPRINT_FILE)
            fpFile.writeText(fingerprint.toJson().toString(2))

            Log.i(TAG, "Model cached for ${projectDir.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache model", e)
        }
    }

    /**
     * Load cached project model
     */
    fun loadModel(projectDir: File): AndroidProjectModel? {
        try {
            val projectCacheDir = getProjectCacheDir(projectDir)
            val modelFile = File(projectCacheDir, MODEL_CACHE_FILE)
            if (!modelFile.exists()) return null

            val json = JSONObject(modelFile.readText())
            return modelFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached model", e)
            return null
        }
    }

    /**
     * Load cached fingerprint
     */
    fun loadFingerprint(projectDir: File): CacheFingerprint? {
        try {
            val projectCacheDir = getProjectCacheDir(projectDir)
            val fpFile = File(projectCacheDir, FINGERPRINT_FILE)
            if (!fpFile.exists()) return null

            val json = JSONObject(fpFile.readText())
            return CacheFingerprint.fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load fingerprint", e)
            return null
        }
    }

    /**
     * Clear cache for a project
     */
    fun clearCache(projectDir: File) {
        val projectCacheDir = getProjectCacheDir(projectDir)
        if (projectCacheDir.exists()) {
            projectCacheDir.deleteRecursively()
            Log.i(TAG, "Cache cleared for ${projectDir.name}")
        }
    }

    /**
     * Clear all caches
     */
    fun clearAllCaches() {
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            Log.i(TAG, "All sync caches cleared")
        }
    }

    private fun getProjectCacheDir(projectDir: File): File {
        val projectHash = md5(projectDir.absolutePath)
        return File(cacheDir, projectHash)
    }

    private fun hashFile(file: File): String {
        if (!file.exists()) return ""
        return try {
            val content = file.readText()
            md5(content)
        } catch (e: Exception) {
            ""
        }
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun resolveSdkVersion(projectDir: File): String {
        val localProps = File(projectDir, "local.properties")
        if (!localProps.exists()) return ""
        return try {
            localProps.readText().lines()
                .firstOrNull { it.startsWith("sdk.dir") }
                ?.substringAfter("=") ?: ""
        } catch (e: Exception) { "" }
    }

    private fun modelToJson(model: AndroidProjectModel): JSONObject {
        return JSONObject().apply {
            put("packageName", model.packageName)
            put("applicationId", model.applicationId)
            put("namespace", model.namespace)
            put("compileSdk", model.compileSdk)
            put("minSdk", model.minSdk)
            put("targetSdk", model.targetSdk)
            put("versionCode", model.versionCode)
            put("versionName", model.versionName)
            put("buildToolsVersion", model.buildToolsVersion)
            put("ndkVersion", model.ndkVersion)
            put("AGPVersion", model.AGPVersion)
            put("kotlinVersion", model.kotlinVersion)
            put("isKotlinApplied", model.isKotlinApplied)
            put("isComposeEnabled", model.isComposeEnabled)
            put("isViewBindingEnabled", model.isViewBindingEnabled)
            put("isDataBindingEnabled", model.isDataBindingEnabled)
            put("hasNativeBuild", model.hasNativeBuild)
            put("hasTestRunner", model.hasTestRunner)
            put("lastSyncTime", model.lastSyncTime)
            put("syncSuccess", model.syncSuccess)

            // Variants
            val variantsArr = JSONArray()
            model.availableVariants.forEach { v ->
                variantsArr.put(JSONObject().apply {
                    put("name", v.name)
                    put("type", v.type)
                    put("flavor", v.flavor)
                    put("isActive", v.isActive)
                    put("isDebug", v.isDebug)
                })
            }
            put("availableVariants", variantsArr)

            // Product flavors
            val flavorsArr = JSONArray()
            model.productFlavors.forEach { f ->
                flavorsArr.put(JSONObject().apply {
                    put("name", f.name)
                    put("dimension", f.dimension)
                })
            }
            put("productFlavors", flavorsArr)

            // Source sets
            val srcSetsObj = JSONObject()
            model.sourceSets.forEach { (name, ss) ->
                srcSetsObj.put(name, JSONObject().apply {
                    put("name", ss.name)
                    put("javaDirs", JSONArray(ss.javaDirs))
                    put("resDirs", JSONArray(ss.resDirs))
                    put("assetsDirs", JSONArray(ss.assetsDirs))
                    put("jniLibsDirs", JSONArray(ss.jniLibsDirs))
                    put("manifest", ss.manifest ?: "")
                })
            }
            put("sourceSets", srcSetsObj)

            // Plugins
            put("plugins", JSONArray(model.plugins))

            // Build types
            val btObj = JSONObject()
            model.buildTypes.forEach { (name, bt) ->
                btObj.put(name, JSONObject().apply {
                    put("name", bt.name)
                    put("isDebuggable", bt.isDebuggable)
                    put("isMinifyEnabled", bt.isMinifyEnabled)
                    put("isShrinkResources", bt.isShrinkResources)
                })
            }
            put("buildTypes", btObj)

            // Dependencies
            val depsArr = JSONArray()
            model.dependencies.forEach { dep ->
                depsArr.put(JSONObject().apply {
                    put("configuration", dep.configuration)
                    put("group", dep.group)
                    put("artifact", dep.artifact)
                    put("version", dep.version)
                })
            }
            put("dependencies", depsArr)

            // Properties
            val propsObj = JSONObject()
            model.propertiesFromGradle.forEach { (k, v) -> propsObj.put(k, v) }
            put("propertiesFromGradle", propsObj)
        }
    }

    private fun modelFromJson(json: JSONObject): AndroidProjectModel {
        val variants = mutableListOf<AndroidProjectModel.BuildVariant>()
        json.optJSONArray("availableVariants")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                variants.add(AndroidProjectModel.BuildVariant(
                    name = v.optString("name"),
                    type = v.optString("type"),
                    flavor = v.optString("flavor"),
                    isActive = v.optBoolean("isActive"),
                    isDebug = v.optBoolean("isDebug")
                ))
            }
        }

        val flavors = mutableListOf<AndroidProjectModel.ProductFlavor>()
        json.optJSONArray("productFlavors")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                flavors.add(AndroidProjectModel.ProductFlavor(
                    name = f.optString("name"),
                    dimension = f.optString("dimension")
                ))
            }
        }

        val sourceSets = mutableMapOf<String, AndroidProjectModel.SourceSetInfo>()
        json.optJSONObject("sourceSets")?.let { obj ->
            for (key in obj.keys()) {
                val ss = obj.getJSONObject(key)
                sourceSets[key] = AndroidProjectModel.SourceSetInfo(
                    name = ss.optString("name"),
                    javaDirs = ss.optJSONArray("javaDirs")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    resDirs = ss.optJSONArray("resDirs")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    assetsDirs = ss.optJSONArray("assetsDirs")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    jniLibsDirs = ss.optJSONArray("jniLibsDirs")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    manifest = ss.optString("manifest", null)
                )
            }
        }

        val plugins = mutableListOf<String>()
        json.optJSONArray("plugins")?.let { arr ->
            for (i in 0 until arr.length()) plugins.add(arr.getString(i))
        }

        val buildTypes = mutableMapOf<String, AndroidProjectModel.BuildTypeInfo>()
        json.optJSONObject("buildTypes")?.let { obj ->
            for (key in obj.keys()) {
                val bt = obj.getJSONObject(key)
                buildTypes[key] = AndroidProjectModel.BuildTypeInfo(
                    name = bt.optString("name"),
                    isDebuggable = bt.optBoolean("isDebuggable"),
                    isMinifyEnabled = bt.optBoolean("isMinifyEnabled"),
                    isShrinkResources = bt.optBoolean("isShrinkResources")
                )
            }
        }

        val dependencies = mutableListOf<AndroidProjectModel.ParsedDependency>()
        json.optJSONArray("dependencies")?.let { arr ->
            for (i in 0 until arr.length()) {
                val dep = arr.getJSONObject(i)
                dependencies.add(AndroidProjectModel.ParsedDependency(
                    configuration = dep.optString("configuration"),
                    group = dep.optString("group"),
                    artifact = dep.optString("artifact"),
                    version = dep.optString("version")
                ))
            }
        }

        val props = mutableMapOf<String, String>()
        json.optJSONObject("propertiesFromGradle")?.let { obj ->
            for (key in obj.keys()) props[key] = obj.getString(key)
        }

        return AndroidProjectModel(
            packageName = json.optString("packageName"),
            applicationId = json.optString("applicationId"),
            namespace = json.optString("namespace"),
            compileSdk = json.optInt("compileSdk"),
            minSdk = json.optInt("minSdk"),
            targetSdk = json.optInt("targetSdk"),
            versionCode = json.optInt("versionCode"),
            versionName = json.optString("versionName"),
            buildToolsVersion = json.optString("buildToolsVersion"),
            ndkVersion = json.optString("ndkVersion"),
            AGPVersion = json.optString("AGPVersion"),
            kotlinVersion = json.optString("kotlinVersion"),
            isKotlinApplied = json.optBoolean("isKotlinApplied"),
            isComposeEnabled = json.optBoolean("isComposeEnabled"),
            isViewBindingEnabled = json.optBoolean("isViewBindingEnabled"),
            isDataBindingEnabled = json.optBoolean("isDataBindingEnabled"),
            hasNativeBuild = json.optBoolean("hasNativeBuild"),
            hasTestRunner = json.optBoolean("hasTestRunner"),
            availableVariants = variants,
            productFlavors = flavors,
            sourceSets = sourceSets,
            plugins = plugins,
            buildTypes = buildTypes,
            dependencies = dependencies,
            propertiesFromGradle = props,
            lastSyncTime = json.optLong("lastSyncTime"),
            syncSuccess = json.optBoolean("syncSuccess")
        )
    }
}
