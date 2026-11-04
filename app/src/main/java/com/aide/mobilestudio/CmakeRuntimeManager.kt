package com.aide.mobilestudio

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.net.URL

/**
 * CmakeRuntimeManager — downloads the user-ported CMake Android runtime
 * (arm64-v8a) from the MobileStudio GitHub releases.
 *
 * Version management: the installed/target version comes from the dynamic
 * catalog (BuildToolCatalog — newest release wins). CMAKE_VERSION / the other
 * constants remain the bundled fallback (used offline and by legacy callers),
 * and the general release form is tag "cmake-android-v<ver>" plus asset
 * "cmake-<ver>-android-arm64.tar.gz".
 *
 * Security policy (same as LlamaRuntimeManager / ToolchainArchiveInstaller):
 *  - HTTPS download only, SHA-256 verification MANDATORY
 *  - safe tar.gz extraction with path traversal protection
 *  - installed runtime is never re-downloaded on app restart
 *
 * Install layout (archive root flattened during extraction):
 *   <filesDir>/toolchain/cmake/3.22.1/bin/cmake, cpack, ctest
 *   <filesDir>/toolchain/cmake/3.22.1/share/cmake-3.22/...
 *
 * ToolchainManager.Kind.CMAKE resolves <filesDir>/toolchain/cmake/<version>,
 * so the installed port is picked up by the build pipeline automatically.
 */
