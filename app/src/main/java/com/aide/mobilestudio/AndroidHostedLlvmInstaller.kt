package com.aide.mobilestudio

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException

object AndroidHostedLlvmInstaller {
    const val DEFAULT_URL: String =
        "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio_LLVM_arm64/llvm-android-arm64-bin.tar.gz"
    private const val LEGACY_UNVERIFIED_HOST = "bnsmb.de/files/public/Android/clang19_toolchain"
    private const val ANDROID_HOST_TAG_MARKER = "MobileStudio: Android host tag"
    private val STUB_NAME_REGEX = Regex("[A-Za-z0-9_.+\\-]+")

    fun hasUsableCompiler(context: Context, extraRoots: List<File> = emptyList()): Boolean {
        return searchRoots(context, extraRoots).any { compilerDirIfUsable(it) != null }
    }

    fun repairSymlinksIfNeeded(context: Context): Boolean {
        val repaired = repairBrokenSymlinks(context)
        patchNdkToolchainFilesForAndroidHost(context)
        repairNdkPrebuiltBinStubs(context)
        routeNdkPrebuiltToAndroidLlvm(context)
        linkNdkClangResourceDirForClangVersion(context)
        return repaired
    }

    /**
     * CMAKE_HOST_SYSTEM_NAME is "Android" on-device, so the stock toolchain
     * files' Linux/Darwin/Windows branches never match, ANDROID_HOST_TAG stays
     * empty and cmake resolves ".../prebuilt//bin/clang". Teach both toolchain
     * variants to fall back to the populated linux-x86_64 prebuilt directory;
     * its tools are routed through the on-device linker64/shim exec chain.
     */
    fun patchNdkToolchainFilesForAndroidHost(context: Context): Boolean {
        var patched = false
        forEachNdkDir(context) { ndk ->
            File(ndk, "build/cmake").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".toolchain.cmake") }
                ?.forEach { if (patchToolchainFileForAndroidHost(it)) patched = true }
        }
        return patched
    }

    /** File-level hook for host tests; idempotent via an inline marker. */
    internal fun patchToolchainFileForAndroidHost(file: File): Boolean {
        if (!file.isFile) return false
        val text = try { file.readText() } catch (e: Exception) { return false }
        if (text.contains(ANDROID_HOST_TAG_MARKER)) return false
        val anchor = "if(CMAKE_HOST_SYSTEM_NAME STREQUAL Linux)"
        val idx = text.indexOf(anchor)
        if (idx < 0) return false
        val patch = "# $ANDROID_HOST_TAG_MARKER: CMAKE_HOST_SYSTEM_NAME is \"Android\"\n" +
            "# on-device, so the branches below never match. Reuse the populated\n" +
            "# linux-x86_64 prebuilt directory (tools run via linker64/shim).\n" +
            "if(CMAKE_HOST_SYSTEM_NAME STREQUAL Android)\n" +
            "  set(ANDROID_HOST_TAG linux-x86_64)\n" +
            "endif()\n\n"
        file.writeText(text.substring(0, idx) + patch + text.substring(idx))
        return true
    }

    /**
     * The SDK zip extractor stores symlink entries as regular files whose
     * content is the link target ("clang" -> text "clang-18"). Restore them as
     * real relative symlinks so exec lookup resolves the actual ELF.
     */
    fun repairNdkPrebuiltBinStubs(context: Context): Boolean {
        var fixed = false
        forEachNdkDir(context) { ndk ->
            File(ndk, "toolchains/llvm/prebuilt").listFiles()?.forEach { host ->
                if (repairTextStubSymlinks(File(host, "bin"))) fixed = true
            }
        }
        return fixed
    }

    /** File-level hook for host tests. */
    internal fun repairTextStubSymlinks(binDir: File): Boolean {
        if (!binDir.isDirectory) return false
        var fixed = false
        binDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val len = file.length()
            if (len == 0L || len > 64L) return@forEach
            val targetName = try { file.readText().trim() } catch (e: Exception) { return@forEach }
            if (targetName == file.name) return@forEach
            if (!targetName.matches(STUB_NAME_REGEX)) return@forEach
            val target = File(binDir, targetName)
            if (!target.isFile || target.length() == 0L) return@forEach
            if (!file.delete()) return@forEach
            try {
                java.nio.file.Files.createSymbolicLink(
                    file.toPath(),
                    java.nio.file.Paths.get(targetName)
                )
                fixed = true
            } catch (e: Exception) {
                runCatching { file.writeText(targetName) } // restore the stub
            }
        }
        return fixed
    }

    /**
     * The NDK ships x86_64 Linux binaries (e_machine 0x3E) that cannot run
     * on-device. Point every such entry at the Android-hosted arm64 LLVM twin
     * in files/toolchain/llvm/bin so the exec chain runs a native compiler.
     * Runs after [repairNdkPrebuiltBinStubs] so text stubs have already been
     * resolved to (x86_64) ELF targets.
     */
    fun routeNdkPrebuiltToAndroidLlvm(context: Context): Boolean {
        val llvmBin = File(context.filesDir, "toolchain/llvm/bin")
        if (!llvmBin.isDirectory) return false
        var routed = false
        forEachNdkDir(context) { ndk ->
            File(ndk, "toolchains/llvm/prebuilt").listFiles()?.forEach { host ->
                if (routeX86EntriesToHostLlvm(File(host, "bin"), llvmBin)) routed = true
            }
        }
        return routed
    }

    /** File-level hook for host tests; idempotent (arm64 targets are skipped). */
    internal fun routeX86EntriesToHostLlvm(binDir: File, llvmBin: File): Boolean {
        if (!binDir.isDirectory) return false
        var routed = false
        binDir.listFiles()?.forEach { entry ->
            val twin = File(llvmBin, entry.name)
            if (!twin.isFile || twin.length() == 0L) return@forEach
            if (!isX86_64Elf(entry)) return@forEach
            if (!entry.delete()) return@forEach
            try {
                java.nio.file.Files.createSymbolicLink(
                    entry.toPath(),
                    java.nio.file.Paths.get(twin.absolutePath)
                )
                routed = true
            } catch (e: Exception) {
                // Best effort; the next build retries the routing.
            }
        }
        return routed
    }

    /**
     * The bundled arm64 clang resolves its resource directory from argv[0]
     * as "../lib/clang/<own major version>", but the NDK only ships the
     * stock versioned entry (e.g. "18"). Without that directory clang sees
     * no -L<resource>/lib/linux/<arch>, so -latomic, -l:libunwind.a and
     * libclang_rt.builtins all fail. Point the expected version entry at
     * the stock one so runtime lookups match a desktop NDK layout.
     */
    fun linkNdkClangResourceDirForClangVersion(context: Context): Boolean {
        val llvmClangLib = File(context.filesDir, "toolchain/llvm/lib/clang")
        val versions = llvmClangLib.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            .orEmpty()
        if (versions.isEmpty()) return false
        var linked = false
        forEachNdkDir(context) { ndk ->
            File(ndk, "toolchains/llvm/prebuilt").listFiles()?.forEach { host ->
                if (linkClangResourceDir(File(host, "lib/clang"), versions)) linked = true
            }
        }
        return linked
    }

    /** File-level hook for host tests; idempotent. */
    internal fun linkClangResourceDir(clangLibDir: File, versions: List<String>): Boolean {
        if (!clangLibDir.isDirectory) return false
        val stock = clangLibDir.listFiles()
            ?.filter { it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            ?.map { it.name }
            ?.firstOrNull { it !in versions }
            ?: return false
        var linked = false
        for (version in versions) {
            if (version == stock) continue
            val link = File(clangLibDir, version)
            val symlink = java.nio.file.Files.isSymbolicLink(link.toPath())
            if (link.isDirectory && !symlink) continue
            if (symlink && link.exists()) continue
            if (link.exists() || symlink) {
                if (!link.delete()) continue
            }
            try {
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(),
                    java.nio.file.Paths.get(stock)
                )
                linked = true
            } catch (e: Exception) {
                // Best effort; the next build retries the link.
            }
        }
        return linked
    }

    /** ELF64 little-endian with e_machine == EM_X86_64 (0x3E). */
    internal fun isX86_64Elf(file: File): Boolean {
        return try {
            java.io.RandomAccessFile(file, "r").use { r ->
                if (r.length() < 20) return false
                val hdr = ByteArray(20)
                r.readFully(hdr)
                hdr[0] == 0x7f.toByte() &&
                    hdr[1] == 'E'.code.toByte() &&
                    hdr[2] == 'L'.code.toByte() &&
                    hdr[3] == 'F'.code.toByte() &&
                    hdr[4].toInt() == 2 &&
                    (hdr[18].toInt() and 0xff) == 0x3e &&
                    hdr[19].toInt() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private inline fun forEachNdkDir(context: Context, action: (File) -> Unit) {
        val sdkRoot = File(context.filesDir, "toolchain/sdk")
        sdkRoot.listFiles()?.forEach { sdk ->
            File(sdk, "ndk").listFiles()?.forEach { ndk ->
                if (ndk.isDirectory) action(ndk)
            }
        }
    }

    fun ensureInstalled(
        context: Context,
        extraRoots: List<File>,
        onLine: (String, LineLevel) -> Unit,
        onBytes: ((Long, Long) -> Unit)? = null
    ): Boolean {
        if (hasUsableCompiler(context, extraRoots)) return true
        if (repairBrokenSymlinks(context)) return true

        val prefs = context.getSharedPreferences("ondevice_ai", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("android_llvm_url", DEFAULT_URL).orEmpty()
        val url = when {
            savedUrl.isBlank() -> DEFAULT_URL
            savedUrl.contains(LEGACY_UNVERIFIED_HOST) -> DEFAULT_URL
            else -> savedUrl
        }
        if (url != savedUrl) {
            prefs.edit().putString("android_llvm_url", url).apply()
        }
        val targetDir = File(context.filesDir, "toolchain/llvm")
        val cacheDir = File(context.cacheDir, "android_llvm_auto").apply {
            deleteRecursively()
            mkdirs()
        }
        val archive = File(cacheDir, "android-llvm.tar.gz")
        val unpacked = File(cacheDir, "unpacked").apply { mkdirs() }

        onLine("> Android-hosted LLVM not found; downloading compiler for on-device NDK builds.", LineLevel.WARNING)
        download(url, archive, onLine, onBytes)
        extractTarGz(archive, unpacked)
        val root = locateAndroidLlvmRoot(unpacked)
        targetDir.deleteRecursively()
        copyDirectory(root, targetDir)
        markExecutables(targetDir)
        cacheDir.deleteRecursively()
        onLine("> Android-hosted LLVM installed: ${targetDir.absolutePath}", LineLevel.SUCCESS)
        return hasUsableCompiler(context, extraRoots + targetDir)
    }

    private fun repairBrokenSymlinks(context: Context): Boolean {
        val roots = searchRoots(context, emptyList())
        var anyFixed = false
        for (root in roots) {
            val dirs = listOf(
                root,
                File(root, "usr/clang19"),
                File(root, "usr/clang20")
            )
            for (dir in dirs) {
                if (repairBinSymlinks(dir)) anyFixed = true
                if (repairLibSymlinks(dir)) anyFixed = true
            }
            // Ensure ld.lld and lld exist next to clang so clang can find them when linking
            ensureLinkerToolsNextToClang(root)
        }
        return anyFixed
    }

    private fun ensureLinkerToolsNextToClang(root: File) {
        // clang looks for ld.lld in its own bin/ directory when acting as linker driver
        // The tools may be in usr/bin/ but not in usr/clang19/bin/
        val usrBin = File(root, "usr/bin")
        if (!usrBin.isDirectory) return
        val linkerTools = listOf("ld.lld", "lld", "lld-link", "ld64.lld", "wasm-ld")
        val clangDirs = listOf(File(root, "usr/clang19"), File(root, "usr/clang20"))
        for (clangDir in clangDirs) {
            val clangBin = File(clangDir, "bin")
            if (!clangBin.isDirectory) continue
            for (tool in linkerTools) {
                val target = File(clangBin, tool)
                if (target.exists() && target.length() > 0) continue
                val source = File(usrBin, tool)
                if (!source.exists() || source.length() == 0L) continue
                try {
                    source.copyTo(target, overwrite = false)
                    target.setExecutable(true, false)
                } catch (_: Exception) {}
            }
        }
    }

    private fun repairBinSymlinks(dir: File): Boolean {
        val bin = File(dir, "bin")
        if (!bin.isDirectory) return false
        val clang = File(bin, "clang")
        val clangpp = File(bin, "clang++")
        val clang19 = File(bin, "clang-19")
        if (clang.length() > 0 || clangpp.length() > 0) return false
        if (!clang19.isFile || clang19.length() == 0L) return false
        val fixed = listOf(
            "clang" to "clang-19",
            "clang++" to "clang-19",
            "clang-cl" to "clang-19",
            "clang-cpp" to "clang-19",
            "ld" to "ld.lld",
            "ld.lld" to "lld",
            "ld64.lld" to "lld",
            "lld-link" to "lld",
            "wasm-ld" to "lld",
            "llvm-addr2line" to "llvm-symbolizer",
            "ar" to "llvm-ar",
            "llvm-lib" to "llvm-ar",
            "llvm-dlltool" to "llvm-ar",
            "llvm-otool" to "llvm-objdump",
            "llvm-readelf" to "llvm-readobj",
            "llvm-strip" to "llvm-objcopy",
            "llvm-windres" to "llvm-rc",
            "llvm-install-name-tool" to "llvm-objcopy",
            "llvm-bitcode-strip" to "llvm-objcopy"
        )
        for ((name, target) in fixed) {
            val link = File(bin, name)
            if (link.isFile && link.length() == 0L) {
                val targetFile = File(bin, target)
                if (targetFile.isFile && targetFile.length() > 0) {
                    link.delete()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            link.toPath(), java.nio.file.Paths.get(target)
                        )
                    } catch (_: Exception) {
                        targetFile.copyTo(link, overwrite = true)
                    }
                }
            }
        }
        return clang.length() > 0 || clangpp.length() > 0
    }

    private fun repairLibSymlinks(dir: File): Boolean {
        val lib = File(dir, "lib")
        if (!lib.isDirectory) return false
        var anyFixed = false
        val nonEmpty = lib.listFiles()?.filter { it.isFile && it.length() > 0 && !it.name.startsWith(".") }
            ?: return false
        if (nonEmpty.isEmpty()) return false
        val emptyFiles = lib.listFiles()?.filter { it.isFile && it.length() == 0L } ?: return false
        for (empty in emptyFiles) {
            val name = empty.name
            val base = name.replace(Regex("\\.(so|dll)(\\.\\d+)*$"), "")
            val candidates = nonEmpty
                .filter { it.name.replace(Regex("\\.(so|dll)(\\.\\d+)*$"), "") == base }
                .sortedByDescending { it.name.length }
            val best = candidates.firstOrNull()
            if (best != null && best.name != name) {
                empty.delete()
                try {
                    java.nio.file.Files.createSymbolicLink(
                        empty.toPath(), java.nio.file.Paths.get(best.name)
                    )
                } catch (_: Exception) {
                    best.copyTo(empty, overwrite = true)
                }
                anyFixed = true
            }
        }
        return anyFixed
    }

    private fun searchRoots(context: Context, extraRoots: List<File>): List<File> {
        return buildList {
            extraRoots.forEach { add(it) }
            add(File(context.filesDir, "toolchain/llvm"))
            add(File(context.filesDir, "toolchain/android-llvm"))
            add(File(context.filesDir, "toolchain/ndk-host"))
            add(File(context.filesDir, "usr"))
            // An installed NDK ships its own clang under
            // toolchains/llvm/prebuilt/<host>/bin, which compilerDirIfUsable
            // already understands. Without these roots the probe could not see
            // an NDK that was right there on disk, concluded "no compiler" and
            // downloaded a second ~1100MB toolchain on every native build.
            ndkSearchRoots(context.filesDir).forEach { add(it) }
        }.map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinctBy { it.absolutePath }
    }

    /**
     * Every directory under the app's files dir that may hold an NDK, i.e. the
     * two layouts the app installs into:
     *
     *  - `toolchain/ndk/<name>` — the Android ARM64 builds fetched from GitHub
     *  - `toolchain/sdk/<name>/ndk/<version>` — SDK-installed NDKs
     *
     * Kept separate from [searchRoots] (and taking a [File] rather than a
     * [Context]) so the layout is covered by host tests. [compilerDirIfUsable]
     * then walks into the `toolchains/llvm/prebuilt/<host>` tree these contain
     * and rejects desktop-hosted toolchains, which cannot execute on-device.
     */
    internal fun ndkSearchRoots(filesDir: File): List<File> {
        val roots = mutableListOf<File>()
        File(filesDir, "toolchain/ndk").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { roots += it }
        File(filesDir, "toolchain/sdk").listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { sdk -> File(sdk, "ndk").listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { roots += it } }
        return roots
    }

    private fun download(
        urlText: String,
        output: File,
        onLine: (String, LineLevel) -> Unit,
        onBytes: ((Long, Long) -> Unit)? = null
    ) {
        output.parentFile?.mkdirs()
        val conn = openHttpsConnection(urlText)
        val total = conn.contentLengthLong
        var done = 0L
        var lastEmit = 0L
        var lastBytesAt = 0L
        conn.inputStream.use { input ->
            output.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (BuildCancellation.isCancelled) throw ProvisioningCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    val now = System.currentTimeMillis()
                    if (now - lastBytesAt >= 250L) {
                        lastBytesAt = now
                        onBytes?.invoke(done, total)
                    }
                    if (now - lastEmit > 3_000L) {
                        lastEmit = now
                        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else -1
                        val percent = if (pct >= 0) "$pct%" else "..."
                        onLine("> Android LLVM download: $percent (${formatBytes(done)}${if (total > 0) " / ${formatBytes(total)}" else ""})", LineLevel.INFO)
                    }
                }
            }
        }
        if (total > 0) onBytes?.invoke(done, total)
    }

    private fun openHttpsConnection(urlText: String): HttpURLConnection {
        var current = urlText
        repeat(5) {
            val url = URL(current)
            require(url.protocol == "https") { "Android LLVM download must use HTTPS: $current" }
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "MobileStudio")
            }
            val code = try {
                conn.responseCode
            } catch (e: SSLException) {
                conn.disconnect()
                error(
                    "Android LLVM download TLS verification failed for $current. " +
                        "Refusing unsafe compiler download. Set a verified HTTPS URL in Build Tools > NDK > Android LLVM URL. " +
                        "Details: ${e.message.orEmpty()}"
                )
            }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                require(!location.isNullOrBlank()) { "Android LLVM download redirect had no Location header." }
                val next = URL(url, location).toString()
                require(next.startsWith("https://")) { "Refusing non-HTTPS Android LLVM redirect: $next" }
                current = next
                return@repeat
            }
            require(code in 200..299) { "Android LLVM download failed ($code): $current" }
            return conn
        }
        error("Too many Android LLVM download redirects.")
    }

    private fun extractTarGz(archive: File, outputDir: File) {
        GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val target = safeTarget(outputDir, entry.name)
                    if (entry.isSymbolicLink) {
                        val linkTarget = entry.linkName
                        target.parentFile?.mkdirs()
                        try {
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkTarget)
                            )
                        } catch (e: Exception) {
                            val resolved = File(target.parentFile, linkTarget)
                            if (resolved.isFile && resolved.length() > 0) {
                                target.parentFile?.mkdirs()
                                resolved.copyTo(target, overwrite = true)
                            }
                        }
                    } else if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> tar.copyTo(out) }
                    }
                }
            }
        }
    }

    private fun safeTarget(root: File, name: String): File {
        val target = File(root, name)
        val rootPath = root.canonicalPath + File.separator
        val targetPath = target.canonicalPath
        require(targetPath.startsWith(rootPath)) { "Blocked unsafe archive entry: $name" }
        return target
    }

    private fun locateAndroidLlvmRoot(root: File): File {
        root.walkTopDown().firstOrNull {
            File(it, "usr/clang19/bin/clang").isFile && File(it, "usr/clang19/bin/clang++").isFile
        }?.let { return it }
        root.walkTopDown().firstOrNull {
            File(it, "usr/clang20/bin/clang").isFile && File(it, "usr/clang20/bin/clang++").isFile
        }?.let { return it }
        root.walkTopDown().firstOrNull {
            File(it, "bin/clang").isFile && File(it, "bin/clang++").isFile
        }?.let { return it }
        error("Downloaded Android LLVM archive does not contain clang/clang++.")
    }

    /** File-level hook for host tests. */
    internal fun compilerDirIfUsable(root: File): File? {
        if (!root.isDirectory) return null
        fun usable(dir: File): Boolean {
            val clang = File(dir, "bin/clang")
            val clangpp = File(dir, "bin/clang++")
            return clang.isFile && clang.length() > 0 &&
                    clangpp.isFile && clangpp.length() > 0
        }
        if (usable(root)) return root
        listOf("usr/clang19", "usr/clang20", "llvm").forEach { child ->
            val dir = File(root, child)
            if (usable(dir)) return dir
        }
        File(root, "toolchains/llvm/prebuilt").listFiles()?.forEach { prebuilt ->
            if (prebuilt.isDirectory && !isDesktopHost(prebuilt.name)) {
                if (usable(prebuilt)) return prebuilt
            }
        }
        return null
    }

    private fun isDesktopHost(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.contains("linux-x86_64") ||
            lower.contains("darwin") ||
            lower.contains("windows") ||
            lower.contains("x86_64")
    }

    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { copyDirectory(it, File(destination, it.name)) }
        } else {
            if (!source.exists()) return
            destination.parentFile?.mkdirs()
            try {
                source.copyTo(destination, overwrite = true)
            } catch (_: Exception) {
            }
        }
    }

    private fun markExecutables(root: File) {
        root.walkTopDown().forEach { file ->
            file.setReadable(true, false)
            if (file.isFile && shouldBeExecutable(file)) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun shouldBeExecutable(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return file.parentFile?.name == "bin" ||
            name in setOf("clang", "clang++", "ld.lld", "llvm-ar", "llvm-strip", "llvm-ranlib") ||
            name.endsWith(".so")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }
}
