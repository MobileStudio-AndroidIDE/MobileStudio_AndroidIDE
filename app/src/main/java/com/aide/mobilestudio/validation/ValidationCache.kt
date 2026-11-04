package com.aide.mobilestudio.validation

import java.io.File
import java.security.MessageDigest

/**
 * Incremental result cache for expensive checks (compiler runs, aapt2).
 *
 * A check computes the fingerprint of its member files (path + mtime + size +
 * content hash) mixed with the toolchain signature; when a matching entry
 * exists the stored issues are returned without re-running the tool. The
 * storage format is line-based (no org.json on the host unit-test classpath).
 *
 * Cache location: `<cacheDir>` — on device `<filesDir>/.cache/validator/cache`.
 */
class ValidationCache(private val dir: File) {

    init {
        runCatching { dir.mkdirs() }
    }

    data class GroupKey(val checkId: String, val groupPath: String, val members: List<File>)

    fun computeKey(toolSignature: String, root: File, key: GroupKey): String {
        val sb = StringBuilder()
        sb.append("v1\n").append(key.checkId).append('\n')
            .append(toolSignature).append('\n')
            .append(root.absolutePath).append('\n')
            .append(key.groupPath).append('\n')
        for (f in key.members.sortedBy { it.absolutePath }) {
            sb.append(relPath(root, f)).append('|')
                .append(f.length()).append('|')
                .append(f.lastModified()).append('|')
                .append(contentHash(f)).append('\n')
        }
        return ValidationToolchain.sha256Hex(sb.toString())
    }

    fun load(key: String): List<InspectionIssue>? {
        val file = entryFile(key) ?: return null
        if (!file.isFile) return null
        return try {
            val lines = file.readText(Charsets.UTF_8).lineSequence().toList()
            if (lines.isEmpty() || lines[0] != HEADER + key) return null
            lines.drop(1).mapNotNull { deserialize(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun store(key: String, issues: List<InspectionIssue>) {
        val file = entryFile(key) ?: return
        try {
            val sb = StringBuilder()
            sb.append(HEADER).append(key).append('\n')
            for (issue in issues) {
                sb.append(serialize(issue)).append('\n')
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            pruneOldEntries()
        } catch (_: Exception) {
            // Cache is best-effort; a failed write must never fail the check.
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun entryFile(key: String): File? {
        if (key.isEmpty()) return null
        if (!dir.isDirectory) runCatching { dir.mkdirs() }
        return File(dir, "$key.idx")
    }

    private fun pruneOldEntries(maxEntries: Int = 800) {
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".idx") } ?: return
        if (files.size <= maxEntries) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxEntries)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val HEADER = "v1|"

        fun relPath(root: File, file: File): String = try {
            file.relativeTo(root).path
        } catch (_: Exception) {
            file.absolutePath
        }

        /** SHA-256 of the file bytes, streamed; large files fall back to identity. */
        fun contentHash(file: File): String {
            return try {
                if (!file.isFile) return "missing"
                if (file.length() > 32L * 1024 * 1024) return "big:${file.length()}:${file.lastModified()}"
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                "err:${file.lastModified()}"
            }
        }

        fun serialize(issue: InspectionIssue): String {
            fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p")
                .replace("\n", "\\n").replace("\r", "")
            return listOf(
                issue.severity.name,
                issue.category.name,
                issue.line.toString(),
                issue.column.toString(),
                issue.errorCode,
                issue.file?.let { esc(it.absolutePath) } ?: "",
                esc(issue.message)
            ).joinToString("|")
        }

        fun deserialize(line: String): InspectionIssue? {
            fun unesc(s: String): String {
                val sb = StringBuilder(s.length)
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        when (s[i + 1]) {
                            'p' -> sb.append('|')
                            'n' -> sb.append('\n')
                            '\\' -> sb.append('\\')
                            else -> sb.append(s[i + 1])
                        }
                        i += 2
                    } else {
                        sb.append(c); i++
                    }
                }
                return sb.toString()
            }
            try {
                val parts = splitEscaped(line)
                if (parts.size < 7) return null
                val severity = InspectionSeverity.valueOf(parts[0])
                val category = InspectionCategory.valueOf(parts[1])
                val filePath = parts[5]
                return InspectionIssue(
                    severity = severity,
                    category = category,
                    file = if (filePath.isEmpty()) null else File(unesc(filePath)),
                    line = parts[2].toIntOrNull() ?: 0,
                    column = parts[3].toIntOrNull() ?: 0,
                    errorCode = parts[4],
                    message = unesc(parts[6])
                )
            } catch (_: Exception) {
                return null
            }
        }

        /** Splits on unescaped '|'; the message field keeps any remaining escaped pipes. */
        private fun splitEscaped(line: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (c == '\\' && i + 1 < line.length) {
                    sb.append(c).append(line[i + 1]); i += 2; continue
                }
                if (c == '|') {
                    out.add(sb.toString()); sb.setLength(0); i++; continue
                }
                sb.append(c); i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
