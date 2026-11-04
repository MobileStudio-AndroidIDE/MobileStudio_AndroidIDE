package com.aide.mobilestudio

import android.content.Context
import java.io.File

/**
 * Configuration policy for the Virtual Runtime Engine.
 * Controls search paths, timeout defaults, and security behavior.
 */
data class VirtualRuntimePolicy(
    val runtimeDir: File,
    val nativeLibDir: File,
    val timeoutMs: Int = 300_000,
    val maxProcessCount: Int = 16,
    val createProcessGroup: Boolean = true,
    val inheritEnvironment: Boolean = true,
    val extraSearchPaths: List<String> = emptyList(),
    val blockSuidBinaries: Boolean = true,
    val requireArm64: Boolean = true
) {
    companion object {
        fun fromContext(context: Context): VirtualRuntimePolicy {
            val runtimeDir = File(context.filesDir, "runtime")
            val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
            return VirtualRuntimePolicy(
                runtimeDir = runtimeDir,
                nativeLibDir = nativeLibDir
            )
        }
    }
}
