package com.aide.mobilestudio

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The archive reader behind "view contents" and "extract" in the file browser.
 *
 * Listing has to be honest about what is inside without unpacking it, and
 * extraction has to stay inside the directory the user pointed at - an
 * archive from the internet is exactly where a `../` member name comes from.
 */
class ArchiveInspectorTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("ms-archive").toFile().also { tempDirs += it }

    // --------------------------------------------------------------- fixtures --

    private fun zipFixture(name: String, members: List<Pair<String, ByteArray>>): File {
        val file = File(tempDir(), name)
        ZipOutputStream(FileOutputStream(file)).use { out ->
            members.forEach { (entryName, bytes) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun tarGzFixture(name: String, members: List<Pair<String, ByteArray>>): File {
        val file = File(tempDir(), name)
        java.util.zip.GZIPOutputStream(FileOutputStream(file)).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                members.forEach { (entryName, bytes) ->
                    val entry = TarArchiveEntry(entryName)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return file
    }

    // ------------------------------------------------------------ recognition --

    @Test
    fun `the archives the browser can open are recognised`() {
        listOf(
            "app-release.apk", "lib.zip", "deps.tar", "src.tar.gz", "src.tgz",
            "src.tar.bz2", "src.tar.xz", "src.tar.zst", "blob.zst", "blob.gz",
            "module.aar", "lib.jar", "app.aab",
        ).forEach {
            assertTrue("expected $it to be an archive", ArchiveInspector.isArchive(it))
        }
    }

    @Test
    fun `everyday source files are not archives`() {
        listOf("MainActivity.kt", "AndroidManifest.xml", "build.gradle", "notes.txt", "image.png")
            .forEach {
                assertFalse("expected $it not to be an archive", ArchiveInspector.isArchive(it))
            }
    }

    @Test
    fun `zip-family detection separates zip from tar`() {
        assertTrue(ArchiveInspector.isZipFamily("app.apk"))
        assertTrue(ArchiveInspector.isZipFamily("lib.zip"))
        assertFalse(ArchiveInspector.isZipFamily("src.tar.gz"))
    }

    @Test
    fun `the tar flavour follows the wrapper`() {
        assertEquals("tar", ArchiveInspector.tarFormatOf("src.tar"))
        assertEquals("tar.gz", ArchiveInspector.tarFormatOf("src.tar.gz"))
        assertEquals("tar.gz", ArchiveInspector.tarFormatOf("src.tgz"))
        assertEquals("tar.bz2", ArchiveInspector.tarFormatOf("src.tar.bz2"))
        assertEquals("tar.xz", ArchiveInspector.tarFormatOf("src.tar.xz"))
        assertEquals("tar.zst", ArchiveInspector.tarFormatOf("src.tar.zst"))
    }

    // ---------------------------------------------------------------- listing --

    @Test
    fun `a zip is listed without being unpacked`() {
        val archive = zipFixture(
            "sample.zip",
            listOf(
                "a.txt" to "hello".toByteArray(),
                "dir/" to ByteArray(0),
                "dir/b.txt" to "world!!".toByteArray(),
            ),
        )
        val outDir = File(tempDir(), "never-created")

        val listing = ArchiveInspector.inspect(archive)

        assertEquals("zip", listing.format)
        assertEquals(3, listing.entries.size)
        assertEquals(2, listing.fileCount)
        assertEquals(1, listing.directoryCount)
        assertEquals(12L, listing.totalUncompressed) // 5 + 7
        assertFalse(outDir.exists())
    }

    @Test
    fun `an apk is labelled as one but still read as a zip`() {
        val archive = zipFixture("sample.apk", listOf("AndroidManifest.xml" to "<m/>".toByteArray()))

        val listing = ArchiveInspector.inspect(archive)

        assertEquals("APK (zip)", listing.format)
        assertEquals(1, listing.fileCount)
        assertEquals("AndroidManifest.xml", listing.entries.first().name)
    }

    @Test
    fun `a tar-gz is listed with the uncompressed total`() {
        val archive = tarGzFixture(
            "src.tar.gz",
            listOf(
                "bin/tool" to ByteArray(4096),
                "README.md" to "read me".toByteArray(),
            ),
        )

        val listing = ArchiveInspector.inspect(archive)

        assertEquals("tar.gz", listing.format)
        assertEquals(2, listing.entries.size)
        assertEquals(4096L + 7L, listing.totalUncompressed)
        // The compressed size is the file on disk, not a sum the tar lacks.
        assertEquals(archive.length(), listing.totalCompressed)
    }

    @Test
    fun `a bare tar is listed too`() {
        val archive = tarGzFixture("src.dat", listOf("only.txt" to "x".toByteArray()))
        // Re-wrap as an uncompressed tar to exercise the plain path.
        val plain = File(tempDir(), "src.tar")
        java.util.zip.GZIPInputStream(archive.inputStream()).use { gz ->
            plain.outputStream().use { gz.copyTo(it) }
        }

        val listing = ArchiveInspector.inspect(plain)

        assertEquals("tar", listing.format)
        assertEquals(1, listing.entries.size)
    }

    @Test
    fun `listing stops at the entry cap and says so`() {
        val members = (1..40).map { "f$it.txt" to ByteArray(4) }
        val archive = zipFixture("many.zip", members)

        val listing = ArchiveInspector.inspect(archive, limit = 10)

        assertEquals(10, listing.entries.size)
        assertTrue(listing.truncated)
    }

    @Test
    fun `an archive under the cap is not marked truncated`() {
        val archive = zipFixture("small.zip", listOf("a.txt" to "a".toByteArray()))

        assertFalse(ArchiveInspector.inspect(archive, limit = 10).truncated)
    }

    // ------------------------------------------------------------- extraction --

    @Test
    fun `extracting a zip restores its layout`() {
        val archive = zipFixture(
            "sample.zip",
            listOf(
                "a.txt" to "hello".toByteArray(),
                "dir/b.txt" to "world".toByteArray(),
            ),
        )
        val dest = File(tempDir(), "out")

        val count = ArchiveInspector.extract(archive, dest)

        assertEquals(2, count)
        assertEquals("hello", File(dest, "a.txt").readText())
        assertEquals("world", File(dest, "dir/b.txt").readText())
    }

    @Test
    fun `extracting a tar-gz restores its layout`() {
        val archive = tarGzFixture(
            "sample.tar.gz",
            listOf(
                "bin/tool" to "#!/bin/sh\n".toByteArray(),
                "lib/thing.txt" to "data".toByteArray(),
            ),
        )
        val dest = File(tempDir(), "out")

        val count = ArchiveInspector.extract(archive, dest)

        assertEquals(2, count)
        assertEquals("#!/bin/sh\n", File(dest, "bin/tool").readText())
        assertEquals("data", File(dest, "lib/thing.txt").readText())
    }

    @Test
    fun `a member that climbs out of the destination is refused`() {
        val archive = zipFixture("evil.zip", listOf("../../escaped.txt" to "pwned".toByteArray()))
        val dest = File(tempDir(), "out")

        val count = ArchiveInspector.extract(archive, dest)

        assertEquals(0, count)
        assertNull(File(dest.parentFile.parentFile, "escaped.txt").takeIf { it.exists() })
    }

    @Test
    fun `an absolute member is refused`() {
        val archive = zipFixture("evil.zip", listOf("/etc/passwd" to "pwned".toByteArray()))
        val dest = File(tempDir(), "out")

        assertEquals(0, ArchiveInspector.extract(archive, dest))
        assertFalse(File("/etc/passwd-ms-archive-test").exists())
    }

    @Test
    fun `a tar member that climbs out is refused`() {
        val archive = tarGzFixture("evil.tar.gz", listOf("../escaped.txt" to "pwned".toByteArray()))
        val dest = File(tempDir(), "out")

        assertEquals(0, ArchiveInspector.extract(archive, dest))
        assertFalse(File(dest.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `resolveWithin keeps well-behaved names and drops the rest`() {
        val dest = File(tempDir(), "out").apply { mkdirs() }

        assertNotNull(ArchiveInspector.resolveWithin(dest, "a/b.txt"))
        assertNotNull(ArchiveInspector.resolveWithin(dest, "plain.txt"))
        assertNull(ArchiveInspector.resolveWithin(dest, "../up.txt"))
        assertNull(ArchiveInspector.resolveWithin(dest, "a/../../up.txt"))
        assertNull(ArchiveInspector.resolveWithin(dest, "/abs.txt"))
        assertNull(ArchiveInspector.resolveWithin(dest, ""))
        assertNull(ArchiveInspector.resolveWithin(dest, "C:\\windows\\system32"))
    }

    // -------------------------------------------------------------- formatting --

    @Test
    fun `byte counts are formatted for reading`() {
        assertEquals("0 B", ArchiveInspector.humanBytes(0))
        assertEquals("512 B", ArchiveInspector.humanBytes(512))
        assertEquals("1.0 KB", ArchiveInspector.humanBytes(1024))
        assertEquals("1.5 MB", ArchiveInspector.humanBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("—", ArchiveInspector.humanBytes(-1))
    }

    @Test
    fun `insisting on a non-archive fails loudly`() {
        val plain = File(tempDir(), "notes.txt").apply { writeText("hi") }
        val thrown = runCatching { ArchiveInspector.inspect(plain) }.exceptionOrNull()
        assertNotNull("a plain file must not be read as an archive", thrown)
    }
}
