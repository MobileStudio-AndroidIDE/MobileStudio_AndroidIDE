package com.aide.mobilestudio.ai.opencode.tool

import com.aide.mobilestudio.ai.model.DownloadEngine
import com.aide.mobilestudio.ai.model.DownloadTask
import com.aide.mobilestudio.ai.model.ModelCatalog
import com.aide.mobilestudio.ai.model.ModelDownloads
import com.aide.mobilestudio.ai.model.ModelDownloadService
import com.aide.mobilestudio.ai.model.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools for the same GGUF catalogue the Models tab uses.
 *
 * Search and status are read-only. Starting a download is a real, observable
 * action: it creates the same task the UI would, shows progress in the same
 * Download tab, and is registered on completion by the catalogue listener.
 * The model can therefore work with DeepSeek, Llama, Gemma, Mistral and any
 * other repository rather than a hard-coded Qwen list.
 */
class ModelSearchTool(private val catalog: ModelCatalog) : OcTool {

    override val name = "model_search"

    override fun description(ctx: ToolContext): String =
        "Searches live Hugging Face GGUF repositories for downloadable local models. " +
            "Use query=\"DeepSeek\" or another model name, or popular=true to browse. " +
            "Results include the exact repo, file/shard count, size and quantisation."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().put("type", "string").put(
                "description", "Model or family to search for, e.g. DeepSeek, Llama, Gemma, Korean. Empty with popular=true browses top models.",
            ))
            put("popular", JSONObject().put("type", "boolean").put(
                "description", "Browse the most-downloaded practical models instead of searching. Defaults to false.",
            ))
            put("maxResults", JSONObject().put("type", "integer").put(
                "description", "Rows to return, 1..30. Defaults to 12.",
            ))
        })
    }

    override fun permissionKey(): String = "read"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = "https://huggingface.co"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.optString("query", "").trim()
        val popular = args.optBoolean("popular", false)
        val limit = args.optInt("maxResults", 12).coerceIn(1, 30)
        val result = withContext(Dispatchers.IO) {
            when {
                popular || query.isEmpty() -> catalog.popularDownloadable(limit = 30, maxRepos = 10)
                else -> catalog.searchDownloadable(query, limit = 30, maxRepos = 10)
            }
        }
        return when (result) {
            is ModelCatalog.SearchResult.Failure -> ToolResult("Error: ${result.message}", isError = true)
            is ModelCatalog.SearchResult.Ok -> {
                val entries = result.entries.take(limit)
                if (entries.isEmpty()) {
                    ToolResult(
                        (if (popular || query.isEmpty()) "No popular GGUF models were returned. "
                        else "No GGUF models matched \"$query\". ") +
                            "Try another name, an owner/repo, or popular=true.",
                    )
                } else {
                    ToolResult(
                        buildString {
                            append("GGUF search: ")
                            append(if (popular || query.isEmpty()) "popular" else query)
                            append(" (").append(entries.size).append(" shown)\n")
                            entries.forEachIndexed { index, entry -> append(formatModel(index + 1, entry)) }
                            append("\nStart one with model_download(repo=\"owner/repo\", file=\"Q4_K_M\").")
                        },
                    )
                }
            }
        }
    }

    private fun formatModel(index: Int, entry: ModelEntry): String = buildString {
        append(index).append(". ").append(entry.displayName).append('\n')
        append("   repo: ").append(entry.repoId ?: "?").append('\n')
        append("   file: ").append(entry.fileName ?: "?").append('\n')
        entry.quant?.let { append("   quant: ").append(it).append('\n') }
        entry.fileSizeBytes?.let { append("   size: ").append(DownloadEngine.humanBytes(it)).append('\n') }
        if (entry.parts.size > 1) {
            append("   parts: ").append(entry.parts.size).append(" (downloaded together)\n")
        }
        entry.sha256?.let { append("   sha256: ").append(it.take(16)).append("…\n") }
    }
}

/** Starts a real download from the live catalogue. */
class ModelDownloadTool(private val catalog: ModelCatalog) : OcTool {

    override val name = "model_download"

