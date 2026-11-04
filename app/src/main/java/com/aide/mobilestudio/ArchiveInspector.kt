package com.aide.mobilestudio

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads and unpacks the archive formats a build output or a downloaded
 * dependency can realistically arrive in.
 *
 * Split out from the file browser so both halves are testable on the host:
 * listing is the part a "look inside without unpacking" action needs, and
 * extraction is the part that must not write outside where the user pointed.
 *
 * Anything that is not an archive is rejected by [isArchive] rather than
 * guessed at, because opening an arbitrary file as a tar silently produces a
 * handful of garbage entries instead of failing.
 */
object ArchiveInspector {

    /** One member of an archive, as reported by its own header. */
    data class Entry(
        val name: String,
        val size: Long,
        val compressedSize: Long,
        val isDirectory: Boolean,
    ) {
        /** Size shown to the user: raw byte counts are unreadable at a glance. */
        val humanSize: String get() = humanBytes(size)
    }

    /** Everything worth knowing about an archive without unpacking it. */
    data class Listing(
        val format: String,
        val entries: List<Entry>,
        val totalUncompressed: Long,
        val totalCompressed: Long,
        val truncated: Boolean,
    ) {
        val fileCount: Int get() = entries.count { !it.isDirectory }
        val directoryCount: Int get() = entries.count { it.isDirectory }
    }

    /** Formats this reader understands, keyed by the extensions they use. */
    private val TAR_EXTENSIONS = setOf("tar")
    private val ZIP_EXTENSIONS = setOf("zip", "apk", "aar", "jar", "xapk", "aab")

    /** Compression wrappers that may sit on top of a tar. */
    private val TAR_WRAPPERS = mapOf(
        "gz" to "tar.gz",
        "gzip" to "tar.gz",
        // The common short form; without it a .tgz was not offered as an
        // archive at all, which is the format Gradle distributions use.
        "tgz" to "tar.gz",
        "bz2" to "tar.bz2",
        "xz" to "tar.xz",
        "zst" to "tar.zst",
        "zstd" to "tar.zst",
    )

