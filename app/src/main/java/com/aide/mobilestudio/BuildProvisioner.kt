package com.aide.mobilestudio

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Build-time tool provisioning ("lazy provisioning").
 *
 * First launch only asks the user to pick a language — [BuildBootActivity] no
 * longer downloads anything. Every tool a build needs is ensured HERE, right
 * before Gradle runs, in this order:
 *
 *   1. JDK 17/21        (hard requirement — download when neither exists)
 *   2. Gradle launcher  (hard requirement — download when missing)
 *   3. aapt2 override   (every Android build; marker-guarded, cheap)
 *   4. NDK + CMake + Ninja + LLVM — ONLY for projects with native code
 *
 * Every step is guarded by a cheap LOCAL existence check (marker file, ELF
 * probe, directory scan) so repeated builds never re-download what is already
 * installed. Native steps are individually failure-tolerant: a failed optional
 * step warns and the build proceeds; only missing JDK/Gradle abort the build.
 */
object BuildProvisioner {

    private const val TAG = "BuildProvisioner"
    private const val GRADLE_INSTALL_TIMEOUT_MINUTES = 15L

    /**
     * Returned when the user pressed stop during provisioning. It is not a
     * failure, so the wording avoids blaming the build.
     */
    const val CANCELLED_MESSAGE = "Build stopped before Gradle started (tool download cancelled)"

    // ------------------------------------------------------------------
    // Pure decision logic (unit-tested in BuildProvisionerTest)
    // ------------------------------------------------------------------

    /**
     * True when the project builds native (C/C++) code through AGP.
     * Cheap, local-only detection:
     *  - any `<module>/src/main/cpp/CMakeLists.txt` (precedent: BuildScheduler), or
     *  - any build script (project root or module level) mentioning
     *    `externalNativeBuild`.
     */
    fun needsNativeTooling(projectDir: File): Boolean {
        for (root in scanRoots(projectDir)) {
            if (File(root, "src/main/cpp/CMakeLists.txt").isFile) return true
        }
        return relevantBuildFiles(projectDir).any { file ->
            runCatching { file.readText() }.getOrDefault("").contains("externalNativeBuild")
        }
    }

    /**
     * Java major version (17 or 21) the project's build scripts request via
     * `JavaVersion.VERSION_xx` / `jvmTarget` / `sourceCompatibility` /
     * `targetCompatibility`. Null when unconstrained — the caller then prefers
     * JDK 21. Conflicting hints resolve to the newer requirement.
     */
    fun requiredJavaMajor(projectDir: File): Int? {
        val found = mutableSetOf<Int>()
        for (file in relevantBuildFiles(projectDir)) {
            val text = runCatching { file.readText() }.getOrDefault("")
            if (text.isEmpty()) continue
            for (match in JAVA_HINT.findAll(text)) {
                match.groupValues.lastOrNull { it.isNotEmpty() }?.toIntOrNull()?.let { found.add(it) }
            }
        }
        return found.maxOrNull()
    }

    /**
     * Newest NDK version that is both offered by ToolchainManager and
     * supported by NdkToolchain's GitHub installer. Null when none overlap.
     */
    fun newestSupportedNdk(available: List<String>, supported: Collection<String>): String? {
        val supportedSet = supported.toSet()
        return available.filter { it in supportedSet }
            .reduceOrNull { acc, v -> if (compareVersions(v, acc) > 0) v else acc }
    }

    /** Numeric segment comparison ("29.0.14206865" > "29.0.13599879"). */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    /** Matches `JavaVersion.VERSION_17/21`, `jvmTarget = "17"/21` etc. */
    private val JAVA_HINT = Regex(
        """(?i)JavaVersion\.VERSION_(17|21)\b|""" +
            """(?i)(?:jvmTarget|sourceCompatibility|targetCompatibility)\s*=\s*["']?(17|21)\b"""
    )

    private fun scanRoots(projectDir: File): List<File> = buildList {
        add(projectDir)
        projectDir.listFiles()?.filter { it.isDirectory }?.let { addAll(it) }
    }

    private fun relevantBuildFiles(projectDir: File): List<File> = buildList {
        for (dir in scanRoots(projectDir)) {
            for (name in listOf("build.gradle", "build.gradle.kts")) {
                val f = File(dir, name)
                if (f.isFile) add(f)
            }
        }
    }

