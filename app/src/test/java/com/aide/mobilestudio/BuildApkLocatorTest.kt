package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The instrumented-test APK lives in the same outputs tree as the app APK and
 * is newer after a test run. Picking "newest .apk" therefore hands the user
 * the test harness instead of their app.
 */
class BuildApkLocatorTest {

    private fun tempDir(): File = File.createTempFile("apks", "").apply { delete(); mkdirs() }

    @Test
    fun `androidTest apk is recognised by its path`() {
        val f = File("/p/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        assertTrue(BuildApkLocator.isTestApk(f))
        assertFalse(BuildApkLocator.isInstallableApk(f))
    }

    @Test
    fun `androidTest apk is recognised by its filename alone`() {
        val f = File("/tmp/app-debug-androidTest.apk")
        assertTrue(BuildApkLocator.isTestApk(f))
        assertFalse(BuildApkLocator.isInstallableApk(f))
    }

    @Test
    fun `plain debug apk is installable`() {
        val root = tempDir()
        val f = File(root, "app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1))
        }
        assertFalse(BuildApkLocator.isTestApk(f))
        assertTrue(BuildApkLocator.isInstallableApk(f))
    }

    @Test
    fun `non existent file is never installable`() {
        assertFalse(BuildApkLocator.isInstallableApk(File("/definitely/not/here/app-debug.apk")))
    }

    @Test
    fun `newest non-test apk wins even when the test apk is newer`() {
        val root = tempDir()
        val app = File(root, "app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1))
        }
        val test = File(root, "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(2))
            // test APK was produced after the app APK, as happens after a test run
            setLastModified(app.lastModified() + 60_000)
        }
        assertEquals(app, BuildApkLocator.latestInstallableApk(root))
        assertEquals(listOf(app), BuildApkLocator.allInstallableApks(root))
        // and the test apk is not silently treated as an installable candidate
        assertTrue(test.isFile)
    }

    @Test
    fun `canonical output path is preferred on a timestamp tie`() {
        val root = tempDir()
        val canonical = File(root, "app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(1))
        }
        val stray = File(root, "some/other/place/app-debug.apk").apply {
            parentFile.mkdirs(); writeBytes(byteArrayOf(2))
            setLastModified(canonical.lastModified())
        }
        assertEquals(canonical, BuildApkLocator.latestInstallableApk(root))
        assertTrue(stray.isFile)
    }

    @Test
    fun `missing root yields nothing`() {
        assertNull(BuildApkLocator.latestInstallableApk(null))
        assertNull(BuildApkLocator.latestInstallableApk(File("/definitely/not/here")))
        assertEquals(emptyList<File>(), BuildApkLocator.allInstallableApks(null))
    }
}
