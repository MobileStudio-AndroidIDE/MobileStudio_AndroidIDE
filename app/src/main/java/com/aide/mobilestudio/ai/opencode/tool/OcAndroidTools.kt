package com.aide.mobilestudio.ai.opencode.tool

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.aide.mobilestudio.ShellEnvironment
import com.aide.mobilestudio.ShellRunner
import com.aide.mobilestudio.ai.GgufInfo
import com.aide.mobilestudio.ai.build.GradleOutput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Tools for the work this app is actually for: building an Android project on
 * the device, reading what the build and the local model runtime did, and
 * seeing which model file is installed.
 *
 * They run through the same [ShellRunner] and tool environment as `bash`, so
 * java/gradle/cmake resolve exactly as they do for a hand-typed command - a
 * second execution path would drift from the first one.
 *
 * None of them report success it did not observe: a missing Gradle wrapper, an
 * empty logcat or a model file without a GGUF header is stated as such.
 */

/** Lines returned when the model does not ask for a specific count. */
internal const val DEFAULT_TOOL_OUTPUT_LINES = 50

/**
 * Head-and-tail shaping shared by the shell-backed tools.
 *
 * The opening lines say what ran and the closing lines carry the failure, so
 * the middle is what gets dropped - keeping only the head hid the interesting
 * half of every long build log. The model can ask for more with `maxLines`.
 */
internal fun shapeOutput(lines: List<String>, maxLines: Int, maxBytes: Int = 48 * 1024): Pair<String, Boolean> {
    var truncated = false
    var body = if (lines.size > maxLines) {
        val head = maxLines / 2
        val tail = maxLines - head
        truncated = true
        (lines.take(head) +
            "… (${lines.size - maxLines} lines omitted - rerun with maxLines=$maxLines or higher to see more) …" +
            lines.takeLast(tail)).joinToString("\n")
    } else {
        lines.joinToString("\n")
    }
    if (body.toByteArray().size > maxBytes) {
        body = String(body.toByteArray(), 0, maxBytes, Charsets.UTF_8).trimEnd('\uFFFD')
        truncated = true
    }
    return body to truncated
}

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

internal fun shellEnv(ctx: ToolContext, cwd: File): Map<String, String> =
    ShellEnvironment.buildToolEnvironment(ctx.androidContext, cwd)

// ---------------------------------------------------------------- gradle

/**
 * Gradle, run through the project's own wrapper.
 *
 * The wrapper decides the Gradle version for the project, so it is used
 * whenever it exists; invoking a global `gradle` instead would build the
 * project with whatever versions happen to be installed on the device.
 *
 * The result is shaped for a model that has to act on it: the failed task,
 * the compiler errors with their file and line, and a bounded excerpt of the
 * log. "BUILD FAILED" alone cannot be acted on.
 */
class GradleBuildTool : OcTool {
    override val name = "gradle_build"

