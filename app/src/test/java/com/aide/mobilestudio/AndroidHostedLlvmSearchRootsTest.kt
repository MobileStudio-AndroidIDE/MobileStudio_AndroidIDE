package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the "NDK is installed but the build downloads the
 * toolchain again" bug.
 *
 * [AndroidHostedLlvmInstaller.hasUsableCompiler] decides whether to download
 * the ~1100MB Android LLVM bundle by probing every search root with
 * [AndroidHostedLlvmInstaller.compilerDirIfUsable]. The NDK directories were
 * missing from that list, so an installed NDK - which ships the very same clang
 * - was invisible to the probe. The build then reported "no compiler" and
 * re-downloaded the bundle on every native build.
 *
 * These tests pin both halves: the roots must include an NDK wherever the app
 * installs one, and the probe must see the clang inside it while still
 * rejecting a desktop-hosted toolchain that cannot execute on the device.
 */
class AndroidHostedLlvmSearchRootsTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("ms-llvm").toFile().also { tempDirs += it }

    /** Creates a directory holding both clang binaries, so a root is usable. */
    private fun touchCompiler(dir: File) {
        File(dir, "bin").mkdirs()
        File(dir, "bin/clang").writeText("elf")
        File(dir, "bin/clang++").writeText("elf")
    }

    // ---------------------------------------------------------------- roots --

    @Test
    fun `an NDK under toolchain-ndk is a search root`() {
        val filesDir = tempDir()
        val ndk = File(filesDir, "toolchain/ndk/android-ndk-r27c-arm64").apply { mkdirs() }

        assertEquals(listOf(ndk), AndroidHostedLlvmInstaller.ndkSearchRoots(filesDir))
    }

    @Test
    fun `an SDK-installed NDK is a search root`() {
        val filesDir = tempDir()
        val ndk = File(filesDir, "toolchain/sdk/android-34/ndk/27.0.12077973").apply { mkdirs() }

        assertEquals(listOf(ndk), AndroidHostedLlvmInstaller.ndkSearchRoots(filesDir))
    }

    @Test
    fun `both NDK layouts are reported together`() {
        val filesDir = tempDir()
        val github = File(filesDir, "toolchain/ndk/android-ndk-r27c-arm64").apply { mkdirs() }
        val sdk = File(filesDir, "toolchain/sdk/android-35/ndk/28.0.13004108").apply { mkdirs() }
        // A plain file must not be mistaken for a root.
        File(filesDir, "toolchain/ndk/README").writeText("not an ndk")

        assertEquals(listOf(github, sdk), AndroidHostedLlvmInstaller.ndkSearchRoots(filesDir))
    }

    @Test
    fun `an empty files dir yields no roots`() {
        assertEquals(
            emptyList<File>(),
            AndroidHostedLlvmInstaller.ndkSearchRoots(tempDir()),
        )
    }

    // ----------------------------------------------------------------- probe --

    @Test
    fun `the clang inside an NDK is found`() {
        val ndk = tempDir()
        val hostDir = File(ndk, "toolchains/llvm/prebuilt/linux-bionic-arm64")
        touchCompiler(hostDir)

        assertEquals(hostDir, AndroidHostedLlvmInstaller.compilerDirIfUsable(ndk))
    }

    @Test
    fun `a desktop-hosted toolchain is rejected`() {
        // This is exactly the shape a stock SDK NDK has. Its clang is an x86_64
        // ELF, so finding it would be worse than finding nothing: the build
        // would proceed and then fail to exec.
        val ndk = tempDir()
        touchCompiler(File(ndk, "toolchains/llvm/prebuilt/linux-x86_64"))

        assertNull(AndroidHostedLlvmInstaller.compilerDirIfUsable(ndk))
    }

    @Test
    fun `an ndk without any compiler is not usable`() {
        val ndk = tempDir()
        File(ndk, "toolchains/llvm/prebuilt/linux-bionic-arm64").mkdirs()
        // source.properties exists but no binaries: a partial extraction.
        File(ndk, "source.properties").writeText("Pkg.Desc = Android NDK")

        assertNull(AndroidHostedLlvmInstaller.compilerDirIfUsable(ndk))
    }

    @Test
    fun `a missing directory is not usable`() {
        assertNull(
            AndroidHostedLlvmInstaller.compilerDirIfUsable(File(tempDir(), "does-not-exist")),
        )
    }

    @Test
    fun `a clang that is not both clang and clang-plus-plus is not usable`() {
        val ndk = tempDir()
        val prebuilt = File(ndk, "toolchains/llvm/prebuilt/linux-bionic-arm64")
        File(prebuilt, "bin").mkdirs()
        File(prebuilt, "bin/clang").writeText("elf") // clang++ missing

        assertNull(AndroidHostedLlvmInstaller.compilerDirIfUsable(ndk))
    }

    // ------------------------------------------- the whole decision, together --

    /**
     * The probe [AndroidHostedLlvmInstaller.hasUsableCompiler] runs: every root
     * against [AndroidHostedLlvmInstaller.compilerDirIfUsable]. Kept in sync by
     * going through the same two functions rather than a reimplementation, so a
     * change to either is reflected here.
     */
    private fun anyRootUsable(filesDir: File): Boolean =
        AndroidHostedLlvmInstaller.ndkSearchRoots(filesDir)
            .any { AndroidHostedLlvmInstaller.compilerDirIfUsable(it) != null }

    @Test
    fun `an installed NDK stops the toolchain download`() {
        val filesDir = tempDir()
        val ndk = File(filesDir, "toolchain/ndk/android-ndk-r27c-arm64")
        touchCompiler(File(ndk, "toolchains/llvm/prebuilt/linux-bionic-arm64"))

        // Before the fix this was false, and the build re-downloaded.
        assertNotNull(AndroidHostedLlvmInstaller.compilerDirIfUsable(ndk))
        assertEquals(listOf(ndk), AndroidHostedLlvmInstaller.ndkSearchRoots(filesDir))
        assertTrue(anyRootUsable(filesDir))
    }

    @Test
    fun `an x86_64-only NDK still allows the download`() {
        val filesDir = tempDir()
        touchCompiler(
            File(filesDir, "toolchain/sdk/android-34/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64"),
        )

        // Downloading is correct here: that clang cannot run on the device.
        assertFalse(anyRootUsable(filesDir))
    }

    @Test
    fun `an installed NDK wins even when another NDK is unusable`() {
        val filesDir = tempDir()
        touchCompiler(
            File(filesDir, "toolchain/sdk/android-34/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64"),
        )
        touchCompiler(
            File(filesDir, "toolchain/ndk/android-ndk-r27c-arm64/toolchains/llvm/prebuilt/linux-bionic-arm64"),
        )

        assertTrue(anyRootUsable(filesDir))
    }
}
