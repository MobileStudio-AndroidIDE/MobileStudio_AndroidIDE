package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads Android ARM64-native build tools (aapt2, cmake, ninja)
 * from known sources that provide Bionic-linked binaries.
 *
 * Sources:
 * - aapt2: MobileStudio-AndroidIDE/StudioShellTools GitHub Release (tarball)
 * - cmake: bnsmb/Magisk-Module-with-cmake (cmake 3.30.4 for Android arm64)
 * - ninja: bnsmb/Magisk-Module-with-cmake (ninja 1.13 for Android arm64)
 */
object Arm64ToolsDownloader {
    private const val TAG = "Arm64Tools"

    /** aapt2 port (release tarball) used for the Gradle aapt2FromMavenOverride override. */
    const val AAPT2_URL =
        "https://github.com/MobileStudio-AndroidIDE/StudioShellTools/releases/download/tools-v1/aapt2-android-arm64.tar.gz"

    /** PIE (ET_DYN) arm64 ninja — runnable through /system/bin/linker64. */
    const val NINJA_URL =
        "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/Ninja_arm64/ninja"

    private data class ToolDef(
        val name: String,
        val url: String,
        val fileName: String,
        val isExecutable: Boolean = true,
        /** url points at a .tar.gz that contains bin/<fileName> — extract instead of saving raw. */
        val isArchive: Boolean = false
    )

    private val TOOLS = listOf(
        ToolDef(
            name = "aapt2",
            url = AAPT2_URL,
            fileName = "aapt2",
            isArchive = true
        ),
        ToolDef(
            name = "cmake",
            url = "https://github.com/bnsmb/Magisk-Module-with-cmake/raw/main/system/bin/cmake",
            fileName = "cmake"
        ),
        ToolDef(
            name = "ninja",
            url = NINJA_URL,
            fileName = "ninja"
        )
    )

    /** Directory where ARM64 tools are stored */
    fun toolsDir(context: Context): File =
        File(context.filesDir, "toolchain/arm64-tools").apply { mkdirs() }

