package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * GLIBC ARM64 prefix downloader for Box64 runtime.
 * Provides ld-linux-aarch64.so.1 and ARM64 glibc libraries.
 */
object GlibcDownloader {

    private const val TAG = "GlibcDownloader"
    private const val GITHUB_URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/GLIBC-arm64/glibc-aarch64.tar.gz"

    data class Result(val success: Boolean, val message: String)

    /** Ensures glibc prefix is installed and valid. */
    fun ensureGlibc(context: Context, destDir: File, onProgress: (String) -> Unit = {}): Result {
        if (isValidGlibc(destDir)) {
            onProgress("glibc already installed")
            return Result(true, "glibc already installed")
        }

        onProgress("Downloading glibc from GitHub...")
        val archive = File(context.cacheDir, "glibc-aarch64.tar.gz")
        return try {
            downloadAndExtract(context, archive, destDir, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "glibc download failed: ${e.message}", e)
            Result(false, "Download failed: ${e.message}")
        }
    }

    private fun downloadAndExtract(
        context: Context,
        archive: File,
        destDir: File,
        onProgress: (String) -> Unit,
        onBytes: ((Long, Long) -> Unit)? = null
    ): Result {
        val conn = (URL(GITHUB_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            connect()
        }

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return Result(false, "HTTP ${conn.responseCode}")
        }

        val total = conn.contentLengthLong
        var done = 0L
        var lastBytesAt = 0L
        archive.parentFile?.mkdirs()

        try {
            conn.inputStream.use { input ->
                FileOutputStream(archive).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastBytesAt >= 250L) {
                            lastBytesAt = now
                            if (onBytes != null) onBytes(done, total)
                        }
                        if (total > 0 && done % (64 * 1024 * 1024) < buf.size) {
                            onProgress("Downloaded ${done / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
                        }
                    }
                    if (total > 0 && done < total) {
                        throw java.io.EOFException("Download incomplete: $done / $total")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        onProgress("Extracting glibc...")
        extractTarGz(archive, destDir, onProgress)
        archive.delete()

        if (!isValidGlibc(destDir)) {
            return Result(false, "Extracted glibc is invalid")
        }

        onProgress("glibc ready")
        return Result(true, "glibc downloaded successfully")
    }

    fun extractTarGz(archive: File, dest: File, onProgress: (String) -> Unit) {
        dest.mkdirs()
        try {
            java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var stripPrefix: String? = null
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        // Checked per entry so a cancel stops extraction
                        // between files rather than after the whole archive.
                        BuildCancellation.throwIfCancelled()
                        if (stripPrefix == null) {
                            stripPrefix = if (entry.name.contains('/')) entry.name.substringBefore('/') + "/" else ""
                        }
                        val rel = entry.name.removePrefix(stripPrefix)
                        if (rel.isBlank()) continue
                        val target = File(dest, rel)
                        if (entry.isSymbolicLink) {
                            target.parentFile?.mkdirs()
                            runCatching { android.system.Os.symlink(entry.linkName, target.absolutePath) }
                        } else if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            val mode = entry.mode
                            if (mode != 0) {
                                val execBits = mode and 0b001_001_001
                                if (execBits != 0) {
                                    runCatching { target.setExecutable(true, false); target.setReadable(true, false) }
                                    runCatching { android.system.Os.chmod(target.absolutePath, (mode and 0b111_111_111)) }
                                }
                            }
                            onProgress("Extracting: $rel")
                        }
                    }
                }
            }
        } catch (e: java.io.EOFException) {
            throw java.io.EOFException("Extraction failed: premature end of stream (archive truncated)")
        }
    }

    /** Validates glibc prefix has required ARM64 libraries. */
    fun isValidGlibc(dir: File): Boolean {
        return try {
            if (!dir.isDirectory) return false
            val ldLinux = findFileRecursive(dir, "ld-linux-aarch64.so.1")
            val libcSo = findFileRecursive(dir, "libc.so.6")
            ldLinux != null && ldLinux.length() > 0 && libcSo != null && libcSo.length() > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Fixes executable bits for all binaries in glibc prefix. */
    fun fixExecutableBits(root: File) {
        if (!root.isDirectory) return
        root.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { f ->
            if (f.name.endsWith(".so") || f.name.contains(".so.") || f.name == "ld-linux-aarch64.so.1") {
                runCatching { f.setExecutable(true, false); f.setReadable(true, false) }
                runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }
            }
        }
    }

    private fun findFileRecursive(root: File, name: String): File? {
        if (!root.isDirectory) return null
        return root.walkTopDown().firstOrNull { it.isFile && it.name == name && it.length() > 0 }
    }

    /** Downloads a file from URL to destination with progress. */
    fun downloadFile(
        urlStr: String,
        dest: File,
        onProgress: (String) -> Unit = {},
        onBytes: ((Long, Long) -> Unit)? = null
    ) {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            connect()
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("HTTP ${conn.responseCode} for $urlStr")
        }
        val total = conn.contentLengthLong
        var done = 0L
        dest.parentFile?.mkdirs()
        try {
            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastBytesAt = 0L
                    while (true) {
                        if (BuildCancellation.isCancelled) {
                            // Drop the partial archive: a half-written file
                            // would be mistaken for a finished download later.
                            output.flush()
                            throw ProvisioningCancelled()
                        }
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastBytesAt >= 250L) {
                            lastBytesAt = now
                            if (onBytes != null) onBytes(done, total)
                        }
                        if (total > 0 && done % (4 * 1024 * 1024) < buf.size) {
                            onProgress("Downloaded ${done / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
                        }
                    }
                }
            }
        } catch (e: ProvisioningCancelled) {
            dest.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (total > 0) if (onBytes != null) onBytes(done, total)
    }

    /** Extracts a .tar.xz archive to destination directory. */
    fun extractTarXz(archive: File, dest: File, onProgress: (String) -> Unit = {}) {
        dest.mkdirs()
        java.io.BufferedInputStream(archive.inputStream()).use { bis ->
            XZCompressorInputStream(bis).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var stripPrefix: String? = null
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        // Checked per entry so a cancel stops extraction
                        // between files rather than after the whole archive.
                        BuildCancellation.throwIfCancelled()
                        if (stripPrefix == null) {
                            stripPrefix = if (entry.name.contains('/')) entry.name.substringBefore('/') + "/" else ""
                        }
                        val rel = entry.name.removePrefix(stripPrefix)
                        if (rel.isBlank()) continue
                        val target = File(dest, rel)
                        if (entry.isSymbolicLink) {
                            target.parentFile?.mkdirs()
                            runCatching { android.system.Os.symlink(entry.linkName, target.absolutePath) }
                        } else if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            val mode = entry.mode
                            if (mode != 0) {
                                val execBits = mode and 0b001_001_001
                                if (execBits != 0) {
                                    runCatching { target.setExecutable(true, false); target.setReadable(true, false) }
                                }
                            }
                            onProgress("Extracting: $rel")
                        }
                    }
                }
            }
        }
    }

    /** Extracts a .tar.zst archive to destination directory. */
    fun extractTarZst(archive: File, dest: File, onProgress: (String) -> Unit = {}) {
        dest.mkdirs()
        java.io.BufferedInputStream(archive.inputStream()).use { bis ->
            com.aide.mobilestudio.ZstdInputStream(bis).use { zst ->
                TarArchiveInputStream(zst).use { tar ->
                    var stripPrefix: String? = null
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        // Checked per entry so a cancel stops extraction
                        // between files rather than after the whole archive.
                        BuildCancellation.throwIfCancelled()
                        if (stripPrefix == null) {
                            stripPrefix = if (entry.name.contains('/')) entry.name.substringBefore('/') + "/" else ""
                        }
                        val rel = entry.name.removePrefix(stripPrefix)
                        if (rel.isBlank()) continue
                        val target = File(dest, rel)
                        if (entry.isSymbolicLink) {
                            target.parentFile?.mkdirs()
                            runCatching { android.system.Os.symlink(entry.linkName, target.absolutePath) }
                        } else if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            val mode = entry.mode
                            if (mode != 0) {
                                val execBits = mode and 0b001_001_001
                                if (execBits != 0) {
                                    runCatching { target.setExecutable(true, false); target.setReadable(true, false) }
                                }
                            }
                            onProgress("Extracting: $rel")
                        }
                    }
                }
            }
        }
    }
}