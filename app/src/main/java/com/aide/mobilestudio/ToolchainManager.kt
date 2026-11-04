package com.aide.mobilestudio

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import android.util.Log
import java.io.File

class ToolchainManager(private val context: Context) {

    companion object {
        private const val TAG = "ToolchainManager"
        private val OVERRIDE_INSTALL_LOCK = Any()
        private const val PREFS = "toolchain_versions"
        private const val KEY_JDK = "active_jdk"
        private const val KEY_GRADLE = "active_gradle"
        private const val KEY_SDK = "active_sdk"
        private const val KEY_NDK = "active_ndk"
        private const val KEY_CMAKE = "active_cmake"
        private const val KEY_PYTHON = "active_python"
        private const val EXTERNAL_PREFIX = "external_"
        // Bundled fallbacks — the dynamic catalog (BuildToolCatalog /
        // ToolchainUpdateSites cache) is authoritative when reachable.
        // NEWEST FIRST so "first()" consumers pick the newest tool.
        const val DEFAULT_JDK = "jdk-17"
        const val FALLBACK_JDK = "jdk-17"
        const val DEFAULT_GRADLE = "gradle-9.7.0"

        val AVAILABLE_JDK = listOf("jdk-21", "jdk-17")
        val AVAILABLE_GRADLE = listOf(
            "gradle-9.7.0",
            "gradle-9.6.1",
            "gradle-9.5.1",
            "gradle-9.4.1",
            "gradle-9.3.1",
            "gradle-9.2.1",
            "gradle-9.1.0",
            "gradle-9.0.0"
        )

        // 8.x series - hidden behind "show old gradle versions" in the Tools UI
        val OLD_GRADLE = listOf(
            "gradle-8.14.5",
            "gradle-8.13",
            "gradle-8.12",
            "gradle-8.11",
            "gradle-8.10",
            "gradle-8.9",
            "gradle-8.8",
            "gradle-8.7",
            "gradle-8.6",
            "gradle-8.5"
        )
        val AVAILABLE_SDK = listOf(
            "android-30",
            "android-31",
            "android-32",
            "android-33",
            "android-34",
            "android-35"
        )
        val AVAILABLE_NDK = listOf(
            "29.0.14206865",
            "29.0.14033849",
            "29.0.13846066",
            "29.0.13599879",
            "29.0.13113456",
            "28.2.13676358",
            "28.1.13356709",
            "28.0.13004108",
            "28.0.12916984",
            "28.0.12674087",
            "28.0.12433566",
            "27.3.13750724",
            "27.2.12479018",
            "27.1.12297006",
            "27.0.12077973",
            "27.0.11902837",
            "27.0.11718014"
        )
        // Bundled fallback: the distributed legacy CMake port. Newer ports are
        // discovered from the dynamic catalog.
        val AVAILABLE_CMAKE = listOf("3.22.1")
        val AVAILABLE_PYTHON = listOf(
            "python-3.14.0",
            "python-3.14.1",
            "python-3.14.2",
            "python-3.14.3",
            "python-3.14.4",
            "python-3.14.5",
            "python-3.14.6"
        )

        /**
         * NDK 버전에 맞는 CMake 버전 반환 — the newest CMake known to the
         * dynamic catalog (process-wide memo), falling back to the bundled
         * legacy port when the catalog has not been loaded yet.
         */
        fun cmakeVersionForNdk(ndkVersion: String): String =
            BuildToolCatalog.newestCachedCmakeVersion() ?: CmakeRuntimeManager.CMAKE_VERSION
    }

    enum class Kind(val displayName: String, val dirName: String) {
        JDK("JDK", "jdk"),
        GRADLE("Gradle", "gradle"),
        SDK("SDK", "sdk"),
        NDK("NDK", "ndk"),
        CMAKE("CMake", "cmake"),
        PYTHON("Python", "python")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val rootDir: File = File(context.filesDir, "toolchain").apply { mkdirs() }
    val overridesDir: File = File(rootDir, "overrides").apply { mkdirs() }