    /**
     * Creates a virtual cmake directory structure at toolchain/arm64-tools/cmake-arm64/
     * with bin/cmake and bin/ninja so GradleRunner's resolver can find them.
     */
    fun ensureCmakeLayout(context: Context): File? {
        val cmake = linkerRunnableToolPath(context, "cmake") ?: return null
        val ninja = linkerRunnableToolPath(context, "ninja") ?: return null
        val cmakeDir = File(toolsDir(context), "cmake-arm64")
        val binDir = File(cmakeDir, "bin").apply { mkdirs() }
        val cmakeBin = File(binDir, "cmake")
        val ninjaBin = File(binDir, "ninja")
        val sourceProperties = File(cmakeDir, "source.properties")
        if (!sourceProperties.isFile) {
            sourceProperties.writeText(
                "Pkg.Desc = CMake\n" +
                    "Pkg.Revision = 3.22.1\n" +
                    "Pkg.Path = cmake;3.22.1\n"
            )
        }

        // 심볼릭 링크 대신 실제 바이너리 복사 (실행 권한 문제 해결).
        // Actually-ARM64-ELF sources are copied directly: linker64 wrapper scripts
        // break cmake's CMAKE_ROOT lookup ("Could not find CMAKE_ROOT") because
        // the executable's parent dir is not named "bin". Direct exec works on
        // devices that allow execute on app_data_file.
        for ((src, dst) in listOf(cmake to cmakeBin, ninja to ninjaBin)) {
            if (isArm64Elf(src)) {
                if (!dst.exists() || dst.length() != src.length() || !dst.canExecute()) {
                    runCatching {
                        dst.parentFile?.mkdirs()
                        src.copyTo(dst, overwrite = true)
                        dst.setExecutable(true, false)
                        try { Os.chmod(dst.absolutePath, 0b111_101_101) } catch (_: Throwable) {}
                    }.onFailure {
                        Log.w(TAG, "copy ${src.absolutePath} failed: ${it.message}")
                    }
                }
                writeExecutableText(File(binDir, "${dst.name}.sdk_original"), linker64WrapperFor(src))
            } else {
                val wrapper = linker64WrapperFor(src)
                if (!dst.exists() || dst.readTextOrNull() != wrapper) {
                    writeExecutableText(dst, wrapper)
                }
                writeExecutableText(File(binDir, "${dst.name}.sdk_original"), wrapper)
            }
        }

        // Create lib/ directory with libexpat.so.1 symlink for cmake dependency
        val libDir = File(cmakeDir, "lib").apply { mkdirs() }
        val libexpat1 = File(libDir, "libexpat.so.1")
        val systemLibexpat = File("/system/lib64/libexpat.so")
        if (!libexpat1.exists() && systemLibexpat.exists()) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("ln", "-sf", systemLibexpat.absolutePath, libexpat1.absolutePath)).waitFor()
            }.onFailure {
                // Fallback: copy the file if symlink fails
                runCatching { systemLibexpat.copyTo(libexpat1, overwrite = true) }
            }
        }

        return cmakeDir
    }

    /** Check if all tools are downloaded */
    fun allInstalled(context: Context): Boolean {
        val dir = toolsDir(context)
        if (TOOLS.all { File(dir, it.fileName).exists() && File(dir, it.fileName).length() > 0 }) {
            context.getSharedPreferences("arm64_tools", Context.MODE_PRIVATE)
                .edit().putBoolean("all_downloaded", true).apply()
            return true
        }
        return false
    }

    /** Get path to a specific tool */
    fun linkerRunnableToolPath(context: Context, name: String): File? {
        val llvmTool = File(context.filesDir, "toolchain/llvm/usr/bin/$name")
        if (llvmTool.exists() && llvmTool.length() > 0) return llvmTool
        return toolPath(context, name)
    }

    fun toolPath(context: Context, name: String): File? {
        val file = File(toolsDir(context), name)
        return if (file.exists() && file.length() > 0) file else null
    }

    /**
     * Returns a runnable arm64 ninja ELF, downloading it when missing or when the
     * cached copy is not a PIE (ET_DYN) — a non-PIE ET_EXEC cannot be launched via
     * /system/bin/linker64 ("has unexpected e_type: 2"). Used by CmakeRuntimeManager
     * to populate <toolchain>/cmake/<ver>/bin/ninja after a fresh reinstall.
     */
    fun ensureNinja(context: Context, onLog: ((String) -> Unit)? = null): File? {
        val target = File(toolsDir(context), "ninja")
        if (target.isFile && target.length() > 0L && isPieArm64Elf(target)) return target
        if (target.exists()) target.delete()
        return try {
            onLog?.invoke("[Ninja] downloading arm64 ninja from $NINJA_URL")
            downloadFile(NINJA_URL, target, onLog)
            target.setExecutable(true, false)
            try { Os.chmod(target.absolutePath, 0b111_101_101) } catch (_: Throwable) {}
            if (!isPieArm64Elf(target)) {
                onLog?.invoke("[Ninja] downloaded binary is not a PIE arm64 ELF")
                target.delete()
                null
            } else {
                onLog?.invoke("[Ninja] ready: ${target.absolutePath} (${target.length()} bytes)")
                target
            }
        } catch (e: Exception) {
            onLog?.invoke("[Ninja] download failed: ${e.message}")
            target.delete()
            null
        }
    }

    private fun linker64WrapperFor(target: File): String =
        "#!/system/bin/sh\nexec /system/bin/linker64 ${target.absolutePath} \"\$@\"\n"

    private fun isArm64Elf(file: File): Boolean {
        if (!file.isFile || file.length() < 20) return false
        val hdr = ByteArray(20)
        val nread = runCatching {
            file.inputStream().use { it.read(hdr) }
        }.getOrDefault(0)
        if (nread < 20) return false
        if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte() ||
            hdr[2] != 'L'.code.toByte() || hdr[3] != 'F'.code.toByte()
        ) return false
        val le = hdr[5].toInt() == 1
        val machine = if (le) {
            (hdr[18].toInt() and 0xff) or ((hdr[19].toInt() and 0xff) shl 8)
        } else {
            ((hdr[18].toInt() and 0xff) shl 8) or (hdr[19].toInt() and 0xff)
        }
        return machine == 183
    }

    /** True for a PIE (ET_DYN, e_type=3) arm64 ELF — the only kind linker64 can load. */
    private fun isPieArm64Elf(file: File): Boolean {
        if (!isArm64Elf(file)) return false
        val type = runCatching {
            file.inputStream().use { input ->
                val hdr = ByteArray(18)
                var read = 0
                while (read < hdr.size) {
                    val n = input.read(hdr, read, hdr.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 18) return false
                (hdr[16].toInt() and 0xff) or ((hdr[17].toInt() and 0xff) shl 8)
            }
        }.getOrDefault(-1)
        return type == 3
    }

    private fun writeExecutableText(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
            file.setExecutable(true, false)
            try { Os.chmod(file.absolutePath, 0b111_101_101) } catch (_: Throwable) {}
        }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { if (isFile) readText() else null }.getOrNull()

    /**
     * Download all ARM64 tools.
     * @param onProgress called with (toolName, current, total) for progress tracking
     * @param onLog called with log messages
     * @return true if all tools downloaded successfully
     */
    fun downloadAll(
        context: Context,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        val dir = toolsDir(context)
        dir.mkdirs()
        var success = true

        for ((index, tool) in TOOLS.withIndex()) {
            val targetFile = File(dir, tool.fileName)

            // Skip if already downloaded and non-empty
            if (targetFile.exists() && targetFile.length() > 0) {
                onLog?.invoke("[SKIP] ${tool.name} already exists (${targetFile.length()} bytes)")
                onProgress?.invoke(tool.name, index + 1, TOOLS.size)
                continue
            }

            onLog?.invoke("[DOWNLOAD] ${tool.name} from ${tool.url}")

            try {
                if (tool.isArchive) {
                    downloadAndExtractTool(tool, targetFile, dir, onLog)
                } else {
                    downloadFile(tool.url, targetFile, onLog)
                }
                if (tool.isExecutable) {
                    targetFile.setExecutable(true, false)
                    try {
                        android.system.Os.chmod(targetFile.absolutePath, 0b111_101_101)
                    } catch (_: Throwable) { }
                }
                onLog?.invoke("[OK] ${tool.name}: ${targetFile.length()} bytes")
            } catch (e: Exception) {
                onLog?.invoke("[FAIL] ${tool.name}: ${e.message}")
                success = false
                // Clean up partial download
                if (targetFile.exists() && targetFile.length() == 0L) {
                    targetFile.delete()
                }
            }

            onProgress?.invoke(tool.name, index + 1, TOOLS.size)
        }

        // Create cmake directory layout for GradleRunner
        if (success) {
            runCatching { ensureCmakeLayout(context) }
            runCatching { ToolchainManager(context).ensureAssetOverridesInstalled() }
            context.getSharedPreferences("arm64_tools", Context.MODE_PRIVATE)
                .edit().putBoolean("all_downloaded", true).apply()
        }

        return success
    }

    /** Downloads a release tarball and extracts bin/<fileName> (or a root file) to [targetFile]. */
    private fun downloadAndExtractTool(
        tool: ToolDef,
        targetFile: File,
        dir: File,
        onLog: ((String) -> Unit)?
    ) {
        val archive = File(dir, "${tool.fileName}.tar.gz.part")
        val tmpDir = File(dir, "${tool.fileName}.extract")
        try {
            downloadFile(tool.url, archive, onLog)
            tmpDir.deleteRecursively()
            ToolchainArchiveInstaller.extractTarGz(archive, tmpDir) { }
            archive.delete()
            val extracted = File(tmpDir, "bin/${tool.fileName}").takeIf { it.isFile }
                ?: File(tmpDir, tool.fileName).takeIf { it.isFile }
                ?: throw Exception("${tool.fileName} not found in archive")
            if (targetFile.exists()) targetFile.delete()
            if (!extracted.copyTo(targetFile, overwrite = true).isFile) {
                throw Exception("could not extract ${tool.fileName}")
            }
            tmpDir.deleteRecursively()
        } catch (t: Throwable) {
            archive.delete()
            tmpDir.deleteRecursively()
            throw t
        }
    }

    /**
     * Downloads the aapt2 port into `toolchain/overrides/aapt2` (the path
     * `android.aapt2FromMavenOverride` points at). Replaces files that are
     * missing or pinned to an older source; `.aapt2-source` records the URL
     * that produced the current binary.
     */
    fun downloadOverridesAapt2(
        context: Context,
        onLog: ((String) -> Unit)? = null
    ): Boolean {
        val overridesDir = File(context.filesDir, "toolchain/overrides").apply { mkdirs() }
        val out = File(overridesDir, "aapt2")
        val marker = File(overridesDir, ".aapt2-source")

        val pinned = runCatching { marker.readText().trim() }.getOrDefault("")
        if (out.exists() && out.length() > 0L && pinned == AAPT2_URL) {
            onLog?.invoke("[SKIP] overrides/aapt2 already from StudioShellTools (${out.length()} bytes)")
            return true
        }

        onLog?.invoke("[DOWNLOAD] overrides/aapt2 from $AAPT2_URL")
        val tmpArchive = File(overridesDir, "aapt2.tar.gz.part")
        val tmpDir = File(overridesDir, "aapt2.extract")
        try {
            downloadFile(AAPT2_URL, tmpArchive, onLog)
            tmpDir.deleteRecursively()
            ToolchainArchiveInstaller.extractTarGz(tmpArchive, tmpDir) { }
            tmpArchive.delete()
            val extracted = File(tmpDir, "bin/aapt2").takeIf { it.isFile }
                ?: File(tmpDir, "aapt2").takeIf { it.isFile }
                ?: throw Exception("aapt2 not found in release archive")
            if (!isArm64Elf(extracted)) throw Exception("downloaded aapt2 is not an arm64 ELF")
            if (out.exists()) out.delete()
            if (!extracted.copyTo(out, overwrite = true).isFile) {
                throw Exception("could not place overrides/aapt2")
            }
            tmpDir.deleteRecursively()
            out.setExecutable(true, false)
            out.setReadable(true, false)
            try { Os.chmod(out.absolutePath, 0b111_101_101) } catch (_: Throwable) {}
            runCatching { marker.writeText(AAPT2_URL) }
            onLog?.invoke("[OK] overrides/aapt2: ${out.length()} bytes")
            return true
        } catch (e: Exception) {
            tmpArchive.delete()
            tmpDir.deleteRecursively()
            onLog?.invoke("[FAIL] overrides/aapt2: ${e.message}")
            return false
        }
    }

    private fun downloadFile(urlStr: String, target: File, onLog: ((String) -> Unit)?) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true

        // Follow GitHub redirects
        var connection = conn
        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code in 301..308) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(location).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                redirects++
            } else {
                break
            }
        }

        if (connection.responseCode != 200) {
            throw Exception("HTTP ${connection.responseCode} for $urlStr")
        }

        val totalBytes = connection.contentLength
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
                output.flush()
                if (totalBytes > 0 && totalRead < totalBytes) {
                    throw java.io.EOFException("Download incomplete: $totalRead / $totalBytes bytes received")
                }
            }
        }
        connection.disconnect()
    }

    /**
     * Verify a downloaded binary is actually an ARM64 ELF executable.
     */
    fun verifyBinary(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        try {
            val process = ProcessBuilder(file.absolutePath, "version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        } catch (_: Throwable) {
            return false
        }
    }

    /**
     * Remove all downloaded tools (for cleanup/reset).
     */
    fun removeAll(context: Context) {
        val dir = toolsDir(context)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }
}
