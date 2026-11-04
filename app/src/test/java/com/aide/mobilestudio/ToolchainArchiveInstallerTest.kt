package com.aide.mobilestudio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * Host tests for [ToolchainArchiveInstaller.extractTarGz].
 *
 * The regression at the center of these tests: POSIX ustar stores long paths
 * as a 155-byte prefix (header offset 345) + '/' + 100-byte name. Reading only
 * the name field relocated those entries to a wrong top-level path; the stray
 * top-level directory then disabled the single-root flattening pass and the
 * install ended up without its top-level `bin/`.
 */
class ToolchainArchiveInstallerTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    // ---- minimal POSIX ustar writer -------------------------------------

    private fun writeString(header: ByteArray, offset: Int, maxLen: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= maxLen) { "'$value' exceeds $maxLen bytes" }
        System.arraycopy(bytes, 0, header, offset, bytes.size)
    }

    private fun writeOctal(header: ByteArray, offset: Int, len: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        require(digits.length <= len - 1) { "value $value does not fit in $len bytes" }
        for (i in digits.indices) header[offset + i] = digits[i].code.toByte()
        header[offset + len - 1] = 0
    }

    private fun ustarHeader(name: String, prefix: String, size: Long, typeflag: Char): ByteArray {
        val h = ByteArray(512)
        writeString(h, 0, 100, name)
        writeOctal(h, 100, 8, 420)          // mode 0644
        writeOctal(h, 108, 8, 0)            // uid
        writeOctal(h, 116, 8, 0)            // gid
        writeOctal(h, 124, 12, size)        // size
        writeOctal(h, 136, 12, 0)           // mtime
        for (i in 148 until 156) h[i] = ' '.code.toByte() // checksum placeholder
        h[156] = typeflag.code.toByte()
        writeString(h, 257, 6, "ustar")     // POSIX magic + NUL
        writeString(h, 263, 2, "00")
        writeString(h, 345, 155, prefix)    // ustar prefix field (the bug)
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xff)
        val chk = sum.toString(8).padStart(6, '0')
        for (i in chk.indices) h[148 + i] = chk[i].code.toByte()
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun putFile(
        out: OutputStream,
        prefix: String,
        name: String,
        content: String,
        typeflag: Char = '0',
    ) {
        val data = content.toByteArray(Charsets.UTF_8)
        out.write(ustarHeader(name, prefix, data.size.toLong(), typeflag))
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        out.write(ByteArray(pad))
    }

    private fun buildTarGz(target: File, block: (OutputStream) -> Unit) {
        GZIPOutputStream(FileOutputStream(target)).use { gz ->
            block(gz)
            gz.write(ByteArray(1024)) // two zero blocks terminate the archive
        }
    }

    // ---- tests -----------------------------------------------------------

    @Test
    fun ustarPrefixLongPathLandsUnderRootAndFlattens() {
        val archive = File(tempDir("archive"), "a.tar.gz")
        val longDir = "d".repeat(60)
        val longFile = "f".repeat(60)
        buildTarGz(archive) { out ->
            putFile(out, "root/bin", "cmake", "ELF")
            // prefix + '/' + name is 126 bytes — the ustar split case that
            // the name-only reader used to misplace at the top level.
            putFile(out, "root/$longDir", longFile, "long")
        }

        val dest = File(tempDir("dest-parent"), "dest")
        val logs = mutableListOf<String>()
        ToolchainArchiveInstaller.extractTarGz(archive, dest) { logs += it }

        assertEquals("ELF", File(dest, "bin/cmake").readText())
        assertEquals("long", File(dest, "$longDir/$longFile").readText())
        // Without the prefix fix the long entry lands at the top level and
        // the stray directory prevents flattening, leaving root/ in place.
        assertFalse("stale root must be flattened away", File(dest, "root").exists())
        assertFalse(
            "long entry must not be mislocated at the top level",
            File(dest, longFile).exists()
        )
        assertEquals(setOf("bin", longDir), dest.list()!!.toSet())
        assertTrue(logs.last().startsWith("Extracted "))
    }

    @Test
    fun flattensSingleRootDirectory() {
        val archive = File(tempDir("archive"), "b.tar.gz")
        buildTarGz(archive) { out -> putFile(out, "pkg/bin", "ninja", "SH") }

        val dest = File(tempDir("dest-parent"), "dest")
        ToolchainArchiveInstaller.extractTarGz(archive, dest)

        assertEquals("SH", File(dest, "bin/ninja").readText())
        assertFalse(File(dest, "pkg").exists())
    }

    @Test
    fun keepsMultipleTopLevelRoots() {
        val archive = File(tempDir("archive"), "c.tar.gz")
        buildTarGz(archive) { out ->
            putFile(out, "rootA", "f", "A")
            putFile(out, "rootB", "f", "B")
        }

        val dest = File(tempDir("dest-parent"), "dest")
        ToolchainArchiveInstaller.extractTarGz(archive, dest)

        assertEquals("A", File(dest, "rootA/f").readText())
        assertEquals("B", File(dest, "rootB/f").readText())
        assertEquals(setOf("rootA", "rootB"), dest.list()!!.toSet())
    }

    @Test
    fun rejectsPathTraversalEntries() {
        val archive = File(tempDir("archive"), "d.tar.gz")
        buildTarGz(archive) { out -> putFile(out, "", "../evil", "x") }

        val parent = tempDir("dest-parent")
        val dest = File(parent, "dest")
        try {
            ToolchainArchiveInstaller.extractTarGz(archive, dest)
            fail("expected SecurityException for ../evil entry")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("traversal"))
        }
        assertFalse(File(parent, "evil").exists())
    }

    @Test
    fun gnuLongNameEntryStillSupported() {
        val archive = File(tempDir("archive"), "e.tar.gz")
        val longPath = "root/gnu/very-long-path-name.txt"
        buildTarGz(archive) { out ->
            putFile(out, "", "././@LongLink", longPath, typeflag = 'L')
            putFile(out, "", "placeholder", "GNU")
        }

        val dest = File(tempDir("dest-parent"), "dest")
        ToolchainArchiveInstaller.extractTarGz(archive, dest)

        assertEquals("GNU", File(dest, "gnu/very-long-path-name.txt").readText())
        assertFalse(File(dest, "root").exists())
    }
}
