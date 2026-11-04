package com.aide.mobilestudio

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized build tools configuration.
 * All values saved to SharedPreferences; every value has a hard-coded default.
 * Users can customize freely and reset to defaults.
 */
object BuildToolsConfig {

    private const val PREFS = "build_tools_config"

    private object K {
        const val GRADLE_HEAP_MB = "gradle_heap_mb"
        const val GRADLE_METASPACE_MB = "gradle_metaspace_mb"
        const val KOTLIN_HEAP_MB = "kotlin_heap_mb"
        const val KOTLIN_METASPACE_MB = "kotlin_metaspace_mb"
        const val MAX_WORKERS = "max_workers"
        const val PARALLEL = "parallel"
        const val BUILD_CACHE = "build_cache"
        const val DAEMON = "daemon"
        const val VFS_WATCH = "vfs_watch"
        const val CONSOLE = "console"
        const val CUSTOM_GRADLE_JVM_ARGS = "custom_gradle_jvm_args"
        const val GC = "gc"
        const val HEAP_DUMP = "heap_dump"
        const val GRADLE_VERBOSE = "gradle_verbose"
        const val OPTIMIZATION = "optimization"
        const val TARGET_API = "target_api"
        const val DEBUG_SYMBOLS = "debug_symbols"
        const val CUSTOM_CFLAGS = "custom_cflags"
        const val CUSTOM_LDFLAGS = "custom_ldflags"
        const val CPP_STANDARD = "cpp_standard"
        const val FPIC = "fpic"
        const val FPIE = "fpie"
        const val WERROR = "werror"
        const val WALL = "wall"
        const val STRIP = "strip"
        const val LINKER = "linker"
        const val CUSTOM_JVM_ARGS = "custom_jvm_args"
        const val FILE_ENCODING = "file_encoding"
        const val USER_COUNTRY = "user_country"
        const val USER_LANGUAGE = "user_language"
        const val ADD_OPENS = "add_opens"
        const val LD_LIBRARY_PATH_EXTRA = "ld_library_path_extra"
        const val KOTLIN_DAEMON_ENABLED = "kotlin_daemon_enabled"
        const val KOTLIN_DAEMON_JVMARGS = "kotlin_daemon_jvmargs"
        const val KOTLIN_INCREMENTAL = "kotlin_incremental"
        // ── Java/Kotlin Compiler ──
        const val JAVA_SOURCE_COMPAT = "java_source_compat"
        const val JAVA_TARGET_COMPAT = "java_target_compat"
        const val KOTLIN_JVM_TARGET = "kotlin_jvm_target"
        const val KOTLIN_API_VERSION = "kotlin_api_version"
        const val KOTLIN_LANGUAGE_VERSION = "kotlin_language_version"
        const val KOTLIN_COMPILER_HEAP_MB = "kotlin_compiler_heap_mb"
        // ── APK Signing ──
        const val SIGNING_KEY_ALIAS = "signing_key_alias"
        const val SIGNING_STORE_PASSWORD = "signing_store_password"
        const val SIGNING_KEY_PASSWORD = "signing_key_password"
        const val SIGNING_ALGORITHM = "signing_algorithm"
        const val SIGNING_KEY_SIZE = "signing_key_size"
        const val SIGNING_VALIDITY_DAYS = "signing_validity_days"
        const val SIGNING_DN = "signing_dn"
        // ── CMake / NDK ──
        const val CMAKE_BUILD_TYPE = "cmake_build_type"
        const val CMAKE_GENERATOR = "cmake_generator"
        const val NDK_STL = "ndk_stl"
        const val NDK_ABI_FILTERS = "ndk_abi_filters"
        // ── Template Defaults ──
        const val TEMPLATE_MIN_SDK = "template_min_sdk"
        const val TEMPLATE_TARGET_SDK = "template_target_sdk"
        const val TEMPLATE_COMPILE_SDK = "template_compile_sdk"
        const val TEMPLATE_KOTLIN_VERSION = "template_kotlin_version"
        const val TEMPLATE_AGP_VERSION = "template_agp_version"
        const val TEMPLATE_RELEASE_MINIFY = "template_release_minify"
        const val TEMPLATE_RELEASE_SHRINK = "template_release_shrink"
        // ── Analysis Engine ──
        const val ANALYSIS_DAEMON_HEAP_MB = "analysis_daemon_heap_mb"
        const val ANALYSIS_COMPILER_TIMEOUT_MS = "analysis_compiler_timeout_ms"
        const val ANALYSIS_DEBOUNCE_MS = "analysis_debounce_ms"
        const val ANALYSIS_CACHE_TTL_MS = "analysis_cache_ttl_ms"
        const val ANALYSIS_CACHE_MAX_ENTRIES = "analysis_cache_max_entries"
        const val CLANG_TIMEOUT_MS = "clang_timeout_ms"
        const val CLANGD_TIMEOUT_MS = "clangd_timeout_ms"
        // ── Shell / Build Limits ──
        const val SHELL_DEFAULT_TIMEOUT_MS = "shell_default_timeout_ms"
        const val SHELL_MAX_OUTPUT_LINES = "shell_max_output_lines"
        const val BUILD_MAX_CAPTURED_CHARS = "build_max_captured_chars"
        const val BUILD_FAILURE_COOLDOWN_MS = "build_failure_cooldown_ms"
        // ── Memory / Layout Preview ──
        const val MEMORY_MONITOR_INTERVAL_MS = "memory_monitor_interval_ms"
        const val MEMORY_HEAP_PRESSURE_THRESHOLD = "memory_heap_pressure_threshold"
        const val LAYOUT_PREVIEW_HEAP_MB = "layout_preview_heap_mb"
        const val LAYOUT_PREVIEW_TIMEOUT_MS = "layout_preview_timeout_ms"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun gradleHeapMb(context: Context): Int = prefs(context).getInt(K.GRADLE_HEAP_MB, 800)
    fun setGradleHeapMb(context: Context, v: Int) { prefs(context).edit().putInt(K.GRADLE_HEAP_MB, v.coerceIn(512, 4096)).apply() }

