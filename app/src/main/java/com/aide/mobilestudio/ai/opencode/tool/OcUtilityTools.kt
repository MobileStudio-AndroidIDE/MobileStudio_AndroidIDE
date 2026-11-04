package com.aide.mobilestudio.ai.opencode.tool

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.aide.mobilestudio.ShellRunner
import com.aide.mobilestudio.ai.ui.AiDiff
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Everyday tools that are useful to an agent working in a real project, kept
 * separate from the shell because each one has a shape the model can copy:
 * listing, metadata, diffing, HTTP, git, SQLite, archives, network, time and
 * installed apps.
 *
 * Every one of them is read-only except `zip` and non-GET `http_request`, which
 * use the normal approval paths. `bash` can do all of this too, but a
 * structured tool is easier for a small model to call correctly and easier for
 * a person to review.
 */

private const val MAX_LIST_ENTRIES = 1_000
private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
private const val MAX_HTTP_CHARS = 60_000
private const val MAX_SQL_ROWS = 500
private const val MAX_ZIP_ENTRIES = 5_000
private const val MAX_ZIP_BYTES = 2L * 1024 * 1024 * 1024

private fun resolve(ctx: ToolContext, path: String): File =
    ctx.resolveInWorkspace(path).let { runCatching { it.canonicalFile }.getOrDefault(it) }

private fun formatEpoch(ms: Long): String =
    runCatching {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }.getOrDefault(ms.toString())

private fun requireFile(ctx: ToolContext, path: String): File? {
    if (path.isBlank()) return null
    val file = resolve(ctx, path)
    return if (file.exists()) file else null
}

private fun textPreview(file: File): String? {
    if (!file.isFile || file.length() > MAX_TEXT_BYTES) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    if (bytes.any { it == 0.toByte() }) return null
    return runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
}

private fun valueSuffix(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "NULL"
    else -> value.toString().replace("\n", "\\n").take(1_000)
}

/**
 * `ls`: a directory listing with the facts an agent needs to decide what to
 * read next. `depth` makes a bounded tree without a second walk.
 */
class ListDirectoryTool : OcTool {
    override val name = "ls"

    override fun description(ctx: ToolContext): String =
        "Lists files and directories with type, size and modification time. " +
            "Use depth to walk a few levels; hidden entries are omitted unless showHidden is true."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put(
                "description", "Directory to list. Defaults to the workspace root.",
            ))
            put("depth", JSONObject().put("type", "integer").put(
                "description", "Levels to walk, 1..6. Defaults to 1.",
            ))
            put("showHidden", JSONObject().put("type", "boolean").put(
                "description", "Include dotfiles and dot-directories. Defaults to false.",
            ))
            put("limit", JSONObject().put("type", "integer").put(
                "description", "Maximum entries to return. Defaults to 200, maximum $MAX_LIST_ENTRIES.",
            ))
        })
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        resolve(ctx, args.optString("path", "")).absolutePath

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val base = resolve(ctx, args.optString("path", "").ifBlank { "." })
        if (!base.isDirectory) return ToolResult("Error: directory not found: ${base.absolutePath}", isError = true)
        val depth = args.optInt("depth", 1).coerceIn(1, 6)
        val showHidden = args.optBoolean("showHidden", false)
        val limit = args.optInt("limit", 200).coerceIn(1, MAX_LIST_ENTRIES)
        val root = base.toPath()
        val rows = mutableListOf<String>()
        var omitted = 0

        fun walk(dir: File, level: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }),
            ) ?: return
            for (child in children) {
                if (!showHidden && child.name.startsWith(".")) continue
                if (rows.size >= limit) {
                    omitted++
                    continue
                }
                val relative = runCatching { root.relativize(child.toPath()).toString() }.getOrDefault(child.name)
                val kind = when {
                    child.isDirectory -> "d"
                    child.isFile -> "f"
                    else -> "?"
                }
                val size = if (child.isFile) humanBytes(child.length()) else "-"
                rows.add("%s %10s  %s  %s".format(kind, size, formatEpoch(child.lastModified()), relative))
                if (level < depth && child.isDirectory) walk(child, level + 1)
            }
        }

        walk(base, 1)
        if (rows.isEmpty()) return ToolResult("(empty directory) ${base.absolutePath}")
        val suffix = if (omitted > 0) "\n… $omitted more entries not shown (raise limit)" else ""
        return ToolResult("${base.absolutePath}\n${rows.joinToString("\n")}$suffix")
    }
}

