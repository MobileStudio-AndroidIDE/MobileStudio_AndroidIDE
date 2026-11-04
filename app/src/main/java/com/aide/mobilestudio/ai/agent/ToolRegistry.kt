package com.aide.mobilestudio.ai.agent

import android.content.Context
import android.util.Log
import com.aide.mobilestudio.ShellRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object ToolRegistry {

    private const val TAG = "ToolRegistry"

    /** Legacy tools that mutate files; each invocation gets its own checkpoint turn. */
    private val CHECKPOINTED_TOOLS = setOf("write_file", "write_files", "edit_file", "apply_patch", "edit_manifest")

    @Volatile
    private var currentProjectDir: File? = null

    fun setCurrentProjectDir(dir: String?) {
        currentProjectDir = dir?.let { File(it) }
    }

    fun setCurrentProjectDir(dir: File?) {
        currentProjectDir = dir
    }

    // --- Async command tracking ---
    private data class RunningCommand(
        val id: String,
        val command: String,
        val startTime: Long,
        val output: MutableList<String> = mutableListOf(),
        var exitCode: Int? = null,
        var isRunning: Boolean = true,
        var process: Process? = null
    )

    private val runningCommands = ConcurrentHashMap<String, RunningCommand>()
    private val commandCounter = AtomicInteger(0)

    // --- Listener for streaming command output to UI ---
    var commandOutputListener: ((commandId: String, line: String) -> Unit)? = null

    // --- Hooks for tools that need UI or the agent engine (legacy chat set these) ---
    // The bottom-panel chat and its engine were removed; nothing wires these
    // any more, so the tools report their "no UI / sub-agent unavailable"
    // fallbacks. The workspace answers questions through OcEngine.onAskQuestion.
    /** Answers a question from the "question" tool. Set by the legacy chat UI. */
    @Volatile
    var questionHandler: ((question: String, options: List<String>) -> String)? = null
    /** Runs a read-only sub-agent. Set by the legacy agent engine. */
    @Volatile
    var taskHandler: ((prompt: String, contextInfo: String) -> String)? = null

    fun getToolDefinitions(): JSONArray {
        return JSONArray().apply {
            put(createTool("read_file", "Reads the content of a file.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Absolute path to the file"))
                    })
                    put("required", JSONArray().put("path"))
                }
            ))
            put(createTool("write_file", "Writes content to a file.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Absolute path to the file"))
                        put("content", JSONObject().put("type", "string").put("description", "New content of the file"))
                    })
                    put("required", JSONArray().put("path").put("content"))
                }
            ))
            put(createTool("list_files", "Lists files in a directory.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Absolute path to the directory"))
                    })
                    put("required", JSONArray().put("path"))
                }
            ))
            put(createTool("search_files", "Searches for a string in the project files.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().put("type", "string").put("description", "Search query"))
                        put("path", JSONObject().put("type", "string").put("description", "Optional path to search in"))
                    })
                    put("required", JSONArray().put("query"))
                }
            ))
            put(createTool("run_command", "Executes a shell command synchronously (waits for completion). Use for short commands (< 5 min). For long builds, use run_command_async + check_command.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().put("type", "string").put("description", "The shell command to run"))
                        put("workingDirectory", JSONObject().put("type", "string").put("description", "Optional working directory"))
                    })
                    put("required", JSONArray().put("command"))
                }
            ))
            put(createTool("run_command_async", "Starts a shell command in background and returns a command ID immediately. Use check_command to poll status and get output. Ideal for long-running builds (gradle, make, etc).",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().put("type", "string").put("description", "The shell command to run"))
                        put("workingDirectory", JSONObject().put("type", "string").put("description", "Optional working directory"))
                    })
                    put("required", JSONArray().put("command"))
                }
            ))
            put(createTool("check_command", "Checks the status and output of a previously started async command. Returns exit code (null if still running), output lines, and whether it's still running.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("commandId", JSONObject().put("type", "string").put("description", "The command ID returned by run_command_async"))
                        put("lastNLines", JSONObject().put("type", "integer").put("description", "Optional: return only the last N lines of output (default: all)"))
                    })
                    put("required", JSONArray().put("commandId"))
                }
            ))
            put(createTool("run_gradle", "Runs a Gradle task on the current Android project. Automatically uses the project's gradlew. Example tasks: assembleDebug, clean, lintDebug.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("task", JSONObject().put("type", "string").put("description", "Gradle task name (e.g. 'assembleDebug', 'clean', 'lintDebug')"))
                        put("args", JSONObject().put("type", "string").put("description", "Optional additional arguments (e.g. '--info', '-x test')"))
                    })
                    put("required", JSONArray().put("task"))
                }
            ))
            put(createTool("read_files", "Reads the content of multiple files.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("paths", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Array of absolute file paths"))
                    })
                    put("required", JSONArray().put("paths"))
                }
            ))
            put(createTool("write_files", "Writes content to multiple files.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("files", JSONObject().apply {
                            put("type", "array")
                            put("description", "Array of files to write")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("description", "A single file to write")
                                put("properties", JSONObject().apply {
                                    put("path", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "Absolute path to the file")
                                    })
                                    put("content", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "New content of the file")
                                    })
                                })
                                put("required", JSONArray().put("path").put("content"))
                            })
                        })
                    })
                    put("required", JSONArray().put("files"))
                }
            ))
            put(createTool("git_diff", "Shows git diff for the project or specific files.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Optional file or directory path"))
                        put("staged", JSONObject().put("type", "boolean").put("description", "Show staged changes (default: false)"))
                    })
                }
            ))
            put(createTool("search_codebase", "Semantic search across project codebase. Finds relevant code snippets for a query using keyword matching with context.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().put("type", "string").put("description", "Search query (natural language or keywords)"))
                        put("maxResults", JSONObject().put("type", "integer").put("description", "Max results (default: 10)"))
                        put("fileTypes", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Optional file extensions to filter (e.g., [\"kt\", \"java\", \"xml\"])"))
                    })
                    put("required", JSONArray().put("query"))
                }
            ))
            put(createTool("get_project_context", "Returns information about the current project: type, build system, source directories, git status, error count. Use this to understand the project before making changes.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("includeFileTree", JSONObject().put("type", "boolean").put("description", "Include top-level file listing (default: false)"))
                    })
                }
            ))
            put(createTool("git_commit", "Stages all changes and creates a git commit.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().put("type", "string").put("description", "Commit message"))
                        put("files", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Optional: specific files to stage (default: all changed files)"))
                    })
                    put("required", JSONArray().put("message"))
                }
            ))
            put(createTool("git_status", "Shows detailed git status: branch, staged/unstaged changes, untracked files.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            ))
            put(createTool("git_branch", "Lists branches, creates a new branch, or switches branch.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string").put("description", "Action: 'list', 'create', 'switch', 'delete'"))
                        put("name", JSONObject().put("type", "string").put("description", "Branch name (required for create/switch/delete)"))
                    })
                    put("required", JSONArray().put("action"))
                }
            ))
            put(createTool("save_memory", "Saves a piece of project knowledge/memory for future sessions. Use this to remember architecture decisions, conventions, bug patterns, etc.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().put("type", "string").put("description", "Short key/title for this memory (e.g. 'auth_flow', 'build_config')"))
                        put("content", JSONObject().put("type", "string").put("description", "The knowledge to remember"))
                        put("category", JSONObject().put("type", "string").put("description", "Category: 'architecture', 'convention', 'bug', 'decision', 'note'"))
                    })
                    put("required", JSONArray().put("key").put("content"))
                }
            ))
            put(createTool("recall_memory", "Recalls saved project memories. Use this to remember past decisions and context.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().put("type", "string").put("description", "Optional search query to filter memories"))
                        put("category", JSONObject().put("type", "string").put("description", "Optional category filter"))
                    })
                }
            ))
            put(createTool("run_tests", "Runs unit or instrumented tests and returns results with failure analysis.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("type", JSONObject().put("type", "string").put("description", "Test type: 'unit' (default) or 'instrumented'"))
                        put("classFilter", JSONObject().put("type", "string").put("description", "Optional: run only tests matching this class name pattern"))
                        put("methodFilter", JSONObject().put("type", "string").put("description", "Optional: run only tests matching this method name pattern"))
                    })
                }
            ))
            put(createTool("web_search", "Search the web for current information using DuckDuckGo. Returns search results with titles, URLs, and snippets.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().put("type", "string").put("description", "Search query"))
                        put("numResults", JSONObject().put("type", "integer").put("description", "Number of results (default: 5)"))
                    })
                    put("required", JSONArray().put("query"))
                }
            ))
            put(createTool("web_fetch", "Fetches the content of a URL and returns it as text. Useful for reading documentation, API references, etc.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().put("type", "string").put("description", "URL to fetch"))
                        put("maxLength", JSONObject().put("type", "integer").put("description", "Max characters to return (default: 15000)"))
                    })
                    put("required", JSONArray().put("url"))
                }
            ))
            put(createTool("edit_file", "Edits a file by searching for a specific string and replacing it with new content. Safer than write_file for targeted changes.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Absolute path to the file"))
                        put("oldString", JSONObject().put("type", "string").put("description", "The exact string to find and replace"))
                        put("newString", JSONObject().put("type", "string").put("description", "The replacement string"))
                    })
                    put("required", JSONArray().put("path").put("oldString").put("newString"))
                }
            ))
            put(createTool("todowrite", "Creates or updates a structured todo list for tracking task progress. The AI maintains todos to track multi-step work.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("todos", JSONObject().apply {
                            put("type", "array")
                            put("description", "Array of todo items")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("content", JSONObject().put("type", "string").put("description", "Todo description"))
                                    put("status", JSONObject().put("type", "string").put("description", "Status: pending, in_progress, or completed"))
                                    put("priority", JSONObject().put("type", "string").put("description", "Priority: high, medium, or low"))
                                })
                            })
                        })
                    })
                    put("required", JSONArray().put("todos"))
                }
            ))
            put(createTool("view_diff", "Shows a unified diff of changes made to files. Use after write_file or edit_file to show what changed.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "File path to show diff for. If omitted, shows all uncommitted changes."))
                    })
                }
            ))
            put(createTool("apply_patch", "Applies a unified diff patch to modify a file. More precise than edit_file for multi-location changes.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Path to the file to patch"))
                        put("patch", JSONObject().put("type", "string").put("description", "Unified diff format patch"))
                    })
                    put("required", JSONArray().put("path").put("patch"))
                }
            ))
            put(createTool("question", "Ask the user a clarifying question before continuing. Blocks until the user answers. Use when requirements are ambiguous or you need a decision.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("question", JSONObject().put("type", "string").put("description", "The question to ask the user"))
                        put("options", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().put("type", "string"))
                            put("description", "Optional suggested answer options")
                        })
                    })
                    put("required", JSONArray().put("question"))
                }
            ))
            put(createTool("task", "Delegate a sub-task to a sub-agent with its own context. Use for research, exploration, or analysis that should not pollute the main conversation. The sub-agent is read-only.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("prompt", JSONObject().put("type", "string").put("description", "Detailed task description for the sub-agent"))
                        put("context", JSONObject().put("type", "string").put("description", "Optional extra context (file paths, constraints)"))
                        put("description", JSONObject().put("type", "string").put("description", "Short 3-5 word label for this sub-task"))
                    })
                    put("required", JSONArray().put("prompt"))
                }
            ))
            put(createTool("lsp", "Code intelligence: 'diagnostics' returns compiler/IDE errors for the open project, 'definition' finds where a symbol is declared, 'references' finds usages of a symbol, 'symbols' lists symbols in a file.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "string")
                            .put("enum", JSONArray().put("diagnostics").put("definition").put("references").put("symbols"))
                            .put("description", "Which LSP operation to perform"))
                        put("symbol", JSONObject().put("type", "string").put("description", "Symbol name (for definition/references)"))
                        put("file", JSONObject().put("type", "string").put("description", "File path (for symbols; optional)"))
                        put("query", JSONObject().put("type", "string").put("description", "Alternative symbol/query text (for definition/references)"))
                    })
                    put("required", JSONArray().put("action"))
                }
            ))
            put(createTool("glob", "Finds files by name pattern (supports * and ? wildcards, e.g. '*.kt' or 'Android*Manifest*'). Returns matching paths.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("pattern", JSONObject().put("type", "string").put("description", "Filename pattern with * / ? wildcards"))
                        put("path", JSONObject().put("type", "string").put("description", "Directory to search in (default: project root)"))
                    })
                    put("required", JSONArray().put("pattern"))
                }
            ))
            put(createTool("grep", "Regex/substring content search across project files. Returns file:line:content matches.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("pattern", JSONObject().put("type", "string").put("description", "Regular expression or plain substring"))
                        put("path", JSONObject().put("type", "string").put("description", "Directory to search in (default: project root)"))
                        put("include", JSONObject().put("type", "string").put("description", "File filter glob, e.g. '*.kt'"))
                    })
                    put("required", JSONArray().put("pattern"))
                }
            ))
            put(createTool("logcat_search", "Reads recent Android logcat and filters by a regex. Use to analyze crashes/ANRs/errors from a just-run app or build tooling.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("filter", JSONObject().put("type", "string").put("description", "Regex to filter log lines (empty = last N lines raw)"))
                        put("maxLines", JSONObject().put("type", "integer").put("description", "Max lines to return (default 200)"))
                    })
                }
            ))
            put(createTool("edit_manifest", "Reads or patches AndroidManifest.xml: add a uses-permission, add an <activity>, or insert raw XML before </application>.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("addPermission", JSONObject().put("type", "string").put("description", "e.g. android.permission.CAMERA"))
                        put("addActivity", JSONObject().put("type", "string").put("description", "Fully-qualified activity class name to register"))
                        put("insertXml", JSONObject().put("type", "string").put("description", "Raw XML snippet to insert before </application>"))
                        put("read", JSONObject().put("type", "boolean").put("description", "Read-only: return current manifest content"))
                    })
                }
            ))
            put(createTool("find_resource", "Searches Android resources: finds res/values definitions and R.<type>.<name> usages for a resource name.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().put("type", "string").put("description", "Resource name (e.g. 'ic_launcher', 'app_name')"))
                        put("type", JSONObject().put("type", "string").put("description", "Optional resource type: string/color/drawable/layout/id/menu/xml"))
                    })
                    put("required", JSONArray().put("name"))
                }
            ))
            put(createTool("analyze_dependencies", "Analyzes the project's dependencies: parses build.gradle(.kts), settings.gradle(.kts) and libs.versions.toml. Returns plugins, modules, and external libraries.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("path", JSONObject().put("type", "string").put("description", "Optional specific gradle file to analyze (default: whole project)"))
                    })
                }
            ))
            put(createTool("undo_last_edit", "Reverts the most recently modified file back to its state before the AI edited it. Each write_file/edit_file/apply_patch/edit_manifest is backed up first.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {}
                        .put("all", JSONObject().put("type", "boolean").put("description", "Revert all pending backups in order (default: just the last one)"))
                    )
                }
            ))
        }
    }

    private fun createTool(name: String, description: String, parameters: JSONObject): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        }
    }

    /** Spec-compatible aliases: read/write/edit/bash/list/websearch/webfetch map to canonical tools. */
    private fun normalizeToolName(raw: String): String = when (raw) {
        "read" -> "read_file"
        "write" -> "write_file"
        "edit" -> "edit_file"
        "bash", "sh", "shell" -> "run_command"
        "list" -> "list_files"
        "websearch" -> "web_search"
        "webfetch" -> "web_fetch"
        else -> raw
    }

    // --- Undo: every AI file modification is backed up before overwriting ---
    private data class UndoEntry(val path: String, val backupFile: File, val timestamp: Long)
    private val undoStack = ArrayDeque<UndoEntry>()
    private const val MAX_UNDO = 50

    // --- Checkpoints (/undo //redo): same pre-edit hook, persisted per turn ---
    @Volatile
    private var legacyCheckpointStore: CheckpointStore? = null
    private val legacyTurnSeq = AtomicInteger(0)
    @Volatile
    private var activeLegacyTurn: String = ""

    private fun checkpointStore(context: Context): CheckpointStore =
        legacyCheckpointStore ?: CheckpointStore(File(context.filesDir, "ai/checkpoints")).also {
            legacyCheckpointStore = it
        }

    /** Copies the current file content into the undo stack before an AI write. */
    private fun backupBeforeWrite(context: Context, file: File) {
        // Checkpoint FIRST and unconditionally: it deliberately supports paths
        // that do not exist yet (content == null), which is what lets /undo
        // delete a file the agent just created. Gating this on file.isFile
        // would silently lose every newly created file.
        runCatching {
            checkpointStore(context).capture(
                "legacy",
                activeLegacyTurn.ifEmpty { "legacy-turn-${legacyTurnSeq.incrementAndGet()}" },
                file,
            )
        }
        // The legacy undo stack still needs a real file to copy a backup from.
        if (!file.exists() || !file.isFile) return
        try {
            val dir = File(context.filesDir, "AI/undo").apply { mkdirs() }
            val bak = File(dir, "${System.currentTimeMillis()}_${file.name}")
            file.copyTo(bak, overwrite = true)
            undoStack.addLast(UndoEntry(file.absolutePath, bak, System.currentTimeMillis()))
            while (undoStack.size > MAX_UNDO) {
                undoStack.removeFirst().backupFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "backup failed for ${file.path}", e)
        }
    }

    /** Reverts the most recent AI file edit. Returns a status message. */
    private fun undoLast(context: Context, all: Boolean): String {
        if (undoStack.isEmpty()) return "Nothing to undo (no AI file edits recorded)."
        val restored = mutableListOf<String>()
        do {
            val entry = undoStack.removeLast()
            try {
                val target = File(entry.path)
                target.parentFile?.mkdirs()
                entry.backupFile.copyTo(target, overwrite = true)
                entry.backupFile.delete()
                restored.add(entry.path)
            } catch (e: Exception) {
                return "Undo failed for ${entry.path}: ${e.message} (restored so far: ${restored.joinToString()})"
            }
        } while (all && undoStack.isNotEmpty())
        return "Reverted: ${restored.joinToString("\n")} (${restored.size} file(s))"
    }

    /**
     * Project sandbox: file access is limited to the project directory and the
     * app's private/external storage. Returns an error message, or null if allowed.
     */
    private fun checkPathAllowed(context: Context, path: String): String? {
        val proj = currentProjectDir ?: return null
        val canon = try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }
        val roots = buildList {
            add(runCatching { proj.canonicalPath }.getOrElse { proj.absolutePath })
            add(context.filesDir.canonicalPath)
            context.cacheDir?.let { add(it.canonicalPath) }
            context.getExternalFilesDir(null)?.let { add(it.canonicalPath) }
            context.externalCacheDir?.let { add(it.canonicalPath) }
        }
        val ok = roots.any { canon == it || canon.startsWith("$it/") }
        return if (ok) null else "Error: Access outside the project is not allowed: $path (allowed: project dir, app storage)"
    }

    fun executeTool(context: Context, nameParam: String, args: JSONObject, onResult: (String) -> Unit) {
        val name = normalizeToolName(nameParam)
        // One checkpoint turn per mutating tool invocation, so write_files and
        // similar multi-file tools roll back together.
        if (name in CHECKPOINTED_TOOLS) {
            activeLegacyTurn = "legacy-turn-${legacyTurnSeq.incrementAndGet()}"
        }
        when (name) {
            "read_file" -> {
                val path = args.getString("path")
                checkPathAllowed(context, path)?.let { onResult(it); return }
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val text = file.readText()
                    if (text.length > 50000) {
                        onResult(text.take(50000) + "\n\n... (truncated, ${text.length} chars total)")
                    } else {
                        onResult(text)
                    }
                } else {
                    onResult("Error: File not found or is a directory: $path")
                }
            }
            "write_file" -> {
                val path = args.getString("path")
                val content = args.getString("content")
                checkPathAllowed(context, path)?.let { onResult(it); return }
                try {
                    val file = File(path)
                    backupBeforeWrite(context, file)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    onResult("Successfully written to $path (${content.length} chars)")
                } catch (e: Exception) {
                    onResult("Error writing file: ${e.message}")
                }
            }
            "list_files" -> {
                val path = args.getString("path")
                checkPathAllowed(context, path)?.let { onResult(it); return }
                val dir = File(path)
                if (dir.exists() && dir.isDirectory) {
                    val entries = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
                    val list = entries.joinToString("\n") { if (it.isDirectory) "[DIR] ${it.name}" else it.name }
                    onResult(list.ifEmpty { "(empty directory)" })
                } else {
                    onResult("Error: Directory not found: $path")
                }
            }
            "search_files" -> {
                val query = args.getString("query")
                val path = args.optString("path", currentProjectDir?.absolutePath ?: ".")
                val results = mutableListOf<String>()
                val root = File(path)
                if (root.exists()) {
                    root.walkTopDown().maxDepth(6).forEach { file ->
                        if (file.isFile && !file.path.contains(".git") && !file.path.contains(".gradle") && !file.path.contains("build/")) {
                            val content = runCatching { file.readText() }.getOrNull() ?: return@forEach
                            if (content.contains(query)) {
                                results.add(file.absolutePath)
                            }
                        }
                        if (results.size > 30) return@forEach
                    }
                    onResult(results.joinToString("\n").ifEmpty { "No results found for: $query" })
                } else {
                    onResult("Error: Search path not found: $path")
                }
            }
            "run_command" -> {
                val command = args.getString("command")
                val workingDir = args.optString("workingDirectory", currentProjectDir?.absolutePath ?: context.filesDir.absolutePath)
                val timeout = if (command.contains("gradlew") || command.contains("gradle")) 300000L else 120000L
                val out = mutableListOf<String>()
                val result = ShellRunner.runSh(context, command, File(workingDir), emptyMap(), timeout) { line ->
                    out.add(line)
                    if (out.size > 2000) out.removeAt(0)
                }
                val output = out.joinToString("\n")
                val truncated = if (output.length > 30000) output.take(30000) + "\n\n... (output truncated)" else output
                onResult("Exit code: ${result.exitCode}\nOutput:\n$truncated")
            }
            "run_command_async" -> {
                val command = args.getString("command")
                val workingDir = args.optString("workingDirectory", currentProjectDir?.absolutePath ?: context.filesDir.absolutePath)
                val cmdId = "cmd_${commandCounter.incrementAndGet()}"
                val running = RunningCommand(cmdId, command, System.currentTimeMillis())
                runningCommands[cmdId] = running

                Thread {
                    try {
                        val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", command))
                            .directory(File(workingDir))
                            .redirectErrorStream(true)
                        val proc = pb.start()
                        running.process = proc
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(running.output) {
                                running.output.add(line)
                                if (running.output.size > 5000) running.output.removeAt(0)
                            }
                            commandOutputListener?.invoke(cmdId, line)
                        }
                        running.exitCode = proc.waitFor()
                    } catch (e: Exception) {
                        running.exitCode = -1
                        synchronized(running.output) {
                            running.output.add("Error: ${e.message}")
                        }
                    } finally {
                        running.isRunning = false
                    }
                }.start()

                onResult("Command started with ID: $cmdId\nCommand: $command\nUse check_command to poll status.")
            }
            "check_command" -> {
                val cmdId = args.getString("commandId")
                val lastN = args.optInt("lastNLines", -1)
                val running = runningCommands[cmdId]
                if (running == null) {
                    onResult("Error: Unknown command ID: $cmdId")
                    return
                }
                val outputLines = synchronized(running.output) {
                    if (lastN > 0) running.output.takeLast(lastN) else running.output.toList()
                }
                val elapsed = System.currentTimeMillis() - running.startTime
                val sb = StringBuilder()
                sb.append("Command: ${running.command}\n")
                sb.append("Status: ${if (running.isRunning) "RUNNING" else "FINISHED"}\n")
                sb.append("Elapsed: ${elapsed / 1000}s\n")
                if (!running.isRunning) {
                    sb.append("Exit code: ${running.exitCode}\n")
                }
                sb.append("Output lines: ${running.output.size}\n")
                if (outputLines.isNotEmpty()) {
                    sb.append("\n--- Output ---\n")
                    sb.append(outputLines.joinToString("\n"))
                }
                val result = sb.toString()
                val truncated = if (result.length > 30000) result.take(30000) + "\n\n... (truncated)" else result
                onResult(truncated)
            }
            "run_gradle" -> {
                val task = args.getString("task")
                val extraArgs = args.optString("args", "")
                val projectDir = currentProjectDir
                if (projectDir == null) {
                    onResult("Error: No project directory set. Open a project first.")
                    return
                }
                val gradlew = File(projectDir, "gradlew")
                if (!gradlew.exists()) {
                    onResult("Error: gradlew not found in ${projectDir.absolutePath}")
                    return
                }
                val cmd = "./gradlew $task $extraArgs".trim()
                val cmdId = "cmd_${commandCounter.incrementAndGet()}"
                val running = RunningCommand(cmdId, cmd, System.currentTimeMillis())
                runningCommands[cmdId] = running

                Thread {
                    try {
                        val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", cmd))
                            .directory(projectDir)
                            .redirectErrorStream(true)
                        val proc = pb.start()
                        running.process = proc
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(running.output) {
                                running.output.add(line)
                                if (running.output.size > 5000) running.output.removeAt(0)
                            }
                            commandOutputListener?.invoke(cmdId, line)
                        }
                        running.exitCode = proc.waitFor()
                    } catch (e: Exception) {
                        running.exitCode = -1
                        synchronized(running.output) {
                            running.output.add("Error: ${e.message}")
                        }
                    } finally {
                        running.isRunning = false
                    }
                }.start()

                onResult("Gradle task '$task' started with ID: $cmdId\nUse check_command to poll status.\nTip: Use lastNLines=50 to see recent output without overwhelming context.")
            }
            "read_files" -> {
                val paths = args.getJSONArray("paths")
                val results = mutableListOf<String>()
                for (i in 0 until paths.length()) {
                    val path = paths.getString(i)
                    val file = File(path)
                    if (file.exists() && file.isFile) {
                        val text = file.readText()
                        val truncated = if (text.length > 20000) text.take(20000) + "\n... (truncated)" else text
                        results.add("=== $path ===\n$truncated")
                    } else {
                        results.add("=== $path ===\nError: File not found or is a directory")
                    }
                }
                onResult(results.joinToString("\n\n"))
            }
            "write_files" -> {
                val files = args.getJSONArray("files")
                val results = mutableListOf<String>()
                for (i in 0 until files.length()) {
                    val fileObj = files.getJSONObject(i)
                    val path = fileObj.getString("path")
                    val content = fileObj.getString("content")
                    if (checkPathAllowed(context, path) != null) {
                        results.add("Error writing $path: access outside the project is not allowed")
                        continue
                    }
                    try {
                        val file = File(path)
                        backupBeforeWrite(context, file)
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                        results.add("Successfully written to $path")
                    } catch (e: Exception) {
                        results.add("Error writing $path: ${e.message}")
                    }
                }
                onResult(results.joinToString("\n"))
            }
            "git_diff" -> {
                val path = args.optString("path", "")
                val staged = args.optBoolean("staged", false)
                val cmd = if (staged) "git diff --cached" else "git diff"
                val fullCmd = if (path.isNotBlank()) "$cmd -- $path" else cmd
                val workingDir = currentProjectDir?.absolutePath ?: context.filesDir.absolutePath
                val out = mutableListOf<String>()
                val result = ShellRunner.runSh(context, fullCmd, File(workingDir), emptyMap(), 30000L) { line ->
                    out.add(line)
                }
                val output = out.joinToString("\n")
                val truncated = if (output.length > 30000) output.take(30000) + "\n... (truncated)" else output
                onResult("Exit code: ${result.exitCode}\nOutput:\n${truncated.ifEmpty { "(no changes)" }}")
            }
            "search_codebase" -> {
                val query = args.getString("query")
                val maxResults = args.optInt("maxResults", 10)
                val fileTypesJson = args.optJSONArray("fileTypes")
                val fileTypes: Array<String>? = if (fileTypesJson != null) {
                    (0 until fileTypesJson.length()).map { fileTypesJson.getString(it) }.toTypedArray()
                } else null
                val projectDir: File = currentProjectDir ?: context.filesDir
                val results = searchCodebase(projectDir, query, maxResults, fileTypes)
                onResult(results)
            }
            "get_project_context" -> {
                val includeFileTree = args.optBoolean("includeFileTree", false)
                val projectDir = currentProjectDir
                val sb = StringBuilder()

                if (projectDir == null) {
                    sb.append("No project currently open.\n")
                    onResult(sb.toString())
                    return
                }

                sb.append("Project directory: ${projectDir.absolutePath}\n\n")

                // Detect project type
                val buildGradle = File(projectDir, "build.gradle")
                val buildGradleKts = File(projectDir, "build.gradle.kts")
                val settingsGradle = File(projectDir, "settings.gradle")
                val settingsGradleKts = File(projectDir, "settings.gradle.kts")
                val hasGradle = buildGradle.exists() || buildGradleKts.exists() || settingsGradle.exists() || settingsGradleKts.exists()
                val hasAndroidManifest = File(projectDir, "app/src/main/AndroidManifest.xml").exists()
                val hasCpp = File(projectDir, "app/src/main/cpp").exists()

                sb.append("Build system: ${if (hasGradle) "Gradle" else "Unknown"}\n")
                sb.append("Project type: ${if (hasAndroidManifest) "Android App" else if (hasGradle) "Gradle/JVM" else "Unknown"}\n")
                sb.append("Native code (C++): ${if (hasCpp) "Yes" else "No"}\n")

                // Source directories
                val srcDirs = mutableListOf<String>()
                listOf("app/src/main/java", "app/src/main/kotlin", "app/src/main/cpp", "src/main/java", "src/main/kotlin").forEach { dir ->
                    if (File(projectDir, dir).exists()) srcDirs.add(dir)
                }
                if (srcDirs.isNotEmpty()) {
                    sb.append("\nSource directories:\n")
                    srcDirs.forEach { sb.append("  - $it\n") }
                }

                // Git status
                val gitDir = File(projectDir, ".git")
                if (gitDir.exists()) {
                    val out = mutableListOf<String>()
                    ShellRunner.runSh(context, "git status --short", projectDir, emptyMap(), 5000L) { line -> out.add(line) }
                    val branchOut = mutableListOf<String>()
                    ShellRunner.runSh(context, "git branch --show-current", projectDir, emptyMap(), 5000L) { line -> branchOut.add(line) }
                    sb.append("\nGit branch: ${branchOut.firstOrNull() ?: "unknown"}\n")
                    sb.append("Git status (${out.size} changed files):\n")
                    out.take(20).forEach { sb.append("  $it\n") }
                    if (out.size > 20) sb.append("  ... and ${out.size - 20} more\n")
                }

                // Dependencies hint
                val appBuildGradle = File(projectDir, "app/build.gradle")
                if (appBuildGradle.exists()) {
                    val gradleText = appBuildGradle.readText()
                    val compileSdk = Regex("compileSdk\\s*[=]?\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)
                    val minSdk = Regex("minSdk\\s*[=]?\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)
                    val targetSdk = Regex("targetSdk\\s*[=]?\\s*(\\d+)").find(gradleText)?.groupValues?.get(1)
                    if (compileSdk != null || minSdk != null) {
                        sb.append("\nSDK versions: compileSdk=$compileSdk, minSdk=$minSdk, targetSdk=$targetSdk\n")
                    }
                }

                if (includeFileTree) {
                    sb.append("\nTop-level files:\n")
                    projectDir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })?.take(30)?.forEach {
                        sb.append("  ${if (it.isDirectory) "[DIR]" else "     "} ${it.name}\n")
                    }
                }

                onResult(sb.toString())
            }
            "git_commit" -> {
                val message = args.getString("message")
                val filesJson = args.optJSONArray("files")
                val projectDir = currentProjectDir?.absolutePath ?: context.filesDir.absolutePath

                // Stage files
                val stageCmd = if (filesJson != null && filesJson.length() > 0) {
                    val files = (0 until filesJson.length()).map { filesJson.getString(it) }.joinToString(" ")
                    "git add $files"
                } else {
                    "git add -A"
                }
                val stageOut = mutableListOf<String>()
                ShellRunner.runSh(context, stageCmd, File(projectDir), emptyMap(), 10000L) { line -> stageOut.add(line) }

                // Commit
                val commitOut = mutableListOf<String>()
                val result = ShellRunner.runSh(context, "git commit -m \"${message.replace("\"", "\\\"").replace("\n", "\\n")}\"", File(projectDir), emptyMap(), 10000L) { line -> commitOut.add(line) }

                if (result.exitCode == 0) {
                    onResult("Committed successfully.\n${commitOut.joinToString("\n")}")
                } else {
                    onResult("Commit failed (exit ${result.exitCode}):\n${commitOut.joinToString("\n")}")
                }
            }
            "git_status" -> {
                val projectDir = currentProjectDir?.absolutePath ?: context.filesDir.absolutePath
                val sb = StringBuilder()

                // Branch
                val branchOut = mutableListOf<String>()
                ShellRunner.runSh(context, "git branch --show-current", File(projectDir), emptyMap(), 5000L) { line -> branchOut.add(line) }
                sb.append("Branch: ${branchOut.firstOrNull() ?: "(detached)"}\n\n")

                // Status
                val statusOut = mutableListOf<String>()
                ShellRunner.runSh(context, "git status --short", File(projectDir), emptyMap(), 5000L) { line -> statusOut.add(line) }
                if (statusOut.isEmpty()) {
                    sb.append("Working tree clean. No changes.\n")
                } else {
                    val staged = statusOut.filter { it.startsWith("M ") || it.startsWith("A ") || it.startsWith("D ") || it.startsWith("R ") }
                    val unstaged = statusOut.filter { it.startsWith(" M") || it.startsWith(" D") || it.startsWith(" M") }
                    val untracked = statusOut.filter { it.startsWith("??") }

                    if (staged.isNotEmpty()) {
                        sb.append("Staged (${staged.size}):\n")
                        staged.take(20).forEach { sb.append("  $it\n") }
                    }
                    if (unstaged.isNotEmpty()) {
                        sb.append("Unstaged (${unstaged.size}):\n")
                        unstaged.take(20).forEach { sb.append("  $it\n") }
                    }
                    if (untracked.isNotEmpty()) {
                        sb.append("Untracked (${untracked.size}):\n")
                        untracked.take(20).forEach { sb.append("  $it\n") }
                    }
                    if (statusOut.size > 60) sb.append("  ... and ${statusOut.size - 60} more\n")
                }

                // Last commit
                val logOut = mutableListOf<String>()
                ShellRunner.runSh(context, "git log --oneline -3", File(projectDir), emptyMap(), 5000L) { line -> logOut.add(line) }
                if (logOut.isNotEmpty()) {
                    sb.append("\nRecent commits:\n")
                    logOut.forEach { sb.append("  $it\n") }
                }

                onResult(sb.toString())
            }
            "git_branch" -> {
                val action = args.getString("action")
                val name = args.optString("name", "")
                val projectDir = currentProjectDir?.absolutePath ?: context.filesDir.absolutePath
                val out = mutableListOf<String>()

                val cmd = when (action) {
                    "list" -> "git branch -a"
                    "create" -> {
                        if (name.isBlank()) { onResult("Error: branch name required"); return }
                        "git checkout -b $name"
                    }
                    "switch" -> {
                        if (name.isBlank()) { onResult("Error: branch name required"); return }
                        "git checkout $name"
                    }
                    "delete" -> {
                        if (name.isBlank()) { onResult("Error: branch name required"); return }
                        "git branch -d $name"
                    }
                    else -> { onResult("Error: unknown action '$action'. Use: list, create, switch, delete"); return }
                }

                val result = ShellRunner.runSh(context, cmd, File(projectDir), emptyMap(), 10000L) { line -> out.add(line) }
                onResult("Action: $action${if (name.isNotEmpty()) " ($name)" else ""}\nExit: ${result.exitCode}\n${out.joinToString("\n")}")
            }
            "save_memory" -> {
                val key = args.getString("key")
                val content = args.getString("content")
                val category = args.optString("category", "note")
                val memDir = File(context.filesDir, "AI/memory").apply { mkdirs() }
                val memFile = File(memDir, "${key.replace(Regex("[^a-zA-Z0-9_]"), "_")}.json")
                val json = JSONObject().apply {
                    put("key", key)
                    put("content", content)
                    put("category", category)
                    put("timestamp", System.currentTimeMillis())
                }
                memFile.writeText(json.toString(2))
                onResult("Memory saved: '$key' (category: $category)")
            }
            "recall_memory" -> {
                val query = args.optString("query", "")
                val category = args.optString("category", "")
                val memDir = File(context.filesDir, "AI/memory")
                if (!memDir.exists()) {
                    onResult("No memories saved yet.")
                    return
                }
                val files = memDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
                val memories = files.mapNotNull { file ->
                    runCatching {
                        val json = JSONObject(file.readText())
                        Triple(json.getString("key"), json.getString("content"), json.optString("category", "note"))
                    }.getOrNull()
                }.let { list ->
                    if (category.isNotEmpty()) list.filter { it.third == category } else list
                }.let { list ->
                    if (query.isNotEmpty()) {
                        val q = query.lowercase()
                        list.filter { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    } else list
                }

                if (memories.isEmpty()) {
                    onResult("No matching memories found.")
                    return
                }

                val sb = StringBuilder("Found ${memories.size} memories:\n\n")
                memories.take(20).forEach { (key, content, cat) ->
                    sb.append("[$cat] $key:\n$content\n\n")
                }
                onResult(sb.toString())
            }
            "run_tests" -> {
                val testType = args.optString("type", "unit")
                val classFilter = args.optString("classFilter", "")
                val methodFilter = args.optString("methodFilter", "")
                val projectDir = currentProjectDir?.absolutePath ?: context.filesDir.absolutePath
                val gradlew = File(projectDir, "gradlew")

                if (!gradlew.exists()) {
                    onResult("Error: gradlew not found in $projectDir")
                    return
                }

                val task = when (testType) {
                    "instrumented" -> "connectedDebugAndroidTest"
                    else -> "testDebugUnitTest"
                }

                var cmd = "./gradlew $task"
                if (classFilter.isNotEmpty()) cmd += " --tests \"*${classFilter}*\""
                if (methodFilter.isNotEmpty()) cmd += " --tests \"*.$methodFilter\""

                val out = mutableListOf<String>()
                val result = ShellRunner.runSh(context, cmd, File(projectDir), emptyMap(), 300000L) { line ->
                    out.add(line)
                    if (out.size > 3000) out.removeAt(0)
                }

                val output = out.joinToString("\n")
                val testResults = out.filter { it.contains("PASSED") || it.contains("FAILED") || it.contains("tests completed") }
                val failures = out.filter { it.contains("FAILED") }

                val sb = StringBuilder()
                sb.append("Test type: $testType\n")
                sb.append("Exit code: ${result.exitCode}\n")
                if (testResults.isNotEmpty()) {
                    sb.append("\nResults:\n")
                    testResults.take(30).forEach { sb.append("  $it\n") }
                }
                if (failures.isNotEmpty()) {
                    sb.append("\nFailures (${failures.size}):\n")
                    failures.take(10).forEach { sb.append("  $it\n") }
                }
                val truncated = if (sb.length > 30000) sb.toString().take(30000) + "\n... (truncated)" else sb.toString()
                onResult(truncated)
            }
            "edit_file" -> {
                val path = args.getString("path")
                val oldString = args.getString("oldString")
                val newString = args.getString("newString")
                checkPathAllowed(context, path)?.let { onResult(it); return }
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        onResult("Error: File not found: $path")
                        return
                    }
                    val content = file.readText()
                    if (!content.contains(oldString)) {
                        onResult("Error: oldString not found in $path")
                        return
                    }
                    val count = content.split(oldString).size - 1
                    val newContent = content.replace(oldString, newString)
                    backupBeforeWrite(context, file)
                    file.writeText(newContent)
                    onResult("Successfully replaced $count occurrence(s) in $path")
                } catch (e: Exception) {
                    onResult("Error editing file: ${e.message}")
                }
            }
            "web_search" -> {
                val query = args.getString("query")
                val numResults = args.optInt("numResults", 5)
                try {
                    val results = performWebSearch(query, numResults)
                    onResult(results)
                } catch (e: Exception) {
                    onResult("Error searching web: ${e.message}")
                }
            }
            "web_fetch" -> {
                val url = args.getString("url")
                val maxLength = args.optInt("maxLength", 15000)
                try {
                    val content = performWebFetch(url, maxLength)
                    onResult(content)
                } catch (e: Exception) {
                    onResult("Error fetching URL: ${e.message}")
                }
            }
            "todowrite" -> {
                val todos = args.optJSONArray("todos") ?: JSONArray()
                val sb = StringBuilder("Todo List:\n")
                for (i in 0 until todos.length()) {
                    val t = todos.getJSONObject(i)
                    val status = t.optString("status", "pending")
                    val icon = when(status) { "completed" -> "[x]"; "in_progress" -> "[~]"; else -> "[ ]" }
                    sb.append("$icon ${t.optString("content")} (${t.optString("priority", "medium")})\n")
                }
                onResult(sb.toString())
            }
            "view_diff" -> {
                val path = args.optString("path", "")
                val projectDir = currentProjectDir?.absolutePath ?: "."
                try {
                    val cmd = if (path.isNotBlank()) "git diff --no-color \"$path\"" else "git diff --no-color"
                    val result = ShellRunner.runSh(context, cmd, File(projectDir), emptyMap(), 10000L) { }
                    val output = result.outputLines.joinToString("\n")
                    onResult(output.ifBlank { "No changes detected." })
                } catch (e: Exception) {
                    onResult("Error: ${e.message}")
                }
            }
            "apply_patch" -> {
                val path = args.getString("path")
                val patch = args.getString("patch")
                checkPathAllowed(context, path)?.let { onResult(it); return }
                try {
                    val file = File(path)
                    if (!file.exists()) { onResult("Error: File not found: $path"); return }
                    val original = file.readText()
                    val lines = original.lines().toMutableList()
                    val patchLines = patch.lines()
                    var i = 0
                    while (i < patchLines.size) {
                        val line = patchLines[i]
                        if (line.startsWith("-") && !line.startsWith("---")) {
                            val removeIdx = lines.indexOf(line.removePrefix("-"))
                            if (removeIdx >= 0) lines.removeAt(removeIdx)
                        } else if (line.startsWith("+") && !line.startsWith("+++")) {
                            val prevPatchLine = if (i > 0) patchLines[i-1] else ""
                            if (prevPatchLine.startsWith("-")) {
                                val replaceIdx = lines.indexOf(prevPatchLine.removePrefix("-"))
                                if (replaceIdx >= 0) lines[replaceIdx] = line.removePrefix("+")
                            } else {
                                lines.add(line.removePrefix("+"))
                            }
                        }
                        i++
                    }
                    backupBeforeWrite(context, file)
                    file.writeText(lines.joinToString("\n"))
                    onResult("Patch applied successfully to $path")
                } catch (e: Exception) {
                    onResult("Error applying patch: ${e.message}")
                }
            }
            // --- Handlers for UI/sub-agent dependent tools (set by engine/panel) ---
            "question" -> {
                val q = args.getString("question")
                val opts = args.optJSONArray("options")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val handler = questionHandler
                if (handler == null) {
                    onResult("No UI available to display the question. Question was: $q\nContinue with your best assumption and state it.")
                } else {
                    Thread {
                        try { onResult(handler(q, opts)) }
                        catch (e: Exception) { onResult("Question failed: ${e.message}. Continue with your best assumption.") }
                    }.start()
                }
            }
            "task" -> {
                val prompt = args.getString("prompt")
                val ctx = args.optString("context", "")
                val handler = taskHandler
                if (handler == null) {
                    onResult("Sub-agent unavailable. Task was: $prompt")
                } else {
                    Thread {
                        try { onResult(handler(prompt, ctx)) }
                        catch (e: Exception) { onResult("Sub-agent failed: ${e.message}") }
                    }.start()
                }
            }
            "lsp" -> {
                val action = args.getString("action")
                when (action) {
                    "diagnostics" -> {
                        val act = context as? com.aide.mobilestudio.MainActivity
                        if (act == null) {
                            onResult("Diagnostics require the editor (MainActivity) to be open. Project files can still be analyzed with grep/glob.")
                        } else {
                            try {
                                kotlinx.coroutines.runBlocking { act.triggerLiveDiagnosticsAndWait()?.join() }
                                val count = act.getTotalErrorCount()
                                val text = act.getErrorsAsText()
                                onResult(if (count <= 0) "No diagnostics (0 errors)."
                                else "Total errors: $count\n$text")
                            } catch (e: Exception) {
                                onResult("Diagnostics failed: ${e.message}")
                            }
                        }
                    }
                    "definition", "references" -> {
                        val sym = args.optString("symbol").ifBlank { args.optString("query") }
                        if (sym.isBlank()) { onResult("Error: symbol/query required for '$action'"); return }
                        onResult(searchSymbol(context, sym, action == "definition"))
                    }
                    "symbols" -> {
                        val path = args.optString("file")
                        if (path.isBlank()) { onResult("Error: file required for 'symbols' action"); return }
                        checkPathAllowed(context, path)?.let { onResult(it); return }
                        val file = File(path)
                        if (!file.isFile) { onResult("Error: File not found: $path"); return }
                        val text = runCatching { file.readText() }.getOrElse { onResult("Error: ${it.message}"); return }
                        val declLine = Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|data|sealed|enum|annotation|static|final|virtual|extern|unsigned)\s+)*(?:fun|class|object|interface|val|var|def|function|struct|enum|typedef|namespace|sub|proc)\s+(\w+)""")
                        val out = text.lines().mapIndexedNotNull { i, l -> declLine.find(l)?.let { "${i + 1}: ${l.trim()}" } }
                        onResult(if (out.isEmpty()) "No symbols found in $path" else "Symbols in ${file.name}:\n${out.take(300).joinToString("\n")}")
                    }
                    else -> onResult("Error: Unknown lsp action: $action")
                }
            }
            "glob" -> {
                val pattern = args.getString("pattern")
                val rootPath = args.optString("path", currentProjectDir?.absolutePath ?: ".")
                checkPathAllowed(context, rootPath)?.let { onResult(it); return }
                val regex = try { Regex("^" + pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$") }
                catch (e: Exception) { onResult("Error: bad pattern: ${e.message}"); return }
                val root = File(rootPath)
                if (!root.exists()) { onResult("Error: Directory not found: $rootPath"); return }
                val matches = mutableListOf<String>()
                root.walkTopDown().maxDepth(10).forEach { f ->
                    if (matches.size >= 200) return@forEach
                    if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/build/") && regex.matches(f.name)) {
                        matches.add(f.absolutePath)
                    }
                }
                onResult(matches.joinToString("\n").ifEmpty { "No files match: $pattern" })
            }
            "grep" -> {
                val pattern = args.getString("pattern")
                val rootPath = args.optString("path", currentProjectDir?.absolutePath ?: ".")
                checkPathAllowed(context, rootPath)?.let { onResult(it); return }
                val include = args.optString("include", "")
                val regex = try { Regex(pattern) } catch (_: Exception) { Regex(Regex.escape(pattern)) }
                val includeRegex = if (include.isBlank()) null
                    else try { Regex("^" + include.replace(".", "\\.").replace("*", ".*").replace("?", ".") + "$") } catch (_: Exception) { null }
                val root = File(rootPath)
                if (!root.exists()) { onResult("Error: Directory not found: $rootPath"); return }
                val hits = mutableListOf<String>()
                root.walkTopDown().maxDepth(10).forEach { f ->
                    if (hits.size >= 200) return@forEach
                    if (!f.isFile || f.path.contains("/.git/") || f.path.contains("/build/")) return@forEach
                    if (includeRegex != null && !includeRegex.matches(f.name)) return@forEach
                    val lines = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    lines.lineSequence().forEachIndexed { idx, line ->
                        if (hits.size >= 200) return@forEach
                        if (regex.containsMatchIn(line)) hits.add("${f.absolutePath}:${idx + 1}: $line")
                    }
                }
                onResult(hits.joinToString("\n").ifEmpty { "No matches for: $pattern" })
            }
            "logcat_search" -> {
                val filter = args.optString("filter", "")
                val maxLines = args.optInt("maxLines", 200).coerceIn(10, 2000)
                try {
                    val result = ShellRunner.runSh(context, "logcat -d -t $maxLines 2>/dev/null", context.cacheDir, emptyMap(), 15_000L) { }
                    val lines = result.outputLines
                    val matched = if (filter.isBlank()) lines else {
                        val re = try { Regex(filter) } catch (_: Exception) { Regex(Regex.escape(filter)) }
                        lines.filter { re.containsMatchIn(it) }
                    }
                    val out = matched.takeLast(maxLines).joinToString("\n")
                    val head = "Logcat lines: ${matched.size} (filter: ${filter.ifBlank { "none" }})" +
                        if (matched.isEmpty() && filter.isNotBlank()) "\nTip: apps can only read their own logs on modern Android." else ""
                    onResult(if (out.isBlank()) head else "$head\n$out")
                } catch (e: Exception) {
                    onResult("logcat failed: ${e.message} (own-app logs only on Android 10+)")
                }
            }
            "edit_manifest" -> {
                val proj = currentProjectDir
                if (proj == null) { onResult("Error: No project open"); return }
                val candidates = listOf(
                    File(proj, "app/src/main/AndroidManifest.xml"),
                    File(proj, "src/main/AndroidManifest.xml"),
                    File(proj, "AndroidManifest.xml")
                )
                val manifest = candidates.firstOrNull { it.isFile }
                    ?: proj.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name == "AndroidManifest.xml" }
                if (manifest == null) { onResult("Error: AndroidManifest.xml not found in project"); return }
                val content = runCatching { manifest.readText() }.getOrElse { onResult("Error: ${it.message}"); return }
                val wantRead = args.optBoolean("read", false)
                val addPerm = args.optString("addPermission", "")
                val addAct = args.optString("addActivity", "")
                val insertXml = args.optString("insertXml", "")
                if (wantRead || (addPerm.isBlank() && addAct.isBlank() && insertXml.isBlank())) {
                    onResult("${manifest.absolutePath}\n\n$content")
                    return
                }
                var newContent = content
                val applied = mutableListOf<String>()
                if (addPerm.isNotBlank()) {
                    if (newContent.contains("android:name=\"$addPerm\"")) {
                        applied.add("permission already present: $addPerm")
                    } else {
                        val permXml = "    <uses-permission android:name=\"$addPerm\" />"
                        newContent = if (newContent.contains("</manifest>"))
                            newContent.replace("</manifest>", "$permXml\n</manifest>")
                        else newContent + "\n$permXml\n"
                        applied.add("added uses-permission: $addPerm")
                    }
                }
                if (addAct.isNotBlank()) {
                    if (newContent.contains("android:name=\"$addAct\"")) {
                        applied.add("activity already present: $addAct")
                    } else {
                        val actXml = "        <activity android:name=\"$addAct\" />"
                        newContent = if (newContent.contains("</application>"))
                            newContent.replace("</application>", "$actXml\n    </application>")
                        else { applied.add("ERROR: no <application> tag found for activity"); newContent }
                        if (newContent.contains(actXml)) applied.add("added activity: $addAct")
                    }
                }
                if (insertXml.isNotBlank()) {
                    if (newContent.contains("</application>")) {
                        newContent = newContent.replace("</application>", "$insertXml\n    </application>")
                        applied.add("inserted XML before </application>")
                    } else applied.add("ERROR: no <application> tag found for insertXml")
                }
                if (newContent != content) {
                    backupBeforeWrite(context, manifest)
                    manifest.writeText(newContent)
                }
                onResult("Manifest ${manifest.name}:\n${applied.joinToString("\n")}")
            }
            "find_resource" -> {
                val resName = args.getString("name")
                val resType = args.optString("type", "")
                val proj = currentProjectDir
                if (proj == null) { onResult("Error: No project open"); return }
                val hits = mutableListOf<String>()
                // res/ definitions
                val resDirs = listOf(File(proj, "app/src/main/res"), File(proj, "src/main/res"), File(proj, "res"))
                for (res in resDirs) {
                    if (!res.isDirectory) continue
                    res.walkTopDown().maxDepth(3).forEach { f ->
                        if (hits.size >= 100 || !f.isFile) return@forEach
                        if (f.extension == "xml") {
                            val lines = runCatching { f.readText() }.getOrNull() ?: return@forEach
                            lines.lineSequence().forEachIndexed { i, l ->
                                if (hits.size >= 100) return@forEach
                                if (l.contains("name=\"$resName\"")) hits.add("${f.absolutePath}:${i + 1}: ${l.trim()}")
                            }
                        } else if (f.nameWithoutExtension == resName && (resType.isBlank() || f.parentFile?.name?.startsWith(resType) == true)) {
                            hits.add(f.absolutePath)
                        }
                    }
                }
                // R.<type>.<name> usages in sources
                val rPattern = Regex("""\bR\.\w+\.$resName\b""")
                proj.walkTopDown().maxDepth(8).forEach { f ->
                    if (hits.size >= 100 || !f.isFile) return@forEach
                    if (f.extension !in setOf("kt", "java", "xml")) return@forEach
                    if (f.path.contains("/build/") || f.path.contains("/.git/")) return@forEach
                    val lines = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    lines.lineSequence().forEachIndexed { i, l ->
                        if (hits.size >= 100) return@forEach
                        if (rPattern.containsMatchIn(l)) hits.add("${f.absolutePath}:${i + 1}: ${l.trim()}")
                    }
                }
                onResult(hits.joinToString("\n").ifEmpty { "No resource found: $resName" })
            }
            "analyze_dependencies" -> {
                val proj = currentProjectDir
                if (proj == null) { onResult("Error: No project open"); return }
                val sb = StringBuilder("=== Dependency analysis ===\n")
                // settings.gradle modules
                val settings = listOf("settings.gradle.kts", "settings.gradle").mapNotNull { n -> File(proj, n).takeIf { it.isFile } }
                for (f in settings) {
                    val text = runCatching { f.readText() }.getOrElse { continue }
                    val rootName = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
                    val modules = Regex("""include\s*\(([^)]+)\)""").findAll(text).flatMap { m ->
                        Regex("""["']([^"']+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }.toList()
                    sb.append("[$f] rootProject=${rootName ?: "?"} modules=${modules.joinToString(", ").ifBlank { "(none)" }}\n")
                }
                // gradle files: extract dependency lines
                val gradleFiles = proj.walkTopDown().maxDepth(4)
                    .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") && !it.path.contains("/build/") }
                    .toList()
                val depLine = Regex("""^\s*(implementation|api|compileOnly|runtimeOnly|classpath|kapt|ksp|annotationProcessor|testImplementation|androidTestImplementation|alias)\s*\(.*""")
                for (f in gradleFiles) {
                    val rel = proj.toPath().relativize(f.toPath()).toString()
                    val deps = runCatching { f.readText() }.getOrElse { continue }
                        .lines().filter { depLine.containsMatchIn(it) }
                        .map { it.trim() }.distinct()
                    sb.append("\n[$rel] (${deps.size} dependency lines)\n")
                    deps.take(60).forEach { sb.append("  $it\n") }
                    if (deps.size > 60) sb.append("  ... +${deps.size - 60} more\n")
                }
                // version catalog
                val catalog = File(proj, "gradle/libs.versions.toml")
                if (catalog.isFile) {
                    val text = runCatching { catalog.readText() }.getOrElse { "" }
                    sb.append("\n[gradle/libs.versions.toml]\n")
                    var section = ""
                    text.lines().forEach { l ->
                        val t = l.trim()
                        if (t.startsWith("[")) section = t
                        else if (t.isNotBlank() && !t.startsWith("#") && (section.contains("libraries") || section.contains("plugins") || section.contains("versions"))) {
                            sb.append("  $t\n")
                        }
                    }
                }
                if (gradleFiles.isEmpty() && settings.isEmpty()) sb.append("(no gradle files found)\n")
                val out = sb.toString()
                onResult(if (out.length > 30000) out.take(30000) + "\n... (truncated)" else out)
            }
            "undo_last_edit" -> {
                val all = args.optBoolean("all", false)
                onResult(undoLast(context, all))
            }
            else -> onResult("Error: Unknown tool: $name")
        }
    }

    /** Grep-based symbol search: 'definition' looks for declarations, 'references' for word-boundary usages. */
    private fun searchSymbol(context: Context, symbol: String, definitionOnly: Boolean): String {
        val proj = currentProjectDir ?: return "Error: No project open"
        val escaped = Regex.escape(symbol)
        val declPatterns = listOf(
            Regex("""\b(?:fun|class|object|interface|val|var|def|function|struct|enum|typedef|namespace)\s+$escaped\b"""),
            Regex("""\b$escaped\s*\([^)]*\)\s*\{"""),                       // method definition (java/c/js)
            Regex("""(?:public|private|protected|static|\s)+[\w<>,\[\].?]+\s+$escaped\s*\("""),
            Regex("""#define\s+$escaped\b"""),
            Regex("""<item[^>]*name="$escaped\"""")                          // android resource item
        )
        val usage = Regex("""\b$symbol\b""")
        val hits = mutableListOf<String>()
        proj.walkTopDown().maxDepth(8).forEach { f ->
            if (hits.size >= 80) return@forEach
            if (!f.isFile || f.path.contains("/.git/") || f.path.contains("/build/")) return@forEach
            if (f.extension !in setOf("kt", "kts", "java", "c", "cpp", "h", "hpp", "cc", "js", "ts", "py", "sh", "xml", "gradle")) return@forEach
            val lines = runCatching { f.readText() }.getOrNull() ?: return@forEach
            lines.lineSequence().forEachIndexed { i, line ->
                if (hits.size >= 80) return@forEach
                val matched = if (definitionOnly) declPatterns.any { it.containsMatchIn(line) } else usage.containsMatchIn(line)
                if (matched && line.isNotBlank()) {
                    val kind = if (definitionOnly) "def" else "ref"
                    hits.add("$kind ${f.absolutePath}:${i + 1}: ${line.trim().take(160)}")
                }
            }
        }
        val label = if (definitionOnly) "Definitions" else "References"
        return if (hits.isEmpty()) "$label not found for: $symbol"
        else "$label of $symbol (${hits.size}):\n${hits.joinToString("\n")}"
    }

    private fun performWebSearch(query: String, numResults: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        val html = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val results = mutableListOf<String>()
        val resultPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""")
        val snippetPattern = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""")

        val links = resultPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in 0 until minOf(links.size, numResults)) {
            val link = links[i]
            val title = link.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val href = link.groupValues[1]
            val snippet = if (i < snippets.size) {
                snippets[i].groupValues[1].replace(Regex("<[^>]*>"), "").trim()
            } else ""
            results.add("${i + 1}. $title\n   $href\n   $snippet")
        }

        return if (results.isEmpty()) {
            "No results found for: $query"
        } else {
            "Search results for: $query\n\n${results.joinToString("\n\n")}"
        }
    }

    private fun performWebFetch(url: String, maxLength: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            return "Error: HTTP $responseCode from $url"
        }

        val contentType = conn.contentType ?: ""
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val text = if (contentType.contains("html")) {
            body.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } else {
            body
        }

        val truncated = if (text.length > maxLength) {
            text.take(maxLength) + "\n\n... (truncated at $maxLength chars, total: ${text.length})"
        } else {
            text
        }

        return "URL: $url\nContent-Type: $contentType\nLength: ${text.length} chars\n\n$truncated"
    }

    /** Cancel a running async command */
    fun cancelCommand(commandId: String): Boolean {
        val running = runningCommands[commandId] ?: return false
        running.process?.destroyForcibly()
        running.isRunning = false
        running.exitCode = -1
        synchronized(running.output) {
            running.output.add("--- CANCELLED ---")
        }
        return true
    }

    /** Get all running command IDs */
    fun getRunningCommandIds(): List<String> {
        return runningCommands.filter { it.value.isRunning }.keys.toList()
    }

    /** Cleanup old finished commands (older than 1 hour) */
    fun cleanupOldCommands() {
        val cutoff = System.currentTimeMillis() - 3600_000L
        val toRemove = runningCommands.filter { !it.value.isRunning && it.value.startTime < cutoff }.keys
        toRemove.forEach { runningCommands.remove(it) }
    }

    private fun searchCodebase(
        root: File,
        query: String,
        maxResults: Int,
        fileTypes: Array<String>?
    ): String {
        val keywords = query.lowercase().split(" ").filter { it.length > 2 }.distinct()
        if (keywords.isEmpty()) return "Query too short"

        val scoredFiles = mutableListOf<Pair<File, Int>>()
        val allowedExtensions = fileTypes?.toSet() ?: setOf("kt", "java", "xml", "gradle", "kts", "cpp", "c", "h", "hpp", "py", "js", "ts", "json", "md", "txt", "yaml", "yml", "properties", "pro", "cmake")

        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (allowedExtensions.any { file.extension == it } && !file.path.contains(".git") && !file.path.contains("build/")) {
                val content = runCatching { file.readText() }.getOrElse { "" }
                val lowerContent = content.lowercase()
                var score = 0
                for (kw in keywords) {
                    val count = lowerContent.split(kw).size - 1
                    score += count * 10
                    if (lowerContent.contains(" $kw ") || lowerContent.contains(" $kw.") || lowerContent.contains(" $kw(")) {
                        score += 20
                    }
                }
                if (score > 0) scoredFiles.add(file to score)
            }
        }

        if (scoredFiles.isEmpty()) return "No relevant files found for: $query"

        scoredFiles.sortByDescending { it.second }
        val topFiles = scoredFiles.take(maxResults)

        val sb = StringBuilder("Found ${topFiles.size} relevant files for \"$query\":\n\n")
        for ((file, score) in topFiles) {
            val relPath = root.toPath().relativize(file.toPath()).toString()
            val content = runCatching { file.readText() }.getOrElse { "" }
            val lines = content.lines().toList()
            var firstMatchLine = -1
            for ((idx, line) in lines.withIndex()) {
                if (keywords.any { line.lowercase().contains(it) }) {
                    firstMatchLine = idx
                    break
                }
            }
            sb.append("=== $relPath (score: $score) ===\n")
            if (firstMatchLine >= 0) {
                val start = maxOf(0, firstMatchLine - 3)
                val end = minOf(lines.size - 1, firstMatchLine + 10)
                for (i in start..end) {
                    val prefix = if (i == firstMatchLine) ">>> " else "    "
                    sb.append("$prefix${i + 1}: ${lines[i]}\n")
                }
            } else {
                lines.take(10).forEach { sb.append("    $it\n") }
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
