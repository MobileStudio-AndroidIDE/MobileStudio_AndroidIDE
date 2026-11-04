package com.aide.mobilestudio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/**
 * StudioShellTools — optional Android ARM64 CLI tools, managed in the
 * MobileStudio-AndroidIDE/StudioShellTools GitHub repository.
 *
 * Design (lazy installation):
 *  - The repository `main` branch only tracks metadata (checksums + scripts);
 *    the repository itself holds no binaries and no manifest.json.
 *  - Real tarballs live as GitHub Release assets:
 *      https://github.com/<OWNER>/<REPO>/releases/download/<RELEASE_TAG>/<tool>-android-arm64.tar.gz
 *  - The tool list / sizes / URLs come from StudioToolsIndex (release assets).
 *  - A tool is downloaded ONLY when a user first runs it (or via `tools install`).
 *  - SHA-256 is verified before install; the extracted main binary must be an
 *    ARM64 (aarch64) ELF — wrong architecture is rejected.
 *
 * Install layout:
 *   <filesDir>/tools/<tool>/bin/<tool>     executable(s)
 *   <filesDir>/tools/<tool>/lib/           bundled shared libraries
 */
object StudioShellTools {

    // ------------------------------------------------------------------
    // Central configuration — change these to move the repository/tag.
    // ------------------------------------------------------------------
    const val GITHUB_OWNER = "MobileStudio-AndroidIDE"
    const val GITHUB_REPO = "StudioShellTools"
    const val RELEASE_TAG = "tools-v1"

    /*
     * No hardcoded tool catalog here: the tools list, versions and checksums
     * are discovered dynamically from the GitHub Releases of this repository
     * (see StudioToolsIndex). The release assets are the single source of
     * truth — publishing a new "<tool>-android-arm64.tar.gz" is enough for
     * it to appear in `tools list`.
     */

    // ------------------------------------------------------------------
    // Install layout
    // ------------------------------------------------------------------

    fun toolsDir(context: Context): File = File(context.filesDir, "tools")

    fun toolInstallDir(context: Context, name: String): File =
        File(toolsDir(context), name)

    fun toolBinDir(context: Context, name: String): File =
        File(toolInstallDir(context, name), "bin")

    fun toolLibDir(context: Context, name: String): File =
        File(toolInstallDir(context, name), "lib")

    /** Installed when the primary executable exists and is non-empty. */
    fun isInstalled(context: Context, name: String): Boolean {
        val bin = toolBinDir(context, name)
        val exe = File(bin, name)
        return exe.isFile && exe.length() > 0L
    }