/** `file_info`: size, timestamps, type, permissions and an optional real SHA-256. */
class FileInfoTool : OcTool {
    override val name = "file_info"

    override fun description(ctx: ToolContext): String =
        "Returns metadata for one path: type, size, modified time, readable/writable/executable flags, " +
            "canonical path, guessed content type, line count for text, and SHA-256 when sha256=true."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put("description", "File or directory path"))
            put("sha256", JSONObject().put("type", "boolean").put(
                "description", "Hash the file contents. Defaults to false because large files take time.",
            ))
        })
        put("required", JSONArray().put("path"))
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        resolve(ctx, args.optString("path", "")).absolutePath

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val path = args.optString("path", "")
        if (path.isBlank()) return ToolResult("Error: path is required", isError = true)
        val file = resolve(ctx, path)
        if (!file.exists()) return ToolResult("Error: path does not exist: ${file.absolutePath}", isError = true)
        val sb = StringBuilder()
        sb.append("path: ").append(file.absolutePath).append('\n')
        sb.append("canonical: ").append(runCatching { file.canonicalPath }.getOrDefault("?")).append('\n')
        sb.append("type: ").append(
            when {
                file.isDirectory -> "directory"
                file.isFile -> "file"
                else -> "other"
            },
        ).append('\n')
        if (file.isFile) sb.append("size: ").append(file.length()).append(" bytes (").append(humanBytes(file.length())).append(")\n")
        sb.append("modified: ").append(formatEpoch(file.lastModified())).append('\n')
        sb.append("readable: ").append(file.canRead())
            .append("  writable: ").append(file.canWrite())
            .append("  executable: ").append(file.canExecute()).append('\n')
        if (file.isFile) {
            val mime = runCatching { URLConnection.guessContentTypeFromName(file.name) }.getOrNull()
            if (!mime.isNullOrBlank()) sb.append("content type: ").append(mime).append('\n')
            val text = textPreview(file)
            if (text != null) {
                sb.append("text: yes, ").append(text.count { it == '\n' } + if (text.isEmpty()) 0 else 1)
                    .append(" line(s)\n")
            } else {
                sb.append("text: no or larger than ").append(humanBytes(MAX_TEXT_BYTES)).append('\n')
            }
            if (args.optBoolean("sha256", false)) {
                val digest = MessageDigest.getInstance("SHA-256")
                runCatching {
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    sb.append("sha256: ").append(DownloadEngineHex.of(digest.digest())).append('\n')
                }.onFailure { sb.append("sha256: unavailable (").append(it.message).append(")\n") }
            }
        }
        return ToolResult(sb.toString())
    }
}

/** Small local helper so this file does not depend on the download package. */
private object DownloadEngineHex {
    fun of(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }
}

/** `diff`: the same bounded line diff the approval dialog uses. */
class DiffFilesTool : OcTool {
    override val name = "diff"

    override fun description(ctx: ToolContext): String =
        "Compares two text files with a bounded line diff. Returns only changed regions plus a few context " +
            "lines, with + / - markers. Binary files and files over 2 MB are refused."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pathA", JSONObject().put("type", "string").put("description", "Original file path"))
            put("pathB", JSONObject().put("type", "string").put("description", "Changed file path"))
            put("context", JSONObject().put("type", "integer").put(
                "description", "Unchanged lines around each change. Defaults to 3.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description", "Maximum diff lines to return. Defaults to 400.",
            ))
        })
        put("required", JSONArray().put("pathA").put("pathB"))
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        listOf(args.optString("pathA", ""), args.optString("pathB", "")).joinToString(" ")

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val a = requireFile(ctx, args.optString("pathA", ""))
            ?: return ToolResult("Error: pathA not found", isError = true)
        val b = requireFile(ctx, args.optString("pathB", ""))
            ?: return ToolResult("Error: pathB not found", isError = true)
        val before = textPreview(a) ?: return ToolResult("Error: pathA is not a readable text file", isError = true)
        val after = textPreview(b) ?: return ToolResult("Error: pathB is not a readable text file", isError = true)
        val context = args.optInt("context", 3).coerceIn(0, 50)
        val maxLines = args.optInt("maxLines", 400).coerceIn(1, 2_000)
        val lines = AiDiff.preview(before, after, maxLines = maxLines, context = context)
        if (lines.isEmpty()) return ToolResult("No differences between ${a.absolutePath} and ${b.absolutePath}")
        val stats = AiDiff.stats(before, after)
        val body = lines.joinToString("\n") { "${it.kind}${it.text}" }
        return ToolResult(
            "${a.absolutePath} → ${b.absolutePath}\n" +
                "+${stats.added} -${stats.removed}\n\n$body",
        )
    }
}

