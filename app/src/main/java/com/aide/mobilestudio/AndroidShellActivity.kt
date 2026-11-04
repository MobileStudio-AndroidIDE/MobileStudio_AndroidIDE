package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.ui.ide.IdeAction
import com.aide.mobilestudio.ui.ide.IdeColors
import com.aide.mobilestudio.ui.ide.IdeDimens
import com.aide.mobilestudio.ui.ide.IdeDot
import com.aide.mobilestudio.ui.ide.IdeDrawerMenu
import com.aide.mobilestudio.ui.ide.IdeMainLayout
import com.aide.mobilestudio.ui.ide.IdeMenuGroup
import com.aide.mobilestudio.ui.ide.IdeMenuItem
import com.aide.mobilestudio.ui.ide.IdeMono
import com.aide.mobilestudio.ui.ide.IdeStatusStrip
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidShellActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_CWD = "cwd"
        private const val MAX_LINES = 2_000

        fun start(context: Context, cwd: File?) {
            context.startActivity(
                Intent(context, AndroidShellActivity::class.java)
                    .putExtra(EXTRA_CWD, cwd?.absolutePath)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lines = mutableStateListOf<TerminalLine>()
    private var cwdState by mutableStateOf<File?>(null)
    private var inputState by mutableStateOf("")
    private var runningState by mutableStateOf(false)
    private var activeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cwdState = resolveInitialCwd()
        appendLine(TerminalKind.Info, "MobileStudio AndroidShell")
        appendLine(TerminalKind.Info, "cwd=${cwdState?.absolutePath}")
        setContent {
            AndroidShellScreen(
                cwd = cwdState?.absolutePath.orEmpty(),
                input = inputState,
                running = runningState,
                lines = lines,
                onInputChange = { inputState = it },
                onRun = { runInput(asAi = false) },
                onAskAi = { runInput(asAi = true) },
                onStop = { stopActiveJob() },
                onClear = {
                    lines.clear()
                    appendLine(TerminalKind.Info, "cwd=${cwdState?.absolutePath}")
                },
                onFinish = { finish() }
            )
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun runInput(asAi: Boolean) {
        val raw = inputState.trim()
        if (raw.isBlank() || runningState) return
        inputState = ""
        if (asAi || raw.startsWith("ai ", ignoreCase = true)) {
            appendLine(TerminalKind.Info, "AI is disabled. Use OpenCode (opencode) CLI instead.")
            return
        }
        appendLine(TerminalKind.Command, "$ $raw")
        when {
            raw == "clear" -> {
                lines.clear()
                appendLine(TerminalKind.Info, "cwd=${cwdState?.absolutePath}")
            }
            raw == "pwd" -> appendLine(TerminalKind.Output, cwdState?.absolutePath.orEmpty())
            raw.startsWith("cd ") -> changeDirectory(raw.removePrefix("cd ").trim())
            // Java/Kotlin 스크립트 실행 (python 스타일)
            matchesKotlinRun(raw) -> runKotlinScript(raw)
            matchesJavaRun(raw)   -> runJavaScript(raw)
            matchesLuaRun(raw)    -> runLuaScript(raw)
            // 그 외 모든 명령어는 쉘에서 실행
            else -> runShellCommand(raw)
        }
    }

    /** Returns true for: `kotlin foo.kt [args]` or `kotlinc foo.kt [args]` */
    private fun matchesKotlinRun(raw: String): Boolean {
        val tok = raw.trim().split("\\s+".toRegex(), limit = 2)
        val cmd = tok.firstOrNull()?.lowercase() ?: return false
        if (cmd != "kotlin" && cmd != "kotlinc") return false
        val rest = tok.getOrNull(1) ?: return false
        val file = rest.trim().split("\\s+".toRegex()).firstOrNull() ?: return false
        return file.endsWith(".kt", ignoreCase = true) ||
                file.endsWith(".kts", ignoreCase = true)
    }

    /** Returns true for: `java Foo.java [args]` */
    private fun matchesJavaRun(raw: String): Boolean {
        val tok = raw.trim().split("\\s+".toRegex(), limit = 2)
        if (tok.firstOrNull()?.lowercase() != "java") return false
        val rest = tok.getOrNull(1) ?: return false
        val file = rest.trim().split("\\s+".toRegex()).firstOrNull() ?: return false
        return file.endsWith(".java", ignoreCase = true)
    }

    /**
     * Kotlin script runner:
     *  .kts  → kotlinc -script foo.kts [args]           (direct interpreter mode)
     *  .kt   → kotlinc foo.kt -include-runtime -d out.jar && java -jar out.jar [args]
     *
     * exec_redirect.so is set as LD_PRELOAD in buildShellEnv; java/kotlinc in
     * filesDir execute via memfd_create even on noexec partitions.
     */
    private fun runKotlinScript(raw: String) {
        val parts     = raw.trim().split("\\s+".toRegex())
        val filePart  = parts.drop(1).firstOrNull() ?: return
        val extraArgs = parts.drop(2).joinToString(" ")

        val cwd      = cwdState ?: resolveInitialCwd()
        val fileObj  = if (filePart.startsWith("/")) File(filePart) else File(cwd, filePart)
        val tmp      = File(cacheDir, "ktscript").apply { mkdirs() }
        val env      = buildShellEnv(cwd)
        val kotlincBin = findKotlinc(env["PATH"])

        if (kotlincBin == null) {
            appendLine(TerminalKind.Error,
                "kotlinc not found. Install via Build Tools > Toolchain, or place kotlinc in PATH.")
            return
        }

        val cmd = if (fileObj.extension.equals("kts", ignoreCase = true)) {
            // .kts ? direct script mode
            buildString {
                append(quote(kotlincBin)); append(" -script ")
                append(quote(fileObj.absolutePath))
                if (extraArgs.isNotBlank()) { append(' '); append(extraArgs) }
            }
        } else {
            // .kt ? compile-then-run
            val jar = File(tmp, "${fileObj.nameWithoutExtension}.jar")
            buildString {
                append(quote(kotlincBin)); append(' ')
                append(quote(fileObj.absolutePath))
                append(" -include-runtime -d ")
                append(quote(jar.absolutePath))
                append(" && java -jar ")
                append(quote(jar.absolutePath))
                if (extraArgs.isNotBlank()) { append(' '); append(extraArgs) }
            }
        }

        appendLine(TerminalKind.Info, "> kotlin-run: $cmd")
        runShellCommand(cmd)
    }

    /**
     * Java source runner:
     *  javac Foo.java -d /cache/jscript && java -cp /cache/jscript Foo [args]
     *
     * exec_redirect.so handles noexec for javac/java binaries.
     */
    private fun runJavaScript(raw: String) {
        val parts     = raw.trim().split("\\s+".toRegex())
        val filePart  = parts.drop(1).firstOrNull() ?: return
        val extraArgs = parts.drop(2).joinToString(" ")

        val cwd      = cwdState ?: resolveInitialCwd()
        val fileObj  = if (filePart.startsWith("/")) File(filePart) else File(cwd, filePart)
        val tmp      = File(cacheDir, "jscript").apply { mkdirs() }
        val className = fileObj.nameWithoutExtension

        val cmd = buildString {
            append("javac ")
            append(quote(fileObj.absolutePath))
            append(" -d ")
            append(quote(tmp.absolutePath))
            append(" && java -cp ")
            append(quote(tmp.absolutePath))
            append(' ')
            append(className)
            if (extraArgs.isNotBlank()) { append(' '); append(extraArgs) }
        }

        appendLine(TerminalKind.Info, "> java-run: $cmd")
        runShellCommand(cmd)
    }

    /** Returns true only when the native Lua 5.4 interpreter is unavailable. */
    private fun matchesLuaRun(raw: String): Boolean {
        val tok = raw.trim().split("\\s+".toRegex(), limit = 2)
        val cmd = tok.firstOrNull()?.lowercase() ?: return false
        if (cmd != "lua") return false
        // Prefer the packaged native interpreter: it matches `luac`'s 5.4
        // bytecode and handles every option (-e, stdin, REPL) through the
        // shell. The in-process LuaJ runner is only a fallback for builds
        // where liblua.so is missing.
        val native = runCatching { File(LuaTools.sync(this), "lua") }.getOrNull()
        if (native?.exists() == true) return false
        return true
    }

    /**
     * Lua script runner using LuaJ (in-process, no external binary needed).
     * Supports: `lua script.lua [args]`
     * Also: `lua -e "code"` for inline code.
     * Captures print() output and displays it.
     */
    private fun runLuaScript(raw: String) {
        val parts = raw.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            appendLine(TerminalKind.Error, "Usage: lua <file.lua> [args] or lua -e \"code\"")
            return
        }
        val cwd = cwdState ?: resolveInitialCwd()
        scope.launch(Dispatchers.IO) {
            try {
                val globals = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals()

                // Capture print() output in buffer (can't use withContext in non-suspend invoke)
                val outputLines = mutableListOf<String>()
                globals["print"] = object : org.luaj.vm2.lib.VarArgFunction() {
                    override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.LuaValue {
                        val sb = StringBuilder()
                        for (i in 1..args.narg()) {
                            if (i > 1) sb.append('\t')
                            sb.append(args.checkjstring(i))
                        }
                        outputLines.add(sb.toString())
                        return org.luaj.vm2.LuaValue.NIL
                    }
                }

                val argTable = org.luaj.vm2.LuaValue.tableOf()
                val isInline = parts[1] == "-e"
                val luaCode: String
                val scriptArgs: List<String>

                if (isInline) {
                    luaCode = parts.drop(2).joinToString(" ")
                    scriptArgs = emptyList()
                } else {
                    val filePart = parts[1]
                    val fileObj = if (filePart.startsWith("/")) File(filePart) else File(cwd, filePart)
                    if (!fileObj.exists()) {
                        withContext(Dispatchers.Main) {
                            appendLine(TerminalKind.Error, "File not found: ${fileObj.absolutePath}")
                        }
                        return@launch
                    }
                    luaCode = fileObj.readText()
                    scriptArgs = parts.drop(2)
                }

                for ((i, arg) in scriptArgs.withIndex()) {
                    argTable.set(i + 1, arg)
                }
                globals["arg"] = argTable

                withContext(Dispatchers.Main) {
                    appendLine(TerminalKind.Info, "> lua ${if (isInline) "-e ..." else parts[1]}")
                }

                val chunk = globals.load(luaCode)
                chunk.call()

                // Post all captured output
                for (line in outputLines) {
                    withContext(Dispatchers.Main) {
                        appendLine(TerminalKind.Output, line)
                    }
                }

                withContext(Dispatchers.Main) {
                    appendLine(TerminalKind.Success, "[lua completed]")
                }
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                withContext(Dispatchers.Main) {
                    appendLine(TerminalKind.Error, "Lua error: $msg")
                }
            }
        }
    }

    /** Locates a usable kotlinc binary by searching PATH then common toolchain dirs. */
    private fun findKotlinc(path: String?): String? {
        val names = listOf("kotlinc", "kotlinc-jvm", "kotlin")
        for (dir in (path ?: "").split(":").filter { it.isNotBlank() }) {
            for (name in names) {
                val f = File(dir, name)
                if (f.canExecute()) return f.absolutePath
            }
        }
        // Fallback: look in common toolchain location
        val toolchainBin = File(filesDir, "toolchain/kotlin/bin")
        for (name in names) {
            val f = File(toolchainBin, name)
            if (f.isFile) return f.absolutePath    // exec_redirect handles noexec
        }
        return null
    }

    private fun runShellCommand(
        command: String,
        onDone: (() -> Unit)? = null
    ) {
        val current = cwdState ?: resolveInitialCwd()
        activeJob = scope.launch {
            runningState = true
            val result = withContext(Dispatchers.IO) {
                ShellRunner.runSh(
                    context = this@AndroidShellActivity,
                    command = command,
                    cwd = current,
                    env = buildShellEnv(current),
                    timeoutMs = 0L
                ) { line ->
                    runOnUiThread { appendLine(classifyLine(line), stripAnsiLine(line)) }
                }
            }
            appendLine(if (result.exitCode == 0) TerminalKind.Success else TerminalKind.Error, "exit=${result.exitCode} runner=${result.strategy}")
            if (result.error.isNotBlank()) appendLine(TerminalKind.Error, "error=${result.error}")
            runningState = false
            activeJob = null
            onDone?.invoke()
        }
    }



    private fun changeDirectory(rawPath: String) {
        val target = when {
            rawPath.isBlank() || rawPath == "~" -> filesDir
            File(rawPath).isAbsolute -> File(rawPath)
            else -> File(cwdState ?: filesDir, rawPath)
        }
        val canonical = runCatching { target.canonicalFile }.getOrDefault(target)
        if (canonical.isDirectory) {
            cwdState = canonical
            appendLine(TerminalKind.Info, "cwd=${canonical.absolutePath}")
        } else {
            appendLine(TerminalKind.Error, "cd: no such directory: $rawPath")
        }
    }

    private fun stopActiveJob() {
        activeJob?.cancel()
        activeJob = null
        runningState = false
        appendLine(TerminalKind.Error, "^C")
    }

    private fun appendLine(kind: TerminalKind, text: String) {
        lines += TerminalLine(kind, text)
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    /** Strips ANSI escape sequences (CSI/OSC) that garble TUI apps like opencode. */
    private fun stripAnsiLine(s: String): String =
        s.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")

    private fun classifyLine(line: String): TerminalKind = when {
        line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> TerminalKind.Error
        line.contains("warning", ignoreCase = true) -> TerminalKind.Warning
        line.contains("success", ignoreCase = true) || line.contains("BUILD SUCCESSFUL") -> TerminalKind.Success
        else -> TerminalKind.Output
    }

    private fun resolveInitialCwd(): File {
        val explicit = intent.getStringExtra(EXTRA_CWD)
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
        if (explicit != null) return explicit
        return File(filesDir, "MobileStudio").apply { mkdirs() }
    }

    private fun buildShellEnv(current: File): Map<String, String> {
        val toolchain = ToolchainManager(this)
        val jdk = toolchain.activeJdkHome()
            ?: toolchain.resolveJdkHome(File(filesDir, "toolchain/jdk/lib/jvm/java-17-openjdk"))
            ?: toolchain.resolveJdkHome(File(filesDir, "toolchain/jdk/jdk-21"))
        val sdk = toolchain.activeDir(ToolchainManager.Kind.SDK)
            ?: File(filesDir, "toolchain/sdk/android-34").takeIf { it.exists() }
        val ndk = toolchain.activeDir(ToolchainManager.Kind.NDK)
        val ndkDirs = collectInstalledNdkDirs(sdk, ndk)
        val cmake = toolchain.activeDir(ToolchainManager.Kind.CMAKE)
        val gradle = toolchain.activeDir(ToolchainManager.Kind.GRADLE)
        val python = toolchain.activeDir(ToolchainManager.Kind.PYTHON)
        val nativeToolShell = prepareNativeToolShimScript()
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val tmp = File(cacheDir, "android_shell_tmp").apply { mkdirs() }
        val runtimeToolsBin = RuntimeToolsInstaller.binDir(this).takeIf { it.isDirectory }
        val pathParts = buildList {
            add("/system/bin")
            add("/system/xbin")
            add(current.absolutePath)
            runtimeToolsBin?.let { add(it.absolutePath) }
            jdk?.let { add(File(it, "bin").absolutePath) }
            gradle?.let { add(File(it, "bin").absolutePath) }
            sdk?.let {
                add(File(it, "platform-tools").absolutePath)
                add(File(it, "cmdline-tools/latest/bin").absolutePath)
                add(File(it, "cmdline-tools/bin").absolutePath)
            }
            cmake?.let { add(File(it, "bin").absolutePath) }
            ndkDirs.forEach { add(File(it, "toolchains/llvm/prebuilt/linux-x86_64/bin").absolutePath) }
            python?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "local/bin").absolutePath)
            }
            runCatching { LuaTools.sync(this@AndroidShellActivity) }
                .getOrNull()
                ?.let { add(it.absolutePath) }
        }.distinct().joinToString(":")
        val ldParts = buildList {
            jdk?.let {
                add(File(it, "lib/server").absolutePath)
                add(File(it, "lib").absolutePath)
                add(File(it, "lib/jli").absolutePath)
            }
            cmake?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "lib").absolutePath)
            }
            add(nativeLibDir)
            add("/system/lib64")
            add("/system/lib")
        }.distinct().joinToString(":")
        return buildMap {
            put("HOME", filesDir.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PATH", pathParts)
            put("LD_LIBRARY_PATH", ldParts)
            put("MOBILESTUDIO_NATIVE_TOOL_SHELL", nativeToolShell.absolutePath)
            put("MOBILESTUDIO_NATIVE_LIB_DIR", nativeLibDir)
            put("MOBILESTUDIO_APK_PATH", applicationInfo.sourceDir)
            put("MOBILESTUDIO_CODE_CACHE", codeCacheDir.absolutePath)
            File(nativeLibDir, "libexec_redirect.so").takeIf { it.exists() }?.let {
                put("LD_PRELOAD", it.absolutePath)
            }
            jdk?.let { put("JAVA_HOME", it.absolutePath) }
            sdk?.let {
                put("ANDROID_HOME", it.absolutePath)
                put("ANDROID_SDK_ROOT", it.absolutePath)
            }
            ndk?.let {
                put("ANDROID_NDK_HOME", it.absolutePath)
                put("ANDROID_NDK_ROOT", it.absolutePath)
            }
            put("MOBILESTUDIO_LLVM_SEARCH_ROOTS", ndkDirs.joinToString(":") { it.absolutePath })
            gradle?.let { put("GRADLE_HOME", it.absolutePath) }
            python?.let {
                put("PYTHONHOME", it.absolutePath)
                put("PYTHONUSERBASE", File(filesDir, "toolchain/python-user").apply { mkdirs() }.absolutePath)
                put("PIP_CACHE_DIR", File(cacheDir, "pip").apply { mkdirs() }.absolutePath)
            }
            put("GRADLE_USER_HOME", File(filesDir, ".gradle").apply { mkdirs() }.absolutePath)
            // Do not force daemon OFF here; project/user gradle.properties governs.
            put("GRADLE_OPTS", "-Djava.io.tmpdir=${tmp.absolutePath}")
        }
    }

    private fun collectInstalledNdkDirs(sdk: File?, activeNdk: File?): List<File> {
        val candidates = buildList {
            activeNdk?.let { add(it) }
            File(filesDir, "toolchain/ndk").listFiles()?.forEach { if (it.isDirectory) add(it) }
            File(filesDir, "toolchain/sdk").listFiles()?.forEach { sdkDir ->
                if (sdkDir.isDirectory) {
                    File(sdkDir, "ndk").listFiles()?.forEach { if (it.isDirectory) add(it) }
                }
            }
            sdk?.let {
                File(it, "ndk").listFiles()?.forEach { ndkDir -> if (ndkDir.isDirectory) add(ndkDir) }
            }
        }
        return candidates
            .map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinctBy { it.absolutePath }
            .filter { File(it, "source.properties").exists() || File(it, "toolchains/llvm").exists() }
    }

    private fun prepareNativeToolShimScript(): File {
        val script = File(codeCacheDir, "android_shell_native_tool_shim.sh")
        val text = """
            #!/system/bin/sh
            unset LD_PRELOAD
            TOOL="${'$'}1"
            shift
            APP_PROCESS=/system/bin/app_process64
            [ -x "${'$'}APP_PROCESS" ] || APP_PROCESS=/system/bin/app_process
            export CLASSPATH=${quote(applicationInfo.sourceDir)}
            export MOBILESTUDIO_APK_PATH=${quote(applicationInfo.sourceDir)}
            export MOBILESTUDIO_NATIVE_LIB_DIR=${quote(applicationInfo.nativeLibraryDir)}
            exec "${'$'}APP_PROCESS" /system/bin --application --nice-name=mobilestudio_android_shell_tool com.aide.mobilestudio.NativeBuildToolShim "${'$'}TOOL" "${'$'}@"
        """.trimIndent() + "\n"
        if (!script.exists() || script.readText() != text) {
            script.writeText(text)
        }
        script.setReadable(true, false)
        script.setExecutable(true, false)
        return script
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

private data class TerminalLine(
    val kind: TerminalKind,
    val text: String
)

private enum class TerminalKind {
    Command,
    Output,
    Ai,
    Info,
    Success,
    Warning,
    Error
}

@Composable
private fun AndroidShellScreen(
    cwd: String,
    input: String,
    running: Boolean,
    lines: List<TerminalLine>,
    onInputChange: (String) -> Unit,
    onRun: () -> Unit,
    onAskAi: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onFinish: () -> Unit
) {
    val ide = LocalIdeColors.current
    val listState = rememberLazyListState()

    // Terminals stay pinned to the newest line.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    IdeMainLayout(
        title = "Android Shell",
        subtitle = cwd,
        subtitleMono = true,
        showBack = true,
        onBack = onFinish,
        drawerContent = {
            IdeDrawerMenu(
                listOf(
                    IdeMenuGroup(
                        "Shell",
                        listOf(
                            if (running) {
                                IdeMenuItem("Stop", danger = true, onClick = { onStop() })
                            } else {
                                IdeMenuItem("Run", onClick = { onRun() })
                            },
                            IdeMenuItem("Clear output", onClick = { onClear() }),
                            IdeMenuItem("Ask AI", onClick = { onAskAi() }),
                            IdeMenuItem("Close", onClick = { onFinish() }),
                        ),
                    ),
                ),
            )
        },
        topActions = {
            IdeAction(text = "Clear", onClick = onClear)
        },
        secondaryActions = {
            if (running) {
                IdeAction(
                    text = "Stop",
                    onClick = onStop,
                    danger = true,
                    iconRes = R.drawable.ic_ide_stop,
                    iconSize = 12.dp,
                )
            } else {
                IdeAction(
                    text = "Run",
                    onClick = onRun,
                    primary = true,
                    iconRes = R.drawable.ic_ide_run,
                    iconSize = 14.dp,
                )
                Spacer(Modifier.width(4.dp))
                IdeAction(text = "AI", onClick = onAskAi)
            }
        },
        statusBar = {
            IdeStatusStrip(
                left = {
                    IdeDot(if (running) ide.warning else ide.success)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (running) "running…" else "shell ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = ide.textSoft,
                        maxLines = 1,
                    )
                },
                right = {
                    Text("${lines.size} lines", style = IdeMono.log, color = ide.muted)
                },
            )
        },
    ) {
        // Full-bleed console: no rounded card, no border box.
        // weight(1f) keeps the prompt strip and status bar visible below it.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(ide.p.editorBg))
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 8.dp
                    ),
                ) {
                    items(lines.size) { index ->
                        TerminalText(lines[index], ide)
                    }
                }
            }
        }

        // Prompt strip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(ide.p.bg))
                .border(0.5.dp, ide.divider)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = IdeMono.terminal, color = ide.accent)
            Spacer(Modifier.width(6.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                maxLines = 4,
                textStyle = IdeMono.terminal.copy(color = ide.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ide.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.None
                ),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                "command",
                                style = IdeMono.terminal.copy(color = ide.muted),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun TerminalText(line: TerminalLine, ide: com.aide.mobilestudio.ui.ide.IdeColors) {
    val color = when (line.kind) {
        TerminalKind.Command -> Color(ide.p.termCommand)
        TerminalKind.Ai -> ide.info
        TerminalKind.Info -> ide.muted
        TerminalKind.Success -> Color(ide.p.termExitCode)
        TerminalKind.Warning -> ide.warning
        TerminalKind.Error -> ide.error
        TerminalKind.Output -> ide.text
    }
    Text(
        text = line.text,
        color = color,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}
