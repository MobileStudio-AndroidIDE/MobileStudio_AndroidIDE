package com.aide.mobilestudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile

object MediaDetector {
    enum class Kind { IMAGE, GIF, VIDEO, AUDIO, APK, PDF, DEX, ELF, ARCHIVE, TEXT, BINARY }

    private val imageExts = setOf(
        "png", "jpg", "jpeg", "webp", "bmp", "svg",
        "heic", "heif", "ico", "tiff", "tif"
    )
    private val videoExts = setOf(
        "mp4", "mkv", "webm", "mov", "3gp", "3g2", "avi",
        "m4v", "ts", "m2ts", "flv", "wmv", "f4v", "ogv"
    )
    private val audioExts = setOf(
        "mp3", "wav", "ogg", "flac", "aac", "m4a", "amr",
        "mid", "midi", "opus", "wma", "ra", "ape", "aiff",
        "caf", "mp2", "3ga", "weba"
    )
    private val archiveExts = setOf(
        "zip", "jar", "tar", "gz", "xz", "bz2", "7z",
        "rar", "war", "ear", "aar"
    )

    fun detect(file: File): Kind {
        val ext = file.name.lowercase().substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            return when {
                ext == "gif" -> Kind.GIF
                ext in imageExts -> Kind.IMAGE
                ext in videoExts -> Kind.VIDEO
                ext in audioExts -> Kind.AUDIO
                ext == "apk" -> Kind.APK
                ext == "aab" -> Kind.APK
                ext == "pdf" -> Kind.PDF
                ext == "dex" -> Kind.DEX
                ext == "so" -> Kind.ELF
                ext in archiveExts -> Kind.ARCHIVE
                else -> sniff(file)
            }
        }
        return sniff(file)
    }

    fun isMedia(kind: Kind): Boolean =
        kind == Kind.IMAGE || kind == Kind.GIF || kind == Kind.VIDEO || kind == Kind.AUDIO

    fun sniff(file: File): Kind {
        if (!file.isFile || file.length() < 4) return Kind.TEXT
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(16)
                var n = 0
                while (n < head.size) {
                    val r = raf.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                val b = head
                val isText = b.take(n).all { it in 9..13 || it in 32..126 }
                when {
                    isPng(b) -> Kind.IMAGE
                    isJpeg(b) -> Kind.IMAGE
                    isWebp(b) -> Kind.IMAGE
                    isGif(b) -> Kind.GIF
                    isBmp(b) -> Kind.IMAGE
                    isMp3(b) -> Kind.AUDIO
                    isFlac(b) -> Kind.AUDIO
                    isOgg(b) -> Kind.AUDIO
                    isWav(b) -> Kind.AUDIO
                    isMp4(b) -> Kind.VIDEO
                    isMkv(b) -> Kind.VIDEO
                    isZip(b) -> Kind.ARCHIVE
                    isElf(b) -> Kind.ELF
                    isDex(b) -> Kind.DEX
                    isPdf(b) -> Kind.PDF
                    isGzip(b) -> Kind.ARCHIVE
                    isXml(b, n) -> Kind.TEXT
                    isText(b, n) -> Kind.TEXT
                    else -> Kind.BINARY
                }
            }
        } catch (_: Exception) {
            Kind.BINARY
        }
    }

    private fun isText(b: ByteArray, n: Int): Boolean {
        if (n == 0) return false
        var text = 0
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            if (v in 9..13 || v in 32..126 || v >= 128) text++
        }
        return text >= n * 0.92f
    }

    private fun magic(b: ByteArray, off: Int, vararg bytes: Int): Boolean {
        for (i in bytes.indices) {
            if (off + i >= b.size) return false
            if ((b[off + i].toInt() and 0xFF) != bytes[i]) return false
        }
        return true
    }

    private fun isPng(b: ByteArray) = magic(b, 0, 0x89, 0x50, 0x4E, 0x47)
    private fun isJpeg(b: ByteArray) = magic(b, 0, 0xFF, 0xD8, 0xFF)
    private fun isGif(b: ByteArray) =
        magic(b, 0, 'G'.code, 'I'.code, 'F'.code, '8'.code)
    private fun isBmp(b: ByteArray) = magic(b, 0, 'B'.code, 'M'.code)
    private fun isWebp(b: ByteArray) =
        magic(b, 0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            magic(b, 8, 'W'.code, 'E'.code, 'B'.code, 'P'.code)
    private fun isMp4(b: ByteArray) =
        (b.size >= 12 && magic(b, 4, 'f'.code, 't'.code, 'y'.code, 'p'.code)) ||
            magic(b, 4, 0x66, 0x74, 0x79, 0x70)
    private fun isMkv(b: ByteArray) = magic(b, 0, 0x1A, 0x45, 0xDF, 0xA3)
    private fun isMp3(b: ByteArray) =
        magic(b, 0, 'I'.code, 'D'.code, '3'.code) ||
            magic(b, 0, 0xFF, 0xFB) || magic(b, 0, 0xFF, 0xF3) || magic(b, 0, 0xFF, 0xF2)
    private fun isFlac(b: ByteArray) = magic(b, 0, 'f'.code, 'L'.code, 'a'.code, 'C'.code)
    private fun isOgg(b: ByteArray) = magic(b, 0, 'O'.code, 'g'.code, 'g'.code, 'S'.code)
    private fun isWav(b: ByteArray) =
        magic(b, 0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) &&
            magic(b, 8, 'W'.code, 'A'.code, 'V'.code, 'E'.code)
    private fun isZip(b: ByteArray) =
        magic(b, 0, 0x50, 0x4B, 0x03, 0x04) ||
            magic(b, 0, 0x50, 0x4B, 0x05, 0x06) ||
            magic(b, 0, 0x50, 0x4B, 0x07, 0x08)
    private fun isElf(b: ByteArray) = magic(b, 0, 0x7F, 'E'.code, 'L'.code, 'F'.code)
    private fun isDex(b: ByteArray) = magic(b, 0, 'D'.code, 'E'.code, 'X'.code)
    private fun isPdf(b: ByteArray) = magic(b, 0, '%'.code, 'P'.code, 'D'.code, 'F'.code)
    private fun isGzip(b: ByteArray) = magic(b, 0, 0x1F, 0x8B)
    private fun isXml(b: ByteArray, n: Int): Boolean {
        val s = String(b, 0, n)
        return s.contains("<?xml") || s.contains("<!DOCTYPE")
    }

    fun thumbnail(file: File, size: Int = 96): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= size && opts.outHeight / (sample * 2) >= size) sample *= 2
            val full = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(file.absolutePath, full) ?: return null
            centerCrop(raw, size)
        } catch (_: Exception) {
            null
        }
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        if (side <= 0) return src
        return try {
            val left = (src.width - side) / 2
            val top = (src.height - side) / 2
            val crop = Bitmap.createBitmap(src, left, top, side, side)
            if (crop.width != size || crop.height != size) {
                Bitmap.createScaledBitmap(crop, size, size, true)
            } else crop
        } catch (_: Exception) {
            src
        }
    }
}