    fun ensureAssetOverridesInstalled() {
        val aapt2Out = File(overridesDir, "aapt2")
        val aapt2Src = File(overridesDir, ".aapt2-source")
        fun pinnedSource(): String =
            runCatching { aapt2Src.readText().trim() }.getOrDefault("")

        val aapt2Asset = when {
            AssetUtils.assetExists(context, "aapt2") -> "aapt2"
            AssetUtils.assetExists(context, "aapt2-arm64") -> "aapt2-arm64"
            else -> null
        }
        if ((!aapt2Out.exists() || aapt2Out.length() == 0L) && aapt2Asset != null) {
            if (AssetUtils.copyAsset(context, aapt2Asset, aapt2Out, overwrite = true)) {
                aapt2Out.setExecutable(true, false)
                aapt2Out.setReadable(true, false)
                runCatching { aapt2Src.writeText("asset") }
            }
        }

        /*
         * The StudioShellTools aapt2 port is the source of truth. Download it
         * when overrides/aapt2 is missing or pinned to any other source (e.g.
         * an old Termux-built binary with hardcoded /data/data/com.termux
         * paths). Re-check inside the lock: several boot paths call this
         * concurrently.
         */
        fun needsDownload(): Boolean {
            val fileOk = aapt2Out.exists() && aapt2Out.length() > 0L
            val pinned = pinnedSource()
            return !fileOk || (pinned != Arm64ToolsDownloader.AAPT2_URL && pinned != "asset")
        }
        if (needsDownload()) {
            synchronized(OVERRIDE_INSTALL_LOCK) {
                if (needsDownload()) {
                    runCatching {
                        Arm64ToolsDownloader.downloadOverridesAapt2(context)
                    }.onFailure {
                        Log.w(TAG, "overrides/aapt2 download failed: ${it.message}")
                    }
                }
            }
        }

        val d8Out = File(overridesDir, "d8.jar")
        if ((!d8Out.exists() || d8Out.length() == 0L) && AssetUtils.assetExists(context, "d8.jar")) {
            if (AssetUtils.copyAsset(context, "d8.jar", d8Out, overwrite = true)) {
                d8Out.setReadable(true, false)
            }
        }
    }

    fun kindDir(kind: Kind): File =
        File(rootDir, kind.dirName).apply { mkdirs() }

    fun installedVersions(kind: Kind): List<String> {
        val local = kindDir(kind).listFiles()
            ?.filter { it.isDirectory && isVersionName(kind, it.name) }
            ?.map { it.name }
            ?: emptyList()
        val legacy = legacyVersion(kind)?.let { listOf(it) } ?: emptyList()
        val sdkCmake = if (kind == Kind.CMAKE) sdkCmakeVersions() else emptyList()
        val sdkNdk = if (kind == Kind.NDK) sdkNdkVersions() else emptyList()
        val external = externalVersion(kind)?.let { listOf(it) } ?: emptyList()
        return (external + local + legacy + sdkCmake + sdkNdk).distinct().sorted()
    }

    /**
     * Installable versions, NEWEST FIRST for JDK/Gradle/NDK/CMake. The live
     * sources (BuildToolCatalog GitHub releases for JDK/NDK/CMake; the
     * services.gradle.org scraper cache for Gradle) are authoritative; the
     * bundled lists above are the offline fallback and are merged in so the
     * list is never smaller than before. SDK/Python keep their existing
     * ascending order.
     */
    fun availableVersions(kind: Kind): List<String> = when (kind) {
        Kind.JDK ->
            BuildToolCatalog.jdkEntries(BuildToolCatalog.loadCached(context))
                .map { "jdk-${it.version}" }
        Kind.GRADLE -> {
            val cached = runCatching { ToolchainUpdateSites(context).cachedVersions(kind) }
                .getOrDefault(emptyList())
            BuildToolCatalog.mergedVersionStringsNewestFirst(cached, AVAILABLE_GRADLE + OLD_GRADLE)
        }
        Kind.SDK -> (AVAILABLE_SDK + ToolchainUpdateSites(context).cachedVersions(kind)).distinct().sorted()
        Kind.NDK ->
            BuildToolCatalog.ndkEntries(BuildToolCatalog.loadCached(context))
                .map { it.version }
        Kind.CMAKE ->
            BuildToolCatalog.cmakeEntries(BuildToolCatalog.loadCached(context))
                .map { it.version }
        Kind.PYTHON -> (AVAILABLE_PYTHON + ToolchainUpdateSites(context).cachedVersions(kind)).distinct().sorted()
    }

    fun isInstalled(kind: Kind, version: String): Boolean =
        installedDir(kind, version) != null

