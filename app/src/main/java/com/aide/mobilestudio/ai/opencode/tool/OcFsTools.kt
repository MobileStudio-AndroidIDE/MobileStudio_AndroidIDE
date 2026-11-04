package com.aide.mobilestudio.ai.opencode.tool

import android.content.Context
import com.aide.mobilestudio.ShellEnvironment
import com.aide.mobilestudio.ShellRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object OcAssets {
    private val cache = mutableMapOf<String, String>()

    fun read(context: Context, path: String): String? {
        cache[path]?.let { return it }
        return runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()?.also { cache[path] = it }
    }
}

class BashTool : OcTool {
    override val name = "bash"

    override fun description(ctx: ToolContext): String {
        val base = OcAssets.read(ctx.androidContext, "ai/prompt/tool/shell.txt")
        if (base != null) {
            return base
                .replace("\${intro}", "Executes a given shell command and returns its output.")
                .replace("\${os}", "android")
                .replace("\${shell}", ctx.config.shell ?: "/bin/sh")
                .replace(
                    "\${workdirSection}",
                    "All commands run in the current working directory by default. Use the `workdir` parameter if you need to run a command in a different directory.",
                )
                .replace("\${tmp}", ctx.tmpDir.absolutePath)
                .replace(
                    "\${commandSection}",
                    "Timeout: commands may time out after a configured limit (default 120000ms).",
                )
        }
        return "Executes a shell command and returns its output. Default workdir is the workspace root. Use the workdir parameter for other directories."
    }

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().put("type", "string").put("description", "Shell command string to execute"))
            put("workdir", JSONObject().put("type", "string").put(
                "description",
                "Working directory. Defaults to the active workspace; relative paths resolve from the workspace.",
            ))
            put("timeout", JSONObject().put("type", "integer").put(
                "description",
                "Timeout in milliseconds. Defaults to ${DEFAULT_TIMEOUT_MS} and may not exceed ${MAX_TIMEOUT_MS}.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description",
                "Output lines to return. Defaults to $DEFAULT_OUTPUT_LINES. When the command printed more, " +
                    "the middle is omitted (first and last lines are kept) - ask for a higher value, up to " +
                    "the configured maximum, when you need the whole log.",
            ))
        })
        put("required", JSONArray().put("command"))
    }

    override fun permissionKey(): String = "bash"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = args.optString("command", "")

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint {
        val assessment = com.aide.mobilestudio.ai.shell.CommandRiskClassifier.classify(args.optString("command", ""))
        return when (assessment.risk) {
            com.aide.mobilestudio.ai.shell.CommandRisk.READ_ONLY -> RiskHint.READ_ONLY
            com.aide.mobilestudio.ai.shell.CommandRisk.MUTATING -> RiskHint.MODIFY
            com.aide.mobilestudio.ai.shell.CommandRisk.INSTALL -> RiskHint.MODIFY
            com.aide.mobilestudio.ai.shell.CommandRisk.DESTRUCTIVE -> RiskHint.DANGEROUS
            com.aide.mobilestudio.ai.shell.CommandRisk.SYSTEM -> RiskHint.DANGEROUS
        }
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val command = args.optString("command", "")
        if (command.isBlank()) return ToolResult("Error: command is required", isError = true)
        val workdir = args.optString("workdir", "")
        val cwd = if (workdir.isNotBlank()) ctx.resolveInWorkspace(workdir).apply { mkdirs() } else ctx.worktree
        val timeout = args.optLong("timeout", DEFAULT_TIMEOUT_MS).coerceIn(1L, MAX_TIMEOUT_MS)
        val outputLines = mutableListOf<String>()
        // Full toolchain environment: without it the shell inherits the bare
        // app PATH and cannot find java/gradle/cmake/python/lua at all.
        val env = ShellEnvironment.buildToolEnvironment(ctx.androidContext, cwd)
        val result = ShellRunner.runSh(ctx.androidContext, command, cwd, env, timeout) { line ->
            outputLines.add(line)
            // Stream live: the engine wired ctx.onOutputLine before execute.
            ctx.onOutputLine?.invoke(line)
        }
        val joined = outputLines.joinToString("\n")
        // 50 lines by default, not the 2000 the config allows: a build log that
        // long used to be handed to the model and the UI whole, which is what
        // made a single command freeze the workspace. The model can ask for
        // more; the config value stays the ceiling.
        val maxLines = args.optInt("maxLines", DEFAULT_OUTPUT_LINES)
            .coerceIn(1, ctx.config.maxToolOutputLines())
        val maxBytes = ctx.config.maxToolOutputBytes()
        var truncated = false
        var output: String
        if (outputLines.size > maxLines) {
            // Head and tail: the opening lines say what ran, the closing lines
            // carry the error. Keeping only the head hid the interesting half
            // of every long build.
            val head = maxLines / 2
            val tail = maxLines - head
            val omitted = outputLines.size - maxLines
            output = (
                outputLines.take(head) +
                    "… ($omitted lines omitted - rerun with maxLines=$maxLines or higher to see more) …" +
                    outputLines.takeLast(tail)
                ).joinToString("\n")
            truncated = true
        } else {
            output = joined
        }
        if (output.toByteArray().size > maxBytes) {
            output = String(output.toByteArray(), 0, maxBytes, Charsets.UTF_8)
                // A multi-byte character cut in half decodes to U+FFFD; drop it
                // rather than leave a replacement mark in the middle of a word.
                .trimEnd('\uFFFD')
            truncated = true
        }
        if (output.isBlank()) output = "(no output)"
        if (truncated) output += "\n\n(Output truncated. Full output was ${joined.length} characters.)"
        val exit = result?.exitCode ?: -1
        if (result?.timedOut == true) {
            return ToolResult("$output\n\nCommand timed out before completion.")
        }
        return ToolResult("$output\n\nCommand exited with code $exit.")
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2L * 60 * 1_000
        const val MAX_TIMEOUT_MS = 10L * 60 * 1_000

        /** Lines returned when the model does not ask for a specific count. */
        const val DEFAULT_OUTPUT_LINES = 50
    }
}

