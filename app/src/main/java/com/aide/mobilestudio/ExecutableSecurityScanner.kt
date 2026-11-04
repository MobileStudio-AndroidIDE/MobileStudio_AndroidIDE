package com.aide.mobilestudio

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Pre-execution validation of ELF binaries.
 * Validates architecture, checks for dangerous patterns,
 * computes trust levels, and optionally blocks untrusted execution.
 */
class ExecutableSecurityScanner {

    enum class TrustLevel {
        TRUSTED,      // System binary, known good
        VERIFIED,     // SHA-256 matches known hash
        UNTRUSTED,    // Unknown origin, architecture valid
        DANGEROUS,    // SUID/SGID, world-writable, wrong arch
        BLOCKED       // Execution blocked by policy
    }

    enum class MalwarePolicy {
        BLOCK_DANGEROUS,  // block DANGEROUS+ level
        WARN_ONLY,        // log warnings but allow all
        ALLOW_ALL         // no validation
    }

    data class ScanResult(
        val path: String,
        val trustLevel: TrustLevel,
        val isArm64: Boolean,
        val sha256: String,
        val issues: List<String>
    ) {
        fun summary(): String = buildString {
            append("[$trustLevel] $path")
            if (sha256.isNotEmpty()) append(" SHA256=$sha256")
            if (issues.isNotEmpty()) {
                append(" ISSUES:")
                issues.joinTo(this, "; ")
            }
        }

        fun shouldBlock(): Boolean =
            trustLevel == TrustLevel.BLOCKED || trustLevel == TrustLevel.DANGEROUS
    }

    companion object {
        private const val TAG = "ExecSecurityScanner"
        private const val SHA256_BUFFER_SIZE = 8192

        private val SYSTEM_PATHS = setOf(
            "/system/bin", "/system/xbin",
            "/vendor/bin",
            "/apex/com.android.runtime/bin"
        )

        private val TRUSTED_HASHES = setOf<String>() // TODO: populate with known-good hashes
    }

    /**
     * Scan an executable file for security issues.
     */
    fun scan(executablePath: String, policy: VirtualRuntimePolicy): ScanResult {
        val file = File(executablePath)
        val issues = mutableListOf<String>()
        var trust = TrustLevel.UNTRUSTED

        if (!file.exists()) {
            return ScanResult(
                path = executablePath,
                trustLevel = TrustLevel.BLOCKED,
                isArm64 = false,
                sha256 = "",
                issues = listOf("File does not exist")
            )
        }

        if (!file.canExecute()) {
            issues.add("File is not executable")
        }

        val isArm64 = NativeExec.nativeIsArm64Elf(executablePath) == 1
        if (policy.requireArm64 && !isArm64) {
            issues.add("Not an ARM64 ELF binary")
            trust = TrustLevel.BLOCKED
        }

        val isSuid = checkSuid(file)
        if (isSuid && policy.blockSuidBinaries) {
            issues.add("SUID binary detected")
            trust = TrustLevel.DANGEROUS
        }

        val isWorldWritable = checkWorldWritable(file)
        if (isWorldWritable) {
            issues.add("World-writable file")
            if (trust != TrustLevel.BLOCKED) trust = TrustLevel.DANGEROUS
        }

        val isSystem = SYSTEM_PATHS.any { executablePath.startsWith(it) }
        if (isSystem && trust != TrustLevel.BLOCKED) {
            trust = TrustLevel.TRUSTED
        }

        val sha256 = computeSha256Streaming(file)
        if (sha256 in TRUSTED_HASHES) {
            trust = TrustLevel.VERIFIED
        }

        if (trust == TrustLevel.UNTRUSTED && issues.isEmpty()) {
            trust = TrustLevel.UNTRUSTED
        }

        return ScanResult(
            path = executablePath,
            trustLevel = trust,
            isArm64 = isArm64,
            sha256 = sha256,
            issues = issues
        )
    }

    /**
     * Determine whether a given scan result should be blocked by the policy.
     */
    fun shouldBlock(result: ScanResult, malwarePolicy: MalwarePolicy = MalwarePolicy.BLOCK_DANGEROUS): Boolean {
        return when (malwarePolicy) {
            MalwarePolicy.ALLOW_ALL -> false
            MalwarePolicy.WARN_ONLY -> false
            MalwarePolicy.BLOCK_DANGEROUS -> result.shouldBlock()
        }
    }

    /**
     * Verify that the SHA-256 of a file matches a known-good hash.
     */
    fun verifyHash(file: File, expectedHash: String): Boolean {
        val actual = computeSha256Streaming(file)
        return actual.equals(expectedHash, ignoreCase = true)
    }

    /**
     * Compute SHA-256 of a file using streaming I/O (handles large files).
     */
    fun computeSha256Streaming(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(SHA256_BUFFER_SIZE)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 computation failed for ${file.path}: ${e.message}")
            ""
        }
    }

    private fun checkSuid(file: File): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val proc = runtime.exec(arrayOf("ls", "-la", file.absolutePath))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.contains("-rws") || output.contains("-rwS")
        } catch (e: Exception) {
            false
        }
    }

    private fun checkWorldWritable(file: File): Boolean {
        return try {
            val runtime = Runtime.getRuntime()
            val proc = runtime.exec(arrayOf("ls", "-la", file.absolutePath))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output[9] == 'w' || output[6] == 'w'
        } catch (e: Exception) {
            false
        }
    }
}
