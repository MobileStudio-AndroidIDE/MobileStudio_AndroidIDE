package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Box64 ARM64 binary downloader from GitHub Releases.
 */
object Box64Downloader {

    private const val TAG = "Box64Downloader"
    private const val GITHUB_URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/Box64-arm64/box64-arm64"

    data class Result(val success: Boolean, val message: String)

    /**
     * Ensures box64 binary exists and is valid.
     * Downloads from GitHub if missing or corrupted.
     */
    fun ensureBox64(context: Context, box64File: File, onProgress: (String) -> Unit = {}): Result {
        box64File.parentFile?.mkdirs()

        if (box64File.exists() && box64File.length() > 1_000_000 && isValidElf64(box64File)) {
            onProgress("box64 already installed (${box64File.length() / 1024} KB)")
            return Result(true, "box64 already installed")
        }

        onProgress("Downloading box64 from GitHub...")
        return try {
            downloadBox64(context, box64File, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "box64 download failed: ${e.message}", e)
            Result(false, "Download failed: ${e.message}")
        }
    }

    private fun downloadBox64(context: Context, dest: File, onProgress: (String) -> Unit): Result {
        val conn = (URL(GITHUB_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            connect()
        }

        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return Result(false, "HTTP ${conn.responseCode}")
        }

        val total = conn.contentLengthLong
        var done = 0L
        dest.parentFile?.mkdirs()

        try {
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0 && done % (4 * 1024 * 1024) < buf.size) {
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

        if (!dest.exists() || dest.length() < 1_000_000) {
            dest.delete()
            return Result(false, "Downloaded file too small or missing")
        }

        if (!isValidElf64(dest)) {
            dest.delete()
            return Result(false, "Downloaded file is not a valid ARM64 ELF")
        }

        dest.setExecutable(true, false)
        runCatching { android.system.Os.chmod(dest.absolutePath, 0b111_101_101) }

        onProgress("box64 ready (${dest.length() / 1024} KB)")
        return Result(true, "box64 downloaded successfully")
    }

    fun isValidElf64(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(5)
            raf.readFully(magic)
            magic[0] == 0x7F.toByte() &&
            magic[1] == 'E'.code.toByte() &&
            magic[2] == 'L'.code.toByte() &&
            magic[3] == 'F'.code.toByte() &&
            magic[4] == 2.toByte()
        }
    } catch (e: Exception) {
        false
    }

    /** Verifies box64 can actually run (executes --version). */
    fun verifyBox64Works(context: Context, box64File: File): Boolean {
        if (!box64File.exists() || box64File.length() < 1_000_000) return false
        try {
            val pb = ProcessBuilder(box64File.absolutePath, "--version")
                .directory(context.filesDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val done = proc.waitFor(30, TimeUnit.SECONDS)
            return done && proc.exitValue() == 0 && out.isNotBlank()
        } catch (e: Exception) {
            Log.w(TAG, "box64 verify failed: ${e.message}")
            return false
        }
    }
}