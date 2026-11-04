package com.aide.mobilestudio.ai

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.aide.mobilestudio.ExecHelper
import com.aide.mobilestudio.NativeExec
import com.aide.mobilestudio.StudioShellTools
import com.aide.mobilestudio.WindowsExeEnvironment
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * OpencodeEnvironment — installs and runs the opencode CLI on-device.
 *
 * Install layout (matches the PATH built by PersistentShell):
 *   <filesDir>/toolchain/ai/opencode/bin/opencode    ARM64 ELF (linux-arm64 build)
 *   <filesDir>/toolchain/ai/opencode/.version        version marker
 *   <filesDir>/opencode/config                       OPENCODE_CONFIG_DIR
 *
 * Source of the binary:
 *   1. already installed somewhere (canonical dir / StudioShellTools tools dir)
 *   2. GitHub release asset `opencode-linux-arm64.tar.gz`
 *      (anomalyco/opencode — https://github.com/anomalyco/opencode)
 *
 * Execution:
 *   opencode is a Linux (glibc) aarch64 ELF, which Android cannot execve()
 *   directly from app data. It is launched through NativeExec (libgradle_exec),
 *   which falls back to memfd+fexecve to bypass noexec/SELinux:
 *     - glibc ELF  → argv = [ld-linux-aarch64.so.1, opencode, ...] + glibc libs
 *     - bionic ELF → argv = [/system/bin/linker64, opencode, ...]
 *     - static ELF → argv = [opencode, ...]
 */
class OpencodeEnvironment(val context: Context) {

    class ServeInstance internal constructor(
        internal val handle: Long,
        val logFile: File,
        private val buffer: StringBuilder
    ) {
        @Volatile internal var exitCode: Int? = null
        @Volatile private var closed = false

        val isAlive: Boolean get() = handle != 0L && exitCode == null && !closed

        /** Combined stdout/stderr captured so far. */
        fun output(): String = synchronized(buffer) { buffer.toString() }

        fun close() {
            if (closed) return
            closed = true
            runCatching { NativeExec.kill(handle) }
        }

        internal fun markExited(code: Int) {
            exitCode = code
        }
    }

    @Volatile private var serve: ServeInstance? = null
    @Volatile private var pumpThread: Thread? = null

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    private val installDir: File get() = File(context.filesDir, "toolchain/ai/opencode")
    private val binDir: File get() = File(installDir, "bin")
    val binary: File get() = File(binDir, "opencode")
    private val versionMarker: File get() = File(installDir, ".version")
    private val configDir: File get() = File(context.filesDir, "opencode/config")
    private val defaultProjectDir: File get() = File(context.filesDir, "MobileStudio")
    private val shellBinMirror: File
        get() = File(context.filesDir, "toolchain/runtime-tools/bin/opencode")

    fun isInstalled(): Boolean = resolveBinary() != null

    fun installedVersion(): String? =
        versionMarker.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** The opencode ELF, preferring the canonical PATH location. */
    private fun resolveBinary(): File? {
        val candidates = listOf(
            binary,
            File(StudioShellTools.toolBinDir(context, "opencode"), "opencode"),
            shellBinMirror
        )
        val found = candidates.firstOrNull { it.isFile && it.length() > 0L } ?: return null
        if (found != binary && (!binary.isFile || binary.length() == 0L)) {
            runCatching {
                binary.parentFile?.mkdirs()
                found.copyTo(binary, overwrite = true)
                binary.setExecutable(true, false)
            }
        }
        if (!binary.isFile) return found
        runCatching { binary.setExecutable(true, false) }
        return binary
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    /**
     * Installs opencode (download + extract + verify). Non-blocking-safe:
     * runs on the caller's thread — call it from a background thread.
     * Returns true when a runnable binary is in place.
     */
    fun installAll(onStatus: ((String) -> Unit)? = null): Boolean {
        val status: (String) -> Unit = { msg -> Log.i(TAG, msg); onStatus?.invoke(msg) }
        try {
            resolveBinary()?.let { exe ->
                status("opencode ready (${installedVersion() ?: "installed"})")
                stageShellMirror(status)
                ensureRuntimeFor(exe, status)
                ensureDefaultConfig()
                return true
            }

            status("Fetching opencode release info...")
            val release = fetchRelease(status)
            val archive = File(context.cacheDir, "opencode-download.tar.gz")
            try {
                status("Downloading opencode ${release.version}...")
                download(release.url, archive) { read, total ->
                    val line = "Downloading opencode... ${formatSize(read)}" +
                        if (total > 0) " / ${formatSize(total)}" else ""
                    if (shouldReport(read)) status(line)
                }
                if (!archive.isFile || archive.length() == 0L) {
                    status("ERROR: download produced no data")
                    return false
                }

                status("Extracting opencode...")
                val tmp = File(context.cacheDir, "opencode-extract")
                tmp.deleteRecursively()
                tmp.mkdirs()
                extractTarGz(archive, tmp)

                val exe = tmp.walkTopDown()
                    .firstOrNull { it.isFile && it.name == "opencode" && it.length() > 0L }
                if (exe == null) {
                    status("ERROR: opencode binary not found in archive")
                    return false
                }

                val elf = StudioShellTools.checkElf(exe)
                if (!elf.isElf || elf.arch != StudioShellTools.ElfArch.ARM64) {
                    status("ERROR: unsupported binary (${elf.archName}) — ARM64 required")
                    return false
                }

                binDir.mkdirs()
                exe.copyTo(binary, overwrite = true)
                binary.setExecutable(true, false)
                binary.setReadable(true, false)
                versionMarker.writeText(release.version)

                stageShellMirror(status)
                ensureRuntimeFor(binary, status)
                ensureDefaultConfig()
                status("opencode ${release.version} installed")
                return true
            } finally {
                runCatching { archive.delete() }
                runCatching { File(context.cacheDir, "opencode-extract").deleteRecursively() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            status("ERROR: ${e.message ?: "install failed"}")
            return false
        }
    }

    /**
     * Makes `opencode` resolvable from shell terminals that only put
     * toolchain/runtime-tools/bin on PATH (symlink — the binary is ~100 MB).
     */
    private fun stageShellMirror(status: (String) -> Unit) {
        val mirrorParent = shellBinMirror.parentFile ?: return
        if (!mirrorParent.isDirectory) return
        if (shellBinMirror.isFile && shellBinMirror.length() > 0L) return
        runCatching {
            Os.symlink(binary.absolutePath, shellBinMirror.absolutePath)
            status("Linked opencode into runtime-tools/bin")
        }.onFailure {
            Log.w(TAG, "shell mirror symlink failed: ${it.message}")
        }
    }

    /**
     * Seeds a starter `opencode.json` (never overwrites a user edit) so the
     * server does not stall on permission prompts the app cannot answer —
     * `external_directory` fires constantly on-device because toolchain files
     * live outside the project cwd.
     */
    private fun ensureDefaultConfig() {
        val json = """{"${'$'}schema":"https://opencode.ai/config.json","permission":{"external_directory":"allow","doom_loop":"allow"}}"""
        val targets = listOf(
            File(configDir, "opencode.json"),
            File(context.filesDir, ".config/opencode/opencode.json"),
            File(context.filesDir, "xdg/config/opencode/opencode.json")
        )
        targets.forEach { target ->
            if (target.isFile) return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(json)
                Log.i(TAG, "seeded default config: $target")
            }.onFailure {
                Log.w(TAG, "config seed failed for $target: ${it.message}")
            }
        }
    }

    /**
     * A glibc ELF needs ld-linux-aarch64.so.1 + glibc libs; downloads the
     * glibc runtime once when the installed binary requires it.
     */
    private fun ensureRuntimeFor(exe: File, status: (String) -> Unit) {
        val interp = ExecHelper.analyzeElf(exe).interp ?: return
        if (!interp.contains("ld-linux")) return
        val win = WindowsExeEnvironment(context)
        if (win.loaderPath() != null) return
        status("Downloading glibc runtime (one time)...")
        win.installGlibcOnly { msg -> if (!msg.startsWith("ERROR")) status(msg) }
        if (win.loaderPath() == null) {
            status("ERROR: glibc runtime unavailable — opencode cannot start")
        } else {
            status("glibc runtime ready")
        }
    }

    private data class Release(val url: String, val version: String)

    /** Latest release JSON → (linux-arm64 asset URL, tag). */
    private fun fetchRelease(status: (String) -> Unit): Release {
        runCatching {
            val conn = URL(RELEASE_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MobileStudio")
            try {
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name", "latest")
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        if (asset.optString("name") == ASSET_NAME) {
                            val url = asset.optString("browser_download_url")
                            if (url.isNotBlank()) return Release(url, tag.trimStart('v'))
                        }
                    }
                }
                status("Release $tag has no $ASSET_NAME — using latest/download fallback")
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            status("Release lookup failed (${it.message}) — using fallback URL")
        }
        return Release(FALLBACK_ASSET_URL, "latest")
    }

    private var lastReportMs = 0L
    private fun shouldReport(read: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportMs < 400) return false
        lastReportMs = now
        return read > 0
    }

    private fun download(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        if (url.protocol != "https") throw SecurityException("HTTPS downloads only")
        var conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        var redirects = 0
        while (redirects < 5) {
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(location).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                redirects++
            } else break
        }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw Exception("HTTP $code for $urlStr")
        }
        val total = conn.contentLengthLong
        if (total > 0) checkStorage(total)
        dest.parentFile?.mkdirs()
        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read, if (total > 0) total else read)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun checkStorage(required: Long) {
        val stat = StatFs(context.filesDir.path)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        if (available < required + 512L * 1024 * 1024) {
            throw IllegalStateException(
                "Insufficient storage: need ${formatSize(required + 512L * 1024 * 1024)}, " +
                    "available ${formatSize(available)}"
            )
        }
    }

    /**
     * Safe tar.gz extraction: rejects absolute paths, ".." components and any
     * entry whose canonical path escapes [dest]. Regular files and directories
     * only (symlinks are skipped).
     */
    private fun extractTarGz(archive: File, dest: File) {
        dest.mkdirs()
        val destCanonical = dest.canonicalFile
        var count = 0
        GZIPInputStream(archive.inputStream()).use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                var read = 0
                while (read < 512) {
                    val r = gz.read(header, read, 512 - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < 512) break
                if (header.all { it == 0.toByte() }) break

                var name = readTarString(header, 0, 100)
                val size = readTarSize(header, 124)
                val typeflag = header[156].toInt().toChar()

                if (typeflag == 'L') {
                    val data = ByteArray(size.toInt())
                    var got = 0
                    while (got < data.size) {
                        val r = gz.read(data, got, data.size - got)
                        if (r <= 0) break
                        got += r
                    }
                    longName = String(data, 0, got).trimEnd('\u0000').trim()
                    continue
                }
                if (longName != null) {
                    name = longName
                    longName = null
                }

                val paddedSize = ((size + 511) / 512) * 512
                when (typeflag) {
                    '0', '\u0000' -> {
                        if (name.isNotBlank()) {
                            val outFile = resolveSafeEntry(dest, destCanonical, name)
                                ?: throw SecurityException("Path traversal in archive: $name")
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(1024 * 1024)
                                var remaining = size
                                while (remaining > 0) {
                                    val chunk = minOf(buffer.size.toLong(), remaining).toInt()
                                    val r = gz.read(buffer, 0, chunk)
                                    if (r <= 0) break
                                    out.write(buffer, 0, r)
                                    remaining -= r
                                }
                            }
                            outFile.setReadable(true, false)
                            outFile.setWritable(true, false)
                            count++
                        }
                    }
                    '5' -> {
                        if (name.isNotBlank()) {
                            val dir = resolveSafeEntry(dest, destCanonical, name.trimEnd('/'))
                                ?: throw SecurityException("Path traversal in archive: $name")
                            dir.mkdirs()
                        }
                    }
                    else -> { /* symlinks/hardlinks skipped */ }
                }

                var skip = paddedSize - size
                while (skip > 0) {
                    val skipped = gz.skip(skip)
                    if (skipped <= 0) break
                    skip -= skipped
                }
            }
        }
        Log.i(TAG, "extracted $count files")
    }

    private fun resolveSafeEntry(dest: File, destCanonical: File, name: String): File? {
        if (name.startsWith("/") || name.startsWith("\\")) return null
        val parts = name.replace('\\', '/').split('/')
        if (parts.any { it == ".." }) return null
        val f = File(dest, parts.joinToString("/"))
        val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
        if (!canonical.path.startsWith(destCanonical.path)) return null
        return canonical
    }

    private fun readTarString(header: ByteArray, offset: Int, maxLen: Int): String {
        var end = offset
        while (end < offset + maxLen && end < header.size && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset).trim()
    }

    private fun readTarSize(header: ByteArray, offset: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var size = (header[offset].toInt() and 0x7f).toLong()
            for (i in 1 until 12) size = (size shl 8) or (header[offset + i].toInt() and 0xff).toLong()
            return size
        }
        var result = 0L
        for (i in 0 until 12) {
            val c = header[offset + i].toInt().toChar()
            if (c == ' ' || c == '\u0000') break
            result = result * 8 + (c - '0')
        }
        return result
    }

    // ------------------------------------------------------------------
    // serve / TUI
    // ------------------------------------------------------------------

    /**
     * Starts `opencode serve --port 4096` for the HTTP API used by [AiService].
     * Returns null when the binary is missing or exec failed (see logcat).
     */
    fun startServe(
        cwd: File? = null,
        onOutput: ((String) -> Unit)? = null
    ): ServeInstance? {
        stopServe()
        val exe = resolveBinary() ?: run {
            Log.e(TAG, "startServe: opencode is not installed")
            return null
        }
        val workDir = cwd?.takeIf { it.isDirectory }
            ?: defaultProjectDir.apply { mkdirs() }

        val args: List<String>
        val env: Map<String, String>
        try {
            val built = buildExecCommand(exe, listOf("serve", "--port", PORT.toString()))
            args = built.first
            env = built.second
        } catch (e: Exception) {
            Log.e(TAG, "startServe: ${e.message}", e)
            return null
        }

        val handle = NativeExec.start(
            args[0],
            context.codeCacheDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            args,
            env.map { (k, v) -> "$k=$v" },
            workDir.absolutePath
        )
        if (handle == 0L) {
            Log.e(TAG, "startServe failed: ${NativeExec.getLastError()}")
            return null
        }

        val logFile = File(context.filesDir, "opencode-serve.log")
        val buffer = StringBuilder()
        val instance = ServeInstance(handle, logFile, buffer)
        serve = instance

        pumpThread = Thread {
            try {
                FileOutputStream(logFile, true).use { fileOut ->
                    while (true) {
                        val chunk = NativeExec.readChunk(handle, 1000) ?: break
                        if (chunk.isEmpty()) continue
                        synchronized(buffer) {
                            buffer.append(chunk)
                            if (buffer.length > 256 * 1024) buffer.delete(0, buffer.length - 256 * 1024)
                        }
                        runCatching { fileOut.write(chunk.toByteArray()); fileOut.flush() }
                        onOutput?.invoke(chunk)
                    }
                }
                val code = NativeExec.waitFor(handle)
                instance.markExited(code)
                Log.i(TAG, "opencode serve exited (code=$code)")
                if (code != 0) Log.e(TAG, "serve output tail: ${instance.output().takeLast(2000)}")
            } catch (e: Exception) {
                Log.e(TAG, "serve pump failed: ${e.message}", e)
                instance.markExited(-1)
            } finally {
                runCatching { NativeExec.free(handle) }
            }
            pumpThread = null
        }.also {
            it.isDaemon = true
            it.name = "opencode-serve"
            it.start()
        }
        Log.i(TAG, "opencode serve started (pid handle=$handle, port=$PORT, cwd=${workDir.path})")
        return instance
    }

    /** Stops a running serve process (safe to call when nothing runs). */
    fun stopServe() {
        pumpThread?.let { thread ->
            runCatching { thread.join(1_000) }
        }
        serve?.close()
        serve = null
        pumpThread = null
    }

    fun serveOutput(): String = serve?.output() ?: ""

    fun isServeAlive(): Boolean = serve?.isAlive == true

    /** Shell command that launches the interactive TUI in a terminal tab. */
    fun tuiCommandLine(): String = resolveBinary()?.absolutePath ?: "opencode"

    /**
     * argv + environment used to exec [exe] with [programArgs].
     * glibc → [ld.so, exe, args...] with glibc libs on LD_LIBRARY_PATH.
     */
    private fun buildExecCommand(
        exe: File,
        programArgs: List<String>
    ): Pair<List<String>, Map<String, String>> {
        val env = linkedMapOf<String, String>()
        env["HOME"] = context.filesDir.absolutePath
        env["SHELL"] = "/system/bin/sh"
        env["LANG"] = "en_US.UTF-8"
        env["TERM"] = "xterm-256color"
        env["TMPDIR"] = File(context.cacheDir, "android_shell_tmp").apply { mkdirs() }.absolutePath
        env["OPENCODE_CONFIG_DIR"] = configDir.apply { mkdirs() }.absolutePath
        env["XDG_CONFIG_HOME"] = File(context.filesDir, "xdg/config").apply { mkdirs() }.absolutePath
        env["XDG_DATA_HOME"] = File(context.filesDir, "xdg/data").apply { mkdirs() }.absolutePath
        env["XDG_CACHE_HOME"] = File(context.cacheDir, "xdg/cache").apply { mkdirs() }.absolutePath
        env["PATH"] = buildList {
            add("/system/bin")
            add("/system/xbin")
            add(binDir.absolutePath)
            File(context.filesDir, "toolchain/runtime-tools/bin").takeIf { it.isDirectory }?.let { add(it.absolutePath) }
            StudioShellTools.installedBinDirs(context).forEach { add(it.absolutePath) }
            File(context.filesDir, "tools/bin").takeIf { it.isDirectory }?.let { add(it.absolutePath) }
        }.distinct().joinToString(":")

        val ldDirs = mutableListOf(
            exe.parentFile?.absolutePath ?: binDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            File(context.filesDir, "toolchain/runtime-tools/lib").absolutePath
        )
        StudioShellTools.installedLibDirs(context).forEach { ldDirs.add(it.absolutePath) }

        val interp = ExecHelper.analyzeElf(exe).interp ?: ""
        var args = listOf(exe.absolutePath) + programArgs
        when {
            interp.contains("ld-linux") -> {
                val win = WindowsExeEnvironment(context)
                val ld = win.loaderPath()
                    ?: throw IllegalStateException(
                        "glibc runtime not installed (ld-linux-aarch64.so.1 missing)"
                    )
                args = listOf(ld.absolutePath, exe.absolutePath) + programArgs
                win.glibcLibDirs().forEach { ldDirs.add(it.absolutePath) }
            }
            interp.contains("linker") -> {
                val linker = if (interp.contains("linker64")) "/system/bin/linker64" else "/system/bin/linker"
                args = listOf(linker, exe.absolutePath) + programArgs
            }
        }
        env["LD_LIBRARY_PATH"] = ldDirs.filterNotNull().distinct().joinToString(":")
        return args to env
    }

    /** Removes the install, the shell mirror and any running serve process. */
    fun cleanup() {
        stopServe()
        runCatching { installDir.deleteRecursively() }
        runCatching { shellBinMirror.delete() }
        Log.i(TAG, "opencode environment cleaned")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    companion object {
        private const val TAG = "OpencodeEnvironment"

        /** Serve API port used by [AiService]. */
        const val PORT = 4096
        const val BASE_URL = "http://127.0.0.1:$PORT"

        private const val RELEASE_API =
            "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        private const val ASSET_NAME = "opencode-linux-arm64.tar.gz"
        private const val FALLBACK_ASSET_URL =
            "https://github.com/anomalyco/opencode/releases/latest/download/$ASSET_NAME"
    }
}
