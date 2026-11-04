package com.aide.mobilestudio

import android.content.Context
import java.io.File

/**
 * Single source of truth for the Android SDK / NDK / CMake / Gradle locations.
 *
 * Both the Build button ([GradleRunner]) and the shell environment
 * ([ShellEnvironment]) resolve the toolchain through this object, so a
 * `gradle` / `sdkmanager` / `ndk-build` run inside the terminal always sees
 * exactly the same toolchain that tapping Build uses.
 */
object BuildToolchain {

    /** The Android SDK the build button uses (active version, then fallbacks). */
    fun sdkDir(context: Context): File? {
        val active = ToolchainManager(context).activeDir(ToolchainManager.Kind.SDK)
        if (active != null && active.isDirectory) return active
        val files = context.filesDir
        return listOf(
            File(files, "toolchain/sdk/active"),
            File(files, "toolchain/sdk/android-34"),
        ).firstOrNull { it.isDirectory }
    }

    /**
     * Every installed NDK, active version first.  The build picks the first
     * entry for `ndk.dir`; the shell exposes them all for `PATH` / search roots.
     */
    fun ndkDirs(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        ToolchainManager(context).activeDir(ToolchainManager.Kind.NDK)
            ?.takeIf { it.isDirectory }?.let { dirs.add(it) }
        File(context.filesDir, "toolchain/ndk").listFiles()
            ?.filter { it.isDirectory }?.forEach { dirs.add(it) }
        // SDK-bundled NDKs (sdk/ndk/<version>).
        File(context.filesDir, "toolchain/sdk").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { sdkDir ->
                File(sdkDir, "ndk").listFiles()
                    ?.filter { it.isDirectory }?.forEach { dirs.add(it) }
            }
        sdkDir(context)?.let { sdk ->
            File(sdk, "ndk").listFiles()
                ?.filter { it.isDirectory }?.forEach { dirs.add(it) }
        }
        return dirs
            .map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinctBy { it.absolutePath }
    }

    /** Primary NDK, if one is installed.  NDK is optional for the build. */
    fun primaryNdkDir(context: Context): File? = ndkDirs(context).firstOrNull()

    /** SDK-bundled CMake (highest x.y), used for `cmake.dir` / `ANDROID_CMAKE_HOME`. */
    fun cmakeDir(context: Context, sdk: File?): File? {
        if (sdk == null) return null
        val cmakeBase = File(sdk, "cmake")
        if (!cmakeBase.isDirectory) return null
        return cmakeBase.listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("""\d+\.\d+""")) }
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
    }

    /**
     * The Gradle user home the build button uses.  Shell and build share it so
     * caches, daemons and gradle.properties (jvmargs) stay in sync.
     *
     * INTERNAL storage only: Gradle extracts native-platform.so into
     * .gradle/native/… and the dynamic linker's namespace cannot load it from
     * /storage/emulated (FUSE) — "library … is not accessible for the
     * namespace".  caches/, daemon/, wrapper/ and gradle.properties all live
     * here; project sources and build outputs keep their external paths.
     */
    fun gradleUserHome(context: Context): File =
        File(context.filesDir, ".gradle")
            .apply { mkdirs() }

    /**
     * One-time migration of the expensive-to-rebuild Gradle caches from the
     * legacy EXTERNAL gradle user home (/storage/emulated/…/files/.gradle) to
     * the internal one.  daemon/ (stale PIDs and old absolute paths),
     * native/ (the linker-namespace-broken native-platform extraction) and
     * gradle.properties (stale paths + the invalid stacktrace entry older
     * builds wrote) are deliberately NOT copied — the runner rebuilds them
     * correctly under the internal home.
     *
     * The copy lands in a temp dir and is swapped in with a rename, so a
     * build that already started with a fresh (empty) caches dir is never
     * clobbered by a half-written migration.
     */
    fun migrateLegacyGradleUserHome(context: Context) {
        val internal = File(context.filesDir, ".gradle")
        val legacy = context.getExternalFilesDir(null)?.let { File(it, ".gradle") }
            ?: return
        // wrapper/: the downloaded Gradle distribution (wrapper dists — the
        // single most expensive thing to rebuild, ~167MB for Gradle 9.7).
        migrateLegacyGradleUserHomeChild(context, internal, legacy, "wrapper") { d ->
            File(d, "dists").isDirectory
        }
        // caches/modules-2: the dependency caches, when populated.
        migrateLegacyGradleUserHomeChild(context, internal, legacy, "caches") { d ->
            File(d, "modules-2").isDirectory
        }
        // daemon/ (stale PIDs and old absolute paths), native/ (the
        // linker-namespace-broken native-platform extraction) and
        // gradle.properties (stale paths + the invalid stacktrace entry older
        // builds wrote) are deliberately NOT copied — the runner rebuilds
        // them correctly under the internal home.
    }

    private fun migrateLegacyGradleUserHomeChild(
        context: Context,
        internal: File,
        legacy: File,
        name: String,
        hasContent: (File) -> Boolean
    ) {
        val src = File(legacy, name)
        if (!src.isDirectory || !hasContent(src)) return
        val dst = File(internal, name)
        if (hasContent(dst)) return
        // The copy lands in a temp dir and is swapped in with a rename, so a
        // build that already started with a fresh (empty) dir is never
        // clobbered by a half-written migration.
        val tmp = File(context.cacheDir, ".gradle-$name-migration")
        runCatching {
            tmp.deleteRecursively()
            src.copyRecursively(tmp, overwrite = true)
            internal.mkdirs()
            if (!tmp.renameTo(dst)) {
                tmp.copyRecursively(dst, overwrite = true)
                tmp.deleteRecursively()
            }
        }
        runCatching { tmp.deleteRecursively() }
    }

    /**
     * The Gradle distribution the build button launches, so the shell's
     * `gradle` on PATH is the very same install.
     */
    fun gradleHome(context: Context): File =
        runCatching {
            JreGradleInstaller(context, {}, {}).gradleHome
        }.getOrDefault(
            ToolchainManager(context).activeDir(ToolchainManager.Kind.GRADLE)
                ?: File(context.filesDir, "toolchain/gradle")
        )
}
