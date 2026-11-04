package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutableSecurityScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scanner = ExecutableSecurityScanner()

    private fun policy() = VirtualRuntimePolicy(
        runtimeDir = temp.root,
        nativeLibDir = File(temp.root, "nativelib").apply { mkdirs() },
        requireArm64 = false,
        blockSuidBinaries = true,
    )

    private fun writeExe(content: ByteArray): File =
        temp.newFile("bin").apply { writeBytes(content) }

    // NOTE: the full scan() path on a *real* file calls NativeExec.nativeIsArm64Elf,
    // which is a JNI function and cannot run on the JVM. The missing-file branch
    // returns before reaching it, so it is testable here; the architecture check
    // itself needs a device test.

    @Test
    fun `missing file is blocked`() {
        val result = scanner.scan(temp.root.resolve("nope").absolutePath, policy())
        assertEquals(ExecutableSecurityScanner.TrustLevel.BLOCKED, result.trustLevel)
        assertTrue(result.shouldBlock())
        assertTrue(result.issues.any { it.contains("does not exist") })
    }

    @Test
    fun `sha256 is computed deterministically`() {
        val file = writeExe("hello windows runtime".toByteArray())
        val a = scanner.computeSha256Streaming(file)
        val b = scanner.computeSha256Streaming(file)
        assertEquals(a, b)
        assertEquals(64, a.length)
    }

    @Test
    fun `verifyHash accepts case insensitive match`() {
        val file = writeExe("payload".toByteArray())
        val hash = scanner.computeSha256Streaming(file)
        assertTrue(scanner.verifyHash(file, hash))
        assertTrue(scanner.verifyHash(file, hash.uppercase()))
        assertFalse(scanner.verifyHash(file, "0".repeat(64)))
    }

    @Test
    fun `policy decides blocking`() {
        val blocked = ExecutableSecurityScanner.ScanResult(
            path = "/x", trustLevel = ExecutableSecurityScanner.TrustLevel.DANGEROUS,
            isArm64 = true, sha256 = "", issues = listOf("SUID binary detected"),
        )
        val untrusted = blocked.copy(trustLevel = ExecutableSecurityScanner.TrustLevel.UNTRUSTED, issues = emptyList())
        assertTrue(scanner.shouldBlock(blocked, ExecutableSecurityScanner.MalwarePolicy.BLOCK_DANGEROUS))
        assertFalse(scanner.shouldBlock(untrusted, ExecutableSecurityScanner.MalwarePolicy.BLOCK_DANGEROUS))
        assertFalse(scanner.shouldBlock(blocked, ExecutableSecurityScanner.MalwarePolicy.WARN_ONLY))
        assertFalse(scanner.shouldBlock(blocked, ExecutableSecurityScanner.MalwarePolicy.ALLOW_ALL))
    }

    @Test
    fun `summary lists issues`() {
        val result = ExecutableSecurityScanner.ScanResult(
            path = "/x", trustLevel = ExecutableSecurityScanner.TrustLevel.DANGEROUS,
            isArm64 = false, sha256 = "abc", issues = listOf("SUID binary detected"),
        )
        val summary = result.summary()
        assertTrue(summary.contains("[DANGEROUS]"))
        assertTrue(summary.contains("SHA256=abc"))
        assertTrue(summary.contains("SUID binary detected"))
    }
}
