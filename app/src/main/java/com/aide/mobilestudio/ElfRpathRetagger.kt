package com.aide.mobilestudio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Retags DT_RPATH (d_tag 0x0f) entries to DT_RUNPATH (d_tag 0x1d) in-place in
 * ELF64 little-endian shared objects.
 *
 * Android's bionic linker ignores DT_RPATH and prints a warning for EVERY
 * preloaded library on EVERY process spawn:
 *
 *   W linker: Warning: ".../jdk/lib/libjava.so" unused DT entry: DT_RPATH
 *       (type 0xf arg 0x3816) (ignoring)
 *
 * The Gradle client merges the whole JDK lib set into LD_PRELOAD, so these
 * lines flood the build log (dozens per spawned tool). Both tags take a
 * dynstr offset, so the conversion is value-preserving: bionic honours
 * DT_RUNPATH with identical $ORIGIN semantics, and the warning disappears at
 * the source. Files that already carry DT_RUNPATH are left untouched (a
 * duplicate RUNPATH entry is not well-defined).
 *
 * Runs on every build; idempotent and cheap (only a header/phdr/dynamic scan,
 * a single 8-byte write the first time).
 */
object ElfRpathRetagger {

    private const val DT_NULL = 0L
    private const val DT_RPATH = 0x0fL
    private const val DT_RUNPATH = 0x1dL
    private const val PT_DYNAMIC = 2L
    private const val ELFCLASS64 = 2.toByte()
    private const val ELFDATA2LSB = 1.toByte()

    /** Retags JDK .so files under [jdkHome] (lib, lib/server, lib/jli). Returns count of files changed. */
    fun retagJdk(jdkHome: File): Int {
        var changed = 0
        for (sub in listOf("lib", "lib/server", "lib/jli")) {
            changed += retagDirectory(File(jdkHome, sub))
        }
        return changed
    }

    /** Retags every `*.so` directly inside [dir]. Returns count of files changed. */
    fun retagDirectory(dir: File): Int {
        if (!dir.isDirectory) return 0
        var changed = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".so") && runCatching { retagFile(f) }.getOrDefault(false)) {
                changed++
            }
        }
        return changed
    }

    private fun retagFile(file: File): Boolean {
        if (file.length() < 64) return false

        // Phase 1: read-only scan; nothing in the file is modified unless a
        // DT_RPATH entry without DT_RUNPATH is actually found.
        val rpathTagOffset = scanForRpath(file) ?: return false
        if (rpathTagOffset < 0) return false

        // Phase 2: in-place retag (little-endian 8-byte write).
        if (!file.canWrite()) file.setWritable(true, false)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val tag = ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(DT_RUNPATH)
                    .array()
                raf.seek(rpathTagOffset)
                raf.write(tag)
            }
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /**
     * Returns the file offset of the first DT_RPATH d_tag, -1 when the file
     * has no DT_RPATH (or already has DT_RUNPATH), or null when the file is
     * not a retaggable ELF64-LE object.
     */
    private fun scanForRpath(file: File): Long? {
        RandomAccessFile(file, "r").use { raf ->
            if (file.length() < 64) return null
            val ident = ByteArray(16)
            raf.seek(0)
            raf.readFully(ident)
            if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) return null
            if (ident[4] != ELFCLASS64 || ident[5] != ELFDATA2LSB) return null

            raf.seek(0x20)
            val phoff = readU64(raf)
            raf.seek(0x36)
            val phentsize = readU16(raf)
            raf.seek(0x38)
            val phnum = readU16(raf)
            if (phoff <= 0 || phentsize < 56 || phnum <= 0 || phnum > 0xffff) return null

            var dynOffset = -1L
            var dynSize = 0L
            for (i in 0 until phnum) {
                raf.seek(phoff + i.toLong() * phentsize)
                if (readU32(raf) != PT_DYNAMIC) continue
                raf.seek(phoff + i.toLong() * phentsize + 8)
                dynOffset = readU64(raf)   // p_offset
                raf.seek(phoff + i.toLong() * phentsize + 32)
                dynSize = readU64(raf)     // p_filesz
                break
            }
            if (dynOffset < 0 || dynSize < 16) return null

            var rpathTagOffset = -1L
            var off = dynOffset
            val end = dynOffset + dynSize
            while (off + 16 <= end) {
                raf.seek(off)
                when (readS64(raf)) {
                    DT_NULL -> break
                    DT_RPATH -> if (rpathTagOffset < 0) rpathTagOffset = off
                    DT_RUNPATH -> return -1L // already has RUNPATH; leave alone
                }
                off += 16
            }
            return rpathTagOffset
        }
    }

    private fun readU16(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
    }

    private fun readU64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readS64(raf: RandomAccessFile): Long = readU64(raf)
}