    /** <tools>/<tool>/bin dirs of every installed tool, for PATH. */
    fun installedBinDirs(context: Context): List<File> =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && isInstalled(context, it.name) }
            ?.map { toolBinDir(context, it.name) }
            ?.sortedBy { it.parentFile?.name }
            ?: emptyList()

    /** <tools>/<tool>/lib dirs of every installed tool, for LD_LIBRARY_PATH. */
    fun installedLibDirs(context: Context): List<File> =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && isInstalled(context, it.name) && toolLibDir(context, it.name).isDirectory }
            ?.map { toolLibDir(context, it.name) }
            ?: emptyList()

    // ------------------------------------------------------------------
    // PATH / LD_LIBRARY_PATH farm
    // ------------------------------------------------------------------

    /**
     * <tools>/bin — a stable directory that stays in the shell PATH from the
     * very first launch, holding links to every installed tool's binaries.
     * PATH itself is fixed when a shell starts, so a tool installed later
     * would otherwise be invisible to running sessions; files appearing in
     * this farm resolve immediately (lookup happens per exec).
     */
    fun binFarmDir(context: Context): File = File(toolsDir(context), "bin")

    /** <tools>/lib — same idea for LD_LIBRARY_PATH. */
    fun libFarmDir(context: Context): File = File(toolsDir(context), "lib")

    /** Rebuilds both farm directories. Call after every install/remove. */
    fun refreshFarms(context: Context) {
        refreshFarmDir(binFarmDir(context), installedBinDirs(context))
        refreshFarmDir(libFarmDir(context), installedLibDirs(context))
    }

    /**
     * Points every file of [sources] into [farm] as a symlink (hardlink as
     * fallback). Symlinks are resolved on each exec, so a reinstall that
     * replaces the target inode is picked up without another rebuild.
     * Duplicate names: last source wins (sources are name-sorted).
     */
    internal fun refreshFarmDir(farm: File, sources: List<File>) {
        farm.mkdirs()
        val wanted = LinkedHashMap<String, File>()
        for (dir in sources) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.isFile) wanted[f.name] = f
            }
        }
        for (existing in farm.listFiles() ?: emptyArray()) {
            if (existing.name !in wanted) existing.delete()
        }
        for ((name, target) in wanted) {
            val link = File(farm, name)
            val alreadyThere = runCatching {
                java.nio.file.Files.isSameFile(link.toPath(), target.toPath())
            }.getOrDefault(false)
            if (alreadyThere) continue
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }.recoverCatching {
                java.nio.file.Files.createLink(link.toPath(), target.toPath())
            }.getOrNull()
        }
    }

    /** True when the tool directory exists but the executable is broken/deleted. */
    fun isBrokenInstall(context: Context, name: String): Boolean {
        val dir = toolInstallDir(context, name)
        if (!dir.isDirectory) return false
        return !isInstalled(context, name)
    }

    // ------------------------------------------------------------------
    // Installed versions
    // ------------------------------------------------------------------

    /** Save the installed version of a tool after installation. */
    fun saveToolVersion(context: Context, name: String, version: String) {
        context.getSharedPreferences("tool_versions", Context.MODE_PRIVATE)
            .edit().putString("${name}_version", version).apply()
    }

    /** Get the installed version of a tool. */
    fun getInstalledVersion(context: Context, name: String): String {
        return context.getSharedPreferences("tool_versions", Context.MODE_PRIVATE)
            .getString("${name}_version", "") ?: ""
    }

    // ------------------------------------------------------------------
    // Verification (SHA-256 + ARM64 ELF)
    // ------------------------------------------------------------------

    fun sha256(file: File): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun verifySha256(file: File, expected: String): Boolean =
        expected.isNotBlank() && expected.equals(sha256(file), ignoreCase = true)

    enum class ElfArch { ARM64, X86_64, X86, ARM, OTHER }

    data class ArchCheck(val arch: ElfArch, val archName: String, val isElf: Boolean)

    /** Reads the ELF header: e_ident magic/class + e_machine (offset 18, LE). */
    fun checkElf(file: File): ArchCheck {
        val head = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(20)
                var off = 0
                while (off < buf.size) {
                    val n = input.read(buf, off, buf.size - off)
                    if (n < 0) break
                    off += n
                }
                buf
            }
        }.getOrDefault(ByteArray(0))
        val isElf = head.size >= 20 &&
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        if (!isElf) return ArchCheck(ElfArch.OTHER, "not an ELF", false)
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        return when (machine) {
            0xB7 -> ArchCheck(ElfArch.ARM64, "ARM64 (aarch64)", true)
            0x3E -> ArchCheck(ElfArch.X86_64, "x86-64", true)
            0x03 -> ArchCheck(ElfArch.X86, "x86", true)
            0x28 -> ArchCheck(ElfArch.ARM, "ARM (32-bit)", true)
            else -> ArchCheck(ElfArch.OTHER, "machine=$machine", true)
        }
    }

    // ------------------------------------------------------------------
    // Download + install
    // ------------------------------------------------------------------

    /** Finds the directory under [root] that contains bin/<tool>. */
    private fun locateRootWithBinary(root: File, tool: String): File {
        if (File(root, "bin/$tool").isFile) return root
        root.listFiles()?.firstOrNull { it.isDirectory && File(it, "bin/$tool").isFile }?.let { return it }
        root.walkTopDown().maxDepth(4).firstOrNull { File(it, "bin/$tool").isFile }?.let { return it }
        return root
    }

    /**
     * Downloads the release tarball for [entry], verifies its SHA-256, and
     * extracts it into <filesDir>/tools/<tool>/ (bin/ + lib/ — every extracted
     * binary lands on PATH). Existing installs are never touched when the
     * archive fails verification (extraction happens into a temp dir first).
     * Throws on any failure.
     */
    fun installTarball(
        context: Context,
        entry: StudioToolsIndex.ToolEntry,
        onMessage: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): File {
        // Fail closed: without a published checksum the archive cannot be
        // verified, so nothing is downloaded or installed.
        if (entry.sha256.isBlank()) {
            throw Exception("no SHA-256 checksum published for ${entry.name} — refusing to install")
        }
        val target = toolInstallDir(context, entry.name)
        val binDir = toolBinDir(context, entry.name).apply { mkdirs() }
        val libDir = toolLibDir(context, entry.name).apply { mkdirs() }

        val tmp = File(File(toolsDir(context), "cache"), "${entry.name}.tar.gz.part")
        val tmpDir = File(File(toolsDir(context), "cache"), "extract-${entry.name}")
        try {
            tmp.parentFile?.mkdirs()
            val versionLabel = entry.version.ifBlank { entry.releaseTag }
            onMessage("Downloading ${entry.name} $versionLabel...")
            downloadFile(entry.url, tmp) { pct -> onProgress(pct) }

            onMessage("Verifying SHA-256...")
            val actual = sha256(tmp)
            if (actual.isBlank()) throw Exception("could not hash the downloaded archive")
            if (!entry.sha256.equals(actual, ignoreCase = true)) {
                throw Exception(
                    "SHA-256 mismatch for ${entry.name}: expected ${entry.sha256.take(12)}…, got ${actual.take(12)}…"
                )
            }

            onMessage("Extracting...")
            tmpDir.deleteRecursively()
            // Path-traversal protected + symlink-skipping extraction; the
            // tarball layout is bin/<files> + lib/<libs>.
            ToolchainArchiveInstaller.extractTarGz(tmp, tmpDir) { msg -> onMessage("  $msg") }
            tmp.delete()

            var copied = 0
            for (sub in listOf("bin", "lib")) {
                val src = File(tmpDir, sub)
                if (!src.isDirectory) continue
                val dst = if (sub == "bin") binDir else libDir
                for (f in src.listFiles() ?: emptyArray()) {
                    if (!f.isFile) continue
                    f.copyTo(File(dst, f.name), overwrite = true)
                    copied++
                }
            }
            // Loose files at the tarball root (no bin/ wrapper) also go to bin/.
            for (f in tmpDir.listFiles() ?: emptyArray()) {
                if (f.isFile) {
                    f.copyTo(File(binDir, f.name), overwrite = true)
                    copied++
                }
            }
            tmpDir.deleteRecursively()

            if (copied == 0) throw Exception("archive contained no files for ${entry.name}")
            makeBinExecutable(binDir)

            if (entry.version.isNotBlank()) saveToolVersion(context, entry.name, entry.version)
            onMessage("Installed ${entry.name} (${formatSize(entry.size)}).")
            return target
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            runCatching { tmpDir.deleteRecursively() }
            throw t
        }
    }

    /** Makes every extracted binary executable (app-data files lose +x). */
    private fun makeBinExecutable(dir: File) {
        for (f in dir.listFiles() ?: emptyArray()) {
            if (!f.isFile) continue
            f.setExecutable(true, false)
            f.setReadable(true, false)
        }
    }

    private fun downloadFile(
        urlStr: String,
        target: File,
        onProgress: (Int) -> Unit
    ) {
        var url = URL(urlStr)
        var connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = true
        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code in 301..308) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(url.toURI().resolve(location).toString()).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                redirects++
            } else break
        }
        if (connection.responseCode != 200) {
            val errorMsg = when (connection.responseCode) {
                404 -> "404 Not Found"
                500 -> "500 Internal Server Error"
                503 -> "503 Service Unavailable"
                else -> "HTTP ${connection.responseCode}"
            }
            connection.disconnect()
            throw Exception("$errorMsg for $urlStr")
        }
        val totalBytes = connection.contentLength
        FileOutputStream(target).use { output ->
            val input = connection.inputStream
            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    totalRead += n
                    if (totalBytes > 0) {
                        onProgress((totalRead * 100 / totalBytes).coerceAtMost(100).toInt())
                    }
                }
                output.flush()
            } finally {
                runCatching { input.close() }
            }
            if (totalBytes > 0 && totalRead < totalBytes) {
                throw java.io.EOFException("Download incomplete: $totalRead / $totalBytes bytes received")
            }
        }
        connection.disconnect()
    }

    /** Removes an installed tool directory. */
    fun remove(context: Context, name: String): Boolean {
        val dir = toolInstallDir(context, name)
        if (!dir.isDirectory) return false
        return runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // CLI-facing helpers (used by `tools ...` in both shells)
    // ------------------------------------------------------------------

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}