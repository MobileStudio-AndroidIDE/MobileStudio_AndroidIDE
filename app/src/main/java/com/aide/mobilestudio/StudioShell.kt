package com.aide.mobilestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.aide.mobilestudio.analysis.AnalysisEngine
import com.aide.mobilestudio.analysis.Diagnostic
import com.aide.mobilestudio.analysis.DiagnosticSeverity
import com.aide.mobilestudio.analysis.EditorDiagnostics
import com.aide.mobilestudio.ai.ui.AiWorkspaceActivity
import com.aide.mobilestudio.ui.ide.IdeAction
import com.aide.mobilestudio.ui.ide.IdeDivider
import com.aide.mobilestudio.ui.ide.IdeFilterField
import com.aide.mobilestudio.ui.ide.IdeIcon
import com.aide.mobilestudio.ui.ide.IdeMono
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/** Default content shown in an empty editor pane. */
internal val EditorDefaultContent = "fun main() {\n    println(\"hi\")\ngreet()\n}"

private val SampleMainCode = """
    package com.example.demo

    import java.util.Date

    /**
     * Sample project for MobileStudio.
     * Edit this file and press Save in the files menu.
     */
    fun main() {
        val now = Date()
        val message = "Hello from MobileStudio"
        println(message)
        println("Started at: " + now)
        greet()
    }
""".trimIndent()

private val SampleHelperCode = """
    package com.example.demo

    fun greet() {
        println("hi from Helper.kt")
    }

    fun add(a: Int, b: Int): Int {
        return a + b
    }
""".trimIndent()

/** Extensions backed by a real compiler inside the live analysis engine. */
private val CompilerCheckedExtensions = setOf("kt", "kts", "java", "c", "cc", "cpp", "h", "hpp")

/** A single line in the build log pane. */
data class StudioLogLine(val text: String, val color: Int)

/**
 * The complete MobileStudio IDE shell, rendered with Jetpack Compose.
 */