/** `http_request`: real API calls, not just page fetching. */
class HttpRequestTool : OcTool {
    override val name = "http_request"

    override fun description(ctx: ToolContext): String =
        "Sends an HTTPS request and returns the status, content type, selected headers and body. " +
            "Use it for JSON APIs when webfetch is not enough. POST/PUT/PATCH/DELETE require approval."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().put("type", "string").put("description", "HTTPS URL"))
            put("method", JSONObject().put("type", "string").put(
                "description", "GET (default), HEAD, POST, PUT, PATCH or DELETE.",
            ))
            put("headers", JSONObject().put("type", "object").put(
                "description", "Optional request headers as a JSON object of string values.",
            ))
            put("body", JSONObject().put("type", "string").put(
                "description", "Optional request body. For JSON, also set Content-Type: application/json.",
            ))
            put("timeout", JSONObject().put("type", "integer").put(
                "description", "Timeout in seconds, 1..120. Defaults to 30.",
            ))
        })
        put("required", JSONArray().put("url"))
    }

    override fun permissionKey(): String = "http_request"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = args.optString("url", "")

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint {
        val method = args.optString("method", "GET").trim().uppercase()
        return if (method == "GET" || method == "HEAD") RiskHint.READ_ONLY else RiskHint.MODIFY
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isBlank()) return ToolResult("Error: url is required", isError = true)
        if (!rawUrl.startsWith("https://")) {
            return ToolResult("Error: only fully-formed https:// URLs are allowed", isError = true)
        }
        val method = args.optString("method", "GET").trim().uppercase()
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) {
            return ToolResult("Error: unsupported method: $method", isError = true)
        }
        val body = args.optString("body", "")
        if ((method == "GET" || method == "HEAD") && body.isNotBlank()) {
            return ToolResult("Error: $method cannot send a body", isError = true)
        }
        val timeoutMs = (args.optInt("timeout", 30).coerceIn(1, 120) * 1_000)
        val headers = args.optJSONObject("headers")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MobileStudio/1.0 (Android; AI http_request)")
                setRequestProperty("Accept", "*/*")
            }
            if (headers != null) {
                val keys = headers.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = headers.optString(key, "")
                    if (key.isNotBlank() && value.isNotBlank()) conn.setRequestProperty(key, value)
                }
            }
            if (body.isNotBlank()) {
                conn.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val finalUrl = conn.url?.toString().orEmpty()
            if (finalUrl.isNotBlank() && !finalUrl.startsWith("https://")) {
                return ToolResult("Error: the request redirected away from HTTPS: $finalUrl", isError = true)
            }
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val sb = StringBuilder()
                val buffer = CharArray(8_192)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    sb.append(buffer, 0, read)
                    total += read
                    if (total >= MAX_HTTP_CHARS) break
                }
                sb.toString()
            }.orEmpty()
            val selectedHeaders = listOf("Content-Type", "Content-Length", "Location", "ETag", "Cache-Control")
                .mapNotNull { name -> conn.getHeaderField(name)?.let { "$name: $it" } }
            val headerText = if (selectedHeaders.isEmpty()) "" else selectedHeaders.joinToString("\n") + "\n"
            val truncated = text.length >= MAX_HTTP_CHARS
            ToolResult(
                buildString {
                    append("HTTP ").append(status).append(' ').append(method).append(' ').append(finalUrl).append('\n')
                    append(headerText)
                    append('\n')
                    append(text.ifBlank { "(empty body)" })
                    if (truncated) append("\n\n… body truncated at ").append(MAX_HTTP_CHARS).append(" characters")
                },
            )
        } catch (e: Exception) {
            ToolResult("Error: request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        } finally {
            conn?.disconnect()
        }
    }
}