class CmakeRuntimeManager private constructor(private val context: Context?) {
    companion object {
        private const val TAG = "CmakeRuntimeManager"

        /** The only distributed version (user-ported). Bundled fallback — the
         *  dynamic catalog (BuildToolCatalog) carries newer published ports. */
        const val CMAKE_VERSION = "3.22.1"
        const val DOWNLOAD_URL =
            "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/cmake-android-v3.22.1/cmake-3.22.1-android-arm64.tar.gz"

        /** SHA-256 of the release archive (verified against the actual download). */
        const val EXPECTED_SHA256 = "092133e9440b88bf5dcbe916594cf2f69c7dd35104f77c4551eb34c73460eea0"

        /** Actual archive size (verified against the GitHub release). */
        const val ESTIMATED_ARCHIVE_BYTES = 12_919_431L

        /** Release tag / asset name forms used by the dynamic catalog. */
        fun releaseTag(version: String): String = "cmake-android-v$version"

        fun assetName(version: String): String = "cmake-$version-android-arm64.tar.gz"

        /** General download URL form (used when the catalog has no entry). */
        fun downloadUrlFor(version: String): String =
            "https://github.com/${StudioToolsIndex.GITHUB_OWNER}/${BuildToolCatalog.CATALOG_REPO}" +
                "/releases/download/${releaseTag(version)}/${assetName(version)}"

        private const val MARKER = ".cmake-runtime-version"

        @Volatile private var appContext: Context? = null
        @Volatile private var shared: CmakeRuntimeManager? = null

        /** Call early so context-free access works. */
        fun initAppContext(ctx: Context) {
            appContext = ctx.applicationContext
        }

        /** Shared instance (context optional once initAppContext was called). */
        fun get(context: Context? = null): CmakeRuntimeManager {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val ctx = context?.applicationContext ?: appContext
                    ?: error("CmakeRuntimeManager: no context (call initAppContext)")
                return CmakeRuntimeManager(ctx).also { shared = it }
            }
        }
    }

    private val ctx: Context get() = context ?: appContext
        ?: error("CmakeRuntimeManager: no context (call initAppContext)")
    private val cmakeRoot by lazy { File(ToolchainManager(ctx).rootDir, "cmake") }
    private val tmpFile get() = File(cmakeRoot, "download.tmp")
    private val installLock = Any()

    /**
     * The version this manager targets: the newest CMake entry from the
     * dynamic catalog (network + bundled fallback), falling back to the
     * legacy single-version constant when the catalog has nothing.
     */
    fun preferredVersion(): String =
        runCatching {
            BuildToolCatalog.cmakeEntries(BuildToolCatalog.loadCached(ctx)).firstOrNull()?.version
        }.getOrNull() ?: CMAKE_VERSION

    private fun versionDirFor(version: String): File = File(cmakeRoot, version)

    private fun isValidInstall(dir: File, version: String): Boolean {
        val marker = File(dir, MARKER)
        if (!marker.isFile) return false
        if (runCatching { marker.readText().trim() }.getOrNull() != version) return false
        val cmakeBin = File(dir, "bin/cmake")
        if (!cmakeBin.isFile) return false
        if (runCatching { java.nio.file.Files.isSymbolicLink(cmakeBin.toPath()) }.getOrDefault(false)) return false
        return NativeToolWrappers.isElf(cmakeBin)
    }

    /** Valid installs (version name to dir), NEWEST first. */
    private fun installedDirs(): List<Pair<String, File>> =
        cmakeRoot.listFiles()
            ?.filter { it.isDirectory && it.name.firstOrNull()?.isDigit() == true }
            ?.mapNotNull { dir -> dir.name.takeIf { isValidInstall(dir, it) }?.let { it to dir } }
            ?.sortedWith { x, y -> BuildToolCatalog.compareVersions(y.first, x.first) }
            ?: emptyList()

    /** Directory containing bin/cmake — passed to the build pipeline. */
    fun cmakeDir(): File = installedDirs().firstOrNull()?.second ?: versionDirFor(preferredVersion())

    fun installedVersion(): String? = installedDirs().firstOrNull()?.first

    /**
     * True when [version] (default: the newest catalog version) is installed:
     * marker present AND bin/cmake is a real ELF. Symlinks and non-ELF files
     * count as damaged — on-device symlink-repair passes previously replaced
     * the binary with self-referential links.
     */
    fun isInstalled(version: String = preferredVersion()): Boolean =
        isValidInstall(versionDirFor(version), version)

    /**
     * Ensures a CMake runtime is installed. Non-blocking (background thread):
     *  1. already installed → done immediately (no re-download)
     *  2. damaged partial download (.tmp) → deleted
     *  3. download with progress ("4 MB / 12 MB")
     *  4. SHA-256 verification when a hash is known (catalog or bundled)
     *  5. safe extraction (path traversal protected, archive root flattened)
     *  6. version marker + executable bits written
     *
     * [version] defaults to the newest catalog version. onDone(success, error):
     * on failure the caller may retry by calling again.
     */
    fun ensureInstalled(
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onLog: (String) -> Unit,
        onDone: (Boolean, String?) -> Unit,
        version: String? = null
    ) {
        val target = version ?: preferredVersion()
        if (isInstalled(target)) {
            onLog("[CMake] Installed: $target (no re-download)")
            onDone(true, null)
            return
        }
        Thread {
            try {
                installBlocking(target, onProgress, onLog)
                onLog("[CMake] Installed: $target")
                onLog("[CMake] Path: ${versionDirFor(target).absolutePath}")
                onDone(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "CMake runtime install failed", e)
                tmpFile.delete()
                onLog("[CMake ERROR] Install failed: ${e.message}")
                onDone(false, e.message)
            }
        }.start()
    }

    /**
     * Blocking variant for background threads that already run off the UI
     * thread (the Gradle build flow). Returns immediately when installed;
     * throws on failure. Concurrent callers serialize on [installLock].
     */
    fun ensureInstalledBlocking(
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
        version: String? = null
    ) {
        val target = version ?: preferredVersion()
        if (isInstalled(target)) return
        synchronized(installLock) {
            if (isInstalled(target)) return
            try {
                installBlocking(target, onProgress, onLog)
                onLog("[CMake] Installed: $target (${versionDirFor(target).absolutePath})")
            } catch (e: Exception) {
                Log.e(TAG, "CMake runtime install failed", e)
                tmpFile.delete()
                throw e
            }
        }
    }

    /**
     * Download + verify + extract. URL/SHA-256 come from the dynamic catalog
     * entry when one exists; otherwise the general tag/asset URL form is
     * used, falling back to the legacy constants for [CMAKE_VERSION] so
     * existing installs keep working byte-for-byte.
     */
    private fun installBlocking(
        version: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
        onLog: (String) -> Unit
    ) {
        synchronized(installLock) {
            if (tmpFile.isFile) {
                onLog("[CMake] Removing damaged partial download...")
                tmpFile.delete()
            }

            val stat = StatFs(ctx.filesDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            if (available < ESTIMATED_ARCHIVE_BYTES + 256L * 1024 * 1024) {
                throw IllegalStateException(
                    "Insufficient storage for CMake runtime: available " +
                        ToolchainArchiveInstaller.formatSize(available)
                )
            }

            val entry = runCatching {
                BuildToolCatalog.findCmake(BuildToolCatalog.fetch(ctx), version)
            }.getOrNull()
            val url = entry?.url
                ?: (if (version == CMAKE_VERSION) DOWNLOAD_URL else downloadUrlFor(version))
            val expectedSha = entry?.sha256
                ?: EXPECTED_SHA256.takeIf { version == CMAKE_VERSION && url == DOWNLOAD_URL }

            onLog("[CMake] Downloading CMake $version (arm64-v8a)...")
            ToolchainArchiveInstaller.download(tmpFile, URL(url), onProgress)
            onLog("[CMake] Download complete (${ToolchainArchiveInstaller.formatSize(tmpFile.length())})")

            if (expectedSha != null) {
                onLog("[CMake] Verifying SHA-256...")
                ToolchainArchiveInstaller.verifySha256(tmpFile, expectedSha)
                onLog("[CMake] SHA-256 OK")
            } else {
                onLog("[CMake WARN] No SHA-256 available for CMake $version — hash verification skipped")
            }

            onLog("[CMake] Extracting (archive root flattened)...")
            // Extract into a FRESH staging dir. versionDir may hold leftovers
            // from older installs (a nested archive root, a stray share/)
            // which defeat the single-root flattening pass and leave the
            // tree without a top-level bin/cmake. Swap only after the staged
            // tree is verified.
            val stageDir = File(cmakeRoot, "extract.tmp")
            stageDir.deleteRecursively()
            ToolchainArchiveInstaller.extractTarGz(tmpFile, stageDir) {
                onLog("[CMake] $it")
            }
            tmpFile.delete()

            val cmakeBin = File(stageDir, "bin/cmake")
            if (!cmakeBin.isFile || !NativeToolWrappers.isElf(cmakeBin)) {
                val found = stageDir.walkTopDown().filter { it.isFile }
                    .map { it.name }.toList()
                onLog("[CMake ERROR] Extraction incomplete — bin/cmake missing")
                onLog("[CMake ERROR]   found: ${found.take(20).joinToString(", ")}")
                stageDir.deleteRecursively()
                throw IllegalStateException("Extraction incomplete: bin/cmake missing")
            }

            val versionDir = versionDirFor(version)
            if (versionDir.exists()) versionDir.deleteRecursively()
            if (!stageDir.renameTo(versionDir)) {
                stageDir.copyRecursively(versionDir, overwrite = true)
                stageDir.deleteRecursively()
            }

            // Executable bits for bin/* (cmake/cpack/ctest)
            File(versionDir, "bin").listFiles()?.forEach { it.setExecutable(true, false) }

            // The release archive ships cmake/cpack/ctest only (tar listing has
            // no ninja entry). Populate bin/ninja so CMAKE_MAKE_PROGRAM and
            // in-tree lookups resolve inside the install (copy, not symlink —
            // symlinks end in a direct execve() that Android denies).
            populateNinja(versionDir, onLog)

            File(versionDir, MARKER).writeText(version)
        }
    }

    /**
     * Ensures `<versionDir>/bin/ninja` is a runnable PIE (ET_DYN) arm64 ninja.
     * Prefers a local toolchain copy; downloads the arm64 ninja release when none
     * exists (e.g. after a fresh reinstall, where the legacy arm64-tools tree is
     * gone and the SDK's bundled ninja is a non-PIE ET_EXEC that linker64 rejects
     * with "has unexpected e_type: 2").
     */
    private fun populateNinja(versionDir: File, onLog: (String) -> Unit) {
        val ninjaDest = File(versionDir, "bin/ninja")
        if (ninjaDest.isFile && NativeToolWrappers.isPieElf(ninjaDest)) return
        var ninjaSrc = NativeToolWrappers
            .scanToolCandidates(File(ctx.filesDir, "toolchain"), "ninja")
            .firstOrNull { it.absolutePath != ninjaDest.absolutePath && NativeToolWrappers.isPieElf(it) }
        if (ninjaSrc == null) {
            onLog("[CMake] no local PIE ninja found — downloading arm64 ninja…")
            ninjaSrc = Arm64ToolsDownloader.ensureNinja(ctx) { onLog(it) }
        }
        if (ninjaSrc == null) {
            onLog("[CMake WARN] no runnable arm64 ninja available — C/C++ build may fall back to the SDK ninja")
            return
        }
        runCatching {
            if (ninjaDest.exists() ||
                runCatching { java.nio.file.Files.isSymbolicLink(ninjaDest.toPath()) }.getOrDefault(false)
            ) {
                ninjaDest.delete()
            }
            ninjaSrc.copyTo(ninjaDest, overwrite = true)
            ninjaDest.setExecutable(true, false)
            onLog("[CMake] ninja: ${ninjaDest.absolutePath} <- ${ninjaSrc.absolutePath}")
        }.onFailure {
            onLog("[CMake WARN] ninja copy failed: ${it.message}")
        }
    }

    /**
     * Cheap per-build repair: ensures the installed runtime's bin/ninja exists and
     * is a PIE ELF. No-op when the runtime is not installed. Returns true when a
     * repair was performed. Called on every build so a marker-present-but-ninja-
     * missing state (the reinstall regression) self-heals without re-downloading
     * the whole CMake archive.
     */
    fun repairNinjaIfNeeded(onLog: (String) -> Unit = {}): Boolean {
        val dir = installedDirs().firstOrNull()?.second ?: return false
        val ninjaDest = File(dir, "bin/ninja")
        if (ninjaDest.isFile && NativeToolWrappers.isPieElf(ninjaDest)) return false
        populateNinja(dir, onLog)
        return true
    }

    /** Removes an installed CMake runtime (for version replacement / reinstall). */
    fun uninstall(version: String = preferredVersion()) {
        versionDirFor(version).deleteRecursively()
        Log.i(TAG, "CMake runtime uninstalled: $version")
    }

    fun formatSize(bytes: Long): String = ToolchainArchiveInstaller.formatSize(bytes)
}