    override fun description(ctx: ToolContext): String =
        "Runs a Gradle task for the open project with its own wrapper (default `assembleDebug`) and returns " +
            "the failed task, the compiler errors with file and line, and a bounded log excerpt. " +
            "Builds on this device can take minutes; set `task` for another task such as `test` or `clean`."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("task", JSONObject().put("type", "string").put(
                "description",
                "Gradle task(s) to run, space separated. Defaults to assembleDebug.",
            ))
            put("args", JSONObject().put("type", "string").put(
                "description", "Extra Gradle arguments, e.g. --stacktrace --offline.",
            ))
            put("timeout", JSONObject().put("type", "integer").put(
                "description",
                "Timeout in milliseconds. Defaults to ${DEFAULT_TIMEOUT_MS} and may not exceed ${MAX_TIMEOUT_MS}.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description", "Log lines to return. Defaults to $DEFAULT_TOOL_OUTPUT_LINES (head and tail are kept).",
            ))
        })
    }

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.BUILD

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val task = args.optString("task", "assembleDebug").trim()
        if (task.isEmpty()) return ToolResult("Error: task is empty", isError = true)
        val extra = args.optString("args", "").trim()
        val cwd = ctx.worktree
        if (!cwd.isDirectory) {
            return ToolResult("Error: workspace directory does not exist: ${cwd.absolutePath}", isError = true)
        }

        val wrapper = File(cwd, "gradlew")
        val hasWrapper = wrapper.isFile
        // `sh gradlew` rather than `./gradlew`: the checked-out file may not
        // carry the executable bit on this filesystem.
        val invocation = if (hasWrapper) "sh ./gradlew" else "gradle"
        val command = listOf(invocation, task, extra).filter { it.isNotEmpty() }.joinToString(" ")

        val timeout = args.optLong("timeout", DEFAULT_TIMEOUT_MS).coerceIn(1L, MAX_TIMEOUT_MS)
        val lines = mutableListOf<String>()
        val started = System.currentTimeMillis()
        val result = ShellRunner.runSh(ctx.androidContext, command, cwd, shellEnv(ctx, cwd), timeout) { line ->
            lines.add(line)
            ctx.onOutputLine?.invoke(line)
        }
        val durationMs = System.currentTimeMillis() - started
        val exit = result?.exitCode ?: -1

        // Shared with the Build screen so the model and the user are handed the
        // same failures from the same log.
        val failedTasks = GradleOutput.failedTasks(lines)
        val errors = GradleOutput.diagnostics(lines, limit = 15)

        val report = StringBuilder()
        report.append(if (exit == 0) "GRADLE BUILD SUCCEEDED" else "GRADLE BUILD FAILED").append('\n')
        report.append("command: ").append(command).append('\n')
        report.append("workdir: ").append(cwd.absolutePath).append('\n')
        report.append("wrapper: ").append(if (hasWrapper) "project gradlew" else "no gradlew in project - used `gradle` from PATH")
            .append('\n')
        report.append("exit: ").append(exit)
            .append("   duration: ").append(String.format(Locale.US, "%.1fs", durationMs / 1000.0)).append('\n')
        if (result?.timedOut == true) {
            report.append("timed out: the build had not finished when the timeout expired\n")
        }
        if (failedTasks.isNotEmpty()) {
            report.append("\nfailed tasks:\n")
            failedTasks.take(10).forEach { report.append("  ").append(it).append('\n') }
        }
        if (errors.isNotEmpty()) {
            report.append("\ndiagnostics (a sample, the full log is below):\n")
            errors.forEach { report.append("  ").append(it.trim()).append('\n') }
        }

        val maxLines = args.optInt("maxLines", DEFAULT_TOOL_OUTPUT_LINES).coerceIn(1, 2000)
        val (body, truncated) = shapeOutput(lines, maxLines)
        report.append("\noutput")
        if (truncated) report.append(" (${lines.size} lines total, first and last kept)")
        report.append(":\n").append(body.ifBlank { "(no output)" }).append('\n')

        if (exit != 0) {
            report.append("\nnext: read the file and line named above, fix it, then run gradle_build again to verify.")
        }
        return ToolResult(report.toString(), isError = exit != 0)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1_000
        const val MAX_TIMEOUT_MS = 15L * 60 * 1_000
    }
}

// ------------------------------------------------------- project diagnostics

/**
 * The shape of the project, so a plan starts from the real tree instead of an
 * assumption about it.
 */
class ProjectDiagnosticsTool : OcTool {
    override val name = "project_diagnostics"