/** Read-only git queries. Writes still belong to `bash` and its approval path. */
class GitTool : OcTool {
    override val name = "git"

    override fun description(ctx: ToolContext): String =
        "Runs a read-only git command in the workspace: status, diff, log, show, branch, rev-parse, " +
            "remote, tag, blame, ls-files, grep or stash list. Use bash for commit/checkout/push."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().put("type", "string").put(
                "description", "One of: status, diff, log, show, branch, rev-parse, remote, tag, blame, ls-files, grep, stash.",
            ))
            put("args", JSONObject().put("type", "string").put(
                "description", "Extra arguments, parsed with quotes. Example: --stat HEAD~1.",
            ))
            put("path", JSONObject().put("type", "string").put(
                "description", "Optional file path, appended after -- for diff/show/blame.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description", "Output lines to return. Defaults to $DEFAULT_TOOL_OUTPUT_LINES.",
            ))
        })
        put("required", JSONArray().put("command"))
    }

    override fun permissionKey(): String = "git"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = ctx.worktree.absolutePath

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.READ_ONLY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = args.optString("command", "status").trim().lowercase()
        val allowed = setOf(
            "status", "diff", "log", "show", "branch", "rev-parse", "remote",
            "tag", "blame", "ls-files", "grep", "stash",
        )
        if (command !in allowed) {
            return ToolResult(
                "Error: '$command' is not a read-only git command. Allowed: ${allowed.sorted().joinToString(", ")}",
                isError = true,
            )
        }
        val argv = mutableListOf("git", command)
        val extra = args.optString("args", "").trim()
        val parsed = if (extra.isNotEmpty()) {
            runCatching { ShellRunner.parseArgsForExecution(extra) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (!gitArgumentsAreReadOnly(command, parsed)) {
            return ToolResult(
                "Error: those arguments can modify the repository or write files; git is read-only. Use bash for that.",
                isError = true,
            )
        }
        argv.addAll(parsed)
        if (command == "log" && argv.none { it == "-n" || it.startsWith("--max-count") }) {
            argv.addAll(listOf("--oneline", "-n", "30"))
        }
        if (command == "stash" && argv.size == 2) argv.add("list")
        val pathArg = args.optString("path", "").trim()
        if (pathArg.isNotEmpty()) {
            val root = ctx.worktree.canonicalFile
            val path = resolve(ctx, pathArg)
            if (path != root && !path.toPath().startsWith(root.toPath())) {
                return ToolResult("Error: path is outside the workspace: $pathArg", isError = true)
            }
            val relative = runCatching { path.relativeTo(root).path }.getOrNull()
                ?: return ToolResult("Error: path is outside the workspace: $pathArg", isError = true)
            if (command in setOf("diff", "show", "blame", "ls-files", "grep")) {
                argv.add("--")
                argv.add(relative)
            }
        }
        val commandLine = argv.joinToString(" ") { shellQuote(it) }
        val lines = mutableListOf<String>()
        val result = ShellRunner.runSh(
            ctx.androidContext,
            commandLine,
            ctx.worktree,
            shellEnv(ctx, ctx.worktree),
            60_000,
        ) { lines.add(it) }
        val maxLines = args.optInt("maxLines", DEFAULT_TOOL_OUTPUT_LINES).coerceIn(1, 2_000)
        val (body, truncated) = shapeOutput(lines, maxLines)
        val exit = result?.exitCode ?: -1
        val header = "git $command (exit $exit)" + if (truncated) " — head and tail shown" else ""
        if (exit != 0 && body.isBlank()) {
            return ToolResult("$header\n(git is unavailable or the workspace is not a repository)", isError = true)
        }
        return ToolResult("$header\n$body", isError = exit != 0)
    }
}

/** Read-only SQLite access for inspecting app/project databases. */
class SqliteQueryTool : OcTool {
    override val name = "sqlite_query"