    // ------------------------------------------------------------------
    // Orchestration (needs an Android Context — not unit-testable)
    // ------------------------------------------------------------------

    /**
     * Ensures every tool the given project's build needs is installed.
     * Returns null on success, or a hard-failure message (missing JDK/Gradle)
     * that the caller should surface and abort the build with.
     */
    @Synchronized
    fun provision(
        context: Context,
        projectDir: File,
        installer: JreGradleInstaller,
        emit: (String, LineLevel) -> Unit
    ): String? {
        // Every build starts a fresh provisioning run; the stop button calls
        // BuildCancellation.cancel() and the steps below abort at their next
        // chunk instead of downloading the rest of a gigabyte.
        BuildCancellation.begin()

        try {
            // 1) JDK — the user asked: "if neither JDK 17 nor 21 (arm64) exists, download".
            if (hasUsableJdk(context, installer)) {
                emit("> [Provision] JDK: already installed (skip)", LineLevel.INFO)
            } else if (!ensureJdk(context, projectDir, emit)) {
                if (BuildCancellation.isCancelled) return CANCELLED_MESSAGE
                return "JDK 17/21 is not installed and automatic download failed. " +
                    "Install a JDK from Tools > Toolchain Manager and build again."
            }

            // 2) Gradle launcher — hard requirement. JreGradleInstaller skips the
            //    download when any Gradle version is already installed, so this is
            //    strictly "download when missing".
            if (installer.isGradleInstalled) {
                emit("> [Provision] Gradle: already installed (skip)", LineLevel.INFO)
            } else if (!ensureGradle(installer, emit)) {
                if (BuildCancellation.isCancelled) return CANCELLED_MESSAGE
                return "Gradle is not installed and automatic download failed. " +
                    "Check the network, or install Gradle from Tools > Toolchain Manager."
            }

            // 3) aapt2 override — needed by every Android APK build. Marker-guarded
            //    inside ToolchainManager (no re-download when present).
            runCatching { ToolchainManager(context).ensureAssetOverridesInstalled() }
                .onFailure {
                    emit("> [Provision] WARN: aapt2 override check failed: ${it.message}", LineLevel.WARNING)
                }

            // 4) Native tooling — never touch the network for projects that do
            //    not build native code.
            if (!needsNativeTooling(projectDir)) {
                emit("> [Provision] native tooling: not needed for this project (skip)", LineLevel.DEBUG)
                return null
            }
            ensureNativeTooling(context, emit)
            return if (BuildCancellation.isCancelled) CANCELLED_MESSAGE else null
        } catch (e: ProvisioningCancelled) {
            emit("> [Provision] stopped before Gradle started", LineLevel.WARNING)
            return CANCELLED_MESSAGE
        }
    }

    private fun hasUsableJdk(context: Context, installer: JreGradleInstaller): Boolean =
        installer.isJdkInstalled || ToolchainManager(context).activeJdkHome() != null

    /**
     * Downloads JDK 21 (or 17 when the project requires it) through
     * JdkDownloader.ensureJdk, which itself skips valid installs.
     */
    private fun ensureJdk(context: Context, projectDir: File, emit: (String, LineLevel) -> Unit): Boolean {
        val toolchain = ToolchainManager(context)
        val required = requiredJavaMajor(projectDir)
        val order = if (required != null) {
            listOf(required, if (required == 21) 17 else 21)
        } else {
            listOf(21, 17)
        }
        for (major in order) {
            BuildCancellation.throwIfCancelled()
            val version = "jdk-$major"
            val dir = File(toolchain.kindDir(ToolchainManager.Kind.JDK), version)
            if (JdkDownloader.isValidJdk(dir, major)) {
                runCatching { toolchain.setActiveVersion(ToolchainManager.Kind.JDK, version) }
                emit("> [Provision] JDK $major: already installed (skip)", LineLevel.INFO)
                return true
            }
            emit("> [Provision] JDK $major: downloading (Android ARM64)...", LineLevel.INFO)
            val result = runCatching {
                JdkDownloader.ensureJdk(context, dir, major) { msg ->
                    emit("> [Provision] JDK: $msg", LineLevel.INFO)
                }
            }.getOrElse {
                android.util.Log.w(TAG, "JDK $major install error", it)
                emit("> [Provision] WARN: JDK $major install error: ${it.message}", LineLevel.WARNING)
                null
            }
            if (result?.success == true && result.dir != null) {
                runCatching { toolchain.setActiveVersion(ToolchainManager.Kind.JDK, version) }
                emit("> [Provision] JDK $major ready", LineLevel.SUCCESS)
                return true
            }
            emit("> [Provision] WARN: JDK $major download failed: ${result?.message}", LineLevel.WARNING)
        }
        return false
    }