    override fun description(ctx: ToolContext): String =
        "Starts downloading one GGUF model from Hugging Face into the app's model folder. " +
            "Pass owner/repo and choose a file or quantisation from model_search. Split models " +
            "download every shard as one task. Progress appears in the Download tab."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("repo", JSONObject().put("type", "string").put(
                "description", "Hugging Face repository, e.g. unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF or a model URL.",
            ))
            put("file", JSONObject().put("type", "string").put(
                "description", "File name, quantisation (Q4_K_M), or a unique part of either. Omit to list choices.",
            ))
        })
        put("required", JSONArray().put("repo"))
    }

    override fun permissionKey(): String = "model_download"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String =
        args.optString("repo", "").trim()

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.MODIFY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val repo = args.optString("repo", "").trim()
        if (repo.isEmpty()) return ToolResult("Error: repo is required", isError = true)
        val wanted = args.optString("file", "").trim()
        val result = withContext(Dispatchers.IO) { catalog.downloadableFromRepo(repo) }
        return when (result) {
            is ModelCatalog.SearchResult.Failure -> ToolResult("Error: ${result.message}", isError = true)
            is ModelCatalog.SearchResult.Ok -> {
                val all = result.entries
                if (all.isEmpty()) return ToolResult("Error: no downloadable GGUF models in $repo", isError = true)
                val matches = if (wanted.isEmpty()) all else all.filter { entry ->
                    entry.fileName.orEmpty().contains(wanted, ignoreCase = true) ||
                        entry.displayName.contains(wanted, ignoreCase = true) ||
                        entry.quant.orEmpty().contains(wanted, ignoreCase = true) ||
                        entry.parts.any { it.fileName.contains(wanted, ignoreCase = true) }
                }
                if (matches.isEmpty()) {
                    return ToolResult(
                        "Error: no file matched \"$wanted\" in $repo.\n" +
                            "Available:\n${all.joinToString("\n") { "  ${it.fileName}  ${it.quant ?: ""}  ${it.fileSizeBytes?.let(DownloadEngine::humanBytes) ?: ""}" }}",
                        isError = true,
                    )
                }
                if (matches.size > 1) {
                    return ToolResult(
                        "Multiple files match \"$wanted\" in $repo. Pass a more specific file or quant:\n" +
                            matches.take(30).joinToString("\n") { "  ${it.fileName}  ${it.quant ?: ""}  ${it.fileSizeBytes?.let(DownloadEngine::humanBytes) ?: ""}" },
                    )
                }
                val entry = matches.single()
                val spec = catalog.downloadSpec(entry)
                    ?: return ToolResult("Error: the selected file is not a safe downloadable GGUF", isError = true)
                val manager = ModelDownloads.get(ctx.androidContext.applicationContext)
                val task = manager.start(spec)
                runCatching {
                    ModelDownloadService.start(ctx.androidContext.applicationContext, task.id)
                }
                ToolResult(
                    buildString {
                        append("started: ").append(task.spec.displayName).append('\n')
                        append("task: ").append(task.id).append('\n')
                        append("file: ").append(task.spec.destination.absolutePath).append('\n')
                        if (task.spec.parts.isNotEmpty()) {
                            append("parts: ").append(task.spec.parts.size).append(" (one task)\n")
                        }
                        task.spec.expectedBytes.takeIf { it > 0 }?.let {
                            append("size: ").append(DownloadEngine.humanBytes(it)).append('\n')
                        }
                        append("track it with model_download_status, or open the Download tab.")
                    },
                )
            }
        }
    }
}

/** Current and recent download tasks, so the model can report real progress. */
class ModelDownloadStatusTool : OcTool {

    override val name = "model_download_status"

    override fun description(ctx: ToolContext): String =
        "Lists GGUF download tasks with state, real bytes on disk, total size, percentage and last error."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("all", JSONObject().put("type", "boolean").put(
                "description", "Include completed/cancelled tasks. Defaults to true.",
            ))
            put("limit", JSONObject().put("type", "integer").put(
                "description", "Maximum tasks. Defaults to 20.",
            ))
        })
    }

    override fun permissionKey(): String = "read"

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val all = args.optBoolean("all", true)
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val tasks = ModelDownloads.get(ctx.androidContext.applicationContext).tasks.value
            .filter { all || it.isActive }
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
        if (tasks.isEmpty()) return ToolResult("No download tasks.")
        return ToolResult(
            tasks.joinToString("\n") { formatTask(it) },
        )
    }

    private fun formatTask(task: DownloadTask): String = buildString {
        append(task.state.name).append("  ").append(task.spec.displayName).append('\n')
        append("  id: ").append(task.id).append('\n')
        append("  progress: ").append(DownloadEngine.humanBytes(task.progress))
        if (task.totalBytes > 0) {
            append(" / ").append(DownloadEngine.humanBytes(task.totalBytes))
            append(" (").append((task.fraction * 100).toInt()).append("%)")
        }
        append('\n')
        if (task.spec.parts.isNotEmpty()) append("  parts: ").append(task.spec.parts.size).append('\n')
        if (task.checksumVerified) append("  checksum: verified\n")
        task.lastError?.takeIf { it.isNotBlank() }?.let { append("  error: ").append(it).append('\n') }
    }
}
