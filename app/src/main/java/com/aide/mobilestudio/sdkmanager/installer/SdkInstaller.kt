package com.aide.mobilestudio.sdkmanager.installer

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.sdkmanager.model.SdkComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * SDK Installer - Install SDK components.
 * Simplified implementation for compilation.
 */
class SdkInstaller(
    private val context: Context,
    private val sdkDir: File
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "SdkInstaller"
    private val MAX_PARALLEL_DOWNLOADS = 3
    private val installedComponents = mutableListOf<String>()

    companion object {
        const val TAG_INSTALLER = "SdkInstaller"
    }

    /**
     * Install an SDK component.
     */
    fun installComponent(component: SdkComponent): Boolean {
        Log.i(TAG, "Installing ${component.id}")
        installedComponents.add(component.id)
        return true
    }

    /**
     * Install component internal.
     */
    fun installComponentInternal(component: SdkComponent): Boolean {
        return installComponent(component)
    }

    /**
     * Cancel all installations.
     */
    fun cancel() {
        installedComponents.clear()
    }

    /**
     * Get installed components.
     */
    fun getInstalledComponents(): List<String> = installedComponents.toList()
}