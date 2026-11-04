package com.aide.mobilestudio

import java.io.File

/**
 * Picks the APK a build actually produced.
 *
 * `build/outputs/apk/` also holds the *instrumented-test* APK
 * (`apk/androidTest/debug/app-debug-androidTest.apk`) whenever androidTest
 * sources exist. After a test run it is newer than `app-debug.apk`, so "newest
 * .apk anywhere in the tree" hands the user the test harness instead of their
 * app — which is exactly what "NDK 프로젝트를 빌드하면 테스트 전용으로 빌드됨"
 * looked like. The same trap existed in the Tools APK list and the signing
 * screen, so the rule lives here once.
 */
object BuildApkLocator {

    /** Any path component named `androidTest`. */
    private val TEST_PATH = Regex("(^|/)androidTest(/|$)")

    /** AGP names test APKs `app-debug-androidTest.apk` / `app-release-androidTest.apk`. */
    private val TEST_NAME = Regex("-androidTest([-.].*)?\\.apk$", RegexOption.IGNORE_CASE)

    fun isTestApk(file: File): Boolean =
        TEST_PATH.containsMatchIn(file.invariantSeparatorsPath) ||
            TEST_NAME.containsMatchIn(file.name)

    fun isInstallableApk(file: File): Boolean =
        file.isFile && file.extension.equals("apk", ignoreCase = true) && !isTestApk(file)

    /**
     * Newest installable APK across [roots], preferring the canonical
     * `build/outputs/apk` tree when two candidates share a timestamp.
     */
    fun latestInstallableApk(roots: List<File>): File? {
        val candidates = roots.asSequence()
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { isInstallableApk(it) }
            .toList()
        return candidates
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { isCanonicalOutput(it).not() }
            )
            .firstOrNull()
    }

    /** Newest installable APK under [root], or null. */
    fun latestInstallableApk(root: File?): File? =
        root?.let { latestInstallableApk(listOf(it)) }

    /** Every installable APK under [root], newest first. */
    fun allInstallableApks(root: File?): List<File> {
        if (root == null || !root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { isInstallableApk(it) }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    private fun isCanonicalOutput(file: File): Boolean =
        file.invariantSeparatorsPath.contains("build/outputs/apk/")
}
