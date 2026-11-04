package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Exec Helper - provides unified process execution for ARM64 ELF binaries
 * on Android. App-data binaries cannot be execve()d directly by untrusted_app
 * (EACCES), so bionic ELFs under the app data dir are launched through
 * /system/bin/linker64 (the "JRE 방식"); system and native-lib binaries are
 * exec'd directly by the kernel.
 */
object ExecHelper {

    private const val TAG = "ExecHelper"

    /**
     * Checks if an ELF is bionic-linked (PT_INTERP = /system/bin/linker64).
     */
    fun isBionicElf(file: File): Boolean {
        return readInterp(file)?.contains("linker64", ignoreCase = true) ?: false
    }

    /**
     * Checks if an ELF is glibc-linked (PT_INTERP = ld-linux-aarch64.so.1).
     */
    fun isGlibcElf(file: File): Boolean {
        return readInterp(file)?.contains("ld-linux", ignoreCase = true) ?: false
    }

    /**
     * Returns PT_INTERP of an ELF64 file, or null when the file is not ELF,
     * not 64-bit, or has no (sane) interpreter. ELF64 header layout:
     * e_phoff @32, e_phentsize @54, e_phnum @56; program header fields are
     * p_type @0, p_flags @4, p_offset @8, p_filesz @32.
     */
    private fun readInterp(file: File): String? {
        if (!file.isFile || file.length() < 64) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(20)
                raf.readFully(ident)
                val isElf = ident[0] == 0x7F.toByte() && ident[1] == 'E'.code.toByte() &&
                    ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()
                if (!isElf || ident[4].toInt() != 2) return@use null // 64-bit only

                raf.seek(32)
                val phoff = readLeLong(raf)
                raf.seek(54)
                val phentsize = readLeShort(raf)
                raf.seek(56)
                val phnum = readLeShort(raf)
                if (phoff <= 0 || phnum == 0 || phnum > 128 || phentsize < 56) return@use null

                for (i in 0 until phnum) {
                    val base = phoff + i * phentsize.toLong()
                    raf.seek(base)
                    val ptype = readLeInt(raf)
                    if (ptype == 3) { // PT_INTERP
                        raf.seek(base + 8)
                        val poff = readLeLong(raf)
                        raf.seek(base + 32)
                        val pfilesz = readLeLong(raf)
                        if (pfilesz <= 0 || pfilesz > 256) return@use null
                        if (poff < 0 || poff + pfilesz > raf.length()) return@use null
                        raf.seek(poff)
                        val interpBytes = ByteArray(pfilesz.toInt())
                        raf.readFully(interpBytes)
                        return@use String(interpBytes).trim('\u0000').trim()
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "readInterp failed for ${file.absolutePath}: ${e.message}")
            null
        }
    }