    override fun description(ctx: ToolContext): String =
        "Runs one read-only SQL query against a SQLite database and returns columns and rows. " +
            "Only SELECT, WITH, PRAGMA and EXPLAIN are accepted; writes and multiple statements are refused."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put("description", "SQLite database file path"))
            put("sql", JSONObject().put("type", "string").put("description", "One read-only SQL statement"))
            put("limit", JSONObject().put("type", "integer").put(
                "description", "Rows to return. Defaults to 100, maximum $MAX_SQL_ROWS.",
            ))
        })
        put("required", JSONArray().put("path").put("sql"))
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        resolve(ctx, args.optString("path", "")).absolutePath

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val file = requireFile(ctx, args.optString("path", ""))
            ?: return ToolResult("Error: database file not found", isError = true)
        val sql = args.optString("sql", "").trim()
        if (sql.isEmpty()) return ToolResult("Error: sql is required", isError = true)
        val withoutTrailing = sql.trimEnd().removeSuffix(";").trimEnd()
        if (withoutTrailing.contains(';')) {
            return ToolResult("Error: only one SQL statement is allowed", isError = true)
        }
        val keyword = withoutTrailing.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
        if (keyword !in setOf("SELECT", "WITH", "PRAGMA", "EXPLAIN")) {
            return ToolResult("Error: only read-only SQL is allowed (SELECT, WITH, PRAGMA, EXPLAIN)", isError = true)
        }
        val limit = args.optInt("limit", 100).coerceIn(1, MAX_SQL_ROWS)
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(withoutTrailing, null)
            val sb = StringBuilder()
            val columns = runCatching { cursor.columnNames.toList() }.getOrDefault(emptyList())
            sb.append("database: ").append(file.absolutePath).append('\n')
            sb.append("columns: ").append(columns.joinToString(", ")).append('\n')
            var rows = 0
            while (rows < limit && cursor.moveToNext()) {
                sb.append(rows + 1).append(": ")
                for (i in columns.indices) {
                    if (i > 0) sb.append(" | ")
                    sb.append(columns[i]).append('=')
                    val value: Any? = when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        // Do not pull a multi-megabyte blob into a text tool
                        // result. The size is not known until it is read; the
                        // marker is honest and keeps memory bounded.
                        android.database.Cursor.FIELD_TYPE_BLOB -> "<blob>"
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        else -> cursor.getString(i)
                    }
                    sb.append(valueSuffix(value))
                }
                sb.append('\n')
                rows++
            }
            if (rows == 0) sb.append("(no rows)\n")
            if (rows >= limit) sb.append("… row limit reached ($limit)\n")
            cursor.close()
            ToolResult(sb.toString())
        } catch (e: Exception) {
            ToolResult("Error: SQLite query failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        } finally {
            runCatching { db?.close() }
        }
    }
}

/** `zip_list`: inspect an archive without extracting it. */
class ZipListTool : OcTool {
    override val name = "zip_list"