    fun activeVersion(kind: Kind): String? {
        val external = externalVersion(kind)
        if (external != null && installedDir(kind, external) != null) return external
        val saved = prefs.getString(keyFor(kind), null)
        if (saved != null && isInstalled(kind, saved)) return saved
        val installed = installedVersions(kind)
        preferredDefaultVersion(kind)?.let { preferred ->
            if (preferred in installed) return preferred
        }
        if (kind == Kind.JDK && FALLBACK_JDK in installed) return FALLBACK_JDK
        return installed.firstOrNull()
    }

    fun setActiveVersion(kind: Kind, version: String): Boolean {
        val target = installedDir(kind, version)
        if (target == null) {
            Log.w(TAG, "setActive: $kind=$version is not installed")
            return false
        }
        if (version != externalVersion(kind)) {
            prefs.edit().remove(externalKeyFor(kind)).apply()
        }
        prefs.edit().putString(keyFor(kind), version).apply()

        val activeLink = File(kindDir(kind), "active")
        runCatching {
            if (activeLink.exists() || isSymlink(activeLink)) Os.remove(activeLink.absolutePath)
        }
        runCatching {
            if (activeLink.exists()) activeLink.deleteRecursively()
        }
        runCatching {
            Os.symlink(target.absolutePath, activeLink.absolutePath)
        }.onFailure {
            Log.w(TAG, "active symlink failed: ${it.message}")
        }

        Log.i(TAG, "active $kind = $version")
        return true
    }

    fun setActivePath(kind: Kind, dir: File): Boolean {
        val target = runCatching { dir.canonicalFile }.getOrDefault(dir)
        if (!target.exists() || !target.isDirectory) return false
        val version = importVersionName(kind, target)
        prefs.edit()
            .putString(externalKeyFor(kind), target.absolutePath)
            .putString(keyFor(kind), version)
            .apply()
        Log.i(TAG, "active external $kind = ${target.absolutePath}")
        return true
    }

    fun activeDir(kind: Kind): File? {
        val version = activeVersion(kind) ?: return null
        return installedDir(kind, version)
    }

    fun activeJdkHome(): File? {
        val active = activeDir(Kind.JDK)
        if (active != null) {
            resolveJdkHome(active)?.let { return it }
        }
        // Fallback: search common JDK paths
        val jdkRoot = File(rootDir, "jdk")
        val fallbackPaths = listOf(
            File(jdkRoot, "jdk-21"),
            File(jdkRoot, "jdk21-android"),
            File(jdkRoot, "jdk-17"),
            File(jdkRoot, "jdk17-android"),
            File(jdkRoot, "lib/jvm/java-21-openjdk"),
            File(jdkRoot, "lib/jvm/java-17-openjdk"),
            File(jdkRoot, "lib/jvm/java-21-open-android"),
            File(jdkRoot, "lib/jvm/java-17-open-android"),
            jdkRoot
        )
        for (path in fallbackPaths) {
            if (path.exists()) {
                resolveJdkHome(path)?.let { return it }
            }
        }
        return null
    }

    fun resolveJdkHome(root: File): File? {
        if (!root.exists()) return null
        val versionHint = root.name.lowercase()
        val candidates = buildList {
            add(root)
            add(File(root, "Contents/Home"))
            File(root, "lib/jvm").listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name.lowercase().contains(versionHint) }
                ?.forEach { add(it) }
            root.listFiles()
                ?.filter { it.isDirectory && it.name !in setOf("shim", "active") }
                ?.forEach { child ->
                    add(child)
                    add(File(child, "Contents/Home"))
                    File(child, "lib/jvm").listFiles()
                        ?.filter { it.isDirectory }
                        ?.sortedByDescending { it.name.lowercase().contains(versionHint) }
                        ?.forEach { add(it) }
                }
            runCatching {
                root.walkTopDown()
                    .maxDepth(5)
                    .filter { it.isDirectory && it.name !in setOf("shim", "active") && File(it, "bin/java").exists() }
                    .forEach { add(it) }
            }
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        return candidates.firstOrNull { isUsableJdkHome(it) }
    }

    fun isUsableJdkHome(home: File): Boolean {
        val javaBin = File(home, "bin/java")
        if (!javaBin.isFile || javaBin.length() == 0L) return false
        if (!javaBin.canExecute()) {
            runCatching { javaBin.setExecutable(true, false) }
        }
        // Check for libjli.so (standard OpenJDK layout) or libjvm.so (alternative layout)
        val hasLibjli = File(home, "lib/libjli.so").let { it.isFile && it.length() > 0L } ||
            File(home, "lib/jli/libjli.so").let { it.isFile && it.length() > 0L }
        val hasLibjvm = File(home, "lib/server/libjvm.so").let { it.isFile && it.length() > 0L }
        return hasLibjli || hasLibjvm
    }