    // ELF multi-byte fields are little-endian, but RandomAccessFile.readShort/
    // readInt/readLong decode big-endian (so on-disk 0x0A00 = phnum 10 came back
    // as 2560). Header fields are therefore assembled byte-by-byte.
    // internal: shared with JdkDownloader/JreGradleInstaller ELF header parsers.
    internal fun readLeShort(raf: java.io.RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) throw java.io.EOFException("EOF in ELF le16")
        return b0 or (b1 shl 8)
    }

    internal fun readLeInt(raf: java.io.RandomAccessFile): Int {
        var v = 0
        for (sh in 0 until 4) {
            val b = raf.read()
            if (b < 0) throw java.io.EOFException("EOF in ELF le32")
            v = v or (b shl (sh * 8))
        }
        return v
    }

    internal fun readLeLong(raf: java.io.RandomAccessFile): Long {
        var v = 0L
        for (sh in 0 until 8) {
            val b = raf.read()
            if (b < 0) throw java.io.EOFException("EOF in ELF le64")
            v = v or (b.toLong() shl (sh * 8))
        }
        return v
    }

    /**
     * Creates a ProcessBuilder that launches ARM64 ELF binaries, loading
     * app-data executables through /system/bin/linker64 when the executable
     * is in app data (noexec/SELinux blocked).
     * For non-ARM64 ELFs (e.g. x86_64), uses box64 for CPU translation.
     */
    fun createProcess(
        context: Context,
        args: List<String>,
        cwd: File? = null,
        env: Map<String, String> = emptyMap()
    ): ProcessBuilder {
        if (args.isEmpty()) throw IllegalArgumentException("Empty args")

        val exePath = args[0]
        val exeFile = File(exePath)
        val info = analyzeElf(exeFile)

        // If analyzeElf failed but file exists, check raw ELF magic bytes
        val resolvedInfo = if (!info.isElf && exeFile.exists() && exeFile.length() > 4) {
            try {
                val header = ByteArray(4)
                java.io.RandomAccessFile(exeFile, "r").use { it.readFully(header) }
                if (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                    Log.w(TAG, "analyzeElf failed but raw ELF magic detected for $exePath, treating as ARM64 ELF")
                    ElfInfo(isElf = true, isArm64 = true, isExecutable = true, interp = "/system/bin/linker64")
                } else {
                    info
                }
            } catch (_: Exception) {
                info
            }
        } else {
            info
        }

        // If it's not an ELF (e.g. script, data) or doesn't exist, use direct exec.
        if (!resolvedInfo.isElf) {
            // App-data is noexec (and untrusted_app may not execute app_data_file):
            // execve() of a shebang script fails with EACCES before the interpreter
            // ever runs. Running the script through /system/bin/sh only needs read
            // access to it, and the script's own exec targets (nativeLibraryDir
            // launcher, memfd redirect hook) remain executable.
            val launch = if (isShellScriptFile(exeFile)) listOf("/system/bin/sh") + args else args
            return ProcessBuilder(launch).apply {
                if (cwd != null) directory(cwd)
                environment().putAll(env)
            }
        }

        val isNativeLib = exePath.startsWith(context.applicationInfo.nativeLibraryDir)

        // Native lib dir: apk_data_file type, direct exec allowed for native arch
        if (isNativeLib && resolvedInfo.isArm64) {
            Log.i(TAG, "Direct exec (native lib dir): $exePath")
            return ProcessBuilder(args).apply {
                if (cwd != null) directory(cwd)
                environment().putAll(env)
            }
        }

        // ARM64 ELF: this ROM denies execve() of app-data binaries for
        // untrusted_app, and the packaged libfork_loader.so is a JNI shared
        // object with e_entry=0 — exec'ing it as a program faults with SIGILL
        // (exit 132). App-data bionic ELFs therefore go through
        // /system/bin/linker64 (the route documented by WindowsX64BuildBackend,
        // ShellRunner and NativeBuildToolShim); native-lib and system binaries
        // are exec'd directly.
        if (resolvedInfo.isArm64) {
            val dataDir = context.applicationInfo.dataDir
            val inAppData = exePath.startsWith(dataDir) ||
                exePath.startsWith("/data/data/${context.packageName}") ||
                exePath.startsWith(context.filesDir.absolutePath)
            val bionicInterp = resolvedInfo.interp?.contains("linker64", true) ?: true
            val cmdline = if (inAppData && bionicInterp) {
                listOf("/system/bin/linker64") + args
            } else {
                args
            }
            Log.i(TAG, "ARM64 ELF (appData=$inAppData, interp=${resolvedInfo.interp ?: "-"}): ${cmdline.joinToString(" ")}")
            return ProcessBuilder(cmdline).apply {
                if (cwd != null) directory(cwd)
                environment().putAll(env)
            }
        }

        // Non-ARM64 ELF (e.g. x86_64): needs box64 for CPU translation
        val box64Env = WindowsExeEnvironment(context)
        val box64Kind = box64Env.box64Kind()

        return when (box64Kind) {
            "bionic" -> {
                val box64 = box64Env.effectiveBox64()?.absolutePath
                    ?: throw IllegalStateException("box64 not found")
                // box64 argv = [box64] + target args. Native-lib box64 is
                // exec'd directly (apk_data_file is exec-allowed); app-data
                // box64 is loaded through linker64 instead of being exec'd.
                val box64InAppData = box64.startsWith(context.applicationInfo.dataDir) ||
                    box64.startsWith("/data/data/${context.packageName}")
                val cmdline = (if (box64InAppData) listOf("/system/bin/linker64") else emptyList()) +
                    listOf(box64) + args // target_argv starts with exePath
                Log.i(TAG, "box64 bionic (appData=$box64InAppData): ${cmdline.joinToString(" ")}")
                ProcessBuilder(cmdline).apply {
                    if (cwd != null) directory(cwd)
                    environment().putAll(env)
                }
            }
            else -> {
                // glibc box64: ld-linux loads box64, ld.so itself is exec'd
                val box64 = box64Env.effectiveBox64()?.absolutePath
                    ?: throw IllegalStateException("box64 not found")
                val ld = box64Env.loaderPath()?.absolutePath
                    ?: throw IllegalStateException("glibc loader not found")
                val cmdline = listOf(ld, box64) + args // target_argv starts with exePath
                Log.i(TAG, "box64 glibc: ${cmdline.joinToString(" ")}")
                ProcessBuilder(cmdline).apply {
                    if (cwd != null) directory(cwd)
                    environment().putAll(env)
                }
            }
        }
    }

    /** True when [file] is a `#!` script whose interpreter is a POSIX shell. */
    private fun isShellScriptFile(file: File): Boolean {
        if (!file.isFile || file.length() < 3L) return false
        return try {
            val firstLine = java.io.RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(256)
                val n = raf.read(buf)
                if (n <= 0) return false
                String(buf, 0, n, Charsets.US_ASCII).lineSequence().firstOrNull() ?: return false
            }
            if (!firstLine.startsWith("#!")) return false
            val interp = firstLine.substring(2).trim()
                .substringBefore(' ').substringBefore('\t')
                .substringAfterLast('/')
            interp in setOf("sh", "bash", "mksh", "ash")
        } catch (_: Exception) {
            false
        }
    }

    /** Checks if file is ARM64 ELF (ET_EXEC or ET_DYN). */
    private fun isArm64Elf(file: File): Boolean {
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(20)
                raf.readFully(magic)
                magic[0] == 0x7F.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte() &&
                magic[4] == 2.toByte() && // ELFCLASS64
                magic[5] == 1.toByte() && // ELFDATA2LSB
                (magic[16] == 2.toByte() || magic[16] == 3.toByte()) && // ET_EXEC or ET_DYN
                magic[18] == 0xB7.toByte() && // EM_AARCH64
                magic[19] == 0x00.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Analyzes an ELF file and returns interpreter info. */
    data class ElfInfo(
        val isElf: Boolean,
        val isArm64: Boolean,
        val isExecutable: Boolean,
        val interp: String?
    )

    fun analyzeElf(file: File): ElfInfo {
        if (!file.isFile || file.length() < 64) return ElfInfo(false, false, false, null)
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                // e_ident is 16 bytes; e_type(2)/e_machine(2) follow it, so the
                // buffer must cover offsets 0..19 (ident[16] threw
                // ArrayIndexOutOfBoundsException when only 16 bytes were read).
                val ident = ByteArray(20)
                raf.readFully(ident)
                val isElf = ident[0] == 0x7F.toByte() && ident[1] == 'E'.code.toByte() &&
                    ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()
                if (!isElf) return ElfInfo(false, false, false, null)

                val m1 = ident[18].toInt() and 0xFF // e_machine (little-endian)
                val m2 = ident[19].toInt() and 0xFF
                val machine = m1 or (m2 shl 8)

                val elf64 = ident[4].toInt() == 2
                val isArm64 = elf64 && machine == 0xB7
                val isExecutable = ident[16].toInt() == 2 || ident[16].toInt() == 3

                // Find PT_INTERP (ELF64: e_phoff @32, e_phentsize @54, e_phnum @56)
                var interp: String? = null
                if (elf64) {
                    raf.seek(32)
                    val phoff = readLeLong(raf)
                    raf.seek(54)
                    val phentsize = readLeShort(raf)
                    raf.seek(56)
                    val phnum = readLeShort(raf)
                    if (phoff > 0 && phnum in 1..128 && phentsize >= 56) {
                        for (i in 0 until phnum) {
                            val base = phoff + i * phentsize.toLong()
                            raf.seek(base)
                            val ptype = readLeInt(raf)
                            if (ptype == 3) { // PT_INTERP: p_offset @+8, p_filesz @+32
                                raf.seek(base + 8)
                                val poff = readLeLong(raf)
                                raf.seek(base + 32)
                                val pfilesz = readLeLong(raf)
                                if (pfilesz > 0 && pfilesz <= 256 && poff >= 0 && poff + pfilesz <= raf.length()) {
                                    raf.seek(poff)
                                    val interpBytes = ByteArray(pfilesz.toInt())
                                    raf.readFully(interpBytes)
                                    interp = String(interpBytes).trim('\u0000').trim()
                                }
                                break
                            }
                        }
                    }
                }
                ElfInfo(isElf, isArm64, isExecutable, interp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyzeElf failed for ${file.absolutePath}: ${e.message}")
            ElfInfo(false, false, false, null)
        }
    }
}