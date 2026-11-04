package com.aide.mobilestudio.ai

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.aide.mobilestudio.ToolchainArchiveInstaller
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipFile

/**
 * LlamaRuntimeManager — llama.cpp Android runtime (arm64-v8a, CPU + Vulkan)
 * download/install/verify/version management.
 *
 * Security policy:
 *  - HTTPS downloads only
 *  - SHA-256 verification is MANDATORY (mismatch → file deleted + error)
 *  - tar.gz extraction blocks path traversal (see ToolchainArchiveInstaller)
 *  - interrupted downloads leave a .tmp file that is detected and removed on
 *    the next start, so a corrupted archive is never extracted
 *  - installed runtime/models are never re-downloaded on app restart
 *
 * Install layout (archive root is flattened during extraction):
 *   <filesDir>/toolchain/llama/<version>/lib/libllama.so, libggml*.so ...
 *   <filesDir>/toolchain/llama/<version>/.runtime-version (marker)
 *   <filesDir>/toolchain/llama/download.tmp (partial download — never extracted)
 */
class LlamaRuntimeManager private constructor(private val context: Context?) {
    companion object {
        private const val TAG = "LlamaRuntimeManager"

        /** Runtime version management: bump this for a new release and reinstall. */
        const val RUNTIME_VERSION = "llama-vulkan-android-v0.0.1-beta"
        const val DOWNLOAD_URL =
            "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/llama-vulkan-android-v0.0.1-beta/llama-vulkan-android-arm64.tar.gz"
        const val EXPECTED_SHA256 = "f04f0e15c55b38170a86dcfede1f75fc718aef250b2c5f6a00d85831a28bfc1a"

        /** Libraries that must be usable after extraction. */
        val REQUIRED_LIBS = listOf(
            "libllama.so", "libggml.so", "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so"
        )

        /** Actual archive size (verified against the GitHub release). */
        const val ESTIMATED_ARCHIVE_BYTES = 22_891_054L

        /** Dependency the runtime .so files may need (libc++_shared.so). */
        const val DEP_CXX_SHARED = "libc++_shared.so"

        /** Sanity cap when reading the dependency out of an APK. */
        private const val MAX_DEP_BYTES = 16L * 1024 * 1024

        @Volatile private var appContext: Context? = null
        @Volatile private var shared: LlamaRuntimeManager? = null

        /** Call early (e.g. from the AI panel init) so context-free access works. */
        fun initAppContext(ctx: Context) {
            appContext = ctx.applicationContext
        }

        /** Shared instance (context optional once initAppContext was called). */
        fun get(context: Context? = null): LlamaRuntimeManager {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val ctx = context?.applicationContext ?: appContext
                    ?: error("LlamaRuntimeManager: no context (call initAppContext)")
                return LlamaRuntimeManager(ctx).also { shared = it }
            }
        }
    }

    private val ctx: Context get() = context ?: appContext
        ?: error("LlamaRuntimeManager: no context (call initAppContext)")
    private val llamaRoot by lazy { File(ctx.filesDir, "toolchain/llama") }
    private val versionDir get() = File(llamaRoot, RUNTIME_VERSION)
    private val libDir get() = File(versionDir, "lib")
    private val markerFile get() = File(versionDir, ".runtime-version")
    private val tmpFile get() = File(llamaRoot, "download.tmp")

    /** True when all required libs exist in [dir] (directly). */
    private fun libsIn(dir: File): Boolean =
        dir.isDirectory && REQUIRED_LIBS.all { File(dir, it).isFile }

    /**
     * Resolves the directory that contains the runtime .so files:
     *  1. <version>/lib  (flattened install layout)
     *  2. <version>/<archiveRoot>/lib (older nested layout, one level deeper)
     *  3. falls back to <version>/lib
     */
    fun runtimeLibDir(): File = when {
        libsIn(libDir) -> libDir
        else -> {
            val nested = versionDir.listFiles()
                ?.filter { it.isDirectory }
                ?.firstOrNull { libsIn(File(it, "lib")) || libsIn(it) }
                ?.let { if (libsIn(File(it, "lib"))) File(it, "lib") else it }
            nested ?: libDir
        }
    }

    /** The app's native lib dir (used to preload libc++_shared.so). */
    fun appNativeLibDir(): String = ctx.applicationInfo.nativeLibraryDir

    fun installedVersion(): String? = if (isInstalled()) RUNTIME_VERSION else null

    /**
     * True when the runtime is installed: version marker present AND all
     * required libraries exist. A missing marker → not installed → re-download.
     */
    fun isInstalled(): Boolean {
        if (!markerFile.isFile) return false
        if (markerFile.readText().trim() != RUNTIME_VERSION) return false
        return libsIn(runtimeLibDir())
    }

    /**
     * Ensures the runtime is installed. Non-blocking (background thread):
     *  1. already installed → done immediately (no re-download)
     *  2. damaged partial download (.tmp) → deleted
     *  3. download with progress ("42 MB / 100 MB")
     *  4. SHA-256 verification (mismatch → delete + error)
     *  5. safe extraction (path traversal protected, archive root flattened)
     *  6. version marker written
     *
     * onDone(success, error): on failure the caller may retry by calling again.
     */
    fun ensureInstalled(
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onLog: (String) -> Unit,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (isInstalled()) {
            onLog("[AI Runtime] Installed: $RUNTIME_VERSION (no re-download)")
            onDone(true, null)
            return
        }
        Thread {
            try {
                // Damaged partial download from a previous interrupted run
                if (tmpFile.isFile) {
                    onLog("[AI Runtime] Removing damaged partial download...")
                    tmpFile.delete()
                }

                val (hasSpace, available) = checkStorageSpace(ESTIMATED_ARCHIVE_BYTES)
                if (!hasSpace) {
                    onDone(false, "Insufficient storage for AI runtime: need ~22MB free, available ${formatSize(available)}")
                    return@Thread
                }

                onLog("[AI Runtime] Downloading AI Runtime...")
                ToolchainArchiveInstaller.download(tmpFile, URL(DOWNLOAD_URL), onProgress)
                onLog("[AI Runtime] Download complete (${formatSize(tmpFile.length())})")

                onLog("[AI Runtime] Verifying SHA-256...")
                ToolchainArchiveInstaller.verifySha256(tmpFile, EXPECTED_SHA256)
                onLog("[AI Runtime] SHA-256 OK")

                onLog("[AI Runtime] Extracting...")
                ToolchainArchiveInstaller.extractTarGz(tmpFile, versionDir) {
                    onLog("[AI Runtime] $it")
                }
                tmpFile.delete()

                val libTarget = runtimeLibDir()
                if (!libsIn(libTarget)) {
                    val found = versionDir.walkTopDown().filter { it.isFile }
                        .map { it.name }.toList()
                    onLog("[AI Runtime ERROR] Extraction incomplete — required libs missing")
                    onLog("[AI Runtime ERROR]   lib dir: $libTarget")
                    onLog("[AI Runtime ERROR]   found: ${found.take(20).joinToString(", ")}")
                    onDone(false, "Extraction incomplete: required libraries missing")
                    return@Thread
                }

                markerFile.writeText(RUNTIME_VERSION)
                // libc++_shared.so may be needed by the runtime — stage it next to the libs
                val dep = ensureNativeDeps()
                onLog(
                    if (dep != null) "[AI Runtime] Dependency ready: ${dep.name} (${formatSize(dep.length())})"
                    else "[AI Runtime WARNING] ${DEP_CXX_SHARED} not staged — runtime load may fail"
                )
                onLog("[AI Runtime] Installed: $RUNTIME_VERSION")
                onLog("[AI Runtime] Libs: ${REQUIRED_LIBS.joinToString(", ")}")
                onDone(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "runtime install failed", e)
                tmpFile.delete()
                onLog("[AI Runtime ERROR] Install failed: ${e.message}")
                onDone(false, e.message)
            }
        }.start()
    }

    /** Removes the installed runtime (for version replacement / reinstall). */
    fun uninstall() {
        llamaRoot.deleteRecursively()
        Log.i(TAG, "Runtime uninstalled: ${llamaRoot.path}")
    }

    private fun checkStorageSpace(required: Long): Pair<Boolean, Long> {
        val stat = StatFs(ctx.filesDir.path)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        return (available > required + 512L * 1024 * 1024) to available
    }

    /**
     * Copies the C++ runtime dependency (libc++_shared.so) next to the runtime
     * libraries so [com.aide.mobilestudio.ai.api.LlamaRuntimeBridge] can
     * preload it with RTLD_GLOBAL before dlopen()ing libllama.so.
     *
     * Sources, in order:
     *  1. already next to the runtime libs (no-op)
     *  2. extracted native libs (applicationInfo.nativeLibraryDir)
     *  3. the APK entry lib/<abi>/libc++_shared.so
     *
     * Returns the destination file, or null when no source could be found.
     */
    fun ensureNativeDeps(): File? {
        val destDir = runtimeLibDir()
        val dest = File(destDir, DEP_CXX_SHARED)
        if (dest.isFile && dest.length() > 0) return dest
        if (!versionDir.isDirectory) return null
        try {
            val inLibDir = File(ctx.applicationInfo.nativeLibraryDir, DEP_CXX_SHARED)
            if (inLibDir.isFile && inLibDir.length() > 0) {
                destDir.mkdirs()
                inLibDir.copyTo(dest, overwrite = true)
                Log.i(TAG, "Copied $DEP_CXX_SHARED from nativeLibraryDir → $destDir")
                return dest
            }
            val apks = buildList {
                ctx.applicationInfo.sourceDir?.let { add(it) }
                ctx.applicationInfo.splitSourceDirs?.let { addAll(it) }
            }
            for (apk in apks) {
                val entry = readCxxFromApk(apk) ?: continue
                destDir.mkdirs()
                FileOutputStream(dest).use { out -> out.write(entry) }
                Log.i(TAG, "Extracted $DEP_CXX_SHARED from ${File(apk).name} → $destDir")
                return dest
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureNativeDeps failed: ${e.message}", e)
        }
        Log.e(TAG, "No source for $DEP_CXX_SHARED (nativeLibraryDir/APK)")
        return null
    }

    /** Reads lib/<abi>/libc++_shared.so from an APK for any supported ABI. */
    private fun readCxxFromApk(apkPath: String): ByteArray? {
        val abis = (Build.SUPPORTED_ABIS.toList() + Build.CPU_ABI).distinct()
        ZipFile(apkPath).use { zip ->
            for (abi in abis) {
                val entry = runCatching { zip.getEntry("lib/$abi/$DEP_CXX_SHARED") }.getOrNull()
                    ?: continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.isNotEmpty() && bytes.size < MAX_DEP_BYTES) return bytes
            }
        }
        return null
    }

    fun formatSize(bytes: Long): String = ToolchainArchiveInstaller.formatSize(bytes)
}