class ReadTool : OcTool {
    override val name = "read"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/read.txt")
            ?: "Read a file or directory from the local filesystem. Returns up to 2000 lines with each line prefixed by its line number."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filePath", JSONObject().put("type", "string").put("description", "The absolute path to the file or directory to read"))
            put("offset", JSONObject().put("type", "integer").put("description", "The 1-based directory entry or text line offset to start reading from"))
            put("limit", JSONObject().put("type", "integer").put("description", "The maximum number of directory entries or text lines to read"))
        })
        put("required", JSONArray().put("filePath"))
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        ctx.resolveInWorkspace(args.optString("filePath", "")).absolutePath

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val path = args.optString("filePath", "")
        if (path.isBlank()) return ToolResult("Error: filePath is required", isError = true)
        val file = ctx.resolveInWorkspace(path)
        if (!file.exists()) return ToolResult("Error: path does not exist: $path", isError = true)
        ctx.bridge.markFileRead(file.absolutePath)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 2000).coerceAtLeast(1)
        if (file.isDirectory) {
            val entries = file.listFiles()?.sortedBy { it.name } ?: emptyList()
            val selected = entries.drop(offset - 1).take(limit)
            val lines = selected.map { if (it.isDirectory) "${it.name}/" else it.name }
            return ToolResult(lines.joinToString("\n").ifEmpty { "(empty directory)" })
        }
        val imageMimes = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp",
        )
        val ext = file.extension.lowercase()
        if (imageMimes.containsKey(ext)) {
            return ToolResult(
                "Image file: ${file.absolutePath} (${file.length()} bytes, mime ${imageMimes[ext]}). " +
                    "The image cannot be displayed in tool output on this device; ask the user to view it in the editor.",
            )
        }
        if (ext == "pdf") {
            return ToolResult(
                "PDF file: ${file.absolutePath} (${file.length()} bytes). PDF content cannot be extracted in tool output on this device.",
            )
        }
        val text = runCatching { file.readText() }.getOrNull()
            ?: return ToolResult("Error: unable to read file (binary?): $path", isError = true)
        val lines = text.split("\n")
        val selected = lines.drop(offset - 1).take(limit)
        val sb = StringBuilder()
        var lineNo = offset
        for (line in selected) {
            val clipped = if (line.length > 2000) line.substring(0, 2000) else line
            sb.append("$lineNo: $clipped\n")
            lineNo++
        }
        return ToolResult(sb.toString())
    }
}