@Composable
fun StudioApp(host: StudioHost) {
    val activity = host.studioActivity
    val context: Context = LocalContext.current
    val ide = LocalIdeColors.current

    var centerPane by remember { mutableStateOf("editor") }
    var drawerOpen by remember { mutableStateOf(false) }
    var drawerMode by remember { mutableStateOf("projects") }
    var showDownloadManager by remember { mutableStateOf(false) }
    var bottomPanelOpen by remember { mutableStateOf(false) }
    var bottomPanelHeight by remember { mutableStateOf(220.dp) }
    var showSettingsOverlay by remember { mutableStateOf(false) }
    var showToolsOverlay by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var toolsInitialTab by remember { mutableStateOf("sdk_manager") }
    var settingsInitialPage by remember { mutableStateOf<String?>(null) }
    val logLines = remember { mutableStateListOf<StudioLogLine>() }

    DisposableEffect(Unit) {
        DownloadCenter.attach()
        onDispose { DownloadCenter.detach() }
    }

    val downloadTask by remember { derivedStateOf { DownloadCenter.state.value } }
    LaunchedEffect(downloadTask) {
        val task = downloadTask
        if (task != null && task.status != DownloadCenter.Status.DONE &&
            task.status != DownloadCenter.Status.FAILED && task.status != DownloadCenter.Status.CANCELLED
        ) {
            showDownloadManager = true
        }
    }

    fun startBuild(installAndRun: Boolean = false) {
        if (host.studioIsBuildRunning) {
            Toast.makeText(context, AppLanguage.string(activity, R.string.build_already_running), Toast.LENGTH_SHORT).show()
            return
        }
        centerPane = "buildlog"
        bottomPanelOpen = true
        logLines.add(StudioLogLine("> Running :app:assembleDebug ...", ide.p.text))
        activity.gradleRunner.run(
            projectDir = host.studioProjectDir ?: activity.filesDir,
            tasks = listOf("assembleDebug"),
            onLine = { line, level ->
                logLines.add(StudioLogLine(line, if (level == LineLevel.ERROR) ide.p.error else ide.p.text))
            },
            onDone = { result ->
                logLines.add(
                    StudioLogLine(
                        "BUILD ${if (result.success) "OK" else "FAILED"} (${result.durationMs}ms)",
                        if (result.success) ide.p.success else ide.p.error,
                    ),
                )
                if (result.success && installAndRun) {
                    logLines.add(StudioLogLine("> Installing and launching on device…", ide.p.info))
                    host.studioInstallAndLaunchLatest()
                }
            },
        )
    }

    fun runGradleTasks(tasks: List<String>, label: String) {
        if (host.studioIsBuildRunning) {
            Toast.makeText(context, AppLanguage.string(activity, R.string.build_already_running), Toast.LENGTH_SHORT).show()
            return
        }
        centerPane = "buildlog"
        bottomPanelOpen = true
        logLines.add(StudioLogLine("> $label: ${tasks.joinToString(" ")}", ide.p.text))
        activity.gradleRunner.run(
            projectDir = host.studioProjectDir ?: activity.filesDir,
            tasks = tasks,
            onLine = { line, level ->
                logLines.add(StudioLogLine(line, if (level == LineLevel.ERROR) ide.p.error else ide.p.text))
            },
            onDone = { result ->
                logLines.add(StudioLogLine("$label ${if (result.success) "OK" else "FAILED"} (${result.durationMs}ms)",
                    if (result.success) ide.p.success else ide.p.error))
            },
        )
    }

    fun saveCurrent() {
        host.studioSaveCurrentFile()
        EditorPaneState.unsaved = false
        Toast.makeText(context, "Saved: ${host.studioCurrentFile?.name ?: "file"}", Toast.LENGTH_SHORT).show()
    }

    fun showFindDialog() {
        val editor = EditorPaneState.editor ?: return
        val input = EditText(context).apply { hint = "Find text"; setSingleLine(true) }
        AlertDialog.Builder(context)
            .setTitle("Find")
            .setView(input)
            .setPositiveButton("Find") { _, _ ->
                val query = input.text.toString()
                val text = editor.text?.toString() ?: ""
                val idx = if (query.isEmpty()) -1 else text.indexOf(query)
                if (idx >= 0) { editor.setSelection(idx, idx + query.length); editor.requestFocus() }
                else Toast.makeText(context, "Not found: $query", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showGoToLineDialog() {
        val editor = EditorPaneState.editor ?: return
        val input = EditText(context).apply { hint = "Line number"; setSingleLine(true) }
        AlertDialog.Builder(context)
            .setTitle("Go to Line")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val line = input.text.toString().toIntOrNull()
                val layout = editor.layout
                if (line != null && layout != null && line in 1..layout.lineCount) {
                    val offset = layout.getLineStart(line - 1)
                    editor.setSelection(offset)
                    editor.requestFocus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun toggleComment() {
        val editor = EditorPaneState.editor ?: return
        val text = editor.text ?: return
        val selStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val selEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val lineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', selEnd).let { if (it < 0) text.length else it }
        val lines = text.subSequence(lineStart, lineEnd).toString().split("\n")
        val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("//") }
        val updated = lines.joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                allCommented -> line.replaceFirst("// ", "").replaceFirst("//", "")
                else -> "// " + line
            }
        }
        text.replace(lineStart, lineEnd, updated)
    }

    fun newFileDialog() {
        val input = EditText(context).apply { hint = "File name (e.g. Screen.kt)"; setSingleLine(true) }
        AlertDialog.Builder(context)
            .setTitle("New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Untitled.kt" }
                host.studioNewEditorTab(name)
                centerPane = "editor"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun openTool(tabId: String) {
        toolsInitialTab = tabId
        showToolsOverlay = true
    }

    fun performAction(action: String) {
        when (action) {
            "new_tab" -> newFileDialog()
            "new_project" -> FileManagerActivity.start(context, projectMode = true, isNewProject = true)
            "new_file" -> showNewFileDialog(activity)
            "new_folder" -> showNewFolderDialog(activity)
            "open_project" -> FileManagerActivity.start(context, projectMode = true)
            "save", "save_all" -> saveCurrent()
            "close_tab" -> {
                if (host.studioTabDirty) {
                    AlertDialog.Builder(context)
                        .setTitle(AppLanguage.string(activity, R.string.close))
                        .setMessage("This file has unsaved changes. Save before closing?")
                        .setPositiveButton(AppLanguage.string(activity, R.string.action_save)) { _, _ -> saveCurrent(); host.studioCloseEditorTab(host.studioSelectedTab); centerPane = "editor" }
                        .setNegativeButton("Discard") { _, _ -> host.studioCloseEditorTab(host.studioSelectedTab); centerPane = "editor" }
                        .setNeutralButton(AppLanguage.string(activity, R.string.action_cancel), null)
                        .show()
                } else { host.studioCloseEditorTab(host.studioSelectedTab); centerPane = "editor" }
            }
            "close_all_tabs" -> {
                if (host.studioTabDirty) saveCurrent()
                host.studioTabs.clear()
                centerPane = "editor"
                Toast.makeText(context, "All tabs closed", Toast.LENGTH_SHORT).show()
            }
            "exit" -> activity.finish()
            "undo" -> EditorPaneState.editor?.undo()
            "redo" -> EditorPaneState.editor?.redo()
            "cut" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.cut)
            "copy" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.copy)
            "paste" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.paste)
            "select_all" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.selectAll)
            "find" -> showFindDialog()
            "find_replace" -> EditorPaneState.editor?.showFindReplaceDialog()
            "find_in_files" -> showFindInFilesDialog(activity)
            "pane_editor" -> { centerPane = "editor"; bottomPanelOpen = false }
            "pane_buildlog" -> { centerPane = "buildlog"; bottomPanelOpen = true }
            "pane_terminal" -> { centerPane = "terminal"; bottomPanelOpen = true }
            "pane_problems" -> { centerPane = "problems"; bottomPanelOpen = true }
            "pane_logcat" -> { centerPane = "logcat"; bottomPanelOpen = true }
            "pane_search" -> { centerPane = "search"; bottomPanelOpen = true }
            "drawer" -> drawerOpen = true
            "next_tab" -> {
                if (host.studioTabs.isNotEmpty()) {
                    host.studioSelectTab((host.studioSelectedTab + 1) % host.studioTabs.size)
                    centerPane = "editor"
                }
            }
            "prev_tab" -> {
                if (host.studioTabs.isNotEmpty()) {
                    host.studioSelectTab((host.studioSelectedTab - 1 + host.studioTabs.size) % host.studioTabs.size)
                    centerPane = "editor"
                }
            }
            "goto_line" -> showGoToLineDialog()
            "comment" -> toggleComment()
            "reformat" -> {
                EditorPaneState.editor?.let { editor ->
                    val src = editor.text?.toString() ?: return@let
                    val ext = host.studioCurrentFile?.extension
                        ?: host.studioTabs.getOrNull(host.studioSelectedTab)?.substringAfterLast('.')
                        ?: ""
                    if (!CodeFormatter.isFormattable(ext)) {
                        Toast.makeText(context, "No formatter for .$ext files", Toast.LENGTH_SHORT).show()
                        return@let
                    }
                    val formatted = CodeFormatter.format(src, ext)
                    if (formatted != src) {
                        editor.setText(formatted)
                        host.studioFileContent = formatted
                        EditorPaneState.content = formatted
                        EditorPaneState.unsaved = true
                        Toast.makeText(context, "Reformatted ${host.studioCurrentFile?.name ?: "file"}", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, "Already formatted", Toast.LENGTH_SHORT).show()
                }
            }
            "optimize" -> {
                EditorPaneState.editor?.let { editor ->
                    val src = editor.text?.toString() ?: return@let
                    val ext = host.studioCurrentFile?.extension
                        ?: host.studioTabs.getOrNull(host.studioSelectedTab)?.substringAfterLast('.')
                        ?: ""
                    if (!CodeFormatter.isImportOptimizable(ext)) {
                        Toast.makeText(context, "Optimize Imports not supported for .$ext files", Toast.LENGTH_SHORT).show()
                        return@let
                    }
                    val result = CodeFormatter.optimizeImports(src)
                    if (result != src) {
                        editor.setText(result)
                        host.studioFileContent = result
                        EditorPaneState.content = result
                        EditorPaneState.unsaved = true
                        Toast.makeText(context, "Optimized imports", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(context, "No unused imports", Toast.LENGTH_SHORT).show()
                }
            }
            "run" -> startBuild(installAndRun = true)
            "build" -> startBuild()
            "rebuild" -> runGradleTasks(listOf("clean", "assembleDebug"), "Rebuild")
            "clean" -> runGradleTasks(listOf("clean"), "Clean")
            "build_apk" -> runGradleTasks(listOf("assembleDebug"), "Build APK")
            "stop_build" -> { host.studioStopBuild(); logLines.add(StudioLogLine("> Build stopped", ide.p.warning)) }
            "install_apk" -> {
                logLines.add(StudioLogLine("> Installing latest APK…", ide.p.info))
                host.studioInstallAndLaunchLatest()
            }
            "sync_project" -> runGradleTasks(listOf("gradleSync"), "Sync Project")
            "command_palette" -> { showCommandPalette = true }
            "quick_open" -> showQuickOpenDialog(activity)
            "project_stats" -> showProjectStatsDialog(activity)
            "documentation" -> HtmlViewerActivity.openAsset(activity, "index.html", "Documentation")
            "keyboard_shortcuts" -> AlertDialog.Builder(context)
                .setTitle("Keyboard Shortcuts")
                .setMessage(
                    "Ctrl+S — Save\nCtrl+F — Find\nCtrl+Shift+F — Find in Files\nCtrl+W — Close Tab\nCtrl+G — Go to Line\nCtrl+N — New File\nCtrl+Shift+P — Command Palette\nAlt+Left/Right — Navigate back/forward"
                )
                .setPositiveButton("OK", null).show()
            "todo" -> TodoExplorer.show(activity, host.studioProjectDir) { file, line -> host.studioOpenFileAt(file, line) }
            "structure" -> SymbolOutline.show(
                activity,
                EditorPaneState.content,
                host.studioCurrentFile?.name,
            ) { line -> host.studioSelectEditorLine(line) }
            "downloads" -> { showDownloadManager = true; DownloadCenter.showIdleWindow() }
            "device_manager" -> openTool("device_manager")
            "sdk_manager" -> openTool("sdk_manager")
            "resource_manager" -> openTool("resource_manager")
            "agp_upgrade" -> openTool("agp_upgrade")
            "android_sdk_upgrade" -> openTool("android_sdk_upgrade")
            "troubleshoot_device" -> openTool("troubleshoot_device")
            "app_links" -> openTool("app_links")
            "firebase" -> openTool("firebase")
            "layout_inspector" -> openTool("layout_inspector")
            "preview_design" -> {
                val tabName = host.studioTabs.getOrNull(host.studioSelectedTab) ?: return@performAction
                val ext = tabName.substringAfterLast('.').lowercase()
                if (ext != "xml" && ext != "kt") {
                    Toast.makeText(context, "Design Preview is available for XML and Kotlin (Compose) files", Toast.LENGTH_SHORT).show()
                    return@performAction
                }
                val file = File(host.studioProjectDir ?: activity.filesDir, tabName)
                if (!file.exists()) { Toast.makeText(context, "File not found: $tabName", Toast.LENGTH_SHORT).show(); return@performAction }
                LayoutPreviewActivity.start(activity, file)
            }
            "generate_javadoc" -> openTool("generate_javadoc")
            "create_cli_launcher" -> openTool("create_cli_launcher")
            "services" -> openTool("services")
            "markdown" -> { settingsInitialPage = "l_markdown"; showSettingsOverlay = true }
            "xml_actions" -> openTool("xml_actions")
            "cling" -> openTool("cling")
            "groovy_console" -> openTool("groovy_console")
            "kotlin" -> { settingsInitialPage = "l_kotlin"; showSettingsOverlay = true }
            "file_manager" -> FileManagerActivity.start(context)
            "git" -> {
                val projDir = host.studioProjectDir?.absolutePath
                if (projDir != null) GitActivity.start(context, projDir)
                else Toast.makeText(context, "Open a project first", Toast.LENGTH_SHORT).show()
            }
            "recent_projects" -> showRecentProjectsDialog(activity)
            "recent_files" -> showRecentFilesDialog(activity)
            "terminal" -> { centerPane = "terminal"; bottomPanelOpen = true }
            "assistant" -> context.startActivity(Intent(context, AiWorkspaceActivity::class.java))
            "settings" -> showSettingsOverlay = true
            "about" -> AlertDialog.Builder(context)
                .setTitle("About MobileStudio")
                .setMessage("MobileStudio IDE\nAIDE-style Android IDE rebuilt with Jetpack Compose.\n\nEditor, Gradle build, terminal, file tree and AI assistant are wired in.")
                .setPositiveButton("OK", null)
                .show()
            "crash_report" -> {
                val crashLogs = com.aide.mobilestudio.MobileStudioCrashGuard.getCrashLogs(activity)
                val latestText = if (crashLogs.isEmpty()) {
                    "No crash logs found."
                } else {
                    com.aide.mobilestudio.MobileStudioCrashGuard.latestCrashText(activity)
                }
                AlertDialog.Builder(activity)
                    .setTitle("Crash Report")
                    .setMessage(latestText)
                    .setPositiveButton("Copy") { _, _ ->
                        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_report", latestText))
                        android.widget.Toast.makeText(activity, "Crash report copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
            else -> Toast.makeText(context, action, Toast.LENGTH_SHORT).show()
        }
    }

    // A single top-level back handler, priorities from topmost overlay down.
    // This avoids multiple enabled handlers racing when several layers are open.
    BackHandler(enabled = showCommandPalette) { showCommandPalette = false }
    BackHandler(enabled = host.studioNewProjectDialogVisible) { host.studioNewProjectDialogVisible = false }
    BackHandler(enabled = showDownloadManager) { showDownloadManager = false; DownloadCenter.dismiss() }
    BackHandler(enabled = showSettingsOverlay) { showSettingsOverlay = false }
    BackHandler(enabled = showToolsOverlay) { showToolsOverlay = false }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ide.window)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed
                val shift = event.isShiftPressed
                when {
                    ctrl && event.key == Key.S -> { performAction("save"); true }
                    ctrl && event.key == Key.W -> { performAction("close_tab"); true }
                    ctrl && event.key == Key.F -> { performAction("find"); true }
                    ctrl && shift && event.key == Key.F -> { performAction("find_in_files"); true }
                    ctrl && event.key == Key.G -> { performAction("goto_line"); true }
                    ctrl && event.key == Key.N -> { performAction("new_tab"); true }
                    ctrl && shift && event.key == Key.P -> { showCommandPalette = true; true }
                    else -> false
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(ide.window)) {
            TopMenuBar(
                activity = context as Activity,
                projectName = host.studioProjectDir?.name ?: "MobileStudio",
                onOpenDrawer = {
                    drawerMode = if (host.studioProjectDir != null) "files" else "projects"
                    drawerOpen = true
                },
                onRun = { startBuild() },
                onOpenSettings = { showSettingsOverlay = true },
                onAction = { performAction(it) },
            )
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LeftStrip(
                    context = context,
                    activePane = when {
                        drawerOpen -> "project"
                        showSettingsOverlay -> "settings"
                        bottomPanelOpen && centerPane == "buildlog" -> "build"
                        bottomPanelOpen && centerPane == "terminal" -> "terminal"
                        else -> ""
                    },
                    onProject = {
                        drawerMode = if (host.studioProjectDir != null) "files" else "projects"
                        drawerOpen = true
                    },
                    onBuildLog = { centerPane = "buildlog"; bottomPanelOpen = true },
                    onTerminal = { centerPane = "terminal"; bottomPanelOpen = true },
                    onIde = { context.startActivity(Intent(context, AiWorkspaceActivity::class.java)) },
                    onSettings = { showSettingsOverlay = true },
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    EditorPane(
                        host = host,
                        tabs = host.studioTabs,
                        selectedTab = host.studioSelectedTab,
                        onTabChange = { idx -> host.studioSelectTab(idx) },
                        pathText = host.studioPathText,
                        fileContent = host.studioFileContent,
                        onContentChange = {
                            host.studioFileContent = it
                            EditorPaneState.content = it
                            EditorPaneState.unsaved = true
                            host.studioScheduleAutosave()
                            host.studioRequestLiveDiagnostics()
                        },
                        onNewTab = { name -> host.studioNewEditorTab(name); centerPane = "editor" },
                        onCloseTab = { idx -> host.studioCloseEditorTab(idx) },
                        onOpenProject = { host.studioPickDir.launch(null) },
                        onSaveCurrent = { saveCurrent() },
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(
                        visible = bottomPanelOpen,
                        enter = expandVertically(expandFrom = Alignment.Bottom, animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)) + fadeIn(animationSpec = tween(durationMillis = 200)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 160)),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            IdeDivider()
                            BottomPanel(
                                host = host,
                                pane = centerPane,
                                logLines = logLines,
                                problems = host.studioEditorProblems,
                                height = bottomPanelHeight,
                                onHeightChange = { bottomPanelHeight = it },
                                onPaneChange = { centerPane = it },
                                onCollapse = { bottomPanelOpen = false },
                                onClearLog = { logLines.clear() },
                            )
                        }
                    }
                }
            }
            StudioStatusBar(
                host = host,
                projectName = host.studioProjectDir?.name ?: "MobileStudio",
                problems = host.studioEditorProblems.size,
                onShowProblems = { centerPane = "problems"; bottomPanelOpen = true },
            )
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing)),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable { drawerOpen = false },
            )
            FileDrawer(
                host = host,
                mode = drawerMode,
                onClose = { drawerOpen = false },
                onPickFile = { file ->
                    host.studioOpenFileInEditor(file)
                    centerPane = "editor"
                    drawerMode = "projects"
                    drawerOpen = false
                },
                onOpenProject = { host.studioPickDir.launch(null) },
                onRefresh = { host.studioLoadFileTree(host.studioProjectDir ?: activity.filesDir) },
                onSwitchToFiles = { drawerMode = "files" },
                onSwitchToProjects = { drawerMode = "projects" },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(320.dp)
                    .fillMaxHeight(),
            )
        }

        AnimatedVisibility(
            visible = showDownloadManager,
            enter = scaleIn(initialScale = 0.94f) + fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
            exit = scaleOut(targetScale = 0.96f) + fadeOut(animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing)),
        ) {
            DownloadManagerWindow(
                activity = activity,
                onDismiss = { showDownloadManager = false; DownloadCenter.dismiss() },
            )
        }

        AnimatedVisibility(
            visible = showToolsOverlay,
            enter = scaleIn(initialScale = 0.94f) + fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
            exit = scaleOut(targetScale = 0.96f) + fadeOut(animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing)),
        ) {
            ToolsWindowOverlay(
                host = host,
                initialTab = toolsInitialTab,
                onClose = { showToolsOverlay = false },
            )
        }
        AnimatedVisibility(
            visible = showSettingsOverlay,
            enter = scaleIn(initialScale = 0.94f) + fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
            exit = scaleOut(targetScale = 0.96f) + fadeOut(animationSpec = tween(durationMillis = 120, easing = LinearOutSlowInEasing)),
        ) {
            SettingsWindowOverlay(
                activity = activity,
                initialPageId = settingsInitialPage,
                onClose = { showSettingsOverlay = false },
            )
        }
        if (host.studioNewProjectDialogVisible) {
            NewProjectDialog(
                activity = activity,
                onDismiss = { host.studioNewProjectDialogVisible = false },
            )
        }
        AnimatedVisibility(
            visible = showCommandPalette,
            enter = fadeIn(animationSpec = tween(140)) + scaleIn(initialScale = 0.97f),
            exit = fadeOut(animationSpec = tween(100)),
        ) {
            CommandPaletteOverlay(
                activity = activity,
                onRun = { action -> showCommandPalette = false; performAction(action) },
                onDismiss = { showCommandPalette = false },
            )
        }
    }
}

