package com.aide.mobilestudio

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Manages the virtual runtime directory structure for on-device execution.
 * Sets up bin/, lib/, lib64/, usr/, tmp/ directories, populates
 * symlinks to native libraries from the APK, and configures shared storage
 * mappings for the virtual filesystem.
 */
class RuntimeManager(val context: Context) {

    companion object {
        private const val TAG = "RuntimeManager"
        private const val RUNTIME_DIR_NAME = "runtime"
    }

    private val runtimeDir: File = File(context.filesDir, RUNTIME_DIR_NAME)
    private val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)

    fun getRuntimeDir(): File = runtimeDir
    fun getNativeLibDir(): File = nativeLibDir
    fun getBinDir(): File = File(runtimeDir, "bin")
    fun getLibDir(): File = File(runtimeDir, "lib")
    fun getLib64Dir(): File = File(runtimeDir, "lib64")
    fun getTmpDir(): File = File(runtimeDir, "tmp")
    fun getSharedStorageDir(): File = File(runtimeDir, "shared")

    /**
     * Initialize the runtime directory structure.
     * Called during app startup (BuildBootActivity, etc.)
     */
    fun initialize(): Boolean {
        return try {
            ensureDirectories()
            linkNativeLibraries()
            setupSharedStorage()
            Log.i(TAG, "Runtime initialized: ${runtimeDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize runtime", e)
            false
        }
    }

    private fun ensureDirectories() {
        val dirs = listOf(
            runtimeDir,
            getBinDir(),
            getLibDir(),
            getLib64Dir(),
            getTmpDir(),
            getSharedStorageDir(),
            File(runtimeDir, "usr"),
            File(runtimeDir, "usr/lib"),
            File(runtimeDir, "usr/lib64"),
            File(runtimeDir, "usr/bin"),
            File(runtimeDir, "etc")
        )
        dirs.forEach { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * Symlink shared libraries from the APK's native lib dir into the runtime's lib dir.
     * This makes them discoverable by the dynamic linker when executing tools.
     */
    private fun linkNativeLibraries() {
        if (!nativeLibDir.exists()) {
            Log.w(TAG, "Native lib dir does not exist: ${nativeLibDir.absolutePath}")
            return
        }

        val nativeLibs = nativeLibDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".so")
        } ?: return

        var linked = 0
        for (lib in nativeLibs) {
            val target = File(getLibDir(), lib.name)
            if (!target.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", lib.absolutePath, target.absolutePath))
                    linked++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to symlink ${lib.name}: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Linked $linked native libraries to runtime lib dir")
    }

    /**
     * Set up shared storage mappings inside the runtime directory.
     * Creates a symlink from <runtime>/shared/ to /storage/emulated/0 (primary)
     * and /storage/emulated/0/Android/data/<pkg>/files for app-private storage.
     */
    private fun setupSharedStorage() {
        val sharedDir = getSharedStorageDir()
        if (!sharedDir.exists()) sharedDir.mkdirs()

        val primaryStorage = File("/storage/emulated/0")
        if (primaryStorage.exists()) {
            val link = File(sharedDir, "emulated-0")
            if (!link.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", primaryStorage.absolutePath, link.absolutePath))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create shared storage symlink: ${e.message}")
                }
            }
        }

        val appFiles = context.filesDir
        if (appFiles.exists()) {
            val link = File(sharedDir, "app-files")
            if (!link.exists()) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-sf", appFiles.absolutePath, link.absolutePath))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create app files symlink: ${e.message}")
                }
            }
        }
    }

    fun getRuntimeDirPath(): String = runtimeDir.absolutePath
    fun getNativeLibDirPath(): String = nativeLibDir.absolutePath
}
