package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Fork Loader - executes ARM64 ELF binaries on Android bypassing noexec/SELinux.
 *
 * Packaged as libfork_loader.so in APK native lib dir (apk_data_file type, exec allowed).
 * Can also be copied to app data but that may fail on Android 10+.
 *
 * Modes:
 * 1. glibc mode: fork_loader <box64> <ld-linux-aarch64.so.1> <target_argv...>
 *    - run_argv = [ld.so, box64] + target_argv
 *    - ld.so loads box64, box64 gets argv = [box64] + target_argv
 * 2. bionic mode: fork_loader <box64> <linker64> <box64> <target_argv...>
 *    - target_argv = &argv[3] becomes program argv
 *    - linker64 loads box64, box64 gets argv = [box64] + target_argv
 *
 * This mimics the JRE launcher pattern from gradle_exec.c.
 */
object ForkLoader {

    private const val TAG = "ForkLoader"

    // Load native library
    init {
        System.loadLibrary("fork_loader")
    }

    // Native methods
    private external fun nativeRunForkLoader(
        forkLoaderPath: String,
        box64Path: String,
        interpPath: String,
        targetArgv: Array<String>,
        envp: Array<String>,
        cwd: String?
    ): Int

    private external fun nativeSelfTest(forkLoaderPath: String): String

    /**
     * Self-test: runs /system/bin/echo through fork_loader to verify exec works.
     */
    fun selfTest(context: Context): String {
        val forkLoader = ensure(context) ?: return "FAIL: fork_loader not available"
        return nativeSelfTest(forkLoader.absolutePath)
    }

    /**
     * Ensures fork_loader exists in native library directory.
     * Returns the path to libfork_loader.so or fork_loader.
     */
    fun ensure(context: Context): File? {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir

        val candidates = listOf(
            File(nativeLibDir, "libfork_loader.so"),
            File(nativeLibDir, "fork_loader")
        )

        val found = candidates.firstOrNull { it.exists() && it.length() > 0 }
        found?.let {
            runCatching { it.setExecutable(true, false) }
            runCatching { Os.chmod(it.absolutePath, 0b111_101_101) }
            Log.i(TAG, "Using fork_loader: ${it.absolutePath} (${it.length()} bytes)")
            return it
        }

        // Try to extract from assets
        val assetName = "fork_loader"
        try {
            val inputStream = context.assets.open(assetName)
            val target = File(nativeLibDir, "libfork_loader.so")
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            target.setExecutable(true, false)
            runCatching { Os.chmod(target.absolutePath, 0b111_101_101) }
            Log.i(TAG, "Extracted fork_loader from assets to ${target.absolutePath}")
            return target
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract fork_loader from assets: ${e.message}")
        }

        return null
    }

    /**
     * Runs fork_loader with given parameters.
     * 
     * @param box64Path Path to box64 binary
     * @param interpPath Path to interpreter (ld-linux-aarch64.so.1 or linker64)
     * @param targetArgv Target program arguments (e.g., ["--version"] or ["wine64", "test.exe"])
     * @param env Environment variables
     * @return Exit code
     */
    fun runForkLoader(
        context: Context,
        box64Path: String,
        interpPath: String,
        targetArgv: List<String>,
        env: Map<String, String>,
        timeoutSec: Int = 60
    ): Int {
        val forkLoader = ensure(context) ?: throw IllegalStateException("fork_loader not available")

        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val cwd = context.filesDir.absolutePath

        return nativeRunForkLoader(
            forkLoader.absolutePath,
            box64Path,
            interpPath,
            targetArgv.toTypedArray(),
            envArray,
            cwd
        )
    }

    /**
     * Runs fork_loader in bionic mode (for bionic-linked box64).
     * argv: [fork_loader, box64, linker64, box64, ...targetArgv]
     */
    fun runForkLoaderBionic(
        context: Context,
        box64Path: String,
        targetArgv: List<String>,
        env: Map<String, String>,
        timeoutSec: Int = 60
    ): Int {
        val forkLoader = ensure(context) ?: throw IllegalStateException("fork_loader not available")
        val interp = "/system/bin/linker64"
        val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
        val cwd = context.filesDir.absolutePath
        val bionicArgv = listOf(box64Path) + targetArgv

        return nativeRunForkLoader(
            forkLoader.absolutePath,
            box64Path,
            interp,
            bionicArgv.toTypedArray(),
            envArray,
            cwd
        )
    }
}