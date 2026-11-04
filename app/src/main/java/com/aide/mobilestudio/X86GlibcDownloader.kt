package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and extracts the **x86_64 glibc runtime** (`ld-linux-x86-64.so.2`,
 * `libc.so.6`, `libpthread.so.0`, ...) needed by box64 to load Wine's x86_64
 * binaries.
 *
 * This is a distinct package from the aarch64 glibc used to run box64 itself:
 * the mobox `glibc-prefix` package we download is AArch64, and Wine's `wine64`
 * is x86-64 with `PT_INTERP=/lib64/ld-linux-x86-64.so.2`. Without the x86_64
 * runtime box64 resolves the guest's `libc.so.6` to the wrong ELF class and
 * crashes — the root cause of "Global Symbol __libc_start_main not found" and
 * the follow-up SIGSEGV.
 *
 * Source: Debian's `libc6` amd64 package (.deb = ar archive containing
 * data.tar.xz). Extracted to `pkgs/glibc-x86_64/x86_64-linux-gnu/`.
 */
object X86GlibcDownloader {

    private const val TAG = "X86Glibc"

    /** Debian bookworm (12) libc6 amd64 — glibc 2.36, widely compatible. */
    private const val URL_PRIMARY =
        "https://deb.debian.org/debian/pool/main/g/glibc/libc6_2.36-9+deb12u14_amd64.deb"
    /** Fallback mirror. */
    private const val URL_FALLBACK =
        "https://security.debian.org/debian-security/pool/updates/main/g/glibc/libc6_2.36-9+deb12u14_amd64.deb"

    data class Result(val success: Boolean, val message: String)

    fun isInstalled(dir: File): Boolean {
        val loader = File(dir, "x86_64-linux-gnu/ld-linux-x86-64.so.2")
        val libc = File(dir, "x86_64-linux-gnu/libc.so.6")
        return loader.isFile && loader.length() > 100_000 && libc.isFile && libc.length() > 1_000_000
    }

    fun ensureX86Glibc(context: Context, dir: File, onProgress: (String) -> Unit): Result {
        if (isInstalled(dir)) return Result(true, "x86_64 glibc already installed")

        val deb = File(context.cacheDir, "libc6_amd64.deb")
        val downloaded = download(URL_PRIMARY, deb, onProgress) ||
            download(URL_FALLBACK, deb, onProgress)
        if (!downloaded || !deb.isFile || deb.length() < 500_000) {
            return Result(false, "failed to download x86_64 glibc package")
        }

        onProgress("Extracting x86_64 glibc...")
        val ok = runCatching { extractDeb(deb, dir, onProgress) }.getOrElse {
            Log.e(TAG, "extract failed", it)
            false
        }
        deb.delete()
        return if (ok && isInstalled(dir)) {
            makeExecutable(dir)
            Result(true, "x86_64 glibc ready")
        } else {
            Result(false, "x86_64 glibc extraction produced no usable loader")
        }
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for $url")
                return false
            }
            val total = conn.contentLengthLong
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct && pct % 10 == 0) {
                                lastPct = pct
                                onProgress("Downloaded $pct%")
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            false
        } finally {
            runCatching { }
        }
    }

    /** .deb is an `ar` archive: debian-binary, control.tar.*, data.tar.xz */
    private fun extractDeb(deb: File, destDir: File, onProgress: (String) -> Unit): Boolean {
        val target = File(destDir, "x86_64-linux-gnu").apply { mkdirs() }
        var extracted = 0
        BufferedInputStream(deb.inputStream()).use { raw ->
            ArArchiveInputStream(raw).use { ar ->
                var entry = ar.nextEntry
                while (entry != null) {
                    if (entry.name.startsWith("data.tar")) {
                        // Wrap the data member; TarArchiveInputStream will read
                        // until the member ends. Handle .xz (bookworm) or plain.
                        val memberStream: java.io.InputStream =
                            if (entry.name.endsWith(".xz")) XZCompressorInputStream(ar) else ar
                        TarArchiveInputStream(memberStream).use { tar ->
                            var te = tar.nextEntry
                            while (te != null) {
                                val name = te.name
                                // We only need lib/x86_64-linux-gnu/*
                                if (te.isDirectory.not() && name.startsWith("lib/x86_64-linux-gnu/")) {
                                    val fileName = name.substringAfterLast('/')
                                    if (fileName.isNotEmpty()) {
                                        val out = File(target, fileName)
                                        FileOutputStream(out).use { fos -> tar.copyTo(fos) }
                                        runCatching { out.setExecutable(true, false) }
                                        extracted++
                                    }
                                }
                                te = tar.nextEntry
                            }
                        }
                        break
                    }
                    entry = ar.nextEntry
                }
            }
        }
        onProgress("Extracted $extracted x86_64 libraries")
        Log.i(TAG, "extracted $extracted files to ${target.absolutePath}")
        return extracted > 5
    }

    private fun makeExecutable(dir: File) {
        File(dir, "x86_64-linux-gnu").listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so.2")) runCatching { f.setExecutable(true, false) }
        }
    }
}
