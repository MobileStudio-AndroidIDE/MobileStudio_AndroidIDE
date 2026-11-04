package com.aide.mobilestudio

import java.io.File

/**
 * Android 프로젝트 메타데이터 모델
 * build.gradle 파싱 + :app:properties Gradle 결과 종합
 */
data class AndroidProjectModel(
    val packageName: String = "",
    val applicationId: String = "",
    val namespace: String = "",
    val compileSdk: Int = 0,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val versionCode: Int = 0,
    val versionName: String = "",
    val buildToolsVersion: String = "",
    val ndkVersion: String = "",
    val AGPVersion: String = "",
    val kotlinVersion: String = "",
    val isKotlinApplied: Boolean = false,
    val isComposeEnabled: Boolean = false,
    val isViewBindingEnabled: Boolean = false,
    val isDataBindingEnabled: Boolean = false,
    val hasNativeBuild: Boolean = false,
    val hasTestRunner: Boolean = false,
    val availableVariants: List<BuildVariant> = emptyList(),
    val productFlavors: List<ProductFlavor> = emptyList(),
    val sourceSets: Map<String, SourceSetInfo> = emptyMap(),
    val plugins: List<String> = emptyList(),
    val signingConfigs: Map<String, SigningConfigInfo> = emptyMap(),
    val buildTypes: Map<String, BuildTypeInfo> = emptyMap(),
    val dependencies: List<ParsedDependency> = emptyList(),
    val propertiesFromGradle: Map<String, String> = emptyMap(),
    val lastSyncTime: Long = 0L,
    val syncSuccess: Boolean = false
) {
    val activeVariant: String get() = availableVariants.firstOrNull { it.isActive }?.name ?: "debug"
    val isDebug get() = activeVariant.contains("debug", ignoreCase = true)
    val isRelease get() = activeVariant.contains("release", ignoreCase = true)

    fun getVariantTasks(variant: String = activeVariant): VariantTasks {
        val type = if (variant.contains("release", ignoreCase = true)) "Release" else "Debug"
        val flavor = variant.removeSuffix("Debug").removeSuffix("Release")
        val flavorPrefix = if (flavor.isNotEmpty()) flavor else ""
        return VariantTasks(
            assemble = "assemble${flavorPrefix}${type}",
            bundle = "bundle${flavorPrefix}${type}",
            compileKotlin = "compile${flavorPrefix}${type}Kotlin",
            compileJava = "compile${flavorPrefix}${type}JavaWithJavac",
            processResources = "process${flavorPrefix}${type}Resources",
            processManifest = "process${flavorPrefix}${type}Manifest",
            mergeResources = "merge${flavorPrefix}${type}Resources",
            mergeAssets = "merge${flavorPrefix}${type}Assets",
            lint = "lint${flavorPrefix}${type}",
            test = "test${flavorPrefix}${type}UnitTest"
        )
    }

    fun hasPlugin(name: String): Boolean = plugins.any { it.contains(name) }

    fun getDependencyMap(): Map<String, List<ParsedDependency>> =
        dependencies.groupBy { it.configuration }

    data class BuildVariant(
        val name: String,
        val type: String,
        val flavor: String = "",
        val isActive: Boolean = false,
        val isDebug: Boolean = name.contains("debug", ignoreCase = true)
    )

    data class ProductFlavor(
        val name: String,
        val dimension: String = "",
        val applicationIdSuffix: String? = null,
        val versionNameSuffix: String? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null,
        val compileSdk: Int? = null
    )

    data class SourceSetInfo(
        val name: String,
        val javaDirs: List<String> = emptyList(),
        val resDirs: List<String> = emptyList(),
        val assetsDirs: List<String> = emptyList(),
        val jniLibsDirs: List<String> = emptyList(),
        val manifest: String? = null
    )

    data class BuildTypeInfo(
        val name: String,
        val isDebuggable: Boolean = false,
        val isMinifyEnabled: Boolean = false,
        val isShrinkResources: Boolean = false,
        val proguardFiles: List<String> = emptyList(),
        val signingConfig: String? = null
    )

    data class SigningConfigInfo(
        val name: String,
        val storeFile: String? = null,
        val storePassword: String? = null,
        val keyAlias: String? = null,
        val keyPassword: String? = null
    )

    data class ParsedDependency(
        val configuration: String,
        val group: String,
        val artifact: String,
        val version: String,
        val isBom: Boolean = false,
        val isPlatform: Boolean = false
    ) {
        val coordinates: String get() = "$group:$artifact:$version"
        val shortCoordinates: String get() = "$group:$artifact:$version"
    }

    data class VariantTasks(
        val assemble: String,
        val bundle: String,
        val compileKotlin: String,
        val compileJava: String,
        val processResources: String,
        val processManifest: String,
        val mergeResources: String,
        val mergeAssets: String,
        val lint: String,
        val test: String
    )

    companion object {
        fun fromGradleProperties(
            properties: Map<String, String>,
            gradleOutput: String? = null
        ): AndroidProjectModel {
            val props = mutableMapOf<String, String>()
            props.putAll(properties)

            // Parse :app:properties output
            gradleOutput?.let { parsePropertiesOutput(it, props) }

            return AndroidProjectModel(
                packageName = props["namespace"] ?: props["applicationId"] ?: "",
                applicationId = props["applicationId"] ?: "",
                namespace = props["namespace"] ?: "",
                compileSdk = props["android.compileSdkVersion"]?.toIntOrNull()
                    ?: props["compileSdkVersion"]?.toIntOrNull() ?: 0,
                minSdk = props["android.minSdkVersion"]?.toIntOrNull()
                    ?: props["minSdkVersion"]?.toIntOrNull() ?: 0,
                targetSdk = props["android.targetSdkVersion"]?.toIntOrNull()
                    ?: props["targetSdkVersion"]?.toIntOrNull() ?: 0,
                versionCode = props["android.versionCode"]?.toIntOrNull()
                    ?: props["versionCode"]?.toIntOrNull() ?: 0,
                versionName = props["android.versionName"] ?: props["versionName"] ?: "",
                buildToolsVersion = props["android.buildToolsVersion"] ?: "",
                ndkVersion = props["android.ndkVersion"] ?: "",
                isKotlinApplied = props["kotlin.jvm.target"] != null ||
                    props["kotlin.android.extensions"] != null,
                isComposeEnabled = props["android.composeCompilerExtensionVersion"] != null ||
                    props["kotlin.compose.compiler"] != null,
                isViewBindingEnabled = props["android.viewBinding"]?.toBooleanStrictOrNull() == true,
                isDataBindingEnabled = props["android.dataBinding"]?.toBooleanStrictOrNull() == true,
                hasNativeBuild = props["android.externalNativeBuild"] != null ||
                    props["android.cmakeVersion"] != null,
                hasTestRunner = props["android.testInstrumentationRunner"] != null,
                AGPVersion = props["android.tools.build.gradle.version"] ?: "",
                kotlinVersion = props["kotlin.version"] ?: "",
                propertiesFromGradle = props.toMap(),
                lastSyncTime = System.currentTimeMillis()
            )
        }

        private fun parsePropertiesOutput(output: String, props: MutableMap<String, String>) {
            // :app:properties outputs "key=value" lines
            output.lines().forEach { line ->
                val eqIndex = line.indexOf('=')
                if (eqIndex > 0) {
                    val key = line.substring(0, eqIndex).trim()
                    val value = line.substring(eqIndex + 1).trim()
                    if (value.isNotEmpty() && value != "null") {
                        props[key] = value
                    }
                }
            }
        }
    }
}

private fun String?.toBooleanStrictOrNull(): Boolean? = when (this?.lowercase()?.trim()) {
    "true", "yes", "1" -> true
    "false", "no", "0" -> false
    else -> null
}