    fun gradleMetaspaceMb(context: Context): Int = prefs(context).getInt(K.GRADLE_METASPACE_MB, 512)
    fun setGradleMetaspaceMb(context: Context, v: Int) { prefs(context).edit().putInt(K.GRADLE_METASPACE_MB, v.coerceIn(128, 1024)).apply() }

    fun kotlinHeapMb(context: Context): Int = prefs(context).getInt(K.KOTLIN_HEAP_MB, 1536)
    fun setKotlinHeapMb(context: Context, v: Int) { prefs(context).edit().putInt(K.KOTLIN_HEAP_MB, v.coerceIn(256, 4096)).apply() }

    fun kotlinMetaspaceMb(context: Context): Int = prefs(context).getInt(K.KOTLIN_METASPACE_MB, 256)
    fun setKotlinMetaspaceMb(context: Context, v: Int) { prefs(context).edit().putInt(K.KOTLIN_METASPACE_MB, v.coerceIn(64, 1024)).apply() }

    fun maxWorkers(context: Context): Int = prefs(context).getInt(K.MAX_WORKERS, 2)
    fun setMaxWorkers(context: Context, v: Int) { prefs(context).edit().putInt(K.MAX_WORKERS, v.coerceIn(1, 8)).apply() }

    fun parallel(context: Context): Boolean = prefs(context).getBoolean(K.PARALLEL, true)
    fun setParallel(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.PARALLEL, v).apply() }