class WriteTool : OcTool {
    override val name = "write"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/write.txt")
            ?: "Writes a file to the local filesystem, overwriting the existing file if there is one at the provided path."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filePath", JSONObject().put("type", "string").put("description", "The absolute path to the file to write"))
            put("content", JSONObject().put("type", "string").put("description", "The content to write to the file"))
        })
        put("required", JSONArray().put("filePath").put("content"))
    }

    override fun permissionKey(): String = "edit"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        ctx.resolveInWorkspace(args.optString("filePath", "")).absolutePath

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val path = args.optString("filePath", "")
        val content = args.optString("content", "")
        if (path.isBlank()) return ToolResult("Error: filePath is required", isError = true)
        val file = ctx.resolveInWorkspace(path)
        if (file.isFile && !ctx.bridge.hasReadFile(file.absolutePath)) {
            return ToolResult(
                "Error: This is an existing file, you MUST use the read tool first to read the file's contents before writing.",
                isError = true,
            )
        }
        ctx.bridge.snapshotFile(ctx.bridge.currentMessageId(), file)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult("File written: ${file.absolutePath}" + validateStructuredFile(file))
        }.getOrElse { ToolResult("Error writing file: ${it.message}", isError = true) }
    }
}

class EditTool : OcTool {
    override val name = "edit"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/edit.txt")
            ?: "Performs exact string replacements in files. The edit will FAIL if oldString is not found or found multiple times."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("filePath", JSONObject().put("type", "string").put("description", "The absolute path to the file to modify"))
            put("oldString", JSONObject().put("type", "string").put("description", "The text to replace"))
            put("newString", JSONObject().put("type", "string").put("description", "The text to replace it with (must be different from oldString)"))
            put("replaceAll", JSONObject().put("type", "boolean").put("description", "Replace all occurrences of oldString (default false)"))
        })
        put("required", JSONArray().put("filePath").put("oldString").put("newString"))
    }

    override fun permissionKey(): String = "edit"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        ctx.resolveInWorkspace(args.optString("filePath", "")).absolutePath

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val path = args.optString("filePath", "")
        val oldString = args.optString("oldString", "")
        val newString = args.optString("newString", "")
        val replaceAll = args.optBoolean("replaceAll", false)
        if (path.isBlank()) return ToolResult("Error: filePath is required", isError = true)
        if (oldString == newString) return ToolResult("Error: newString must differ from oldString", isError = true)
        val file = ctx.resolveInWorkspace(path)
        if (!file.isFile) return ToolResult("Error: File not found: $path", isError = true)
        if (!ctx.bridge.hasReadFile(file.absolutePath)) {
            return ToolResult(
                "Error: You must use the read tool at least once in the conversation before editing this file.",
                isError = true,
            )
        }
        val content = runCatching { file.readText() }.getOrNull()
            ?: return ToolResult("Error: unable to read file: $path", isError = true)
        val count = countOccurrences(content, oldString)
        if (count == 0) return ToolResult("Error: oldString not found in content", isError = true)
        if (count > 1 && !replaceAll) {
            return ToolResult(
                "Error: Found multiple matches for oldString. Provide more surrounding lines in oldString to identify the correct match, or use replaceAll.",
                isError = true,
            )
        }
        ctx.bridge.snapshotFile(ctx.bridge.currentMessageId(), file)
        val updated = if (replaceAll) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
        return runCatching {
            file.writeText(updated)
            ToolResult(
                "File edited: ${file.absolutePath} ($count occurrence${if (count > 1) "s" else ""} replaced)" +
                    validateStructuredFile(file),
            )
        }.getOrElse { ToolResult("Error writing file: ${it.message}", isError = true) }
    }

    private fun countOccurrences(content: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = content.indexOf(needle, index)
            if (index < 0) break
            count++
            index += needle.length
        }
        return count
    }
}