    fun sdkManagerPath(sdkDir: File): File {
        val candidates = buildList {
            fun addSdk(root: File) {
                add(File(root, "cmdline-tools/latest/bin/sdkmanager"))
                add(File(root, "cmdline-tools/bin/sdkmanager"))
                File(root, "cmdline-tools").listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { add(File(it, "bin/sdkmanager")) }
            }
            addSdk(sdkDir)
            sdkRoots().forEach { addSdk(it) }
        }
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    fun uninstall(kind: Kind, version: String): Boolean {
        val dir = File(kindDir(kind), version)
        if (!dir.exists()) return false
        val wasActive = prefs.getString(keyFor(kind), null) == version
        val ok = dir.deleteRecursively()
        if (wasActive) {
            prefs.edit().remove(keyFor(kind)).apply()
        }
        Log.i(TAG, "uninstall $kind/$version = $ok")
        return ok
    }

    fun downloadUrl(kind: Kind, version: String): String? = when (kind) {
        Kind.GRADLE -> {
            val gradleVersion = version.removePrefix("gradle-")
            "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
        }
        Kind.JDK -> version.removePrefix("jdk-").toIntOrNull()?.let { major ->
            // Dynamic catalog first (newest published build for this major),
            // bundled legacy URLs as fallback via the catalog's merge.
            BuildToolCatalog.findJdk(BuildToolCatalog.loadCached(context), major)?.url
        }
        Kind.SDK -> "https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
        Kind.NDK -> null  // NDK is resolved via Google SDK repository XML
        Kind.CMAKE -> null  // CMake is resolved via Google SDK repository XML
        Kind.PYTHON -> pythonAndroidDownloadUrl(version)
    }

    private fun keyFor(kind: Kind): String = when (kind) {
        Kind.JDK -> KEY_JDK
        Kind.GRADLE -> KEY_GRADLE
        Kind.SDK -> KEY_SDK
        Kind.NDK -> KEY_NDK
        Kind.CMAKE -> KEY_CMAKE
        Kind.PYTHON -> KEY_PYTHON
    }

    private fun externalKeyFor(kind: Kind): String = EXTERNAL_PREFIX + keyFor(kind)

    /**
     * Preferred default when no version is saved AND the version is actually
     * installed — so the default tracks the NEWEST available tool without ever
     * auto-changing an already-active installation (activeVersion checks the
     * saved/installed state before consulting this).
     */
    private fun preferredDefaultVersion(kind: Kind): String? = when (kind) {
        Kind.JDK -> DEFAULT_JDK
        Kind.GRADLE -> availableVersions(Kind.GRADLE).firstOrNull() ?: DEFAULT_GRADLE
        Kind.SDK -> "android-34"
        Kind.NDK -> availableVersions(Kind.NDK).firstOrNull() ?: AVAILABLE_NDK.firstOrNull()
        Kind.CMAKE -> availableVersions(Kind.CMAKE).firstOrNull() ?: CmakeRuntimeManager.CMAKE_VERSION
        Kind.PYTHON -> AVAILABLE_PYTHON.lastOrNull()
    }

    private fun pythonAndroidDownloadUrl(version: String): String? {
        val pythonVersion = version.removePrefix("python-")
        if (!Regex("""\d+\.\d+\.\d+""").matches(pythonVersion)) return null
        return "https://www.python.org/ftp/python/$pythonVersion/python-$pythonVersion-aarch64-linux-android.tar.gz"
    }

    private fun externalDir(kind: Kind): File? {
        val path = prefs.getString(externalKeyFor(kind), null) ?: return null
        return File(path).takeIf { it.exists() && it.isDirectory }
    }

    private fun externalVersion(kind: Kind): String? {
        val dir = externalDir(kind) ?: return null
        return importVersionName(kind, dir)
    }

    private fun importVersionName(kind: Kind, dir: File): String {
        val safeName = dir.name.ifBlank { kind.dirName }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "$safeName [import ${kind.dirName}]"
    }

    private fun installedDir(kind: Kind, version: String): File? {
        val external = externalDir(kind)
        if (external != null && version == importVersionName(kind, external)) return external
        val direct = File(kindDir(kind), version)
        if (direct.exists()) return direct
        if (legacyVersion(kind) == version) return kindDir(kind)
        if (kind == Kind.CMAKE) return sdkCmakeDir(version)
        if (kind == Kind.NDK) return sdkNdkDir(version)
        return null
    }

    private fun legacyVersion(kind: Kind): String? = when (kind) {
        Kind.JDK -> null
        Kind.GRADLE -> {
            val libDir = File(kindDir(Kind.GRADLE), "lib")
            val launcher = libDir.listFiles()
                ?.firstOrNull { it.name.startsWith("gradle-gradle-cli-main") && it.name.endsWith(".jar") }
                ?: libDir.listFiles()
                    ?.firstOrNull { it.name.startsWith("gradle-launcher") && it.name.endsWith(".jar") }
                ?: libDir.listFiles()
                    ?.firstOrNull { it.name.contains("launcher", ignoreCase = true) && it.name.endsWith(".jar") }
                ?: libDir.listFiles()
                    ?.filter { it.name.contains("gradle", ignoreCase = true) && it.name.endsWith(".jar") }
                    ?.minByOrNull { it.length() }
            if (launcher != null) DEFAULT_GRADLE else null
        }
        Kind.PYTHON -> {
            val python = File(kindDir(Kind.PYTHON), "bin/python3")
            if (python.exists()) "python-local" else null
        }
        else -> null
    }

    private fun isVersionName(kind: Kind, name: String): Boolean {
        if (name == "active" || name == "overrides" || name == "lib" || name == "shim") return false
        return when (kind) {
            Kind.JDK -> name.startsWith("jdk-")
            Kind.GRADLE -> name.startsWith("gradle-")
            Kind.SDK -> name.startsWith("android-")
            Kind.NDK -> name.firstOrNull()?.isDigit() == true
            Kind.CMAKE -> name.firstOrNull()?.isDigit() == true
            Kind.PYTHON -> name.startsWith("python-") || File(kindDir(Kind.PYTHON), name).resolve("bin/python3").exists()
        }
    }

    private fun sdkCmakeVersions(): List<String> {
        return sdkRoots().flatMap { sdk ->
            File(sdk, "cmake").listFiles()
                ?.filter { it.isDirectory && isVersionName(Kind.CMAKE, it.name) }
                ?.map { it.name }
                ?: emptyList()
        }
    }

    private fun sdkCmakeDir(version: String): File? {
        return sdkRoots()
            .map { File(File(it, "cmake"), version) }
            .firstOrNull { it.exists() && it.isDirectory }
    }

    private fun sdkNdkVersions(): List<String> {
        return sdkRoots().flatMap { sdk ->
            File(sdk, "ndk").listFiles()
                ?.filter { it.isDirectory && isVersionName(Kind.NDK, it.name) }
                ?.map { it.name }
                ?: emptyList()
        }
    }

    private fun sdkNdkDir(version: String): File? {
        return sdkRoots()
            .map { File(File(it, "ndk"), version) }
            .firstOrNull { it.exists() && it.isDirectory }
    }

    private fun sdkRoots(): List<File> {
        val direct = kindDir(Kind.SDK).listFiles()
            ?.filter { it.isDirectory && isVersionName(Kind.SDK, it.name) }
            ?: emptyList()
        val active = activeVersion(Kind.SDK)?.let { installedDir(Kind.SDK, it) }
        return (listOfNotNull(active) + direct).distinctBy { it.absolutePath }
    }

    private fun isSymlink(file: File): Boolean = try {
        file.canonicalPath != file.absolutePath
    } catch (_: Exception) {
        false
    }
}

object CompatibilityChecker {

