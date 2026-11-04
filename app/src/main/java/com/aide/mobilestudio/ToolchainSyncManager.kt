package com.aide.mobilestudio

import com.aide.mobilestudio.CompatibilityChecker.RecommendedSet
import java.io.File

object ToolchainSyncManager {

    fun syncNdkVersion(projectDir: File, newNdkVersion: String) {
        val buildFiles = mutableListOf<File>()
        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", ".git", "node_modules", ".cxx", "captures", "out") }
            .forEach {
                if (it.name == "build.gradle" || it.name == "build.gradle.kts") {
                    buildFiles.add(it)
                }
            }

        for (file in buildFiles) {
            val content = file.readText()
            val ndkRegex = if (file.name.endsWith(".kts")) {
                Regex("""ndkVersion\s*=\s*".*"""")
            } else {
                Regex("""ndkVersion\s*".*"""")
            }

            if (ndkRegex.containsMatchIn(content)) {
                val newContent = content.replace(ndkRegex, if (file.name.endsWith(".kts")) "ndkVersion = \"$newNdkVersion\"" else "ndkVersion \"$newNdkVersion\"")
                if (content != newContent) {
                    file.writeText(newContent)
                }
            }
        }
    }

    fun syncAllVersions(projectDir: File, recommended: RecommendedSet) {
        syncNdkVersion(projectDir, recommended.ndk)
        syncGradleWrapperVersion(projectDir, recommended.gradle)
        val newAgp = agpVersionForGradle(recommended.gradle)
        syncAgpVersion(projectDir, newAgp)
        syncKotlinVersion(projectDir, kotlinVersionForGradle(recommended.gradle))
    }

    fun syncGradleWrapperVersion(projectDir: File, newGradleVersion: String) {
        // Also sync Gradle version in gradle-wrapper.properties
        val wrapperProps = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (wrapperProps.exists()) {
            val content = wrapperProps.readText()
            val gradleRegex = Regex("""distributionUrl=.*gradle-(.*)-(bin|all)\.zip""")
            val match = gradleRegex.find(content)
            if (match != null) {
                val currentVer = match.groupValues[1]
                val newVer = newGradleVersion.removePrefix("gradle-")
                if (currentVer != newVer) {
                    val newContent = content.replace(gradleRegex, "distributionUrl=https\\://services.gradle.org/distributions/gradle-$newVer-all.zip")
                    wrapperProps.writeText(newContent)
                }
            }
        }
    }

    fun agpVersionForGradle(gradleVersion: String): String {
        val version = gradleVersion.removePrefix("gradle-")
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 14
        return when {
            major >= 9 -> "9.3.0"
            major == 8 && minor >= 14 -> "8.13.0"
            major == 8 && minor >= 2 -> "8.$minor.0"
            else -> "8.2.0"
        }
    }

    fun kotlinVersionForGradle(gradleVersion: String): String {
        val agp = agpVersionForGradle(gradleVersion)
        return when {
            agp.startsWith("9.") -> "2.1.0"
            agp.startsWith("8.13") -> "2.0.20"
            else -> "2.0.0"
        }
    }

    fun syncKotlinVersion(projectDir: File, newKotlinVersion: String) {
        val libsToml = File(projectDir, "gradle/libs.versions.toml")
        if (libsToml.exists()) {
            val content = libsToml.readText()
            val kotlinRegex = Regex("""(?m)^([ \t]*kotlin[ \t]*=[ \t]*")[^"]*(")""")
            val newContent = kotlinRegex.replace(content) { match ->
                val prefix = match.groupValues[1]
                val suffix = match.groupValues[2]
                "$prefix$newKotlinVersion$suffix"
            }
            if (newContent != content) {
                libsToml.writeText(newContent)
            }
        }

        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", ".git", "node_modules", ".cxx", "captures", "out") }
            .forEach { file ->
                if (file.name !in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) return@forEach
                val content = file.readText()
                val pluginRegex = Regex("""(id\s*[("']org\.jetbrains\.kotlin\.android[")']\s*(?:version\s*)?[("'])[^"']*([")'])""")
                val newContent = pluginRegex.replace(content) { match ->
                    match.groupValues[1] + newKotlinVersion + match.groupValues[2]
                }
                if (newContent != content) {
                    file.writeText(newContent)
                }
            }
    }

    /** Raise the AGP version to match the (new) Gradle version:
     *  - gradle/libs.versions.toml [versions] agp = "..."
     *  - AGP plugin declarations in build.gradle.kts / build.gradle / settings.gradle.kts */
    fun syncAgpVersion(projectDir: File, newAgpVersion: String) {
        val libsToml = File(projectDir, "gradle/libs.versions.toml")
        if (libsToml.exists()) {
            val content = libsToml.readText()
            val agpRegex = Regex("""(?m)^([ \t]*agp[ \t]*=[ \t]*")[^"]*(")""")
            val newContent = agpRegex.replace(content) { match ->
                val prefix = match.groupValues[1]
                val suffix = match.groupValues[2]
                "$prefix$newAgpVersion$suffix"
            }
            if (newContent != content) {
                libsToml.writeText(newContent)
            }
        }

        projectDir.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", ".git", "node_modules", ".cxx", "captures", "out") }
            .forEach { file ->
                if (file.name !in setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) return@forEach
                val content = file.readText()
                val pluginRegex = Regex("""(id\s*[("']com\.android\.application[")']\s*(?:version\s*)?[("'])[^"']*([")'])""")
                val newContent = pluginRegex.replace(content) { match ->
                    match.groupValues[1] + newAgpVersion + match.groupValues[2]
                }
                if (newContent != content) {
                    file.writeText(newContent)
                }
            }
    }
}
