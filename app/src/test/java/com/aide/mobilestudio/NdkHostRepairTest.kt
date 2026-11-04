package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Host tests for the on-device NDK repairs:
 *  - [AndroidHostedLlvmInstaller.patchToolchainFileForAndroidHost] — cmake
 *    reports CMAKE_HOST_SYSTEM_NAME=Android on-device, so the stock
 *    Linux/Darwin/Windows branches never match and ANDROID_HOST_TAG ends up
 *    empty (".../prebuilt//bin/clang").
 *  - [AndroidHostedLlvmInstaller.repairTextStubSymlinks] — the SDK zip
 *    extractor stores symlink entries as text files holding the link target.
 *  - [AndroidHostedLlvmInstaller.linkClangResourceDir] — the arm64 clang
 *    expects lib/clang/<own version> next to its argv[0], but the stock NDK
 *    only ships lib/clang/18.
 */
class NdkHostRepairTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    private val stockToolchain = """
        # Copyright header
        cmake_minimum_required(VERSION 3.6.0)
        if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux)
          set(ANDROID_HOST_TAG linux-x86_64)
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL Darwin)
          set(ANDROID_HOST_TAG darwin-x86_64)
        elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL Windows)
          set(ANDROID_HOST_TAG windows-x86_64)
        endif()
        set(ANDROID_TOOLCHAIN_ROOT "${'$'}{CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${'$'}{ANDROID_HOST_TAG}")
    """.trimIndent()

    @Test
    fun toolchainFileGetsAndroidHostTagBranch() {
        val f = File(tempDir("tc"), "android.toolchain.cmake")
        f.writeText(stockToolchain)

        assertTrue(AndroidHostedLlvmInstaller.patchToolchainFileForAndroidHost(f))
        val patched = f.readText()

        assertTrue(
            "Android branch must set linux-x86_64",
            patched.contains("if(CMAKE_HOST_SYSTEM_NAME STREQUAL Android)") &&
                patched.contains("set(ANDROID_HOST_TAG linux-x86_64)")
        )
        // The Android branch must come BEFORE the stock chain so the empty
        // fallback branch cannot be the only source of the value.
        assertTrue(
            "Android branch precedes the stock chain",
            patched.indexOf("STREQUAL Android") < patched.indexOf("STREQUAL Linux")
        )
        assertTrue("stock Linux branch preserved", patched.contains("STREQUAL Linux"))

        // Idempotent: the marker prevents a second insertion.
        assertFalse(AndroidHostedLlvmInstaller.patchToolchainFileForAndroidHost(f))
        assertEquals(patched, f.readText())
    }

    @Test
    fun toolchainFileWithoutAnchorUntouched() {
        val f = File(tempDir("tc2"), "unrelated.cmake")
        f.writeText("set(FOO bar)\n")

        assertFalse(AndroidHostedLlvmInstaller.patchToolchainFileForAndroidHost(f))
        assertEquals("set(FOO bar)\n", f.readText())
    }

    @Test
    fun textStubsBecomeRelativeSymlinks() {
        val bin = File(tempDir("ndkbin"), "bin")
        bin.mkdirs()
        // stub -> real binary (the case seen on-device)
        File(bin, "clang").writeText("clang-18")
        File(bin, "clang-18").writeBytes(ByteArray(128) { 0x7f })
        // stub -> stub chain (clang++ -> clang -> clang-18)
        File(bin, "clang++").writeText("clang")
        // stub whose target does not exist: left alone
        File(bin, "ghost").writeText("missing-tool")
        // not an identifier: left alone
        File(bin, "weird").writeText("not a name!")
        // large regular file: never touched
        val script = "#!/usr/bin/env bash\n" + "x".repeat(200)
        File(bin, "clang-tidy.sh").writeText(script)

        assertTrue(AndroidHostedLlvmInstaller.repairTextStubSymlinks(bin))

        assertTrue("clang must be a symlink", Files.isSymbolicLink(File(bin, "clang").toPath()))
        assertEquals("clang-18", Files.readSymbolicLink(File(bin, "clang").toPath()).toString())
        assertTrue("clang++ must be a symlink", Files.isSymbolicLink(File(bin, "clang++").toPath()))
        assertEquals("clang", Files.readSymbolicLink(File(bin, "clang++").toPath()).toString())
        // resolved chain ends at the real binary
        assertTrue(File(bin, "clang++").canonicalFile.name == "clang-18")

        assertFalse(Files.isSymbolicLink(File(bin, "ghost").toPath()))
        assertEquals("missing-tool", File(bin, "ghost").readText())
        assertFalse(Files.isSymbolicLink(File(bin, "weird").toPath()))
        assertEquals("not a name!", File(bin, "weird").readText())
        assertEquals(script, File(bin, "clang-tidy.sh").readText())

        // Second pass has nothing left to convert.
        assertFalse(AndroidHostedLlvmInstaller.repairTextStubSymlinks(bin))
    }

    @Test
    fun repairKeepsBrokenSymlinksUntouched() {
        val bin = File(tempDir("ndkbin2"), "bin")
        bin.mkdirs()
        val link = File(bin, "dangling")
        Files.createSymbolicLink(link.toPath(), Paths.get("does-not-exist"))

        assertFalse(AndroidHostedLlvmInstaller.repairTextStubSymlinks(bin))
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test
    fun x86EntriesRoutedToAndroidHostedLlvm() {
        val root = tempDir("route")
        val bin = File(root, "ndk/bin").apply { mkdirs() }
        val llvmBin = File(root, "llvm/bin").apply { mkdirs() }

        writeElf(File(bin, "clang"), 0x3e)      // x86_64: must be rerouted
        writeElf(File(bin, "no-twin"), 0x3e)    // x86_64 without llvm twin: keep
        writeElf(File(bin, "already-arm64"), 0xb7)
        File(bin, "unrelated").writeText("hello") // not ELF
        writeElf(File(llvmBin, "clang"), 0xb7)    // arm64 twin

        assertTrue(AndroidHostedLlvmInstaller.routeX86EntriesToHostLlvm(bin, llvmBin))

        val clang = File(bin, "clang")
        assertTrue("x86 entry must become a symlink", Files.isSymbolicLink(clang.toPath()))
        assertEquals(
            File(llvmBin, "clang").absolutePath,
            Files.readSymbolicLink(clang.toPath()).toString()
        )
        assertFalse(Files.isSymbolicLink(File(bin, "no-twin").toPath()))
        assertFalse(Files.isSymbolicLink(File(bin, "already-arm64").toPath()))
        assertEquals("hello", File(bin, "unrelated").readText())

        // Second pass: clang now resolves to an arm64 ELF — nothing to do.
        assertFalse(AndroidHostedLlvmInstaller.routeX86EntriesToHostLlvm(bin, llvmBin))
    }

    @Test
    fun stubRepairsThenRoutesToHostLlvm() {
        // The exact on-device sequence: text stub "clang-18" first becomes a
        // same-dir symlink, then the x86_64 target causes routing to the
        // Android-hosted arm64 twin.
        val root = tempDir("seq")
        val bin = File(root, "ndk/bin").apply { mkdirs() }
        val llvmBin = File(root, "llvm/bin").apply { mkdirs() }
        File(bin, "clang").writeText("clang-18")
        writeElf(File(bin, "clang-18"), 0x3e)
        writeElf(File(llvmBin, "clang"), 0xb7)

        assertTrue(AndroidHostedLlvmInstaller.repairTextStubSymlinks(bin))
        assertTrue(Files.isSymbolicLink(File(bin, "clang").toPath()))
        assertTrue(AndroidHostedLlvmInstaller.routeX86EntriesToHostLlvm(bin, llvmBin))

        val clang = File(bin, "clang")
        assertTrue(Files.isSymbolicLink(clang.toPath()))
        assertEquals(
            File(llvmBin, "clang").absolutePath,
            Files.readSymbolicLink(clang.toPath()).toString()
        )
    }

    @Test
    fun clangResourceDirVersionLinksToStock() {
        // The arm64 clang (major 24) looks up ../lib/clang/24 relative to
        // argv[0]; the stock NDK only ships 18. Link 24 -> 18 so -latomic,
        // -l:libunwind.a and builtins resolve like on desktop.
        val clangLib = tempDir("clangres")
        File(clangLib, "18").mkdirs()

        assertTrue(AndroidHostedLlvmInstaller.linkClangResourceDir(clangLib, listOf("24")))
        val link = File(clangLib, "24")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("18", Files.readSymbolicLink(link.toPath()).toString())

        assertFalse(AndroidHostedLlvmInstaller.linkClangResourceDir(clangLib, listOf("24")))
    }

    @Test
    fun clangResourceDirKeepsRealVersionDir() {
        val clangLib = tempDir("clangres2")
        File(clangLib, "18").mkdirs()
        File(clangLib, "24").mkdirs() // already a real resource directory

        assertFalse(AndroidHostedLlvmInstaller.linkClangResourceDir(clangLib, listOf("24")))
        assertFalse(Files.isSymbolicLink(File(clangLib, "24").toPath()))
    }

    @Test
    fun clangResourceDirReplacesBrokenLink() {
        val clangLib = tempDir("clangres3")
        File(clangLib, "18").mkdirs()
        Files.createSymbolicLink(
            File(clangLib, "24").toPath(),
            Paths.get("19") // dangling: stock 19 absent
        )

        assertTrue(AndroidHostedLlvmInstaller.linkClangResourceDir(clangLib, listOf("24")))
        assertEquals("18", Files.readSymbolicLink(File(clangLib, "24").toPath()).toString())
    }

    @Test
    fun clangResourceDirWithoutStockSkips() {
        val clangLib = tempDir("clangres4")
        assertFalse(AndroidHostedLlvmInstaller.linkClangResourceDir(clangLib, listOf("24")))
    }

    private fun writeElf(file: File, machine: Int) {
        val b = ByteArray(64)
        b[0] = 0x7f
        b[1] = 'E'.code.toByte()
        b[2] = 'L'.code.toByte()
        b[3] = 'F'.code.toByte()
        b[4] = 2 // ELF64
        b[5] = 1 // little endian
        b[18] = machine.toByte()
        b[19] = 0
        file.parentFile?.mkdirs()
        file.writeBytes(b)
    }
}
