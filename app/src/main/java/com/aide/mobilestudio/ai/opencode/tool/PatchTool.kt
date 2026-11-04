package com.aide.mobilestudio.ai.opencode.tool

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PatchTool : OcTool {
    override val name = "patch"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/apply_patch.txt")
            ?: "Use the apply_patch tool to edit files with a stripped-down, file-oriented diff format."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("patchText", JSONObject().put("type", "string").put(
                "description",
                "The full patch text describing add, update, and delete operations",
            ))
        })
        put("required", JSONArray().put("patchText"))
    }

    override fun permissionKey(): String = "edit"

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val patchText = args.optString("patchText", "")
        if (patchText.isBlank()) return ToolResult("Error: patchText is required", isError = true)
        val lines = patchText.split("\n")
        val begin = lines.indexOfFirst { it.trim() == "*** Begin Patch" }
        if (begin < 0) return ToolResult("Error: patch must start with *** Begin Patch", isError = true)
        val end = lines.indexOfFirst { it.trim() == "*** End Patch" }
        val body = lines.subList(begin + 1, if (end >= 0) end else lines.size)

        data class Op(val action: String, val path: String, val moveTo: String?, val hunks: MutableList<List<String>>)

        val ops = mutableListOf<Op>()
        var current: Op? = null
        var currentHunk = mutableListOf<String>()
        for (raw in body) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("*** Add File: ") -> {
                    current?.let { it.hunks.add(currentHunk); ops.add(it) }
                    currentHunk = mutableListOf()
                    current = Op("add", line.removePrefix("*** Add File: ").trim(), null, mutableListOf())
                }
                line.startsWith("*** Delete File: ") -> {
                    current?.let { it.hunks.add(currentHunk); ops.add(it) }
                    currentHunk = mutableListOf()
                    current = Op("delete", line.removePrefix("*** Delete File: ").trim(), null, mutableListOf())
                }
                line.startsWith("*** Update File: ") -> {
                    current?.let { it.hunks.add(currentHunk); ops.add(it) }
                    currentHunk = mutableListOf()
                    current = Op("update", line.removePrefix("*** Update File: ").trim(), null, mutableListOf())
                }
                line.startsWith("*** Move to: ") -> {
                    val op = current ?: continue
                    current = op.copy(moveTo = line.removePrefix("*** Move to: ").trim())
                }
                else -> {
                    val op = current ?: continue
                    if (line.startsWith("@@")) continue
                    if (line.isEmpty() && currentHunk.isEmpty()) continue
                    currentHunk.add(line)
                }
            }
        }
        current?.let { it.hunks.add(currentHunk); ops.add(it) }

        if (ops.isEmpty()) return ToolResult("Error: no file operations found in patch", isError = true)

        val summary = mutableListOf<String>()
        for (op in ops) {
            val file = ctx.resolveInWorkspace(op.path)
            when (op.action) {
                "add" -> {
                    if (file.exists()) return ToolResult("Error: cannot add file that already exists: ${op.path}", isError = true)
                    val content = op.hunks.flatMap { it }
                        .filter { it.startsWith("+") }
                        .joinToString("\n") { it.removePrefix("+") }
                    ctx.bridge.snapshotFile(ctx.bridge.currentMessageId(), file)
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        summary.add("Added ${op.path}")
                    }.getOrElse { return ToolResult("Error adding file ${op.path}: ${it.message}", isError = true) }
                }
                "delete" -> {
                    if (!file.isFile) return ToolResult("Error: cannot delete missing file: ${op.path}", isError = true)
                    ctx.bridge.snapshotFile(ctx.bridge.currentMessageId(), file)
                    runCatching { file.delete() }
                        .getOrElse { return ToolResult("Error deleting file ${op.path}: ${it.message}", isError = true) }
                    summary.add("Deleted ${op.path}")
                }
                "update" -> {
                    if (!file.isFile) return ToolResult("Error: cannot update missing file: ${op.path}", isError = true)
                    if (!ctx.bridge.hasReadFile(file.absolutePath)) {
                        return ToolResult("Error: You must use the read tool before updating ${op.path}.", isError = true)
                    }
                    val content = runCatching { file.readText() }.getOrNull()
                        ?: return ToolResult("Error: unable to read file: ${op.path}", isError = true)
                    var updated = content
                    for (hunk in op.hunks) {
                        if (hunk.isEmpty()) continue
                        val oldLines = hunk.filter { it.startsWith(" ") || it.startsWith("-") }
                            .map { it.drop(1) }
                        val newLines = hunk.filter { it.startsWith(" ") || it.startsWith("+") }
                            .map { it.drop(1) }
                        if (oldLines.isEmpty()) continue
                        val oldBlock = oldLines.joinToString("\n")
                        val newBlock = newLines.joinToString("\n")
                        val count = countOccurrences(updated, oldBlock)
                        if (count == 0) {
                            return ToolResult("Error: hunk context not found in ${op.path}:\n$oldBlock", isError = true)
                        }
                        if (count > 1) {
                            return ToolResult("Error: hunk context matches multiple locations in ${op.path}. Provide more surrounding lines.", isError = true)
                        }
                        updated = updated.replaceFirst(oldBlock, newBlock)
                    }
                    ctx.bridge.snapshotFile(ctx.bridge.currentMessageId(), file)
                    val target = if (op.moveTo != null) ctx.resolveInWorkspace(op.moveTo) else file
                    runCatching {
                        target.parentFile?.mkdirs()
                        target.writeText(updated)
                        if (target != file) file.delete()
                        summary.add(if (op.moveTo != null) "Updated and moved ${op.path} -> ${op.moveTo}" else "Updated ${op.path}")
                    }.getOrElse { return ToolResult("Error updating file ${op.path}: ${it.message}", isError = true) }
                }
            }
        }
        return ToolResult(summary.joinToString("\n"))
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
