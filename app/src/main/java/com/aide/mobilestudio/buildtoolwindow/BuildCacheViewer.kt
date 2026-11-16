package com.aide.mobilestudio.buildtoolwindow

import java.io.File

/**
 * Build Cache Viewer - displays Gradle build cache status and statistics.
 * Supports local and remote cache inspection and cleanup.
 */
class BuildCacheViewer(
    private val context: android.content.Context,
    private val projectDir: File
) {

    data class CacheInfo(
        val totalSize: Long,
        val fileCount: Int,
        val lastCleanup: Long?,
        val hitRate: Double
    )

    private var cacheInfo: CacheInfo? = null

    /**
     * Scans the Gradle build cache directory for cache information.
     */
    fun scanCache(): CacheInfo? {
        val gradleCacheDir = File(projectDir, ".gradle")
        if (!gradleCacheDir.exists()) {
            return null
        }

        var totalSize = 0L
        var fileCount = 0

        gradleCacheDir.walk().filter { it.isFile }.forEach { file ->
            totalSize += file.length()
            fileCount++
        }

        val info = CacheInfo(
            totalSize = totalSize,
            fileCount = fileCount,
            lastCleanup = null,
            hitRate = 0.0
        )
        cacheInfo = info
        return info
    }

    /**
     * Cleans the Gradle build cache.
     */
    fun cleanCache(): Long {
        val gradleCacheDir = File(projectDir, ".gradle")
        if (!gradleCacheDir.exists()) {
            return 0L
        }

        var freedSize = 0L
        gradleCacheDir.walk().filter { it.isFile }.forEach { file ->
            freedSize += file.length()
            file.delete()
        }

        cacheInfo = null
        return freedSize
    }

    /**
     * Returns current cache info or null if not scanned yet.
     */
    fun getCacheInfo(): CacheInfo? = cacheInfo
}