@Composable
private fun EditorPane(
    host: StudioHost,
    tabs: MutableList<String>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    pathText: String,
    fileContent: String,
    onContentChange: (String) -> Unit,
    onNewTab: (String) -> Unit,
    onCloseTab: (Int) -> Unit,
    onOpenProject: () -> Unit,
    onSaveCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = host.studioActivity
    val ide = LocalIdeColors.current
    var searchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var replaceVisible by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }

    fun findNext(forward: Boolean) {
        val editor = EditorPaneState.editor ?: return
        val content = editor.text?.toString() ?: return
        val q = searchQuery.trim()
        if (q.isEmpty()) return
        val len = content.length
        val current = editor.selectionStart.coerceIn(0, len)
        val haystack = if (matchCase) content else content.lowercase()
        val needle = if (matchCase) q else q.lowercase()
        var abs = -1
        if (forward) {
            val from = (current + 1).coerceAtMost(len)
            val idx = haystack.indexOf(needle, from)
            abs = if (idx >= 0) idx else haystack.indexOf(needle) // wrap around
        } else {
            val from = (current - 1).coerceAtLeast(0)
            val idx = haystack.lastIndexOf(needle, from)
            abs = if (idx >= 0) idx else haystack.lastIndexOf(needle)
        }
        if (abs >= 0) {
            editor.setSelection(abs, (abs + q.length).coerceAtMost(len))
            editor.requestFocus()
        }
    }

    fun replaceAll() {
        val editor = EditorPaneState.editor ?: return
        val text = editor.text ?: return
        val q = searchQuery.trim()
        val r = replaceQuery
        if (q.isEmpty()) return
        val newText = text.toString().replace(q, r, ignoreCase = !matchCase)
        editor.setText(newText) // TextWatcher publishes content + autosave
        Toast.makeText(activity, "Replaced all occurrences", Toast.LENGTH_SHORT).show()
    }

    Column(modifier = modifier.fillMaxSize().background(ide.window)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color(ide.p.surface))
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, title ->
                EditorTab(
                    title = title,
                    active = selectedTab == index,
                    dirty = selectedTab == index && host.studioTabDirty,
                    onClick = { onTabChange(index) },
                    onClose = { onCloseTab(index) },
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onNewTab("Untitled.kt") }
                    .semantics { contentDescription = "New file" },
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(iconRes = R.drawable.ic_ide_add, color = ide.textSoft, modifier = Modifier.size(16.dp))
            }
        }
        IdeDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(ide.p.bg))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pathText.ifBlank { "No file selected" },
                style = IdeMono.path,
                color = ide.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable { searchVisible = true; replaceVisible = false }
                    .semantics { contentDescription = "Find" },
                contentAlignment = Alignment.Center,
            ) {
                Text("🔍", color = ide.textSoft, fontSize = 14.sp)
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable { replaceVisible = true; searchVisible = false }
                    .semantics { contentDescription = "Find & Replace" },
                contentAlignment = Alignment.Center,
            ) {
                Text("🔄", color = ide.textSoft, fontSize = 14.sp)
            }
        }
        IdeDivider()
        if (searchVisible || replaceVisible) {
            FindReplaceToolbar(
                searchQuery = searchQuery,
                replaceQuery = replaceQuery,
                searchVisible = searchVisible,
                replaceVisible = replaceVisible,
                onSearchChange = { searchQuery = it },
                onReplaceChange = { replaceQuery = it },
                onFindNext = { findNext(true) },
                onFindPrev = { findNext(false) },
                onReplace = {
                    val editor = EditorPaneState.editor
                    val text = editor?.text
                    val q = searchQuery.trim()
                    val r = replaceQuery
                    if (editor != null && text != null && q.isNotEmpty()) {
                        val s = editor.selectionStart.coerceAtLeast(0)
                        val e = editor.selectionEnd.coerceAtLeast(0)
                        val selected = if (e > s) text.subSequence(s, e).toString() else ""
                        val matches = if (matchCase) selected == q else selected.equals(q, ignoreCase = true)
                        if (matches) {
                            (editor.text as android.text.Editable).replace(s, e, r)
                            Toast.makeText(activity, "Replaced", Toast.LENGTH_SHORT).show()
                        } else {
                            findNext(true) // jump to next match instead of replacing wrong range
                        }
                    }
                },
                onReplaceAll = { replaceAll() },
                onClose = { searchVisible = false; replaceVisible = false },
                matchCase = matchCase,
                onToggleMatchCase = { matchCase = !matchCase },
            )
        }
        IdeDivider()
        if (host.studioTabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ide.window),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = AppLanguage.string(activity, R.string.welcome_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ide.text,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AppLanguage.string(activity, R.string.welcome_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ide.textSoft,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IdeAction(text = AppLanguage.string(activity, R.string.welcome_new_file), onClick = { showNewFileDialog(activity) }, primary = true)
                        IdeAction(text = AppLanguage.string(activity, R.string.welcome_new_project), onClick = { showNewProjectDialog(host) })
                    }
                }
            }
        } else {
            AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(ide.p.editorBg)),
            factory = { context ->
                LineNumberEditText(context).apply {
                    EditorPaneState.editor = this
                    EditorPaneState.currentFilePath = pathText
                    activity.autoComplete = KotlinAutoComplete(activity, this).apply {
                        host.studioProjectDir?.let { setProjectDir(it) }
                        setCurrentFile(host.studioCurrentFile ?: File(activity.filesDir, pathText))
                    }
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    tag = "loading"
                    setText(fileContent)
                    setSelection(0)
                    scrollTo(0, 0)
                    tag = "editor"
                    text?.let { KotlinHighlighter.highlight(it, pathText) }
                    KotlinHighlighter.attachTo(this, pathText, suppressCheck = { tag == "loading" }) { errors ->
                        val ext = EditorPaneState.currentFilePath.substringAfterLast('.', "").lowercase()
                        if (host.studioAnalysisEngine != null && ext in CompilerCheckedExtensions) {
                            return@attachTo
                        }
                        val newProblems = errors.map { e ->
                            EditorProblem(line = e.line, message = e.message, severity = "ERROR")
                        }
                        val ep: MutableList<EditorProblem> = host.studioEditorProblems
                        ep.clear()
                        ep.addAll(newProblems)
                        
                        // Pass real-time compiler diagnostics to editor gutter markers
                        val editorView = EditorPaneState.editor
                        if (editorView != null) {
                            val errorLineSet = errors.map { it.line }.toSet()
                            editorView.setDiagnostics(errorLineSet, emptySet())
                        }
                    }
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: android.text.Editable) {
                            if (tag == "loading") return
                            val text = s.toString()
                            EditorPaneState.content = text
                            onContentChange(text)
                        }
                    })
                }
            },
            update = { view: android.widget.EditText ->
                // Keep autocomplete + diagnostics pointed at the newly selected file.
                EditorPaneState.currentFilePath = pathText
                activity.autoComplete?.setCurrentFile(host.studioCurrentFile ?: File(activity.filesDir, pathText))
                if (view.text.toString() != fileContent) {
                    view.tag = "loading"
                    view.setText(fileContent)
                    view.setSelection(0)
                    view.scrollTo(0, 0)
                    view.tag = "editor"
                    view.text?.let { KotlinHighlighter.highlight(it, pathText) }
                }
            },
        )
        }
    }
}

/** Find / Replace toolbar shown below the editor tab bar. */
@Composable
private fun FindReplaceToolbar(
    searchQuery: String,
    replaceQuery: String,
    searchVisible: Boolean,
    replaceVisible: Boolean,
    onSearchChange: (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onFindNext: () -> Unit,
    onFindPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    matchCase: Boolean = false,
    onToggleMatchCase: () -> Unit = {},
) {
    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(ide.p.surface))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        if (searchVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Aa", color = if (matchCase) ide.accent else ide.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onToggleMatchCase).padding(4.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Find", color = ide.textSoft, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(6.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    maxLines = 1,
                    cursorBrush = SolidColor(ide.accent),
                    textStyle = TextStyle(color = ide.text, fontSize = 12.sp),
                    modifier = Modifier.background(Color(ide.p.editorBg)).weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onFindNext),
                    contentAlignment = Alignment.Center,
                ) { Text("↓", color = ide.text, fontSize = 12.sp) }
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onFindPrev),
                    contentAlignment = Alignment.Center,
                ) { Text("↑", color = ide.text, fontSize = 12.sp) }
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = ide.muted, fontSize = 12.sp) }
            }
        }
        if (replaceVisible) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Replace", color = ide.textSoft, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(6.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    maxLines = 1,
                    cursorBrush = SolidColor(ide.accent),
                    textStyle = TextStyle(color = ide.text, fontSize = 12.sp),
                    modifier = Modifier.background(Color(ide.p.editorBg)).weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("with", color = ide.textSoft, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(6.dp))
                BasicTextField(
                    value = replaceQuery,
                    onValueChange = onReplaceChange,
                    maxLines = 1,
                    cursorBrush = SolidColor(ide.accent),
                    textStyle = TextStyle(color = ide.text, fontSize = 12.sp),
                    modifier = Modifier.background(Color(ide.p.editorBg)).weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onReplace),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = ide.success, fontSize = 12.sp) }
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onReplaceAll),
                    contentAlignment = Alignment.Center,
                ) { Text("✓✓", color = ide.success, fontSize = 10.sp) }
                Box(
                    modifier = Modifier.size(26.dp).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = ide.muted, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun EditorTab(title: String, active: Boolean, dirty: Boolean = false, onClick: () -> Unit, onClose: () -> Unit) {
    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier
            .widthIn(min = 104.dp, max = 220.dp)
            .height(36.dp)
            .background(if (active) Color(ide.p.card) else Color.Transparent)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = if (active) ide.text else ide.textSoft,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(ide.warning))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = "Close $title" },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = ide.muted, fontSize = 15.sp)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) ide.accent else Color.Transparent),
        )
    }
}

@Composable
private fun BottomPanel(
    host: StudioHost,
    pane: String,
    logLines: List<StudioLogLine>,
    problems: List<EditorProblem>,
    height: Dp,
    onHeightChange: (Dp) -> Unit,
    onPaneChange: (String) -> Unit,
    onCollapse: () -> Unit,
    onClearLog: () -> Unit,
) {
    val activity = host.studioActivity
    val ide = LocalIdeColors.current
    val density = LocalDensity.current
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var startHeightPx by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Color(ide.p.card)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .pointerInput(height) {
                    detectDragGestures(
                        onDragEnd = { dragging = false },
                    ) { _, dragAmount ->
                        if (!dragging) {
                            dragging = true
                            startHeightPx = height.value * density.density
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        val minPx = 120f * density.density
                        val maxPx = 440f * density.density
                        val newPx = (startHeightPx - dragAmount.y).coerceIn(minPx, maxPx)
                        onHeightChange(Dp(newPx / density.density))
                    }
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color(ide.p.surface)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomPanelTab(
                label = AppLanguage.string(activity, R.string.tab_build),
                active = pane == "buildlog",
                onClick = { onPaneChange("buildlog") },
            )
            BottomPanelTab(
                label = AppLanguage.string(activity, R.string.tab_problems),
                badge = problems.size.takeIf { it > 0 }?.toString(),
                active = pane == "problems",
                onClick = { onPaneChange("problems") },
            )
            BottomPanelTab(
                label = AppLanguage.string(activity, R.string.tab_shell),
                active = pane == "terminal",
                onClick = { onPaneChange("terminal") },
            )
            BottomPanelTab(
                label = AppLanguage.string(activity, R.string.tab_logcat),
                active = pane == "logcat",
                onClick = { onPaneChange("logcat") },
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clickable(onClick = onCollapse)
                    .semantics { contentDescription = "Close tool window" },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = ide.textSoft, fontSize = 18.sp)
            }
        }
        IdeDivider()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(ide.p.editorBg)),
        ) {
            when (pane) {
                "terminal" -> TerminalPane(activity = activity)
                "problems" -> ProblemsPane(
                    problems = problems,
                    onProblemFix = { line ->
                        host.studioSelectEditorLine(line)
                        onCollapse()
                    },
                )
                "logcat" -> LogcatPane(activity = activity)
                else -> BuildLogPane(lines = logLines, activity = activity, host = host, onClear = onClearLog)
            }
        }
    }
}