    override fun description(ctx: ToolContext): String =
        "Summarises the workspace: top-level entries, which build system it uses (Gradle/Android, Gradle/JVM, " +
            "Python, Node, CMake, Windows .exe), the key build files, and source file counts."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("maxEntries", JSONObject().put("type", "integer").put(
                "description", "Top-level entries to list. Defaults to 40.",
            ))
        })
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val root = ctx.worktree
        if (!root.isDirectory) {
            return ToolResult("The workspace is empty or missing: ${root.absolutePath}")
        }
        val entries = root.listFiles()?.sortedBy { it.name } ?: emptyList()
        val maxEntries = args.optInt("maxEntries", 40).coerceIn(1, 200)

        val markers = listOf(
            "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle",
            "gradle.properties", "gradlew", "AndroidManifest.xml", "app/src/main/AndroidManifest.xml",
            "CMakeLists.txt", "requirements.txt", "package.json", "cargo.toml", "Makefile",
            "extension.json", "local.properties", "pubspec.yaml",
        )
        val present = markers.filter { File(root, it).isFile }
        val kind = when {
            present.any { it.endsWith("AndroidManifest.xml") } || File(root, "app/build.gradle.kts").isFile ||
                File(root, "app/build.gradle").isFile -> "Android app (Gradle)"
            present.contains("gradlew") || present.any { it.startsWith("settings.gradle") } -> "Gradle JVM project"
            present.contains("requirements.txt") -> "Python"
            present.contains("package.json") -> "Node"
            present.contains("CMakeLists.txt") -> "CMake / C++"
            else -> "unknown (no standard build file at the root)"
        }

        val sourceCounts = mutableMapOf<String, Int>()
        val exts = setOf("kt", "java", "c", "cpp", "h", "hpp", "py", "js", "ts", "lua", "xml", "gradle", "kts", "rs", "go", "swift", "m", "mm", "sh")
        var visited = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || visited > 4000) return
            val children = dir.listFiles() ?: return
            for (child in children) {
                val n = child.name
                if (n.startsWith(".") || n == "build" || n == "node_modules" || n == ".gradle") continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else {
                    visited++
                    val ext = n.substringAfterLast('.', "").lowercase()
                    if (ext in exts) sourceCounts[ext] = (sourceCounts[ext] ?: 0) + 1
                }
            }
        }
        walk(root, 0)

        val sb = StringBuilder()
        sb.append("project: ").append(root.absolutePath).append('\n')
        sb.append("detected: ").append(kind).append('\n')
        sb.append("build files: ").append(if (present.isEmpty()) "none found" else present.joinToString(", ")).append('\n')
        if (sourceCounts.isNotEmpty()) {
            sb.append("sources: ").append(
                sourceCounts.entries.sortedByDescending { it.value }.take(10)
                    .joinToString(", ") { "${it.key}=${it.value}" },
            ).append(" (${visited} files scanned)\n")
        }
        sb.append("\ntop level:\n")
        entries.take(maxEntries).forEach { f ->
            val size = if (f.isDirectory) "<dir>" else humanBytes(f.length())
            sb.append("  ").append(if (f.isDirectory) "d " else "f ").append(f.name).append("  ").append(size).append('\n')
        }
        if (entries.size > maxEntries) sb.append("  … ${entries.size - maxEntries} more\n")
        return ToolResult(sb.toString())
    }
}

/**
 * Logcat, filtered the way an agent needs it.
 *
 * A model cannot read 4000 unfiltered lines, and the interesting ones are the
 * warnings and errors around the moment something failed. The level and tag
 * filters happen here rather than in logcat's own arguments so that a tag with
 * a regex character in it cannot turn into a broken command.
 */
class AndroidLogcatTool : OcTool {
    override val name = "android_logcat"