    override fun description(ctx: ToolContext): String =
        "Lists entries in a ZIP archive with uncompressed and compressed sizes. Does not write anything."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("archive", JSONObject().put("type", "string").put("description", "Path to the .zip file"))
            put("limit", JSONObject().put("type", "integer").put(
                "description", "Maximum entries to list. Defaults to 200.",
            ))
        })
        put("required", JSONArray().put("archive"))
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        resolve(ctx, args.optString("archive", "")).absolutePath

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val file = requireFile(ctx, args.optString("archive", ""))
            ?: return ToolResult("Error: archive not found", isError = true)
        val limit = args.optInt("limit", 200).coerceIn(1, 2_000)
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().take(limit).toList()
                if (entries.isEmpty()) return ToolResult("(empty archive) ${file.absolutePath}")
                val lines = entries.map { entry ->
                    "%10d %10d  %s%s".format(
                        entry.size,
                        entry.compressedSize,
                        entry.name,
                        if (entry.isDirectory) "/" else "",
                    )
                }
                val suffix = if (zip.size() > limit) "\n… ${zip.size() - limit} more entries" else ""
                ToolResult("${file.absolutePath}\nsize  compressed  name\n${lines.joinToString("\n")}$suffix")
            }
        } catch (e: Exception) {
            ToolResult("Error: cannot read zip: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }
}

/** `zip`: extract or create a ZIP. Extraction is guarded against path traversal. */
class ZipTool : OcTool {
    override val name = "zip"

    override fun description(ctx: ToolContext): String =
        "Extracts a ZIP into a directory or creates a ZIP from a file/directory. " +
            "Extraction refuses entries that escape the destination directory."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().put("type", "string").put(
                "description", "extract or create.",
            ))
            put("archive", JSONObject().put("type", "string").put("description", "Path to the .zip file"))
            put("path", JSONObject().put("type", "string").put(
                "description", "For extract: destination directory. For create: source file or directory.",
            ))
            put("overwrite", JSONObject().put("type", "boolean").put(
                "description", "For extract: replace existing files. Defaults to false.",
            ))
        })
        put("required", JSONArray().put("action").put("archive"))
    }

    override fun permissionKey(): String = "edit"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        resolve(ctx, args.optString("archive", "")).absolutePath

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString("action", "").trim().lowercase()
        val archive = resolve(ctx, args.optString("archive", ""))
        val path = args.optString("path", "").trim()
        return when (action) {
            "extract" -> extract(ctx, archive, path, args.optBoolean("overwrite", false))
            "create" -> create(ctx, archive, path)
            else -> ToolResult("Error: action must be extract or create", isError = true)
        }
    }

    private fun extract(ctx: ToolContext, archive: File, path: String, overwrite: Boolean): ToolResult {
        if (!archive.isFile) return ToolResult("Error: archive not found: ${archive.absolutePath}", isError = true)
        val destination = resolve(ctx, path.ifBlank { archive.parentFile?.absolutePath ?: "." })
        destination.mkdirs()
        if (!destination.isDirectory) return ToolResult("Error: destination is not a directory", isError = true)
        val root = destination.canonicalFile
        var files = 0
        var bytes = 0L
        var skipped = 0
        return try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    if (files >= MAX_ZIP_ENTRIES || bytes > MAX_ZIP_BYTES) {
                        return ToolResult(
                            "Error: archive exceeds the safety limit ($MAX_ZIP_ENTRIES entries / 2 GB)",
                            isError = true,
                        )
                    }
                    val entry = entries.nextElement()
                    val target = File(destination, entry.name).canonicalFile
                    if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) {
                        return ToolResult("Error: archive entry escapes destination: ${entry.name}", isError = true)
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    if (target.exists() && !overwrite) {
                        skipped++
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                bytes += read
                            }
                        }
                    }
                    files++
                }
            }
            ToolResult(
                "extracted $files file(s), $skipped existing file(s) skipped, " +
                    "${humanBytes(bytes)} → ${destination.absolutePath}",
            )
        } catch (e: Exception) {
            ToolResult("Error: extract failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun create(ctx: ToolContext, archive: File, path: String): ToolResult {
        val source = resolve(ctx, path.ifBlank { "." })
        if (!source.exists()) return ToolResult("Error: source not found: ${source.absolutePath}", isError = true)
        archive.parentFile?.mkdirs()
        var files = 0
        var bytes = 0L
        return try {
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                val base = if (source.isDirectory) source else source.parentFile ?: source
                val root = base.canonicalFile
                val candidates = if (source.isFile) listOf(source) else source.walkTopDown().filter { it.isFile }.toList()
                for (file in candidates) {
                    if (files >= MAX_ZIP_ENTRIES || bytes > MAX_ZIP_BYTES) {
                        return ToolResult("Error: source exceeds the safety limit", isError = true)
                    }
                    if (file.canonicalFile == archive.canonicalFile) continue
                    val relative = runCatching { root.toPath().relativize(file.canonicalFile.toPath()).toString() }
                        .getOrElse { file.name }
                    zip.putNextEntry(java.util.zip.ZipEntry(relative.replace('\\', '/')))
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                            bytes += read
                        }
                    }
                    zip.closeEntry()
                    files++
                }
            }
            ToolResult("created ${archive.absolutePath} with $files file(s), ${humanBytes(bytes)}")
        } catch (e: Exception) {
            ToolResult("Error: create failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }
}

/** `network_info`: transports, addresses and DNS for the active network. */
class NetworkInfoTool : OcTool {
    override val name = "network_info"

    override fun description(ctx: ToolContext): String =
        "Reports the active network transport (Wi-Fi/cellular/VPN), metered/validated state, interface, " +
            "IP addresses, DNS servers, proxy and route count. Read-only."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override fun permissionKey(): String = "read"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val sb = StringBuilder()
        val cm = runCatching {
            ctx.androidContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
        if (cm == null) return ToolResult("ConnectivityManager is unavailable")
        runCatching {
            val network = cm.activeNetwork
            if (network == null) {
                sb.append("active network: none\n")
            } else {
                val caps = cm.getNetworkCapabilities(network)
                val transports = buildList {
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
                }
                sb.append("active network: ").append(transports.ifEmpty { listOf("other") }.joinToString(", ")).append('\n')
                caps?.let {
                    sb.append("validated: ").append(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .append("  metered: ").append(!it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                        .append('\n')
                    sb.append("bandwidth: down ").append(it.linkDownstreamBandwidthKbps).append(" kbps, up ")
                        .append(it.linkUpstreamBandwidthKbps).append(" kbps\n")
                }
                val lp = cm.getLinkProperties(network)
                if (lp != null) {
                    sb.append("interface: ").append(lp.interfaceName).append('\n')
                    if (lp.linkAddresses.isNotEmpty()) {
                        sb.append("addresses: ").append(lp.linkAddresses.joinToString(", ") { it.address.hostAddress ?: "?" }).append('\n')
                    }
                    val dns = lp.dnsServers.mapNotNull { it.hostAddress }
                    if (dns.isNotEmpty()) sb.append("dns: ").append(dns.joinToString(", ")).append('\n')
                    sb.append("routes: ").append(lp.routes.size).append('\n')
                    lp.httpProxy?.let { proxy ->
                        // Never copy proxy credentials into a model prompt.
                        sb.append("proxy: ").append(proxy.host).append(':').append(proxy.port).append('\n')
                    }
                }
            }
        }.onFailure { sb.append("network state: unavailable (").append(it.message).append(")\n") }
        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
            for (iface in interfaces) {
                val addresses = iface.inetAddresses.toList().mapNotNull { it.hostAddress }
                if (addresses.isNotEmpty()) {
                    sb.append("interface ").append(iface.name).append(": ")
                        .append(addresses.joinToString(", ")).append('\n')
                }
            }
        }
        return ToolResult(sb.toString().ifBlank { "No network information available" })
    }
}

/** `current_time`: the real clock, with an explicit zone. */
class CurrentTimeTool : OcTool {
    override val name = "current_time"

    override fun description(ctx: ToolContext): String =
        "Returns the current date and time in ISO form, the local time, epoch milliseconds and time zone. " +
            "Use it instead of guessing today's date."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("timezone", JSONObject().put("type", "string").put(
                "description", "Optional IANA zone such as Asia/Seoul. Defaults to the device zone.",
            ))
        })
    }

    override fun permissionKey(): String = "read"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val requested = args.optString("timezone", "").trim()
        val zone = if (requested.isEmpty()) ZoneId.systemDefault() else runCatching { ZoneId.of(requested) }
            .getOrElse { return ToolResult("Error: unknown time zone: $requested", isError = true) }
        val now = ZonedDateTime.now(zone)
        return ToolResult(
            buildString {
                append("iso: ").append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
                append("local: ").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE"))).append('\n')
                append("zone: ").append(zone.id).append(" (offset ").append(now.offset).append(")\n")
                append("epoch_ms: ").append(now.toInstant().toEpochMilli()).append('\n')
            },
        )
    }
}