@Composable
private fun BottomPanelTab(
    label: String,
    active: Boolean,
    badge: String? = null,
    onClick: () -> Unit,
) {
    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier
            .width(88.dp)
            .height(34.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (active) ide.text else ide.textSoft,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (badge != null) {
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .background(Color(ide.p.errBg), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text(badge, color = ide.error, fontSize = 9.sp)
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (active) ide.accent else Color.Transparent),
        )
    }
}

@Composable
private fun BuildLogPane(lines: List<StudioLogLine>, activity: MainActivity, host: StudioHost, onClear: () -> Unit) {
    val ide = LocalIdeColors.current
    var errorsOnly by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val visible = if (errorsOnly) lines.filter { it.color == ide.p.error } else lines
    LaunchedEffect(visible.size) { scrollState.animateScrollTo(scrollState.maxValue) }
    Column(modifier = Modifier.fillMaxSize().background(Color(ide.p.editorBg))) {
        // Toolbar: build state + copy + clear + stop + errors filter
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).background(Color(ide.p.surface)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (host.studioIsBuildRunning) {
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(ide.warning))
                Spacer(Modifier.width(6.dp))
                Text("Building…", color = ide.warning, fontSize = 10.sp)
            } else {
                Text("Idle", color = ide.muted, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Errors only", color = if (errorsOnly) ide.error else ide.textSoft, fontSize = 10.sp,
                modifier = Modifier.clickable { errorsOnly = !errorsOnly }.padding(6.dp))
            Text("Copy", color = ide.textSoft, fontSize = 10.sp, modifier = Modifier.clickable {
                val text = visible.joinToString("\n") { it.text }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("build_log", text))
                Toast.makeText(activity, "Build log copied", Toast.LENGTH_SHORT).show()
            }.padding(6.dp))
            Text("Clear", color = ide.textSoft, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onClear).padding(6.dp))
            if (host.studioIsBuildRunning) {
                Text("Stop", color = ide.error, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { host.studioStopBuild() }.padding(6.dp))
            }
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (visible.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (host.studioIsBuildRunning) "Build in progress…" else AppLanguage.string(activity, R.string.shell_no_build_output),
                        style = IdeMono.log, color = ide.muted,
                    )
                }
            } else {
                visible.forEach { line ->
                    Text(text = line.text, color = Color(line.color), style = IdeMono.log)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun TerminalPane(activity: MainActivity) {
    val ide = LocalIdeColors.current
    var shellProcess by remember { mutableStateOf<java.lang.Process?>(null) }
    var terminalText by remember { mutableStateOf("") }
    var draftInput by remember { mutableStateOf("") }
    var shellError by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0L) }

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    // Kill any previous shell before (re)starting so Retry never leaves orphans.
    DisposableEffect(retryKey) {
        shellProcess?.let { runCatching { it.destroy() } }
        onDispose {
            // Destroy the shell when the pane leaves composition / the panel collapses.
            shellProcess?.let { runCatching { it.destroy() } }
        }
    }

    LaunchedEffect(retryKey) {
        try {
            val pb = java.lang.ProcessBuilder("/system/bin/sh")
            pb.directory(activity.studioProjectDir ?: activity.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            shellProcess = proc
            shellError = null
            activity.studioScope.launch(Dispatchers.IO) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream, Charset.forName("UTF-8")))
                while (proc.isAlive) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) { terminalText = terminalText + line + "\n" }
                }
                withContext(Dispatchers.Main) {
                    if (shellError == null) shellError = "Shell process exited"
                }
            }
        } catch (e: Exception) {
            shellError = "Failed to start shell: ${e.message}"
            Log.e("TerminalPane", "Shell start failed", e)
        }
    }

    LaunchedEffect(shellProcess) {
        if (shellProcess != null) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(terminalText) {
        if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
    }

    // --- Built-in tool commands (not sent to /system/bin/sh) ---
    // Returns true if the command was handled here.
    fun handleBuiltinCommand(cmd: String): Boolean {
        if (cmd.startsWith("tool install ")) {
            val toolName = cmd.substringAfter("tool install ").trim()
            terminalText = terminalText + "$ $cmd\n"
            DownloadCenter.installTool(toolName, activity) { success ->
                terminalText = terminalText + (if (success) "[OK] $toolName installed\n" else "[FAIL] $toolName install failed\n")
            }
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            return true
        }

        // <toolName> --version  or  <toolName> -version
        val versionMatch = Regex("^(java|clang|clangd|clang-tidy|lld|cling)(?: --version| -version)\\s*$").find(cmd)
        if (versionMatch != null) {
            val toolName = versionMatch.groupValues[1]
            terminalText = terminalText + "$ $cmd\n"
            activity.studioScope.launch(Dispatchers.IO) {
                val result = NativeToolRunner.runToolVersion(toolName)
                withContext(Dispatchers.Main) {
                    terminalText = terminalText + result + (if (result.endsWith("\n")) "" else "\n")
                }
            }
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            return true
        }

        return false
    }

    fun sendQuickCommand(cmd: String) {
        if (handleBuiltinCommand(cmd)) return
        terminalText = terminalText + "$ $cmd\n"
        val proc = shellProcess ?: return
        try {
            val os = proc.outputStream
            os.write((cmd + "\n").toByteArray(Charset.forName("UTF-8")))
            os.flush()
        } catch (_: Exception) { }
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    fun sendCommand() {
        val cmd = draftInput.trim()
        if (cmd.isEmpty()) {
            draftInput = ""
            return
        }
        draftInput = ""

        if (handleBuiltinCommand(cmd)) return

        terminalText = terminalText + "$ $cmd\n"
        val proc = shellProcess ?: return
        try {
            val os = proc.outputStream
            os.write((cmd + "\n").toByteArray(Charset.forName("UTF-8")))
            os.flush()
        } catch (_: Exception) { }
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(ide.p.editorBg))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(Color(ide.p.surface))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickCommandChip(label = "pwd", cmd = "pwd", onClick = { sendQuickCommand("pwd") })
            QuickCommandChip(label = "ls", cmd = "ls -la", onClick = { sendQuickCommand("ls -la") })
            QuickCommandChip(label = "whoami", cmd = "whoami", onClick = { sendQuickCommand("whoami") })
            QuickCommandChip(label = "java", cmd = "java --version", onClick = { sendQuickCommand("java --version") })
            QuickCommandChip(label = "gradle", cmd = "gradle --version", onClick = { sendQuickCommand("gradle --version") })
            QuickCommandChip(label = "clang", cmd = "clang --version", onClick = { sendQuickCommand("clang --version") })
            QuickCommandChip(label = "clangd", cmd = "clangd --version", onClick = { sendQuickCommand("clangd --version") })
            QuickCommandChip(label = "cling", cmd = "cling --version", onClick = { sendQuickCommand("cling --version") })
            QuickCommandChip(label = "lld", cmd = "lld --version", onClick = { sendQuickCommand("lld --version") })
            QuickCommandChip(label = "python", cmd = "python --version", onClick = { sendQuickCommand("python --version") })
        }
        IdeDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (shellError != null) {
                val error = shellError!!
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = AppLanguage.string(activity, R.string.shell_error_title),
                        color = ide.error,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = ide.textSoft,
                        fontSize = 12.sp,
                        style = IdeMono.terminal,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clickable { shellError = null; retryKey++ }
                            .background(Color(ide.p.surface2), RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Retry", color = ide.accent, fontSize = 12.sp)
                    }
                }
            } else {
                if (terminalText.isNotEmpty()) {
                    Text(
                        text = terminalText,
                        color = ide.text,
                        fontSize = 12.sp,
                        style = IdeMono.terminal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (shellProcess == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading shell…", color = ide.textSoft, fontSize = 12.sp, style = IdeMono.terminal)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    text = AppLanguage.string(activity, R.string.shell_prompt),
                    color = ide.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                BasicTextField(
                    value = draftInput,
                    onValueChange = { draftInput = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendCommand() }),
                    cursorBrush = SolidColor(ide.accent),
                    textStyle = IdeMono.terminal,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent)
                        .focusRequester(focusRequester),
                )
            }
        }
    }
}