    fun check(kind: ToolchainManager.Kind, currentVersion: String, newVersion: String): List<String> {
        val warnings = mutableListOf<String>()

        when (kind) {
            ToolchainManager.Kind.JDK -> checkJdk(newVersion, warnings)
            ToolchainManager.Kind.GRADLE -> checkGradle(newVersion, warnings)
            ToolchainManager.Kind.NDK -> checkNdk(newVersion, warnings)
            ToolchainManager.Kind.CMAKE -> checkCmake(newVersion, warnings)
            ToolchainManager.Kind.SDK -> checkSdk(newVersion, warnings)
            ToolchainManager.Kind.PYTHON -> Unit
        }

        return warnings
    }

    private fun checkJdk(version: String, warnings: MutableList<String>) {
        val jdkNum = version.removePrefix("jdk-").toIntOrNull() ?: return
        if (jdkNum < 17) {
            warnings.add("JDK $jdkNum is below minimum recommended version (17+). Some Gradle features may not work.")
        }
        if (jdkNum == 21 || jdkNum == 22) {
            warnings.add("JDK $jdkNum requires Gradle 8.5 or later for full compatibility.")
        }
    }

    private fun checkGradle(version: String, warnings: MutableList<String>) {
        val gradleVersion = version.removePrefix("gradle-")
        val parts = gradleVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0

        if (major < 8) {
            warnings.add("Gradle $gradleVersion is older than 8.x. AGP 8.x requires Gradle 8.0+.")
        }
        if (major == 8 && minor < 5) {
            warnings.add("Gradle $gradleVersion may not fully support JDK 21. Consider upgrading to Gradle 8.5+.")
        }
        if (major == 8 && minor == 13 && patch < 5) {
            warnings.add("Gradle $gradleVersion is not the latest minor version.\nWe recommend upgrading to Gradle version 8.14.5.")
        }
        if (major == 8 && minor < 13) {
            warnings.add("Gradle $gradleVersion is outdated.\nWe recommend upgrading to Gradle version 8.14.5.")
        }
        if (major >= 9) {
            warnings.add("Gradle $gradleVersion is a major upgrade. Verify AGP compatibility before building.")
        }
    }

