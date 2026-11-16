package com.aide.mobilestudio.sdkmanager.path

import android.content.Context

/**
 * SDK Path Manager - manages SDK installation paths.
 * Simplified implementation for compilation.
 */
class SdkPathManager(
    private val context: Context
) {
    private val KEY_SDK_PATH = "sdk_path"
    private val KEY_NDK_PATH = "ndk_path"
    private val KEY_CMAKE_PATH = "cmake_path"

    private val prefs = context.getSharedPreferences("sdk_paths", Context.MODE_PRIVATE)

    fun getSdkPath(): String {
        val path = prefs.getString(KEY_SDK_PATH, null)
        return if (path != null && path.isNotEmpty()) path else ""
    }

    fun setSdkPath(path: String) {
        prefs.edit().putString(KEY_SDK_PATH, path).apply()
    }

    fun getNdkPath(): String {
        val path = prefs.getString(KEY_NDK_PATH, null)
        return if (path != null && path.isNotEmpty()) path else ""
    }

    fun setNdkPath(path: String) {
        prefs.edit().putString(KEY_NDK_PATH, path).apply()
    }
}