    fun buildCache(context: Context): Boolean = prefs(context).getBoolean(K.BUILD_CACHE, true)
    fun setBuildCache(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.BUILD_CACHE, v).apply() }

    // Daemon mode is enabled by default. GradleRunner injects the native
    // exec redirect into child processes so the daemon can start on Android.
    fun daemon(context: Context): Boolean = prefs(context).getBoolean(K.DAEMON, true)
    fun setDaemon(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.DAEMON, v).apply() }

    fun vfsWatch(context: Context): Boolean = prefs(context).getBoolean(K.VFS_WATCH, false)
    fun setVfsWatch(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.VFS_WATCH, v).apply() }

    fun console(context: Context): String = prefs(context).getString(K.CONSOLE, "plain") ?: "plain"
    fun setConsole(context: Context, v: String) { prefs(context).edit().putString(K.CONSOLE, v).apply() }

    fun customGradleJvmArgs(context: Context): String = prefs(context).getString(K.CUSTOM_GRADLE_JVM_ARGS, "") ?: ""
    fun setCustomGradleJvmArgs(context: Context, v: String) { prefs(context).edit().putString(K.CUSTOM_GRADLE_JVM_ARGS, v).apply() }

    fun gc(context: Context): String = prefs(context).getString(K.GC, "UseSerialGC") ?: "UseSerialGC"
    fun setGc(context: Context, v: String) { prefs(context).edit().putString(K.GC, v).apply() }

    fun heapDump(context: Context): Boolean = prefs(context).getBoolean(K.HEAP_DUMP, true)
    fun setHeapDump(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.HEAP_DUMP, v).apply() }

    fun gradleVerbose(context: Context): Boolean = prefs(context).getBoolean(K.GRADLE_VERBOSE, false)
    fun setGradleVerbose(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.GRADLE_VERBOSE, v).apply() }

    fun optimization(context: Context): String = prefs(context).getString(K.OPTIMIZATION, "-O2") ?: "-O2"
    fun setOptimization(context: Context, v: String) { prefs(context).edit().putString(K.OPTIMIZATION, v).apply() }

    fun targetApi(context: Context): Int = prefs(context).getInt(K.TARGET_API, 29)
    fun setTargetApi(context: Context, v: Int) { prefs(context).edit().putInt(K.TARGET_API, v.coerceIn(21, 35)).apply() }

    fun debugSymbols(context: Context): Boolean = prefs(context).getBoolean(K.DEBUG_SYMBOLS, true)
    fun setDebugSymbols(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.DEBUG_SYMBOLS, v).apply() }

    fun customCflags(context: Context): String = prefs(context).getString(K.CUSTOM_CFLAGS, "") ?: ""
    fun setCustomCflags(context: Context, v: String) { prefs(context).edit().putString(K.CUSTOM_CFLAGS, v).apply() }

    fun customLdflags(context: Context): String = prefs(context).getString(K.CUSTOM_LDFLAGS, "") ?: ""
    fun setCustomLdflags(context: Context, v: String) { prefs(context).edit().putString(K.CUSTOM_LDFLAGS, v).apply() }

    fun cppStandard(context: Context): String = prefs(context).getString(K.CPP_STANDARD, "") ?: ""
    fun setCppStandard(context: Context, v: String) { prefs(context).edit().putString(K.CPP_STANDARD, v).apply() }

    fun fpic(context: Context): Boolean = prefs(context).getBoolean(K.FPIC, true)
    fun setFpic(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.FPIC, v).apply() }

    fun fpie(context: Context): Boolean = prefs(context).getBoolean(K.FPIE, true)
    fun setFpie(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.FPIE, v).apply() }

    fun werror(context: Context): Boolean = prefs(context).getBoolean(K.WERROR, false)
    fun setWerror(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.WERROR, v).apply() }

    fun wall(context: Context): Boolean = prefs(context).getBoolean(K.WALL, true)
    fun setWall(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.WALL, v).apply() }

    fun strip(context: Context): Boolean = prefs(context).getBoolean(K.STRIP, false)
    fun setStrip(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.STRIP, v).apply() }

    fun linker(context: Context): String = prefs(context).getString(K.LINKER, "") ?: ""
    fun setLinker(context: Context, v: String) { prefs(context).edit().putString(K.LINKER, v).apply() }

    fun customJvmArgs(context: Context): String = prefs(context).getString(K.CUSTOM_JVM_ARGS, "") ?: ""
    fun setCustomJvmArgs(context: Context, v: String) { prefs(context).edit().putString(K.CUSTOM_JVM_ARGS, v).apply() }

    fun fileEncoding(context: Context): String = prefs(context).getString(K.FILE_ENCODING, "UTF-8") ?: "UTF-8"
    fun setFileEncoding(context: Context, v: String) { prefs(context).edit().putString(K.FILE_ENCODING, v).apply() }

    fun userCountry(context: Context): String = prefs(context).getString(K.USER_COUNTRY, "US") ?: "US"
    fun setUserCountry(context: Context, v: String) { prefs(context).edit().putString(K.USER_COUNTRY, v).apply() }

    fun userLanguage(context: Context): String = prefs(context).getString(K.USER_LANGUAGE, "en") ?: "en"
    fun setUserLanguage(context: Context, v: String) { prefs(context).edit().putString(K.USER_LANGUAGE, v).apply() }

    fun addOpens(context: Context): Boolean = prefs(context).getBoolean(K.ADD_OPENS, true)
    fun setAddOpens(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.ADD_OPENS, v).apply() }

    fun ldLibraryPathExtra(context: Context): String = prefs(context).getString(K.LD_LIBRARY_PATH_EXTRA, "") ?: ""
    fun setLdLibraryPathExtra(context: Context, v: String) { prefs(context).edit().putString(K.LD_LIBRARY_PATH_EXTRA, v).apply() }

    fun kotlinDaemonEnabled(context: Context): Boolean = prefs(context).getBoolean(K.KOTLIN_DAEMON_ENABLED, true)
    fun setKotlinDaemonEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.KOTLIN_DAEMON_ENABLED, v).apply() }

    fun kotlinDaemonJvmArgs(context: Context): String = prefs(context).getString(K.KOTLIN_DAEMON_JVMARGS, "") ?: ""
    fun setKotlinDaemonJvmArgs(context: Context, v: String) { prefs(context).edit().putString(K.KOTLIN_DAEMON_JVMARGS, v).apply() }

    fun kotlinIncremental(context: Context): Boolean = prefs(context).getBoolean(K.KOTLIN_INCREMENTAL, true)
    fun setKotlinIncremental(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.KOTLIN_INCREMENTAL, v).apply() }

    // ── Java/Kotlin Compiler ─────────────────────────────────────────

    fun javaSourceCompat(context: Context): String = prefs(context).getString(K.JAVA_SOURCE_COMPAT, "17") ?: "17"
    fun setJavaSourceCompat(context: Context, v: String) { prefs(context).edit().putString(K.JAVA_SOURCE_COMPAT, v).apply() }

    fun javaTargetCompat(context: Context): String = prefs(context).getString(K.JAVA_TARGET_COMPAT, "17") ?: "17"
    fun setJavaTargetCompat(context: Context, v: String) { prefs(context).edit().putString(K.JAVA_TARGET_COMPAT, v).apply() }

    fun kotlinJvmTarget(context: Context): String = prefs(context).getString(K.KOTLIN_JVM_TARGET, "17") ?: "17"
    fun setKotlinJvmTarget(context: Context, v: String) { prefs(context).edit().putString(K.KOTLIN_JVM_TARGET, v).apply() }

    fun kotlinApiVersion(context: Context): String = prefs(context).getString(K.KOTLIN_API_VERSION, "1.8") ?: "1.8"
    fun setKotlinApiVersion(context: Context, v: String) { prefs(context).edit().putString(K.KOTLIN_API_VERSION, v).apply() }

    fun kotlinLanguageVersion(context: Context): String = prefs(context).getString(K.KOTLIN_LANGUAGE_VERSION, "1.8") ?: "1.8"
    fun setKotlinLanguageVersion(context: Context, v: String) { prefs(context).edit().putString(K.KOTLIN_LANGUAGE_VERSION, v).apply() }

    fun kotlinCompilerHeapMb(context: Context): Int = prefs(context).getInt(K.KOTLIN_COMPILER_HEAP_MB, 512)
    fun setKotlinCompilerHeapMb(context: Context, v: Int) { prefs(context).edit().putInt(K.KOTLIN_COMPILER_HEAP_MB, v.coerceIn(128, 2048)).apply() }

    // ── APK Signing ──────────────────────────────────────────────────

    fun signingKeyAlias(context: Context): String = prefs(context).getString(K.SIGNING_KEY_ALIAS, "androiddebugkey") ?: "androiddebugkey"
    fun setSigningKeyAlias(context: Context, v: String) { prefs(context).edit().putString(K.SIGNING_KEY_ALIAS, v).apply() }

    fun signingStorePassword(context: Context): String = prefs(context).getString(K.SIGNING_STORE_PASSWORD, "android") ?: "android"
    fun setSigningStorePassword(context: Context, v: String) { prefs(context).edit().putString(K.SIGNING_STORE_PASSWORD, v).apply() }

    fun signingKeyPassword(context: Context): String = prefs(context).getString(K.SIGNING_KEY_PASSWORD, "android") ?: "android"
    fun setSigningKeyPassword(context: Context, v: String) { prefs(context).edit().putString(K.SIGNING_KEY_PASSWORD, v).apply() }

    fun signingAlgorithm(context: Context): String = prefs(context).getString(K.SIGNING_ALGORITHM, "RSA") ?: "RSA"
    fun setSigningAlgorithm(context: Context, v: String) { prefs(context).edit().putString(K.SIGNING_ALGORITHM, v).apply() }

    fun signingKeySize(context: Context): Int = prefs(context).getInt(K.SIGNING_KEY_SIZE, 2048)
    fun setSigningKeySize(context: Context, v: Int) { prefs(context).edit().putInt(K.SIGNING_KEY_SIZE, v.coerceIn(1024, 4096)).apply() }

    fun signingValidityDays(context: Context): Int = prefs(context).getInt(K.SIGNING_VALIDITY_DAYS, 10000)
    fun setSigningValidityDays(context: Context, v: Int) { prefs(context).edit().putInt(K.SIGNING_VALIDITY_DAYS, v.coerceIn(365, 36500)).apply() }

    fun signingDn(context: Context): String = prefs(context).getString(K.SIGNING_DN, "CN=Android Debug,O=Android,C=US") ?: "CN=Android Debug,O=Android,C=US"
    fun setSigningDn(context: Context, v: String) { prefs(context).edit().putString(K.SIGNING_DN, v).apply() }

    // ── CMake / NDK ──────────────────────────────────────────────────

    fun cmakeBuildType(context: Context): String = prefs(context).getString(K.CMAKE_BUILD_TYPE, "Release") ?: "Release"
    fun setCmakeBuildType(context: Context, v: String) { prefs(context).edit().putString(K.CMAKE_BUILD_TYPE, v).apply() }

    fun cmakeGenerator(context: Context): String = prefs(context).getString(K.CMAKE_GENERATOR, "") ?: ""
    fun setCmakeGenerator(context: Context, v: String) { prefs(context).edit().putString(K.CMAKE_GENERATOR, v).apply() }

    fun ndkStl(context: Context): String = prefs(context).getString(K.NDK_STL, "c++_shared") ?: "c++_shared"
    fun setNdkStl(context: Context, v: String) { prefs(context).edit().putString(K.NDK_STL, v).apply() }

    fun ndkAbiFilters(context: Context): String = prefs(context).getString(K.NDK_ABI_FILTERS, "arm64-v8a") ?: "arm64-v8a"
    fun setNdkAbiFilters(context: Context, v: String) { prefs(context).edit().putString(K.NDK_ABI_FILTERS, v).apply() }

    // ── Template Defaults ────────────────────────────────────────────

    fun templateMinSdk(context: Context): Int = prefs(context).getInt(K.TEMPLATE_MIN_SDK, 24)
    fun setTemplateMinSdk(context: Context, v: Int) { prefs(context).edit().putInt(K.TEMPLATE_MIN_SDK, v.coerceIn(21, 35)).apply() }

    fun templateTargetSdk(context: Context): Int = prefs(context).getInt(K.TEMPLATE_TARGET_SDK, 34)
    fun setTemplateTargetSdk(context: Context, v: Int) { prefs(context).edit().putInt(K.TEMPLATE_TARGET_SDK, v.coerceIn(28, 35)).apply() }

    fun templateCompileSdk(context: Context): Int = prefs(context).getInt(K.TEMPLATE_COMPILE_SDK, 34)
    fun setTemplateCompileSdk(context: Context, v: Int) { prefs(context).edit().putInt(K.TEMPLATE_COMPILE_SDK, v.coerceIn(28, 35)).apply() }

    fun templateKotlinVersion(context: Context): String = prefs(context).getString(K.TEMPLATE_KOTLIN_VERSION, "2.0.0") ?: "2.0.0"
    fun setTemplateKotlinVersion(context: Context, v: String) { prefs(context).edit().putString(K.TEMPLATE_KOTLIN_VERSION, v).apply() }

    fun templateAgpVersion(context: Context): String = prefs(context).getString(K.TEMPLATE_AGP_VERSION, "9.3.0") ?: "9.3.0"
    fun setTemplateAgpVersion(context: Context, v: String) { prefs(context).edit().putString(K.TEMPLATE_AGP_VERSION, v).apply() }

    fun templateReleaseMinify(context: Context): Boolean = prefs(context).getBoolean(K.TEMPLATE_RELEASE_MINIFY, true)
    fun setTemplateReleaseMinify(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.TEMPLATE_RELEASE_MINIFY, v).apply() }

    fun templateReleaseShrink(context: Context): Boolean = prefs(context).getBoolean(K.TEMPLATE_RELEASE_SHRINK, false)
    fun setTemplateReleaseShrink(context: Context, v: Boolean) { prefs(context).edit().putBoolean(K.TEMPLATE_RELEASE_SHRINK, v).apply() }

    // ── Analysis Engine ──────────────────────────────────────────────

    fun analysisDaemonHeapMb(context: Context): Int = prefs(context).getInt(K.ANALYSIS_DAEMON_HEAP_MB, 64)
    fun setAnalysisDaemonHeapMb(context: Context, v: Int) { prefs(context).edit().putInt(K.ANALYSIS_DAEMON_HEAP_MB, v.coerceIn(16, 512)).apply() }

    fun analysisCompilerTimeoutMs(context: Context): Long = prefs(context).getLong(K.ANALYSIS_COMPILER_TIMEOUT_MS, 10_000L)
    fun setAnalysisCompilerTimeoutMs(context: Context, v: Long) { prefs(context).edit().putLong(K.ANALYSIS_COMPILER_TIMEOUT_MS, v.coerceIn(1000L, 120_000L)).apply() }

    fun analysisDebounceMs(context: Context): Long = prefs(context).getLong(K.ANALYSIS_DEBOUNCE_MS, 300L)
    fun setAnalysisDebounceMs(context: Context, v: Long) { prefs(context).edit().putLong(K.ANALYSIS_DEBOUNCE_MS, v.coerceIn(100L, 5000L)).apply() }

    fun analysisCacheTtlMs(context: Context): Long = prefs(context).getLong(K.ANALYSIS_CACHE_TTL_MS, 30_000L)
    fun setAnalysisCacheTtlMs(context: Context, v: Long) { prefs(context).edit().putLong(K.ANALYSIS_CACHE_TTL_MS, v.coerceIn(5000L, 300_000L)).apply() }

    fun analysisCacheMaxEntries(context: Context): Int = prefs(context).getInt(K.ANALYSIS_CACHE_MAX_ENTRIES, 200)
    fun setAnalysisCacheMaxEntries(context: Context, v: Int) { prefs(context).edit().putInt(K.ANALYSIS_CACHE_MAX_ENTRIES, v.coerceIn(10, 2000)).apply() }

    fun clangTimeoutMs(context: Context): Long = prefs(context).getLong(K.CLANG_TIMEOUT_MS, 10_000L)
    fun setClangTimeoutMs(context: Context, v: Long) { prefs(context).edit().putLong(K.CLANG_TIMEOUT_MS, v.coerceIn(1000L, 60_000L)).apply() }

    fun clangdTimeoutMs(context: Context): Long = prefs(context).getLong(K.CLANGD_TIMEOUT_MS, 5_000L)
    fun setClangdTimeoutMs(context: Context, v: Long) { prefs(context).edit().putLong(K.CLANGD_TIMEOUT_MS, v.coerceIn(1000L, 30_000L)).apply() }

    // ── Shell / Build Limits ─────────────────────────────────────────

    fun shellDefaultTimeoutMs(context: Context): Long = prefs(context).getLong(K.SHELL_DEFAULT_TIMEOUT_MS, 1_800_000L)
    fun setShellDefaultTimeoutMs(context: Context, v: Long) { prefs(context).edit().putLong(K.SHELL_DEFAULT_TIMEOUT_MS, v.coerceIn(60_000L, 7_200_000L)).apply() }

    fun shellMaxOutputLines(context: Context): Int = prefs(context).getInt(K.SHELL_MAX_OUTPUT_LINES, 8_000)
    fun setShellMaxOutputLines(context: Context, v: Int) { prefs(context).edit().putInt(K.SHELL_MAX_OUTPUT_LINES, v.coerceIn(500, 50_000)).apply() }

    fun buildMaxCapturedChars(context: Context): Int = prefs(context).getInt(K.BUILD_MAX_CAPTURED_CHARS, 1_200_000)
    fun setBuildMaxCapturedChars(context: Context, v: Int) { prefs(context).edit().putInt(K.BUILD_MAX_CAPTURED_CHARS, v.coerceIn(100_000, 10_000_000)).apply() }

    fun buildFailureCooldownMs(context: Context): Long = prefs(context).getLong(K.BUILD_FAILURE_COOLDOWN_MS, 30_000L)
    fun setBuildFailureCooldownMs(context: Context, v: Long) { prefs(context).edit().putLong(K.BUILD_FAILURE_COOLDOWN_MS, v.coerceIn(5_000L, 300_000L)).apply() }

    // ── Memory / Layout Preview ──────────────────────────────────────

    fun memoryMonitorIntervalMs(context: Context): Long = prefs(context).getLong(K.MEMORY_MONITOR_INTERVAL_MS, 5_000L)
    fun setMemoryMonitorIntervalMs(context: Context, v: Long) { prefs(context).edit().putLong(K.MEMORY_MONITOR_INTERVAL_MS, v.coerceIn(5_000L, 120_000L)).apply() }

    fun memoryHeapPressureThreshold(context: Context): Float = prefs(context).getFloat(K.MEMORY_HEAP_PRESSURE_THRESHOLD, 0.85f)
    fun setMemoryHeapPressureThreshold(context: Context, v: Float) { prefs(context).edit().putFloat(K.MEMORY_HEAP_PRESSURE_THRESHOLD, v.coerceIn(0.5f, 0.98f)).apply() }

    fun layoutPreviewHeapMb(context: Context): Int = prefs(context).getInt(K.LAYOUT_PREVIEW_HEAP_MB, 384)
    fun setLayoutPreviewHeapMb(context: Context, v: Int) { prefs(context).edit().putInt(K.LAYOUT_PREVIEW_HEAP_MB, v.coerceIn(128, 2048)).apply() }

    fun layoutPreviewTimeoutMs(context: Context): Long = prefs(context).getLong(K.LAYOUT_PREVIEW_TIMEOUT_MS, 12_000L)
    fun setLayoutPreviewTimeoutMs(context: Context, v: Long) { prefs(context).edit().putLong(K.LAYOUT_PREVIEW_TIMEOUT_MS, v.coerceIn(3_000L, 60_000L)).apply() }

    // ── Derived helpers ────────────────────────────────────────────────

    fun addOpensArgs(context: Context): List<String> {
        if (!addOpens(context)) return emptyList()
        return listOf(
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED",
            "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
        )
    }

    fun buildCflags(context: Context): List<String> = buildList {
        add(optimization(context))
        if (debugSymbols(context)) add("-g")
        if (fpic(context)) add("-fPIC")
        if (fpie(context)) add("-fPIE")
        if (wall(context)) add("-Wall")
        if (werror(context)) add("-Werror")
        val cppStd = cppStandard(context)
        if (cppStd.isNotBlank()) add("-std=$cppStd")
        val extra = customCflags(context)
        if (extra.isNotBlank()) addAll(extra.trim().split("\\s+".toRegex()))
    }

    fun buildLdflags(context: Context): List<String> = buildList {
        val extra = customLdflags(context)
        if (extra.isNotBlank()) addAll(extra.trim().split("\\s+".toRegex()))
    }

    fun buildGradleJvmArgs(context: Context): List<String> = buildList {
        addAll(addOpensArgs(context))
        add("-Xmx${gradleHeapMb(context)}m")
        add("-XX:MaxMetaspaceSize=${gradleMetaspaceMb(context)}m")
        val gcVal = gc(context)
        if (gcVal.isNotBlank()) add("-XX:+$gcVal")
        if (heapDump(context)) add("-XX:+HeapDumpOnOutOfMemoryError")
        add("-Dfile.encoding=${fileEncoding(context)}")
        add("-Duser.country=${userCountry(context)}")
        add("-Duser.language=${userLanguage(context)}")
        add("-Duser.variant")
        add("-Dmobilestudio.gradle.env=jdk-cmake-tool-proxy-v29")
        add("-Djdk.lang.Process.launchMechanism=FORK")
        add("-Dorg.gradle.native=false")
        val custom = customGradleJvmArgs(context)
        if (custom.isNotBlank()) addAll(custom.trim().split("\\s+".toRegex()))
    }

    fun buildGradleJvmArgsString(context: Context): String {
        val base = buildList {
            addAll(addOpensArgs(context))
            add("-Xmx${gradleHeapMb(context)}m")
            add("-XX:MaxMetaspaceSize=${gradleMetaspaceMb(context)}m")
            val gcVal = gc(context)
            if (gcVal.isNotBlank()) add("-XX:+$gcVal")
            add("-Dfile.encoding=${fileEncoding(context)}")
            add("-Dorg.gradle.native=false")
            val custom = customGradleJvmArgs(context)
            if (custom.isNotBlank()) addAll(custom.trim().split("\\s+".toRegex()))
        }
        return base.joinToString(" ")
    }

    fun buildGradleCliArgs(context: Context): List<String> = buildList {
        if (daemon(context)) add("--daemon") else add("--no-daemon")
        if (parallel(context)) add("--parallel")
        if (buildCache(context)) add("--build-cache")
        add("--max-workers=${maxWorkers(context)}")
        add("--console=${console(context)}")
    }

    fun buildKotlinDaemonArgs(context: Context): String {
        // Explicit kotlin.daemon.jvmargs-style custom args win (comma-separated),
        // otherwise fall back to the MobileStudio memory policy (heap/metaspace prefs).
        val custom = kotlinDaemonJvmArgs(context)
        if (custom.isNotBlank()) return custom.trim().removePrefix("-Dkotlin.daemon.jvmargs=").trim()
        val heap = kotlinHeapMb(context)
        val meta = kotlinMetaspaceMb(context)
        return "-Xmx${heap}m,-XX:MaxMetaspaceSize=${meta}m"
    }

    // ── Reset ──────────────────────────────────────────────────────────

    fun resetAll(context: Context) { prefs(context).edit().clear().apply() }

    fun resetGradleDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.GRADLE_HEAP_MB); remove(K.GRADLE_METASPACE_MB)
            remove(K.KOTLIN_HEAP_MB); remove(K.KOTLIN_METASPACE_MB)
            remove(K.MAX_WORKERS); remove(K.PARALLEL); remove(K.BUILD_CACHE)
            remove(K.DAEMON); remove(K.VFS_WATCH); remove(K.CONSOLE)
            remove(K.CUSTOM_GRADLE_JVM_ARGS); remove(K.GC)
            remove(K.HEAP_DUMP); remove(K.GRADLE_VERBOSE)
            apply()
        }
    }

    fun resetCompilerDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.JAVA_SOURCE_COMPAT); remove(K.JAVA_TARGET_COMPAT)
            remove(K.KOTLIN_JVM_TARGET); remove(K.KOTLIN_API_VERSION)
            remove(K.KOTLIN_LANGUAGE_VERSION); remove(K.KOTLIN_COMPILER_HEAP_MB)
            remove(K.KOTLIN_DAEMON_ENABLED); remove(K.KOTLIN_INCREMENTAL)
            remove(K.KOTLIN_DAEMON_JVMARGS)
            apply()
        }
    }

    fun resetSigningDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.SIGNING_KEY_ALIAS); remove(K.SIGNING_STORE_PASSWORD)
            remove(K.SIGNING_KEY_PASSWORD); remove(K.SIGNING_ALGORITHM)
            remove(K.SIGNING_KEY_SIZE); remove(K.SIGNING_VALIDITY_DAYS)
            remove(K.SIGNING_DN)
            apply()
        }
    }

    fun resetClangDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.OPTIMIZATION); remove(K.TARGET_API); remove(K.DEBUG_SYMBOLS)
            remove(K.CUSTOM_CFLAGS); remove(K.CUSTOM_LDFLAGS); remove(K.CPP_STANDARD)
            remove(K.FPIC); remove(K.FPIE); remove(K.WERROR)
            remove(K.WALL); remove(K.STRIP); remove(K.LINKER)
            remove(K.CMAKE_BUILD_TYPE); remove(K.CMAKE_GENERATOR)
            remove(K.NDK_STL); remove(K.NDK_ABI_FILTERS)
            apply()
        }
    }

    fun resetTemplateDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.TEMPLATE_MIN_SDK); remove(K.TEMPLATE_TARGET_SDK)
            remove(K.TEMPLATE_COMPILE_SDK); remove(K.TEMPLATE_KOTLIN_VERSION)
            remove(K.TEMPLATE_AGP_VERSION); remove(K.TEMPLATE_RELEASE_MINIFY)
            remove(K.TEMPLATE_RELEASE_SHRINK)
            apply()
        }
    }

    fun resetJdkDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.CUSTOM_JVM_ARGS); remove(K.FILE_ENCODING)
            remove(K.USER_COUNTRY); remove(K.USER_LANGUAGE)
            remove(K.ADD_OPENS); remove(K.LD_LIBRARY_PATH_EXTRA)
            apply()
        }
    }

    fun resetAnalysisDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.ANALYSIS_DAEMON_HEAP_MB); remove(K.ANALYSIS_COMPILER_TIMEOUT_MS)
            remove(K.ANALYSIS_DEBOUNCE_MS); remove(K.ANALYSIS_CACHE_TTL_MS)
            remove(K.ANALYSIS_CACHE_MAX_ENTRIES); remove(K.CLANG_TIMEOUT_MS)
            remove(K.CLANGD_TIMEOUT_MS)
            apply()
        }
    }

    fun resetShellDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.SHELL_DEFAULT_TIMEOUT_MS); remove(K.SHELL_MAX_OUTPUT_LINES)
            remove(K.BUILD_MAX_CAPTURED_CHARS); remove(K.BUILD_FAILURE_COOLDOWN_MS)
            apply()
        }
    }

    fun resetMemoryDefaults(context: Context) {
        prefs(context).edit().apply {
            remove(K.MEMORY_MONITOR_INTERVAL_MS); remove(K.MEMORY_HEAP_PRESSURE_THRESHOLD)
            remove(K.LAYOUT_PREVIEW_HEAP_MB); remove(K.LAYOUT_PREVIEW_TIMEOUT_MS)
            apply()
        }
    }
}
