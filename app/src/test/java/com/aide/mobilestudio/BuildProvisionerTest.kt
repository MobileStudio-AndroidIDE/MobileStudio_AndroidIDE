package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure decision logic of [BuildProvisioner] — the lazy build-time tool
 * provisioning. No network, no Android context: only file-system detection
 * and version choice.
 */
class BuildProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- needsNativeTooling ----------------

    @Test
    fun emptyProjectNeedsNoNativeTooling() {
        val root = tmp.newFolder("empty")
        assertFalse(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun appCppCMakeListsDetected() {
        val root = tmp.newFolder("proj")
        File(root, "app/src/main/cpp").mkdirs()
        File(root, "app/src/main/cpp/CMakeLists.txt").writeText("cmake_minimum_required(VERSION 3.22)")
        assertTrue(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun moduleCppCMakeListsDetected() {
        val root = tmp.newFolder("proj2")
        File(root, "feature/src/main/cpp").mkdirs()
        File(root, "feature/src/main/cpp/CMakeLists.txt").writeText("project(native)")
        assertTrue(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun singleModuleCppCMakeListsDetected() {
        val root = tmp.newFolder("proj3")
        File(root, "src/main/cpp").mkdirs()
        File(root, "src/main/cpp/CMakeLists.txt").writeText("project(native)")
        assertTrue(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun externalNativeBuildInModuleBuildScriptDetected() {
        val root = tmp.newFolder("proj4")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            android {
                externalNativeBuild { cmake { path = file("CMakeLists.txt") } }
            }
            """.trimIndent()
        )
        assertTrue(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun externalNativeBuildInRootBuildScriptDetected() {
        val root = tmp.newFolder("proj4b")
        File(root, "build.gradle").writeText(
            "android { externalNativeBuild { ndkBuild { path 'Android.mk' } } }"
        )
        assertTrue(BuildProvisioner.needsNativeTooling(root))
    }

    @Test
    fun plainKotlinProjectNeedsNoNativeTooling() {
        val root = tmp.newFolder("proj5")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle").writeText(
            """
            android {
                compileSdk 34
                defaultConfig { minSdk 24 }
            }
            """.trimIndent()
        )
        File(root, "app/src/main/java/com/example").mkdirs()
        File(root, "app/src/main/java/com/example/A.kt").writeText("class A")
        assertFalse(BuildProvisioner.needsNativeTooling(root))
    }

    // ---------------- requiredJavaMajor ----------------

    @Test
    fun noConstraintYieldsNull() {
        val root = tmp.newFolder("jdk0")
        File(root, "build.gradle").writeText("android { compileSdk 34 }")
        assertNull(BuildProvisioner.requiredJavaMajor(root))
    }

    @Test
    fun jvmTarget17Detected() {
        val root = tmp.newFolder("jdk17")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText("kotlinOptions { jvmTarget = \"17\" }")
        assertEquals(17, BuildProvisioner.requiredJavaMajor(root))
    }

    @Test
    fun javaVersion21Detected() {
        val root = tmp.newFolder("jdk21")
        File(root, "build.gradle").writeText(
            "compileOptions { sourceCompatibility JavaVersion.VERSION_21 }"
        )
        assertEquals(21, BuildProvisioner.requiredJavaMajor(root))
    }

    @Test
    fun conflictingHintsPreferNewer() {
        val root = tmp.newFolder("jdkboth")
        File(root, "app").mkdirs()
        File(root, "app/build.gradle").writeText("jvmTarget = \"17\"")
        File(root, "lib").mkdirs()
        File(root, "lib/build.gradle").writeText("jvmTarget = \"21\"")
        assertEquals(21, BuildProvisioner.requiredJavaMajor(root))
    }

    @Test
    fun otherJavaVersionsIgnored() {
        val root = tmp.newFolder("jdk11")
        File(root, "build.gradle").writeText("jvmTarget = \"11\"")
        assertNull(BuildProvisioner.requiredJavaMajor(root))
    }

    // ---------------- newestSupportedNdk ----------------

    @Test
    fun picksNewestVersionOfferedByToolchainManager() {
        val newest = BuildProvisioner.newestSupportedNdk(
            ToolchainManager.AVAILABLE_NDK,
            NdkToolchain.GITHUB_NDK_VERSIONS
        )
        assertEquals("29.0.14206865", newest)
    }

    @Test
    fun candidatesOutsideInstallerSupportAreFiltered() {
        val picked = BuildProvisioner.newestSupportedNdk(
            listOf("26.0.1", "29.0.14206865", "30.0.99999"),
            listOf("29.0.14206865")
        )
        assertEquals("29.0.14206865", picked)
    }

    @Test
    fun emptyCandidatesYieldNull() {
        assertNull(BuildProvisioner.newestSupportedNdk(emptyList(), listOf("29.0.14206865")))
    }

    @Test
    fun noOverlapYieldsNull() {
        assertNull(BuildProvisioner.newestSupportedNdk(listOf("26.0.1"), listOf("29.0.14206865")))
    }
}
