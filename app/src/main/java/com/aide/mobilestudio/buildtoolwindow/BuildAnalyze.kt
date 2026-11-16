package com.aide.mobilestudio.buildtoolwindow

import java.io.File

/**
 * Build Analyze - parses Gradle --profile reports and analyzes build performance.
 */
class BuildAnalyze(private val context: android.content.Context) {

    data class TaskProfile(
        val name: String,
        val startTime: Long,
        val endTime: Long,
        val duration: Long,
        val cacheHit: Boolean = false,
        val type: String = "UNKNOWN"
    )

    data class ProfileReport(
        val tasks: List<TaskProfile>,
        val totalTime: Long,
        val cacheHitRate: Double,
        val bottleneckTasks: List<TaskProfile>
    )

    private var lastProfileReport: ProfileReport? = null

    /**
     * Parses a Gradle --profile build report directory.
     * For now, generates mock data since parsing HTML requires Jsoup.
     */
    fun parseProfileReport(buildDir: File): ProfileReport? {
        val profileDir = File(buildDir, "reports/profile")
        if (!profileDir.exists()) {
            return null
        }

        val htmlFiles = profileDir.listFiles { f -> f.name.endsWith(".html") }
        if (htmlFiles.isEmpty()) {
            return null
        }

        // Mock data - real HTML parsing would require Jsoup
        val tasks = listOf(
            TaskProfile(":app:compileDebugKotlin", 0, 15000, 15000, false, "COMPILE"),
            TaskProfile(":app:compileDebugJavaWithJavac", 15000, 25000, 10000, false, "COMPILE"),
            TaskProfile(":app:mergeDebugResources", 25000, 35000, 10000, true, "RESOURCE"),
            TaskProfile(":app:processDebugManifest", 35000, 40000, 5000, true, "PROCESS"),
            TaskProfile(":app:mergeDebugShaders", 40000, 45000, 5000, false, "MERGE"),
            TaskProfile(":app:compileDebugAidl", 45000, 47000, 2000, false, "COMPILE"),
            TaskProfile(":app:generateDebugBuildConfig", 47000, 49000, 2000, true, "GENERATE"),
            TaskProfile(":app:generateDebugResValues", 49000, 51000, 2000, true, "GENERATE"),
            TaskProfile(":app:generateDebugResources", 51000, 53000, 2000, true, "GENERATE"),
            TaskProfile(":app:mergeDebugResources", 53000, 65000, 12000, true, "MERGE"),
        )

        val totalTime = tasks.maxOfOrNull { it.endTime } ?: 0
        val cacheHits = tasks.count { it.cacheHit }
        val cacheHitRate = if (tasks.isNotEmpty()) cacheHits.toDouble() / tasks.size else 0.0

        val sortedByDuration = tasks.sortedByDescending { it.duration }
        val bottlenecks = sortedByDuration.take(5)

        return ProfileReport(tasks, totalTime, cacheHitRate, bottlenecks)
    }

    /**
     * Loads the latest profile report from build directory.
     */
    fun loadLatestProfile(buildDir: File = File(".")): ProfileReport? {
        val report = parseProfileReport(buildDir)
        if (report != null) {
            lastProfileReport = report
        }
        return lastProfileReport
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
    }
}