class GlobTool : OcTool {
    override val name = "glob"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/glob.txt")
            ?: "Fast file pattern matching tool. Supports glob patterns like \"**/*.js\". Returns matching file paths."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().put("type", "string").put("description", "The glob pattern to match files against"))
            put("path", JSONObject().put("type", "string").put("description", "The directory to search in. Omit to use the workspace root."))
            put("limit", JSONObject().put("type", "integer").put("description", "Maximum results to return (default 100)"))
        })
        put("required", JSONArray().put("pattern"))
    }

    override fun permissionKey(): String = "glob"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pattern = args.optString("pattern", "")
        if (pattern.isBlank()) return ToolResult("Error: pattern is required", isError = true)
        val pathArg = args.optString("path", "")
        val base = if (pathArg.isBlank()) ctx.worktree else ctx.resolveInWorkspace(pathArg)
        if (!base.isDirectory) return ToolResult("Error: directory not found: $pathArg", isError = true)
        val limit = args.optInt("limit", 100).coerceAtLeast(1)
        val results = mutableListOf<String>()
        base.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeToOrNull(base)?.path ?: return@forEach
            if (relative.contains("/.git/") || relative.startsWith(".git/")) return@forEach
            if (OcGlob.match(relative, pattern)) {
                results.add(file.absolutePath)
            }
        }
        if (results.isEmpty()) return ToolResult("No files found")
        return ToolResult(results.take(limit).joinToString("\n"))
    }
}

class GrepTool : OcTool {
    override val name = "grep"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/grep.txt")
            ?: "Fast content search tool. Searches file contents using regular expressions. Returns file paths and line numbers with matching lines."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().put("type", "string").put("description", "The regex pattern to search for in file contents"))
            put("path", JSONObject().put("type", "string").put("description", "The directory to search in. Defaults to the workspace root."))
            put("include", JSONObject().put("type", "string").put("description", "File glob to include in the search (e.g. \"*.js\" or \"*.{ts,tsx}\")"))
            put("limit", JSONObject().put("type", "integer").put("description", "Maximum matches to return (default 200)"))
        })
        put("required", JSONArray().put("pattern"))
    }

    override fun permissionKey(): String = "grep"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pattern = args.optString("pattern", "")
        if (pattern.isBlank()) return ToolResult("Error: pattern is required", isError = true)
        val regex = runCatching { Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL)) }.getOrNull()
            ?: return ToolResult("Error: invalid regex pattern: $pattern", isError = true)
        val pathArg = args.optString("path", "")
        val base = if (pathArg.isBlank()) ctx.worktree else ctx.resolveInWorkspace(pathArg)
        if (!base.isDirectory) return ToolResult("Error: directory not found: $pathArg", isError = true)
        val include = args.optString("include", "")
        val includeGlobs = if (include.isBlank()) emptyList() else OcGlob.expand(include)
        val limit = args.optInt("limit", 200).coerceAtLeast(1)
        val results = mutableListOf<String>()
        base.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeToOrNull(base)?.path ?: return@forEach
            if (relative.contains("/.git/") || relative.startsWith(".git/") ||
                relative.contains("/.gradle/") || relative.contains("/build/")
            ) return@forEach
            if (includeGlobs.isNotEmpty() && includeGlobs.none { OcGlob.match(relative, it) }) return@forEach
            if (file.length() > 4L * 1024 * 1024) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            var lineNo = 1
            for (line in text.split("\n")) {
                if (regex.containsMatchIn(line)) {
                    results.add("${file.absolutePath}:$lineNo: ${line.take(500)}")
                    if (results.size >= limit) return@forEach
                }
                lineNo++
            }
        }
        if (results.isEmpty()) return ToolResult("No matches found")
        return ToolResult(results.joinToString("\n"))
    }
}

/**
 * After an AI edit, JSON/JSONC files are validated immediately. Straightforward
 * syntax mistakes are auto-repaired with deterministic rules (re-validated before
 * being accepted); otherwise the exact failure location is surfaced so the model
 * can correct it. Non-structured files are left untouched.
 */