    /**
     * True for the archives this reader can list or unpack.
     *
     * A plain `.gz`/`.zst` is included: those wrap a single file rather than a
     * tar, and unpacking them still has a well-defined meaning.
     */
    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase(java.util.Locale.US)
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.bz2") ||
            lower.endsWith(".tar.xz") || lower.endsWith(".tar.zst")
        ) {
            return true
        }
        val ext = lower.substringAfterLast('.', "")
        return ext in TAR_EXTENSIONS || ext in ZIP_EXTENSIONS ||
            ext in TAR_WRAPPERS.keys
    }

    /** True when the archive is a zip family member, i.e. also readable as an APK. */
    fun isZipFamily(fileName: String): Boolean {
        val ext = fileName.lowercase(java.util.Locale.US).substringAfterLast('.', "")
        return ext in ZIP_EXTENSIONS
    }

    /**
     * Lists an archive's members without unpacking any of them.
     *
     * [limit] caps how many entries are read so a multi-gigabyte tar cannot
     * stall the UI thread that asked; [Listing.truncated] says whether the cap
     * was hit, so the caller can say "showing the first N" instead of quietly
     * lying about the archive's contents.
     */
    fun inspect(file: File, limit: Int = 5000): Listing {
        require(file.isFile) { "Not a file: ${file.name}" }
        require(isArchive(file.name)) { "Not a supported archive: ${file.name}" }

        return when {
            isZipFamily(file.name) -> inspectZip(file, limit)
            else -> inspectTar(file, limit)
        }
    }

    private fun inspectZip(file: File, limit: Int): Listing {
        val entries = mutableListOf<Entry>()
        var totalUncompressed = 0L
        var totalCompressed = 0L
        var truncated = false

        ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry: ZipEntry = enumeration.nextElement()
                if (entries.size >= limit) {
                    truncated = true
                    break
                }
                entries += Entry(
                    name = entry.name,
                    size = entry.size,
                    compressedSize = entry.compressedSize,
                    isDirectory = entry.isDirectory,
                )
                if (!entry.isDirectory) {
                    // Both sizes are -1 when a zip was written without them;
                    // adding -1 per entry would report a wrong total and no
                    // amount of display code could tell it apart from a real one.
                    if (entry.size >= 0) totalUncompressed += entry.size
                    if (entry.compressedSize >= 0) totalCompressed += entry.compressedSize
                }
            }
        }

        return Listing(
            format = if (isApkName(file.name)) "APK (zip)" else "zip",
            entries = entries,
            totalUncompressed = totalUncompressed,
            totalCompressed = totalCompressed,
            truncated = truncated,
        )
    }

    private fun inspectTar(file: File, limit: Int): Listing {
        val entries = mutableListOf<Entry>()
        var totalUncompressed = 0L
        var truncated = false

        openTarStream(file).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                if (entries.size >= limit) {
                    truncated = true
                    break
                }
                entries += Entry(
                    name = entry.name,
                    size = entry.size,
                    compressedSize = 0L,
                    isDirectory = entry.isDirectory,
                )
                if (!entry.isDirectory && entry.size > 0) {
                    totalUncompressed += entry.size
                }
            }
        }

        return Listing(
            format = tarFormatOf(file.name),
            entries = entries,
            totalUncompressed = totalUncompressed,
            totalCompressed = file.length(),
            truncated = truncated,
        )
    }

    /**
     * Unpacks an archive into [destDir].
     *
     * Every entry is resolved against [destDir] and rejected if it escapes it.
     * `../` in a member name, or an absolute one, is a zip slip: writing it as
     * given puts attacker-chosen content anywhere the app can write, which for
     * an archive downloaded off the internet is not a theoretical concern.
     *
     * Returns the number of files written.
     */
    fun extract(file: File, destDir: File, onProgress: (String) -> Unit = {}): Int {
        require(isArchive(file.name)) { "Not a supported archive: ${file.name}" }
        destDir.mkdirs()
        val written = if (isZipFamily(file.name)) {
            extractZip(file, destDir, onProgress)
        } else {
            extractTar(file, destDir, onProgress)
        }
        // Toolchain archives ship executables with no execute bit once written
        // through the API, so anything under a bin/ directory gets it back.
        restoreExecutableBits(destDir)
        return written
    }

    private fun extractZip(file: File, destDir: File, onProgress: (String) -> Unit): Int {
        var written = 0
        ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                val target = resolveWithin(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                written++
                if (written % 200 == 0) onProgress("Extracted $written files")
            }
        }
        onProgress("Extracted $written files")
        return written
    }

    private fun extractTar(file: File, destDir: File, onProgress: (String) -> Unit): Int {
        var written = 0
        openTarStream(file).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = resolveWithin(destDir, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = tar.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
                written++
                if (written % 200 == 0) onProgress("Extracted $written files")
            }
        }
        onProgress("Extracted $written files")
        return written
    }

    /**
     * Resolves an archive member name to a file inside [destDir], or null when
     * the name tries to leave it.
     */
    internal fun resolveWithin(destDir: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        // Absolute names and Windows-style roots are rejected outright rather
        // than normalised: there is no legitimate member that needs either.
        if (entryName.startsWith("/") || entryName.startsWith("\\") ||
            entryName.matches(Regex("^[A-Za-z]:.*"))
        ) {
            return null
        }
        val target = File(destDir, entryName)
        val destPath = destDir.canonicalFile.toPath()
        val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return null
        if (!targetPath.startsWith(destPath)) return null
        return target
    }

    /** Opens a tar stream with whatever compression wrapper the name implies. */
    private fun openTarStream(file: File): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(file.inputStream())
        val wrapped: InputStream = when (tarFormatOf(file.name)) {
            "tar.gz" -> GZIPInputStream(raw)
            "tar.bz2" -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw)
            "tar.xz" -> org.apache.commons.compress.compressors.xz.XZCompressorInputStream(raw)
            "tar.zst" -> ZstdInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(wrapped)
    }

    /** The tar flavour a filename implies; a bare `.tar` is uncompressed. */
    internal fun tarFormatOf(fileName: String): String {
        val lower = fileName.lowercase(java.util.Locale.US)
        for ((ext, label) in TAR_WRAPPERS) {
            if (lower.endsWith(".tar.$ext") || lower.endsWith(".$ext")) return label
        }
        return "tar"
    }

    private fun isApkName(fileName: String): Boolean =
        fileName.lowercase(java.util.Locale.US).endsWith(".apk")

    /** Puts the execute bit back on files that look like tools. */
    private fun restoreExecutableBits(root: File) {
        root.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val path = file.invariantSeparatorsPath
                val executable = file.extension in setOf("sh", "so") ||
                    path.contains("/bin/") ||
                    path.contains("/lib/") && file.extension.isEmpty()
                if (executable) {
                    runCatching { file.setExecutable(true, false) }
                }
            }
    }

    /** Byte counts formatted the way a file browser shows them. */
    fun humanBytes(bytes: Long): String = when {
        bytes < 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 ->
            String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** Kept for callers that only need the stream, e.g. reading one entry. */
    internal fun zipStreamOf(file: File): ZipInputStream =
        ZipInputStream(BufferedInputStream(file.inputStream()))
}
