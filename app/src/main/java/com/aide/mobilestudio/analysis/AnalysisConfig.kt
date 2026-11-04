package com.aide.mobilestudio.analysis

import android.content.Context
import com.aide.mobilestudio.BuildToolsConfig
import java.io.File

data class AnalysisConfig(
    val debounceMs: Long = 300L,
    val enableQuickSyntaxCheck: Boolean = true,
    val enableKotlinCompiler: Boolean = true,
    val enableKotlinDaemon: Boolean = true,
    val daemonHeapMb: Int = 64,
    val daemonIdleTimeoutMs: Long = 60_000L,
    val maxFileSizeKb: Int = 512,
    val maxDiagnosticsPerFile: Int = 100,
    val cacheMaxEntries: Int = 200,
    val cacheTtlMs: Long = 30_000L,
    val compilerTimeoutMs: Long = 10_000L
) {
    companion object {
        fun defaultForDevice(context: Context): AnalysisConfig {
            val isLowRam = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                android.app.ActivityManager::class.java
                    .getMethod("isLowRamDevice")
                    .invoke(am) as Boolean
            }.getOrDefault(false)
            return if (isLowRam) {
                AnalysisConfig(
                    debounceMs = 800L,
                    enableKotlinCompiler = false,
                    enableKotlinDaemon = false,
                    daemonHeapMb = 32,
                    daemonIdleTimeoutMs = 30_000L,
                    maxFileSizeKb = 256,
                    maxDiagnosticsPerFile = 50,
                    cacheTtlMs = 30_000L
                )
            } else {
                AnalysisConfig(
                    debounceMs = BuildToolsConfig.analysisDebounceMs(context),
                    daemonHeapMb = BuildToolsConfig.analysisDaemonHeapMb(context),
                    cacheMaxEntries = BuildToolsConfig.analysisCacheMaxEntries(context),
                    cacheTtlMs = BuildToolsConfig.analysisCacheTtlMs(context),
                    compilerTimeoutMs = BuildToolsConfig.analysisCompilerTimeoutMs(context)
                )
            }
        }
    }
}

data class CachedResult(
    val sourceHash: Int,
    val diagnostics: List<Diagnostic>,
    val timestampMs: Long
) {
    fun isValid(ttlMs: Long, currentHash: Int): Boolean =
        sourceHash == currentHash && (System.currentTimeMillis() - timestampMs) < ttlMs
}