private fun validateStructuredFile(file: File): String {
    val ext = file.extension.lowercase()
    if (ext != "json" && ext != "jsonc") return ""
    val text = runCatching { file.readText() }.getOrNull() ?: return ""
    val result = com.aide.mobilestudio.ai.validation.JsonValidator.validate(text)
    if (result.valid) return "\nJSON validation: OK"
    val repair = com.aide.mobilestudio.ai.validation.JsonRepairer.repair(text)
    if (repair.success) {
        return runCatching {
            file.writeText(repair.repaired)
            "\nJSON validation: syntax was auto-repaired (${repair.fixes.joinToString(", ")})."
        }.getOrElse {
            "\nJSON validation: invalid — ${result.issues.firstOrNull()?.toShortString()} (auto-repair write failed)"
        }
    }
    val issue = result.issues.firstOrNull()
    return "\nJSON validation FAILED at ${issue?.line ?: "?"}:${issue?.column ?: "?"}: ${issue?.message ?: "invalid JSON"}"
}

// ---------------------------------------------------------------- file tools

/** Copies a file or directory inside the workspace to a new destination. */
class CopyTool : OcTool {
    override val name = "copy_file"

    override fun description(ctx: ToolContext): String =
        "Copies a file or directory from one path to another inside the workspace. " +
            "Use for duplicating code, assets or configs without rewriting them by hand."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put("description", "Source path (absolute or workspace-relative)"))
            put("dest", JSONObject().put("type", "string").put("description", "Destination path (absolute or workspace-relative)"))
        })
        put("required", JSONArray().put("path").put("dest"))
    }

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val src = ctx.resolveInWorkspace(args.optString("path", ""))
        val dest = ctx.resolveInWorkspace(args.optString("dest", ""))
        if (!src.exists()) return ToolResult("Error: Source not found: ${src.path}", isError = true)
        if (dest.exists()) return ToolResult("Error: Destination already exists: ${dest.path}", isError = true)
        var ok: Boolean
        if (src.isDirectory) {
            dest.mkdirs()
            ok = src.copyRecursively(dest)
        } else {
            dest.parentFile?.mkdirs()
            src.copyTo(dest)
            ok = true
        }
        return ToolResult(
            if (ok) "Copied ${src.path} -> ${dest.path}" else "Copy failed: ${src.path} -> ${dest.path}",
            isError = !ok,
        )
    }
}

/** Moves (renames) a file or directory inside the workspace. */
class MoveTool : OcTool {
    override val name = "move_file"

    override fun description(ctx: ToolContext): String =
        "Moves or renames a file or directory inside the workspace. " +
            "Use for reorganising paths after a rename or split."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put("description", "Source path (absolute or workspace-relative)"))
            put("dest", JSONObject().put("type", "string").put("description", "Destination path (absolute or workspace-relative)"))
        })
        put("required", JSONArray().put("path").put("dest"))
    }

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val src = ctx.resolveInWorkspace(args.optString("path", ""))
        val dest = ctx.resolveInWorkspace(args.optString("dest", ""))
        if (!src.exists()) return ToolResult("Error: Source not found: ${src.path}", isError = true)
        dest.parentFile?.mkdirs()
        val ok = src.renameTo(dest)
        return ToolResult(
            if (ok) "Moved ${src.path} -> ${dest.path}" else "Move failed: ${src.path} -> ${dest.path}",
            isError = !ok,
        )
    }
}

/** Deletes a file or directory inside the workspace. Irreversible once approved. */
class DeleteTool : OcTool {
    override val name = "delete_file"

    override fun description(ctx: ToolContext): String =
        "Deletes a file or directory inside the workspace. " +
            "Destructive: the permission layer asks the user before anything is removed."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put("description", "Path to delete (absolute or workspace-relative)"))
        })
        put("required", JSONArray().put("path"))
    }

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.DANGEROUS

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val target = ctx.resolveInWorkspace(args.optString("path", ""))
        if (!target.exists()) return ToolResult("Error: Not found: ${target.path}", isError = true)
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        return ToolResult(
            if (ok) "Deleted ${target.path}" else "Delete failed: ${target.path}",
            isError = !ok,
        )
    }
}