    private fun checkNdk(version: String, warnings: MutableList<String>) {
        val major = version.split(".").firstOrNull()?.toIntOrNull() ?: return
        if (major <= 25) {
            warnings.add("NDK $version is outdated. Consider upgrading to NDK 27+ for latest features and bug fixes.")
        }
        val recommendedCmake = ToolchainManager.cmakeVersionForNdk(version)
        warnings.add("NDK $version works best with CMake $recommendedCmake.")
    }

    private fun checkCmake(version: String, warnings: MutableList<String>) {
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (major < 3 || (major == 3 && minor < 22)) {
            warnings.add("CMake $version is older than 3.22. NDK 27+ requires CMake 3.22.1+.")
        }
    }

    fun checkSdk(version: String, warnings: MutableList<String>) {
        val apiLevel = version.removePrefix("android-").toIntOrNull() ?: return
        if (apiLevel < 26) {
            warnings.add("API $apiLevel is below minimum supported level (26+). Build may fail.")
        }
        if (apiLevel > 35) {
            warnings.add("API $apiLevel is a preview target. Use stable API level for production builds.")
        }
    }

    data class RecommendedSet(
        val jdk: String,
        val gradle: String,
        val sdk: String,
        val ndk: String,
        val cmake: String
    )

    fun getRecommendedSet(kind: ToolchainManager.Kind, version: String): RecommendedSet {
        // Latest MobileStudio on-device defaults.
        val latestJdk = ToolchainManager.DEFAULT_JDK
        val latestGradle = ToolchainManager.DEFAULT_GRADLE
        val latestSdk = "android-34"
        val latestNdk = "27.0.12077973"
        val latestCmake = "3.22.1"

        var jdk = latestJdk
        var gradle = latestGradle
        var sdk = latestSdk
        var ndk = latestNdk
        var cmake = latestCmake

        when (kind) {
            ToolchainManager.Kind.GRADLE -> {
                if (version.startsWith("gradle-9")) {
                    jdk = "jdk-21"
                    gradle = version
                } else if (version.startsWith("gradle-8.1") || version.startsWith("gradle-8.5") || 
                           version.startsWith("gradle-8.6") || version.startsWith("gradle-8.7") ||
                           version.startsWith("gradle-8.8") || version.startsWith("gradle-8.9")) {
                    // Modern 8.x supports JDK 21
                    jdk = "jdk-21"
                    gradle = version
                } else {
                    gradle = version
                }
            }
            ToolchainManager.Kind.JDK -> {
                jdk = version
                if (version == "jdk-21") {
                    gradle = latestGradle
                }
            }
            ToolchainManager.Kind.NDK -> {
                ndk = version
                cmake = ToolchainManager.cmakeVersionForNdk(version)
            }
            ToolchainManager.Kind.SDK -> {
                sdk = version
            }
            else -> {}
        }

        return RecommendedSet(jdk, gradle, sdk, ndk, cmake)
    }

    fun getLatestSet(): RecommendedSet {
        return RecommendedSet(ToolchainManager.DEFAULT_JDK, ToolchainManager.DEFAULT_GRADLE, "android-34", "27.0.12077973", "3.22.1")
    }
}
