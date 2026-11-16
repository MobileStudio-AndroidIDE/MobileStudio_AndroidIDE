package com.aide.mobilestudio.sdkmanager.offline

import android.content.Context
import com.aide.mobilestudio.sdkmanager.model.SdkComponent

/**
 * SDK Offline Installer - Install SDK components from offline packages.
 * Simplified implementation for compilation.
 */
class SdkOfflineInstaller(
    private val context: Context
) {
    private val installed = mutableListOf<String>()

    fun installPackage(packagePath: String): Boolean {
        installed.add(packagePath)
        return true
    }

    fun getInstalledPackages(): List<String> = installed.toList()
}