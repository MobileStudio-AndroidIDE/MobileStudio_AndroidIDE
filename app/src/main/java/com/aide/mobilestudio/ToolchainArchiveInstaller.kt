package com.aide.mobilestudio

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * ToolchainArchiveInstaller — shared HTTPS download + SHA-256 + safe tar.gz
 * extraction used by all on-device toolchain installers (llama runtime, CMake...).
 *
 * Security policy:
 *  - HTTPS downloads only
 *  - tar.gz extraction blocks path traversal (no absolute paths, no "..",
 *    canonical path must stay inside the destination)
 *  - symlinks/hardlinks in archives are skipped for safety
 *
 * Extraction flattens a single top-level directory (e.g. archives that wrap
 * everything in "llama-vulkan-android-arm64/" or "cmake-3.22.1-android-arm64/")
 * so the destination contains bin/, lib/, share/ directly.
 */
object ToolchainArchiveInstaller {
    private const val TAG = "ToolchainArchiveInstaller"

    /**
     * Downloads [url] (HTTPS only) into [dest] with progress reporting.
     * dest is written atomically enough: callers download to a .tmp path and
     * verify before extracting, so an interrupted download is never used.
     */
    fun download(dest: File, url: URL, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit) {
        if (url.protocol != "https") throw SecurityException("HTTPS downloads only")
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code while downloading ${url.file}")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, if (total > 0) total else downloaded)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** SHA-256 mismatch → deletes the archive and throws (safe to retry). */
    fun verifySha256(file: File, expected: String) {
        val actual = sha256(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw SecurityException(
                "SHA-256 mismatch (archive deleted, safe to retry)\n" +
                "  expected: $expected\n" +
                "  actual:   $actual"
            )
        }
    }

    /**
     * Safe tar.gz extraction with path traversal protection.
     * A single top-level directory is flattened into [dest].
     */
    fun extractTarGz(archive: File, dest: File, onLog: (String) -> Unit = {}) {
        dest.mkdirs()
        val destCanonical = dest.canonicalFile
        var count = 0
        GZIPInputStream(FileInputStream(archive)).use { gz ->
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
                if (header.all { it == 0.toByte() }) break // end of archive

                var name = readTarString(header, 0, 100)
                val size = readTarSize(header, 124)
                val typeflag = header[156].toInt().toChar()
                val paddedSize = ((size + 511) / 512) * 512

                if (typeflag == 'L') { // GNU long name entry
                    val data = ByteArray(size.toInt().coerceAtMost(64 * 1024))
                    var got = 0
                    while (got < data.size) {
                        val r = gz.read(data, got, data.size - got)
                        if (r <= 0) break
                        got += r
                    }
                    longName = String(data, 0, got).trimEnd('\u0000').trim()
                    // Consume this entry's 512-byte padding before continuing:
                    // the shared padding-skip below is bypassed by `continue`,
                    // and stale zeros desync every following header (they read
                    // as size-0 blank-name files and burn the stored longName).
                    var lPad = paddedSize - size
                    while (lPad > 0) {
                        val skipped = gz.skip(lPad)
                        if (skipped <= 0) break
                        lPad -= skipped
                    }
                    continue
                }
                if (longName != null) {
                    name = longName
                    longName = null
                } else {
                    // POSIX ustar splits long paths into a 155-byte prefix
                    // (offset 345) and the 100-byte name. Ignoring the prefix
                    // relocates those entries to a wrong top-level path; the
                    // stray dirs then defeat the single-root flattening pass
                    // below and leave the install without a top-level bin/.
                    val prefix = readTarString(header, 345, 155)
                    if (prefix.isNotBlank()) name = "$prefix/$name"
                }

                when (typeflag) {
                    '0', '\u0000' -> { // regular file
                        if (name.isNotBlank()) {
                            val outFile = resolveSafeEntry(dest, destCanonical, name)
                                ?: throw SecurityException("Path traversal blocked in archive entry: $name")
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
                            if (count % 20 == 0) onLog("Extracted $count files...")
                        }
                    }
                    '5' -> { // directory
                        if (name.isNotBlank()) {
                            val dirEntry = resolveSafeEntry(dest, destCanonical, name.trimEnd('/'))
                                ?: throw SecurityException("Path traversal blocked in archive entry: $name")
                            dirEntry.mkdirs()
                        }
                    }
                    else -> { /* symlinks/hardlinks/special: skipped for safety */ }
                }

                var skip = paddedSize - size
                while (skip > 0) {
                    val skipped = gz.skip(skip)
                    if (skipped <= 0) break
                    skip -= skipped
                }
            }
        }
        onLog("Extracted $count files")
        flattenSingleRootDir(dest)
    }

    /**
     * If the archive wrapped everything in exactly one top-level directory
     * (no sibling files), move its contents up one level into [dest].
     */
    private fun flattenSingleRootDir(dest: File) {
        val children = dest.listFiles() ?: return
        val dirs = children.filter { it.isDirectory }
        if (dirs.size != 1 || children.size != dirs.size) return
        val root = dirs[0]
        Log.i(TAG, "Flattening single root directory: ${root.name}")
        root.listFiles()?.forEach { entry ->
            val target = File(dest, entry.name)
            if (target.exists()) target.deleteRecursively()
            if (!entry.renameTo(target)) {
                // rename can fail across filesystems — fall back to copy
                entry.copyRecursively(target, overwrite = true)
                entry.deleteRecursively()
            }
        }
        root.delete()
    }

    /** Resolves an archive entry path inside [dest] or returns null on traversal. */
    private fun resolveSafeEntry(dest: File, destCanonical: File, name: String): File? {
        if (name.startsWith("/") || name.startsWith("\\")) return null // absolute
        val parts = name.replace('\\', '/').split('/')
        if (parts.any { it == ".." }) return null // parent traversal
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

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
