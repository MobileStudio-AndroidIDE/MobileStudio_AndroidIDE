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
 * MinGW-w64 x86_64 sysroot downloader for Windows cross-compilation.
 */
object MingwDownloader {

    private const val TAG = "MingwDownloader"
    private const val GITHUB_URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MINGW-x86_64/mingw-sysroot.tar.xz"

    data class Result(val success: Boolean, val message: String)

    /** Ensures MinGW sysroot is installed and valid. */
    fun ensureMingw(context: Context, destDir: File, onProgress: (String) -> Unit = {}): Result {
        if (isValidMingw(destDir)) {
            onProgress("MinGW already installed")
            return Result(true, "MinGW already installed")
        }

        onProgress("Downloading MinGW from GitHub...")
        val archive = File(context.cacheDir, "mingw-sysroot.tar.xz")
        return try {
            downloadAndExtract(context, archive, destDir, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "MinGW download failed: ${e.message}", e)
            Result(false, "Download failed: ${e.message}")
        }
    }

    private fun downloadAndExtract(
        context: Context,
        archive: File,
        destDir: File,
        onProgress: (String) -> Unit
    ): Result {
        val conn = (URL(GITHUB_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
            connect()
        }

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return Result(false, "HTTP ${conn.responseCode}")
        }

        val total = conn.contentLengthLong
        var done = 0L
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
                        if (total > 0 && done % (8 * 1024 * 1024) < buf.size) {
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

        onProgress("Extracting MinGW (this may take a while)...")
        extractTarXz(archive, destDir, onProgress)
        archive.delete()

        if (!isValidMingw(destDir)) {
            return Result(false, "Extracted MinGW is invalid")
        }

        onProgress("MinGW ready")
        return Result(true, "MinGW downloaded successfully")
    }

    private fun extractTarXz(archive: File, dest: File, onProgress: (String) -> Unit) {
        dest.mkdirs()
        try {
            XZCompressorInputStream(archive.inputStream().buffered()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var stripPrefix: String? = null
                    var count = 0
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
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
                        }
                        count++
                        if (count % 500 == 0) onProgress("Extracted $count files...")
                    }
                }
            }
        } catch (e: java.io.EOFException) {
            throw java.io.EOFException("Extraction failed: premature end of XZ stream")
        }
    }

    /** Validates MinGW has required x86_64 libraries and CRT objects. */
    fun isValidMingw(dir: File): Boolean {
        return try {
            if (!dir.isDirectory) return false
            val mingwLib = File(dir, "x86_64-w64-mingw32/lib")
            val crt2 = File(mingwLib, "crt2.o")
            val crtbegin = File(mingwLib, "crtbegin.o")
            val libmingw32 = File(mingwLib, "libmingw32.a")
            val libkernel32 = File(mingwLib, "libkernel32.a")
            listOf(crt2, crtbegin, libmingw32, libkernel32).all { it.isFile && it.length() > 0 }
        } catch (e: Exception) {
            false
        }
    }
}