/** Chip button that sends a quick command to the terminal. */
@Composable
private fun QuickCommandChip(
    label: String,
    cmd: String,
    onClick: () -> Unit,
) {
    val ide = LocalIdeColors.current
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(Color(ide.p.surface2), RoundedCornerShape(6.dp))
            .border(0.5.dp, ide.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = IdeMono.path,
            color = ide.text,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun DownloadManagerWindow(
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val task = DownloadCenter.state.value
    // Never write state during composition: defer dismissal to an effect.
    LaunchedEffect(task) {
        if (task == null) onDismiss()
    }
    if (task == null) return

    val context: Context = LocalContext.current
    val ide = LocalIdeColors.current

    val t: DownloadCenter.Task = task
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.width(420.dp).height(IntrinsicSize.Min),
            colors = CardDefaults.cardColors(containerColor = Color(ide.p.card)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = AppLanguage.string(activity, R.string.downloader_title), color = ide.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        if (t.status == DownloadCenter.Status.CONFIRM) DownloadCenter.decline()
                        else if (t.status == DownloadCenter.Status.DOWNLOADING) DownloadCenter.cancel()
                        onDismiss()
                    }) { Text("×", color = ide.textSoft, fontSize = 20.sp) }
                }
                Divider(color = ide.divider, modifier = Modifier.padding(vertical = 12.dp))
                Text(text = AppLanguage.string(activity, R.string.downloader_downloading).replace("%s", t.label), color = ide.info, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = AppLanguage.string(activity, R.string.downloader_download_path), color = ide.textSoft, fontSize = 11.sp)
                Text(text = t.downloadPath.ifBlank { "—" }, color = ide.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = AppLanguage.string(activity, R.string.downloader_file_name), color = ide.textSoft, fontSize = 11.sp)
                Text(text = t.fileName.ifBlank { "—" }, color = ide.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))

                when (t.status) {
                    DownloadCenter.Status.CONFIRM -> {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            val totalMB = if (t.totalBytes > 0) "${(t.totalBytes / (1024 * 1024)).toString()} MB" else "Unknown size"
                            Text(text = AppLanguage.string(activity, R.string.downloader_file_size).replace("%s", totalMB), color = ide.text, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = AppLanguage.string(activity, R.string.downloader_confirm_title), color = ide.textSoft, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = AppLanguage.string(activity, R.string.downloader_confirm_message).replace("%s", t.label.lowercase()), color = ide.error, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { DownloadCenter.approve() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ide.success)) { Text(AppLanguage.string(activity, R.string.yes), color = Color.White, fontWeight = FontWeight.Bold) }
                                Button(onClick = { DownloadCenter.decline(); onDismiss() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ide.error)) { Text(AppLanguage.string(activity, R.string.no), color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                    DownloadCenter.Status.DOWNLOADING -> {
                        val percent = t.percent
                        val downloadedMB = t.downloadedBytes / (1024 * 1024)
                        val totalMB = if (t.totalBytes > 0) t.totalBytes / (1024 * 1024) else 0L
                        val speedMB = t.speedBytesPerSec / (1024 * 1024)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(modifier = Modifier.fillMaxWidth().height(14.dp).background(Color(ide.p.surface2)).clip(RoundedCornerShape(7.dp))) {
                                Box(modifier = Modifier.fillMaxWidth(percent.toFloat() / 100f).fillMaxHeight().background(ide.accent).clip(RoundedCornerShape(7.dp)))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                val progressText = if (totalMB > 0) "${downloadedMB} MB / ${totalMB} MB (${percent}%)" else "${downloadedMB} MB / ? (${percent}%)"
                                Text(text = progressText, color = ide.text, fontSize = 12.sp)
                                if (speedMB > 0) Text(text = AppLanguage.string(activity, R.string.downloader_speed).replace("%s", "${speedMB} MB/s"), color = ide.info, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            if (t.message.isNotBlank()) { Text(text = t.message, color = ide.textSoft, fontSize = 11.sp, modifier = Modifier.fillMaxWidth()) }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { DownloadCenter.cancel() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ide.error)) { Text(AppLanguage.string(activity, R.string.action_cancel), color = Color.White, fontWeight = FontWeight.Bold) }
                        }
                    }
                    DownloadCenter.Status.DONE, DownloadCenter.Status.FAILED, DownloadCenter.Status.CANCELLED -> {
                        val iconText: String
                        val iconColor: Color
                        val title: String
                        when (t.status) {
                            DownloadCenter.Status.DONE -> { iconText = "✓"; iconColor = ide.success; title = "Download Complete" }
                            DownloadCenter.Status.FAILED -> { iconText = "✗"; iconColor = ide.error; title = "Download Failed" }
                            else -> { iconText = "⊘"; iconColor = ide.muted; title = "Download Cancelled" }
                        }
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = iconText, color = iconColor, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = title, color = iconColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            if (t.message.isNotBlank()) { Text(text = t.message, color = ide.muted, fontSize = 13.sp, textAlign = TextAlign.Center) }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { onDismiss() }, modifier = Modifier.width(120.dp)) { Text(AppLanguage.string(activity, R.string.close), color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

private data class StudioMenuItem(val label: String, val action: String = "", val shortcut: String = "", val divider: Boolean = false, val subItems: List<StudioMenuItem> = emptyList())
private data class StudioMenu(val label: String, val items: List<StudioMenuItem>)

@Composable
private fun TopMenuBar(
    activity: Activity,
    projectName: String,
    onOpenDrawer: () -> Unit,
    onRun: () -> Unit,
    onOpenSettings: () -> Unit,
    onAction: (String) -> Unit,
) {
    val menus = remember(activity) {
        val tr = { key: Int -> AppLanguage.string(activity, key) }
        listOf(
            StudioMenu(tr(R.string.menu_file), listOf(
                StudioMenuItem("New", "", subItems = listOf(
                    StudioMenuItem("Project...", "new_project"),
                    StudioMenuItem("File...", "new_file"),
                    StudioMenuItem("Folder...", "new_folder"),
                )),
                StudioMenuItem("Open Project...", "open_project"),
                StudioMenuItem("Recent Projects", "recent_projects"),
                StudioMenuItem("Recent Files", "recent_files"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Save", "save", "Ctrl+S"),
                StudioMenuItem("Save All", "save_all"),
                StudioMenuItem("Close Tab", "close_tab", "Ctrl+W"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Settings...", "settings", "Ctrl+Alt+S"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Project Statistics", "project_stats"),
                StudioMenuItem("Exit", "exit"),
            )),
            StudioMenu(tr(R.string.menu_edit), listOf(
                StudioMenuItem("Undo", "undo", "Ctrl+Z"),
                StudioMenuItem("Redo", "redo", "Ctrl+Shift+Z"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Cut", "cut", "Ctrl+X"),
                StudioMenuItem("Copy", "copy", "Ctrl+C"),
                StudioMenuItem("Paste", "paste", "Ctrl+V"),
                StudioMenuItem("Select All", "select_all", "Ctrl+A"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Find...", "find", "Ctrl+F"),
                StudioMenuItem("Find/Replace...", "find_replace"),
                StudioMenuItem("Find in Files...", "find_in_files"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Close All Tabs", "close_all_tabs"),
            )),
            StudioMenu(tr(R.string.menu_view), listOf(
                StudioMenuItem("Editor", "pane_editor"),
                StudioMenuItem("Build Log", "pane_buildlog"),
                StudioMenuItem("Terminal", "pane_terminal"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Design Preview", "preview_design"),
                StudioMenuItem("Project Files Drawer", "drawer"),
            )),
            StudioMenu(tr(R.string.menu_navigate), listOf(
                StudioMenuItem("Next Tab", "next_tab", "Ctrl+Tab"),
                StudioMenuItem("Previous Tab", "prev_tab", "Ctrl+Shift+Tab"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Go to Line...", "goto_line", "Ctrl+G"),
                StudioMenuItem("Quick Open (Go to File)...", "quick_open", "Ctrl+Shift+N"),
                StudioMenuItem("Search Everywhere...", "command_palette", "Ctrl+Shift+P"),
                StudioMenuItem("Structure / Outline", "structure"),
                StudioMenuItem("TODO / FIXME", "todo"),
            )),
            StudioMenu(tr(R.string.menu_code), listOf(
                StudioMenuItem("Comment / Uncomment", "comment", "Ctrl+/"),
                StudioMenuItem("Reformat Code", "reformat", "Ctrl+Alt+L"),
                StudioMenuItem("Optimize Imports", "optimize", "Ctrl+Alt+O"),
            )),
            StudioMenu(tr(R.string.menu_build), listOf(
                StudioMenuItem("Build APK", "build_apk"),
                StudioMenuItem("Rebuild Project", "rebuild"),
                StudioMenuItem("Clean Project", "clean"),
                StudioMenuItem("Sync Project", "sync_project"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Stop Build", "stop_build"),
            )),
            StudioMenu(tr(R.string.menu_run), listOf(
                StudioMenuItem("Run 'app'", "run", "Shift+F10"),
                StudioMenuItem("Install APK", "install_apk"),
                StudioMenuItem("Stop Build", "stop_build"),
            )),
            StudioMenu(tr(R.string.menu_tools), listOf(
                StudioMenuItem("Terminal", "terminal", "Alt+F12"),
                StudioMenuItem("AI Assistant", "assistant"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Device Manager", "device_manager"),
                StudioMenuItem("SDK Manager", "sdk_manager"),
                StudioMenuItem("Resource Manager", "resource_manager"),
                StudioMenuItem("AGP Upgrade Assistant...", "agp_upgrade"),
                StudioMenuItem("Android SDK Upgrade Assistant", "android_sdk_upgrade"),
                StudioMenuItem("Troubleshoot Device Connections", "troubleshoot_device"),
                StudioMenuItem("App Links Assistant", "app_links"),
                StudioMenuItem("Firebase", "firebase"),
                StudioMenuItem("Layout Inspector", "layout_inspector"),
                StudioMenuItem("Generate Javadoc...", "generate_javadoc"),
                StudioMenuItem("Create Command Line Launcher...", "create_cli_launcher"),
                StudioMenuItem("Services", "services"),
                StudioMenuItem("Markdown", "markdown"),
                StudioMenuItem("XML Actions", "xml_actions"),
                StudioMenuItem("Cling", "cling"),
                StudioMenuItem("Groovy Console", "groovy_console"),
                StudioMenuItem("Kotlin", "kotlin"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Git", "git"),
                StudioMenuItem("File Manager", "file_manager"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Download Manager", "downloads"),
            )),
            StudioMenu(tr(R.string.menu_vcs), listOf(StudioMenuItem("Git...", "git"), StudioMenuItem("File Manager", "file_manager"))),
            StudioMenu(tr(R.string.menu_window), listOf(
                StudioMenuItem("Editor", "pane_editor"),
                StudioMenuItem("Build Log", "pane_buildlog"),
                StudioMenuItem("Terminal", "pane_terminal"),
                StudioMenuItem("Project Files Drawer", "drawer"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Download Manager", "downloads"),
            )),
            StudioMenu(tr(R.string.menu_help), listOf(
                StudioMenuItem("Documentation", "documentation"),
                StudioMenuItem("Keyboard Shortcuts", "keyboard_shortcuts"),
                StudioMenuItem("About MobileStudio", "about"),
                StudioMenuItem("Crash Report", "crash_report"),
            )),
        )
    }
    var openMenu by remember { mutableStateOf<String?>(null) }
    val ide = LocalIdeColors.current

    Column(modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface))) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onOpenDrawer).semantics { contentDescription = "Open project" },
                contentAlignment = Alignment.Center,
            ) {
                IdeIcon(iconRes = R.drawable.ic_ide_menu, color = ide.textSoft, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f).clickable(onClick = onOpenDrawer).padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = projectName,
                        color = ide.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("  ▾", color = ide.muted, fontSize = 10.sp)
                }
                Text(text = AppLanguage.string(activity, R.string.menu_mobile_studio), color = ide.textSoft, fontSize = 10.sp, maxLines = 1)
            }
            Box(
                modifier = Modifier.size(40.dp).background(ide.success.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).clickable(onClick = onRun).semantics { contentDescription = "Run build" },
                contentAlignment = Alignment.Center,
            ) { IdeIcon(iconRes = R.drawable.ic_ide_run, color = ide.success, modifier = Modifier.size(19.dp)) }
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onOpenSettings).semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { GearIcon(Modifier.size(19.dp), tint = ide.textSoft) }
        }
        IdeDivider()
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).background(Color(ide.p.surface)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            menus.forEach { menu ->
                Box {
                    Text(
                        text = menu.label,
                        color = if (openMenu == menu.label) ide.accent else ide.textSoft,
                        fontSize = 12.sp,
                        fontWeight = if (openMenu == menu.label) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { openMenu = if (openMenu == menu.label) null else menu.label }.padding(horizontal = 9.dp, vertical = 8.dp),
                    )
                    DropdownMenu(
                        expanded = openMenu == menu.label,
                        onDismissRequest = { openMenu = null },
                        modifier = Modifier.width(260.dp).background(Color(ide.p.card)),
                    ) {
                        menu.items.forEach { item ->
                            if (item.divider) {
                                Divider(color = ide.divider, modifier = Modifier.padding(vertical = 4.dp))
                            } else if (item.subItems.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.label, color = ide.text, fontSize = 12.sp)
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("›", color = ide.muted, fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        openMenu = null
                                        showSubMenuDialog(activity, item.label, item.subItems, onAction)
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                )
                            } else {
                                DropdownMenuItem(
                                    text = {
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.label, color = ide.text, fontSize = 12.sp)
                                            Spacer(modifier = Modifier.weight(1f))
                                            if (item.shortcut.isNotEmpty()) Text(item.shortcut, color = ide.muted, fontSize = 10.sp)
                                        }
                                    },
                                    onClick = { openMenu = null; onAction(item.action) },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        IdeDivider()
    }
}

@Composable
private fun FileDrawer(
    host: StudioHost,
    mode: String,
    onClose: () -> Unit,
    onPickFile: (File) -> Unit,
    onOpenProject: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchToFiles: () -> Unit,
    onSwitchToProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = host.studioActivity
    val ide = LocalIdeColors.current
    Column(
        modifier = modifier.fillMaxWidth().fillMaxHeight().background(Color(ide.p.surface)).border(1.dp, Color(ide.p.border)),
    ) {
        when (mode) {
            "projects" -> ProjectsView(
                activity = activity,
                onOpenProject = onOpenProject,
                onSwitchToFiles = onSwitchToFiles,
                currentProjectDir = host.studioProjectDir,
                onClose = onClose,
            )
            "files" -> FilesView(
                activity = activity,
                onPickFile = onPickFile,
                onRefresh = onRefresh,
                onSwitchToProjects = onSwitchToProjects,
                onOpenProject = onOpenProject,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ProjectsView(
    activity: MainActivity,
    onOpenProject: () -> Unit,
    onSwitchToFiles: () -> Unit,
    currentProjectDir: File?,
    onClose: () -> Unit,
) {
    val ide = LocalIdeColors.current
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().background(Color(ide.p.surface))) {
        Text(
            text = AppLanguage.string(activity, R.string.tab_project),
            color = ide.muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (currentProjectDir != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface2)).clickable { onSwitchToFiles() }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderIcon(modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(7.dp))
                Text(text = currentProjectDir.name, color = ide.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("▸", color = ide.muted, fontSize = 14.sp)
            }
            Divider(color = ide.divider)
        } else {
            Box(modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface2)).padding(horizontal = 14.dp, vertical = 14.dp)) {
                Text(text = AppLanguage.string(activity, R.string.sidebar_no_project_open), color = ide.textSoft, fontSize = 13.sp)
            }
            Divider(color = ide.divider)
        }

        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val knownProjects = getKnownProjects(activity)
            if (knownProjects.isEmpty()) {
                Text(text = "No projects yet. Tap 'Open Project' to add one.", color = ide.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            } else {
                knownProjects.forEach { projectDir ->
                    val isCurrent = currentProjectDir != null && projectDir.absolutePath == currentProjectDir.absolutePath
                    Row(
                        modifier = Modifier.fillMaxWidth().background(if (isCurrent) Color(ide.p.selectedBg) else Color.Transparent).clickable { activity.studioOpenProject(projectDir); onSwitchToFiles() }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FolderIcon(modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(text = projectDir.name, color = if (isCurrent) ide.accent else ide.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (isCurrent) Text("✓", color = ide.success, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Divider(color = ide.divider, modifier = Modifier.padding(start = 36.dp))
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerButton("Open Project", onOpenProject, primary = true)
            DrawerButton("Close", onClose)
        }
    }
}

@Composable
private fun FilesView(
    activity: MainActivity,
    onPickFile: (File) -> Unit,
    onRefresh: () -> Unit,
    onSwitchToProjects: () -> Unit,
    onOpenProject: () -> Unit,
    onClose: () -> Unit,
) {
    val ide = LocalIdeColors.current
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().background(Color(ide.p.surface))) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface2)).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = ide.textSoft, fontSize = 19.sp, modifier = Modifier.size(40.dp).clickable { onSwitchToProjects() }.semantics { contentDescription = "Back to projects" })
            Text("Files", color = ide.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            Text("×", color = ide.textSoft, fontSize = 16.sp, modifier = Modifier.size(40.dp).clickable { onClose() }.semantics { contentDescription = "Close drawer" })
        }
        Row(modifier = Modifier.fillMaxWidth().clickable { onRefresh() }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderIcon(modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(7.dp))
            Text(text = activity.studioProjectDir?.name ?: "No project", color = ide.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("↻", color = ide.muted, fontSize = 15.sp)
        }
        Divider(color = ide.divider)
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (activity.studioTreeItems.isEmpty()) {
                Text(text = "No files in this project.\nUse Open Project to pick a folder.", color = ide.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            activity.studioTreeItems.forEach { item ->
                key(item.file.absolutePath, item.isExpanded) {
                    FileTreeRow(item = item) {
                        if (item.file.isDirectory) activity.studioToggleTreeFolder(item.file)
                        else onPickFile(item.file)
                    }
                }
            }
        }
        Divider(color = ide.divider)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerButton("Open Project", onOpenProject, primary = true)
            DrawerButton("Close", onClose)
        }
    }
}

@Composable
private fun DrawerButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    val ide = LocalIdeColors.current
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(if (primary) ide.accent else Color(ide.p.surface2), RoundedCornerShape(4.dp))
            .border(if (primary) 0.5.dp else 0.5.dp, if (primary) ide.accent else Color(ide.p.border), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) ide.onAccent else ide.text, fontSize = 12.sp) }
}

@Composable
private fun StudioStatusBar(host: StudioHost, projectName: String, problems: Int, onShowProblems: () -> Unit) {
    val ide = LocalIdeColors.current
    val activity = host.studioActivity
    // Caret position is published by LineNumberEditText into this shared state.
    val caretLine by EditorPaneState.caretLine
    val caretColumn by EditorPaneState.caretColumn
    // Language / encoding / device / git branch come from the host hooks.
    val currentFile = host.studioCurrentFile
    val language = currentFile?.extension?.lowercase().orEmpty()
    val langLabel = when (language) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "xml" -> "XML"
        "gradle" -> "Gradle"
        "py" -> "Python"
        "c", "h" -> "C"
        "cpp", "hpp" -> "C++"
        "json" -> "JSON"
        "md" -> "Markdown"
        else -> if (language.isBlank()) "--" else language
    }
    val encoding = host.studioFileEncoding
    val branch = host.studioGitBranch
    val device = host.studioConnectedDevice
    val problemsAlpha by animateFloatAsState(targetValue = if (problems > 0) 1f else 0f, animationSpec = tween(180), label = "problems-alpha")
    Row(
        modifier = Modifier.fillMaxWidth().height(26.dp).background(Color(ide.p.surface)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Project name (left)
        Text(
            text = projectName,
            color = ide.textSoft,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Git branch chip
        if (!branch.isNullOrBlank()) {
            StatusChip(text = branch, ide = ide, color = ide.info)
            Spacer(modifier = Modifier.width(6.dp))
        }
        // Device chip
        if (!device.isNullOrBlank()) {
            StatusChip(text = device, ide = ide, color = ide.success)
            Spacer(modifier = Modifier.width(6.dp))
        }
        // Caret position
        if (caretLine > 0) {
            Text(text = "Ln $caretLine, Col $caretColumn", color = ide.textSoft, fontSize = 9.sp, maxLines = 1)
            Spacer(modifier = Modifier.width(8.dp))
        }
        // Language
        Text(text = langLabel, color = ide.textSoft, fontSize = 9.sp, maxLines = 1)
        Spacer(modifier = Modifier.width(8.dp))
        // Encoding
        Text(text = encoding, color = ide.muted, fontSize = 9.sp, maxLines = 1)
        Spacer(modifier = Modifier.width(8.dp))
        // Problems badge (animated alpha)
        if (problemsAlpha > 0.01f) {
            Row(
                modifier = Modifier
                    .alpha(problemsAlpha)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ide.error.copy(alpha = 0.16f))
                    .clickable(onClick = onShowProblems)
                    .semantics { contentDescription = "$problems problems" }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("!", color = ide.error, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = problems.toString(), color = ide.error, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}

@Composable
private fun StatusChip(text: String, ide: com.aide.mobilestudio.ui.ide.IdeColors, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = color, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileTreeRow(item: FileItem, onClick: () -> Unit) {
    val ide = LocalIdeColors.current
    val isDir = item.file.isDirectory
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(start = (10 + item.depth * 14).dp, top = 7.dp, bottom = 7.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDir) {
            Text(if (item.isExpanded) "▾" else "▸", color = ide.textSoft, fontSize = 11.sp)
            Spacer(modifier = Modifier.width(4.dp))
            FolderIcon(modifier = Modifier.size(14.dp))
        } else {
            Spacer(modifier = Modifier.width(15.dp))
            Text(
                text = fileBadge(item.file),
                color = fileBadgeColor(item.file),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(ide.p.surface2), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = item.file.name,
            color = if (isDir) ide.text else ide.textSoft,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun fileBadge(file: File): String = when (file.extension.lowercase()) {
    "kt" -> "KT"
    "java" -> "J"
    "xml" -> "XML"
    "gradle" -> "G"
    "md" -> "MD"
    "json" -> "{}"
    else -> "·"
}

private fun fileBadgeColor(file: File): Color = when (file.extension.lowercase()) {
    "kt" -> Color(0xFF7F9EFF)
    "java" -> Color(0xFFE8A33D)
    "xml" -> Color(0xFF6FBF73)
    "gradle" -> Color(0xFF5BC0BE)
    else -> Color(0xFF999999)
}

@Composable
private fun GearIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width; val c = Offset(w / 2f, w / 2f)
        val strokeW = w * 0.16f
        repeat(8) { i ->
            val ang = Math.toRadians((i * 45).toDouble())
            val r1 = w * 0.26f; val r2 = w * 0.46f
            drawLine(color = tint, start = Offset(c.x + (r1 * kotlin.math.cos(ang)).toFloat(), c.y + (r1 * kotlin.math.sin(ang)).toFloat()), end = Offset(c.x + (r2 * kotlin.math.cos(ang)).toFloat(), c.y + (r2 * kotlin.math.sin(ang)).toFloat()), strokeWidth = strokeW)
        }
        drawCircle(color = tint, radius = w * 0.30f, center = c, style = Stroke(width = strokeW))
    }
}

@Composable
private fun LeftStrip(
    context: Context,
    activePane: String,
    onProject: () -> Unit,
    onBuildLog: () -> Unit,
    onTerminal: () -> Unit,
    onIde: () -> Unit,
    onSettings: () -> Unit,
) {
    val ide = LocalIdeColors.current
    val items = listOf(
        StudioRailItem("project", "▤", AppLanguage.string(context, R.string.tab_project), onProject),
        StudioRailItem("build", "▶", AppLanguage.string(context, R.string.tab_build), onBuildLog),
        StudioRailItem("terminal", ">_", AppLanguage.string(context, R.string.tab_shell), onTerminal),
        StudioRailItem("ai", "✦", "AI", onIde),
        StudioRailItem("settings", "⚙", AppLanguage.string(context, R.string.action_settings), onSettings),
    )
    Row(modifier = Modifier.fillMaxHeight()) {
        Column(modifier = Modifier.width(54.dp).fillMaxHeight().background(Color(ide.p.surface)), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(6.dp))
            items.forEach { item -> StudioRailButton(item = item, active = activePane == item.id, onClick = item.onClick) }
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(ide.p.divider)))
    }
}

private data class StudioRailItem(val id: String, val glyph: String, val label: String, val onClick: () -> Unit)

@Composable
private fun StudioRailButton(item: StudioRailItem, active: Boolean, onClick: () -> Unit) {
    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier.width(54.dp).height(54.dp).clickable(onClick = onClick).semantics { contentDescription = item.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.width(22.dp).height(2.dp).background(if (active) ide.accent else Color.Transparent))
        Text(
            text = item.glyph,
            color = when {
                active -> ide.accent
                item.id == "build" -> ide.success
                else -> ide.textSoft
            },
            fontSize = 16.sp,
            fontWeight = if (item.id == "terminal") FontWeight.Bold else FontWeight.Normal,
        )
        Text(text = item.label, color = if (active) ide.text else ide.textSoft, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun FolderIcon(modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: LocalIdeColors.current.warning
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.05f, h * 0.25f); lineTo(w * 0.38f, h * 0.25f); lineTo(w * 0.46f, h * 0.38f)
            lineTo(w * 0.95f, h * 0.38f); lineTo(w * 0.95f, h * 0.85f); lineTo(w * 0.05f, h * 0.85f); close()
        }
        drawPath(p, color.copy(alpha = 0.72f))
        drawPath(p, color, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun SettingsWindowOverlay(
    activity: MainActivity,
    initialPageId: String? = null,
    onClose: () -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    val controller = remember(activity) { SettingsController(activity, onClose = { close() }, initialPageId = initialPageId) }
    MobileStudioTheme {
        Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
            Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Palette.border, RoundedCornerShape(6.dp))) {
                Surface(Modifier.fillMaxSize(), color = Palette.bg) { controller.SettingsScreen() }
            }
        }
    }
}

@Composable
private fun ToolsWindowOverlay(
    host: StudioHost,
    initialTab: String,
    onClose: () -> Unit,
) {
    val close by rememberUpdatedState(onClose)
    val panel = remember(host) { ToolsPanel(host.studioActivity) { close() } }
    DisposableEffect(panel) {
        host.studioToolsPanelForResult = panel
        onDispose {
            if (host.studioToolsPanelForResult === panel) host.studioToolsPanelForResult = null
            panel.dispose()
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
        Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF393B40), RoundedCornerShape(6.dp))) {
            AndroidView(factory = { panel.create(initialTab) }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun showSubMenuDialog(activity: Activity, parentLabel: String, subItems: List<StudioMenuItem>, onAction: (String) -> Unit) {
    val items = subItems.filter { !it.divider && it.label.isNotEmpty() }.map { it.label }
    AlertDialog.Builder(activity)
        .setTitle(parentLabel)
        .setItems(items.toTypedArray()) { _, which ->
            val selected = subItems.filter { !it.divider && it.label.isNotEmpty() }[which]
            onAction(selected.action)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

fun showNewProjectDialog(host: StudioHost) { 
    android.util.Log.d("StudioShell", "showNewProjectDialog called, setting visible=true")
    host.studioNewProjectDialogVisible = true 
}

private data class NewProjectType(val label: String, val description: String)

@Composable
private fun NewProjectDialog(
    activity: MainActivity,
    onDismiss: () -> Unit,
) {
    val host = activity as StudioHost
    val ide = LocalIdeColors.current
    var selectedType by remember { mutableStateOf(0) }  // 0=Android, 1=Java, 2=Kotlin, 3=EXE
    var projectName by remember { mutableStateOf("") }
    var projectLocation by remember { mutableStateOf(host.studioGetLastProject() ?: StorageHelper.projectsRoot(activity).absolutePath) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val projectTypes = remember {
        listOf(
            NewProjectType(AppLanguage.string(activity, R.string.newproject_type_android), AppLanguage.string(activity, R.string.newproject_type_android_desc)),
            NewProjectType(AppLanguage.string(activity, R.string.newproject_type_java), AppLanguage.string(activity, R.string.newproject_type_java_desc)),
            NewProjectType(AppLanguage.string(activity, R.string.newproject_type_kotlin), AppLanguage.string(activity, R.string.newproject_type_kotlin_desc)),
            NewProjectType(AppLanguage.string(activity, R.string.newproject_type_exe), AppLanguage.string(activity, R.string.newproject_type_exe_desc)),
        )
    }
    val previewPath = remember(projectLocation, projectName) {
        val location = projectLocation.trim()
        val name = projectName.trim()
        when {
            location.isEmpty() -> ""
            name.isEmpty() -> File(location).absolutePath
            else -> File(location, name).absolutePath
        }
    }

    fun createProject() {
        val name = projectName.trim()
        val location = projectLocation.trim()
        errorMessage = when {
            name.isEmpty() -> AppLanguage.string(activity, R.string.projects_app_name_empty)
            location.isEmpty() -> AppLanguage.string(activity, R.string.newproject_enter_location)
            else -> null
        }
        if (errorMessage != null) return
        // Validate project name: must be a valid identifier for package/derivePackage
        if (!name.matches(Regex("^[A-Za-z][A-Za-z0-9_ -]*$"))) {
            errorMessage = AppLanguage.string(activity, R.string.projects_create_failed).replace("%s", "Invalid project name")
            return
        }
        val generator = ProjectTemplateGenerator(activity)
        // Every option produces a complete, buildable Android project skeleton
        // (settings.gradle.kts, root/app build files, Gradle wrapper, manifest,
        // MainActivity, resources). The selected type only changes the source
        // language / native layer:
        //   0 Android (Kotlin) -> EMPTY_ACTIVITY
        //   1 Java source      -> EMPTY_JAVA_ACTIVITY
        //   2 Kotlin source    -> EMPTY_ACTIVITY
        //   3 Native (C++/JNI) -> NATIVE_CPP (Android skeleton + native layer)
        val template = when (selectedType) {
            1 -> ProjectTemplateGenerator.Template.EMPTY_JAVA_ACTIVITY
            3 -> ProjectTemplateGenerator.Template.NATIVE_CPP
            else -> ProjectTemplateGenerator.Template.EMPTY_ACTIVITY
        }
        val safePkg = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifEmpty { "myapp" }
        val config = ProjectTemplateGenerator.ProjectConfig(
            appName = name,
            packageName = "com.example.$safePkg",
            template = template,
            projectRoot = File(location),
        )
        try {
            val projectDir = generator.create(config)
            // Ensure the essential Android skeleton is present even if a
            // template path skipped a file — guarantees a basic Android project.
            ensureAndroidSkeleton(projectDir, name, "com.example.$safePkg")
            activity.studioOpenProject(projectDir)
            activity.studioPutLastProject(projectDir.absolutePath)
            onDismiss()
            Toast.makeText(activity, AppLanguage.string(activity, R.string.projects_created).replace("%s", projectDir.toString()), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            errorMessage = AppLanguage.string(activity, R.string.projects_create_failed).replace("%s", e.message ?: "")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss))
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 680.dp).clip(RoundedCornerShape(6.dp)).background(Color(ide.p.card)).border(1.dp, Color(ide.p.border), RoundedCornerShape(6.dp)),
        ) {
            Row(modifier = Modifier.fillMaxWidth().height(52.dp).background(Color(ide.p.surface)), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(text = AppLanguage.string(activity, R.string.newproject_title), color = ide.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = AppLanguage.string(activity, R.string.newproject_subtitle), color = ide.textSoft, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(modifier = Modifier.size(44.dp).clickable(onClick = onDismiss).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) { Text("×", color = ide.textSoft, fontSize = 20.sp) }
            }
            IdeDivider()
            Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(text = AppLanguage.string(activity, R.string.newproject_project_type_label), color = ide.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                projectTypes.chunked(2).forEachIndexed { rowIdx, rowTypes ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTypes.forEachIndexed { colIdx, type ->
                            val idx = rowIdx * 2 + colIdx
                            NewProjectTypeOption(
                                type = type,
                                selected = selectedType == idx,
                                onClick = { selectedType = idx; errorMessage = null },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowTypes.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(text = AppLanguage.string(activity, R.string.projects_app_name), color = ide.textSoft, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                IdeFilterField(
                    value = projectName,
                    onValueChange = { projectName = it; errorMessage = null },
                    placeholder = AppLanguage.string(activity, R.string.newproject_placeholder_app),
                    modifier = Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(12.dp))
                Text(text = AppLanguage.string(activity, R.string.newproject_path_label), color = ide.textSoft, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                IdeFilterField(
                    value = projectLocation,
                    onValueChange = { projectLocation = it; errorMessage = null },
                    placeholder = AppLanguage.string(activity, R.string.newproject_placeholder_folder),
                    modifier = Modifier.fillMaxWidth(),
                    imeAction = ImeAction.Done,
                )
                if (previewPath.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(text = previewPath, color = ide.muted, style = IdeMono.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(text = errorMessage.orEmpty(), color = ide.error, fontSize = 11.sp)
                }
            }
            IdeDivider()
            Row(modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface)).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IdeAction(text = AppLanguage.string(activity, R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                IdeAction(text = AppLanguage.string(activity, R.string.action_create), onClick = { createProject() }, primary = true)
            }
        }
    }
}

@Composable
private fun NewProjectTypeOption(type: NewProjectType, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ide = LocalIdeColors.current
    Column(
        modifier = modifier.heightIn(min = 66.dp).background(if (selected) Color(ide.p.selectedBg) else Color.Transparent).border(if (selected) 1.dp else 0.5.dp, if (selected) Color(ide.p.accent) else Color(ide.p.divider), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(14.dp).border(if (selected) 1.dp else 0.5.dp, if (selected) Color(ide.p.accent) else Color(ide.p.muted), RoundedCornerShape(7.dp)).background(if (selected) Color(ide.p.accent) else Color.Transparent, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(modifier = Modifier.size(6.dp).background(Color(ide.p.onAccent), RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.width(7.dp))
            Text(text = type.label, color = ide.text, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(5.dp))
        Text(text = type.description, color = ide.textSoft, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Guarantees that a newly created project always contains the minimal set of
 * Android project files. Called after the template generator; it only fills
 * in files that are missing, so a complete template is left untouched.
 */
private fun ensureAndroidSkeleton(projectDir: File, appName: String, packageName: String) {
    fun writeIfMissing(rel: String, content: String) {
        val f = File(projectDir, rel)
        if (f.exists()) return
        f.parentFile?.mkdirs()
        f.writeText(content.trimIndent())
    }
    val pkgPath = packageName.replace('.', '/')
    // Gradle build structure
    writeIfMissing("settings.gradle.kts", """
        pluginManagement {
            repositories { google(); mavenCentral(); gradlePluginPortal() }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories { google(); mavenCentral() }
        }
        rootProject.name = "$appName"
        include(":app")
    """)
    writeIfMissing("build.gradle.kts", """
        plugins {
            id("com.android.application") version "8.13.0" apply false
            id("org.jetbrains.kotlin.android") version "2.0.20" apply false
        }
    """)
    writeIfMissing("gradle.properties", """
        android.useAndroidX=true
        android.nonTransitiveRClass=true
        org.gradle.jvmargs=-Xmx1536m
    """)
    writeIfMissing("app/build.gradle.kts", """
        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
        }
        android {
            namespace = "$packageName"
            compileSdk = 34
            defaultConfig {
                applicationId = "$packageName"
                minSdk = 24
                targetSdk = 34
                versionCode = 1
                versionName = "1.0"
            }
        }
        dependencies {
            implementation("androidx.core:core-ktx:1.12.0")
            implementation("androidx.appcompat:appcompat:1.6.1")
            implementation("com.google.android.material:material:1.11.0")
        }
    """)
    // Android manifest
    writeIfMissing("app/src/main/AndroidManifest.xml", """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application
                android:allowBackup="true"
                android:label="$appName"
                android:theme="@style/Theme.$appName">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """)
    // MainActivity (Kotlin)
    writeIfMissing("app/src/main/kotlin/$pkgPath/MainActivity.kt", """
        package $packageName

        import android.os.Bundle
        import androidx.appcompat.app.AppCompatActivity

        class MainActivity : AppCompatActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
            }
        }
    """)
    // Basic resources
    writeIfMissing("app/src/main/res/values/strings.xml", """
        <resources>
            <string name="app_name">$appName</string>
        </resources>
    """)
    writeIfMissing("app/src/main/res/values/themes.xml", """
        <resources>
            <style name="Theme.$appName" parent="Theme.MaterialComponents.DayNight.DarkActionBar" />
        </resources>
    """)
}

private fun createProjectFiles(projectDir: File, projectName: String, projectType: String) {
    if (!projectDir.exists() && !projectDir.mkdirs()) throw IllegalStateException("Cannot create ${projectDir.absolutePath}")
    when (projectType) {
        "Android Application" -> {
            File(projectDir, "app").mkdirs()
            File(projectDir, "app/src/main/java").mkdirs()
            File(projectDir, "app/src/main/res").mkdirs()
            File(projectDir, "app/build.gradle.kts").writeText("""
                plugins { id('com.android.application') id('org.jetbrains.kotlin.android') }
                android { namespace = 'com.example.${projectName.lowercase()}' compileSdk = 34 }
                dependencies { implementation(project(':app')) }
            """.trimIndent())
            File(projectDir, "settings.gradle.kts").writeText("rootProject.name = \"$projectName\"")
            val activityFile = File(projectDir, "app/src/main/java/com/example/${projectName.lowercase()}/MainActivity.kt")
            activityFile.parentFile?.mkdirs()
            activityFile.writeText("""
                package com.example.${projectName.lowercase()}

                import android.os.Bundle
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                    }
                }
            """.trimIndent())
            File(projectDir, "README.md").writeText("# $projectName\n\nAndroid Application created with MobileStudio")
        }
        "Java Application" -> {
            File(projectDir, "src").mkdirs()
            File(projectDir, "src/com/example").mkdirs()
            File(projectDir, "src/com/example/Main.java").writeText("""
                package com.example;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("Hello, $projectName!");
                    }
                }
            """.trimIndent())
            File(projectDir, "README.md").writeText("# $projectName\n\nJava Application created with MobileStudio")
        }
        "Kotlin Application" -> {
            File(projectDir, "src").mkdirs()
            File(projectDir, "src/com/example").mkdirs()
            File(projectDir, "src/com/example/Main.kt").writeText("""
                package com.example

                fun main() {
                    println("Hello, $projectName!")
                }
            """.trimIndent())
            File(projectDir, "README.md").writeText("# $projectName\n\nKotlin Application created with MobileStudio")
        }
        "EXE / Native Binary" -> {
            File(projectDir, "src").mkdirs()
            File(projectDir, "src/main.cpp").writeText("""
                #include <iostream>

                int main() {
                    std::cout << "Hello, $projectName!" << std::endl;
                    return 0;
                }
            """.trimIndent())
            File(projectDir, "README.md").writeText("# $projectName\n\nEXE / Native Binary project created with MobileStudio")
        }
    }
}

fun showNewFileDialog(activity: MainActivity) {
    val host = activity as StudioHost
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val input = EditText(activity).apply {
        hint = "File name (e.g. Main.kt)"
        setTextColor(StudioUi.color(StudioUi.Text))
        setHintTextColor(StudioUi.color(StudioUi.Muted))
        background = StudioUi.inputBg(activity)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(activity)
        .setTitle("New File")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) { Toast.makeText(activity, "Enter a file name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val projectDir = activity.studioProjectDir ?: return@setPositiveButton
            val newFile = File(projectDir, name)
            try {
                newFile.createNewFile()
                host.studioLoadFileTree(projectDir)
                activity.studioOpenFileInEditor(newFile)
                Toast.makeText(activity, "Created: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

fun showNewFolderDialog(activity: MainActivity) {
    val host = activity as StudioHost
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val input = EditText(activity).apply {
        hint = "Folder name"
        setTextColor(StudioUi.color(StudioUi.Text))
        setHintTextColor(StudioUi.color(StudioUi.Muted))
        background = StudioUi.inputBg(activity)
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(activity)
        .setTitle("New Folder")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) { Toast.makeText(activity, "Enter a folder name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val projectDir = activity.studioProjectDir ?: return@setPositiveButton
            val newDir = File(projectDir, name)
            if (newDir.mkdirs()) { host.studioLoadFileTree(projectDir); Toast.makeText(activity, "Created: $name", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(activity, "Failed to create folder", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

fun showRecentProjectsDialog(activity: MainActivity) {
    val recent = activity.studioGetRecentProjects()
    if (recent.isEmpty()) { Toast.makeText(activity, "No recent projects", Toast.LENGTH_SHORT).show(); return }
    val items = recent.map { it.takeLast(30).toString() }.toTypedArray()
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items)
    val fullPaths = recent.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Recent Projects")
        .setAdapter(adapter, { dialog: DialogInterface?, which: Int ->
            val dir = File(fullPaths[which])
            if (dir.exists()) { activity.studioOpenProject(dir); activity.studioPutLastProject(dir.absolutePath) }
            else Toast.makeText(activity, "Project not found: ${dir.name}", Toast.LENGTH_SHORT).show()
        })
        .setNegativeButton("Cancel", null)
        .show()
}

fun showQuickOpenDialog(activity: MainActivity) {
    val projectDir = activity.studioProjectDir ?: return
    val files = mutableListOf<String>()
    projectDir.walkTopDown().forEach { f ->
        if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) files.add(f.absolutePath)
    }
    if (files.isEmpty()) { Toast.makeText(activity, "No files found", Toast.LENGTH_SHORT).show(); return }
    val prefix = projectDir.absolutePath + File.separator
    val items = files.map { it.removePrefix(prefix) }.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Quick Open")
        .setItems(items) { _, which -> activity.studioOpenFileInEditor(File(files[which])) }
        .setNegativeButton("Cancel", null)
        .show()
}

fun showRecentFilesDialog(activity: MainActivity) {
    val recent = activity.studioGetRecentFiles()
    if (recent.isEmpty()) { Toast.makeText(activity, "No recent files", Toast.LENGTH_SHORT).show(); return }
    val items = recent.map { it.takeLast(40).toString() }.toTypedArray()
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items)
    val fullPaths = recent.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Recent Files")
        .setAdapter(adapter, { dialog: DialogInterface?, which: Int ->
            val file = File(fullPaths[which])
            if (file.exists()) activity.studioOpenFileInEditor(file)
            else Toast.makeText(activity, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
        })
        .setNegativeButton("Cancel", null)
        .show()
}

fun showProjectStatsDialog(activity: MainActivity) {
    val projectDir = activity.studioProjectDir ?: return
    val progress = android.app.ProgressDialog(activity).apply {
        setTitle(AppLanguage.string(activity, R.string.stats_title))
        setMessage("Calculating…")
        isIndeterminate = true
        setCancelable(false)
        show()
    }
    activity.studioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        var fileCount = 0
        var lineCount = 0L
        var dirCount = 0
        projectDir.walkTopDown().forEach { f ->
            if (f.isDirectory) dirCount++
            else if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) {
                fileCount++
                try { lineCount += f.readLines().size } catch (_: Exception) {}
            }
        }
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { progress.dismiss() }
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            layout.addView(TextView(activity).apply {
                text = AppLanguage.string(activity, R.string.stats_title)
                setTextColor(StudioUi.color(StudioUi.Text))
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, 0, 0, dp(8))
            })
            layout.addView(TextView(activity).apply {
                text = AppLanguage.string(activity, R.string.stats_summary)
                    .replace("%d", fileCount.toString())
                    .replace("%d", dirCount.toString())
                    .replace("%d", lineCount.toString())
                setTextColor(StudioUi.color(StudioUi.Text))
                textSize = 14f
                setTypeface(Typeface.create("monospace", Typeface.NORMAL))
                setPadding(0, 0, 0, dp(8))
            })
            AlertDialog.Builder(activity)
                .setTitle(AppLanguage.string(activity, R.string.stats_title))
                .setView(layout)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}

fun showFindInFilesDialog(activity: MainActivity) {
    val projectDir = activity.studioProjectDir ?: return
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val queryInput = EditText(activity).apply { hint = "Search text"; setPadding(dp(8), dp(8), dp(8), dp(8)) }
    val layout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16, 16, 16, 16)
        addView(TextView(activity).apply {
            text = AppLanguage.string(activity, R.string.find_in_files_title)
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        addView(queryInput)
    }
    AlertDialog.Builder(activity)
        .setTitle(AppLanguage.string(activity, R.string.find_in_files_title))
        .setView(layout)
        .setPositiveButton("Search") { _, _ ->
            val query = queryInput.text.toString().trim()
            if (query.isBlank()) return@setPositiveButton
            val progress = android.app.ProgressDialog(activity).apply {
                setTitle(AppLanguage.string(activity, R.string.find_in_files_title))
                setMessage("Searching…")
                isIndeterminate = true
                setCancelable(false)
                show()
            }
            activity.studioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val results = mutableListOf<String>()
                projectDir.walkTopDown().forEach { f ->
                    if (results.size >= 200) return@forEach
                    if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) {
                        try {
                            val lines = f.readText(Charsets.UTF_8).lines()
                            for ((idx, l) in lines.withIndex()) {
                                if (l.contains(query, ignoreCase = true)) {
                                    results.add("${f.relativeTo(projectDir).path}:${idx + 1}: ${l.take(60).trim()}")
                                    if (results.size >= 200) break
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { progress.dismiss() }
                    if (results.isEmpty()) Toast.makeText(activity, "No results found", Toast.LENGTH_SHORT).show()
                    else AlertDialog.Builder(activity).setTitle("Results (${results.size})").setMessage(results.joinToString("\n")).setPositiveButton("OK", null).show()
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun getKnownProjects(activity: MainActivity): List<File> {
    val projects = linkedSetOf<File>()
    activity.studioGetRecentProjects().asSequence().map(::File).filter { it.isDirectory }.forEach(projects::add)
    File(activity.filesDir, "workspace").listFiles()?.asSequence()?.filter { it.isDirectory }?.forEach(projects::add)
    return projects.toList()
}


@Composable
private fun LogcatPane(activity: MainActivity) {
    val ide = LocalIdeColors.current
    val lines = remember { mutableStateListOf<Pair<String, String>>() } // level -> raw line
    var paused by remember { mutableStateOf(false) }
    var levelFilter by remember { mutableStateOf("V") }
    var modeLabel by remember { mutableStateOf("") }
    val manager = remember {
        com.aide.mobilestudio.LogcatManager(
            activity,
            onLine = { raw, _ ->
                val level = when {
                    raw.contains(" E/") || raw.startsWith("E/") -> "E"
                    raw.contains(" W/") || raw.startsWith("W/") -> "W"
                    raw.contains(" I/") || raw.startsWith("I/") -> "I"
                    raw.contains(" D/") || raw.startsWith("D/") -> "D"
                    else -> "V"
                }
                lines.add(level to raw)
                if (lines.size > 1000) lines.removeAt(0)
            },
            onError = { err -> lines.add("E" to ("logcat: $err")) },
            onMode = { m -> modeLabel = m },
        )
    }
    DisposableEffect(manager) {
        manager.start()
        onDispose { manager.stop() }
    }
    LaunchedEffect(paused) {
        if (paused) manager.pause() else manager.resume()
    }

    val levels = listOf("V", "D", "I", "W", "E")
    val levelRank = mapOf("V" to 0, "D" to 1, "I" to 2, "W" to 3, "E" to 4)
    val minRank = levelRank[levelFilter] ?: 0
    val visible = lines.filter { (level, _) -> (levelRank[level] ?: 0) >= minRank }
    val scrollState = rememberScrollState()
    LaunchedEffect(visible.size) { scrollState.scrollTo(scrollState.maxValue) }

    Column(modifier = Modifier.fillMaxSize().background(Color(ide.p.editorBg))) {
        // Filter / control bar
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp).background(Color(ide.p.surface)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            levels.forEach { lv ->
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (levelFilter == lv) ide.accent.copy(alpha = 0.22f) else Color(ide.p.surface2))
                        .clickable { levelFilter = lv }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(lv, color = if (levelFilter == lv) ide.accent else ide.textSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (modeLabel.isNotBlank()) {
                Text(modeLabel, color = ide.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Text(if (paused) "▶" else "❚❚", color = if (paused) ide.success else ide.warning, fontSize = 12.sp, modifier = Modifier.clickable { paused = !paused }.padding(6.dp))
            Text("✕", color = ide.textSoft, fontSize = 14.sp, modifier = Modifier.clickable { lines.clear() }.padding(6.dp))
            Text("⧉", color = ide.textSoft, fontSize = 13.sp, modifier = Modifier.clickable {
                val text = visible.joinToString("\n") { it.second }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("logcat", text))
                Toast.makeText(activity, "Logcat copied", Toast.LENGTH_SHORT).show()
            }.padding(6.dp))
        }
        Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(8.dp)) {
            if (visible.isEmpty()) {
                Text("Waiting for logcat output…", color = ide.muted, style = IdeMono.log)
            } else {
                Column {
                    visible.forEach { (level, line) ->
                        val color = when (level) {
                            "E" -> ide.error
                            "W" -> ide.warning
                            "I" -> ide.info
                            else -> ide.textSoft
                        }
                        Text(text = line, color = color, style = IdeMono.log, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Pairs of (display label, action id) shown in the command palette. */
private data class PaletteCommand(val label: String, val action: String)

@Composable
private fun CommandPaletteOverlay(
    activity: MainActivity,
    onRun: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ide = LocalIdeColors.current
    val commands = remember {
        listOf(
            PaletteCommand(AppLanguage.string(activity, R.string.action_new_file), "new_file"),
            PaletteCommand(AppLanguage.string(activity, R.string.newproject_title), "new_project"),
            PaletteCommand(AppLanguage.string(activity, R.string.action_open_project), "open_project"),
            PaletteCommand(AppLanguage.string(activity, R.string.action_save), "save"),
            PaletteCommand(AppLanguage.string(activity, R.string.action_settings), "settings"),
            PaletteCommand(AppLanguage.string(activity, R.string.tab_build), "pane_buildlog"),
            PaletteCommand(AppLanguage.string(activity, R.string.tab_problems), "pane_problems"),
            PaletteCommand(AppLanguage.string(activity, R.string.tab_shell), "pane_terminal"),
            PaletteCommand(AppLanguage.string(activity, R.string.tab_logcat), "pane_logcat"),
            PaletteCommand("Run 'app'", "run"),
            PaletteCommand("Build", "build"),
            PaletteCommand("Stop Build", "stop_build"),
            PaletteCommand("Find", "find"),
            PaletteCommand(AppLanguage.string(activity, R.string.find_in_files_title), "find_in_files"),
            PaletteCommand("Git", "git"),
            PaletteCommand("Device Manager", "device_manager"),
            PaletteCommand("SDK Manager", "sdk_manager"),
            PaletteCommand("File Manager", "file_manager"),
            PaletteCommand("Terminal", "terminal"),
            PaletteCommand("Recent Projects", "recent_projects"),
            PaletteCommand("Go to File… (Quick Open)", "quick_open"),
            PaletteCommand(AppLanguage.string(activity, R.string.stats_title), "project_stats"),
        )
    }
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) commands else commands.filter { it.label.lowercase().contains(q) }
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000)).clickable(enabled = true, onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .heightIn(max = 480.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(ide.p.card))
                .border(1.dp, ide.border, RoundedCornerShape(8.dp))
                .clickable(enabled = false) {},
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(ide.p.surface)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("›", color = ide.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    cursorBrush = SolidColor(ide.accent),
                    textStyle = TextStyle(color = ide.text, fontSize = 14.sp),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Type a command…", color = ide.muted, fontSize = 14.sp)
                        inner()
                    },
                )
            }
            IdeDivider()
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
                if (results.isEmpty()) {
                    Text("No matching commands", color = ide.muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    results.forEach { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onRun(cmd.action) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(cmd.label, color = ide.text, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