    override fun description(ctx: ToolContext): String =
        "Reads recent logcat lines, filtered by tag, minimum level (default W) and/or text. With `around` it " +
            "returns the lines before and after the last match instead - the context an error needs."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("tag", JSONObject().put("type", "string").put(
                "description", "Case-insensitive substring the log tag must contain.",
            ))
            put("level", JSONObject().put("type", "string").put(
                "description", "Minimum level: V, D, I, W (default), E or F.",
            ))
            put("text", JSONObject().put("type", "string").put(
                "description", "Case-insensitive substring the message must contain.",
            ))
            put("around", JSONObject().put("type", "integer").put(
                "description", "Return this many lines before and after the last match instead of the matches.",
            ))
            put("lines", JSONObject().put("type", "integer").put(
                "description", "How many recent log lines to scan. Defaults to 500.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description", "Lines to return. Defaults to $DEFAULT_TOOL_OUTPUT_LINES (head and tail are kept).",
            ))
        })
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val scan = args.optInt("lines", 500).coerceIn(50, 5000)
        val tagFilter = args.optString("tag", "").trim().lowercase()
        val textFilter = args.optString("text", "").trim().lowercase()
        val around = args.optInt("around", 0).coerceIn(0, 200)
        val minLevel = when (args.optString("level", "W").trim().uppercase().firstOrNull()) {
            'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F' -> 5; else -> 3
        }

        val raw = mutableListOf<LogLine>()
        ShellRunner.runSh(ctx.androidContext, "logcat -d -v brief -t $scan", ctx.worktree, shellEnv(ctx, ctx.worktree), 20_000) { line ->
            parseBriefLogLine(line)?.let { raw.add(it) }
        }
        if (raw.isEmpty()) {
            return ToolResult(
                "logcat returned nothing that looked like a log line (the buffer may have rotated, or this " +
                    "process may not be allowed to read logs). Nothing was filtered out - there was no input.",
            )
        }

        val matches = raw.filter { line ->
            levelRank(line.level) >= minLevel &&
                (tagFilter.isEmpty() || line.tag.lowercase().contains(tagFilter)) &&
                (textFilter.isEmpty() || line.message.lowercase().contains(textFilter))
        }
        if (matches.isEmpty()) {
            return ToolResult(
                "Scanned ${raw.size} log lines: none matched (tag='$tagFilter', level>=${'V'}${'D'}${'I'}${'W'}${'E'}${'F'}[$minLevel], " +
                    "text='$textFilter'). Nothing was hidden by a limit - try a lower level or a wider `lines`.",
            )
        }

        val selected = if (around > 0) {
            // Centre on the last match: the failure is usually the last thing
            // that happened, and the lines before it say what led there.
            val index = raw.indexOfLast { it.message.lowercase().contains(textFilter.ifEmpty { "" }) &&
                levelRank(it.level) >= minLevel }
            val centre = if (index >= 0) index else raw.indexOf(matches.first())
            raw.subList((centre - around).coerceAtLeast(0), (centre + around + 1).coerceAtMost(raw.size))
                .map { it.raw }
        } else {
            matches.map { it.raw }
        }

        val maxLines = args.optInt("maxLines", DEFAULT_TOOL_OUTPUT_LINES).coerceIn(1, 2000)
        val (body, truncated) = shapeOutput(selected, maxLines)
        val header = buildString {
            append(if (around > 0) "${selected.size} lines around the last match" else "${matches.size} matching lines")
            append(" out of ${raw.size} scanned")
            if (truncated) append(" (head and tail kept, $maxLines shown - ask with maxLines for more)")
        }
        return ToolResult("$header:\n$body")
    }
}

private data class LogLine(val level: Char, val tag: String, val message: String, val raw: String)

private fun levelRank(level: Char): Int = when (level.uppercaseChar()) {
    'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F' -> 5; else -> 0
}

/** `-v brief` writes `L/Tag(  pid): message`; anything else is not a log line. */
private fun parseBriefLogLine(line: String): LogLine? {
    if (line.length < 6) return null
    val level = line[0]
    if (level !in "VDIWEF") return null
    if (line.getOrNull(1) != '/') return null
    val tag = line.substringAfter('/').substringBefore('(').trim()
    val message = line.substringAfter("): ", "").ifEmpty { line.substringAfter(':', "") }
    return LogLine(level, tag, message, line)
}

// ---------------------------------------------------------------- llama