/** `app_info`: the installed app's version and SDK facts, or a bounded package list. */
class AppInfoTool : OcTool {
    override val name = "app_info"

    override fun description(ctx: ToolContext): String =
        "Reports an installed app's label, package, version, target/min SDK, UID, debug flag and permissions. " +
            "With list=true it returns a bounded list of installed packages."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("packageName", JSONObject().put("type", "string").put(
                "description", "Package to inspect. Defaults to this app.",
            ))
            put("list", JSONObject().put("type", "boolean").put(
                "description", "List installed packages instead of inspecting one. Defaults to false.",
            ))
            put("limit", JSONObject().put("type", "integer").put(
                "description", "Maximum packages when list=true. Defaults to 100.",
            ))
        })
    }

    override fun permissionKey(): String = "read"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pm = ctx.androidContext.packageManager
        if (args.optBoolean("list", false)) {
            val limit = args.optInt("limit", 100).coerceIn(1, 500)
            val packages = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0)
                }
            }.getOrElse { return ToolResult("Error: cannot list packages: ${it.message}", isError = true) }
            val sorted = packages.sortedBy { it.packageName }
            val sb = StringBuilder("installed packages: ${packages.size}\n")
            for (pkgInfo in sorted.take(limit)) {
                val label = runCatching { pm.getApplicationLabel(pkgInfo.applicationInfo!!).toString() }.getOrDefault("")
                val version = pkgInfo.versionName ?: "?"
                sb.append(pkgInfo.packageName).append("  ").append(label).append("  ").append(version)
                    .append(if ((pkgInfo.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) "  [system]" else "")
                    .append('\n')
            }
            if (packages.size > limit) sb.append("… ${packages.size - limit} more\n")
            return ToolResult(sb.toString())
        }

        val packageName = args.optString("packageName", "").trim().ifBlank { ctx.androidContext.packageName }
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrElse { return ToolResult("Error: package not found: $packageName", isError = true) }
        val app = info.applicationInfo!!
        return ToolResult(
            buildString {
                append("package: ").append(info.packageName).append('\n')
                append("label: ").append(runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("?")).append('\n')
                append("version: ").append(info.versionName ?: "?").append(" (").append(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
                ).append(")\n")
                append("target sdk: ").append(app.targetSdkVersion)
                    .append("  min sdk: ").append(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.minSdkVersion else 0,
                    ).append('\n')
                append("uid: ").append(app.uid).append('\n')
                append("system: ").append((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                    .append("  debuggable: ").append((app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0).append('\n')
                append("enabled: ").append(app.enabled).append('\n')
                val requested = info.requestedPermissions?.toList().orEmpty()
                if (requested.isNotEmpty()) {
                    append("permissions (").append(requested.size).append("):\n")
                    for (permission in requested.take(40)) append("  ").append(permission).append('\n')
                    if (requested.size > 40) append("  … ").append(requested.size - 40).append(" more\n")
                }
            },
        )
    }
}

/**
 * True when [args] cannot make a `git` subcommand write to the repository or
 * filesystem. The tool advertises read-only access, so the check is a real
 * boundary, not a description: `git branch -D`, `git tag -d`, `git remote
 * remove`, `git stash drop` and `--output` are all refused.
 */
internal fun gitArgumentsAreReadOnly(command: String, args: List<String>): Boolean {
    val lower = args.map { it.lowercase() }
    fun any(vararg values: String): Boolean = lower.any { it in values }
    fun anyPrefix(vararg values: String): Boolean = lower.any { arg -> values.any { arg.startsWith(it) } }

    // An allowlist, not a denylist: git has many commands that write objects,
    // refs or worktree files, and a new one must not become "read-only" by
    // accident.
    val safeCommands = setOf(
        "status", "diff", "log", "show", "branch", "tag", "remote", "rev-parse",
        "ls-files", "grep", "blame", "describe", "merge-base", "count-objects",
        "for-each-ref", "cat-file", "show-ref", "shortlog", "whatchanged", "stash",
        "worktree",
    )
    if (command !in safeCommands) return false

    if (anyPrefix("--output=", "--ext-diff", "--textconv", "--open-files-in-pager")) return false
    if (any("-o")) return false
    return when (command) {
        "branch" -> !any(
            "-d", "-D", "-m", "-M", "-c", "-C", "-f", "-t", "-u",
            "--delete", "--move", "--copy", "--edit-description", "--set-upstream-to",
            "--unset-upstream", "--track", "--force",
        ) && !anyPrefix("--set-upstream-to=")
        "tag" -> !any(
            "-d", "-a", "-s", "-f", "-m", "-F",
            "--delete", "--annotate", "--sign", "--force", "--message", "--file",
        )
        "remote" -> lower.none {
            it == "add" || it == "remove" || it == "rm" || it == "rename" ||
                it == "set-url" || it == "set-head" || it == "prune" || it == "update"
        }
        "stash" -> lower.firstOrNull() in setOf(null, "list", "show")
        "worktree" -> lower.firstOrNull() in setOf("list", "--porcelain")
        "diff", "show", "log" -> !any("--output") && !anyPrefix("--output=")
        else -> true
    }
}

/** Quotes one argv element for `/system/bin/sh -c`. */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