    /**
     * Downloads Gradle when the launcher is missing. installGradleOnly runs on
     * its own thread and reports back on the main looper, so block on a latch.
     */
    private fun ensureGradle(installer: JreGradleInstaller, emit: (String, LineLevel) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        emit("> [Provision] Gradle: not found — downloading (latest available)...", LineLevel.INFO)
        val started = runCatching {
            installer.installGradleOnly(null) { success ->
                ok = success
                latch.countDown()
            }
            true
        }.getOrElse {
            android.util.Log.w(TAG, "Gradle install error", it)
            emit("> [Provision] WARN: Gradle install error: ${it.message}", LineLevel.WARNING)
            latch.countDown()
            false
        }
        if (started && !latch.await(GRADLE_INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            emit("> [Provision] WARN: Gradle install timed out", LineLevel.WARNING)
            return false
        }
        if (ok && installer.isGradleInstalled) {
            emit("> [Provision] Gradle ready", LineLevel.SUCCESS)
            return true
        }
        return false
    }

    /**
     * NDK + CMake + Ninja + LLVM for native projects. Each step is guarded by
     * a local existence probe and each failure is tolerated (warn + continue):
     * AGP produces its own precise error when a tool really was required.
     */
    private fun ensureNativeTooling(context: Context, emit: (String, LineLevel) -> Unit) {
        val toolchain = ToolchainManager(context)

        // --- NDK (skip when ANY usable arm64 NDK is installed) ---
        val existingNdk = NdkToolchain.findInstalledArm64Ndk(context)
        if (existingNdk != null) {
            val installed = NdkToolchain.versionOf(existingNdk)
            emit(
                "> [Provision] NDK ${installed ?: existingNdk.name}: already installed (skip)",
                LineLevel.INFO,
            )
            // Make sure Gradle resolves ndk.dir to the copy we actually have.
            if (installed != null) {
                runCatching { toolchain.setActiveVersion(ToolchainManager.Kind.NDK, installed) }
            }
        } else {
            // Say WHY a download is happening. Finding an NDK that cannot run
            // on this device looks identical to "no NDK" from the outside, and
            // without this line the user sees a download they already paid for
            // once and reasonably calls it a bug.
            NdkToolchain.describeInstalled(context).forEach { emit(it, LineLevel.INFO) }

            val version = newestSupportedNdk(
                ToolchainManager.AVAILABLE_NDK,
                NdkToolchain.GITHUB_NDK_VERSIONS
            )
            if (version == null) {
                emit("> [Provision] WARN: no downloadable NDK version available", LineLevel.WARNING)
            } else {
                emit(
                    "> [Provision] NDK $version: downloading the Android ARM64 build (may take a while)...",
                    LineLevel.INFO,
                )
                runCatching {
                    val installedDir = NdkToolchain.installFromGithub(context, version) { msg ->
                        BuildCancellation.throwIfCancelled()
                        if (!msg.startsWith("Downloaded ")) {
                            emit("> [Provision] NDK: $msg", LineLevel.INFO)
                        }
                    }
                    // installFromGithub may reuse a different revision already on
                    // disk, so activate the version that actually landed.
                    val actual = NdkToolchain.versionOf(installedDir) ?: version
                    runCatching { toolchain.setActiveVersion(ToolchainManager.Kind.NDK, actual) }
                    emit("> [Provision] NDK $actual ready", LineLevel.SUCCESS)
                }.onFailure {
                    if (it.message?.contains("declined", ignoreCase = true) == true) {
                        emit(
                            "> [Provision] NDK download declined — the Gradle build cannot continue without it.",
                            LineLevel.ERROR
                        )
                        BuildCancellation.cancel()
                    } else if (BuildCancellation.isCancellation(it)) {
                        emit("> [Provision] NDK download cancelled", LineLevel.WARNING)
                    } else {
                        emit("> [Provision] WARN: NDK $version install failed: ${it.message}", LineLevel.WARNING)
                    }
                }
            }
        }
        BuildCancellation.throwIfCancelled()

        // --- CMake runtime (marker + ELF probe = skip; never re-downloads) ---
        runCatching {
            CmakeRuntimeManager.initAppContext(context)
            val cmake = CmakeRuntimeManager.get(context)
            if (cmake.isInstalled()) {
                emit(
                    "> [Provision] CMake ${CmakeRuntimeManager.CMAKE_VERSION}: already installed (skip)",
                    LineLevel.INFO
                )
            } else {
                emit(
                    "> [Provision] CMake ${CmakeRuntimeManager.CMAKE_VERSION}: downloading (arm64)...",
                    LineLevel.INFO
                )
                DownloadCenter.begin(
                    "CMake ${CmakeRuntimeManager.CMAKE_VERSION}",
                    "",
                    File(context.filesDir, "toolchain/cmake/${CmakeRuntimeManager.CMAKE_VERSION}").path,
                    "cmake-${CmakeRuntimeManager.CMAKE_VERSION}-android-arm64.tar.gz",
                    0L
                )
                cmake.ensureInstalledBlocking(
                    onLog = { emit(it, LineLevel.INFO) },
                    onProgress = { read, total ->
                        BuildCancellation.throwIfCancelled()
                        DownloadCenter.progress(read, total)
                    }
                )
                runCatching {
                    toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, CmakeRuntimeManager.CMAKE_VERSION)
                }
                DownloadCenter.done("CMake ready")
                emit("> [Provision] CMake ${CmakeRuntimeManager.CMAKE_VERSION} ready", LineLevel.SUCCESS)
            }
        }.onFailure {
            DownloadCenter.fail("CMake download failed: ${it.message}")
            if (BuildCancellation.isCancellation(it)) {
                emit("> [Provision] CMake download cancelled", LineLevel.WARNING)
            } else {
                emit("> [Provision] WARN: CMake install failed: ${it.message}", LineLevel.WARNING)
            }
        }

        // --- Ninja (self-heal inside the installed CMake; only downloads when
        //     no local PIE ninja exists at all) ---
        runCatching {
            CmakeRuntimeManager.get(context).repairNinjaIfNeeded { emit(it, LineLevel.INFO) }
        }.onFailure {
            emit("> [Provision] WARN: ninja repair failed: ${it.message}", LineLevel.WARNING)
        }

        // --- Android-hosted LLVM (Clang) for on-device NDK builds ---
        if (AndroidHostedLlvmInstaller.hasUsableCompiler(context)) {
            emit("> [Provision] Android LLVM: already installed (skip)", LineLevel.INFO)
        } else {
            emit("> [Provision] Android LLVM (Clang): downloading (~1100MB, one-time)...", LineLevel.INFO)
            DownloadCenter.begin(
                "Android LLVM",
                "",
                File(context.filesDir, "toolchain/llvm").path,
                "android-llvm-arm64.tar.gz",
                0L
            )
            runCatching {
                AndroidHostedLlvmInstaller.ensureInstalled(context, emptyList(), { line, level ->
                    BuildCancellation.throwIfCancelled()
                    if (!line.contains("Android LLVM download:")) emit(line, level)
                }, onBytes = { done, total -> DownloadCenter.progress(done, total) })
                DownloadCenter.done("Android LLVM installed")
                emit("> [Provision] Android LLVM ready", LineLevel.SUCCESS)
            }.onFailure {
                DownloadCenter.fail("Android LLVM download failed: ${it.message}")
                if (BuildCancellation.isCancellation(it)) {
                    emit("> [Provision] Android LLVM download cancelled", LineLevel.WARNING)
                } else {
                    emit("> [Provision] WARN: Android LLVM install failed: ${it.message}", LineLevel.WARNING)
                }
            }
        }
    }
}