/**
 * GGUF files installed for the local model, and what their headers declare.
 *
 * The header is what the engine itself reads when it decides a context window
 * and a GPU offload, so the numbers here are the numbers in play.
 */
class LlamaModelInfoTool : OcTool {
    override val name = "llama_model_info"

    override fun description(ctx: ToolContext): String =
        "Lists the GGUF models installed for local inference and reads a file's GGUF header (architecture, " +
            "layer count, context length, head counts). Pass `path` for a specific .gguf file."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().put("type", "string").put(
                "description", "Optional path to a .gguf file. Defaults to every model in the app's models directory.",
            ))
        })
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val modelsDir = File(ctx.androidContext.filesDir, "AI/models")
        val requested = args.optString("path", "").trim()

        val files = if (requested.isNotEmpty()) {
            val f = File(requested).let { if (it.isAbsolute) it else File(ctx.worktree, requested) }
            if (!f.isFile) return ToolResult("No such model file: ${f.absolutePath}", isError = true)
            listOf(f)
        } else {
            modelsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".gguf") }?.sortedBy { it.name }
                ?: emptyList()
        }

        val bridge = com.aide.mobilestudio.ai.api.LlamaRuntimeBridge.isBridgeAvailable()
        val runtime = runCatching { com.aide.mobilestudio.ai.LlamaRuntimeManager.get().isInstalled() }.getOrDefault(false)
        val sb = StringBuilder()
        sb.append("models directory: ").append(modelsDir.absolutePath).append('\n')
        sb.append("runtime: ").append(if (runtime) "installed" else "not installed")
            .append("   bridge: ").append(if (bridge) "available" else "unavailable").append('\n')
        if (files.isEmpty()) {
            sb.append("\nno .gguf files installed. The AI workspace's model tab downloads one; " +
                "a file can also be copied into ").append(modelsDir.absolutePath).append('\n')
            return ToolResult(sb.toString())
        }

        for (file in files) {
            sb.append('\n').append(file.name).append("  ").append(humanBytes(file.length())).append('\n')
            sb.append("  path: ").append(file.absolutePath).append('\n')
            val info = runCatching { GgufInfo.read(file) }.getOrNull()
            if (info == null) {
                sb.append("  GGUF header: unreadable (not a GGUF file, or a header this reader does not know)\n")
                continue
            }
            sb.append("  architecture: ").append(info.architecture ?: "undeclared").append('\n')
            if (info.generalName != null) sb.append("  name: ").append(info.generalName).append('\n')
            sb.append("  layers: ").append(info.nLayer ?: "undeclared").append('\n')
            sb.append("  context length: ").append(info.contextLength ?: "undeclared").append('\n')
            info.headCount?.let { sb.append("  heads: ").append(it) }
            info.headCountKv?.let { sb.append("  kv heads: ").append(it) }
            if (info.headCount != null || info.headCountKv != null) sb.append('\n')
            info.embeddingLength?.let { sb.append("  embedding: ").append(it).append('\n') }
            // Split models are loaded by naming any shard; llama.cpp resolves the
            // rest from the same directory. Say so instead of failing silently.
            val split = Regex("""^(.*)-(\d{5})-of-(\d{5})\.gguf$""").find(file.name)
            if (split != null) {
                sb.append("  split model: shard ").append(split.groupValues[2]).append(" of ")
                    .append(split.groupValues[3].trimStart('0').ifEmpty { "0" })
                    .append(" - every shard must sit in this directory\n")
            }
        }
        return ToolResult(sb.toString())
    }
}

/**
 * What device this is, since the answer changes what can be built and run.
 */
class DeviceInfoTool : OcTool {
    override val name = "device_info"

