package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Host tests for the on-device CMake/Ninja linker64 wrapper repair.
 *
 * Real tool ELFs cannot be executed on the host (they are Android arm64
 * binaries), so process spawning is replaced by an [NativeToolWrappers.ExecTester]
 * stub that mimics Android: direct exec fails, `/system/bin/linker64 <elf>
 * --version` succeeds.
 */
class NativeToolShimWrappersTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    /** Minimal fake arm64 ELF (magic + padding past the 64-byte minimum). */
    private fun writeFakeElf(file: File): File {
        file.parentFile?.mkdirs()
        val bytes = ByteArray(128)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // ELFDATA2LSB
        file.writeBytes(bytes)
        return file
    }

    /** Android-like tester: only `/system/bin/linker64 <file> --version` works. */
    private val linkerOnlyTester = NativeToolWrappers.ExecTester { cmd ->
        cmd.size == 3 && cmd[0] == "/system/bin/linker64" && cmd[2] == "--version"
    }

    private val allOkTester = NativeToolWrappers.ExecTester { cmd -> cmd.lastOrNull() == "--version" }
    private val noneOkTester = NativeToolWrappers.ExecTester { false }

    // ---------------------------------------------------------------- scan --

    @Test
    fun scanFindsElfAndSkipsScriptsSymlinksAndSdkTree() {
        val root = tempDir("tw-scan")
        val real = writeFakeElf(File(root, "cmake/3.22.1/bin/cmake"))
        // Non-ELF script named cmake -> skipped
        File(root, "arm64-tools").mkdirs()
        File(root, "arm64-tools/cmake").apply {
            writeText("#!/system/bin/sh\nexec /system/bin/linker64 /nowhere \"\$@\"\n")
            setExecutable(true, false)
        }
        // Symlink to the real ELF -> skipped (would end in a direct execve)
        val link = File(root, "linked")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val linkNamed = File(root, "arm64-tools/linked-cmake")
        Files.createSymbolicLink(linkNamed.toPath(), real.toPath())
        // SDK layout (AGP's own tree, pruned) -> skipped
        writeFakeElf(File(root, "sdk/android-34/cmake/3.22.1/bin/cmake"))

        val found = NativeToolWrappers.scanToolCandidates(root, "cmake")
        assertEquals("only the real runtime ELF qualifies: $found", 1, found.size)
        assertEquals(real.absolutePath, found[0].absolutePath)
    }

    @Test
    fun scanPrefersCanonicalRuntimeInstallLayout() {
        val root = File(tempDir("tw-priority"), "toolchain").apply { mkdirs() }
        val other = writeFakeElf(File(root, "arm64-tools/deep-nested-cmake/bin/cmake"))
        val canonical = writeFakeElf(File(root, "cmake/3.22.1/bin/cmake"))

        val found = NativeToolWrappers.scanToolCandidates(root, "cmake")
        assertTrue("expected both candidates, got $found", found.size >= 2)
        assertEquals("toolchain/cmake/<ver>/bin wins", canonical.absolutePath, found[0].absolutePath)
        assertTrue(found.any { it == other })
    }

    @Test
    fun scanReturnsEmptyForMissingRoot() {
        val missing = File(tempDir("tw-empty"), "does-not-exist")
        assertTrue(NativeToolWrappers.scanToolCandidates(missing, "cmake").isEmpty())
    }

    // -------------------------------------------------------------- verify --

    @Test
    fun verifyToolReportsDirectLinkerOrFailure() {
        val elf = writeFakeElf(File(tempDir("tw-verify"), "bin/cmake"))
        assertEquals("direct", NativeToolWrappers.verifyTool(elf, allOkTester))
        assertEquals("linker64", NativeToolWrappers.verifyTool(elf, linkerOnlyTester))
        assertNull(NativeToolWrappers.verifyTool(elf, noneOkTester))
    }

    @Test
    fun findExecutableToolReturnsFirstWorkingCandidate() {
        val root = File(tempDir("tw-find"), "toolchain").apply { mkdirs() }
        writeFakeElf(File(root, "arm64-tools/broken/bin/cmake"))
        val good = writeFakeElf(File(root, "cmake/3.22.1/bin/cmake"))
        val hit = NativeToolWrappers.findExecutableTool(root, "cmake", linkerOnlyTester)
        assertNotNull("linker64-only exec is acceptable on Android", hit)
        assertEquals("linker64", hit!!.second)
        assertEquals(good.absolutePath, hit.first.absolutePath)

        assertNull(NativeToolWrappers.findExecutableTool(root, "cmake", noneOkTester))
    }

    // ------------------------------------------------------------- wrapper --

    @Test
    fun probeEnvironmentAddsInstallLibToolchainLlvmAndCmakeRoot() {
        val files = tempDir("tw-probe")
        val real = writeFakeElf(File(files, "toolchain/cmake/3.22.1/bin/cmake"))
        val llvmLib = File(files, "toolchain/llvm/lib").apply { mkdirs() }
        val baseLib = File(files, "toolchain/cmake/3.22.1/lib").apply { mkdirs() }
        val share = File(files, "toolchain/cmake/3.22.1/share/cmake-3.22").apply { mkdirs() }

        val env = NativeToolWrappers.probeEnvironment(listOf(real.absolutePath, "--version"))
        val ld = env["LD_LIBRARY_PATH"] ?: "missing LD_LIBRARY_PATH"
        assertTrue("base lib: $ld", ld.contains(baseLib.absolutePath))
        assertTrue("toolchain llvm lib: $ld", ld.contains(llvmLib.absolutePath))
        assertEquals(share.absolutePath, env["CMAKE_ROOT"])

        // Nothing to derive when the command carries no ELF path.
        assertTrue(NativeToolWrappers.probeEnvironment(listOf("--version")).isEmpty())

        // A tool without share/ gets LD_LIBRARY_PATH but no CMAKE_ROOT.
        val ninja = writeFakeElf(File(files, "toolchain/arm64-tools/bin/ninja"))
        val ninjaEnv = NativeToolWrappers.probeEnvironment(listOf(ninja.absolutePath))
        assertFalse(ninjaEnv.containsKey("CMAKE_ROOT"))
        assertTrue((ninjaEnv["LD_LIBRARY_PATH"] ?: "").contains(llvmLib.absolutePath))
    }

    @Test
    fun wrapperScriptExportsToolchainLlvmLibAndClosesQuotes() {
        val files = tempDir("tw-wllvm")
        val real = writeFakeElf(File(files, "toolchain/cmake/3.22.1/bin/cmake"))
        File(files, "toolchain/llvm/lib").mkdirs()

        val script = NativeToolWrappers.wrapperScript(real)
        assertTrue(script.contains(File(files, "toolchain/llvm/lib").absolutePath))
        // The static part must be closed before the inherited-value expansion:
        // putting the ${LD_LIBRARY_PATH:+...} inside single quotes makes the
        // shell pass it literally and the linker chokes on "${...}" entries.
        val ldLine = script.lineSequence().last { it.startsWith("export LD_LIBRARY_PATH") }
        assertTrue("line must expand after a closed quote: $ldLine", ldLine.endsWith("}"))
        assertTrue("static lib path stays quoted: $ldLine", ldLine.contains("'${File(files, "toolchain/llvm/lib").absolutePath}'"))
        assertTrue(
            "inherited expansion right after the closing quote: $ldLine",
            ldLine.contains("'\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}")
        )
    }

    @Test
    fun wrapperScriptCarriesRealPathLinkerAndEnv() {
        val root = File(tempDir("tw-script"), "cmake-3.22.1")
        val real = writeFakeElf(File(root, "bin/cmake"))
        File(root, "share/cmake-3.22").mkdirs()
        File(root, "lib").mkdirs()

        val script = NativeToolWrappers.wrapperScript(real)
        assertTrue("shebang", script.startsWith("#!/system/bin/sh\n"))
        assertTrue("marker", script.contains("# MobileStudio generated linker64 wrapper"))
        assertTrue("REAL path", script.contains("REAL='${real.absolutePath}'"))
        assertTrue("linker64 exec", script.contains("exec '/system/bin/linker64' \"\$REAL\" \"\$@\""))
        assertTrue("CMAKE_ROOT", script.contains("export CMAKE_ROOT='${File(root, "share/cmake-3.22").absolutePath}'"))
        assertTrue("LD_LIBRARY_PATH", script.contains("export LD_LIBRARY_PATH='${File(root, "lib").absolutePath}"))
    }

    @Test
    fun wrapperScriptOmitsEnvWhenShareAndLibAbsent() {
        val real = writeFakeElf(File(tempDir("tw-script2"), "bin/ninja"))
        val script = NativeToolWrappers.wrapperScript(real)
        assertFalse("no CMAKE_ROOT for a bare tool", script.contains("CMAKE_ROOT"))
        assertFalse(script.contains("LD_LIBRARY_PATH"))
    }

    @Test
    fun writeWrapperReplacesSymlinksIncludingDanglingOnes() {
        val real = writeFakeElf(File(tempDir("tw-replace"), "cmake/3.22.1/bin/cmake"))
        val sdkBin = File(tempDir("tw-replace-sdk"), "bin").apply { mkdirs() }
        // Dangling symlink: exists() is false, but isSymbolicLink must catch it.
        val dest = File(sdkBin, "cmake")
        Files.createSymbolicLink(dest.toPath(), File(sdkBin, "missing-target").toPath())
        assertFalse(dest.exists())

        val written = NativeToolWrappers.writeWrapper(dest, real)
        assertTrue("symlink must be replaced", written)
        assertFalse("must not stay a symlink", Files.isSymbolicLink(dest.toPath()))
        assertTrue(dest.isFile)
        assertTrue("executable bit", dest.canExecute())
        assertTrue(dest.readText().contains("REAL='${real.absolutePath}'"))

        // Idempotent: second write is a no-op.
        assertFalse(NativeToolWrappers.writeWrapper(dest, real))
        assertTrue(dest.canExecute())
    }

    // ------------------------------------------------------ ensureSdkWrappers --

    @Test
    fun ensureSdkWrappersRepairsSdkCmakeLayout() {
        val files = tempDir("tw-files")
        val toolchain = File(files, "toolchain")
        val realCmake = writeFakeElf(File(toolchain, "cmake/3.22.1/bin/cmake"))
        val realNinja = writeFakeElf(File(toolchain, "arm64-tools/cmake-arm64/bin/ninja"))
        File(toolchain, "cmake/3.22.1/share/cmake-3.22").mkdirs()

        val sdk = tempDir("tw-sdk")
        val sdkBin = File(sdk, "cmake/3.22.1/bin").apply { mkdirs() }
        // The on-device failure mode: symlinked SDK cmake + missing ninja.
        Files.createSymbolicLink(
            File(sdkBin, "cmake").toPath(),
            File(toolchain, "vanished/cmake").toPath()
        )

        val lines = NativeToolWrappers.ensureSdkWrappers(sdk, toolchain, tester = linkerOnlyTester)
        val report = lines.joinToString("\n")
        assertTrue("real cmake logged:\n$report", report.contains("real cmake: ${realCmake.absolutePath}"))
        assertTrue("exec mode logged (linker64-only):\n$report", report.contains("(exec: linker64)"))
        assertTrue("real ninja logged:\n$report", report.contains("real ninja: ${realNinja.absolutePath}"))

        val sdkCmake = File(sdkBin, "cmake")
        val sdkNinja = File(sdkBin, "ninja")
        assertTrue("cmake wrapper written: $lines", lines.any { it.startsWith("> [CMake] wrapper written:") && "bin/cmake" in it })
        assertTrue("ninja wrapper created even though bin/ninja was absent", sdkNinja.isFile)
        assertTrue(lines.any { it.startsWith("> [CMake] wrapper written:") && "bin/ninja" in it })
        assertTrue(Files.isSymbolicLink(File(sdkBin, "cmake").toPath()).not())
        assertTrue(sdkCmake.readText().contains("REAL='${realCmake.absolutePath}'"))
        assertTrue(sdkNinja.readText().contains("REAL='${realNinja.absolutePath}'"))
        // CMAKE_ROOT exported for cmake (share tree present) but not for ninja.
        assertTrue(sdkCmake.readText().contains("export CMAKE_ROOT="))
        assertFalse(sdkNinja.readText().contains("CMAKE_ROOT"))

        // Second run: idempotent, reports "wrapper ok".
        val second = NativeToolWrappers.ensureSdkWrappers(sdk, toolchain, tester = linkerOnlyTester)
        assertTrue(second.any { it.startsWith("> [CMake] wrapper ok:") && "bin/cmake" in it })
        assertFalse(second.any { it.startsWith("> [CMake] wrapper written:") })
    }

    @Test
    fun ensureSdkWrappersWarnsWhenNoRealToolsExist() {
        val files = tempDir("tw-bare")
        val sdk = tempDir("tw-bare-sdk")
        File(sdk, "cmake/3.22.1/bin").mkdirs()

        val lines = NativeToolWrappers.ensureSdkWrappers(sdk, File(files, "toolchain"), tester = linkerOnlyTester)
        val report = lines.joinToString("\n")
        assertTrue("warn about missing cmake:\n$report", report.contains("WARN: no executable arm64 cmake"))
        assertTrue("warn about missing ninja:\n$report", report.contains("WARN: no executable arm64 ninja"))
        // Nothing was created.
        assertFalse(File(sdk, "cmake/3.22.1/bin/cmake").exists())
        assertFalse(File(sdk, "cmake/3.22.1/bin/ninja").exists())
    }

    @Test
    fun ensureSdkWrappersWithoutSdkCmakeFolderIsNoop() {
        val lines = NativeToolWrappers.ensureSdkWrappers(
            tempDir("tw-nosdk"),
            tempDir("tw-notools"),
            tester = linkerOnlyTester
        )
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("no SDK cmake folder"))
    }
}