    override fun description(ctx: ToolContext): String =
        "Reports the device: model, Android version and API level, supported ABIs, CPU cores, memory, and free " +
            "storage. Use it before choosing build options or a model size."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val sb = StringBuilder()
        sb.append("manufacturer: ").append(Build.MANUFACTURER).append('\n')
        sb.append("model: ").append(Build.MODEL).append("  device: ").append(Build.DEVICE).append('\n')
        sb.append("android: ").append(Build.VERSION.RELEASE).append("  api: ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("abis: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n')

        runCatching {
            val am = ctx.androidContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("memory: ").append(humanBytes(mi.totalMem)).append(" total, ")
                .append(humanBytes(mi.availMem)).append(" available, lowMemory=")
                .append(mi.lowMemory).append('\n')
        }
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            sb.append("storage: ").append(humanBytes(stat.availableBytes)).append(" free of ")
                .append(humanBytes(stat.totalBytes)).append('\n')
        }
        runCatching {
            val stat = StatFs(ctx.androidContext.filesDir.path)
            sb.append("app storage: ").append(humanBytes(stat.availableBytes)).append(" free\n")
        }
        sb.append("runtime backend: ").append(
            if (com.aide.mobilestudio.ai.api.LlamaRuntimeBridge.isBridgeAvailable()) {
                "llama-runtime bridge present (Vulkan when the downloaded runtime is installed)"
            } else {
                "bridge unavailable"
            },
        ).append('\n')
        return ToolResult(sb.toString())
    }
}

/**
 * What the local model runtime logged.
 *
 * This is the log the engine's own JNI bridge and llama.cpp write to, so it
 * carries the layer offload, the backend choice, the prompt decode timing and
 * the failures - the difference between "the model is slow" and "the model
 * never reached the GPU".
 */
class LlamaRuntimeLogsTool : OcTool {
    override val name = "llama_runtime_logs"

    override fun description(ctx: ToolContext): String =
        "Returns recent log lines from the local model runtime (LlamaRuntimeJNI, LlamaRuntimeBridge, " +
            "OcLocalLlamaProvider, ggml, Vulkan): layer offload, backend, decode timing and load failures."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("lines", JSONObject().put("type", "integer").put(
                "description", "Lines of log to scan. Defaults to 400.",
            ))
            put("filter", JSONObject().put("type", "string").put(
                "description", "Optional extra case-insensitive substring every returned line must contain.",
            ))
            put("maxLines", JSONObject().put("type", "integer").put(
                "description", "Lines to return. Defaults to $DEFAULT_TOOL_OUTPUT_LINES (head and tail are kept).",
            ))
        })
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val scan = args.optInt("lines", 400).coerceIn(50, 5000)
        val filter = args.optString("filter", "").trim().lowercase()
        val tags = listOf(
            "llamaruntimejni", "llamaruntimebridge", "oclocalllamaprovider", "llamaandroid",
            "ggml", "vulkan", "llama_", "llama.", "jni",
        )
        val lines = mutableListOf<String>()
        val timed = ShellRunner.runSh(ctx.androidContext, "logcat -d -t $scan", ctx.worktree, shellEnv(ctx, ctx.worktree), 20_000) {
            lines.add(it)
        }
        if (lines.isEmpty()) {
            return ToolResult(
                "logcat returned nothing (the device may not allow reading logs from this process, " +
                    "or the buffer rotated). No lines were produced - this is not a report that the runtime is healthy.",
            )
        }
        val matches = lines.filter { line ->
            val lower = line.lowercase()
            tags.any { lower.contains(it) } && (filter.isEmpty() || lower.contains(filter))
        }
        if (matches.isEmpty()) {
            return ToolResult("Scanned the last ${lines.size} log lines: no runtime lines matched (filter='$filter'). Try a larger `lines`.")
        }
        val maxLines = args.optInt("maxLines", DEFAULT_TOOL_OUTPUT_LINES).coerceIn(1, 2000)
        val (body, truncated) = shapeOutput(matches, maxLines)
        val header = "${matches.size} runtime lines matched out of ${lines.size} scanned" +
            if (truncated) " (head and tail kept, ${maxLines} shown)" else ""
        return ToolResult("$header:\n$body")
    }
}
